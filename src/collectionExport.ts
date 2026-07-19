// Build a shareable ZIP of the whole meme collection: a manifest.json (per-meme
// tags, caption, OCR, and embeddings) plus images/<id>.jpg. One artifact that
// carries everything — the images to re-run tagging on, the tags to score
// coverage, and the embeddings for the eval golden set — so it's a single file
// to hand off instead of a tag dump + a separate image zip.
//
// buildManifest is pure (unit-tested); the ZIP assembly takes an injected image
// loader so the native/IO bits stay out of the testable core.
import JSZip from 'jszip';

import { PRIMARY_EMBEDDING_MODEL } from './embeddingModels';
import type { CollectionRecord } from './db';

export const COLLECTION_FORMAT = 'memeget-collection';
export const COLLECTION_VERSION = 1;

export interface ManifestMeme {
  id: string;
  file: string | null; // path within the zip, or null if no image was attached
  name: string;
  kind: string;
  caption: string;
  ocr: string;
  transcript: string;
  tags: { label: string; category: string; source?: string }[];
  extraTerms: string;
  embedding: number[] | null;
  captionEmbedding: number[] | null;
}

export interface CollectionManifest {
  format: string;
  version: number;
  model: string;
  dim: number;
  exportedAt: number;
  count: number;
  memes: ManifestMeme[];
}

export function buildManifest(
  records: CollectionRecord[],
  hasImage: (id: number) => boolean,
  exportedAt: number
): CollectionManifest {
  return {
    format: COLLECTION_FORMAT,
    version: COLLECTION_VERSION,
    model: PRIMARY_EMBEDDING_MODEL.id,
    dim: PRIMARY_EMBEDDING_MODEL.dim,
    exportedAt,
    count: records.length,
    memes: records.map((r) => ({
      id: String(r.id),
      file: hasImage(r.id) ? `images/${r.id}.jpg` : null,
      name: r.name,
      kind: r.kind,
      caption: r.caption,
      ocr: r.ocrText,
      transcript: r.transcript,
      tags: r.tags.map((t) => ({ label: t.label, category: t.category, source: t.source })),
      extraTerms: r.extraTerms,
      embedding: r.embedding,
      captionEmbedding: r.captionEmbedding,
    })),
  };
}

// Assemble the zip and return it as a base64 string (written to disk by the
// caller). `loadImageBase64` returns a base64 JPEG for a meme, or null to skip
// its image (metadata is always kept). A throw is treated as null so one bad
// image never fails the whole export.
export async function buildCollectionZip(
  records: CollectionRecord[],
  loadImageBase64: (r: CollectionRecord) => Promise<string | null>,
  exportedAt: number
): Promise<string> {
  const zip = new JSZip();
  const images = zip.folder('images');
  const withImage = new Set<number>();
  for (const r of records) {
    let b64: string | null = null;
    try {
      b64 = await loadImageBase64(r);
    } catch {
      b64 = null;
    }
    if (b64) {
      images?.file(`${r.id}.jpg`, b64, { base64: true });
      withImage.add(r.id);
    }
  }
  zip.file('manifest.json', JSON.stringify(buildManifest(records, (id) => withImage.has(id), exportedAt)));
  return zip.generateAsync({ type: 'base64' });
}
