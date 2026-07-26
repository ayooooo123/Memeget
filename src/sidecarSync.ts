// The I/O shell around the `.memeget` sidecar format (see sidecar.ts): read the
// library out of SQLite, write it into the user's own folder, and fold it back
// in on a fresh install.
//
// Two properties this must hold, because it runs unattended in the background:
//   * It never throws into its caller. A revoked folder grant, a read-only
//     provider, a full disk — all degrade to "didn't sync", never to a crash in
//     the middle of an index pass.
//   * It never makes the library worse. Restore is additive (see
//     restoreSidecarMemes); sync only writes files whose content changed.
import {
  getExemplars,
  getFolders,
  getSidecarRows,
  importExemplars,
  restoreSidecarMemes,
  type SidecarRestoreEntry,
} from './db';
import { onKnowledgeChanged } from './events';
import {
  listChildNames,
  listMedia,
  readSidecarFile,
  sidecarDir,
  writeSidecarFile,
} from './saf';
import {
  MANIFEST_FILE,
  SIDECAR_DIR,
  SIDECAR_VERSION,
  TEACHINGS_FILE,
  buildManifest,
  chunkFileName,
  decodeVec,
  digest,
  encodeVec,
  groupChunks,
  parseChunk,
  parseManifest,
  serializeChunk,
  vectorsUsable,
  type ChunkStamp,
  type SidecarMeme,
} from './sidecar';
import { buildPack, parsePack, serializePack } from './teachingPack';
import { createYielder } from './learnCore';
import { utf8Length } from './zipWriter';

export interface SidecarSyncResult {
  folder: string;
  memes: number;
  chunksWritten: number;
  teachingsWritten: boolean;
  skipped: boolean; // no writable sidecar directory
}

export interface SidecarRestoreResult {
  folder: string;
  added: number;
  enriched: number;
  teachingsAdded: number;
  vectorsDropped: boolean; // sidecar was written under a different model
  // Records the manifest promised that no longer read back — damaged files. A
  // backup silently losing entries is the one failure worth shouting about.
  unreadable: number;
  // Records whose file is no longer in the folder. Expected and harmless: the
  // user deleted those memes.
  orphaned: number;
  found: boolean;
}

// ---- write --------------------------------------------------------------------

