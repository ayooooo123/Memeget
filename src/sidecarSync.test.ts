// A teachings.json written under another embedding model can't be restored by
// this build, and the pack format carries no source images to re-embed from —
// so it is the only copy of that teaching. Sync must set it aside intact before
// replacing it, and must never replace it on the strength of a damaged copy.
const files = new Map<string, string>();
const writes: string[] = [];
let failNextArchiveWrite = false;

jest.mock('./saf', () => ({
  listMedia: async () => [{ uri: 'content://f/document/a', name: 'a.jpg', kind: 'image' }],
  sidecarDir: async () => 'dir',
  listChildNames: async () => [...files.keys()],
  readSidecarFile: async (_dir: string, name: string) => files.get(name) ?? null,
  writeSidecarFile: async (_dir: string, name: string, text: string) => {
    if (failNextArchiveWrite && name.startsWith('teachings-')) {
      // SAF creates the document first; a write that dies leaves it partial.
      failNextArchiveWrite = false;
      files.set(name, text.slice(0, 10));
      throw new Error('write interrupted');
    }
    writes.push(name);
    files.set(name, text);
  },
}));
jest.mock('./db', () => ({
  getSidecarRows: async () => [],
  getExemplars: async () => [],
  getFolders: async () => [],
  getIndexModelMismatch: async () => null,
  importExemplars: async () => ({ added: 0 }),
  restoreSidecarMemes: async () => ({ added: 0, enriched: 0 }),
}));
jest.mock('./embeddingModels', () => ({
  PRIMARY_EMBEDDING_MODEL: { id: 'current-model', dim: 512 },
}));

import { syncFolderSidecar } from './sidecarSync';

const pack = (model: string) =>
  JSON.stringify({
    format: 'memeget-teaching-pack',
    version: 2,
    model,
    dim: 512,
    exemplars: [{ label: 'pepe', category: 'character', vector: [1, 0], positive: true }],
  });
const FOREIGN = pack('old-model');

beforeEach(() => {
  files.clear();
  writes.length = 0;
  failNextArchiveWrite = false;
});

describe('sidecar sync over a foreign-model teachings.json', () => {
  it('sets the foreign pack aside intact before replacing it', async () => {
    files.set('teachings.json', FOREIGN);
    await syncFolderSidecar('content://f', 'f');
    expect(files.get('teachings-old-model.json')).toBe(FOREIGN);
    expect(writes.indexOf('teachings-old-model.json')).toBeLessThan(writes.indexOf('teachings.json'));
  });

  it('keeps the original when the archive write dies, and repairs the archive next sync', async () => {
    files.set('teachings.json', FOREIGN);
    failNextArchiveWrite = true;

    await syncFolderSidecar('content://f', 'f');
    expect(files.get('teachings.json')).toBe(FOREIGN);
    expect(files.get('teachings-old-model.json')).not.toBe(FOREIGN); // partial

    await syncFolderSidecar('content://f', 'f');
    expect(files.get('teachings-old-model.json')).toBe(FOREIGN);
    expect(files.get('teachings.json')).not.toBe(FOREIGN);
  });

  it('replaces a same-model pack without archiving it', async () => {
    files.set('teachings.json', pack('current-model'));
    await syncFolderSidecar('content://f', 'f');
    expect(writes.some((w) => w.startsWith('teachings-'))).toBe(false);
    expect(writes).toContain('teachings.json');
  });
});
