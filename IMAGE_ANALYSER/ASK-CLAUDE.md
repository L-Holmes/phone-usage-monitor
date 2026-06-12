
also, are we doing nude net? i thought it wasn't open source for use with a paid app, or am i imagining that?


most recent run, please fix any issues...


chmod +x setup.sh && ./setup.sh
==> toolchain ok (rustc 1.94.1)

==> ensuring directory layout

==> reusing existing venv (.venv)

==> installing/upgrading conversion deps (first run pulls torch and is slow)

==> converting models to ONNX (already-built models are skipped)
== marqo-nsfw-384: already converted, skipping (use --force to redo) ==
== falconsai-nsfw: already converted, skipping (use --force to redo) ==
== adamcodd-vit-nsfw: already converted, skipping (use --force to redo) ==
== siglip2-explicit: already converted, skipping (use --force to redo) ==

== siglip2-explicit-p32: SigLIP2 variant (x256p32) ==
  [siglip2-explicit-p32] loading prithivMLmods/siglip2-x256p32-explicit-content ...
Warning: You are sending unauthenticated requests to the HF Hub. Please set a HF_TOKEN to enable higher rate limits and faster downloads.
config.json: 100%|█████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████| 1.30k/1.30k [00:00<00:00, 1.24MB/s]
[transformers] Model config: bos_token_id must be `None` or an integer within the vocabulary (between 0 and 31999), got 49406. This may result in unexpected behavior.
[transformers] Model config: eos_token_id must be `None` or an integer within the vocabulary (between 0 and 31999), got 49407. This may result in unexpected behavior.
model.safetensors: 100%|█████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████| 378M/378M [00:21<00:00, 17.7MB/s]
Loading weights: 100%|█████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████| 210/210 [00:00<00:00, 10598.94it/s]
preprocessor_config.json: 100%|████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████| 394/394 [00:00<00:00, 2.46MB/s]
  [siglip2-explicit-p32] exporting to ONNX (256x256) ...
/home/main/code/stay-focused/scripts/convert_models.py:136: DeprecationWarning: You are using the legacy TorchScript-based ONNX export. Starting in PyTorch 2.9, the new torch.export-based ONNX exporter has become the default. Learn more about the new export logic: https://docs.pytorch.org/docs/stable/onnx_export.html. For exporting control flow: https://pytorch.org/tutorials/beginner/onnx/export_control_flow_model_to_onnx_tutorial.html
  torch.onnx.export(model, dummy, str(out_path), dynamo=False, **common)
/home/main/code/stay-focused/.venv/lib/python3.13/site-packages/transformers/integrations/sdpa_attention.py:77: TracerWarning: Converting a tensor to a Python boolean might cause the trace to be incorrect. We can't record the data flow of Python values, so this value will be treated as a constant in the future. This means that the trace might not generalize to other inputs!
  is_causal = query.shape[2] > 1 and attention_mask is None and is_causal
  [siglip2-explicit-p32] wrote model.onnx
  [siglip2-explicit-p32] wrote preproc.json

== nsfwjs-gantman: NSFWJS/GantMan MobileNetV2 5-class ==
  !! nsfwjs-gantman FAILED: No module named 'tf2onnx'

== opennsfw2: OpenNSFW2 (Yahoo) score ==
  !! opennsfw2 FAILED: No module named 'opennsfw2'

== bumble-private-detector: Bumble EfficientNetV2 (optional) ==
  !! bumble-private-detector FAILED: No module named 'tf2onnx'

==================== summary ====================
converted: siglip2-explicit-p32
already present (skipped): marqo-nsfw-384, falconsai-nsfw, adamcodd-vit-nsfw, siglip2-explicit
failed/skipped: nsfwjs-gantman, opennsfw2, bumble-private-detector
(failures are usually missing deps or a SavedModel you must download first)

==> ground truth exists, leaving as-is: test/are-images-nsfw-ground-truth.json

==> ONNX Runtime 1.22.0 already present in onnxruntime/lib

==> ORT_DYLIB_PATH=/home/main/code/stay-focused/onnxruntime/lib/libonnxruntime.so

==> building Rust core (compiles deps; no OpenSSL needed now)
   Compiling nsfw_test_harness v0.1.0 (/home/main/code/stay-focused)
    Finished `release` profile [optimized] target(s) in 3.03s

==> running the model test (--test-models)
    Finished `release` profile [optimized] target(s) in 0.03s
     Running `target/release/nsfw_test_harness --test-models`
== setup checks ==
  found 7 test image(s) in test/static/testImages
  ground-truth file present: test/are-images-nsfw-ground-truth.json
  found 6 model(s) under models/
  thresholds file present: models/thresholds.json

== loading detectors (level: strict) ==
  loaded detector: adamcodd-vit-nsfw (threshold 0.10)
  loaded detector: falconsai-nsfw (threshold 0.05)
  loaded detector: marqo-nsfw-384 (threshold 0.50)
  FAILED to load models/nudenet: loading model models/nudenet/model.onnx: Load model from models/nudenet/model.onnx failed:Protobuf parsing failed.
  loaded detector: siglip2-explicit (threshold 0.52)
  loaded detector: siglip2-explicit-p32 (threshold 0.52)

