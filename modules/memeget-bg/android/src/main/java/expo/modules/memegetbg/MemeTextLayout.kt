package expo.modules.memegetbg

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal class MemeTextDensity(scale: Float) {
  val scale: Float = if (scale.isFinite() && scale > 0f) scale else 1f

  fun dipToPx(valueDip: Float): Float = valueDip * scale
  fun dipToRoundedPx(valueDip: Float): Int = dipToPx(valueDip).roundToInt()
  fun dipToCeilPx(valueDip: Float): Int = ceil(dipToPx(valueDip).toDouble()).toInt()
  fun pxToDip(valuePx: Float): Float = valuePx / scale
  fun pxToDip(valuePx: Int): Float = valuePx / scale
}

internal data class MemeTextLayoutLinePx(
  val text: String,
  val start: Int,
  val end: Int,
  val widthPx: Float,
  val topPx: Int,
  val baselinePx: Int
)

internal data class MemeTextLayoutResultPx(
  val widthPx: Int,
  val heightPx: Int,
  val includeFontPadding: Boolean,
  val tolerancePx: Float,
  val lines: List<MemeTextLayoutLinePx>
) {
  fun toDip(density: MemeTextDensity): MemeTextLayoutResultDip = MemeTextLayoutResultDip(
    widthDip = density.pxToDip(widthPx),
    heightDip = density.pxToDip(heightPx),
    includeFontPadding = includeFontPadding,
    toleranceDip = density.pxToDip(tolerancePx),
    lines = lines.map { line ->
      MemeTextLayoutLineDip(
        text = line.text,
        start = line.start,
        end = line.end,
        widthDip = density.pxToDip(line.widthPx),
        topDip = density.pxToDip(line.topPx),
        baselineDip = density.pxToDip(line.baselinePx)
      )
    }
  )
}

internal data class MemeTextLayoutLineDip(
  val text: String,
  val start: Int,
  val end: Int,
  val widthDip: Float,
  val topDip: Float,
  val baselineDip: Float
) {
  fun toMap(): Map<String, Any> = mapOf(
    "text" to text,
    "start" to start,
    "end" to end,
    "widthDip" to widthDip,
    "topDip" to topDip,
    "baselineDip" to baselineDip
  )
}

internal data class MemeTextLayoutResultDip(
  val widthDip: Float,
  val heightDip: Float,
  val includeFontPadding: Boolean,
  val toleranceDip: Float,
  val lines: List<MemeTextLayoutLineDip>
) {
  fun toMap(): Map<String, Any> = mapOf(
    "widthDip" to widthDip,
    "heightDip" to heightDip,
    "includeFontPadding" to includeFontPadding,
    "toleranceDip" to toleranceDip,
    "lines" to lines.map { it.toMap() }
  )
}

internal object MemeTextLayout {
  const val TOLERANCE_DIP = 2f
  private const val ANTON_ASSET = "fonts/Anton-Regular.ttf"
  private const val NOTO_SANS_ASSET = "fonts/NotoSans.ttf"
  private data class TypefaceKey(val asset: String, val style: Int)
  private val typefaceCache = HashMap<TypefaceKey, Typeface>()

  fun measure(
    context: Context,
    text: String,
    fontFamily: String,
    fontWeight: Int,
    fontSizeDip: Float,
    lineHeightDip: Float,
    letterSpacingEm: Float,
    widthDip: Float,
    align: String,
    density: MemeTextDensity = MemeTextDensity(context.resources.displayMetrics.density)
  ): MemeTextLayoutResultPx {
    val widthPx = max(1, density.dipToRoundedPx(widthDip))
    val lineHeightPx = max(1f, density.dipToPx(lineHeightDip))
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
      textSize = max(1f, density.dipToPx(fontSizeDip))
      letterSpacing = letterSpacingEm
      typeface = weightedTypeface(context, fontFamily, fontWeight)
    }
    val layout = buildStaticLayout(text, paint, widthPx, lineHeightPx, align)
    return resultFromLayout(text, layout, density.dipToPx(TOLERANCE_DIP))
  }

  internal fun resultFromLayout(text: String, layout: StaticLayout, tolerancePx: Float): MemeTextLayoutResultPx {
    val lines = (0 until layout.lineCount).map { index ->
      val start = layout.getLineStart(index)
      val end = layout.getLineEnd(index).coerceAtLeast(start)
      MemeTextLayoutLinePx(
        text = text.substring(start, end).trimEnd('\n'),
        start = start,
        end = end,
        widthPx = max(0f, layout.getLineWidth(index)),
        topPx = layout.getLineTop(index),
        baselinePx = layout.getLineBaseline(index)
      )
    }
    return MemeTextLayoutResultPx(
      widthPx = layout.width,
      heightPx = layout.height,
      includeFontPadding = false,
      tolerancePx = tolerancePx,
      lines = lines
    )
  }

  internal fun buildStaticLayout(
    text: String,
    paint: TextPaint,
    widthPx: Int,
    lineHeightPx: Float,
    align: String,
    lineSpacingExtraPx: Float = 0f
  ): StaticLayout {
    val styledText = withAbsoluteLineHeightPx(text, lineHeightPx)
    return StaticLayout.Builder.obtain(styledText, 0, styledText.length, paint, max(1, widthPx))
      .setAlignment(androidAlignment(align))
      .setLineSpacing(lineSpacingExtraPx, 1f)
      .setIncludePad(false)
      .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
      .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
      .build()
  }

  @Synchronized
  internal fun weightedTypeface(context: Context, fontFamily: String, fontWeight: Int): Typeface {
    val asset = if (fontFamily == "Anton") ANTON_ASSET else NOTO_SANS_ASSET
    val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fontWeight.coerceIn(100, 900) else if (fontWeight >= 700) Typeface.BOLD else Typeface.NORMAL
    return typefaceCache.getOrPut(TypefaceKey(asset, style)) {
      val base = Typeface.createFromAsset(context.assets, asset)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, style, false) else Typeface.create(base, style)
    }
  }

  internal fun androidAlignment(align: String): Layout.Alignment = when (align) {
    "left" -> Layout.Alignment.ALIGN_NORMAL
    "right" -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_CENTER
  }

  internal fun withAbsoluteLineHeightPx(text: String, lineHeightPx: Float): SpannableString {
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
      val descentAdjustment = ceil(extra / 2.0).toInt()
      fm.descent += descentAdjustment
      fm.ascent = fm.descent - requestedHeightPx
      fm.bottom = fm.descent
      fm.top = fm.ascent
    }
  }
}
