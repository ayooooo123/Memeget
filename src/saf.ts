// Storage Access Framework helpers: let the user link an arbitrary on-device
// folder, enumerate its media, and copy individual files into the app cache so
// native modules (CLIP, OCR, thumbnailer) get a stable file:// path to work
// with. Uses the stable legacy FileSystem API for SAF + copy operations.
import * as FileSystem from 'expo-file-system/legacy';
import { File, FileMode } from 'expo-file-system';
import * as VideoThumbnails from 'expo-video-thumbnails';

import { getFileModifiedTime } from '../modules/memeget-bg';
import { extForMime, extOf, kindOf, mimeForName, videoMimeFor } from './mediaFormats';
import { utf8Length } from './zipWriter';
import { hashFileSample } from './contentHash';

// Re-exported so existing importers (MemeGrid, the zip importer) can keep
// pulling these off the SAF module; the definitions now live in the pure,
// dependency-free mediaFormats module so they're unit-testable in isolation.
export { kindOf, mimeForName, videoMimeFor };

export interface SafFile {
  uri: string; // content:// uri
  name: string;
  kind: 'image' | 'video';
}

export interface PickedFolder {
  uri: string;
  name: string;
}

const SAF = FileSystem.StorageAccessFramework;

// SAF content URIs encode the document id (including filename) in the path.
// Decode and pull the trailing segment to recover a display name + extension.
export function nameFromContentUri(uri: string): string {
  try {
    const decoded = decodeURIComponent(uri);
    const lastColon = decoded.lastIndexOf(':');
    const tail = lastColon >= 0 ? decoded.slice(lastColon + 1) : decoded;
    const lastSlash = tail.lastIndexOf('/');
    return lastSlash >= 0 ? tail.slice(lastSlash + 1) : tail;
  } catch {
    return uri;
  }
}

// Prompt the user to grant access to a folder. Returns the tree URI + a label.
export async function pickFolder(): Promise<PickedFolder | null> {
  const res = await SAF.requestDirectoryPermissionsAsync();
  if (!res.granted) return null;
  const uri = res.directoryUri;
  const name = nameFromContentUri(uri) || 'Linked folder';
  return { uri, name };
}

// List media files directly inside a linked folder (non-recursive).
export async function listMedia(folderUri: string): Promise<SafFile[]> {
  const children = await SAF.readDirectoryAsync(folderUri);
  const out: SafFile[] = [];
  for (const childUri of children) {
    const name = nameFromContentUri(childUri);
    const kind = kindOf(name);
    if (kind) out.push({ uri: childUri, name, kind });
  }
  return out;
}

// ---- sidecar directory access -------------------------------------------------
//
// The `.memeget` folder lives inside the user's own linked folder (see
// sidecar.ts for why). listMedia above never sees it — it is a directory, and
// directories carry no media extension for kindOf to match — so mirroring
// knowledge into the folder can't feed it back to the indexer as memes.

// Resolve a child by display name inside a SAF directory, or null when absent.
// SAF gives no "does this path exist" call: a document is found by listing the
// parent and matching names, and createFileAsync/makeDirectoryAsync will
// silently mint a SECOND document ("library-00 (1).json") if handed a name that
// already exists. Every write therefore looks the child up first and reuses its
// uri.
export async function findChild(dirUri: string, name: string): Promise<string | null> {
  const children = await SAF.readDirectoryAsync(dirUri);
  for (const childUri of children) {
    if (nameFromContentUri(childUri) === name) return childUri;
  }
  return null;
}

export async function listChildNames(dirUri: string): Promise<string[]> {
  const children = await SAF.readDirectoryAsync(dirUri);
  return children.map(nameFromContentUri);
}

// The sidecar directory inside a linked folder, creating it when `create` is
// set. Returns null when it doesn't exist (nothing to restore) or when the
// provider refuses — a linked folder the user has since revoked, or one on a
// read-only provider, must degrade to "no sidecar", never to a crash on a
// background sync.
export async function sidecarDir(
  folderUri: string,
  dirName: string,
  create: boolean
): Promise<string | null> {
  try {
    const existing = await findChild(folderUri, dirName);
    if (existing) return existing;
    if (!create) return null;
    return await SAF.makeDirectoryAsync(folderUri, dirName);
  } catch {
    return null;
  }
}