== running 5 detector(s) over 7 image(s) ==

================================ model test (level: strict) ================================
model                    image                      expect    score  thresh verdict    ok       ms
adamcodd-vit-nsfw        allowed.jpg                fine      0.025    0.10 fine       ok   1455.5
adamcodd-vit-nsfw        allowed2.jpg               fine      0.048    0.10 fine       ok   1439.6
adamcodd-vit-nsfw        not-allowed1.jpg           nsfw      0.445    0.10 nsfw       ok   1471.9
adamcodd-vit-nsfw        not-allowed2.jpg           nsfw      0.998    0.10 nsfw       ok   1466.1
adamcodd-vit-nsfw        not-allowed3.jpeg          nsfw      0.678    0.10 nsfw       ok   1440.8
adamcodd-vit-nsfw        not-allowed4.jpg           nsfw      0.572    0.10 nsfw       ok   1473.3
adamcodd-vit-nsfw        not-allowed5.jpeg          nsfw      0.111    0.10 nsfw       ok   1460.7
falconsai-nsfw           allowed.jpg                fine      0.006    0.05 fine       ok    437.2
falconsai-nsfw           allowed2.jpg               fine      0.001    0.05 fine       ok    432.4
falconsai-nsfw           not-allowed1.jpg           nsfw      0.130    0.05 nsfw       ok    423.8
falconsai-nsfw           not-allowed2.jpg           nsfw      0.805    0.05 nsfw       ok    428.7
falconsai-nsfw           not-allowed3.jpeg          nsfw      0.000    0.05 fine     FAIL    441.8
falconsai-nsfw           not-allowed4.jpg           nsfw      0.000    0.05 fine     FAIL    440.2
falconsai-nsfw           not-allowed5.jpeg          nsfw      0.001    0.05 fine     FAIL    440.3
marqo-nsfw-384           allowed.jpg                fine      0.928    0.50 nsfw     FAIL    128.8
marqo-nsfw-384           allowed2.jpg               fine      0.943    0.50 nsfw     FAIL    132.2
marqo-nsfw-384           not-allowed1.jpg           nsfw      0.908    0.50 nsfw       ok    138.0
marqo-nsfw-384           not-allowed2.jpg           nsfw      0.157    0.50 fine     FAIL    123.3
marqo-nsfw-384           not-allowed3.jpeg          nsfw      0.918    0.50 nsfw       ok    135.8
marqo-nsfw-384           not-allowed4.jpg           nsfw      0.934    0.50 nsfw       ok    139.3
marqo-nsfw-384           not-allowed5.jpeg          nsfw      0.941    0.50 nsfw       ok    130.4
siglip2-explicit         allowed.jpg                fine      0.062    0.52 fine       ok    553.2
siglip2-explicit         allowed2.jpg               fine      0.504    0.52 fine       ok    553.4
siglip2-explicit         not-allowed1.jpg           nsfw      0.991    0.52 nsfw       ok    579.1
siglip2-explicit         not-allowed2.jpg           nsfw      0.996    0.52 nsfw       ok    585.6
siglip2-explicit         not-allowed3.jpeg          nsfw      0.992    0.52 nsfw       ok    559.1
siglip2-explicit         not-allowed4.jpg           nsfw      0.038    0.52 fine     FAIL    595.0
siglip2-explicit         not-allowed5.jpeg          nsfw      0.447    0.52 fine     FAIL    564.6
siglip2-explicit-p32     allowed.jpg                fine      0.042    0.52 fine       ok    146.5
siglip2-explicit-p32     allowed2.jpg               fine      0.128    0.52 fine       ok    145.3
siglip2-explicit-p32     not-allowed1.jpg           nsfw      0.984    0.52 nsfw       ok    141.7
siglip2-explicit-p32     not-allowed2.jpg           nsfw      0.996    0.52 nsfw       ok    150.4
siglip2-explicit-p32     not-allowed3.jpeg          nsfw      0.657    0.52 nsfw       ok    150.3
siglip2-explicit-p32     not-allowed4.jpg           nsfw      0.085    0.52 fine     FAIL    152.5
siglip2-explicit-p32     not-allowed5.jpeg          nsfw      0.395    0.52 fine     FAIL    134.9
====================================================================================================

================ accuracy vs ground truth ================
detector                    acc     n   TP   FP   TN   FN    avg_ms
adamcodd-vit-nsfw        100.0%     7    5    0    2    0    1458.3
falconsai-nsfw            57.1%     7    2    0    2    3     434.9
marqo-nsfw-384            57.1%     7    4    2    0    1     132.5
siglip2-explicit          71.4%     7    3    0    2    2     570.0
siglip2-explicit-p32      71.4%     7    3    0    2    2     145.9
==========================================================

wrote results -> test/results.json

############### FAILURES (1) ###############
  MODEL LOAD  nudenet                  loading model models/nudenet/model.onnx: Load model from models/nudenet/model.onnx failed:Protobuf parsing failed.
##################################################
(a model dir with a broken model.onnx? delete it or re-convert. a bad image? fix or remove it.)
main@main:~/code/stay-focused$ f
Changed directory to: /home/main/code/stay-focused
