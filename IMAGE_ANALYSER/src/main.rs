//! Test harness entry point (NOT the app runtime):
//!
//!   cargo run --release -- --test-models                 # run the NSFW detectors
//!   cargo run --release -- --test-models --level strict  # strict | moderate | lenient
//!
//! Loads every detector under models/ (model.onnx + preproc.json), runs them over
//! the labelled ground-truth set (timed), prints a per-(model,image) table +
//! accuracy/timing summary, writes test/results.json, and EXITS NON-ZERO on any
//! load/open/inference failure.

mod config {
    //! Central configuration: directory paths and tunables.
    //! Paths mirror exactly what you specified in the task.
    
    use std::path::PathBuf;
    
    /// Directory holding the test images.
    pub const TEST_IMG_DIR: &str = "test/static/testImages";
    
    /// Ground-truth labels. One JSON object mapping each image to a single boolean:
    ///   "path": true|false   (true = NSFW)
    pub const GROUND_TRUTH_FILE: &str = "test/are-images-ground-truth.json";
    
    /// Directory the Python converter writes ONNX models + preproc sidecars into.
    /// Layout: models/<detector_name>/model.onnx  +  models/<detector_name>/preproc.json
    pub const MODELS_DIR: &str = "models";
    
    /// Where the run writes its results (one JSON file with a separate map per detector).
    pub const RESULTS_FILE: &str = "test/results.json";
    
    /// Central thresholds file: per-model, per-level cutoffs. Replaces the per-model
    /// `threshold` that used to live inside each preproc.json.
    pub const THRESHOLDS_FILE: &str = "models/thresholds.json";
    
    /// Image extensions we treat as test images.
    pub const IMAGE_EXTS: &[&str] = &["jpg", "jpeg", "png", "webp", "bmp", "gif"];
    
    pub fn test_img_dir() -> PathBuf { PathBuf::from(TEST_IMG_DIR) }
    pub fn ground_truth_file() -> PathBuf { PathBuf::from(GROUND_TRUTH_FILE) }
    pub fn models_dir() -> PathBuf { PathBuf::from(MODELS_DIR) }
    pub fn results_file() -> PathBuf { PathBuf::from(RESULTS_FILE) }
    pub fn thresholds_file() -> PathBuf { PathBuf::from(THRESHOLDS_FILE) }
}

mod calibrate {
    //! Turn a raw model score into a calibrated NSFW confidence in [0,1], with the
    //! configured threshold pinned to exactly 0.5.
    //!
    //!   raw == threshold -> 0.5   (perfectly borderline)
    //!   raw == 0         -> 0.0   (definitely NOT nsfw)
    //!   raw == 1         -> 1.0   (definitely nsfw)
    //!
    //! The curve is STEEPEST right at the threshold and flattens as you move away,
    //! so scores near the boundary get the most resolution and far-away scores all
    //! collapse toward 0 or 1. Shape: a logistic squashed onto each side of the
    //! threshold, rescaled so the endpoints land exactly on 0.0 / 0.5 / 1.0.

    /// Curve steepness. Higher = sharper transition at the threshold (scores saturate
    /// toward 0/1 faster). 3.0 is a gentle ramp near the boundary. Tune to taste.
    pub const K: f32 = 1.8;

    fn sigmoid(z: f32) -> f32 {
        1.0 / (1.0 + (-z).exp())
    }

    /// Maps x in [0,1] -> [0,1], steepest at x=0, flat near x=1.
    fn half(x: f32, k: f32) -> f32 {
        let s0 = 0.5_f32;        // sigmoid(0)
        let sk = sigmoid(k);     // sigmoid(k)
        (sigmoid(k * x) - s0) / (sk - s0)
    }

    /// Calibrated confidence in [0,1]. `threshold` is the cutoff that should read 0.5.
    pub fn calibrate(raw: f32, threshold: f32) -> f32 {
        calibrate_k(raw, threshold, K)
    }

    pub fn calibrate_k(raw: f32, threshold: f32, k: f32) -> f32 {
        let raw = raw.clamp(0.0, 1.0);
        let t = threshold.clamp(1e-6, 1.0 - 1e-6);
        let c = if raw >= t {
            0.5 + 0.5 * half((raw - t) / (1.0 - t), k)
        } else {
            0.5 - 0.5 * half((t - raw) / t, k)
        };
        c.clamp(0.0, 1.0)
    }
}

mod detector {
    //! A single detector = one model.onnx + its preproc.json sidecar.
    //! `infer()` returns a uniform DetectorResult regardless of the underlying model.
    
    use crate::image_proc::preprocess;
    use crate::sidecar::{Preproc, Task};
    use anyhow::{Context, Result};
    use image::DynamicImage;
    use ort::session::{builder::GraphOptimizationLevel, Session};
    use ort::value::Tensor;
    use serde::Serialize;
    use std::collections::BTreeMap;
    use std::path::Path;
    
