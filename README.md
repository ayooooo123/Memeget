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
| Search by description | **CLIP** (ViT-B/32) via [`react-native-executorch`](https://github.com/software-mansion/react-native-executorch). Images and your text query are embedded into the same vector space; results are ranked by cosine similarity. |
| Meme format / character / emotion tags | **Zero-shot classification** against a curated prompt library (`src/memeLabels.ts`) — Pepe, Wojak, Doomer, Gigachad, Drake format, This Is Fine, etc. This is the editable "knowledge" layer. |
| Quick filters | A slim chip row under the search box: tap **▦ Images / ▶ Videos** to narrow by media type, or tap a known meme tag (the formats/characters actually present in your library, plus your taught labels) to filter without typing. Filters apply to both browse and search. |
| Words in the meme | On-device **OCR** ([`expo-text-extractor`](https://github.com/pchalupa/expo-text-extractor) → ML Kit on Android). |
| Video | A keyframe is extracted (`expo-video-thumbnails`) and indexed like an image. |
| Index storage | `expo-sqlite`; embeddings stored as float32 blobs, brute-force cosine search. |
| Folder access | Android **Storage Access Framework** — per-folder permission, no broad media access. |
| Save from a link | Share an **X/Twitter**, **Tenor**, or any social-post URL into Memeget and it resolves the underlying media (tweet-syndication for X, Open Graph `og:video`/`og:image` for everything else), downloads it into your linked folder, and indexes it like a normal share — no manual download + re-import. |

## Saving from a shared link

Besides sharing image/video *files*, you can share a **link** to a post and let
Memeget fetch the meme for you:

- In X, Tenor, your browser, Reddit, etc., tap **Share → Memeget** (or copy the
  link and share it). The URL doesn't have to be bare — "caption text https://…"
  works; the first URL is used.
- Memeget figures out the actual media: for `x.com`/`twitter.com` it reads X's
  public tweet-syndication endpoint (no login) and picks the highest-quality
  video variant, or the full-res photo; for Tenor and anything else it scrapes
  the page's Open Graph / Twitter-card tags (`og:video`, then `og:image`). A URL
  that already points straight at a `.mp4`/`.gif`/`.jpg`… is downloaded directly.
- It then drops the file into your first linked folder and hands it to the same
  background indexer as any other share — so it picks up CLIP/OCR/VLM tags
  automatically and shows up in search.

This is best-effort: a private/age-gated post, or a site that hides its media
behind JavaScript, may not resolve — you'll get a short "no media found" notice
and nothing is saved.

## Privacy / network honesty

Indexing and search are fully **offline** — no network, no accounts, no uploads.
There are exactly two times the app reaches out, both download-only:

1. A *one-time* download of the CLIP model from Hugging Face on first launch
   (ExecuTorch fetches it, then caches it locally at
   `{documentDirectory}/react-native-executorch/`). After that, you can stay
   airplane-mode forever. To make it *truly* zero-network from install, the model
   can be bundled into the APK assets — see "Next steps".
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

A GitHub Actions workflow (`.github/workflows/android-apk.yml`) builds a
standalone, sideloadable APK on every push to the dev branch and attaches it to
the **`android-latest`** GitHub Release.

1. Open the repo's **Releases** → **Memeget (latest Android build)**.
2. Download the `.apk` on your Android phone.
3. Enable "Install unknown apps" for your browser/Files app, then open it.

The APK is signed with the auto-generated debug key (fine for personal
testing), so it installs without Metro or an EAS account.

## Local development

```bash
npm install
npx expo run:android   # builds a dev client onto a connected device/emulator
```

Requires a custom dev build (not Expo Go) because of the native ML modules.

## Project layout

```
App.tsx                 # tab shell
src/embeddings.tsx      # CLIP image+text hooks + zero-shot classifier
src/indexer.ts          # SAF -> copy -> (thumbnail) -> embed -> OCR -> tag -> store
src/db.ts               # SQLite schema, vector storage, cosine search
src/saf.ts              # Storage Access Framework folder linking
src/linkResolver.ts     # shared X/Tenor/social links -> resolve + download media
src/memeLabels.ts       # curated meme-format/character/emotion prompts
src/screens/            # Library (index), Search, Settings
```

## Culture layer (keeping up with new memes)

A frozen model never knows last week's meme, so the *knowledge* lives outside the
weights — editable and on-device:

- **Association graph** (`src/memeLabels.ts`) — each label carries related terms
  (Milady → Remilia, NFT, Ethereum, neochibi…). Matching a label folds those into
  the meme's searchable text, so "ethereum" finds a Milady meme that never says it.
- **Teach-by-example** — tap any meme → *Teach a label* to name a character/format
  (e.g. "Milady"). It stores that meme's image embedding as an **exemplar**;
  future images are tagged by image-to-image similarity to your exemplars, so the
  model doesn't need to have ever heard the word. New format drops? Teach it in
  seconds.
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
- Multi-frame video sampling (currently one keyframe).
- Recursive folder walking.
- Audio/music recognition (needs an on-device fingerprint DB — deferred).
- `sqlite-vec` for very large collections.
- Incremental/background re-indexing when folders change.
