package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class NormalizedImageRect(
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double
) {
  fun toMap(): Map<String, Double> = mapOf(
    "x" to x,
    "y" to y,
    "width" to width,
    "height" to height
  )
}

// An upright, size-capped copy of an image written for the JS OCR engine.
// `width`/`height` are the copy's pixels (the frame OCR boxes are measured in);
// `sourceWidth`/`sourceHeight` are the EXIF-upright original's.
internal data class PreparedTextImage(
  val uri: String,
  val width: Int,
  val height: Int,
  val sourceWidth: Int,
  val sourceHeight: Int,
  val rotation: Int
) {
  fun toMap(): Map<String, Any> = mapOf(
    "uri" to uri,
    "width" to width,
    "height" to height,
    "sourceWidth" to sourceWidth,
    "sourceHeight" to sourceHeight,
    "rotation" to rotation
  )
}

internal data class BorderColorSample(val hex: String, val sampleCount: Int) {
  fun toMap(): Map<String, Any> = mapOf("hex" to hex, "sampleCount" to sampleCount)
}

internal data class ImagePixelGrid(
  val rows: Int,
  val columns: Int,
  val colors: List<String>
) {
  fun toMap(): Map<String, Any> = mapOf(
    "rows" to rows,
    "columns" to columns,
    "colors" to colors
  )
}

internal object MemeTextDetector {
  const val MAX_BORDER_SAMPLES = 4096
  private const val MAX_OCR_DECODE_DIMENSION = 2048
  private const val OCR_JPEG_QUALITY = 95
  private const val MAX_SAMPLER_DECODE_DIMENSION = 1024
  private const val MIN_BORDER_RING_PIXELS = 2
  private const val MAX_BORDER_RING_PIXELS = 24