    /// One detector's verdict on one image. Carries both the raw score and the
    /// thresholded boolean, plus per-class scores for inspection.
    #[derive(Serialize, Clone, Debug)]
    pub struct DetectorResult {
        /// NSFW probability in [0,1] (or detector-native score).
        pub score: f32,
        /// score >= threshold.
        pub is_nsfw: bool,
        /// argmax label (classification) or "nsfw"/"sfw" (score).
        pub label: String,
        /// label -> probability, for debugging which class fired.
        pub class_scores: BTreeMap<String, f32>,
    }
    
    pub struct Detector {
        pub name: String,
        session: Session,
        pre: Preproc,
        threshold: f32,
    }
    
    impl Detector {
        pub fn load(model_path: &Path, sidecar_path: &Path, threshold: f32) -> Result<Self> {
            let sidecar_txt = std::fs::read_to_string(sidecar_path)
                .with_context(|| format!("reading sidecar {}", sidecar_path.display()))?;
            let pre: Preproc = serde_json::from_str(&sidecar_txt)
                .with_context(|| format!("parsing sidecar {}", sidecar_path.display()))?;
    
            let session = Session::builder()?
                .with_optimization_level(GraphOptimizationLevel::Level3)?
                .with_intra_threads(1)?
                .commit_from_file(model_path)
                .with_context(|| format!("loading model {}", model_path.display()))?;
    
            Ok(Self { name: pre.name.clone(), session, pre, threshold })
        }
    
        /// The active NSFW cutoff (from thresholds.json for the selected level).
        pub fn threshold(&self) -> f32 {
            self.threshold
        }
    
        pub fn infer(&mut self, img: &DynamicImage) -> Result<DetectorResult> {
            let (data, shape) = preprocess(img, &self.pre);
            let logits = self.run(data, shape)?;
    
            match self.pre.task {
                Task::Classification | Task::Score => Ok(self.post_classify(&logits)),
            }
        }
    
        /// ---- ort API touch-point -------------------------------------------------
        /// This is the ONE place tied to ort's exact version. On ort 2.0.0-rc.10,
        /// `try_extract_tensor::<f32>()` yields `(Shape, &[f32])`. If you bump ort and
        /// it stops compiling here, that call is what changed:
        ///   - some versions: `.try_extract_tensor::<f32>()? -> (Shape, &[f32])`
        ///   - others:        `.try_extract_array::<f32>()?  -> ndarray::ArrayViewD<f32>`
        /// Adjust these ~3 lines and nothing else in the codebase needs to change.
        fn run(&mut self, data: Vec<f32>, shape: Vec<i64>) -> Result<Vec<f32>> {
            let tensor = Tensor::from_array((shape, data))?;
            let outputs = self.session.run(ort::inputs![tensor])?;
            let (_out_shape, slice) = outputs[0].try_extract_tensor::<f32>()?;
            Ok(slice.to_vec())
        }
        // --------------------------------------------------------------------------
    
        fn post_classify(&self, logits: &[f32]) -> DetectorResult {
            let probs = if self.pre.apply_softmax { softmax(logits) } else { logits.to_vec() };
            let n = probs.len();
            let nsfw_idx = self.pre.nsfw_indices(n);
    
            let score = if n == 1 {
                probs[0]
            } else {
                nsfw_idx.iter().filter_map(|i| probs.get(*i)).sum::<f32>()
            };
    
            // argmax label
            let (amax, _) = probs
                .iter()
                .enumerate()
                .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
                .unwrap_or((0, &0.0f32));
            let label = self
                .pre
                .id2label
                .get(&amax.to_string())
                .cloned()
                .unwrap_or_else(|| if score >= self.threshold { "nsfw".into() } else { "sfw".into() });
    
            let mut class_scores = BTreeMap::new();
            for (i, p) in probs.iter().enumerate() {
                let key = self.pre.id2label.get(&i.to_string()).cloned().unwrap_or_else(|| i.to_string());
                class_scores.insert(key, *p);
            }
    
            DetectorResult { score, is_nsfw: score >= self.threshold, label, class_scores }
        }
    }
    
    fn softmax(x: &[f32]) -> Vec<f32> {
        if x.is_empty() {
            return vec![];
        }
        let m = x.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let exps: Vec<f32> = x.iter().map(|v| (v - m).exp()).collect();
        let sum: f32 = exps.iter().sum();
        if sum == 0.0 {
            return exps;
        }
        exps.iter().map(|v| v / sum).collect()
    }
}
mod detectors {
    //! Detector registry. Fully sidecar-driven: we scan models/<name>/ for a
    //! model.onnx + preproc.json pair and load every one we find. Adding or removing
    //! a detector is just adding/removing a folder under models/ — no code change.
    //!
    //! The list below documents the detector this project ships and its licence,
    //! so you can see at a glance that it is safe to SHIP in a closed-source Android app.
    //! The harness still loads whatever ONNX files exist under models/.
    
    use crate::detector::Detector;
    use crate::thresholds::Thresholds;
    use anyhow::Result;
    use std::path::Path;
    
    /// A model directory that exists but could not be loaded.
    pub struct LoadFailure {
        pub name: String,
        pub error: String,
    }
    
