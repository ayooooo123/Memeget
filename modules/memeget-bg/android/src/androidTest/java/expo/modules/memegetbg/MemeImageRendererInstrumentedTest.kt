package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    try {
      MemeImageRenderer.render(context, json.toString())
      throw AssertionError("expected an unsupported-version failure")
    } catch (error: IOException) {
      assertTrue(error.message.orEmpty().contains("Unsupported render plan version"))
    } finally {
      File(requireNotNull(source.path)).delete()
    }
  }

  @Test
  fun repeatedRendersStayStableAndDoNotLeakDescriptors() {
    val source = writeFixtureSource("render-fixture-repeat.png")
    val planJson = plan(TRANSPARENT_PLAN, source)
    MemeImageRenderer.render(context, planJson)
    val descriptorsBefore = File("/proc/self/fd").list()?.size ?: 0
    var lastLength = 0L
    repeat(5) {
      val file = renderedFile(planJson)
      if (lastLength != 0L) assertEquals("deterministic encode", lastLength, file.length())
      lastLength = file.length()
    }
    val descriptorsAfter = File("/proc/self/fd").list()?.size ?: 0
    assertTrue(
      "renderer leaked descriptors: before=$descriptorsBefore after=$descriptorsAfter",
      descriptorsAfter <= descriptorsBefore + 2
    )
    File(context.cacheDir, "meme_render/gate-transparent.png").delete()
    File(requireNotNull(source.path)).delete()
  }
}
