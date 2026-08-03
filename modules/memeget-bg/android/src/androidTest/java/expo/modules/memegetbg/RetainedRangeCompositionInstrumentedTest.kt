package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Device proof for the multi-range composition: retained ranges concatenate without a gap, static
 * title cards land where the plan says they do, a rotated source stays rotated across every seam,
 * and an asset the exporter cannot honour is rejected with a readable reason instead of being
 * quietly substituted.
 *
 * Export assertions run four transcodes; the structural assertions cost nothing and run first.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class RetainedRangeCompositionInstrumentedTest {
  private companion object {
    const val TAG = "RetainedRangeComp"
    const val EXPORT_TIMEOUT_SECONDS = 180L

    // Same window the media3 device gate accepts: container timestamps are quantised by the muxer
    // and the encoder's final sample duration is an estimate.
    const val DURATION_TOLERANCE_US = 150_000L

    const val LANDSCAPE_ASSET = "composition_landscape_3s_240p.mp4"
    const val ROTATED_ASSET = "composition_rotated_3s_240p.mp4"
    const val CARD_ASSET = "composition_title_card_720x480.png"
    const val SILENT_ASSET = "synthetic_silent_1s_240p.mp4"
    const val SOURCE_DURATION_US = 3_000_000L
  }

  private lateinit var context: Context
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    workDir = File(context.cacheDir, "retained_range_composition").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
  }

  @After
  fun tearDown() {
    workDir.deleteRecursively()
  }

  // ---------------------------------------------------------------- structural

  @Test
  fun expectedOutputDurationCountsCardsOnTheSameTimelineAsTheRanges() {
    val segments = listOf(
      RetainedRangeComposition.Range(0L, 1_000_000L),
      RetainedRangeComposition.Card("file:///card.png", MimeTypes.IMAGE_PNG, 500_000L),
      RetainedRangeComposition.Range(2_000_000L, 2_500_000L)
    )

    assertEquals(2_000_000L, RetainedRangeComposition.expectedOutputDurationUs(segments))
    assertEquals(1_000_000L, RetainedRangeComposition.expectedOutputDurationUs(segments, 2f))
    // Ranges only: unchanged from the pre-card contract.
    assertEquals(
      1_500_000L,
      RetainedRangeComposition.expectedOutputDurationUs(
        listOf(
          RetainedRangeComposition.Range(0L, 1_000_000L),
          RetainedRangeComposition.Range(2_000_000L, 2_500_000L)
        )
      )
    )
  }

  @Test
  fun titleCardsRefuseDurationsTheExporterCannotRepresent() {
    assertThrows("below the floor") {
      RetainedRangeComposition.Card("file:///c.png", MimeTypes.IMAGE_PNG, 100_000L)
    }
    assertThrows("above the ceiling") {
      RetainedRangeComposition.Card("file:///c.png", MimeTypes.IMAGE_PNG, 11_000_000L)
    }
    assertThrows("not a whole millisecond") {
      RetainedRangeComposition.Card("file:///c.png", MimeTypes.IMAGE_PNG, 1_000_500L)
    }
    assertThrows("not a still image type") {
      RetainedRangeComposition.Card("file:///c.gif", "image/gif", 1_000_000L)
    }
    assertThrows("not an image at all") {
      RetainedRangeComposition.Card("file:///c.mp4", MimeTypes.VIDEO_H264, 1_000_000L)
    }
    // The supported set builds.
    RetainedRangeComposition.Card("file:///c.png", MimeTypes.IMAGE_PNG, 1_000_000L)
    RetainedRangeComposition.Card("file:///c.jpg", MimeTypes.IMAGE_JPEG, 200_000L)
  }

  @Test
  fun titleCardsRequireAnExplicitOutputSizeSoNoCardCanResizeTheComposition() {
    val segments = listOf(
      RetainedRangeComposition.Card("file:///card.png", MimeTypes.IMAGE_PNG, 1_000_000L),
      RetainedRangeComposition.Range(0L, 1_000_000L)
    )

    val error = assertThrows("card without an output size") {
      RetainedRangeComposition.buildTimeline(
        uri = "file:///clip.mp4",
        sourceDurationUs = SOURCE_DURATION_US,
        segments = segments,
        outputSize = null
      )
    }
    assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("outputSize"))

    // With a size it builds, and the card contributes an item of its own.
    val composition = RetainedRangeComposition.buildTimeline(
      uri = "file:///clip.mp4",
      sourceDurationUs = SOURCE_DURATION_US,
      segments = segments,
      outputSize = Size(320, 240)
    )
    assertEquals(2, composition.sequences[0].editedMediaItems.size)
  }

  @Test
  fun aSilentAudioTrackIsForcedOnlyWhenTheSourceActuallyHasAudio() {
    val segments = listOf(
      RetainedRangeComposition.Card("file:///card.png", MimeTypes.IMAGE_PNG, 1_000_000L),
      RetainedRangeComposition.Range(0L, 1_000_000L)
    )

    val withAudio = RetainedRangeComposition.buildTimeline(
      uri = "file:///clip.mp4",
      sourceDurationUs = SOURCE_DURATION_US,
      segments = segments,
      sourceHasAudio = true,
      outputSize = Size(320, 240)
    )
    val withoutAudio = RetainedRangeComposition.buildTimeline(
      uri = "file:///clip.mp4",
      sourceDurationUs = SOURCE_DURATION_US,
      segments = segments,
      sourceHasAudio = false,
      outputSize = Size(320, 240)
    )
    val rangesOnly = RetainedRangeComposition.build(
      uri = "file:///clip.mp4",
      sourceDurationUs = SOURCE_DURATION_US,
      ranges = listOf(RetainedRangeComposition.Range(0L, 1_000_000L))
    )

    @Suppress("DEPRECATION")
    assertTrue("a leading card needs silence under it", withAudio.sequences[0].forceAudioTrack)
    @Suppress("DEPRECATION")
    assertFalse("a silent source must not gain audio", withoutAudio.sequences[0].forceAudioTrack)
    @Suppress("DEPRECATION")
    assertFalse("range-only compositions are unchanged", rangesOnly.sequences[0].forceAudioTrack)
  }

  @Test
  fun refusesMoreSegmentsThanTheBoundedCeiling() {
    val segments = (0 until RetainedRangeComposition.MAX_SEGMENTS + 1).map { index ->
      RetainedRangeComposition.Range(index * 2_000L, index * 2_000L + 1_000L)
    }

    val error = assertThrows("past the ceiling") {
      RetainedRangeComposition.buildTimeline(
        uri = "file:///clip.mp4",
        sourceDurationUs = SOURCE_DURATION_US,
        segments = segments
      )
    }
    assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("segment"))
  }

  // ---------------------------------------------------------------- asset guard

  @Test
  fun acceptsTheAssetsTheCompositionCanActuallyDraw() {
    val card = copyAsset(CARD_ASSET)
    val clip = copyAsset(LANDSCAPE_ASSET)

    val rejections = RetainedRangeComposition.inspectAssets(
      context,
      listOf(
        RetainedRangeComposition.AssetRequirement(
          card.toURI().toString(),
          RetainedRangeComposition.AssetRole.TITLE_CARD,
          MimeTypes.IMAGE_PNG
        ),
        RetainedRangeComposition.AssetRequirement(
          clip.toURI().toString(),
          RetainedRangeComposition.AssetRole.REPLACEMENT_VIDEO
        ),
        RetainedRangeComposition.AssetRequirement(
          clip.toURI().toString(),
          RetainedRangeComposition.AssetRole.REPLACEMENT_AUDIO
        )
      )
    )

    assertEquals(rejections.toString(), emptyList<Any>(), rejections)
  }

  @Test
  fun rejectsIncompatibleReplacementAssetsWithAReadableReason() {
    val clip = copyAsset(LANDSCAPE_ASSET)
    val silent = copyAsset(SILENT_ASSET)
    val cardWithVideoBytes = File(workDir, "card_is_really_a_video.png").also { target ->
      clip.copyTo(target, overwrite = true)
    }
    val truncated = File(workDir, "card_truncated.png").also { target ->
      target.writeBytes(ByteArray(64) { 0x7f })
    }
    val missing = File(workDir, "card_missing.png")

    val rejections = RetainedRangeComposition.inspectAssets(
      context,
      listOf(
        RetainedRangeComposition.AssetRequirement(
          clip.toURI().toString(),
          RetainedRangeComposition.AssetRole.TITLE_CARD,
          MimeTypes.VIDEO_H264
        ),
        RetainedRangeComposition.AssetRequirement(
          cardWithVideoBytes.toURI().toString(),
          RetainedRangeComposition.AssetRole.TITLE_CARD,
          MimeTypes.IMAGE_PNG
        ),
        RetainedRangeComposition.AssetRequirement(
          truncated.toURI().toString(),
          RetainedRangeComposition.AssetRole.TITLE_CARD,
          MimeTypes.IMAGE_PNG
        ),
        RetainedRangeComposition.AssetRequirement(
          missing.toURI().toString(),
          RetainedRangeComposition.AssetRole.TITLE_CARD,
          MimeTypes.IMAGE_PNG
        ),
        RetainedRangeComposition.AssetRequirement(
          silent.toURI().toString(),
          RetainedRangeComposition.AssetRole.REPLACEMENT_AUDIO
        )
      )
    )

    for (rejection in rejections) Log.i(TAG, "rejected ${rejection.role}: ${rejection.reason}")
    assertEquals(rejections.toString(), 5, rejections.size)
    assertTrue(
      rejections[0].reason,
      rejections[0].reason.contains(MimeTypes.VIDEO_H264) &&
        rejections[0].reason.contains("still image")
    )
    assertTrue(
      rejections[1].reason,
      rejections[1].reason.contains("could not be decoded") ||
        rejections[1].reason.contains("not the declared")
    )
    assertTrue(rejections[2].reason, rejections[2].reason.contains("could not be decoded"))
    assertTrue(rejections[3].reason, rejections[3].reason.contains("could not be opened"))
    assertTrue(
      rejections[4].reason,
      rejections[4].reason.contains("no audio track")
    )
    for (rejection in rejections) {
      assertTrue("reason must be readable prose", rejection.reason.length in 16..400)
    }
  }

  // ---------------------------------------------------------------- exports

  @Test
  fun concatenatesRetainedRangesWithoutAGap() {
    val source = copyAsset(LANDSCAPE_ASSET)
    val ranges = listOf(
      RetainedRangeComposition.Range(200_000L, 900_000L),
      RetainedRangeComposition.Range(1_200_000L, 1_800_000L),
      RetainedRangeComposition.Range(2_100_000L, 2_900_000L)
    )
    val expectedUs = RetainedRangeComposition.expectedOutputDurationUs(ranges)
    assertEquals(2_100_000L, expectedUs)

    val output = export(
      RetainedRangeComposition.build(
        uri = source.toURI().toString(),
        sourceDurationUs = SOURCE_DURATION_US,
        ranges = ranges
      ),
      "gapless_speed_1.mp4"
    )
    val probe = inspect(output)

    assertCodecs(probe)
    assertDuration("three ranges at 1x", expectedUs, probe)
    assertNoSampleHole(probe)
  }

  @Test
  fun concatenatesRetainedRangesAtSpeedWithoutAGap() {
    val source = copyAsset(LANDSCAPE_ASSET)
    val ranges = listOf(
      RetainedRangeComposition.Range(200_000L, 900_000L),
      RetainedRangeComposition.Range(1_200_000L, 1_800_000L),
      RetainedRangeComposition.Range(2_100_000L, 2_900_000L)
    )
    val expectedUs = RetainedRangeComposition.expectedOutputDurationUs(ranges, 1.25f)
    assertEquals(1_680_000L, expectedUs)

    val output = export(
      RetainedRangeComposition.build(
        uri = source.toURI().toString(),
        sourceDurationUs = SOURCE_DURATION_US,
        ranges = ranges,
        speed = 1.25f
      ),
      "gapless_speed_125.mp4"
    )
    val probe = inspect(output)

    assertCodecs(probe)
    assertDuration("three ranges at 1.25x", expectedUs, probe)
    assertNoSampleHole(probe)
    // The drift this composition wiring exists to kill left video short by
    // `(1 - 1/speed) * offset` against a full-length audio track — 281ms on the gate's own
    // fixtures. DURATION_TOLERANCE_US catches that with room to spare. The gate's tighter 50ms
    // A/V criterion is a *hardware* criterion measured on a physical device; emulator-5554's AAC
    // encoder pads the last frames well past it, so asserting it here would test the emulator,
    // not the composition. Physical confirmation of the 50ms bound is still owed to the gate lane.
    val videoEndUs = requireNotNull(probe.videoEndUs)
    val audioEndUs = requireNotNull(probe.audioEndUs)
    assertTrue(
      "audio and video must end together, video=${videoEndUs}us audio=${audioEndUs}us",
      abs(videoEndUs - audioEndUs) <= DURATION_TOLERANCE_US
    )
  }

  @Test
  fun insertsTitleCardsWithoutResizingOrReorderingTheComposition() {
    val source = copyAsset(LANDSCAPE_ASSET)
    val card = copyAsset(CARD_ASSET).toURI().toString()
    val segments = listOf(
      RetainedRangeComposition.Card(card, MimeTypes.IMAGE_PNG, 1_000_000L),
      RetainedRangeComposition.Range(300_000L, 1_300_000L),
      RetainedRangeComposition.Card(card, MimeTypes.IMAGE_PNG, 600_000L),
      RetainedRangeComposition.Range(2_000_000L, 2_800_000L)
    )
    val expectedUs = RetainedRangeComposition.expectedOutputDurationUs(segments)
    assertEquals(3_400_000L, expectedUs)

    val output = export(
      RetainedRangeComposition.buildTimeline(
        uri = source.toURI().toString(),
        sourceDurationUs = SOURCE_DURATION_US,
        segments = segments,
        sourceHasAudio = true,
        outputSize = Size(320, 240)
      ),
      "title_cards.mp4"
    )
    val probe = inspect(output)

    assertCodecs(probe)
    assertDuration("two cards around two ranges", expectedUs, probe)
    assertNoSampleHole(probe)
    assertEquals("the leading card must not resize the composition", 320, probe.displayWidth)
    assertEquals(240, probe.displayHeight)
    // Card spans: 0..1.0s and 2.0..2.6s of output. Sample the middle of each.
    assertDominantChannel("leading card", output, probe, 500_000L, 0.5f, 0.5f, Channel.GREEN)
    assertDominantChannel("second card", output, probe, 2_300_000L, 0.5f, 0.5f, Channel.GREEN)
  }

  @Test
  fun preservesSourceOrientationAcrossEverySeam() {
    val source = copyAsset(ROTATED_ASSET)
    val card = copyAsset(CARD_ASSET).toURI().toString()
    val segments = listOf(
      RetainedRangeComposition.Range(100_000L, 900_000L),
      RetainedRangeComposition.Card(card, MimeTypes.IMAGE_PNG, 400_000L),
      RetainedRangeComposition.Range(1_200_000L, 2_000_000L),
      RetainedRangeComposition.Range(2_400_000L, 2_900_000L)
    )
    val expectedUs = RetainedRangeComposition.expectedOutputDurationUs(segments)
    assertEquals(2_500_000L, expectedUs)

    val output = export(
      RetainedRangeComposition.buildTimeline(
        uri = source.toURI().toString(),
        sourceDurationUs = SOURCE_DURATION_US,
        segments = segments,
        sourceHasAudio = true,
        // Display size of the rotated source: coded 320x240 with a quarter turn.
        outputSize = Size(240, 320)
      ),
      "rotated_seams.mp4"
    )
    val probe = inspect(output)

    assertCodecs(probe)
    assertDuration("rotated source with a card between seams", expectedUs, probe)
    // The static red/blue fixture encodes to a B-pyramid whose anchor for the last three frames
    // of a clipped range sits past the cut; the seam therefore holds a picture, it does not go
    // blank. See assertNoSampleHole.
    assertNoSampleHole(probe, maxHeldFrames = 3)
    assertEquals("portrait display width", 240, probe.displayWidth)
    assertEquals("portrait display height", 320, probe.displayHeight)

    // Output spans: 0..0.8s source, 0.8..1.2s card, 1.2..2.0s source, 2.0..2.5s source.
    // The source is red on the display TOP and blue below; a segment that lost the container
    // rotation would letterbox landscape content instead, leaving those samples black.
    for ((label, timeUs) in listOf(
      "first segment" to 400_000L,
      "segment after the card" to 1_600_000L,
      "segment after the second seam" to 2_250_000L
    )) {
      assertDominantChannel(label, output, probe, timeUs, 0.5f, 0.2f, Channel.RED)
      assertDominantChannel(label, output, probe, timeUs, 0.5f, 0.8f, Channel.BLUE)
    }
    assertDominantChannel("card between seams", output, probe, 1_000_000L, 0.5f, 0.5f, Channel.GREEN)
  }

  // ---------------------------------------------------------------- helpers

  private enum class Channel { RED, GREEN, BLUE }

  private data class Probe(
    val videoEndUs: Long?,
    val audioEndUs: Long?,
    val videoMime: String?,
    val audioMime: String?,
    val codedWidth: Int,
    val codedHeight: Int,
    val rotationDegrees: Int,
    val sampleTimesUs: List<Long>
  ) {
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) codedWidth else codedHeight
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) codedHeight else codedWidth
  }

  private fun copyAsset(name: String): File {
    val target = File(workDir, name)
    if (target.isFile && target.length() > 0L) return target
    InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    }
    return target
  }

  private fun assertThrows(label: String, block: () -> Unit): Throwable {
    try {
      block()
    } catch (error: Throwable) {
      return error
    }
    throw AssertionError("Expected a rejection for $label")
  }

  private fun assertCodecs(probe: Probe) {
    assertEquals("video must stay H.264", MimeTypes.VIDEO_H264, probe.videoMime)
    assertEquals("audio must stay AAC", MimeTypes.AUDIO_AAC, probe.audioMime)
  }

  private fun assertDuration(label: String, expectedUs: Long, probe: Probe) {
    val videoEndUs = requireNotNull(probe.videoEndUs) { "$label produced no video samples" }
    val audioEndUs = requireNotNull(probe.audioEndUs) { "$label produced no audio samples" }
    // A passing duration assertion is only convincing if the number it passed on is visible; the
    // whole point of this test is the measurement, not the boolean.
    Log.i(
      TAG,
      "$label: expected=${expectedUs}us video=${videoEndUs}us (${videoEndUs - expectedUs}us) " +
        "audio=${audioEndUs}us (${audioEndUs - expectedUs}us) ${probe.displayWidth}x" +
        "${probe.displayHeight} rotation=${probe.rotationDegrees}"
    )
    assertTrue(
      "$label: video ended at ${videoEndUs}us, expected ${expectedUs}us",
      abs(videoEndUs - expectedUs) <= DURATION_TOLERANCE_US
    )
    assertTrue(
      "$label: audio ended at ${audioEndUs}us, expected ${expectedUs}us",
      abs(audioEndUs - expectedUs) <= DURATION_TOLERANCE_US
    )
  }

  /**
   * No hole in the output video track. A dropped range shortens the file (which [assertDuration]
   * catches); a *gap* between concatenated items instead leaves a stretch of output time with no
   * frame, which only shows up as an outsized interval between consecutive sample timestamps.
   *
   * @param maxHeldFrames How many frames the last picture of a segment may be held for. Frame
   *   accurate clipping cannot emit a trailing B-frame whose anchor sits past the cut, so a source
   *   whose encoder reordered by N frames loses up to N of them at a seam - the timeline keeps its
   *   length, the final picture is simply shown a beat longer. Measured, not guessed: both
   *   fixtures report `has_b_frames=2`, and the static rotated fixture's pyramid puts the anchor
   *   for the last three frames of a clipped range after the cut, while the moving landscape
   *   fixture's does not.
   */
  private fun assertNoSampleHole(probe: Probe, maxHeldFrames: Int = 1) {
    val times = probe.sampleTimesUs
    // `sampleTimesUs` is already distinct and sorted, so every interval is positive.
    val intervals = times.zipWithNext { first, second -> second - first }
    assertTrue("expected a decodable video track", intervals.size > 10)
    val median = intervals.sorted()[intervals.size / 2]
    val largestIndex = intervals.indices.maxBy { intervals[it] }
    val largest = intervals[largestIndex]
    Log.i(
      TAG,
      "continuity: ${times.size} frames, median=${median}us, largest=${largest}us at " +
        "${times[largestIndex]}us -> ${times[largestIndex + 1]}us (allowed $maxHeldFrames held)"
    )
    assertTrue(
      "largest sample interval ${largest}us (${times[largestIndex]}us -> " +
        "${times[largestIndex + 1]}us, ${times.size} frames) holds a picture for more than " +
        "$maxHeldFrames frames of ${median}us",
      largest <= median * (maxHeldFrames + 1) + median / 2
    )
  }

  private fun assertDominantChannel(
    label: String,
    file: File,
    probe: Probe,
    timeUs: Long,
    xFraction: Float,
    yFraction: Float,
    channel: Channel
  ) {
    val frame = orientedFrame(file, probe, timeUs)
    try {
      val x = ((frame.width - 1) * xFraction).toInt().coerceIn(0, frame.width - 1)
      val y = ((frame.height - 1) * yFraction).toInt().coerceIn(0, frame.height - 1)
      var red = 0L
      var green = 0L
      var blue = 0L
      var samples = 0
      for (dy in -2..2) {
        for (dx in -2..2) {
          val pixel = frame.getPixel(
            (x + dx).coerceIn(0, frame.width - 1),
            (y + dy).coerceIn(0, frame.height - 1)
          )
          red += (pixel shr 16) and 0xff
          green += (pixel shr 8) and 0xff
          blue += pixel and 0xff
          samples += 1
        }
      }
      val average = intArrayOf((red / samples).toInt(), (green / samples).toInt(), (blue / samples).toInt())
      val observed = "rgb(${average[0]}, ${average[1]}, ${average[2]})"
      val (dominant, others) = when (channel) {
        Channel.RED -> average[0] to intArrayOf(average[1], average[2])
        Channel.GREEN -> average[1] to intArrayOf(average[0], average[2])
        Channel.BLUE -> average[2] to intArrayOf(average[0], average[1])
      }
      assertTrue(
        "$label at ${timeUs}us ($xFraction, $yFraction) should be $channel but was $observed",
        dominant >= 90 && others.all { dominant - it >= 40 }
      )
    } finally {
      frame.recycle()
    }
  }

  /**
   * A frame in DISPLAY orientation. `MediaMetadataRetriever` applies the container's rotation on
   * most builds but not all, so normalise it here — the rotation under test is the one media3 bakes
   * into each segment, not the platform's decode-time convenience.
   */
  private fun orientedFrame(file: File, probe: Probe, timeUs: Long): Bitmap {
    val retriever = MediaMetadataRetriever()
    val raw = try {
      retriever.setDataSource(file.absolutePath)
      retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally {
      retriever.release()
    } ?: throw AssertionError("No frame at ${timeUs}us in $file")
    if (raw.width == probe.displayWidth && raw.height == probe.displayHeight) return raw
    val matrix = Matrix().apply { postRotate(probe.rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
  }

  private fun export(composition: Composition, name: String): File {
    val output = File(workDir, name).apply { delete() }
    val latch = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val transformerRef = AtomicReference<Transformer>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val startedMs = SystemClock.elapsedRealtime()

    instrumentation.runOnMainSync {
      val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
          override fun onCompleted(composition: Composition, result: ExportResult) {
            latch.countDown()
          }

          override fun onError(
            composition: Composition,
            result: ExportResult,
            exception: ExportException
          ) {
            failure.set(exception)
            latch.countDown()
          }
        })
        .build()
      transformerRef.set(transformer)
      transformer.start(composition, output.absolutePath)
    }
    if (!latch.await(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      instrumentation.runOnMainSync { transformerRef.get().cancel() }
      throw AssertionError("Export of $name timed out after $EXPORT_TIMEOUT_SECONDS seconds")
    }
    failure.get()?.let { throw AssertionError("Export of $name failed", it) }
    check(output.isFile && output.length() > 0L) { "Export of $name produced no bytes" }
    Log.i(
      TAG,
      "exported $name in ${SystemClock.elapsedRealtime() - startedMs}ms, ${output.length()} bytes"
    )
    return output
  }

  private fun inspect(file: File): Probe {
    val extractor = MediaExtractor()
    var videoTrack: Int? = null
    var audioTrack: Int? = null
    var videoMime: String? = null
    var audioMime: String? = null
    var codedWidth = 0
    var codedHeight = 0
    var rotationDegrees = 0
    try {
      extractor.setDataSource(file.absolutePath)
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        when {
          mime.startsWith("video/") && videoMime == null -> {
            videoTrack = index
            videoMime = mime
            codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
              rotationDegrees = ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
            }
          }
          mime.startsWith("audio/") && audioMime == null -> {
            audioTrack = index
            audioMime = mime
          }
        }
      }
    } finally {
      extractor.release()
    }
    val videoTimes = videoTrack?.let { sampleTimesUs(file, it) } ?: emptyList()
    val audioTimes = audioTrack?.let { sampleTimesUs(file, it) } ?: emptyList()
    return Probe(
      videoEndUs = endTimeUs(videoTimes),
      audioEndUs = endTimeUs(audioTimes),
      videoMime = videoMime,
      audioMime = audioMime,
      codedWidth = codedWidth,
      codedHeight = codedHeight,
      rotationDegrees = rotationDegrees,
      sampleTimesUs = videoTimes
    )
  }

  private fun sampleTimesUs(file: File, trackIndex: Int): List<Long> {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      extractor.selectTrack(trackIndex)
      val timestamps = ArrayList<Long>()
      val buffer = ByteBuffer.allocate(1 shl 20)
      while (extractor.readSampleData(buffer, 0) >= 0) {
        timestamps += extractor.sampleTime
        buffer.clear()
        if (!extractor.advance()) break
      }
      timestamps.distinct().sorted()
    } finally {
      extractor.release()
    }
  }

  /** Last sample timestamp plus the median inter-sample interval: the track's real end. */
  private fun endTimeUs(sorted: List<Long>): Long? {
    if (sorted.isEmpty()) return null
    val intervals = sorted.zipWithNext { first, second -> second - first }.filter { it > 0L }.sorted()
    if (intervals.isEmpty()) return sorted.last()
    return sorted.last() + intervals[intervals.size / 2]
  }
}