    /// Reference catalogue (name, licence, ok-to-ship-closed-source, note).
    #[allow(dead_code)]
    pub const CATALOGUE: &[(&str, &str, bool, &str)] = &[
        ("adamcodd-vit-nsfw", "Apache-2.0", true, "ViT binary sfw/nsfw. The model this project ships."),
    ];
    
    /// Load every detector under models/ that has both model.onnx and preproc.json.
    /// Each detector's cutoff comes from thresholds.json for the chosen `level`.
    /// Returns (loaded detectors, failures) — the caller decides whether failures are fatal.
pub fn load_all(
        models_dir: &Path,
        thresholds: &Thresholds,
        level: &str,
        quiet: bool,
    ) -> Result<(Vec<Detector>, Vec<LoadFailure>)> {
        let mut detectors = Vec::new();
        let mut failures = Vec::new();
        if !models_dir.exists() {
            return Ok((detectors, failures));
        }
        let mut entries: Vec<_> = std::fs::read_dir(models_dir)?
            .filter_map(|e| e.ok())
            .filter(|e| e.path().is_dir())
            .collect();
        entries.sort_by_key(|e| e.path());
    
        for entry in entries {
            let dir = entry.path();
            let model = dir.join("model.onnx");
            let sidecar = dir.join("preproc.json");
            if !(model.exists() && sidecar.exists()) {
                continue; // not a detector dir; ignore quietly
            }
            let name = dir
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("?")
                .to_string();
            let threshold = thresholds.get(&name, level);
            match Detector::load(&model, &sidecar, threshold) {
                Ok(d) => {
                    if !quiet {
                        println!("  loaded detector: {} (threshold {:.2})", d.name, threshold);
                    }
                    detectors.push(d);
                }
                Err(e) => {
                    if !quiet {
                        println!("  FAILED to load {}: {e:#}", dir.display());
                    }
                    failures.push(LoadFailure { name, error: format!("{e:#}") });
                }
            }
        }
        Ok((detectors, failures))
    }
}
mod eval {
    //! Ground-truth loading, the per-image result record, the results.json writer,
    //! the model-test console table, and the accuracy/timing summary.
    //!
    //! Ground-truth format: one JSON object mapping each image to a single boolean:
    //!   { "path": true|false }   (true = NSFW)
    
    use serde::Serialize;
    use anyhow::{Context, Result};
    use std::collections::BTreeMap;
    use std::path::Path;
    
    /// path-or-url -> is_nsfw
    pub type GroundTruth = BTreeMap<String, bool>;
    
    /// Load the ground truth: a flat `{ "path": true|false }` map (true = NSFW).
    pub fn load_ground_truth(path: &Path) -> Result<GroundTruth> {
        let txt = std::fs::read_to_string(path)
            .with_context(|| format!("reading ground truth {}", path.display()))?;
        serde_json::from_str(&txt).with_context(|| {
            format!(
                "parsing ground truth {} (expected {{\"path\": true|false}})",
                path.display()
            )
        })
    }
    
    /// round helpers keep the JSON readable instead of 0.048417117
    pub fn r4(x: f32) -> f32 { (x * 1e4).round() / 1e4 }
    pub fn r3(x: f64) -> f64 { (x * 1e3).round() / 1e3 }
    
    /// One detector's verdict on one image (what gets written per entry).
    /// `is_nsfw` = the detector's thresholded decision.
    #[derive(Serialize, Clone, Debug)]
    pub struct OutImage {
        pub score: f32,
        pub threshold: f32,
        pub calibrated: f32, // 0..1 confidence, threshold pinned to 0.5
        pub is_nsfw: bool,
        pub expected: bool,
        pub correct: bool,
        pub label: String,
        pub ms: f64,
        pub class_scores: BTreeMap<String, f32>,
    }
    
    /// detector -> { image -> result }
    pub type Results = BTreeMap<String, BTreeMap<String, OutImage>>;
    
    #[derive(Serialize, Default, Clone)]
    pub struct Summary {
        pub total: usize,
        pub correct: usize,
        pub accuracy: f32,
        pub true_pos: usize,
        pub false_pos: usize,
        pub true_neg: usize,
        pub false_neg: usize,
        pub avg_ms: f64,
        pub total_ms: f64,
    }
    
    #[derive(Serialize)]
    pub struct ResultsFile {
        pub generated_by: &'static str,
        pub level: String,
        pub summary: BTreeMap<String, Summary>,
        pub results: Results,
    }
    
    /// Build the per-detector accuracy + timing summary from the results.
    pub fn summarize(results: &Results) -> BTreeMap<String, Summary> {
        let mut out = BTreeMap::new();
        for (name, imgs) in results {
            let mut s = Summary::default();
            let mut ms_sum = 0.0f64;
            for r in imgs.values() {
                s.total += 1;
                match (r.expected, r.is_nsfw) {
                    (true, true) => s.true_pos += 1,
                    (false, false) => s.true_neg += 1,
                    (false, true) => s.false_pos += 1,
                    (true, false) => s.false_neg += 1,
                }
                if r.correct {
                    s.correct += 1;
                }
                ms_sum += r.ms;
            }
            s.accuracy = if s.total > 0 { r4(s.correct as f32 / s.total as f32) } else { 0.0 };
            s.total_ms = r3(ms_sum);
            s.avg_ms = if s.total > 0 { r3(ms_sum / s.total as f64) } else { 0.0 };
            out.insert(name.clone(), s);
        }
        out
    }
    
