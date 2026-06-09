// Storage Access Framework helpers: let the user link an arbitrary on-device
// folder, enumerate its media, and copy individual files into the app cache so
// native modules (CLIP, OCR, thumbnailer) get a stable file:// path to work
// with. Uses the stable legacy FileSystem API for SAF + copy operations.
import * as FileSystem from 'expo-file-system/legacy';

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

// Copy a SAF file into the cache directory and return a file:// path the native
// modules can read. Caller is responsible for deleting it afterwards.
export async function copyToCache(file: SafFile, index: number): Promise<string> {
  const ext = extOf(file.name) || (file.kind === 'video' ? 'mp4' : 'jpg');
  const dest = `${FileSystem.cacheDirectory}meme_work_${index}.${ext}`;
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

export async function deleteCache(uri: string): Promise<void> {
  try {
    await FileSystem.deleteAsync(uri, { idempotent: true });
  } catch {
    // best-effort cleanup
  }
}
