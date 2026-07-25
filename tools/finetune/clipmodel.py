"""MobileCLIP-S2 loader + batched image/text embedding on Apple MPS.

MobileCLIP-S2 is the app's PRIMARY_EMBEDDING_MODEL (dim 512). Embedding here with
the SAME architecture is what makes the offline eval score what the phone would
(tools/eval/README.md). A fine-tune is loaded via `ckpt` (a merged state_dict);
pass none for stock weights.
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
        missing, unexpected = model.load_state_dict(state, strict=False)
        if missing:
            print(f"  [load] {len(missing)} missing keys (ok if only buffers)")
        if unexpected:
            print(f"  [load] {len(unexpected)} unexpected keys")
    model = model.to(device).eval()
    return model, preprocess, tokenizer, device


def _l2(x: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(x, axis=-1, keepdims=True)
    n[n == 0] = 1.0
    return x / n


@torch.no_grad()
def embed_images(model, preprocess, device, paths, batch=64, log_every=10):
    """Return (vecs [N,512] L2-normalized, ok_mask) — unreadable images get a
    zero row and ok_mask=False so callers can drop them."""
    vecs = np.zeros((len(paths), 512), dtype=np.float32)
    ok = np.zeros(len(paths), dtype=bool)
    buf, idx = [], []
    done = 0

    def flush():
        nonlocal done
        if not buf:
            return
        x = torch.stack(buf).to(device)
        e = model.encode_image(x).float().cpu().numpy()
        for j, row in zip(idx, e):
            vecs[j] = row
            ok[j] = True
        done += len(buf)
        buf.clear()
        idx.clear()

    for i, p in enumerate(paths):
        try:
            img = Image.open(p).convert("RGB")
            buf.append(preprocess(img))
            idx.append(i)
        except Exception:
            continue
        if len(buf) >= batch:
            flush()
            if (done // batch) % log_every == 0:
                print(f"  images {done}/{len(paths)}")
    flush()
    return _l2(vecs), ok


@torch.no_grad()
def embed_texts(model, tokenizer, device, texts, batch=256):
    vecs = np.zeros((len(texts), 512), dtype=np.float32)
    for i in range(0, len(texts), batch):
        chunk = texts[i : i + batch]
        toks = tokenizer(chunk).to(device)
        e = model.encode_text(toks).float().cpu().numpy()
        vecs[i : i + len(chunk)] = e
    return _l2(vecs)
