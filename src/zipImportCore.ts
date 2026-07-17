// Pure planning logic for the zip importer — no expo, no native, no DB — so it
// unit-tests in isolation (the orchestration that reads the archive and writes
// files lives in zipImport.ts). Given the archive's listing and the filenames
// already in the target folder, it decides which entries to import and which to
// skip (and why).
import { kindOf, type MediaKindName } from './mediaFormats';

// One entry from the archive, reduced to what the planner needs. `path` is the
// full in-zip path (used later to look the bytes back up); `isDir` marks
// directory entries, which carry no file.
export interface ZipEntryMeta {
  path: string;
  isDir: boolean;
}

export interface PlannedImport {
  path: string; // full path inside the zip, to fetch the bytes
  name: string; // display basename (sanitized when written to the folder)
  kind: MediaKindName;
}

export interface ZipPlan {
  imports: PlannedImport[];
  duplicates: string[]; // basenames skipped — already present (folder or earlier in zip)
  unsupported: string[]; // basenames skipped — not an image/video format we handle
}

// Trailing path segment of an in-zip path, tolerant of both slash styles
// (Windows-made zips can use backslashes).
function basenameOf(path: string): string {
  const parts = path.replace(/\\/g, '/').split('/').filter(Boolean);
  return parts[parts.length - 1] ?? '';
}

// Archive cruft that isn't a real user file: the macOS resource-fork sidecar
// tree, AppleDouble `._name` companions, `.DS_Store`, `Thumbs.db`, and any
// dotfile. Filtered silently — they aren't "unsupported memes", they're noise.
function isJunk(path: string, basename: string): boolean {
  if (path.includes('__MACOSX/')) return true;
  if (basename.startsWith('.')) return true;
  if (basename.toLowerCase() === 'thumbs.db') return true;
  return false;
}

// Decide, from the archive's listing and the names already in the target folder,
// which entries to import and which to skip. Duplicates are matched by
// case-insensitive basename — against the folder's current contents and against
// entries already accepted from this same archive (nested folders are flattened
// by filename, first occurrence wins).
export function planZipImport(entries: ZipEntryMeta[], existingNames: Iterable<string>): ZipPlan {
  const existing = new Set<string>();
  for (const n of existingNames) existing.add(n.toLowerCase());

  const seen = new Set<string>();
  const imports: PlannedImport[] = [];
  const duplicates: string[] = [];
  const unsupported: string[] = [];

  for (const e of entries) {
    if (e.isDir) continue;
    const basename = basenameOf(e.path);
    if (!basename || isJunk(e.path, basename)) continue;

    const kind = kindOf(basename);
    if (!kind) {
      unsupported.push(basename);
      continue;
    }
    const key = basename.toLowerCase();
    if (existing.has(key) || seen.has(key)) {
      duplicates.push(basename);
      continue;
    }
    seen.add(key);
    imports.push({ path: e.path, name: basename, kind });
  }

  return { imports, duplicates, unsupported };
}
