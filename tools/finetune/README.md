# Meme fine-tune of MobileCLIP-S2 (local corpus)

Realizes the fine-tune thread of `docs/memedepot-finetune.md` on a **local**
basedmemes.lol + KnowYourMeme corpus (~11.3k image/tag memes; memedepot's own
collections are egress-blocked from this box). Trains on Apple **MPS** (M1); no
cloud, corpus never leaves disk.

## What ships
A residual text→image adapter `W = I + Δ` folded into the text tower's
`text.text_projection` (`text_projection <- text_projection @ (I+Δ)`). Exact,
because MobileCLIP-S2's text tower ends in `x @ text_projection` with nothing
after — so `encode_text` already includes the adaptation and the app L2-normalizes
as usual. **Image tower untouched** (image space can't drift, stored image vectors
stay valid). Ship like any custom export: point
`EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE` at the re-exported text
`.pte`, bump `…_MODEL_ID`. No app code change.

**Anchor term (real COCO captions).** A global linear Δ nudges ALL text, which
regressed generic queries (a text-drift proxy missed this; the real image guard
below caught a −6pt hit). Training adds `λ·(1 − cos(adapted_generic, stock_generic))`
over 3k **real COCO captions** (disjoint from the guard set) so InfoNCE reshapes
meme text while generic text stays pinned. `λ` trades meme gain vs generic
preservation (default 8.0).

## Result — through the app's OWN eval harness (`npm run eval`)
595-meme eval holdout, disjoint from training (hash bucket 0/20). Folded drop-in
checkpoint (λ=8, COCO-anchored) vs stock MobileCLIP-S2:

| metric              | stock | fine-tuned | Δ     |
|---------------------|-------|-----------|-------|
| retrieval Recall@1  | 47.9% | 52.8%     | +4.9  |
| retrieval Recall@5  | 66.2% | 72.8%     | +6.6  |
| retrieval Recall@10 | 72.4% | 79.7%     | +7.3  |
| retrieval MRR       | 0.563 | 0.621     | +0.058|
| tagging top-1       | 49.4% | 52.9%     | +3.5  |
| aspect MAP          | 0.159 | 0.219     | +6.0  |

## Forgetting guards
Two checks (the image tower is frozen, so forgetting is a text-side question):

1. `forgetting_guard.py` — text-drift on 89 generic prompts: generic drift-cosine
   0.93 (vs 0.87 unanchored); meme text moves far more (0.58) ⇒ selective. Fast proxy.
2. `forgetting_coco.py` — **definitive**: generic COCO caption→image retrieval over
   500 real photos (from HF, the only reachable image source), stock vs ft:

   | | stock | ft | Δ |
   |---|---|---|---|
   | generic R@1 | 74.7% | 72.0% | −2.7 |
   | generic R@5 | 91.3% | 91.3% | **0.0** |
   | generic MRR | 0.814 | 0.803 | −0.011 |

**Status:** generic R@5 preserved exactly and MRR ~flat; generic R@1 −2.7 (≈4 of
150 queries) marginally exceeds the strict 2pt gate. Effectively at-parity generic
with strong meme gains. Raise `--anchor-lambda` to push generic R@1 under the gate
at some meme cost (the tunable knob); the linear form has a real meme/generic
Pareto frontier — a low-rank or meme-subspace-gated Δ is the next step to clear it
outright.

## Files
- `dataset.py` — multi-source corpus loader (basedmemes + KYM now; drop a
  memedepot/other export into an `extra_dirs` dir as `collection.json` to add it),
  hash split (train / eval holdout).
- `clipmodel.py` — MobileCLIP-S2 load (stock or `--ckpt`) + batched MPS embedding.
- `textviews.py` — tag→text views (shared by trainer + golden builder).
- `finetune.py` — train Δ on RAW text features with COCO anchoring, fold into
  `text_projection`, save merged state_dict.
- `forgetting_guard.py` / `forgetting_coco.py` — the two forgetting checks.
- `../eval/build_golden_local.py` — build `tools/eval/golden.json` (stock or `--ckpt`).

## Reproduce
```bash
python3 tools/finetune/finetune.py --train-size 6000              # COCO-anchored, λ=8 -> mobileclip_s2_memeft.pt
python3 tools/finetune/forgetting_coco.py                         # generic retrieval guard
python3 tools/eval/build_golden_local.py --out tools/eval/golden.json && npm run eval                          # stock
python3 tools/eval/build_golden_local.py --ckpt tools/finetune/mobileclip_s2_memeft.pt --out tools/eval/golden.json && npm run eval   # fine-tuned
```
Deps: `torch open_clip_torch timm pillow datasets` (torch uses MPS on Apple Silicon).
The merged `.pt` (~400 MB) is git-ignored — regenerate it.

## Data sources
- **basedmemes.lol** + **KnowYourMeme** — local images+tags, driving the fine-tune.
- **memedepot** — locally only the tag *vocabulary*; its `(image, tag)` collections
  are Cloudflare/egress-blocked here. Fold in via the CI harvest, or drop a local
  export as `collection.json` in a dir passed to `load_records(extra_dirs=[…])`.
- **COCO** (HuggingFace) — generic captions/images for the anchor term + forgetting guard.

## Owed before bumping the shipped model
- **Export to `.pte`** via `tools/model-export` + `export-models.yml` (load this
  merged checkpoint; image `.pte` unchanged).
- Clear the generic R@1 gate outright (raise λ, or low-rank/meme-subspace Δ).
- A new `…_MODEL_ID` forces a full re-embed + invalidates teaching packs — communicate first.

## RL / preference learning (later)
True RL needs a reward signal. The app's implicit feedback — manual tags kept,
memes not deleted, teaching-pack exemplars — is a real substrate for a reranker /
preference model. A genuine follow-on, not attempted here.
