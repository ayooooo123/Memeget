package expo.modules.memegetbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import expo.modules.kotlin.viewevent.EventDispatcher
import kotlin.math.max

internal class MemeTextPreviewView(context: Context) : View(context) {
  private val fillPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
  private val strokePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val onMetrics by EventDispatcher<Map<String, Any?>>()
  private var layout: StaticLayout? = null
  private var strokeLayout: StaticLayout? = null
  private var result: MemeTextLayoutResult? = null
  private var textValue = ""
  private var fontFamilyValue = "NotoSans"
  private var fontWeightValue = 400
  private var fontSizePxValue = 1f
  private var lineHeightPxValue = 1f
  private var letterSpacingEmValue = 0f
  private var widthValue = 1
  private var alignValue = "center"
  private var fillColorValue = Color.WHITE
  private var strokeColorValue = Color.BLACK
  private var strokeWidthPxValue = 0f

  fun setText(value: String) { textValue = value; rebuild() }
  fun setFontFamily(value: String) { fontFamilyValue = value; rebuild() }
  fun setFontWeight(value: Int) { fontWeightValue = value; rebuild() }
  fun setFontSizePx(value: Float) { fontSizePxValue = max(1f, value); rebuild() }
  fun setLineHeightPx(value: Float) { lineHeightPxValue = max(1f, value); rebuild() }
  fun setLetterSpacingEm(value: Float) { letterSpacingEmValue = value; rebuild() }
  fun setWidthPx(value: Int) { widthValue = max(1, value); rebuild() }
  fun setAlign(value: String) { alignValue = value; rebuild() }
  fun setFillColor(value: String) { fillColorValue = parseColor(value, Color.WHITE); rebuild() }
  fun setStrokeColor(value: String) { strokeColorValue = parseColor(value, Color.BLACK); rebuild() }
  fun setStrokeWidthPx(value: Float) { strokeWidthPxValue = max(0f, value); rebuild() }
  fun setOpacity(value: Float) { alpha = value.coerceIn(0f, 1f) }

  fun configure(
    text: String,
    fontFamily: String,
    fontWeight: Int,
    fontSizePx: Float,
    lineHeightPx: Float,
    letterSpacingEm: Float,
    widthPx: Int,
    align: String,
    fillColor: Int,
    outlineColor: Int,
    outlineWidthPx: Float
  ) {
    textValue = text
    fontFamilyValue = fontFamily
    fontWeightValue = fontWeight
    fontSizePxValue = max(1f, fontSizePx)
    lineHeightPxValue = max(1f, lineHeightPx)
    letterSpacingEmValue = letterSpacingEm
    widthValue = max(1, widthPx)
    alignValue = align
    fillColorValue = fillColor
    strokeColorValue = outlineColor
    strokeWidthPxValue = max(0f, outlineWidthPx)
    rebuild()
  }

  fun layoutResult(): MemeTextLayoutResult = result ?: MemeTextLayout.measure(context, textValue, fontFamilyValue, fontWeightValue, fontSizePxValue, lineHeightPxValue, letterSpacingEmValue, widthValue, alignValue)

  private fun rebuild() {
    val typeface = Typeface.create(
      Typeface.createFromAsset(context.assets, if (fontFamilyValue == "Anton") "fonts/Anton-Regular.ttf" else "fonts/NotoSans.ttf"),
      fontWeightValue.coerceIn(100, 900),
      false
    )
    fillPaint.apply {
      color = fillColorValue
      textSize = fontSizePxValue
      letterSpacing = letterSpacingEmValue
      this.typeface = typeface
      style = Paint.Style.FILL
    }
    strokePaint.apply {
      color = strokeColorValue
      textSize = fillPaint.textSize
      letterSpacing = letterSpacingEmValue
      this.typeface = typeface
      strokeWidth = strokeWidthPxValue
      style = Paint.Style.STROKE
    }
    layout = buildLayout(fillPaint)
    strokeLayout = if (strokeWidthPxValue > 0f) buildLayout(strokePaint) else null
    result = layout?.let(::resultFromDrawnLayout)
    requestLayout()
    invalidate()
    emitMetrics()
  }

  private fun buildLayout(paint: TextPaint): StaticLayout = StaticLayout.Builder.obtain(textValue, 0, textValue.length, paint, widthValue)
    .setAlignment(androidAlignment(alignValue))
    .setLineSpacing(MemeTextLayout.lineSpacingExtra(paint, lineHeightPxValue), 1f)
    .setIncludePad(false)
    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
    .build()

  internal fun forceDiagnosticsLineSpacingExtra(lineSpacingExtraPx: Float) {
    layout = StaticLayout.Builder.obtain(textValue, 0, textValue.length, fillPaint, widthValue)
      .setAlignment(androidAlignment(alignValue))
      .setLineSpacing(lineSpacingExtraPx, 1f)
      .setIncludePad(false)
      .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
      .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
      .build()
    result = layout?.let(::resultFromDrawnLayout)
    requestLayout()
    invalidate()
    emitMetrics()
  }

  private fun resultFromDrawnLayout(drawnLayout: StaticLayout): MemeTextLayoutResult {
    val lines = (0 until drawnLayout.lineCount).map { index ->
      val start = drawnLayout.getLineStart(index)
      val end = drawnLayout.getLineEnd(index)
      MemeTextLayoutLine(
        text = textValue.substring(start, end).trimEnd('\n'),
        start = start,
        end = end,
        widthPx = max(0f, drawnLayout.getLineWidth(index)),
        topPx = drawnLayout.getLineTop(index),
        baselinePx = drawnLayout.getLineBaseline(index)
      )
    }
    return MemeTextLayoutResult(
      widthPx = widthValue,
      heightPx = drawnLayout.height,
      includeFontPadding = false,
      tolerancePx = MemeTextLayout.TOLERANCE_PX,
      lines = lines
    )
  }

  private fun emitMetrics() {
    val measured = layout?.let(::resultFromDrawnLayout) ?: return
    try {
      onMetrics(
        mapOf(
          "widthPx" to measured.widthPx,
          "heightPx" to measured.heightPx,
          "includeFontPadding" to measured.includeFontPadding,
          "tolerancePx" to measured.tolerancePx,
          "lines" to measured.lines.map { line ->
            mapOf(
              "text" to line.text,
              "start" to line.start,
              "end" to line.end,
              "widthPx" to line.widthPx,
              "topPx" to line.topPx,
              "baselinePx" to line.baselinePx
            )
          }
        )
      )
    } catch (_: ClassCastException) {
      // Plain Android instrumentation contexts are not ReactContexts; JS-hosted views still emit.
    }
  }

  override fun onDraw(canvas: Canvas) {
    strokeLayout?.draw(canvas)
    layout?.draw(canvas)
  }

  private fun androidAlignment(align: String): Layout.Alignment = when (align) {
    "left" -> Layout.Alignment.ALIGN_NORMAL
    "right" -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_CENTER
  }

  private fun parseColor(value: String, fallback: Int): Int = try {
    Color.parseColor(value)
  } catch (_: IllegalArgumentException) {
    fallback
  }
}
