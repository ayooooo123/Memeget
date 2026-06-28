import * as VideoThumbnails from 'expo-video-thumbnails';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';

import {
  classifyImage,
  trainHead,
  type EmbeddingsApi,
  type LabelHead,
  type LabelVec,
} from './embeddings';
import {
  addIndexError,
  bulkUpdateMemeTags,
  clearIndexErrors,
  getAllMemeEmbeddings,
  getEmbeddingSample,
  getExemplars,
  getFolders,
  getLabelVectors,
  getMemesNeedingVision,
  insertMeme,
  insertPendingMeme,
  markVisionFailed,
  memeExists,
  putLabelVector,
  setMemeVision,
  updateMemeTags,
} from './db';
import { ASSOCIATIONS, MEME_LABELS, NEGATIVE_ANCHORS, ocrTags } from './memeLabels';
import { copyToCache, deleteCache, listMedia, saveToFolder, type SafFile } from './saf';
import type { VisionResult } from './vision';
import type { Tag } from './types';

// Confidence in how a label was matched, highest first:
//   ocr      — text literally in the image (watermark/caption)
//   exemplar — the user's own ground truth, taught by example
//   vision   — LFM2-VL's open-vocabulary read of the image
//   prompt   — CLIP zero-shot guess against the fixed label vocabulary
const TAG_RANK: Record<NonNullable<Tag['source']>, number> = {
  ocr: 4,
  exemplar: 3,
  vision: 2,
  prompt: 1,
};
const tagRank = (t: Tag): number => TAG_RANK[t.source ?? 'prompt'] ?? 1;

// De-dupe a pile of tags by label, keeping the highest-confidence source (and
// highest score within a source), best first, capped.
function dedupeRankTags(tags: Tag[], cap = 6): Tag[] {
  const best = new Map<string, Tag>();
  for (const t of tags) {
    const cur = best.get(t.label);
    if (!cur || tagRank(t) > tagRank(cur) || (tagRank(t) === tagRank(cur) && t.score > cur.score)) {
      best.set(t.label, t);
    }
  }
  return [...best.values()].sort((a, b) => tagRank(b) - tagRank(a) || b.score - a.score).slice(0, cap);
}

// Fast-pass merge: CLIP/exemplar visual tags + OCR-derived tags. Kept tight (4)
// because the slower LFM2-VL pass adds richer tags later.
function mergeTags(visual: Tag[], fromOcr: Tag[]): Tag[] {
  return dedupeRankTags([...visual, ...fromOcr], 4);
}

const NEG_PREFIX = 'neg::';

// On-device OCR (Google ML Kit on Android). Imported lazily/defensively so a
// missing module never breaks the whole index run.
// ExecuTorch's native image decoder rejects WebP/HEIC/animated formats
// ("Read image error: invalid argument"). Transcode every frame to a plain
// JPEG (downscaled — CLIP only needs 224px) so embed + OCR always get a format
// they can read. Also sidesteps out-of-memory on very large images.
async function toJpeg(uri: string): Promise<string> {
  const r = await manipulateAsync(uri, [{ resize: { width: 768 } }], {
    compress: 0.9,
    format: SaveFormat.JPEG,
  });
  return r.uri;
}

async function ocr(uri: string): Promise<string> {
  try {
    const mod = require('expo-text-extractor');
    const fn = mod.extractTextFromImage ?? mod.default?.extractTextFromImage;
    if (!fn) return '';
    const res = await fn(uri);
    if (Array.isArray(res)) return res.join(' ').trim();
    return typeof res === 'string' ? res.trim() : '';
  } catch {
    return '';
  }
}

// Turn a library item (image or video) into a local JPEG path the models can
// read, plus the temp files to clean up afterward. The VLM needs an actual
// on-disk path (its `mediaPath`), and SAF content:// URIs aren't usable
// directly — so we re-derive the frame the same way the index pass does.
async function materializeFrame(
  file: SafFile,
  idx: number
): Promise<{ jpeg: string; temp: string[] }> {
  const temp: string[] = [];
  const work = await copyToCache(file, idx);
  temp.push(work);
  let frame = work;
  if (file.kind === 'video') {
    const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time: 1000 });
    frame = uri;
    temp.push(uri);
  }
  const jpeg = await toJpeg(frame);
  temp.push(jpeg);
  return { jpeg, temp };
}