    pub fn write_results(path: &Path, file: &ResultsFile) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_string_pretty(file)?)?;
        Ok(())
    }
    
    fn word(is_nsfw: bool) -> &'static str {
        if is_nsfw { "nsfw" } else { "fine" }
    }
    
    fn base(path: &str) -> &str {
        path.rsplit('/').next().unwrap_or(path)
    }
    
    /// The model-test table: one row per (model, image). Verdict is nsfw/fine.
    pub fn print_test_table(results: &Results, level: &str) {
        println!("\n================================ model test (level: {level}) ================================");
        println!(
            "{:<24} {:<26} {:<7} {:>7} {:>7} {:>6} {:<7} {:>5} {:>8}",
            "model", "image", "expect", "score", "thresh", "conf", "verdict", "ok", "ms"
        );
        for (name, imgs) in results {
            for (path, r) in imgs {
                println!(
                    "{:<24} {:<26} {:<7} {:>7.3} {:>7.2} {:>6.3} {:<7} {:>5} {:>8.1}",
                    name,
                    base(path),
                    word(r.expected),
                    r.score,
                    r.threshold,
                    r.calibrated,
                    word(r.is_nsfw),
                    if r.correct { "ok" } else { "FAIL" },
                    r.ms,
                );
            }
        }
        println!("====================================================================================================");
    }
    
    pub fn print_summary(summary: &BTreeMap<String, Summary>) {
        println!("\n================ accuracy vs ground truth ================");
        println!(
            "{:<24} {:>6} {:>5} {:>4} {:>4} {:>4} {:>4} {:>9}",
            "detector", "acc", "n", "TP", "FP", "TN", "FN", "avg_ms"
        );
        for (name, a) in summary {
            println!(
                "{:<24} {:>5.1}% {:>5} {:>4} {:>4} {:>4} {:>4} {:>9.1}",
                name,
                a.accuracy * 100.0,
                a.total,
                a.true_pos,
                a.false_pos,
                a.true_neg,
                a.false_neg,
                a.avg_ms
            );
        }
        println!("==========================================================");
    }
}
mod image_proc {
    //! Image -> input tensor, driven entirely by the per-model Preproc sidecar.
    //! Returns a flat Vec<f32> plus its shape (as i64 dims for ort).
    
    use crate::sidecar::{ChannelOrder, Layout, Preproc, ResizeMode};
    use anyhow::Result;
    use image::{imageops::FilterType, DynamicImage, GenericImageView};
    use std::path::Path;
    
    pub fn load_image(path: &Path) -> Result<DynamicImage> {
        Ok(image::open(path)?)
    }
    
    /// Build the model input tensor for `img` according to `p`.
    /// Returns (flat_data, shape) where shape is [N, C, H, W] or [N, H, W, C].
    pub fn preprocess(img: &DynamicImage, p: &Preproc) -> (Vec<f32>, Vec<i64>) {
        let (out_h, out_w) = p.final_hw();
    
        // ---- 1. resize (and optional center crop) ----
        let prepared = match p.resize {
            ResizeMode::Stretch => img.resize_exact(out_w, out_h, FilterType::Triangle),
            ResizeMode::ShortestThenCenterCrop => {
                let target = p.input_size[0].min(p.input_size[1]) as f32;
                let (ow, oh) = img.dimensions();
                let scale = target / (ow.min(oh) as f32);
                let nw = ((ow as f32) * scale).round().max(out_w as f32) as u32;
                let nh = ((oh as f32) * scale).round().max(out_h as f32) as u32;
                let resized = img.resize_exact(nw, nh, FilterType::Triangle);
                // center crop to (out_w, out_h)
                let x = (nw.saturating_sub(out_w)) / 2;
                let y = (nh.saturating_sub(out_h)) / 2;
                DynamicImage::ImageRgba8(
                    image::imageops::crop_imm(&resized, x, y, out_w, out_h).to_image(),
                )
            }
        };
    
        let rgb = prepared.to_rgb8(); // RGB8, [out_h x out_w x 3]
    
        // ---- 2. normalize + lay out ----
        let (h, w) = (out_h as usize, out_w as usize);
        let mut data = vec![0.0f32; h * w * 3];
    
        // channel index order in which we WRITE values
        let chan_for_pos = |pos: usize| -> usize {
            // pos 0,1,2 in output channel order -> source RGB index
            match p.channel_order {
                ChannelOrder::Rgb => pos,        // 0->R,1->G,2->B
                ChannelOrder::Bgr => 2 - pos,    // 0->B,1->G,2->R
            }
        };
    
        for y in 0..h {
            for x in 0..w {
                let px = rgb.get_pixel(x as u32, y as u32).0; // [R,G,B]
                for c in 0..3usize {
                    let src = chan_for_pos(c);
                    let raw = px[src] as f32;
                    let v = (raw * p.rescale - p.mean[c]) / p.std[c];
                    let idx = match p.layout {
                        Layout::Nchw => c * h * w + y * w + x,
                        Layout::Nhwc => (y * w + x) * 3 + c,
                    };
                    data[idx] = v;
                }
            }
        }
    
        let shape = match p.layout {
            Layout::Nchw => vec![1i64, 3, h as i64, w as i64],
            Layout::Nhwc => vec![1i64, h as i64, w as i64, 3],
        };
    
        (data, shape)
    }
}
mod setup {
    //! One-time setup checks, safe to run repeatedly (idempotent).
    //! The shell script (setup.sh) is the main entry point, but this also runs on
    //! every `cargo run` so a bare build still works:
    //!   * create the directories that must exist,
    //!   * if the ground-truth file is missing, generate it from the images present
    //!     (filename heuristic: "*not-allowed*" => true, otherwise false),
    //!   * report whether models / images / ground truth are ready.
    
