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
matters: the vectors you feed it must come from **the same encoder the app ships** (MobileCLIP-S2, `PRIMARY_EMBEDDING_MODEL`), or the scores are measuring
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
   depot **name** (the query) with MobileCLIP-S2, and opens a PR with
   `tools/eval/golden.json`. The encoded eval: *does searching a format's name
   retrieve that format's memes?* **Vectors + ids only — never images.**
2. Merge the PR → `npm run eval` now scores the real set and prints Recall@k /
   MRR (see `src/evalCore.golden.test.ts`).

Run locally instead (Colab or any box with network + torch):

```bash
pip install open_clip_torch timm torch pillow requests
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

Baseline on the current 180-meme / 24-format golden set (MobileCLIP-S2, the app's
real model): **top-1 33%, top-3 42%, top-5 47%, MRR 0.41** — the number a
labels/prompt change has to beat.

## Aspect search (single-word queries — how the app is really searched)

Nobody types a full sentence to find a meme; they type **one word** — an emotion
(`smug`), an action (`pointing`), a character (`wojak`), a format — and expect
every meme carrying that aspect to surface. That's the north star (*any aspect
findable by a plain-word description*), and it runs through the **lexical
`searchText` channel** (`scoreEntry`'s `.includes`), which retrieval and tagging
never touch. So it's the eval that actually moves when tags get deeper or a
caption changes.

A one-word query has **many** correct answers, so this is multi-relevant
retrieval. `evaluateAspectSearch(golden)` scores every meme and reports **MAP**
(the headline), **precision@5** ("are my top 5 on-topic"), **recall@10**, and
**MRR** of the first hit. Ground truth is free: each memedepot meme's own
per-meme **tags** are its aspects — a meme tagged `smug` is a labeled positive
for the query `smug`. No hand-annotation.

`build_golden.py` emits the substrate: it walks each meme for tag fields,
lowercases `name + tags` into `searchText` (mirroring `db.ts`'s `rowSearchText`),
embeds `name + tags` as `captionVec`, and turns every tag on ≥ `--min-tag-memes`
memes into an `aspects[]` query. `npm run eval` prints aspect metrics under
retrieval + tagging once the golden set carries `aspects[]` (re-run the **Build
eval golden set** workflow to refresh an older set that lacks them).

```ts
const a = evaluateAspectSearch(golden);   // { n, avgRelevant, precisionAt5, recallAt10, map, mrr }
```

Two modes: the default runs through the lexical `searchText` channel; `{ lexical:
false }` is **dense-only** (image + caption, no text match). The gap is the
finding — on the real set (MobileCLIP-S2) MAP is **0.841 with text** vs **0.239
dense-only**: single-word aspect search rides mostly on the aspect word being
written into the meme's tags (dense image/caption understanding recovers ~¼ of it
on its own). **So tag generation is the dominant lever**, which is what
the loop below tunes.

## VLM prompt-tuning loop (facet coverage)

Since search depends on the facet word being *in the tags*, the question every
prompt change must answer is: **of the memes the model describes, what fraction
get a tag in each facet** (an action, an emotion, the situation, …)? That's what
`src/facetCoverage.ts` scores — it classifies each free tag into a facet using
the app's `MEME_LABELS` taxonomy plus a small everyday-word lexicon, and reports
per-facet coverage. A prompt tweak that finally makes the model emit
situation/action tags shows up as coverage going **up**, measured.

The model only runs on-device, so the loop is human-in-the-loop but tight:

1. **Export** a sample of described memes from a device to
   `tools/eval/described.json` — an array of `{ "id"?: string, "tags": [...] }`
   (the tags the VLM produced). Even 20–50 memes is enough to see the shape.
2. **Score:** `npm run coverage` prints per-facet coverage (weakest facets last —
   those are the targets). With no export it prints a synthetic sample + this how-to.
3. **Read the weak facets.** Low `situation`/`action`/`tone` = the prompt isn't
   eliciting them; high `unclassified` = the model emits words the taxonomy
   doesn't know (candidates to add to `MEME_LABELS` or `FACET_LEXICON`).
4. **Tune** the `USER_PROMPT` / `TAGS` line in `src/visionCore.ts` to push the
   weak facets, rebuild the APK (push to `main`), re-describe, re-export.
5. **Compare** the new coverage to the last run. Ship the prompt that lifts the
   weak facets without dropping the strong ones.

```ts
const c = facetCoverage(describedMemes);   // { n, perFacet, avgFacetsPerMeme, unclassifiedRate }
```

Note the metric is bounded by the classifier's vocabulary (`MEME_LABELS`
associations + `FACET_LEXICON`); it's for **relative** comparison across prompt
versions with the same classifier, not an absolute truth. Expanding the lexicon
tightens it.

## Tagging test (findable-by-search gate)

The coverage loop measures *how much* the model tags; this measures whether it
tags a meme with the words you'd actually **search**. A hand-labeled set states,
per meme, the search terms it must be findable by and the facets it must carry:

```jsonc
// tools/eval/tagging-cases.json   (see tagging-cases.sample.json)
{ "id": "shush", "file": "images/shush.jpg",
  "mustFind": ["shush", "quiet", "be quiet"],   // findable by ANY of these
  "expectFacets": ["situation", "action"] }      // and tagged in these facets
