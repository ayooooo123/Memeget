package com.memeget.nativeapp.knowledge

import android.app.Application
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.LocalAnalysis
import com.memeget.nativeapp.LocalKnowledge
import com.memeget.nativeapp.ModelProgress
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.TextRegion
import com.memeget.nativeapp.inference.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetagCorrectionTest {
    private lateinit var store: SqliteLibraryStore
    private lateinit var knowledge: LocalKnowledge
    private val model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT

    @Before fun freshLibrary() {
        val context = RuntimeEnvironment.getApplication()
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = SqliteLibraryStore(context)
        knowledge = LocalKnowledge(store, NoModelEngine())
        // No zero-shot labels or anchors: only taught labels can produce tags.
        for (field in listOf("cachedLabels", "cachedAnchors")) {
            LocalKnowledge::class.java.getDeclaredField(field).apply { isAccessible = true }.set(knowledge, emptyList<Any>())
        }
    }

    private fun unit(vararg components: Pair<Int, Float>): FloatArray {
        val v = FloatArray(512)
        for ((i, x) in components) v[i] = x
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
        return v
    }

    private suspend fun indexed(name: String, hash: Char, vector: FloatArray, tags: List<Tag> = emptyList(), terms: String = ""): Long {
        val id = store.insertPending("file:///synthetic/$name", name, "image", 42L, hash.toString().repeat(64))
        store.completeIndex(id, LocalAnalysis(vector, "", tags, terms), model, "")
        return id
    }

    @Test fun notThisOnOneMemeRemovesTheMachineLabelFromItsNearCopyAndFromSearch() = runBlocking {
        val taught = indexed("taught.png", 'a', unit(0 to 1f))
        val corrected = indexed("corrected.png", 'b', unit(0 to 0.6f, 1 to 0.8f))
        // A near-copy of `corrected` that an earlier pass tagged from the positive example,
        // plus a stale zero-shot guess and a label the user confirmed by hand.
        val copy = indexed(
            "copy.png", 'c', unit(0 to 0.6f, 1 to 0.8f, 2 to 0.1f),
            tags = listOf(
                Tag("nick fuentes", "person", 0.7f, "entity_pack"),
                Tag("stale guess", "topic", 0.4f, "prompt"),
            ),
            terms = "nick fuentes stale guess",
        )
        store.manualTag(listOf(copy), "kept label")
        store.teach(taught, "nick fuentes", "person", true)
        store.teach(corrected, "nick fuentes", "person", false)
        assertTrue(store.search("fuentes", null).any { it.meme.id == copy })

        knowledge.retag {}

        val after = requireNotNull(store.meme(copy))
        assertFalse(after.tags.any { it.label == "nick fuentes" })
        assertFalse(after.tags.any { it.label == "stale guess" })
        assertTrue(after.tags.any { it.label == "kept label" && it.confirmation == "confirmed" })
        assertFalse(after.extraTerms.split(' ').contains("fuentes"))
        assertEquals(listOf(taught), store.search("fuentes", null).map { it.meme.id })
        assertTrue(store.search("kept", null).any { it.meme.id == copy })
    }

    @Test fun aHandTagAddedAfterNotThisSurvivesRetagging() = runBlocking {
        val id = indexed("relabeled.png", 'd', unit(3 to 1f))
        store.teach(id, "milady", "character", false)
        store.manualTag(listOf(id), "milady", "character")

        knowledge.retag {}

        val after = requireNotNull(store.meme(id))
        assertTrue(after.tags.any { it.label == "milady" && it.confirmation == "confirmed" })
        assertFalse("the newer hand tag withdraws the older 'not this'", after.rejectedLabels.contains("milady"))
        assertEquals(listOf(id), store.search("milady", null).map { it.meme.id })
    }

    private class NoModelEngine : InferenceEngine {
        override val progress: StateFlow<ModelProgress> = MutableStateFlow(ModelProgress())
        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        override suspend fun embedImage(uri: String): FloatArray = throw UnsupportedOperationException()
        override suspend fun embedText(text: String): FloatArray = throw UnsupportedOperationException()
        override suspend fun embedVisual(uri: String): FloatArray = throw UnsupportedOperationException()
        override suspend fun recognize(uri: String): List<TextRegion> = emptyList()
        override suspend fun describe(uri: String, grounding: String): String = ""
        override suspend fun transcribe(uri: String): String = ""
        override suspend fun segment(uri: String, requestId: String): expo.modules.memegetbg.SubjectCutoutResult =
            throw UnsupportedOperationException()
        override suspend fun unload() {}
        override fun cancel() {}
        override fun close() {}
    }
}