// Overwrite (or create) a UTF-8 text document in a SAF directory.
//
// SAF writes DO NOT truncate. expo-file-system opens the document with
// ContentResolver mode "w", and ExternalStorageProvider honours that literally:
// bytes are overwritten from offset 0 and anything the previous, longer content
// left behind stays. Rewriting a chunk that shrank therefore produced a file
// holding valid JSON followed by the tail of the old JSON — which JSON.parse
// rejects outright, so the whole chunk silently restored as nothing. A backup
// that quietly stops being readable is worse than no backup.
//
// The fix is to pad the new content with trailing spaces up to the byte length
// of what was there before. JSON.parse ignores trailing whitespace, so the file
// stays valid, the stale tail is overwritten, and — unlike delete-then-recreate
// — there is never an instant where the document does not exist. `previousBytes`
// comes from the caller's manifest when known; otherwise the old content is read
// back to measure it.
export async function writeSidecarFile(
  dirUri: string,
  fileName: string,
  text: string,
  previousBytes?: number
): Promise<void> {
  const uri = await findChild(dirUri, fileName);
  if (!uri) {
    // createFileAsync appends the extension implied by the mime type, so it gets
    // the basename — the same dance writeBase64ToFolder does. A brand-new document has
    // no stale tail, so it needs no padding.
    const dot = fileName.lastIndexOf('.');
    const created = await SAF.createFileAsync(
      dirUri,
      dot > 0 ? fileName.slice(0, dot) : fileName,
      'application/json'
    );
    await FileSystem.writeAsStringAsync(created, text);
    return;
  }
  let stale = previousBytes;
  if (stale === undefined) {
    const old = await FileSystem.readAsStringAsync(uri).catch(() => '');
    stale = utf8Length(old);
  }
  const fresh = utf8Length(text);
  // Spaces are one byte each, so the count is exact.
  await FileSystem.writeAsStringAsync(uri, stale > fresh ? text + ' '.repeat(stale - fresh) : text);
}

// Read a sidecar document as text, or null when it isn't there / can't be read.
export async function readSidecarFile(dirUri: string, fileName: string): Promise<string | null> {
  try {
    const uri = await findChild(dirUri, fileName);
    if (!uri) return null;
    return await FileSystem.readAsStringAsync(uri);
  } catch {
    return null;
  }
}

// Best-effort last-modified time (ms since epoch) for a linked file, so the
// library can order by when a meme was actually added to the device rather than
// when we happened to index it.
//
// Reads the SAF DocumentFile's lastModified() directly in native code (see
// modules/memeget-bg): expo-file-system doesn't reliably surface this for SAF
// content:// URIs — legacy getInfoAsync never sets modificationTime for them at
// all — which is why earlier attempts left every meme without a time and the
// library fell back to index order. Falls back to the new `File` API where the
// native module isn't built in, then to null so the caller uses the index time.
export function getModifiedTime(uri: string): number | null {
  const native = getFileModifiedTime(uri);
  if (native != null) return Math.round(native);
  try {
    const t = new File(uri).modificationTime;
    if (typeof t === 'number' && t > 0) return Math.round(t);
  } catch {
    // best-effort; caller falls back to the index time
  }
  return null;
}

// Copy a SAF file into the cache directory and return a file:// path the native
// modules can read. Caller is responsible for deleting it afterwards.
//
// The name must be unique PER CALL, not per queue index: the index pipeline,
// the DINO backfill, and the VLM enrichment loop can all be materializing
// frames concurrently, and an index-keyed name let two passes silently clobber
// (and then delete) each other's temp files mid-read. The sweep prefix
// ('meme_work_') still matches for stale-cache cleanup.
export async function copyUriToCachePath(source: string, destination: string): Promise<void> {
  await FileSystem.copyAsync({ from: source, to: destination });
}

