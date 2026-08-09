package expo.modules.memegetbg

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one place a draw-layer element becomes Canvas commands.
 *
 * Both the full-resolution still renderer ([MemeImageRenderer.drawDraw], which is also the video
 * overlay path) and the on-device editor preview ([MemeDrawPreviewView]) resolve their points into
 * pixels their own way — the exporter from a plan in output pixels, the preview from normalized
 * points against the laid-out view — and then hand the SAME pixels to the SAME primitives here.
 * That shared last mile is what keeps an annotation identical between the editor and the export;
 * a second copy of the arrowhead trig or the dot radius would be a place for them to drift.
 *
 * Every method draws with a round cap and join and takes a [Paint] whose colour and alpha the
 * caller has already resolved, so it never needs to know about layer opacity.
 */
internal object MemeDrawPrimitives {
  // Draw-layer ceilings, mirroring PROJECT_LIMITS in src/memeEditProjectCore.ts (maxDrawElements
  // 512, maxPointsPerDrawElement 1024, maxDrawPointsPerLayer 8192). The TS validator rejects a
  // project past these, so anything that arrives over them is malformed; the excess is dropped
  // rather than turned into an unbounded Path or an unbounded number of draw calls.
  const val MAX_DRAW_ELEMENTS = 512
  const val MAX_DRAW_POINTS_PER_ELEMENT = 1_024
  const val MAX_DRAW_POINTS_PER_LAYER = 8_192

  // Arrowhead geometry: each leg is 3.5 stroke widths long, or a 12px floor for a hairline
  // stroke, and sits 28 degrees off the shaft.
  private const val ARROWHEAD_LEG_STROKE_FACTOR = 3.5f
  private const val ARROWHEAD_MIN_LEG_PX = 12f
  private const val ARROWHEAD_ANGLE_DEG = 28.0

  /** A stroke Paint with the round cap + join every drawn element shares. */
  fun newPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  /**
   * Draw one element from points already resolved to pixels.
   *
   * [paint]'s colour and alpha must already be set; this only chooses the style, the stroke width
   * and the primitive. [xs] and [ys] are parallel and the same length; a shape that needs two
   * points ([line], [arrow], [rectangle], [ellipse]) draws nothing with fewer.
   */
  fun drawShape(
    canvas: Canvas,
    paint: Paint,
    shape: String,
    strokeWidthPx: Float,
    filled: Boolean,
    xs: FloatArray,
    ys: FloatArray
  ) {
    val count = min(xs.size, ys.size)
    if (count <= 0) return
    when (shape) {
      "free" -> drawFree(canvas, paint, xs, ys, count, strokeWidthPx)
      "line" -> if (count >= 2) strokedLine(canvas, paint, xs[0], ys[0], xs[1], ys[1], strokeWidthPx)
      "arrow" -> if (count >= 2) drawArrow(canvas, paint, xs[0], ys[0], xs[1], ys[1], strokeWidthPx)
      "rectangle" -> if (count >= 2) {
        applyFill(paint, filled, strokeWidthPx)
        canvas.drawRect(rectOf(xs[0], ys[0], xs[1], ys[1]), paint)
      }
      "ellipse" -> if (count >= 2) {
        applyFill(paint, filled, strokeWidthPx)
        canvas.drawOval(rectOf(xs[0], ys[0], xs[1], ys[1]), paint)
      }
    }
  }

  /** A polyline through every point; a lone point is a filled dot of radius w/2. */
  private fun drawFree(
    canvas: Canvas,
    paint: Paint,
    xs: FloatArray,
    ys: FloatArray,
    count: Int,
    strokeWidthPx: Float
  ) {
    if (count == 1) {
      paint.style = Paint.Style.FILL
      canvas.drawCircle(xs[0], ys[0], max(0.5f, strokeWidthPx / 2f), paint)
      return
    }
    val path = Path()
    path.moveTo(xs[0], ys[0])
    for (i in 1 until count) path.lineTo(xs[i], ys[i])
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = strokeWidthPx
    canvas.drawPath(path, paint)
  }

  private fun strokedLine(
    canvas: Canvas,
    paint: Paint,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    strokeWidthPx: Float
  ) {
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = strokeWidthPx
    canvas.drawLine(x0, y0, x1, y1, paint)
  }

  /** A line plus a two-legged V head at the end point, all in one stroke pass. */
  private fun drawArrow(
    canvas: Canvas,
    paint: Paint,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    strokeWidthPx: Float
  ) {
    strokedLine(canvas, paint, x0, y0, x1, y1, strokeWidthPx)
    val dx = x1 - x0
    val dy = y1 - y0
    val length = sqrt(dx * dx + dy * dy)
    if (length <= 0f) return
    // Unit vector pointing back down the shaft; each leg is it rotated by the arrowhead angle to
    // either side.
    val backX = -dx / length
    val backY = -dy / length
    val leg = max(strokeWidthPx * ARROWHEAD_LEG_STROKE_FACTOR, ARROWHEAD_MIN_LEG_PX)
    val theta = Math.toRadians(ARROWHEAD_ANGLE_DEG)
    val cosT = cos(theta).toFloat()
    val sinT = sin(theta).toFloat()
    for (sign in intArrayOf(1, -1)) {
      val s = sign * sinT
      val legX = backX * cosT - backY * s
      val legY = backX * s + backY * cosT
      canvas.drawLine(x1, y1, x1 + legX * leg, y1 + legY * leg, paint)
    }
  }

  private fun applyFill(paint: Paint, filled: Boolean, strokeWidthPx: Float) {
    if (filled) {
      paint.style = Paint.Style.FILL
    } else {
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = strokeWidthPx
    }
  }

  /** A RectF from two opposite corners, ordered so it is never inside-out. */
  private fun rectOf(x0: Float, y0: Float, x1: Float, y1: Float): RectF =
    RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
}
