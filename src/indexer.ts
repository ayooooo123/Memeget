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
  clearIndexErrors,
  getAllMemeEmbeddings,
  getEmbeddingSample,
  getExemplars,
  getFolders,
  getLabelVectors,
  insertMeme,
  memeExists,
  putLabelVector,
  updateMemeTags,
} from './db';
import { ASSOCIATIONS, MEME_LABELS, NEGATIVE_ANCHORS, ocrTags } from './memeLabels';
import { copyToCache, deleteCache, listMedia } from './saf';
import type { Tag } from './types';

// Merge visual (prompt/exemplar) tags with OCR-derived tags, de-duped by label.
// Priority: ocr > exemplar > prompt (watermarks and the user's ground truth
// beat shaky zero-shot guesses).
function mergeTags(visual: Tag[], fromOcr: Tag[]): Tag[] {
  const rank = (t: Tag) => (t.source === 'ocr' ? 3 : t.source === 'exemplar' ? 2 : 1);
  const best = new Map<string, Tag>();
  for (const t of [...visual, ...fromOcr]) {
    const cur = best.get(t.label);
    if (!cur || rank(t) > rank(cur) || (rank(t) === rank(cur) && t.score > cur.score)) {
      best.set(t.label, t);
    }
  }
  return [...best.values()].sort((a, b) => rank(b) - rank(a) || b.score - a.score).slice(0, 4);
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

  const negatives = sample.map(center);

  // Group taught examples by label.
  const byLabel = new Map<string, { category: string; pos: number[][] }>();
  for (const e of exemplars) {
    const g = byLabel.get(e.label) ?? { category: e.category, pos: [] };
    g.pos.push(center(e.vector));
    byLabel.set(e.label, g);
  }

  const heads: LabelHead[] = [];
  for (const [label, g] of byLabel) {
    // Other labels' positives are also negatives for this one (push them apart).
    const otherPos: number[][] = [];
    for (const [l2, g2] of byLabel) if (l2 !== label) otherPos.push(...g2.pos);
    heads.push(trainHead(label, g.category, g.pos, [...negatives, ...otherPos]));
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

    let stage = 'copy';
    const temp: string[] = [];
    try {
      if (await memeExists(file.uri)) {
        skipped++;
        continue;
      }

      const work = await copyToCache(file, i);
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
      added++;
    } catch (e) {
      errors++;
      const reason = String((e as Error)?.message ?? e).slice(0, 300);
      await addIndexError({ name: file.name, kind: file.kind, stage, reason }).catch(() => {});
    } finally {
      for (const t of temp) await deleteCache(t);
    }
  }

  opts.onProgress?.({ processed: total, total, added, current: '' });
  return { added, skipped, errors };
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

  let updated = 0;
  for (let i = 0; i < rows.length; i++) {
    const vec = Array.from(rows[i].embedding);
    const tags = mergeTags(
      classifyImage(vec, know.labelVecs, know.exemplarHeads, know.mean, know.negativeVecs),
      ocrTags(rows[i].ocrText)
    );
    await updateMemeTags(rows[i].id, tags, extraTermsFor(tags, know.assoc));
    updated++;
    opts.onProgress?.(i + 1, rows.length);
  }
  return { updated };
}
