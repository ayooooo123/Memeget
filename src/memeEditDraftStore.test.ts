jest.mock('expo-file-system/legacy', () => ({
  cacheDirectory: 'file:///expo-cache/',
  deleteAsync: jest.fn(async () => {}),
  getInfoAsync: jest.fn(async () => ({ exists: false })),
  moveAsync: jest.fn(async () => {}),
  readAsStringAsync: jest.fn(async () => ''),
  readDirectoryAsync: jest.fn(async () => []),
  writeAsStringAsync: jest.fn(async () => {}),
}));

jest.mock('./saf', () => ({
  copyUriToCachePath: jest.fn(async () => {}),
}));
jest.mock('../modules/memeget-bg', () => ({
  probeMedia: jest.fn(async () => null),
}));

import {
  createDefaultVideoProject,
  type MemeEditProject,
} from './memeEditProjectCore';
import {
  DRAFT_EXPIRY_MS,
  MemeEditAutosaveController,
  MemeEditDraftStore,
  MemeEditSourcePreparationController,
  MemeEditSourceSessionController,
  flushAutosaveBeforeSourceRelease,
  requestSourceSessionClose,
  createExpoMemeEditDraftIo,
  draftStoragePaths,
  type MemeEditDraftIdentity,
  type MemeEditDraftIo,
  type MemeEditSourcePreparationIo,
  type MemeEditTimers,
} from './memeEditDraftStore';

type IoEvent =
  | { type: 'read'; path: string }
  | { type: 'write'; path: string; text: string }
  | { type: 'replace'; from: string; to: string }
  | { type: 'remove'; path: string }
  | { type: 'list' };

class MemoryDraftIo implements MemeEditDraftIo {
  readonly cacheDirectory = 'file:///cache/';
  readonly files = new Map<string, string>();
  readonly events: IoEvent[] = [];
  readonly readFailurePaths = new Set<string>();
  writeError: Error | null = null;
  replaceError: Error | null = null;

  async readText(path: string): Promise<string | null> {
    this.events.push({ type: 'read', path });
    if (this.readFailurePaths.has(path)) throw new Error(`read failed: ${path}`);
    return this.files.get(path) ?? null;
  }

  async writeText(path: string, text: string): Promise<void> {
    this.events.push({ type: 'write', path, text });
    this.files.set(path, text);
    if (this.writeError) throw this.writeError;
  }

  async replace(from: string, to: string): Promise<void> {
    this.events.push({ type: 'replace', from, to });
    if (this.replaceError) throw this.replaceError;
    const text = this.files.get(from);
    if (text === undefined) throw new Error(`Missing replacement source ${from}`);
    this.files.set(to, text);
    this.files.delete(from);
  }

  async remove(path: string): Promise<void> {
    this.events.push({ type: 'remove', path });
    this.files.delete(path);
  }

  async listCacheEntries(): Promise<string[]> {
    this.events.push({ type: 'list' });
    const prefix = this.cacheDirectory;
    return [...this.files.keys()]
      .filter((path) => path.startsWith(prefix))
      .map((path) => path.slice(prefix.length));
  }
}

class FakeTimers implements MemeEditTimers {
  private nextId = 1;
  private nowMs = 0;
  private readonly scheduled = new Map<number, { atMs: number; callback: () => void }>();

  setTimeout(callback: () => void, delayMs: number): number {
    const id = this.nextId++;
    this.scheduled.set(id, { atMs: this.nowMs + delayMs, callback });
    return id;
  }

  clearTimeout(handle: unknown): void {
    this.scheduled.delete(handle as number);
  }

  advanceBy(ms: number): void {
    this.nowMs += ms;
    const ready = [...this.scheduled.entries()]
      .filter(([, timer]) => timer.atMs <= this.nowMs)
      .sort((left, right) => left[1].atMs - right[1].atMs || left[0] - right[0]);
    for (const [id, timer] of ready) {
      if (!this.scheduled.delete(id)) continue;
      timer.callback();
    }
  }

  get size(): number {
    return this.scheduled.size;
  }
}

const identity: MemeEditDraftIdentity = {
  sessionId: 'editor/session:alpha',
  source: {
    stableId: 'provider-document:42',
    uri: 'content://provider/document/42',
    name: 'source.mp4',
    kind: 'video',
    width: 1_280,
    height: 720,
    durationUs: 5_000_000,
    byteSize: 1_024,
    modifiedTimeMs: 10_000,
  },
};

function project(marker = 'initial'): MemeEditProject {
  const value = createDefaultVideoProject({
    uri: identity.source.uri,
    name: identity.source.name,
    width: identity.source.width,
    height: identity.source.height,
    durationUs: identity.source.durationUs!,
  });
  value.background.color = marker;
  return value;
}

function draftWriteEvents(io: MemoryDraftIo): Extract<IoEvent, { type: 'write' }>[] {
  return io.events.filter((event): event is Extract<IoEvent, { type: 'write' }> => event.type === 'write');
}

