package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-resolution still renderer for a meme edit project.
 *
 * The JS side (src/memeImageRenderCore.ts) resolves every layer into output
 * pixels ahead of time, so this file only decodes, transforms and draws — it
 * never re-derives geometry. Text goes through the same [MemeTextLayout]
 * StaticLayout path the on-device preview uses, which is what keeps the
 * exported PNG and the editor canvas in agreement.
 *
 * The plan is data, not trusted input: every number is finiteness-checked and
 * every allocation is bounded before it is made, so a malformed or tampered
 * plan fails as the IOException the bridge advertises instead of as an OOM, an
 * IllegalArgumentException, or a silently blank export.
 */
internal object MemeImageRenderer {
  const val PLAN_VERSION = 1

  // Mirrors MAX_IMAGE_RENDER_PIXELS in src/memeImageRenderCore.ts. The plan
  // builder downscales past this itself, so a plan arriving above it is
  // malformed — reject it instead of letting createBitmap raise OutOfMemoryError.
  private const val MAX_OUTPUT_PIXELS = 16_000_000L

  // Absolute ceiling on a decoded source, whatever the destination asks for.
  private const val MAX_SOURCE_DECODE_PIXELS = 32_000_000L

  // inSampleSize is powers of two; 64 already reduces a 100 MP source to 24k
  // pixels, which is past the point of any destination caring.
  private const val MAX_DECODE_SAMPLE_SIZE = 64

  // Longest source edge any destination is allowed to ask for. A degenerate
  // crop (width 1e-6) would otherwise demand a 200-million-pixel edge.
  private const val MAX_SOURCE_EDGE = 1_000_000L

  // Mirrors MAX_MOSAIC_CELLS in src/memeImageRenderCore.ts.
  private const val MAX_MOSAIC_CELLS = 65_536L

  /**
   * Pixel budget for ONE decoded subject cutout.
   *
   * A cutout is already decoded no larger than the rect it is drawn into, so a
   * small sticker costs almost nothing. This is the ceiling for the other case —
   * a full-frame cutout on a 16 MP export — because the output bitmap is 64 MB
   * on its own, and an unbounded RGBA cutout beside it plus the alpha silhouette
   * the sticker effects extract is exactly the second full-resolution allocation
   * that OOM'd this app. A quarter of the output budget is 16 MB, and it costs
   * nothing visible: ML Kit's mask resolution is far below 4 MP, so the detail
   * was never there to lose.
   */
  private const val MAX_CUTOUT_DECODE_PIXELS = MAX_OUTPUT_PIXELS / 4

  /** Stamps around the silhouette that make up an outline. */
  private const val OUTLINE_STAMPS = 16

  /** Hard cap on a sticker effect, so a malformed plan cannot ask for a mile. */
  private const val MAX_STICKER_EFFECT_PX = 512f

  /** Below this radius a BlurMaskFilter does nothing but cost an allocation. */
  private const val MIN_BLUR_RADIUS_PX = 0.5f

  /** Sticker shadow colour when a plan names none: black at 55%. */
  private const val DEFAULT_SHADOW_COLOR = 0x8C000000.toInt()

  /** Strongest background blur: the source is drawn 1/32 size and stretched. */
  private const val MAX_BLUR_DOWNSCALE = 31f

  /** Never blur below this edge; past it the "blur" is visible blocks. */
  private const val MIN_BLUR_EDGE = 24

  // A wrap width is an output-pixel measurement; nothing legible needs more,
  // and this keeps a finite-but-absurd plan value from reaching roundToInt.
  private const val MAX_TEXT_WRAP_PX = 65_536.0

  // Renders are consumed and deleted by the caller. A crash in between used to
  // be self-healing (the next same-id render overwrote the file) and is not,
  // now that every render writes its own file — so sweep what got left behind.
  private const val STALE_RENDER_MS = 60L * 60L * 1000L

