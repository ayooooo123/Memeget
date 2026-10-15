package com.memeget.nativeapp

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
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
    fun retagKeepsRunningAfterTheScreenThatAskedForItGoesAway() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testEngine.onEmbedText = {
            entered.complete(Unit)
            release.await()
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

        // A screen's coroutine asks for the re-tag; then the screen leaves and its scope is cancelled.
        val screen = launch { coordinator.retag() }
        awaitStage(entered, screen, coordinator)
        screen.cancel()
        screen.join()
        release.complete(Unit)

        val finished = withTimeout(10_000L) { coordinator.progress.first { !it.running && it.stage != "retag" } }
        assertEquals("complete", finished.stage)
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
    fun stopRightAfterTheTranscriptMarkerCommitsReturnsTheClipToTheQueue() = runBlocking {
        // An indexed video. Robolectric cannot decode video frames, so its poster stands in for them.
        val poster = Uri.fromFile(createValidBitmapFile("poster.png")).toString()
        val memeId = store.insertPending(Uri.fromFile(createValidBitmapFile("clip.mp4")).toString(), "clip.mp4", "video", 100L, "hashT")
        store.completeIndex(memeId, LocalAnalysis(FloatArray(512).apply { this[0] = 1f }, "", emptyList(), ""), ModelRegistry.PRIMARY_MODEL_FINGERPRINT, poster)
        store.setSetting("audio.enabled", "1")
        var coordinator: IndexCoordinator? = null
        var markerCommitted = false
        val stopAfterMarker = object : LibraryStore by store {
            override suspend fun setAudioState(id: Long, state: String) {
                store.setAudioState(id, state)
                if (state == "failed") {
                    markerCommitted = true
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

        assertTrue("the session never reached the transcript marker: ${store.errors()}", markerCommitted)
        assertEquals("pending", requireNotNull(store.meme(memeId)).audioState)
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

    private fun plugInCharger() {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                .putExtra(BatteryManager.EXTRA_LEVEL, 100)
                .putExtra(BatteryManager.EXTRA_SCALE, 100),
        )
    }

    private fun queuedHandovers(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("memeget-native-continue").get()

    /** Two memes to caption; the second caption blocks until the session limit stops it. */
    private suspend fun captionSessionThatRunsOutOfTime(): IndexCoordinator {
        store.setSetting("vision.enabled", "1")
        var calls = 0
        testEngine.onDescribe = { _, _ ->
            if (++calls == 1) {
                "CAPTION: frog waving\nTAGS: frog, wave"
            } else {
                withContext(Dispatchers.IO) {
                    while (!testEngine.cancelled.get()) Thread.sleep(10)
                    throw RuntimeException("Inference cancelled")
                }
            }
        }
        return IndexCoordinator(context, store, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 4_000L)
    }

    @Test(timeout = 30_000)
    fun chargingSessionWithWorkLeftQueuesTheNextOneAndStopCancelsIt() = runBlocking {
        store.insertPending(Uri.fromFile(createValidBitmapFile("a.png")).toString(), "a.png", "image", 100L, "hashC1")
        store.insertPending(Uri.fromFile(createValidBitmapFile("b.png")).toString(), "b.png", "image", 101L, "hashC2")
        plugInCharger()
        val coordinator = captionSessionThatRunsOutOfTime()

        coordinator.runPending()

        assertEquals(listOf(WorkInfo.State.ENQUEUED), queuedHandovers().map { it.state })
        coordinator.cancel()
        assertEquals(listOf(WorkInfo.State.CANCELLED), queuedHandovers().map { it.state })
    }

    @Test(timeout = 30_000)
    fun chargingSessionThatEndsWarmQueuesTheNextOneAfterACoolDown() = runBlocking {
        store.insertPending(Uri.fromFile(createValidBitmapFile("a.png")).toString(), "a.png", "image", 100L, "hashW1")
        store.insertPending(Uri.fromFile(createValidBitmapFile("b.png")).toString(), "b.png", "image", 101L, "hashW2")
        store.setSetting("vision.enabled", "1")
        store.setSetting("background.pauseHot", "1")
        plugInCharger()
        // The phone warms up as the session indexes.
        val warming = object : LibraryStore by store {
            override suspend fun completeIndex(id: Long, analysis: LocalAnalysis, model: String, thumbUri: String) {
                store.completeIndex(id, analysis, model, thumbUri)
                shadowOf(context.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
            }
        }
        val coordinator = IndexCoordinator(context, warming, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 10_000L)

        coordinator.runPending()

        assertEquals("Paused while the device cools", coordinator.progress.value.detail)
        // Waiting for the next 30-minute run would leave the phone idle long after it has cooled.
        val handover = queuedHandovers().single()
        assertEquals(WorkInfo.State.ENQUEUED, handover.state)
        assertTrue("a warm phone gets time to cool: ${handover.initialDelayMillis} ms", handover.initialDelayMillis >= TimeUnit.MINUTES.toMillis(5))
    }

    @Test(timeout = 30_000)
    fun sessionOnBatteryLeavesTheRestForTheRegularSchedule() = runBlocking {
        val ids = listOf(
            store.insertPending(Uri.fromFile(createValidBitmapFile("a.png")).toString(), "a.png", "image", 100L, "hashB1"),
            store.insertPending(Uri.fromFile(createValidBitmapFile("b.png")).toString(), "b.png", "image", 101L, "hashB2"),
        )
        val coordinator = captionSessionThatRunsOutOfTime()

        coordinator.runPending()

        // Work is left, but without a charger it waits for the regular schedule.
        assertTrue(ids.any { requireNotNull(store.meme(it)).visionState == "pending" })
        assertTrue(queuedHandovers().isEmpty())
    }

    @Test(timeout = 30_000)
    fun chargingSessionThatOnlyTranscribesStillCountsAsProgress() = runBlocking {
        // An indexed video that needs a transcript and a caption; the session only gets the transcript done.
        val poster = Uri.fromFile(createValidBitmapFile("poster.png")).toString()
        val memeId = store.insertPending(Uri.fromFile(createValidBitmapFile("clip.mp4")).toString(), "clip.mp4", "video", 100L, "hashV")
        store.completeIndex(memeId, LocalAnalysis(FloatArray(512).apply { this[0] = 1f }, "", emptyList(), ""), ModelRegistry.PRIMARY_MODEL_FINGERPRINT, poster)
        plugInCharger()
        val coordinator = captionSessionThatRunsOutOfTime()
        store.setSetting("audio.enabled", "1")
        testEngine.onDescribe = { _, _ ->
            withContext(Dispatchers.IO) {
                while (!testEngine.cancelled.get()) Thread.sleep(10)
                throw RuntimeException("Inference cancelled")
            }
        }

        coordinator.runPending()

        val meme = requireNotNull(store.meme(memeId))
        assertEquals("done", meme.audioState)
        assertEquals("pending", meme.visionState)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), queuedHandovers().map { it.state })
    }

    @Test(timeout = 30_000)
    fun chargingSessionWithoutProgressQueuesNothing() = runBlocking {
        val file = createValidBitmapFile("gone.png")
        val id = store.insertPending(Uri.fromFile(file).toString(), "gone.png", "image", 100L, "hashG")
        plugInCharger()
        val coordinator = IndexCoordinator(context, store, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 10_000L)
        coordinator.runPending()
        assertFalse(requireNotNull(store.meme(id)).pending)

        // Captions turn on after the file is gone, so the one remaining caption fails every time.
        assertTrue(file.delete())
        store.setSetting("vision.enabled", "1")
        coordinator.runPending()

        assertEquals("pending", requireNotNull(store.meme(id)).visionState)
        assertTrue(queuedHandovers().isEmpty())
    }

    @Test(timeout = 30_000)
    fun anImageModelThatCannotBeDownloadedLeavesTheQueueWaitingInsteadOfFailingEveryMeme() = runBlocking {
        val ids = (1..3).map { n ->
            store.insertPending(Uri.fromFile(createValidBitmapFile("d$n.png")).toString(), "d$n.png", "image", 100L + n, "hashD$n")
        }
        var attempts = 0
        testEngine.onEmbedImage = {
            attempts++
            throw ModelUnavailableException("mobileclip-s2-image", "Couldn't download mobileclip_s2_image_xnnpack_fp32.pte: Unable to resolve host")
        }
        val coordinator = IndexCoordinator(context, store, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 10_000L)

        coordinator.runPending()

        // A phone that can't reach the download says nothing about these memes: they stay queued, unmarked.
        assertTrue(ids.map { requireNotNull(store.meme(it)) }.all { it.pending && it.indexError.isEmpty() })
        // One attempt and one report per session, not one per meme.
        assertEquals(1, attempts)
        assertEquals(listOf("[model] mobileclip-s2-image"), store.errors().map { it.substringBefore(" - ") })
        val progress = coordinator.progress.value
        assertEquals("paused", progress.stage)
        assertTrue(progress.detail, progress.detail.contains("Unable to resolve host"))
    }

    @Test(timeout = 30_000)
    fun anImageModelThatCannotBeDownloadedStillLetsTheSessionTranscribeIndexedVideos() = runBlocking {
        // An indexed video waiting for its transcript, and a new meme waiting for the image model. Robolectric cannot
        // decode video frames, so the poster stands in for them.
        val poster = Uri.fromFile(createValidBitmapFile("poster.png")).toString()
        val videoId = store.insertPending(Uri.fromFile(createValidBitmapFile("clip.mp4")).toString(), "clip.mp4", "video", 100L, "hashI1")
        store.completeIndex(videoId, LocalAnalysis(FloatArray(512).apply { this[0] = 1f }, "", emptyList(), ""), ModelRegistry.PRIMARY_MODEL_FINGERPRINT, poster)
        val newId = store.insertPending(Uri.fromFile(createValidBitmapFile("new.png")).toString(), "new.png", "image", 101L, "hashI2")
        store.setSetting("audio.enabled", "1")
        testEngine.onEmbedImage = {
            throw ModelUnavailableException("mobileclip-s2-image", "Couldn't download mobileclip_s2_image_xnnpack_fp32.pte: timeout")
        }
        val coordinator = IndexCoordinator(context, store, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 10_000L)

        coordinator.runPending()

        // The new meme waits for its model; the transcript needs only the speech model, so the session still does it
        // and then ends, rather than going back to the waiting meme until its time runs out.
        assertTrue(requireNotNull(store.meme(newId)).pending)
        assertEquals("done", requireNotNull(store.meme(videoId)).audioState)
        assertTrue(coordinator.progress.value.detail, coordinator.progress.value.detail.contains("timeout"))
    }

    @Test(timeout = 30_000)
    fun speechAndCaptionModelsThatCannotBeDownloadedPutTheirWorkBackInTheQueue() = runBlocking {
        // Two indexed videos waiting for transcripts and captions. Robolectric cannot decode video frames, so their
        // posters stand in for them.
        val ids = (1..2).map { n ->
            val poster = Uri.fromFile(createValidBitmapFile("poster$n.png")).toString()
            val id = store.insertPending(Uri.fromFile(createValidBitmapFile("clip$n.mp4")).toString(), "clip$n.mp4", "video", 100L + n, "hashU$n")
            store.completeIndex(id, LocalAnalysis(FloatArray(512).apply { this[0] = 1f }, "", emptyList(), ""), ModelRegistry.PRIMARY_MODEL_FINGERPRINT, poster)
            id
        }
        store.setSetting("audio.enabled", "1")
        store.setSetting("vision.enabled", "1")
        var transcribeAttempts = 0
        var describeAttempts = 0
        testEngine.onTranscribe = {
            transcribeAttempts++
            throw ModelUnavailableException("whisper-small-en-int8", "Couldn't download whisper_small_en_xnnpack_int8.pte: timeout")
        }
        testEngine.onDescribe = { _, _ ->
            describeAttempts++
            throw ModelUnavailableException("minicpm-v-4.7-1b-q4km", "Couldn't download MiniCPM-V-4_7-1B-Q4_K_M.gguf: timeout")
        }
        val coordinator = IndexCoordinator(context, store, files, testEngine, knowledge, cloud, sessionTimeoutMillis = 10_000L)

        coordinator.runPending()

        val memes = ids.map { requireNotNull(store.meme(it)) }
        assertTrue("transcripts must wait, not fail: ${memes.map { it.audioState }}", memes.all { it.audioState == "pending" })
        assertTrue("captions must wait, not fail: ${memes.map { it.visionState }}", memes.all { it.visionState == "pending" })
        assertEquals(1, transcribeAttempts)
        assertEquals(1, describeAttempts)
        assertEquals(
            listOf("[model] minicpm-v-4.7-1b-q4km", "[model] whisper-small-en-int8"),
            store.errors().map { it.substringBefore(" - ") }.sorted(),
        )
    }

    private class TestInferenceEngine : InferenceEngine {
        val cancelled = AtomicBoolean(false)

        var onEmbedImage: (suspend (String) -> FloatArray)? = null
        var onRecognize: (suspend (String) -> List<TextRegion>)? = null
        var onDescribe: (suspend (String, String) -> String)? = null
        var onTranscribe: (suspend (String) -> String)? = null
        var onEmbedText: (suspend (String) -> FloatArray)? = null

        private val _progress = MutableStateFlow(ModelProgress())
        override val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT

        override suspend fun embedImage(uri: String): FloatArray {
            if (cancelled.get()) throw CancellationException("Cancelled")
            onEmbedImage?.let { return it(uri) }
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