let workSeq = 0;
export async function copyToCache(file: SafFile, index: number): Promise<string> {
  const ext = extOf(file.name) || (file.kind === 'video' ? 'mp4' : 'jpg');
  const dest = `${FileSystem.cacheDirectory}meme_work_${++workSeq}_${index}.${ext}`;
  await copyUriToCachePath(file.uri, dest);
  return dest;
}

// Persisted video posters live in the DOCUMENTS dir, not the cache: the OS may
// purge the cache at will (and our own launch sweep does), but a poster must
// survive as long as its meme row references it. Small jpegs, one per video.
const THUMBS_DIR = `${FileSystem.documentDirectory}thumbs/`;
let thumbSeq = 0;

// Copy an extracted poster jpeg into permanent storage and return its path
// (what gets stored in the meme row's thumb_uri).
export async function persistThumb(srcJpeg: string): Promise<string> {
  await FileSystem.makeDirectoryAsync(THUMBS_DIR, { intermediates: true }).catch(() => {});
  const dest = `${THUMBS_DIR}thumb_${Date.now()}_${++thumbSeq}.jpg`;
  await FileSystem.copyAsync({ from: srcJpeg, to: dest });
  return dest;
}

// Delete posters no longer referenced by any meme row (deleted memes, cleared
// index). Best-effort; runs after an index pass, when the reference set is
// fresh. Returns how many it removed.
export async function sweepOrphanThumbs(keep: Set<string>): Promise<number> {
  try {
    const entries = await FileSystem.readDirectoryAsync(THUMBS_DIR);
    const stale = entries.filter((name) => !keep.has(THUMBS_DIR + name));
    await Promise.all(
      stale.map((name) => FileSystem.deleteAsync(THUMBS_DIR + name, { idempotent: true }).catch(() => {}))
    );
    return stale.length;
  } catch {
    // dir doesn't exist yet, or listing failed — nothing to reclaim
    return 0;
  }
}

// Copy any SAF/content:// uri into the cache as a stable file:// path so native
// share sheets (which can't stream a raw content uri) have a real file to send.
export async function materialize(uri: string, name: string): Promise<string> {
  const ext = extOf(name) || 'jpg';
  const safe = (name || `meme.${ext}`).replace(/[^a-zA-Z0-9._-]/g, '_');
  const dest = `${FileSystem.cacheDirectory}share_${Date.now()}_${safe}`;
  await FileSystem.copyAsync({ from: uri, to: dest });
  return dest;
}

// Read a SAF/content:// image as a base64 string (no data-URI prefix) so it can
// be placed on the system clipboard via expo-clipboard's setImageAsync. Copies
// to a temp file first (content:// can't always be read directly), then cleans
// up. Returns the base64 payload.
export async function readImageBase64(uri: string, name: string): Promise<string> {
  const dest = await materialize(uri, name);
  try {
    return await FileSystem.readAsStringAsync(dest, {
      encoding: FileSystem.EncodingType.Base64,
    });
  } finally {
    await FileSystem.deleteAsync(dest, { idempotent: true });
  }
}

// Grab a representative frame from a video and return it as base64 (no data-URI
// prefix). Fallback for copying a video when the native file-clipboard module
// (memeget-bg's copyFileToClipboard) isn't built in — expo-clipboard itself can
// only hold images, so without native support copying a video means copying a
// still frame you can paste anywhere. Same path the indexer uses to thumbnail
// videos. Materializes the content:// uri to a temp file first (the thumbnailer
// needs a real file path), then cleans both up.
export async function readVideoFrameBase64(uri: string, name: string): Promise<string> {
  const file = await materialize(uri, name);
  let thumb: string | null = null;
  try {
    // Retried: Android caps concurrent codec instances, and by the time the
    // user hits Copy the viewer's own player holds one and the background
    // loops (DINO backfill, describes) may hold others — the first attempt can
    // fail purely from contention. Later attempts run after the interactive
    // stand-down has let those loops yield. t=0 also covers sub-second clips
    // where seeking to 1000ms has nothing to decode.
    const attempts = [
      { time: 1000, delayMs: 0 },
      { time: 0, delayMs: 400 },
      { time: 0, delayMs: 1500 },
    ];
    let lastErr: unknown = null;
    for (const a of attempts) {
      if (a.delayMs) await new Promise<void>((resolve) => setTimeout(resolve, a.delayMs));
      try {
        const { uri: t } = await VideoThumbnails.getThumbnailAsync(file, { time: a.time });
        thumb = t;
        return await FileSystem.readAsStringAsync(t, {
          encoding: FileSystem.EncodingType.Base64,
        });
      } catch (e) {
        lastErr = e;
      }
    }
    throw lastErr;
  } finally {
    await FileSystem.deleteAsync(file, { idempotent: true }).catch(() => {});
    if (thumb) await FileSystem.deleteAsync(thumb, { idempotent: true }).catch(() => {});
  }
}

