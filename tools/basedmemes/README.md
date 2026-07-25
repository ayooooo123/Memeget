# basedmemes label miner

Mines a machine-generated **breadth tier** of zero-shot classification labels
from a large **local** archive of basedmemes.lol + Know Your Meme memes, and
writes it to `src/data/basedmemesBaseline.json`.

This is the second baseline tier alongside the CI-only memedepot harvest
(`tools/memedepot/`). memedepot is egress-blocked from the dev sandbox, so it can
only be reached from GitHub CI; this archive, by contrast, is a large local
corpus the harvester could never reach. Both tiers are folded **under** the
hand-authored curated core in `src/memeLabels.ts` by
`buildAllBaselineLabels` (curated wins, cross-tier de-dupe, each tier capped).

## What it mines

The archive (outside this repo) has two files:

- `dataset.jsonl` — one record per line, `{image, prefix, suffix}`, where
  `suffix` is a comma-separated tag list and `image` is the bare image filename.
- `meme_dataset_kym.json` — a JSON array of `{image, tags, file}` entries with
  richer KYM per-meme tags (occasionally an object, not a bare string); `file` is
  `images/<filename>`.

## "Each image = one page"

The miner treats **each meme image as one "page"** and its tag list as that
page's terms. The two files are merged by image filename (KYM keyed on the
basename of `file`) and tags are unioned per image. Those pages go through the
**exact same** quality machinery the memedepot harvester exports
(`aggregatePages` → `buildBaseline`):

- `aggregatePages(pages)` counts each tag **once per image**, so a term's count
  is the number of **distinct memes** it appears on (a frequency×distinctness
  signal).
- `buildBaseline` then ranks by that count, drops singletons (`count >= 2`
  floor — i.e. "on at least 2 memes"), collapses trivial plural variants,
  Title-Cases the label, guesses a facet category, writes the CLIP prompt
  template, and caps the list.

There is deliberately **one** normalization/category/prompt convention: this
tool imports those primitives from `../memedepot/harvest.mjs` rather than
reimplementing them.

## How to run

```sh
npm run mine:basedmemes
# or, explicitly:
node tools/basedmemes/mine_labels.mjs [--data-dir <path>] [--out <path>] [--max <n>]
```

Defaults: `--data-dir /Users/jd/projects/basedmemes_archive/www.basedmemes.lol`,
`--out src/data/basedmemesBaseline.json`, `--max 300`. The archive lives outside
the repo, so pass `--data-dir` on any other machine. The miner prints the image
count, unique-tag count, labels written, and the top 12 terms; if 0 labels
result it exits non-zero **without** overwriting the output.

Tests: `npm run mine:basedmemes:test` (or `node --test tools/basedmemes/*.test.mjs`).

## Tuning

Every extra label is another text embedding computed once, another comparison
per image, and another false-positive chance — so the tier is capped
(`MAX_BASEDMEMES_LABELS` in `src/baselineLabels.ts`, applied when composing) and
should be tuned against the search-quality eval harness (see
`tools/eval/README.md`) before the cap is raised.
