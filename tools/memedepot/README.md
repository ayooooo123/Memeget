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

## Files

- `harvest.mjs` — the crawler. Pure helpers (normalization, category guessing,
  sitemap/HTML extraction, aggregation, baseline building) are exported and
  unit-tested; `main()` is the network orchestration.
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

## Caveats

- The extraction strategies in `extractTagsFromHtml` are best-effort against a
  site structure we can't inspect from here. The first real run may surface junk
  or miss a tag source — that's why the output lands in a **reviewed PR**, and
  why `main()` refuses to ship an empty overwrite. Adjust the strategies against
  a saved page if a run comes back thin.
- Raising the label cap should be gated on the search-quality eval harness
  (`docs/memedepot-corpus.md`) — more zero-shot classes means more chances at a
  false-positive tag.
