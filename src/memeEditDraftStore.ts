import * as FileSystem from 'expo-file-system/legacy';

import { probeMedia, type MediaProbeResult } from '../modules/memeget-bg';
import {
  createDefaultImageProject,
  createDefaultVideoProject,
  validateMemeEditProject,
  type MediaEditKind,
  type MemeEditProject,
  type ProjectValidationError,
} from './memeEditProjectCore';
import { copyUriToCachePath } from './saf';

export const DRAFT_EXPIRY_MS = 7 * 24 * 60 * 60 * 1_000;
export const DRAFT_AUTOSAVE_DELAY_MS = 500;

const DRAFT_VERSION = 1;
const MAX_DRAFT_JSON_LENGTH = 4 * 1024 * 1024;
const MAX_SESSION_ID_LENGTH = 1_024;
const MAX_SOURCE_STRING_LENGTH = 4_096;

export interface MemeEditDraftSourceIdentity {
  stableId: string;
  uri: string;
  name: string;
  kind: MediaEditKind;
  width: number;
  height: number;
  durationUs: number | null;
  // null is an explicit "unknown" value. A null/non-null transition is a
  // mismatch, because silently treating unknown as a wildcard can bind a draft
  // to replaced provider content.
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
  // Compatibility aliases for the first journal slot.
  draft: string;
  temporary: string;
  slotA: string;
  slotB: string;
  temporaryA: string;
  temporaryB: string;
  ownedAssetPrefix: string;
}

export type MemeEditDraftRestoreResult =
  | { status: 'restored'; project: MemeEditProject; savedAtMs: number }
  | {
      status: 'rejected';
      reason: 'missing' | 'expired' | 'corrupt' | 'io-error' | 'source-mismatch';
    };

export interface MemeEditSourcePreparationIo {
  readonly cacheDirectory: string;
  materialize(source: string, destination: string): Promise<void>;
  remove(path: string): Promise<void>;
  probe(source: string): Promise<MediaProbeResult | null>;
}

export interface PreparedMemeEditSource {
  project: MemeEditProject;
  probe: MediaProbeResult | null;
  materializedSourceUri: string;
  owned: boolean;
}

export interface MemeEditSourceSessionSeed {
  sessionId: string;
  uri: string;
  name: string;
  indexedKind: MediaEditKind;
  modifiedTimeMs: number | null;
}

export interface PreparedMemeEditSourceSession extends PreparedMemeEditSource {
  identity: MemeEditDraftIdentity;
}

interface DraftPayload {
  version: 1;
  generation: number;
  savedAtMs: number;
  source: MemeEditDraftSourceIdentity;
  project: MemeEditProject;
}

interface SerializedDraft extends DraftPayload {
  checksum: string;
}

interface MemeEditAutosaveOptions {
  timers?: MemeEditTimers;
  onError?: (error: unknown) => void;
}

interface PreparedResource {
  uri: string;
  probe: MediaProbeResult | null;
  owned: boolean;
}

const SYSTEM_CLOCK: MemeEditDraftClock = { now: () => Date.now() };
const SYSTEM_TIMERS: MemeEditTimers = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

// Stores/controllers can be created independently for the same editing
// session. Keep every journal mutation on one recovered per-path tail so direct
// saves remain monotonic and discard cannot interleave. Settled tails remove
// themselves, bounding this map to active journal keys.
const JOURNAL_MUTATION_TAILS = new Map<string, Promise<void>>();

function serializeJournalMutation<T>(key: string, mutation: () => Promise<T>): Promise<T> {
  const previous = JOURNAL_MUTATION_TAILS.get(key) ?? Promise.resolve();
  const operation = previous.then(mutation);
  const recoveredTail = operation.then(
    () => undefined,
    () => undefined
  );
  JOURNAL_MUTATION_TAILS.set(key, recoveredTail);
  void recoveredTail.then(() => {
    if (JOURNAL_MUTATION_TAILS.get(key) === recoveredTail) {
      JOURNAL_MUTATION_TAILS.delete(key);
    }
  });
  return operation;
}

