package expo.modules.memegetbg

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One video export: a media3 [Transformer] run with progress, cancellation and honest fallbacks.
 *
 * Three rules drive everything in this file, and each of them is a bug that has cost real time:
 *
 * * **Settle exactly once.** A promise that resolves twice creates two memes from one render, and
 *   a promise that resolves after a cancel creates the one the user just cancelled. Every terminal
 *   path goes through [Run.settle], which is guarded by a compare-and-set.
 * * **Release everything on every terminal path, including the unexpected ones.** A leaked
 *   hardware codec does not fail here; it fails hours later in an unrelated feature that cannot
 *   get an encoder. Cancellation releases the transformer, the poll callback, the partial file and
 *   the keep-alive lease in one place, so a new terminal path cannot forget one of them.
 * * **Never hand back a file that is not what was asked for.** media3 will happily fall back to a
 *   different codec or a smaller frame, and it will finish a truncated file rather than fail. Both
 *   are surfaced: the first as a warning the user reads, the second as an error.
 */
@OptIn(UnstableApi::class)
object MemeVideoExporter {
  /** Mirrors `EXPORT_STAGES` in `src/memeExportCore.ts`; the names cross the bridge verbatim. */
  const val STAGE_PREPARING = "preparing"
  const val STAGE_ENCODING = "encoding"

  /**
   * media3 has no progress callback - progress is pulled. 200 ms is four frames of a 60 Hz UI:
   * fast enough that a progress bar moves smoothly, slow enough that a long export does not spend
   * its CPU crossing the bridge.
   */
  private const val POLL_INTERVAL_MS = 200L

  private const val OUTPUT_DIR = "meme_export"

  /** An export nobody claimed within this window is cache the user cannot see. */
  private const val STALE_OUTPUT_MS = 6L * 60L * 60L * 1000L

  /**
   * How far the muxed duration may sit from the plan's before the file counts as truncated.
   *
   * Absolute floor plus a fraction: the last frame's duration is not knowable up front (it depends
   * on the source frame rate after the speed change), and a long export accumulates rounding in
   * every segment boundary.
   */
  private const val DURATION_TOLERANCE_MS = 300L
  private const val DURATION_TOLERANCE_FRACTION = 0.02

  /** Cancellation is not a failure, and the UI must be able to tell them apart. */
  class CancelledException : Exception("The export was cancelled")

  data class Progress(val stage: String, val fraction: Double?, val detail: String)

  data class Outcome(val uri: String, val warnings: List<String>)

  private val runs = ConcurrentHashMap<String, Run>()
  private val pollers = AtomicInteger(0)

  /**
   * Start an export. [onSettled] is called exactly once, on the main looper.
   *
   * [exportId] is the caller's handle for [cancel]; starting a second export under a live id is
   * refused rather than silently orphaning the first one's file.
   */
  fun start(
    context: Context,
    exportId: String,
    planJson: String,
    onProgress: (Progress) -> Unit,
    onSettled: (Result<Outcome>) -> Unit
  ) {
    val appContext = context.applicationContext
    val run = Run(appContext, exportId, onProgress, onSettled)
    // Registered before anything slow happens, so a cancel that arrives while the plan is still
    // being parsed finds a run to cancel instead of racing past it.
    if (runs.putIfAbsent(exportId, run) != null) {
      onSettled(Result.failure(IOException("An export with id $exportId is already running")))
      return
    }
    Handler(Looper.getMainLooper()).post { run.startOnMain(planJson) }
  }

  /**
   * Ask a running export to stop. Returns false when there is nothing to cancel - the export
   * already finished, or it was never started.
   *
   * The release itself happens on the main looper (media3 requires it), so a `true` here means
   * "the cancel was accepted", and [onSettled] is what says it completed.
   */
  fun cancel(exportId: String): Boolean {
    val run = runs[exportId] ?: return false
    return run.requestCancel()
  }

  /** Ids of exports that have not settled yet. Test hook: a leak is invisible from the outside. */
  fun activeExportIds(): Set<String> = runs.keys.toSet()

  /**
   * How many exports are still pulling progress out of media3.
   *
   * Counted because a poll loop that outlives its export is invisible: a cancelled transformer
   * answers `PROGRESS_STATE_NOT_STARTED`, so the leaked loop reports nothing, changes nothing, and
   * just wakes the main looper five times a second holding a transformer that should be gone.
   * Anything but zero between exports is that leak.
   */
  internal fun activePollCount(): Int = pollers.get()

  /** Where renders land while nobody has claimed them yet. */
  internal fun exportCacheDir(context: Context): File = File(context.cacheDir, OUTPUT_DIR)

