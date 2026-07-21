// Tests for the pure part of the collection export — the manifest builder. The
// zip assembly (writeCollectionZip) is thin IO over this and jszip, so the value
// worth locking is the manifest shape callers/consumers depend on.

import { buildManifest, COLLECTION_FORMAT } from './collectionExport';
import type { CollectionRecord } from './db';

const rec = (over: Partial<CollectionRecord> = {}): CollectionRecord => ({
  id: 1,
  uri: 'content://x/1',
  thumbUri: '',
  name: 'shush.jpg',
  kind: 'image',
  caption: 'a man holds a finger to his lips',
  ocrText: '',
  transcript: '',
  tags: [{ label: 'shushing', category: 'action', score: 0.9, source: 'vision' }],
  extraTerms: 'be quiet',
  embedding: [0.1, 0.2],
  captionEmbedding: null,
  ...over,
});

describe('buildManifest', () => {
  it('maps records into the manifest shape with embeddings and tag facets', () => {
    const m = buildManifest([rec()], () => true, 1234);
    expect(m.format).toBe(COLLECTION_FORMAT);
    expect(m.count).toBe(1);
    expect(m.exportedAt).toBe(1234);
    const meme = m.memes[0];
    expect(meme.id).toBe('1');
    expect(meme.file).toBe('images/1.jpg');
    expect(meme.tags[0]).toEqual({ label: 'shushing', category: 'action', source: 'vision' });
    expect(meme.embedding).toEqual([0.1, 0.2]);
    expect(meme.caption).toContain('finger to his lips');
  });

  it('sets file to null when no image was attached for that meme', () => {
    const m = buildManifest([rec({ id: 7 })], (id) => id !== 7, 0);
    expect(m.memes[0].file).toBeNull();
  });
});
