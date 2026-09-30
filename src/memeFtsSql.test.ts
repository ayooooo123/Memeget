// Runs the REAL FTS statements from memeFtsSql.ts against an in-memory SQLite,
// the same way memeSql.test.ts exercises the meme upsert. The point is to lock
// the transcript column into lexical search: a swap in the INSERT/CREATE column
// order or a shifted bm25() weight would still compile and still return SOME
// rows, so only executing the actual SQL catches it.
import { DatabaseSync } from 'node:sqlite';
import {
  MEME_SEARCH_FTS_DDL,
  MEME_SEARCH_FTS_DELETE,
  MEME_SEARCH_FTS_INSERT,
  MEME_SEARCH_FTS_QUERY,
} from './memeFtsSql';

interface Row {
  id: number;
  name?: string;
  ocr?: string;
  caption?: string;
  transcript?: string;
  tags?: string;
  extra?: string;
}

function freshFts(rows: Row[]): DatabaseSync {
  const db = new DatabaseSync(':memory:');
  db.exec(MEME_SEARCH_FTS_DDL);
  const stmt = db.prepare(MEME_SEARCH_FTS_INSERT);
  for (const r of rows) {
    stmt.run(r.id, r.name ?? '', r.ocr ?? '', r.caption ?? '', r.transcript ?? '', r.tags ?? '', r.extra ?? '');
  }
  return db;
}

function search(db: DatabaseSync, match: string, limit = 100): number[] {
  return (db.prepare(MEME_SEARCH_FTS_QUERY).all(match, limit) as { id: number }[]).map((r) => r.id);
}

describe('meme_search_fts', () => {
  it('finds a word that appears only in a video transcript', () => {
    const db = freshFts([
      { id: 1, transcript: 'i am the one who knocks' },
      { id: 2, caption: 'a frog looks smug', ocr: 'hello world' },
    ]);
    expect(search(db, '"knocks"')).toEqual([1]);
  });

  it('routes each token into the field the column list claims', () => {
    // One distinct word per column; if the INSERT/CREATE order ever drifts, the
    // word lands in the wrong field and these single-column queries break.
    const db = freshFts([
      { id: 1, name: 'zname', ocr: 'zocr', caption: 'zcaption', transcript: 'ztranscript', tags: 'ztags', extra: 'zextra' },
    ]);
    expect(search(db, '{transcript} : ztranscript')).toEqual([1]);
    expect(search(db, '{transcript} : zcaption')).toEqual([]);
    expect(search(db, '{caption} : zcaption')).toEqual([1]);
    expect(search(db, '{tags} : ztags')).toEqual([1]);
  });

  it('ranks a curated-tag hit above the same word buried in a transcript', () => {
    // bm25 weights (tags 5.0 > transcript 1.8) are what make a deliberately
    // tagged meme beat one that merely happened to say the word out loud.
    const db = freshFts([
      { id: 1, transcript: 'pepe pepe pepe' },
      { id: 2, tags: 'pepe' },
    ]);
    expect(search(db, '"pepe"')).toEqual([2, 1]);
  });
});

// Re-indexing one row is DELETE + INSERT of its rowid. It has to leave the
// index in the state a whole-table rebuild would have produced — the app trusts
// BM25 ranking outright once it believes the index is current, so a per-row
// repair that left a stale copy behind (or dropped the row's neighbours) would
// misrank silently.
describe('per-row re-indexing', () => {
  const original: Row[] = [
    { id: 1, caption: 'a frog looks smug', tags: 'pepe' },
    { id: 2, caption: 'a doomer stares out of a window', tags: 'wojak' },
  ];
  const describedAgain: Row = { id: 2, caption: 'a soyjak points excitedly', tags: 'soyjak' };

  function reindex(db: DatabaseSync, row: Row): void {
    db.prepare(MEME_SEARCH_FTS_DELETE).run(row.id);
    db.prepare(MEME_SEARCH_FTS_INSERT).run(
      row.id,
      row.name ?? '',
      row.ocr ?? '',
      row.caption ?? '',
      row.transcript ?? '',
      row.tags ?? '',
      row.extra ?? ''
    );
  }

  it('replaces the row instead of duplicating it', () => {
    const db = freshFts(original);
    reindex(db, describedAgain);

    expect(search(db, '"soyjak"')).toEqual([2]);
    expect(search(db, '"doomer"')).toEqual([]); // the superseded text is gone
    expect(search(db, '"frog"')).toEqual([1]); // untouched rows survive
  });

  it('matches a full rebuild, ranking included', () => {
    const patched = freshFts(original);
    reindex(patched, describedAgain);
    const rebuilt = freshFts([original[0], describedAgain]);

    for (const query of ['"soyjak"', '"a"', '"pepe"', '"points"']) {
      expect(search(patched, query)).toEqual(search(rebuilt, query));
    }
  });

  it('retracts a row that has left the searchable set', () => {
    // A meme deleted, or bounced back to pending, is a DELETE with no INSERT.
    const db = freshFts(original);
    db.prepare(MEME_SEARCH_FTS_DELETE).run(2);

    expect(search(db, '"doomer"')).toEqual([]);
    expect(search(db, '"frog"')).toEqual([1]);
  });
});

// The repair writes in slices (FTS_WRITE_CHUNK rows per transaction) so it
// hands the event loop back mid-rebuild instead of holding the one SQLite
// connection for seconds. Committing in pieces must land the same index as one
// big transaction — if a boundary dropped or doubled rows, search would be
// wrong only for libraries past the chunk size, which is exactly the kind of
// bug that ships.
describe('writing the index in slices', () => {
  const CHUNK = 100;
  const library: Row[] = Array.from({ length: 250 }, (_, i) => ({
    id: i + 1,
    name: `meme_${i}.jpg`,
    caption: `caption number ${i}`,
    tags: i % 3 === 0 ? 'pepe' : 'wojak',
  }));

  function writeSliced(db: DatabaseSync, rows: Row[]): void {
    const del = db.prepare(MEME_SEARCH_FTS_DELETE);
    const ins = db.prepare(MEME_SEARCH_FTS_INSERT);
    for (let i = 0; i < rows.length; i += CHUNK) {
      // One transaction per slice, exactly as writeFtsRows does.
      db.exec('BEGIN');
      for (const row of rows.slice(i, i + CHUNK)) {
        del.run(row.id);
        ins.run(
          row.id,
          row.name ?? '',
          row.ocr ?? '',
          row.caption ?? '',
          row.transcript ?? '',
          row.tags ?? '',
          row.extra ?? ''
        );
      }
      db.exec('COMMIT');
    }
  }

  it('lands the same index as one unsliced write', () => {
    const sliced = new DatabaseSync(':memory:');
    sliced.exec(MEME_SEARCH_FTS_DDL);
    writeSliced(sliced, library);
    const whole = freshFts(library);

    expect(search(sliced, '"pepe"', 500)).toEqual(search(whole, '"pepe"', 500));
    expect(search(sliced, '"caption"', 500).length).toBe(250);
    expect(search(sliced, '"137"')).toEqual(search(whole, '"137"'));
  });

  it('re-indexes a slice-spanning set without duplicating boundary rows', () => {
    const db = freshFts(library);
    const rewritten = library.map((row) => ({ ...row, caption: `rewritten ${row.id}` }));
    writeSliced(db, rewritten);

    // Every row present exactly once, and no trace of the superseded captions.
    expect(search(db, '"rewritten"', 500).length).toBe(250);
    expect(search(db, '"number"', 500)).toEqual([]);
  });
});
