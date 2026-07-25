"""Linear-adapter fine-tune of MobileCLIP-S2's text->image alignment (fast path).

The doc's cheapest, safest tuning (docs/memedepot-finetune.md "Linear adapter"):
freeze BOTH towers, precompute their features ONCE, then learn a single 512x512
projection W on the cached vectors so meme *descriptions* land nearer the right
meme image. Training is pure matmul on cached 512-d features — seconds, not the
tens-of-minutes a transformer backward costs on MPS — and it CANNOT catastrophically
forget (the encoders never move). W folds into the text tower's `text_projection`
for a drop-in .pte (linear ∘ linear), so it ships like any other export.

W is saved standalone (tools/finetune/text_adapter.pt); build_golden_local.py
--adapter applies it to query/aspect text embeddings to measure the gain, and
merge_adapter() folds it into a full state_dict for shipping.

Eval holdout (dataset.is_eval) is never touched here.
"""
from __future__ import annotations

import argparse
import os
import random
import sys

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
import clipmodel  # noqa: E402
import dataset  # noqa: E402
from finetune import encode_images  # reuse frozen image encoder  # noqa: E402
from textviews import primary_caption, training_views  # noqa: E402


@torch.no_grad()
def encode_texts_t(model, tokenizer, device, texts, batch=512):
    outs = []
    for i in range(0, len(texts), batch):
        toks = tokenizer(texts[i : i + batch]).to(device)
        outs.append(F.normalize(model.encode_text(toks).float(), dim=-1).cpu())
    return torch.cat(outs, dim=0)


def recall1(txt, img):
    """text->image R@1 over a square set (rows aligned)."""
    sims = txt @ img.T
    return float((sims.argmax(dim=1).cpu().numpy() == np.arange(len(txt))).mean())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "text_adapter.pt"))
    ap.add_argument("--train-size", type=int, default=4000)
    ap.add_argument("--epochs", type=int, default=200)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--resid", type=float, default=1.0, help="init W = resid*I (residual start)")
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    random.seed(a.seed)
    torch.manual_seed(a.seed)

    recs = dataset.load_records(a.data_dir)
    train, held = dataset.split(recs)
    random.shuffle(train)
    if a.train_size:
        train = train[: a.train_size]
    n_val = max(64, int(len(train) * a.val_frac))
    val, train = train[:n_val], train[n_val:]
    print(f"train {len(train)}  val {len(val)}  (eval holdout {len(held)} untouched)", flush=True)

    model, preprocess, tokenizer, device = clipmodel.load()
    for p in model.parameters():
        p.requires_grad_(False)

    print("precomputing frozen image features…", flush=True)
    tr_img, tok = encode_images(model, preprocess, device, [r.path for r in train])
    train = [r for r, k in zip(train, tok) if k]
    tr_img = torch.from_numpy(tr_img[tok])  # [Ntr,512] normalized, CPU
    va_img_np, vok = encode_images(model, preprocess, device, [r.path for r in val])
    val = [r for r, k in zip(val, vok) if k]
    va_img = torch.from_numpy(va_img_np[vok])

    print("precomputing frozen text features (augmented views)…", flush=True)
    # Expand: each image contributes several text views -> more positive pairs.
    tr_texts, tr_row = [], []
    for i, r in enumerate(train):
        for v in (training_views(r.tags) or [primary_caption(r.tags)]):
            tr_texts.append(v)
            tr_row.append(i)
    tr_txt = encode_texts_t(model, tokenizer, device, tr_texts)  # [P,512]
    tr_row = torch.tensor(tr_row)
    va_txt = encode_texts_t(model, tokenizer, device, [primary_caption(r.tags) for r in val])
    print(f"  pairs: {len(tr_texts)} text views over {len(train)} images", flush=True)

    dev = device
    tr_img_d, tr_txt_d, tr_row_d = tr_img.to(dev), tr_txt.to(dev), tr_row.to(dev)
    va_img_d, va_txt_d = va_img.to(dev), va_txt.to(dev)

    W = torch.nn.Parameter((a.resid * torch.eye(512, device=dev)).clone())
    scale = model.logit_scale.exp().clamp(max=100).detach()
    opt = torch.optim.AdamW([W], lr=a.lr, weight_decay=a.wd)

    base_val = recall1(va_txt_d, va_img_d)
    best = recall1(F.normalize(va_txt_d @ W, dim=-1), va_img_d)
    print(f"val R@1: stock {base_val:.3f}  |  init-adapter {best:.3f}", flush=True)
    best_W = W.detach().cpu().clone()

    P = len(tr_texts)
    order = list(range(P))
    for ep in range(1, a.epochs + 1):
        random.shuffle(order)
        model_loss = 0.0
        nb = 0
        for s in range(0, P, a.batch):
            b = order[s : s + a.batch]
            if len(b) < 8:
                continue
            bt = torch.tensor(b, device=dev)
            T = F.normalize(tr_txt_d[bt] @ W, dim=-1)  # adapted text
            I = tr_img_d[tr_row_d[bt]]  # paired frozen image feats
            logits = scale * T @ I.T
            labels = torch.arange(len(b), device=dev)
            loss = 0.5 * (F.cross_entropy(logits, labels) + F.cross_entropy(logits.T, labels))
            opt.zero_grad()
            loss.backward()
            opt.step()
            model_loss += float(loss.detach())
            nb += 1
        if ep % 10 == 0 or ep == 1:
            vr = recall1(F.normalize(va_txt_d @ W, dim=-1), va_img_d)
            flag = ""
            if vr > best:
                best = vr
                best_W = W.detach().cpu().clone()
                flag = " *best"
            print(f"epoch {ep:3d}  loss {model_loss/max(1,nb):.4f}  val R@1 {vr:.3f}{flag}", flush=True)

    torch.save({"W": best_W, "base_val_r1": base_val, "best_val_r1": best}, a.out)
    print(f"saved adapter (val R@1 {base_val:.3f} -> {best:.3f}) -> {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
