package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import expo.modules.memegetbg.MemeTextDensity
import expo.modules.memegetbg.MemeTextLayout
import expo.modules.memegetbg.MemeTextLayoutResultPx
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class TextPresetSpec(
    val preset: String,
    val defaultWidth: Float,
    val defaultFontSize: Float,
    val style: TextStyle,
    val fontFamily: String,
    val fontWeight: Int,
    val lineHeightRatio: Float,
    val letterSpacingEm: Float,
    val backingRadiusScale: Float,
    val paddingXScale: Float,
    val paddingYScale: Float,
    val tail: String // "none", "bottom-left"
)

internal object TextLayoutBridge {

    val COLOR_SWATCHES = listOf(
        "#f2f4f8", // White/Text
        "#0a0b0e", // Black/Bg
        "#d8ff4a", // Volt green
        "#11130a", // Dark on-volt
        "#c0c6d4", // Text Dim
        "#ff7a76", // Danger red
        "#5ee0a0", // Good green
        "#8ab4ff", // Accent blue
        "#ffcc00", // Yellow
        "#ff8800"  // Orange
    )

    val PRESETS: Map<String, TextPresetSpec> = mapOf(
        "impact" to TextPresetSpec(
            preset = "impact",
            defaultWidth = 0.88f,
            defaultFontSize = 0.118f,
            style = TextStyle(
                preset = "impact",
                color = "#f2f4f8",
                outlineColor = "#0a0b0e",
                outlineScale = 0.22f,
                backgroundColor = null,
                opacity = 1f,
                align = "center",
                uppercase = true
            ),
            fontFamily = "Anton",
            fontWeight = 900,
            lineHeightRatio = 0.95f,
            letterSpacingEm = 0.018f,
            backingRadiusScale = 0f,
            paddingXScale = 0f,
            paddingYScale = 0f,
            tail = "none"
        ),
        "subtitle" to TextPresetSpec(
            preset = "subtitle",
            defaultWidth = 0.78f,
            defaultFontSize = 0.05f,
            style = TextStyle(
                preset = "subtitle",
                color = "#f2f4f8",
                outlineColor = "#0a0b0e",
                outlineScale = 0.08f,
                backgroundColor = "#0a0b0e",
                opacity = 1f,
                align = "center",
                uppercase = false
            ),
            fontFamily = "NotoSans",
            fontWeight = 700,
            lineHeightRatio = 1.18f,
            letterSpacingEm = 0f,
            backingRadiusScale = 0.24f,
            paddingXScale = 0.48f,
            paddingYScale = 0.24f,
            tail = "none"
        ),
        "label" to TextPresetSpec(
            preset = "label",
            defaultWidth = 0.42f,
            defaultFontSize = 0.064f,
            style = TextStyle(
                preset = "label",
                color = "#11130a",
                outlineColor = "#11130a",
                outlineScale = 0f,
                backgroundColor = "#d8ff4a",
                opacity = 1f,
                align = "center",
                uppercase = true
            ),
            fontFamily = "NotoSans",
            fontWeight = 900,
            lineHeightRatio = 1.05f,
            letterSpacingEm = 0.035f,
            backingRadiusScale = 0.18f,
            paddingXScale = 0.42f,
            paddingYScale = 0.2f,
            tail = "none"
        ),
        "news" to TextPresetSpec(
            preset = "news",
            defaultWidth = 1.0f,
            defaultFontSize = 0.058f,
            style = TextStyle(
                preset = "news",
                color = "#11130a",
                outlineColor = "#11130a",
                outlineScale = 0f,
                backgroundColor = "#d8ff4a",
                opacity = 1f,
                align = "left",
                uppercase = true
            ),
            fontFamily = "NotoSans",
            fontWeight = 900,
            lineHeightRatio = 1.02f,
            letterSpacingEm = 0.02f,
            backingRadiusScale = 0f,
            paddingXScale = 0.38f,
            paddingYScale = 0.22f,
            tail = "none"
        ),
        "bubble" to TextPresetSpec(
            preset = "bubble",
            defaultWidth = 0.62f,
            defaultFontSize = 0.064f,
            style = TextStyle(
                preset = "bubble",
                color = "#0a0b0e",
                outlineColor = "#0a0b0e",
                outlineScale = 0f,
                backgroundColor = "#f2f4f8",
                opacity = 1f,
                align = "left",
                uppercase = false
            ),
            fontFamily = "NotoSans",
            fontWeight = 700,
            lineHeightRatio = 1.12f,
            letterSpacingEm = 0f,
            backingRadiusScale = 0.55f,
            paddingXScale = 0.5f,
            paddingYScale = 0.34f,
            tail = "bottom-left"
        ),
        "plain" to TextPresetSpec(
            preset = "plain",
            defaultWidth = 0.58f,
            defaultFontSize = 0.06f,
            style = TextStyle(
                preset = "plain",
                color = "#f2f4f8",
                outlineColor = "#0a0b0e",
                outlineScale = 0f,
                backgroundColor = null,
                opacity = 1f,
                align = "center",
                uppercase = false
            ),
            fontFamily = "NotoSans",
            fontWeight = 400,
            lineHeightRatio = 1.16f,
            letterSpacingEm = 0f,
            backingRadiusScale = 0f,
            paddingXScale = 0f,
            paddingYScale = 0f,
            tail = "none"
        )
    )

