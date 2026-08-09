// Runs the REAL FTS statements from memeFtsSql.ts against an in-memory SQLite,
// the same way memeSql.test.ts exercises the meme upsert. The point is to lock
// the transcript column into lexical search: a swap in the INSERT/CREATE column
// order or a shifted bm25() weight would still compile and still return SOME
// rows, so only executing the actual SQL catches it.
import { DatabaseSync } from 'node:sqlite';
import {
  MEME_SEARCH_FTS_DDL,
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
