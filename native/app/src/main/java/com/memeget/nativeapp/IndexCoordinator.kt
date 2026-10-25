package com.memeget.nativeapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import expo.modules.memegetbg.KeepAliveLease
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.storage.FrameLikeness
import com.memeget.nativeapp.storage.MediaIoUtils
import com.memeget.nativeapp.storage.Mp4Repair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Durable rows are the queue; only one foreground/background session owns the engine pipeline. */
internal class IndexCoordinator(
    private val context: Context,
    private val store: LibraryStore,
    private val files: LibraryFiles,
    private val engine: InferenceEngine,
    private val knowledge: LocalKnowledge,
    private val cloud: CloudAssistant,
    private val sessionTimeoutMillis: Long = SESSION_MILLIS,
    private val sessionGraceMillis: Long = SESSION_GRACE_MILLIS,
    /** Session length on the charger when a foreground service keeps the app alive; see [runSession]. */
    private val longSessionMillis: Long = LONG_SESSION_MILLIS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Mutex()
    private val mutableProgress = MutableStateFlow(WorkProgress())
    val progress: StateFlow<WorkProgress> = mutableProgress.asStateFlow()
    @Volatile private var activeJob: Job? = null
    /** Set when a session's hard stop, not Stop, cancels it. */
    @Volatile private var hardStop = false
    /** Clips a background session can't finish in time; only a session started in the app transcribes them. */
    @Volatile private var backgroundSkip: Set<Long> = emptySet()
    /**
     * Re-tags asked for, and how many of those a started pass or Stop has covered. Requests count
     * up before anyone tries the lock, so the session that holds it always sees them on unlock.
     */
    private val retagRequests = AtomicLong()
    private val retagCovered = AtomicLong()
    @Volatile private var interactive = false
    /** Set when [setInteractive] cancels an in-flight enrichment call; cleared once the session handles it. */
    @Volatile private var interactiveInterrupted = false
    /** Set when [setInteractive] leaves interactive mode while a session still holds [session]. */
    @Volatile private var resumeAfterSession = false
    fun setInteractive(value: Boolean) {
        val was = interactive
        interactive = value
        if (value) {
            resumeAfterSession = false
            if (mutableProgress.value.stage in setOf("local description", "local transcription", "visual embedding")) {
                interactiveInterrupted = true
                engine.cancel()
            }
        } else if (was) {
            if (session.isLocked) {
                resumeAfterSession = true
            } else {
                resumePending()
            }
        }
    }

    /** Stop presses so far; a run started with untilDone ends at the first one after it started. */
    private val stopRequests = AtomicLong()

    /**
     * Indexes [ids] again, or with no ids everything pending plus items whose analysis failed. With [untilDone], while
     * the phone charges, each session that got something done hands straight over to the next until no work is left,
     * the phone is unplugged, or Stop; on battery it is one session, as without it.
     *
     * These sessions keep the app alive with a `dataSync` foreground service, which Android 15 and later allow six
     * hours in the background per day. When Android ends it, the session in progress stops as Stop would, and
     * background sessions (WorkManager, which doesn't need it) take over while the phone charges.
     */
    fun start(ids: List<Long>? = null, untilDone: Boolean = false): Job {
        val stopsBefore = stopRequests.get()
        return scope.launch {
            val run = coroutineContext.job
            val keepAliveLost = AtomicBoolean(false)
            val onKeepAliveLost: () -> Unit = {
                keepAliveLost.set(true)
                run.cancel()
            }
            KeepAliveLease.addLostListener(onKeepAliveLost)
            try {
                if (ids == null) store.requeueFailed() else if (ids.isNotEmpty()) store.requeue(ids.distinct())
                var again = true
                while (again && stopRequests.get() == stopsBefore) again = runSession(background = false, untilDone = untilDone)
            } catch (cancelled: CancellationException) {
                if (keepAliveLost.get()) withContext(NonCancellable) { handOverAfterKeepAliveLost() }
                throw cancelled
            } catch (failure: Exception) {
                mutableProgress.value = WorkProgress(stage = "failed", error = failure.message ?: "Indexing failed")
            } finally {
                KeepAliveLease.removeLostListener(onKeepAliveLost)
            }
        }
    }

    private suspend fun handOverAfterKeepAliveLost() {
        mutableProgress.value = WorkProgress(
            stage = "paused",
            detail = "Android's six-hour background limit stopped this session; unfinished items remain queued",
        )
        if (store.setting("background.enabled") == "1" && charging()) continueWhileCharging()
    }

    /**
     * Looks for new files in every linked folder, then indexes, as the original app's Index does. Runs in this
     * coordinator's scope, so leaving the screen that asked stops neither the scan nor the session. Returns how many
     * new files the scan found; a folder that can't be read is logged and skipped.
     */
    suspend fun scanAndStart(): Int = scope.async {
        var found = 0
        for (folder in store.folders()) {
            try {
                found += files.scan(folder).size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                store.addError(folder.name, "scan", failure.message ?: "Folder could not be read")
            }
        }
        start()
        found
    }.await()
    fun cancel() {
        stopRequests.incrementAndGet()
        retagCovered.accumulateAndGet(retagRequests.get(), ::maxOf)
        activeJob?.cancel()
        engine.cancel()
        cloud.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(CONTINUE_WORK)
    }
    /**
     * Re-tags the library now or, when another session holds the lock, as soon as it ends. The pass
     * runs in the coordinator's scope and the caller only waits for it, so a caller that stops
     * waiting, such as a screen the user left, does not stop the re-tag.
     */
    suspend fun retag() {
        retagRequests.incrementAndGet()
        scope.launch { runOwedRetag() }.join()
    }

    private suspend fun runOwedRetag() {
        if (!session.tryLock()) return
        // Published before the coverage check: Stop raises coverage before reading the job, so a Stop
        // that misses this check still finds the job and cancels it.
        activeJob = currentCoroutineContext()[Job]
        val upTo = retagRequests.get()
        if (upTo <= retagCovered.get()) {
            activeJob = null
            session.unlock()
            launchOwedRetag()
            return
        }
        retagCovered.accumulateAndGet(upTo, ::maxOf)
        try {
            val completed = withSessionDeadline(sessionTimeoutMillis) {
                knowledge.retag { mutableProgress.value = it }
                true
            }
            if (completed == null && mutableProgress.value.stage != "complete") {
                mutableProgress.value = WorkProgress(
                    stage = "paused",
                    done = mutableProgress.value.done,
                    total = mutableProgress.value.total,
                    detail = "Re-tagging paused: session limit reached",
                    running = false,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableProgress.value = mutableProgress.value.copy(
                stage = "cancelled",
                running = false,
                detail = "Re-tagging cancelled",
            )
            throw cancelled
        } catch (failure: Exception) {
            mutableProgress.value = WorkProgress(
                stage = "failed",
                error = failure.message ?: "Re-tagging failed",
                running = false,
            )
        } finally {
            try {
                withContext(NonCancellable) { engine.unload() }
            } finally {
                activeJob = null
                mutableProgress.value = mutableProgress.value.copy(running = false)
                session.unlock()
                launchOwedRetag()
            }
        }
    }

    private fun launchOwedRetag() {
        if (retagRequests.get() > retagCovered.get()) scope.launch { runOwedRetag() }
    }

    fun schedule() {
        scope.launch {
            val manager = WorkManager.getInstance(context)
            if (store.setting("background.enabled") != "1") {
                manager.cancelUniqueWork(PERIODIC_WORK)
                manager.cancelUniqueWork(CONTINUE_WORK)
                return@launch
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(store.setting("background.onlyCharging") == "1")
                .setRequiresBatteryNotLow(store.setting("background.pauseLowBattery") != "0")
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<IndexWorker>(30, TimeUnit.MINUTES).setConstraints(constraints).build())
            manager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build())
        }
    }
    /** [longRunning]: WorkManager gave the session a foreground service, so it isn't held to a regular job's 10 minutes. */
    suspend fun runPending(longRunning: Boolean = false) {
        runSession(background = true, longRunning = longRunning)
    }

    /**
     * [runPending] for a screen: the session runs in this coordinator's scope, so closing the screen or an activity
     * restart neither stops it nor cuts off a model download it started.
     */
    fun resumePending() {
        scope.launch {
            try {
                if (store.pending(1).isNotEmpty() || (charging() && remainingWork() > 0)) runSession(background = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableProgress.value = WorkProgress(stage = "failed", error = failure.message ?: "Indexing failed")
            }
        }
    }
    /**
     * Returns true when a session started with [untilDone] should be followed by another: the phone charges, work is
     * left, and this one got something done. A background session queues its successor itself and returns false.
     *
     * Sessions last seven minutes, so a background one fits a regular job's ten. On the charger, a run started with
     * [untilDone] or a background session that got a foreground service ([longRunning]) lasts [longSessionMillis]
     * instead: every session start and end unloads and reloads the 1.7 GB caption model and costs about 75 s on the
     * Pixel 5a between seven-minute sessions.
     */
    private suspend fun runSession(background: Boolean, untilDone: Boolean = false, longRunning: Boolean = false): Boolean {
        if (background && store.setting("background.enabled") != "1") return false
        if (!session.tryLock()) return false
        var handOver = false
        activeJob = currentCoroutineContext()[Job]
        val started = SystemClock.elapsedRealtime()
        val limit = if ((untilDone || longRunning) && charging()) longSessionMillis else sessionTimeoutMillis
        var completed = 0
        var failed = 0
        var held = false
        var pause: String? = null
        var completedNaturally = false
        val gaps = ModelGaps(store)

        try {
            mutableProgress.value = WorkProgress("indexing", running = true)
            hardStop = false
            backgroundSkip = if (background) backgroundSkips() else emptySet()
            // Transcripts made by an earlier speech model (or imported from the original app's tiny.en) are redone once.
            if (store.setting("audio.enabled") == "1") store.requeueTranscripts(ModelRegistry.WHISPER_MODEL.id)
            recoverInterrupted()
            val remainingBefore = if (background || untilDone) remainingWork() else 0
            if (!background) {
                KeepAliveLease.acquire(context, "Memeget Native", "Indexing saved media")
                held = true
            }

            val sessionFinished = withSessionDeadline(limit + sessionGraceMillis) {
                val tried = HashSet<Long>()
                val captionTexts = HashMap<Long, Pair<String, String>>()
                val triedSpeech = HashSet<Long>()
                // Memes waiting to be indexed come first: transcripts and captions stop for them between clips. A
                // background session stops counting them at its item cap, and none count while the image model is
                // missing: those memes stay queued and indexing can't take them.
                val newMemesWaiting: suspend () -> Boolean = {
                    (!background || completed + failed < BACKGROUND_ITEMS) && "index" !in gaps && store.pending(1).isNotEmpty()
                }
                // Once the session's time is up no new item starts, but one already running may finish within the
                // grace: on the Pixel 5a a background session needs about 6.5 minutes for a 120 s clip, and cutting it
                // off at the limit sent it back to the front of the queue every session.
                val stopForNext: suspend () -> Boolean = {
                    SystemClock.elapsedRealtime() - started >= limit || newMemesWaiting()
                }
                // Indexing, then enrichment; a meme shared or imported during enrichment sends the session back to
                // indexing, so it becomes searchable now instead of waiting behind the transcript and caption backlog.
                session@ while (true) {
                while (SystemClock.elapsedRealtime() - started < limit) {
                    currentCoroutineContext().ensureActive()
                    pause = powerBlock(background)
                    if (pause != null || (background && interactive) || "index" in gaps) break
                    val pending = store.pending(BATCH_SIZE)
                    if (pending.isEmpty()) break
                    val prepared = ArrayList<Prepared>(pending.size)
                    var stagedBytes = 0L
                    try {
                        // Run the image family for the batch, then OCR for the batch: no per-frame model thrash.
                        for (meme in pending) {
                            currentCoroutineContext().ensureActive()
                            if (prepared.isNotEmpty() && stagedBytes >= BATCH_DISK_BYTES) break
                            mutableProgress.value = WorkProgress("embedding", completed, completed + pending.size, meme.name, true, memeId = meme.id)
                            try {
                                // A collection import supplied the vector and OCR: reuse them once instead of the models.
                                val importedVector = if (meme.analysisImported) {
                                    store.vectors(meme.id)?.takeIf { it.model == engine.primaryModel && it.image.size == 512 }?.image
                                } else null
                                val item = prepare(meme, importedVector, importedFor = meme.contentHash.takeIf { importedVector != null })
                                prepared += item
                                stagedBytes += item.source.length()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (unavailable: ModelUnavailableException) {
                                // The model is missing, not this meme: it stays queued for the next session.
                                gaps.note("index", unavailable)
                                break
                            } catch (failure: Exception) {
                                failed++
                                store.markIndexFailed(meme.id, failure.message ?: "Media embedding failed")
                            }
                        }
                        val indexed = ArrayList<Prepared>(prepared.size)
                        for (item in prepared) {
                            currentCoroutineContext().ensureActive()
                            mutableProgress.value = WorkProgress("reading text", completed, completed + prepared.size, item.meme.name, true, memeId = item.meme.id)
                            try {
                                val ocr = if (item.imported) item.meme.ocr else {
                                    val texts = ArrayList<String>(item.frames.size)
                                    for (uri in item.frames) {
                                        val text = engine.recognize(uri).joinToString(" ") { it.text }.trim()
                                        if (text.isNotEmpty() && texts.none { it.contains(text, ignoreCase = true) }) {
                                            texts.removeAll { text.contains(it, ignoreCase = true) }
                                            texts += text
                                        }
                                    }
                                    texts.joinToString("\n")
                                }
                                val (localTags, terms) = knowledge.classify(item.image, ocr, item.meme.id)
                                val hints = cloud.candidateHints(item.image, contentHash = item.hash)
                                    .filterNot { hint -> localTags.any { it.label.equals(hint.label, true) } }
                                val tags = localTags + hints
                                store.setContentHash(item.meme.id, item.hash)
                                store.completeIndex(
                                    item.meme.id,
                                    LocalAnalysis(item.image, ocr, tags, terms, item.sceneVectors),
                                    engine.primaryModel,
                                    item.thumbnail,
                                )
                                indexed += item
                                completed++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (unavailable: ModelUnavailableException) {
                                gaps.note("index", unavailable)
                                break
                            } catch (failure: Exception) {
                                failed++
                                store.markIndexFailed(item.meme.id, failure.message ?: "Text recognition failed")
                            }
                        }
                        // Captions wait until the queue is indexed; the enrichment pass below writes them. Transcripts
                        // of this batch wait too while more memes are queued: the enrichment pass picks them up.
                        enrich(indexed, background, gaps, captionTexts, describe = false, yieldTo = stopForNext)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            prepared.forEach(::cleanPrepared)
                        }
                    }
                    KeepAliveLease.update(context, "Memeget Native", "$completed indexed; $failed failed", completed, completed + store.pending(BATCH_SIZE).size)
                    if (background && completed + failed >= BACKGROUND_ITEMS) break
                }

                // Already-indexed items need optional enrichment too. Captions only happen here, after the queue is
                // indexed, and keep going until the session ends.
                if (pause == null && SystemClock.elapsedRealtime() - started < limit && !interactive && !resumeAfterSession) {
                    val vision = store.setting("vision.enabled") == "1"
                    val audio = store.setting("audio.enabled") == "1"
                    val visual = store.setting("visual.enabled") == "1"
                    if (vision || audio || visual) {

                        while (SystemClock.elapsedRealtime() - started < limit && powerBlock(background) == null && !interactive && !resumeAfterSession) {
                            if (newMemesWaiting()) {
                                // The batch this cut short goes back in the queue; failed items already left it.
                                tried.clear()
                                triedSpeech.clear()
                                continue@session
                            }
                            // Work whose model could not be fetched this session is left for the next one.
                            val captions = vision && "vision" !in gaps
                            val speech = audio && "audio" !in gaps
                            val appearance = visual && "visual" !in gaps
                            // Videos waiting for a transcript go first, in batches that skip captions: on the Pixel 5a
                            // small.en transcribes a 30 s clip in about 30 s, while captioning a video takes about a
                            // minute, and the caption backlog is several times longer.
                            val transcripts = if (speech) {
                                store.allMemes().filter { meme ->
                                    meme.id !in triedSpeech && meme.id !in backgroundSkip && !meme.pending && meme.kind == "video" && meme.audioState == "pending"
                                }.take(BATCH_SIZE).toList()
                            } else emptyList()
                            val candidates = transcripts.ifEmpty {
                                store.allMemes().filter { meme ->
                                    meme.id !in tried && !meme.pending && (captions && meme.visionState == "pending" ||
                                        speech && meme.kind == "video" && meme.audioState == "pending" && meme.id !in backgroundSkip ||
                                        appearance && store.vectors(meme.id)?.visual == null)
                                }.take(BATCH_SIZE).toList()
                            }
                            if (candidates.isEmpty()) break
                            val batch = ArrayList<Prepared>(candidates.size)
                            var stagedBytes = 0L
                            val audioOnlyBatch = transcripts.isNotEmpty()
                            try {
                                for (meme in candidates) {
                                    if (batch.isNotEmpty() && stagedBytes >= BATCH_DISK_BYTES) break
                                    if (audioOnlyBatch) triedSpeech += meme.id else tried += meme.id
                                    val vectors = store.vectors(meme.id)?.takeIf { it.model == engine.primaryModel } ?: continue
                                    mutableProgress.value = WorkProgress("preparing media", detail = meme.name, running = true, memeId = meme.id)
                                    val memeNeedsAudio = speech && meme.kind == "video" && meme.audioState == "pending" && meme.id !in backgroundSkip
                                    try {
                                        prepare(
                                            meme = meme,
                                            knownVector = vectors.image,
                                            extractFrames = !audioOnlyBatch,
                                            requireSource = audioOnlyBatch || memeNeedsAudio,
                                        ).also {
                                            batch += it
                                            stagedBytes += if (it.source.exists()) it.source.length() else 0L
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        // One unreadable item must not end every session before the rest are enriched.
                                        store.addError(meme.name, "enrichment", failure.message ?: "Media could not be prepared")
                                    }
                                }
                                // One call per batch, so each model loads once per batch rather than once per meme.
                                enrich(batch, background, gaps, captionTexts, describe = !audioOnlyBatch, yieldTo = stopForNext)
                            } finally {
                                withContext(NonCancellable + Dispatchers.IO) { batch.forEach(::cleanPrepared) }
                            }
                        }
                    }
                }
                break
                }
                if (store.setting("vision.enabled") == "1") embedCaptions(captionTexts, gaps)

                val remaining = store.pending(1).isNotEmpty()
                val waiting = gaps.first?.let { "${it.message}. $MODEL_WAIT" }
                mutableProgress.value = WorkProgress(
                    stage = if (pause != null || waiting != null || remaining) "paused" else "complete",
                    done = completed, total = completed + failed,
                    detail = pause ?: waiting ?: if (remaining) "More items are saved and waiting. Resume indexing or enable background work." else "$completed indexed; $failed failed",
                    running = false,
                )
                completedNaturally = true
                true
            }

            if (sessionFinished == null && !completedNaturally && mutableProgress.value.stage != "complete") {
                val remaining = store.pending(1).isNotEmpty()
                mutableProgress.value = WorkProgress(
                    stage = if (remaining || pause != null) "paused" else "complete",
                    done = completed,
                    total = completed + failed,
                    detail = pause ?: if (remaining) {
                        "Session limit reached; $completed indexed. Unfinished items remain queued."
                    } else {
                        "$completed indexed; $failed failed"
                    },
                    running = false,
                )
            }
            // While the phone charges, a session that made progress hands over to the next one: a background session
            // queues it instead of leaving the rest for the periodic run 30 minutes later, and a run started with
            // untilDone (Caption or Transcribe now) starts it as soon as this one has let go of the engine.
            if ((background || untilDone) && keepGoingWhileCharging(background, remainingBefore, completed)) {
                if (background) continueWhileCharging() else handOver = true
            }
        } catch (cancelled: CancellationException) {
            mutableProgress.value = mutableProgress.value.copy(
                stage = "cancelled",
                running = false,
                detail = "Indexing cancelled; unfinished items remain queued",
            )
            throw cancelled
        } finally {
            try {
                withContext(NonCancellable) { engine.unload() }
            } finally {
                if (held) KeepAliveLease.release(context)
                activeJob = null
                interactiveInterrupted = false
                mutableProgress.value = mutableProgress.value.copy(running = false, memeId = null)
                val shouldResume = resumeAfterSession && !interactive
                resumeAfterSession = false
                session.unlock()
                launchOwedRetag()
                if (shouldResume) resumePending()
            }
        }
        return handOver
    }

    private suspend fun <T> withSessionDeadline(limitMillis: Long, block: suspend () -> T): T? {
        return withTimeoutOrNull(limitMillis) {
            @OptIn(InternalCoroutinesApi::class)
            val hook = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause != null) {
                    if (cause is TimeoutCancellationException) hardStop = true
                    engine.cancel()
                    cloud.cancel()
                }
            }
            try {
                block()
            } finally {
                hook.dispose()
            }
        }
    }

    private data class Prepared(
        val meme: Meme,
        val source: File,
        val hash: String,
        val image: FloatArray,
        val frames: List<String>,
        val temporaryFrames: List<File>,
        val thumbnail: String,
        val imported: Boolean = false,
        val sceneVectors: List<FloatArray> = emptyList(),
        val representativeFrame: String = frames.firstOrNull().orEmpty(),
    )

    /**
     * Models a session could not download or prepare. The work that needs one ("index", "vision", "audio", "visual",
     * "caption embedding") stops for the rest of the session and its items keep their place in the queue; each model
     * is reported once, not once per item.
     */
    private class ModelGaps(private val store: LibraryStore) {
        private val blocked = HashSet<String>()
        private val reported = HashSet<String>()
        var first: ModelUnavailableException? = null
            private set

        operator fun contains(work: String): Boolean = work in blocked

        suspend fun note(work: String, failure: ModelUnavailableException) {
            blocked += work
            if (first == null) first = failure
            if (reported.add(failure.model)) store.addError(failure.model, "model", "${failure.message}. $MODEL_WAIT")
        }
    }

    /**
     * [knownVector] stands in for the image model. With [importedFor] it is a collection import's
     * vector for the bytes with that hash, reused (with the import's OCR) only while the file
     * still has those bytes.
     */
    private fun sceneCacheDir(memeId: Long, hash: String): File =
        File(context.cacheDir, "scene_cache/${memeId}_${hash.take(16)}")

    private fun readCachedSceneFrames(memeId: Long, hash: String): Pair<List<String>, String>? {
        if (hash.isBlank()) return null
        val dir = sceneCacheDir(memeId, hash)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("upright_scene_") && f.length() > 0L }
            ?.sortedBy { it.name }
            ?: return null
        if (files.isEmpty()) return null
        val uris = files.map { Uri.fromFile(it).toString() }
        val rep = File(dir, "rep.txt").takeIf(File::isFile)?.readText()?.trim().orEmpty()
        val validRep = rep.takeIf { it in uris } ?: uris.first()
        return uris to validRep
    }

    private fun saveSceneFramesCache(memeId: Long, hash: String, distinctFrames: List<String>, repFrame: String) {
        if (hash.isBlank() || distinctFrames.isEmpty()) return
        runCatching {
            // Prune any older hash cache for this memeId first
            File(context.cacheDir, "scene_cache").listFiles { f -> f.isDirectory && f.name.startsWith("${memeId}_") }
                ?.forEach { it.deleteRecursively() }
            val dir = sceneCacheDir(memeId, hash).apply { mkdirs() }
            val selected = LinkedHashSet<String>()
            distinctFrames.take(3).forEach { selected += it }
            if (repFrame.isNotBlank()) selected += repFrame
            var mappedRep = ""
            for ((idx, uriStr) in selected.withIndex()) {
                val src = Uri.parse(uriStr).path?.let(::File) ?: continue
                if (!src.isFile) continue
                val dst = File(dir, "upright_scene_$idx.jpg")
                src.copyTo(dst, overwrite = true)
                val dstUri = Uri.fromFile(dst).toString()
                if (uriStr == repFrame || mappedRep.isEmpty()) mappedRep = dstUri
            }
            if (mappedRep.isNotBlank()) File(dir, "rep.txt").writeText(mappedRep)
        }
    }

    private suspend fun prepare(
        meme: Meme,
        knownVector: FloatArray? = null,
        importedFor: String? = null,
        extractFrames: Boolean = true,
        requireSource: Boolean = true,
    ): Prepared {
        // Fast path for caption / DINO backfill on already-indexed videos: reuse the CLIP-deduplicated
        // 768px scene JPEGs from indexing without re-copying a 100 MB MP4 from SAF or spinning MediaCodec!
        if (knownVector != null && importedFor == null && extractFrames && !requireSource && meme.contentHash.isNotBlank()) {
            readCachedSceneFrames(meme.id, meme.contentHash)?.let { (cachedFrames, cachedRep) ->
                return Prepared(
                    meme = meme,
                    source = File(context.cacheDir, "index_work/.nostage_${meme.id}"),
                    hash = meme.contentHash,
                    image = knownVector,
                    frames = cachedFrames,
                    temporaryFrames = emptyList(),
                    thumbnail = meme.thumbUri,
                    imported = false,
                    representativeFrame = cachedRep,
                )
            }
        }

        val (source, hash) = stage(meme.uri)
        val temporaryFrames = ArrayList<File>()
        try {
            val reused = knownVector?.takeIf { importedFor == null || importedFor == hash }
            // Audio-only speech batches only need the staged container; never spin video decoders or write JPEGs!
            if (!extractFrames && reused != null) {
                return Prepared(
                    meme = meme,
                    source = source,
                    hash = hash,
                    image = reused,
                    frames = emptyList(),
                    temporaryFrames = emptyList(),
                    thumbnail = meme.thumbUri,
                )
            }
            if (reused != null && importedFor == null) {
                readCachedSceneFrames(meme.id, hash)?.let { (cachedFrames, cachedRep) ->
                    return Prepared(
                        meme = meme,
                        source = source,
                        hash = hash,
                        image = reused,
                        frames = cachedFrames,
                        temporaryFrames = emptyList(),
                        thumbnail = meme.thumbUri.ifBlank { if (meme.kind == "video") files.thumbnail(meme.copy(uri = Uri.fromFile(source).toString())) else meme.uri },
                        representativeFrame = cachedRep,
                    )
                }
            }
            // The hash above covers the original bytes; the repair only touches this private copy.
            if (meme.kind == "video") Mp4Repair.zeroCttsFlags(source)
            val staged = meme.copy(uri = Uri.fromFile(source).toString())
            // A known vector needs frames only for thumbnails and optional enrichment.
            val candidates = if (meme.kind == "video") files.frames(staged, if (reused != null) 3 else 8) else listOf(staged.uri)
            require(candidates.isNotEmpty()) { "No decodable video frames" }
            val cacheRoot = context.cacheDir.canonicalPath + File.separator
            for (uri in candidates) {
                if (uri == staged.uri || uri == meme.uri || uri == meme.thumbUri) continue
                val file = Uri.parse(uri).path?.let(::File) ?: continue
                if (file.canonicalPath.startsWith(cacheRoot)) temporaryFrames += file
            }
            // Named `upright_*.jpg` so NativeInferenceEngine.recognize skips MemeTextDetector's redundant JPEG re-encode.
            val workDir = File(context.cacheDir, "index_work")
            val inputs = ArrayList<String>(candidates.size)
            for (uri in if (reused != null) candidates.take(3) else candidates) {
                currentCoroutineContext().ensureActive()
                val input = File(workDir, "upright_${UUID.randomUUID()}.jpg")
                temporaryFrames += input
                withContext(Dispatchers.IO) { MediaIoUtils.writeModelInputJpeg(context, Uri.parse(uri), input) }
                inputs += Uri.fromFile(input).toString()
            }
            val frames = ArrayList<String>(inputs.size)
            val vectors = ArrayList<FloatArray>(inputs.size)
            if (reused != null) {
                frames += if (meme.kind == "video") withContext(Dispatchers.IO) { FrameLikeness.distinct(inputs) } else inputs
                vectors += reused
            } else {
                for (uri in inputs) {
                    currentCoroutineContext().ensureActive()
                    val vector = engine.embedImage(uri)
                    require(vector.size == 512 && vector.all(Float::isFinite)) { "Invalid image embedding" }
                    if (vectors.none { dot(it, vector) >= 0.985f }) {
                        frames += uri
                        vectors += vector
                    }
                }
            }
            val pooled = if (vectors.size == 1) vectors[0] else pool(vectors)
            val sceneVectors = ArrayList<FloatArray>()
            if (vectors.size > 1) {
                sceneVectors.addAll(vectors)
            } else if (meme.kind == "image" && reused == null && inputs.size == 1) {
                val firstInputPath = Uri.parse(inputs[0]).path?.let(::File)
                val cropFiles = if (firstInputPath != null) {
                    withContext(Dispatchers.IO) { MediaIoUtils.writeAspectSquareCrops(firstInputPath, workDir) }
                } else emptyList()
                if (cropFiles.isNotEmpty()) {
                    temporaryFrames += cropFiles
                    sceneVectors += pooled
                    for (crop in cropFiles) {
                        currentCoroutineContext().ensureActive()
                        val cropVec = engine.embedImage(Uri.fromFile(crop).toString())
                        if (cropVec.size == 512 && cropVec.all(Float::isFinite) && sceneVectors.none { dot(it, cropVec) >= 0.985f }) {
                            sceneVectors += cropVec
                        }
                    }
                    if (sceneVectors.size <= 1) sceneVectors.clear()
                }
            }
            val repFrame = if (frames.size > 1 && vectors.size == frames.size) {
                var bestIdx = 0
                var bestSim = -2f
                for (idx in vectors.indices) {
                    val sim = dot(vectors[idx], pooled) + if (idx > 0) 0.005f else 0f
                    if (sim > bestSim) {
                        bestSim = sim
                        bestIdx = idx
                    }
                }
                frames[bestIdx]
            } else {
                frames.firstOrNull().orEmpty()
            }
            if (reused == null && meme.kind == "video" && frames.isNotEmpty()) {
                withContext(Dispatchers.IO) { saveSceneFramesCache(meme.id, hash, frames, repFrame) }
            }
            val thumbnail = if (meme.kind == "video") files.thumbnail(staged) else meme.uri
            return Prepared(
                meme = meme,
                source = source,
                hash = hash,
                image = pooled,
                frames = frames,
                temporaryFrames = temporaryFrames,
                thumbnail = thumbnail,
                imported = importedFor != null && reused != null,
                sceneVectors = sceneVectors,
                representativeFrame = repFrame,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                source.delete()
                temporaryFrames.forEach(File::delete)
            }
            throw failure
        }
    }

    private suspend fun enrich(
        items: List<Prepared>,
        background: Boolean,
        gaps: ModelGaps,
        /** Captions written in this session: caption → the text its search vector is made from; see [embedCaptions]. */
        captionTexts: MutableMap<Long, Pair<String, String>>,
        describe: Boolean = true,
        // Checked before each caption and transcript: a long batch stops early for memes waiting to be indexed.
        yieldTo: suspend () -> Boolean = { false },
    ) {
        if (items.isEmpty() || powerBlock(background) != null || interactive) return
        if (describe && store.setting("vision.enabled") == "1" && "vision" !in gaps) {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (powerBlock(background) != null || interactive || yieldTo()) break
                val meme = store.meme(item.meme.id) ?: continue
                if (meme.visionState != "pending") continue
                val counts = store.enrichmentCounts()
                mutableProgress.value = WorkProgress("local description", counts.captionsDone, counts.captionsDone + counts.captionsWaiting, meme.name, true, memeId = meme.id)
                // Recorded first, so a session after the app was killed mid-caption knows which meme it was on.
                store.setSetting(VISION_IN_FLIGHT, meme.id.toString())
                val result = try {
                    // A process-killing native failure must not create an automatic crash loop. The marker is set
                    // inside this try so a stop that lands as it commits still returns the meme to pending.
                    store.setCaption(meme.id, meme.caption, emptyList(), null, "failed")
                    val openHints = store.vectors(meme.id)?.image?.let { vec ->
                        runCatching { knowledge.suggestFor(meme, vec, limit = 5).filter { it.score >= 0.74f } }.getOrDefault(emptyList())
                    }.orEmpty()
                    val descriptions = ArrayList<VisionDescription>()
                    for (frame in item.frames.take(3)) {
                        val parsed = VisionText.parse(engine.describe(frame, VisionText.grounding(meme, openHints)))
                        require(parsed.caption.isNotBlank() || parsed.tags.isNotEmpty()) { "The local model returned no usable description" }
                        if (descriptions.none { it.caption.equals(parsed.caption, ignoreCase = true) }) descriptions += parsed
                    }
                    VisionDescription(
                        descriptions.map { it.caption }.filter(String::isNotBlank).joinToString(" / ").take(1000),
                        descriptions.map { it.text }.filter(String::isNotBlank).distinct().joinToString("\n"),
                        descriptions.flatMap { it.tags }.distinctBy { it.label },
                    )
                } catch (failure: Exception) {
                    // A native call stopped by Stop, interactive pause, or the session limit can fail as an ordinary
                    // exception. Each path writes the meme's state before it drops the in-flight record, so a kill in
                    // between still leaves the record for the next session to find.
                    if (failure is CancellationException || !currentCoroutineContext().isActive) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setCaption(meme.id, meme.caption, emptyList(), null, "pending")
                            store.setSetting(VISION_IN_FLIGHT, "")
                        }
                        throw failure as? CancellationException
                            ?: CancellationException("Local description stopped").apply { initCause(failure) }
                    }
                    if (interactive || interactiveInterrupted) {
                        interactiveInterrupted = false
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setCaption(meme.id, meme.caption, emptyList(), null, "pending")
                            store.setSetting(VISION_IN_FLIGHT, "")
                        }
                        break
                    }
                    if (failure is ModelUnavailableException) {
                        // Not this meme's fault: it goes back to the queue, and captions wait for a later session.
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setCaption(meme.id, meme.caption, emptyList(), null, "pending")
                            store.setSetting(VISION_IN_FLIGHT, "")
                        }
                        gaps.note("vision", failure)
                        break
                    }
                    // A real failure: the crash marker stays as the meme's state.
                    store.addError(meme.name, "vision", failure.message ?: "Local description failed")
                    withContext(NonCancellable) { store.setSetting(VISION_IN_FLIGHT, "") }
                    null
                } ?: continue
                // Keep each caption as soon as it exists, so a later stop cannot discard finished work. Its search
                // vector comes at the end of the session: the text model would unload the caption model.
                withContext(NonCancellable) {
                    if (result.text.isNotBlank()) store.setVlmOcr(meme.id, result.text)
                    store.setCaption(meme.id, result.caption, result.tags, null)
                    store.setSetting(VISION_IN_FLIGHT, "")
                }
                captionTexts[meme.id] = result.caption to listOf(result.caption, result.text, result.tags.joinToString(" ") { it.label }).joinToString(" ").trim()
            }
        }
        if (store.setting("audio.enabled") == "1" && "audio" !in gaps) {
            for (item in items.filter { it.meme.kind == "video" }) {
                currentCoroutineContext().ensureActive()
                if (powerBlock(background) != null || interactive || yieldTo()) break
                val meme = store.meme(item.meme.id) ?: continue
                if (meme.audioState == "done" || meme.id in backgroundSkip) continue
                val counts = store.enrichmentCounts()
                mutableProgress.value = WorkProgress("local transcription", counts.transcriptsDone, counts.transcriptsDone + counts.transcriptsWaiting, meme.name, true, memeId = meme.id)
                val began = SystemClock.elapsedRealtime()
                store.setSetting(AUDIO_IN_FLIGHT, meme.id.toString())
                val text = try {
                    // As for captions: a native crash must not loop, so the clip is marked failed while it runs, and
                    // the marker is set inside this try so a stop that lands as it commits returns the clip to the queue.
                    store.setAudioState(meme.id, "failed")
                    engine.transcribe(Uri.fromFile(item.source).toString())
                } catch (failure: Exception) {
                    // As for captions, the clip's state is written before the in-flight record is dropped.
                    if (failure is CancellationException || !currentCoroutineContext().isActive) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setAudioState(meme.id, "pending")
                            // Still running when the hard stop came after a whole session's time: background
                            // sessions can't finish this clip, so they leave it to one started in the app instead
                            // of trying it first every time and never reaching the rest of the queue.
                            if (background && hardStop && SystemClock.elapsedRealtime() - began >= sessionTimeoutMillis) skipInBackground(meme.id)
                            store.setSetting(AUDIO_IN_FLIGHT, "")
                        }
                        throw failure as? CancellationException
                            ?: CancellationException("Local transcription stopped").apply { initCause(failure) }
                    }
                    if (interactive || interactiveInterrupted) {
                        interactiveInterrupted = false
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setAudioState(meme.id, "pending")
                            store.setSetting(AUDIO_IN_FLIGHT, "")
                        }
                        break
                    }
                    if (failure is ModelUnavailableException) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setAudioState(meme.id, "pending")
                            store.setSetting(AUDIO_IN_FLIGHT, "")
                        }
                        gaps.note("audio", failure)
                        break
                    }
                    store.addError(meme.name, "audio", failure.message ?: "Transcription failed")
                    withContext(NonCancellable) { store.setSetting(AUDIO_IN_FLIGHT, "") }
                    null
                } ?: continue
                // Keep the transcript as soon as it exists, so a later stop cannot discard it.
                withContext(NonCancellable) {
                    store.setTranscript(meme.id, text)
                    store.setSetting(AUDIO_IN_FLIGHT, "")
                }
            }
        }
        if (describe && store.setting("visual.enabled") == "1" && "visual" !in gaps) {
            for (item in items) {
                if (powerBlock(background) != null || interactive || yieldTo()) break
                if (item.frames.isEmpty() || store.vectors(item.meme.id)?.visual != null) continue
                mutableProgress.value = WorkProgress("visual embedding", detail = item.meme.name, running = true, memeId = item.meme.id)
                val dinoFrame = item.representativeFrame.ifBlank { item.frames.first() }
                try { store.setVisual(item.meme.id, engine.embedVisual(dinoFrame), ModelRegistry.DINOV2_MODEL_FINGERPRINT) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (unavailable: ModelUnavailableException) { gaps.note("visual", unavailable); break }
                catch (failure: Exception) {
                    if (interactive || interactiveInterrupted) {
                        interactiveInterrupted = false
                        break
                    }
                    store.addError(item.meme.name, "visual", failure.message ?: "Visual embedding failed")
                }
            }
        }
        if (describe) {
            val visionOn = store.setting("vision.enabled") == "1"
            val visualOn = store.setting("visual.enabled") == "1"
            for (item in items) {
                if (item.meme.kind != "video" || item.hash.isBlank()) continue
                val latest = store.meme(item.meme.id) ?: continue
                val visionSettled = !visionOn || latest.visionState != "pending"
                val visualSettled = !visualOn || store.vectors(latest.id)?.visual != null
                if (visionSettled && visualSettled) {
                    withContext(Dispatchers.IO) { runCatching { sceneCacheDir(latest.id, item.hash).deleteRecursively() } }
                }
            }
        }
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val meme = store.meme(item.meme.id) ?: continue
            mutableProgress.value = WorkProgress("reviewing candidates", detail = meme.name, running = true, memeId = meme.id)
            try { cloud.consider(meme) { knowledge.candidateLabels(item.image, 12) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { store.addError(meme.name, "cloud", failure.message ?: "Optional cloud analysis unavailable") }
        }
    }

    /**
     * Search vectors for the session's captions, all at the end: the text model and the caption model can't be
     * loaded together, and making them after each batch of eight unloaded and reloaded the 1.7 GB caption model every
     * time (about 29 s a batch on the Pixel 5a). Also makes up to [CAPTION_VECTOR_BACKLOG] vectors that an earlier
     * session never wrote because it was stopped or killed; their reply's TEXT line is gone, so those are made from
     * the caption and the tags the caption added.
     */
    private suspend fun embedCaptions(captionTexts: Map<Long, Pair<String, String>>, gaps: ModelGaps) {
        if ("caption embedding" in gaps) return
        val due = LinkedHashMap(captionTexts)
        for (id in store.captionsWithoutVectors(engine.primaryModel, CAPTION_VECTOR_BACKLOG)) {
            if (id in due) continue
            val meme = store.meme(id) ?: continue
            due[id] = meme.caption to listOf(
                meme.caption,
                meme.vlmOcr,
                meme.tags.filter { it.source == "vision" }.joinToString(" ") { it.label },
            ).filter { it.isNotBlank() }.joinToString(" ").trim()
        }
        for ((id, texts) in due) {
            currentCoroutineContext().ensureActive()
            val (caption, searchable) = texts
            if (store.vectors(id)?.caption != null) continue
            mutableProgress.value = WorkProgress("caption embedding", running = true, memeId = id)
            try {
                // Written only while the meme still has this caption and isn't waiting for a new one: a re-caption
                // asked for meanwhile (or Re-analyze) keeps its place in the queue.
                store.setCaptionVector(id, caption, engine.embedText(searchable))
            } catch (failure: Exception) {
                if (failure is CancellationException || !currentCoroutineContext().isActive) {
                    throw failure as? CancellationException
                        ?: CancellationException("Caption embedding stopped").apply { initCause(failure) }
                }
                if (failure is ModelUnavailableException) {
                    gaps.note("caption embedding", failure)
                    return
                }
                store.addError("item $id", "caption embedding", failure.message ?: "Caption vector failed; text remains searchable")
            }
        }
    }

    /**
     * A caption or transcript is marked failed while it runs, so a native crash can't loop. When the app was killed
     * instead (an install, a force-stop, Android reclaiming memory), that marker would leave the meme failed for good:
     * 45 of 46 failed captions on the Pixel 5a had no error. Each meme gets one more try; killed a second time it
     * stays failed. The store makes the change in one transaction, so a kill during it can't strand the meme.
     */
    private suspend fun recoverInterrupted() {
        for ((stage, inFlightKey, onceKey) in listOf(
            Triple("vision", VISION_IN_FLIGHT, VISION_KILLED_ONCE),
            Triple("audio", AUDIO_IN_FLIGHT, AUDIO_KILLED_ONCE),
        )) {
            val recovery = store.recoverKilled(stage, inFlightKey, onceKey) ?: continue
            if (!recovery.retried) {
                store.meme(recovery.id)?.let { store.addError(it.name, stage, "The app stopped twice while working on this item") }
            }
        }
    }

    private suspend fun stage(uri: String): Pair<File, String> = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "index_work").apply { mkdirs() }
        val target = File(directory, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = if (uri.startsWith("/")) File(uri).inputStream() else context.contentResolver.openInputStream(Uri.parse(uri))
            requireNotNull(input) { "The source document is unavailable; relink its folder" }
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > MAX_SOURCE_BYTES) throw IOException("This item exceeds the 512 MiB processing limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            target to digest.digest().joinToString("") { "%02x".format(it) }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun cleanPrepared(item: Prepared) {
        item.source.delete()
        item.temporaryFrames.forEach(File::delete)
    }

    /**
     * Tasks a session can still work on: indexing plus each enabled enrichment not done yet, counted separately,
     * so a video that gets its transcript but still waits for a caption counts as progress.
     */
    private suspend fun remainingWork(): Int {
        val vision = store.setting("vision.enabled") == "1"
        val audio = store.setting("audio.enabled") == "1"
        val visual = store.setting("visual.enabled") == "1"
        return store.allMemes().fold(0) { tasks, meme ->
            var left = tasks
            if (meme.pending) left++
            if (vision && meme.visionState == "pending") left++
            if (audio && meme.kind == "video" && meme.audioState == "pending") left++
            if (visual && store.vectors(meme.id)?.visual == null) left++
            left
        }
    }

    private suspend fun backgroundSkips(): Set<Long> =
        store.setting(BACKGROUND_SKIP).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    private suspend fun skipInBackground(id: Long) {
        store.setSetting(BACKGROUND_SKIP, (backgroundSkips() + id).joinToString(","))
        Log.i(LOG_TAG, "Meme $id takes longer to transcribe than a background session allows; it waits for a session started in the app")
    }

    private suspend fun keepGoingWhileCharging(background: Boolean, remainingBefore: Int, indexed: Int): Boolean {
        if ((background && store.setting("background.enabled") != "1") || interactive || !charging()) return false
        val remainingAfter = remainingWork()
        // Only after progress, so items that fail every time can't keep the phone busy all night.
        return remainingAfter > 0 && (indexed > 0 || remainingAfter < remainingBefore)
    }

    private suspend fun continueWhileCharging() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(store.setting("background.pauseLowBattery") != "0")
            .build()
        // The short delay lets the finished worker's foreground service stop first: WorkManager 2.10.3 can crash
        // when one foreground worker starts while another's service is shutting down. A warm phone gets a few
        // minutes to cool instead; if it is still warm then, that session pauses at once and the periodic run
        // takes over.
        val cooling = warm()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTINUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(constraints)
                .setInitialDelay(if (cooling) COOL_DOWN_SECONDS else CONTINUE_DELAY_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        Log.i(LOG_TAG, if (cooling) "Charging with work left, phone warm: next background session queued after a cool-down" else "Charging with work left: next background session queued")
    }

    private fun batteryStatus(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun charging(): Boolean = (batteryStatus()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private suspend fun powerBlock(background: Boolean): String? {
        val battery = batteryStatus()
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (background && store.setting("background.onlyCharging") == "1" && !plugged) return "Waiting for charging"
        if (store.setting("background.pauseLowBattery") != "0" && !plugged && level >= 0 && scale > 0 && level.toFloat() / scale < 0.2f) return "Paused for low battery"
        if (warm()) return "Paused while the device cools"
        return null
    }

    private suspend fun warm(): Boolean =
        Build.VERSION.SDK_INT >= 29 && store.setting("background.pauseHot") != "0" &&
            context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun pool(vectors: List<FloatArray>): FloatArray {
        val result = FloatArray(vectors.first().size)
        for (vector in vectors) for (i in result.indices) result[i] += vector[i]
        var norm = 0.0
        for (value in result) norm += value.toDouble() * value
        val magnitude = sqrt(norm)
        require(magnitude > 0 && magnitude.isFinite()) { "Cannot normalize an empty video embedding" }
        for (i in result.indices) result[i] = (result[i] / magnitude).toFloat()
        return result
    }

    companion object {
        private const val BATCH_SIZE = 8
        private const val BACKGROUND_ITEMS = 32
        private const val SESSION_MILLIS = 7L * 60 * 1000
        /** A session on the charger with a foreground service: about seven captions' worth of reload per hour. */
        private const val LONG_SESSION_MILLIS = 60L * 60 * 1000
        /** How long the item running when [SESSION_MILLIS] ends may take; 9.5 minutes in all stays inside a regular job's 10-minute limit. */
        private const val SESSION_GRACE_MILLIS = 150_000L
        private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
        private const val BATCH_DISK_BYTES = 256L * 1024 * 1024
        private const val MODEL_WAIT = "Work that needs it waits for the next session."
        private const val IMMEDIATE_WORK = "memeget-native-index"
        private const val PERIODIC_WORK = "memeget-native-periodic"
        private const val CONTINUE_WORK = "memeget-native-continue"
        private const val CONTINUE_DELAY_SECONDS = 30L
        private const val COOL_DOWN_SECONDS = 5L * 60
        /** Setting: ids of clips background sessions leave to a session started in the app, comma-separated. */
        private const val BACKGROUND_SKIP = "audio.backgroundSkip"
        /** Settings: the meme whose caption or transcript is running, so a session after a kill can tell. */
        private const val VISION_IN_FLIGHT = "vision.inFlight"
        private const val AUDIO_IN_FLIGHT = "audio.inFlight"
        /** Settings: memes already given another try after a kill, comma-separated. */
        private const val VISION_KILLED_ONCE = "vision.killedOnce"
        private const val AUDIO_KILLED_ONCE = "audio.killedOnce"
        /** Missing caption vectors made at the end of a session, beyond its own captions. */
        private const val CAPTION_VECTOR_BACKLOG = 200
        internal const val LOG_TAG = "MemegetIndex"
    }
}

class IndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MemegetApplication).graph
        if (graph.store.setting("background.enabled") != "1") return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("native-index", "Native indexing", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "native-index")
            .setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Memeget Native")
            .setContentText("Processing saved items locally").setOngoing(true).build()
        val foreground = try {
            setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7240, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7240, notification))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: Exception) {
            // Android 12+ refuses a foreground service started while the app is in the background, which is how
            // these runs usually start (phone locked on the charger). A session starts nothing new after 7 minutes
            // and ends within 9.5, inside a regular job's 10-minute limit, so it runs without one.
            Log.i(IndexCoordinator.LOG_TAG, "Background session runs without a foreground service: ${refused.javaClass.simpleName}")
            false
        }
        return try {
            graph.coordinator.runPending(longRunning = foreground)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            graph.store.addError("Background indexing", "worker", failure.message ?: "Worker failed")
            Result.failure()
        }
    }
}
