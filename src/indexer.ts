import * as VideoThumbnails from 'expo-video-thumbnails';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';

import type { EmbeddingsApi } from './embeddings';
import {
  classifyExemplars,
  classifyImage,
  classifyPrompts,
  createYielder,
  mergeClassified,
  trainLabelModel,
  type LabelHead,
  type LabelVec,
} from './learnCore';
import {
  addIndexError,
  bulkUpdateMemeTags,
  clearIndexErrors,
  countMemesDescribed,
  dot,
  getAllMemeEmbeddings,
  getDescribedVisionRecords,
  getEmbeddingSample,
  getExemplars,
  getFolders,
  getIndexedUris,
  getKnowledgeVersion,
  getLabelVectors,
  getMemesNeedingCaptionEmbedding,
  getMemesNeedingVisualEmbedding,
  getMemesNeedingVision,
  insertMeme,
  insertPendingMeme,
  markVisionFailed,
  putLabelVector,
  setMemeCaptionEmbedding,
  setMemeVisualEmbedding,
  setMemeVision,
  updateMemeTags,
  type DescribedVisionRow,
  type MemeNeedingVisualEmbeddingRow,
  type MemeNeedingVisionRow,
} from './db';
import { ASSOCIATIONS, MEME_LABELS, NEGATIVE_ANCHORS, ocrTags } from './memeLabels';
import { copyToCache, deleteCache, getModifiedTime, listMedia, saveToFolder, type SafFile } from './saf';
import type { VisionResult } from './visionCore';
import type { Tag } from './types';

