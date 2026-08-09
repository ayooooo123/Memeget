package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof for the video overlay burn-in: a plan carrying an `overlay` is accepted, the
 * overlay is composited over every output frame scaled to fill it, and the transparent parts of
 * the overlay leave the source showing through.
 *
 * The overlay is a magenta top half over a transparent bottom half at the output frame size, the
 * shape `buildVideoOverlayRenderPlan` produces. Magenta is chosen because a 240p test clip has
 * effectively none of it, so "the top is magenta" and "the bottom is not" are both real signals.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MemeVideoOverlayInstrumentedTest {
  private companion object {
    const val EXPORT_TIMEOUT_SECONDS = 180L
    const val SAMPLE_TIME_US = 1_500_000L
  }

  private lateinit var context: Context
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    workDir = File(context.cacheDir, "video_overlay_test").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
  }

  @After
  fun tearDown() {
    workDir.deleteRecursively()
  }

  @Test
  fun aPlanWithNoOverlayDecodesToNoBitmap() {
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)
    val plan = VideoExportPlan.parse(
      ExportTestSupport.planJson(
        source,
        ExportTestSupport.SHORT_DURATION_US,
        ExportTestSupport.SHORT_WIDTH,
        ExportTestSupport.SHORT_HEIGHT
      )
    )
    assertNull("no overlay in the base plan", plan.overlay)
    assertNull("nothing to decode", plan.decodeOverlayBitmap(context))
  }

  @Test
  fun compositesTheOverlayOverEveryFrameAndHonoursItsTransparency() {
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)
    val overlayUri = writeOverlay(ExportTestSupport.SHORT_WIDTH, ExportTestSupport.SHORT_HEIGHT)

    val basePlan = JSONObject(
      ExportTestSupport.planJson(
        source,
        ExportTestSupport.SHORT_DURATION_US,
        ExportTestSupport.SHORT_WIDTH,
        ExportTestSupport.SHORT_HEIGHT
      )
    )
    basePlan.put(
      "overlay",
      JSONObject()
        .put("uri", overlayUri)
        .put("widthPx", ExportTestSupport.SHORT_WIDTH)
        .put("heightPx", ExportTestSupport.SHORT_HEIGHT)
    )

    val plan = VideoExportPlan.parse(basePlan.toString())
    assertNotNull("overlay parsed", plan.overlay)
    val overlayBitmap = requireNotNull(plan.decodeOverlayBitmap(context)) { "overlay did not decode" }
    val output = try {
      val sourceHasAudio = VideoExportPlan.sourceHasAudioTrack(context, plan.sourceUri)
      export(plan.buildComposition(sourceHasAudio, overlayBitmap), "overlaid.mp4")
    } finally {
      overlayBitmap.recycle()
    }

    assertTrue("exporter produced a file", output.isFile && output.length() > 0L)

    val frame = frameAt(output, SAMPLE_TIME_US)
    try {
      // The magenta top half burns in over the frame, at every corner of that half (proving the
      // overlay fills the frame width, not a centred sub-rect).
      assertMagenta("top centre", frame, 0.5f, 0.25f)
      assertMagenta("top left", frame, 0.08f, 0.08f)
      assertMagenta("top right", frame, 0.92f, 0.08f)
      // The transparent bottom half leaves the source showing through - not magenta.
      assertNotMagenta("bottom centre", frame, 0.5f, 0.75f)
    } finally {
      frame.recycle()
    }
  }

  // ---------------------------------------------------------------- helpers

  /** A magenta-over-transparent overlay PNG at the output frame size, written to the work dir. */
  private fun writeOverlay(width: Int, height: Int): String {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = Color.MAGENTA }
    canvas.drawRect(Rect(0, 0, width, height / 2), paint)
    val file = File(workDir, "overlay.png")
    file.outputStream().use { stream ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "overlay png failed to encode" }
    }
    bitmap.recycle()
    return Uri.fromFile(file).toString()
  }

  private fun assertMagenta(label: String, frame: Bitmap, xFraction: Float, yFraction: Float) {
    val pixel = sample(frame, xFraction, yFraction)
    assertTrue(
      "$label should be magenta, was #${Integer.toHexString(pixel)}",
      Color.red(pixel) > 180 && Color.blue(pixel) > 180 && Color.green(pixel) < 90
    )
  }

  private fun assertNotMagenta(label: String, frame: Bitmap, xFraction: Float, yFraction: Float) {
    val pixel = sample(frame, xFraction, yFraction)
    assertTrue(
      "$label should show the source, not the magenta overlay, was #${Integer.toHexString(pixel)}",
      !(Color.red(pixel) > 180 && Color.blue(pixel) > 180 && Color.green(pixel) < 90)
    )
  }

  private fun sample(frame: Bitmap, xFraction: Float, yFraction: Float): Int {
    val x = ((frame.width - 1) * xFraction).toInt().coerceIn(0, frame.width - 1)
    val y = ((frame.height - 1) * yFraction).toInt().coerceIn(0, frame.height - 1)
    return frame.getPixel(x, y)
  }

  private fun frameAt(file: File, timeUs: Long): Bitmap {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally {
      retriever.release()
    } ?: throw AssertionError("No frame at ${timeUs}us in $file")
  }

  private fun export(composition: Composition, name: String): File {
    val output = File(workDir, name).apply { delete() }
    val latch = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val transformerRef = AtomicReference<Transformer>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
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
    return output
  }
}
