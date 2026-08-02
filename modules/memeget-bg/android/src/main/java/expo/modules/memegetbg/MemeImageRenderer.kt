package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.text.TextPaint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-resolution still renderer for a meme edit project.
 *
 * The JS side (src/memeImageRenderCore.ts) resolves every layer into output
 * pixels ahead of time, so this file only decodes, transforms and draws — it
 * never re-derives geometry. Text goes through the same [MemeTextLayout]
 * StaticLayout path the on-device preview uses, which is what keeps the
 * exported PNG and the editor canvas in agreement.
 */
internal object MemeImageRenderer {
  // Ceiling for the decoded source bitmap. The output bitmap is already capped
  // by the plan's own pixel guard; this bounds the decode side independently so
  // a 100 MP source cannot blow the heap before the first draw.
  private const val MAX_SOURCE_DECODE_PIXELS = 32_000_000L
  const val PLAN_VERSION = 1

  fun render(context: Context, planJson: String): String {
    val plan = try {
      JSONObject(planJson)
    } catch (error: Throwable) {
      throw IOException("Render plan is not valid JSON", error)
    }
    val version = plan.optInt("version", -1)
    if (version != PLAN_VERSION) throw IOException("Unsupported render plan version $version")
    val output = plan.getJSONObject("output")
    val widthPx = output.getInt("widthPx")
    val heightPx = output.getInt("heightPx")
    if (widthPx <= 0 || heightPx <= 0) {
      throw IOException("Render plan output must be at least one pixel")
    }

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      drawBackground(context, canvas, plan.getJSONObject("background"), plan.getJSONObject("source"))
      val layers = plan.optJSONArray("layers") ?: JSONArray()
      for (index in 0 until layers.length()) {
        val layer = layers.optJSONObject(index) ?: continue
        when (layer.optString("kind")) {
          // Honestly skipped — the plan already told the caller why.
          "unavailable" -> Unit
          "cover" -> drawCover(canvas, bitmap, layer)
          "text" -> drawText(context, canvas, layer)
          "media" -> drawMedia(context, canvas, layer)
        }
      }
      return writePng(context, bitmap, plan.optString("id", "meme"))
    } finally {
      bitmap.recycle()
    }
  }

  // --- background + base transform -----------------------------------------

  private fun drawBackground(
    context: Context,
    canvas: Canvas,
    background: JSONObject,
    source: JSONObject
  ) {
    when (background.optString("mode")) {
      // A transparent canvas is the whole point of exporting PNG: leave the
      // pixels untouched so the alpha channel survives the encode.
      "transparent" -> Unit
      "solid" -> canvas.drawColor(parseColor(background.optString("color"), Color.BLACK))
      else -> {
        val under = parseColor(background.optString("color"), Color.TRANSPARENT)
        if (Color.alpha(under) != 0) canvas.drawColor(under)
        drawTransformedSource(context, canvas, source)
      }
    }
  }

  /**
   * Decode the source honoring EXIF, then map the project's rotation / flips /
   * crop onto the output rect with a single Matrix — no intermediate bitmap
   * copy per transform step.
   */
  private fun drawTransformedSource(context: Context, canvas: Canvas, source: JSONObject) {
    val uri = source.optString("uri")
    if (uri.isEmpty()) throw IOException("Render plan source has no uri")
    val decoded = decodeExifOrientedBitmap(context, uri)
    try {
      val rotation = ((source.optInt("rotation") % 360) + 360) % 360
      val crop = source.getJSONObject("crop")
      val sourceWidth = decoded.width.toFloat()
      val sourceHeight = decoded.height.toFloat()
      val quarterTurn = rotation == 90 || rotation == 270
      val orientedWidth = if (quarterTurn) sourceHeight else sourceWidth
      val orientedHeight = if (quarterTurn) sourceWidth else sourceHeight
      val cropX = crop.optDouble("x", 0.0).toFloat()
      val cropY = crop.optDouble("y", 0.0).toFloat()
      val cropWidth = max(1e-6f, crop.optDouble("width", 1.0).toFloat())
      val cropHeight = max(1e-6f, crop.optDouble("height", 1.0).toFloat())

      val matrix = Matrix()
      // 1. rotate about the source center, then re-origin the oriented box.
      matrix.postTranslate(-sourceWidth / 2f, -sourceHeight / 2f)
      matrix.postRotate(rotation.toFloat())
      matrix.postTranslate(orientedWidth / 2f, orientedHeight / 2f)
      // 2. flips act on the oriented box the editor shows.
      val flipX = source.optBoolean("flipX")
      val flipY = source.optBoolean("flipY")
      if (flipX || flipY) {
        matrix.postScale(
          if (flipX) -1f else 1f,
          if (flipY) -1f else 1f,
          orientedWidth / 2f,
          orientedHeight / 2f
        )
      }
      // 3. crop window -> output rect.
      matrix.postTranslate(-cropX * orientedWidth, -cropY * orientedHeight)
      matrix.postScale(
        canvas.width / (cropWidth * orientedWidth),
        canvas.height / (cropHeight * orientedHeight)
      )

      canvas.drawBitmap(decoded, matrix, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
    } finally {
      decoded.recycle()
    }
  }

  private fun decodeExifOrientedBitmap(context: Context, source: String): Bitmap {
    val uri = sourceUri(source)
    val exifInput = openInputStream(context, uri) ?: throw IOException("Could not open $source")
    val orientation = exifInput.use { input ->
      ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }

    // decodeStream returns null for a bounds-only pass, so the stream check and
    // the decode result have to stay separate.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsInput = openInputStream(context, uri) ?: throw IOException("Could not open $source")
    boundsInput.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw IOException("Could not read image dimensions for $source")
    }
    var sampleSize = 1
    while (
      (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) >
      MAX_SOURCE_DECODE_PIXELS
    ) {
      sampleSize *= 2
    }
    val pixelInput = openInputStream(context, uri) ?: throw IOException("Could not open $source")
    val decoded = pixelInput.use { input ->
      BitmapFactory.decodeStream(
        input,
        null,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      )
    } ?: throw IOException("Could not decode $source")
    val oriented = try {
      MemeTextDetector.orientBitmapForExif(decoded, orientation)
    } catch (error: Throwable) {
      decoded.recycle()
      throw IOException("Could not orient $source", error)
    }
    if (oriented !== decoded) decoded.recycle()
    return oriented
  }

  // --- layers ---------------------------------------------------------------

  private fun drawCover(canvas: Canvas, target: Bitmap, layer: JSONObject) {
    val rect = readRect(layer.getJSONObject("rect")) ?: return
    if (layer.optString("mode") == "pixelate") {
      drawPixelateMosaic(canvas, target, rect, max(1, layer.optInt("pixelSizePx", 1)))
      return
    }
    canvas.drawRect(
      rect,
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseColor(layer.optString("color"), Color.BLACK)
        style = Paint.Style.FILL
      }
    )
  }

  /**
   * A real mosaic: read the already-composited pixels back out of the output
   * bitmap and repaint them as averaged cells, so the covered area still shows
   * the image underneath instead of a flat swatch.
   */
  internal fun drawPixelateMosaic(canvas: Canvas, target: Bitmap, rect: RectF, cellPx: Int) {
    val left = rect.left.toInt().coerceIn(0, target.width)
    val top = rect.top.toInt().coerceIn(0, target.height)
    val right = ceil(rect.right.toDouble()).toInt().coerceIn(left, target.width)
    val bottom = ceil(rect.bottom.toDouble()).toInt().coerceIn(top, target.height)
    val regionWidth = right - left
    val regionHeight = bottom - top
    if (regionWidth <= 0 || regionHeight <= 0) return

    val columns = max(1, ceil(regionWidth.toDouble() / cellPx).toInt())
    val rows = max(1, ceil(regionHeight.toDouble() / cellPx).toInt())
    val pixels = IntArray(regionWidth * regionHeight)
    target.getPixels(pixels, 0, regionWidth, left, top, regionWidth, regionHeight)
    val cellPaint = Paint()
    for (row in 0 until rows) {
      val cellTop = row * regionHeight / rows
      val cellBottom = ((row + 1) * regionHeight / rows).coerceIn(cellTop + 1, regionHeight)
      for (column in 0 until columns) {
        val cellLeft = column * regionWidth / columns
        val cellRight = ((column + 1) * regionWidth / columns).coerceIn(cellLeft + 1, regionWidth)
        cellPaint.color = averageColor(pixels, regionWidth, cellLeft, cellTop, cellRight, cellBottom)
        canvas.drawRect(
          (left + cellLeft).toFloat(),
          (top + cellTop).toFloat(),
          (left + cellRight).toFloat(),
          (top + cellBottom).toFloat(),
          cellPaint
        )
      }
    }
  }

  private fun averageColor(
    pixels: IntArray,
    stride: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
  ): Int {
    var alpha = 0L
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in top until bottom) {
      val row = y * stride
      for (x in left until right) {
        val pixel = pixels[row + x]
        alpha += Color.alpha(pixel).toLong()
        red += Color.red(pixel).toLong()
        green += Color.green(pixel).toLong()
        blue += Color.blue(pixel).toLong()
        count++
      }
    }
    if (count == 0L) return Color.TRANSPARENT
    return Color.argb(
      (alpha / count).toInt(),
      (red / count).toInt(),
      (green / count).toInt(),
      (blue / count).toInt()
    )
  }

  private fun drawText(context: Context, canvas: Canvas, layer: JSONObject) {
    val spec = layer.optJSONObject("spec") ?: return
    val displayText = spec.optString("displayText")
    if (displayText.isEmpty()) return
    val canvasSpec = spec.getJSONObject("canvas")
    val font = spec.getJSONObject("font")
    val transform = spec.getJSONObject("transform")
    val fill = spec.getJSONObject("fill")
    val outline = spec.getJSONObject("outline")
    val backing = spec.getJSONObject("backing")
    val layout = spec.getJSONObject("layout")

    // The plan already speaks in output pixels, so dip == px for this render.
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
      textSize = max(1f, canvasSpec.optDouble("fontSizeDip", 1.0).toFloat())
      letterSpacing = font.optDouble("letterSpacingEm", 0.0).toFloat()
      typeface = MemeTextLayout.weightedTypeface(
        context,
        font.optString("family", "NotoSans"),
        font.optString("weight", "400").toIntOrNull() ?: 400
      )
    }
    val staticLayout = MemeTextLayout.buildStaticLayout(
      displayText,
      paint,
      max(1, canvasSpec.optDouble("wrapWidthDip", 1.0).roundToInt()),
      max(1f, layout.optDouble("lineHeightDip", 1.0).toFloat()),
      spec.optString("align", "center")
    )

    val centerDip = canvasSpec.getJSONObject("centerDip")
    val opacity = (fill.optDouble("opacity", 1.0) * transform.optDouble("opacity", 1.0))
      .coerceIn(0.0, 1.0)
      .toFloat()
    val contentWidth = staticLayout.width.toFloat()
    val contentHeight = staticLayout.height.toFloat()
    val alpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
    if (alpha == 0) return

    val checkpoint = if (alpha < 255) {
      canvas.saveLayerAlpha(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), alpha)
    } else {
      canvas.save()
    }
    try {
      canvas.translate(centerDip.optDouble("x", 0.0).toFloat(), centerDip.optDouble("y", 0.0).toFloat())
      canvas.rotate(transform.optDouble("rotationDegrees", 0.0).toFloat())
      val scale = max(0.01f, transform.optDouble("scale", 1.0).toFloat())
      canvas.scale(scale, scale)
      val backingColor = backing.opt("color")
      if (backingColor != null && backingColor != JSONObject.NULL) {
        val paddingX = backing.optDouble("paddingXDip", 0.0).toFloat()
        val paddingY = backing.optDouble("paddingYDip", 0.0).toFloat()
        val cornerRadius = backing.optDouble("radiusDip", 0.0).toFloat()
        canvas.drawRoundRect(
          RectF(
            -contentWidth / 2f - paddingX,
            -contentHeight / 2f - paddingY,
            contentWidth / 2f + paddingX,
            contentHeight / 2f + paddingY
          ),
          cornerRadius,
          cornerRadius,
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(backingColor.toString(), Color.TRANSPARENT)
          }
        )
      }
      canvas.translate(-contentWidth / 2f, -contentHeight / 2f)
      val strokeWidth = max(0f, outline.optDouble("widthDip", 0.0).toFloat())
      if (strokeWidth > 0f) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = parseColor(outline.optString("color"), Color.BLACK)
        staticLayout.draw(canvas)
      }
      paint.style = Paint.Style.FILL
      paint.strokeWidth = 0f
      paint.color = parseColor(fill.optString("color"), Color.WHITE)
      staticLayout.draw(canvas)
    } finally {
      canvas.restoreToCount(checkpoint)
    }
  }

  private fun drawMedia(context: Context, canvas: Canvas, layer: JSONObject) {
    val rect = readRect(layer.getJSONObject("rect")) ?: return
    val assetUri = layer.optString("assetUri")
    if (assetUri.isEmpty()) return
    val overlay = decodeExifOrientedBitmap(context, assetUri)
    try {
      val fitCover = layer.optString("fit") == "cover"
      val scale = if (fitCover) {
        max(rect.width() / overlay.width, rect.height() / overlay.height)
      } else {
        min(rect.width() / overlay.width, rect.height() / overlay.height)
      }
      val drawnWidth = overlay.width * scale
      val drawnHeight = overlay.height * scale
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        alpha = (layer.optDouble("opacity", 1.0).coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(0, 255)
      }
      val checkpoint = canvas.save()
      try {
        canvas.rotate(layer.optDouble("rotationDegrees", 0.0).toFloat(), rect.centerX(), rect.centerY())
        if (fitCover) canvas.clipRect(rect)
        canvas.drawBitmap(
          overlay,
          Rect(0, 0, overlay.width, overlay.height),
          RectF(
            rect.centerX() - drawnWidth / 2f,
            rect.centerY() - drawnHeight / 2f,
            rect.centerX() + drawnWidth / 2f,
            rect.centerY() + drawnHeight / 2f
          ),
          paint
        )
      } finally {
        canvas.restoreToCount(checkpoint)
      }
    } finally {
      overlay.recycle()
    }
  }

  // --- io -------------------------------------------------------------------

  private fun writePng(context: Context, bitmap: Bitmap, planId: String): String {
    val dir = File(context.cacheDir, "meme_render")
    dir.mkdirs()
    val safe = planId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "meme" }
    val out = File(dir, "$safe.png")
    try {
      FileOutputStream(out).use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
          throw IOException("PNG encode failed")
        }
      }
    } catch (error: Throwable) {
      out.delete()
      throw error
    }
    return Uri.fromFile(out).toString()
  }

  private fun readRect(rect: JSONObject): RectF? {
    val left = rect.optDouble("x", 0.0).toFloat()
    val top = rect.optDouble("y", 0.0).toFloat()
    val width = rect.optDouble("width", 0.0).toFloat()
    val height = rect.optDouble("height", 0.0).toFloat()
    if (!left.isFinite() || !top.isFinite() || width <= 0f || height <= 0f) return null
    return RectF(left, top, left + width, top + height)
  }

  internal fun parseColor(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    return try {
      Color.parseColor(value)
    } catch (error: IllegalArgumentException) {
      fallback
    }
  }

  private fun sourceUri(source: String): Uri =
    if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))

  private fun openInputStream(context: Context, uri: Uri): InputStream? =
    if (uri.scheme.equals("file", ignoreCase = true)) {
      uri.path?.let { path -> FileInputStream(File(path)) }
    } else {
      context.contentResolver.openInputStream(uri)
    }
}
