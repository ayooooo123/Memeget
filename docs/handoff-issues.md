# Handoff: open issues on `claude/gemma-local-vl-model-7viqpi` (build 91 / bec7840)

State as of 2026-07-14. The branch carries the S2+DINO embedding cutover, the
Gemma VLM switch, video posters, whole-video clipboard copy, and the
keep-alive background service. PR #28 tracks it. Two issues are open, plus
known tech debt.

## Issue 1 — Infinite-scroll jitter regressed (likely by the poster work)

Symptom: grid stutters/jitters while flick-scrolling the library.

Almost certainly a regression from the poster pipeline's refresh pattern,
introduced across builds 86–91:

- The poster backfill (`vision.tsx`, poster loop) calls `emitLibraryChanged()`
  after **every batch of ≤24 posters, every ~250ms** while a backlog drains.
- `LibraryScreen` debounces refreshes only 300ms (`scheduleRefresh`), then
  re-fetches the whole loaded span (`getRecentMemes(span)` where span = all
  rows loaded so far — can be hundreds) and merges via `mergeRecords`.
- `sameRecord` now (correctly — commit 9098e16) compares `thumbUri`, so rows
  whose poster just landed get NEW object identities → their memoized
  `GridCell`s re-render and the list re-lays-out — while the user scrolls.
  Before 9098e16 the grid ignored poster changes entirely (bug: posters never
  appeared until app restart), so the jitter was hidden.

Suggested directions (pick one, they compose):
- Lengthen the refresh debounce a lot (2–5s) while a poster drain is active,
  or have the poster loop emit at most every few seconds instead of per batch.
- Suppress refreshes while the user is actively scrolling (FlatList
  onScrollBeginDrag/EndDrag) and flush after.
- Update `recent` rows in place by id (patch `thumbUri` into existing state)
  instead of re-fetching the full span per event.

Relevant code: `src/vision.tsx` (poster loop, emit), `src/screens/LibraryScreen.tsx`
(`refresh`, `scheduleRefresh`, `mergeRecords`, `sameRecord`),
`src/components/MemeGrid.tsx` (`GridCell` memoization).

## Issue 2 — A handful of videos still have no thumbnail

Current poster architecture (all in `src/indexer.ts` + `modules/memeget-bg`):
persistent poster JPEGs in `documentDirectory/thumbs/`, `thumb_uri` column on
`memes`, backfill loop with 3-worker pool. Per video, a 3-way extraction
ladder: (1) native MediaCodec extractor (`VideoFrameExtractor.kt`) in AUTO
mode — duration-proportional offsets, near-black frames rejected by Y-plane
luma; (2) expo-video-thumbnails (MediaMetadataRetriever) off the SAF uri;
(3) MMR on a local file copy. Failures of all three stamp `thumb_uri='failed'`
and log one `[poster]` row in `index_errors` with all three per-decoder
reasons (Expo boilerplate stripped since b87515b).

Remaining sub-cases:

1. **~5 files fail all three decoders** (`tweet_20728…`, `tweet_20753…`,
   `tweet_20746…`, `tweet_20757…`.mp4 etc. — see Settings → Index →
   "Indexing errors"). The readable reasons have NOT been captured yet: tap
   "Retry N failed posters" on build ≥91 and read the `[poster]` rows. If they
   say `open failed`/`no video track`/`stream ended`, the files are corrupt
   downloads and the filmstrip-stub tile is the intended final state. Check
   whether they PLAY in the viewer (ExoPlayer) — if they play but won't
   decode a frame, the extractor has a real bug for that container.
2. **Stuck pending share-ins** (spinner tiles): fixed in build 91 by an
   auto-recovery sweep (`indexPendingMemes`, driven from `vision.tsx`) —
   verify it clears them within ~1 min of app open; they previously required
   a manual Index.
3. **Timeout-benched rows**: extraction attempts that time out (20s) under
   CPU contention sit out for a 10-min cooldown (`thumbSkip` in indexer),
   then retry. If posters seem stuck, check Settings → Index "Video posters"
   row: `queued` = waiting/benched, `failed` = stamped undecodable.

Diagnostics available in-app (Settings → Index): poster coverage row
(done/total · failed · queued), "Retry failed posters" button, and the
indexing-errors list with per-file, per-decoder reasons.

## Known tech debt / must-do before merge

- **Remove the TEMPORARY branch push triggers** from
  `.github/workflows/android-apk.yml` and `.github/workflows/export-models.yml`
  (entries for `claude/gemma-local-vl-model-7viqpi`) — they exist only because
  `workflow_dispatch` is unavailable to the integration.
- int8 quantization of the S2/DINO exports is parked (nine failed CI attempts
  against executorch 1.0's quant stack; eager cosine gate kept the broken
  results from shipping — see notes in `tools/model-export/export_mobileclip_s2.py`).
- The keep-alive service is a `dataSync` FGS capped at ~6h/day by Android; a
  fully-native headless indexer is the long-term path (note in
  `KeepAliveService.kt`).
- Multi-frame video sampling for embeddings (posters already pick good frames;
  the EMBED frame is still a fixed t=1s grab in `prepareFile`).
