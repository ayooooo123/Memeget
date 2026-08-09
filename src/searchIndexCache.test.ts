import {
  ensureSearchIndex,
  invalidateSearchIndex,
  patchSearchIndexEntries,
  peekSearchIndex,
  resetSearchIndexForTest,
  type SearchCacheEntry,
} from './searchIndexCache';

const makeEntry = (id: number): SearchCacheEntry => ({
  id,
  kind: 'image',
  imageVec: Float32Array.from([1, 0]),
  captionVec: null,
  searchText: `meme ${id}`,
  record: {
    id,
    uri: `u${id}`,
    name: `n${id}`,
    kind: 'image',
    ocrText: '',
    caption: '',
    transcript: '',
    tags: [],
    extraTerms: '',
    visionState: 'pending',
    audioState: 'none',
    indexedAt: id,
  },
});

beforeEach(() => resetSearchIndexForTest());

describe('search index cache', () => {
  it('builds once, then serves from memory until invalidated', async () => {
    let calls = 0;
    const load = async () => {
      calls++;
      return [makeEntry(1)];
    };

    await ensureSearchIndex(load);
    await ensureSearchIndex(load);
    expect(calls).toBe(1); // second call hit the cache

    invalidateSearchIndex();
    await ensureSearchIndex(load);
    expect(calls).toBe(2); // rebuilt after invalidation
  });

  it('coalesces concurrent callers onto a single build', async () => {
    let calls = 0;
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const load = async () => {
      calls++;
      await gate;
      return [makeEntry(1), makeEntry(2)];
    };

    const a = ensureSearchIndex(load);
    const b = ensureSearchIndex(load);
    release();
    const [ra, rb] = await Promise.all([a, b]);

    expect(calls).toBe(1); // one SELECT shared by both callers
    expect(ra).toBe(rb); // same array instance
    expect(ra).toHaveLength(2);
  });

  it('exposes resident entries via peek only after a build', async () => {
    expect(peekSearchIndex()).toBeNull();
    await ensureSearchIndex(async () => [makeEntry(7)]);
    expect(peekSearchIndex()?.map((e) => e.id)).toEqual([7]);
  });

  it('retries the build after a failure instead of caching the error', async () => {
    let calls = 0;
    const load = async () => {
      calls++;
      if (calls === 1) throw new Error('db busy');
      return [makeEntry(1)];
    };

    await expect(ensureSearchIndex(load)).rejects.toThrow('db busy');
    const entries = await ensureSearchIndex(load); // must retry, not serve stale
    expect(calls).toBe(2);
    expect(entries).toHaveLength(1);
  });

  it('never resolves an in-flight build to data a mid-build write superseded', async () => {
    let calls = 0;
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const load = async () => {
      calls++;
      if (calls === 1) await gate;
      return [makeEntry(calls)];
    };

    const first = ensureSearchIndex(load);
    invalidateSearchIndex(); // lands while the first build is in flight
    release();
    const result = await first;

    // The in-flight build reloads before resolving, so the awaiting caller gets
    // the post-invalidation data — not the pre-write snapshot that would leave a
    // just-written transcript looking unsearchable.
    expect(calls).toBe(2);
    expect(result.map((e) => e.id)).toEqual([2]);

    // …and the index is clean afterwards: no redundant rebuild.
    await ensureSearchIndex(load);
    expect(calls).toBe(2);
  });

  it('patches searchable tag fields without rebuilding every decoded vector', async () => {
    let calls = 0;
    await ensureSearchIndex(async () => {
      calls++;
      return [makeEntry(1), makeEntry(2)];
    });

    const patched = patchSearchIndexEntries([
      {
        id: 2,
        record: { tags: [{ label: 'pepe', category: 'user', source: 'manual', score: 1 }] },
        searchText: 'meme 2 pepe',
      },
    ]);
    const entries = await ensureSearchIndex(async () => {
      calls++;
      return [];
    });

    expect(patched).toBe(true);
    expect(calls).toBe(1);
    expect(entries[0].searchText).toBe('meme 1');
    expect(entries[1].searchText).toBe('meme 2 pepe');
    expect(entries[1].record.tags[0].label).toBe('pepe');
  });
});
