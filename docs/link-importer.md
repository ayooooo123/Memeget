# Design: Paste / share a link → import the meme

> Status: **Design / proposal.** No code yet. A much simpler, more reliable
> cousin of [auto-meme-discovery.md](./auto-meme-discovery.md): instead of the
> app *finding* trending memes, the user hands it one link (a tweet, a Reddit
> post, a direct image/video URL) and the app downloads the media and indexes
> it.

## Why this is the tractable version

Auto-discovery is hard because of the *find-what's-trending* half. Paste-a-link
deletes that problem: the user has already decided this specific post is worth
keeping. All that remains is the easy, reliable half — resolve the link to a
media file, download it, and run the **existing** pipeline.

It also sidesteps the thing that made X impossible for auto-discovery. We never
need to read X's timeline or search it — we only need the media off **one known
tweet**, and there are free, purpose-built resolvers for exactly that.

## Ingestion is already built (same as before)

```
download media → saveToFolder()      src/saf.ts:130
              → indexSavedFiles()     src/indexer.ts:345
                → processFile()       src/indexer.ts:251  (embed/OCR/tag/store)
```

`processFile` doesn't care where the bytes came from. So this feature is only:
**(1) turn a link into a media URL, (2) download it to cache, (3) hand it to the
pipeline above.** No new indexing code.

No new dependencies either — `expo-clipboard` (~56.0.4) and `expo-file-system`
(`downloadAsync`) are already in `package.json`.

## Resolving a link to media

A small `src/linkImport.ts` with a resolver per host. Each takes a URL and
returns `{ mediaUrl, kind, title }[]` (a tweet can have up to 4 images).

| Link | How we resolve it | Returns |
|---|---|---|
| **X / Twitter** (`x.com`, `twitter.com`, `/status/<id>`) | `https://api.fxtwitter.com/status/<id>` → JSON with direct photo + **video mp4** URLs. Fallback: vxtwitter, then X's own `cdn.syndication.twimg.com/tweet-result?id=<id>`. | image / mp4 |
| **Reddit** (`/comments/<id>`) | append `.json`; read `url` / `media` / `gallery_data`. | image / gif / mp4 |
| **imgur** | direct media URL from the page/id. | image / gif / mp4 |
| **Direct media URL** (ends in `.jpg/.png/.gif/.mp4/.webm…`) | use as-is. | image / video |

Unknown host → friendly "couldn't find media in that link" message.

```ts
interface ResolvedMedia { mediaUrl: string; kind: 'image' | 'video'; title: string; }

async function resolveLink(url: string): Promise<ResolvedMedia[]>;  // picks a resolver by host

async function importFromLink(api, url): Promise<{ added: number }> {
  const media = await resolveLink(url);                 // 1. link → media URL(s)
  const files = [];
  for (const m of media) {
    const tmp = await FileSystem.downloadAsync(m.mediaUrl, cachePath(m));  // 2. download
    const saved = await saveToFolder(tmp.uri, fileName(m), mime(m), folder.uri);
    files.push(saved);                                  // 3a. into the linked folder
  }
  return indexSavedFiles(api, files);                   // 3b. existing pipeline
}
```

Same media caps as discovery (max bytes / video seconds / allowed mime), and the
same `finally`-cleanup of cache temp files that `processFile` already does.

## Two entry points

1. **Paste a link** — a field/button (Library or Settings; or a `+` in the
   header). On submit, optionally pre-fill from `Clipboard.getStringAsync()` if
   it already holds a URL. Shows the same import banner the share flow uses.
2. **Share a link into Memeget** — the high-value one. From inside the X app,
   *Share → Memeget* sends the **tweet URL as text**, not a file. Today
   `ShareReceiver` only accepts image/video files
   (`ShareReceiver.tsx:43` filters on `^(image|video)/`), so a shared URL is
   silently dropped. Extend it: if the share intent carries text/`webUrl` that
   looks like a supported link, route it through `importFromLink` instead of
   `saveSharedFiles`. Result: share a tweet straight from X into your library,
   no copy-paste.

The two-phase "save instant, index in background" UX from `ShareReceiver` still
applies — the file is permanent the moment `saveToFolder` returns; embed/tag
happens after.

## Data model

Reuse the dedup idea from the discovery doc: optional `source_id` /
`source_url` on `memes` (additive `ALTER TABLE`, like `extra_terms`) so the same
tweet pasted twice isn't added twice, and the detail view can link back to the
original post.

## The tradeoff (smaller than auto-discovery)

- Still a **runtime network call**, so the README's "no network at runtime"
  line needs the same carve-out. But it's *user-initiated, one fetch per link,
  no monitoring* — much milder than background discovery.
- The fx/vx route **relays the tweet ID** to a third-party service. The
  syndication fallback talks to X directly but is undocumented and can change.
  Worth a one-line note in Settings about where the request goes. Nothing about
  the user is uploaded.
- Resolvers are brittle by nature (hosts change shapes). Keep each one small,
  isolated, and fail soft with a clear message; one broken resolver never
  affects the others or the rest of the app.

## Phased rollout

1. **Resolver module + paste field.** `linkImport.ts` with the X resolver +
   direct-URL resolver, wired to `saveToFolder`/`indexSavedFiles`. A paste
   field and the import banner. *(Smallest shippable slice.)*
2. **Share-a-link.** Extend `ShareReceiver` to accept shared URLs.
3. **More resolvers.** Reddit, imgur, etc.
4. **Dedup + provenance** (`source_id`/`source_url`) and the README/privacy note.

## Open questions

- Where does the paste entry point live — Library header `+`, or Settings?
- Auto-detect a URL on the clipboard and offer a one-tap "Import this?" prompt
  when the app opens? (Nice, but slightly magic — opt-in.)
- Resolver order/fallbacks for X: fxtwitter → vxtwitter → syndication, or let
  the user pick a preferred one?
