package expo.modules.memegetbg

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof for the export lifecycle, and above all for **cancel** - the path that leaks.
 *
 * A cancelled export has to give back four things nobody can see it keeping: the hardware codecs,
 * the progress callback, the partial file, and the keep-alive lease. None of them fail loudly at
 * the moment they leak; they fail later, somewhere else, as "the encoder is busy" or a foreground
 * notification that will not go away. So each one is asserted here, on a real encode, on hardware.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MemeVideoExportInstrumentedTest {
  private companion object {
    const val TAG = "MemeVideoExport"

    // 15 s of 1080p: long enough that a cancel lands mid-encode on fast hardware rather than
    // racing the finish, which is the case that would otherwise go untested.
    const val LONG_ASSET = "synthetic_15s_1080p.mp4"
    const val LONG_DURATION_US = 15_000_000L
    const val LONG_WIDTH = 1920
    const val LONG_HEIGHT = 1080

    // 3 s of 240p with an AAC track: the completion assertions, cheap enough to run twice.
    const val SHORT_ASSET = ExportTestSupport.SHORT_ASSET
    const val SHORT_DURATION_US = ExportTestSupport.SHORT_DURATION_US
    const val SHORT_WIDTH = ExportTestSupport.SHORT_WIDTH
    const val SHORT_HEIGHT = ExportTestSupport.SHORT_HEIGHT

    const val SETTLE_TIMEOUT_SECONDS = 180L
    const val PROGRESS_TIMEOUT_MS = 30_000L

    // Same window the media3 device gate accepts: the muxer quantises container timestamps and
    // the encoder's final sample duration is an estimate.
    const val DURATION_TOLERANCE_US = 150_000L
  }

  private lateinit var context: Context
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    workDir = File(context.cacheDir, "meme_video_export_test").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
    MemeVideoExporter.exportCacheDir(context).deleteRecursively()
  }

  @After
  fun tearDown() {
    workDir.deleteRecursively()
    MemeVideoExporter.exportCacheDir(context).deleteRecursively()
  }

  // ------------------------------------------------------------------- cancel

  @Test
  fun cancelMidEncodeSettlesOnceAndReleasesEverything() {
    val source = copyAsset(LONG_ASSET)
    val run = startExport(
      "cancel-mid-encode",
      planJson(source, LONG_DURATION_US, LONG_WIDTH, LONG_HEIGHT)
    )

    // A reported FRACTION, not just the encoding stage: media3 reports "waiting for
    // availability" the instant it is asked, and cancelling then would test the easy case
    // instead of the one that has to tear a running encoder down.
    val fraction = run.awaitEncodingFraction()
    assertTrue("no encoding fraction within ${PROGRESS_TIMEOUT_MS}ms", fraction != null)
    Log.i(TAG, "cancelling at ${fraction}")
    assertTrue("cancel was refused for a running export", MemeVideoExporter.cancel(run.id))

    val settleMs = run.awaitSettle()
    Log.i(TAG, "cancel unwound in ${settleMs}ms after ${run.progressCount()} progress reports")

    // Settled exactly once, as a cancellation and not as a failure: a second settle would be a
    // second meme, and a cancel dressed as an error is a red toast for something the user asked
    // for.
    assertEquals(1, run.settleCount())
    val error = run.result()?.exceptionOrNull()
    assertTrue("expected a cancellation, got $error", error is MemeVideoExporter.CancelledException)

    // The run is gone, so its transformer and codecs are unreachable.
    assertFalse(MemeVideoExporter.activeExportIds().contains(run.id))
    // The partial file is gone. A cancelled export that leaves bytes behind fills the cache with
    // files no screen in the app can show or delete.
    assertEquals(emptyList<String>(), exportedFileNames())
    // The keep-alive lease is back, so the foreground notification is down.
    assertEquals(0, KeepAliveLease.holderCount())

    // Polling stopped. The count, not the absence of further events: a leaked loop polls a
    // cancelled transformer, which answers PROGRESS_STATE_NOT_STARTED and so reports nothing at
    // all while still waking the main looper five times a second forever. A mutation that
    // deleted the stopPolling() call went unnoticed until this assertion existed.
    assertEquals(0, MemeVideoExporter.activePollCount())
    val afterSettle = run.progressCount()
    SystemClock.sleep(1_000L)
    assertEquals(afterSettle, run.progressCount())

    // And the codecs really came back: a second export of the same source completes. A leaked
    // encoder surfaces here as a timeout or ERROR_CODE_ENCODER_INIT_FAILED.
    val second = startExport(
      "after-cancel",
      planJson(copyAsset(SHORT_ASSET), SHORT_DURATION_US, SHORT_WIDTH, SHORT_HEIGHT)
    )
    second.awaitSettle()
    val outcome = second.result()?.getOrNull()
    assertNotNull("export after a cancel failed: ${second.result()?.exceptionOrNull()}", outcome)
  }

  @Test
  fun cancelBeforeTheEncoderStartsStillSettlesOnce() {
    val source = copyAsset(LONG_ASSET)
    val run = startExport(
      "cancel-immediately",
      planJson(source, LONG_DURATION_US, LONG_WIDTH, LONG_HEIGHT)
    )
    // No wait at all, and twice: two cancels in flight are two paths into the same teardown, and
    // the second one settling as well would deliver two outcomes for one export.
    MemeVideoExporter.cancel(run.id)
    MemeVideoExporter.cancel(run.id)

    run.awaitSettle()
    assertEquals(1, run.settleCount())
    assertTrue(run.result()?.exceptionOrNull() is MemeVideoExporter.CancelledException)
    assertEquals(emptyList<String>(), exportedFileNames())
    assertEquals(0, KeepAliveLease.holderCount())
    assertFalse(MemeVideoExporter.activeExportIds().contains(run.id))
  }

  @Test
  fun cancellingAFinishedExportChangesNothing() {
    val run = startExport(
      "cancel-after-completion",
      planJson(copyAsset(SHORT_ASSET), SHORT_DURATION_US, SHORT_WIDTH, SHORT_HEIGHT)
    )
    run.awaitSettle()
    val outcome = run.result()?.getOrNull()
    assertNotNull("export failed: ${run.result()?.exceptionOrNull()}", outcome)

    // Nothing left to cancel, and cancelling anyway must not delete the file the user is about to
    // be handed or settle the promise a second time.
    assertFalse(MemeVideoExporter.cancel(run.id))
    SystemClock.sleep(500L)
    assertEquals(1, run.settleCount())
    assertTrue(File(Uri.parse(outcome!!.uri).path!!).isFile)
    assertEquals(0, KeepAliveLease.holderCount())
  }

  // --------------------------------------------------------------- completion

  @Test
  fun completedExportIsAFullLengthH264AacMp4() {
    val run = startExport(
      "completion",
      planJson(copyAsset(SHORT_ASSET), SHORT_DURATION_US, SHORT_WIDTH, SHORT_HEIGHT)
    )
    val elapsed = run.awaitSettle()
    val outcome = run.result()?.getOrNull()
    assertNotNull("export failed: ${run.result()?.exceptionOrNull()}", outcome)
    val file = File(Uri.parse(outcome!!.uri).path!!)
    assertTrue(file.isFile && file.length() > 0L)

    val tracks = trackSummary(file)
    val videoDurationUs = tracks.videoDurationUs
    Log.i(
      TAG,
      "export took ${elapsed}ms, ${file.length()} bytes, video=${tracks.videoMime} " +
        "audio=${tracks.audioMime} ${tracks.width}x${tracks.height} " +
        "duration=${videoDurationUs}us warnings=${outcome.warnings}"
    )

    // Default output, stated in Task 7.1 and depended on by the clipboard path.
    assertEquals(MimeTypes.VIDEO_H264, tracks.videoMime)
    assertEquals(MimeTypes.AUDIO_AAC, tracks.audioMime)
    assertEquals(SHORT_WIDTH, tracks.width)
    assertEquals(SHORT_HEIGHT, tracks.height)
    // The whole clip, not a truncated one.
    assertTrue(
      "video track is ${videoDurationUs}us, expected ~${SHORT_DURATION_US}us",
      abs(videoDurationUs - SHORT_DURATION_US) <= DURATION_TOLERANCE_US
    )
    // Nothing was bent, so nothing is claimed: a warning here would be a lie about a clean export.
    assertEquals(emptyList<String>(), outcome.warnings)
    // Success is a terminal path like any other: it releases the poll and the lease.
    assertEquals(0, MemeVideoExporter.activePollCount())
    assertEquals(0, KeepAliveLease.holderCount())
  }

  @Test
  fun aPlanTheExporterCannotHonourFailsBeforeAnythingIsEncoded() {
    // A plan from a newer build is refused with a sentence rather than exported as whatever this
    // build happens to understand of it.
    val plan = JSONObject(planJson(copyAsset(SHORT_ASSET), SHORT_DURATION_US, SHORT_WIDTH, SHORT_HEIGHT))
      .put("version", 99)
    val run = startExport("bad-plan", plan.toString())
    run.awaitSettle()

    assertEquals(1, run.settleCount())
    val error = run.result()?.exceptionOrNull()
    assertNotNull(error)
    assertFalse(error is MemeVideoExporter.CancelledException)
    assertEquals(emptyList<String>(), exportedFileNames())
    assertEquals(0, KeepAliveLease.holderCount())
  }

  @Test
  fun whateverMedia3ChangedIsSaidInWordsTheUserCanRead() {
    // The device will not reliably refuse H.264, so the reporting itself is asserted directly:
    // an unsurfaced fallback is a file that silently differs from what the studio promised.
    val requested = TransformationRequest.Builder()
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .build()
    val fallback = TransformationRequest.Builder()
      .setVideoMimeType(MimeTypes.VIDEO_H265)
      .setAudioMimeType(MimeTypes.AUDIO_AMR_NB)
      .build()
    val notes = MemeVideoExporter.fallbackWarnings(requested, fallback)
    assertEquals(2, notes.size)
    assertTrue(notes[0], notes[0].contains("H.264") && notes[0].contains("H.265"))
    assertTrue(notes[1], notes[1].contains("AAC") && notes[1].contains("AMR"))
    assertEquals(emptyList<String>(), MemeVideoExporter.fallbackWarnings(requested, requested))

    val plan = VideoExportPlan.parse(
      planJson(copyAsset(SHORT_ASSET), SHORT_DURATION_US, SHORT_WIDTH, SHORT_HEIGHT)
    )
    val downscaled = ExportResult.Builder()
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setWidth(256)
      .setHeight(192)
      .setAverageVideoBitrate(2_400_000)
      .build()
    val resized = MemeVideoExporter.resultWarnings(plan, downscaled)
    assertEquals(1, resized.size)
    assertTrue(
      resized[0],
      resized[0].contains("256x192") && resized[0].contains("320x240") && resized[0].contains("Mb/s")
    )

    val asAsked = ExportResult.Builder()
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setWidth(SHORT_WIDTH)
      .setHeight(SHORT_HEIGHT)
      .build()
    assertEquals(emptyList<String>(), MemeVideoExporter.resultWarnings(plan, asAsked))
  }

  // ------------------------------------------------------------------ helpers

  private class RunHandle(val id: String) {
    private val latch = CountDownLatch(1)
    private val settles = AtomicInteger(0)
    private val outcome = AtomicReference<Result<MemeVideoExporter.Outcome>?>(null)
    private val progress = AtomicInteger(0)
    private val startedAt = SystemClock.elapsedRealtime()
    private val lastFraction = AtomicReference<Double?>(null)

    fun onProgress(report: MemeVideoExporter.Progress) {
      val fraction = report.fraction
      // Strictly above zero: media3 reports 0% as soon as it accepts the composition, so a
      // cancel there would still be a cancel before any frame was encoded.
      if (report.stage == MemeVideoExporter.STAGE_ENCODING && fraction != null && fraction > 0.0) {
        lastFraction.set(fraction)
      }
      progress.incrementAndGet()
    }

    fun onSettled(result: Result<MemeVideoExporter.Outcome>) {
      settles.incrementAndGet()
      outcome.compareAndSet(null, result)
      latch.countDown()
    }

    fun progressCount(): Int = progress.get()

    fun settleCount(): Int = settles.get()

    fun result(): Result<MemeVideoExporter.Outcome>? = outcome.get()

    /** Milliseconds from start to settle. Fails the test rather than hanging the suite. */
    fun awaitSettle(): Long {
      assertTrue(
        "export $id did not settle within ${SETTLE_TIMEOUT_SECONDS}s",
        latch.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      )
      return SystemClock.elapsedRealtime() - startedAt
    }

    /** The first fraction the encoder actually reported, or null if it never got that far. */
    fun awaitEncodingFraction(): Double? {
      val deadline = SystemClock.elapsedRealtime() + PROGRESS_TIMEOUT_MS
      while (SystemClock.elapsedRealtime() < deadline) {
        lastFraction.get()?.let { return it }
        if (latch.count == 0L) return null
        SystemClock.sleep(25L)
      }
      return null
    }
  }

  private fun startExport(id: String, planJson: String): RunHandle {
    val handle = RunHandle(id)
    MemeVideoExporter.start(context, id, planJson, handle::onProgress, handle::onSettled)
    return handle
  }

  private fun planJson(source: File, durationUs: Long, width: Int, height: Int): String =
    ExportTestSupport.planJson(source, durationUs, width, height)

  private fun exportedFileNames(): List<String> =
    MemeVideoExporter.exportCacheDir(context).listFiles()?.map { it.name }?.sorted() ?: emptyList()

  private class TrackSummary(
    val videoMime: String?,
    val audioMime: String?,
    val width: Int,
    val height: Int,
    val videoDurationUs: Long
  )

  /** Read back what the muxer actually wrote, rather than what the exporter said it wrote. */
  private fun trackSummary(file: File): TrackSummary {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      var videoMime: String? = null
      var audioMime: String? = null
      var width = 0
      var height = 0
      var videoDurationUs = 0L
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        when {
          mime.startsWith("video/") -> {
            videoMime = mime
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
          }
          mime.startsWith("audio/") -> audioMime = mime
        }
      }
      return TrackSummary(videoMime, audioMime, width, height, videoDurationUs)
    } finally {
      extractor.release()
    }
  }

  private fun copyAsset(name: String): File = ExportTestSupport.copyAsset(workDir, name)
}