    fun getPresetSpec(preset: String): TextPresetSpec =
        PRESETS[preset] ?: PRESETS["impact"]!!

    fun applyPreset(layer: MemeEditLayer.Text, presetName: String): MemeEditLayer.Text {
        val spec = getPresetSpec(presetName)
        return layer.copy(
            width = spec.defaultWidth,
            fontSize = spec.defaultFontSize,
            style = spec.style.copy(preset = presetName)
        )
    }

    fun parseColor(hex: String, fallback: Int = Color.WHITE): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        fallback
    }

    fun measureLayout(
        context: Context,
        text: String,
        style: TextStyle,
        fontSizePx: Float,
        widthPx: Int
    ): Pair<StaticLayout, MemeTextLayoutResultPx> {
        val spec = getPresetSpec(style.preset)
        val displayText = if (style.uppercase) text.uppercase() else text
        val safeText = displayText.ifEmpty { " " }
        val lineHeightPx = max(1f, fontSizePx * spec.lineHeightRatio)

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = max(1f, fontSizePx)
            letterSpacing = spec.letterSpacingEm
            typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
        }

        val layout = MemeTextLayout.buildStaticLayout(
            text = safeText,
            paint = paint,
            widthPx = max(1, widthPx),
            lineHeightPx = lineHeightPx,
            align = style.align
        )
        val result = MemeTextLayout.resultFromLayout(safeText, layout, 2f)
        return Pair(layout, result)
    }

    fun drawTextLayer(
        canvas: Canvas,
        context: Context,
        layer: MemeEditLayer.Text,
        transform: InterpolatedTransform,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ) {
        val style = layer.style
        val spec = getPresetSpec(style.preset)

        val fontSizePx = layer.fontSize * canvasHeightPx.toFloat()
        val wrapWidthPx = (layer.width * canvasWidthPx.toFloat()).roundToInt()

        val displayText = if (style.uppercase) layer.text.uppercase() else layer.text
        if (displayText.isBlank()) return

        val (layout, result) = measureLayout(context, displayText, style, fontSizePx, max(1, wrapWidthPx))

        val layoutWidth = layout.width.toFloat()
        val layoutHeight = layout.height.toFloat()

        val centerX = transform.center.x * canvasWidthPx.toFloat()
        val centerY = transform.center.y * canvasHeightPx.toFloat()

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(transform.rotationDegrees)
        canvas.scale(transform.scale, transform.scale)
        // Draw backing (speech bubble or pill/rect) if present
        style.backgroundColor?.let { bgHex ->
            val bgColor = parseColor(bgHex)
            val paddingX = fontSizePx * spec.paddingXScale
            val paddingY = fontSizePx * spec.paddingYScale
            val radius = fontSizePx * spec.backingRadiusScale

            val left = -layoutWidth / 2f - paddingX
            val top = -layoutHeight / 2f - paddingY
            val right = layoutWidth / 2f + paddingX
            val bottom = layoutHeight / 2f + paddingY

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                alpha = (style.opacity * transform.opacity * 255f).coerceIn(0f, 255f).roundToInt()
                this.style = Paint.Style.FILL
            }

            val rect = RectF(left, top, right, bottom)
            if (radius > 0f) {
                canvas.drawRoundRect(rect, radius, radius, bgPaint)
            } else {
                canvas.drawRect(rect, bgPaint)
            }
        }

        // Translate to top-left of layout for StaticLayout.draw
        canvas.translate(-layoutWidth / 2f, -layoutHeight / 2f)

        val alpha = (style.opacity * transform.opacity * 255f).coerceIn(0f, 255f).roundToInt()

        // 1. Draw outline stroke if outlineScale > 0
        if (style.outlineScale > 0.001f) {
            val outlineStrokeWidth = fontSizePx * style.outlineScale
            val strokePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = outlineStrokeWidth
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = parseColor(style.outlineColor)
                this.alpha = alpha
                textSize = fontSizePx
                letterSpacing = spec.letterSpacingEm
                typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
            }
            val strokeLayout = MemeTextLayout.buildStaticLayout(
                text = displayText,
                paint = strokePaint,
                widthPx = max(1, wrapWidthPx),
                lineHeightPx = max(1f, fontSizePx * spec.lineHeightRatio),
                align = style.align
            )
            strokeLayout.draw(canvas)
        }

        // 2. Draw text fill
        val fillPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            color = parseColor(style.color)
            this.alpha = alpha
            textSize = fontSizePx
            letterSpacing = spec.letterSpacingEm
            typeface = MemeTextLayout.weightedTypeface(context, spec.fontFamily, spec.fontWeight)
        }
        val fillLayout = MemeTextLayout.buildStaticLayout(
            text = displayText,
            paint = fillPaint,
            widthPx = max(1, wrapWidthPx),
            lineHeightPx = max(1f, fontSizePx * spec.lineHeightRatio),
            align = style.align
        )
        fillLayout.draw(canvas)

        canvas.restore()
    }
}