  fun render(context: Context, planJson: String): String {
    val plan = try {
      JSONObject(planJson)
    } catch (error: Throwable) {
      throw IOException("Render plan is not valid JSON", error)
    }
    val version = plan.optInt("version", -1)
    if (version != PLAN_VERSION) throw IOException("Unsupported render plan version $version")
    return try {
      renderPlan(context, plan)
    } catch (error: JSONException) {
      throw IOException("Render plan is malformed: ${error.message}", error)
    }
  }

  private fun renderPlan(context: Context, plan: JSONObject): String {
    val output = plan.getJSONObject("output")
    val widthPx = output.getInt("widthPx")
    val heightPx = output.getInt("heightPx")
    if (widthPx <= 0 || heightPx <= 0) {
      throw IOException("Render plan output must be at least one pixel")
    }
    if (widthPx.toLong() * heightPx.toLong() > MAX_OUTPUT_PIXELS) {
      throw IOException(
        "Render plan output ${widthPx}x$heightPx exceeds the $MAX_OUTPUT_PIXELS pixel cap"
      )
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
          "subject" -> drawSubject(context, canvas, layer)
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
      // The subject is composited on top of another picture; drawing the source
      // here instead would quietly ignore the background the user chose.
      "image" -> drawBackgroundImage(context, canvas, background)
      "blurred-source" -> drawBlurredSource(context, canvas, background, source)
      else -> {
        val under = parseColor(background.optString("color"), Color.TRANSPARENT)
        if (Color.alpha(under) != 0) canvas.drawColor(under)
        drawTransformedSource(context, canvas, source)
      }
    }
  }

  /**
   * Cover-fit a replacement background over the whole canvas.
   *
   * A background the user picked and we cannot read is a failure, not a reason
   * to fall back to the source: substituting the photo they were removing would
   * look like the cutout silently did nothing.
   */
  private fun drawBackgroundImage(context: Context, canvas: Canvas, background: JSONObject) {
    val assetUri = background.optString("assetUri")
    if (assetUri.isEmpty()) {
      throw IOException("Render plan asks for a replacement background but names no image")
    }
    val asset = try {
      decodeSource(context, assetUri, canvas.width.toDouble(), canvas.height.toDouble())
    } catch (error: IOException) {
      throw IOException("Could not read the replacement background: ${error.message}", error)
    }
    try {
      val scale = max(canvas.width / asset.width.toFloat(), canvas.height / asset.height.toFloat())
      val matrix = asset.orientationMatrix()
      matrix.postScale(scale, scale)
      matrix.postTranslate(
        (canvas.width - asset.width * scale) / 2f,
        (canvas.height - asset.height * scale) / 2f
      )
      canvas.drawBitmap(
        asset.bitmap,
        matrix,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
      )
    } finally {
      asset.recycle()
    }
  }

  /**
   * The source, blurred behind the subject.
   *
   * Downscale-then-upscale rather than a real Gaussian: a separable blur at
   * export resolution needs a second full-size buffer and a pass per axis, and
   * RenderEffect only applies to RenderNodes. Drawing the source into a
   * thumbnail and letting bilinear filtering stretch it back is one small
   * allocation, and because [drawTransformedSource] sizes its own decode from
   * the canvas it is handed, the SOURCE decode shrinks with it — a blurred
   * background costs less than a sharp one.
   */
  private fun drawBlurredSource(
    context: Context,
    canvas: Canvas,
    background: JSONObject,
    source: JSONObject
  ) {
    val blurScale = finiteFloat(background, "blurScale", 0.0).coerceIn(0f, 1f)
    if (blurScale <= 0f) {
      drawTransformedSource(context, canvas, source)
      return
    }
    val factor = 1f + blurScale * MAX_BLUR_DOWNSCALE
    val smallWidth = max(MIN_BLUR_EDGE, (canvas.width / factor).roundToInt())
      .coerceAtMost(canvas.width)
    val smallHeight = max(MIN_BLUR_EDGE, (canvas.height / factor).roundToInt())
      .coerceAtMost(canvas.height)
    val small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
    try {
      drawTransformedSource(context, Canvas(small), source)
      canvas.drawBitmap(
        small,
        null,
        RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
      )
    } finally {
      small.recycle()
    }
  }

