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
| Words in the meme | On-device **OCR** ([`expo-text-extractor`](https://github.com/pchalupa/expo-text-extractor) → ML Kit on Android). |
| Video | A keyframe is extracted (`expo-video-thumbnails`) and indexed like an image. |
| Index storage | `expo-sqlite`; embeddings stored as float32 blobs, brute-force cosine search. |
| Folder access | Android **Storage Access Framework** — per-folder permission, no broad media access. |

## Privacy / network honesty

The app makes **no network calls at runtime** — indexing and search are fully
offline. The **one** exception is a *one-time* download of the CLIP model from
Hugging Face on first launch (ExecuTorch fetches it, then caches it locally).
After that, you can stay airplane-mode forever. To make it *truly* zero-network
from install, the model can be bundled into the APK assets — see "Next steps".

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
src/memeLabels.ts       # curated meme-format/character/emotion prompts
src/screens/            # Library (index), Search, Settings
```

## Next steps / roadmap

- Bundle the CLIP model in assets for zero-network-from-install.
- Multi-frame video sampling (currently one keyframe).
- Recursive folder walking.
- Audio/music recognition (needs an on-device fingerprint DB — deferred).
- `sqlite-vec` for very large collections.
- Incremental/background re-indexing when folders change.