// Curated associations + any added when teaching an exemplar. Shared by the
// classification pass and the VLM enrichment pass.
async function buildAssociations(): Promise<Map<string, string[]>> {
  const assoc = new Map<string, string[]>(Object.entries(ASSOCIATIONS));
  for (const e of await getExemplars()) {
    if (e.associations.length) {
      assoc.set(e.label, [...(assoc.get(e.label) ?? []), ...e.associations]);
    }
  }
  return assoc;
}

export interface IndexProgress {
  processed: number;
  total: number;
  added: number;
  current: string;
}

export interface IndexResult {
  added: number;
  skipped: number;
  errors: number;
}

// Everything needed to tag an image: zero-shot text labels, taught exemplars,
// negative anchors, and the world-knowledge association lookup.
interface Knowledge {
  labelVecs: LabelVec[];
  exemplarHeads: LabelHead[];
  mean: Float32Array | null;
  negativeVecs: Float32Array[];
  assoc: Map<string, string[]>;
}

export interface ExemplarModel {
  heads: LabelHead[];
  mean: Float32Array | null;
}

// Train a logistic-regression head for every taught label from the exemplars in
// the DB, using a random sample of the library as the negative background. Pure
// vector math (no CLIP/api), so it can also be called standalone (e.g. the
// detail-view debug readout). Returns the per-label heads plus the library mean
// used to center vectors at inference time.
export async function buildExemplarHeads(): Promise<ExemplarModel> {
  const exemplars = await getExemplars();
  if (exemplars.length === 0) return { heads: [], mean: null };

  const sample = await getEmbeddingSample(500);
  const dim = exemplars[0].vector.length;

  // Library mean (background proxy) for mean-centering — this is what cancels
  // CLIP's anisotropic baseline so non-matches can actually reach ~0.
  const mean = new Float32Array(dim);
  if (sample.length) {
    for (const v of sample) for (let i = 0; i < dim; i++) mean[i] += v[i];
    for (let i = 0; i < dim; i++) mean[i] /= sample.length;
  }
  const center = (v: ArrayLike<number>): number[] => {
    const out = new Array<number>(dim);
    for (let i = 0; i < dim; i++) out[i] = v[i] - mean[i];
    return out;
  };

  const background = sample.map(center);

  // Group taught examples by label, split into positive ("is a <label>") and
  // explicit negative ("is NOT a <label>") sets.
  const byLabel = new Map<string, { category: string; pos: number[][]; neg: number[][] }>();
  for (const e of exemplars) {
    const g = byLabel.get(e.label) ?? { category: e.category, pos: [], neg: [] };
    (e.positive ? g.pos : g.neg).push(center(e.vector));
    byLabel.set(e.label, g);
  }

  // A handful of explicit "not this" corrections would be drowned out by ~500
  // background samples, so replicate each so it carries real weight (~1 copy per
  // 25 background items) — one correction visibly moves the boundary.
  const negBoost = Math.max(1, Math.round(background.length / 25));

  const heads: LabelHead[] = [];
  for (const [label, g] of byLabel) {
    if (g.pos.length === 0) continue; // need at least one positive to train
    // Negatives = random background + every other label's positives (they're
    // definitionally not this label) + this label's oversampled corrections.
    const otherPos: number[][] = [];
    for (const [l2, g2] of byLabel) if (l2 !== label) otherPos.push(...g2.pos);
    const corrections: number[][] = [];
    for (const n of g.neg) for (let k = 0; k < negBoost; k++) corrections.push(n);
    // trainHead yields internally, so awaiting it keeps the UI alive even when
    // many labels have been taught (one full train each, back to back).
    heads.push(await trainHead(label, g.category, g.pos, [...background, ...otherPos, ...corrections]));
  }
  return { heads, mean };
}

