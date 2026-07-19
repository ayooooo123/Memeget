import { appendPage, mergeRecords, patchThumbs, sameRecord } from './libraryCore';
import type { MemeRecord } from './types';

const rec = (over: Partial<MemeRecord> = {}): MemeRecord => ({
  id: 1,
  uri: 'u1',
  name: 'n1',
  kind: 'video',
  ocrText: '',
  caption: '',
  transcript: '',
  tags: [],
  extraTerms: '',
  visionState: 'pending',
  audioState: 'none',
  indexedAt: 1,
  ...over,
});

describe('patchThumbs', () => {
  it('returns the SAME array reference when no patch matches (FlatList bails)', () => {
    const list = [rec({ id: 1 }), rec({ id: 2 })];
    expect(patchThumbs(list, [{ id: 99, thumbUri: 'file://x.jpg' }])).toBe(list);
  });

  it('returns the same reference for an empty patch set', () => {
    const list = [rec({ id: 1 })];
    expect(patchThumbs(list, [])).toBe(list);
  });

  it('gives a new identity ONLY to patched rows, preserving all others', () => {
    const a = rec({ id: 1 });
    const b = rec({ id: 2 });
    const c = rec({ id: 3 });
    const next = patchThumbs([a, b, c], [{ id: 2, thumbUri: 'file://poster.jpg' }]);

    expect(next).not.toBe([a, b, c]); // a fresh array
    expect(next[0]).toBe(a); // unchanged rows keep identity → no re-render
    expect(next[2]).toBe(c);
    expect(next[1]).not.toBe(b); // patched row is a new object
    expect(next[1].thumbUri).toBe('file://poster.jpg');
    expect(next[1].id).toBe(2); // everything else carried over
  });

  it('no-ops a patch that would not change the thumbUri', () => {
    const list = [rec({ id: 1, thumbUri: 'file://same.jpg' })];
    expect(patchThumbs(list, [{ id: 1, thumbUri: 'file://same.jpg' }])).toBe(list);
  });

  it('preserves extra fields like a SearchHit score when spreading', () => {
    const hit = { ...rec({ id: 5 }), score: 0.87 };
    const next = patchThumbs([hit], [{ id: 5, thumbUri: 'file://p.jpg' }]) as typeof hit[];
    expect(next[0].score).toBe(0.87);
    expect(next[0].thumbUri).toBe('file://p.jpg');
  });
});

describe('mergeRecords identity reuse', () => {
  it('returns the SAME array when nothing changed', () => {
    const prev = [rec({ id: 1 }), rec({ id: 2 })];
    const next = [rec({ id: 1 }), rec({ id: 2 })];
    expect(mergeRecords(prev, next)).toBe(prev);
  });

  it('reuses unchanged rows and only swaps genuinely-changed ones', () => {
    const prev = [rec({ id: 1 }), rec({ id: 2, caption: 'old' })];
    const next = [rec({ id: 1 }), rec({ id: 2, caption: 'new' })];
    const merged = mergeRecords(prev, next);
    expect(merged).not.toBe(prev);
    expect(merged[0]).toBe(prev[0]); // identical row kept
    expect(merged[1]).not.toBe(prev[1]); // caption changed
  });
});

describe('appendPage', () => {
  it('appends a fresh page after the loaded rows', () => {
    const prev = [rec({ id: 1 }), rec({ id: 2 })];
    const next = [rec({ id: 3 }), rec({ id: 4 })];
    expect(appendPage(prev, next).map((m) => m.id)).toEqual([1, 2, 3, 4]);
  });

  it('drops rows already loaded so FlatList never sees duplicate keys', () => {
    const prev = [rec({ id: 1 }), rec({ id: 2 })];
    // A refresh landing between cursor read and fetch can re-serve a boundary row.
    const next = [rec({ id: 2 }), rec({ id: 3 })];
    expect(appendPage(prev, next).map((m) => m.id)).toEqual([1, 2, 3]);
  });

  it('returns the SAME array reference for an empty or fully-duplicate page', () => {
    const prev = [rec({ id: 1 }), rec({ id: 2 })];
    expect(appendPage(prev, [])).toBe(prev);
    expect(appendPage(prev, [rec({ id: 1 }), rec({ id: 2 })])).toBe(prev);
  });

  it('keeps identity of the previously loaded rows on append', () => {
    const a = rec({ id: 1 });
    const merged = appendPage([a], [rec({ id: 2 })]);
    expect(merged[0]).toBe(a);
  });
});

describe('sameRecord', () => {
  it('detects a thumbUri change (the poster case)', () => {
    expect(sameRecord(rec({ thumbUri: undefined }), rec({ thumbUri: 'file://p.jpg' }))).toBe(false);
  });
  it('treats fully-identical records as equal', () => {
    expect(sameRecord(rec(), rec())).toBe(true);
  });
});
