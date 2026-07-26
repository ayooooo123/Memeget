// The two statements that decide what survives when a meme row is written
// twice, plus the table they operate on.
//
// They live here, apart from db.ts, for one reason: db.ts imports expo-sqlite
// and most of the app, so nothing can execute its SQL in a unit test. These are
// plain strings with no imports, so a test can create the real table in an
// in-memory SQLite and assert what an overwrite actually does — which is the
// only way to catch the failure mode these statements exist to prevent, and the
// kind of change a bad merge can silently revert.
//
// Both are upserts rather than INSERT OR REPLACE. REPLACE deletes the old row
// and inserts a new one, so every column absent from the statement reverts to
// its default: an index re-scan wiped the VLM caption and the transcript, the
// two most expensive things the app ever computes and the two an index pass has
// nothing newer to say about.

// Note the shape includes thumb_uri. Older databases get it from an ALTER in
// initDb; new ones get it here, so a fresh install and a migrated one agree.
export const MEMES_TABLE_SQL = `CREATE TABLE IF NOT EXISTS memes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      uri TEXT UNIQUE NOT NULL,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      embedding BLOB NOT NULL,
      visual_embedding BLOB,
      visual_model TEXT NOT NULL DEFAULT '',
      ocr_text TEXT NOT NULL DEFAULT '',
      caption TEXT NOT NULL DEFAULT '',
      caption_embedding BLOB,
      transcript TEXT NOT NULL DEFAULT '',
      tags TEXT NOT NULL DEFAULT '[]',
      extra_terms TEXT NOT NULL DEFAULT '',
      vision_state TEXT NOT NULL DEFAULT 'pending',
      audio_state TEXT NOT NULL DEFAULT 'none',
      thumb_uri TEXT NOT NULL DEFAULT '',
      indexed_at INTEGER NOT NULL,
      modified_at INTEGER NOT NULL DEFAULT 0,
      pending INTEGER NOT NULL DEFAULT 0
    )`;

// What the indexer writes for a file it just processed. Refreshes everything an
// index pass genuinely re-derives; caption, caption_embedding and transcript are
// deliberately absent from the SET list, so they survive untouched.
export const INSERT_MEME_SQL = `INSERT INTO memes (uri, name, kind, embedding, visual_embedding, visual_model, ocr_text, tags, extra_terms, indexed_at, modified_at, vision_state, audio_state, thumb_uri)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(uri) DO UPDATE SET
       name = excluded.name,
       kind = excluded.kind,
       embedding = excluded.embedding,
       visual_embedding = excluded.visual_embedding,
       visual_model = excluded.visual_model,
       ocr_text = excluded.ocr_text,
       tags = excluded.tags,
       extra_terms = excluded.extra_terms,
       indexed_at = excluded.indexed_at,
       modified_at = excluded.modified_at,
       pending = 0,
       -- Keep a poster we already extracted when this pass didn't produce one;
       -- otherwise the row would point at nothing and the jpeg would be swept.
       thumb_uri = CASE WHEN excluded.thumb_uri = '' THEN memes.thumb_uri ELSE excluded.thumb_uri END,
       -- A described meme stays described; an undescribed one re-queues.
       vision_state = CASE WHEN memes.caption = '' THEN excluded.vision_state ELSE memes.vision_state END,
       -- 'done' with an empty transcript is a real answer (a silent clip), so
       -- it's the state, not the text, that decides whether to re-queue.
       audio_state = CASE WHEN memes.audio_state = 'done' THEN 'done' ELSE excluded.audio_state END`;

// What a `.memeget` restore writes. Strictly additive: every column is taken
// from the sidecar only where the live row has nothing, so restoring can never
// overwrite something the user has since re-taught or re-described.
export const RESTORE_SIDECAR_MEME_SQL = `INSERT INTO memes (uri, name, kind, embedding, visual_embedding, visual_model, ocr_text,
                          caption, caption_embedding, transcript, tags, extra_terms,
                          vision_state, audio_state, indexed_at, modified_at, pending)
       VALUES ($uri, $name, $kind, $embedding, $visualEmbedding, $visualModel, $ocr,
               $caption, $captionEmbedding, $transcript, $tags, $extraTerms,
               $visionState, $audioState, $now, $modifiedAt, $pending)
       ON CONFLICT(uri) DO UPDATE SET
         ocr_text = CASE WHEN memes.ocr_text = '' THEN excluded.ocr_text ELSE memes.ocr_text END,
         caption = CASE WHEN memes.caption = '' THEN excluded.caption ELSE memes.caption END,
         caption_embedding = CASE WHEN memes.caption_embedding IS NULL THEN excluded.caption_embedding ELSE memes.caption_embedding END,
         transcript = CASE WHEN memes.transcript = '' THEN excluded.transcript ELSE memes.transcript END,
         tags = CASE WHEN memes.tags IN ('', '[]') THEN excluded.tags ELSE memes.tags END,
         extra_terms = CASE WHEN memes.extra_terms = '' THEN excluded.extra_terms ELSE memes.extra_terms END,
         embedding = CASE WHEN length(memes.embedding) = 0 THEN excluded.embedding ELSE memes.embedding END,
         visual_embedding = CASE WHEN memes.visual_embedding IS NULL THEN excluded.visual_embedding ELSE memes.visual_embedding END,
         visual_model = CASE WHEN memes.visual_embedding IS NULL THEN excluded.visual_model ELSE memes.visual_model END,
         vision_state = CASE WHEN memes.caption = '' THEN excluded.vision_state ELSE memes.vision_state END,
         audio_state = CASE WHEN memes.audio_state = 'done' THEN 'done' ELSE excluded.audio_state END,
         modified_at = CASE WHEN memes.modified_at = 0 THEN excluded.modified_at ELSE memes.modified_at END,
         -- A placeholder that just received a usable vector is a real, indexed
         -- meme now; leaving pending = 1 would hide it from search and keep the
         -- indexer treating it as unfinished work.
         pending = CASE WHEN length(memes.embedding) > 0 OR length(excluded.embedding) > 0 THEN 0 ELSE memes.pending END`;
