#!/usr/bin/env python3
"""
Reference inference in Python, applying the SAME sidecar logic as the Rust core.

Use this to (a) sanity-check that a converted model produces sensible scores, and
(b) diff Python vs Rust on the same image — if they disagree, the bug is in one
preprocessing implementation, not in the model.

Usage:
    python scripts/verify_onnx.py models/adamcodd-vit-nsfw test/static/testImages/foo.jpg
"""

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image


def load_sidecar(model_dir: Path) -> dict:
    return json.loads((model_dir / "preproc.json").read_text())


def preprocess(img: Image.Image, p: dict) -> np.ndarray:
    resize = p.get("resize", "stretch")
    in_h, in_w = p["input_size"]
    if resize == "shortest_then_center_crop":
        ch, cw = p.get("crop_size", [in_h, in_w])
        target = min(in_h, in_w)
        ow, oh = img.size
        scale = target / min(ow, oh)
        nw, nh = max(round(ow * scale), cw), max(round(oh * scale), ch)
        img = img.resize((nw, nh), Image.BILINEAR)
        left, top = (nw - cw) // 2, (nh - ch) // 2
        img = img.crop((left, top, left + cw, top + ch))
        out_h, out_w = ch, cw
    else:
        out_h, out_w = in_h, in_w
        img = img.resize((out_w, out_h), Image.BILINEAR)

    arr = np.asarray(img.convert("RGB"), dtype=np.float32)  # H,W,3 RGB
    if p.get("channel_order", "rgb") == "bgr":
        arr = arr[:, :, ::-1]

    mean = np.array(p.get("mean", [0.5, 0.5, 0.5]), dtype=np.float32)
    std = np.array(p.get("std", [0.5, 0.5, 0.5]), dtype=np.float32)
    rescale = float(p.get("rescale", 1.0 / 255.0))
    arr = (arr * rescale - mean) / std

    if p.get("layout", "nchw") == "nchw":
        arr = np.transpose(arr, (2, 0, 1))      # C,H,W
    arr = arr[np.newaxis, ...]                  # add batch
    return np.ascontiguousarray(arr, dtype=np.float32)


def softmax(x):
    x = x - np.max(x)
    e = np.exp(x)
    return e / e.sum()


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    model_dir = Path(sys.argv[1])
    img_path = Path(sys.argv[2])

    p = load_sidecar(model_dir)
    sess = ort.InferenceSession(str(model_dir / "model.onnx"),
                                providers=["CPUExecutionProvider"])
    img = Image.open(img_path)
    x = preprocess(img, p)

    in_name = sess.get_inputs()[0].name
    out = sess.run(None, {in_name: x})[0].reshape(-1)

    task = p.get("task", "classification")
    if task in ("classification", "score"):
        probs = softmax(out) if p.get("apply_softmax", True) else out
        idxs = p.get("nsfw_label_indices") or [len(probs) - 1]
        score = float(probs[0]) if len(probs) == 1 else float(sum(probs[i] for i in idxs))
        id2label = p.get("id2label", {})
        print(f"model     : {p['name']}")
        print(f"image     : {img_path.name}")
        for i, pr in enumerate(probs):
            print(f"  {id2label.get(str(i), str(i)):<22} {pr:.4f}")
        print(f"NSFW score: {score:.4f}  -> is_nsfw={score >= p.get('threshold', 0.5)}")
    else:
        print(f"task '{task}' (detection): raw output shape {out.shape}; "
              "use Rust harness for full detection postprocessing.")


if __name__ == "__main__":
    main()