// Compute (and cache) CLIP text vectors for every curated label + negative
// anchor, then load taught exemplars and build the association lookup.
export async function buildKnowledge(api: EmbeddingsApi): Promise<Knowledge> {
  const cache = await getLabelVectors();

  for (const def of MEME_LABELS) {
    if (!cache.has(def.label)) {
      const vec = await api.embedText(def.prompt);
      await putLabelVector(def.label, vec);
      cache.set(def.label, Float32Array.from(vec));
    }
  }
  for (let i = 0; i < NEGATIVE_ANCHORS.length; i++) {
    const key = `${NEG_PREFIX}${i}`;
    if (!cache.has(key)) {
      const vec = await api.embedText(NEGATIVE_ANCHORS[i]);
      await putLabelVector(key, vec);
      cache.set(key, Float32Array.from(vec));
    }
  }

  const labelVecs: LabelVec[] = MEME_LABELS.filter((d) => cache.has(d.label)).map((d) => ({
    label: d.label,
    category: d.category,
    vec: cache.get(d.label)!,
  }));
  const negativeVecs = NEGATIVE_ANCHORS.map((_, i) => cache.get(`${NEG_PREFIX}${i}`)!).filter(Boolean);

  const exemplars = await getExemplars();
  const { heads: exemplarHeads, mean } = await buildExemplarHeads();

  // Association lookup: curated terms + any added with an exemplar.
  const assoc = new Map<string, string[]>(Object.entries(ASSOCIATIONS));
  for (const e of exemplars) {
    if (e.associations.length) {
      assoc.set(e.label, [...(assoc.get(e.label) ?? []), ...e.associations]);
    }
  }

  return { labelVecs, exemplarHeads, mean, negativeVecs, assoc };
}

// Build the searchable world-knowledge string for a set of tags: the label
// names plus their association terms, de-duplicated.
function extraTermsFor(tags: Tag[], assoc: Map<string, string[]>): string {
  const terms = new Set<string>();
  for (const t of tags) {
    terms.add(t.label.toLowerCase());
    for (const a of assoc.get(t.label) ?? []) terms.add(a.toLowerCase());
  }
  return [...terms].join(' ');
}

// Walk every linked folder and index any media not already in the DB.
export async function runIndex(
  api: EmbeddingsApi,
  opts: { onProgress?: (p: IndexProgress) => void; shouldCancel?: () => boolean } = {}
): Promise<IndexResult> {
  const know = await buildKnowledge(api);
  await clearIndexErrors();

  const folders = await getFolders();
  const allFiles = [];
  for (const folder of folders) {
    try {
      const media = await listMedia(folder.uri);
      allFiles.push(...media);
    } catch {
      // folder permission may have been revoked; skip it
    }
  }

  let added = 0;
  let skipped = 0;
  let errors = 0;
  const total = allFiles.length;

  for (let i = 0; i < allFiles.length; i++) {
    if (opts.shouldCancel?.()) break;
    const file = allFiles[i];
    opts.onProgress?.({ processed: i, total, added, current: file.name });
    const r = await processFile(api, file, know, i);
    if (r === 'added') added++;
    else if (r === 'skipped') skipped++;
    else errors++;
  }

  opts.onProgress?.({ processed: total, total, added, current: '' });
  return { added, skipped, errors };
}

