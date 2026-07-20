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
  clearIndexErrorsFor,
  countMemesDescribed,
  dot,
  getAllMemeEmbeddings,
  getAllThumbUris,
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
  getPendingMemes,
  getPendingUris,
  getVideosNeedingThumb,
  findContentHashUri,
  insertMeme,
  insertPendingMeme,
  markVisionFailed,
  recordContentHash,
  markVisualEmbeddingFailed,
  migrateStaleExemplars,
  refacetExemplars,
  getSetting,
  setSetting,
  putLabelVector,
  setMemeCaptionEmbedding,
  setMemeThumb,
  setMemeVisualEmbedding,
  setMemeVision,
  stampIndexModel,
  THUMB_FAILED,
  updateMemeTags,
  type DescribedVisionRow,
  type MemeNeedingVisualEmbeddingRow,
  type MemeNeedingVisionRow,
} from './db';
import { extractVideoFrame, extractVideoFramePlayer } from '../modules/memeget-bg';
import type { ThumbPatch } from './events';
import { acquireKeepAlive } from './keepAlive';
import { codecInteractiveActive, interactiveActive, yieldToSearch } from './interactive';
import { ASSOCIATIONS, MEME_LABELS, NEGATIVE_ANCHORS, ocrTags } from './memeLabels';
import {
  copyToCache,
  deleteCache,
  getModifiedTime,
  listMedia,
  persistThumb,
  readSourceBase64,
  writeBase64ToFolder,
  sweepOrphanThumbs,
  type SafFile,
} from './saf';
import { hashBase64 } from './contentHash';
import { formatGrounding, type GroundingLabel, type VisionResult } from './visionCore';
import { captionSearchText, memeExtraTerms } from './searchText';
import {
  dedupeFrames,
  flattenFrameTags,
  frameLadderMs,
  meanPoolNormalized,
  mergeVisionResults,
  unionOcrText,
  visionResultsSimilar,
  MAX_VIDEO_FRAMES,
  MAX_VLM_FRAMES,
} from './videoFrames';
import type { Tag } from './types';

// Confidence in how a label was matched, highest first:
//   manual     — the user typed it (e.g. multi-select bulk tag); never overridden
//   ocr        — text literally in the image (watermark/caption)
//   exemplar   — the user's own ground truth, taught by example
//   propagated — spread from a manual tag to a visual look-alike
//   vision     — the VLM's (Gemma) open-vocabulary read of the image
//   prompt     — CLIP zero-shot guess against the fixed label vocabulary
const TAG_RANK: Record<NonNullable<Tag['source']>, number> = {
  manual: 6,
  ocr: 5,
  exemplar: 4,
  propagated: 3,
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

// Width fed to the VLM. Gemma resamples to its vision encoder's fixed square
// anyway, so feeding more pixels only inflates decode/transcode cost. Capping at
// 512 keeps that bounded (the ML Kit OCR hint covers any small text we'd lose).
// Overridable for on-device A/B: lowering it trims the per-meme prefill. Falls
// back to 512.
const VLM_FRAME_WIDTH_ENV = Number(process.env.EXPO_PUBLIC_MEMEGET_VLM_FRAME_WIDTH);
const VLM_FRAME_WIDTH =
  Number.isFinite(VLM_FRAME_WIDTH_ENV) && VLM_FRAME_WIDTH_ENV > 0
    ? Math.round(VLM_FRAME_WIDTH_ENV)
    : 512;

// Pull frames from a local video by climbing the timestamp ladder, stopping as
// soon as a rung lands past the clip's real end. Thumbnail extraction either
// throws or clamps to the last frame for an out-of-range time depending on the
// device/codec — both are handled: a throw stops the climb here, and a clamp is
// collapsed downstream (dedupeFrames in the fast pass, visionResultsSimilar in
// the VLM pass) because every out-of-range rung returns the same final frame.
// A single-frame request (maxFrames <= 1) keeps the legacy t=1s grab so the
// callers that only need one representative frame (poster/DINO backfill) are
// unchanged. Extracted uris are appended to `temp` so the caller cleans them up.
async function sampleVideoFrames(
  work: string,
  temp: string[],
  maxFrames: number
): Promise<string[]> {
  if (maxFrames <= 1) {
    const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time: 1000 });
    temp.push(uri);
    return [uri];
  }
  const uris: string[] = [];
  for (const time of frameLadderMs(maxFrames)) {
    try {
      const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time });
      uris.push(uri);
      temp.push(uri);
    } catch {
      // Past the end of the video (or an undecodable timestamp): the higher
      // rungs would only fail too, so stop climbing.
      break;
    }
    if (uris.length >= maxFrames) break;
  }
  if (uris.length === 0) {
    // Legacy fallback — one keyframe at 1s — so a clip the ladder couldn't
    // sample (e.g. a first rung that threw) still gets indexed if it can at all.
    const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time: 1000 });
    uris.push(uri);
    temp.push(uri);
  }
  return uris;
}

