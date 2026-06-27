# Design: Automatic meme discovery

> Status: **Design / proposal.** No code yet. This documents how Memeget could
> automatically find newly-trending memes on the web, pull in the actual
> video/image, and add them to the on-device collection — and the tradeoffs that
> come with it.

## The motivating example

A WNBA star (Sophie Cunningham) does something, and within hours "pointing"
memes of her [explode all over X](https://www.mediaite.com/media/sports/wnba-star-sophie-cunningham-pointing-memes-explode-all-over-x-in-gusher-of-gags-good-bad-and-ugly/).
We want Memeget to notice that *something is blowing up*, fetch the actual
media, and index it — with no human in the loop.

The WNBA angle is **incidental**. The real requirement (from the brief) is the
hard version:

> A new trending meme might not even start trending on, or be recognized by,
> other people/platforms yet.

So a system that only works by **searching for a meme's known name** is
structurally always a step behind: by the time "Sophie Cunningham pointing" is a
searchable phrase on Tenor/Giphy/Know Your Meme, the moment is half over, and a
truly novel format (no name, no article, no aggregator entry) is invisible to
it. The design below treats **novelty detection from raw feeds** as the primary
mechanism and name-based search as a secondary convenience.

---

## Key reframe: ingestion already exists

Discovery is two problems, and we only have to build one of them.

1. **Ingestion** — given a media file, get it into the library tagged &
   searchable. **This is already built and fully reusable.**
2. **Discovery** — decide *which* files from the open web are worth ingesting.
   **This is the new work.**

The share-import path is the proof. Today a meme shared from another app flows:

```
saveToFolder()          src/saf.ts:130   write the file into the linked folder
  → indexSavedFiles()   src/indexer.ts:345
    → processFile()     src/indexer.ts:251
        copy → (video? thumbnail) → transcode to JPEG → CLIP embed
        → OCR → zero-shot/exemplar tag → store
```

`processFile` doesn't care whether the bytes came from a share sheet or an HTTP
download. **"Automatically add a discovered meme" therefore reduces to:
download the media to a cache path, then call the same `saveToFolder` +
`indexSavedFiles` the `ShareReceiver` already calls.** Discovery is a *producer*
that feeds the existing pipeline.

That keeps the new surface area tiny and means discovered memes get the same
CLIP embedding, OCR, association-graph tags, and teach-by-example behaviour as
everything else for free.

---

## The hard part: catching a meme *before* it has a name

Two complementary detectors. Neither requires the meme to be named.

### Detector A — virality/velocity on raw feeds

Watch public, key-free, media-bearing feeds and flag posts whose engagement is
*accelerating*, not just high. Reddit is the anchor source:

- `https://www.reddit.com/r/<sub>/rising.json` and `.../hot.json` return JSON
  with post title, score, `created_utc`, crosspost count, and direct media URLs
  (`i.redd.it`, `v.redd.it`, imgur, gif/mp4). No API key, no auth.
- Velocity ≈ `score / age_in_hours`, boosted when the same post appears as a
  crosspost across *multiple unrelated subreddits* in a short window. A format
  jumping from `r/wnba` into `r/memes` and `r/sports` simultaneously is the
  signal — and it works whether or not anyone has named it.

This catches the Sophie Cunningham case via the **re-hosts** (Reddit mirrors X
memes within hours) without ever touching X.

### Detector B — visual novelty via the embeddings we already compute

This is the part that makes "unnamed, unrecognized" memes catchable, and it
falls out of infrastructure Memeget already has.

Every candidate gets a **CLIP image embedding** (the same `api.embedImage` used
in `processFile`). With that:

- **Emergent-format clustering.** Embed the day's candidates and cluster by
  cosine similarity. A *tight, suddenly-dense* cluster — the same face/template
  reused across many posts within a short window — is an emerging meme format,
  even with zero text describing it. (Cosine search is already implemented for
  the library; this reuses the same math on the candidate pool.)
- **Novelty vs. the existing library.** Compare a candidate's embedding to the
  user's indexed memes. *Low* max-similarity = a visual the collection has never
  seen = genuinely new, worth surfacing. *High* similarity to an existing meme =
  a near-dup repost, skip.

So the merit signal is: **high virality (A) + part of a dense fresh visual
cluster (B) + low similarity to what you already own.** A meme can clear all
three thresholds while still being completely anonymous. Naming can happen
**later** — it's searchable by vibe/visual immediately, and the existing
*teach-by-example* flow (README "Culture layer") lets the user christen the
format in seconds, retroactively tagging the whole cluster.

### Name-based search as a *secondary* path

When a name *does* exist, use it. A lightweight detector reads trend text —
Reddit `rising` titles, Know Your Meme's "Newsfeed"/trending RSS, Google Trends
RSS — extracts candidate phrases, and runs them as searches against media
sources (Tenor/Giphy search, Reddit search) to pull canonical examples. This is
the fast path for the named case; it is explicitly *not* the only path, because
of the constraint above.

---

## What about X (Twitter)?

The example meme lived on X, so it's worth being blunt about it:

- There is **no free, reliable, ToS-clean way to read X programmatically** today.
  The API is paid and rate-limited; unofficial scrapers and Nitter-style mirrors
  are fragile and frequently down.
- Therefore X is **not** a viable on-device first-party source. The realistic
  substitute is the Reddit/Giphy/Tenor/KYM **re-host layer**, which carries the
  same memes within hours.
- If first-party X coverage is ever a hard requirement, it belongs behind an
  **optional, user-supplied** path — the user brings their own API token or
  points the app at a small self-hosted relay — never baked-in scraping. Treat
  it as a pluggable source like any other (see interface below), not a core
  dependency.

---

## Architecture

One new module, `src/discovery.ts`, plus a thin source-adapter layer. Sources
are pluggable behind one interface so adding/removing a feed never touches the
engine.

```ts
// A raw candidate from some feed, before we decide whether to keep it.
interface Candidate {
  sourceId: string;       // stable id for dedup, e.g. "reddit:t3_abc123"
  mediaUrl: string;       // direct image/gif/mp4 URL
  kind: 'image' | 'video';
  title: string;          // post title / caption — feeds OCR-adjacent text + naming
  score: number;          // raw engagement
  createdAt: number;      // epoch seconds, for velocity
  permalink?: string;
}

interface MemeSource {
  id: string;                                  // "reddit", "tenor", ...
  fetchTrending(): Promise<Candidate[]>;       // velocity feed
  search?(query: string): Promise<Candidate[]>;// optional, for the named path
}

// The engine:
async function runDiscovery(api: EmbeddingsApi, opts): Promise<DiscoveryResult> {
  // 1. gather    — Promise.allSettled over enabled sources
  // 2. prefilter — drop already-seen sourceIds; apply velocity threshold
  // 3. download   — fetch media to cache (size/type/duration caps)
  // 4. embed      — api.embedImage on each (videos: thumbnail first, as today)
  // 5. score      — virality + cluster density + novelty-vs-library
  // 6. accept     — top-N over a merit threshold
  // 7. ingest     — saveToFolder(...) → indexSavedFiles(api, saved)  // REUSE
  // 8. record     — mark every candidate sourceId as seen (accepted or not)
}
```

### Initial source adapters

| Source | Key? | Role | Returns |
|---|---|---|---|
| **Reddit** | none | Primary virality + re-host capture | image / gif / mp4 |
| **Tenor** (Google) | free key | Named-phrase fetcher | gif / mp4 |
| **Giphy** | free key | Second named-phrase fetcher | gif / mp4 |
| **KYM / Trends RSS** | none | *Name detector* only (no media) | trend phrases → fed to search |
| *X* | user token | Optional, off by default | — |

Reddit-only is the zero-config starting point and already exercises the full
engine (virality + visual novelty). The rest are additive.

---

## Data-model changes (`src/db.ts`)

Small and migration-friendly (the schema already does additive
`ALTER TABLE … ADD COLUMN` migrations, e.g. `extra_terms`).

1. **Dedup discovered items.** Add `source_id TEXT` to `memes` (nullable; null
   for folder/shared memes) with a unique index. Prevents re-adding the same
   trending post on every poll.
2. **Remember rejects.** New table so a candidate we *looked at and declined*
   isn't re-downloaded forever:
   ```sql
   CREATE TABLE IF NOT EXISTS discovery_seen (
     source_id TEXT PRIMARY KEY,
     verdict   TEXT NOT NULL,      -- 'accepted' | 'rejected'
     score     REAL NOT NULL,
     seen_at   INTEGER NOT NULL
   );
   ```
3. **Provenance (nice-to-have).** `source_url TEXT` on `memes` so a discovered
   meme can link back to where it came from in the detail view.

`MemeRecord` (src/types.ts) gains optional `sourceId?` / `sourceUrl?`.

---

## Triggering it

- **v1 — manual + on-open.** A "Fetch trending now" button plus an opt-in
  "check on app open (max once / N hours)" toggle in Settings. No new native
  deps; runs the same in-app async flow as the share importer.
- **v2 — true background.** Add `expo-background-fetch` + `expo-task-manager`
  to poll on a schedule while the app is closed. Deferred because it needs
  native config and careful battery/data behaviour. The engine itself is
  trigger-agnostic, so this is purely additive.

---

## UI sketch

- A **Discover** surface (new tab, or a section in Settings) listing what the
  last run pulled in: thumbnail, source, virality score, "why" (velocity /
  novel cluster), and **Keep / Discard**. A *review queue* rather than silent
  auto-add is safer given the content-quality and copyright realities below.
- Per-run summary banner reusing the existing toast/banner component
  (`ShareReceiver`'s pattern).
- Settings: master on/off (default **off**), source toggles, API-key fields,
  subreddit/seed-query list, "max adds per run", "NSFW filter on/off", and a
  cellular-vs-wifi-only switch.

---

## The tradeoffs to accept consciously

This feature **changes what Memeget is**, so it can't be slipped in silently.

- **It breaks the headline promise.** The README's first lines are *"No
  accounts. No servers. No uploads."* and *"makes no network calls at
  runtime."* Discovery is outbound network. It must be **opt-in, off by
  default, and clearly labelled**, and the README's "Privacy / network honesty"
  section needs an explicit carve-out: *indexing & search stay 100% local;
  discovery, when you turn it on, makes outbound requests to the feeds you
  enable.* Nothing about the *user* is uploaded — but airplane-mode purity is
  gone the moment it's enabled.
- **Content quality & safety.** The open web includes NSFW, gore, and
  harassment. Need a default-on NSFW filter (Reddit's `over_18` flag; source
  safety params), size/duration caps, and a human Keep/Discard step before
  anything lands.
- **Copyright / ToS.** Auto-collecting third-party media has the usual reposting
  caveats. Personal/offline use is the gentlest case; respect each source's ToS
  and rate limits; never hammer a feed.
- **Cost & battery.** Downloading + CLIP-embedding many candidates per poll uses
  data and CPU. Hence wifi-only option, per-run caps, and the `discovery_seen`
  table so work isn't repeated.

---

## Failure modes & guards

- Sources behind `Promise.allSettled` — one dead feed never sinks a run.
- Media caps (max bytes, max video seconds, allowed mime types) before download.
- All downloads land in cache and are cleaned in a `finally`, exactly like
  `processFile` does today.
- Velocity + novelty thresholds tunable; start conservative to avoid flooding
  the library with low-effort reposts.
- Everything funnels through `processFile`, so its existing error logging
  (`index_errors`) covers discovered items too.

---

## Phased roadmap

1. **Engine + Reddit, backend only.** `discovery.ts`, `MemeSource`, `Candidate`,
   `runDiscovery`, Reddit adapter, `source_id` dedup, reuse `indexSavedFiles`.
   Test via a dev button. *(No UI, no new promises broken until shipped.)*
2. **Review queue UI + Settings opt-in** with the NSFW filter and caps.
3. **Visual novelty engine** — candidate clustering + novelty-vs-library scoring
   (the part that catches unnamed formats).
4. **Named-path sources** — Tenor/Giphy search + KYM/Trends detector.
5. **Background polling** — `expo-background-fetch`.
6. **README/privacy update** ships *with* whatever first exposes network.

---

## Open questions

- Auto-add vs. review-queue as the default? (Recommendation: **review queue**,
  for safety and trust.)
- Which subreddits/seed queries ship as defaults, and are they user-editable?
  (Recommendation: small editable default list.)
- How aggressive should novelty thresholds be out of the box?
- Is first-party X coverage ever a real requirement, or is the re-host layer
  sufficient? (Recommendation: re-host layer; X stays optional/BYO-token.)
