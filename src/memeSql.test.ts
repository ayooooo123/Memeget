// Executes the real statements from memeSql.ts against a real SQLite so the
// question "what survives an overwrite?" is answered by the database, not by
// reading the SQL and hoping.
//
// This exists because the insertMeme fix — stop discarding VLM captions and
// transcripts on re-index — is invisible to every other test, and a rebase
// across a busy db.ts is exactly the sort of thing that silently reverts it.
import { DatabaseSync } from 'node:sqlite';

import {
  INSERT_MEME_SQL,
  MEMES_BEFORE_WHERE,
  MEMES_TABLE_SQL,
  RESTORE_SIDECAR_MEME_SQL,
} from './memeSql';

type Row = Record<string, unknown>;

function freshDb(): DatabaseSync {
  const db = new DatabaseSync(':memory:');
  db.exec(MEMES_TABLE_SQL);
  return db;
}

// Mirrors insertMeme's bind order in db.ts.
function indexPass(
  db: DatabaseSync,
  o: Partial<{
    uri: string;
    name: string;
    kind: string;
    embedding: Uint8Array;
    visualEmbedding: Uint8Array | null;
    visualModel: string;
    ocr: string;
    tags: string;
    extraTerms: string;
    indexedAt: number;
    modifiedAt: number;
    visionState: string;
    audioState: string;
    thumbUri: string;
  }> = {}
): void {
  db.prepare(INSERT_MEME_SQL).run(
    o.uri ?? 'content://tree/doc/pepe.jpg',
    o.name ?? 'pepe.jpg',
    o.kind ?? 'image',
    o.embedding ?? new Uint8Array([1, 2, 3, 4]),
    o.visualEmbedding ?? null,
    o.visualModel ?? '',
    o.ocr ?? '',
    o.tags ?? '[]',
    o.extraTerms ?? '',
    o.indexedAt ?? 1000,
    o.modifiedAt ?? 1000,
    o.visionState ?? 'pending',
    o.audioState ?? 'none',
    o.thumbUri ?? ''
  );
}

function read(db: DatabaseSync, uri = 'content://tree/doc/pepe.jpg'): Row {
  return db.prepare('SELECT * FROM memes WHERE uri = ?').get(uri) as Row;
}

describe('insertMeme upsert', () => {
  it('inserts a new meme', () => {
    const db = freshDb();
    indexPass(db, { ocr: 'top text' });
    expect(read(db).ocr_text).toBe('top text');
    expect(read(db).pending).toBe(0);
  });

  it('KEEPS the caption, caption vector and transcript when a file is re-indexed', () => {
    // The whole point. An index pass re-derives embeddings, OCR and tags; it has
    // nothing to say about what the VLM described or what the audio said, and
    // those cost minutes of on-device model time to produce.
    const db = freshDb();
    indexPass(db);
    db.prepare(
      `UPDATE memes SET caption = ?, caption_embedding = ?, transcript = ?, vision_state = 'done', audio_state = 'done' WHERE uri = ?`
    ).run('a frog looks smug', new Uint8Array([9, 9]), 'i am the one who knocks', 'content://tree/doc/pepe.jpg');

    indexPass(db, { ocr: 're-scanned', indexedAt: 2000 });

    const row = read(db);
    expect(row.caption).toBe('a frog looks smug');
    expect(row.transcript).toBe('i am the one who knocks');
    expect(Array.from(row.caption_embedding as Uint8Array)).toEqual([9, 9]);
    // …while the index-derived columns DID refresh.
    expect(row.ocr_text).toBe('re-scanned');
    expect(row.indexed_at).toBe(2000);
  });

  it('keeps a described meme described, and re-queues an undescribed one', () => {
    const db = freshDb();
    indexPass(db);
    db.prepare(`UPDATE memes SET caption = 'x', vision_state = 'done' WHERE uri = ?`).run(
      'content://tree/doc/pepe.jpg'
    );
    indexPass(db, { visionState: 'pending' });
    expect(read(db).vision_state).toBe('done');

    const db2 = freshDb();
    indexPass(db2);
    db2.prepare(`UPDATE memes SET vision_state = 'failed' WHERE uri = ?`).run('content://tree/doc/pepe.jpg');
    indexPass(db2, { visionState: 'pending' });
    expect(read(db2).vision_state).toBe('pending');
  });

  it("treats 'done' with an empty transcript as a real answer, not a retry", () => {
    // A silent clip legitimately transcribes to nothing.
    const db = freshDb();
    indexPass(db, { kind: 'video' });
    db.prepare(`UPDATE memes SET audio_state = 'done', transcript = '' WHERE uri = ?`).run(
      'content://tree/doc/pepe.jpg'
    );
    indexPass(db, { kind: 'video', audioState: 'pending' });
    expect(read(db).audio_state).toBe('done');
  });

  it('keeps an existing poster when the new pass produced none', () => {
    const db = freshDb();
    indexPass(db, { thumbUri: 'file:///thumbs/1.jpg' });
    indexPass(db, { thumbUri: '' });
    expect(read(db).thumb_uri).toBe('file:///thumbs/1.jpg');
    indexPass(db, { thumbUri: 'file:///thumbs/2.jpg' });
    expect(read(db).thumb_uri).toBe('file:///thumbs/2.jpg');
  });

  it('promotes a pending placeholder to a real row', () => {
    const db = freshDb();
    db.prepare(
      `INSERT INTO memes (uri, name, kind, embedding, indexed_at, pending) VALUES (?, ?, ?, ?, ?, 1)`
    ).run('content://tree/doc/pepe.jpg', 'pepe.jpg', 'image', new Uint8Array(0), 500);
    indexPass(db);
    expect(read(db).pending).toBe(0);
  });
});