describe('MemeEditDraftStore', () => {
  test('writes a temporary JSON file and atomically replaces the deterministic draft path', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const paths = draftStoragePaths(io.cacheDirectory, identity);

    await store.save(identity, project());

    expect(paths.draft).not.toContain('provider');
    expect(paths.draft).not.toContain('session:alpha');
    const commitEvents = io.events.filter(
      (event) => event.type === 'write' || event.type === 'replace'
    );
    expect(commitEvents.map((event) => event.type)).toEqual(['write', 'replace']);
    expect(commitEvents[0]).toMatchObject({ type: 'write', path: paths.temporary });
    expect(commitEvents[1]).toEqual({
      type: 'replace',
      from: paths.temporary,
      to: paths.draft,
    });
    expect(io.files.has(paths.draft)).toBe(true);
    expect(io.files.has(paths.temporary)).toBe(false);
  });

  test('keeps the prior valid generation when an overwrite temp write fails', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    await store.save(identity, project('prior.mp4'));
    io.writeError = new Error('disk full');

    await expect(store.save(identity, project('newer.mp4'))).rejects.toThrow('disk full');

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'prior.mp4' } },
    });
  });

  test('preserves the latest slot when iOS-style replacement deletes an existing older target then fails', async () => {
    const io = new MemoryDraftIo();
    let nowMs = 20_000;
    const store = new MemeEditDraftStore(io, { now: () => nowMs });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project('generation-1'));
    nowMs += 1;
    await store.save(identity, project('generation-2'));
    nowMs += 1;
    await store.save(identity, project('generation-3-latest'));
    expect(io.files.has(paths.slotA)).toBe(true);
    expect(io.files.has(paths.slotB)).toBe(true);

    io.replace = async (from, to) => {
      io.events.push({ type: 'replace', from, to });
      io.files.delete(to);
      throw new Error('iOS move interrupted after destination delete');
    };
    nowMs += 1;
    await expect(store.save(identity, project('generation-4'))).rejects.toThrow(
      'iOS move interrupted'
    );

    expect(io.files.has(paths.slotB)).toBe(false);
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'generation-3-latest' } },
    });
  });

  test('selects the newest complete checksummed generation and falls back from corruption', async () => {
    const io = new MemoryDraftIo();
    let nowMs = 20_000;
    const store = new MemeEditDraftStore(io, { now: () => nowMs });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project('older.mp4'));
    nowMs += 1;
    await store.save(identity, project('newer.mp4'));

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'newer.mp4' } },
    });

    io.files.set(paths.slotB, io.files.get(paths.slotB)!.replace('newer.mp4', 'tampered.mp4'));
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'older.mp4' } },
    });
  });

  test('aborts save when either journal generation cannot be read and preserves the latest', async () => {
    const io = new MemoryDraftIo();
    let nowMs = 20_000;
    const store = new MemeEditDraftStore(io, { now: () => nowMs });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project('generation-1'));
    nowMs += 1;
    await store.save(identity, project('generation-2-latest'));
    const writesBeforeFailure = draftWriteEvents(io).length;
    io.readFailurePaths.add(paths.slotA);

    await expect(store.save(identity, project('must-not-write'))).rejects.toThrow(
      /could not read draft journal/i
    );
    expect(draftWriteEvents(io)).toHaveLength(writesBeforeFailure);

    io.readFailurePaths.clear();
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'generation-2-latest' } },
    });
  });

  test('returns typed io-error instead of restoring an older slot when a newer candidate is unreadable', async () => {
    const io = new MemoryDraftIo();
    let nowMs = 20_000;
    const store = new MemeEditDraftStore(io, { now: () => nowMs });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project('older-readable'));
    nowMs += 1;
    await store.save(identity, project('newer-unreadable'));
    io.readFailurePaths.add(paths.slotB);

    await expect(store.restore(identity)).resolves.toEqual({
      status: 'rejected',
      reason: 'io-error',
    });
  });

  test('serializes concurrent direct saves across store instances with monotonic generations', async () => {
    const io = new MemoryDraftIo();
    const firstStore = new MemeEditDraftStore(io, { now: () => 20_000 });
    const secondStore = new MemeEditDraftStore(io, { now: () => 20_001 });
    let releaseFirstReplace!: () => void;
    let notifyFirstReplace!: () => void;
    const firstReplaceGate = new Promise<void>((resolve) => {
      releaseFirstReplace = resolve;
    });
    const firstReplaceStarted = new Promise<void>((resolve) => {
      notifyFirstReplace = resolve;
    });
    const originalReplace = io.replace.bind(io);
    let replaceCount = 0;
    io.replace = async (from, to) => {
      replaceCount += 1;
      if (replaceCount === 1) {
        notifyFirstReplace();
        await firstReplaceGate;
      }
      await originalReplace(from, to);
    };

    const firstSave = firstStore.save(identity, project('concurrent-1'));
    await firstReplaceStarted;
    const readsBeforeSecondSave = io.events.filter((event) => event.type === 'read').length;
    const secondSave = secondStore.save(identity, project('concurrent-2'));
    expect(io.events.filter((event) => event.type === 'read')).toHaveLength(
      readsBeforeSecondSave
    );

    releaseFirstReplace();
    await Promise.all([firstSave, secondSave]);
    await expect(firstStore.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'concurrent-2' } },
    });
  });

  test('serializes discard behind an in-flight save from another store instance', async () => {
    const io = new MemoryDraftIo();
    const savingStore = new MemeEditDraftStore(io, { now: () => 20_000 });
    const discardingStore = new MemeEditDraftStore(io, { now: () => 20_001 });
    let releaseReplace!: () => void;
    let notifyReplace!: () => void;
    const replaceGate = new Promise<void>((resolve) => {
      releaseReplace = resolve;
    });
    const replaceStarted = new Promise<void>((resolve) => {
      notifyReplace = resolve;
    });
    const originalReplace = io.replace.bind(io);
    io.replace = async (from, to) => {
      notifyReplace();
      await replaceGate;
      await originalReplace(from, to);
    };

    const save = savingStore.save(identity, project('save-before-discard'));
    await replaceStarted;
    const listsBeforeDiscard = io.events.filter((event) => event.type === 'list').length;
    const discard = discardingStore.discard(identity);
    expect(io.events.filter((event) => event.type === 'list')).toHaveLength(
      listsBeforeDiscard
    );

    releaseReplace();
    await Promise.all([save, discard]);
    await expect(savingStore.restore(identity)).resolves.toEqual({
      status: 'rejected',
      reason: 'missing',
    });
  });

  test('uses deterministic session journal paths without leaking source or session text', () => {
    const base = draftStoragePaths('file:///cache/', identity);
    expect(draftStoragePaths('file:///cache/', identity)).toEqual(base);
    expect(
      draftStoragePaths('file:///cache/', {
        ...identity,
        source: { ...identity.source, stableId: 'provider-document:43' },
      })
    ).toEqual(base);
    expect(
      draftStoragePaths('file:///cache/', { ...identity, sessionId: 'editor/session:beta' }).draft
    ).not.toBe(base.draft);
    expect(base.draft).toMatch(/^file:\/\/\/cache\/meme_edit_draft_[a-f0-9]+_a\.json$/);
    expect(base.slotB).toMatch(/^file:\/\/\/cache\/meme_edit_draft_[a-f0-9]+_b\.json$/);
  });

  test('reads and restores a validated project snapshot', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const expected = project('restored.mp4');
    await store.save(identity, expected);

    const restored = await store.restore(identity);

    expect(restored).toEqual({ status: 'restored', project: expected, savedAtMs: 20_000 });
  });

  test('returns typed missing and corrupt reasons for absent, malformed, and invalid snapshots', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const paths = draftStoragePaths(io.cacheDirectory, identity);

    await expect(store.restore(identity)).resolves.toEqual({ status: 'rejected', reason: 'missing' });

    io.files.set(paths.draft, '{not json');
    await expect(store.restore(identity)).resolves.toEqual({ status: 'rejected', reason: 'corrupt' });

    io.files.set(
      paths.draft,
      JSON.stringify({
        version: 1,
        savedAtMs: 19_000,
        source: identity.source,
        project: { ...project(), layers: new Array(65).fill({}) },
      })
    );
    await expect(store.restore(identity)).resolves.toEqual({ status: 'rejected', reason: 'corrupt' });
  });

  test.each([
    ['stableId', 'provider-document:changed'],
    ['uri', 'content://provider/document/changed'],
    ['name', 'renamed.mp4'],
    ['kind', 'image'],
    ['width', 1_281],
    ['height', 721],
    ['durationUs', 5_000_001],
    ['byteSize', 1_025],
    ['modifiedTimeMs', 10_001],
  ] as const)('rejects a draft when source %s no longer matches', async (field, value) => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    await store.save(identity, project());
    const changedSource = {
      ...identity,
      source: { ...identity.source, [field]: value },
    } as MemeEditDraftIdentity;

    await expect(store.restore(changedSource)).resolves.toEqual({
      status: 'rejected',
      reason: 'source-mismatch',
    });
  });

  test('treats null source facts as explicit unknown values rather than wildcards', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const unknownIdentity: MemeEditDraftIdentity = {
      ...identity,
      source: { ...identity.source, byteSize: null, modifiedTimeMs: null },
    };
    await store.save(unknownIdentity, project());

    await expect(store.restore(identity)).resolves.toEqual({
      status: 'rejected',
      reason: 'source-mismatch',
    });
  });

  test('expires at the exact seven-day millisecond boundary, but not one millisecond before', async () => {
    const io = new MemoryDraftIo();
    let nowMs = 1_000;
    const store = new MemeEditDraftStore(io, { now: () => nowMs });
    await store.save(identity, project());

    nowMs = 1_000 + DRAFT_EXPIRY_MS - 1;
    await expect(store.restore(identity)).resolves.toMatchObject({ status: 'restored' });

    nowMs = 1_000 + DRAFT_EXPIRY_MS;
    await expect(store.restore(identity)).resolves.toEqual({ status: 'rejected', reason: 'expired' });
  });

  test('strips transient native cache URIs but retains bounded persistent mask corrections', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const edited = project();
    edited.maskTracks = [
      {
        id: 'subject-1',
        active: { startUs: 0, endUs: 5_000_000 },
        corrections: [
          {
            timeUs: 0,
            rect: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            easing: 'linear',
          },
        ],
      },
    ];
    edited.transient = {
      materializedSourceUri: 'file:///cache/meme_work_source.mp4',
      maskTracks: { 'subject-1': 'file:///cache/meme_work_mask.bin' },
    };

    await store.save(identity, edited);
    const restored = await store.restore(identity);

    expect(restored.status).toBe('restored');
    if (restored.status !== 'restored') throw new Error('expected restored draft');
    expect(restored.project.transient).toEqual({ materializedSourceUri: null, maskTracks: {} });
    expect(restored.project.maskTracks).toEqual(edited.maskTracks);
  });

  test('rejects a project whose source metadata does not match the draft identity', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const mismatched = project();
    mismatched.source.uri = 'content://provider/document/other';

    await expect(store.save(identity, mismatched)).rejects.toThrow(/source.*does not match/i);
    expect(draftWriteEvents(io)).toHaveLength(0);
  });

  test('rejects unbounded projects before writing any bytes', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const unbounded = project();
    unbounded.layers = new Array(65).fill(null) as MemeEditProject['layers'];

    await expect(store.save(identity, unbounded)).rejects.toThrow(/invalid project/i);
    expect(draftWriteEvents(io)).toHaveLength(0);
  });

  test('discard removes draft JSON, temp JSON, and only owned session work assets idempotently', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project());
    io.files.set(paths.temporary, 'partial');
    io.files.set(paths.slotB, 'older generation');
    io.files.set(paths.temporaryB, 'interrupted generation');
    io.files.set(`${io.cacheDirectory}${paths.ownedAssetPrefix}source.mp4`, 'owned source');
    io.files.set(`${io.cacheDirectory}${paths.ownedAssetPrefix}mask_1.bin`, 'owned mask');
    io.files.set(`${io.cacheDirectory}meme_work_another-session_source.mp4`, 'other session');
    io.files.set(`${io.cacheDirectory}share_finished.mp4`, 'share');
    io.files.set(`${io.cacheDirectory}clipboard/video.mp4`, 'clipboard');
    io.files.set(`${io.cacheDirectory}export_final.mp4`, 'export');

    await store.discard(identity);
    await store.discard(identity);

    expect(io.files.has(paths.draft)).toBe(false);
    expect(io.files.has(paths.temporary)).toBe(false);
    expect(io.files.has(paths.slotB)).toBe(false);
    expect(io.files.has(paths.temporaryB)).toBe(false);
    expect([...io.files.keys()].sort()).toEqual(
      [
        `${io.cacheDirectory}clipboard/video.mp4`,
        `${io.cacheDirectory}export_final.mp4`,
        `${io.cacheDirectory}meme_work_another-session_source.mp4`,
        `${io.cacheDirectory}share_finished.mp4`,
      ].sort()
    );
  });

  test('cleans a partially written temp file when the write fails and preserves the old draft', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    io.files.set(paths.draft, 'old complete draft');
    io.writeError = new Error('disk full');

    await expect(store.save(identity, project())).rejects.toThrow('disk full');

    expect(io.files.get(paths.draft)).toBe('old complete draft');
    expect(io.files.has(paths.temporary)).toBe(false);
    expect(io.events).toContainEqual({ type: 'remove', path: paths.temporary });
  });
});

