import * as FileSystem from 'expo-file-system/legacy';

import {
  validateMemeEditProject,
  type MemeEditProject,
  type ProjectValidationError,
} from './memeEditProjectCore';

export const DRAFT_EXPIRY_MS = 7 * 24 * 60 * 60 * 1_000;
export const DRAFT_AUTOSAVE_DELAY_MS = 500;

const DRAFT_VERSION = 1;
const MAX_DRAFT_JSON_LENGTH = 4 * 1024 * 1024;
const MAX_SESSION_ID_LENGTH = 1_024;
const MAX_STABLE_SOURCE_ID_LENGTH = 4_096;

export interface MemeEditDraftSourceIdentity {
  stableId: string;
  byteSize: number | null;
  modifiedTimeMs: number | null;
}

export interface MemeEditDraftIdentity {
  sessionId: string;
  source: MemeEditDraftSourceIdentity;
}

export interface MemeEditDraftIo {
  readonly cacheDirectory: string;
  readText(path: string): Promise<string | null>;
  writeText(path: string, text: string): Promise<void>;
  replace(from: string, to: string): Promise<void>;
  remove(path: string): Promise<void>;
  listCacheEntries(): Promise<string[]>;
}

export interface MemeEditDraftClock {
  now(): number;
}

export interface MemeEditTimers {
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface MemeEditDraftStoragePaths {
  draft: string;
  temporary: string;
  ownedAssetPrefix: string;
}

export type MemeEditDraftRestoreResult =
  | { status: 'restored'; project: MemeEditProject; savedAtMs: number }
  | {
      status: 'rejected';
      reason: 'missing' | 'expired' | 'corrupt' | 'source-mismatch';
    };

interface SerializedDraft {
  version: 1;
  savedAtMs: number;
  source: MemeEditDraftSourceIdentity;
  project: MemeEditProject;
}

interface MemeEditAutosaveOptions {
  timers?: MemeEditTimers;
  onError?: (error: unknown) => void;
}

const SYSTEM_CLOCK: MemeEditDraftClock = { now: () => Date.now() };
const SYSTEM_TIMERS: MemeEditTimers = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

function assertIdentity(identity: MemeEditDraftIdentity): void {
  if (
    typeof identity.sessionId !== 'string' ||
    identity.sessionId.length === 0 ||
    identity.sessionId.length > MAX_SESSION_ID_LENGTH
  ) {
    throw new Error(`Draft sessionId must contain 1-${MAX_SESSION_ID_LENGTH} characters.`);
  }
  if (
    typeof identity.source?.stableId !== 'string' ||
    identity.source.stableId.length === 0 ||
    identity.source.stableId.length > MAX_STABLE_SOURCE_ID_LENGTH
  ) {
    throw new Error(
      `Draft source stableId must contain 1-${MAX_STABLE_SOURCE_ID_LENGTH} characters.`
    );
  }
  for (const [label, value] of [
    ['byteSize', identity.source.byteSize],
    ['modifiedTimeMs', identity.source.modifiedTimeMs],
  ] as const) {
    if (value !== null && (!Number.isSafeInteger(value) || value < 0)) {
      throw new Error(`Draft source ${label} must be a non-negative safe integer or null.`);
    }
  }
}

// cyrb128 gives four independently mixed 32-bit words. It is not a security
// boundary; it keeps source paths/session labels out of cache filenames while
// making accidental collisions across the bounded identity input negligible.
function identityToken(value: string): string {
  let h1 = 1_779_033_703;
  let h2 = 3_144_134_277;
  let h3 = 1_013_904_242;
  let h4 = 2_773_480_762;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    h1 = h2 ^ Math.imul(h1 ^ code, 597_399_067);
    h2 = h3 ^ Math.imul(h2 ^ code, 2_869_860_233);
    h3 = h4 ^ Math.imul(h3 ^ code, 951_274_213);
    h4 = h1 ^ Math.imul(h4 ^ code, 2_716_044_179);
  }
  h1 = Math.imul(h3 ^ (h1 >>> 18), 597_399_067);
  h2 = Math.imul(h4 ^ (h2 >>> 22), 2_869_860_233);
  h3 = Math.imul(h1 ^ (h3 >>> 17), 951_274_213);
  h4 = Math.imul(h2 ^ (h4 >>> 19), 2_716_044_179);
  h1 ^= h2 ^ h3 ^ h4;
  h2 ^= h1;
  h3 ^= h1;
  h4 ^= h1;
  return [h1, h2, h3, h4].map((word) => (word >>> 0).toString(16).padStart(8, '0')).join('');
}

