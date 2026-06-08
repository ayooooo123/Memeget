import * as VideoThumbnails from 'expo-video-thumbnails';

import { classifyImage, type EmbeddingsApi } from './embeddings';
import {
  getFolders,
  getLabelVectors,
  insertMeme,
  memeExists,
  putLabelVector,
} from './db';
import { MEME_LABELS, NEGATIVE_ANCHORS } from './memeLabels';
import { copyToCache, deleteCache, listMedia } from './saf';

const NEG_PREFIX = 'neg::';

// On-device OCR (Google ML Kit on Android). Imported lazily/defensively so a
// missing module never breaks the whole index run.
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

// Compute (and cache) CLIP text vectors for every curated label + negative
// anchor. Runs once; subsequent indexes read straight from SQLite.
export async function ensureLabelVectors(api: EmbeddingsApi) {
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

  const labelVecs = MEME_LABELS.filter((d) => cache.has(d.label)).map((d) => ({
    label: d.label,
    category: d.category,
    vec: cache.get(d.label)!,
  }));
  const negativeVecs = NEGATIVE_ANCHORS.map((_, i) => cache.get(`${NEG_PREFIX}${i}`)!).filter(
    Boolean
  );

  return { labelVecs, negativeVecs };
}

// Walk every linked folder and index any media not already in the DB.
export async function runIndex(
  api: EmbeddingsApi,
  opts: { onProgress?: (p: IndexProgress) => void; shouldCancel?: () => boolean } = {}
): Promise<IndexResult> {
  const { labelVecs, negativeVecs } = await ensureLabelVectors(api);

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

    try {
      if (await memeExists(file.uri)) {
        skipped++;
        continue;
      }

      const work = await copyToCache(file, i);
      let frame = work;
      let thumbForCleanup: string | null = null;

      if (file.kind === 'video') {
        const { uri } = await VideoThumbnails.getThumbnailAsync(work, { time: 1000 });
        frame = uri;
        thumbForCleanup = uri;
      }

      const embedding = await api.embedImage(frame);
      const ocrText = await ocr(frame);
      const tags = classifyImage(embedding, labelVecs, negativeVecs);

      await insertMeme({
        uri: file.uri,
        name: file.name,
        kind: file.kind,
        embedding,
        ocrText,
        tags,
      });
      added++;

      await deleteCache(work);
      if (thumbForCleanup) await deleteCache(thumbForCleanup);
    } catch {
      errors++;
    }
  }

  opts.onProgress?.({ processed: total, total, added, current: '' });
  return { added, skipped, errors };
}
