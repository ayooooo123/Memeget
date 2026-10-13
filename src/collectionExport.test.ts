import JSZip from 'jszip';
import { closeSync, mkdtempSync, openSync, readFileSync, rmSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeCollectionZip, type CollectionManifest } from './collectionExport';
import type { CollectionRecord } from './db';
import type { ZipSink } from './zipWriter';

const record = (overrides: Partial<CollectionRecord> = {}): CollectionRecord => ({
  id: 1,
  uri: 'content://originals/1',
  name: 'original.png',
  kind: 'image',
  caption: 'A caption',
  ocrText: 'visible text',
  transcript: '',
  tags: [{ label: 'example', category: 'format', score: 0.73, source: 'exemplar' }],
  extraTerms: 'search terms',
  embedding: [0.1, 0.2],
  captionEmbedding: [0.3, 0.4],
  visualEmbedding: [0.5, 0.6],
  visualModel: 'visual-model:2',
  embeddingModel: 'stored-model:512',
  pending: false,
  visionState: 'done',
  audioState: 'none',
  indexedAt: 123,
  modifiedAt: 100,
  ...overrides,
});

describe('original-media collection export', () => {
  let directory: string;
  let path: string;
  let descriptor: number;
  let sink: ZipSink;

  beforeEach(() => {
    directory = mkdtempSync(join(tmpdir(), 'memeget-collection-'));
    path = join(directory, 'collection.zip');
    descriptor = openSync(path, 'w+');
    let offset = 0;
    sink = {
      write: (chunk) => {
        writeSync(descriptor, chunk, 0, chunk.length, offset);
        offset += chunk.length;
      },
      patch: (at, chunk) => { writeSync(descriptor, chunk, 0, chunk.length, at); },
    };
  });

  afterEach(() => {
    closeSync(descriptor);
    rmSync(directory, { recursive: true, force: true });
  });

  it('round-trips original binary media and stored evidence without converting or dropping scores', async () => {
    const image = record();
    const video = record({
      id: 2, name: 'original.webm', kind: 'video', transcript: 'spoken words',
      tags: [{ label: 'uncertain', category: 'topic', score: 0, source: 'vision' }],
      pending: true, audioState: 'done', embedding: null, captionEmbedding: null,
      visualEmbedding: null, visualModel: '',
    });
    const originals = new Map([
      [1, Uint8Array.from({ length: 513 }, (_, i) => i % 256)],
      [2, Uint8Array.from({ length: 1025 }, (_, i) => (i * 37) % 256)],
    ]);
    await writeCollectionZip([image, video], async function* (item) {
      const bytes = originals.get(item.id)!;
      for (let start = 0; start < bytes.length; start += 127) {
        yield bytes.subarray(start, start + 127);
      }
    }, 1234, sink);

    const zip = await JSZip.loadAsync(readFileSync(path), { checkCRC32: true });
    const manifest: CollectionManifest = JSON.parse(await zip.file('manifest.json')!.async('string'));
    expect(manifest.memes.map((m) => m.kind)).toEqual(['image', 'video']);
    for (const [index, original] of [image, video].entries()) {
      const exported = manifest.memes[index];
      expect(await zip.file(exported.file)!.async('uint8array')).toEqual(originals.get(original.id));
      expect(exported.name).toBe(original.name);
      expect(exported.tags).toEqual(original.tags);
      expect(exported.embedding).toEqual(original.embedding);
      expect(exported.captionEmbedding).toEqual(original.captionEmbedding);
      expect(exported.visualEmbedding).toEqual(original.visualEmbedding);
      expect(exported.visualModel).toBe(original.visualModel);
      expect(exported.embeddingModel).toBe(original.embeddingModel);
      expect(exported.pending).toBe(original.pending);
      expect(exported.transcript).toBe(original.transcript);
      expect(exported).not.toHaveProperty('uri');
    }
  });

  it('rejects an unreadable original rather than finalizing a metadata-only export', async () => {
    await expect(writeCollectionZip([record()], async function* () {
      yield new Uint8Array([1, 2, 3]);
      throw new Error('source disconnected');
    }, 1234, sink)).rejects.toThrow();
    await expect(JSZip.loadAsync(readFileSync(path))).rejects.toThrow();
  });

  it('rejects an empty original rather than publishing unusable media', async () => {
    await expect(writeCollectionZip([record()], async function* () {
      yield new Uint8Array(0);
    }, 1234, sink)).rejects.toThrow();
    await expect(JSZip.loadAsync(readFileSync(path))).rejects.toThrow();
  });

  it('closes the active reader when output storage fails', async () => {
    let closed = false;
    let written = 0;
    const limitedSink: ZipSink = {
      write: (chunk) => {
        written += chunk.length;
        if (written > 1024) throw new Error('storage full');
        sink.write(chunk);
      },
      patch: sink.patch,
    };
    await expect(writeCollectionZip([record()], async function* () {
      try {
        yield new Uint8Array(4096);
      } finally {
        closed = true;
      }
    }, 1234, limitedSink)).rejects.toThrow();
    expect(closed).toBe(true);
  });
});
