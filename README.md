# Memeget

A private, **on-device** meme indexer & semantic search app. Link a folder of
memes (images + videos) and Memeget builds a searchable index entirely on your
phone — so you can find memes by describing them ("crying wojak in the rain"),
by the format/character ("gigachad", "distracted boyfriend"), by the words
written on them, or by vibe.

No accounts. No servers. No uploads.

## How it works

| Capability | What powers it (all on-device) |
|---|---|
| Search by description | **Hybrid CLIP retrieval** via [`react-native-executorch`](https://github.com/software-mansion/react-native-executorch). Every query is matched against the meme's image vector, and AI-described memes also get a CLIP text vector from their caption/tags/search terms. Results use the stronger of image↔text and text↔text similarity, plus literal keyword/OCR boosts. |
| Meme format / character / emotion tags | **Zero-shot classification** against a curated prompt library (`src/memeLabels.ts`) — Pepe, Wojak, Doomer, Gigachad, Drake format, This Is Fine, etc. This is the editable "knowledge" layer. |
| Quick filters | A slim chip row under the search box: tap **▦ Images / ▶ Videos** to narrow by media type, or tap a known meme tag (the formats/characters actually present in your library, plus your taught labels) to filter without typing. Filters apply to both browse and search. |
| Words in the meme | On-device **OCR** ([`expo-text-extractor`](https://github.com/pchalupa/expo-text-extractor) → ML Kit on Android). |
| Video | Multiple frames are sampled across the clip (`expo-video-thumbnails`), visually-identical frames collapsed, and each distinct moment analyzed — the primary embeddings mean-pooled into one "gist" vector, OCR text unioned, and tags merged. So a caption or character that only appears partway through is still found. The heavier VLM caption pass describes each distinct scene (stopping once frames stop changing) and folds them together. |
| Words *said* in the meme | Opt-in **audio analysis** (Settings): a native decoder (MediaCodec) pulls each video's audio track as 16 kHz PCM and on-device **Moonshine** (its encoder/decoder driven over the same ExecuTorch runtime) transcribes it. Moonshine's compute scales with clip length instead of Whisper's fixed 30 s window, so short meme audio transcribes faster. The transcript shows in the viewer and is searchable — find a clip by the line you remember hearing. |
| Similar memes | Open any meme → **More like this**: the library ranked by stored visual vectors. By default this uses CLIP cosine similarity; if a custom DINOv2 visual model is configured, DINO vectors are stored/backfilled and used for this flow. Tap a thumbnail to hop to it — great for finding the other variants of a template. |
| Bulk actions | **Long-press** a thumbnail to enter selection mode, tap to add/remove memes (or **All**), then act on the whole set from the bottom bar: **Tag** them all with one label at once, or **Delete** them together. A bulk tag is a first-class user tag — it's searchable and survives re-tagging. The tag sheet's **Spread to look-alikes** toggle (on by default) also propagates the tag to the library's near-identical memes — other crops/variants of the same template, matched by the stored visual vectors (DINOv2 when configured, else CLIP) above a strict same-template similarity bar. |
| Index storage | `expo-sqlite`; image and caption embeddings stored as float32 blobs, brute-force cosine search. |
| Folder access | Android **Storage Access Framework** — per-folder permission, no broad media access. |
| Save from a link | Share an **X/Twitter**, **Tenor**, **memedepot**, or any social-post URL into Memeget and it resolves the underlying media (tweet-syndication for X, Open Graph `og:video`/`og:image` — with an embedded-media fallback — for everything else), downloads it into your linked folder, and indexes it like a normal share — no manual download + re-import. |
| Import from a `.zip` | **Share a .zip into Memeget** (or Settings → *Index* → **Import from a .zip**): pulls every supported image/video out of the archive into your linked folder, skips anything already there (by filename), indexes the rest in the background, and discards the archive — the same two-phase path as any other share. |

## Saving from a shared link

Besides sharing image/video *files*, you can share a **link** to a post and let
Memeget fetch the meme for you:

- In X, Tenor, [memedepot](https://memedepot.com/), your browser, Reddit, etc.,
  tap **Share → Memeget** (or copy the link and share it). The URL doesn't have
  to be bare — "caption text https://…" works; the first URL is used.
- Memeget figures out the actual media: for `x.com`/`twitter.com` it reads X's
  public tweet-syndication endpoint (no login) and picks the highest-quality
  video variant, or the full-res photo; for Tenor, memedepot, and anything else
  it scrapes the page's Open Graph / Twitter-card tags (`og:video`, then
  `og:image`), and — for JS-rendered galleries that emit no such tags — falls
  back to an inline `<video>`/`<source>` element or a direct media URL embedded
  in the page. A URL that already points straight at a `.mp4`/`.gif`/`.jpg`… is
  downloaded directly.
- It then drops the file into your first linked folder and hands it to the same
  background indexer as any other share — so it picks up CLIP/OCR/VLM tags
  automatically and shows up in search.

This is best-effort: a private/age-gated post, or a site that hides its media
behind JavaScript, may not resolve — you'll get a short "no media found" notice
and nothing is saved.

## Importing a `.zip` of memes

Got a whole archive of memes (a backup, a shared pack, a download dump)? Just
**share the `.zip` into Memeget** — from your Files app, a chat, a browser
download, wherever — and it imports the lot, then throws the archive away. (No
zip handy in a share sheet? **Settings → Index → Import from a .zip** opens a
file picker for the same thing.)

- Memeget reads the archive on-device (via [`jszip`](https://stuk.github.io/jszip/))
  and pulls out only the formats it handles — the same image/video extensions the
  folder scan accepts (`jpg`, `png`, `gif`, `webp`, `heic`, `mp4`, `webm`, …).
  Anything else (PDFs, text, audio) is left behind and reported as *unsupported*.
- **Duplicates are skipped**: any entry whose filename already exists in your
  linked folder — or appears twice in the archive — is not imported again, so
  re-importing the same zip is a no-op. Nested folders inside the zip are
  flattened by filename, and macOS/Windows archive cruft (`__MACOSX`,
  `.DS_Store`, `Thumbs.db`) is ignored.
- Everything that survives is copied into your **first linked folder** and handed
  to the same background indexer as a share — so it picks up CLIP/OCR/VLM tags
  automatically and shows up in search. You'll get a summary like
  *"Imported 42 memes from zip — indexing in background (3 dups skipped)."*
- The shared archive itself is **discarded** once its memes are extracted — only
  the individual memes are kept, as normal files in your folder.

You need a linked folder first (it's where the imported files live). Nothing is
uploaded — the archive is read entirely on your device.

## Privacy / network honesty

Indexing and search are fully **offline** — no network, no accounts, no uploads.
There are exactly two times the app reaches out, both download-only:

1. A *one-time* download of the CLIP model from Hugging Face on first launch
   (ExecuTorch fetches it, then caches it locally at
   `{documentDirectory}/react-native-executorch/`). Opting in to AI descriptions
   or audio analysis (Settings) likewise triggers a one-time download of that
   model (Gemma 4 E2B / Moonshine), cached the same way. After that, you can stay
   airplane-mode forever. To make it *truly* zero-network from install, the model
   can be bundled into the APK assets — see "Next steps". Developer builds can
   also point Memeget at custom MobileCLIP-S2 and DINOv2 ExecuTorch exports via
   `EXPO_PUBLIC_MEMEGET_*` model-source variables; those are not bundled in the
   default APK.
2. **Only when you share a link** (see above), Memeget contacts that link's host
   (plus X's public syndication endpoint for tweets) to download the media you
   asked for. No URL is fetched unless you explicitly share one, and nothing
   about you or your library is sent — it's a plain download.

**Online only once — across updates too.** The cached model and the SQLite index
both live in the app's internal storage, which Android keeps across an app update
**as long as the new APK is signed with the same key** and is installed *over* the
existing app (don't uninstall first). To guarantee the key never drifts between
builds, the Android signing key is pinned: a fixed `signing/debug.keystore` (the
standard Android debug key) is copied into the generated project on every
`prebuild` by `plugins/withFixedDebugKeystore.js`. So updating the app never
re-downloads the model or re-indexes your library. (Uninstalling or "Clear data"
*will* wipe both — that's the only thing that forces a re-download.)

## Getting the APK

Every release is a standalone, sideloadable APK built by GitHub Actions
(`.github/workflows/android-apk.yml`). Two channels:

- **Versioned releases** — tagged `vX.Y.Z` (e.g. `v0.2.0`), permanent, with a
  `memeget-vX.Y.Z.apk` you can always come back to. This is the real history.
- **`latest`** — a single rolling pre-release that tracks the newest build on
  `main`. Grab `memeget-latest.apk` when you just want the current bits.

1. Open the repo's **Releases** and pick a version (or **latest**).
2. Download the `.apk` on your Android phone.
3. Enable "Install unknown apps" for your browser/Files app, then open it.

The APK is signed with the auto-generated debug key (fine for personal
testing), so it installs without Metro or an EAS account.

### Cutting a release

Releases are driven entirely by git tags — no manual upload:

```bash
# bump the version in app.json / package.json first, then:
git tag v0.2.0
git push origin v0.2.0
```

The workflow stamps that version into the build (`versionName`/`versionCode`),
builds the APK, and publishes the `v0.2.0` GitHub Release. Pushing to `main`
(or running the workflow manually) refreshes the rolling `latest` pre-release
instead. Keep `version` in `app.json` and `package.json` in sync with the tag.

## Local development

```bash
npm install
npx expo run:android   # builds a dev client onto a connected device/emulator
```

Requires a custom dev build (not Expo Go) because of the native ML modules.

## Project layout

```
App.tsx                 # tab shell
src/embeddings.tsx      # CLIP/custom primary hooks + optional DINO visual hook
src/searchCore.ts       # Hybrid image/caption retrieval scoring
src/indexer.ts          # SAF -> copy -> (thumbnail) -> embed -> OCR -> tag -> store
src/audio.tsx           # Moonshine transcription pass over video memes (opt-in)
src/audioCore.ts        # React-free audio core: model sources, greedy decode loop, PCM decode, cleanup
src/db.ts               # SQLite schema, vector storage, cosine search
src/saf.ts              # Storage Access Framework folder linking
src/linkResolver.ts     # shared X/Tenor/social links -> resolve + download media
src/zipImport.ts        # .zip archive -> filter compatible media, skip dups, save
src/memeLabels.ts       # curated meme-format/character/emotion prompts
src/screens/            # Library (index), Search, Settings
modules/memeget-bg/     # native module: power/thermal, keep-alive, SAF mtime,
                        #   video audio-track -> 16 kHz PCM decoder
```

## Culture layer (keeping up with new memes)

A frozen model never knows last week's meme, so the *knowledge* lives outside the
weights — editable and on-device:

- **Association graph** (`src/memeLabels.ts`) — each label carries related terms
  (Milady → Remilia, NFT, Ethereum, neochibi…). Matching a label folds those into
  the meme's searchable text, so "ethereum" finds a Milady meme that never says it.
- **Teach-by-example** — tap any meme → *Teach a label* to name a character/format
  (e.g. "Milady"). It stores that meme's image embedding as an **exemplar**;
  future images are matched by a per-label classifier trained on your examples
  (`src/learnCore.ts`), so the model doesn't need to have ever heard the word.
  New format drops? Teach it in seconds. The learner is built for the few-shot
  case: library samples that look like your examples are excluded from the
  negatives (so teaching a label that's *common* in your library can't backfire),
  each label's acceptance threshold is calibrated against your actual library,
  a nearest-exemplar pathway catches close template variants from even a single
  example, and your "NOT this" corrections veto it. After each teach, Memeget shows the **look-alikes** from your library —
  tap the ones that are also that label and every confirmation becomes another
  example, so one teach round yields a sharp label instead of a single-example
  guess. The teach sheet also suggests the meme's **own tags** as label names.
- **Hold a tag to confirm or fix it** — in the viewer, long-press any tag chip and
  say whether it's right (✓ teaches it as a positive example) or wrong (✗ teaches
  a "NOT this" correction). Model guesses become your ground truth one hold at a
  time.
- **Taught-knowledge list** (Settings → *Taught knowledge*) — every tag you've
  taught, with how many examples back it and how many memes currently carry it.
  Each tag shows a **you / pack / you + pack** chip so you can tell your own
  teaching from imported knowledge at a glance. Tap ✕ to forget a tag.
- **Teaching packs** (Settings → *Export / Import*) — share your taught tags as a
  small JSON pack so other collectors inherit your meme knowledge instantly.
  On import you choose **Merge** (add to your tags, skipping duplicates) or
  **Replace all** (wipe everything and start from the pack); then re-tag to apply.
  Packs carry the CLIP model + dimension stamp and are rejected on import if they
  don't match, so vectors are always comparable. Device-local source paths are
  stripped from exports.
- **Pack management** (Settings → *Imported packs*) — imported packs are listed
  with their tag/example counts and provenance, and each can be removed as a unit
  (✕) without touching your own teaching.
- **Re-tag library** (Settings) — re-applies all current knowledge to everything
  already indexed, reusing stored embeddings (no re-scanning/re-embedding).

## Next steps / roadmap

- Bundle the CLIP model in assets for zero-network-from-install.
- Modern embedding backends: model-space stamps are centralized for a future
  MobileCLIP-S2 migration, and the DB/search path has a DINOv2 visual-similarity
  slot that currently falls back to CLIP until a compatible export exists. See
  `docs/embedding-roadmap.md`.
- Recursive folder walking.
- Music *recognition* (Shazam-style fingerprinting needs an on-device fingerprint
  DB — deferred; speech transcription shipped, see Audio analysis above).
- `sqlite-vec` for very large collections.
- Incremental/background re-indexing when folders change.

## License

[MIT](LICENSE) — do whatever you want, no warranty. The bundled model weights
and any third-party assets are under their own respective licenses.