    use crate::config;
    use anyhow::Result;
    use std::collections::BTreeMap;
    use std::path::Path;
    
    pub struct SetupReport {
        pub models_ready: bool,
        pub images_ready: bool,
    }
    
    pub fn ensure_setup() -> Result<SetupReport> {
        println!("== setup checks ==");
    
        // 1. directories (mkdir -p style; safe to repeat)
        for d in [config::TEST_IMG_DIR, config::MODELS_DIR] {
            if !Path::new(d).exists() {
                std::fs::create_dir_all(d)?;
                println!("  created missing dir: {d}");
            }
        }
        if let Some(parent) = config::ground_truth_file().parent() {
            std::fs::create_dir_all(parent)?;
        }
    
        // 2. images present?
        let images = list_images(&config::test_img_dir());
        let images_ready = !images.is_empty();
        if images_ready {
            println!("  found {} test image(s) in {}", images.len(), config::TEST_IMG_DIR);
        } else {
            println!("  no test images yet in {}", config::TEST_IMG_DIR);
        }
    
        // 3. ground truth: generate from images if missing
        let gt = config::ground_truth_file();
        if gt.exists() {
            println!("  ground-truth file present: {}", gt.display());
        } else if images_ready {
            let map = ground_truth_from_images(&images);
            std::fs::write(&gt, serde_json::to_string_pretty(&map)?)?;
            println!("  generated ground truth from images -> {}", gt.display());
            println!("  (heuristic labels from filenames — fix any that are wrong)");
        } else {
            println!("  no ground-truth file and no images to derive one from");
        }
    
        // 4. models converted? (detect only; conversion needs the Python env)
        let model_names = list_model_names(&config::models_dir());
        let num_models = model_names.len();
        let models_ready = num_models > 0;
        if models_ready {
            println!("  found {num_models} model(s) under {}/", config::MODELS_DIR);
        } else {
            println!("  no ONNX models under {}/  -> run ./setup.sh (or python scripts/convert_models.py)", config::MODELS_DIR);
        }
    
        // 5. thresholds.json: generate a default if missing (never overwrite yours)
        let tf = config::thresholds_file();
        if tf.exists() {
            println!("  thresholds file present: {}", tf.display());
        } else if models_ready {
            write_default_thresholds(&tf, &model_names)?;
            println!("  generated default thresholds -> {} (edit to taste)", tf.display());
        }
    
        Ok(SetupReport { models_ready, images_ready })
    }
    
    /// Model directory names that contain a model.onnx + preproc.json pair, sorted.
    fn list_model_names(models_dir: &Path) -> Vec<String> {
        let mut names = Vec::new();
        if let Ok(rd) = std::fs::read_dir(models_dir) {
            for e in rd.filter_map(|e| e.ok()) {
                let p = e.path();
                if p.is_dir() && p.join("model.onnx").exists() && p.join("preproc.json").exists() {
                    if let Some(n) = p.file_name().and_then(|n| n.to_str()) {
                        names.push(n.to_string());
                    }
                }
            }
        }
        names.sort();
        names
    }
    
    /// Write a starter thresholds.json: every model gets strict/moderate/lenient.
    fn write_default_thresholds(path: &Path, models: &[String]) -> Result<()> {
        let mut model_map = serde_json::Map::new();
        for name in models {
            model_map.insert(
                name.clone(),
                serde_json::json!({ "strict": 0.30, "moderate": 0.50, "lenient": 0.70 }),
            );
        }
        let doc = serde_json::json!({
            "default_level": "moderate",
            "models": serde_json::Value::Object(model_map),
        });
        std::fs::write(path, serde_json::to_string_pretty(&doc)?)?;
        Ok(())
    }
    fn list_images(dir: &Path) -> Vec<String> {
        let mut out = Vec::new();
        if !dir.exists() {
            return out;
        }
        for e in walkdir::WalkDir::new(dir).max_depth(1).into_iter().filter_map(|e| e.ok()) {
            if !e.file_type().is_file() {
                continue;
            }
            let is_img = e
                .path()
                .extension()
                .and_then(|x| x.to_str())
                .map(|ext| config::IMAGE_EXTS.contains(&ext.to_lowercase().as_str()))
                .unwrap_or(false);
            if is_img {
                // store as "<TEST_IMG_DIR>/<filename>" for a stable, portable key
                if let Some(name) = e.path().file_name().and_then(|n| n.to_str()) {
                    out.push(format!("{}/{}", config::TEST_IMG_DIR, name));
                }
            }
        }
        out.sort();
        out
    }
    
