# memedepot tag harvester

Dev/CI tooling that mines [memedepot](https://memedepot.com/) for the tag /
format vocabulary its users curate, and writes it to
`src/data/memedepotBaseline.json`. The app folds a capped, de-duplicated slice
of that vocabulary into its zero-shot tagging label set on first launch
(`src/baselineLabels.ts` → `MEME_LABELS`), so a fresh install recognizes a much
broader set of meme formats/characters on day one — without a human hand-writing
every entry.

**This never ships in the app and never runs on-device.** It only produces a
committed data file.

## Why it runs in CI, not locally

memedepot sits behind Cloudflare, and this repo's dev sandbox / agent egress
policy blocks `memedepot.com` outright (a bare request 403s at the proxy).
GitHub-hosted runners, by contrast, have open internet — so
`.github/workflows/harvest-memedepot-tags.yml` is where the harvest actually
reaches the site. It runs on demand (`workflow_dispatch`) or monthly, and opens
a **pull request** with the regenerated `memedepotBaseline.json` for review. It
never writes straight to a release branch.

## What it collects (and what it doesn't)

- **Collects:** derived *text* only — tag terms, depot/format names, and their
  cross-page frequency. Ranked, Title-Cased, de-duplicated, capped.
- **Never collects:** the meme images themselves. Keep it that way — shipping a
  frequency-ranked vocabulary is defensible; redistributing user-uploaded images
  is not.
- **Politeness:** obeys `robots.txt` (sitemaps + `*`-scoped `Disallow`),
  rate-limits (~1 req/sec), identifies as a browser, caps total pages, and backs
  off on `429`/`503`.

## Where the labels come from (multi-source)

The harvester merges **candidates** from several source adapters, each emitting
`{ term, weight, source }`. Two weight tiers: **names** (collection/template
names — the human-authored taxonomy, `weight = NAME_BASE − rank`, always kept
and ranked top) and **tags** (frequency terms, `weight = count`). The pipeline
normalizes + quality-filters every term, de-dupes across sources by stem
(highest weight wins, provenance kept), ranks, and caps.

Sources today:

- **memedepot** (`depotCandidates`) — a Next.js app whose taxonomy is its
  **depots** (collections named by format — "Milady", "Wojak"), read from the
  catalog API `/api/depots?page=N`. Depot **names** → name-tier candidates;
  depot **tags** → tag-tier (counted across depots, ≥2-depot floor). Structure
  mapped by `diagnose.mjs`; re-run it if the API shape changes.
- **imgflip** (`imgflipCandidates`) — `api.imgflip.com/get_memes`, the ~100
  canonical image-macro templates (Drake, Distracted Boyfriend, Two Buttons…),
  popularity-ranked → all name-tier. The clean classic-format list memedepot's
  crypto-heavy catalog underweights.

Adding a source = write a `*Candidates(payload)` adapter returning candidates +
a small fetch helper, then `candidates.push(...)` in `main()`. If every source
comes back empty, it falls back to the legacy memedepot per-post HTML crawl.

## Files

- `harvest.mjs` — the harvester. Pure helpers (normalization, category guessing,
  depot-catalog parsing, HTML extraction, aggregation, baseline building) are
  exported and unit-tested; `main()` / `harvestDepots` / `crawlHtmlBaseline` are
  the network orchestration.
- `diagnose.mjs` — one-shot structure recon (run via the *Diagnose memedepot
  structure* workflow) that dumps the URL scheme + where depot names live, so the
  extractor targets the right fields instead of guessing.
- `harvest.test.mjs` — `node:test` coverage for the pure helpers. The live crawl
  isn't covered (it can't run from the sandbox — that's the whole reason it's a
  CI job).

## Run

```bash
npm run harvest:test          # unit-test the parsing/normalization logic
npm run harvest               # crawl + write src/data/memedepotBaseline.json
# options: --maxPages 400 --maxTags 300 --delayMs 1100 --base https://memedepot.com
```

From the restricted sandbox `npm run harvest` will just fail to reach the host
and leave the file untouched (by design — it refuses to overwrite with an empty
result). Run it via the workflow, or locally from an unrestricted network.

## Output shape

```jsonc
{
  "source": "memedepot.com",
  "generatedAt": "2026-07-17T06:00:00.000Z",  // null in the shipped-empty default
  "labels": [
    { "label": "Gigachad", "prompt": "a gigachad meme", "category": "character", "count": 412 }
  ]
}
```

`prompt` is a generic template — the harvested tier is *breadth*. The curated
`CURATED_MEME_LABELS` in `src/memeLabels.ts`, with hand-written prompts, remains
the quality core; the app merges the two and the curated entry always wins on a
label collision.

## Quality filter

memedepot's per-item tags are freeform user hashtags, not a clean format
taxonomy, so `normalizeTerm` / `buildBaseline` apply a quality gate before a term
becomes a zero-shot label:

- **`GENERIC_DENYLIST`** — generic concrete nouns / actions / brands (`gun`,
  `car`, `phone`, `walmart`, `family`…) that are real words but make terrible
  zero-shot classes (they'd fire on a huge fraction of any library). Hand-
  maintained; extend it as review surfaces more.
- **Single-token heuristics** — drop bare tokens that are too short (`gme`,
  `esq`), contain digits (tickers/ids like `usd1`, `51349b`), or have no vowel
  (`rrs`). Multi-word terms are exempt.
- **Apostrophe folding** — `mcdonald's` → `mcdonalds`, not `mcdonald s`.
- **Plural stem-dedupe** — `pill`/`pills`, `goblin`/`goblins` collapse to one
  slot (higher count wins).
- **Per-page counting** (`aggregatePages`) — a term is counted at most once per
  page. A tag routinely appears in several extraction strategies on one page
  (JSON-LD + inline array + `/tag` href), so a flat count double/triple-counts
  it and the `count >= 2` "seen on >1 page" floor collapses to "seen once",
  leaking the single-page tail. Per-page dedupe makes the count mean *distinct
  pages*, which is what the floor assumes.

On the first real harvest this removed ~half the raw tags. The residue is
community-specific in-group slang (vowel-having, non-English) that generic rules
can't catch without overfitting — that's what the **eval harness**
(`docs/memedepot-corpus.md`) is for: measure which harvested labels actually help
tagging precision, then set the cap and denylist from data, not eyeballing.

## Caveats

- The extraction strategies in `extractTagsFromHtml` are best-effort against a
  site structure we can't inspect from here. The first real run may surface junk
  or miss a tag source — that's why the output lands in a **reviewed PR**, and
  why `main()` refuses to ship an empty overwrite. Adjust the strategies against
  a saved page if a run comes back thin. memedepot embeds tags as JSON *objects*
  (`{name: …}`), so array entries are unwrapped via `jsonTerm`, not `String()`
  (which would leak `[object Object]`); `normalizeTerm` guards that string too.
  The crawl also logs its top raw (pre-normalization) terms, so a wrong
  object shape is visible in the run logs without another blind harvest.
- **Auto-PR needs a repo setting.** `peter-evans/create-pull-request` uses the
  Actions `GITHUB_TOKEN`, which by default is blocked from opening PRs
  (`GitHub Actions is not permitted to create or approve pull requests`). Enable
  Settings → Actions → General → Workflow permissions → *Allow GitHub Actions to
  create and approve pull requests*. Without it the run still pushes the
  `automation/memedepot-tags` branch — a PR just has to be opened manually.
- Raising the label cap should be gated on the search-quality eval harness
  (`docs/memedepot-corpus.md`) — more zero-shot classes means more chances at a
  false-positive tag.
