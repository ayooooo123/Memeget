// The sidecar is a backup: its whole value is that a file written by one
// install is readable by a later one that has lost everything else. So the
// contracts worth locking are round-trip fidelity (especially of vectors),
// stability of the chunk layout across installs, and that a mangled folder
// degrades to partial knowledge rather than an exception mid-restore.
import {
  SIDECAR_CHUNKS,
  SIDECAR_FORMAT,
  buildManifest,
  chunkFileName,
  chunkFor,
  decodeVec,
  digest,
  encodeVec,
  groupChunks,
  parseChunk,
  parseManifest,
  serializeChunk,
  vectorsUsable,
  type ChunkStamp,
  type SidecarMeme,
} from './sidecar';

function meme(name: string, over: Partial<SidecarMeme> = {}): SidecarMeme {
  return {
    name,
    kind: 'image',
    tags: [{ label: 'wojak', category: 'character', score: 0.9, source: 'exemplar' }],
    extraTerms: 'doomer coping',
    ocr: 'when you',
    caption: 'a wojak stares into the middle distance',
    transcript: '',
    visionState: 'done',
    audioState: 'none',
    modifiedAt: 1_700_000_000_000,
    embedding: encodeVec([0.1, -0.2, 0.3]),
    visualEmbedding: '',
    visualModel: '',
    captionEmbedding: '',
    ...over,
  };
}

describe('vector encoding', () => {
  it('round-trips float32 values bit-exactly', () => {
    // Values chosen to be unrepresentable in decimal shorthand — this is the
    // reason vectors are stored as raw bytes rather than JSON numbers.
    const vec = [0.1, -0.2, 1 / 3, 1e-8, -1e8];
    const back = decodeVec(encodeVec(vec));
    expect(back).toEqual(Array.from(Float32Array.from(vec)));
  });

  it('treats an absent vector and a corrupt one alike, as no vector', () => {
    expect(encodeVec([])).toBe('');
    expect(encodeVec(null)).toBe('');
    expect(decodeVec('')).toEqual([]);
    // 5 bytes is not a whole number of float32s: a truncated write, not a
    // partial embedding. Restoring it would poison similarity silently.
    expect(decodeVec(Buffer.from(Uint8Array.of(1, 2, 3, 4, 5)).toString('base64'))).toEqual([]);
  });

  it('accepts a Float32Array as readily as a number[]', () => {
    const f32 = Float32Array.from([0.5, -0.25]);
    expect(encodeVec(f32)).toBe(encodeVec([0.5, -0.25]));
  });
});

describe('chunk assignment', () => {
  it('is a pure function of the file name, so it survives a reinstall', () => {
    // Row ids and content:// uris are reissued per install; the name is not.
    // A record must land in the same chunk on every device for the digest-skip
    // to mean anything.
    expect(chunkFor('pepe.jpg')).toBe(chunkFor('pepe.jpg'));
    expect(chunkFileName(chunkFor('pepe.jpg'))).toMatch(/^library-[0-9a-f]{2}\.json$/);
  });

  it('spreads a realistic library across the chunk space', () => {
    const used = new Set<string>();
    for (let i = 0; i < 2000; i++) used.add(chunkFor(`meme_${i}.jpg`));
    expect(used.size).toBe(SIDECAR_CHUNKS);
  });
});

describe('groupChunks', () => {
  it('orders records within a chunk so an unchanged library re-serializes identically', () => {
    const a = groupChunks([meme('b.jpg'), meme('a.jpg')]);
    const b = groupChunks([meme('a.jpg'), meme('b.jpg')]);
    const key = chunkFor('a.jpg');
    if (key === chunkFor('b.jpg')) {
      expect(a.get(key)!.map((m) => m.name)).toEqual(b.get(key)!.map((m) => m.name));
    }
    for (const [k, list] of a) {
      expect(serializeChunk(k, list)).toBe(serializeChunk(k, b.get(k)!));
    }
  });

  it('emits an empty chunk for a bucket the folder still holds but the library no longer fills', () => {
    // Otherwise a deleted meme's record stays in the folder forever and comes
    // back on the next restore.
    const stale = chunkFor('deleted.jpg');
    const grouped = groupChunks([], [stale]);
    expect(grouped.get(stale)).toEqual([]);
  });
});

