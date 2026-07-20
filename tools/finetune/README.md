# Meme fine-tune of MobileCLIP-S2 (local, 3-source corpus) + RL

Realizes the fine-tune thread of `docs/memedepot-finetune.md` on a **local**
3-source corpus — **basedmemes.lol + KnowYourMeme + memedepot** — trained on Apple
**MPS** (M1); no cloud. The image corpus lives outside the repo (large binaries), not in git.

## What ships
A residual text→image adapter `W = I + Δ` folded into `text.text_projection`
(`text_projection <- text_projection @ (I+Δ)`, exact — the text tower ends in
`x @ text_projection`). **Image tower untouched** (image space fixed, stored
vectors valid). Drop-in re-export: point
`EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE` at the re-exported text
`.pte`, bump `…_MODEL_ID`. No app code change.

Trained with symmetric InfoNCE + a **COCO anchor term** (`λ·(1 − cos(adapted,
stock))` over real COCO captions) that keeps generic queries near stock. `λ`
trades meme gain vs generic preservation (16 for the 3-source corpus).

## Result — through the app's OWN eval harness (`npm run eval`)
595-meme eval holdout (basedmemes+KYM, hash bucket 0/20), disjoint from training.
Final model (3 sources, λ=16) vs stock MobileCLIP-S2:

| metric              | stock | fine-tuned | Δ     |
|---------------------|-------|-----------|-------|
| retrieval Recall@1  | 47.9% | 52.4%     | +4.5  |
| retrieval Recall@5  | 66.2% | 73.3%     | +7.1  |
| retrieval Recall@10 | 72.4% | 79.3%     | +6.9  |
| retrieval MRR       | 0.563 | 0.621     | +0.058|
| tagging top-1       | 49.4% | 51.3%     | +1.9  |
| aspect MAP          | 0.159 | 0.237     | +7.8  |

memedepot's rich AI labels notably lifted **aspect MAP** (single-word findability).

## Forgetting guards
`forgetting_coco.py` — definitive generic COCO caption→image retrieval, stock vs
ft (image tower frozen ⇒ forgetting is a text-side question):

| | stock | ft | Δ |
|---|---|---|---|
| generic R@1  | 74.7% | 72.7% | −2.0 |
| generic R@5  | 91.3% | 92.0% | **+0.7** |
| generic MRR  | 0.814 | 0.806 | −0.008 |

Generic R@5 *improved* and MRR is flat; R@1 −2.0 sits on the strict 2pt gate
(~3 of 150 queries) — effectively at-parity. `forgetting_guard.py` is a fast
text-drift proxy. There's a real meme/generic Pareto knob (`--anchor-lambda`);
2-source λ=8 gave generic R@1 −2.7 with R@5 +6.6, 3-source λ=16 gives generic R@1
−2.0 with R@5 +7.1. A low-rank / meme-subspace Δ is the path to clear R@1 outright.

## RL / preference optimization (`preference.py`)
The "RL stuff": **DPO** (Direct Preference Optimization — the closed-form solution
to RLHF's KL-regularized reward objective) on the same adapter.
- **Reward** (no human labels): HARD-NEGATIVE preferences mined from the model's
  own behavior — for query q (a meme's caption), prefer the correct image over the
  image the STOCK model ranks highest-but-wrong. A reward model over (q, image).
- **Reference policy** = frozen stock model (DPO's KL term = built-in anti-forgetting).
- Optionally online-re-mines negatives (`--remine-every`) for a more on-policy loop.

**Honest result: DPO did not beat the InfoNCE fine-tune on retrieval.** Single-
hard-negative pairwise preferences whack-a-mole (fixing one negative promotes
another), so full-contrastive InfoNCE — which corrects against *all* negatives at
once — wins for this task. The guardrail correctly kept the identity map rather
than shipping a non-improvement (no false gain). Productive RL here needs a
stronger/real reward signal (human or AI-judge preferences, e.g. from memedepot's
`ai_description`), or hard-negative-mined contrastive rather than pairwise DPO.

## Files
- `dataset.py` — multi-source loader (`extra_dirs` + `collection.json`), hash split.
- `clipmodel.py` — MobileCLIP-S2 load (stock/`--ckpt`) + MPS embedding.
- `textviews.py` — tag→text views.
- `finetune.py` — InfoNCE + COCO-anchor trainer; `--extra-dir` for extra sources.
- `preference.py` — DPO/RL trainer (hard-neg reward, stock reference).
- `forgetting_guard.py` / `forgetting_coco.py` — forgetting checks.
- `../memedepot/harvest_corpus.py` — harvest memedepot (image, AI-labels) → a
  `collection.json` source dir.
- `../eval/build_golden_local.py` — build `tools/eval/golden.json` (stock/`--ckpt`).

## Reproduce
```bash
# 3rd source (writes ~/projects/basedmemes_archive/memedepot/{images_only,collection.json})
python3 tools/memedepot/harvest_corpus.py --depots 80 --per-depot 40 --max-images 2500
MD=~/projects/basedmemes_archive/memedepot
python3 tools/finetune/finetune.py --extra-dir $MD --train-size 8000 --anchor-lambda 16
python3 tools/finetune/forgetting_coco.py
python3 tools/eval/build_golden_local.py --out tools/eval/golden.json && npm run eval          # stock
python3 tools/eval/build_golden_local.py --ckpt tools/finetune/mobileclip_s2_memeft.pt --out tools/eval/golden.json && npm run eval  # ft
# RL (preference) variant:
python3 tools/finetune/preference.py --extra-dir $MD --train-size 8000 --beta 1.0 --anchor-coco 0 --remine-every 40
```
Deps: `torch open_clip_torch timm pillow datasets`. Merged `.pt` (~400 MB) git-ignored.

## Export to .pte (drop-in ExecuTorch text encoder) — DONE
`tools/model-export/export_mobileclip_s2.py` takes `--ckpt` (the merged fine-tune)
and `--text-only`; it bakes normalization, exports the text tower via XNNPACK
(fp32), and verifies the on-device output against the eager model:
```bash
# needs a py3.10+ venv: pip install "executorch==1.0.0" "torch==2.9.*" open_clip_torch timm transformers tokenizers
#   and a matching flatc on PATH / $FLATC_EXECUTABLE (brew install flatbuffers)
FLATC_EXECUTABLE=$(which flatc) python tools/model-export/export_mobileclip_s2.py \
  --ckpt tools/finetune/mobileclip_s2_memeft.pt --text-only --out-dir dist-memeft
```
Verified locally: `mobileclip_s2_text_xnnpack_fp32.pte` (243 MB), output (1,512),
**cos(fp32)=1.0000** — the `.pte` is identical to the fine-tuned eager tower, so it
carries the full retrieval gain. Ships via the `models-v1` release (git-ignored,
not committed); the image `.pte` is unchanged from stock.

## Owed before bumping the shipped model
- Publish the `.pte` to the `models-v1` release + point `…_TEXT_MODEL_SOURCE` at it,
  bump `…_MODEL_ID` (forces a full re-embed + invalidates teaching packs — communicate first).
- Clear generic R@1 gate outright (low-rank/subspace Δ) if strict parity is required.
