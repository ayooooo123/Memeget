package com.memeget.nativeapp.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.memeget.nativeapp.CloudAssistant
import com.memeget.nativeapp.CloudResult
import com.memeget.nativeapp.Exemplar
import com.memeget.nativeapp.LocalAnalysis
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.NativeInferenceEngine
import com.memeget.nativeapp.SearchCursor
import com.memeget.nativeapp.SearchHit
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.VectorRecord
import com.memeget.nativeapp.cloudEvidenceStamp
import com.memeget.nativeapp.cloudVectorStamp
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.knowledge.Recognition
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SqliteLibraryStoreTest {
    private lateinit var store: SqliteLibraryStore
    private val model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
    private fun axis(index: Int = 0) = FloatArray(512).apply { this[index] = 1f }

    @Before fun freshDatabase() {
        val context = RuntimeEnvironment.getApplication()
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = SqliteLibraryStore(context)
    }

    private suspend fun insert(name: String, kind: String = "image"): Long =
        store.insertPending("file:///synthetic/$name", name, kind, 42L, "a".repeat(64))

    private suspend fun cloudResult(id: Long, caption: String = "violet airship"): CloudResult {
        val meme = requireNotNull(store.meme(id))
        val vectors = store.vectors(id)
        return CloudResult(
            contentHash = meme.contentHash, model = "test-decision-model", caption = caption,
            tags = emptyList(), terms = "", probabilities = mapOf("unknown" to 1f),
            providerConfidence = 0.7f, expectedEvidence = cloudEvidenceStamp(meme),
            embeddingModel = vectors?.model.orEmpty(), expectedVectorHash = cloudVectorStamp(vectors),
        )
    }

    @Test fun indexFailureReleasesWriterForSubsequentOperations() = runBlocking {
        val id = insert("failure.png")
        withTimeout(15_000) {
            store.markIndexFailed(id, "synthetic decoder failure")
            store.manualTag(listOf(id), "reviewed")
        }
        assertFalse(requireNotNull(store.meme(id)).pending)
        assertTrue(store.errors().any { it.contains("synthetic decoder failure") })
        assertEquals("reviewed", requireNotNull(store.meme(id)).tags.single().label)
    }

    @Test fun indexFailureSurvivesReopenAndClearsOnRetryOrSuccess() = runBlocking {
        val id = insert("retry.png")
        store.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        store.markIndexFailed(id, "synthetic decoder failure")
        val reopened = SqliteLibraryStore(RuntimeEnvironment.getApplication())
        val failed = requireNotNull(reopened.meme(id))
        assertFalse(failed.pending)
        assertEquals("synthetic decoder failure", failed.indexError)
        assertNotNull(reopened.vectors(id))

        reopened.requeue(listOf(id))
        val queued = requireNotNull(reopened.meme(id))
        assertTrue(queued.pending)
        assertEquals("", queued.indexError)

        reopened.markIndexFailed(id, "second failure")
        reopened.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        val recovered = requireNotNull(reopened.meme(id))
        assertFalse(recovered.pending)
        assertEquals("", recovered.indexError)
    }

    @Test fun negativeCorrectionWithoutVectorSurvivesReopenAndReindex() = runBlocking {
        val id = insert("rejected.png")
        store.manualTag(listOf(id), "trusted", "character")
        store.teach(id, "wrong", "character", false)
        val reopened = SqliteLibraryStore(RuntimeEnvironment.getApplication())
        assertTrue(requireNotNull(reopened.meme(id)).rejectedLabels.contains("wrong"))
        reopened.completeIndex(id, LocalAnalysis(axis(), "", listOf(Tag("wrong")), "wrong"), model, "")
        val updated = requireNotNull(reopened.meme(id))
        assertFalse(updated.tags.any { it.label == "wrong" })
        assertEquals("confirmed", updated.tags.single { it.label == "trusted" }.confirmation)
        assertTrue(updated.rejectedLabels.contains("wrong"))
        assertTrue(reopened.search("wrong", null).isEmpty())
        reopened.updateTags(id, listOf(Tag("wrong")), "wrong")
        assertTrue(reopened.search("wrong", null).isEmpty())
        assertTrue(reopened.setCloud(id, cloudResult(id).copy(terms = "wrong")))
        assertTrue(reopened.search("wrong", null).isEmpty())
    }

    @Test fun importedNotThisAlsoRemovesTheLabelFromCloudSearchText() = runBlocking {
        val id = insert("cloud-terms.png")
        store.completeIndex(id, LocalAnalysis(axis(4), "", emptyList(), ""), model, "")
        assertTrue(store.setCloud(id, cloudResult(id, caption = "a wojak at a desk").copy(terms = "wojak desk")))
        assertEquals(listOf(id), store.search("wojak", null).map { it.meme.id })

        store.importExemplars(listOf(Exemplar(0L, "wojak", "character", axis(4), false, emptyList(), "", model)), pack = "sample", asSelf = false)
        store.relinkTeachings()

        assertTrue(requireNotNull(store.meme(id)).rejectedLabels.contains("wojak"))
        assertTrue(store.search("wojak", null).isEmpty())
        assertEquals(listOf(id), store.search("desk", null).map { it.meme.id })
    }

    @Test fun aRetagHoldingAnOlderCopyCannotRestoreALabelRejectedSince() = runBlocking {
        val id = insert("stale-copy.png")
        store.completeIndex(id, LocalAnalysis(axis(6), "", emptyList(), ""), model, "")
        store.manualTag(listOf(id), "pepe", "character")
        val retagCopy = requireNotNull(store.meme(id)) // what a running re-tag read earlier
        store.teach(id, "pepe", "character", false)    // then the user says "not this"

        store.updateTags(id, retagCopy.tags, retagCopy.extraTerms)

        val after = requireNotNull(store.meme(id))
        assertFalse(after.tags.any { it.label == "pepe" })
        assertTrue(after.rejectedLabels.contains("pepe"))
        assertTrue(store.search("pepe", null).isEmpty())
    }

    @Test fun cloudCommitRejectsChangedVectorsEvenWhenOriginallyAbsent() = runBlocking {
        val id = insert("cloud.png")
        val old = cloudResult(id)
        store.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        assertFalse(store.setCloud(id, old))
        val current = cloudResult(id)
        assertTrue(store.setCloud(id, current))
        assertEquals(current, requireNotNull(store.meme(id)).cloudObservation)
        store.manualTag(listOf(id), "user correction")
        assertFalse(store.setCloud(id, current))
    }

    @Test fun forgettingCaptionOnlyObservationRemovesOfflineSearchEvidence() = runBlocking {
        val id = insert("observation.png")
        store.manualTag(listOf(id), "kept")
        assertTrue(store.setCloud(id, cloudResult(id)))
        assertEquals(listOf(id), store.search("airship", null).map { it.meme.id })
        store.forgetCloud()
        assertTrue(store.search("airship", null).isEmpty())
        val meme = requireNotNull(store.meme(id))
        assertEquals("", meme.cloudCaption)
        assertNull(meme.cloudObservation)
        assertEquals("kept", meme.tags.single().label)
    }

    @Test fun acceptedCloudIdentityKeepsItsEvidenceAndHumanReviewAcrossRetagAndForget() = runBlocking {
        val id = insert("review.png")
        store.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        val observed = Tag("reviewed format", "format", 0.73f, "cloud",
            "provider-choice-probability", "unreviewed", "cloud:test-model:prompt-hash")
        assertTrue(store.setCloud(id, cloudResult(id).copy(tags = listOf(observed))))
        val staleResponse = cloudResult(id)
        val engine = NativeInferenceEngine(RuntimeEnvironment.getApplication())
        try {
            CloudAssistant(RuntimeEnvironment.getApplication(), store, engine).confirm(id, observed.label, true)
        } finally {
            engine.close()
        }
        val reviewed = observed.copy(confirmation = "confirmed")
        assertEquals(reviewed, requireNotNull(store.meme(id)).tags.single())
        assertEquals("recognized", Recognition.recognitionTier(listOf(reviewed)))
        assertFalse("Human review must invalidate an in-flight response", store.setCloud(id, staleResponse))
        val teaching = store.exemplars().single()
        assertEquals("cloud-confirmed", teaching.origin)
        assertEquals(observed.provenance, teaching.pack)
        val automatic = List(8) { Tag("other-$it", "topic", 0.9f, "prompt") }
        store.updateTags(id, automatic, "")
        store.forgetCloud()
        val reopened = SqliteLibraryStore(RuntimeEnvironment.getApplication())
        val after = requireNotNull(reopened.meme(id))
        assertEquals(reviewed, after.tags.single { it.label == observed.label })
        assertNull(after.cloudObservation)
        assertEquals(listOf(id), reopened.search(observed.label, null).map { it.meme.id })
    }

    @Test fun lateVectorsLearnReviewedCloudTagsButNeverLegacyOrUnreviewedEvidence() = runBlocking {
        val id = insert("late-vector.png")
        val observed = Tag("reviewed identity", "character", 0f, "cloud_hint",
            "prototype-cosine", "unreviewed", "cloud:synthetic:prototype")
        store.updateTags(id, listOf(observed), "")
        val engine = NativeInferenceEngine(RuntimeEnvironment.getApplication())
        try {
            CloudAssistant(RuntimeEnvironment.getApplication(), store, engine).confirm(id, observed.label, true)
        } finally {
            engine.close()
        }
        assertTrue(store.exemplars().isEmpty())
        assertTrue("nothing to learn from until the meme has a vector", store.humanLabels().isEmpty())
        val reviewed = observed.copy(confirmation = "confirmed")
        assertEquals(reviewed, requireNotNull(store.meme(id)).tags.single())
        store.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        val unreviewedId = insert("unreviewed.png")
        val legacyId = insert("legacy.png")
        store.completeIndex(unreviewedId, LocalAnalysis(axis(1), "",
            listOf(observed.copy(label = "unreviewed identity")), ""), model, "")
        store.completeIndex(legacyId, LocalAnalysis(axis(2), "",
            listOf(Tag("legacy identity", "character", 1f, "manual", "user", "confirmed", "legacy-sidecar")), ""), model, "")
        val learned = store.humanLabels()
        assertEquals("unreviewed and filename-only legacy labels never teach", listOf(id), learned.map { it.memeId })
        assertEquals(listOf(observed.label), learned.single().labels.map { it.label })
        assertArrayEquals(axis(), learned.single().image, 0f)
    }

    @Test fun keysetSearchTraversesBeyondOneHeapWithoutMissingOrRepeatingTies() = runBlocking {
        val ids = ArrayList<Long>()
        repeat(310) { index ->
            val id = insert("entry-$index.png")
            ids += id
            store.completeIndex(id, LocalAnalysis(axis(index % 512), "searchable sample", emptyList(), ""), model, "")
        }
        for (vector in listOf(null, axis())) {
            var cursor: SearchCursor? = null
            val hits = ArrayList<SearchHit>()
            do {
                val page = store.search("sample", vector, limit = 90, after = cursor)
                hits.addAll(page)
                cursor = page.lastOrNull()?.let { SearchCursor(it.score, it.meme.modifiedAt, it.meme.id) }
            } while (cursor != null)
            assertEquals(ids.toSet(), hits.map { it.meme.id }.toSet())
            assertEquals(ids.size, hits.size)
            val ordered = hits.sortedWith(compareByDescending<SearchHit> { it.score }
                .thenByDescending { it.meme.modifiedAt }.thenByDescending { it.meme.id })
            assertEquals(ordered.map { it.meme.id }, hits.map { it.meme.id })
        }
    }

    @Test fun restorePreservesCaptionVectorPairAndCompletedSilentAudio() = runBlocking {
        val id = insert("silent.mp4", "video")
        store.completeIndex(id, LocalAnalysis(axis(), "", emptyList(), ""), model, "")
        store.setCaption(id, "original scene", emptyList(), axis(1))
        store.setTranscript(id, "", "done")
        val before = requireNotNull(store.meme(id))
        val incoming = before.copy(caption = "unrelated scene", audioState = "pending")
        store.restore(incoming, VectorRecord(id, axis(), caption = axis(2), model = model))
        assertEquals("original scene", requireNotNull(store.meme(id)).caption)
        assertArrayEquals(axis(1), requireNotNull(store.vectors(id)?.caption), 0f)
        assertEquals("done", requireNotNull(store.meme(id)).audioState)
    }

    @Test fun exemplarDeduplicationUsesWholeVectorAndIsIdempotent() = runBlocking {
        val first = Exemplar(0, "distinct", "character", axis(100), true, listOf("association"), "synthetic:one", model)
        val second = first.copy(vector = axis(200), sourceUri = "synthetic:two")
        assertEquals(2, store.importExemplars(listOf(first, second), "test-pack"))
        assertEquals(0, store.importExemplars(listOf(first, second), "test-pack"))
        assertEquals(2, store.exemplars().size)
    }

    @Test fun fuzzySearchIncludesTheWholeLibraryAndEverySearchableTextField() = runBlocking {
        val helper = SqliteLibraryDbHelper(RuntimeEnvironment.getApplication())
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            repeat(2001) { index ->
                db.execSQL(
                    "INSERT INTO memes(uri, name, kind, indexed_at, modified_at, pending) VALUES (?, 'archived item', 'image', 2, 2, 0)",
                    arrayOf("synthetic:archive-$index"),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            helper.close()
        }
        val id = store.insertPending("file:///synthetic/quartzite.png", "quartzite.png", "image", 1L, "a".repeat(64))
        store.completeIndex(id, LocalAnalysis(axis(), "lavender", emptyList(), ""), model, "")
        store.setTranscript(id, "tangerine")
        assertTrue(store.setCloud(id, cloudResult(id, "ultramarine")))
        for (query in listOf("quarzite", "lavendar", "tangerene", "ultramrine")) {
            assertEquals("Missing fuzzy match for $query", listOf(id), store.search(query, null).map { it.meme.id })
        }
    }

    @Test fun pendingLegacyTextRemainsAvailableToFuzzySearch() = runBlocking {
        val incoming = Meme(0L, "file:///synthetic/legacy.png", "legacy.png", "image",
            ocr = "orchard", pending = true, legacyImported = true)
        assertTrue(store.restore(incoming, null))
        val restored = requireNotNull(store.memeByUri(incoming.uri))
        assertTrue(restored.pending)
        assertEquals(listOf(restored.id), store.search("orhard", null).map { it.meme.id })
    }

    private fun rawDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        RuntimeEnvironment.getApplication().getDatabasePath(SqliteLibraryDbHelper.DATABASE_NAME).path,
        null, SQLiteDatabase.OPEN_READWRITE,
    )

    @Test fun aLibraryWrittenByANewerBuildStillOpensAfterARollback() = runBlocking {
        val id = insert("rollback.png")
        store.completeIndex(id, LocalAnalysis(axis(), "quartzite", emptyList(), ""), model, "")
        // Reinstalling an older APK over a newer one must keep the owner's library readable.
        rawDatabase().use { it.version = SqliteLibraryDbHelper.DATABASE_VERSION + 1 }
        val reopened = SqliteLibraryStore(RuntimeEnvironment.getApplication())
        assertEquals(listOf(id), reopened.search("quarzite", null).map { it.meme.id })
    }

    @Test fun aLibraryCreatedBeforeTheSearchWordListStillCorrectsTypos() = runBlocking {
        val id = insert("older.png")
        store.completeIndex(id, LocalAnalysis(axis(), "quartzite", emptyList(), ""), model, "")
        rawDatabase().use { it.execSQL("DROP TABLE meme_search_terms") }
        val reopened = SqliteLibraryStore(RuntimeEnvironment.getApplication())
        assertEquals(listOf(id), reopened.search("quarzite", null).map { it.meme.id })
    }
}
