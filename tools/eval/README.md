# Search-quality eval harness

Measures whether a change makes Memeget's search **better or worse**, instead of
guessing. The core (`src/evalCore.ts`) scores a golden set with the app's *own*
ranking function — `scoreEntry` from `src/searchCore.ts`, the exact code the
on-device DB scan uses — and reports **Recall@k** and **MRR**, plus an A/B
**regression gate**.

This is the yardstick the rest of the memedepot work needs: it's how you set the
harvested-label cap and denylist from data (not eyeballing), and it's the
accept-gate for the CLIP fine-tune (`docs/memedepot-finetune.md`).

## Run

```bash
npm run eval        # runs the eval tests + prints a sample metrics report
```

Today that prints metrics for a tiny **synthetic** golden set (proving the
plumbing). To get a *real* number you supply a real golden set — see below.

## How it's wired (and why it can't drift)

`src/evalCore.ts` is pure and model-free: it takes **precomputed vectors** and
ranks with `scoreEntry`. It deliberately does not embed anything itself, so the
benchmark is deterministic and has no native/CLIP dependency. The only rule that
matters: the vectors you feed it must come from **the same CLIP encoder the app
ships** (CLIP ViT-B/32, `PRIMARY_EMBEDDING_MODEL`), or the scores are measuring
the wrong space.

```
golden.json ──▶ rankQuery (scoreEntry) ──▶ rankOfExpected ──▶ Recall@k / MRR
                                                          └──▶ regressions(baseline, candidate)
```

## Golden set

Shape (`golden.sample.json` is a runnable schema example):

```jsonc
{
  "memes":   [{ "id": "...", "imageVec": [...], "captionVec": [...]|null, "searchText": "caption tags ocr" }],
  "queries": [{ "query": "gigachad coding", "queryVec": [...], "expectedId": "..." }]
}
```

Building a real one is automated by **`build_golden.py`** + the **Build eval
golden set** workflow — it runs in CI (needs memedepot access + a torch/CLIP
toolchain, both unavailable in the dev sandbox):

1. Actions → **Build eval golden set** → Run workflow (`depots`, `per_depot`
   inputs). It pulls memes from N memedepot depots, embeds each **image** + the
   depot **name** (the query) with CLIP ViT-B/32, and opens a PR with
   `tools/eval/golden.json`. The encoded eval: *does searching a format's name
   retrieve that format's memes?* **Vectors + ids only — never images.**
2. Merge the PR → `npm run eval` now scores the real set and prints Recall@k /
   MRR (see `src/evalCore.golden.test.ts`).

Run locally instead (Colab or any box with network + torch):

```bash
pip install open_clip_torch torch pillow requests
python tools/eval/build_golden.py --out tools/eval/golden.json --depots 25 --per-depot 8
```

Note: `build_golden.py`'s `meme_image_url()` guesses the memedepot meme-image
field; if the first run writes 0 memes, the log names the keys it saw — adjust
and re-run (same diagnostic pattern as the harvester).

## Accept-gate usage

Before a change to `memeLabels.ts`, the `searchCore` weights, `MAX_BASELINE_LABELS`,
or the embedding model merges:

```ts
const before = evaluateRetrieval(golden);   // on main
// …apply the change, re-embed if the model changed…
const after = evaluateRetrieval(golden);
const bad = regressions(before, after);      // [] = safe to ship
```

## Tagging eval (zero-shot format)

Retrieval routes a query straight to an image, so it never touches the label
prompts — it can't tell you whether the labels/prompts are any good. The
**tagging** eval is the dual that does: given a meme **image**, does zero-shot
classification put its right **format** at the top? That's the metric that moves
when you add labels, fix a prompt, or retune the harvested baseline — and it's
the one that tracks the north star (*every aspect of a meme searchable*), since
aspect search is classification against a label vocabulary.

Ground truth is free and needs no extra annotation: each golden meme's **depot
is its format**, and every depot already contributes a text vector (its name
query), so the depots *are* the label set. `evaluateTagging(golden)` ranks each
meme's image against every label vector and reports top-1/3/5 + MRR
(`formatTagging`). `npm run eval` prints it right under retrieval.

```ts
const t = evaluateTagging(golden);   // { n, labels, recallAt1/3/5, mrr }
```

Baseline on the current 180-meme / 24-format golden set: **top-1 15%, top-3 30%,
top-5 38%, MRR 0.29** — the number a labels/prompt change has to beat.

## Next (not yet built)

- A standalone `npm run eval -- path/to/golden.json` CLI once real golden sets
  exist (trivial wrapper around `evaluateRetrieval` + `formatMetrics`).
