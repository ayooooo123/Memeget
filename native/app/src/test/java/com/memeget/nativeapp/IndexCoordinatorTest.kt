package com.memeget.nativeapp

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.work.testing.WorkManagerTestInitHelper
import com.memeget.nativeapp.inference.ModelRegistry
import expo.modules.memegetbg.SubjectCutoutResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IndexCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var store: SqliteLibraryStore
    private lateinit var files: LibraryFiles
    private lateinit var testEngine: TestInferenceEngine
    private lateinit var knowledge: LocalKnowledge
    private lateinit var cloud: CloudAssistant

    @Before
    fun setup() = runBlocking {
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
        store.setSetting("background.enabled", "1")
        store.setSetting("background.pauseLowBattery", "0")
        store.setSetting("background.pauseHot", "0")
        files = LibraryFiles(context, store)
        testEngine = TestInferenceEngine()
        knowledge = LocalKnowledge(context, store, testEngine)
        cloud = CloudAssistant(context, store, testEngine)
    }

    @After
    fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun createValidBitmapFile(name: String = "sample.png"): File {
        val dir = File(context.cacheDir, "test_images").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        return file
    }

    private suspend fun awaitStage(entered: CompletableDeferred<Unit>, job: Job, coordinator: IndexCoordinator) {
        val reached = withTimeout(10_000L) {
            select {
                entered.onAwait { true }
                job.onJoin { entered.isCompleted }
            }
        }
        if (!reached) throw AssertionError("Session stopped before the expected stage: ${coordinator.progress.value}; ${store.errors()}")
    }

    @Test(timeout = 30_000)
    fun sessionDeadlineCancelsEngineAndSetsPausedStagePreservingPendingRows() = runBlocking {
        val file = createValidBitmapFile()
        val memeId = store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash1")

        val enteredInference = CompletableDeferred<Unit>()
        testEngine.onRecognize = {
            enteredInference.complete(Unit)
            awaitCancellation()
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 2_000L,
        )

        coordinator.runPending()

        assertTrue("Inference must have been entered", enteredInference.isCompleted)

        val progress = coordinator.progress.value
        assertEquals("paused", progress.stage)
        assertFalse(progress.running)
        assertNull(progress.memeId)

        val pendingMemes = store.pending(10)
        assertEquals(1, pendingMemes.size)
        assertEquals(memeId, pendingMemes[0].id)
        assertTrue(pendingMemes[0].pending)
        assertEquals(0, store.errors().size)
    }

    @Test(timeout = 30_000)
    fun userCancellationInterruptsActiveInferenceAndSetsCancelledStage() = runBlocking {
        val file = createValidBitmapFile()
        val memeId = store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash2")
        val otherFile = createValidBitmapFile("other.png")
        val otherId = store.insertPending(Uri.fromFile(otherFile).toString(), "sample.png", "image", 99L, "other-hash")

        val enteredInference = CompletableDeferred<Unit>()
        testEngine.onRecognize = {
            enteredInference.complete(Unit)
            awaitCancellation()
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 5000L,
        )

        val job = launch {
            try {
                coordinator.runPending()
            } catch (_: CancellationException) {}
        }

        awaitStage(enteredInference, job, coordinator)
        assertTrue(coordinator.progress.value.running)
        assertEquals(memeId, coordinator.progress.value.memeId)
        assertTrue(requireNotNull(store.meme(otherId)).pending)
        coordinator.cancel()
        job.join()

        val progress = coordinator.progress.value
        assertEquals("cancelled", progress.stage)
        assertFalse(progress.running)
        assertNull(progress.memeId)

        val pendingMemes = store.pending(10)
        assertEquals(2, pendingMemes.size)
        assertEquals(memeId, pendingMemes[0].id)
        assertTrue(pendingMemes[0].pending)
    }

    @Test(timeout = 30_000)
    fun naturalCompletionReachesCompleteStage() = runBlocking {
        val file = createValidBitmapFile()
        val memeId = store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash3")

        testEngine.onRecognize = {
            listOf(TextRegion("fast text", 1f, 0f, 0f, 1f, 1f))
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 5000L,
        )

        coordinator.runPending()

        val progress = coordinator.progress.value
        assertEquals("complete", progress.stage)
        assertFalse(progress.running)
        assertNull(progress.memeId)
        assertEquals(1, progress.done)
        assertEquals(0, store.pending(10).size)

        val meme = store.meme(memeId)
        assertNotNull(meme)
        assertFalse(meme!!.pending)
    }

    @Test(timeout = 30_000)
    fun retagDeadlinePausesAnUnfinishedPass() = runBlocking {
        val enteredEmbed = CompletableDeferred<Unit>()
        testEngine.onEmbedText = {
            enteredEmbed.complete(Unit)
            awaitCancellation()
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 2_000L,
        )

        coordinator.retag()

        assertTrue(enteredEmbed.isCompleted)
        val progress = coordinator.progress.value
        assertEquals("paused", progress.stage)
        assertFalse(progress.running)
    }

    @Test(timeout = 30_000)
    fun retagUserCancelSetsCancelledStage() = runBlocking {
        val enteredEmbed = CompletableDeferred<Unit>()
        testEngine.onEmbedText = {
            enteredEmbed.complete(Unit)
            awaitCancellation()
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 5000L,
        )

        val job = launch {
            try {
                coordinator.retag()
            } catch (_: CancellationException) {}
        }

        awaitStage(enteredEmbed, job, coordinator)
        coordinator.cancel()
        job.join()

        val progress = coordinator.progress.value
        assertEquals("cancelled", progress.stage)
        assertFalse(progress.running)
    }

    @Test(timeout = 30_000)
    fun retagAskedForWhileIndexingRunsOnceTheSessionEnds() = runBlocking {
        val file = createValidBitmapFile()
        store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash-retag")
        // Indexing holds the session in text recognition, then fails the item before classifying
        // it, so only a re-tag loads the labels that call the text model.
        val recognizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testEngine.onRecognize = {
            recognizing.complete(Unit)
            release.await()
            throw IllegalStateException("synthetic recognition failure")
        }
        val retagged = CompletableDeferred<Unit>()
        testEngine.onEmbedText = {
            retagged.complete(Unit)
            FloatArray(512).apply { this[1] = 1f }
        }
        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 10_000L,
        )

        val indexing = launch { coordinator.runPending() }
        awaitStage(recognizing, indexing, coordinator)
        coordinator.retag()
        assertFalse("indexing still owns the session", retagged.isCompleted)

        release.complete(Unit)
        indexing.join()
        withTimeout(10_000L) { retagged.await() }
    }

    @Test(timeout = 30_000)
    fun visionCancellationRestoresPendingStateUnderNonCancellable() = runBlocking {
        val file = createValidBitmapFile()
        val memeId = store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash4")

        store.setSetting("vision.enabled", "1")
        testEngine.onRecognize = { listOf(TextRegion("quick", 1f, 0f, 0f, 1f, 1f)) }

        val enteredVision = CompletableDeferred<Unit>()
        testEngine.onDescribe = { _, _ ->
            enteredVision.complete(Unit)
            awaitCancellation()
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 5000L,
        )

        val job = launch {
            try {
                coordinator.runPending()
            } catch (_: CancellationException) {}
        }

        awaitStage(enteredVision, job, coordinator)
        coordinator.cancel()
        job.join()

        val meme = store.meme(memeId)
        assertNotNull(meme)
        assertEquals("pending", meme!!.visionState)
    }

    @Test(timeout = 30_000)
    fun sessionLimitDuringCaptionsKeepsFinishedCaptionsAndRequeuesTheInterruptedMeme() = runBlocking {
        val first = store.insertPending(Uri.fromFile(createValidBitmapFile("a.png")).toString(), "a.png", "image", 100L, "hashA")
        val second = store.insertPending(Uri.fromFile(createValidBitmapFile("b.png")).toString(), "b.png", "image", 101L, "hashB")
        store.setSetting("vision.enabled", "1")
        var calls = 0
        testEngine.onDescribe = { _, _ ->
            if (++calls == 1) {
                "CAPTION: frog waving\nTAGS: frog, wave"
            } else {
                withContext(Dispatchers.IO) {
                    // Like the native runner: blocks until cancel(), then fails as an ordinary exception.
                    while (!testEngine.cancelled.get()) Thread.sleep(10)
                    throw RuntimeException("Inference cancelled")
                }
            }
        }
        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 4_000L,
        )

        coordinator.runPending()

        val memes = listOf(first, second).map { requireNotNull(store.meme(it)) }
        val captioned = memes.single { it.visionState == "done" }
        assertEquals("frog waving", captioned.caption)
        assertEquals("pending", memes.single { it.id != captioned.id }.visionState)
        assertTrue(store.errors().none { it.startsWith("[vision]") })
    }

    @Test(timeout = 30_000)
    fun stopRightAfterTheCaptionMarkerCommitsReturnsTheMemeToPending() = runBlocking {
        val memeId = store.insertPending(Uri.fromFile(createValidBitmapFile()).toString(), "sample.png", "image", 100L, "hashM")
        store.setSetting("vision.enabled", "1")
        var coordinator: IndexCoordinator? = null
        val stopAfterMarker = object : LibraryStore by store {
            override suspend fun setCaption(id: Long, caption: String, tags: List<Tag>, vector: FloatArray?, state: String) {
                store.setCaption(id, caption, tags, vector, state)
                if (state == "failed") {
                    // The marker is committed; Stop lands before the call returns, as withContext allows.
                    requireNotNull(coordinator).cancel()
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        coordinator = IndexCoordinator(
            context = context,
            store = stopAfterMarker,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 10_000L,
        )

        launch {
            try {
                requireNotNull(coordinator).runPending()
            } catch (_: CancellationException) {}
        }.join()

        assertEquals("pending", requireNotNull(store.meme(memeId)).visionState)
    }

    @Test(timeout = 30_000)
    fun captionsWaitUntilTheQueueIsIndexedThenUseTheRestOfTheSession() = runBlocking {
        val ids = (1..9).map { n ->
            store.insertPending(Uri.fromFile(createValidBitmapFile("m$n.png")).toString(), "m$n.png", "image", 100L + n, "hashQ$n")
        }
        store.setSetting("vision.enabled", "1")
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        testEngine.onRecognize = { events += "index"; listOf(TextRegion("text", 1f, 0f, 0f, 1f, 1f)) }
        testEngine.onDescribe = { _, _ -> events += "describe"; "CAPTION: a frog\nTAGS: frog" }
        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 25_000L,
        )

        coordinator.runPending()

        // More memes than one batch: all are indexed before the first caption, and all get captions in one session.
        assertTrue(ids.all { requireNotNull(store.meme(it)).visionState == "done" })
        assertEquals(9, events.count { it == "describe" })
        assertTrue("captions must wait for indexing: $events", events.indexOf("describe") > events.lastIndexOf("index"))
    }

    @Test(timeout = 30_000)
    fun overlappingSessionCannotConsumeTheActivePendingItem() = runBlocking {
        val file = createValidBitmapFile()
        val id = store.insertPending(Uri.fromFile(file).toString(), "sample.png", "image", 100L, "hash5")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testEngine.onRecognize = {
            entered.complete(Unit)
            release.await()
            listOf(TextRegion("text", 1f, 0f, 0f, 1f, 1f))
        }

        val coordinator = IndexCoordinator(
            context = context,
            store = store,
            files = files,
            engine = testEngine,
            knowledge = knowledge,
            cloud = cloud,
            sessionTimeoutMillis = 5000L,
        )

        val job = launch { coordinator.runPending() }
        awaitStage(entered, job, coordinator)
        try {
            withTimeout(1_000L) { coordinator.runPending() }
            assertTrue(requireNotNull(store.meme(id)).pending)
        } finally {
            release.complete(Unit)
        }
        job.join()
        val indexed = requireNotNull(store.meme(id))
        assertFalse(indexed.pending)
        assertEquals("text", indexed.ocr)
        assertEquals(1, coordinator.progress.value.done)
    }

    private class TestInferenceEngine : InferenceEngine {
        val cancelled = AtomicBoolean(false)

        var onRecognize: (suspend (String) -> List<TextRegion>)? = null
        var onDescribe: (suspend (String, String) -> String)? = null
        var onTranscribe: (suspend (String) -> String)? = null
        var onEmbedText: (suspend (String) -> FloatArray)? = null

        private val _progress = MutableStateFlow(ModelProgress())
        override val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT

        override suspend fun embedImage(uri: String): FloatArray {
            if (cancelled.get()) throw CancellationException("Cancelled")
            return FloatArray(512).apply { this[0] = 1f }
        }

        override suspend fun embedText(text: String): FloatArray {
            if (cancelled.get()) throw CancellationException("Cancelled")
            onEmbedText?.let { return it(text) }
            return FloatArray(512).apply { this[1] = 1f }
        }

        override suspend fun embedVisual(uri: String): FloatArray {
            if (cancelled.get()) throw CancellationException("Cancelled")
            return FloatArray(768).apply { this[0] = 1f }
        }

        override suspend fun recognize(uri: String): List<TextRegion> {
            if (cancelled.get()) throw CancellationException("Cancelled")
            onRecognize?.let { return it(uri) }
            return listOf(TextRegion("default text", 0.9f, 0f, 0f, 1f, 1f))
        }

        override suspend fun describe(uri: String, grounding: String): String {
            if (cancelled.get()) throw CancellationException("Cancelled")
            onDescribe?.let { return it(uri, grounding) }
            return "sample description [topic: meme]"
        }

        override suspend fun transcribe(uri: String): String {
            if (cancelled.get()) throw CancellationException("Cancelled")
            onTranscribe?.let { return it(uri) }
            return "sample transcript"
        }

        override suspend fun segment(uri: String, requestId: String): SubjectCutoutResult {
            throw UnsupportedOperationException("Not needed in coordinator tests")
        }

        override fun cancel() {
            cancelled.set(true)
        }

        override suspend fun unload() {}

        override fun close() {
            cancel()
        }
    }
}