// Confidence in how a label was matched, highest first:
//   ocr      — text literally in the image (watermark/caption)
//   exemplar — the user's own ground truth, taught by example
//   vision   — the VLM's (Gemma) open-vocabulary read of the image
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
// because the slower VLM pass adds richer tags later.
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
async function toJpeg(uri: string, width = 768): Promise<string> {
  const r = await manipulateAsync(uri, [{ resize: { width } }], {
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

// Width fed to the VLM. Both supported models resample the input to their
// vision encoder's working resolution anyway (Gemma to a fixed square, LFM by
// tiling over 512), so feeding more pixels only inflates decode/transcode cost —
// and for LFM, prefill time, the dominant cost of a caption. Capping at 512
// keeps that bounded (the ML Kit OCR hint covers any small text we'd lose).
const VLM_FRAME_WIDTH = 512;

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
  const jpeg = await toJpeg(frame, VLM_FRAME_WIDTH);
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

// Trained heads are cached across knowledge builds: most builds (indexing a
// shared meme, a background enrichment tick, opening the teach sheet) happen
// when nothing was taught in between, and retraining every head was their
// dominant cost. The version stamp changes on any exemplar add/remove and as
// the library grows (which shifts the training background), so a stale model is
// never served.
let headsCache: { version: string; model: ExemplarModel } | null = null;

// Train a logistic-regression head for every taught label from the exemplars in
// the DB, using a random sample of the library as the negative background. Pure
// vector math (no CLIP/api), so it can also be called standalone (e.g. the
// detail-view debug readout). Returns the per-label heads plus the library mean
// used to center vectors at inference time.
export async function buildExemplarHeads(): Promise<ExemplarModel> {
  const version = await getKnowledgeVersion();
  if (headsCache && headsCache.version === version) return headsCache.model;

  const model = await trainExemplarHeads();
  headsCache = { version, model };
  return model;
}

async function trainExemplarHeads(): Promise<ExemplarModel> {
  const exemplars = await getExemplars();
  if (exemplars.length === 0) return { heads: [], mean: null };

  // 250 random library vectors is plenty to estimate the mean and act as the
  // negative background for a logistic head — and roughly halves the per-pass
  // training cost vs. 500, which is what the user feels as teach latency.
  const sample = await getEmbeddingSample(250);
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

  const backgroundCentered = sample.map(center);

  // Group taught examples by label, split into positive ("is a <label>") and
  // explicit negative ("is NOT a <label>") sets — keeping both the raw vectors
  // (for background cleaning + the kNN pathway) and the centered ones (for the
  // logistic head).
  interface Group {
    category: string;
    posRaw: number[][];
    posCentered: number[][];
    negRaw: number[][];
    negCentered: number[][];
  }
  const byLabel = new Map<string, Group>();
  for (const e of exemplars) {
    const g =
      byLabel.get(e.label) ??
      { category: e.category, posRaw: [], posCentered: [], negRaw: [], negCentered: [] };
    if (e.positive) {
      g.posRaw.push(e.vector);
      g.posCentered.push(center(e.vector));
    } else {
      g.negRaw.push(e.vector);
      g.negCentered.push(center(e.vector));
    }
    byLabel.set(e.label, g);
  }

  const heads: LabelHead[] = [];
  for (const [label, g] of byLabel) {
    if (g.posRaw.length === 0) continue; // need at least one positive to train
    const otherPosRaw: number[][] = [];
    const otherPosCentered: number[][] = [];
    for (const [l2, g2] of byLabel) {
      if (l2 === label) continue;
      otherPosRaw.push(...g2.posRaw);
      otherPosCentered.push(...g2.posCentered);
    }
    // trainLabelModel yields internally, so awaiting it keeps the UI alive even
    // when many labels have been taught (one full train each, back to back).
    heads.push(
      await trainLabelModel({
        label,
        category: g.category,
        posRaw: g.posRaw,
        posCentered: g.posCentered,
        negRaw: g.negRaw,
        negCentered: g.negCentered,
        backgroundRaw: sample,
        backgroundCentered,
        otherPosRaw,
        otherPosCentered,
      })
    );
  }
  return { heads, mean };
}

// Compute (and cache) CLIP text vectors for every curated label + negative
// anchor, then load taught exemplars and build the association lookup.
export async function buildKnowledge(api: EmbeddingsApi): Promise<Knowledge> {
  const modelId = api.primaryModel.id;
  const cache = await getLabelVectors(modelId);

  for (const def of MEME_LABELS) {
    if (!cache.has(def.label)) {
      const vec = await api.embedText(def.prompt);
      await putLabelVector(def.label, vec, modelId);
      cache.set(def.label, Float32Array.from(vec));
    }
  }
  for (let i = 0; i < NEGATIVE_ANCHORS.length; i++) {
    const key = `${NEG_PREFIX}${i}`;
    if (!cache.has(key)) {
      const vec = await api.embedText(NEGATIVE_ANCHORS[i]);
      await putLabelVector(key, vec, modelId);
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

  // Skip everything already indexed with one query + hash lookups instead of a
  // per-file memeExists() round-trip — re-running Index over a mostly-indexed
  // library now costs one SELECT, not thousands.
  const known = await getIndexedUris();
  const queue: SafFile[] = [];
  let skipped = 0;
  for (const f of allFiles) {
    if (known.has(f.uri)) skipped++;
    else queue.push(f);
  }

  const total = allFiles.length;
  const { added, errors } = await indexQueue(api, queue, know, {
    shouldCancel: opts.shouldCancel,
    onFile: (i, file, addedSoFar) =>
      opts.onProgress?.({ processed: skipped + i, total, added: addedSoFar, current: file.name }),
  });

  opts.onProgress?.({ processed: total, total, added, current: '' });
  return { added, skipped, errors };
}

// Result of stage 1 (prepare). A failed prepare still carries its temp files so
// stage 2 can clean up and log the error with the right stage name.
type Prepared =
  | { ok: true; file: SafFile; modifiedAt: number | null; jpeg: string; temp: string[] }
  | { ok: false; file: SafFile; stage: string; reason: string; temp: string[] };

// Stage 1 of the per-file pipeline: copy out of SAF → (video keyframe) → JPEG
// transcode. Pure native I/O + codecs, no model involvement — which is what
// lets the NEXT file's stage 1 run while the current file sits in the model.
async function prepareFile(file: SafFile, idx: number): Promise<Prepared> {
  let stage = 'copy';
  const temp: string[] = [];
  try {
    // Read the file's own last-modified time so the library can sort by when the
    // meme was actually added, not by when this scan reached it. It's a
    // best-effort read (null on providers that don't report it) — insertMeme
    // falls back to the index time so a row is never left unsorted.
    const modifiedAt = getModifiedTime(file.uri);

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
    return { ok: true, file, modifiedAt, jpeg, temp };
  } catch (e) {
    return { ok: false, file, stage, reason: String((e as Error)?.message ?? e).slice(0, 300), temp };
  }
}

// Stage 2: embed + OCR → classify → store. Logs failures to the index-error
// table and cleans up the prepare stage's temp files.
async function finishFile(api: EmbeddingsApi, prep: Prepared, know: Knowledge): Promise<'added' | 'error'> {
  let stage = 'embed';
  try {
    if (!prep.ok) {
      stage = prep.stage;
      throw new Error(prep.reason);
    }

    // CLIP (ExecuTorch) and OCR (ML Kit) are independent native calls on the
    // same JPEG — running them concurrently hides the shorter one entirely.
    const [embedding, ocrText, visual] = await Promise.all([
      api.embedImage(prep.jpeg),
      ocr(prep.jpeg),
      api.embedVisualImage ? api.embedVisualImage(prep.jpeg) : Promise.resolve(null),
    ]);
    const tags = mergeTags(
      classifyImage(embedding, know.labelVecs, know.exemplarHeads, know.mean, know.negativeVecs),
      ocrTags(ocrText)
    );

    stage = 'store';
    await insertMeme({
      uri: prep.file.uri,
      name: prep.file.name,
      kind: prep.file.kind,
      embedding,
      visualEmbedding: visual?.embedding ?? null,
      visualModel: visual?.model ?? null,
      ocrText,
      tags,
      extraTerms: extraTermsFor(tags, know.assoc),
      modifiedAt: prep.modifiedAt,
    });
    return 'added';
  } catch (e) {
    const reason = String((e as Error)?.message ?? e).slice(0, 300);
    await addIndexError({ name: prep.file.name, kind: prep.file.kind, stage, reason }).catch(() => {});
    return 'error';
  } finally {
    for (const t of prep.temp) await deleteCache(t);
  }
}

// Pipelined core shared by the folder scan and the share importer: while file N
// is being embedded/OCR'd (ExecuTorch + ML Kit), file N+1 is already being
// copied/thumbnailed/transcoded (I/O + codecs). The stages run in different
// native pools, so overlapping them hides most of the prepare cost; JS only
// coordinates. One file is in each stage at a time, so peak memory stays flat.
async function indexQueue(
  api: EmbeddingsApi,
  queue: SafFile[],
  know: Knowledge,
  opts: {
    shouldCancel?: () => boolean;
    onFile?: (index: number, file: SafFile, addedSoFar: number) => void;
  }
): Promise<{ added: number; errors: number }> {
  let added = 0;
  let errors = 0;
  let next: Promise<Prepared> | null = queue.length ? prepareFile(queue[0], 0) : null;
  for (let i = 0; i < queue.length; i++) {
    const prep = await next!;
    if (opts.shouldCancel?.()) {
      for (const t of prep.temp) await deleteCache(t);
      break;
    }
    // Kick off the next file's prepare BEFORE finishing this one — this is the
    // overlap that makes the pipeline worth having.
    next = i + 1 < queue.length ? prepareFile(queue[i + 1], i + 1) : null;
    opts.onFile?.(i, queue[i], added);
    const r = await finishFile(api, prep, know);
    if (r === 'added') added++;
    else errors++;
  }
  return { added, errors };
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
  const { added, errors } = await indexQueue(api, saved, know, {
    onFile: (i) => opts.onProgress?.(i, saved.length),
  });
  opts.onProgress?.(saved.length, saved.length);
  return { added, errors };
}

export interface RetagResult {
  updated: number; // rows whose tags/terms actually changed (unchanged rows are skipped)
}

// Zero-shot prompt tags are a pure function of (embedding, curated labels) —
// teaching can never change them — so they're cached per meme across re-tags
// and only the taught heads are re-scored each pass. That drops the per-teach
// cost from "library × ~100 label vectors" to "library × taught heads". The
// signature guards against a row being re-indexed (new embedding for the same
// id) or an id being reused after a clear.
const promptTagCache = new Map<number, { sig: number; tags: Tag[] }>();
function embSig(e: Float32Array): number {
  const n = e.length;
  return n ? n + e[0] + e[n >> 1] * 3 + e[n - 1] * 7 : 0;
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
  // library, so we time-slice (createYielder) — otherwise the awaited DB writes
  // only flush microtasks and React never gets to render, which froze the app
  // while teaching. Yielding on elapsed time (not a row count) keeps it
  // responsive whether the library is 100 memes or 100,000.
  const updates: { id: number; tags: Tag[]; extraTerms: string }[] = [];
  const tick = createYielder();
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    const vec = Array.from(row.embedding);
    const sig = embSig(row.embedding);
    let prompts = promptTagCache.get(row.id);
    if (!prompts || prompts.sig !== sig) {
      prompts = { sig, tags: classifyPrompts(vec, know.labelVecs, know.negativeVecs) };
      promptTagCache.set(row.id, prompts);
    }
    const visual = mergeClassified(prompts.tags, classifyExemplars(vec, know.exemplarHeads, know.mean));
    const base = mergeTags(visual, ocrTags(row.ocrText));
    // Preserve any VLM tags already on the meme — re-tagging applies new
    // taught knowledge, it shouldn't erase the vision pass's work.
    const visionTags = row.tags.filter((t) => t.source === 'vision');
    const merged = dedupeRankTags([...base, ...visionTags], 6);
    // Likewise keep the vision search terms (subjects/text/caption keywords)
    // that already live in extra_terms — union them with the fresh assoc terms.
    const extraTerms = unionTerms(extraTermsFor(merged, know.assoc), row.extraTerms);
    // Write only what moved: on a typical teach a handful of memes change, so
    // the transaction shrinks from "whole library" to "what the teach touched".
    if (extraTerms !== row.extraTerms || JSON.stringify(merged) !== row.rawTags) {
      updates.push({ id: row.id, tags: merged, extraTerms });
    }
    opts.onProgress?.(i + 1, rows.length);
    await tick();
  }

  // Single transaction for all the writes — fast even on a big library.
  await bulkUpdateMemeTags(updates);
  return { updated: updates.length };
}

// Merge two whitespace-separated term strings into a de-duplicated bag.
function unionTerms(a: string, b: string): string {
  const set = new Set<string>();
  for (const w of `${a} ${b}`.split(/\s+/)) {
    const t = w.trim();
    if (t) set.add(t);
  }
  return [...set].join(' ');
}

// ---- VLM enrichment pass (Gemma 4 / LFM2.5-VL) --------------------------------

export interface EnrichProgress {
  done: number;
  total: number;
  current: string;
}

export interface EnrichResult {
  described: number; // ran the model
  deduped: number; // skipped — copied an identical meme's result
  failed: number;
}

// Minimal surface of the vision API the enricher needs — keeps indexer.ts free
// of any React/provider coupling. `ocrHint` is the text ML Kit already pulled
// from the image, passed so the model uses it verbatim instead of (poorly)
// re-reading small text — which lets us downscale the frame aggressively.
export interface VisionEnricher {
  ready: boolean;
  describe: (jpegPath: string, ocrHint?: string) => Promise<VisionResult | null>;
  embedText?: (text: string) => Promise<number[]>;
}

interface VisionPayload {
  caption: string;
  captionEmbedding: number[] | null;
  tags: Tag[];
  extraTerms: string;
}

// ---- duplicate-skip twin index ----------------------------------------------
// The cheapest description is the one we never run. Two memes are treated as
// "the same" when their CLIP vectors are nearly identical AND their OCR text
// matches. The OCR check is what makes this SAFE for memes: the same template
// with different top-text has a high visual cosine but different text, so it is
// NOT merged — its caption genuinely differs.
const DUP_COSINE = 0.99;
const normText = (s: string) => s.toLowerCase().replace(/\s+/g, ' ').trim();

type TwinRec = DescribedVisionRow;
let twinCache: { count: number; recs: TwinRec[] } | null = null;

// Cache keyed by the described-meme count, which self-heals on delete/clear
// (count drops → reload). Mutations only happen under the provider's mutex, so
// there's no concurrent access to worry about.
async function getTwinIndex(): Promise<TwinRec[]> {
  const count = await countMemesDescribed();
  if (!twinCache || twinCache.count !== count) {
    twinCache = { count, recs: await getDescribedVisionRecords() };
  }
  return twinCache.recs;
}
function pushTwin(rec: TwinRec) {
  if (twinCache) {
    twinCache.recs.push(rec);
    twinCache.count += 1; // the matching setMemeVision bumped the DB count too
  }
}
export function invalidateTwinIndex() {
  twinCache = null;
}

function findTwin(m: MemeNeedingVisionRow, twins: TwinRec[]): TwinRec | null {
  const text = normText(m.ocrText);
  let best: TwinRec | null = null;
  let bestCos = DUP_COSINE;
  for (const t of twins) {
    if (normText(t.ocrText) !== text) continue;
    const c = dot(m.embedding, t.embedding); // both normalized → cosine
    if (c >= bestCos) {
      bestCos = c;
      best = t;
    }
  }
  return best;
}

// ---- telemetry (per-stage timing, for tuning) -------------------------------
const telem = { described: 0, deduped: 0, failed: 0, durations: [] as number[] };
function recordDuration(ms: number) {
  telem.durations.push(ms);
  if (telem.durations.length > 30) telem.durations.shift();
}

export interface VisionTelemetry {
  described: number;
  deduped: number;
  failed: number;
  avgMs: number; // mean model time over the last ~30 described memes
}
export function getVisionTelemetry(): VisionTelemetry {
  const d = telem.durations;
  const avgMs = d.length ? d.reduce((a, b) => a + b, 0) / d.length : 0;
  return { described: telem.described, deduped: telem.deduped, failed: telem.failed, avgMs };
}

// Build searchable terms from merged tags + the model's free text.
function visionExtraTerms(merged: Tag[], assoc: Map<string, string[]>, res: VisionResult): string {
  const extra = [res.text, res.subjects.join(' '), res.tags.join(' ')].join(' ').toLowerCase();
  return `${extraTermsFor(merged, assoc)} ${extra}`.replace(/\s+/g, ' ').trim();
}

function captionSearchText(caption: string, tags: Tag[], extraTerms: string): string {
  return [caption, tags.map((t) => t.label).join(' '), extraTerms]
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

// Run the model on one meme and persist. Returns the saved payload (to seed the
// twin index) or a terminal status. Re-derives the frame each time so memory
// stays flat across a whole library; times the call for telemetry.
async function describeAndSave(
  vision: VisionEnricher,
  m: MemeNeedingVisionRow,
  assoc: Map<string, string[]>,
  idx: number
): Promise<{ status: 'done'; payload: VisionPayload } | { status: 'failed' | 'unready' }> {
  const temp: string[] = [];
  const started = Date.now();
  try {
    const frame = await materializeFrame({ uri: m.uri, name: m.name, kind: m.kind }, idx);
    temp.push(...frame.temp);

    const res = await vision.describe(frame.jpeg, m.ocrText);
    if (!res) return { status: 'unready' }; // model went unready; leave pending

    // The VLM's open-vocabulary tags join the existing CLIP/OCR/exemplar tags,
    // ranked between them (above CLIP guesses, below the user's truth).
    const visionTags: Tag[] = res.tags.map((label) => ({
      label,
      category: 'topic',
      score: 0.9,
      source: 'vision' as const,
    }));
    const merged = dedupeRankTags([...m.tags, ...visionTags], 6);
    const extraTerms = visionExtraTerms(merged, assoc, res);
    const captionEmbedding = vision.embedText
      ? await vision.embedText(captionSearchText(res.caption, merged, extraTerms))
      : null;

    await setMemeVision(m.id, { caption: res.caption, captionEmbedding, tags: merged, extraTerms });
    recordDuration(Date.now() - started);
    return { status: 'done', payload: { caption: res.caption, captionEmbedding, tags: merged, extraTerms } };
  } catch {
    await markVisionFailed(m.id).catch(() => {});
    return { status: 'failed' };
  } finally {
    for (const t of temp) await deleteCache(t);
  }
}

// Process one pending meme: skip-as-duplicate when a twin exists, else describe.
// Keeps the running telemetry counters and the twin index up to date.
async function enrichOne(
  vision: VisionEnricher,
  m: MemeNeedingVisionRow,
  assoc: Map<string, string[]>,
  twins: TwinRec[]
): Promise<'done' | 'deduped' | 'failed' | 'unready'> {
  const twin = findTwin(m, twins);
  if (twin) {
    // Copy the twin's caption; re-rank tags so this meme keeps its own
    // CLIP/OCR/exemplar tags alongside the twin's vision tags.
    const visionTags = twin.tags.filter((t) => t.source === 'vision');
    const merged = dedupeRankTags([...m.tags, ...visionTags], 6);
    const extraTerms = `${extraTermsFor(merged, assoc)} ${twin.extraTerms}`.replace(/\s+/g, ' ').trim();
    const captionEmbedding =
      twin.captionEmbedding ??
      (vision.embedText ? await vision.embedText(captionSearchText(twin.caption, merged, extraTerms)) : null);
    const captionEmbeddingArray = captionEmbedding ? Array.from(captionEmbedding) : null;
    await setMemeVision(m.id, {
      caption: twin.caption,
      captionEmbedding: captionEmbeddingArray,
      tags: merged,
      extraTerms,
    });
    pushTwin({
      embedding: m.embedding,
      ocrText: m.ocrText,
      caption: twin.caption,
      captionEmbedding: captionEmbeddingArray ? Float32Array.from(captionEmbeddingArray) : null,
      tags: merged,
      extraTerms,
    });
    telem.deduped += 1;
    return 'deduped';
  }

  const r = await describeAndSave(vision, m, assoc, 0);
  if (r.status === 'done') {
    telem.described += 1;
    pushTwin({
      embedding: m.embedding,
      ocrText: m.ocrText,
      caption: r.payload.caption,
      captionEmbedding: r.payload.captionEmbedding
        ? Float32Array.from(r.payload.captionEmbedding)
        : null,
      tags: r.payload.tags,
      extraTerms: r.payload.extraTerms,
    });
    return 'done';
  }
  if (r.status === 'failed') {
    telem.failed += 1;
    return 'failed';
  }
  return 'unready';
}

// Walk every meme still awaiting a description. This is the "burst" path (the
// Settings "Describe N now" button). Runs AFTER the fast CLIP pass so the
// library is browsable immediately; this just makes it smarter. Strictly
// sequential — the on-device LLM does one generation at a time.
export async function enrichLibrary(
  vision: VisionEnricher,
  opts: { onProgress?: (p: EnrichProgress) => void; shouldCancel?: () => boolean } = {}
): Promise<EnrichResult> {
  if (!vision.ready) throw new Error('Vision model is still loading — try again shortly.');

  const assoc = await buildAssociations();
  const twins = await getTwinIndex();
  const queue = await getMemesNeedingVision();
  const total = queue.length;

  let described = 0;
  let deduped = 0;
  let failed = 0;
  for (let i = 0; i < queue.length; i++) {
    if (opts.shouldCancel?.()) break;
    opts.onProgress?.({ done: i, total, current: queue[i].name });
    const r = await enrichOne(vision, queue[i], assoc, twins);
    if (r === 'unready') break;
    if (r === 'done') described++;
    else if (r === 'deduped') deduped++;
    else failed++;
  }

  opts.onProgress?.({ done: total, total, current: '' });
  return { described, deduped, failed };
}

// Process exactly ONE pending meme — the unit of work for the paced background
// loop. Returns 'empty' when nothing is left to do (caller can back off).
export async function enrichNextMeme(
  vision: VisionEnricher
): Promise<'done' | 'deduped' | 'failed' | 'empty'> {
  if (!vision.ready) return 'empty';
  const queue = await getMemesNeedingVision(1);
  if (queue.length === 0) return 'empty';
  const assoc = await buildAssociations();
  const twins = await getTwinIndex();
  const r = await enrichOne(vision, queue[0], assoc, twins);
  return r === 'unready' ? 'empty' : r;
}

export async function backfillCaptionEmbeddings(
  api: Pick<EmbeddingsApi, 'embedText'>,
  opts: { limit?: number; onProgress?: (done: number, total: number) => void } = {}
): Promise<number> {
  const rows = await getMemesNeedingCaptionEmbedding(opts.limit ?? 25);
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    const text = captionSearchText(row.caption, row.tags, row.extraTerms);
    if (text) await setMemeCaptionEmbedding(row.id, await api.embedText(text));
    opts.onProgress?.(i + 1, rows.length);
  }
  return rows.length;
}

async function prepareVisualBackfillRow(
  row: MemeNeedingVisualEmbeddingRow,
  idx: number
): Promise<Prepared> {
  return prepareFile({ uri: row.uri, name: row.name, kind: row.kind }, idx);
}

export async function backfillVisualEmbeddings(
  api: Pick<EmbeddingsApi, 'embedVisualImage' | 'visualModel' | 'visualReady'>,
  opts: { limit?: number; onProgress?: (done: number, total: number) => void } = {}
): Promise<number> {
  if (!api.visualReady || !api.embedVisualImage || !api.visualModel.available) return 0;
  const rows = await getMemesNeedingVisualEmbedding(api.visualModel.id, opts.limit ?? 10);
  for (let i = 0; i < rows.length; i++) {
    const prep = await prepareVisualBackfillRow(rows[i], i);
    try {
      if (prep.ok) {
        const visual = await api.embedVisualImage(prep.jpeg);
        if (visual) await setMemeVisualEmbedding(rows[i].id, visual.model, visual.embedding);
      }
    } finally {
      for (const t of prep.temp) await deleteCache(t);
    }
    opts.onProgress?.(i + 1, rows.length);
  }
  return rows.length;
}

// Bounded session for an OS-scheduled background run: describe up to `maxItems`
// pending memes, stopping early if `shouldStop` flips (time budget expiring,
// device went hot/unplugged). Unlike the burst path it re-queries one meme at a
// time so a long session always sees freshly-indexed work and can bail cleanly.
export async function runBackgroundSession(
  vision: VisionEnricher,
  opts: { maxItems?: number; shouldStop?: () => boolean } = {}
): Promise<EnrichResult> {
  const result: EnrichResult = { described: 0, deduped: 0, failed: 0 };
  if (!vision.ready) return result;

  const assoc = await buildAssociations();
  const twins = await getTwinIndex();
  const max = opts.maxItems ?? Infinity;

  let n = 0;
  while (n < max) {
    if (opts.shouldStop?.()) break;
    const queue = await getMemesNeedingVision(1);
    if (queue.length === 0) break;
    const r = await enrichOne(vision, queue[0], assoc, twins);
    if (r === 'unready') break;
    if (r === 'done') result.described++;
    else if (r === 'deduped') result.deduped++;
    else result.failed++;
    n++;
  }
  return result;
}
