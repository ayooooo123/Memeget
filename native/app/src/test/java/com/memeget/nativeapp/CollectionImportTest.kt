package com.memeget.nativeapp

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.work.testing.WorkManagerTestInitHelper
import com.memeget.nativeapp.inference.ModelRegistry
import expo.modules.memegetbg.SubjectCutoutResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var store: SqliteLibraryStore
    private lateinit var files: LibraryFiles
    private lateinit var engine: CountingEngine

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        val filesRoot = temporary.newFolder("files")
        val cacheRoot = temporary.newFolder("cache")
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = filesRoot
            override fun getCacheDir(): File = cacheRoot
        }
        context.databaseList().forEach { context.deleteDatabase(it) }
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        store = SqliteLibraryStore(context)
        files = LibraryFiles(context, store)
        engine = CountingEngine()
    }

    @After fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun png(size: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun axis(index: Int) = FloatArray(512).apply { this[index] = 1f }

    private fun vectorJson(v: FloatArray) = JSONArray().apply { v.forEach { put(it.toDouble()) } }

    private fun tag(label: String, source: String, score: Double, category: String = "user") =
        JSONObject().put("label", label).put("category", category).put("score", score).put("source", source)

    /** An original-app version 2 export: media under media/, manifest.json written last. */
    private fun exportZip(name: String, media: List<Pair<String, ByteArray>>, records: List<JSONObject>, version: Int = 2): File {
        val zip = File(temporary.root, name)
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((path, bytes) in media) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
            val manifest = JSONObject()
                .put("format", "memeget-collection").put("version", version).put("model", "mobileclip-s2").put("dim", 512)
                .put("count", records.size).put("memes", JSONArray(records))
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
        }
        return zip
    }

    private fun record(file: String, kind: String, tags: List<JSONObject>, ocr: String = "", transcript: String = "", embedding: FloatArray? = null) =
        JSONObject().put("file", file).put("name", file.substringAfterLast('/')).put("kind", kind)
            .put("tags", JSONArray(tags)).put("ocr", ocr).put("transcript", transcript).put("caption", "machine caption")
            .put("embedding", embedding?.let(::vectorJson) ?: JSONObject.NULL)
            .put("captionEmbedding", vectorJson(axis(7))).put("embeddingModel", "mobileclip-s2").put("pending", false)

    private fun teachingPack(vararg examples: Triple<String, FloatArray, Boolean>): File {
        val pack = JSONObject().put("format", "memeget-teaching-pack").put("version", 2).put("model", "mobileclip-s2")
            .put("dim", 512).put("name", "sample").put("author", "").put("createdAt", 1).put("count", examples.size)
            .put("exemplars", JSONArray(examples.map { (label, vector, positive) ->
                JSONObject().put("label", label).put("category", "character").put("vector", vectorJson(vector))
                    .put("associations", JSONArray()).put("positive", positive)
            }))
        return File(temporary.root, "memeget-teachings.json").apply { writeText(pack.toString()) }
    }

    @Test fun versionTwoExportCarriesHumanLabelsTextAndVectorsButNotPredictions() = runBlocking {
        val still = png(16, Color.RED)
        val zip = exportZip("collection.zip",
            media = listOf("media/1_still.png" to still, "media/2_clip.mp4" to "not decoded here".toByteArray(), "media/3_copy.png" to still),
            records = listOf(
                record("media/1_still.png", "image", listOf(
                    tag("Nick Fuentes", "manual", 1.0),
                    tag("disbelief", "vision", 0.6, "emotion"),
                    tag("pepe", "exemplar", 0.8, "character"),
                ), ocr = "hello world", embedding = axis(0)),
                record("media/2_clip.mp4", "video", listOf(tag("grandpa", "exemplar", 1.0, "character")), transcript = "ok boomer"),
                record("media/3_copy.png", "image", listOf(tag("crt", "manual", 1.0)), embedding = axis(0)),
            ))

        val ids = files.importUri(Uri.fromFile(zip), "application/zip")
        assertEquals(2, store.pending(10).size)
        val still1 = requireNotNull(store.meme(ids[0]))
        assertEquals(ids[0], ids[2])
        val labels = still1.tags.associateBy { it.label }
        for (label in listOf("nick fuentes", "crt")) {
            val t = requireNotNull(labels[label])
            assertEquals("confirmed", t.confirmation)
            assertEquals("manual", t.source)
            assertEquals("collection-import", t.provenance)
        }
        assertFalse("machine predictions are not imported", labels.containsKey("disbelief") || labels.containsKey("pepe"))
        assertEquals("hello world", still1.ocr)
        assertEquals("", still1.caption)
        assertTrue(still1.pending && still1.analysisImported)
        assertEquals(ModelRegistry.PRIMARY_MODEL_FINGERPRINT, requireNotNull(store.vectors(still1.id)).model)
        assertEquals(listOf(still1.id), store.search("fuentes", null).map { it.meme.id })

        val clip = requireNotNull(store.meme(ids[1]))
        assertEquals(listOf("grandpa"), clip.tags.map { it.label })
        assertEquals("ok boomer", clip.transcript)
        assertEquals("done", clip.audioState)
        assertFalse(clip.analysisImported)
        assertNull(store.vectors(clip.id))

        assertEquals(ids, files.importUri(Uri.fromFile(zip), "application/zip"))
        assertEquals(2, store.pending(10).size)
        assertEquals(still1.tags, requireNotNull(store.meme(still1.id)).tags)
    }

    @Test fun previewExportsKeepLabelsButNotVectorsOfOtherBytes() = runBlocking {
        val zip = exportZip("preview.zip", listOf("images/1.jpg" to png(12, Color.BLUE)),
            listOf(record("images/1.jpg", "image", listOf(tag("milady", "manual", 1.0)), embedding = axis(0))), version = 1)
        val id = files.importUri(Uri.fromFile(zip), "application/zip").single()
        val meme = requireNotNull(store.meme(id))
        assertEquals(listOf("milady"), meme.tags.map { it.label })
        assertFalse(meme.analysisImported)
        assertNull(store.vectors(id))
    }

    private fun relinkFixtures(): Pair<File, File> {
        val zip = exportZip("collection.zip",
            media = listOf("media/a.png" to png(10, Color.GREEN), "media/b.png" to png(11, Color.GREEN)),
            records = listOf(
                record("media/a.png", "image", listOf(tag("hasbulla", "manual", 1.0)), embedding = axis(0)),
                record("media/b.png", "image", emptyList(), embedding = axis(1)),
            ))
        val pack = teachingPack(
            Triple("milady", axis(1), true),
            Triple("wojak", axis(1), false),
            Triple("hasbulla", axis(0), false),
            Triple("unrelated", axis(5), true),
        )
        return zip to pack
    }

    private suspend fun assertTeachingsAttached(a: Long, b: Long) {
        val memeB = requireNotNull(store.meme(b))
        val milady = memeB.tags.single { it.label == "milady" }
        assertEquals("confirmed", milady.confirmation)
        assertEquals("exemplar", milady.source)
        assertTrue(memeB.rejectedLabels.contains("wojak"))
        val memeA = requireNotNull(store.meme(a))
        assertTrue("a 'not X' teaching never overrides the user's own tag", memeA.tags.any { it.label == "hasbulla" })
        assertFalse(memeA.rejectedLabels.contains("hasbulla"))
        assertFalse(memeA.tags.any { it.label == "unrelated" } || memeB.tags.any { it.label == "unrelated" })
        assertEquals(listOf(b), store.search("milady", null).map { it.meme.id })
    }

    @Test fun teachingsImportedBeforeTheCollectionAttachToTheMemesTheyShow() = runBlocking {
        val (zip, pack) = relinkFixtures()
        assertTrue(files.importUri(Uri.fromFile(pack), "application/json").isEmpty())
        val (a, b) = files.importUri(Uri.fromFile(zip), "application/zip")
        assertTeachingsAttached(a, b)
    }

    @Test fun teachingsImportedAfterTheCollectionAttachToTheMemesTheyShow() = runBlocking {
        val (zip, pack) = relinkFixtures()
        val (a, b) = files.importUri(Uri.fromFile(zip), "application/zip")
        assertTrue(files.importUri(Uri.fromFile(pack), "application/json").isEmpty())
        assertTeachingsAttached(a, b)
    }

    @Test fun importedAnalysisIndexesWithoutRunningImageOrTextModels() = runBlocking {
        val zip = exportZip("collection.zip",
            media = listOf("media/known.png" to png(14, Color.YELLOW), "media/fresh.png" to png(15, Color.YELLOW)),
            records = listOf(
                record("media/known.png", "image", listOf(tag("schizo", "manual", 1.0)), ocr = "imported text", embedding = axis(0)),
                record("media/fresh.png", "image", emptyList()),
            ))
        val (known, fresh) = files.importUri(Uri.fromFile(zip), "application/zip")
        store.setSetting("background.enabled", "1")
        store.setSetting("background.pauseLowBattery", "0")
        store.setSetting("background.pauseHot", "0")
        val coordinator = IndexCoordinator(context, store, files, engine, LocalKnowledge(context, store, engine), CloudAssistant(context, store, engine))

        coordinator.runPending()

        val knownMeme = requireNotNull(store.meme(known))
        assertFalse(knownMeme.pending || knownMeme.analysisImported)
        assertEquals("imported text", knownMeme.ocr)
        assertTrue(knownMeme.tags.any { it.label == "schizo" && it.confirmation == "confirmed" })
        assertEquals(axis(0).toList(), requireNotNull(store.vectors(known)).image.toList())
        assertEquals("only the meme without imported analysis runs the image model", 1, engine.embedded.size)
        assertEquals(1, engine.recognized.size)
        val freshMeme = requireNotNull(store.meme(fresh))
        assertFalse(freshMeme.pending)
        assertEquals("fresh text", freshMeme.ocr)
        assertNotNull(store.vectors(fresh))
    }

    private class CountingEngine : InferenceEngine {
        val embedded = ArrayList<String>()
        val recognized = ArrayList<String>()
        override val progress: StateFlow<ModelProgress> = MutableStateFlow(ModelProgress())
        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        override suspend fun embedImage(uri: String): FloatArray {
            embedded += uri
            return FloatArray(512).apply { this[3] = 1f }
        }
        override suspend fun embedText(text: String): FloatArray = FloatArray(512).apply { this[1] = 1f }
        override suspend fun embedVisual(uri: String): FloatArray = FloatArray(768).apply { this[0] = 1f }
        override suspend fun recognize(uri: String): List<TextRegion> {
            recognized += uri
            return listOf(TextRegion("fresh text", 0.9f, 0f, 0f, 1f, 1f))
        }
        override suspend fun describe(uri: String, grounding: String): String = ""
        override suspend fun transcribe(uri: String): String = ""
        override suspend fun segment(uri: String, requestId: String): SubjectCutoutResult = throw UnsupportedOperationException()
        override suspend fun unload() {}
        override fun cancel() {}
        override fun close() {}
    }
}
