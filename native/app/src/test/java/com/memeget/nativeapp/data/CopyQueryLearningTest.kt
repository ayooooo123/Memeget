package com.memeget.nativeapp.data

import android.app.Application
import com.memeget.nativeapp.LocalAnalysis
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.inference.ModelRegistry
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CopyQueryLearningTest {

    private lateinit var store: SqliteLibraryStore
    private val model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
    private fun axis(index: Int = 0) = FloatArray(512).apply { this[index] = 1f }

    @Before
    fun freshDatabase() {
        val context = RuntimeEnvironment.getApplication()
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = SqliteLibraryStore(context)
    }

    private suspend fun insertIndexed(name: String, ocr: String = "", transcript: String = "", axisIdx: Int = 0): Long {
        val id = store.insertPending("file:///synthetic/$name", name, "image", 1000L, name.hashCode().toString())
        store.completeIndex(id, LocalAnalysis(axis(axisIdx), ocr, emptyList(), ""), model, "")
        if (transcript.isNotEmpty()) {
            store.setTranscript(id, transcript)
        }
        return id
    }

    @Test
    fun copyQueryBoostSurfacesCopiedMemeForSameQueryAndNotDifferentQuery() = runBlocking {
        val catId = insertIndexed("dancing_cat.png", ocr = "dancing cat", axisIdx = 0)
        val dogId = insertIndexed("sleeping_dog.png", ocr = "sleeping dog", axisIdx = 1)

        // Query with no lexical or semantic overlap returns nothing
        val before = store.search("party vibes", null)
        assertTrue("No match before learning", before.isEmpty())

        // User copies catId after searching "party vibes"
        store.recordCopy(catId, "party vibes")

        // Same search now surfaces catId through sentBoost alone
        val afterSame = store.search("party vibes", null)
        assertEquals("Copied meme must surface for the same search query", 1, afterSame.size)
        assertEquals(catId, afterSame[0].meme.id)
        assertTrue("Score must be positive boost", afterSame[0].score > 0f)

        // Different search does NOT surface catId
        val afterDifferent = store.search("quiet study session", null)
        assertTrue("Different query must not surface catId", afterDifferent.none { it.meme.id == catId })

        // Punctuation and casing normalize to the same query key
        val afterPunct = store.search("Party, Vibes!", null)
        assertEquals(1, afterPunct.size)
        assertEquals(catId, afterPunct[0].meme.id)
    }

    @Test
    fun transcriptsReturnsNonBlankOnly() = runBlocking {
        val videoId = insertIndexed("clip.mp4", transcript = "wake up sheeple", axisIdx = 2)
        val imageId = insertIndexed("silent.png", transcript = "", axisIdx = 3)

        val list = store.transcripts()
        assertEquals(listOf(videoId to "wake up sheeple"), list)
        assertFalse(list.any { it.first == imageId })
    }

    @Test
    fun deleteCleansUpCopyQueries() = runBlocking {
        val catId = insertIndexed("cat.png", ocr = "cat", axisIdx = 4)
        store.recordCopy(catId, "meow mood")

        assertEquals(1, store.search("meow mood", null).size)

        store.delete(listOf(catId))
        val afterDelete = store.search("meow mood", null)
        assertTrue(afterDelete.isEmpty())
    }
}