describe('sidecar restore upsert', () => {
  const base = {
    $uri: 'content://tree/doc/pepe.jpg',
    $name: 'pepe.jpg',
    $kind: 'image',
    $embedding: new Uint8Array([7, 7, 7, 7]),
    $visualEmbedding: null,
    $visualModel: '',
    $ocr: 'sidecar ocr',
    $caption: 'sidecar caption',
    $captionEmbedding: new Uint8Array([5, 5]),
    $transcript: 'sidecar transcript',
    $tags: '[{"label":"wojak"}]',
    $extraTerms: 'sidecar terms',
    $visionState: 'done',
    $audioState: 'done',
    $now: 3000,
    $modifiedAt: 2500,
    $pending: 0,
  };

  function restore(db: DatabaseSync, over: Partial<typeof base> = {}): void {
    db.prepare(RESTORE_SIDECAR_MEME_SQL).run({ ...base, ...over });
  }

  it('inserts knowledge for a meme the library has never seen', () => {
    const db = freshDb();
    restore(db);
    const row = read(db);
    expect(row.caption).toBe('sidecar caption');
    expect(row.transcript).toBe('sidecar transcript');
    expect(row.pending).toBe(0);
  });

  it('NEVER overwrites knowledge the live row already has', () => {
    // Restore is additive by contract: the user may have re-taught or
    // re-described something since the backup was written.
    const db = freshDb();
    indexPass(db, { ocr: 'live ocr', tags: '[{"label":"live"}]', extraTerms: 'live terms' });
    db.prepare(`UPDATE memes SET caption = 'live caption', transcript = 'live transcript' WHERE uri = ?`).run(
      base.$uri
    );

    restore(db);

    const row = read(db);
    expect(row.caption).toBe('live caption');
    expect(row.transcript).toBe('live transcript');
    expect(row.ocr_text).toBe('live ocr');
    expect(row.tags).toBe('[{"label":"live"}]');
    expect(row.extra_terms).toBe('live terms');
  });

  it('fills only the gaps on a partially-known row', () => {
    const db = freshDb();
    indexPass(db, { ocr: 'live ocr' }); // no caption, no transcript, empty tags
    restore(db);
    const row = read(db);
    expect(row.ocr_text).toBe('live ocr'); // kept
    expect(row.caption).toBe('sidecar caption'); // filled
    expect(row.transcript).toBe('sidecar transcript'); // filled
    expect(row.tags).toBe('[{"label":"wojak"}]'); // '[]' counted as empty
  });

  it('un-pends a placeholder that just received a usable vector', () => {
    const db = freshDb();
    db.prepare(
      `INSERT INTO memes (uri, name, kind, embedding, indexed_at, pending) VALUES (?, ?, ?, ?, ?, 1)`
    ).run(base.$uri, 'pepe.jpg', 'image', new Uint8Array(0), 500);
    restore(db);
    const row = read(db);
    expect(row.pending).toBe(0);
    expect(Array.from(row.embedding as Uint8Array)).toEqual([7, 7, 7, 7]);
  });

  it('leaves a vector-less restore pending so the indexer re-embeds it', () => {
    // A sidecar written under a different primary model restores its text but
    // not its vectors; the row must stay queued rather than look searchable.
    const db = freshDb();
    restore(db, { $embedding: new Uint8Array(0), $pending: 1 });
    const row = read(db);
    expect(row.pending).toBe(1);
    expect(row.caption).toBe('sidecar caption');
  });

  it('keeps a live embedding rather than taking the sidecar copy', () => {
    const db = freshDb();
    indexPass(db, { embedding: new Uint8Array([1, 1, 1, 1]) });
    restore(db);
    expect(Array.from(read(db).embedding as Uint8Array)).toEqual([1, 1, 1, 1]);
  });
});
describe('filtered recent pagination', () => {
  it('keeps the media-kind filter applied to both keyset branches', () => {
    const db = freshDb();
    indexPass(db, { uri: 'content://doc/image-new.jpg', kind: 'image', modifiedAt: 100 });
    indexPass(db, { uri: 'content://doc/video-cursor.mp4', kind: 'video', modifiedAt: 100 });
    indexPass(db, { uri: 'content://doc/image-old.jpg', kind: 'image', modifiedAt: 90 });
    indexPass(db, { uri: 'content://doc/video-old.mp4', kind: 'video', modifiedAt: 80 });

    const rows = db
      .prepare(
        `SELECT id, kind FROM memes
         WHERE ${MEMES_BEFORE_WHERE} AND kind = ?
         ORDER BY modified_at DESC, id DESC`
      )
      .all(100, 100, 2, 'video') as { id: number; kind: string }[];

    expect(rows.map((row) => row.kind)).toEqual(['video']);
    expect(rows.map((row) => row.id)).toEqual([4]);
  });
});
