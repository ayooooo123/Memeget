package com.memeget.nativeapp.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import expo.modules.memegetbg.MemeDrawPrimitives
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class DrawSettings(
    val shape: DrawShape = DrawShape.FREE,
    val color: String = "#FF4E42",
    val strokeScale: Float = 0.012f,
    val filled: Boolean = false
)

internal object DrawToolCore {

    val STROKE_PRESETS = listOf(
        Pair("Fine", 0.006f),
        Pair("Medium", 0.012f),
        Pair("Bold", 0.024f),
        Pair("Heavy", 0.048f)
    )

    val COLORS = listOf(
        "#FF4E42", // Red
        "#FFD600", // Yellow
        "#00E676", // Green
        "#2979FF", // Blue
        "#FFFFFF", // White
        "#000000", // Black
        "#D8FF4A"  // Volt
    )

    val SHAPE_ORDER = listOf(
        DrawShape.FREE,
        DrawShape.LINE,
        DrawShape.ARROW,
        DrawShape.RECTANGLE,
        DrawShape.ELLIPSE
    )

    fun appendFreehandPoint(
        existing: List<NormalizedPoint>,
        newPoint: NormalizedPoint,
        minDistance: Float = 0.003f
    ): List<NormalizedPoint> {
        if (existing.size >= ProjectLimits.MAX_POINTS_PER_DRAW_ELEMENT) return existing
        if (existing.isEmpty()) return listOf(newPoint)

        val last = existing.last()
        val dx = newPoint.x - last.x
        val dy = newPoint.y - last.y
        val distSq = dx * dx + dy * dy
        if (distSq < minDistance * minDistance) return existing

        return existing + newPoint
    }

    fun parseColor(hex: String, fallback: Int = Color.RED): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        fallback
    }

    fun drawLayer(
        canvas: Canvas,
        layer: MemeEditLayer.Draw,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ) {
        if (layer.elements.isEmpty()) return
        val shortEdgePx = min(canvasWidthPx, canvasHeightPx).toFloat()
        val layerOpacity = layer.opacity.coerceIn(0f, 1f)

        for (elem in layer.elements) {
            drawElement(canvas, elem, shortEdgePx, canvasWidthPx.toFloat(), canvasHeightPx.toFloat(), layerOpacity)
        }
    }

    fun drawElement(
        canvas: Canvas,
        elem: DrawElement,
        shortEdgePx: Float,
        widthPx: Float,
        heightPx: Float,
        layerOpacity: Float
    ) {
        val colorInt = parseColor(elem.color)
        val strokeWidthPx = max(1f, elem.strokeScale * shortEdgePx)
        val alpha = (layerOpacity * 255f).coerceIn(0f, 255f).roundToInt()

        val paint = MemeDrawPrimitives.newPaint().apply {
            color = colorInt
            this.alpha = alpha
        }
        val xs = FloatArray(elem.points.size) { elem.points[it].x * widthPx }
        val ys = FloatArray(elem.points.size) { elem.points[it].y * heightPx }
        MemeDrawPrimitives.drawShape(
            canvas = canvas,
            paint = paint,
            shape = elem.shape.wire,
            strokeWidthPx = strokeWidthPx,
            filled = elem.filled,
            xs = xs,
            ys = ys
        )
    }
}