// Turn a library item (image or video) into the local JPEG paths the models can
// read, plus the temp files to clean up afterward. The VLM needs an actual
// on-disk path (its `mediaPath`), and SAF content:// URIs aren't usable
// directly — so we re-derive the frames the same way the index pass does. A
// video yields up to `maxFrames` distinct-moment candidates (the VLM pass
// early-stops once captions stop changing); an image yields exactly one.
async function materializeFrames(
  file: SafFile,
  idx: number,
  maxFrames: number
): Promise<{ jpegs: string[]; temp: string[] }> {
  const temp: string[] = [];
  const work = await copyToCache(file, idx);
  temp.push(work);
  const frames = file.kind === 'video' ? await sampleVideoFrames(work, temp, maxFrames) : [work];
  const jpegs: string[] = [];
  for (const frame of frames) {
    const jpeg = await toJpeg(frame, VLM_FRAME_WIDTH);
    temp.push(jpeg);
    jpegs.push(jpeg);
  }
  return { jpegs, temp };
}

// Embed → OCR → classify a set of already-transcoded frame JPEGs and fold them
// into one meme's worth of signal, without touching the DB schema:
//  - embedding: the mean-pooled, re-normalized primary vector — one "gist"
//    vector standing in for the whole clip, a better anchor than any keyframe.
//  - ocrText:   the union of text read across DISTINCT frames, so a caption that
//    only shows up partway through a video still gets indexed.
//  - tags:      zero-shot labels unioned across distinct frames (a character
//    that appears in only one moment still gets tagged), merged with OCR tags.
// Visually-identical frames are collapsed first (dedupeFrames) so a static clip
// costs one classify, not `maxFrames`. A single image flows through unchanged
// (dedupe/mean-pool of one frame is a no-op, and its embed∥OCR overlap is kept).
async function analyzeFrames(
  api: EmbeddingsApi,
  jpegs: string[],
  know: Knowledge
): Promise<{ embedding: number[]; ocrText: string; tags: Tag[] }> {
  const frames: { embedding: number[]; ocrText: string }[] = [];
  for (const jpeg of jpegs) {
    // Primary embed (ExecuTorch) and OCR (ML Kit) are independent native calls
    // on the same frame — run concurrently so the shorter hides behind the
    // longer. Frames run sequentially so only one embed is ever in flight,
    // keeping peak memory flat (matching the pipeline's one-per-stage design).
    const [embedding, ocrText] = await Promise.all([api.embedImage(jpeg), ocr(jpeg)]);
    frames.push({ embedding, ocrText });
  }
  const reps = dedupeFrames(frames);
  const embedding = meanPoolNormalized(reps.map((r) => r.embedding));
  const ocrText = unionOcrText(reps.map((r) => r.ocrText));
  const perFrame = reps.map((r) =>
    classifyImage(r.embedding, know.labelVecs, know.exemplarHeads, know.mean, know.negativeVecs)
  );
  const tags = mergeTags(flattenFrameTags(perFrame), ocrTags(ocrText));
  return { embedding, ocrText, tags };
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

// Coordination with the idle-time backfills: while any heavy pass runs
// (indexing, re-tagging), the DINO/caption backfill loops must stand down —
// fp32 DINOv2 running flat-out saturates the CPU and starves everything else,
// which is what made a fresh index sit on "Preparing to index…" for minutes.
// Heavy passes also hold the keep-alive foreground service: a multi-minute
// index must keep running when the user switches apps or the screen sleeps,
// not silently freeze until they come back.
let heavyPasses = 0;
async function withHeavyPass<T>(fn: () => Promise<T>, label = 'Indexing your library'): Promise<T> {
  heavyPasses++;
  const release = acquireKeepAlive(label);
  try {
    return await fn();
  } finally {
    heavyPasses--;
    release();
  }
}

// Interactive work (a live search) also outranks idle loops: a text embed
// stuck behind a DINO batch or a queued describe is what made search feel dead
// while "AI description stuff" ran. The interactive window lives in its own
// dependency-free module (src/interactive.ts) so the describe/transcribe/embed
// loops can all share it; re-exported here for the existing callers that import
// it from the indexer.
export { noteInteractive, interactiveActive, yieldToSearch } from './interactive';

export function heavyPassActive(): boolean {
  return heavyPasses > 0 || interactiveActive();
}

// Narrower gate for light idle work (the poster backfill): yield to indexing/
// re-tagging, but NOT to the interactive window. Posters are what the user is
// actively watching for — gating them on "the user touched the app in the
// last 8s" froze the backfill for exactly as long as anyone sat there waiting
// for tiles to fill in.
export function indexingActive(): boolean {
  return heavyPasses > 0;
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
  // Old-space taught examples automatically re-based onto the fresh index
  // (see migrateStaleExemplars) — when > 0 the caller should re-tag.
  migratedExemplars: number;
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
// `onStatus` streams what the slow parts are actually doing — a silent
// buildKnowledge is what made "Indexing 0/…" read as a hang: vocabulary
// re-embeds only happen after a model change, but retraining the taught-label
// heads happens whenever the teach state changed, and with many taught labels
// that's real minutes of on-device math.
export async function buildKnowledge(
  api: EmbeddingsApi,
  onStatus?: (s: string) => void
): Promise<Knowledge> {
  const modelId = api.primaryModel.id;
  const cache = await getLabelVectors(modelId);

  const missingLabels = MEME_LABELS.filter((d) => !cache.has(d.label));
  const missingAnchors = NEGATIVE_ANCHORS.map((_, i) => i).filter(
    (i) => !cache.has(`${NEG_PREFIX}${i}`)
  );
  const vocabTotal = missingLabels.length + missingAnchors.length;
  let vocabDone = 0;
  const noteVocab = () => {
    vocabDone++;
    if (vocabDone % 10 === 0 || vocabDone === vocabTotal) {
      onStatus?.(`embedding label vocabulary ${vocabDone}/${vocabTotal} (model changed)…`);
    }
  };
  if (vocabTotal > 0) onStatus?.(`embedding label vocabulary 0/${vocabTotal} (model changed)…`);

  for (const def of missingLabels) {
    const vec = await api.embedText(def.prompt);
    await putLabelVector(def.label, vec, modelId);
    cache.set(def.label, Float32Array.from(vec));
    noteVocab();
  }
  for (const i of missingAnchors) {
    const key = `${NEG_PREFIX}${i}`;
    const vec = await api.embedText(NEGATIVE_ANCHORS[i]);
    await putLabelVector(key, vec, modelId);
    cache.set(key, Float32Array.from(vec));
    noteVocab();
  }

  const labelVecs: LabelVec[] = MEME_LABELS.filter((d) => cache.has(d.label)).map((d) => ({
    label: d.label,
    category: d.category,
    vec: cache.get(d.label)!,
  }));
  const negativeVecs = NEGATIVE_ANCHORS.map((_, i) => cache.get(`${NEG_PREFIX}${i}`)!).filter(Boolean);

  const exemplars = await getExemplars();
  onStatus?.('training taught labels…');
  const { heads: exemplarHeads, mean } = await buildExemplarHeads();
  onStatus?.('');

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
  return withHeavyPass(async () => {
  // Give the progress card something honest to show right away, then stream
  // buildKnowledge's real phases through it — a static "warming up" line over
  // minutes of head-retraining read as a hang (and wrongly blamed a "model
  // change" on every single run).
  const status = (s: string) =>
    opts.onProgress?.({ processed: 0, total: 0, added: 0, current: s || 'preparing to index…' });
  status('preparing to index…');
  const know = await buildKnowledge(api, status);
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

  // Stuck share-imports jump the queue: pending placeholder rows sort to the
  // very top of the library showing eternal spinners, so an index run should
  // replace them with real rows in its first seconds, not after grinding
  // through however many other new files the folder scan found.
  const pendingUris = await getPendingUris().catch(() => new Set<string>());
  if (pendingUris.size > 0) {
    queue.sort((a, b) => Number(pendingUris.has(b.uri)) - Number(pendingUris.has(a.uri)));
  }

  // Record which primary space this index is being built in, so a later model
  // swap is detected instead of silently searched across spaces.
  await stampIndexModel().catch(() => {});

  const total = allFiles.length;
  const { added, errors } = await indexQueue(api, queue, know, {
    shouldCancel: opts.shouldCancel,
    onFile: (i, file, addedSoFar) =>
      opts.onProgress?.({ processed: skipped + i, total, added: addedSoFar, current: file.name }),
  });

  // The library is now (re)indexed in the active primary space, which is the
  // one moment old-space taught examples can be re-based automatically from
  // their source memes' fresh embeddings. Cheap no-op when nothing is stale.
  // Say so on the card — post-bar silence reads as a hang.
  opts.onProgress?.({ processed: total, total, added, current: 'migrating taught examples…' });
  const { migrated } = await migrateStaleExemplars().catch(() => ({ migrated: 0 }));

  // One-time: re-file exemplars the old teach flow blanket-tagged as 'character'
  // into their real facet (Waving→action, Excited→emotion, …). Guarded so it
  // runs once; new teachings already infer their facet at save time.
  if ((await getSetting(REFACET_EXEMPLARS_KEY).catch(() => null)) !== '1') {
    await refacetExemplars()
      .then(() => setSetting(REFACET_EXEMPLARS_KEY, '1'))
      .catch(() => {});
  }

  // Reclaim posters whose meme rows are gone (deleted memes, a cleared+rebuilt
  // index). Cheap set-difference over one small directory.
  try {
    await sweepOrphanThumbs(await getAllThumbUris());
  } catch {
    // best-effort; orphans get another chance next index
  }

  opts.onProgress?.({ processed: total, total, added, current: '' });
  return { added, skipped, errors, migratedExemplars: migrated };
  });
}

// Result of stage 1 (prepare). A failed prepare still carries its temp files so
// stage 2 can clean up and log the error with the right stage name.
type Prepared =
  | { ok: true; file: SafFile; modifiedAt: number | null; jpegs: string[]; temp: string[] }
  | { ok: false; file: SafFile; stage: string; reason: string; temp: string[] };

// Stage 1 of the per-file pipeline: copy out of SAF → (video keyframes) → JPEG
// transcode. Pure native I/O + codecs, no model involvement — which is what
// lets the NEXT file's stage 1 run while the current file sits in the model. A
// video yields up to `maxFrames` frames sampled across its timeline (the index
// pass wants several; the single-frame backfill callers leave the default so a
// poster/DINO grab stays one keyframe).
async function prepareFile(file: SafFile, idx: number, maxFrames = 1): Promise<Prepared> {
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

    stage = file.kind === 'video' ? 'thumbnail' : 'transcode';
    const frames = file.kind === 'video' ? await sampleVideoFrames(work, temp, maxFrames) : [work];

    stage = 'transcode';
    const jpegs: string[] = [];
    for (const frame of frames) {
      const jpeg = await toJpeg(frame);
      temp.push(jpeg);
      jpegs.push(jpeg);
    }
    return { ok: true, file, modifiedAt, jpegs, temp };
  } catch (e) {
    return { ok: false, file, stage, reason: String((e as Error)?.message ?? e).slice(0, 300), temp };
  }
}

// Stage 2: embed + OCR → classify → store. Logs failures to the index-error
// table and cleans up the prepare stage's temp files. Posters are deliberately
// NOT stamped here: the embed frame is a fixed t=1s grab that lands on
// fade-from-black intros, so every poster instead flows through the backfill's
// luma-checked native extractor (the poster loop wakes on the library-changed
// events indexing emits, so tiles fill within seconds of a row landing).
async function finishFile(api: EmbeddingsApi, prep: Prepared, know: Knowledge): Promise<'added' | 'error'> {
  let stage = 'embed';
  try {
    if (!prep.ok) {
      stage = prep.stage;
      throw new Error(prep.reason);
    }

    // Embed + OCR + classify every sampled frame and fold them into one meme's
    // worth of signal (mean-pooled gist vector, unioned OCR, unioned tags) —
    // an image has one frame, a video several distinct moments. Per-frame the
    // primary embed (ExecuTorch) and OCR (ML Kit) run concurrently so the
    // shorter hides behind the longer.
    //
    // The DINO visual embed is deliberately NOT here: fp32 DINOv2-base costs a
    // multiple of the primary embed per frame, and putting it in the indexing
    // hot path made a fresh index crawl. The idle-time backfill loop
    // (backfillVisualEmbeddings) owns visual vectors instead — the library is
    // browsable/searchable immediately and "More like this" upgrades to DINO
    // as the backfill catches up.
    const { embedding, ocrText, tags } = await analyzeFrames(api, prep.jpegs, know);

    stage = 'store';
    await insertMeme({
      uri: prep.file.uri,
      name: prep.file.name,
      kind: prep.file.kind,
      embedding,
      ocrText,
      tags,
      extraTerms: extraTermsFor(tags, know.assoc),
      modifiedAt: prep.modifiedAt,
    });
    return 'added';
  } catch (e) {
    const reason = String((e as Error)?.message ?? e).slice(0, 300);
    await addIndexError({ name: prep.file.name, kind: prep.file.kind, stage, reason }).catch(() => {});
    // A file the pipeline can't process (e.g. an animated GIF the transcoder
    // rejects) used to stay a pending placeholder forever — a spinner in the
    // grid on every launch. Store it as a degraded row instead: visible,
    // findable by filename, and permanently skipped by every model pass. The
    // error stays in the Settings diagnostics list.
    await insertMeme({
      uri: prep.file.uri,
      name: prep.file.name,
      kind: prep.file.kind,
      embedding: [],
      ocrText: '',
      tags: [],
      extraTerms: '',
      modifiedAt: prep.ok ? prep.modifiedAt : null,
      degraded: true,
    }).catch(() => {});
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
  let next: Promise<Prepared> | null = queue.length
    ? prepareFile(queue[0], 0, MAX_VIDEO_FRAMES)
    : null;
  for (let i = 0; i < queue.length; i++) {
    const prep = await next!;
    if (opts.shouldCancel?.()) {
      for (const t of prep.temp) await deleteCache(t);
      break;
    }
    // Kick off the next file's prepare BEFORE finishing this one — this is the
    // overlap that makes the pipeline worth having.
    next = i + 1 < queue.length ? prepareFile(queue[i + 1], i + 1, MAX_VIDEO_FRAMES) : null;
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
): Promise<{ saved: SafFile[]; errors: number; duplicates: number; folderName: string }> {
  const folders = await getFolders();
  if (folders.length === 0) {
    throw new Error('Link a folder first (Library tab) so shared memes have a place to live.');
  }
  const folder = folders[0];

  const saved: SafFile[] = [];
  let errors = 0;
  let duplicates = 0;
  for (let i = 0; i < files.length; i++) {
    opts.onProgress?.(i, files.length);
    const src = files[i];
    const kind: 'image' | 'video' = src.mimeType.startsWith('video') ? 'video' : 'image';
    try {
      // Read the bytes once, fingerprint them, and skip the save entirely if
      // this exact meme is already in the library. This is what stops a re-share
      // (or an OS that redelivers the same share intent to a cold-started
      // process) from writing a second copy — every save mints a new file/URI,
      // so URI-uniqueness alone never catches it. writeBase64ToFolder then reuses
      // the bytes we already read, so a duplicate costs no extra write.
      const data = await readSourceBase64(src.path);
      const hash = hashBase64(data);
      if (await findContentHashUri(hash).catch(() => null)) {
        duplicates++;
        continue;
      }
      const { uri, name } = await writeBase64ToFolder(data, src.fileName, src.mimeType, folder.uri);
      await recordContentHash(hash, uri).catch(() => {});
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
  return { saved, errors, duplicates, folderName: folder.name };
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
  return withHeavyPass(async () => {
    const know = await buildKnowledge(api);
    await stampIndexModel().catch(() => {});
    const { added, errors } = await indexQueue(api, saved, know, {
      onFile: (i) => opts.onProgress?.(i, saved.length),
    });
    opts.onProgress?.(saved.length, saved.length);
    return { added, errors };
  });
}

// Recovery sweep for share-imports whose at-share-time index never finished
// (model still loading, app killed mid-import): their placeholder rows sit at
// the top of the library as eternal spinner tiles, and nothing retried them
// without the user manually running Index. Re-indexes exactly those files.
// insertMeme replaces the placeholder by uri; a file that has since vanished
// from the folder degrades its row instead, so the sweep always terminates.
export async function indexPendingMemes(
  api: EmbeddingsApi
): Promise<{ added: number; errors: number }> {
  const rows = await getPendingMemes();
  if (rows.length === 0) return { added: 0, errors: 0 };
  return indexSavedFiles(
    api,
    rows.map((r) => ({ uri: r.uri, name: r.name, kind: r.kind as SafFile['kind'] }))
  );
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
  opts: { onProgress?: (done: number, total: number) => void; shouldCancel?: () => boolean } = {}
): Promise<RetagResult> {
  return withHeavyPass(async () => {
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
    // Cancellable (the Stop button reaches this now): rows classified so far
    // are still written below, so a stopped re-tag makes partial progress.
    if (opts.shouldCancel?.()) break;
    const row = rows[i];
    // Degraded rows (files the pipeline couldn't process) have no embedding.
    if (row.embedding.length === 0) {
      opts.onProgress?.(i + 1, rows.length);
      continue;
    }
    const vec = Array.from(row.embedding);
    const sig = embSig(row.embedding);
    let prompts = promptTagCache.get(row.id);
    if (!prompts || prompts.sig !== sig) {
      prompts = { sig, tags: classifyPrompts(vec, know.labelVecs, know.negativeVecs) };
      promptTagCache.set(row.id, prompts);
    }
    const visual = mergeClassified(prompts.tags, classifyExemplars(vec, know.exemplarHeads, know.mean));
    const base = mergeTags(visual, ocrTags(row.ocrText));
    // Preserve any VLM tags, user-applied (manual) tags, and tags spread from a
    // manual tag to look-alikes — re-tagging applies new taught knowledge, it
    // shouldn't erase the vision pass's work or a tag the user put there.
    const kept = row.tags.filter(
      (t) => t.source === 'vision' || t.source === 'manual' || t.source === 'propagated'
    );
    const merged = dedupeRankTags([...base, ...kept], 6);
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
  });
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

// ---- VLM enrichment pass (Gemma 4 E2B) --------------------------------------

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
  describe: (jpegPath: string, ocrHint?: string, grounding?: string) => Promise<VisionResult | null>;
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
// One-time flag: existing exemplars have been re-filed off the legacy blanket
// 'character' category into their inferred facet. Bump the version when the
// facet vocabulary grows so the re-facet pass runs again and catches labels it
// now recognizes (v2: added a public-figure person list + topic words).
const REFACET_EXEMPLARS_KEY = 'exemplars.refaceted.v2';

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
  return memeExtraTerms(extraTermsFor(merged, assoc), res);
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
    const frames = await materializeFrames({ uri: m.uri, name: m.name, kind: m.kind }, idx, MAX_VLM_FRAMES);
    temp.push(...frames.temp);

    // Retrieval-augmented grounding: the fast CLIP pass already tagged this meme
    // (m.tags) from the harvested label vocabulary — knowledge the small VLM
    // often lacks. Hand it the top format/character guesses (+ their association
    // terms) so it can NAME templates/characters it couldn't recognize alone.
    const groundLabels: GroundingLabel[] = [...m.tags]
      .sort((a, b) => b.score - a.score)
      .map((t) => ({ label: t.label, category: t.category }));
    const related = m.tags.flatMap((t) => assoc.get(t.label) ?? []);
    const grounding = formatGrounding(groundLabels, related);

    // Describe each distinct frame, stopping as soon as a frame says essentially
    // the same thing as the previous one — a static clip pays for one generation,
    // a multi-scene edit for a few. Their descriptions are then folded into one
    // (unioned subjects/tags/text, joined scene captions).
    const results: VisionResult[] = [];
    for (const jpeg of frames.jpegs) {
      const r = await vision.describe(jpeg, m.ocrText, grounding);
      if (!r) break; // model went unready mid-way
      if (results.length && visionResultsSimilar(r, results[results.length - 1])) break;
      results.push(r);
    }
    if (results.length === 0) return { status: 'unready' }; // never got a frame in; leave pending
    const res = mergeVisionResults(results);

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
    // Let a live search through: this burst runs generations back-to-back, and
    // without a break between them the query's text embed never reaches the
    // accelerator until the whole queue drains. Stand down while the user is
    // searching so their vector lands and results upgrade past lexical-only.
    await yieldToSearch(opts.shouldCancel);
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
    // This backfill calls the SAME text-embed model a live search waits on, so a
    // batch of 20 run back-to-back would starve the query's own embed until the
    // batch drained. Stand down between items while the user is searching so
    // their vector lands and results upgrade past lexical-only — same per-item
    // yield the describe/transcribe bursts use.
    await yieldToSearch();
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

// Returns the number of rows FETCHED (0 = queue drained, caller can stop
// looping). Termination is guaranteed by failure stamping: a row that can't be
// prepared or embedded is marked failed and stops matching the pending query,
// so no batch can be re-served forever. A model that went unready mid-batch is
// transient — bail with 0 and let the next readiness change retry.
export async function backfillVisualEmbeddings(
  api: Pick<EmbeddingsApi, 'embedVisualImage' | 'visualModel' | 'visualReady'>,
  opts: { limit?: number; onProgress?: (done: number, total: number) => void } = {}
): Promise<number> {
  if (!api.visualReady || !api.embedVisualImage || !api.visualModel.available) return 0;
  const model = api.visualModel.id;
  const rows = await getMemesNeedingVisualEmbedding(model, opts.limit ?? 10);
  for (let i = 0; i < rows.length; i++) {
    // Yield to indexing/re-tagging the moment one starts — this loop is idle
    // work and DINO is the heaviest model in the app. Returning nonzero keeps
    // the caller's loop alive; it re-checks the gate before calling again.
    if (heavyPassActive()) return rows.length;
    // Breathe between items even when idle so the backfill never pins the CPU
    // (thermals, UI responsiveness) — it has all day.
    if (i > 0) await new Promise<void>((r) => setTimeout(r, 400));
    const prep = await prepareVisualBackfillRow(rows[i], i);
    try {
      if (!prep.ok) {
        // Unreadable file (deleted from the folder, corrupt) — permanent.
        await markVisualEmbeddingFailed(rows[i].id, model).catch(() => {});
      } else {
        const visual = await api.embedVisualImage(prep.jpegs[0]);
        if (!visual) return 0; // model went unready — stop, don't stamp
        await setMemeVisualEmbedding(rows[i].id, visual.model, visual.embedding);
      }
    } catch {
      // Per-image native failure (decode error etc.) — permanent for this row;
      // stamping it keeps the loop marching instead of aborting the batch.
      await markVisualEmbeddingFailed(rows[i].id, model).catch(() => {});
    } finally {
      for (const t of prep.temp) await deleteCache(t);
    }
    opts.onProgress?.(i + 1, rows.length);
  }
  return rows.length;
}

// Grid thumbnail width for images. Cells are one-third of the screen — a few
// hundred physical px on a phone — so a 512px JPEG covers them crisply while
// decoding an order of magnitude faster than a full-res original and costing a
// few tens of KB on disk (vs the multi-MB source). expo-image downscales it the
// rest of the way to the exact cell size.
const IMAGE_THUMB_WIDTH = 512;

// One image's grid thumbnail: transcode the original down to IMAGE_THUMB_WIDTH
// and persist it. manipulateAsync reads the SAF content:// uri directly (via the
// resolver), so the common case never copies the whole file; a copy-to-cache
// fallback covers providers/formats that won't decode straight off the uri.
// Returns the persisted path, or null if neither path could decode it — for a
// normal image that's a corrupt file (the grid keeps falling back to the
// original uri); for an mp4-as-gif the caller then tries the video ladder.
async function extractImageThumb(row: { uri: string; name: string }, idx: number): Promise<string | null> {
  try {
    const jpeg = await toJpeg(row.uri, IMAGE_THUMB_WIDTH);
    try {
      return await persistThumb(jpeg);
    } finally {
      await deleteCache(jpeg);
    }
  } catch {
    // fall through to the local-copy attempt
  }
  let work: string | null = null;
  try {
    work = await copyToCache({ uri: row.uri, name: row.name, kind: 'image' }, idx);
    const jpeg = await toJpeg(work, IMAGE_THUMB_WIDTH);
    try {
      return await persistThumb(jpeg);
    } finally {
      await deleteCache(jpeg);
    }
  } catch {
    return null;
  } finally {
    if (work) await deleteCache(work);
  }
}

// Fast path for a poster: MediaMetadataRetriever reads the SAF content:// uri
// directly (the module opens a file descriptor off the resolver), so grabbing
// one frame does NOT require copying the whole multi-megabyte video into the
// cache the way the embed pipeline must. Frame → downscaled jpeg → permanent
// thumbs dir. Throws on any failure; the caller falls back to the copy path
// (some providers/containers only cooperate with a real local file).
async function extractPosterDirect(uri: string): Promise<string> {
  const { uri: frame } = await VideoThumbnails.getThumbnailAsync(uri, { time: 1000 });
  try {
    const jpeg = await toJpeg(frame);
    try {
      return await persistThumb(jpeg);
    } finally {
      await deleteCache(jpeg);
    }
  } finally {
    await deleteCache(frame);
  }
}

// MediaMetadataRetriever is known to occasionally HANG (not fail) on certain
// streams — and one hung native call would wedge the whole worker pool below,
// silently ending the backfill for the session. Race every grab against a
// timeout; the abandoned native op can't be cancelled (its temp files are
// reclaimed by the launch sweep), but the loop keeps marching.
function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      }
    );
  });
}
const THUMB_DIRECT_TIMEOUT_MS = 8_000;
const THUMB_FALLBACK_TIMEOUT_MS = 20_000;

// Videos whose poster extraction TIMED OUT recently. Deliberately not stamped
// THUMB_FAILED (a timeout is usually transient codec/CPU contention — a
// describe generation or an index running underneath — and a permanent stamp
// would blank the tile forever), but re-serving them every batch would
// re-wedge the pool. They sit out for a cooldown, then retry: benching them
// for the whole session turned one busy stretch into "many thumbnails still
// missing" until the next app launch.
const THUMB_SKIP_RETRY_MS = 10 * 60_000;
const thumbSkip = new Map<number, number>(); // meme id -> retry-after timestamp

async function nextThumbRows(
  limit: number
): Promise<{ id: number; uri: string; name: string; kind: 'image' | 'video' }[]> {
  const now = Date.now();
  for (const [id, until] of thumbSkip) {
    if (until <= now) thumbSkip.delete(id);
  }
  const rows = await getVideosNeedingThumb(limit + thumbSkip.size);
  return rows.filter((r) => !thumbSkip.has(r.id)).slice(0, limit);
}

// Whether any video still awaits a poster (excluding this session's timed-out
// skips) — the DINO backfill defers to this queue.
export async function videoThumbsPending(): Promise<boolean> {
  return (await nextThumbRows(1)).length > 0;
}

// Extract grid posters for videos indexed before posters existed (or whose
// inline extraction failed transiently). No model involved, and the fast path
// doesn't even copy the file — so a few hundred missing posters drain in
// seconds, not minutes. A small worker pool keeps several frame grabs in
// flight (frame retrieval is I/O + a single decoded frame; Android handles a
// few retrievers at once fine). Yields to indexing/re-tagging only — NOT the
// interactive window: posters are what the user is actively watching for.
// Returns rows FETCHED (0 = drained); THUMB_FAILED stamping and the timeout
// skip-set guarantee termination.
const isTimeout = (e: unknown): boolean => String((e as Error)?.message) === 'timeout';
// Expo wraps native rejections in boilerplate — "Call to function 'X' has
// been rejected. → Caused by: java.lang.IllegalStateException: <reason>" —
// which ate the whole diagnostics budget before the reason. Keep only the
// part after the last cause, minus the exception class name.
const errText = (e: unknown): string => {
  let s = String((e as Error)?.message ?? e);
  const causedBy = s.lastIndexOf('Caused by:');
  if (causedBy >= 0) {
    s = s
      .slice(causedBy + 'Caused by:'.length)
      .replace(/^\s*[a-zA-Z0-9_.$]*(Exception|Error)[:\s]*/, '');
  }
  return s.trim().slice(0, 120);
};

// One video's poster, tried four ways:
//  1. our own MediaCodec decoder (native) in AUTO mode — the player's decode
//     path, duration-aware and near-black-frame-rejecting (a fixed t=1s
//     poster landed on fade-from-black intros), and it reads "mp4 gif" style
//     streams MMR flatly refuses. The primary path.
//  2. MediaMetadataRetriever straight off the SAF uri (no copy)
//  3. MediaMetadataRetriever on a real local copy (provider/container quirks)
//  4. ExoPlayer's (media3) frame extractor — its own container parsers read a
//     few streams the platform demuxers in 1–3 reject; the last resort for a
//     clip that plays in the viewer but no platform decoder will poster.
// Returns the persisted path, 'timeout' (transient — retry next session), or
// null (genuinely undecodable — stamp THUMB_FAILED). A final failure logs all
// four per-path reasons to the diagnostics list: extraction failures happen
// on a device we can't attach to, and "which path said what" is the only way
// to tell a missing codec from a broken file from a permission problem.
async function extractPosterAnyway(
  row: { id: number; uri: string; name: string; kind: 'image' | 'video' },
  idx: number
): Promise<string | 'timeout' | null> {
  // Images just transcode down to a small grid thumb (no codec, no seek). A
  // real image succeeds here; only a genuinely undecodable one falls through —
  // and then only .gif-named files are worth the video ladder (mp4 bytes wearing
  // a .gif name). A normal image that won't decode is a corrupt download: return
  // null so it's stamped and the grid keeps rendering off the original uri,
  // instead of burning the whole 30s video ladder on a file it can't help.
  if (row.kind === 'image') {
    const thumb = await extractImageThumb(row, idx);
    if (thumb) return thumb;
    if (!/\.gif$/i.test(row.name)) {
      await addIndexError({
        name: row.name,
        kind: 'image',
        stage: 'thumbnail',
        reason: 'image could not be decoded to a grid thumbnail',
      }).catch(() => {});
      return null;
    }
    // .gif-named and undecodable as an image → almost certainly an mp4 in
    // disguise; fall through to the video decode ladder below.
  }

  let timedOut = false;
  const errs: string[] = [];

  try {
    const frame = await withTimeout(extractVideoFrame(row.uri, -1), THUMB_FALLBACK_TIMEOUT_MS);
    if (frame) {
      try {
        const jpeg = await toJpeg(frame);
        try {
          return await persistThumb(jpeg);
        } finally {
          await deleteCache(jpeg);
        }
      } finally {
        await deleteCache(frame);
      }
    }
    errs.push('codec: native module not built in');
  } catch (e) {
    timedOut ||= isTimeout(e);
    errs.push(`codec: ${errText(e)}`);
  }

  try {
    return await withTimeout(extractPosterDirect(row.uri), THUMB_DIRECT_TIMEOUT_MS);
  } catch (e) {
    timedOut ||= isTimeout(e);
    errs.push(`direct: ${errText(e)}`);
  }

  try {
    const prep = await withTimeout(
      prepareFile({ uri: row.uri, name: row.name, kind: 'video' }, idx),
      THUMB_FALLBACK_TIMEOUT_MS
    );
    try {
      if (prep.ok) return await persistThumb(prep.jpegs[0]);
      errs.push(`copy: ${prep.stage}: ${prep.reason.slice(0, 90)}`);
    } finally {
      for (const t of prep.temp) await deleteCache(t);
    }
  } catch (e) {
    timedOut ||= isTimeout(e);
    errs.push(`copy: ${errText(e)}`);
  }

  // Last resort: ExoPlayer's own decode pipeline (media3), which parses a few
  // containers the platform demuxers above reject. If the clip plays in the
  // viewer, this posters it; if this fails too, the file is genuinely
  // undecodable (a corrupt/truncated download) and the stub is the final state.
  try {
    const frame = await withTimeout(extractVideoFramePlayer(row.uri, -1), THUMB_FALLBACK_TIMEOUT_MS);
    if (frame) {
      try {
        const jpeg = await toJpeg(frame);
        try {
          return await persistThumb(jpeg);
        } finally {
          await deleteCache(jpeg);
        }
      } finally {
        await deleteCache(frame);
      }
    }
    errs.push('player: native module not built in');
  } catch (e) {
    timedOut ||= isTimeout(e);
    errs.push(`player: ${errText(e)}`);
  }

  if (timedOut) return 'timeout';
  await addIndexError({
    name: row.name,
    kind: 'video',
    stage: 'poster',
    reason: errs.join(' | ').slice(0, 300),
  }).catch(() => {});
  return null;
}

// The Settings retry button clears the failure stamps in the DB; the session
// skip-set has to go with them or the retried rows stay invisible here.
export function clearThumbSkips(): void {
  thumbSkip.clear();
}

const THUMB_POOL = 3;
// `fetched` = rows attempted this batch (0 = queue drained, the loop's stop
// signal). `patches` = only the rows that got a REAL poster this batch, so the
// caller can patch those tiles in place by id instead of re-fetching the whole
// library span — timeouts and THUMB_FAILED stamps produce no patch (nothing new
// for the grid to show).
export async function backfillVideoThumbs(
  opts: { limit?: number } = {}
): Promise<{ fetched: number; patches: ThumbPatch[] }> {
  const rows = await nextThumbRows(opts.limit ?? 24);
  const patches: ThumbPatch[] = [];
  let next = 0;
  const worker = async () => {
    // Stop grabbing the decoder the moment indexing needs the device OR the user
    // opens/copies a video (codec window) — the unprocessed rows are picked up on
    // the next batch once the window passes.
    while (!indexingActive() && !codecInteractiveActive()) {
      const i = next++;
      if (i >= rows.length) return;
      const row = rows[i];
      const result = await extractPosterAnyway(row, i).catch(() => null);
      if (result === 'timeout') thumbSkip.set(row.id, Date.now() + THUMB_SKIP_RETRY_MS);
      else {
        await setMemeThumb(row.id, result ?? THUMB_FAILED).catch(() => {});
        // A successful extraction is the only thing the grid needs to repaint;
        // JS is single-threaded so this push across workers is race-free.
        if (result) {
          patches.push({ id: row.id, thumbUri: result });
          // Retire any stale error row now that this file finally postered.
          await clearIndexErrorsFor(row.name).catch(() => {});
        }
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(THUMB_POOL, rows.length) }, worker));
  return { fetched: rows.length, patches };
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
