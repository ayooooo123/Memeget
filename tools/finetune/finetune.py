"""Fine-tune MobileCLIP-S2 on the local meme corpus (Apple MPS).

Strategy (the doc's cheaper, safer first experiment — docs/memedepot-finetune.md
"Cheaper alternatives"): FREEZE the image tower, fine-tune the TEXT tower with
symmetric InfoNCE against precomputed frozen image features. This shifts meme
*descriptions* toward the right meme image (the retrieval objective) while making
it impossible to drift/forget the image space, and it's cheap: images are encoded
once, only text is forwarded during training.

Guards against the classic "silently worse" meme fine-tune:
  - eval holdout (dataset.is_eval) is NEVER seen here — it's the accept gate.
  - a train-internal val split drives early-stopping (best val R@1 kept).
  - low LR + weight decay + few epochs; image tower frozen.

Output: a merged state_dict at --out (image weights stock, text weights tuned),
loadable by clipmodel.load(ckpt=...). Re-embed the golden set with it and compare
to stock via `npm run eval`.
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
from textviews import primary_caption, training_views  # noqa: E402


def freeze_image_tower(model):
    trainable = 0
    for name, p in model.named_parameters():
        train = not name.startswith("visual.") and "logit_scale" not in name
        p.requires_grad_(train)
        if train:
            trainable += p.numel()
    return trainable


@torch.no_grad()
def encode_images(model, preprocess, device, paths, batch=64):
    from PIL import Image

    feats = np.zeros((len(paths), 512), dtype=np.float32)
    ok = np.zeros(len(paths), dtype=bool)
    buf, idx = [], []

    def flush():
        if not buf:
            return
        x = torch.stack(buf).to(device)
        e = F.normalize(model.encode_image(x).float(), dim=-1).cpu().numpy()
        for j, row in zip(idx, e):
            feats[j] = row
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
    return feats, ok


def val_recall1(model, tokenizer, device, captions, img_feats):
    """Text->image R@1 over the val slice (square retrieval)."""
    model.eval()
    with torch.no_grad():
        t = F.normalize(clip_text(model, tokenizer, device, captions), dim=-1)
        I = torch.from_numpy(img_feats).to(device)
        sims = t @ I.T  # [V, V]
        pred = sims.argmax(dim=1).cpu().numpy()
    return float((pred == np.arange(len(captions))).mean())


def clip_text(model, tokenizer, device, texts, batch=512):
    outs = []
    for i in range(0, len(texts), batch):
        toks = tokenizer(texts[i : i + batch]).to(device)
        outs.append(model.encode_text(toks).float())
    return torch.cat(outs, dim=0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memeft.pt"))
    ap.add_argument("--train-size", type=int, default=6000, help="cap train images (0 = all)")
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--wd", type=float, default=0.1)
    ap.add_argument("--val-frac", type=float, default=0.06)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    random.seed(a.seed)
    torch.manual_seed(a.seed)

    recs = dataset.load_records(a.data_dir)
    train, _held = dataset.split(recs)  # _held is the eval golden slice — untouched
    random.shuffle(train)
    if a.train_size:
        train = train[: a.train_size]
    n_val = max(64, int(len(train) * a.val_frac))
    val, train = train[:n_val], train[n_val:]
    print(f"train images: {len(train)}   val: {len(val)}   (eval holdout {len(_held)} untouched)")

    model, preprocess, tokenizer, device = clipmodel.load()
    trainable = freeze_image_tower(model)
    print(f"model on {device}; trainable text params: {trainable/1e6:.1f}M")

    print("precomputing frozen image features…")
    tr_feats, tr_ok = encode_images(model, preprocess, device, [r.path for r in train])
    train = [r for r, k in zip(train, tr_ok) if k]
    tr_feats = torch.from_numpy(tr_feats[tr_ok]).to(device)
    va_feats, va_ok = encode_images(model, preprocess, device, [r.path for r in val])
    val = [r for r, k in zip(val, va_ok) if k]
    va_feats = va_feats[va_ok]
    val_caps = [primary_caption(r.tags) for r in val]
    print(f"  usable train {len(train)}   val {len(val)}")

    # Pre-tokenize a pool of text views per training image for cheap augmentation.
    views = [training_views(r.tags) or [primary_caption(r.tags)] for r in train]
    scale = model.logit_scale.exp().clamp(max=100).detach()

    opt = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=a.lr, weight_decay=a.wd)

    best = val_recall1(model, tokenizer, device, val_caps, va_feats)
    print(f"epoch 0  val R@1 {best:.3f}  (stock text tower)")
    best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    idxs = list(range(len(train)))

    for ep in range(1, a.epochs + 1):
        model.train()
        random.shuffle(idxs)
        tot = 0.0
        steps = 0
        for s in range(0, len(idxs), a.batch):
            b = idxs[s : s + a.batch]
            if len(b) < 8:
                continue
            texts = [random.choice(views[i]) for i in b]
            toks = tokenizer(texts).to(device)
            T = F.normalize(model.encode_text(toks).float(), dim=-1)
            I = tr_feats[b]  # frozen, already normalized
            logits = scale * T @ I.T
            labels = torch.arange(len(b), device=device)
            loss = 0.5 * (F.cross_entropy(logits, labels) + F.cross_entropy(logits.T, labels))
            opt.zero_grad()
            loss.backward()
            opt.step()
            tot += float(loss)
            steps += 1
        vr = val_recall1(model, tokenizer, device, val_caps, va_feats)
        flag = ""
        if vr > best:
            best = vr
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            torch.save(best_state, a.out)  # persist immediately so a kill keeps the best
            flag = " *best (saved)"
        print(f"epoch {ep}  loss {tot/max(1,steps):.4f}  val R@1 {vr:.3f}{flag}", flush=True)

    torch.save(best_state, a.out)
    print(f"saved best (val R@1 {best:.3f}) -> {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