let importSeq = 0;

// Stage a shared source (a content:// provider uri or a file:// cache copy)
// into the OS cache as a plain file:// path, WITHOUT ever reading it into JS.
// copyAsync streams the bytes natively, so a multi-hundred-MB video never
// becomes a base64 string on the JS heap — which is what OOM'd and froze the
// app when a video was shared in. The caller deletes the returned path when done.
export async function stageSourceToCache(src: string): Promise<string> {
  const norm = src.startsWith('file://') || src.startsWith('content://') ? src : `file://${src}`;
  const tmp = `${FileSystem.cacheDirectory}import_${Date.now()}_${++importSeq}`;
  await FileSystem.copyAsync({ from: norm, to: tmp });
  return tmp;
}

// Bytes read per positioned window when fingerprinting a staged file.
const HASH_WINDOW_BYTES = 64 * 1024;

// Fingerprint a staged file for the dedup check WITHOUT loading it whole: its
// exact byte length plus up to three small base64 windows (head / middle /
// tail), read with positioned reads so even a large video only touches a few
// hundred KB of JS memory. See contentHash.hashFileSample for why length +
// windows separates distinct memes.
export async function hashStagedFile(path: string): Promise<string> {
  const info = await FileSystem.getInfoAsync(path);
  const size = info.exists && typeof info.size === 'number' ? info.size : 0;
  const windows: string[] = [];
  if (size <= HASH_WINDOW_BYTES * 3) {
    // Small enough that windowed reads would just re-read the whole thing.
    windows.push(await FileSystem.readAsStringAsync(path, { encoding: FileSystem.EncodingType.Base64 }));
  } else {
    for (const position of [0, Math.floor(size / 2), size - HASH_WINDOW_BYTES]) {
      windows.push(
        await FileSystem.readAsStringAsync(path, {
          encoding: FileSystem.EncodingType.Base64,
          position,
          length: HASH_WINDOW_BYTES,
        })
      );
    }
  }
  return hashFileSample(size, windows);
}

// Bytes moved per read/write when streaming a staged file into a linked folder.
const COPY_CHUNK_BYTES = 4 * 1024 * 1024;

