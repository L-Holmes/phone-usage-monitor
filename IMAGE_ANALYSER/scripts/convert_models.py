#!/usr/bin/env python3
"""
ONE-TIME, OFFLINE model conversion. Runs on your Debian box, never ships.

For the detector it:
  1. obtains the model,
  2. exports it to  models/<name>/model.onnx,
  3. writes        models/<name>/preproc.json  (the sidecar the Rust core reads).

The sidecar carries the preprocessing constants pulled from the model's OWN
config, so Rust never has to hardcode (and mis-guess) mean/std/size/labels.

Usage:
    python scripts/convert_models.py              # convert everything it can
    python scripts/convert_models.py --list       # list recipes
    python scripts/convert_models.py --only adamcodd-vit-nsfw

Notes:
  * This project ships a single detector: AdamCodd's ViT binary NSFW model, a
    Hugging Face image classifier that exports cleanly via torch.onnx. It Just
    Works given the deps in requirements.txt are installed.
"""

import argparse
import json
import os
import shutil
import warnings
from pathlib import Path

# Keep the conversion log readable: silence known-benign noise from the export path.
# (These are warnings, not errors — the ONNX still exports correctly.)
os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")
warnings.filterwarnings("ignore", message=r".*legacy TorchScript-based ONNX export.*")
warnings.filterwarnings("ignore", message=r".*Converting a tensor to a Python boolean.*")

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"

NSFW_KEYWORDS = (
    "nsfw", "porn", "hentai", "sexy", "sensual", "enticing",
    "explicit", "lewd", "nude", "sex",
)


# --------------------------------------------------------------------------- #
# sidecar helpers
# --------------------------------------------------------------------------- #
def nsfw_indices_from_labels(id2label: dict) -> list:
    """Pick the label indices that look NSFW (case-insensitive keyword match)."""
    out = []
    for k, v in id2label.items():
        if any(kw in str(v).lower() for kw in NSFW_KEYWORDS):
            out.append(int(k))
    return sorted(out)


def write_sidecar(name: str, sidecar: dict):
    out_dir = MODELS_DIR / name
    out_dir.mkdir(parents=True, exist_ok=True)
    sidecar["name"] = name
    (out_dir / "preproc.json").write_text(json.dumps(sidecar, indent=2))
    print(f"  [{name}] wrote preproc.json")


def sidecar_from_hf_dir(export_dir: Path) -> dict:
    """Build a sidecar from a HF export's config.json + preprocessor_config.json."""
    cfg = json.loads((export_dir / "config.json").read_text())
    pre_path = export_dir / "preprocessor_config.json"
    pre = json.loads(pre_path.read_text()) if pre_path.exists() else {}

    id2label = cfg.get("id2label", {"0": "sfw", "1": "nsfw"})
    id2label = {str(k): v for k, v in id2label.items()}

    # size
    size = pre.get("size", {})
    crop = pre.get("crop_size", {})
    do_center_crop = pre.get("do_center_crop", False)
    if isinstance(size, dict) and "height" in size:
        input_size = [int(size["height"]), int(size["width"])]
        resize = "stretch"
        crop_size = None
    elif isinstance(size, dict) and "shortest_edge" in size:
        se = int(size["shortest_edge"])
        input_size = [se, se]
        if do_center_crop and crop:
            resize = "shortest_then_center_crop"
            crop_size = [int(crop["height"]), int(crop["width"])]
        else:
            resize = "stretch"
            crop_size = None
    elif isinstance(size, int):
        input_size = [size, size]
        resize = "stretch"
        crop_size = None
    else:
        input_size = [224, 224]
        resize = "stretch"
        crop_size = None

    do_rescale = pre.get("do_rescale", True)
    rescale = float(pre.get("rescale_factor", 1.0 / 255.0)) if do_rescale else 1.0
    do_norm = pre.get("do_normalize", True)
    mean = [float(x) for x in pre.get("image_mean", [0.5, 0.5, 0.5])] if do_norm else [0.0, 0.0, 0.0]
    std = [float(x) for x in pre.get("image_std", [0.5, 0.5, 0.5])] if do_norm else [1.0, 1.0, 1.0]

    sidecar = {
        "task": "classification",
        "input_size": input_size,
        "resize": resize,
        "channel_order": "rgb",   # HF image processors emit RGB, NCHW
        "layout": "nchw",
        "rescale": rescale,
        "mean": mean,
        "std": std,
        "apply_softmax": True,
        "id2label": id2label,
        "nsfw_label_indices": nsfw_indices_from_labels(id2label),
    }
    if crop_size:
        sidecar["crop_size"] = crop_size
    return sidecar


