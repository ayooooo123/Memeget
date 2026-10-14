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
import com.memeget.nativeapp.storage.MediaIoUtils
import com.memeget.nativeapp.storage.Mp4Repair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Mutex()
    private val mutableProgress = MutableStateFlow(WorkProgress())
    val progress: StateFlow<WorkProgress> = mutableProgress.asStateFlow()
    @Volatile private var activeJob: Job? = null
    /**
     * Re-tags asked for, and how many of those a started pass or Stop has covered. Requests count
     * up before anyone tries the lock, so the session that holds it always sees them on unlock.
     */
    private val retagRequests = AtomicLong()
    private val retagCovered = AtomicLong()
    @Volatile private var interactive = false
    fun setInteractive(value: Boolean) { interactive = value }

    /** Indexes [ids] again, or with no ids everything pending plus items whose analysis failed. */
    fun start(ids: List<Long>? = null) {
        scope.launch {
            try {
                if (ids == null) store.requeueFailed() else if (ids.isNotEmpty()) store.requeue(ids.distinct())
                runSession(background = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableProgress.value = WorkProgress(stage = "failed", error = failure.message ?: "Indexing failed")
            }
        }
    }
    fun cancel() {
        retagCovered.accumulateAndGet(retagRequests.get(), ::maxOf)
        activeJob?.cancel()
        engine.cancel()
        cloud.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
    }
    /** Re-tags the library now or, when another session holds the lock, as soon as it ends. */
    suspend fun retag() {
        retagRequests.incrementAndGet()
        runOwedRetag()
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
            val completed = withSessionDeadline {
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
    suspend fun runPending() = runSession(background = true)
    private suspend fun runSession(background: Boolean) {
        if (background && store.setting("background.enabled") != "1") return
        if (!session.tryLock()) return
        activeJob = currentCoroutineContext()[Job]
        val started = SystemClock.elapsedRealtime()
        var completed = 0
        var failed = 0
        var held = false
        var pause: String? = null
        var completedNaturally = false

        try {
            mutableProgress.value = WorkProgress("indexing", running = true)
            if (!background) {
                KeepAliveLease.acquire(context, "Memeget Native", "Indexing saved media")
                held = true
            }

            val sessionFinished = withSessionDeadline {
                while (SystemClock.elapsedRealtime() - started < sessionTimeoutMillis) {
                    currentCoroutineContext().ensureActive()
                    pause = powerBlock(background)
                    if (pause != null || (background && interactive)) break
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
                                store.completeIndex(item.meme.id, LocalAnalysis(item.image, ocr, tags, terms), engine.primaryModel, item.thumbnail)
                                indexed += item
                                completed++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                failed++
                                store.markIndexFailed(item.meme.id, failure.message ?: "Text recognition failed")
                            }
                        }
                        enrich(indexed, background)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            prepared.forEach(::cleanPrepared)
                        }
                    }
                    KeepAliveLease.update(context, "Memeget Native", "$completed indexed; $failed failed", completed, completed + store.pending(BATCH_SIZE).size)
                    if (background && completed + failed >= BACKGROUND_ITEMS) break
                }

                // Already-indexed items also need optional enrichment after the user enables a model.
                if (pause == null && SystemClock.elapsedRealtime() - started < sessionTimeoutMillis && !interactive) {
                    val vision = store.setting("vision.enabled") == "1"
                    val audio = store.setting("audio.enabled") == "1"
                    val visual = store.setting("visual.enabled") == "1"
                    if (vision || audio || visual) {
                        val candidates = store.allMemes().filter { meme ->
                            !meme.pending && (vision && meme.visionState == "pending" ||
                                audio && meme.kind == "video" && meme.audioState == "pending" ||
                                visual && store.vectors(meme.id)?.visual == null)
                        }.take(BATCH_SIZE).toList()
                        for (meme in candidates) {
                            if (SystemClock.elapsedRealtime() - started >= sessionTimeoutMillis || powerBlock(background) != null || interactive) break
                            val vectors = store.vectors(meme.id)?.takeIf { it.model == engine.primaryModel } ?: continue
                            mutableProgress.value = WorkProgress("preparing media", detail = meme.name, running = true, memeId = meme.id)
                            val item = prepare(meme, vectors.image)
                            try { enrich(listOf(item), background) }
                            finally { withContext(NonCancellable + Dispatchers.IO) { cleanPrepared(item) } }
                        }
                    }
                }

                val remaining = store.pending(1).isNotEmpty()
                mutableProgress.value = WorkProgress(
                    stage = if (pause != null || remaining) "paused" else "complete",
                    done = completed, total = completed + failed,
                    detail = pause ?: if (remaining) "More items are saved and waiting. Resume indexing or enable background work." else "$completed indexed; $failed failed",
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
                mutableProgress.value = mutableProgress.value.copy(running = false, memeId = null)
                session.unlock()
                launchOwedRetag()
            }
        }
    }

    private suspend fun <T> withSessionDeadline(block: suspend () -> T): T? {
        return withTimeoutOrNull(sessionTimeoutMillis) {
            @OptIn(InternalCoroutinesApi::class)
            val hook = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause != null) {
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
        /** Vector and OCR came from a collection import; text recognition is skipped. */
        val imported: Boolean = false,
    )

    /**
     * [knownVector] stands in for the image model. With [importedFor] it is a collection import's
     * vector for the bytes with that hash, reused (with the import's OCR) only while the file
     * still has those bytes.
     */
    private suspend fun prepare(meme: Meme, knownVector: FloatArray? = null, importedFor: String? = null): Prepared {
        val (source, hash) = stage(meme.uri)
        val temporaryFrames = ArrayList<File>()
        try {
            val reused = knownVector?.takeIf { importedFor == null || importedFor == hash }
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
            // The models read what the original app gave them, a 768-px-wide JPEG of each frame, so
            // its exports and teachings stay comparable; WebP, GIF and HEIF, which the native decoder
            // cannot read, then index like any other image.
            val workDir = File(context.cacheDir, "index_work")
            val inputs = ArrayList<String>(candidates.size)
            for (uri in if (reused != null) candidates.take(3) else candidates) {
                currentCoroutineContext().ensureActive()
                val input = File(workDir, "${UUID.randomUUID()}.jpg")
                temporaryFrames += input
                withContext(Dispatchers.IO) { MediaIoUtils.writeModelInputJpeg(context, Uri.parse(uri), input) }
                inputs += Uri.fromFile(input).toString()
            }
            val frames = ArrayList<String>(inputs.size)
            val vectors = ArrayList<FloatArray>(inputs.size)
            if (reused != null) {
                frames += inputs
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
            val thumbnail = if (meme.kind == "video") files.thumbnail(staged) else meme.uri
            return Prepared(meme, source, hash, pooled, frames, temporaryFrames, thumbnail, imported = importedFor != null && reused != null)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                source.delete()
                temporaryFrames.forEach(File::delete)
            }
            throw failure
        }
    }

    private suspend fun enrich(items: List<Prepared>, background: Boolean) {
        if (items.isEmpty() || powerBlock(background) != null || interactive) return
        val captions = ArrayList<Pair<Long, VisionDescription>>()
        if (store.setting("vision.enabled") == "1") {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (powerBlock(background) != null || interactive) break
                val meme = store.meme(item.meme.id) ?: continue
                if (meme.visionState != "pending") continue
                mutableProgress.value = WorkProgress("local description", detail = meme.name, running = true, memeId = meme.id)
                // A process-killing native failure must not create an automatic crash loop.
                store.setCaption(meme.id, meme.caption, emptyList(), null, "failed")
                val result = try {
                    val descriptions = ArrayList<VisionDescription>()
                    for (frame in item.frames.take(3)) {
                        val parsed = VisionText.parse(engine.describe(frame, VisionText.grounding(meme)))
                        require(parsed.caption.isNotBlank() || parsed.tags.isNotEmpty()) { "The local model returned no usable description" }
                        if (descriptions.none { it.caption.equals(parsed.caption, ignoreCase = true) }) descriptions += parsed
                    }
                    VisionDescription(
                        descriptions.map { it.caption }.filter(String::isNotBlank).joinToString(" / ").take(1000),
                        descriptions.map { it.text }.filter(String::isNotBlank).distinct().joinToString("\n"),
                        descriptions.flatMap { it.tags }.distinctBy { it.label },
                    )
                } catch (failure: Exception) {
                    // A native call stopped by Stop or the session limit can fail as an ordinary exception.
                    if (failure is CancellationException || !currentCoroutineContext().isActive) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            store.setCaption(meme.id, meme.caption, emptyList(), null, "pending")
                        }
                        throw failure as? CancellationException
                            ?: CancellationException("Local description stopped").apply { initCause(failure) }
                    }
                    store.addError(meme.name, "vision", failure.message ?: "Local description failed")
                    null
                } ?: continue
                // Keep each caption as soon as it exists, so a later stop cannot discard finished work.
                withContext(NonCancellable) { store.setCaption(meme.id, result.caption, result.tags, null) }
                captions += meme.id to result
                delay(1200)
            }
        }
        // The captions are saved; this adds their search vectors. Their text stays searchable if it stops or fails.
        for ((id, result) in captions) {
            currentCoroutineContext().ensureActive()
            mutableProgress.value = mutableProgress.value.copy(stage = "caption embedding", detail = "", memeId = id)
            val searchable = listOf(result.caption, result.text, result.tags.joinToString(" ") { it.label }).joinToString(" ").trim()
            try {
                store.setCaption(id, result.caption, result.tags, engine.embedText(searchable))
            } catch (failure: Exception) {
                if (failure is CancellationException || !currentCoroutineContext().isActive) {
                    throw failure as? CancellationException
                        ?: CancellationException("Caption embedding stopped").apply { initCause(failure) }
                }
                store.addError("item $id", "caption embedding", failure.message ?: "Caption vector failed; text remains searchable")
            }
        }
        if (store.setting("audio.enabled") == "1") {
            for (item in items.filter { it.meme.kind == "video" }) {
                currentCoroutineContext().ensureActive()
                if (powerBlock(background) != null || interactive) break
                val meme = store.meme(item.meme.id) ?: continue
                if (meme.audioState == "done") continue
                store.setTranscript(meme.id, meme.transcript, "failed")
                mutableProgress.value = WorkProgress("local transcription", detail = meme.name, running = true, memeId = meme.id)
                try {
                    store.setTranscript(meme.id, engine.transcribe(Uri.fromFile(item.source).toString()))
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        store.setTranscript(meme.id, meme.transcript, "pending")
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    store.addError(meme.name, "audio", failure.message ?: "Transcription failed")
                }
            }
        }
        if (store.setting("visual.enabled") == "1") {
            for (item in items) {
                if (powerBlock(background) != null || interactive) break
                if (store.vectors(item.meme.id)?.visual != null) continue
                mutableProgress.value = WorkProgress("visual embedding", detail = item.meme.name, running = true, memeId = item.meme.id)
                try { store.setVisual(item.meme.id, engine.embedVisual(item.frames.first()), ModelRegistry.DINOV2_MODEL_FINGERPRINT) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { store.addError(item.meme.name, "visual", failure.message ?: "Visual embedding failed") }
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

    private suspend fun powerBlock(background: Boolean): String? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (background && store.setting("background.onlyCharging") == "1" && !plugged) return "Waiting for charging"
        if (store.setting("background.pauseLowBattery") != "0" && !plugged && level >= 0 && scale > 0 && level.toFloat() / scale < 0.2f) return "Paused for low battery"
        if (Build.VERSION.SDK_INT >= 29 && store.setting("background.pauseHot") != "0") {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return "Paused while the device cools"
        }
        return null
    }

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
        private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
        private const val BATCH_DISK_BYTES = 256L * 1024 * 1024
        private const val IMMEDIATE_WORK = "memeget-native-index"
        private const val PERIODIC_WORK = "memeget-native-periodic"
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
        setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7240, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7240, notification))
        return try {
            graph.coordinator.runPending()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            graph.store.addError("Background indexing", "worker", failure.message ?: "Worker failed")
            Result.failure()
        }
    }
}