  private class Run(
    private val context: Context,
    private val exportId: String,
    private val onProgress: (Progress) -> Unit,
    private val onSettled: (Result<Outcome>) -> Unit
  ) : Transformer.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val settled = AtomicBoolean(false)
    private val progressHolder = ProgressHolder()
    private val warnings = mutableListOf<String>()

    private var transformer: Transformer? = null
    private var output: File? = null
    private var plan: VideoExportPlan? = null
    private var expectsAudio = false
    private var polling = false
    private var lastProgress: Progress? = null
    private var keepAlive = false

    private val poll = Runnable { pollProgress() }

    fun startOnMain(planJson: String) {
      // Nothing starts after a settle. Today the looper's FIFO ordering already guarantees it -
      // a cancel posted after this one cannot run before it - but the invariant is what makes
      // "settled means released" true, and an inline cancel added later would break it silently.
      if (settled.get()) return
      emit(Progress(STAGE_PREPARING, null, "Preparing the composition"))
      try {
        val parsed = VideoExportPlan.parse(planJson)
        plan = parsed
        val sourceHasAudio = VideoExportPlan.sourceHasAudioTrack(context, parsed.sourceUri)
        expectsAudio = parsed.expectsAudio(sourceHasAudio)
        val composition = parsed.buildComposition(sourceHasAudio)
        val file = createOutputFile(parsed.id)
        output = file
        // The transformer owns the file from here on; before that, this is the only reference to
        // it, so a failure between creating it and starting must not leave it behind.
        val built = Transformer.Builder(context)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .addListener(this)
          .build()
        transformer = built
        KeepAliveLease.acquire(context, "Memeget", "Rendering your remix")
        keepAlive = true
        built.start(composition, file.absolutePath)
        startPolling()
      } catch (error: Throwable) {
        fail(error)
      }
    }

    fun requestCancel(): Boolean {
      if (settled.get()) return false
      handler.post {
        if (settled.get()) return@post
        // cancel() blocks until media3 has released its codecs and its muxer, so deleting the
        // partial file afterwards cannot race the writer.
        runCatching { transformer?.cancel() }
        fail(CancelledException())
      }
      return true
    }

