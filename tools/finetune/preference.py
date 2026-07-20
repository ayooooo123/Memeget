"""RL / preference optimization of the meme retriever — DPO on the text adapter.

This is the "RL stuff": Direct Preference Optimization (Rafailov et al.), the
closed-form solution to RLHF's KL-regularized reward objective. We optimize the
same drop-in residual text adapter (W = I + Δ, folds into text_projection) but
with a PREFERENCE objective instead of plain InfoNCE.

Reward signal (no human labels needed): HARD-NEGATIVE preferences mined from the
model's own behavior. For a query q = a meme's tag caption:
    preferred    m+  = that meme's image (the correct answer)
    dispreferred m-  = the image the STOCK model ranks highest but is WRONG
i.e. "prefer the right meme over the one you were confidently, wrongly retrieving."
That is a reward model over (q, image) pairs; DPO turns those preferences into a
policy update.

DPO loss with the STOCK model as the frozen reference policy:
    L = -log σ( β·[ (s_θ(q,m+) - s_θ(q,m-)) - (s_ref(q,m+) - s_ref(q,m-)) ] )
The reference term is a built-in KL anchor to stock — the same mechanism that
guards against catastrophic forgetting (kept alongside a light COCO anchor).

s(q,m) = normalize(rawtext(q)·(I+Δ)) · imageVec(m). Everything trains on cached
frozen features (image tower frozen), so it's fast on MPS.

Output: merged drop-in checkpoint at --out (loadable via clipmodel.load).
Eval holdout (dataset.is_eval) never seen.
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
from finetune import load_coco_anchors, raw_text  # noqa: E402
from textviews import primary_caption  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--extra-dir", action="append", default=[], help="additional source dir(s), e.g. the memedepot corpus")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memedpo.pt"))
    ap.add_argument("--train-size", type=int, default=8000)
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--beta", type=float, default=0.5, help="DPO temperature")
    ap.add_argument("--anchor-lambda", type=float, default=4.0)
    ap.add_argument("--anchor-coco", type=int, default=3000)
    ap.add_argument("--anchor-skip", type=int, default=500)
    ap.add_argument("--remine-every", type=int, default=0, help="re-mine hard negatives every N epochs (0=offline/fixed)")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    random.seed(a.seed)
    torch.manual_seed(a.seed)

    recs = dataset.load_records(a.data_dir, extra_dirs=a.extra_dir)
    train, held = dataset.split(recs)
    random.shuffle(train)
    if a.train_size:
        train = train[: a.train_size]
    n_val = max(64, len(train) // 10)
    val, train = train[:n_val], train[n_val:]
    print(f"train {len(train)}  val {len(val)}  (holdout {len(held)} untouched; sources: base+{len(a.extra_dir)} extra)", flush=True)

    model, pre, tok, dev = clipmodel.load()
    for p in model.parameters():
        p.requires_grad_(False)
    print(f"MobileCLIP-S2 on {dev}", flush=True)

    print("precomputing frozen image + raw text features…", flush=True)
    ti, ok = clipmodel.embed_images(model, pre, dev, [r.path for r in train])
    train = [r for r, k in zip(train, ok) if k]
    I = torch.from_numpy(ti[ok]).to(dev)                         # [N,512] normalized image feats
    Traw = torch.from_numpy(raw_text(model, tok, dev, [primary_caption(r.tags) for r in train])).to(dev)  # [N,512] raw
    vi, vok = clipmodel.embed_images(model, pre, dev, [r.path for r in val])
    val = [r for r, k in zip(val, vok) if k]
    VI = torch.from_numpy(vi[vok]).to(dev)
    VTraw = torch.from_numpy(raw_text(model, tok, dev, [primary_caption(r.tags) for r in val])).to(dev)
    N = len(train)

    Ieye = torch.eye(512, device=dev)
    Ts = F.normalize(Traw, dim=-1)  # stock (reference) normalized text

    def mine(Wtext_normed):
        """hard negative = highest-scoring WRONG image for each query."""
        S = Wtext_normed @ I.T          # [N,N]
        S.fill_diagonal_(-1e4)
        return S.argmax(1)              # [N]

    with torch.no_grad():
        neg = mine(Ts)                                   # offline mine against stock
        sref_pos = (Ts * I).sum(1)                       # s_ref(q, m+)
        sref_neg = (Ts * I[neg]).sum(1)                  # s_ref(q, m-)

    # COCO anchor (generic preservation), same as finetune.py
    anchor = load_coco_anchors(a.anchor_coco, a.anchor_skip) if a.anchor_coco else []
    if anchor:
        A_raw = torch.from_numpy(raw_text(model, tok, dev, anchor)).to(dev)
        A_stock = F.normalize(A_raw, dim=-1)
        print(f"anchoring on {len(anchor)} COCO captions", flush=True)

    def val_r1(delta):
        s = F.normalize(VTraw @ (Ieye + delta), dim=-1) @ VI.T
        return float((s.argmax(1).cpu().numpy() == np.arange(len(val))).mean())

    best, best_delta, best_hp = val_r1(torch.zeros(512, 512, device=dev)), torch.zeros(512, 512), None
    print(f"stock val R@1 {best:.3f}", flush=True)
    idx = list(range(N))
    for lr in (3e-4, 1e-3):
        delta = torch.nn.Parameter(torch.zeros(512, 512, device=dev))
        opt = torch.optim.AdamW([delta], lr=lr, weight_decay=1e-2)
        local_best, local_delta = best, None
        cur_neg, cur_sref_neg = neg, sref_neg
        for ep in range(1, a.epochs + 1):
            if a.remine_every and ep % a.remine_every == 1 and ep > 1:
                with torch.no_grad():
                    Wn = F.normalize(Traw @ (Ieye + delta), dim=-1)
                    cur_neg = mine(Wn)
                    cur_sref_neg = (Ts * I[cur_neg]).sum(1)
            random.shuffle(idx)
            for s in range(0, N, 512):
                b = torch.tensor(idx[s : s + 512], device=dev)
                if len(b) < 8:
                    continue
                Tt = F.normalize(Traw[b] @ (Ieye + delta), dim=-1)
                s_pos = (Tt * I[b]).sum(1)
                s_neg = (Tt * I[cur_neg[b]]).sum(1)
                logits = a.beta * ((s_pos - s_neg) - (sref_pos[b] - cur_sref_neg[b]))
                loss = -F.logsigmoid(logits).mean()
                if anchor:
                    loss = loss + a.anchor_lambda * (1 - (F.normalize(A_raw @ (Ieye + delta), dim=-1) * A_stock).sum(1)).mean()
                opt.zero_grad()
                loss.backward()
                opt.step()
            if ep % 15 == 0:
                vr = val_r1(delta.detach())
                if vr > local_best:
                    local_best, local_delta = vr, delta.detach().cpu().clone()
        print(f"  lr={lr:g} beta={a.beta}: best val R@1 {local_best:.3f}", flush=True)
        if local_delta is not None and local_best > best:
            best, best_delta, best_hp = local_best, local_delta, (lr, a.beta)

    print(f"selected hp {best_hp}  val R@1 {best:.3f}", flush=True)
    with torch.no_grad():
        tp = model.text.text_projection
        model.text.text_projection.copy_(tp @ (torch.eye(512) + best_delta).to(tp.device, tp.dtype))
    torch.save({"model": model.state_dict(), "best_val_r1": best, "hparams": best_hp, "method": "dpo"}, a.out)
    print(f"folded Δ into text_projection; saved DPO checkpoint -> {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
