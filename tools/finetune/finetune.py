"""Fine-tune MobileCLIP-S2 for meme retrieval and produce a DROP-IN checkpoint.

Method (docs/memedepot-finetune.md, safe path): freeze both towers, learn a
residual linear map W = I + Δ on the TEXT side against precomputed frozen image
features (symmetric InfoNCE). MobileCLIP-S2's text tower ends in
`x @ text.text_projection` (no bias/norm after), so the residual FOLDS EXACTLY:

    text_projection <- text_projection @ (I + Δ)

=> a plain re-export, no app change; the image tower is untouched (image space
can't drift, stored image vectors stay valid).

ANCHOR TERM (the doc's "distillation/anchor so general queries don't break"): a
global linear Δ would nudge ALL text, regressing generic non-meme queries. We add
`λ · (1 - cos(adapted_generic, stock_generic))` over a pool of generic captions,
so InfoNCE reshapes meme text while generic text is pinned near stock. The
forgetting guard (forgetting_guard.py) validates this on a DISJOINT generic set.

Guards: eval holdout (dataset.is_eval) never seen; train-internal val early-stop;
residual init 0; small lr/wd grid, best val kept.
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

# Generic anchor captions to PIN near stock (kept DISJOINT from forgetting_guard's
# generic set so a guard pass generalizes). Template x noun over everyday concepts.
_A_TMPL = ["a photo of {}", "{} in the background", "a picture of {}", "{} on display", "{} outdoors"]
_A_NOUN = [
    "a wooden fence", "a coffee mug", "a mountain trail", "a river delta", "a office desk",
    "a park bench", "a street lamp", "a ceramic bowl", "a leather bag", "a denim jacket",
    "a bowl of cereal", "a cutting board", "a garden hose", "a picket gate", "a stone wall",
    "a tractor", "a fishing boat", "a hot air balloon", "a ferris wheel", "a windmill",
    "a chalkboard", "a microscope", "a telescope", "a keyboard", "a computer mouse",
    "a running shoe", "a wool sweater", "a straw hat", "a silk scarf", "a rubber duck",
    "a maple tree", "a cactus", "a sunflower", "a fern", "a rose bush",
    "a panda", "a penguin", "a giraffe", "a koala", "a dolphin",
    "a ripe tomato", "a wedge of cheese", "a bowl of soup", "a stack of pancakes", "a fruit basket",
    "a snowy village", "a coastal cliff", "a wheat field", "a pine forest", "a canyon",
    "a fire truck", "a delivery van", "a cargo ship", "a helicopter", "a scooter",
]
ANCHOR = [t.format(n) for n in _A_NOUN for t in _A_TMPL]  # ~275 generic captions


@torch.no_grad()
def raw_text(model, tok, device, texts, batch=256):
    out = np.zeros((len(texts), 512), np.float32)
    for i in range(0, len(texts), batch):
        c = texts[i : i + batch]
        out[i : i + len(c)] = model.encode_text(tok(c).to(device)).float().cpu().numpy()
    return out


def load_coco_anchors(n: int, skip: int) -> list[str]:
    """Real generic captions to pin near stock. Uses the cached COCO val set
    (HF-reachable); returns [] if unavailable so training still runs on templates.
    Skips the first `skip` captions so the forgetting-guard query set stays disjoint."""
    try:
        import warnings as _w
        _w.filterwarnings("ignore")
        from datasets import load_dataset
    except Exception:
        return []
    try:
        ds = load_dataset("sayakpaul/coco-30-val-2014", split="train", streaming=True)
    except Exception:
        return []
    out, seen = [], 0
    for ex in ds:
        c = ex.get("caption")
        if isinstance(c, list):
            c = c[0] if c else ""
        c = str(c or "").strip()
        if not c:
            continue
        seen += 1
        if seen <= skip:
            continue
        out.append(c)
        if len(out) >= n:
            break
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memeft.pt"))
    ap.add_argument("--train-size", type=int, default=6000)
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--anchor-lambda", type=float, default=8.0)
    ap.add_argument("--anchor-coco", type=int, default=3000, help="real COCO captions to anchor on (0=templated only)")
    ap.add_argument("--anchor-skip", type=int, default=500, help="skip first N COCO captions (keep guard set disjoint)")
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
    print(f"train {len(train)}  val {len(val)}  anchors {len(ANCHOR)}  (eval holdout {len(held)} untouched)", flush=True)

    model, pre, tok, device = clipmodel.load()
    for p in model.parameters():
        p.requires_grad_(False)
    print(f"MobileCLIP-S2 on {device}", flush=True)

    print("precomputing frozen image + raw/stock text features…", flush=True)
    ti, tok_ok = clipmodel.embed_images(model, pre, device, [r.path for r in train])
    train = [r for r, k in zip(train, tok_ok) if k]
    ti = torch.from_numpy(ti[tok_ok]).to(device)
    vi, v_ok = clipmodel.embed_images(model, pre, device, [r.path for r in val])
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

    anchor_prompts = list(ANCHOR)
    if a.anchor_coco:
        coco = load_coco_anchors(a.anchor_coco, a.anchor_skip)
        if coco:
            anchor_prompts = coco  # real generic captions >> templates for preserving generic
            print(f"anchoring on {len(coco)} real COCO captions (skip {a.anchor_skip})", flush=True)
    A_raw = torch.from_numpy(raw_text(model, tok, device, anchor_prompts)).to(device)  # RAW anchor
    A_stock = F.normalize(A_raw, dim=-1)  # stock target (identity Δ)
    print(f"train pairs {len(texts)} over {len(train)} images", flush=True)

    Ieye = torch.eye(512, device=device)
    scale = model.logit_scale.exp().clamp(max=100).detach()

    def val_r1(delta):
        s = F.normalize(vT @ (Ieye + delta), dim=-1) @ vi.T
        return float((s.argmax(1).cpu().numpy() == np.arange(len(val))).mean())

    def anchor_cos(delta):
        delta = delta.to(A_raw.device)
        return float((F.normalize(A_raw @ (Ieye + delta), dim=-1) * A_stock).sum(1).mean())

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
                    logits = scale * Tt @ ti[rows[bt]].T
                    lab = torch.arange(len(b), device=device)
                    info = 0.5 * (F.cross_entropy(logits, lab) + F.cross_entropy(logits.T, lab))
                    anchor = (1 - (F.normalize(A_raw @ (Ieye + delta), dim=-1) * A_stock).sum(1)).mean()
                    loss = info + a.anchor_lambda * anchor
                    opt.zero_grad()
                    loss.backward()
                    opt.step()
                if ep % 15 == 0:
                    vr = val_r1(delta.detach())
                    if vr > local_best:
                        local_best, local_delta = vr, delta.detach().cpu().clone()
            ac = anchor_cos(local_delta if local_delta is not None else torch.zeros(512, 512, device=device))
            print(f"  lr={lr:g} wd={wd:g}: best val R@1 {local_best:.3f}  anchor-cos {ac:.3f}", flush=True)
            if local_delta is not None and local_best > best_val:
                best_val, best_delta, best_hp = local_best, local_delta, (lr, wd)

    if best_hp is None:
        print("no config beat stock on val — writing stock (identity) checkpoint.", flush=True)
    print(f"selected hp {best_hp}  val R@1 {best_val:.3f}  anchor-cos {anchor_cos(best_delta.to(device)):.3f}", flush=True)

    with torch.no_grad():
        tp = model.text.text_projection  # [512,512], applied as x @ tp
        model.text.text_projection.copy_(tp @ (torch.eye(512) + best_delta).to(tp.device, tp.dtype))
    torch.save({"model": model.state_dict(), "best_val_r1": best_val, "hparams": best_hp}, a.out)
    print(f"folded Δ into text_projection; saved -> {a.out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
