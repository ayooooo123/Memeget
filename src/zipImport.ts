// Import memes from a .zip archive. Mirrors the share-import flow: compatible
// media inside the zip is copied into the first linked folder as normal library
// files, a pending placeholder row is inserted so each shows up in the grid
// immediately, and the slow embed/OCR/tag work is left to the background indexer
// (indexSavedFiles) — a file saved here but not yet indexed is never lost, since
// it's a normal file in the linked folder that the next runIndex would catch.
//
// The planning (which entries are compatible, which are duplicates) is pure and
// lives in zipImportCore; this module is just the archive read + folder writes.
import { getFolders, insertPendingMeme } from './db';
import {
  listMedia,
  mimeForName,
  readFileBase64,
  writeBase64ToFolder,
  type SafFile,
} from './saf';
import { planZipImport, type ZipEntryMeta } from './zipImportCore';

export type { PlannedImport, ZipEntryMeta, ZipPlan } from './zipImportCore';
export { planZipImport } from './zipImportCore';

export interface ZipImportResult {
  imported: number;
  duplicates: number;
  unsupported: number;
  errors: number; // entries that matched but failed to write to the folder
  saved: SafFile[]; // freshly saved files, to hand to the background indexer
  folderName: string;
}

export type ZipImportPhase = 'reading' | 'saving';

// Minimal shape of the jszip instance we use — declared locally so the module
// typechecks without jszip's types resolved (it's lazily require()'d, matching
// how the other optional heavy modules are loaded).
interface LoadedZip {
  files: Record<string, { name: string; dir: boolean }>;
  file(path: string): { async(type: 'base64'): Promise<string> } | null;
}

// Read a .zip (a DocumentPicker file:// cache copy, or a content:// uri), pull
// every compatible non-duplicate meme out of it into the first linked folder,
// and register each as a pending library row. Returns a summary plus the saved
// files so the caller can kick off background indexing. Throws only for
// pre-flight problems (no folder linked, unreadable/invalid zip); per-file write
// failures are counted in `errors`, not thrown.
export async function importMemesFromZip(
  zipUri: string,
  opts: {
    zipName?: string;
    onProgress?: (done: number, total: number, phase: ZipImportPhase) => void;
  } = {}
): Promise<ZipImportResult> {
  const folders = await getFolders();
  if (folders.length === 0) {
    throw new Error('Link a folder first (Library tab) so imported memes have a place to live.');
  }
  const folder = folders[0];

  opts.onProgress?.(0, 0, 'reading');
  const base64 = await readFileBase64(zipUri, opts.zipName ?? 'import.zip');

  // Lazily required (same defensive style as the other optional heavy modules)
  // so a build without jszip fails with a clear message, not at import time.
  let zip: LoadedZip;
  try {
    const JSZip = require('jszip');
    zip = (await JSZip.loadAsync(base64, { base64: true })) as LoadedZip;
  } catch (e) {
    throw new Error(
      `Couldn't read that .zip — it may be corrupt or not a zip file. (${String((e as Error)?.message ?? e)})`
    );
  }

  const entries: ZipEntryMeta[] = Object.values(zip.files).map((f) => ({
    path: f.name,
    isDir: f.dir,
  }));
  const existing = (await listMedia(folder.uri).catch(() => [] as SafFile[])).map((f) => f.name);
  const plan = planZipImport(entries, existing);

  const total = plan.imports.length;
  const saved: SafFile[] = [];
  let errors = 0;

  for (let i = 0; i < plan.imports.length; i++) {
    opts.onProgress?.(i, total, 'saving');
    const item = plan.imports[i];
    try {
      const entry = zip.file(item.path);
      if (!entry) {
        errors++;
        continue;
      }
      const bytes = await entry.async('base64');
      const { uri, name } = await writeBase64ToFolder(
        bytes,
        item.name,
        mimeForName(item.name),
        folder.uri
      );
      // Show up in the grid right away; the background indexer replaces this
      // placeholder with the real embedded/OCR'd/tagged row.
      await insertPendingMeme({ uri, name, kind: item.kind }).catch(() => {});
      saved.push({ uri, name, kind: item.kind });
    } catch {
      errors++;
    }
  }
  opts.onProgress?.(total, total, 'saving');

  return {
    imported: saved.length,
    duplicates: plan.duplicates.length,
    unsupported: plan.unsupported.length,
    errors,
    saved,
    folderName: folder.name,
  };
}
