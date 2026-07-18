# Mining memedepot: corpus, label mining, and a search-quality eval harness

Status: **design / proposal.** Nothing here ships in the app or runs on-device.
This describes an *offline* data pipeline whose only outputs are artifacts we
already know how to ship: additions to the curated label library
(`src/memeLabels.ts`), an optional teaching pack, and a reusable benchmark that
tells us whether a change to search actually helped.

[memedepot](https://memedepot.com/) is a meme-hosting / curation site: users
build **depots** (named collections) of memes, and depots are frequently
organized *by format or character* ("wojak variants", "gigachad", "reaction
images"). That human curation is the valuable signal — it's a large, tagged,
frequency-ranked map of which meme templates people actually care about, which
is exactly the "knowledge layer" Memeget maintains by hand today.

## Why this fits Memeget (and where the line is)

Memeget's entire pitch is **on-device, no servers, no uploads**. A cloud scrape
plus a cloud model would violate that if it touched the app at runtime. It does
not. The rule this pipeline follows:

> The network-touching, memedepot-touching work happens **once, offline, on a
> developer's machine**. The app only ever receives *derived, redistributable
> artifacts*: a bigger label list (plain text prompts), and — separately — a
> fine-tuned model (see [`memedepot-finetune.md`](./memedepot-finetune.md)).
> The scraped memes themselves are never bundled, never uploaded, never shipped.

So this is the same posture as `tools/model-export/`: developer-only tooling
that produces assets, not a runtime dependency.

### Legal / ethical guardrails (read before writing a scraper)

- **The memes are user-uploaded, mostly copyrighted content.** Using them to
  *derive a taxonomy* (which formats exist, how popular they are) and to build a
  *private evaluation set* is defensible; **redistributing the images** — or a
  model that can reproduce them — is not. Keep the raw corpus local. Ship
  prompts and metrics, not pictures.
- **Respect the site.** `robots.txt`, `Terms of Service`, and a low request
  rate are non-negotiable. memedepot sits behind Cloudflare (a bare
  non-browser request 403s), so a scraper must behave like a considerate
  browser, not hammer it. Prefer an official/JSON API endpoint if one exists
  over HTML scraping.
- **Attribution + provenance.** Store each item's source URL and depot so any
  downstream artifact can be traced back and a takedown honored.
- This repo's egress is policy-restricted and **cannot reach `memedepot.com`
  from CI or the agent sandbox** (the proxy 403s the CONNECT). The scraper is a
  *local dev tool*; do not wire it into a workflow that runs here.

## Pipeline shape

```
memedepot ──crawl──▶ corpus.jsonl ──┬─▶ label mining ─▶ memeLabels.ts additions (PR, human-reviewed)
   (local, once)     (+ images/)    │
                                    ├─▶ eval golden set ─▶ tools/eval (search-quality benchmark)
                                    │
                                    └─▶ training pairs ──▶ fine-tune (see memedepot-finetune.md)
```

Everything lives under `tools/memedepot/` (proposed), mirroring the
`tools/model-export/` convention: a `README.md`, a small crawler, and the
post-processing scripts. Python is the path of least resistance (shares the
Colab/ExecuTorch stack the fine-tune uses), but a Node crawler is fine too.

> **Implemented (tagging slice):** a first, tag-focused cut of this crawler now
> ships as `tools/memedepot/harvest.mjs`, run by the
> `harvest-memedepot-tags` GitHub Action (it runs in CI because GitHub runners
> can reach memedepot even though the dev sandbox can't). It harvests the tag
> vocabulary into `src/data/memedepotBaseline.json`, which the app folds into its
> zero-shot label set on first launch (`src/baselineLabels.ts`). The full
> image-collecting corpus below (for the eval harness and fine-tune) is still a
> proposal.

## 1. The crawler (`tools/memedepot/crawl.py`)

Goal: produce `corpus.jsonl` (one JSON object per meme) plus a local
`images/<sha256>.<ext>` cache. **No app code depends on this**; it's a
throwaway data-gathering step.

### Discovery

Two-level crawl:

1. **Depots** — enumerate collections (from the site's browse/trending pages or
   API). Record `depot_slug`, `depot_title`, `depot_tags`, `item_count`.
2. **Media** — for each depot, enumerate its items. For each item record the
   direct media URL, type, and any per-item title/tags/caption.

Reuse what the app already learned about memedepot in `src/linkResolver.ts`:
share-preview **Open Graph** tags (`og:image`/`og:video`) usually give the
direct media URL, and the embedded-media fallback (`<video>`/`<source>` or a
direct CDN URL in the page/JSON) covers JS-rendered pages. If memedepot exposes
a JSON API, prefer it — it's cheaper and more stable than HTML scraping.

### Politeness / robustness

- One shared `requests.Session` with a real browser `User-Agent`, HTTP/2 if
  available, cookies persisted.
- **Global rate limit** (e.g. ≤1 req/sec) + jittered backoff on `429`/`503`.
- Resume-safe: skip items whose media hash is already cached; append-only JSONL.
- Cap total items per run (`--max`) so a first pass is a few hundred, not the
  whole site.

### `corpus.jsonl` schema

```jsonc
{
  "id": "memedepot:d/funny/media/gigachad-clip",   // stable, dedupes reruns
  "source_url": "https://memedepot.com/d/funny/media/gigachad-clip",
  "depot_slug": "funny",
  "depot_title": "Chad Energy",
  "depot_tags": ["gigachad", "sigma", "reaction"],  // human curation — the gold
  "item_title": "when the code compiles first try",
  "item_tags": [],
  "media_url": "https://cdn.memedepot.com/abc/clip.mp4",
  "media_kind": "video",                            // image | video
  "media_sha256": "…",                              // dedupe + local file key
  "local_path": "images/…mp4",
  "width": 720, "height": 720,
  "fetched_at": "2026-07-17T00:00:00Z"
}
```

The `depot_*` fields are the point. A depot titled "Wojak Variants" with 200
members is 200 weak labels for `Wojak` for free.

## 2. Label mining → `src/memeLabels.ts`

`src/memeLabels.ts` is the app's editable knowledge layer. Each entry:

```ts
interface LabelDef {
  label: string;      // human-facing tag, e.g. "Gigachad"
  prompt: string;     // CLIP text prompt used for zero-shot matching
  category: 'format' | 'character' | 'emotion' | 'topic' | 'person';
  associations?: string[]; // world-knowledge terms folded into search text
}
```

Plus `OCR_RULES` (regex → label, for watermarks/text the classifier can't read)
and `NEGATIVE_ANCHORS` (the dynamic reject threshold). The goal of mining is to
**propose new `LabelDef` entries, associations, and OCR rules** from the corpus,
for a human to review into a PR. It is a *suggestion generator*, never an
auto-committer — the curation quality is the moat.

### Mining algorithm (`tools/memedepot/mine_labels.py`)

1. **Aggregate depot/item tags** across the corpus into a frequency table:
   `term -> {count, example_source_urls[]}`. Split multiword depot titles into
   candidate terms; normalize case/plurals.
2. **Diff against what we already cover.** Load the existing `MEME_LABELS`
   (labels + associations) and `OCR_RULES`. Drop any candidate already
   represented (by label match or association membership). What remains is the
   *uncovered long tail* — the memes memedepot users name that Memeget currently
   can't.
3. **Rank** the uncovered terms by frequency × distinctness (a term that names
   one depot of 3 is noise; a term across 40 depots and 2k memes is a real
   format). Emit the top N as candidates.
4. **Draft each candidate** into a `LabelDef` skeleton:
   - `label`: title-cased term.
   - `category`: heuristic from co-occurring terms (a proper name → `person`;
     "format"/"template"/"vs"/"comparison" → `format`; an -er/-jak archetype →
     `character`; a mood word → `emotion`; else `topic`). Human fixes these.
   - `prompt`: a template — `"a <term> meme"` — flagged **TODO: hand-write**.
     The prompt is the load-bearing part (it's what the CLIP text encoder
     embeds), so it must be authored by a person, exactly like the existing
     entries ("a Gigachad meme, a jawline chiseled black and white man"). The
     miner only proposes the *skeleton*.
   - `associations`: the other terms that co-occur with this one in depots
     (that's literally the "world-knowledge graph" the file describes).
   - `OCR_RULES` suggestion when a term reliably appears as on-image text /
     watermark (e.g. a project domain), phrased as an unambiguous regex.
5. **Output** a review file: `label-candidates.md` (ranked, with example thumbs
   + source links) and a `label-candidates.ts` snippet ready to paste-and-edit
   into `MEME_LABELS`. A maintainer edits prompts, fixes categories, deletes
   junk, and opens the PR.

### Guardrails

- **Never auto-append to `memeLabels.ts`.** Machine-drafted prompts are low
  quality; the whole value of that file is that a human wrote each prompt.
- Bound additions per PR (e.g. ≤25) so review stays real.
- **Every label change must clear the eval harness (§3)** before merge — adding
  labels changes zero-shot tagging for the whole library and can regress it.
- Optionally emit a **teaching pack** (`src/teachingPack.ts` format) instead of
  new labels for character/template concepts best taught by *example*: embed a
  handful of exemplar images per concept with the app's CLIP image encoder and
  package them as `PackExemplar[]`. Note the hard constraint — a pack is stamped
  with `PACK_MODEL`/`PACK_DIM` and is only valid for the current primary encoder
  (`isTeachingPackCompatible`), so a pack must be rebuilt if the model changes.
  Packs embed *vectors*, not images, so they're redistributable where raw memes
  are not.

## 3. Search-quality eval harness (the highest-value output)

Today there is **no way to measure whether search got better or worse.** Every
tweak to `memeLabels.ts`, `searchCore.ts`, the caption weights, or the embedding
model is a shot in the dark. The corpus gives us free `(image, human caption,
tags)` triples — turn a slice of them into a golden set and we get a regression
test for retrieval. Build this *first*; it's the yardstick for both label mining
and the fine-tune.

> **Scaffolded:** the core now exists — `src/evalCore.ts` ranks a golden set with
> the app's own `scoreEntry` and reports Recall@k / MRR plus a `regressions()`
> A/B gate (`npm run eval`, tests in `src/evalCore.test.ts`, schema +
> instructions in `tools/eval/`). What remains is curating a *real* golden set
> (offline CLIP vectors) and adding the tagging-precision companion metric.

### Design (`tools/eval/`)

**Golden set** (`golden.jsonl`, curated from the corpus — a few hundred items):

```jsonc
{
  "meme_id": "memedepot:…",
  "local_path": "images/….jpg",
  "queries": ["gigachad coding", "when the code compiles"], // from title/tags, human-checked
  "expected_labels": ["Gigachad"]                            // for tagging eval
}
```

Curate deliberately: dedupe near-identical templates, drop items whose
title/tags aren't a fair "query", and keep a spread across formats so the metric
isn't dominated by one popular template.

**Runner.** Compute each meme's embeddings *with the app's real encoder* (export
the CLIP ViT-B/32 image + text towers from `react-native-executorch`, or run the
equivalent `open_clip` weights — see the fine-tune doc — and confirm parity), so
the offline harness scores what the phone would. Then, crucially, **score with
the app's own scoring code, not a reimplementation**: `src/searchCore.ts`
exports the exact functions the DB scan uses —

```ts
scoreEntry(queryVec, terms, { imageVec, captionVec, searchText })
hybridSearchScore(queryVec, imageVec, captionVec)
```

Drive them from a tiny Node harness (they're pure and already unit-tested) so
the benchmark can't drift from production ranking. For each query: embed it,
`scoreEntry` every meme in the golden set, sort, and check the rank of the
expected meme.

**Metrics**

- **Recall@k** (k = 1, 5, 10): is the target meme in the top-k for its query?
  The headline number.
- **MRR**: mean reciprocal rank of the target — rewards putting it at #1.
- **Tagging precision/recall**: run the zero-shot label pipeline and compare to
  `expected_labels` — this is what directly gates `memeLabels.ts` changes.
- Report a per-category breakdown (formats vs characters vs people) so a change
  that helps one and hurts another is visible.

**Gate.** A change to `memeLabels.ts`, `searchCore.ts`, caption weights, or the
model must **not regress** Recall@5 / MRR beyond a small tolerance, and label
changes must not drop tagging F1. Wire it as an npm script (`npm run eval`) and,
once the golden set is committed (vectors/metadata only — **no raw images**),
optionally a CI check. Because `scoreEntry` is already covered by
`searchCore.test.ts`, the harness is thin: fixtures + a ranking loop + a
metrics printout.

## Suggested milestones

1. **Eval harness + a 100-item hand-curated golden set.** Immediately useful:
   locks in current search quality as a baseline. No scraper needed if seeded
   from your own library.
2. **Crawler + `corpus.jsonl`** (a few hundred items) with politeness/legal
   guardrails.
3. **Label miner** → first PR of ~15 reviewed labels; confirm no eval
   regression; watch Recall@k move.
4. **Scale the golden set** from the corpus; only then consider the fine-tune,
   which *needs* this harness as its accept/reject gate.

See [`memedepot-finetune.md`](./memedepot-finetune.md) for turning the same
corpus into a meme-tuned on-device encoder.
