# Embedding Roadmap

Memeget currently uses the only image/text embedding pair exposed by
`react-native-executorch@0.9.2`: CLIP ViT-B/32 image embeddings plus the matching
CLIP text tower. The package registry does not currently expose MobileCLIP,
SigLIP, or DINO image embeddings.

## Tier 1: hybrid caption retrieval

Implemented now. The app keeps the CLIP image vector as the universal backbone,
then stores a second CLIP text vector for each VLM-described meme:

- input text: caption + merged labels + extra search terms
- storage: `memes.caption_embedding`
- ranking: `max(image_score, 0.2 * image_score + 0.8 * caption_score)`

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

## Tier 3: DINOv2 visual similarity

DINOv2 is not a replacement for text search because it has no text tower. It is
a second visual-similarity space for:

- `More like this`
- duplicate/template variant discovery
- teach-by-example candidate expansion

Expected schema shape:

- keep `memes.embedding` as the text-search CLIP/S2 vector
- add `memes.visual_embedding` for DINOv2
- add a model stamp for the visual space so future custom exports can migrate
  independently from text search

The current learner already recovers much of DINO's value through calibrated
heads and nearest-exemplar matching. DINOv2 should be treated as an additive
quality upgrade after Tier 1, not as a prerequisite for usable learning.