function isBoundedString(value: unknown, maximumLength = MAX_SOURCE_STRING_LENGTH): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximumLength;
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function assertIdentity(identity: MemeEditDraftIdentity): void {
  if (!isBoundedString(identity.sessionId, MAX_SESSION_ID_LENGTH)) {
    throw new Error(`Draft sessionId must contain 1-${MAX_SESSION_ID_LENGTH} characters.`);
  }
  const source = identity.source;
  if (
    !source ||
    !isBoundedString(source.stableId) ||
    !isBoundedString(source.uri) ||
    !isBoundedString(source.name) ||
    (source.kind !== 'image' && source.kind !== 'video') ||
    !Number.isSafeInteger(source.width) ||
    source.width <= 0 ||
    !Number.isSafeInteger(source.height) ||
    source.height <= 0 ||
    (source.durationUs !== null && !isNonNegativeSafeInteger(source.durationUs)) ||
    (source.byteSize !== null && !isNonNegativeSafeInteger(source.byteSize)) ||
    (source.modifiedTimeMs !== null && !isNonNegativeSafeInteger(source.modifiedTimeMs))
  ) {
    throw new Error('Draft source identity is malformed or unbounded.');
  }
}

// A bounded 128-bit token keeps source/session text out of cache filenames.
// The same mixer also detects torn/corrupted journal payloads; it is not used
// as a security boundary.
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

function cacheRoot(cacheDirectory: string): string {
  if (!cacheDirectory) throw new Error('Expo cache directory is unavailable.');
  return cacheDirectory.endsWith('/') ? cacheDirectory : `${cacheDirectory}/`;
}

export function draftStoragePaths(
  cacheDirectory: string,
  identity: MemeEditDraftIdentity
): MemeEditDraftStoragePaths {
  assertIdentity(identity);
  const directory = cacheRoot(cacheDirectory);
  const sessionToken = identityToken(identity.sessionId);
  const base = `${directory}meme_edit_draft_${sessionToken}`;
  const slotA = `${base}_a.json`;
  const slotB = `${base}_b.json`;
  return {
    draft: slotA,
    temporary: `${slotA}.tmp`,
    slotA,
    slotB,
    temporaryA: `${slotA}.tmp`,
    temporaryB: `${slotB}.tmp`,
    ownedAssetPrefix: `meme_work_${sessionToken}_`,
  };
}

function sourceIdentityMatches(
  saved: MemeEditDraftSourceIdentity,
  current: MemeEditDraftSourceIdentity
): boolean {
  return (
    saved.stableId === current.stableId &&
    saved.uri === current.uri &&
    saved.name === current.name &&
    saved.kind === current.kind &&
    saved.width === current.width &&
    saved.height === current.height &&
    saved.durationUs === current.durationUs &&
    saved.byteSize === current.byteSize &&
    saved.modifiedTimeMs === current.modifiedTimeMs
  );
}

