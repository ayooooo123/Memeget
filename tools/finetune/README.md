# Meme fine-tune of MobileCLIP-S2 (local corpus)

Realizes the fine-tune thread of `docs/memedepot-finetune.md` using a **local**
basedmemes.lol + KnowYourMeme corpus (the memedepot crawl the doc assumed is
egress-blocked; this corpus is the same shape — `(image, human tags)` — and stays
on-disk, never shipped). Trains on Apple **MPS** (M1); no cloud, no accounts.

## What ships
A residual text→image adapter `W = I + Δ` folded into the text tower's
`text.text_projection`:

    text_projection <- text_projection @ (I + Δ)

MobileCLIP-S2's text tower ends in `x @ text_projection` (no bias/norm after), so
this is exact — `encode_text` then already includes the adaptation and the app
L2-normalizes as usual. **The image tower is untouched**, so image space can't
drift and stored image vectors stay valid. Ship it like any custom export: point
`EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE` at the re-exported text
`.pte` and bump `…_MODEL_ID` (image source unchanged). No app code change.

## Result — measured through the app's OWN eval harness (`npm run eval`)
595-meme eval holdout, provably disjoint from training (`dataset.is_eval`,
hash bucket 0/20). Folded drop-in checkpoint vs stock MobileCLIP-S2:

| metric              | stock | fine-tuned | Δ     |
|---------------------|-------|-----------|-------|
| retrieval Recall@1  | 47.9% | 49.6%     | +1.7  |
| retrieval Recall@5  | 66.2% | 71.3%     | +5.1  |
| retrieval Recall@10 | 72.4% | 78.7%     | +6.3  |
| retrieval MRR       | 0.563 | 0.593     | +0.030|
| tagging top-1       | 49.4% | 51.3%     | +1.9  |
| tagging top-3       | 60.8% | 64.5%     | +3.7  |
| aspect MAP          | 0.159 | 0.207     | +4.8  |

Guardrails held: higher learning rates collapsed back to identity (early-stop on
a disjoint train-internal val kept the no-op), so only the config that genuinely
generalized was accepted.

## Files
- `dataset.py` — load/merge the corpus, hash split (train / eval holdout).
- `clipmodel.py` — MobileCLIP-S2 load (stock or `--ckpt`) + batched MPS embedding.
- `textviews.py` — tag→text views (shared by trainer + golden builder, no drift).
- `finetune.py` — train Δ on RAW (pre-norm) text features, fold into
  `text_projection`, save merged state_dict.
- `../eval/build_golden_local.py` — build `tools/eval/golden.json` (stock or `--ckpt`).

## Reproduce
```bash
python3 tools/finetune/finetune.py --train-size 6000        # -> tools/finetune/mobileclip_s2_memeft.pt
python3 tools/eval/build_golden_local.py --out tools/eval/golden.json                                    # stock golden
npm run eval                                                                                              # baseline
python3 tools/eval/build_golden_local.py --ckpt tools/finetune/mobileclip_s2_memeft.pt --out tools/eval/golden.json
npm run eval                                                                                              # fine-tuned
```
Deps: `torch open_clip_torch timm pillow` (torch uses MPS on Apple Silicon).
The merged `.pt` (~full model, hundreds of MB) is git-ignored — regenerate it.

## Owed before bumping the shipped model
- **Export to `.pte`** via `tools/model-export` + `export-models.yml` (adapt the
  export to load this merged checkpoint). Image `.pte` is unchanged.
- **Catastrophic-forgetting guard** (doc's non-negotiable): confirm no regression
  on generic, non-meme `(image, text)` queries before shipping. A residual linear
  text nudge with the image tower frozen is low-risk, but the guard is not yet run
  (needs a small generic image/text set — not in this local corpus).
- A new `…_MODEL_ID` forces a full library re-embed and invalidates teaching packs
  (`isTeachingPackCompatible`) — communicate + regenerate first-party packs.

## RL / preference learning (later)
True RL needs a reward signal. The app's implicit feedback — manual tags kept,
memes not deleted, teaching-pack exemplars — is a real substrate for a reranker /
preference model. A genuine follow-on, not attempted here.