def torch_onnx_export(model, dummy, out_path: Path, input_names, output_names):
    """Export a torch module to ONNX, preferring the stable (non-dynamo) exporter.

    Newer torch defaults to the dynamo exporter, which needs `onnxscript` and was the
    cause of the 'No module named onnxscript' failure. We force dynamo=False (legacy
    TorchScript exporter); if the running torch is too old to know that kwarg, we fall
    back to the plain call.
    """
    import torch
    dyn = {input_names[0]: {0: "batch"}, output_names[0]: {0: "batch"}}
    common = dict(input_names=input_names, output_names=output_names,
                  dynamic_axes=dyn, opset_version=17)
    try:
        torch.onnx.export(model, dummy, str(out_path), dynamo=False, **common)
    except TypeError:
        torch.onnx.export(model, dummy, str(out_path), **common)


def convert_hf_torch(name: str, hf_id: str):
    """HF image classifier -> ONNX via torch.onnx directly (no optimum).

    optimum 2.x removed the [exporters] extra and split the ONNX exporter into a
    separate package, so we skip it entirely: load the model with transformers,
    export with torch, and build the sidecar from the model's own config.
    """
    import torch
    from transformers import AutoModelForImageClassification, AutoImageProcessor

    out_dir = MODELS_DIR / name
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"  [{name}] loading {hf_id} ...")

    model = AutoModelForImageClassification.from_pretrained(hf_id).eval()
    proc = AutoImageProcessor.from_pretrained(hf_id)

    # dump config + preprocessor so sidecar_from_hf_dir can read the real constants
    cfg_dir = MODELS_DIR / f"_cfg_{name}"
    cfg_dir.mkdir(parents=True, exist_ok=True)
    model.config.save_pretrained(cfg_dir)
    proc.save_pretrained(cfg_dir)
    sidecar = sidecar_from_hf_dir(cfg_dir)
    h, w = (sidecar.get("crop_size") or sidecar["input_size"])

    # wrap so the ONNX graph has a single clean "logits" output
    class LogitsOnly(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, pixel_values):
            return self.m(pixel_values=pixel_values).logits

    print(f"  [{name}] exporting to ONNX ({h}x{w}) ...")
    dummy = torch.randn(1, 3, h, w)
    torch_onnx_export(LogitsOnly(model), dummy, out_dir / "model.onnx",
                      ["pixel_values"], ["logits"])
    print(f"  [{name}] wrote model.onnx")

    write_sidecar(name, sidecar)
    shutil.rmtree(cfg_dir, ignore_errors=True)


# --------------------------------------------------------------------------- #
# recipe table
# --------------------------------------------------------------------------- #
# (name, description, converter callable)
RECIPES = {
    "adamcodd-vit-nsfw": ("AdamCodd ViT binary",
                          lambda n: convert_hf_torch(n, "AdamCodd/vit-base-nsfw-detector")),
}

DEFAULT_TARGETS = list(RECIPES.keys())


def main():
    ap = argparse.ArgumentParser(description="Convert NSFW detectors to ONNX + sidecars.")
    ap.add_argument("--only", nargs="*", help="convert only these recipe names")
    ap.add_argument("--force", action="store_true",
                    help="re-convert even if models/<name>/model.onnx already exists")
    ap.add_argument("--list", action="store_true", help="list recipes and exit")
    args = ap.parse_args()

    if args.list:
        print("recipes:")
        for name, (desc, _) in RECIPES.items():
            print(f"  {name:<26} {desc}")
        return

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    targets = args.only if args.only else DEFAULT_TARGETS

    ok, failed, skipped = [], [], []
    for name in targets:
        if name not in RECIPES:
            print(f"!! unknown recipe: {name}")
            failed.append(name)
            continue
        if (MODELS_DIR / name / "model.onnx").exists() and not args.force:
            print(f"== {name}: already converted, skipping (use --force to redo) ==")
            skipped.append(name)
            continue
        desc, fn = RECIPES[name]
        print(f"\n== {name}: {desc} ==")
        try:
            fn(name)
            ok.append(name)
        except Exception as e:  # keep going; one model's deps shouldn't block others
            print(f"  !! {name} FAILED: {e}")
            failed.append(name)

    print("\n==================== summary ====================")
    print(f"converted: {', '.join(ok) if ok else '(none)'}")
    if skipped:
        print(f"already present (skipped): {', '.join(skipped)}")
    if failed:
        print(f"failed/skipped: {', '.join(failed)}")
        print("(failures are usually missing deps or a SavedModel you must download first)")


if __name__ == "__main__":
    main()
