# Embedding Roadmap

Memeget currently uses the only image/text embedding pair exposed by
`react-native-executorch@0.9.2`: CLIP ViT-B/32 image embeddings plus the matching
CLIP text tower. The package registry does not currently expose MobileCLIP,
SigLIP, or DINO image embeddings.

The code now centralizes vector-space metadata in `src/embeddingModels.ts`:

- `PRIMARY_EMBEDDING_MODEL`: current CLIP ViT-B/32 image/text space, or a
  custom MobileCLIP-S2 image/text pair when configured
- `VISUAL_EMBEDDING_MODEL`: reserved DINOv2 visual-similarity space, activated
  by a custom image model source
- `FUTURE_EMBEDDING_MODELS.mobileClipS2`: planned MobileCLIP-S2 primary space
- `modelStamp()` / `isTeachingPackCompatible()` compatibility helpers

Teaching packs derive their model/dimension stamp from that registry, so a
future S2 switch has one compatibility gate instead of duplicated literals.
Cached zero-shot label vectors are read/written with the active primary model id
so S2 cannot accidentally reuse stale CLIP prompt vectors.

## Tier 1: hybrid caption retrieval

Implemented now. The app keeps the CLIP image vector as the universal backbone,
then stores a second CLIP text vector for each VLM-described meme:

- input text: caption + merged labels + extra search terms
- storage: `memes.caption_embedding`
- ranking: `image_score + 0.9 * max(0, caption_score - 0.55)` — only the margin
  ABOVE the unrelated-text baseline counts. CLIP text↔text cosines sit at ~0.5+
  even for unrelated captions, so blending the raw cosine would hand every
  described meme a flat boost and bury undescribed-but-relevant results.

This avoids re-embedding images and improves queries where text-to-image CLIP is
weak but Gemma/LFM has already described the joke, scene, subject, or format in
plain text. Foreground description writes the vector immediately. Descriptions
created by the headless OS task are backfilled when the app next opens with CLIP
ready.

## Tier 2: MobileCLIP-S2

MobileCLIP-S2 is the best candidate for replacing CLIP as the primary
image/text encoder when it is available in the runtime or exported reliably:

- likely target: a full image/text pair, replacing both current CLIP towers
- migration cost: full image re-index, label-vector cache rebuild, and teaching
  pack model stamp bump
- compatibility risk: existing exemplar vectors cannot be mixed with S2 vectors,
  so old taught labels need re-teaching or a one-time migration from source
  images if source URIs are still readable
- implementation note: if shipped as custom `.pte`, preprocessing must match S2
  exactly. Do not rely on the current CLIP-oriented native image preprocessor
  unless the exported graph bakes in resize/normalization.

Runtime activation is wired for custom ExecuTorch exports. Set all three values
before bundling/running the app:

```bash
EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE=...
EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE=...
EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE=...
# optional, defaults to 512 / mobileclip-s2
EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM=512
EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID=mobileclip-s2
```

When those are present, `EmbeddingsProvider` loads both towers through
`react-native-executorch`'s custom model path, Settings shows MobileCLIP-S2 as
the active image/text model, teaching-pack compatibility stamps with S2, and the
label-vector cache is rebuilt under the S2 model id.

Remaining migration reality (now guarded at runtime):

- every index run stamps `settings.index.primaryModel`; on mismatch Settings
  shows a hard "Clear index → re-Index → re-teach" warning instead of letting a
  swapped build silently search a foreign-space index
- exemplars carry a `model` column; only rows stamped for the ACTIVE primary
  space train heads or appear in taught-knowledge/suggestion lists, so a swap
  disables (not corrupts) old teaching
- hidden old-space examples are MIGRATED automatically: each self-taught
  example remembers its source meme, and once the library is re-indexed the
  meme's fresh embedding is exactly what re-teaching would store. Migration
  runs at the end of every index pass and from a Settings button; only
  pack-imported examples (no source image) need a pack re-exported under the
  new model.
- custom `.pte` preprocessing must match S2 exactly. Verified against the
  0.9.2 native runtime: images arrive resized to the model's own declared input
  size, RGB, pixel/255, NO mean/std normalization, CHW planar — bake
  normalization into the exported graph. Text models receive int64
  `(tokenIds, attentionMask)` and must output the final pooled embedding
  (the app L2-normalizes in JS).

## Tier 3: DINOv2 visual similarity

DINOv2 is not a replacement for text search because it has no text tower. It is
a second visual-similarity space for:

- `More like this`
- duplicate/template variant discovery
- teach-by-example candidate expansion

Implemented schema shape:

- keep `memes.embedding` as the text-search CLIP/S2 vector
- use `memes.visual_embedding` for DINOv2
- use `memes.visual_model` as the visual-space model stamp so future custom
  exports can migrate independently from text search

Runtime activation is wired for a custom image-only export:

```bash
EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE=...
# optional, defaults to 768 / dinov2
EXPO_PUBLIC_MEMEGET_DINOV2_DIM=768
EXPO_PUBLIC_MEMEGET_DINOV2_MODEL_ID=dinov2
```

When configured, DINO vectors come exclusively from the idle-time backfill loop
— they are deliberately NOT computed during indexing, because fp32 DINOv2-base
costs a multiple of the primary embed per frame and made fresh indexing crawl.
The library is browsable immediately; `More like this` upgrades from primary to
DINO similarity per pair as the backfill catches up.

Groundwork in place:

- nullable `visual_embedding` and `visual_model` columns
- `src/visualSearch.ts` chooses the space PER PAIR: DINO only when both rows
  carry a vector stamped for the active visual model, otherwise
  primary-vs-primary — a cross-space dot (768-dim DINO against 512-dim CLIP)
  is never computed
- the backfill stamps permanently-failing rows (`visual_model = 'failed:<id>'`)
  so an unreadable file can't wedge the loop, and bails without stamping when
  the model goes transiently unready

The current learner already recovers much of DINO's value through calibrated
heads and nearest-exemplar matching. DINOv2 should be treated as an additive
quality upgrade after Tier 1, not as a prerequisite for usable learning.
