# Fine-tuning an on-device meme encoder on the memedepot corpus

Status: **design / proposal.** The ambitious follow-on to
[`memedepot-corpus.md`](./memedepot-corpus.md). Do **not** start this before the
eval harness in that doc exists — without it you cannot tell whether a fine-tune
helped, and a meme fine-tune is very easy to *silently* make worse (see
Risks). Label mining + eval are the high-ROI work; this is the shiny object.

## Goal

Memeget's semantic search runs on **CLIP ViT-B/32** (`PRIMARY_EMBEDDING_MODEL`
in `src/embeddingModels.ts`, `dim: 512`), exported to ExecuTorch and run on-phone
via `react-native-executorch`. Off-the-shelf CLIP is trained on generic web
image/text — it's mediocre at internet-native meme semantics ("gigachad",
"distracted boyfriend", template variants, greentext). The hypothesis: a
**contrastive fine-tune on `(meme image, human caption/tags)` pairs from the
corpus** shifts the joint embedding space so meme *descriptions* land near the
right meme, improving Recall@k on the eval harness.

The result is a drop-in replacement encoder shipped exactly like the existing
custom exports — the app already supports swapping the primary model via
`EXPO_PUBLIC_MEMEGET_*` env vars, so **no app code changes are required** to try
one; only a new `.pte` and an env pointer.

## Why this composes with what's already here

The repo already has the whole "custom model → ExecuTorch → ship" machine:

- `src/embeddingModels.ts` — `primaryEmbeddingModelFromEnv()` reads
  `EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE` /
  `…_TEXT_MODEL_SOURCE` / `…_TOKENIZER_SOURCE` and, if set, uses that pair as the
  primary space instead of stock CLIP. A fine-tuned CLIP fits this slot directly
  (same 512-dim image/text contract).
- `tools/model-export/export_mobileclip_s2.py` — the export template + the
  **interface contract** the runtime requires (documented in that dir's README
  and `docs/embedding-roadmap.md`):
  - image: `[1,3,H,W]` float32 RGB in `[0,1]`, **normalization baked into the
    graph**, no runtime mean/std;
  - text: `(tokenIds int64 [1,77], attentionMask int64 [1,77])` → pooled
    embedding; tokenizer pads/truncates to exactly 77;
  - outputs are raw embeddings; **the app L2-normalizes in JS**.
- `.github/workflows/export-models.yml` publishes `.pte` assets to the
  `models-v1` GitHub release; the APK build points the env vars at those assets.

So the fine-tune's job is narrow: **produce fine-tuned CLIP image+text weights
that satisfy that contract**, hand them to a (lightly adapted) export script, and
let the existing pipeline ship them.

## Data

From `corpus.jsonl` (see corpus doc), build training pairs:

- **Positive pair** = (meme image, text) where text is drawn from
  `item_title` / `item_tags` / `depot_title` / `depot_tags`. Build several text
  views per image (title alone; tags joined; "a <depot_title> meme") for
  augmentation.
- **In-batch negatives** = every other image's text in the batch (standard
  CLIP/InfoNCE). No hard-negative mining needed for a first pass.
- **Hygiene:** dedupe by `media_sha256` and near-duplicate hash so one viral
  template doesn't dominate; drop pairs with empty/garbage text; hold out a
  **disjoint** slice by `depot_slug` for validation so you're not testing on
  memformats you trained on. The eval golden set (corpus doc §3) must be
  **fully disjoint** from training — it's the real accept gate.
