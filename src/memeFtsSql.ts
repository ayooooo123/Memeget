// The FTS5 statements behind lexical search ranking, kept apart from db.ts for
// the same reason as memeSql.ts: db.ts imports expo-sqlite and most of the app,
// so nothing there can run in a unit test. These are plain strings, so a test
// can create the real virtual table in an in-memory SQLite and assert that a
// word appearing ONLY in a video's transcript is found and ranks the way the
// column weights say it should — the exact contract a careless edit to the
// column list or the bm25() weight vector would silently break.
//
// Three invariants a refactor must not violate:
//  1. The INSERT column order matches the CREATE column order (name, ocr,
//     caption, transcript, tags, extra_terms) — a swap indexes text into the
//     wrong field and quietly misranks everything.
//  2. The bm25() weight vector has one weight per column in that same order.
//  3. `transcript` is a first-class column, so speech in a video is findable
//     and copyable exactly like OCR or a caption.

export const MEME_SEARCH_FTS_DDL = `CREATE VIRTUAL TABLE IF NOT EXISTS meme_search_fts USING fts5(
        name,
        ocr,
        caption,
        transcript,
        tags,
        extra_terms,
        tokenize='unicode61'
      )`;

export const MEME_SEARCH_FTS_INSERT = `INSERT INTO meme_search_fts(rowid, name, ocr, caption, transcript, tags, extra_terms)
         VALUES (?, ?, ?, ?, ?, ?, ?)`;

// Column weights, in DDL order: name, ocr, caption, transcript, tags,
// extra_terms. Tags/extra-terms are the curated facet channel and outrank raw
// transcript/OCR; caption (the VLM's own words) sits just above transcript.
export const MEME_SEARCH_FTS_QUERY = `SELECT rowid AS id
     FROM meme_search_fts
     WHERE meme_search_fts MATCH ?
     ORDER BY bm25(meme_search_fts, 1.0, 1.2, 2.0, 1.8, 5.0, 4.0)
     LIMIT ?`;
