package expo.modules.memegetbg

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.roundToInt

internal data class MemeTextLayoutLine(
  val text: String,
  val start: Int,
  val end: Int,
  val widthPx: Float,
  val topPx: Int,
  val baselinePx: Int
) {
  fun toMap(): Map<String, Any> = mapOf(
    "text" to text,
    "start" to start,
    "end" to end,
    "widthPx" to widthPx,
    "topPx" to topPx,
    "baselinePx" to baselinePx
  )
}

internal data class MemeTextLayoutResult(
  val widthPx: Int,
  val heightPx: Int,
  val includeFontPadding: Boolean,
  val tolerancePx: Int,
  val lines: List<MemeTextLayoutLine>
) {
  fun toMap(): Map<String, Any> = mapOf(
    "widthPx" to widthPx,
    "heightPx" to heightPx,
    "includeFontPadding" to includeFontPadding,
    "tolerancePx" to tolerancePx,
    "lines" to lines.map { it.toMap() }
  )
}

internal object MemeTextLayout {
  const val TOLERANCE_PX = 2
  private const val ANTON_ASSET = "fonts/Anton-Regular.ttf"
  private const val NOTO_SANS_ASSET = "fonts/NotoSans.ttf"

  fun measure(
    context: Context,
    text: String,
    fontFamily: String,
    fontWeight: Int,
    fontSizePx: Float,
    lineHeightPx: Float,
    letterSpacingEm: Float,
    widthPx: Int,
    align: String
  ): MemeTextLayoutResult {
    val boundedWidth = max(1, widthPx)
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
      textSize = max(1f, fontSizePx)
      letterSpacing = letterSpacingEm
      typeface = weightedTypeface(context, fontFamily, fontWeight)
    }
    val styledText = withAbsoluteLineHeight(text, lineHeightPx)
    val layout = StaticLayout.Builder.obtain(styledText, 0, styledText.length, paint, boundedWidth)
      .setAlignment(androidAlignment(align))
      .setLineSpacing(0f, 1f)
      .setIncludePad(false)
      .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
      .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
      .build()
    val lines = (0 until layout.lineCount).map { index ->
      val start = layout.getLineStart(index)
      val end = layout.getLineEnd(index).coerceAtLeast(start)
      MemeTextLayoutLine(
        text = text.substring(start, end).trimEnd('\n'),
        start = start,
        end = end,
        widthPx = layout.getLineWidth(index),
        topPx = layout.getLineTop(index),
        baselinePx = layout.getLineBaseline(index)
      )
    }
    return MemeTextLayoutResult(
      widthPx = boundedWidth,
      heightPx = layout.height,
      includeFontPadding = false,
      tolerancePx = TOLERANCE_PX,
      lines = lines
    )
  }

  internal fun weightedTypeface(context: Context, fontFamily: String, fontWeight: Int): Typeface {
    val asset = if (fontFamily == "Anton") ANTON_ASSET else NOTO_SANS_ASSET
    val base = Typeface.createFromAsset(context.assets, asset)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Typeface.create(base, fontWeight.coerceIn(100, 900), false)
    } else if (fontWeight >= 700) {
      Typeface.create(base, Typeface.BOLD)
    } else {
      base
    }
  }

  private fun androidAlignment(align: String): Layout.Alignment = when (align) {
    "left" -> Layout.Alignment.ALIGN_NORMAL
    "right" -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_CENTER
  }


  internal fun withAbsoluteLineHeight(text: String, lineHeightPx: Float): SpannableString {
    val styled = SpannableString(text)
    if (styled.isNotEmpty()) {
      styled.setSpan(ExactLineHeightSpan(max(1, lineHeightPx.roundToInt())), 0, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return styled
  }

  private class ExactLineHeightSpan(private val requestedHeightPx: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: android.graphics.Paint.FontMetricsInt) {
      val currentHeight = fm.descent - fm.ascent
      if (currentHeight <= 0) return
      val extra = requestedHeightPx - currentHeight
      val descentAdjustment = kotlin.math.ceil(extra / 2.0).toInt()
      fm.descent += descentAdjustment
      fm.ascent = fm.descent - requestedHeightPx
      fm.bottom = fm.descent
      fm.top = fm.ascent
    }
  }
  internal fun lineSpacingExtra(paint: TextPaint, lineHeightPx: Float): Float {
    val metrics = paint.fontMetrics
    val nativeLineHeight = metrics.descent - metrics.ascent
    return (lineHeightPx - nativeLineHeight).coerceAtLeast(-nativeLineHeight * 0.25f)
  }
}