- Images: for videos, train on the poster/first distinct frame (matches how the
  app derives a video's primary embedding).

Target size for a first experiment: a few thousand to low-tens-of-thousands of
pairs. LoRA on CLIP converges fast and overfits fast; more data > more epochs.

## Method (Google Colab free tier)

Colab's free T4 (16 GB) is plenty for CLIP ViT-B/32. Use
[`open_clip`](https://github.com/mlfoundations/open_clip), whose ViT-B/32
matches the architecture the export scripts already target.

1. **Load** `open_clip` ViT-B/32 (the checkpoint corresponding to the runtime's
   CLIP; verify embedding parity against the current on-device model on a few
   images before training — offline vectors must match production).
2. **LoRA-adapt** attention projections in both towers (via `peft` or manual
   low-rank adapters). LoRA over full fine-tune because: tiny trainable set fits
   the free tier, far less catastrophic forgetting of general vision, and the
   adapter merges back into dense weights for export (no runtime LoRA needed).
3. **Objective:** symmetric InfoNCE (image→text + text→image) with a learned/
   fixed temperature — stock CLIP loss.
4. **Regularize hard:** low LR (~1e-5–5e-5 on adapters), 1–3 epochs, early-stop
   on the held-out depots, weight-decay, and a **distillation/anchor term** or a
   frozen copy so the space doesn't drift so far that general queries break.
5. **Checkpoint** the merged (LoRA-folded) image + text encoders in the layout
   the export script expects.

Colab caveats: free sessions are preemptible and time-boxed — checkpoint to
Drive every few hundred steps and make the notebook resumable. An A100 (Colab
Pro) shortens iteration but isn't required.

Deliverable: `tools/memedepot/finetune.ipynb` (or a `.py` + `README`) that,
given `corpus.jsonl` + image cache, outputs merged encoder weights.

## Export & wiring (reuse `tools/model-export`)

1. Adapt `export_mobileclip_s2.py` (or add `export_clip_finetuned.py`) to load
   the fine-tuned weights and export image + text `.pte` honoring the contract
   above — **bake normalization into the image graph, pad tokens to 77, emit raw
   (un-normalized) embeddings.** Keep `dim = 512` so nothing downstream shifts.
2. Sanity-check on-device load (a program/version load error means the
   ExecuTorch pip version outran the bundled runtime — lower `ET_VERSION` in the
   workflow, per the model-export README).
3. Point the env at the new assets:
   `EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE`,
   `…_TEXT_MODEL_SOURCE`, `…_TOKENIZER_SOURCE`, and a fresh `…_MODEL_ID`
   (e.g. `clip-memedepot-ft-v1`). `primaryEmbeddingModelFromEnv()` picks it up;
   no app code changes.

### Two consequences you must handle (the model identity changed)

The primary vector space moving is not free — `src/embeddingModels.ts` and the
teaching-pack code treat model identity as load-bearing:

- **Full re-index.** Stored image/caption embeddings are in the *old* space;
  mixing spaces makes cosines meaningless. A new `…_MODEL_ID` must trigger a
  re-embed of the library (the app already keys stored vectors to a model; the
  changeover needs a migration/backfill path, like the DINOv2 groundwork).
- **Teaching packs invalidate.** `PACK_MODEL`/`PACK_DIM` stamp packs to the
  encoder; `isTeachingPackCompatible()` will (correctly) reject old packs
  against the new `MODEL_ID`. Communicate this, and regenerate any first-party
  packs against the new model.

## Accept gate (non-negotiable)

Ship the fine-tune **only if** it beats stock CLIP on the eval harness
(corpus doc §3): higher Recall@5 / MRR on the disjoint golden set, **and no
regression** on a set of generic (non-meme) queries that guards against
catastrophic forgetting. A fine-tune that wins on memes but tanks "a photo of a
dog" is a net loss for a general library. Report both before/after tables in the
PR that bumps the model.

## Risks / honest caveats

- **Copyright.** Training on scraped copyrighted memes for a *personal/on-device*
  model is far more defensible than **distributing** the resulting weights.
  Distributing a model that memorized specific images carries real risk; keep
  the corpus local, prefer LoRA (less memorization), and get a human/legal call
  before publishing weights to the `models-v1` release.
- **Overfitting / forgetting** — the whole reason for LoRA + heavy regularization
  + the generic-query guard above.
- **Distribution shift** — memedepot's meme population is not your users'. Treat
  gains as directional, not guaranteed; the on-device teaching-pack + label
  paths remain the per-user personalization surface.
- **Cost/benefit** — this is the most effort for the least *certain* payoff of
  the three threads. The label mining + eval harness deliver value regardless of
  whether the fine-tune ever pans out, and they're the prerequisite that lets
  you *know* if it did.

## Cheaper alternatives worth trying first

- **Prompt/label engineering** (corpus doc §2) — often most of the win at ~none
  of the risk, since zero-shot matching is only as good as the prompts.
- **Caption-side tuning** — the app already stores a CLIP *text* vector of each
  meme's VLM caption (`searchCore.ts` hybrid channel). Improving captions (a
  better VLM prompt, or a small caption model) may beat re-training the encoder.
- **Linear adapter** — freeze CLIP, learn a tiny projection on top from the
  corpus pairs. Trivial to train/host and can't catastrophically forget; a
  sensible ablation before committing to LoRA.
