"""MobileCLIP-S2 loader + batched image/text embedding on Apple MPS.

MobileCLIP-S2 is the app's PRIMARY_EMBEDDING_MODEL (dim 512). A fine-tuned model
is loaded via `ckpt` (a full merged state_dict, e.g. from finetune.py with the
text_projection folded); pass none for stock weights.
"""
from __future__ import annotations

import numpy as np
import open_clip
import torch
from PIL import Image

MODEL_NAME = "MobileCLIP-S2"
PRETRAINED = "datacompdr"


def pick_device() -> str:
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


def load(ckpt: str | None = None, device: str | None = None):
    device = device or pick_device()
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL_NAME, pretrained=PRETRAINED)
    tokenizer = open_clip.get_tokenizer(MODEL_NAME)
    if ckpt:
        state = torch.load(ckpt, map_location="cpu")
        state = state.get("model", state)
        model.load_state_dict(state, strict=False)
    return model.to(device).eval(), preprocess, tokenizer, device


def _l2(x: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(x, axis=-1, keepdims=True)
    n[n == 0] = 1.0
    return x / n


@torch.no_grad()
def embed_images(model, preprocess, device, paths, batch=64):
    vecs = np.zeros((len(paths), 512), dtype=np.float32)
    ok = np.zeros(len(paths), dtype=bool)
    buf, idx = [], []

    def flush():
        if not buf:
            return
        e = model.encode_image(torch.stack(buf).to(device)).float().cpu().numpy()
        for j, row in zip(idx, e):
            vecs[j] = row
            ok[j] = True
        buf.clear()
        idx.clear()

    for i, p in enumerate(paths):
        try:
            buf.append(preprocess(Image.open(p).convert("RGB")))
            idx.append(i)
        except Exception:
            continue
        if len(buf) >= batch:
            flush()
    flush()
    return _l2(vecs), ok


@torch.no_grad()
def embed_texts(model, tokenizer, device, texts, batch=256):
    vecs = np.zeros((len(texts), 512), dtype=np.float32)
    for i in range(0, len(texts), batch):
        chunk = texts[i : i + batch]
        vecs[i : i + len(chunk)] = model.encode_text(tokenizer(chunk).to(device)).float().cpu().numpy()
    return _l2(vecs)
