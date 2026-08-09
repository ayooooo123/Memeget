package expo.modules.memegetbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editor's live preview of a draw layer.
 *
 * It renders the SAME annotations the export does, through the SAME
 * [MemeDrawPrimitives], which is the whole point: a stroke the user places over
 * the preview canvas has to land in the exact place — and with the exact head,
 * dot and corner rounding — in the exported PNG or clip. The preview receives
 * its elements in NORMALIZED [0,1] coordinates with a `strokeScale` (a fraction
 * of the working canvas' short edge), rather than the output pixels the render
 * plan carries, because the view does not know its pixel size until it is laid
 * out; it resolves both against its own bounds at draw time exactly the way the
 * plan builder (src/memeImageRenderCore.ts) resolves them against the output.
 *
 * Layer opacity is applied per element, not with [View.setAlpha], so two
 * overlapping translucent strokes composite the same way they do when the
 * exporter draws them straight onto one canvas.
 *
 * A malformed `elementsJson` draws nothing: the preview is a view, not a bridge
 * call, so a bad prop is a blank overlay rather than a red box.
 */
internal class MemeDrawPreviewView(context: Context) : View(context) {
  private var elementsJsonValue = "[]"
  private var opacityValue = 1f

  private var elements: List<DrawPreviewElement> = emptyList()
  private var elementsDirty = true
  private var drawDirty = true

  private val paint = MemeDrawPrimitives.newPaint()

  fun setElementsJson(value: String) {
    if (elementsJsonValue == value) return
    elementsJsonValue = value
    elementsDirty = true
    drawDirty = true
  }

  fun setOpacity(value: Float) {
    // coerceIn preserves NaN, which would later crash roundToInt in onDraw; a
    // non-finite prop means "fully opaque" so the preview draws instead of dying.
    val bounded = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
    if (opacityValue == bounded) return
    opacityValue = bounded
    drawDirty = true
  }

  internal fun commitPendingProps() {
    if (!elementsDirty && !drawDirty) return
    if (elementsDirty) {
      elements = parseElements(elementsJsonValue)
      elementsDirty = false
    }
    invalidate()
    drawDirty = false
  }

  override fun onDraw(canvas: Canvas) {
    if (elementsDirty || drawDirty) commitPendingProps()
    val resolved = elements
    if (resolved.isEmpty()) return
    val widthF = width.toFloat()
    val heightF = height.toFloat()
    if (widthF <= 0f || heightF <= 0f) return
    val shortEdge = min(widthF, heightF)
    val layerAlpha = (opacityValue * 255f).roundToInt().coerceIn(0, 255)
    if (layerAlpha == 0) return

    for (element in resolved) {
      val alpha = Color.alpha(element.color) * layerAlpha / 255
      if (alpha == 0) continue
      paint.color = element.color
      paint.alpha = alpha
      val count = element.normalizedXs.size
      val xs = FloatArray(count)
      val ys = FloatArray(count)
      for (i in 0 until count) {
        xs[i] = element.normalizedXs[i] * widthF
        ys[i] = element.normalizedYs[i] * heightF
      }
      // Stroke width tracks the short edge, the same formula drawPlan uses so a
      // line's visual weight survives the jump from preview to export.
      val strokeWidthPx = max(1f, element.strokeScale * shortEdge)
      MemeDrawPrimitives.drawShape(canvas, paint, element.shape, strokeWidthPx, element.filled, xs, ys)
    }
  }

  /** Points held normalized; resolved to pixels against the live bounds in [onDraw]. */
  private class DrawPreviewElement(
    val shape: String,
    val color: Int,
    val strokeScale: Float,
    val filled: Boolean,
    val normalizedXs: FloatArray,
    val normalizedYs: FloatArray
  )

  /**
   * Parse the element array, bounded exactly like the exporter, and drop
   * anything malformed rather than throwing — a preview never crashes the
   * editor over a half-finished stroke.
   */
  private fun parseElements(json: String): List<DrawPreviewElement> {
    val array = try {
      JSONArray(json)
    } catch (error: JSONException) {
      return emptyList()
    }
    val count = min(array.length(), MemeDrawPrimitives.MAX_DRAW_ELEMENTS)
    val out = ArrayList<DrawPreviewElement>(count)
    var pointBudget = MemeDrawPrimitives.MAX_DRAW_POINTS_PER_LAYER
    for (index in 0 until count) {
      if (pointBudget <= 0) break
      val obj = array.optJSONObject(index) ?: continue
      val element = parseElement(obj, pointBudget) ?: continue
      pointBudget -= element.normalizedXs.size
      out.add(element)
    }
    return out
  }

  private fun parseElement(obj: JSONObject, pointBudget: Int): DrawPreviewElement? {
    val rawPoints = obj.optJSONArray("points") ?: return null
    val count = minOf(rawPoints.length(), MemeDrawPrimitives.MAX_DRAW_POINTS_PER_ELEMENT, pointBudget)
    if (count <= 0) return null
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    for (i in 0 until count) {
      val point = rawPoints.optJSONObject(i) ?: return null
      val x = point.optDouble("x", Double.NaN)
      val y = point.optDouble("y", Double.NaN)
      if (!x.isFinite() || !y.isFinite()) return null
      xs[i] = x.toFloat().coerceIn(0f, 1f)
      ys[i] = y.toFloat().coerceIn(0f, 1f)
    }
    val strokeScale = obj.optDouble("strokeScale", Double.NaN)
    if (!strokeScale.isFinite() || strokeScale <= 0.0) return null
    return DrawPreviewElement(
      shape = obj.optString("shape"),
      color = parseColor(obj.optString("color"), Color.BLACK),
      strokeScale = strokeScale.toFloat(),
      filled = obj.optBoolean("filled"),
      normalizedXs = xs,
      normalizedYs = ys
    )
  }

  private fun parseColor(value: String, fallback: Int): Int = try {
    Color.parseColor(value)
  } catch (_: IllegalArgumentException) {
    fallback
  }
}