export function draftStoragePaths(
  cacheDirectory: string,
  identity: MemeEditDraftIdentity
): MemeEditDraftStoragePaths {
  assertIdentity(identity);
  if (!cacheDirectory) throw new Error('Expo cache directory is unavailable.');
  const directory = cacheDirectory.endsWith('/') ? cacheDirectory : `${cacheDirectory}/`;
  const draftToken = identityToken(`${identity.sessionId.length}:${identity.sessionId}\0${identity.source.stableId}`);
  const sessionToken = identityToken(identity.sessionId);
  const draft = `${directory}meme_edit_draft_${draftToken}.json`;
  return {
    draft,
    temporary: `${draft}.tmp`,
    ownedAssetPrefix: `meme_work_${sessionToken}_`,
  };
}

function sourceIdentityMatches(
  saved: MemeEditDraftSourceIdentity,
  current: MemeEditDraftSourceIdentity
): boolean {
  if (saved.stableId !== current.stableId) return false;
  if (saved.byteSize !== null && current.byteSize !== null && saved.byteSize !== current.byteSize) {
    return false;
  }
  if (
    saved.modifiedTimeMs !== null &&
    current.modifiedTimeMs !== null &&
    saved.modifiedTimeMs !== current.modifiedTimeMs
  ) {
    return false;
  }
  return true;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactFields(value: Record<string, unknown>, fields: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  return actual.length === expected.length && actual.every((field, index) => field === expected[index]);
}

function parseSerializedDraft(text: string): SerializedDraft | null {
  if (text.length === 0 || text.length > MAX_DRAFT_JSON_LENGTH) return null;
  let input: unknown;
  try {
    input = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isRecord(input) || !hasExactFields(input, ['version', 'savedAtMs', 'source', 'project'])) {
    return null;
  }
  if (
    input.version !== DRAFT_VERSION ||
    typeof input.savedAtMs !== 'number' ||
    !Number.isSafeInteger(input.savedAtMs) ||
    input.savedAtMs < 0
  ) {
    return null;
  }
  if (
    !isRecord(input.source) ||
    !hasExactFields(input.source, ['stableId', 'byteSize', 'modifiedTimeMs'])
  ) {
    return null;
  }
  const source = input.source;
  if (
    typeof source.stableId !== 'string' ||
    source.stableId.length === 0 ||
    source.stableId.length > MAX_STABLE_SOURCE_ID_LENGTH
  ) {
    return null;
  }
  for (const value of [source.byteSize, source.modifiedTimeMs]) {
    if (
      value !== null &&
      (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)
    ) {
      return null;
    }
  }
  const validation = validateMemeEditProject(input.project);
  if (!validation.ok) return null;
  return {
    version: 1,
    savedAtMs: input.savedAtMs,
    source: source as unknown as MemeEditDraftSourceIdentity,
    project: validation.value,
  };
}

function invalidProjectMessage(errors: readonly ProjectValidationError[]): string {
  const first = errors[0];
  return first
    ? `Invalid project snapshot at ${first.path || '<root>'}: ${first.message}`
    : 'Invalid project snapshot.';
}

export class MemeEditDraftStore {
  private readonly clock: MemeEditDraftClock;

  constructor(
    private readonly io: MemeEditDraftIo,
    clock: MemeEditDraftClock = SYSTEM_CLOCK
  ) {
    this.clock = clock;
  }

  async save(identity: MemeEditDraftIdentity, project: MemeEditProject): Promise<void> {
    assertIdentity(identity);
    const validation = validateMemeEditProject(project);
    if (!validation.ok) throw new Error(invalidProjectMessage(validation.errors));
    const savedAtMs = this.clock.now();
    if (!Number.isSafeInteger(savedAtMs) || savedAtMs < 0) {
      throw new Error('Draft clock must return a non-negative safe integer in milliseconds.');
    }
    const snapshot: MemeEditProject = {
      ...validation.value,
      transient: { materializedSourceUri: null, maskTracks: {} },
    };
    const sanitizedValidation = validateMemeEditProject(snapshot);
    if (!sanitizedValidation.ok) throw new Error(invalidProjectMessage(sanitizedValidation.errors));
    const serialized = JSON.stringify({
      version: DRAFT_VERSION,
      savedAtMs,
      source: identity.source,
      project: sanitizedValidation.value,
    } satisfies SerializedDraft);
    if (serialized.length > MAX_DRAFT_JSON_LENGTH) {
      throw new Error(`Invalid project snapshot: serialized draft exceeds ${MAX_DRAFT_JSON_LENGTH} characters.`);
    }

    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    try {
      await this.io.writeText(paths.temporary, serialized);
      await this.io.replace(paths.temporary, paths.draft);
    } finally {
      await this.io.remove(paths.temporary).catch(() => {});
    }
  }

  async restore(identity: MemeEditDraftIdentity): Promise<MemeEditDraftRestoreResult> {
    assertIdentity(identity);
    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    let text: string | null;
    try {
      text = await this.io.readText(paths.draft);
    } catch {
      return { status: 'rejected', reason: 'corrupt' };
    }
    if (text === null) return { status: 'rejected', reason: 'missing' };
    const draft = parseSerializedDraft(text);
    if (!draft) return { status: 'rejected', reason: 'corrupt' };
    if (!sourceIdentityMatches(draft.source, identity.source)) {
      return { status: 'rejected', reason: 'source-mismatch' };
    }
    const ageMs = this.clock.now() - draft.savedAtMs;
    if (!Number.isSafeInteger(ageMs)) return { status: 'rejected', reason: 'corrupt' };
    if (ageMs >= DRAFT_EXPIRY_MS) {
      await this.discard(identity).catch(() => {});
      return { status: 'rejected', reason: 'expired' };
    }
    return { status: 'restored', project: draft.project, savedAtMs: draft.savedAtMs };
  }

  async discard(identity: MemeEditDraftIdentity): Promise<void> {
    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    const entries = await this.io.listCacheEntries();
    const ownedAssets = entries
      .filter((name) => !name.includes('/') && name.startsWith(paths.ownedAssetPrefix))
      .sort();
    let firstError: unknown = null;
    for (const path of [paths.draft, paths.temporary, ...ownedAssets.map((name) => `${this.io.cacheDirectory}${name}`)]) {
      try {
        await this.io.remove(path);
      } catch (error) {
        if (firstError === null) firstError = error;
      }
    }
    if (firstError !== null) throw firstError;
  }
}

export class MemeEditAutosaveController {
  private readonly timers: MemeEditTimers;
  private readonly onError: (error: unknown) => void;
  private timerHandle: unknown | null = null;
  private pendingProject: MemeEditProject | null = null;
  private queueTail: Promise<void> = Promise.resolve();
  private discarded = false;

  constructor(
    private readonly store: MemeEditDraftStore,
    private readonly identity: MemeEditDraftIdentity,
    options: MemeEditAutosaveOptions = {}
  ) {
    this.timers = options.timers ?? SYSTEM_TIMERS;
    this.onError = options.onError ?? (() => {});
  }

  schedule(project: MemeEditProject): void {
    if (this.discarded) throw new Error('Cannot schedule a discarded autosave controller.');
    this.pendingProject = project;
    if (this.timerHandle !== null) this.timers.clearTimeout(this.timerHandle);
    this.timerHandle = this.timers.setTimeout(() => {
      this.timerHandle = null;
      void this.flush().catch(this.onError);
    }, DRAFT_AUTOSAVE_DELAY_MS);
  }

  flush(): Promise<void> {
    if (this.timerHandle !== null) {
      this.timers.clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    const project = this.pendingProject;
    if (project === null) return this.queueTail;
    this.pendingProject = null;
    const operation = this.queueTail.then(() => this.store.save(this.identity, project));
    this.queueTail = operation.catch(() => {});
    return operation;
  }

  cancel(): void {
    if (this.timerHandle !== null) {
      this.timers.clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    this.pendingProject = null;
  }

  async discard(): Promise<void> {
    this.cancel();
    this.discarded = true;
    await this.queueTail;
    await this.store.discard(this.identity);
  }
}

export function createExpoMemeEditDraftIo(): MemeEditDraftIo {
  const cacheDirectory = FileSystem.cacheDirectory;
  if (!cacheDirectory) throw new Error('Expo cache directory is unavailable.');
  return {
    cacheDirectory,
    async readText(path) {
      const info = await FileSystem.getInfoAsync(path);
      return info.exists ? FileSystem.readAsStringAsync(path) : null;
    },
    writeText: (path, text) => FileSystem.writeAsStringAsync(path, text),
    replace: (from, to) => FileSystem.moveAsync({ from, to }),
    remove: (path) => FileSystem.deleteAsync(path, { idempotent: true }),
    listCacheEntries: () => FileSystem.readDirectoryAsync(cacheDirectory),
  };
}

export function createExpoMemeEditDraftStore(
  clock: MemeEditDraftClock = SYSTEM_CLOCK
): MemeEditDraftStore {
  return new MemeEditDraftStore(createExpoMemeEditDraftIo(), clock);
}