describe('MemeEditAutosaveController', () => {
  test('coalesces updates into the latest snapshot after exactly 500ms', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });

    controller.schedule(project('first.mp4'));
    timers.advanceBy(499);
    expect(draftWriteEvents(io)).toHaveLength(0);
    controller.schedule(project('latest.mp4'));
    expect(timers.size).toBe(1);
    timers.advanceBy(499);
    expect(draftWriteEvents(io)).toHaveLength(0);
    timers.advanceBy(1);
    await controller.flush();

    expect(draftWriteEvents(io)).toHaveLength(1);
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'latest.mp4' } },
    });
  });

  test('explicit flush saves immediately for an AppState background transition', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    controller.schedule(project('background.mp4'));

    await controller.flush();

    expect(timers.size).toBe(0);
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'background.mp4' } },
    });
  });

  test('serializes overlapping flushes so an older write cannot replace a newer draft', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    let notifyFirstWrite!: () => void;
    const firstWriteStarted = new Promise<void>((resolve) => {
      notifyFirstWrite = resolve;
    });
    const originalWrite = io.writeText.bind(io);
    let writeCount = 0;
    io.writeText = async (path, text) => {
      await originalWrite(path, text);
      writeCount += 1;
      if (writeCount === 1) notifyFirstWrite();
    };
    let releaseFirstReplace!: () => void;
    const firstReplaceGate = new Promise<void>((resolve) => {
      releaseFirstReplace = resolve;
    });
    const originalReplace = io.replace.bind(io);
    let replaceCount = 0;
    io.replace = async (from, to) => {
      replaceCount += 1;
      if (replaceCount === 1) await firstReplaceGate;
      await originalReplace(from, to);
    };

    controller.schedule(project('older.mp4'));
    const olderFlush = controller.flush();
    await firstWriteStarted;
    controller.schedule(project('newer.mp4'));
    const newerFlush = controller.flush();
    await Promise.resolve();
    expect(draftWriteEvents(io)).toHaveLength(1);

    releaseFirstReplace();
    await Promise.all([olderFlush, newerFlush]);

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'newer.mp4' } },
    });
  });

  test('background flush observes an in-flight timer save failure and the queue later recovers', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const onError = jest.fn();
    const controller = new MemeEditAutosaveController(store, identity, { timers, onError });
    io.writeError = new Error('autosave disk failure');
    controller.schedule(project('failed.mp4'));
    timers.advanceBy(500);

    await expect(controller.flush()).rejects.toThrow('autosave disk failure');
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'autosave disk failure' }));

    io.writeError = null;
    controller.schedule(project('recovered.mp4'));
    await expect(controller.flush()).resolves.toBeUndefined();
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'recovered.mp4' } },
    });
  });

  test('contains an onError callback that throws after a timer-started save failure', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const onError = jest.fn(() => {
      throw new Error('error reporter failed');
    });
    const controller = new MemeEditAutosaveController(store, identity, { timers, onError });
    io.writeError = new Error('timer save failed');
    controller.schedule(project('failed.mp4'));

    timers.advanceBy(500);
    await expect(controller.flush()).rejects.toThrow('timer save failed');
    await Promise.resolve();
    await Promise.resolve();

    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'timer save failed' }));
  });

  test('cancel clears the timer and prevents the pending snapshot from being written', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    controller.schedule(project());

    controller.cancel();
    timers.advanceBy(1_000);
    await controller.flush();

    expect(timers.size).toBe(0);
    expect(draftWriteEvents(io)).toHaveLength(0);
  });

  test('discard cancels pending saves, waits for active saves, and removes owned artifacts', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    const paths = draftStoragePaths(io.cacheDirectory, identity);
    await store.save(identity, project());
    io.files.set(`${io.cacheDirectory}${paths.ownedAssetPrefix}mask.bin`, 'mask');
    controller.schedule(project('never-written.mp4'));

    await controller.discard();
    timers.advanceBy(1_000);

    expect(timers.size).toBe(0);
    expect(io.files.has(paths.draft)).toBe(false);
    expect(io.files.has(`${io.cacheDirectory}${paths.ownedAssetPrefix}mask.bin`)).toBe(false);
  });

  test('discard remains schedulable when store cleanup fails', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    const originalRemove = io.remove.bind(io);
    let failed = false;
    io.remove = async (path) => {
      if (!failed) {
        failed = true;
        throw new Error(`remove failed: ${path}`);
      }
      await originalRemove(path);
    };

    await expect(controller.discard()).rejects.toThrow('remove failed');
    io.remove = originalRemove;
    controller.schedule(project('after-failed-discard'));
    await controller.flush();

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'after-failed-discard' } },
    });
  });

  test('discard failure preserves pending snapshot and timer for retry flush', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    const originalRemove = io.remove.bind(io);
    io.remove = async (path) => {
      throw new Error(`remove failed: ${path}`);
    };
    controller.schedule(project('pending-latest'));

    await expect(controller.discard()).rejects.toThrow('remove failed');
    io.remove = originalRemove;
    await controller.flush();

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'pending-latest' } },
    });
  });

  test('discard failure does not overwrite a newer schedule made while discard is pending', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    let releaseRemove!: () => void;
    const removeGate = new Promise<void>((resolve) => {
      releaseRemove = resolve;
    });
    io.remove = async (path) => {
      await removeGate;
      throw new Error(`remove failed: ${path}`);
    };
    controller.schedule(project('discard-A'));
    const discard = controller.discard();
    await Promise.resolve();

    controller.schedule(project('newer-B'));
    releaseRemove();
    await expect(discard).rejects.toThrow('remove failed');
    await controller.flush();

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'newer-B' } },
    });
  });

  test('teardown helper flushes pending debounce before releasing source assets', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    const release = jest.fn(async () => {});
    controller.schedule(project('teardown-save'));

    await flushAutosaveBeforeSourceRelease(controller, release);

    expect(release).toHaveBeenCalledTimes(1);
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'teardown-save' } },
    });
  });

  test('teardown helper keeps source release retryable when autosave flush fails', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const errors: unknown[] = [];
    const controller = new MemeEditAutosaveController(store, identity, { timers });
    const release = jest.fn(async () => {});
    io.writeError = new Error('flush failed');
    controller.schedule(project('flush-retry'));

    await flushAutosaveBeforeSourceRelease(controller, release, (error) => errors.push(error));

    expect(release).not.toHaveBeenCalled();
    expect(errors).toHaveLength(1);
    io.writeError = null;
    await flushAutosaveBeforeSourceRelease(controller, release, (error) => errors.push(error));

    expect(release).toHaveBeenCalledTimes(1);
    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { background: { color: 'flush-retry' } },
    });
  });
});