// Full per-file pipeline: copy → (thumbnail) → transcode → embed → OCR →
// classify → store. Logs failures to the index-error table and cleans up temp
// files. Shared by the folder scan (runIndex) and the share-target importer.
async function processFile(
  api: EmbeddingsApi,
  file: SafFile,
  know: Knowledge,
  idx: number
): Promise<'added' | 'skipped' | 'error'> {
  let stage = 'copy';
  const temp: string[] = [];
  try {
    if (await memeExists(file.uri)) return 'skipped';

    const work = await copyToCache(file, idx);
    temp.push(work);
    let frame = work;

    if (file.kind === 'video') {
      stage = 'thumbnail';
      const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time: 1000 });
      frame = uri;
      temp.push(uri);
    }

    stage = 'transcode';
    const jpeg = await toJpeg(frame);
    temp.push(jpeg);

    stage = 'embed';
    const embedding = await api.embedImage(jpeg);
    const ocrText = await ocr(jpeg);
    const tags = mergeTags(
      classifyImage(embedding, know.labelVecs, know.exemplarHeads, know.mean, know.negativeVecs),
      ocrTags(ocrText)
    );

    stage = 'store';
    await insertMeme({
      uri: file.uri,
      name: file.name,
      kind: file.kind,
      embedding,
      ocrText,
      tags,
      extraTerms: extraTermsFor(tags, know.assoc),
    });
    return 'added';
  } catch (e) {
    const reason = String((e as Error)?.message ?? e).slice(0, 300);
    await addIndexError({ name: file.name, kind: file.kind, stage, reason }).catch(() => {});
    return 'error';
  } finally {
    for (const t of temp) await deleteCache(t);
  }
}

// Phase 1 of accepting a shared meme: copy each file into the first linked
// folder so it lives alongside the rest of the library. This is deliberately
// the *only* step that has to finish before the user can leave the app — it
// needs no CLIP model and is just a file copy, so it returns almost instantly.
// The slow embed/OCR/tag work is deferred to indexSavedFiles. Crucially, a file
// saved here but not yet indexed is NOT lost: it's a normal file in the linked
// folder, so the next runIndex (or a later call to indexSavedFiles) picks it up.
export async function saveSharedFiles(
  files: { path: string; fileName: string; mimeType: string }[],
  opts: { onProgress?: (done: number, total: number) => void } = {}
): Promise<{ saved: SafFile[]; errors: number; folderName: string }> {
  const folders = await getFolders();
  if (folders.length === 0) {
    throw new Error('Link a folder first (Library tab) so shared memes have a place to live.');
  }
  const folder = folders[0];

  const saved: SafFile[] = [];
  let errors = 0;
  for (let i = 0; i < files.length; i++) {
    opts.onProgress?.(i, files.length);
    const src = files[i];
    const kind: 'image' | 'video' = src.mimeType.startsWith('video') ? 'video' : 'image';
    try {
      const { uri, name } = await saveToFolder(src.path, src.fileName, src.mimeType, folder.uri);
      // Record a pending placeholder right away so the meme appears in the
      // library list the instant it's saved — before the (model-dependent,
      // possibly much later) embed/OCR/tag pass replaces it with the real row.
      await insertPendingMeme({ uri, name, kind }).catch(() => {});
      saved.push({ uri, name, kind });
    } catch {
      errors++;
    }
  }
  opts.onProgress?.(files.length, files.length);
  return { saved, errors, folderName: folder.name };
}

// Phase 2: embed/OCR/tag files already saved into the library and store them.
// Knowledge (label vectors + trained exemplar heads) is built ONCE for the whole
// batch — rebuilding it per file is what made importing slow. Runs in the
// background after saveSharedFiles, so the user is long gone by the time it
// finishes. Skipping it (app killed, model not ready) loses nothing: the saved
// files are still in the folder for the next runIndex to catch.
export async function indexSavedFiles(
  api: EmbeddingsApi,
  saved: SafFile[],
  opts: { onProgress?: (done: number, total: number) => void } = {}
): Promise<{ added: number; errors: number }> {
  if (saved.length === 0) return { added: 0, errors: 0 };
  const know = await buildKnowledge(api);

  let added = 0;
  let errors = 0;
  for (let i = 0; i < saved.length; i++) {
    opts.onProgress?.(i, saved.length);
    const r = await processFile(api, saved[i], know, i);
    if (r === 'error') errors++;
    else added++;
  }
  opts.onProgress?.(saved.length, saved.length);
  return { added, errors };
}

export interface RetagResult {
  updated: number;
}