```

`scoreTagging(cases, predictions)` (`src/taggingEval.ts`) joins those against
predicted tags — a device export (`described.json`) or a CI proxy-VLM run — and
reports **findable %** (a search term hits the meme, matched with the app's own
lexical `.includes`), **facet recall**, and a per-meme list of what failed. The
shush meme becomes a literal pass/fail. `taggingRegressions(base, cand)` is the
A/B gate so a prompt change can't silently make tagging worse.

```bash
npm run tagtest    # scores tools/eval/tagging-cases.json vs described.json
```

Model-free and deterministic — it scores *given* predictions. Producing the
predictions is the model step: either a device export, or (planned) a CI action
that runs the app's prompt through a proxy VLM on the committed test images so
the prompt can be A/B'd in a PR without a device.

## Emergent templates (npm run templates)

There is no list of meme templates — anything can become one
(`docs/composite-meme-understanding.md`). So templates are **discovered, not
enumerated**: `src/templateClusters.ts` single-links the collection by embedding
cosine (primary space — the same vectors the collection zip carries), and a
cluster of visually-linked memes with **different overlay text** is a learned
template: the same base media reused to convey different ideas. Clusters are
named from the dominant shared tag when one exists.

```bash
npm run templates   # clusters tools/eval/collection-manifest.json when present
```

Drop the `manifest.json` from a Settings → "Export collection (zip)" export at
`tools/eval/collection-manifest.json` to see a real library's learned formats.
Tunables: link threshold (default 0.86 — above noise, below the 0.99 twin-dedup
bar), min size 2 ("the second variation is the moment a template is born"),
min distinct texts 2 (a dupe pile is not a template).

## Tag agreement (npm run agreement) — free ground truth from your own tags

Coverage measures the *shape* of the model's output; it can't know what's TRUE.
But every manual tag and taught exemplar is a labeled example: the user asserted
"this meme IS X." `src/tagAgreement.ts` grades the model against those — on any
meme carrying a user-truth tag (source `manual`/`exemplar`), does the model's
OWN description (its `vision` tags + caption) surface that label? Agreement =
the model sees what you see; each miss is a named recognition gap. Zero labeling
effort — the user already did the work by using the app.

```bash
npm run agreement   # grades tools/eval/collection-manifest.json when present
```

Scope, honestly: user truth skews toward IDENTITY labels (characters, people,
formats), so this grades **recognition**; the hand-labeled tagging cases grade
**recall-by-meaning** (situations, reactions). Complements, not substitutes.
Memes the model never described are reported as `undescribed` and skipped — a
coverage gap, not a wrong answer.

## Next (not yet built)

- A CI-backed **tag-precision** eval: score our produced tags against memedepot's
  ground-truth `extracted_labels`, embedding-matched to bridge the two
  vocabularies (needs our label vectors embedded in CI).
- A standalone `npm run eval -- path/to/golden.json` CLI.
