package expo.modules.memegetbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import expo.modules.kotlin.viewevent.EventDispatcher
import kotlin.math.ceil
import kotlin.math.max

internal data class MemeTextPreviewDiagnostics(
  val layoutBuilds: Int,
  val metricsEvents: Int,
  val drawCommits: Int
)

internal data class MemeTextDrawBoundsPx(
  val glyphOverflowTopPx: Int,
  val glyphOverflowBottomPx: Int,
  val strokePaddingPx: Int,
  val contentOffsetXPx: Int,
  val contentOffsetYPx: Int,
  val outerWidthPx: Int,
  val outerHeightPx: Int
)

internal class MemeTextPreviewView(
  context: Context,
  private val density: MemeTextDensity = MemeTextDensity(context.resources.displayMetrics.density)
) : View(context) {
  private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
  private val onMetrics by EventDispatcher<Map<String, Any?>>()
  private var layout: StaticLayout? = null
  private var resultPx: MemeTextLayoutResultPx? = null
  private var boundsPx: MemeTextDrawBoundsPx? = null

  private var textValue = ""
  private var fontFamilyValue = "NotoSans"
  private var fontWeightValue = 400
  private var fontSizeDipValue = 1f
  private var lineHeightDipValue = 1f
  private var letterSpacingEmValue = 0f
  private var widthDipValue = 1f
  private var alignValue = "center"
  private var fillColorValue = Color.WHITE
  private var strokeColorValue = Color.BLACK
  private var strokeWidthDipValue = 0f
  private var opacityValue = 1f

  private var layoutDirty = true
  private var drawDirty = true
  private var layoutBuildCount = 0
  private var metricsEventCount = 0
  private var drawCommitCount = 0

  fun setText(value: String) {
    if (textValue == value) return
    textValue = value
    markLayoutDirty()
  }

  fun setFontFamily(value: String) {
    if (fontFamilyValue == value) return
    fontFamilyValue = value
    markLayoutDirty()
  }

  fun setFontWeight(value: Int) {
    if (fontWeightValue == value) return
    fontWeightValue = value
    markLayoutDirty()
  }

  fun setFontSizeDip(value: Float) {
    val bounded = max(1f, value)
    if (fontSizeDipValue == bounded) return
    fontSizeDipValue = bounded
    markLayoutDirty()
  }

  fun setLineHeightDip(value: Float) {
    val bounded = max(1f, value)
    if (lineHeightDipValue == bounded) return
    lineHeightDipValue = bounded
    markLayoutDirty()
  }

  fun setLetterSpacingEm(value: Float) {
    if (letterSpacingEmValue == value) return
    letterSpacingEmValue = value
    markLayoutDirty()
  }

  fun setWidthDip(value: Float) {
    val bounded = max(1f, value)
    if (widthDipValue == bounded) return
    widthDipValue = bounded
    markLayoutDirty()
  }

  fun setAlign(value: String) {
    if (alignValue == value) return
    alignValue = value
    markLayoutDirty()
  }

  fun setFillColor(value: String) = setFillColorInt(parseColor(value, Color.WHITE))
  fun setStrokeColor(value: String) = setStrokeColorInt(parseColor(value, Color.BLACK))

  fun setStrokeWidthDip(value: Float) {
    val bounded = max(0f, value)
    if (strokeWidthDipValue == bounded) return
    strokeWidthDipValue = bounded
    markLayoutDirty()
  }

  fun setOpacity(value: Float) {
    val bounded = value.coerceIn(0f, 1f)
    if (opacityValue == bounded) return
    opacityValue = bounded
    drawDirty = true
  }

  fun configure(
    text: String,
    fontFamily: String,
    fontWeight: Int,
    fontSizeDip: Float,
    lineHeightDip: Float,
    letterSpacingEm: Float,
    widthDip: Float,
    align: String,
    fillColor: Int,
    outlineColor: Int,
    outlineWidthDip: Float
  ) {
    setText(text)
    setFontFamily(fontFamily)
    setFontWeight(fontWeight)
    setFontSizeDip(fontSizeDip)
    setLineHeightDip(lineHeightDip)
    setLetterSpacingEm(letterSpacingEm)
    setWidthDip(widthDip)
    setAlign(align)
    setFillColorInt(fillColor)
    setStrokeColorInt(outlineColor)
    setStrokeWidthDip(outlineWidthDip)
    commitPendingProps()
  }

  internal fun commitPendingProps() {
    if (!layoutDirty && !drawDirty) return
    if (layoutDirty) {
      rebuildLayout()
      requestLayout()
      emitMetrics()
    }
    alpha = opacityValue
    invalidate()
    drawCommitCount += 1
    layoutDirty = false
    drawDirty = false
  }

  internal fun layoutResultPx(): MemeTextLayoutResultPx {
    if (layoutDirty || resultPx == null) commitPendingProps()
    return requireNotNull(resultPx)
  }

  internal fun layoutResultDip(): MemeTextLayoutResultDip = layoutResultPx().toDip(density)

  internal fun drawBoundsPx(): MemeTextDrawBoundsPx {
    if (layoutDirty || boundsPx == null) commitPendingProps()
    return requireNotNull(boundsPx)
  }

  internal fun diagnostics(): MemeTextPreviewDiagnostics = MemeTextPreviewDiagnostics(layoutBuildCount, metricsEventCount, drawCommitCount)

  internal fun resetDiagnostics() {
    layoutBuildCount = 0
    metricsEventCount = 0
    drawCommitCount = 0
  }

  internal fun forceDiagnosticsLineSpacingExtra(lineSpacingExtraPx: Float) {
    ensurePaintGeometry()
    val nextLayout = MemeTextLayout.buildStaticLayout(
      textValue,
      textPaint,
      max(1, density.dipToRoundedPx(widthDipValue)),
      max(1f, density.dipToPx(lineHeightDipValue)),
      alignValue,
      lineSpacingExtraPx
    )
    layout = nextLayout
    resultPx = MemeTextLayout.resultFromLayout(textValue, nextLayout, density.dipToPx(MemeTextLayout.TOLERANCE_DIP))
    boundsPx = calculateDrawBounds(nextLayout)
    layoutBuildCount += 1
    requestLayout()
    invalidate()
    drawCommitCount += 1
    emitMetrics()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    if (layoutDirty || boundsPx == null) commitPendingProps()
    val bounds = requireNotNull(boundsPx)
    setMeasuredDimension(resolveSize(bounds.outerWidthPx, widthMeasureSpec), resolveSize(bounds.outerHeightPx, heightMeasureSpec))
  }

  override fun onDraw(canvas: Canvas) {
    if (layoutDirty || boundsPx == null) commitPendingProps()
    val drawnLayout = layout ?: return
    val bounds = boundsPx ?: return
    val checkpoint = canvas.save()
    canvas.translate(bounds.contentOffsetXPx.toFloat(), bounds.contentOffsetYPx.toFloat())
    val strokeWidthPx = max(0f, density.dipToPx(strokeWidthDipValue))
    if (strokeWidthPx > 0f) {
      textPaint.style = Paint.Style.STROKE
      textPaint.strokeWidth = strokeWidthPx
      textPaint.color = strokeColorValue
      drawnLayout.draw(canvas)
    }
    textPaint.style = Paint.Style.FILL
    textPaint.strokeWidth = 0f
    textPaint.color = fillColorValue
    drawnLayout.draw(canvas)
    canvas.restoreToCount(checkpoint)
  }

  private fun markLayoutDirty() {
    layoutDirty = true
    drawDirty = true
  }

  private fun setFillColorInt(value: Int) {
    if (fillColorValue == value) return
    fillColorValue = value
    drawDirty = true
  }

  private fun setStrokeColorInt(value: Int) {
    if (strokeColorValue == value) return
    strokeColorValue = value
    drawDirty = true
  }

  private fun ensurePaintGeometry() {
    textPaint.apply {
      textSize = max(1f, this@MemeTextPreviewView.density.dipToPx(fontSizeDipValue))
      letterSpacing = letterSpacingEmValue
      typeface = MemeTextLayout.weightedTypeface(context, fontFamilyValue, fontWeightValue)
      style = Paint.Style.FILL
      strokeWidth = 0f
      color = fillColorValue
    }
  }

  private fun rebuildLayout() {
    ensurePaintGeometry()
    val nextLayout = MemeTextLayout.buildStaticLayout(
      textValue,
      textPaint,
      max(1, density.dipToRoundedPx(widthDipValue)),
      max(1f, density.dipToPx(lineHeightDipValue)),
      alignValue
    )
    layout = nextLayout
    resultPx = MemeTextLayout.resultFromLayout(textValue, nextLayout, density.dipToPx(MemeTextLayout.TOLERANCE_DIP))
    boundsPx = calculateDrawBounds(nextLayout)
    layoutBuildCount += 1
  }

  private fun calculateDrawBounds(drawnLayout: StaticLayout): MemeTextDrawBoundsPx {
    val fontMetrics = textPaint.fontMetricsInt
    var inkTopPx = 0
    var inkBottomPx = drawnLayout.height
    for (lineIndex in 0 until drawnLayout.lineCount) {
      val baselinePx = drawnLayout.getLineBaseline(lineIndex)
      inkTopPx = minOf(inkTopPx, baselinePx + fontMetrics.top)
      inkBottomPx = maxOf(inkBottomPx, baselinePx + fontMetrics.bottom)
    }
    val glyphOverflowTopPx = max(0, -inkTopPx)
    val glyphOverflowBottomPx = max(0, inkBottomPx - drawnLayout.height)
    val strokePaddingPx = max(0, ceil(density.dipToPx(strokeWidthDipValue) / 2.0).toInt())
    return MemeTextDrawBoundsPx(
      glyphOverflowTopPx = glyphOverflowTopPx,
      glyphOverflowBottomPx = glyphOverflowBottomPx,
      strokePaddingPx = strokePaddingPx,
      contentOffsetXPx = strokePaddingPx,
      contentOffsetYPx = strokePaddingPx + glyphOverflowTopPx,
      outerWidthPx = drawnLayout.width + strokePaddingPx * 2,
      outerHeightPx = drawnLayout.height + glyphOverflowTopPx + glyphOverflowBottomPx + strokePaddingPx * 2
    )
  }

  private fun emitMetrics() {
    val contentDip = resultPx?.toDip(density) ?: return
    val bounds = boundsPx ?: return
    metricsEventCount += 1
    val metrics = contentDip.toMap().toMutableMap<String, Any?>()
    metrics["outerWidthDip"] = density.pxToDip(bounds.outerWidthPx)
    metrics["outerHeightDip"] = density.pxToDip(bounds.outerHeightPx)
    metrics["contentOffsetXDip"] = density.pxToDip(bounds.contentOffsetXPx)
    metrics["contentOffsetYDip"] = density.pxToDip(bounds.contentOffsetYPx)
    try {
      onMetrics(metrics)
    } catch (_: ClassCastException) {
      // Plain instrumentation contexts are not ReactContexts; hosted views emit normally.
    }
  }

  private fun parseColor(value: String, fallback: Int): Int = try {
    Color.parseColor(value)
  } catch (_: IllegalArgumentException) {
    fallback
  }
}