function projectSourceMatchesIdentity(
  project: MemeEditProject,
  identity: MemeEditDraftSourceIdentity
): boolean {
  const source = project.source;
  return (
    source.uri === identity.uri &&
    source.name === identity.name &&
    source.kind === identity.kind &&
    source.width === identity.width &&
    source.height === identity.height &&
    source.durationUs === identity.durationUs
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactFields(value: Record<string, unknown>, fields: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  return actual.length === expected.length && actual.every((field, index) => field === expected[index]);
}

function parseSourceIdentity(value: unknown): MemeEditDraftSourceIdentity | null {
  if (
    !isRecord(value) ||
    !hasExactFields(value, [
      'stableId',
      'uri',
      'name',
      'kind',
      'width',
      'height',
      'durationUs',
      'byteSize',
      'modifiedTimeMs',
    ])
  ) {
    return null;
  }
  const candidate = value as unknown as MemeEditDraftSourceIdentity;
  try {
    assertIdentity({ sessionId: 'validation', source: candidate });
    return candidate;
  } catch {
    return null;
  }
}

function payloadText(payload: DraftPayload): string {
  return JSON.stringify({
    version: payload.version,
    generation: payload.generation,
    savedAtMs: payload.savedAtMs,
    source: payload.source,
    project: payload.project,
  });
}

function serializeDraft(payload: DraftPayload): string {
  return JSON.stringify({ ...payload, checksum: identityToken(payloadText(payload)) } satisfies SerializedDraft);
}

function parseSerializedDraft(text: string): SerializedDraft | null {
  if (text.length === 0 || text.length > MAX_DRAFT_JSON_LENGTH) return null;
  let input: unknown;
  try {
    input = JSON.parse(text);
  } catch {
    return null;
  }
  if (
    !isRecord(input) ||
    !hasExactFields(input, [
      'version',
      'generation',
      'savedAtMs',
      'source',
      'project',
      'checksum',
    ]) ||
    input.version !== DRAFT_VERSION ||
    !isNonNegativeSafeInteger(input.generation) ||
    input.generation < 1 ||
    !isNonNegativeSafeInteger(input.savedAtMs) ||
    typeof input.checksum !== 'string'
  ) {
    return null;
  }
  const source = parseSourceIdentity(input.source);
  if (!source) return null;
  const validation = validateMemeEditProject(input.project);
  if (!validation.ok) return null;
  const payload: DraftPayload = {
    version: 1,
    generation: input.generation,
    savedAtMs: input.savedAtMs,
    source,
    project: validation.value,
  };
  if (input.checksum !== identityToken(payloadText(payload))) return null;
  return { ...payload, checksum: input.checksum };
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

  private async readDraft(path: string): Promise<SerializedDraft | null> {
    try {
      const text = await this.io.readText(path);
      return text === null ? null : parseSerializedDraft(text);
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      throw new Error(`Could not read draft journal slot ${path}: ${detail}`);
    }
  }

  save(identity: MemeEditDraftIdentity, project: MemeEditProject): Promise<void> {
    const key = draftStoragePaths(this.io.cacheDirectory, identity).slotA;
    return serializeJournalMutation(key, () => this.saveUnlocked(identity, project));
  }

  private async saveUnlocked(
    identity: MemeEditDraftIdentity,
    project: MemeEditProject
  ): Promise<void> {
    assertIdentity(identity);
    const validation = validateMemeEditProject(project);
    if (!validation.ok) throw new Error(invalidProjectMessage(validation.errors));
    if (!projectSourceMatchesIdentity(validation.value, identity.source)) {
      throw new Error('Project source does not match the draft source identity.');
    }
    const savedAtMs = this.clock.now();
    if (!isNonNegativeSafeInteger(savedAtMs)) {
      throw new Error('Draft clock must return a non-negative safe integer in milliseconds.');
    }
    const snapshot: MemeEditProject = {
      ...validation.value,
      transient: { materializedSourceUri: null, maskTracks: {} },
    };
    const sanitizedValidation = validateMemeEditProject(snapshot);
    if (!sanitizedValidation.ok) throw new Error(invalidProjectMessage(sanitizedValidation.errors));

    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    const [slotA, slotB] = await Promise.all([
      this.readDraft(paths.slotA),
      this.readDraft(paths.slotB),
    ]);
    const generation = Math.max(slotA?.generation ?? 0, slotB?.generation ?? 0) + 1;
    if (!Number.isSafeInteger(generation)) throw new Error('Draft generation overflow.');
    const targetA = (slotA?.generation ?? 0) <= (slotB?.generation ?? 0);
    const target = targetA ? paths.slotA : paths.slotB;
    const temporary = targetA ? paths.temporaryA : paths.temporaryB;
    const serialized = serializeDraft({
      version: 1,
      generation,
      savedAtMs,
      source: identity.source,
      project: sanitizedValidation.value,
    });
    if (serialized.length > MAX_DRAFT_JSON_LENGTH) {
      throw new Error(`Invalid project snapshot: serialized draft exceeds ${MAX_DRAFT_JSON_LENGTH} characters.`);
    }

    try {
      await this.io.writeText(temporary, serialized);
      await this.io.replace(temporary, target);
    } finally {
      await this.io.remove(temporary).catch(() => {});
    }
  }

  async restore(identity: MemeEditDraftIdentity): Promise<MemeEditDraftRestoreResult> {
    assertIdentity(identity);
    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    const candidatePaths = [paths.slotA, paths.slotB, paths.temporaryA, paths.temporaryB];
    let foundBytes = false;
    let readError = false;
    const candidates: SerializedDraft[] = [];
    for (const path of candidatePaths) {
      try {
        const text = await this.io.readText(path);
        if (text === null) continue;
        foundBytes = true;
        const parsed = parseSerializedDraft(text);
        if (parsed) candidates.push(parsed);
      } catch {
        readError = true;
      }
    }
    if (readError) {
      return { status: 'rejected', reason: 'io-error' };
    }
    if (candidates.length === 0) {
      return { status: 'rejected', reason: foundBytes ? 'corrupt' : 'missing' };
    }
    candidates.sort(
      (left, right) => right.generation - left.generation || right.savedAtMs - left.savedAtMs
    );
    const draft = candidates[0];
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

  discard(identity: MemeEditDraftIdentity): Promise<void> {
    const key = draftStoragePaths(this.io.cacheDirectory, identity).slotA;
    return serializeJournalMutation(key, () => this.discardUnlocked(identity));
  }

  private async discardUnlocked(identity: MemeEditDraftIdentity): Promise<void> {
    const paths = draftStoragePaths(this.io.cacheDirectory, identity);
    let firstError: unknown = null;
    let entries: string[] = [];
    try {
      entries = await this.io.listCacheEntries();
    } catch (error) {
      firstError = error;
    }
    const directory = cacheRoot(this.io.cacheDirectory);
    const ownedAssets = entries
      .filter((name) => !name.includes('/') && name.startsWith(paths.ownedAssetPrefix))
      .sort()
      .map((name) => `${directory}${name}`);
    for (const path of [
      paths.slotA,
      paths.slotB,
      paths.temporaryA,
      paths.temporaryB,
      ...ownedAssets,
    ]) {
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
  private activeOperation: Promise<void> | null = null;
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
      void this.flush().catch((error) => {
        try {
          this.onError(error);
        } catch {
          // Error reporting is best-effort and must never create a second
          // unhandled rejection after the save failure is already contained.
        }
      });
    }, DRAFT_AUTOSAVE_DELAY_MS);
  }

  flush(): Promise<void> {
    if (this.timerHandle !== null) {
      this.timers.clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    const project = this.pendingProject;
    if (project === null) return this.activeOperation ?? this.queueTail;
    this.pendingProject = null;
    const operation = this.queueTail.then(() => this.store.save(this.identity, project)).catch((error) => {
      if (this.pendingProject === null) this.pendingProject = project;
      throw error;
    });
    this.activeOperation = operation;
    this.queueTail = operation.catch(() => {});
    void operation.then(
      () => {
        if (this.activeOperation === operation) this.activeOperation = null;
      },
      () => {
        if (this.activeOperation === operation) this.activeOperation = null;
      }
    );
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
    const pendingProject = this.pendingProject;
    const hadTimer = this.timerHandle !== null;
    if (this.timerHandle !== null) {
      this.timers.clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    try {
      await this.queueTail;
      await this.store.discard(this.identity);
      this.pendingProject = null;
      this.discarded = true;
    } catch (error) {
      this.pendingProject = pendingProject;
      if (hadTimer && pendingProject !== null && this.timerHandle === null) {
        this.timerHandle = this.timers.setTimeout(() => {
          this.timerHandle = null;
          void this.flush().catch((flushError) => {
            try {
              this.onError(flushError);
            } catch {
              // Error reporting is best-effort and must never create a second
              // unhandled rejection after the save failure is already contained.
            }
          });
        }, DRAFT_AUTOSAVE_DELAY_MS);
      }
      throw error;
    }
  }
}

export function requestSourceSessionClose(
  controller: { cancel(): Promise<void> },
  onClose: () => void,
  onError: (error: unknown) => void = () => {}
): void {
  onClose();
  void controller.cancel().catch((error) => {
    onError(error);
  });
}

export async function flushAutosaveBeforeSourceRelease(
  autosave: MemeEditAutosaveController | null,
  release: () => Promise<void>,
  onError: (error: unknown) => void = () => {}
): Promise<void> {
  if (autosave) {
    try {
      await autosave.flush();
    } catch (error) {
      onError(error);
      return;
    }
  }
  try {
    await release();
  } catch (error) {
    onError(error);
  }
}

interface SharedPreparationEntry {
  key: string;
  io: MemeEditSourcePreparationIo;
  promise: Promise<PreparedResource>;
  references: number;
  closing: boolean;
  finalization: Promise<void> | null;
}

interface PreparationLocation {
  key: string;
  destination: string;
  isFile: boolean;
}

// A session-owned destination is process-global even when callers construct
// multiple controllers. Coordinate it here so no controller can remove or
// overwrite another controller's in-flight/shared materialization.
const SHARED_SOURCE_PREPARATIONS = new Map<string, SharedPreparationEntry>();

function preparationLocation(
  io: MemeEditSourcePreparationIo,
  identity: MemeEditDraftIdentity
): PreparationLocation {
  const source = identity.source;
  const isFile = source.uri.startsWith('file://');
  if (isFile) {
    return {
      key: `unowned:${identityToken(
        JSON.stringify({
          stableId: source.stableId,
          uri: source.uri,
          name: source.name,
          kind: source.kind,
          width: source.width,
          height: source.height,
          durationUs: source.durationUs,
          byteSize: source.byteSize,
          modifiedTimeMs: source.modifiedTimeMs,
        })
      )}`,
      destination: source.uri,
      isFile: true,
    };
  }
  const extensionMatch = /\.([a-zA-Z0-9]{1,10})$/.exec(source.name);
  const extension = (
    extensionMatch?.[1] ?? (source.kind === 'video' ? 'mp4' : 'jpg')
  ).toLowerCase();
  const paths = draftStoragePaths(io.cacheDirectory, identity);
  const destination =
    `${cacheRoot(io.cacheDirectory)}${paths.ownedAssetPrefix}` +
    `materialized_source.${extension}`;
  return { key: destination, destination, isFile: false };
}

async function acquireSharedPreparation(
  io: MemeEditSourcePreparationIo,
  identity: MemeEditDraftIdentity
): Promise<SharedPreparationEntry> {
  const location = preparationLocation(io, identity);
  for (;;) {
    const existing = SHARED_SOURCE_PREPARATIONS.get(location.key);
    if (existing) {
      if (existing.closing) {
        await existing.finalization?.catch(() => {});
        continue;
      }
      existing.references += 1;
      return existing;
    }

    const source = identity.source;
    const operation = (async (): Promise<PreparedResource> => {
      if (location.isFile) {
        return { uri: source.uri, probe: await io.probe(source.uri), owned: false };
      }
      try {
        await io.remove(location.destination);
        await io.materialize(source.uri, location.destination);
        const probe = await io.probe(location.destination);
        return { uri: location.destination, probe, owned: true };
      } catch (error) {
        await io.remove(location.destination).catch(() => {});
        throw error;
      }
    })();
    const entry: SharedPreparationEntry = {
      key: location.key,
      io,
      promise: operation,
      references: 1,
      closing: false,
      finalization: null,
    };
    SHARED_SOURCE_PREPARATIONS.set(location.key, entry);
    void operation.catch(() => {
      if (SHARED_SOURCE_PREPARATIONS.get(location.key) === entry) {
        SHARED_SOURCE_PREPARATIONS.delete(location.key);
      }
    });
    return entry;
  }
}

async function releaseSharedPreparation(entry: SharedPreparationEntry): Promise<void> {
  if (entry.references <= 0) return;
  entry.references -= 1;
  if (entry.references > 0) return;
  entry.closing = true;
  const finalization = (async (): Promise<void> => {
    const prepared = await entry.promise.catch(() => null);
    if (prepared?.owned) await entry.io.remove(prepared.uri);
    if (SHARED_SOURCE_PREPARATIONS.get(entry.key) === entry) {
      SHARED_SOURCE_PREPARATIONS.delete(entry.key);
    }
  })();
  entry.finalization = finalization;
  try {
    await finalization;
  } catch (error) {
    entry.closing = false;
    entry.references = 1;
    entry.finalization = null;
    throw error;
  }
}

function projectFromPreparedProbe(uri: string, name: string, probe: MediaProbeResult): MemeEditProject {
  const width = probe.rotationDegrees === 90 || probe.rotationDegrees === 270 ? probe.height : probe.width;
  const height = probe.rotationDegrees === 90 || probe.rotationDegrees === 270 ? probe.width : probe.height;
  if (probe.kind === 'video') {
    if (probe.durationUs === null || probe.durationUs <= 0) {
      throw new Error('Prepared video source has no usable duration.');
    }
    return createDefaultVideoProject({ uri, name, width, height, durationUs: probe.durationUs });
  }
  return createDefaultImageProject({ uri, name, width, height });
}

export class MemeEditSourceSessionController {
  private preparation: Promise<PreparedMemeEditSourceSession> | null = null;
  private destination: string | null = null;
  private owned = false;
  private closed = false;
  private released = false;

  constructor(
    private readonly io: MemeEditSourcePreparationIo,
    private readonly seed: MemeEditSourceSessionSeed
  ) {
    if (!isBoundedString(seed.sessionId, MAX_SESSION_ID_LENGTH)) {
      throw new Error('Source session ID must be a bounded non-empty string.');
    }
    if (!isBoundedString(seed.uri) || !isBoundedString(seed.name)) {
      throw new Error('Source session URI and name must be bounded non-empty strings.');
    }
  }

  private destinationForSeed(): PreparationLocation {
    if (this.seed.uri.startsWith('file://')) {
      return { key: `unowned:${identityToken(this.seed.sessionId + this.seed.uri)}`, destination: this.seed.uri, isFile: true };
    }
    const extensionMatch = /\.([a-zA-Z0-9]{1,10})$/.exec(this.seed.name);
    const extension = (extensionMatch?.[1] ?? (this.seed.indexedKind === 'video' ? 'mp4' : 'jpg')).toLowerCase();
    const token = identityToken(JSON.stringify({
      sessionId: this.seed.sessionId,
      uri: this.seed.uri,
      name: this.seed.name,
      indexedKind: this.seed.indexedKind,
      modifiedTimeMs: this.seed.modifiedTimeMs,
    }));
    return {
      key: token,
      destination: `${cacheRoot(this.io.cacheDirectory)}meme_work_${token}_materialized_source.${extension}`,
      isFile: false,
    };
  }

  prepare(): Promise<PreparedMemeEditSourceSession> {
    if (this.closed) return Promise.reject(new Error('Source session was cancelled.'));
    if (this.preparation) return this.preparation;
    this.preparation = this.prepareUnlocked();
    return this.preparation;
  }

  private async prepareUnlocked(): Promise<PreparedMemeEditSourceSession> {
    const location = this.destinationForSeed();
    this.destination = location.destination;
    this.owned = !location.isFile;
    if (!location.isFile) {
      await this.io.remove(location.destination);
      await this.io.materialize(this.seed.uri, location.destination);
      if (this.closed) {
        await this.io.remove(location.destination).catch(() => {});
        throw new Error('Source session preparation was cancelled.');
      }
    }
    const probe = await this.io.probe(location.destination);
    if (this.closed) {
      if (!location.isFile) await this.io.remove(location.destination).catch(() => {});
      throw new Error('Source session preparation was cancelled.');
    }
    if (probe === null) throw new Error('Media probe is unavailable for this source.');
    const displayName = probe.displayName || this.seed.name;
    const project = projectFromPreparedProbe(this.seed.uri, displayName, probe);
    const identity: MemeEditDraftIdentity = {
      sessionId: this.seed.sessionId,
      source: {
        stableId: probe.stableId,
        uri: this.seed.uri,
        name: displayName,
        kind: project.source.kind,
        width: project.source.width,
        height: project.source.height,
        durationUs: project.source.durationUs,
        byteSize: probe.byteSize,
        modifiedTimeMs: this.seed.modifiedTimeMs ?? probe.modifiedTimeMs,
      },
    };
    assertIdentity(identity);
    return {
      identity,
      project: { ...project, transient: { ...project.transient, materializedSourceUri: location.destination } },
      probe,
      materializedSourceUri: location.destination,
      owned: !location.isFile,
    };
  }

  async cancel(): Promise<void> {
    this.closed = true;
    if (this.released) return;
    const prepared = this.preparation ? await this.preparation.catch(() => null) : null;
    const destination = this.destination ?? prepared?.materializedSourceUri ?? null;
    if (this.owned && destination) await this.io.remove(destination);
    this.released = true;
  }

  discard(): Promise<void> {
    return this.cancel();
  }
}

export class MemeEditSourcePreparationController {
  private entry: SharedPreparationEntry | null = null;
  private acquisition: Promise<SharedPreparationEntry> | null = null;
  private preparation: Promise<PreparedResource> | null = null;
  private closed = false;
  private released = false;

  constructor(
    private readonly io: MemeEditSourcePreparationIo,
    private readonly identity: MemeEditDraftIdentity
  ) {
    assertIdentity(identity);
  }

  private acquireEntry(): Promise<SharedPreparationEntry> {
    if (this.entry) return Promise.resolve(this.entry);
    if (this.acquisition) return this.acquisition;
    const acquisition = acquireSharedPreparation(this.io, this.identity).then((entry) => {
      this.entry = entry;
      return entry;
    });
    this.acquisition = acquisition;
    return acquisition;
  }

  private prepareResource(): Promise<PreparedResource> {
    if (this.preparation) return this.preparation;
    const operation = this.acquireEntry().then((entry) => entry.promise);
    this.preparation = operation;
    void operation.catch(() => {
      if (this.preparation === operation) {
        this.entry = null;
        this.acquisition = null;
        this.preparation = null;
      }
    });
    return operation;
  }

  async prepare(project: MemeEditProject): Promise<PreparedMemeEditSource> {
    if (this.closed) throw new Error('Source preparation controller is closed.');
    if (!projectSourceMatchesIdentity(project, this.identity.source)) {
      throw new Error('Project source does not match the preparation session source.');
    }
    const prepared = await this.prepareResource();
    if (this.closed) throw new Error('Source preparation was cancelled.');
    return {
      project: {
        ...project,
        transient: { ...project.transient, materializedSourceUri: prepared.uri },
      },
      probe: prepared.probe,
      materializedSourceUri: prepared.uri,
      owned: prepared.owned,
    };
  }

  async cancel(): Promise<void> {
    await this.discard();
  }

  async discard(): Promise<void> {
    this.closed = true;
    if (this.released) return;
    this.released = true;
    let entry = this.entry;
    if (!entry && this.acquisition) {
      entry = await this.acquisition.catch(() => null);
    }
    if (entry) await releaseSharedPreparation(entry);
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

export function createExpoMemeEditSourcePreparationIo(): MemeEditSourcePreparationIo {
  const cacheDirectory = FileSystem.cacheDirectory;
  if (!cacheDirectory) throw new Error('Expo cache directory is unavailable.');
  return {
    cacheDirectory,
    materialize: copyUriToCachePath,
    remove: (path) => FileSystem.deleteAsync(path, { idempotent: true }),
    probe: probeMedia,
  };
}
