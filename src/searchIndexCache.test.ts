import {
  ensureSearchIndex,
  invalidateSearchIndex,
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

  it('rebuilds against newer data when invalidated mid-build', async () => {
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
    await first;

    // The mid-build invalidation must force the next call to rebuild.
    await ensureSearchIndex(load);
    expect(calls).toBe(2);
  });
});