    /// Heuristic NSFW labels from the filename, written as a flat `{ "path": bool }` map.
    ///   nsfw = filename mentions "not-allowed"/"nsfw"
    /// These are only a starting point — review and fix any that are wrong.
    fn ground_truth_from_images(images: &[String]) -> BTreeMap<String, bool> {
        let mut map = BTreeMap::new();
        for path in images {
            let lower = path.to_lowercase();
            let is_nsfw = lower.contains("not-allowed")
                || lower.contains("not_allowed")
                || lower.contains("notallowed")
                || lower.contains("nsfw");
            map.insert(path.clone(), is_nsfw);
        }
        map
    }
}
mod sidecar {
    //! The "preproc sidecar": a small JSON file the Python converter writes next to
    //! every model.onnx. It fully describes how to turn an image into the model's
    //! input tensor and how to read the model's output.
    //!
    //! The point of this design: preprocessing constants (input size, mean/std,
    //! channel order, label maps) differ per model and are the easiest thing to get
    //! wrong. Instead of hardcoding them in Rust, the converter reads them straight
    //! from each model's own config and emits them here. Rust just executes the spec.
    //!
    //! Adding a new detector therefore requires NO Rust changes: drop
    //! models/<name>/model.onnx + models/<name>/preproc.json and re-run.
    
    use serde::Deserialize;
    use std::collections::BTreeMap;
    
    #[derive(Deserialize, Clone, Debug, PartialEq)]
    #[serde(rename_all = "snake_case")]
    pub enum Task {
        /// Multi-logit classifier; apply softmax, NSFW = sum of nsfw_label_indices.
        Classification,
        /// Single probability output (or 2-logit) giving one NSFW score.
        Score,
    }
    
    #[derive(Deserialize, Clone, Debug, PartialEq, Default)]
    #[serde(rename_all = "snake_case")]
    pub enum ResizeMode {
        /// Resize directly to input_size, ignoring aspect ratio.
        #[default]
        Stretch,
        /// Resize so the shortest side == input_size, then center-crop to crop_size.
        ShortestThenCenterCrop,
    }
    
    #[derive(Deserialize, Clone, Debug, PartialEq, Default)]
    #[serde(rename_all = "snake_case")]
    pub enum ChannelOrder {
        #[default]
        Rgb,
        Bgr,
    }
    
    #[derive(Deserialize, Clone, Debug, PartialEq, Default)]
    #[serde(rename_all = "snake_case")]
    pub enum Layout {
        /// [N, C, H, W] — standard for PyTorch/ONNX exports (HF vision models).
        #[default]
        Nchw,
        /// [N, H, W, C] — common for TensorFlow/Keras conversions.
        Nhwc,
    }
    
    fn d_rescale() -> f32 { 1.0 / 255.0 }
    fn d_mean() -> [f32; 3] { [0.5, 0.5, 0.5] }
    fn d_std() -> [f32; 3] { [0.5, 0.5, 0.5] }
    fn d_true() -> bool { true }
    
    #[derive(Deserialize, Clone, Debug)]
    pub struct Preproc {
        /// Human-readable detector id (also the models/<name>/ folder name).
        pub name: String,
    
        pub task: Task,
    
        /// [height, width] of the model input (for stretch mode this is the final size;
        /// for shortest_then_center_crop this is the shortest-side resize target).
        pub input_size: [u32; 2],
    
        #[serde(default)]
        pub resize: ResizeMode,
    
        /// Final crop [h, w], used only for shortest_then_center_crop.
        #[serde(default)]
        pub crop_size: Option<[u32; 2]>,
    
        #[serde(default)]
        pub channel_order: ChannelOrder,
    
        #[serde(default)]
        pub layout: Layout,
    
        /// Multiply each 0-255 pixel by this. 1/255 maps to [0,1]; use 1.0 to keep 0-255.
        #[serde(default = "d_rescale")]
        pub rescale: f32,
    
        /// Per-channel mean (in channel_order), subtracted after rescale.
        #[serde(default = "d_mean")]
        pub mean: [f32; 3],
    
        /// Per-channel std (in channel_order), divided after mean subtraction.
        #[serde(default = "d_std")]
        pub std: [f32; 3],
    
        /// Apply softmax to the logits before reading scores.
        #[serde(default = "d_true")]
        pub apply_softmax: bool,
    
        /// index -> label, copied from the model's own config.json (id2label).
        #[serde(default)]
        pub id2label: BTreeMap<String, String>,
    
        /// Which output indices count as "NSFW" for the binary decision.
        /// For multi-class models this is e.g. {hentai, porn, sexy, enticing}.
        /// If empty, Rust falls back to "the last index" (works for 2-class sfw/nsfw).
        #[serde(default)]
        pub nsfw_label_indices: Vec<usize>,
    }
    