// Copy a staged cache file into a linked folder as a NEW SAF document, streaming
// the bytes in bounded chunks. createFileAsync mints the document (and returns
// its uri, preserving the folder's collision handling — "name (1)" and so on);
// then a pair of file handles moves the bytes across COPY_CHUNK_BYTES at a time,
// so peak JS memory is one chunk, never the whole file. A yield between chunks
// keeps the event loop live so the UI stays responsive while a large video
// streams in — the whole-file base64 round-trip this replaces froze the app
// outright. Returns the new content:// uri + its on-disk display name.
export async function streamStagedToFolder(
  stagedPath: string,
  fileName: string,
  mimeType: string,
  folderUri: string
): Promise<{ uri: string; name: string }> {
  const type = mimeType || mimeForName(fileName || '');
  const safeFull = (fileName || `meme_${Date.now()}.${extForMime(type)}`).replace(/[^a-zA-Z0-9._-]/g, '_');
  const dot = safeFull.lastIndexOf('.');
  // createFileAsync derives the extension from the mime type, so pass the base.
  const base = dot > 0 ? safeFull.slice(0, dot) : safeFull;
  const uri = await SAF.createFileAsync(folderUri, base, type || 'image/jpeg');
  const src = new File(stagedPath).open(FileMode.ReadOnly);
  const dst = new File(uri).open(FileMode.WriteOnly);
  try {
    for (;;) {
      const chunk = src.readBytes(COPY_CHUNK_BYTES);
      if (chunk.length === 0) break;
      dst.writeBytes(chunk);
      // Give RN a beat to process touches/renders so a big stream can't starve
      // the JS thread for its whole duration.
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  } finally {
    src.close();
    dst.close();
  }
  return { uri, name: nameFromContentUri(uri) || `${base}.${extForMime(type)}` };
}

// Create a new SAF document in a linked folder and write raw base64 bytes into
// it. Used by the zip importer: jszip hands us each entry's bytes as base64
// already, so there's no source file to stream from — we skip straight to
// createFileAsync + write. (Shared image/video files take streamStagedToFolder
// instead, which never holds the whole payload in memory.) Returns the new
// content:// uri + the sanitized display name.
export async function writeBase64ToFolder(
  base64: string,
  fileName: string,
  mimeType: string,
  folderUri: string
): Promise<{ uri: string; name: string }> {
  const safeFull = (fileName || `meme_${Date.now()}.jpg`).replace(/[^a-zA-Z0-9._-]/g, '_');
  const dot = safeFull.lastIndexOf('.');
  // createFileAsync derives the extension from the mime type, so pass the base.
  const base = dot > 0 ? safeFull.slice(0, dot) : safeFull;
  const uri = await SAF.createFileAsync(folderUri, base, mimeType || 'image/jpeg');
  await FileSystem.writeAsStringAsync(uri, base64, { encoding: FileSystem.EncodingType.Base64 });
  return { uri, name: safeFull };
}

// Read any file (a DocumentPicker file:// cache copy, or a content:// uri) as a
// base64 string with no data-URI prefix. Tries the uri directly first — the zip
// the user picks with copyToCacheDirectory is already a readable file:// path —
// and only stages a cache copy if that fails (some content:// providers refuse a
// direct read).
export async function readFileBase64(uri: string, name = 'file.bin'): Promise<string> {
  try {
    return await FileSystem.readAsStringAsync(uri, { encoding: FileSystem.EncodingType.Base64 });
  } catch {
    return await readImageBase64(uri, name);
  }
}

// Delete a file from its linked folder. The user granted write access when
// linking the folder, so this removes the original from on-device storage.
export async function deleteFile(uri: string): Promise<void> {
  await FileSystem.deleteAsync(uri, { idempotent: true });
}

export async function deleteCache(uri: string): Promise<void> {
  try {
    await FileSystem.deleteAsync(uri, { idempotent: true });
  } catch {
    // best-effort cleanup
  }
}

// Throwaway files this app stages into the OS cache directory. Indexing
// (meme_work_*), link imports (import_*), and audio transcription (audio_pcm_*,
// written by the native decoder) delete their own temp files in a `finally`,
// but the share path can't — Sharing.shareAsync hands the file to another app
// and we never learn when it's done, so each Share leaks a full copy of the
// meme into the cache dir forever. Sweeping on launch reclaims all of these:
// nothing here is meant to survive a process restart, so any match is stale by
// definition.
const TEMP_CACHE_PREFIX = /^(share_|import_|meme_work_|audio_pcm_)/;

// Delete the app's leaked temp files from the cache directory. Best-effort and
// safe to run at any time — it only touches files this app created and never
// keeps across launches. Returns how many it removed (for diagnostics/logging).
export async function sweepStaleCache(): Promise<number> {
  try {
    const dir = FileSystem.cacheDirectory;
    if (!dir) return 0;
    const entries = await FileSystem.readDirectoryAsync(dir);
    const stale = entries.filter((name) => TEMP_CACHE_PREFIX.test(name));
    await Promise.all(
      stale.map((name) => FileSystem.deleteAsync(dir + name, { idempotent: true }).catch(() => {}))
    );
    return stale.length;
  } catch {
    // best-effort; a failed sweep should never block startup
    return 0;
  }
}
