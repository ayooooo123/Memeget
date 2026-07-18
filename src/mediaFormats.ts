// The single source of truth for which media formats Memeget handles, plus the
// filename → kind / MIME mapping. Deliberately dependency-free (no expo, no
// native modules) so both the SAF layer and the pure zip-import planner — and
// their unit tests — can share it without pulling in a native import graph.

export const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif'];
export const VIDEO_EXTS = ['mp4', 'mov', 'mkv', 'webm', 'avi', 'm4v', '3gp'];

export type MediaKindName = 'image' | 'video';

// Lowercased extension (no dot) of a filename, or '' if it has none.
export function extOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

// Classify a filename as image/video, or null if we don't handle its format.
export function kindOf(name: string): MediaKindName | null {
  const ext = extOf(name);
  if (IMAGE_EXTS.includes(ext)) return 'image';
  if (VIDEO_EXTS.includes(ext)) return 'video';
  return null;
}

// MIME type for a video file from its extension, for the clipboard clip
// description — paste targets decide whether to accept the clip by its type.
const VIDEO_MIME: Record<string, string> = {
  mp4: 'video/mp4',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  webm: 'video/webm',
  avi: 'video/x-msvideo',
  m4v: 'video/x-m4v',
  '3gp': 'video/3gpp',
};

// Counterpart to VIDEO_MIME, needed when creating a SAF document for an imported
// image so createFileAsync can map the type back to the right extension.
const IMAGE_MIME: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  heic: 'image/heic',
  heif: 'image/heif',
};

export function videoMimeFor(name: string): string {
  return VIDEO_MIME[extOf(name)] ?? 'video/mp4';
}

// Best-effort MIME type for any supported meme filename, image or video. Falls
// back to a sane default per kind so createFileAsync always gets something.
export function mimeForName(name: string): string {
  const ext = extOf(name);
  return IMAGE_MIME[ext] ?? VIDEO_MIME[ext] ?? (kindOf(name) === 'video' ? 'video/mp4' : 'image/jpeg');
}
