#!/usr/bin/env python3
"""
Quantize the NSFW detector to INT8 for fast, low-memory on-device inference.

Produces models/adamcodd-vit-nsfw-int8/ (model.onnx + preproc.json) from the
full-precision models/adamcodd-vit-nsfw/. The Android app ships this INT8 model:
~4x smaller (344 MB -> ~90 MB) and far less memory-bandwidth-hungry, which is
what stops inference ballooning to 10-15s while a browser is busy on the same
phone. Accuracy is preserved on the test set with the threshold nudged 0.11->0.10
(see models/thresholds.json) — re-run `cargo run --release -- --test-models` after
quantizing to confirm on your own images.

Why MatMul-only + dynamic:
  * Dynamic quantization needs no calibration data and suits transformers, where
    the MatMul/dense layers hold essentially all the weights and compute.
  * We deliberately do NOT quantize the single patch-embedding Conv: dynamic
    quantization turns it into a `ConvInteger` op that the ONNX Runtime CPU/XNNPACK
    backends can't execute, and that one small Conv isn't worth it anyway.

Deps (kept out of the heavy conversion venv on purpose):
    python3 -m venv .venv-quant
    .venv-quant/bin/pip install onnx onnxruntime sympy
    .venv-quant/bin/python scripts/quantize_int8.py

Run from the IMAGE_ANALYSER repo root.
"""
import json
import os
import shutil
import sys
import tempfile
import time

from onnxruntime.quantization import QuantType, quantize_dynamic
from onnxruntime.quantization.shape_inference import quant_pre_process

SRC_DIR = "models/adamcodd-vit-nsfw"
OUT_DIR = "models/adamcodd-vit-nsfw-int8"
OUT_NAME = "adamcodd-vit-nsfw-int8"

src_model = os.path.join(SRC_DIR, "model.onnx")
src_preproc = os.path.join(SRC_DIR, "preproc.json")

if not os.path.isfile(src_model):
    sys.exit(f"source model not found: {src_model} (run ./setup.sh first)")

os.makedirs(OUT_DIR, exist_ok=True)
out_model = os.path.join(OUT_DIR, "model.onnx")
out_preproc = os.path.join(OUT_DIR, "preproc.json")

with tempfile.NamedTemporaryFile(suffix=".onnx", delete=False) as tf:
    prepped = tf.name

try:
    t0 = time.time()
    print("pre-processing (shape inference + graph opt)...", flush=True)
    quant_pre_process(src_model, prepped, skip_symbolic_shape=False)
    print(f"  done in {time.time() - t0:.1f}s", flush=True)

    t1 = time.time()
    print("dynamic INT8 quantization (MatMul only)...", flush=True)
    quantize_dynamic(
        prepped,
        out_model,
        op_types_to_quantize=["MatMul"],
        weight_type=QuantType.QInt8,
        per_channel=True,
    )
    print(f"  done in {time.time() - t1:.1f}s", flush=True)
finally:
    if os.path.exists(prepped):
        os.remove(prepped)

# Sidecar: same preprocessing as the FP32 model, just a distinct name so the test
# harness lists it separately and the app reads the right threshold for it.
with open(src_preproc) as f:
    pre = json.load(f)
pre["name"] = OUT_NAME
with open(out_preproc, "w") as f:
    json.dump(pre, f, indent=2)

fp32_mb = os.path.getsize(src_model) / 1e6
int8_mb = os.path.getsize(out_model) / 1e6
print(f"\nwrote {out_model}")
print(f"  fp32: {fp32_mb:6.1f} MB")
print(f"  int8: {int8_mb:6.1f} MB  ({fp32_mb / int8_mb:.1f}x smaller)")
print(f"\nNext: confirm accuracy with  cargo run --release -- --test-models --level strict")
