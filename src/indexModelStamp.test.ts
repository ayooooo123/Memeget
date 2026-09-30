// After a primary-model change the library keeps its old-space vectors until
// "Clear index" + re-index. An ordinary index pass (one shared meme) must not
// relabel that library as current: search warnings and the `.memeget` backup
// both decide from the stamp whether stored vectors are trustworthy. Runs the
// real db.ts + sidecarSync.ts against a real SQLite (node:sqlite behind an
// expo-sqlite-shaped adapter); only the folder I/O is faked.
import { DatabaseSync } from 'node:sqlite';

const tick = () => new Promise<void>((resolve) => setImmediate(resolve));

function expoShapedDb() {
  const raw = new DatabaseSync(':memory:');
  const db = {
    async execAsync(sql: string) {
      await tick();
      raw.exec(sql);
    },
    async runAsync(sql: string, ...params: never[]) {
      await tick();
      const r = raw.prepare(sql).run(...params);
      return { changes: Number(r.changes), lastInsertRowId: Number(r.lastInsertRowid) };
    },
    async getFirstAsync(sql: string, ...params: never[]) {
      await tick();
      return raw.prepare(sql).get(...params) ?? null;
    },
    async getAllAsync(sql: string, ...params: never[]) {
      await tick();
      return raw.prepare(sql).all(...params);
    },
    async prepareAsync(sql: string) {
      const stmt = raw.prepare(sql);
      return {
        async executeAsync(...params: never[]) {
          await tick();
          stmt.run(...params);
        },
        async finalizeAsync() {},
      };
    },
    async withTransactionAsync(task: () => Promise<void>) {
      raw.exec('BEGIN');
      try {
        await task();
        raw.exec('COMMIT');
      } catch (e) {
        raw.exec('ROLLBACK');
        throw e;
      }
    },
  };
  return { raw, db };
}

const sqlite = expoShapedDb();
jest.mock('expo-sqlite', () => ({ openDatabaseAsync: async () => sqlite.db }));

const FOLDER = 'content://tree/primary%3AMeme';
const URI = `${FOLDER}/document/primary%3AMeme%2Fold.jpg`;
const files = new Map<string, string>();
jest.mock('./saf', () => ({
  listMedia: async () => [{ uri: URI, name: 'old.jpg', kind: 'image' }],
  sidecarDir: async () => 'dir',
  listChildNames: async () => [...files.keys()],
  readSidecarFile: async (_dir: string, name: string) => files.get(name) ?? null,
  writeSidecarFile: async (_dir: string, name: string, text: string) => void files.set(name, text),
}));

import {
  INDEX_MODEL_KEY,
  clearIndex,
  getIndexModelMismatch,
  initDb,
  setSetting,
  stampIndexModel,
  vecToBlob,
} from './db';
import { syncFolderSidecar } from './sidecarSync';

function backedUpEmbedding(): string {
  const chunk = [...files.entries()].find(([name, text]) => name.startsWith('library-') && text.includes('old.jpg'));
  if (!chunk) throw new Error('no backup chunk holds old.jpg');
  return JSON.parse(chunk[1]).memes[0].embedding;
}

describe('index model stamp after a model change', () => {
  beforeAll(async () => {
    await initDb();
    await setSetting(INDEX_MODEL_KEY, 'retired-model@512');
    sqlite.raw
      .prepare(
        `INSERT INTO memes (uri, name, kind, embedding, indexed_at, modified_at, pending, caption)
         VALUES (?, 'old.jpg', 'image', ?, 1, 1, 0, 'a smug frog')`
      )
      .run(URI, vecToBlob([0.6, 0.8]));
  });

  it('an incremental index pass keeps the old stamp while old vectors remain, so backups drop them', async () => {
    await stampIndexModel(); // what runIndex / indexSavedFiles do before embedding a new share
    expect(await getIndexModelMismatch()).toMatchObject({ stored: 'retired-model@512' });

    await syncFolderSidecar(FOLDER, 'Meme');
    expect(backedUpEmbedding()).toBe('');
  });

  it('after Clear index the next pass claims the index for the running model', async () => {
    await clearIndex();
    await stampIndexModel();
    expect(await getIndexModelMismatch()).toBeNull();
  });
});
