# Twetch meme-library harvester

Pulls the [Twetch](https://twetch.com/meme-library) meme library ("Dank Rares")
— ~6.5k on-chain memes, organised into folders curated by format and, uniquely
among the sources we harvest, **tagged by hand** — and writes
`src/data/twetchBaseline.json`.

```bash
npm run harvest:twetch        # ~60s, no key needed
npm run harvest:twetch:test   # the pure extraction logic, no network
```

Unlike the memedepot harvest, this one runs anywhere: `api.twetch.com` is public
JSON and not egress-blocked. CI (`.github/workflows/harvest-twetch-tags.yml`)
runs it monthly and opens a PR.

## The API

Two facts matter, and both are non-obvious:

```
GET /v1/dank-rares?limit=200&cursor=…   -> { items, nextCursor, total }
GET /v1/dank-rares/folders              -> { categories: [...] }
```

1. **A meme's `folder` is the curated format category** — `Pepe Memes`,
   `Wojak Memes`, `Bobo Memes`, `Brainlet Memes`, `Apu Memes`, `Grug Memes`,
   `BSV Memes`, `$TOSHI Memes`, `Reaction Memes`, `Meme Templates`. 14 of them.
2. **`/v1/dank-rares/folders` is something else**: 44 *aspect* buckets (Laugh,
   Sad, Smug, Computer, Weapon, Outdoors). Useful words, but as zero-shot
   classes they are weak visual anchors, so the harvester ignores that endpoint
   and derives folders from the memes themselves.

Each item carries `tags: string[]` (100% of the corpus does). The file container
is stored as a tag too — `png` is on ~90% of memes — so `FORMAT_NOISE` strips
those before anything is counted.

## Output

The same baseline shape every source emits, so `src/baselineLabels.ts` composes
it like the others: folder names at NAME tier (ranked by how many memes they
hold), per-meme tags at frequency tier (counted once per meme, singletons
dropped), all of it through the shared `normalizeTerm`/denylist from
`tools/memedepot/harvest.mjs`.

## Why the tier is capped at 0

`MAX_TWETCH_LABELS` is **0**, and that is a measurement, not an oversight.
`npm run recognition` on two independent corpora:

| vocabulary | 595-meme KYM/basedmemes holdout | a real 2k-meme library |
|---|---|---|
| 399 labels (no Twetch) | 546 right / 632 wrong | 140/323 correct |
| + 3 Twetch folder names | 545 / 633 | 140/323 |
| + 20 Twetch labels | 542 / 638 | 140/323 |
| + 60 Twetch labels | 540 / 649 | 140/325 |

Not because the source is bad — because it overlaps. 10 of the 14 folders
(Pepe, Wojak, Bobo, Brainlet, Boomer, Apu, Grug, Zoomer, Reaction, Happy) are
already in the vocabulary from the curated core and the earlier tiers, so the
only slots left go to the generic aspect tail, which costs precision.

The harvest still ships and still runs: it is the snapshot the eval grades, and
the moment Twetch adds formats the other sources lack, raising the cap is a
one-line change the harness can justify.

**What Twetch is actually best positioned to give us is not words but
examples** — 6.5k images with human labels, in a corpus where the image side of
the learner (`trainLabelModel`) measurably beats text prompts. That is a
separate design: it means committing image-derived embeddings, which needs its
own licensing/provenance review, so it is deliberately not part of this
harvester's contract. This one collects derived text only — folder names, tag
terms, counts. No images, no on-chain identifiers, no user data.
