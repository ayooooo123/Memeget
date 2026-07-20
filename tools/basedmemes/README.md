# basedmemes label miner

Mines a second machine-generated **breadth tier** of zero-shot meme labels from a
large LOCAL archive, writing `src/data/basedmemesBaseline.json`. It is the
local-archive sibling of `tools/memedepot/harvest.mjs`: memedepot is
egress-blocked from the dev sandbox (so that harvester only runs in CI), whereas
this one mines an archive that already lives on disk.

## What it mines

The [basedmemes.lol](https://www.basedmemes.lol) + Know Your Meme archive at
`/Users/jd/projects/basedmemes_archive/www.basedmemes.lol` (override with
`--data-dir`):

- **`dataset.jsonl`** — one object per line:
  `{ "image": "<filename>", "prefix": "...", "suffix": "tag1, tag2, ..." }`.
  `suffix` is a comma-separated tag list (~11k distinct images).
- **`meme_dataset_kym.json`** — an array of
  `{ "image": "<url>", "tags": ["...", ...], "file": "images/<filename>" }`.
  Tags are usually strings but occasionally objects, coerced with the harvester's
  `jsonTerm`. Keyed on `basename(file)`.

The two sources overlap (`dataset.jsonl` re-lists many KYM items under their
descriptive filenames), so the loader **merges by image filename** and unions the
tag sets. Missing source files are skipped, so the loader still works with only
one present.

## Each image = one page

Every meme image becomes one **page** whose terms are its de-duplicated tags —
mirroring the memedepot harvester's per-page model. The reused pipeline then:

1. `aggregatePages(pages)` counts **distinct images** per normalized tag.
2. `buildBaseline(freq, …)` keeps tags with `count >= 2` (i.e. seen on **>=2
   memes** — a real breadth signal, not one-off noise), ranks by that count,
   collapses trivial plural variants, Title-Cases the label, and caps.

All normalization, category guessing, prompt templating, and ranking are imported
from `tools/memedepot/harvest.mjs` — one convention, not a second copy.

## How to run

```sh
npm run mine:basedmemes
# or, with options:
node tools/basedmemes/mine_labels.mjs [--data-dir <path>] [--out <path>] [--max <n>]
```

Defaults: `--data-dir /Users/jd/projects/basedmemes_archive/www.basedmemes.lol`,
`--out src/data/basedmemesBaseline.json`, `--max 300`. The tool prints
image/unique-tag/label counts and the top 12 terms, and **refuses to overwrite**
the output (exiting non-zero) if it mines zero labels — an empty result means the
archive was unreadable, not that the vocabulary is empty.

Tests (deterministic, no network, no real-archive dependence):

```sh
npm run mine:basedmemes:test
```

## Where it plugs in

`src/baselineLabels.ts` folds this file in as the **second** breadth tier under
the hand-authored curated core (`CURATED_MEME_LABELS`), behind the CI-harvested
memedepot tier:

```
MEME_LABELS = [...CURATED_MEME_LABELS, ...buildAllBaselineLabels(CURATED_MEME_LABELS)]
            = curated core + memedepot tier + basedmemes tier   (shared dedup)
```

`buildAllBaselineLabels` dedupes the basedmemes tier against both the curated core
and the memedepot tier, so it only contributes vocabulary neither already covers.
Like the first tier it is deliberately **capped** (`MAX_BASEDMEMES_LABELS`) — every
extra label is another text embedding and another chance at a false-positive tag —
and should be tuned against the search-quality eval harness before raising the cap.