describe('MemeEditSourcePreparationController', () => {
  const probeResult = {
    kind: 'video' as const,
    width: 1_280,
    height: 720,
    rotationDegrees: 0 as const,
    flipX: false,
    flipY: false,
    durationUs: 5_000_000,
    frameRate: 30,
    videoMime: 'video/avc',
    audioMime: 'audio/mp4a-latm',
    hasAudio: true,
    seekable: true,
    byteSize: 1_024,
    modifiedTimeMs: 10_000,
    stableId: 'materialized-source',
    displayName: 'source.mp4',
  };

  test('materializes content once per session, shares concurrent work, probes the file, and discards it', async () => {
    let releaseCopy!: () => void;
    const copyGate = new Promise<void>((resolve) => {
      releaseCopy = resolve;
    });
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => copyGate),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourcePreparationController(io, identity);
    const firstProject = project('first-editor-state.mp4');
    const secondProject = project('second-editor-state.mp4');

    const first = controller.prepare(firstProject);
    const second = controller.prepare(secondProject);
    await Promise.resolve();
    expect(io.materialize).toHaveBeenCalledTimes(1);
    const destination = (io.materialize as jest.Mock).mock.calls[0][1] as string;
    expect(destination).toMatch(
      /^file:\/\/\/cache\/meme_work_[a-f0-9]+_materialized_source\.mp4$/
    );

    releaseCopy();
    const [firstPrepared, secondPrepared] = await Promise.all([first, second]);
    expect(io.probe).toHaveBeenCalledTimes(1);
    expect(io.probe).toHaveBeenCalledWith(destination);
    expect(firstPrepared.project.transient.materializedSourceUri).toBe(destination);
    expect(secondPrepared.project.transient.materializedSourceUri).toBe(destination);
    expect(firstPrepared.project.background.color).toBe('first-editor-state.mp4');
    expect(secondPrepared.project.background.color).toBe('second-editor-state.mp4');
    expect(firstPrepared.probe).toEqual(probeResult);
    expect(firstPrepared.owned).toBe(true);

    await controller.prepare(project());
    expect(io.materialize).toHaveBeenCalledTimes(1);
    expect(io.probe).toHaveBeenCalledTimes(1);
    await controller.discard();
    expect(io.remove).toHaveBeenCalledWith(destination);
  });

  test('shares preparation across controllers and deletes only after the final staggered release', async () => {
    let releaseCopy!: () => void;
    const copyGate = new Promise<void>((resolve) => {
      releaseCopy = resolve;
    });
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => copyGate),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const firstController = new MemeEditSourcePreparationController(io, identity);
    const secondController = new MemeEditSourcePreparationController(io, identity);

    const first = firstController.prepare(project('first-controller'));
    const firstAgain = firstController.prepare(project('first-controller-again'));
    const second = secondController.prepare(project('second-controller'));
    await Promise.resolve();
    expect(io.materialize).toHaveBeenCalledTimes(1);
    expect(io.remove).toHaveBeenCalledTimes(1);

    releaseCopy();
    const [firstPrepared, firstAgainPrepared, secondPrepared] = await Promise.all([
      first,
      firstAgain,
      second,
    ]);
    expect(firstPrepared.materializedSourceUri).toBe(secondPrepared.materializedSourceUri);
    expect(firstAgainPrepared.materializedSourceUri).toBe(secondPrepared.materializedSourceUri);
    expect(io.probe).toHaveBeenCalledTimes(1);

    await firstController.discard();
    expect(io.remove).toHaveBeenCalledTimes(1);
    await expect(secondController.prepare(project('still-live'))).resolves.toMatchObject({
      materializedSourceUri: secondPrepared.materializedSourceUri,
    });

    await secondController.discard();
    expect(io.remove).toHaveBeenCalledTimes(2);
    expect(io.remove).toHaveBeenLastCalledWith(secondPrepared.materializedSourceUri);
  });

  test('removes a failed shared preparation so another controller can retry', async () => {
    const retryIdentity: MemeEditDraftIdentity = {
      ...identity,
      sessionId: 'editor/session:retry',
    };
    const failingIo: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {
        throw new Error('copy failed');
      }),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const firstController = new MemeEditSourcePreparationController(failingIo, retryIdentity);
    await expect(firstController.prepare(project())).rejects.toThrow('copy failed');

    const workingIo: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const retryController = new MemeEditSourcePreparationController(workingIo, retryIdentity);
    await expect(retryController.prepare(project())).resolves.toMatchObject({ owned: true });
    expect(workingIo.materialize).toHaveBeenCalledTimes(1);
    await retryController.discard();
  });

  test('removes a stale owned destination before the first materialization copy', async () => {
    const paths = draftStoragePaths('file:///cache/', identity);
    const destination =
      `file:///cache/${paths.ownedAssetPrefix}materialized_source.mp4`;
    const files = new Set([destination]);
    const events: string[] = [];
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      async materialize(_source, target) {
        events.push(`materialize:${target}`);
        if (files.has(target)) throw new Error('destination already exists');
        files.add(target);
      },
      async remove(path) {
        events.push(`remove:${path}`);
        files.delete(path);
      },
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourcePreparationController(io, identity);

    await expect(controller.prepare(project())).resolves.toMatchObject({
      materializedSourceUri: destination,
      owned: true,
    });
    expect(events.slice(0, 2)).toEqual([
      `remove:${destination}`,
      `materialize:${destination}`,
    ]);
    await controller.discard();
  });

  test.each([
    ['name', 'same-uri-renamed.mp4'],
    ['kind', 'image'],
    ['width', 1_281],
    ['height', 721],
    ['durationUs', 5_000_001],
  ] as const)('rejects same-URI project source %s changes before materializing', async (field, value) => {
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourcePreparationController(io, identity);
    const mismatched = project();
    (mismatched.source as unknown as Record<string, unknown>)[field] = value;

    await expect(controller.prepare(mismatched)).rejects.toThrow(/source does not match/i);
    expect(io.materialize).not.toHaveBeenCalled();
    expect(io.probe).not.toHaveBeenCalled();
  });

  test('reuses file sources without taking ownership or deleting them on cancel', async () => {
    const fileIdentity: MemeEditDraftIdentity = {
      ...identity,
      source: {
        ...identity.source,
        stableId: 'local-file-source',
        uri: 'file:///documents/source.mp4',
      },
    };
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourcePreparationController(io, fileIdentity);
    const fileProject = project();
    fileProject.source.uri = fileIdentity.source.uri;

    const prepared = await controller.prepare(fileProject);
    await controller.cancel();

    expect(io.materialize).not.toHaveBeenCalled();
    expect(io.probe).toHaveBeenCalledWith(fileIdentity.source.uri);
    expect(prepared.project.transient.materializedSourceUri).toBe(fileIdentity.source.uri);
    expect(prepared.owned).toBe(false);
    expect(io.remove).not.toHaveBeenCalled();
  });

  test('does not alias unowned file probes when the same URI has changed source facts', async () => {
    const firstIdentity: MemeEditDraftIdentity = {
      ...identity,
      sessionId: 'editor/session:file-facts-1',
      source: {
        ...identity.source,
        stableId: 'local-file-before',
        uri: 'file:///documents/shared-source.mp4',
      },
    };
    const changedIdentity: MemeEditDraftIdentity = {
      ...firstIdentity,
      sessionId: 'editor/session:file-facts-2',
      source: {
        ...firstIdentity.source,
        stableId: 'local-file-after',
        byteSize: firstIdentity.source.byteSize! + 1,
        modifiedTimeMs: firstIdentity.source.modifiedTimeMs! + 1,
      },
    };
    const probe = jest
      .fn()
      .mockResolvedValueOnce({ ...probeResult, stableId: 'probe-before' })
      .mockResolvedValueOnce({ ...probeResult, stableId: 'probe-after' });
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove: jest.fn(async () => {}),
      probe,
    };
    const firstController = new MemeEditSourcePreparationController(io, firstIdentity);
    const changedController = new MemeEditSourcePreparationController(io, changedIdentity);
    const fileProject = project();
    fileProject.source.uri = firstIdentity.source.uri;

    const [before, after] = await Promise.all([
      firstController.prepare(fileProject),
      changedController.prepare(fileProject),
    ]);

    expect(probe).toHaveBeenCalledTimes(2);
    expect(before.probe?.stableId).toBe('probe-before');
    expect(after.probe?.stableId).toBe('probe-after');
    await firstController.discard();
    await changedController.discard();
  });

  test('session controller materializes then probes once and lets probed kind win over indexed gif kind', async () => {
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => ({ ...probeResult, kind: 'video' as const, displayName: 'funny.gif' })),
    };
    const controller = new MemeEditSourceSessionController(io, {
      sessionId: 'meme-remix/99',
      uri: 'content://provider/funny.gif',
      name: 'funny.gif',
      indexedKind: 'image',
      modifiedTimeMs: 10_000,
    });

    const prepared = await controller.prepare();

    expect(io.materialize).toHaveBeenCalledTimes(1);
    const destination = (io.materialize as jest.Mock).mock.calls[0][1] as string;
    expect(io.probe).toHaveBeenCalledTimes(1);
    expect(io.probe).toHaveBeenCalledWith(destination);
    expect(prepared.project.source.kind).toBe('video');
    expect(prepared.identity.source.kind).toBe('video');
    expect(prepared.project.transient.materializedSourceUri).toBe(destination);
    await controller.cancel();
    expect(io.remove).toHaveBeenLastCalledWith(destination);
  });

  test('session controller releases owned materialization after in-flight cancel without creating draft state', async () => {
    let releaseCopy!: () => void;
    const copyGate = new Promise<void>((resolve) => {
      releaseCopy = resolve;
    });
    const events: string[] = [];
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      async materialize(_source, destination) {
        events.push(`materialize:${destination}`);
        await copyGate;
      },
      async remove(path) {
        events.push(`remove:${path}`);
      },
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourceSessionController(io, {
      sessionId: 'meme-remix/cancel',
      uri: 'content://provider/source.mp4',
      name: 'source.mp4',
      indexedKind: 'video',
      modifiedTimeMs: null,
    });

    const preparing = controller.prepare();
    const cancelling = controller.cancel();
    releaseCopy();

    await expect(preparing).rejects.toThrow(/cancelled/i);
    await cancelling;
    expect(io.probe).not.toHaveBeenCalled();
    expect(events.some((event) => event.startsWith('remove:file:///cache/'))).toBe(true);
  });

  test('loading close helper calls onClose before a stalled materialize releases', async () => {
    let releaseCopy!: () => void;
    const copyGate = new Promise<void>((resolve) => {
      releaseCopy = resolve;
    });
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => copyGate),
      remove: jest.fn(async () => {}),
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourceSessionController(io, {
      sessionId: 'meme-remix/prompt-close',
      uri: 'content://provider/source.mp4',
      name: 'source.mp4',
      indexedKind: 'video',
      modifiedTimeMs: null,
    });
    const onClose = jest.fn();
    const onError = jest.fn();
    void controller.prepare().catch(() => {});

    requestSourceSessionClose(controller, onClose, onError);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(io.probe).not.toHaveBeenCalled();
    releaseCopy();
    await Promise.resolve();
    await Promise.resolve();
    expect(onError).not.toHaveBeenCalled();
  });

  test('source session cancel retries owned deletion until cleanup succeeds', async () => {
    const remove = jest
      .fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('unlink busy'))
      .mockResolvedValue(undefined);
    const io: MemeEditSourcePreparationIo = {
      cacheDirectory: 'file:///cache/',
      materialize: jest.fn(async () => {}),
      remove,
      probe: jest.fn(async () => probeResult),
    };
    const controller = new MemeEditSourceSessionController(io, {
      sessionId: 'meme-remix/retry-remove',
      uri: 'content://provider/source.mp4',
      name: 'source.mp4',
      indexedKind: 'video',
      modifiedTimeMs: null,
    });
    await controller.prepare();

    await expect(controller.cancel()).rejects.toThrow('unlink busy');
    await expect(controller.cancel()).resolves.toBeUndefined();
    expect(remove).toHaveBeenCalledTimes(3);
  });
});