    impl Preproc {
        /// Final tensor spatial dims (h, w) after any resize/crop.
        pub fn final_hw(&self) -> (u32, u32) {
            match (self.resize.clone(), self.crop_size) {
                (ResizeMode::ShortestThenCenterCrop, Some([h, w])) => (h, w),
                _ => (self.input_size[0], self.input_size[1]),
            }
        }
    
        /// Indices that count as NSFW, with a sensible fallback.
        pub fn nsfw_indices(&self, num_classes: usize) -> Vec<usize> {
            if !self.nsfw_label_indices.is_empty() {
                self.nsfw_label_indices.clone()
            } else if num_classes >= 2 {
                vec![num_classes - 1] // assume final logit is "nsfw" for binary models
            } else {
                vec![0] // single-output score model
            }
        }
    }
}
mod thresholds {
    //! Central thresholds, loaded from models/thresholds.json.
    //!
    //! One file maps each model to several *levels* (strict / moderate / lenient by
    //! default — but the names are free-form, you can add your own). A run picks one
    //! level; lower threshold = stricter = more images flagged as NSFW.
    //!
    //! Shape:
    //! {
    //!   "default_level": "moderate",
    //!   "models": {
    //!     "adamcodd-vit-nsfw": { "strict": 0.10, "moderate": 0.30, "lenient": 0.50 }
    //!   }
    //! }
    
    use anyhow::{Context, Result};
    use serde::Deserialize;
    use std::collections::BTreeMap;
    use std::path::Path;
    
    fn default_level() -> String {
        "moderate".to_string()
    }
    
    #[derive(Deserialize, Clone, Debug)]
    pub struct Thresholds {
        #[serde(default = "default_level")]
        pub default_level: String,
        #[serde(default)]
        pub models: BTreeMap<String, BTreeMap<String, f32>>,
    }
    
    impl Thresholds {
        pub fn load(path: &Path) -> Result<Self> {
            let txt = std::fs::read_to_string(path)
                .with_context(|| format!("reading thresholds {}", path.display()))?;
            serde_json::from_str(&txt)
                .with_context(|| format!("parsing thresholds {}", path.display()))
        }
    
        /// Cutoff for (model, level). Falls back: requested level -> "moderate" ->
        /// first level present -> 0.5. Returns (threshold, used_level_or_default_note).
        pub fn get(&self, model: &str, level: &str) -> f32 {
            if let Some(m) = self.models.get(model) {
                if let Some(v) = m.get(level) {
                    return *v;
                }
                if let Some(v) = m.get("moderate") {
                    return *v;
                }
                if let Some((_, v)) = m.iter().next() {
                    return *v;
                }
            }
            0.5
        }
    
        /// True if `level` exists for at least one model (used to warn on typos).
        pub fn level_known(&self, level: &str) -> bool {
            self.models.values().any(|m| m.contains_key(level))
        }
    }
    
}

use anyhow::Result;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Instant;

