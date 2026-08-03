package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for the full-resolution still exporter.
 *
 * The two plan fixtures are byte-for-byte what `buildImageRenderPlan` emits —
 * src/memeImageRenderCore.test.ts pins them against the TS builder, so this
 * test cannot drift away from production input. Only `source.uri` is patched,
 * because it has to point at a file this test just wrote.
 */
@RunWith(AndroidJUnit4::class)
class MemeImageRendererInstrumentedTest {
  private companion object {
    const val ROTATED_PLAN = "render_plan_rotated_cropped.json"
    const val TRANSPARENT_PLAN = "render_plan_transparent.json"
    val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    // Fixture source quadrants (640x400).
    const val TOP_LEFT = Color.RED
    const val TOP_RIGHT = Color.GREEN
    const val BOTTOM_LEFT = Color.BLUE
    const val BOTTOM_RIGHT = Color.YELLOW

    // Large EXIF-rotated fixture, in raw (pre-orientation) pixels.
    const val LARGE_SOURCE_WIDTH = 3264
    const val LARGE_SOURCE_HEIGHT = 2448
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  /**
   * 640x400 with four flat quadrants plus a 1px checkerboard patch. The
   * checkerboard is positioned so that — after the plan's 90 degree rotation
   * and crop — it lands exactly under the pixelate cover, which is what makes
   * "did the mosaic actually average pixels" answerable.
   */
  private fun writeFixtureSource(name: String): Uri {
    val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
    for (y in 0 until 400) {
      for (x in 0 until 640) {
        val quadrant = when {
          x < 320 && y < 200 -> TOP_LEFT
          x >= 320 && y < 200 -> TOP_RIGHT
          x < 320 -> BOTTOM_LEFT
          else -> BOTTOM_RIGHT
        }
        val checkered = x in 360 until 540 && y in 110 until 210
        bitmap.setPixel(x, y, if (checkered) (if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK) else quadrant)
      }
    }
    val file = File(context.cacheDir, name)
    FileOutputStream(file).use { stream ->
      assertTrue("fixture png encoded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
    }
    bitmap.recycle()
    return Uri.fromFile(file)
  }

  private fun plan(asset: String, sourceUri: Uri): String =
    instrumentation.context.assets.open(asset).bufferedReader().use { it.readText() }
      .replace("SOURCE_URI", sourceUri.toString())

  private fun renderedFile(planJson: String): File {
    val path = MemeImageRenderer.render(context, planJson)
    val file = File(requireNotNull(Uri.parse(path).path) { "renderer returned $path" })
    assertTrue("renderer wrote $path", file.isFile && file.length() > 0)
    return file
  }

  private fun assertPngSignature(file: File) {
    val header = ByteArray(8)
    file.inputStream().use { input -> assertEquals(8, input.read(header)) }
    assertEquals(
      "PNG magic bytes",
      PNG_SIGNATURE.joinToString(",") { it.toInt().and(0xFF).toString(16) },
      header.joinToString(",") { it.toInt().and(0xFF).toString(16) }
    )
  }

  private fun decode(file: File): Bitmap = requireNotNull(
    BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    )
  ) { "could not decode ${file.absolutePath}" }

  /**
   * An 8 MP camera-shaped JPEG tagged EXIF orientation 6, quadrant-coloured so
   * the orientation the renderer applies is observable in the output.
   */
  private fun writeLargeExifRotatedSource(name: String): File {
    val bitmap = Bitmap.createBitmap(LARGE_SOURCE_WIDTH, LARGE_SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val halfWidth = LARGE_SOURCE_WIDTH / 2f
    val halfHeight = LARGE_SOURCE_HEIGHT / 2f
    val quadrants = listOf(
      RectF(0f, 0f, halfWidth, halfHeight) to TOP_LEFT,
      RectF(halfWidth, 0f, LARGE_SOURCE_WIDTH.toFloat(), halfHeight) to TOP_RIGHT,
      RectF(0f, halfHeight, halfWidth, LARGE_SOURCE_HEIGHT.toFloat()) to BOTTOM_LEFT,
      RectF(halfWidth, halfHeight, LARGE_SOURCE_WIDTH.toFloat(), LARGE_SOURCE_HEIGHT.toFloat())
        to BOTTOM_RIGHT
    )
    for ((rect, color) in quadrants) {
      paint.color = color
      canvas.drawRect(rect, paint)
    }
    val file = File(context.cacheDir, name)
    FileOutputStream(file).use { stream ->
      assertTrue("fixture jpeg encoded", bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
    }
    bitmap.recycle()
    ExifInterface(file.absolutePath).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
      saveAttributes()
    }
    return file
  }

  /**
   * Peak growth of the native heap — where bitmap pixels live — while [block]
   * runs. Sampled from a second thread because the render is one synchronous
   * call and the transient copies this guards against never outlive it.
   */
  private fun measuringPeakNativeHeap(block: () -> Unit): Long {
    Runtime.getRuntime().gc()
    Thread.sleep(250)
    val baseline = Debug.getNativeHeapAllocatedSize()
    val peak = AtomicLong(baseline)
    val running = AtomicBoolean(true)
    val sampler = Thread {
      while (running.get()) {
        val allocated = Debug.getNativeHeapAllocatedSize()
        if (allocated > peak.get()) peak.set(allocated)
        try {
          Thread.sleep(2)
        } catch (error: InterruptedException) {
          return@Thread
        }
      }
    }
    sampler.start()
    try {
      block()
    } finally {
      running.set(false)
      sampler.join(2_000)
    }
    return peak.get() - baseline
  }

  private fun renderDirectorySnapshot(): Set<String> =
    File(context.cacheDir, "meme_render").list()?.toSet() ?: emptySet()

  // JPEG is lossy; a flat quadrant survives it to within a few units.
  private fun assertColorNear(message: String, expected: Int, actual: Int) {
    val delta = maxOf(
      Math.abs(Color.red(expected) - Color.red(actual)),
      Math.abs(Color.green(expected) - Color.green(actual)),
      Math.abs(Color.blue(expected) - Color.blue(actual))
    )
    assertTrue(
      "$message: expected #${Integer.toHexString(expected)}, got #${Integer.toHexString(actual)}",
      delta <= 24
    )
  }

  @Test
  fun rendersRotatedAndCroppedProjectAtPlanDimensionsWithRealMosaic() {
    val source = writeFixtureSource("render-fixture-rotated.png")
    val planJson = plan(ROTATED_PLAN, source)
    assertEquals(
      "committed fixture speaks the renderer's plan version",
      MemeImageRenderer.PLAN_VERSION,
      JSONObject(planJson).getInt("version")
    )
    val file = renderedFile(planJson)
    assertPngSignature(file)

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    // 640x400 rotated 90 -> 400x640, cropped 0.5 x 0.8 -> 200x512.
    assertEquals(200, bounds.outWidth)
    assertEquals(512, bounds.outHeight)

    val rendered = decode(file)
    try {
      // Rotation actually happened: the source's top-left quadrant is now on
      // the output's right-hand side, and the bottom-right one is bottom-left.
      assertEquals("rotated top-left quadrant", TOP_LEFT, rendered.getPixel(150, 10))
      assertEquals("rotated top-right quadrant", TOP_RIGHT, rendered.getPixel(150, 500))
      assertEquals("rotated bottom-right quadrant", BOTTOM_RIGHT, rendered.getPixel(10, 500))
      // Solid cover, drawn in output pixels straight from the plan rect.
      assertEquals("solid cover", Color.parseColor("#FF00FF"), rendered.getPixel(10, 10))

      // Real mosaic: the checkerboard underneath alternates every pixel, so
      // neighbours inside one 16px cell can only match if they were averaged,
      // and the average can be neither pure black nor pure white.
      val cellPixel = rendered.getPixel(110, 440)
      assertEquals("mosaic cell is flat", cellPixel, rendered.getPixel(111, 440))
      assertEquals("mosaic cell is flat", cellPixel, rendered.getPixel(114, 440))
      assertNotEquals("mosaic averaged, not copied", Color.WHITE, cellPixel)
      assertNotEquals("mosaic averaged, not copied", Color.BLACK, cellPixel)
      assertTrue("mosaic average is mid-grey", Color.red(cellPixel) in 60..200)
      assertEquals("mosaic stays opaque", 255, Color.alpha(cellPixel))
    } finally {
      rendered.recycle()
      file.delete()
      File(requireNotNull(source.path)).delete()
    }
  }

  @Test
  fun rendersTransparentBackgroundWithBurnedInText() {
    val source = writeFixtureSource("render-fixture-transparent.png")
    val file = renderedFile(plan(TRANSPARENT_PLAN, source))
    assertPngSignature(file)

    val rendered = decode(file)
    try {
      assertEquals(640, rendered.width)
      assertEquals(400, rendered.height)
      // Nothing was drawn in the bottom-right corner, so the alpha channel
      // survived the PNG encode.
      assertEquals("transparent corner", 0, Color.alpha(rendered.getPixel(630, 390)))
      assertEquals("transparent corner", 0, Color.alpha(rendered.getPixel(5, 395)))
      // The solid cover is the only opaque block besides the text.
      assertEquals("solid cover", Color.parseColor("#FF00FF"), rendered.getPixel(10, 10))

      // Burned-in text: outside the cover rect the ONLY possible opaque pixels
      // are the glyph fill and its stroke, so counting them proves real text
      // was rasterized — and both colors must be present.
      var white = 0
      var black = 0
      var opaqueOutsideCover = 0
      for (y in 120 until 400) {
        for (x in 0 until 640) {
          val pixel = rendered.getPixel(x, y)
          if (Color.alpha(pixel) < 255) continue
          opaqueOutsideCover++
          if (Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240) white++
          if (Color.red(pixel) < 15 && Color.green(pixel) < 15 && Color.blue(pixel) < 15) black++
        }
      }
      assertTrue("text rasterized ($opaqueOutsideCover opaque px)", opaqueOutsideCover > 500)
      assertTrue("glyph fill present ($white px)", white > 200)
      assertTrue("glyph stroke present ($black px)", black > 200)
    } finally {
      rendered.recycle()
      file.delete()
      File(requireNotNull(source.path)).delete()
    }
  }

  @Test
  fun honorsTheDownscaledOutputSizeTheMemoryGuardEmitted() {
    val source = writeFixtureSource("render-fixture-downscaled.png")
    val json = JSONObject(plan(ROTATED_PLAN, source))
    // What buildImageRenderPlan emits when the pixel cap bites: a reduced
    // output with an explicit scale factor. The renderer must allocate exactly
    // that, never the full size.
    json.getJSONObject("output").apply {
      put("widthPx", 100)
      put("heightPx", 256)
      put("downscaled", true)
      put("scale", 0.5)
    }
    val file = renderedFile(json.toString())
    try {
      assertPngSignature(file)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      assertEquals(100, bounds.outWidth)
      assertEquals(256, bounds.outHeight)
    } finally {
      file.delete()
      File(requireNotNull(source.path)).delete()
    }
  }

  @Test
  fun rejectsAnUnsupportedPlanVersionInsteadOfWritingAFile() {
    val source = writeFixtureSource("render-fixture-version.png")
    val json = JSONObject(plan(ROTATED_PLAN, source)).put("version", 99)
    val before = renderDirectorySnapshot()
    try {
      MemeImageRenderer.render(context, json.toString())
      throw AssertionError("expected an unsupported-version failure")
    } catch (error: IOException) {
      assertTrue(error.message.orEmpty().contains("Unsupported render plan version"))
      assertEquals("rejected plan wrote a file", before, renderDirectorySnapshot())
    } finally {
      File(requireNotNull(source.path)).delete()
    }
  }

  @Test
  fun repeatedRendersStayStableAndDoNotLeakDescriptors() {
    val source = writeFixtureSource("render-fixture-repeat.png")
    val planJson = plan(TRANSPARENT_PLAN, source)
    val written = ArrayList<File>()
    written.add(File(requireNotNull(Uri.parse(MemeImageRenderer.render(context, planJson)).path)))
    val descriptorsBefore = File("/proc/self/fd").list()?.size ?: 0
    var lastLength = 0L
    repeat(5) {
      val file = renderedFile(planJson)
      written.add(file)
      if (lastLength != 0L) assertEquals("deterministic encode", lastLength, file.length())
      lastLength = file.length()
    }
    val descriptorsAfter = File("/proc/self/fd").list()?.size ?: 0
    assertTrue(
      "renderer leaked descriptors: before=$descriptorsBefore after=$descriptorsAfter",
      descriptorsAfter <= descriptorsBefore + 2
    )
    written.forEach { it.delete() }
    File(requireNotNull(source.path)).delete()
  }

  /**
   * RENDER-MEM-001. An 8 MP EXIF-rotated camera JPEG used to be decoded whole
   * (32 MB) and then copied whole again by orientBitmapForExif (another 32 MB,
   * both alive at once) no matter how small the export was. The decode is now
   * sampled down to what the output rect consumes and the orientation rides in
   * the draw matrix, so nothing full-size is ever materialized.
   */
  @Test
  fun rendersALargeExifRotatedSourceWithinABoundedFootprint() {
    val source = writeLargeExifRotatedSource("render-fixture-large.jpg")
    val json = JSONObject(plan(ROTATED_PLAN, Uri.fromFile(source)))
    json.getJSONObject("source").apply {
      put("widthPx", LARGE_SOURCE_HEIGHT)
      put("heightPx", LARGE_SOURCE_WIDTH)
      put("rotation", 0)
      put("crop", JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 1))
    }
    // 2448x3264 as displayed, exported at 1/8 — the shape the plan builder
    // emits once the pixel guard bites on a phone photo.
    json.getJSONObject("output").apply {
      put("widthPx", 306)
      put("heightPx", 408)
      put("downscaled", true)
      put("scale", 0.125)
    }
    json.put("layers", JSONArray())

    var file: File? = null
    val peakBytes = measuringPeakNativeHeap { file = renderedFile(json.toString()) }
    val rendered = decode(requireNotNull(file))
    try {
      assertEquals(306, rendered.width)
      assertEquals(408, rendered.height)
      // EXIF orientation 6 rotates the raw frame a quarter turn clockwise, so
      // every raw quadrant has to land on the adjacent display quadrant.
      assertColorNear("exif applied: raw bottom-left", BOTTOM_LEFT, rendered.getPixel(48, 48))
      assertColorNear("exif applied: raw top-left", TOP_LEFT, rendered.getPixel(256, 48))
      assertColorNear("exif applied: raw bottom-right", BOTTOM_RIGHT, rendered.getPixel(48, 360))
      assertColorNear("exif applied: raw top-right", TOP_RIGHT, rendered.getPixel(256, 360))
      // Two full-size copies of this source are 64 MB; the sampled decode plus
      // the 0.5 MB output is nowhere near this bound.
      assertTrue(
        "peak native heap grew by ${peakBytes / (1024 * 1024)} MB during the render",
        peakBytes < 24L * 1024L * 1024L
      )
    } finally {
      rendered.recycle()
      requireNotNull(file).delete()
      source.delete()
    }
  }

  /**
   * RENDER-INT-002. `column * regionWidth / columns` overflows Int once the
   * region is wider than 46340 with a one-pixel cell; the wrapped value lands
   * on a negative pixel index a third of the way across.
   */
  @Test
  fun pixelatesAnExtremeAspectRegionWithoutOverflowingTheCellMath() {
    val width = 70_000
    val target = Bitmap.createBitmap(width, 2, Bitmap.Config.ARGB_8888)
    try {
      val pixels = IntArray(width * 2) { index ->
        if (index % width % 2 == 0) Color.WHITE else Color.BLACK
      }
      target.setPixels(pixels, 0, width, 0, 0, width, 2)
      MemeImageRenderer.drawPixelateMosaic(
        Canvas(target),
        target,
        RectF(0f, 0f, width.toFloat(), 2f),
        1
      )

      // Two-pixel cells over a one-pixel checkerboard: every cell is mid-grey,
      // including the ones past the 46340-pixel overflow point.
      for (x in intArrayOf(0, 30_679, 46_341, width - 1)) {
        val averaged = target.getPixel(x, 0)
        assertEquals("cell at $x averaged", 127, Color.red(averaged))
        assertEquals("cell at $x averaged", 127, Color.blue(averaged))
        assertEquals("cell at $x stays opaque", 255, Color.alpha(averaged))
      }
    } finally {
      target.recycle()
    }
  }

  /**
   * RENDER-DOS-003. Cell count, not region area, is what a mosaic pays for.
   */
  @Test
  fun clampsTheMosaicCellToTheRegionAndSkipsAOnePixelMosaic() {
    // A full 16 MP canvas at pixelSize 1 would be 16M averaged cells; the same
    // floor src/memeImageRenderCore.ts applies keeps it at 65536.
    assertEquals(16, MemeImageRenderer.mosaicCellPx(4000, 4000, 1))
    // A cell the plan can afford is left exactly as asked.
    assertEquals(16, MemeImageRenderer.mosaicCellPx(80, 154, 16))
    assertEquals(1, MemeImageRenderer.mosaicCellPx(64, 64, 1))

    val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    try {
      val pixels = IntArray(64 * 64) { if (it % 2 == 0) Color.WHITE else Color.BLACK }
      target.setPixels(pixels, 0, 64, 0, 0, 64, 64)
      MemeImageRenderer.drawPixelateMosaic(Canvas(target), target, RectF(0f, 0f, 64f, 64f), 1)
      // A one-pixel cell averages each pixel with itself: a no-op, not 4096
      // read-and-repaint round trips.
      assertEquals(Color.WHITE, target.getPixel(0, 0))
      assertEquals(Color.BLACK, target.getPixel(1, 0))
    } finally {
      target.recycle()
    }
  }

  /** RENDER-ALLOC-004. */
  @Test
  fun rejectsAnOutputBeyondThePixelCapInsteadOfAllocatingIt() {
    val source = writeFixtureSource("render-fixture-oversized.png")
    val json = JSONObject(plan(ROTATED_PLAN, source))
    json.getJSONObject("output").put("widthPx", 8000).put("heightPx", 8000)
    val before = renderDirectorySnapshot()
    try {
      MemeImageRenderer.render(context, json.toString())
      throw AssertionError("expected an oversized-output failure")
    } catch (error: IOException) {
      assertTrue(error.message.orEmpty().contains("exceeds"))
      assertEquals("rejected plan wrote a file", before, renderDirectorySnapshot())
    } finally {
      File(requireNotNull(source.path)).delete()
    }
  }

  /**
   * RENDER-VAL-007 / RENDER-VAL-008. NaN passes `<= 0f`, survives `max`, turns
   * the whole matrix into NaN — a background-only PNG that looks like a
   * successful export — and reaches roundToInt as an IllegalArgumentException.
   */
  @Test
  fun rejectsNonFiniteNumbersAsAMalformedPlan() {
    val source = writeFixtureSource("render-fixture-nonfinite.png")
    val malformed = mapOf(
      "NaN crop" to JSONObject(plan(ROTATED_PLAN, source)).also {
        it.getJSONObject("source").getJSONObject("crop").put("width", "NaN")
      },
      "infinite cover rect" to JSONObject(plan(ROTATED_PLAN, source)).also {
        it.getJSONArray("layers").getJSONObject(0).getJSONObject("rect").put("height", "1e999")
      },
      "NaN wrap width" to JSONObject(plan(ROTATED_PLAN, source)).also {
        it.getJSONArray("layers")
          .getJSONObject(2)
          .getJSONObject("spec")
          .getJSONObject("canvas")
          .put("wrapWidthDip", "NaN")
      }
    )
    try {
      for ((label, json) in malformed) {
        val before = renderDirectorySnapshot()
        try {
          MemeImageRenderer.render(context, json.toString())
          throw AssertionError("expected $label to be rejected")
        } catch (error: IOException) {
          assertTrue(
            "$label: ${error.message}",
            error.message.orEmpty().contains("finite") || error.message.orEmpty().contains("range")
          )
          assertEquals("$label wrote a file", before, renderDirectorySnapshot())
        }
      }
    } finally {
      File(requireNotNull(source.path)).delete()
    }
  }

  /**
   * RENDER-MEM-005. The translucency layer is bounded to the text's content
   * box now, so it must still composite the whole glyph run at the right alpha.
   */
  @Test
  fun burnsInTranslucentTextThroughABoundedLayer() {
    val source = writeFixtureSource("render-fixture-translucent.png")
    val json = JSONObject(plan(TRANSPARENT_PLAN, source))
    json.getJSONArray("layers")
      .getJSONObject(1)
      .getJSONObject("spec")
      .getJSONObject("fill")
      .put("opacity", 0.5)
    val file = renderedFile(json.toString())
    val rendered = decode(file)
    try {
      assertEquals("transparent corner survives the layer", 0, Color.alpha(rendered.getPixel(630, 390)))
      var translucent = 0
      var opaque = 0
      for (y in 120 until 400) {
        for (x in 0 until 640) {
          val alpha = Color.alpha(rendered.getPixel(x, y))
          if (alpha in 100..160) translucent++
          if (alpha == 255) opaque++
        }
      }
      assertTrue("half-opacity text rasterized ($translucent px)", translucent > 500)
      assertEquals("nothing outside the cover stayed opaque", 0, opaque)
    } finally {
      rendered.recycle()
      file.delete()
      File(requireNotNull(source.path)).delete()
    }
  }

  /**
   * RENDER-FILE-006. The output path used to be a pure function of the plan id,
   * so two renders of one project raced into the same file and either one's
   * failure path deleted the other's finished PNG.
   */
  @Test
  fun givesEachRenderOfTheSamePlanItsOwnFile() {
    val source = writeFixtureSource("render-fixture-unique.png")
    val planJson = plan(TRANSPARENT_PLAN, source)
    val first = renderedFile(planJson)
    val second = renderedFile(planJson)
    try {
      assertNotEquals(first.absolutePath, second.absolutePath)
      assertTrue("first render survived the second", first.isFile && first.length() > 0)
      assertEquals("both renders are the same image", first.length(), second.length())
    } finally {
      first.delete()
      second.delete()
      File(requireNotNull(source.path)).delete()
    }
  }

  /** RENDER-URI-009. */
  @Test
  fun refusesToReadOutsideTheAppSandbox() {
    val rejected = mapOf(
      "file:///system/etc/hosts" to "app-local",
      "/proc/self/environ" to "app-local",
      "http://127.0.0.1/pixel.png" to "scheme"
    )
    for ((uri, expected) in rejected) {
      val json = JSONObject(plan(ROTATED_PLAN, Uri.parse("file:///ignored.png")))
      json.getJSONObject("source").put("uri", uri)
      val before = renderDirectorySnapshot()
      try {
        MemeImageRenderer.render(context, json.toString())
        throw AssertionError("expected $uri to be refused")
      } catch (error: IOException) {
        assertTrue("$uri: ${error.message}", error.message.orEmpty().contains(expected))
        assertEquals("$uri wrote a file", before, renderDirectorySnapshot())
      }
    }
  }
}
