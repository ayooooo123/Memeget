import {
  ensureSearchIndex,
  invalidateSearchIndex,
  invalidateSearchIndexRows,
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

  it('reloads only the rows a write touched, in place', async () => {
    const load = async () => [makeEntry(1), makeEntry(2), makeEntry(3)];
    await ensureSearchIndex(load);

    const asked: number[][] = [];
    const loadRows = async (ids: readonly number[]) => {
      asked.push([...ids]);
      return ids.map((id) => ({ ...makeEntry(id), searchText: `described ${id}` }));
    };

    invalidateSearchIndexRows([2]);
    const entries = await ensureSearchIndex(async () => {
      throw new Error('must not reload the whole library');
    }, loadRows);

    expect(asked).toEqual([[2]]);
    expect(entries.map((e) => e.searchText)).toEqual(['meme 1', 'described 2', 'meme 3']);
  });

  it('appends a newly indexed row and drops one that left the searchable set', async () => {
    await ensureSearchIndex(async () => [makeEntry(1), makeEntry(2)]);

    // 3 is a just-imported meme; 1 was deleted, so the reload can't return it.
    invalidateSearchIndexRows([1, 3]);
    const entries = await ensureSearchIndex(
      async () => [],
      async (ids) => ids.filter((id) => id === 3).map(makeEntry)
    );

    expect(entries.map((e) => e.id)).toEqual([2, 3]);
  });

  it('hands back a new array identity so identity-keyed memos rebuild', async () => {
    const before = await ensureSearchIndex(async () => [makeEntry(1)]);
    invalidateSearchIndexRows([1]);
    const after = await ensureSearchIndex(
      async () => [],
      async (ids) => ids.map(makeEntry)
    );

    expect(after).not.toBe(before);
  });

  it('picks up a row invalidated mid-build without re-reading the library', async () => {
    await ensureSearchIndex(async () => [makeEntry(1), makeEntry(2)]);

    let release = () => {};
    const gate = new Promise<void>((r) => {
      release = r;
    });
    const asked: number[][] = [];
    const loadRows = async (ids: readonly number[]) => {
      asked.push([...ids]);
      if (asked.length === 1) await gate;
      return ids.map((id) => ({ ...makeEntry(id), searchText: `v${asked.length} ${id}` }));
    };

    invalidateSearchIndexRows([1]);
    const build = ensureSearchIndex(async () => {
      throw new Error('must not reload the whole library');
    }, loadRows);
    invalidateSearchIndexRows([2]); // lands while the first reload is in flight
    release();
    const entries = await build;

    expect(asked).toEqual([[1], [2]]);
    expect(entries.map((e) => e.searchText)).toEqual(['v1 1', 'v2 2']);
  });

  it('falls back to a full reload when a row reload fails', async () => {
    await ensureSearchIndex(async () => [makeEntry(1)]);

    invalidateSearchIndexRows([1]);
    await expect(
      ensureSearchIndex(
        async () => [makeEntry(1)],
        async () => {
          throw new Error('db busy');
        }
      )
    ).rejects.toThrow('db busy');

    let fullReloads = 0;
    await ensureSearchIndex(async () => {
      fullReloads++;
      return [makeEntry(1)];
    });
    expect(fullReloads).toBe(1);
  });

  it('reloads everything when row dirt arrives before any resident cache exists', async () => {
    invalidateSearchIndexRows([7]);
    let fullReloads = 0;
    await ensureSearchIndex(
      async () => {
        fullReloads++;
        return [makeEntry(7)];
      },
      async () => {
        throw new Error('nothing to splice into');
      }
    );
    expect(fullReloads).toBe(1);
  });
});
