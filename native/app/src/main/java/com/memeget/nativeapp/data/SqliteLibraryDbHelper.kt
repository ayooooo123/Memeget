package com.memeget.nativeapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class SqliteLibraryDbHelper(context: Context, dbName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT UNIQUE NOT NULL,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                ocr_text TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                transcript TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '[]',
                extra_terms TEXT NOT NULL DEFAULT '',
                thumb_uri TEXT NOT NULL DEFAULT '',
                indexed_at INTEGER NOT NULL,
                modified_at INTEGER NOT NULL DEFAULT 0,
                pending INTEGER NOT NULL DEFAULT 0,
                index_error TEXT NOT NULL DEFAULT '',
                vision_state TEXT NOT NULL DEFAULT 'pending',
                audio_state TEXT NOT NULL DEFAULT 'none',
                content_hash TEXT NOT NULL DEFAULT '',
                cloud_caption TEXT NOT NULL DEFAULT '',
                cloud_terms TEXT NOT NULL DEFAULT '',
                legacy_imported INTEGER NOT NULL DEFAULT 0,
                embedding BLOB,
                visual_embedding BLOB,
                visual_model TEXT NOT NULL DEFAULT '',
                caption_embedding BLOB,
                cloud_observation TEXT NOT NULL DEFAULT '',
                rejected_labels TEXT NOT NULL DEFAULT '[]',
                analysis_imported INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vectors (
                id INTEGER PRIMARY KEY,
                image BLOB NOT NULL,
                caption BLOB,
                visual BLOB,
                model TEXT NOT NULL DEFAULT '',
                visual_model TEXT NOT NULL DEFAULT ''
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS folders (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                added_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exemplars (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                category TEXT NOT NULL,
                vector BLOB NOT NULL,
                associations TEXT NOT NULL DEFAULT '[]',
                source_uri TEXT NOT NULL DEFAULT '',
                is_positive INTEGER NOT NULL DEFAULT 1,
                origin TEXT NOT NULL DEFAULT 'self',
                pack TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT 'MobileCLIP-S2:stock',
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS index_errors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT '',
                stage TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS content_hashes (
                hash TEXT PRIMARY KEY,
                uri TEXT NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS label_vectors (
                label TEXT PRIMARY KEY,
                model TEXT NOT NULL DEFAULT 'MobileCLIP-S2:stock',
                vector BLOB NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_memes_modified_id ON memes(modified_at DESC, id DESC);
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_memes_pending ON memes(pending);
            """.trimIndent()
        )

        try {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS meme_search_fts USING fts4(
                    name,
                    ocr,
                    caption,
                    transcript,
                    tags,
                    extra_terms,
                    tokenize=unicode61
                );
                """.trimIndent()
            )
        } catch (_: Exception) {
            // Fallback without explicit unicode61 if platform tokenizer differs
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS meme_search_fts USING fts4(
                    name,
                    ocr,
                    caption,
                    transcript,
                    tags,
                    extra_terms
                );
                """.trimIndent()
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive column migrations
        ensureColumns(db)
    }

    fun ensureColumns(db: SQLiteDatabase) {
        val existingCols = HashSet<String>()
        val cursor = db.rawQuery("PRAGMA table_info(memes)", null)
        cursor.use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                existingCols.add(c.getString(nameIdx))
            }
        }

        fun addCol(name: String, ddl: String) {
            if (!existingCols.contains(name)) {
                try {
                    db.execSQL("ALTER TABLE memes ADD COLUMN $name $ddl;")
                } catch (_: Exception) {}
            }
        }

        addCol("content_hash", "TEXT NOT NULL DEFAULT ''")
        addCol("cloud_caption", "TEXT NOT NULL DEFAULT ''")
        addCol("cloud_terms", "TEXT NOT NULL DEFAULT ''")
        addCol("legacy_imported", "INTEGER NOT NULL DEFAULT 0")
        addCol("visual_embedding", "BLOB")
        addCol("visual_model", "TEXT NOT NULL DEFAULT ''")
        addCol("caption_embedding", "BLOB")
        addCol("thumb_uri", "TEXT NOT NULL DEFAULT ''")
        addCol("cloud_observation", "TEXT NOT NULL DEFAULT ''")
        addCol("rejected_labels", "TEXT NOT NULL DEFAULT '[]'")
        addCol("index_error", "TEXT NOT NULL DEFAULT ''")
        addCol("analysis_imported", "INTEGER NOT NULL DEFAULT 0")
        // Ensure vectors table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vectors (
                id INTEGER PRIMARY KEY,
                image BLOB NOT NULL,
                caption BLOB,
                visual BLOB,
                model TEXT NOT NULL DEFAULT '',
                visual_model TEXT NOT NULL DEFAULT ''
            );
            """.trimIndent()
        )

        // Ensure indices
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memes_modified_id ON memes(modified_at DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memes_pending ON memes(pending);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exemplars_model ON exemplars(model);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_model ON vectors(model);")
    }

    companion object {
        const val DATABASE_NAME = "memeget.db"
        const val DATABASE_VERSION = 3
    }
}
