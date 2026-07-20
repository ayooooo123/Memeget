"""Definitive forgetting check: generic (non-meme) caption->image retrieval,
stock vs fine-tuned MobileCLIP-S2.

The doc's accept-gate requires "no regression on generic queries." The fine-tune
only touches the TEXT tower (image tower frozen -> image vectors identical stock
vs ft), so any generic caption->image regression comes purely from the text
tower. We measure it on real COCO photos from HuggingFace (the only reachable
image source here; memedepot/general web are egress-blocked).

A pool of `--pool` COCO images; the first `--queries` of them are the retrieval
queries (their human caption should retrieve their own image out of the pool).
Report Recall@1/@5 + MRR for stock vs ft. ft ~ stock => generic retrieval
preserved => no catastrophic forgetting.

  python tools/finetune/forgetting_coco.py --ckpt tools/finetune/mobileclip_s2_memeft.pt
"""
from __future__ import annotations

import argparse
import os
import sys
import warnings

warnings.filterwarnings("ignore")
import numpy as np  # noqa: E402
import torch  # noqa: E402

sys.path.insert(0, os.path.dirname(__file__))
import clipmodel  # noqa: E402


def _caption(ex):
    for k in ("caption", "sentences", "text", "captions"):
        c = ex.get(k)
        if isinstance(c, list) and c:
            c = c[0]
        if isinstance(c, dict):
            c = c.get("raw") or next((v for v in c.values() if isinstance(v, str)), "")
        if isinstance(c, str) and c.strip():
            return c.strip()
    return ""


@torch.no_grad()
def embed_imgs(model, pre, dev, imgs, batch=64):
    out = np.zeros((len(imgs), 512), np.float32)
    for i in range(0, len(imgs), batch):
        x = torch.stack([pre(im.convert("RGB")) for im in imgs[i : i + batch]]).to(dev)
        out[i : i + x.shape[0]] = model.encode_image(x).float().cpu().numpy()
    n = np.linalg.norm(out, axis=1, keepdims=True); n[n == 0] = 1
    return out / n


@torch.no_grad()
def embed_txt(model, tok, dev, texts):
    e = model.encode_text(tok(texts).to(dev)).float().cpu().numpy()
    n = np.linalg.norm(e, axis=1, keepdims=True); n[n == 0] = 1
    return e / n


def score(qv, img, nq):
    order = np.argsort(-(qv @ img.T), axis=1)
    ranks = np.array([np.where(order[k] == k)[0][0] + 1 for k in range(nq)])
    return dict(R1=float((ranks <= 1).mean()), R5=float((ranks <= 5).mean()), MRR=float((1 / ranks).mean()))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memeft.pt"))
    ap.add_argument("--pool", type=int, default=500)
    ap.add_argument("--queries", type=int, default=150)
    ap.add_argument("--tol", type=float, default=0.02)
    a = ap.parse_args()
    if not os.path.isfile(a.ckpt):
        print(f"no checkpoint {a.ckpt}", file=sys.stderr)
        return 2

    from datasets import load_dataset

    print(f"streaming {a.pool} COCO images (cached shards)…", flush=True)
    ds = load_dataset("sayakpaul/coco-30-val-2014", split="train", streaming=True)
    imgs, caps = [], []
    for ex in ds:
        im = ex.get("image")
        cap = _caption(ex)
        if im is not None and cap:
            imgs.append(im)
            caps.append(cap)
        if len(imgs) >= a.pool:
            break
    nq = min(a.queries, len(imgs))
    print(f"  pool {len(imgs)} images; {nq} caption queries", flush=True)

    stock, pre, tok, dev = clipmodel.load()
    print("embedding pool images (frozen tower; identical stock/ft)…", flush=True)
    img = embed_imgs(stock, pre, dev, imgs)
    q_stock = embed_txt(stock, tok, dev, caps[:nq])
    ft, _, ftok, _ = clipmodel.load(a.ckpt)
    q_ft = embed_txt(ft, ftok, dev, caps[:nq])

    s, f = score(q_stock, img, nq), score(q_ft, img, nq)
    print(f"\ngeneric COCO caption->image over {len(imgs)} images ({nq} queries):", flush=True)
    print(f"  stock: R@1 {s['R1']*100:.1f}  R@5 {s['R5']*100:.1f}  MRR {s['MRR']:.3f}", flush=True)
    print(f"  ft   : R@1 {f['R1']*100:.1f}  R@5 {f['R5']*100:.1f}  MRR {f['MRR']:.3f}", flush=True)
    dR1, dMRR = f["R1"] - s["R1"], f["MRR"] - s["MRR"]
    print(f"  Δ    : R@1 {dR1*100:+.1f}  MRR {dMRR:+.3f}", flush=True)
    ok = dR1 >= -a.tol and dMRR >= -a.tol
    print(f"\nGENERIC FORGETTING GUARD: {'PASS' if ok else 'FAIL'} "
          f"(ft must not drop generic retrieval by > {a.tol*100:.0f}pt)", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
