package expo.modules.memegetbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

internal class MemeTextPreviewView(context: Context) : View(context) {
  private val fillPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
  private val strokePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private var layout: StaticLayout? = null
  private var result: MemeTextLayoutResult? = null
  private var textValue = ""
  private var widthValue = 1

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
    widthValue = max(1, widthPx)
    val typeface = Typeface.create(
      Typeface.createFromAsset(context.assets, if (fontFamily == "Anton") "fonts/Anton-Regular.ttf" else "fonts/NotoSans.ttf"),
      fontWeight.coerceIn(100, 900),
      false
    )
    fillPaint.apply {
      color = fillColor
      textSize = max(1f, fontSizePx)
      letterSpacing = letterSpacingEm
      this.typeface = typeface
      style = Paint.Style.FILL
    }
    strokePaint.apply {
      color = outlineColor
      textSize = fillPaint.textSize
      letterSpacing = letterSpacingEm
      this.typeface = typeface
      strokeWidth = max(0f, outlineWidthPx)
    }
    layout = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, widthValue)
      .setAlignment(when (align) {
        "left" -> Layout.Alignment.ALIGN_NORMAL
        "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
      })
      .setLineSpacing(MemeTextLayout.lineSpacingExtra(fillPaint, lineHeightPx), 1f)
      .setIncludePad(false)
      .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
      .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
      .build()
    result = MemeTextLayout.measure(context, text, fontFamily, fontWeight, fontSizePx, lineHeightPx, letterSpacingEm, widthValue, align)
    requestLayout()
    invalidate()
  }

  fun layoutResult(): MemeTextLayoutResult = result ?: MemeTextLayout.measure(context, textValue, "NotoSans", 400, 1f, 1f, 0f, widthValue, "center")

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val h = layout?.height ?: 1
    setMeasuredDimension(widthValue, h)
  }

  override fun onDraw(canvas: Canvas) {
    val current = layout ?: return
    if (strokePaint.strokeWidth > 0f) {
      val strokeLayout = StaticLayout.Builder.obtain(textValue, 0, textValue.length, strokePaint, widthValue)
        .setAlignment(current.alignment)
        .setLineSpacing(0f, 1f)
        .setIncludePad(false)
        .build()
      strokeLayout.draw(canvas)
    }
    current.draw(canvas)
  }
}