describe('Expo draft IO adapter', () => {
  test('maps cache operations to Expo legacy filesystem primitives', async () => {
    const FileSystem = jest.requireMock('expo-file-system/legacy') as {
      getInfoAsync: jest.Mock;
      readAsStringAsync: jest.Mock;
      writeAsStringAsync: jest.Mock;
      moveAsync: jest.Mock;
      deleteAsync: jest.Mock;
      readDirectoryAsync: jest.Mock;
    };
    FileSystem.getInfoAsync.mockResolvedValueOnce({ exists: true });
    FileSystem.readAsStringAsync.mockResolvedValueOnce('draft');
    FileSystem.readDirectoryAsync.mockResolvedValueOnce(['a', 'b']);
    const io = createExpoMemeEditDraftIo();

    await expect(io.readText('file:///expo-cache/a')).resolves.toBe('draft');
    await io.writeText('file:///expo-cache/a.tmp', 'json');
    await io.replace('file:///expo-cache/a.tmp', 'file:///expo-cache/a');
    await io.remove('file:///expo-cache/a');
    await expect(io.listCacheEntries()).resolves.toEqual(['a', 'b']);

    expect(FileSystem.writeAsStringAsync).toHaveBeenCalledWith('file:///expo-cache/a.tmp', 'json');
    expect(FileSystem.moveAsync).toHaveBeenCalledWith({
      from: 'file:///expo-cache/a.tmp',
      to: 'file:///expo-cache/a',
    });
    expect(FileSystem.deleteAsync).toHaveBeenCalledWith('file:///expo-cache/a', {
      idempotent: true,
    });
  });
});
