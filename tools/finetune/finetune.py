"""Fine-tune MobileCLIP-S2 for meme retrieval and produce a DROP-IN checkpoint.

Method (docs/memedepot-finetune.md's safe/cheap path): freeze both towers,
learn a residual linear map W = I + Δ on the TEXT side against precomputed frozen
image features (symmetric InfoNCE). Because MobileCLIP-S2's text tower ends in
`x @ text.text_projection` (no bias/norm after), the residual FOLDS EXACTLY into
that projection:

    text_projection <- text_projection @ (I + Δ)

so `encode_text` then already includes the adaptation and the app L2-normalizes
as usual — a plain re-export, no app code change (the app swaps the primary model
via EXPO_PUBLIC_MEMEGET_* env vars). The image tower is untouched, so the image
space can't drift and stored image vectors stay valid.

Δ is therefore trained on the RAW (pre-normalization) text output, not the
normalized one, so the fold is exact. Guards: eval holdout (dataset.is_eval) is
never seen; a train-internal val drives early-stop; residual init 0 with weight
decay pulls toward identity; small lr/wd grid, best val kept.

Output: merged full state_dict at --out, loadable by clipmodel.load(ckpt=...).
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


@torch.no_grad()
def raw_text(model, tok, device, texts, batch=256):
    """encode_text WITHOUT L2-norm (so a residual folds into text_projection)."""
    out = np.zeros((len(texts), 512), np.float32)
    for i in range(0, len(texts), batch):
        c = texts[i : i + batch]
        out[i : i + len(c)] = model.encode_text(tok(c).to(device)).float().cpu().numpy()
    return out


@torch.no_grad()
def norm_img(model, pre, device, paths):
    v, ok = clipmodel.embed_images(model, pre, device, paths)  # already L2-normed
    return v, ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memeft.pt"))
    ap.add_argument("--train-size", type=int, default=6000)
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    random.seed(a.seed)
    torch.manual_seed(a.seed)

    recs = dataset.load_records(a.data_dir)
    train, held = dataset.split(recs)
    random.shuffle(train)
    if a.train_size:
        train = train[: a.train_size]
    n_val = max(64, len(train) // 10)
    val, train = train[:n_val], train[n_val:]
    print(f"train {len(train)}  val {len(val)}  (eval holdout {len(held)} untouched)", flush=True)

    model, pre, tok, device = clipmodel.load()
    for p in model.parameters():
        p.requires_grad_(False)
    print(f"MobileCLIP-S2 on {device}", flush=True)

    print("precomputing frozen image + raw text features…", flush=True)
    ti, tok_ok = norm_img(model, pre, device, [r.path for r in train])
    train = [r for r, k in zip(train, tok_ok) if k]
    ti = torch.from_numpy(ti[tok_ok]).to(device)
    vi, v_ok = norm_img(model, pre, device, [r.path for r in val])
    val = [r for r, k in zip(val, v_ok) if k]
    vi = torch.from_numpy(vi[v_ok]).to(device)

    texts, rows = [], []
    for i, r in enumerate(train):
        for v in (training_views(r.tags) or [primary_caption(r.tags)]):
            texts.append(v)
            rows.append(i)
    T = torch.from_numpy(raw_text(model, tok, device, texts)).to(device)  # RAW (pre-norm)
    rows = torch.tensor(rows, device=device)
    vT = torch.from_numpy(raw_text(model, tok, device, [primary_caption(r.tags) for r in val])).to(device)
    print(f"train pairs {len(texts)} over {len(train)} images", flush=True)

    Ieye = torch.eye(512, device=device)
    scale = model.logit_scale.exp().clamp(max=100).detach()

    def val_r1(delta):
        Tv = F.normalize(vT @ (Ieye + delta), dim=-1)
        s = Tv @ vi.T
        return float((s.argmax(1).cpu().numpy() == np.arange(len(val))).mean())

    best_delta, best_val, best_hp = torch.zeros(512, 512), val_r1(torch.zeros(512, 512, device=device)), None
    print(f"stock val R@1 {best_val:.3f}", flush=True)
    idx = list(range(len(texts)))
    for lr in (3e-4, 1e-3):
        for wd in (1e-2, 3e-2):
            delta = torch.nn.Parameter(torch.zeros(512, 512, device=device))
            opt = torch.optim.AdamW([delta], lr=lr, weight_decay=wd)
            local_best, local_delta = best_val, None
            for ep in range(1, a.epochs + 1):
                random.shuffle(idx)
                for s in range(0, len(idx), 512):
                    b = idx[s : s + 512]
                    if len(b) < 8:
                        continue
                    bt = torch.tensor(b, device=device)
                    Tt = F.normalize(T[bt] @ (Ieye + delta), dim=-1)
                    Ii = ti[rows[bt]]
                    logits = scale * Tt @ Ii.T
                    lab = torch.arange(len(b), device=device)
                    loss = 0.5 * (F.cross_entropy(logits, lab) + F.cross_entropy(logits.T, lab))
                    opt.zero_grad()
                    loss.backward()
                    opt.step()
                if ep % 15 == 0:
                    vr = val_r1(delta.detach())
                    if vr > local_best:
                        local_best, local_delta = vr, delta.detach().cpu().clone()
            print(f"  lr={lr:g} wd={wd:g}: best val R@1 {local_best:.3f}", flush=True)
            if local_delta is not None and local_best > best_val:
                best_val, best_delta, best_hp = local_best, local_delta, (lr, wd)

    if best_hp is None:
        print("no config beat stock on val — writing stock (identity) checkpoint.", flush=True)

    # Fold Δ into text_projection: tp <- tp @ (I + Δ). Exact because encode_text
    # ends in `x @ text_projection` with nothing after it.
    with torch.no_grad():
        tp = model.text.text_projection  # [512,512], applied as x @ tp
        model.text.text_projection.copy_(tp @ (torch.eye(512) + best_delta).to(tp.device, tp.dtype))
    torch.save({"model": model.state_dict(), "best_val_r1": best_val, "hparams": best_hp},
               a.out)
    print(f"folded Δ into text_projection; saved merged checkpoint (val R@1 {best_val:.3f}, hp {best_hp}) -> {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
