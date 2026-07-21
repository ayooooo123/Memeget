// Build a shareable ZIP of the whole meme collection: a manifest.json (per-meme
// tags, caption, OCR, and embeddings) plus images/<id>.jpg. One artifact that
// carries everything — the images to re-run tagging on, the tags to score
// coverage, and the embeddings for the eval golden set — so it's a single file
// to hand off instead of a tag dump + a separate image zip.
//
// buildManifest is pure (unit-tested); writeCollectionZip streams the archive to
// an injected chunk sink (via the dependency-free ZipWriter) so the native/IO
// bits stay out of the testable core and only one entry is resident at a time.
import { PRIMARY_EMBEDDING_MODEL } from './embeddingModels';
import type { CollectionRecord } from './db';
import { ZipWriter, base64Decode, utf8Encode } from './zipWriter';

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

// Map one record to its manifest entry. Shared by buildManifest (whole-object,
// unit-tested) and the streaming export (one meme at a time) so both emit the
// identical per-meme shape consumers depend on.
function toManifestMeme(r: CollectionRecord, hasImage: boolean): ManifestMeme {
  return {
    id: String(r.id),
    file: hasImage ? `images/${r.id}.jpg` : null,
    name: r.name,
    kind: r.kind,
    caption: r.caption,
    ocr: r.ocrText,
    transcript: r.transcript,
    tags: r.tags.map((t) => ({ label: t.label, category: t.category, source: t.source })),
    extraTerms: r.extraTerms,
    embedding: r.embedding,
    captionEmbedding: r.captionEmbedding,
  };
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
    memes: records.map((r) => toManifestMeme(r, hasImage(r.id))),
  };
}

// Assemble the zip and stream it out in chunks via `onChunk` — the caller writes
// each chunk to disk as it arrives. Nothing is ever fully buffered: each image
// is decoded, written, and released before the next, and the manifest is
// serialized one meme at a time into a streamed entry. So the whole export stays
// flat in memory regardless of library size — a 2000+ item collection no longer
// materializes every image (plus a full base64 copy) and a ~tens-of-MB manifest
// string at once, which OOM'd the JS runtime mid-export and left no file behind.
// `loadImageBase64` returns a base64 JPEG for a meme, or null to skip its image
// (metadata is always kept). A throw is treated as null so one bad image never
// fails the whole export. `onChunk` is synchronous (a file-handle write), so
// there's no stream backpressure to manage. Images stream first; the manifest —
// which records which images made it in — is written last.
export async function writeCollectionZip(
  records: CollectionRecord[],
  loadImageBase64: (r: CollectionRecord) => Promise<string | null>,
  exportedAt: number,
  onChunk: (chunk: Uint8Array) => void
): Promise<void> {
  const zip = new ZipWriter(onChunk, new Date(exportedAt));
  const withImage = new Set<number>();
  for (const r of records) {
    let b64: string | null = null;
    try {
      b64 = await loadImageBase64(r);
    } catch {
      b64 = null;
    }
    if (b64) {
      zip.add(`images/${r.id}.jpg`, base64Decode(b64));
      withImage.add(r.id);
    }
  }

  // Stream manifest.json meme-by-meme. The envelope is built from an empty-memes
  // object and split just before the closing `]}` so its top-level shape/order
  // stays in lockstep with buildManifest; each meme is then appended as its own
  // JSON fragment.
  const envelope = JSON.stringify({
    format: COLLECTION_FORMAT,
    version: COLLECTION_VERSION,
    model: PRIMARY_EMBEDDING_MODEL.id,
    dim: PRIMARY_EMBEDDING_MODEL.dim,
    exportedAt,
    count: records.length,
    memes: [],
  });
  const manifest = zip.addStream('manifest.json');
  manifest.write(utf8Encode(envelope.slice(0, -2))); // drops trailing "]}" -> ends at "…["
  for (let i = 0; i < records.length; i++) {
    const r = records[i];
    const fragment = (i > 0 ? ',' : '') + JSON.stringify(toManifestMeme(r, withImage.has(r.id)));
    manifest.write(utf8Encode(fragment));
  }
  manifest.write(utf8Encode(']}'));
  manifest.end();

  zip.finish();
}
