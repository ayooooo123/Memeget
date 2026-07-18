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

Building a real one (a few hundred entries) — the one manual/offline step:

1. **Curate** `(image, query, expected meme)` triples. The memedepot corpus
   (`docs/memedepot-corpus.md`) hands these over nearly for free: an item's
   human title/tags become the `query`, the item is the `expectedId`. Dedupe
   near-identical templates and spread across formats so one viral template
   doesn't dominate the score. **Commit vectors + ids only — never raw images.**
2. **Embed** each image and query with CLIP ViT-B/32 (the `open_clip` weights
   matching the app's export, or the on-device model) and L2-normalize. Verify
   parity against a couple of on-device vectors first.
3. Drop the vectors into a `golden.json` and evaluate.

## Accept-gate usage

Before a change to `memeLabels.ts`, the `searchCore` weights, `MAX_BASELINE_LABELS`,
or the embedding model merges:

```ts
const before = evaluateRetrieval(golden);   // on main
// …apply the change, re-embed if the model changed…
const after = evaluateRetrieval(golden);
const bad = regressions(before, after);      // [] = safe to ship
```

## Next (not yet built)

- **Tagging eval** — precision/recall/F1 of zero-shot label assignment vs.
  `expectedLabels`, reusing the classify path. This is what most directly gates
  the harvested-baseline cap; the retrieval metrics above are the headline, this
  is the companion.
- A standalone `npm run eval -- path/to/golden.json` CLI once real golden sets
  exist (trivial wrapper around `evaluateRetrieval` + `formatMetrics`).
