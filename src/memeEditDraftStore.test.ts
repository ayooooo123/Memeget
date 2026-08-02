jest.mock('expo-file-system/legacy', () => ({
  cacheDirectory: 'file:///expo-cache/',
  deleteAsync: jest.fn(async () => {}),
  getInfoAsync: jest.fn(async () => ({ exists: false })),
  moveAsync: jest.fn(async () => {}),
  readAsStringAsync: jest.fn(async () => ''),
  readDirectoryAsync: jest.fn(async () => []),
  writeAsStringAsync: jest.fn(async () => {}),
}));

import {
  createDefaultVideoProject,
  type MemeEditProject,
} from './memeEditProjectCore';
import {
  DRAFT_EXPIRY_MS,
  MemeEditAutosaveController,
  MemeEditDraftStore,
  createExpoMemeEditDraftIo,
  draftStoragePaths,
  type MemeEditDraftIdentity,
  type MemeEditDraftIo,
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
  writeError: Error | null = null;
  replaceError: Error | null = null;

  async readText(path: string): Promise<string | null> {
    this.events.push({ type: 'read', path });
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
    byteSize: 1_024,
    modifiedTimeMs: 10_000,
  },
};

function project(name = 'source.mp4'): MemeEditProject {
  return createDefaultVideoProject({
    uri: 'content://provider/document/42',
    name,
    width: 1_280,
    height: 720,
    durationUs: 5_000_000,
  });
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
    expect(io.events.slice(0, 2).map((event) => event.type)).toEqual(['write', 'replace']);
    expect(io.events[0]).toMatchObject({ type: 'write', path: paths.temporary });
    expect(io.events[1]).toEqual({ type: 'replace', from: paths.temporary, to: paths.draft });
    expect(io.files.has(paths.draft)).toBe(true);
    expect(io.files.has(paths.temporary)).toBe(false);
  });

  test('uses distinct deterministic paths for source and session identities without path text', () => {
    const base = draftStoragePaths('file:///cache/', identity);
    expect(draftStoragePaths('file:///cache/', identity)).toEqual(base);
    expect(
      draftStoragePaths('file:///cache/', {
        ...identity,
        source: { ...identity.source, stableId: 'provider-document:43' },
      }).draft
    ).not.toBe(base.draft);
    expect(
      draftStoragePaths('file:///cache/', { ...identity, sessionId: 'editor/session:beta' }).draft
    ).not.toBe(base.draft);
    expect(base.draft).toMatch(/^file:\/\/\/cache\/meme_edit_draft_[a-f0-9]+\.json$/);
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

  test('rejects a draft when stable source facts no longer match', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    await store.save(identity, project());

    const changedSource: MemeEditDraftIdentity = {
      ...identity,
      source: { ...identity.source, byteSize: identity.source.byteSize! + 1 },
    };
    await expect(store.restore(changedSource)).resolves.toEqual({
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
      project: { source: { name: 'latest.mp4' } },
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
      project: { source: { name: 'background.mp4' } },
    });
  });

  test('serializes overlapping flushes so an older write cannot replace a newer draft', async () => {
    const io = new MemoryDraftIo();
    const store = new MemeEditDraftStore(io, { now: () => 20_000 });
    const timers = new FakeTimers();
    const controller = new MemeEditAutosaveController(store, identity, { timers });
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
    await Promise.resolve();
    controller.schedule(project('newer.mp4'));
    const newerFlush = controller.flush();
    await Promise.resolve();
    expect(draftWriteEvents(io)).toHaveLength(1);

    releaseFirstReplace();
    await Promise.all([olderFlush, newerFlush]);

    await expect(store.restore(identity)).resolves.toMatchObject({
      status: 'restored',
      project: { source: { name: 'newer.mp4' } },
    });
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
