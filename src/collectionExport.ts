// Original-media collection archive. Media bytes are copied without conversion;
// metadata is serialized one record at a time. Only the DB snapshot, ZIP directory
// and one input chunk need to remain resident.
import { PRIMARY_EMBEDDING_MODEL } from './embeddingModels';
import type { CollectionRecord } from './db';
import { ZipWriter, utf8Encode, type ZipSink } from './zipWriter';

export const COLLECTION_FORMAT = 'memeget-collection';
export const COLLECTION_VERSION = 2;

export interface ManifestMeme extends Omit<CollectionRecord, 'id' | 'uri' | 'ocrText'> {
  id: string;
  file: string;
  ocr: string;
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

function mediaPath(record: CollectionRecord): string {
  const name = record.name.replace(/[/\\\u0000]/g, '_').replace(/\.\./g, '_');
  return `media/${record.id}_${name || `${record.kind}.bin`}`;
}

function toManifestMeme(record: CollectionRecord, file: string): ManifestMeme {
  const { id, uri: _uri, ocrText, ...metadata } = record;
  return { ...metadata, id: String(id), file, ocr: ocrText };
}

// Readers yield bounded original-byte chunks and close their handles in finally.
// Any unreadable/empty original or output failure aborts the whole export; the
// caller must close/delete the partial output rather than publish it.
export async function writeCollectionZip(
  records: CollectionRecord[],
  readOriginal: (record: CollectionRecord) => AsyncIterable<Uint8Array>,
  exportedAt: number,
  output: ZipSink,
  onProgress?: (done: number, total: number) => void
): Promise<void> {
  const zip = new ZipWriter(output, new Date(exportedAt));
  const files = records.map(mediaPath);
  for (let i = 0; i < records.length; i++) {
    const record = records[i];
    try {
      const entry = zip.addStream(files[i]);
      let bytes = 0;
      for await (const chunk of readOriginal(record)) {
        if (chunk.length === 0) continue;
        entry.write(chunk);
        bytes += chunk.length;
      }
      if (bytes === 0) throw new Error('Original media is empty or unreadable');
      entry.end();
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(`Cannot export "${record.name}" (item ${record.id}): ${reason}. No partial collection was shared.`);
    }
    onProgress?.(i + 1, records.length);
  }

  // The manifest keeps complete stored tags (including score/source), analysis
  // state, timestamps and vector provenance, but never device-local source URIs.
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
    const fragment = (i > 0 ? ',' : '') + JSON.stringify(toManifestMeme(r, files[i]));
    manifest.write(utf8Encode(fragment));
  }
  manifest.write(utf8Encode(']}'));
  manifest.end();

  zip.finish();
}