// Re-run tagging over every already-indexed meme using current knowledge
// (new exemplars, edited associations). Reuses stored embeddings, so there is
// no image re-embedding — only cheap vector math.
export async function retagAll(
  api: EmbeddingsApi,
  opts: { onProgress?: (done: number, total: number) => void } = {}
): Promise<RetagResult> {
  const know = await buildKnowledge(api);
  const rows = await getAllMemeEmbeddings();

  // Classify every meme up front. This is pure JS vector math over the whole
  // library, so we hand the event loop a macrotask every so often — otherwise
  // the awaited DB writes only flush microtasks and React never gets to render,
  // which froze the app while teaching.
  const updates: { id: number; tags: Tag[]; extraTerms: string }[] = [];
  for (let i = 0; i < rows.length; i++) {
    const vec = Array.from(rows[i].embedding);
    const tags = mergeTags(
      classifyImage(vec, know.labelVecs, know.exemplarHeads, know.mean, know.negativeVecs),
      ocrTags(rows[i].ocrText)
    );
    updates.push({ id: rows[i].id, tags, extraTerms: extraTermsFor(tags, know.assoc) });
    opts.onProgress?.(i + 1, rows.length);
    if ((i & 63) === 63) await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }

  // Single transaction for all the writes — fast even on a big library.
  await bulkUpdateMemeTags(updates);
  return { updated: updates.length };
}

// ---- LFM2-VL enrichment pass -------------------------------------------------

export interface EnrichProgress {
  done: number;
  total: number;
  current: string;
}

export interface EnrichResult {
  described: number;
  failed: number;
}

// Minimal surface of the vision API the enricher needs — keeps indexer.ts free
// of any React/provider coupling.
export interface VisionEnricher {
  ready: boolean;
  describe: (jpegPath: string) => Promise<VisionResult | null>;
}

// Walk every meme still awaiting a description and run LFM2-VL over it: caption,
// literal text, and open-vocabulary tags get merged into the record and folded
// into the search index. Runs AFTER the fast CLIP pass so the library is
// browsable immediately; this just makes it smarter in the background.
//
// Strictly sequential: the on-device LLM does one generation at a time, and
// re-deriving each frame keeps memory flat across a whole library.
export async function enrichLibrary(
  vision: VisionEnricher,
  opts: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean } = {}
): Promise<EnrichResult> {
  if (!vision.ready) throw new Error('Vision model is still loading — try again shortly.');

  const assoc = await buildAssociations();
  const queue = await getMemesNeedingVision();
  const total = queue.length;

  let described = 0;
  let failed = 0;
  for (let i = 0; i < queue.length; i++) {
    if (opts.shouldCancel?.()) break;
    const m = queue[i];
    opts.onProgress?.({ done: i, total, current: m.name });

    const temp: string[] = [];
    try {
      const frame = await materializeFrame({ uri: m.uri, name: m.name, kind: m.kind }, i);
      temp.push(...frame.temp);

      const res = await vision.describe(frame.jpeg);
      if (!res) {
        // Model went unready mid-run; leave as pending to retry next time.
        break;
      }

      // LFM's open-vocabulary tags join the existing CLIP/OCR/exemplar tags,
      // ranked between them (above CLIP guesses, below the user's truth).
      const visionTags: Tag[] = res.tags.map((label) => ({
        label,
        category: 'topic',
        score: 0.9,
        source: 'vision' as const,
      }));
      const merged = dedupeRankTags([...m.tags, ...visionTags], 6);

      // Everything the model surfaced becomes searchable: the literal text, the
      // named subjects, and the keywords — on top of the usual association terms.
      const visionExtra = [res.text, res.subjects.join(' '), res.tags.join(' ')]
        .join(' ')
        .toLowerCase();
      const extraTerms = `${extraTermsFor(merged, assoc)} ${visionExtra}`.replace(/\s+/g, ' ').trim();

      await setMemeVision(m.id, { caption: res.caption, tags: merged, extraTerms });
      described++;
    } catch {
      await markVisionFailed(m.id).catch(() => {});
      failed++;
    } finally {
      for (const t of temp) await deleteCache(t);
    }
  }

  opts.onProgress?.({ done: total, total, current: '' });
  return { described, failed };
}