  private data class OrientedBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotation: Int
  ) : AutoCloseable {
    override fun close() {
      if (!bitmap.isRecycled) bitmap.recycle()
    }
  }

  // Decode `source` upright (EXIF applied) and at most MAX_OCR_DECODE_DIMENSION
  // on its long edge, and write it as a JPEG the on-device OCR model can read
  // by file:// path — it can't open content:// uris, and it doesn't apply EXIF.
  // Recognition itself runs in JS (react-native-executorch); this keeps the
  // decode, orientation and coordinate frame the editor has always used, so a
  // box normalized against `width`/`height` lands on the same pixels.
  fun prepareForTextDetection(context: Context, source: String): PreparedTextImage {
    decodeOrientedBitmap(context, source, MAX_OCR_DECODE_DIMENSION).use { decoded ->
      val file = File(context.cacheDir, "meme_work_ocr_${System.nanoTime()}.jpg")
      try {
        FileOutputStream(file).use { out ->
          if (!decoded.bitmap.compress(Bitmap.CompressFormat.JPEG, OCR_JPEG_QUALITY, out)) {
            throw IOException("Could not encode the image for text detection")
          }
        }
      } catch (error: Throwable) {
        file.delete()
        throw error
      }
      return PreparedTextImage(
        uri = Uri.fromFile(file).toString(),
        width = decoded.bitmap.width,
        height = decoded.bitmap.height,
        sourceWidth = decoded.sourceWidth,
        sourceHeight = decoded.sourceHeight,
        rotation = decoded.rotation
      )
    }
  }

  fun sampleBorderColor(
    context: Context,
    source: String,
    rect: NormalizedImageRect
  ): BorderColorSample {
    decodeOrientedBitmap(context, source, MAX_SAMPLER_DECODE_DIMENSION).use { decoded ->
      return sampleOrientedBitmapBorder(decoded.bitmap, rect)
    }
  }

  fun samplePixelGrid(
    context: Context,
    source: String,
    rect: NormalizedImageRect,
    pixelSize: Int
  ): ImagePixelGrid {
    decodeOrientedBitmap(context, source, MAX_SAMPLER_DECODE_DIMENSION).use { decoded ->
      return sampleOrientedBitmapPixelGrid(decoded.bitmap, rect, pixelSize)
    }
  }

  fun sampleOrientedBitmapPixelGrid(
    bitmap: Bitmap,
    rect: NormalizedImageRect,
    pixelSize: Int
  ): ImagePixelGrid {
    val left = floor(rect.x.coerceIn(0.0, 1.0) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
    val top = floor(rect.y.coerceIn(0.0, 1.0) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
    val right = ceil((rect.x + rect.width).coerceIn(0.0, 1.0) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = ceil((rect.y + rect.height).coerceIn(0.0, 1.0) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
    val boundedPixelSize = pixelSize.coerceIn(1, 256)
    val columns = ceil((right - left).toDouble() / boundedPixelSize).toInt().coerceIn(1, 16)
    val rows = ceil((bottom - top).toDouble() / boundedPixelSize).toInt().coerceIn(1, 16)
    val colors = ArrayList<String>(rows * columns)
    for (row in 0 until rows) {
      val y = (top + ((row + 0.5) * (bottom - top) / rows).toInt()).coerceIn(top, bottom - 1)
      for (column in 0 until columns) {
        val x = (left + ((column + 0.5) * (right - left) / columns).toInt()).coerceIn(left, right - 1)
        colors.add(String.format(Locale.US, "#%08X", bitmap.getPixel(x, y)))
      }
    }
    return ImagePixelGrid(rows, columns, colors)
  }
  fun normalizePixelRect(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    width: Int,
    height: Int
  ): NormalizedImageRect? {
    if (width <= 0 || height <= 0) return null
    val clippedLeft = max(0, min(width, left))
    val clippedTop = max(0, min(height, top))
    val clippedRight = max(0, min(width, right))
    val clippedBottom = max(0, min(height, bottom))
    if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
    return NormalizedImageRect(
      x = clippedLeft.toDouble() / width,
      y = clippedTop.toDouble() / height,
      width = (clippedRight - clippedLeft).toDouble() / width,
      height = (clippedBottom - clippedTop).toDouble() / height
    )
  }

  fun sampleOrientedBitmapBorder(bitmap: Bitmap, rect: NormalizedImageRect): BorderColorSample {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
      throw IllegalArgumentException("Decoded image has no pixels")
    }
    val left = floor(rect.x.coerceIn(0.0, 1.0) * bitmap.width).toInt()
      .coerceIn(0, bitmap.width)
    val top = floor(rect.y.coerceIn(0.0, 1.0) * bitmap.height).toInt()
      .coerceIn(0, bitmap.height)
    val right = ceil((rect.x + rect.width).coerceIn(0.0, 1.0) * bitmap.width).toInt()
      .coerceIn(0, bitmap.width)
    val bottom = ceil((rect.y + rect.height).coerceIn(0.0, 1.0) * bitmap.height).toInt()
      .coerceIn(0, bitmap.height)
    if (right <= left || bottom <= top) {
      throw IllegalArgumentException("Sample rectangle must overlap the oriented image")
    }
    val ring = (min(bitmap.width, bitmap.height) * 0.06).toInt()
      .coerceIn(MIN_BORDER_RING_PIXELS, MAX_BORDER_RING_PIXELS)
    val outerLeft = max(0, left - ring)
    val outerTop = max(0, top - ring)
    val outerRight = min(bitmap.width, right + ring)
    val outerBottom = min(bitmap.height, bottom + ring)
    val estimatedRingArea =
      (outerRight - outerLeft) * (outerBottom - outerTop) - (right - left) * (bottom - top)
    val stride = max(1, ceil(sqrt(estimatedRingArea.coerceAtLeast(1).toDouble() / MAX_BORDER_SAMPLES)).toInt())
    val reds = IntArray(MAX_BORDER_SAMPLES)
    val greens = IntArray(MAX_BORDER_SAMPLES)
    val blues = IntArray(MAX_BORDER_SAMPLES)
    var count = 0
    var y = outerTop
    while (y < outerBottom && count < MAX_BORDER_SAMPLES) {
      var x = outerLeft
      while (x < outerRight && count < MAX_BORDER_SAMPLES) {
        val inside = x in left until right && y in top until bottom
        if (!inside) {
          val pixel = bitmap.getPixel(x, y)
          if (Color.alpha(pixel) > 0) {
            reds[count] = Color.red(pixel)
            greens[count] = Color.green(pixel)
            blues[count] = Color.blue(pixel)
            count += 1
          }
        }
        x += stride
      }
      y += stride
    }
    if (count == 0) throw IOException("No opaque pixels were available around the selected region")
    val red = median(reds, count)
    val green = median(greens, count)
    val blue = median(blues, count)
    return BorderColorSample(
      hex = String.format(Locale.US, "#%02X%02X%02X", red, green, blue),
      sampleCount = count
    )
  }

  private fun median(values: IntArray, count: Int): Int {
    val sorted = values.copyOf(count)
    sorted.sort()
    return if (count % 2 == 1) sorted[count / 2]
    else (sorted[count / 2 - 1] + sorted[count / 2]) / 2
  }

  internal fun orientBitmapForExif(source: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
      orientation == ExifInterface.ORIENTATION_UNDEFINED) {
      return source
    }
    val values = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
        floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 ->
        floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL ->
        floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSPOSE ->
        floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_90 ->
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSVERSE ->
        floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_270 ->
        floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
      else -> return source
    }
    return Bitmap.createBitmap(
      source,
      0,
      0,
      source.width,
      source.height,
      Matrix().apply { setValues(values) },
      false
    )
  }

  private fun displayRotationForExif(orientation: Int): Int {
    return when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_180 -> 180
      ExifInterface.ORIENTATION_TRANSPOSE,
      ExifInterface.ORIENTATION_ROTATE_90,
      ExifInterface.ORIENTATION_TRANSVERSE -> 90
      ExifInterface.ORIENTATION_ROTATE_270 -> 270
      else -> 0
    }
  }

  private fun sourceUri(source: String): Uri {
    return if (source.contains("://") || source.startsWith("file:")) Uri.parse(source)
    else Uri.fromFile(File(source))
  }

  private fun openInputStream(context: Context, uri: Uri): InputStream? {
    return if (uri.scheme.equals("file", ignoreCase = true)) {
      uri.path?.let { path -> FileInputStream(File(path)) }
    } else {
      context.contentResolver.openInputStream(uri)
    }
  }

  private fun decodeOrientedBitmap(context: Context, source: String, maximumDimension: Int): OrientedBitmap {
    val uri = sourceUri(source)
    val orientation = try {
      openInputStream(context, uri)?.use { input ->
        ExifInterface(input).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL
        )
      } ?: throw IOException("Could not open local image $source")
    } catch (error: Throwable) {
      if (error is IOException) throw error
      throw IOException("Could not read image orientation for $source", error)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsInput = openInputStream(context, uri)
      ?: throw IOException("Could not open local image $source")
    boundsInput.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw IOException("Could not read image dimensions for $source")
    }
    var sampleSize = 1
    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maximumDimension) {
      sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = openInputStream(context, uri)?.use { input ->
      BitmapFactory.decodeStream(input, null, options)
    } ?: throw IOException("Could not decode local image $source")
    val oriented = try {
      orientBitmapForExif(decoded, orientation)
    } catch (error: Throwable) {
      decoded.recycle()
      throw IOException("Could not orient local image $source", error)
    }
    if (oriented !== decoded) decoded.recycle()
    val rotation = displayRotationForExif(orientation)
    val swapsAxes = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
    return OrientedBitmap(
      bitmap = oriented,
      sourceWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth,
      sourceHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight,
      rotation = rotation
    )
  }
}