describe('chunk round-trip', () => {
  it('preserves every field a restore depends on', () => {
    const original = meme('pepe.jpg', {
      kind: 'video',
      transcript: 'i am the one who knocks',
      audioState: 'done',
      visualEmbedding: encodeVec([0.7, 0.7]),
      visualModel: 'dinov2-base',
      captionEmbedding: encodeVec([0.1, 0.9]),
    });
    const [back] = parseChunk(serializeChunk('00', [original]));
    expect(back).toEqual(original);
  });

  it('keeps the readable records out of a chunk with one bad entry', () => {
    const good = meme('good.jpg');
    const text = JSON.stringify({
      format: SIDECAR_FORMAT,
      version: 1,
      chunk: '00',
      count: 3,
      memes: [good, { name: '' }, null, 'nonsense'],
    });
    expect(parseChunk(text).map((m) => m.name)).toEqual(['good.jpg']);
  });

  it('refuses foreign or future files instead of guessing at their shape', () => {
    expect(parseChunk('not json at all')).toEqual([]);
    expect(parseChunk(JSON.stringify({ format: 'something-else', memes: [meme('a.jpg')] }))).toEqual(
      []
    );
    expect(
      parseChunk(JSON.stringify({ format: SIDECAR_FORMAT, version: 99, memes: [meme('a.jpg')] }))
    ).toEqual([]);
  });

  it('repairs a record with missing or nonsense states rather than dropping it', () => {
    const text = JSON.stringify({
      format: SIDECAR_FORMAT,
      version: 1,
      chunk: '00',
      count: 1,
      memes: [{ name: 'x.jpg', visionState: 'weird', audioState: 42, tags: [{ label: 'ok' }] }],
    });
    const [back] = parseChunk(text);
    expect(back.visionState).toBe('pending');
    expect(back.audioState).toBe('none');
    expect(back.tags).toEqual([{ label: 'ok', category: 'unknown', score: 0, source: undefined }]);
  });
});

describe('digest', () => {
  it('changes when any part of the payload does', () => {
    const base = serializeChunk('00', [meme('a.jpg')]);
    expect(digest(base)).toBe(digest(serializeChunk('00', [meme('a.jpg')])));
    expect(digest(base)).not.toBe(digest(serializeChunk('00', [meme('a.jpg', { ocr: 'other' })])));
  });

  it('notices a same-length edit — a sampled hash would not', () => {
    // The skip-if-unchanged optimisation is only safe if this never collides on
    // an edit; a stride-sampling digest can miss a change between samples.
    const long = 'x'.repeat(2_000_000);
    expect(digest(long)).not.toBe(digest(`${long.slice(0, 1_500_001)}y${long.slice(1_500_002)}`));
  });
});

describe('manifest', () => {
  const stamps = new Map<string, ChunkStamp>([
    ['01', { count: 2, digest: 'a', bytes: 120 }],
    ['00', { count: 3, digest: 'b', bytes: 340 }],
  ]);
  const teachings: ChunkStamp = { count: 0, digest: 't', bytes: 90 };

  it('round-trips and totals the library size', () => {
    const built = buildManifest(stamps, teachings, 1234, 'mobileclip-s2', 512);
    expect(built.memeCount).toBe(5);
    expect(parseManifest(JSON.stringify(built))).toEqual(built);
  });

  it('gates vectors on the embedding space, not on the file being readable', () => {
    const built = buildManifest(stamps, teachings, 1234, 'mobileclip-s2', 512);
    expect(vectorsUsable(built, 'mobileclip-s2', 512)).toBe(true);
    expect(vectorsUsable(built, 'clip-vit-base-patch32', 512)).toBe(false);
    expect(vectorsUsable(built, 'mobileclip-s2', 768)).toBe(false);
  });

  it('survives a v1 manifest: no teachings stamp, no per-chunk byte lengths', () => {
    // v1 is what the first shipped sidecar wrote. It must still parse — the
    // chunk files it points at are readable — with the missing lengths coming
    // back as 0, which the writer reads as "measure the file yourself".
    const built = buildManifest(stamps, teachings, 1234);
    const { teachings: _dropped, ...older } = built;
    older.version = 1;
    older.chunks = { '00': { count: 3, digest: 'b' } as ChunkStamp };
    const parsed = parseManifest(JSON.stringify(older));
    expect(parsed?.teachings).toEqual({ count: 0, digest: '', bytes: 0 });
    expect(parsed?.chunks['00']).toEqual({ count: 3, digest: 'b', bytes: 0 });
    expect(parsed?.version).toBe(1);
  });

  it('returns null for anything that isn\'t one of ours', () => {
    expect(parseManifest('')).toBeNull();
    expect(parseManifest('{')).toBeNull();
    expect(parseManifest(JSON.stringify({ format: 'other', version: 1 }))).toBeNull();
  });
});