fn main() -> Result<()> {
    ensure_ort_dylib_env();

    // ---- args (only --level <name> is meaningful; --test-models is accepted for clarity) ----
    let args: Vec<String> = std::env::args().collect();
    let mut cli_level: Option<String> = None;
    let mut cli_file: Option<String> = None;
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--level" && i + 1 < args.len() {
            cli_level = Some(args[i + 1].clone());
            i += 1;
        } else if args[i] == "--file" && i + 1 < args.len() {
            cli_file = Some(args[i + 1].clone());
            i += 1;
        }
        i += 1;
    }

    // --file <path>: score ONE image and print ONLY the number, then exit.
    if let Some(file) = cli_file {
        return run_file_mode(&file, cli_level);
    }

    // 1. setup
    let report = setup::ensure_setup()?;
    if !report.models_ready {
        println!("\nNo models to run yet. Convert them (./setup.sh), then re-run. Exiting.");
        return Ok(());
    }
    if !report.images_ready {
        println!("\nNo test images found. Drop images into {} and re-run. Exiting.", config::TEST_IMG_DIR);
        return Ok(());
    }

    let gt_path = config::ground_truth_file();
    if !gt_path.exists() {
        println!("\nGround truth {} not found. Run ./setup.sh to generate it. Exiting.", gt_path.display());
        return Ok(());
    }

    // 2. thresholds + level
    let thr = thresholds::Thresholds::load(&config::thresholds_file())?;
    let level = cli_level
        .or_else(|| std::env::var("LEVEL").ok())
        .unwrap_or_else(|| thr.default_level.clone());
    if !thr.level_known(&level) {
        eprintln!("WARNING: level '{level}' not present in thresholds.json; using per-model fallback (0.5 if a model is missing).");
    }

    // 3. load detectors
    println!("\n== loading detectors (level: {level}) ==");
    let (mut detectors, load_failures) =
        detectors::load_all(&config::models_dir(), &thr, &level, false)?;
    if detectors.is_empty() && load_failures.is_empty() {
        println!("No detectors found under models/ (need model.onnx + preproc.json). Exiting.");
        return Ok(());
    }

    // 4. run everything, timed
    let gt = eval::load_ground_truth(&gt_path)?;
    println!("\n== running {} detector(s) over {} image(s) ==", detectors.len(), gt.len());

    let mut results: eval::Results = BTreeMap::new();
    for d in &detectors {
        results.insert(d.name.clone(), BTreeMap::new());
    }

    let mut open_failures: Vec<(String, String)> = Vec::new();
    let mut infer_failures: Vec<(String, String, String)> = Vec::new();

    for (path, expected) in &gt {
        let img_path = resolve_image_path(path);
        let img = match image_proc::load_image(&img_path) {
            Ok(i) => i,
            Err(e) => {
                open_failures.push((path.clone(), format!("{e}")));
                continue;
            }
        };

        for d in &mut detectors {
            let t0 = Instant::now();
            match d.infer(&img) {
                Ok(res) => {
                    let ms = eval::r3(t0.elapsed().as_secs_f64() * 1000.0);
                    let out = eval::OutImage {
                        score: eval::r4(res.score),
                        threshold: d.threshold(),
                        calibrated: eval::r4(calibrate::calibrate(res.score, d.threshold())),
                        is_nsfw: res.is_nsfw, // detector's threshold decision
                        expected: *expected,
                        correct: res.is_nsfw == *expected,
                        label: res.label,
                        ms,
                        class_scores: res
                            .class_scores
                            .into_iter()
                            .map(|(k, v)| (k, eval::r4(v)))
                            .collect(),
                    };
                    results.get_mut(&d.name).unwrap().insert(path.clone(), out);
                }
                Err(e) => infer_failures.push((d.name.clone(), path.clone(), format!("{e:#}"))),
            }
        }
    }

    // 5. report + write
    eval::print_test_table(&results, &level);
    let summary = eval::summarize(&results);
    eval::print_summary(&summary);

    let results_path = config::results_file();
    let out = eval::ResultsFile {
        generated_by: "nsfw_test_harness",
        level: level.clone(),
        summary,
        results,
    };
    eval::write_results(&results_path, &out)?;
    println!("\nwrote results -> {}", results_path.display());

    // 6. fail loudly if anything went wrong
    let n_fail = load_failures.len() + open_failures.len() + infer_failures.len();
    if n_fail > 0 {
        eprintln!("\n############### FAILURES ({n_fail}) ###############");
        for f in &load_failures {
            eprintln!("  MODEL LOAD  {:<24} {}", f.name, f.error);
        }
        for (path, e) in &open_failures {
            eprintln!("  IMAGE OPEN  {:<24} {}", path, e);
        }
        for (model, path, e) in &infer_failures {
            eprintln!("  INFERENCE   {model} on {path}: {e}");
        }
        eprintln!("##################################################");
        eprintln!("(a model dir with a broken model.onnx? delete it or re-convert. a bad image? fix or remove it.)");
        std::process::exit(1);
    }

    Ok(())
}

/// Ground-truth `path` may be a bare filename (resolved under the test image dir),
/// an already-relative/absolute path, or — later — a URL (not handled here yet).
fn resolve_image_path(p: &str) -> PathBuf {
    let direct = PathBuf::from(p);
    if direct.is_absolute() || direct.exists() {
        return direct;
    }
    config::test_img_dir().join(p)
}


/// `--file <path>` mode: load detectors quietly, run the first one on the image,
/// and print ONLY the calibrated 0..1 score to stdout (diagnostics go to stderr).
fn run_file_mode(file: &str, cli_level: Option<String>) -> Result<()> {
    let thr = thresholds::Thresholds::load(&config::thresholds_file())?;
    let level = cli_level
        .or_else(|| std::env::var("LEVEL").ok())
        .unwrap_or_else(|| thr.default_level.clone());

    let (mut detectors, _failures) =
        detectors::load_all(&config::models_dir(), &thr, &level, true)?; // quiet
    let det = detectors
        .first_mut()
        .ok_or_else(|| anyhow::anyhow!("no detectors found under {}/", config::MODELS_DIR))?;

    let img = image_proc::load_image(Path::new(file))?;
    let res = det.infer(&img)?;
    let confidence = calibrate::calibrate(res.score, det.threshold());

    println!("{:.2}", confidence); // the one and only stdout line
    Ok(())
}

/// If ORT_DYLIB_PATH isn't already set, point it at the ONNX Runtime that setup.sh
/// downloaded into ./onnxruntime/lib/. Plain std; runs before any ort call.
fn ensure_ort_dylib_env() {
    if std::env::var_os("ORT_DYLIB_PATH").is_some() {
        return;
    }
    for cand in [
        "onnxruntime/lib/libonnxruntime.so",
        "onnxruntime/lib/libonnxruntime.dylib",
    ] {
        let p = Path::new(cand);
        if p.exists() {
            if let Ok(abs) = std::fs::canonicalize(p) {
                std::env::set_var("ORT_DYLIB_PATH", abs);
            }
            return;
        }
    }
}