  /**
   * Decode the source honoring EXIF, then map the project's rotation / flips /
   * crop onto the output rect with a single Matrix — no intermediate bitmap
   * copy per transform step, and none for the EXIF orientation either.
   */
  private fun drawTransformedSource(context: Context, canvas: Canvas, source: JSONObject) {
    val uri = source.optString("uri")
    if (uri.isEmpty()) throw IOException("Render plan source has no uri")
    val crop = source.getJSONObject("crop")
    val cropX = finiteFloat(crop, "x", 0.0)
    val cropY = finiteFloat(crop, "y", 0.0)
    val cropWidth = max(1e-6f, finiteFloat(crop, "width", 1.0))
    val cropHeight = max(1e-6f, finiteFloat(crop, "height", 1.0))

    // Only the crop window lands on the canvas, so the source resolution this
    // render can consume is the canvas blown back up by the crop fraction.
    val decoded = decodeSource(
      context,
      uri,
      canvas.width / cropWidth.toDouble(),
      canvas.height / cropHeight.toDouble()
    )
    try {
      val rotation = ((source.optInt("rotation") % 360) + 360) % 360
      val sourceWidth = decoded.width.toFloat()
      val sourceHeight = decoded.height.toFloat()
      val quarterTurn = rotation == 90 || rotation == 270
      val orientedWidth = if (quarterTurn) sourceHeight else sourceWidth
      val orientedHeight = if (quarterTurn) sourceWidth else sourceHeight

      // 0. EXIF orientation rides in the same matrix instead of being baked
      //    into a second full-size bitmap.
      val matrix = decoded.orientationMatrix()
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

      canvas.drawBitmap(
        decoded.bitmap,
        matrix,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
      )
    } finally {
      decoded.recycle()
    }
  }

  /**
   * A decoded bitmap plus the EXIF transform deliberately NOT baked into it.
   *
   * Materializing the oriented copy (MemeTextDetector.orientBitmapForExif)
   * costs a second full-size bitmap while the first is still alive — ~256 MB
   * for a 32 MP camera JPEG, on top of the output bitmap. Every caller here
   * already draws through a Matrix, so the orientation rides along in that
   * draw and the copy never happens.
   */
  private class DecodedSource(val bitmap: Bitmap, private val orientation: Int) {
    private val swapsAxes = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
      orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_270

    /** Size the photo is meant to be seen at. */
    val width: Int = if (swapsAxes) bitmap.height else bitmap.width
    val height: Int = if (swapsAxes) bitmap.width else bitmap.height

    /** Maps raw decoded pixels onto the oriented box, origin at (0,0). */
    fun orientationMatrix(): Matrix {
      val matrix = Matrix()
      when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
          matrix.setRotate(90f)
          matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
          matrix.setRotate(270f)
          matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return matrix
      }
      // Same re-origining Bitmap.createBitmap(src, .., matrix, ..) performs.
      val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
      matrix.mapRect(bounds)
      matrix.postTranslate(-bounds.left, -bounds.top)
      return matrix
    }