export async function syncFolderSidecar(
  folderUri: string,
  folderName: string
): Promise<SidecarSyncResult> {
  const result: SidecarSyncResult = {
    folder: folderName,
    memes: 0,
    chunksWritten: 0,
    teachingsWritten: false,
    skipped: true,
  };
  // Read the folder BEFORE the sidecar directory is touched. The backup must
  // describe the files that are actually there: a row whose file was deleted
  // outside the app is knowledge about nothing, and keeping it would let a
  // later file that happens to reuse the name inherit a dead meme's tags and
  // caption (restore matches on name — that's what survives a reinstall).
  let onDisk: { uri: string; name: string }[];
  try {
    onDisk = await listMedia(folderUri);
  } catch {
    return result; // grant revoked mid-run; a backup we can't verify isn't one
  }

  const dir = await sidecarDir(folderUri, SIDECAR_DIR, true);
  if (!dir) return result;
  const tick = createYielder();

  const previous = parseManifest((await readSidecarFile(dir, MANIFEST_FILE)) ?? '');
  const rows = await getSidecarRows(folderUri);

  // Guard against writing an EMPTY backup over a good one. A folder that reads
  // as empty while the library still has rows for it is a permission or
  // provider hiccup, not 2000 deletions — and blanking every chunk on that
  // basis would destroy exactly what this feature exists to protect.
  if (onDisk.length === 0 && rows.length > 0) return result;

  // Past every bail-out: this folder really is being backed up.
  result.skipped = false;

  // Key each record by the name the FOLDER reports, not the one the meme row
  // stores. They can differ: saving a shared meme asks SAF for "x.jpeg" and
  // Android may hand back "x.jpg" (the extension implied by the mime type) or
  // "x (1).jpg" (a name collision), while the row keeps the name we asked for.
  // Restore resolves records by listing the folder, so a record filed under the
  // requested name would never match the file that actually exists — 25 memes
  // in a 2105-item library, silently unrestorable.
  const nameByUri = new Map(onDisk.map((f) => [f.uri, f.name]));
  const memes: SidecarMeme[] = rows
    .filter((r) => nameByUri.has(r.uri))
    .map((r) => ({
      name: nameByUri.get(r.uri)!,
      kind: r.kind,
      tags: r.tags,
      extraTerms: r.extraTerms,
      ocr: r.ocr,
      caption: r.caption,
      transcript: r.transcript,
      visionState: r.visionState as SidecarMeme['visionState'],
      audioState: r.audioState as SidecarMeme['audioState'],
      modifiedAt: r.modifiedAt,
      embedding: encodeVec(r.embedding),
      visualEmbedding: encodeVec(r.visualEmbedding),
      visualModel: r.visualModel,
      captionEmbedding: encodeVec(r.captionEmbedding),
    }));
  result.memes = memes.length;

  // A chunk is left alone only when the folder demonstrably already holds
  // exactly it. That needs three things, and each has bitten:
  //   * same digest — nothing changed;
  //   * the file is still THERE — a stamp alone is not evidence; something
  //     outside the app can delete a chunk, and trusting the manifest would
  //     mean never rewriting it;
  //   * the folder was written by this format version — v1 chunks may carry a
  //     stale untruncated tail (see SIDECAR_VERSION), so they get rewritten
  //     once regardless of digest.
  const present = new Set(await listChildNames(dir).catch(() => []));
  const trustDigests = previous?.version === SIDECAR_VERSION;

  const groups = groupChunks(memes, Object.keys(previous?.chunks ?? {}));
  const stamps = new Map<string, ChunkStamp>();
  for (const [key, list] of groups) {
    const fileName = chunkFileName(key);
    const payload = serializeChunk(key, list);
    const stamp: ChunkStamp = {
      count: list.length,
      digest: digest(payload),
      bytes: utf8Length(payload),
    };
    const prior = previous?.chunks[key];
    if (trustDigests && prior?.digest === stamp.digest && present.has(fileName)) {
      // Carry the recorded byte length forward: it describes the file on disk,
      // which we did not rewrite.
      stamps.set(key, prior);
      continue;
    }
    stamps.set(key, stamp);
    try {
      // `undefined` (not 0) when the length is unknown — a v1 stamp has no
      // `bytes` — so the writer measures the file instead of assuming there is
      // no tail to cover.
      await writeSidecarFile(dir, fileName, payload, present.has(fileName) ? prior?.bytes || undefined : 0);
      result.chunksWritten++;
    } catch {
      // Keep going: one unwritable chunk shouldn't cost the other 63. Re-stamp
      // it with what the folder held BEFORE this attempt: that file is still
      // there and still restorable, and the stale digest makes the next sync
      // retry the write. Dropping the key instead would erase a perfectly good
      // chunk from the manifest, hiding real backed-up knowledge from restore.
      if (prior) stamps.set(key, prior);
      else stamps.delete(key);
    }
    // Yield on a frame budget: a sync runs alongside a live UI (and often
    // alongside an index pass), and 64 encode+write rounds back to back are
    // felt as a scroll hitch.
    await tick();
  }

  // Taught exemplars are global, not per-folder, yet every folder gets a full
  // copy. They are the smallest and most irreplaceable thing here — a few
  // hundred KB against a library of megabytes — and copying them everywhere
  // means ANY surviving folder can restore them. Written in the interchangeable
  // teaching-pack format, so this file also works with Settings > Import.
  const teachingsText = serializePack(buildPack(await getExemplars(), Date.now(), { name: folderName }));
  const teachings: ChunkStamp = {
    count: 0,
    digest: digest(teachingsText),
    bytes: utf8Length(teachingsText),
  };
  const teachingsPresent = present.has(TEACHINGS_FILE);
  if (!trustDigests || previous?.teachings.digest !== teachings.digest || !teachingsPresent) {
    try {
      await writeSidecarFile(
        dir,
        TEACHINGS_FILE,
        teachingsText,
        teachingsPresent ? previous?.teachings.bytes || undefined : 0
      );
      result.teachingsWritten = true;
    } catch {
      // Record what the folder still holds, so the digest mismatch makes the
      // next sync try again.
      if (previous?.teachings) {
        teachings.digest = previous.teachings.digest;
        teachings.bytes = previous.teachings.bytes;
      }
    }
  }

  // The manifest is written LAST and is the only thing a restore trusts for
  // change detection, so a sync interrupted halfway leaves stale stamps that
  // simply cause the next run to rewrite those chunks. The chunk files
  // themselves are always self-describing and readable on their own.
  const manifestText = JSON.stringify(buildManifest(stamps, teachings, Date.now()));
  try {
    await writeSidecarFile(
      dir,
      MANIFEST_FILE,
      manifestText,
      present.has(MANIFEST_FILE) ? undefined : 0
    );
  } catch {
    /* stamps stay unpersisted; next sync redoes the work */
  }
  return result;
}

