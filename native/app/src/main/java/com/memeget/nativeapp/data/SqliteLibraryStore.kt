package com.memeget.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.memeget.nativeapp.data.FloatBlob
import com.memeget.nativeapp.data.SearchRanking
import com.memeget.nativeapp.data.SearchText
import com.memeget.nativeapp.data.SearchVocab
import com.memeget.nativeapp.data.SqliteLibraryDbHelper
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.data.TagPropagation
import com.memeget.nativeapp.inference.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue

internal class SqliteLibraryStore(val context: Context) : LibraryStore {

    private val dbHelper = SqliteLibraryDbHelper(context)
    private val writeMutex = Mutex()
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile private var cachedVocab: SearchVocab? = null
    @Volatile private var cachedVocabRevision: Long = -1L

    private fun bumpRevision() {
        _revision.value += 1L
    }

    private fun getReadableDb(): SQLiteDatabase = dbHelper.readableDatabase
    private fun getWritableDb(): SQLiteDatabase = dbHelper.writableDatabase

    private fun writeErrorLocked(db: SQLiteDatabase, name: String, stage: String, reason: String) {
        db.delete("index_errors", "name = ? AND stage = ?", arrayOf(name, stage))
        val cv = ContentValues().apply {
            put("name", name)
            put("stage", stage)
            put("reason", reason)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("index_errors", null, cv)
    }

    companion object {
        fun serializeTags(tags: List<Tag>): String {
            val array = JSONArray()
            for (t in tags) {
                val obj = JSONObject()
                obj.put("label", t.label)
                obj.put("category", t.category)
                obj.put("score", t.score.toDouble())
                obj.put("source", t.source)
                obj.put("scoreDomain", t.scoreDomain)
                obj.put("confirmation", t.confirmation)
                obj.put("provenance", t.provenance)
                array.put(obj)
            }
            return array.toString()
        }

        fun deserializeTags(json: String?): List<Tag> {
            if (json.isNullOrBlank() || json == "[]") return emptyList()
            val list = ArrayList<Tag>()
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        Tag(
                            label = obj.optString("label", ""),
                            category = obj.optString("category", "topic"),
                            score = obj.optDouble("score", 0.0).toFloat(),
                            source = obj.optString("source", "prompt"),
                            scoreDomain = obj.optString("scoreDomain", "uncalibrated"),
                            confirmation = obj.optString("confirmation", "unreviewed"),
                            provenance = obj.optString("provenance", ""),
                        )
                    )
                }
            } catch (_: Exception) {}
            return list
        }

        fun serializeStrings(list: List<String>): String {
            val arr = JSONArray()
            for (s in list) {
                if (s.isNotBlank()) arr.put(s.trim())
            }
            return arr.toString()
        }

        fun deserializeStrings(json: String?): List<String> {
            if (json.isNullOrBlank() || json == "[]") return emptyList()
            val list = ArrayList<String>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i).trim()
                    if (s.isNotBlank()) list.add(s)
                }
            } catch (_: Exception) {}
            return list
        }

        fun serializeCloudResult(res: CloudResult): String {
            val obj = JSONObject()
            obj.put("contentHash", res.contentHash)
            obj.put("model", res.model)
            obj.put("caption", res.caption)
            obj.put("terms", res.terms)
            obj.put("schemaVersion", res.schemaVersion)
            obj.put("promptHash", res.promptHash)
            obj.put("createdAt", res.createdAt)
            obj.put("expectedEvidence", res.expectedEvidence)
            obj.put("embeddingModel", res.embeddingModel)
            obj.put("expectedVectorHash", res.expectedVectorHash)
            if (res.providerConfidence != null) {
                obj.put("providerConfidence", res.providerConfidence.toDouble())
            }

            val probObj = JSONObject()
            for ((k, v) in res.probabilities) {
                probObj.put(k, v.toDouble())
            }
            obj.put("probabilities", probObj)

            val tagsArr = JSONArray()
            for (t in res.tags) {
                val tObj = JSONObject()
                tObj.put("label", t.label)
                tObj.put("category", t.category)
                tObj.put("score", t.score.toDouble())
                tObj.put("source", t.source)
                tObj.put("scoreDomain", t.scoreDomain)
                tObj.put("confirmation", t.confirmation)
                tObj.put("provenance", t.provenance)
                tagsArr.put(tObj)
            }
            obj.put("tags", tagsArr)
            return obj.toString()
        }

        fun deserializeCloudResult(json: String?): CloudResult? {
            if (json.isNullOrBlank() || json == "{}") return null
            return try {
                val obj = JSONObject(json)
                val probObj = obj.optJSONObject("probabilities")
                val probs = mutableMapOf<String, Float>()
                if (probObj != null) {
                    val keys = probObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        probs[k] = probObj.optDouble(k, 0.0).toFloat()
                    }
                }
                val tagsArr = obj.optJSONArray("tags")
                val tags = mutableListOf<Tag>()
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val tObj = tagsArr.getJSONObject(i)
                        tags.add(
                            Tag(
                                label = tObj.optString("label", ""),
                                category = tObj.optString("category", "topic"),
                                score = tObj.optDouble("score", 0.0).toFloat(),
                                source = tObj.optString("source", "cloud"),
                                scoreDomain = tObj.optString("scoreDomain", "uncalibrated"),
                                confirmation = tObj.optString("confirmation", "unreviewed"),
                                provenance = tObj.optString("provenance", ""),
                            )
                        )
                    }
                }
                val conf = if (obj.has("providerConfidence") && !obj.isNull("providerConfidence")) {
                    obj.optDouble("providerConfidence", 0.0).toFloat()
                } else null

                CloudResult(
                    contentHash = obj.optString("contentHash", ""),
                    model = obj.optString("model", ""),
                    caption = obj.optString("caption", ""),
                    tags = tags,
                    terms = obj.optString("terms", ""),
                    probabilities = probs,
                    providerConfidence = conf,
                    schemaVersion = obj.optInt("schemaVersion", 1),
                    promptHash = obj.optString("promptHash", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    expectedEvidence = obj.optString("expectedEvidence", ""),
                    embeddingModel = obj.optString("embeddingModel", ""),
                    expectedVectorHash = obj.optString("expectedVectorHash", ""),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun cursorToMeme(c: Cursor): Meme {
            val id = c.getLong(c.getColumnIndexOrThrow("id"))
            val uri = c.getString(c.getColumnIndexOrThrow("uri"))
            val name = c.getString(c.getColumnIndexOrThrow("name"))
            val kind = c.getString(c.getColumnIndexOrThrow("kind"))
            val ocr = c.getString(c.getColumnIndexOrThrow("ocr_text")) ?: ""
            val caption = c.getString(c.getColumnIndexOrThrow("caption")) ?: ""
            val transcript = c.getString(c.getColumnIndexOrThrow("transcript")) ?: ""
            val tagsJson = c.getString(c.getColumnIndexOrThrow("tags")) ?: "[]"
            val extraTerms = c.getString(c.getColumnIndexOrThrow("extra_terms")) ?: ""
            val thumbUri = c.getString(c.getColumnIndexOrThrow("thumb_uri")) ?: ""
            val modifiedAt = c.getLong(c.getColumnIndexOrThrow("modified_at"))
            val pending = c.getInt(c.getColumnIndexOrThrow("pending")) != 0
            val visionState = c.getString(c.getColumnIndexOrThrow("vision_state")) ?: "pending"
            val audioState = c.getString(c.getColumnIndexOrThrow("audio_state")) ?: "none"
            val contentHash = c.getString(c.getColumnIndexOrThrow("content_hash")) ?: ""
            val cloudCaption = c.getString(c.getColumnIndexOrThrow("cloud_caption")) ?: ""
            val cloudTerms = c.getString(c.getColumnIndexOrThrow("cloud_terms")) ?: ""
            val legacyImported = c.getInt(c.getColumnIndexOrThrow("legacy_imported")) != 0

            val cloudObsCol = c.getColumnIndex("cloud_observation")
            val cloudObsJson = if (cloudObsCol >= 0 && !c.isNull(cloudObsCol)) c.getString(cloudObsCol) else null

            val rejCol = c.getColumnIndex("rejected_labels")
            val rejJson = if (rejCol >= 0 && !c.isNull(rejCol)) c.getString(rejCol) else null

            return Meme(
                id = id,
                uri = uri,
                name = name,
                kind = kind,
                ocr = ocr,
                caption = caption,
                transcript = transcript,
                tags = deserializeTags(tagsJson),
                extraTerms = extraTerms,
                thumbUri = thumbUri,
                modifiedAt = modifiedAt,
                pending = pending,
                visionState = visionState,
                audioState = audioState,
                contentHash = contentHash,
                cloudCaption = cloudCaption,
                cloudTerms = cloudTerms,
                legacyImported = legacyImported,
                cloudObservation = deserializeCloudResult(cloudObsJson),
                rejectedLabels = deserializeStrings(rejJson),
                indexError = c.getString(c.getColumnIndexOrThrow("index_error")) ?: "",
            )
        }

        fun updateFtsRow(
            db: SQLiteDatabase,
            id: Long,
            name: String,
            ocr: String,
            caption: String,
            transcript: String,
            tags: List<Tag>,
            extraTerms: String,
            cloudCaption: String = "",
            cloudTerms: String = "",
        ) {
            val tagLabels = tags.joinToString(" ") { it.label }
            val fullCaption = if (cloudCaption.isNotBlank()) {
                if (caption.isNotBlank()) "$caption $cloudCaption" else cloudCaption
            } else caption
            val fullExtra = if (cloudTerms.isNotBlank()) {
                if (extraTerms.isNotBlank()) "$extraTerms $cloudTerms" else cloudTerms
            } else extraTerms

            db.execSQL("DELETE FROM meme_search_fts WHERE rowid = ?", arrayOf(id))
            db.execSQL(
                "INSERT INTO meme_search_fts (rowid, name, ocr, caption, transcript, tags, extra_terms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(id, name, ocr, fullCaption, transcript, tagLabels, fullExtra)
            )
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun getSearchVocab(): SearchVocab {
        val rev = _revision.value
        val cur = cachedVocab
        if (cur != null && cachedVocabRevision == rev) return cur
        val db = getReadableDb()
        val coroutine = currentCoroutineContext()
        val signal = CancellationSignal()
        val cancellation = coroutine.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) signal.cancel()
        }
        try {
            val vocab = SearchVocab()
            val texts = ArrayList<String>(256)
            var rows = 0
            db.rawQuery(
                "SELECT name, ocr_text, caption, transcript, extra_terms, cloud_caption, cloud_terms, tags FROM memes",
                null, signal,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    for (column in 0..6) {
                        val text = cursor.getString(column).orEmpty()
                        if (text.isNotBlank()) texts.add(text)
                    }
                    for (tag in deserializeTags(cursor.getString(7))) {
                        if (tag.label.isNotBlank()) texts.add(tag.label)
                    }
                    if (++rows % 32 == 0) {
                        coroutine.ensureActive()
                        vocab.addTexts(texts)
                        texts.clear()
                    }
                }
            }
            coroutine.ensureActive()
            if (texts.isNotEmpty()) vocab.addTexts(texts)
            cachedVocab = vocab
            cachedVocabRevision = rev
            return vocab
        } catch (failure: RuntimeException) {
            coroutine.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }
    }

    private fun isManualPositive(tag: Tag): Boolean =
        tag.label.isNotBlank() && tag.confirmation == "confirmed" && !tag.provenance.startsWith("legacy-") &&
            (tag.source == "manual" || tag.source == "cloud" || tag.source == "cloud_hint")

    suspend fun getManualPositiveVectors(): Map<String, List<FloatArray>> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val out = LinkedHashMap<String, MutableList<FloatArray>>()
        val cursor = db.rawQuery(
            """
            SELECT m.tags, v.image
            FROM memes m
            JOIN vectors v ON m.id = v.id
            WHERE m.pending = 0 AND v.model = ? AND (
                m.tags LIKE '%"source":"manual"%' OR m.tags LIKE '%"source":"cloud"%' OR m.tags LIKE '%"source":"cloud_hint"%'
            )
            ORDER BY m.id ASC
            """.trimIndent(),
            arrayOf(ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val tags = deserializeTags(c.getString(0))
                val imgBytes = c.getBlob(1) ?: continue
                val vec = FloatBlob.fromBytes(imgBytes)
                if (vec.isEmpty()) continue
                for (t in tags) {
                    if (isManualPositive(t)) {
                        val norm = TagMerge.normalizeTagLabel(t.label)
                        val list = out.getOrPut(norm) { ArrayList() }
                        if (list.size < 16) {
                            list.add(vec)
                        }
                    }
                }
            }
        }
        out
    }

    suspend fun getManualTagStamp(): String = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val sb = StringBuilder()
        val cursor = db.rawQuery(
            """SELECT id, tags FROM memes WHERE tags LIKE '%"source":"manual"%' OR tags LIKE '%"source":"cloud"%' OR tags LIKE '%"source":"cloud_hint"%' ORDER BY id ASC""",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val tags = deserializeTags(c.getString(1))
                for (t in tags) {
                    if (isManualPositive(t)) {
                        sb.append(id).append(':').append(TagMerge.normalizeTagLabel(t.label)).append(';')
                    }
                }
            }
        }
        sb.toString()
    }

    override suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val list = ArrayList<Folder>()
        val cursor = db.rawQuery("SELECT uri, name, added_at FROM folders ORDER BY added_at ASC", null)
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    Folder(
                        uri = c.getString(0),
                        name = c.getString(1),
                        addedAt = c.getLong(2),
                    )
                )
            }
        }
        list
    }

    override suspend fun addFolder(uri: String, name: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("uri", uri)
                    put("name", name)
                    put("added_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun removeFolder(uri: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.delete("folders", "uri = ?", arrayOf(uri))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun insertPending(
        uri: String,
        name: String,
        kind: String,
        modifiedAt: Long,
        contentHash: String,
    ): Long = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val existingCursor = db.rawQuery("SELECT id, content_hash FROM memes WHERE uri = ?", arrayOf(uri))
                val (existingId, existingHash) = existingCursor.use {
                    if (it.moveToNext()) Pair(it.getLong(0), it.getString(1) ?: "") else Pair(null, null)
                }
                if (existingId != null) {
                    if (contentHash.isNotBlank() && contentHash != existingHash) {
                        val updateCv = ContentValues().apply {
                            put("content_hash", contentHash)
                            put("modified_at", if (modifiedAt > 0L) modifiedAt else System.currentTimeMillis())
                            put("pending", 1)
                            put("index_error", "")
                            put("vision_state", "pending")
                        }
                        db.update("memes", updateCv, "id = ?", arrayOf(existingId.toString()))
                        val hashCv = ContentValues().apply {
                            put("hash", contentHash)
                            put("uri", uri)
                        }
                        db.insertWithOnConflict("content_hashes", null, hashCv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                    return@withLock existingId
                }

                val now = System.currentTimeMillis()
                val mTime = if (modifiedAt > 0L) modifiedAt else now
                val cv = ContentValues().apply {
                    put("uri", uri)
                    put("name", name)
                    put("kind", kind)
                    put("indexed_at", now)
                    put("modified_at", mTime)
                    put("pending", 1)
                    put("vision_state", "pending")
                    put("audio_state", if (kind == "video") "pending" else "none")
                    put("content_hash", contentHash)
                }
                val id = db.insertWithOnConflict("memes", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (contentHash.isNotBlank()) {
                    val hashCv = ContentValues().apply {
                        put("hash", contentHash)
                        put("uri", uri)
                    }
                    db.insertWithOnConflict("content_hashes", null, hashCv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
                id
            } finally {
                db.endTransaction()
            }
        }.also {
            bumpRevision()
        }
    }

    override suspend fun meme(id: Long): Meme? = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val cursor = db.rawQuery("SELECT * FROM memes WHERE id = ?", arrayOf(id.toString()))
        cursor.use { c ->
            if (c.moveToNext()) cursorToMeme(c) else null
        }
    }

    override suspend fun memeByUri(uri: String): Meme? = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val cursor = db.rawQuery("SELECT * FROM memes WHERE uri = ?", arrayOf(uri))
        cursor.use { c ->
            if (c.moveToNext()) cursorToMeme(c) else null
        }
    }

    override suspend fun page(
        limit: Int,
        after: PageCursor?,
        kind: String?,
        tag: String?,
    ): List<Meme> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val whereClauses = ArrayList<String>()
        val args = ArrayList<String>()

        if (after != null) {
            whereClauses.add("(modified_at < ? OR (modified_at = ? AND id < ?))")
            args.add(after.modifiedAt.toString())
            args.add(after.modifiedAt.toString())
            args.add(after.id.toString())
        }
        if (!kind.isNullOrBlank()) {
            whereClauses.add("kind = ?")
            args.add(kind)
        }
        if (!tag.isNullOrBlank()) {
            whereClauses.add("tags LIKE ?")
            args.add("%\"label\":\"$tag\"%")
        }

        val whereSql = if (whereClauses.isNotEmpty()) "WHERE " + whereClauses.joinToString(" AND ") else ""
        val sql = "SELECT * FROM memes $whereSql ORDER BY modified_at DESC, id DESC LIMIT ?"
        args.add(limit.toString())

        val list = ArrayList<Meme>()
        val cursor = db.rawQuery(sql, args.toTypedArray())
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMeme(c))
            }
        }
        list
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun search(
        text: String,
        vector: FloatArray?,
        kind: String?,
        tag: String?,
        limit: Int,
        after: SearchCursor?,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        require(limit in 1..256) { "Search page size must be 1–256" }
        val db = getReadableDb()
        val cleanQuery = text.trim()
        val semantic = vector != null && vector.isNotEmpty()
        if (cleanQuery.isEmpty() && !semantic) {
            return@withContext page(limit, after?.let { PageCursor(it.modifiedAt, it.id) }, kind, tag)
                .map { SearchHit(it, 1f) }
        }
        val coroutine = currentCoroutineContext()
        val exactTerms = SearchText.searchTermsForText(cleanQuery, keepShort = !semantic)
        val expandedTerms = if (exactTerms.isEmpty()) emptyList()
            else getSearchVocab().expand(exactTerms, lastIsPrefix = !text.endsWith(" "))
        val ftsQuery = SearchRanking.buildFtsMatchQuery(exactTerms, expandedTerms)
        val phrase = SearchText.phraseKey(cleanQuery)
        val hasPhrase = SearchText.phraseTokenCount(phrase) >= 2
        val comparator = compareBy<SearchHit>({ it.score }, { it.meme.modifiedAt }, { it.meme.id })
        val top = PriorityQueue(limit, comparator)
        val signal = CancellationSignal()
        val cancellation = coroutine.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) signal.cancel()
        }
        val emptyVector = FloatArray(0)
        val tagKey = tag?.takeIf { it.isNotBlank() }?.let(TagMerge::normalizeTagLabel)
        try {
            fun scan(useFts: Boolean) {
                val args = ArrayList<String>()
                val filters = ArrayList<String>()
                val rank: String
                val from: String
                if (useFts && semantic) {
                    // Correlated FTS lookup avoids materializing every lexical hit or a huge IN list.
                    rank = "(SELECT matchinfo(meme_search_fts, 'pcx') FROM meme_search_fts WHERE rowid = m.id AND meme_search_fts MATCH ?) AS fts_rank"
                    args.add(ftsQuery)
                    from = "memes m LEFT JOIN vectors v ON m.id = v.id"
                } else if (useFts) {
                    rank = "matchinfo(meme_search_fts, 'pcx') AS fts_rank"
                    from = "meme_search_fts JOIN memes m ON m.id = meme_search_fts.rowid LEFT JOIN vectors v ON m.id = v.id"
                    filters.add("meme_search_fts MATCH ?")
                    args.add(ftsQuery)
                } else {
                    rank = "NULL AS fts_rank"
                    from = "memes m LEFT JOIN vectors v ON m.id = v.id"
                    if (!semantic && exactTerms.isNotEmpty()) {
                        val fields = listOf("ocr_text", "name", "caption", "transcript", "tags", "extra_terms", "cloud_caption", "cloud_terms")
                        filters.add(exactTerms.joinToString(" OR ", "(", ")") {
                            fields.joinToString(" OR ", "(", ")") { field ->
                                args.add("%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%")
                                "m.$field LIKE ? ESCAPE '\\'"
                            }
                        })
                    }
                }
                if (!kind.isNullOrBlank()) {
                    filters.add("m.kind = ?")
                    args.add(kind)
                }
                val where = if (filters.isEmpty()) "" else " WHERE ${filters.joinToString(" AND ")}"
                val sql = "SELECT m.*, v.image, v.caption AS caption_vec, v.model AS vec_model, $rank FROM $from$where"
                db.rawQuery(sql, args.toTypedArray(), signal).use { cursor ->
                    val imageColumn = cursor.getColumnIndexOrThrow("image")
                    val captionColumn = cursor.getColumnIndexOrThrow("caption_vec")
                    val modelColumn = cursor.getColumnIndexOrThrow("vec_model")
                    val rankColumn = cursor.getColumnIndexOrThrow("fts_rank")
                    var count = 0
                    while (cursor.moveToNext()) {
                        if (++count % 32 == 0) coroutine.ensureActive()
                        val meme = cursorToMeme(cursor)
                        if (tagKey != null && meme.tags.none { TagMerge.normalizeTagLabel(it.label) == tagKey }) continue
                        val labels = meme.tags.map { it.label }
                        val compatible = semantic && cursor.getString(modelColumn) == ModelRegistry.PRIMARY_MODEL_FINGERPRINT
                        val image = if (compatible && !cursor.isNull(imageColumn)) FloatBlob.fromBytes(cursor.getBlob(imageColumn)) else emptyVector
                        val caption = if (compatible && !cursor.isNull(captionColumn)) FloatBlob.fromBytes(cursor.getBlob(captionColumn)) else null
                        val document = SearchText.assembleSearchText(
                            meme.ocr, meme.name, meme.caption, meme.transcript, labels, meme.extraTerms,
                            meme.cloudCaption, meme.cloudTerms,
                        )
                        var score = SearchRanking.scoreEntry(vector, exactTerms, expandedTerms, image, caption, document)
                        score += SearchRanking.tagTermScore(labels, exactTerms)
                        if (!cursor.isNull(rankColumn)) score += SearchRanking.calculateFts4Bm25(cursor.getBlob(rankColumn)) * 0.5f
                        if (hasPhrase && SearchText.containsPhrase(SearchText.phraseKey(document), phrase)) score += 10f
                        if (!score.isFinite()) continue
                        if (after != null) {
                            val comparison = compareValues(score, after.score).takeIf { it != 0 }
                                ?: compareValues(meme.modifiedAt, after.modifiedAt).takeIf { it != 0 }
                                ?: compareValues(meme.id, after.id)
                            if (comparison >= 0) continue
                        }
                        val candidate = SearchHit(meme, score)
                        if (top.size < limit) top.offer(candidate)
                        else if (comparator.compare(candidate, top.peek()) > 0) {
                            top.poll()
                            top.offer(candidate)
                        }
                    }
                }
            }
            var useFts = ftsQuery.isNotBlank()
            if (useFts && !semantic) {
                val args = arrayListOf(ftsQuery)
                val kindClause = if (kind.isNullOrBlank()) "" else {
                    args.add(kind)
                    " AND m.kind = ?"
                }
                useFts = db.rawQuery(
                    "SELECT m.tags FROM meme_search_fts JOIN memes m ON m.id = meme_search_fts.rowid WHERE meme_search_fts MATCH ?$kindClause",
                    args.toTypedArray(), signal,
                ).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        coroutine.ensureActive()
                        if (tagKey == null || deserializeTags(cursor.getString(0)).any { TagMerge.normalizeTagLabel(it.label) == tagKey }) {
                            found = true
                            break
                        }
                    }
                    found
                }
            }
            scan(useFts)
            val rows = ArrayList<SearchHit>(top.size)
            while (top.isNotEmpty()) rows.add(top.poll())
            rows.reverse()
            rows
        } catch (failure: RuntimeException) {
            coroutine.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun similar(id: Long, limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val targetVectors = vectors(id) ?: return@withContext emptyList()
        if (targetVectors.image.isEmpty() || targetVectors.model != ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
            return@withContext emptyList()
        }

        val db = getReadableDb()
        val targetRecord = TagPropagation.VisualRecord(
            imageEmbedding = targetVectors.image,
            imageModel = targetVectors.model,
            visualEmbedding = targetVectors.visual,
            visualModel = targetVectors.visualModel,
        )

        data class ScoredSimilar(val meme: Meme, val score: Float)
        val candidateComparator = compareBy<ScoredSimilar>({ it.score }, { it.meme.modifiedAt }, { it.meme.id })
        val kCapacity = minOf(256, maxOf(1, limit))
        val topKHeap = PriorityQueue<ScoredSimilar>(kCapacity + 1, candidateComparator)

        val coroutine = currentCoroutineContext()
        val cancellationSignal = CancellationSignal()
        val cancellation = coroutine.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) cancellationSignal.cancel()
        }
        try {
            db.rawQuery(
                """
                SELECT m.*, v.image, v.caption as caption_vec, v.visual, v.visual_model, v.model as vec_model
                FROM memes m
                JOIN vectors v ON m.id = v.id
                WHERE m.id != ? AND m.pending = 0 AND v.model = ?
                """.trimIndent(),
                arrayOf(id.toString(), ModelRegistry.PRIMARY_MODEL_FINGERPRINT),
                cancellationSignal,
            ).use { c ->
                var rowCount = 0
                val imgCol = c.getColumnIndexOrThrow("image")
                val visCol = c.getColumnIndexOrThrow("visual")
                val visModCol = c.getColumnIndexOrThrow("visual_model")

                while (c.moveToNext()) {
                    rowCount++
                    if (rowCount % 32 == 0) coroutine.ensureActive()

                    val meme = cursorToMeme(c)
                    val imgBlob = if (!c.isNull(imgCol)) c.getBlob(imgCol) else null ?: continue
                    val visBlob = if (!c.isNull(visCol)) c.getBlob(visCol) else null
                    val visModel = if (!c.isNull(visModCol)) c.getString(visModCol) ?: "" else ""

                    val candRecord = TagPropagation.VisualRecord(
                        imageEmbedding = FloatBlob.fromBytes(imgBlob),
                        imageModel = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
                        visualEmbedding = if (visBlob != null) FloatBlob.fromBytes(visBlob) else null,
                        visualModel = visModel,
                    )

                    val (a, b, _) = TagPropagation.selectPairVectors(targetRecord, candRecord)
                    if (a.isNotEmpty() && a.size == b.size) {
                        val score = FloatBlob.dot(a, b)
                        val cand = ScoredSimilar(meme, score)
                        if (topKHeap.size < kCapacity) {
                            topKHeap.offer(cand)
                        } else if (candidateComparator.compare(cand, topKHeap.peek()!!) > 0) {
                            topKHeap.poll()
                            topKHeap.offer(cand)
                        }
                    }
                }
            }
        } catch (failure: RuntimeException) {
            coroutine.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }

        val sortedList = ArrayList<ScoredSimilar>(topKHeap.size)
        while (topKHeap.isNotEmpty()) {
            sortedList.add(topKHeap.poll()!!)
        }
        sortedList.reverse()
        sortedList.take(limit).map { SearchHit(it.meme, it.score) }
    }

    override suspend fun vectors(id: Long): VectorRecord? = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val cursor = db.rawQuery("SELECT image, caption, visual, model, visual_model FROM vectors WHERE id = ?", arrayOf(id.toString()))
        cursor.use { c ->
            if (c.moveToNext()) {
                val imgBytes = c.getBlob(0) ?: return@withContext null
                val capBytes = if (!c.isNull(1)) c.getBlob(1) else null
                val visBytes = if (!c.isNull(2)) c.getBlob(2) else null
                val model = c.getString(3) ?: ""
                val visualModel = c.getString(4) ?: ""

                VectorRecord(
                    id = id,
                    image = FloatBlob.fromBytes(imgBytes),
                    caption = if (capBytes != null) FloatBlob.fromBytes(capBytes) else null,
                    visual = if (visBytes != null) FloatBlob.fromBytes(visBytes) else null,
                    model = model,
                    visualModel = visualModel,
                )
            } else null
        }
    }

    override suspend fun vectorSample(limit: Int): List<VectorRecord> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val list = ArrayList<VectorRecord>()
        val cursor = db.rawQuery(
            "SELECT id, image, caption, visual, model, visual_model FROM vectors WHERE model = ? ORDER BY id DESC LIMIT ?",
            arrayOf(ModelRegistry.PRIMARY_MODEL_FINGERPRINT, limit.toString())
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val imgBytes = c.getBlob(1) ?: continue
                val capBytes = if (!c.isNull(2)) c.getBlob(2) else null
                val visBytes = if (!c.isNull(3)) c.getBlob(3) else null
                val model = c.getString(4) ?: ""
                val visualModel = c.getString(5) ?: ""

                list.add(
                    VectorRecord(
                        id = id,
                        image = FloatBlob.fromBytes(imgBytes),
                        caption = if (capBytes != null) FloatBlob.fromBytes(capBytes) else null,
                        visual = if (visBytes != null) FloatBlob.fromBytes(visBytes) else null,
                        model = model,
                        visualModel = visualModel,
                    )
                )
            }
        }
        list
    }

    override suspend fun pending(limit: Int): List<Meme> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val list = ArrayList<Meme>()
        val cursor = db.rawQuery("SELECT * FROM memes WHERE pending = 1 ORDER BY modified_at DESC, id DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMeme(c))
            }
        }
        list
    }

    override fun allMemes(): Flow<Meme> = flow {
        var cursor: PageCursor? = null
        val batchSize = 100
        while (true) {
            val batch = page(limit = batchSize, after = cursor, kind = null, tag = null)
            if (batch.isEmpty()) break
            for (m in batch) {
                emit(m)
            }
            val last = batch.last()
            cursor = PageCursor(last.modifiedAt, last.id)
            if (batch.size < batchSize) break
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeIndex(
        id: Long,
        analysis: LocalAnalysis,
        model: String,
        thumbUri: String,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val currentMeme = meme(id) ?: return@withLock
                val now = System.currentTimeMillis()

                // Filter out any tags vetoed by durable negative teachings
                val rejected = currentMeme.rejectedLabels.map { TagMerge.normalizeTagLabel(it) }.toSet()
                val validAnalysisTags = analysis.tags.filter { !rejected.contains(TagMerge.normalizeTagLabel(it.label)) }
                val mergedTags = TagMerge.mergeDurableTags(currentMeme.tags + validAnalysisTags)
                val extraTerms = TagMerge.withoutRejectedTerms(analysis.extraTerms, rejected)

                val memeCv = ContentValues().apply {
                    put("ocr_text", analysis.ocr)
                    put("tags", serializeTags(mergedTags))
                    put("extra_terms", extraTerms)
                    put("pending", 0)
                    put("index_error", "")
                    put("indexed_at", now)
                    if (thumbUri.isNotBlank()) {
                        put("thumb_uri", thumbUri)
                    }
                }
                db.update("memes", memeCv, "id = ?", arrayOf(id.toString()))

                // Preserve existing compatible caption & visual vectors without blindly wiping them
                val existingVec = vectors(id)
                val vecCv = ContentValues().apply {
                    put("id", id)
                    put("image", FloatBlob.toBytes(analysis.image))
                    put("model", model)
                    if (existingVec != null) {
                        if (existingVec.caption != null && existingVec.model == model) {
                            put("caption", FloatBlob.toBytes(existingVec.caption))
                        }
                        if (existingVec.visual != null) {
                            put("visual", FloatBlob.toBytes(existingVec.visual))
                            put("visual_model", existingVec.visualModel)
                        }
                    }
                }
                db.insertWithOnConflict("vectors", null, vecCv, SQLiteDatabase.CONFLICT_REPLACE)

                updateFtsRow(
                    db = db,
                    id = id,
                    name = currentMeme.name,
                    ocr = analysis.ocr,
                    caption = currentMeme.caption,
                    transcript = currentMeme.transcript,
                    tags = mergedTags,
                    extraTerms = extraTerms,
                    cloudCaption = currentMeme.cloudCaption,
                    cloudTerms = currentMeme.cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setCaption(
        id: Long,
        caption: String,
        tags: List<Tag>,
        vector: FloatArray?,
        state: String,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val currentMeme = meme(id) ?: return@withLock
                val rejected = currentMeme.rejectedLabels.map { TagMerge.normalizeTagLabel(it) }.toSet()
                val validTags = tags.filter { !rejected.contains(TagMerge.normalizeTagLabel(it.label)) }
                val mergedTags = TagMerge.mergeDurableTags(currentMeme.tags + validTags)

                val cv = ContentValues().apply {
                    put("caption", caption)
                    put("tags", serializeTags(mergedTags))
                    put("vision_state", state)
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))

                if (vector != null && vector.isNotEmpty()) {
                    val vecCv = ContentValues().apply { put("caption", FloatBlob.toBytes(vector)) }
                    db.update("vectors", vecCv, "id = ? AND model = ?", arrayOf(id.toString(), ModelRegistry.PRIMARY_MODEL_FINGERPRINT))
                } else if (caption != currentMeme.caption) {
                    // Stale caption vector must be cleared when text changes without matching vector
                    val vecCv = ContentValues().apply {
                        putNull("caption")
                    }
                    db.update("vectors", vecCv, "id = ?", arrayOf(id.toString()))
                }

                updateFtsRow(
                    db = db,
                    id = id,
                    name = currentMeme.name,
                    ocr = currentMeme.ocr,
                    caption = caption,
                    transcript = currentMeme.transcript,
                    tags = mergedTags,
                    extraTerms = currentMeme.extraTerms,
                    cloudCaption = currentMeme.cloudCaption,
                    cloudTerms = currentMeme.cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setTranscript(id: Long, text: String, state: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val currentMeme = meme(id) ?: return@withLock
                val cv = ContentValues().apply {
                    put("transcript", text)
                    put("audio_state", state)
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                updateFtsRow(
                    db = db,
                    id = id,
                    name = currentMeme.name,
                    ocr = currentMeme.ocr,
                    caption = currentMeme.caption,
                    transcript = text,
                    tags = currentMeme.tags,
                    extraTerms = currentMeme.extraTerms,
                    cloudCaption = currentMeme.cloudCaption,
                    cloudTerms = currentMeme.cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setVisual(id: Long, vector: FloatArray, model: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val vecCv = ContentValues().apply {
                    put("visual", FloatBlob.toBytes(vector))
                    put("visual_model", model)
                }
                db.update("vectors", vecCv, "id = ?", arrayOf(id.toString()))
                val memeCv = ContentValues().apply {
                    put("visual_model", model)
                }
                db.update("memes", memeCv, "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setThumb(id: Long, uri: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("thumb_uri", uri)
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setContentHash(id: Long, hash: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("content_hash", hash)
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))

                val m = meme(id)
                if (m != null && hash.isNotBlank()) {
                    val hashCv = ContentValues().apply {
                        put("hash", hash)
                        put("uri", m.uri)
                    }
                    db.insertWithOnConflict("content_hashes", null, hashCv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun requeue(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val idList = ids.joinToString(",")
                db.execSQL("UPDATE memes SET pending = 1, index_error = '' WHERE id IN ($idList)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun markIndexFailed(id: Long, reason: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val failureReason = reason.ifBlank { "Analysis failed" }
                db.execSQL("UPDATE memes SET pending = 0, index_error = ? WHERE id = ?", arrayOf(failureReason, id))
                var memeName = "meme $id"
                db.rawQuery("SELECT name FROM memes WHERE id = ?", arrayOf(id.toString())).use { c ->
                    if (c.moveToNext()) memeName = c.getString(0) ?: memeName
                }
                writeErrorLocked(db, memeName, "index", failureReason)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setCloud(id: Long, result: CloudResult): Boolean = withContext(Dispatchers.IO) {
        if (result.expectedEvidence.isBlank()) return@withContext false

        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val currentMeme = meme(id) ?: return@withLock false
                val currentVec = vectors(id)

                if (currentMeme.contentHash != result.contentHash) {
                    return@withLock false
                }

                val expectedStamp = cloudEvidenceStamp(currentMeme)
                if (result.expectedEvidence != expectedStamp) {
                    return@withLock false
                }

                val curModel = currentVec?.model ?: ""
                if (result.embeddingModel != curModel) {
                    return@withLock false
                }
                val curVecStamp = cloudVectorStamp(currentVec)
                if (result.expectedVectorHash != curVecStamp) {
                    return@withLock false
                }

                val rejected = currentMeme.rejectedLabels.map { TagMerge.normalizeTagLabel(it) }.toSet()
                val cloudTags = result.tags
                    .filter { !rejected.contains(TagMerge.normalizeTagLabel(it.label)) }
                    .map {
                        it.copy(
                            source = "cloud",
                            scoreDomain = if (it.scoreDomain.isNotBlank()) it.scoreDomain else "uncalibrated",
                            confirmation = "unreviewed",
                            provenance = if (it.provenance.isNotBlank()) it.provenance else result.model,
                        )
                    }

                // Keep manual truth; never let cloud overwrite manual or exemplar
                val nonCloudTags = currentMeme.tags.filter { it.source != "cloud" }
                val mergedTags = TagMerge.mergeDurableTags(nonCloudTags + cloudTags)
                val cloudTerms = TagMerge.withoutRejectedTerms(result.terms, rejected)

                val cv = ContentValues().apply {
                    put("cloud_caption", result.caption)
                    put("cloud_terms", cloudTerms)
                    put("tags", serializeTags(mergedTags))
                    put("cloud_observation", serializeCloudResult(result))
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                updateFtsRow(
                    db = db,
                    id = id,
                    name = currentMeme.name,
                    ocr = currentMeme.ocr,
                    caption = currentMeme.caption,
                    transcript = currentMeme.transcript,
                    tags = mergedTags,
                    extraTerms = currentMeme.extraTerms,
                    cloudCaption = result.caption,
                    cloudTerms = cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
            true
        }
    }

    override suspend fun forgetCloud() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.execSQL("UPDATE memes SET cloud_caption = '', cloud_terms = '', cloud_observation = ''")
                val cursor = db.rawQuery("SELECT id, tags, name, ocr_text, caption, transcript, extra_terms FROM memes", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val rawTags = c.getString(1)
                        val name = c.getString(2)
                        val ocr = c.getString(3)
                        val cap = c.getString(4)
                        val trans = c.getString(5)
                        val extra = c.getString(6)

                        val filtered = deserializeTags(rawTags).filter {
                            it.source != "cloud" && it.source != "cloud_hint" || it.confirmation == "confirmed"
                        }
                        val cv = ContentValues().apply {
                            put("tags", serializeTags(filtered))
                        }
                        db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                        updateFtsRow(db, id, name, ocr, cap, trans, filtered, extra)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun manualTag(
        ids: List<Long>,
        label: String,
        category: String,
        spread: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty() || label.isBlank()) return@withContext 0
        val normLabel = TagMerge.normalizeTagLabel(label)

        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            val affected = try {
                var directlyTagged = 0
                val directTags = Tag(
                    label = normLabel,
                    category = category,
                    score = 1.0f,
                    source = "manual",
                    scoreDomain = "user",
                    confirmation = "confirmed",
                    provenance = "user",
                )

                for (id in ids) {
                    val currentMeme = meme(id) ?: continue
                    val (updatedTags, updatedTerms) = TagMerge.upsertDurableTag(currentMeme.tags, currentMeme.extraTerms, directTags)
                    val cv = ContentValues().apply {
                        put("tags", serializeTags(updatedTags))
                        put("extra_terms", updatedTerms)
                    }
                    db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                    updateFtsRow(
                        db = db,
                        id = id,
                        name = currentMeme.name,
                        ocr = currentMeme.ocr,
                        caption = currentMeme.caption,
                        transcript = currentMeme.transcript,
                        tags = updatedTags,
                        extraTerms = updatedTerms,
                        cloudCaption = currentMeme.cloudCaption,
                        cloudTerms = currentMeme.cloudTerms,
                    )
                    directlyTagged++
                }

                var spreadCount = 0
                if (spread) {
                    val sources = ArrayList<TagPropagation.VisualRecord>()
                    for (id in ids) {
                        val vr = vectors(id) ?: continue
                        if (vr.image.isNotEmpty() && vr.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
                            sources.add(TagPropagation.VisualRecord(vr.image, vr.model, vr.visual, vr.visualModel))
                        }
                    }

                    if (sources.isNotEmpty()) {
                        val idSet = ids.toSet()
                        val hits = ArrayList<TagPropagation.PropagationHit>()
                        val cursor = db.rawQuery(
                            """
                            SELECT m.id, m.tags, v.image, v.visual, v.visual_model, v.model, m.rejected_labels
                            FROM memes m
                            JOIN vectors v ON m.id = v.id
                            WHERE m.pending = 0 AND v.model = ?
                            """.trimIndent(),
                            arrayOf(ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
                        )
                        cursor.use { c ->
                            val idCol = c.getColumnIndexOrThrow("id")
                            val tagsCol = c.getColumnIndexOrThrow("tags")
                            val imgCol = c.getColumnIndexOrThrow("image")
                            val visCol = c.getColumnIndexOrThrow("visual")
                            val visModCol = c.getColumnIndexOrThrow("visual_model")
                            val rejCol = c.getColumnIndex("rejected_labels")

                            while (c.moveToNext()) {
                                val candidateId = c.getLong(idCol)
                                if (idSet.contains(candidateId)) continue

                                val rejList = if (rejCol >= 0 && !c.isNull(rejCol)) deserializeStrings(c.getString(rejCol)) else emptyList()
                                if (rejList.any { TagMerge.normalizeTagLabel(it) == normLabel }) continue

                                val tags = deserializeTags(c.getString(tagsCol))
                                val hasDurable = tags.any {
                                    TagMerge.normalizeTagLabel(it.label) == normLabel && TagMerge.isDurableTag(it)
                                }
                                val imgBlob = c.getBlob(imgCol) ?: continue
                                val visBlob = if (!c.isNull(visCol)) c.getBlob(visCol) else null
                                val visModel = if (!c.isNull(visModCol)) c.getString(visModCol) ?: "" else ""

                                val candRecord = TagPropagation.VisualRecord(
                                    imageEmbedding = FloatBlob.fromBytes(imgBlob),
                                    imageModel = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
                                    visualEmbedding = if (visBlob != null) FloatBlob.fromBytes(visBlob) else null,
                                    visualModel = visModel,
                                )

                                val hit = TagPropagation.scorePropagationCandidate(
                                    sources = sources,
                                    candidate = TagPropagation.Candidate(candidateId, hasDurable, candRecord),
                                )
                                if (hit != null) hits.add(hit)
                            }
                        }

                        val winners = TagPropagation.rankPropagationHits(hits)
                        for (winner in winners) {
                            val m = meme(winner.id) ?: continue
                            if (m.rejectedLabels.any { TagMerge.normalizeTagLabel(it) == normLabel }) continue

                            val propTag = TagPropagation.propagatedTag(normLabel, winner.score)
                            val (updatedTags, updatedTerms) = TagMerge.upsertDurableTag(m.tags, m.extraTerms, propTag)
                            val cv = ContentValues().apply {
                                put("tags", serializeTags(updatedTags))
                                put("extra_terms", updatedTerms)
                            }
                            db.update("memes", cv, "id = ?", arrayOf(winner.id.toString()))
                            updateFtsRow(
                                db = db,
                                id = winner.id,
                                name = m.name,
                                ocr = m.ocr,
                                caption = m.caption,
                                transcript = m.transcript,
                                tags = updatedTags,
                                extraTerms = updatedTerms,
                                cloudCaption = m.cloudCaption,
                                cloudTerms = m.cloudTerms,
                            )
                            spreadCount++
                        }
                    }
                }

                db.setTransactionSuccessful()
                directlyTagged + spreadCount
            } finally {
                db.endTransaction()
            }
            bumpRevision()
            affected
        }
    }

    override suspend fun removeTag(id: Long, label: String) = withContext(Dispatchers.IO) {
        val normLabel = TagMerge.normalizeTagLabel(label)
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val m = meme(id) ?: return@withLock
                val filtered = m.tags.filter { TagMerge.normalizeTagLabel(it.label) != normLabel }
                val cv = ContentValues().apply {
                    put("tags", serializeTags(filtered))
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                updateFtsRow(
                    db = db,
                    id = id,
                    name = m.name,
                    ocr = m.ocr,
                    caption = m.caption,
                    transcript = m.transcript,
                    tags = filtered,
                    extraTerms = m.extraTerms,
                    cloudCaption = m.cloudCaption,
                    cloudTerms = m.cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun teach(
        id: Long,
        label: String,
        category: String,
        positive: Boolean,
        associations: List<String>,
        confirmedCloudTag: Tag?,
    ) = withContext(Dispatchers.IO) {
        val normLabel = TagMerge.normalizeTagLabel(label)
        if (normLabel.isEmpty()) return@withContext

        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val currentMeme = meme(id) ?: return@withLock
                val currentVectors = vectors(id)
                val now = System.currentTimeMillis()
                val reviewedCloudTag = confirmedCloudTag?.let { observed ->
                    require(positive && TagMerge.normalizeTagLabel(observed.label) == normLabel &&
                        observed.category == category && observed.confirmation == "unreviewed" &&
                        (observed.source == "cloud" || observed.source == "cloud_hint") &&
                        currentMeme.tags.contains(observed)) {
                        "The cloud suggestion changed before confirmation"
                    }
                    observed.copy(label = normLabel, confirmation = "confirmed")
                }

                if (positive) {
                    if (currentVectors != null && currentVectors.image.isNotEmpty() && currentVectors.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
                        val cv = ContentValues().apply {
                            put("label", normLabel)
                            put("category", category)
                            put("vector", FloatBlob.toBytes(currentVectors.image))
                            put("associations", JSONArray(associations).toString())
                            put("source_uri", currentMeme.uri)
                            put("is_positive", 1)
                            put("origin", if (reviewedCloudTag != null) "cloud-confirmed" else "self")
                            put("pack", reviewedCloudTag?.provenance.orEmpty())
                            put("model", ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
                            put("created_at", now)
                        }
                        db.insert("exemplars", null, cv)
                    }

                    val updatedRejected = currentMeme.rejectedLabels.filter { TagMerge.normalizeTagLabel(it) != normLabel }

                    val teachTag = reviewedCloudTag ?: Tag(
                        label = normLabel,
                        category = category,
                        score = 1.0f,
                        source = "exemplar",
                        scoreDomain = "user",
                        confirmation = "confirmed",
                        provenance = "user",
                    )
                    val (updatedTags, updatedTerms) = TagMerge.upsertDurableTag(currentMeme.tags, currentMeme.extraTerms, teachTag)
                    val memeCv = ContentValues().apply {
                        put("tags", serializeTags(updatedTags))
                        put("extra_terms", updatedTerms)
                        put("rejected_labels", serializeStrings(updatedRejected))
                    }
                    db.update("memes", memeCv, "id = ?", arrayOf(id.toString()))
                    updateFtsRow(
                        db = db,
                        id = id,
                        name = currentMeme.name,
                        ocr = currentMeme.ocr,
                        caption = currentMeme.caption,
                        transcript = currentMeme.transcript,
                        tags = updatedTags,
                        extraTerms = updatedTerms,
                        cloudCaption = currentMeme.cloudCaption,
                        cloudTerms = currentMeme.cloudTerms,
                    )
                } else {
                    // Negative correction: persist rejected_labels independently of vector availability
                    if (currentVectors != null && currentVectors.image.isNotEmpty() && currentVectors.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
                        val cv = ContentValues().apply {
                            put("label", normLabel)
                            put("category", category)
                            put("vector", FloatBlob.toBytes(currentVectors.image))
                            put("associations", JSONArray(associations).toString())
                            put("source_uri", currentMeme.uri)
                            put("is_positive", 0)
                            put("origin", "self")
                            put("pack", "")
                            put("model", ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
                            put("created_at", now)
                        }
                        db.insert("exemplars", null, cv)
                    }

                    val updatedRejected = (currentMeme.rejectedLabels + normLabel).distinct()
                    val filteredTags = currentMeme.tags.filter { TagMerge.normalizeTagLabel(it.label) != normLabel }

                    // Rebuild extra terms: remove words associated with rejected label
                    val wordsToRemove = normLabel.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
                    val cleanedExtraTerms = currentMeme.extraTerms.split(Regex("\\s+"))
                        .filter { it.isNotBlank() && !wordsToRemove.contains(it.lowercase()) }
                        .joinToString(" ")
                    val cleanedCloudTerms = currentMeme.cloudTerms.split(Regex("\\s+"))
                        .filter { it.isNotBlank() && !wordsToRemove.contains(it.lowercase()) }
                        .joinToString(" ")

                    var updatedCloudCaption = currentMeme.cloudCaption
                    if (updatedCloudCaption.contains(normLabel, ignoreCase = true)) {
                        updatedCloudCaption = ""
                    }

                    val memeCv = ContentValues().apply {
                        put("tags", serializeTags(filteredTags))
                        put("extra_terms", cleanedExtraTerms)
                        put("rejected_labels", serializeStrings(updatedRejected))
                        put("cloud_caption", updatedCloudCaption)
                        put("cloud_terms", cleanedCloudTerms)
                    }
                    db.update("memes", memeCv, "id = ?", arrayOf(id.toString()))
                    updateFtsRow(
                        db = db,
                        id = id,
                        name = currentMeme.name,
                        ocr = currentMeme.ocr,
                        caption = currentMeme.caption,
                        transcript = currentMeme.transcript,
                        tags = filteredTags,
                        extraTerms = cleanedExtraTerms,
                        cloudCaption = updatedCloudCaption,
                        cloudTerms = cleanedCloudTerms,
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun exemplars(): List<Exemplar> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val list = ArrayList<Exemplar>()
        val cursor = db.rawQuery("SELECT id, label, category, vector, is_positive, associations, source_uri, model, origin, pack FROM exemplars ORDER BY created_at DESC", null)
        cursor.use { c ->
            while (c.moveToNext()) {
                val vecBytes = c.getBlob(3) ?: continue
                val rawAssoc = c.getString(5) ?: "[]"
                val assocList = ArrayList<String>()
                try {
                    val arr = JSONArray(rawAssoc)
                    for (i in 0 until arr.length()) assocList.add(arr.getString(i))
                } catch (_: Exception) {}

                list.add(
                    Exemplar(
                        id = c.getLong(0),
                        label = c.getString(1),
                        category = c.getString(2),
                        vector = FloatBlob.fromBytes(vecBytes),
                        positive = c.getInt(4) != 0,
                        associations = assocList,
                        sourceUri = c.getString(6) ?: "",
                        model = c.getString(7) ?: "",
                        origin = c.getString(8) ?: "self",
                        pack = c.getString(9) ?: "",
                    )
                )
            }
        }
        list
    }

    override suspend fun importExemplars(items: List<Exemplar>, pack: String, asSelf: Boolean): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            val imported = try {
                // Deduplication set built from existing exemplars
                val existing = exemplars()
                val seen = HashSet<String>()
                for (e in existing) {
                    val norm = TagMerge.normalizeTagLabel(e.label)
                    val fp = FloatBlob.fingerprint(e.vector)
                    seen.add("${norm}:${e.positive}:${e.model}:${fp}")
                }

                var count = 0
                val now = System.currentTimeMillis()
                for (item in items) {
                    val normLabel = TagMerge.normalizeTagLabel(item.label)
                    val fp = FloatBlob.fingerprint(item.vector)
                    val sig = "${normLabel}:${item.positive}:${item.model}:${fp}"
                    if (seen.contains(sig)) {
                        continue
                    }
                    seen.add(sig)

                    val origin = if (asSelf) "self" else if (item.origin.isNotBlank()) item.origin else if (pack.isNotBlank()) "pack" else "self"
                    val packName = if (asSelf) "" else if (item.pack.isNotBlank()) item.pack else pack

                    val cv = ContentValues().apply {
                        put("label", normLabel)
                        put("category", item.category)
                        put("vector", FloatBlob.toBytes(item.vector))
                        put("associations", JSONArray(item.associations).toString())
                        put("source_uri", item.sourceUri)
                        put("is_positive", if (item.positive) 1 else 0)
                        put("origin", origin)
                        put("pack", packName)
                        put("model", item.model)
                        put("created_at", now)
                    }
                    db.insert("exemplars", null, cv)
                    count++
                }
                db.setTransactionSuccessful()
                count
            } finally {
                db.endTransaction()
            }
            bumpRevision()
            imported
        }
    }

    override suspend fun forgetLabel(label: String) = withContext(Dispatchers.IO) {
        val norm = TagMerge.normalizeTagLabel(label)
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.delete("exemplars", "label = ?", arrayOf(norm))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun forgetPack(pack: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.delete("exemplars", "pack = ? AND origin = 'pack'", arrayOf(pack))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun updateTags(id: Long, tags: List<Tag>, extraTerms: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val m = meme(id) ?: return@withLock
                val rejected = m.rejectedLabels.map { TagMerge.normalizeTagLabel(it) }.toSet()
                val validTags = tags.filter { !rejected.contains(TagMerge.normalizeTagLabel(it.label)) }
                val merged = TagMerge.mergeDurableTags(m.tags + validTags)
                val filteredTerms = TagMerge.withoutRejectedTerms(extraTerms, rejected)

                val cv = ContentValues().apply {
                    put("tags", serializeTags(merged))
                    put("extra_terms", filteredTerms)
                }
                db.update("memes", cv, "id = ?", arrayOf(id.toString()))
                updateFtsRow(
                    db = db,
                    id = id,
                    name = m.name,
                    ocr = m.ocr,
                    caption = m.caption,
                    transcript = m.transcript,
                    tags = merged,
                    extraTerms = filteredTerms,
                    cloudCaption = m.cloudCaption,
                    cloudTerms = m.cloudTerms,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun delete(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val idList = ids.joinToString(",")
                db.execSQL("DELETE FROM vectors WHERE id IN ($idList)")
                db.execSQL("DELETE FROM meme_search_fts WHERE rowid IN ($idList)")
                db.execSQL("DELETE FROM memes WHERE id IN ($idList)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun clearIndex() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM vectors")
                db.execSQL("DELETE FROM meme_search_fts")
                db.execSQL("DELETE FROM content_hashes")
                db.execSQL("DELETE FROM memes")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun setting(key: String): String? = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val cursor = db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key))
        cursor.use { c ->
            if (c.moveToNext()) c.getString(0) else null
        }
    }

    override suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                }
                db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun addError(name: String, stage: String, reason: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            writeErrorLocked(db, name, stage, reason)
            bumpRevision()
        }
    }

    override suspend fun errors(): List<String> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val list = ArrayList<String>()
        val cursor = db.rawQuery("SELECT stage, name, reason FROM index_errors ORDER BY created_at DESC LIMIT 300", null)
        cursor.use { c ->
            while (c.moveToNext()) {
                val stage = c.getString(0)
                val name = c.getString(1)
                val reason = c.getString(2)
                list.add("[$stage] $name - $reason")
            }
        }
        list
    }

    override suspend fun clearErrors() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            try {
                db.delete("index_errors", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bumpRevision()
        }
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val cursor = db.rawQuery("SELECT COUNT(*) FROM memes WHERE pending = 0", null)
        cursor.use { c ->
            if (c.moveToNext()) c.getInt(0) else 0
        }
    }

    override suspend fun tagLabels(): List<String> = withContext(Dispatchers.IO) {
        val db = getReadableDb()
        val set = LinkedHashSet<String>()
        val cursor = db.rawQuery("SELECT tags FROM memes WHERE pending = 0 AND tags != '[]'", null)
        cursor.use { c ->
            while (c.moveToNext()) {
                val tags = deserializeTags(c.getString(0))
                for (t in tags) {
                    if (t.label.isNotBlank()) set.add(t.label)
                }
            }
        }
        set.sorted()
    }

    override suspend fun restore(meme: Meme, vectors: VectorRecord?): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = getWritableDb()
            db.beginTransaction()
            val restored = try {
                val existing = memeByUri(meme.uri)
                val now = System.currentTimeMillis()
                val isCompatible = vectors != null && vectors.image.isNotEmpty() && vectors.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT

                if (existing == null) {
                    val rejected = meme.rejectedLabels.map { TagMerge.normalizeTagLabel(it) }.toSet()
                    val validTags = meme.tags.filter { !rejected.contains(TagMerge.normalizeTagLabel(it.label)) }

                    val cv = ContentValues().apply {
                        put("uri", meme.uri)
                        put("name", meme.name)
                        put("kind", meme.kind)
                        put("ocr_text", meme.ocr)
                        put("caption", meme.caption)
                        put("transcript", meme.transcript)
                        put("tags", serializeTags(validTags))
                        put("extra_terms", meme.extraTerms)
                        put("thumb_uri", meme.thumbUri)
                        put("indexed_at", now)
                        put("modified_at", if (meme.modifiedAt > 0) meme.modifiedAt else now)
                        put("pending", if (isCompatible) 0 else 1)
                        put("vision_state", if (isCompatible) meme.visionState else "pending")
                        put("audio_state", meme.audioState)
                        put("content_hash", meme.contentHash)
                        put("cloud_caption", meme.cloudCaption)
                        put("cloud_terms", meme.cloudTerms)
                        put("legacy_imported", if (meme.legacyImported) 1 else 0)
                        put("rejected_labels", serializeStrings(meme.rejectedLabels))
                        if (meme.cloudObservation != null) {
                            put("cloud_observation", serializeCloudResult(meme.cloudObservation))
                        }
                    }
                    val insertedId = db.insertWithOnConflict("memes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    if (insertedId > 0 && isCompatible && vectors != null) {
                        val vecCv = ContentValues().apply {
                            put("id", insertedId)
                            put("image", FloatBlob.toBytes(vectors.image))
                            if (meme.caption.isNotBlank() && vectors.caption != null) {
                                put("caption", FloatBlob.toBytes(vectors.caption))
                            }
                            if (vectors.visual != null) {
                                put("visual", FloatBlob.toBytes(vectors.visual))
                                put("visual_model", vectors.visualModel)
                            }
                            put("model", vectors.model)
                        }
                        db.insertWithOnConflict("vectors", null, vecCv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    updateFtsRow(
                        db = db,
                        id = insertedId,
                        name = meme.name,
                        ocr = meme.ocr,
                        caption = meme.caption,
                        transcript = meme.transcript,
                        tags = validTags,
                        extraTerms = meme.extraTerms,
                        cloudCaption = meme.cloudCaption,
                        cloudTerms = meme.cloudTerms,
                    )
                    db.setTransactionSuccessful()
                    true
                } else {
                    val cv = ContentValues()
                    val mergedRejected = (existing.rejectedLabels + meme.rejectedLabels).distinct()
                    val rejectedSet = mergedRejected.map { TagMerge.normalizeTagLabel(it) }.toSet()

                    if (existing.ocr.isBlank() && meme.ocr.isNotBlank()) cv.put("ocr_text", meme.ocr)
                    val captionToRestore = existing.caption.isBlank() && meme.caption.isNotBlank()
                    if (captionToRestore) cv.put("caption", meme.caption)
                    if (existing.transcript.isBlank() && meme.transcript.isNotBlank()) cv.put("transcript", meme.transcript)
                    if (existing.tags.isEmpty() && meme.tags.isNotEmpty()) {
                        val validTags = meme.tags.filter { !rejectedSet.contains(TagMerge.normalizeTagLabel(it.label)) }
                        cv.put("tags", serializeTags(validTags))
                    }
                    if (existing.extraTerms.isBlank() && meme.extraTerms.isNotBlank()) cv.put("extra_terms", meme.extraTerms)
                    if (existing.thumbUri.isBlank() && meme.thumbUri.isNotBlank()) cv.put("thumb_uri", meme.thumbUri)
                    if (existing.modifiedAt == 0L && meme.modifiedAt > 0L) cv.put("modified_at", meme.modifiedAt)
                    if (existing.audioState != "done" && meme.audioState == "done") cv.put("audio_state", "done")
                    if (existing.cloudCaption.isBlank() && meme.cloudCaption.isNotBlank()) cv.put("cloud_caption", meme.cloudCaption)
                    if (existing.cloudTerms.isBlank() && meme.cloudTerms.isNotBlank()) cv.put("cloud_terms", meme.cloudTerms)
                    if (existing.cloudObservation == null && meme.cloudObservation != null) {
                        cv.put("cloud_observation", serializeCloudResult(meme.cloudObservation))
                    }
                    if (mergedRejected.size != existing.rejectedLabels.size) {
                        cv.put("rejected_labels", serializeStrings(mergedRejected))
                    }
                    if (!existing.legacyImported && meme.legacyImported) {
                        cv.put("legacy_imported", 1)
                    }

                    val updatedTags = if (existing.tags.isEmpty() && meme.tags.isNotEmpty()) {
                        meme.tags.filter { !rejectedSet.contains(TagMerge.normalizeTagLabel(it.label)) }
                    } else existing.tags
                    val updatedCaption = if (captionToRestore) meme.caption else existing.caption
                    val updatedOcr = if (existing.ocr.isBlank() && meme.ocr.isNotBlank()) meme.ocr else existing.ocr
                    val updatedTranscript = if (existing.transcript.isBlank() && meme.transcript.isNotBlank()) meme.transcript else existing.transcript
                    val updatedExtra = if (existing.extraTerms.isBlank() && meme.extraTerms.isNotBlank()) meme.extraTerms else existing.extraTerms
                    val updatedCloudCaption = if (existing.cloudCaption.isBlank() && meme.cloudCaption.isNotBlank()) meme.cloudCaption else existing.cloudCaption
                    val updatedCloudTerms = if (existing.cloudTerms.isBlank() && meme.cloudTerms.isNotBlank()) meme.cloudTerms else existing.cloudTerms

                    if (cv.size() > 0) {
                        db.update("memes", cv, "id = ?", arrayOf(existing.id.toString()))
                    }

                    if (vectors != null && vectors.image.isNotEmpty()) {
                        val existingVec = vectors(existing.id)
                        if (existingVec == null) {
                            if (isCompatible) {
                                val vecCv = ContentValues().apply {
                                    put("id", existing.id)
                                    put("image", FloatBlob.toBytes(vectors.image))
                                    if (captionToRestore && vectors.caption != null) {
                                        put("caption", FloatBlob.toBytes(vectors.caption))
                                    }
                                    if (vectors.visual != null) {
                                        put("visual", FloatBlob.toBytes(vectors.visual))
                                        put("visual_model", vectors.visualModel)
                                    }
                                    put("model", vectors.model)
                                }
                                db.insertWithOnConflict("vectors", null, vecCv, SQLiteDatabase.CONFLICT_REPLACE)
                                db.execSQL("UPDATE memes SET pending = 0, index_error = '' WHERE id = ?", arrayOf(existing.id))
                            } else {
                                db.execSQL("UPDATE memes SET pending = 1, index_error = '' WHERE id = ?", arrayOf(existing.id))
                            }
                        } else if (isCompatible && existingVec.caption == null && captionToRestore && vectors.caption != null) {
                            val vecCv = ContentValues().apply {
                                put("caption", FloatBlob.toBytes(vectors.caption))
                            }
                            db.update("vectors", vecCv, "id = ?", arrayOf(existing.id.toString()))
                        }
                    }

                    updateFtsRow(
                        db = db,
                        id = existing.id,
                        name = existing.name,
                        ocr = updatedOcr,
                        caption = updatedCaption,
                        transcript = updatedTranscript,
                        tags = updatedTags,
                        extraTerms = updatedExtra,
                        cloudCaption = updatedCloudCaption,
                        cloudTerms = updatedCloudTerms,
                    )
                    db.setTransactionSuccessful()
                    true
                }
            } finally {
                db.endTransaction()
            }
            bumpRevision()
            restored
        }
    }
}