    fun recycle() = bitmap.recycle()
  }

  private fun decodeSource(
    context: Context,
    source: String,
    targetWidth: Double,
    targetHeight: Double
  ): DecodedSource {
    val uri = readableUri(context, source)
    val orientation = openStream(context, uri).use { input ->
      ExifInterface(input).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
      )
    }

    // decodeStream returns null for a bounds-only pass, so the stream check and
    // the decode result have to stay separate.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream(context, uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw IOException("Could not read image dimensions for $source")
    }
    val decoded = openStream(context, uri).use { input ->
      BitmapFactory.decodeStream(
        input,
        null,
        BitmapFactory.Options().apply {
          inSampleSize =
            decodeSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      )
    } ?: throw IOException("Could not decode $source")
    return DecodedSource(decoded, orientation)
  }

  /**
   * Decode no more source than the destination can consume.
   *
   * A 32 MP camera JPEG decoded whole is 128 MB whether the export is a 16 MP
   * poster or a 300 px sticker, so the ceiling has to come from the destination
   * rect, not from the heap alone. inSampleSize is powers of two only: take the
   * largest step that still covers the destination on both axes, which never
   * lands below the destination's own resolution and never keeps more than one
   * halving (2x linear) of oversample above it.
   *
   * The source reaches the destination through a rotation the caller applies
   * later, so which source axis feeds which destination axis is not knowable
   * here — but the sorted pair of edges is, and rotation cannot change it.
   */
  internal fun decodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Double,
    targetHeight: Double
  ): Int {
    val shortEdge = edgeBudget(min(targetWidth, targetHeight))
    val longEdge = edgeBudget(max(targetWidth, targetHeight))
    var sampleSize = 1
    while (
      sampleSize < MAX_DECODE_SAMPLE_SIZE &&
      covers(sourceWidth, sourceHeight, sampleSize * 2, shortEdge, longEdge)
    ) {
      sampleSize *= 2
    }
    while (
      sampleSize < MAX_DECODE_SAMPLE_SIZE &&
      sampledPixels(sourceWidth, sourceHeight, sampleSize) > MAX_SOURCE_DECODE_PIXELS
    ) {
      sampleSize *= 2
    }
    return sampleSize
  }

  private fun covers(
    width: Int,
    height: Int,
    sampleSize: Int,
    shortEdge: Long,
    longEdge: Long
  ): Boolean {
    val sampledWidth = width.toLong() / sampleSize
    val sampledHeight = height.toLong() / sampleSize
    return min(sampledWidth, sampledHeight) >= shortEdge &&
      max(sampledWidth, sampledHeight) >= longEdge
  }

  private fun edgeBudget(edge: Double): Long =
    if (edge.isFinite()) ceil(edge).toLong().coerceIn(1L, MAX_SOURCE_EDGE) else MAX_SOURCE_EDGE

  private fun sampledPixels(width: Int, height: Int, sampleSize: Int): Long =
    (width.toLong() / sampleSize) * (height.toLong() / sampleSize)

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

    val cell = mosaicCellPx(regionWidth, regionHeight, cellPx)
    // A one-pixel cell averages every pixel with itself: the mosaic would cost
    // one read and one drawRect per output pixel to reproduce its input.
    if (cell <= 1) return

    val columns = max(1, ceil(regionWidth.toDouble() / cell).toInt())
    val rows = max(1, ceil(regionHeight.toDouble() / cell).toInt())
    // One row of cells at a time: snapshotting a full-canvas cover would cost a
    // second 64 MB buffer beside the output bitmap. No cell row is taller than
    // the cell itself, and each band is read before anything paints over it.
    val band = IntArray(regionWidth * min(regionHeight, cell))
    val cellPaint = Paint()
    for (row in 0 until rows) {
      val cellTop = cellEdge(row, regionHeight, rows)
      val cellBottom = cellEdge(row + 1, regionHeight, rows).coerceIn(cellTop + 1, regionHeight)
      val bandHeight = cellBottom - cellTop
      target.getPixels(band, 0, regionWidth, left, top + cellTop, regionWidth, bandHeight)
      for (column in 0 until columns) {
        val cellLeft = cellEdge(column, regionWidth, columns)
        val cellRight = cellEdge(column + 1, regionWidth, columns).coerceIn(cellLeft + 1, regionWidth)
        cellPaint.color = averageColor(band, regionWidth, cellLeft, 0, cellRight, bandHeight)
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

  /**
   * Cell boundary in Long. `index * extent` overflows Int once the region is
   * wider than 46340 with a one-pixel cell, and the wrapped value lands on a
   * negative pixel index.
   */
  private fun cellEdge(index: Int, extent: Int, count: Int): Int =
    (index.toLong() * extent.toLong() / count.toLong()).toInt().coerceIn(0, extent)

  /**
   * The cell the mosaic will really use. Mirrors mosaicCellFloorPx() in
   * src/memeImageRenderCore.ts, so the cell the plan states and the cell that
   * gets drawn agree — and bounds the work of a hand-built plan independently,
   * because cell COUNT, not region area, is what a mosaic pays for.
   */
  internal fun mosaicCellPx(regionWidth: Int, regionHeight: Int, requestedCellPx: Int): Int {
    val area = regionWidth.toLong() * regionHeight.toLong()
    val floor = ceil(sqrt(area.toDouble() / MAX_MOSAIC_CELLS)).toInt()
    return max(max(1, floor), max(1, requestedCellPx))
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
      textSize = max(1f, finiteFloat(canvasSpec, "fontSizeDip", 1.0))
      letterSpacing = finiteFloat(font, "letterSpacingEm", 0.0)
      typeface = MemeTextLayout.weightedTypeface(
        context,
        font.optString("family", "NotoSans"),
        font.optString("weight", "400").toIntOrNull() ?: 400
      )
    }
    val staticLayout = MemeTextLayout.buildStaticLayout(
      displayText,
      paint,
      max(1, finiteDouble(canvasSpec, "wrapWidthDip", 1.0).coerceAtMost(MAX_TEXT_WRAP_PX).roundToInt()),
      max(1f, finiteFloat(layout, "lineHeightDip", 1.0)),
      spec.optString("align", "center")
    )

    val centerDip = canvasSpec.getJSONObject("centerDip")
    val opacity = (finiteDouble(fill, "opacity", 1.0) * finiteDouble(transform, "opacity", 1.0))
      .coerceIn(0.0, 1.0)
      .toFloat()
    val contentWidth = staticLayout.width.toFloat()
    val contentHeight = staticLayout.height.toFloat()
    val alpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
    if (alpha == 0) return

    val paddingX = finiteFloat(backing, "paddingXDip", 0.0)
    val paddingY = finiteFloat(backing, "paddingYDip", 0.0)
    val strokeWidth = max(0f, finiteFloat(outline, "widthDip", 0.0))

    val checkpoint = canvas.save()
    try {
      canvas.translate(finiteFloat(centerDip, "x", 0.0), finiteFloat(centerDip, "y", 0.0))
      canvas.rotate(finiteFloat(transform, "rotationDegrees", 0.0))
      val scale = max(0.01f, finiteFloat(transform, "scale", 1.0))
      canvas.scale(scale, scale)
      if (alpha < 255) {
        // Bound the translucency layer to the text's own content box. Over the
        // whole canvas it costs a second full-size buffer per translucent text
        // layer; the margin covers the stroke, the backing and glyph overhang.
        val margin = paint.textSize / 2f + strokeWidth
        val halfWidth = contentWidth / 2f + max(paddingX, margin)
        val halfHeight = contentHeight / 2f + max(paddingY, margin)
        canvas.saveLayerAlpha(-halfWidth, -halfHeight, halfWidth, halfHeight, alpha)
      }
      val backingColor = backing.opt("color")
      if (backingColor != null && backingColor != JSONObject.NULL) {
        val cornerRadius = finiteFloat(backing, "radiusDip", 0.0)
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
    val overlay = decodeSource(
      context,
      assetUri,
      rect.width().toDouble(),
      rect.height().toDouble()
    )
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
        alpha = (finiteDouble(layer, "opacity", 1.0).coerceIn(0.0, 1.0) * 255)
          .roundToInt()
          .coerceIn(0, 255)
      }
      // EXIF orientation, then the fit scale, then the destination rect — one
      // matrix, one draw, no intermediate bitmap.
      val matrix = overlay.orientationMatrix()
      matrix.postScale(scale, scale)
      matrix.postTranslate(
        rect.centerX() - drawnWidth / 2f,
        rect.centerY() - drawnHeight / 2f
      )
      val checkpoint = canvas.save()
      try {
        canvas.rotate(finiteFloat(layer, "rotationDegrees", 0.0), rect.centerX(), rect.centerY())
        if (fitCover) canvas.clipRect(rect)
        canvas.drawBitmap(overlay.bitmap, matrix, paint)
      } finally {
        canvas.restoreToCount(checkpoint)
      }
    } finally {
      overlay.recycle()
    }
  }

  /**
   * Draw a subject cutout: shadow, outline, then the subject itself.
   *
   * The alpha lives in the PNG the segmenter wrote, so there is no mask
   * arithmetic here — this scales that bitmap into the rect the plan resolved,
   * which is the same rect the preview resolved from the same function
   * (resolveCutoutPlacement in src/memeImageRenderCore.ts) against the preview
   * canvas. Effect sizes arrive in OUTPUT pixels for exactly the same reason.
   */
  private fun drawSubject(context: Context, canvas: Canvas, layer: JSONObject) {
    val rect = readRect(layer.getJSONObject("rect")) ?: return
    val cutoutUri = layer.optString("cutoutUri")
    if (cutoutUri.isEmpty()) return
    // Decode no bigger than the destination, and never past the cutout budget.
    val budgetScale = cutoutDecodeScale(rect)
    val cutout = decodeSource(
      context,
      cutoutUri,
      rect.width().toDouble() * budgetScale,
      rect.height().toDouble() * budgetScale
    )
    try {
      if (cutout.width <= 0 || cutout.height <= 0) return
      // Contain, never cover: a cutout's aspect ratio IS its shape, and cropping
      // it would clip the subject the user is looking at.
      val scale = min(rect.width() / cutout.width, rect.height() / cutout.height)
      val drawnWidth = cutout.width * scale
      val drawnHeight = cutout.height * scale
      val matrix = cutout.orientationMatrix()
      matrix.postScale(scale, scale)
      matrix.postTranslate(
        rect.centerX() - drawnWidth / 2f,
        rect.centerY() - drawnHeight / 2f
      )
      val opacity = (finiteDouble(layer, "opacity", 1.0).coerceIn(0.0, 1.0) * 255)
        .roundToInt()
        .coerceIn(0, 255)
      val checkpoint = canvas.save()
      try {
        canvas.rotate(finiteFloat(layer, "rotationDegrees", 0.0), rect.centerX(), rect.centerY())
        val shadowBlurPx = finiteFloat(layer, "shadowBlurPx", 0.0)
          .coerceIn(0f, MAX_STICKER_EFFECT_PX)
        val shadowOffsetPx = finiteFloat(layer, "shadowOffsetPx", 0.0)
          .coerceIn(0f, MAX_STICKER_EFFECT_PX)
        if (shadowBlurPx > 0f || shadowOffsetPx > 0f) {
          drawCutoutShadow(
            canvas = canvas,
            cutout = cutout.bitmap,
            matrix = matrix,
            sourceScale = scale,
            blurPx = shadowBlurPx,
            offsetPx = shadowOffsetPx,
            color = parseColor(layer.optString("shadowColor"), DEFAULT_SHADOW_COLOR),
            opacity = opacity
          )
        }
        val outlinePx = finiteFloat(layer, "outlinePx", 0.0).coerceIn(0f, MAX_STICKER_EFFECT_PX)
        val outlineColor = layer.optString("outlineColor")
        if (outlinePx > 0f && outlineColor.isNotBlank()) {
          drawCutoutOutline(
            canvas = canvas,
            cutout = cutout.bitmap,
            matrix = matrix,
            radiusPx = outlinePx,
            color = parseColor(outlineColor, Color.WHITE),
            opacity = opacity
          )
        }
        canvas.drawBitmap(
          cutout.bitmap,
          matrix,
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            alpha = opacity
          }
        )
      } finally {
        canvas.restoreToCount(checkpoint)
      }
    } finally {
      cutout.recycle()
    }
  }

  /**
   * How much of the destination rect a cutout decode may cover, so a full-frame
   * cutout cannot become a second full-resolution bitmap.
   */
  private fun cutoutDecodeScale(rect: RectF): Double {
    val wanted = rect.width().toDouble() * rect.height().toDouble()
    if (!wanted.isFinite() || wanted <= MAX_CUTOUT_DECODE_PIXELS) return 1.0
    return sqrt(MAX_CUTOUT_DECODE_PIXELS.toDouble() / wanted)
  }

  /**
   * The subject's silhouette, blurred and offset.
   *
   * `extractAlpha` gives a one-byte-per-pixel copy of just the alpha channel — a
   * quarter of the cutout, itself already bounded — and the blur is applied while
   * extracting, in SOURCE pixels, which is why the plan's output-pixel radius is
   * divided by the draw scale. The offset is applied after the matrix, so it
   * stays in the output pixels the plan stated.
   */
  private fun drawCutoutShadow(
    canvas: Canvas,
    cutout: Bitmap,
    matrix: Matrix,
    sourceScale: Float,
    blurPx: Float,
    offsetPx: Float,
    color: Int,
    opacity: Int
  ) {
    val sourceRadius = if (sourceScale > 0f) blurPx / sourceScale else blurPx
    val offsetXY = IntArray(2)
    val blurPaint = if (sourceRadius >= MIN_BLUR_RADIUS_PX) {
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(sourceRadius, BlurMaskFilter.Blur.NORMAL)
      }
    } else {
      null
    }
    val silhouette = try {
      cutout.extractAlpha(blurPaint, offsetXY)
    } catch (error: Throwable) {
      // A silhouette we cannot build is a missing shadow, not a failed export.
      return
    }
    try {
      val shadowMatrix = Matrix(matrix)
      // extractAlpha's bitmap starts at offsetXY in the cutout's own space.
      shadowMatrix.preTranslate(offsetXY[0].toFloat(), offsetXY[1].toFloat())
      shadowMatrix.postTranslate(offsetPx, offsetPx)
      canvas.drawBitmap(
        silhouette,
        shadowMatrix,
        // An ALPHA_8 bitmap takes its colour from the paint.
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          isFilterBitmap = true
          this.color = color
          alpha = Color.alpha(color) * opacity / 255
        }
      )
    } finally {
      silhouette.recycle()
    }
  }

  /**
   * A sticker outline: the silhouette stamped in a ring around itself.
   *
   * Dilation by stamping, not by blurring: a blurred alpha fades out, which reads
   * as a glow rather than the hard border a sticker has. Sixteen stamps of an
   * antialiased silhouette leave no visible scalloping at the radii these
   * fractions produce, and each stamp is a bounded ALPHA_8 draw.
   */
  private fun drawCutoutOutline(
    canvas: Canvas,
    cutout: Bitmap,
    matrix: Matrix,
    radiusPx: Float,
    color: Int,
    opacity: Int
  ) {
    val silhouette = try {
      cutout.extractAlpha()
    } catch (error: Throwable) {
      return
    }
    try {
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        this.color = color
        alpha = Color.alpha(color) * opacity / 255
      }
      for (index in 0 until OUTLINE_STAMPS) {
        val angle = index * 2.0 * Math.PI / OUTLINE_STAMPS
        val stamped = Matrix(matrix)
        stamped.postTranslate(
          (cos(angle) * radiusPx).toFloat(),
          (sin(angle) * radiusPx).toFloat()
        )
        canvas.drawBitmap(silhouette, stamped, paint)
      }
    } finally {
      silhouette.recycle()
    }
  }

  // --- io -------------------------------------------------------------------

  private fun writePng(context: Context, bitmap: Bitmap, planId: String): String {
    val dir = File(context.cacheDir, "meme_render")
    if (!dir.isDirectory && !dir.mkdirs()) {
      throw IOException("Could not create ${dir.absolutePath}")
    }
    sweepStaleRenders(dir)
    val safe = planId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "meme" }.padEnd(3, '_')
    // One file per invocation. A plan id is not unique in time: two concurrent
    // renders of the same project wrote the same path, interleaving their
    // bytes, and a failure in either deleted the other's finished PNG.
    val out = File.createTempFile("${safe}_", ".png", dir)
    try {
      FileOutputStream(out).use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
          throw IOException("PNG encode failed")
        }
      }
    } catch (error: Throwable) {
      // Only ever the file this invocation created.
      out.delete()
      throw error
    }
    return Uri.fromFile(out).toString()
  }

  private fun sweepStaleRenders(dir: File) {
    val cutoff = System.currentTimeMillis() - STALE_RENDER_MS
    dir.listFiles()?.forEach { file ->
      if (file.isFile && file.lastModified() < cutoff) file.delete()
    }
  }

  private fun readRect(rect: JSONObject): RectF? {
    val left = finiteFloat(rect, "x", 0.0)
    val top = finiteFloat(rect, "y", 0.0)
    val width = finiteFloat(rect, "width", 0.0)
    val height = finiteFloat(rect, "height", 0.0)
    // An empty rect is a layer with nothing to draw; a non-finite one is a
    // malformed plan, and finiteFloat has already rejected it.
    if (width <= 0f || height <= 0f) return null
    return RectF(left, top, left + width, top + height)
  }

  /**
   * Every number the plan carries comes through here. NaN and Infinity survive
   * arithmetic silently — a NaN crop yields a NaN matrix and a
   * background-only export that looks like a successful render — and then
   * surface late in roundToInt as IllegalArgumentException, which is not what
   * the bridge promises. A malformed plan is an IOException, immediately.
   */
  private fun finiteDouble(json: JSONObject, key: String, fallback: Double): Double {
    val value = json.optDouble(key, fallback)
    if (!value.isFinite()) throw IOException("Render plan field '$key' must be a finite number")
    return value
  }

  private fun finiteFloat(json: JSONObject, key: String, fallback: Double): Float {
    val value = finiteDouble(json, key, fallback).toFloat()
    if (!value.isFinite()) throw IOException("Render plan field '$key' is out of range")
    return value
  }

  internal fun parseColor(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    return try {
      Color.parseColor(value)
    } catch (error: IllegalArgumentException) {
      fallback
    }
  }

  /**
   * A plan uri is a read instruction. The plans this app builds only ever name
   * its own storage or a content:// grant the system already checked, so that
   * is all this renderer will open — a plan that ever arrives from somewhere
   * else cannot turn the app into a confused deputy for /proc, shared storage,
   * or another app's data.
   */
  private fun readableUri(context: Context, source: String): Uri {
    val uri = if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))
    return when (uri.scheme?.lowercase()) {
      "content" -> uri
      "file", null -> {
        val path = uri.path ?: throw IOException("Render plan uri has no path: $source")
        val file = File(path).canonicalFile
        if (!isAppLocal(context, file)) {
          throw IOException("Render plan may only read app-local files: $source")
        }
        Uri.fromFile(file)
      }
      else -> throw IOException("Unsupported render plan uri scheme: $source")
    }
  }

  private fun isAppLocal(context: Context, file: File): Boolean {
    val roots = ArrayList<File>()
    roots.add(File(context.applicationInfo.dataDir))
    roots.add(context.cacheDir)
    roots.add(context.filesDir)
    context.externalCacheDirs?.forEach { dir -> dir?.let(roots::add) }
    context.getExternalFilesDirs(null)?.forEach { dir -> dir?.let(roots::add) }
    val path = file.path
    return roots.any { root ->
      val rootPath = try {
        root.canonicalPath
      } catch (error: IOException) {
        root.path
      }
      path == rootPath || path.startsWith(rootPath + File.separator)
    }
  }

  private fun openStream(context: Context, uri: Uri): InputStream =
    if (uri.scheme.equals("file", ignoreCase = true)) {
      FileInputStream(File(uri.path ?: throw IOException("Could not open $uri")))
    } else {
      context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
    }
}
