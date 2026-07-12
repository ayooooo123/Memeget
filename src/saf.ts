// Storage Access Framework helpers: let the user link an arbitrary on-device
// folder, enumerate its media, and copy individual files into the app cache so
// native modules (CLIP, OCR, thumbnailer) get a stable file:// path to work
// with. Uses the stable legacy FileSystem API for SAF + copy operations.
import * as FileSystem from 'expo-file-system/legacy';
import { File } from 'expo-file-system';
import * as VideoThumbnails from 'expo-video-thumbnails';

import { getFileModifiedTime } from '../modules/memeget-bg';

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif'];
const VIDEO_EXTS = ['mp4', 'mov', 'mkv', 'webm', 'avi', 'm4v', '3gp'];

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
function nameFromContentUri(uri: string): string {
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

function extOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

function kindOf(name: string): 'image' | 'video' | null {
  const ext = extOf(name);
  if (IMAGE_EXTS.includes(ext)) return 'image';
  if (VIDEO_EXTS.includes(ext)) return 'video';
  return null;
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
let workSeq = 0;
export async function copyToCache(file: SafFile, index: number): Promise<string> {
  const ext = extOf(file.name) || (file.kind === 'video' ? 'mp4' : 'jpg');
  const dest = `${FileSystem.cacheDirectory}meme_work_${++workSeq}_${index}.${ext}`;
  await FileSystem.copyAsync({ from: file.uri, to: dest });
  return dest;
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
// prefix) — the FALLBACK copy path for videos, used only when the native
// whole-video clipboard (memeget-bg copyFileToClipboard) isn't built in. Same
// thumbnail path the indexer uses. Materializes the content:// uri to a temp
// file first (the thumbnailer needs a real file path), then cleans both up.
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

// Create a new file inside a linked folder (the SAF tree the user granted) and
// copy `src` — a shared image/video, given as a content:// or file:// uri / path
// — into it. Returns the new content:// uri + sanitized name so the importer can
// index it as a normal library item. The user granted read+write when linking
// the folder, so createFileAsync is permitted.
export async function saveToFolder(
  src: string,
  fileName: string,
  mimeType: string,
  folderUri: string
): Promise<{ uri: string; name: string }> {
  const safeFull = (fileName || `meme_${Date.now()}.jpg`).replace(/[^a-zA-Z0-9._-]/g, '_');
  const dot = safeFull.lastIndexOf('.');
  // createFileAsync derives the extension from the mime type, so pass the base.
  const base = dot > 0 ? safeFull.slice(0, dot) : safeFull;
  const norm = src.startsWith('file://') || src.startsWith('content://') ? src : `file://${src}`;

  // Stage to a cache file:// first (content:// can't always be read directly),
  // then write the bytes into the freshly created SAF document.
  const tmp = `${FileSystem.cacheDirectory}import_${Date.now()}`;
  await FileSystem.copyAsync({ from: norm, to: tmp });
  try {
    const data = await FileSystem.readAsStringAsync(tmp, { encoding: FileSystem.EncodingType.Base64 });
    const uri = await SAF.createFileAsync(folderUri, base, mimeType || 'image/jpeg');
    await FileSystem.writeAsStringAsync(uri, data, { encoding: FileSystem.EncodingType.Base64 });
    return { uri, name: safeFull };
  } finally {
    await FileSystem.deleteAsync(tmp, { idempotent: true });
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