    override fun onCompleted(composition: Composition, result: ExportResult) {
      stopPolling()
      val current = plan
      val file = output
      if (current == null || file == null) {
        fail(IllegalStateException("The export completed without a plan"))
        return
      }
      try {
        verify(current, file, result)
      } catch (error: Throwable) {
        fail(error)
        return
      }
      warnings += resultWarnings(current, result)
      settle(Result.success(Outcome(Uri.fromFile(file).toString(), warnings.toList())))
    }

    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
      fail(exception)
    }

    /**
     * media3 telling us it could not honour the request. This is the ONLY place the change is
     * reported - the export goes on to succeed - so a fallback that is not collected here is a
     * file that silently differs from what the user asked for.
     */
    override fun onFallbackApplied(
      composition: Composition,
      originalTransformationRequest: TransformationRequest,
      fallbackTransformationRequest: TransformationRequest
    ) {
      warnings += fallbackWarnings(originalTransformationRequest, fallbackTransformationRequest)
    }

    private fun startPolling() {
      if (polling) return
      polling = true
      pollers.incrementAndGet()
      handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
      if (!polling) return
      polling = false
      pollers.decrementAndGet()
      handler.removeCallbacks(poll)
    }

    private fun pollProgress() {
      if (!polling) return
      val active = transformer
      if (active == null) {
        stopPolling()
        return
      }
      when (active.getProgress(progressHolder)) {
        Transformer.PROGRESS_STATE_AVAILABLE ->
          emit(Progress(STAGE_ENCODING, progressHolder.progress / 100.0, "Encoding video"))
        Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY ->
          emit(Progress(STAGE_ENCODING, null, "Encoding video"))
        // The encoder cannot report a fraction on this device (no duration, or a transmux). The
        // stage is still honest, and a null fraction is what the state machine's indeterminate
        // bar exists for.
        Transformer.PROGRESS_STATE_UNAVAILABLE ->
          emit(Progress(STAGE_ENCODING, null, "Encoding video"))
        // Either the export has not begun or it is over. Either way there is nothing to report,
        // and the terminal callbacks own stopping the poll.
        else -> Unit
      }
      if (polling) handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun emit(progress: Progress) {
      // The bridge is not free and a repeated payload tells the user nothing.
      if (progress == lastProgress) return
      lastProgress = progress
      onProgress(progress)
    }

    /** Every failure path: cancellation, a media3 error, a bad plan, a file that is not the file. */
    private fun fail(error: Throwable) {
      output?.delete()
      settle(Result.failure(error))
    }

    private fun settle(result: Result<Outcome>) {
      if (!settled.compareAndSet(false, true)) return
      stopPolling()
      runs.remove(exportId, this)
      transformer?.removeListener(this)
      transformer = null
      if (keepAlive) {
        keepAlive = false
        KeepAliveLease.release(context)
      }
      onSettled(result)
    }

    /**
     * What the file has to be before it is handed back: present, non-empty, the length the plan
     * promised, and still carrying its audio.
     *
     * media3 finishes a truncated export as a *success* when the muxer stops receiving samples, so
     * without this check a dropped decoder surfaces as a meme that ends early.
     */
    private fun verify(plan: VideoExportPlan, file: File, result: ExportResult) {
      if (!file.isFile || file.length() <= 0L) {
        throw IOException("The encoder reported success but produced no file")
      }
      if (expectsAudio && result.channelCount <= 0 && result.audioMimeType == null) {
        throw IOException("The export lost the source's audio track")
      }
      val expectedMs = plan.expectedOutputDurationUs / 1_000L
      // `approximateDurationMs`, not the deprecated `durationMs`: media3 reports what the muxer
      // wrote, which is a sample-count estimate. The tolerance below exists for exactly that.
      val actualMs = result.approximateDurationMs
      if (expectedMs > 0L && actualMs > 0L) {
        val tolerance = max(DURATION_TOLERANCE_MS, (expectedMs * DURATION_TOLERANCE_FRACTION).toLong())
        if (expectedMs - actualMs > tolerance) {
          throw IOException(
            "The export stopped early: ${actualMs / 1000.0}s of the ${expectedMs / 1000.0}s it " +
              "should be"
          )
        }
        if (actualMs - expectedMs > tolerance) {
          warnings += "The result runs ${actualMs / 1000.0}s instead of ${expectedMs / 1000.0}s"
        }
      }
    }

    private fun createOutputFile(planId: String): File {
      val dir = exportCacheDir(context)
      if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
      sweepStaleOutputs(dir)
      val safe = planId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "meme" }.padEnd(3, '_')
      // One file per invocation: a plan id is not unique in time, and two runs of the same project
      // sharing a path would interleave their bytes.
      return File.createTempFile("${safe}_", ".mp4", dir)
    }

    private fun sweepStaleOutputs(dir: File) {
      val cutoff = System.currentTimeMillis() - STALE_OUTPUT_MS
      dir.listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) file.delete()
      }
    }
  }

  /** What media3 changed about the request, in the user's terms. */
  internal fun fallbackWarnings(
    original: TransformationRequest,
    fallback: TransformationRequest
  ): List<String> {
    val notes = mutableListOf<String>()
    if (original.videoMimeType != fallback.videoMimeType) {
      notes += "This device could not encode ${codecName(original.videoMimeType)}, so the video " +
        "is ${codecName(fallback.videoMimeType)}"
    }
    if (original.audioMimeType != fallback.audioMimeType) {
      notes += "This device could not encode ${codecName(original.audioMimeType)} audio, so the " +
        "audio is ${codecName(fallback.audioMimeType)}"
    }
    if (original.outputHeight != fallback.outputHeight && fallback.outputHeight > 0) {
      notes += "The encoder capped the height at ${fallback.outputHeight}p"
    }
    if (original.hdrMode != fallback.hdrMode) {
      notes += "HDR could not be kept, so the colours were tone-mapped to SDR"
    }
    return notes
  }

  /** What the finished file turned out to be, wherever that differs from what was asked for. */
  internal fun resultWarnings(plan: VideoExportPlan, result: ExportResult): List<String> {
    val notes = mutableListOf<String>()
    val videoMime = result.videoMimeType
    if (videoMime != null && videoMime != MimeTypes.VIDEO_H264) {
      notes += "Saved as ${codecName(videoMime)} video${bitrateSuffix(result.averageVideoBitrate)}"
    }
    val audioMime = result.audioMimeType
    if (audioMime != null && audioMime != MimeTypes.AUDIO_AAC) {
      notes += "Saved with ${codecName(audioMime)} audio"
    }
    val width = plan.outputSize.width
    val height = plan.outputSize.height
    if (result.width > 0 && result.height > 0 && (result.width != width || result.height != height)) {
      notes += "Saved at ${result.width}x${result.height} instead of ${width}x$height" +
        bitrateSuffix(result.averageVideoBitrate)
    }
    return notes
  }

  private fun bitrateSuffix(bitrate: Int): String =
    if (bitrate > 0) " at ${(bitrate / 100_000.0).roundToInt() / 10.0} Mb/s" else ""

  /** `video/avc` means nothing to a user; "H.264" is on the box their phone came in. */
  private fun codecName(mimeType: String?): String = when (mimeType) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "H.265"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB -> "AMR"
    null -> "an unknown codec"
    else -> mimeType
  }
}