// Mirror every linked folder. Errors are per-folder, so one bad grant can't
// stop the rest from being backed up.
//
// Single-flight: the debounced auto-backup, the end of an index pass, and the
// Settings button all call straight in here, and two of them writing the same
// .memeget documents at once is a corrupt chunk waiting to happen. A caller
// arriving mid-sync joins the run already in progress instead of starting a
// second one.
let inFlight: Promise<SidecarSyncResult[]> | null = null;

export function syncAllSidecars(): Promise<SidecarSyncResult[]> {
  inFlight ??= runSync().finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function runSync(): Promise<SidecarSyncResult[]> {
  // Stamped BEFORE the writes: a mutation landing mid-sync may miss this pass,
  // and must still read as dirty afterwards so it gets its own.
  const startedAt = Date.now();
  const out: SidecarSyncResult[] = [];
  for (const folder of await getFolders()) {
    try {
      out.push(await syncFolderSidecar(folder.uri, folder.name));
    } catch {
      out.push({
        folder: folder.name,
        memes: 0,
        chunksWritten: 0,
        teachingsWritten: false,
        skipped: true,
      });
    }
  }
  // Only call the work done when a folder actually took the backup. If every
  // one bailed out — revoked grant, unreadable folder — the library is still
  // un-mirrored, and advancing the clock would mark it clean and cancel the
  // retry. An empty folder list is the exception: there is nothing to write, so
  // waiting for it forever would just re-run a no-op on every mutation.
  if (out.length === 0 || out.some((r) => !r.skipped)) syncedAt = startedAt;
  return out;
}

// ---- automatic backup ---------------------------------------------------------
//
// An index pass syncs at the end, but the knowledge most expensive to recreate
// isn't produced by indexing — it's the tags and examples the user teaches by
// hand, one at a time, in a session that may never run another index. Those
// mutations announce themselves on the knowledge channel (db.ts), and this
// coalesces a burst of them into a single write once the user stops.
//
// The debounce is also what keeps this off the back of a heavy pass without
// having to know one is running: describing or transcribing a library emits on
// every meme, so the timer keeps getting pushed out and only fires once the
// pass has gone quiet.
const SYNC_DEBOUNCE_MS = 30_000;

let dirtyAt = 0;
let syncedAt = 0;
let pending: ReturnType<typeof setTimeout> | null = null;

function schedule(): void {
  clearTimeout(pending ?? undefined);
  pending = setTimeout(drain, SYNC_DEBOUNCE_MS);
}

async function drain(): Promise<void> {
  pending = null;
  if (dirtyAt <= syncedAt) return; // nothing new since the last write
  await syncAllSidecars().catch(() => {
    // syncedAt is left behind, so this stays dirty and the next emit retries
  });
  if (dirtyAt > syncedAt) schedule();
}

// Subscribe the debounced backup to knowledge mutations. Returns the
// unsubscribe so the app root can tear it down.
export function startSidecarAutoSync(): () => void {
  const off = onKnowledgeChanged(() => {
    dirtyAt = Date.now();
    schedule();
  });
  return () => {
    off();
    clearTimeout(pending ?? undefined);
    pending = null;
  };
}

// Write immediately if anything is outstanding — for the app leaving the
// foreground, where the debounce window may never elapse.
export async function flushSidecarSync(): Promise<void> {
  if (dirtyAt <= syncedAt) return;
  clearTimeout(pending ?? undefined);
  pending = null;
  await syncAllSidecars().catch(() => {
    /* retried on the next mutation */
  });
}

// ---- read ---------------------------------------------------------------------

// What a folder's sidecar claims to hold, without touching the DB. Used to tell
// the user "this folder has knowledge for 2105 memes" before they commit to a
// restore.
export async function peekFolderSidecar(
  folderUri: string
): Promise<{ memeCount: number; teachings: number; writtenAt: number; sameModel: boolean } | null> {
  const dir = await sidecarDir(folderUri, SIDECAR_DIR, false);
  if (!dir) return null;
  const manifest = parseManifest((await readSidecarFile(dir, MANIFEST_FILE)) ?? '');
  if (!manifest) return null;
  let teachings = 0;
  const packText = await readSidecarFile(dir, TEACHINGS_FILE);
  if (packText) {
    try {
      teachings = parsePack(packText).exemplars.length;
    } catch {
      teachings = 0;
    }
  }
  return {
    memeCount: manifest.memeCount,
    teachings,
    writtenAt: manifest.writtenAt,
    sameModel: vectorsUsable(manifest),
  };
}

export async function restoreFolderSidecar(
  folderUri: string,
  folderName: string
): Promise<SidecarRestoreResult> {
  const result: SidecarRestoreResult = {
    folder: folderName,
    added: 0,
    enriched: 0,
    teachingsAdded: 0,
    vectorsDropped: false,
    unreadable: 0,
    orphaned: 0,
    found: false,
  };
  const dir = await sidecarDir(folderUri, SIDECAR_DIR, false);
  if (!dir) return result;
  const tick = createYielder();

  const manifest = parseManifest((await readSidecarFile(dir, MANIFEST_FILE)) ?? '');
  // A missing or unreadable manifest is not fatal — the chunk files carry
  // everything a restore needs. Fall back to whatever library-*.json documents
  // are actually in the directory, so a folder whose manifest was lost (or
  // never finished writing) still gives its knowledge back.
  const chunkKeys = manifest
    ? Object.keys(manifest.chunks)
    : (await listChildNames(dir))
        .map((n) => /^library-([0-9a-f]{2})\.json$/.exec(n)?.[1])
        .filter((k): k is string => k !== undefined);
  if (chunkKeys.length === 0 && !manifest) return result;
  result.found = true;

  // Vectors only mean something in the space they were produced in. Without a
  // manifest we can't know that space, so we take the text and let the indexer
  // re-embed rather than restore vectors that might be from another model.
  const keepVectors = manifest ? vectorsUsable(manifest) : false;
  result.vectorsDropped = !keepVectors;

  // Sidecar records are keyed by file name; the uri they map to is whatever
  // that name resolves to in the folder TODAY. This is what makes a restore
  // work at all after a reinstall — content:// uris are reissued, but the
  // user's file names are their own.
  const uriByName = new Map<string, { uri: string; kind: string }>();
  for (const file of await listMedia(folderUri)) {
    uriByName.set(file.name, { uri: file.uri, kind: file.kind });
  }

  const entries: SidecarRestoreEntry[] = [];
  for (const key of chunkKeys) {
    const text = await readSidecarFile(dir, chunkFileName(key));
    const parsed = text ? parseChunk(text) : [];
    // A chunk that reads as nothing while the manifest says it holds records is
    // damage, not emptiness — and it must not pass for a clean restore. The
    // caller surfaces this so "restored everything" never quietly means
    // "restored everything that still parsed".
    const expected = manifest?.chunks[key]?.count ?? 0;
    if (parsed.length < expected) result.unreadable += expected - parsed.length;
    for (const meme of parsed) {
      const target = uriByName.get(meme.name);
      // A record whose file is gone describes a meme that no longer exists here.
      if (!target) {
        result.orphaned++;
        continue;
      }
      entries.push({
        uri: target.uri,
        name: meme.name,
        kind: target.kind,
        tags: meme.tags,
        extraTerms: meme.extraTerms,
        ocr: meme.ocr,
        caption: meme.caption,
        transcript: meme.transcript,
        visionState: meme.visionState,
        audioState: meme.audioState,
        modifiedAt: meme.modifiedAt,
        embedding: keepVectors ? decodeVec(meme.embedding) : [],
        visualEmbedding: keepVectors ? decodeVec(meme.visualEmbedding) : [],
        visualModel: meme.visualModel,
        captionEmbedding: keepVectors ? decodeVec(meme.captionEmbedding) : [],
      });
    }
    await tick();
  }

  const { added, enriched } = await restoreSidecarMemes(entries);
  result.added = added;
  result.enriched = enriched;

  const packText = await readSidecarFile(dir, TEACHINGS_FILE);
  if (packText) {
    try {
      const pack = parsePack(packText);
      // parsePack already rejects a foreign embedding space, so anything that
      // gets here is trainable. Merge, never replace: whatever the user has
      // taught in this install outranks the snapshot.
      const { added: taught } = await importExemplars(pack.exemplars, {
        pack: '',
        mode: 'merge',
        origin: 'self',
      });
      result.teachingsAdded = taught;
    } catch {
      // A pack from another primary model — the memes' text knowledge still
      // restored above, which is the part that can't be recomputed.
    }
  }
  return result;
}

// Restore from every linked folder that has a sidecar. Called after the user
// re-links a folder on a fresh install.
export async function restoreAllSidecars(): Promise<SidecarRestoreResult[]> {
  const out: SidecarRestoreResult[] = [];
  for (const folder of await getFolders()) {
    try {
      out.push(await restoreFolderSidecar(folder.uri, folder.name));
    } catch {
      out.push({
        folder: folder.name,
        added: 0,
        enriched: 0,
        teachingsAdded: 0,
        vectorsDropped: false,
        unreadable: 0,
        orphaned: 0,
        found: false,
      });
    }
  }
  return out;
}
