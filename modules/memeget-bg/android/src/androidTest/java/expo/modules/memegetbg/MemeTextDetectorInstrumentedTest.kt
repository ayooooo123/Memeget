package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemeTextDetectorInstrumentedTest {
  @Test
  fun normalizedBoxClipsToOrientedBoundsAndDropsOutside() {
    assertEquals(
      NormalizedImageRect(0.0, 0.1, 0.3, 0.5),
      MemeTextDetector.normalizePixelRect(-20, 10, 30, 60, 100, 100)
    )
    assertEquals(null, MemeTextDetector.normalizePixelRect(110, 10, 140, 30, 100, 100))
  }

  @Test
  fun borderSamplerReturnsRobustOpaqueChannelMedian() {
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLUE)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
      color = Color.rgb(210, 42, 24)
      style = Paint.Style.STROKE
      strokeWidth = 12f
    }
    canvas.drawRect(24f, 24f, 76f, 76f, paint)

    val sample = MemeTextDetector.sampleOrientedBitmapBorder(
      bitmap,
      NormalizedImageRect(0.3, 0.3, 0.4, 0.4)
    )

    assertEquals("#D22A18", sample.hex)
    assertTrue(sample.sampleCount in 1..MemeTextDetector.MAX_BORDER_SAMPLES)
    bitmap.recycle()
  }

  @Test
  fun appliesEveryExifOrientationToBitmapPixels() {
    val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
    source.setPixel(0, 0, Color.RED)
    source.setPixel(2, 0, Color.GREEN)
    source.setPixel(0, 1, Color.BLUE)
    source.setPixel(2, 1, Color.YELLOW)
    val expectations = listOf(
      ExifInterface.ORIENTATION_NORMAL to listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
      ExifInterface.ORIENTATION_ROTATE_180 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
      ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
      ExifInterface.ORIENTATION_TRANSPOSE to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
      ExifInterface.ORIENTATION_ROTATE_90 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
      ExifInterface.ORIENTATION_TRANSVERSE to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
      ExifInterface.ORIENTATION_ROTATE_270 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
    )

    for ((orientation, corners) in expectations) {
      val oriented = MemeTextDetector.orientBitmapForExif(source, orientation)
      assertEquals(if (orientation >= ExifInterface.ORIENTATION_TRANSPOSE) 2 else 3, oriented.width)
      assertEquals(if (orientation >= ExifInterface.ORIENTATION_TRANSPOSE) 3 else 2, oriented.height)
      assertEquals(corners[0], oriented.getPixel(0, 0))
      assertEquals(corners[1], oriented.getPixel(oriented.width - 1, 0))
      assertEquals(corners[2], oriented.getPixel(0, oriented.height - 1))
      assertEquals(corners[3], oriented.getPixel(oriented.width - 1, oriented.height - 1))
      if (oriented !== source) oriented.recycle()
    }
    source.recycle()
  }

  @Test
  fun generatedTextProducesRealNestedOcrWhenModelIsAvailableAndRepeatsSafely() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val bitmap = Bitmap.createBitmap(720, 320, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    Canvas(bitmap).drawText(
      "HELLO",
      70f,
      210f,
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 150f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      }
    )
    val file = File(context.cacheDir, "meme_text_detector_generated.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()

    try {
      // Warm up the Play Services process/model before counting descriptors;
      // its long-lived model mappings are runtime state, not per-call leaks.
      MemeTextDetector.detect(context, file.toURI().toString())
      val descriptorCountBefore = File("/proc/self/fd").list()?.size ?: 0
      repeat(8) {
        val result = MemeTextDetector.detect(context, file.toURI().toString())
        assertEquals(720, result.sourceWidth)
        assertEquals(320, result.sourceHeight)
        assertTrue(result.blocks.any { block ->
          block.text.contains("HELLO", ignoreCase = true) &&
            block.lines.isNotEmpty() &&
            block.lines.any { line -> line.elements.isNotEmpty() }
        })
      }
      val descriptorCountAfter = File("/proc/self/fd").list()?.size ?: 0
      assertTrue(
        "OCR leaked descriptors: before=$descriptorCountBefore after=$descriptorCountAfter",
        descriptorCountAfter <= descriptorCountBefore + 2
      )
    } catch (error: Throwable) {
      if (error.message.orEmpty().contains("recognize text", ignoreCase = true)) {
        // The Play Services OCR model may not be provisioned on a fresh/offline
        // emulator. Skip rather than fabricating OCR output; connected-run
        // output reports this assumption explicitly.
        assumeNoException("ML Kit OCR model unavailable on this device", error)
      } else {
        throw error
      }
    } finally {
      file.delete()
    }
  }

  @Test
  fun honorsExifRotationBeforeRecognitionAndReportsOrientedSourceDimensions() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    Canvas(bitmap).drawText(
      "UP",
      20f,
      58f,
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 52f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      }
    )
    val file = File(context.cacheDir, "meme_text_detector_exif.jpg")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    bitmap.recycle()
    ExifInterface(file.absolutePath).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
      saveAttributes()
    }

    try {
      val result = MemeTextDetector.detect(context, file.toURI().toString())
      assertEquals(80, result.sourceWidth)
      assertEquals(160, result.sourceHeight)
      assertEquals(90, result.rotation)
      assertTrue(result.blocks.isNotEmpty())
    } finally {
      file.delete()
    }
  }
}
