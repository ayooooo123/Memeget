package expo.modules.memegetbg
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemeTextLayoutInstrumentedTest {
  @Test
  fun measuresAllPresetRepresentativesWithBundledFontsAndNoFontPadding() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val presets = listOf("impact", "subtitle", "label", "news", "bubble", "plain")
    presets.forEach { preset ->
      val family = if (preset == "impact") "Anton" else "NotoSans"
      val result = MemeTextLayout.measure(
        context = context,
        text = "$preset layout fixture words",
        fontFamily = family,
        fontWeight = if (preset == "plain") 400 else 700,
        fontSizePx = 48f,
        lineHeightPx = 56f,
        letterSpacingEm = if (preset == "impact") 0.018f else 0f,
        widthPx = 720,
        align = if (preset == "news" || preset == "bubble") "left" else "center"
      )

      assertTrue("$preset should produce at least one line", result.lines.isNotEmpty())
      assertTrue("$preset measured height should be positive", result.heightPx > 0)
      result.lines.forEach { line ->
        assertTrue("$preset line width within wrap", line.widthPx <= 720f)
        assertTrue("$preset baseline below top", line.baselinePx > line.topPx)
        assertTrue("$preset valid line range", line.start < line.end)
      }
    }
  }

  @Test
  fun recordsUnicodeFallbackKerningAndMultilineDiagnostics() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val result = MemeTextLayout.measure(
      context = context,
      text = "AVATAR kerning\n\n日本語 fallback test",
      fontFamily = "NotoSans",
      fontWeight = 700,
      fontSizePx = 42f,
      lineHeightPx = 50f,
      letterSpacingEm = 0f,
      widthPx = 360,
      align = "center"
    )

    assertEquals(2, result.tolerancePx)
    assertTrue(result.lines.map { it.text }.contains(""))
    assertTrue(result.lines.any { it.text.contains("AV") })
    assertTrue(result.lines.any { it.text.contains("日本語") })
  }

  @Test
  fun previewMetricsComeFromDrawnLayoutAndCatchConfigurationDrift() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val helper = MemeTextLayout.measure(
      context = context,
      text = "drift\n\nprobe words",
      fontFamily = "Anton",
      fontWeight = 900,
      fontSizePx = 48f,
      lineHeightPx = 46f,
      letterSpacingEm = 0.018f,
      widthPx = 360,
      align = "center"
    )
    val preview = MemeTextPreviewView(context).apply {
      configure(
        text = "drift\n\nprobe words",
        fontFamily = "Anton",
        fontWeight = 900,
        fontSizePx = 48f,
        lineHeightPx = 46f,
        letterSpacingEm = 0.018f,
        widthPx = 360,
        align = "center",
        fillColor = android.graphics.Color.WHITE,
        outlineColor = android.graphics.Color.BLACK,
        outlineWidthPx = 8f
      )
    }
    assertEquals("preview initially mirrors helper", helper.heightPx, preview.layoutResult().heightPx)
    preview.forceDiagnosticsLineSpacingExtra(32f)
    assertTrue("drawn preview drift is observable", kotlin.math.abs(helper.heightPx - preview.layoutResult().heightPx) > 2)
  }

  @Test
  fun serializedPreviewFixturesMatchTextViewPlacementWithinTwoPreviewPixels() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val json = context.assets.open("text_layout_preview_fixtures.json").bufferedReader().use { it.readText() }
    val fixtures = JSONArray(json)
    for (index in 0 until fixtures.length()) {
      val fixture = fixtures.getJSONObject(index)
      val preset = fixture.getString("preset")
      val scale = fixture.getDouble("scale").toFloat()
      val input = fixture.getJSONObject("input")
      val text = input.getString("text")
      val fontFamily = input.getString("fontFamily")
      val fontWeight = input.getInt("fontWeight")
      val fontSizePx = input.getDouble("fontSizePx").toFloat()
      val lineHeightPx = input.getDouble("lineHeightPx").toFloat()
      val letterSpacingEm = input.getDouble("letterSpacingEm").toFloat()
      val widthPx = input.getInt("widthPx")
      val align = input.getString("align")

      val result = MemeTextLayout.measure(
        context = context,
        text = text,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        letterSpacingEm = letterSpacingEm,
        widthPx = widthPx,
        align = align
      )
      val textView = TextView(context).apply {
        includeFontPadding = false
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
        letterSpacing = letterSpacingEm
        typeface = Typeface.create(Typeface.createFromAsset(context.assets, if (fontFamily == "Anton") "fonts/Anton-Regular.ttf" else "fonts/NotoSans.ttf"), fontWeight, false)
        setLineSpacing(MemeTextLayout.lineSpacingExtra(paint, lineHeightPx), 1f)
        textAlignment = if (align == "right") View.TEXT_ALIGNMENT_TEXT_END else if (align == "left") View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_CENTER
      }
      val preview = MemeTextPreviewView(context).apply {
        configure(
          text = text,
          fontFamily = fontFamily,
          fontWeight = fontWeight,
          fontSizePx = fontSizePx,
          lineHeightPx = lineHeightPx,
          letterSpacingEm = letterSpacingEm,
          widthPx = widthPx,
          align = align,
          fillColor = android.graphics.Color.WHITE,
          outlineColor = android.graphics.Color.BLACK,
          outlineWidthPx = 8f
        )
      }
      assertEquals("$preset native preview line count", result.lines.size, preview.layoutResult().lines.size)
      val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
      val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
      textView.measure(widthSpec, heightSpec)
      textView.layout(0, 0, widthPx, textView.measuredHeight)
      val layout = textView.layout
      assertEquals("$preset line count", layout.lineCount, result.lines.size)
      assertTrue("$preset total height", kotlin.math.abs(layout.height - result.heightPx) * scale <= 2f)
      result.lines.forEachIndexed { lineIndex, line ->
        assertEquals("$preset line text", text.substring(layout.getLineStart(lineIndex), layout.getLineEnd(lineIndex)).trimEnd('\n'), line.text)
        assertEquals("$preset line start", layout.getLineStart(lineIndex), line.start)
        assertEquals("$preset line end", layout.getLineEnd(lineIndex), line.end)
        assertTrue("$preset width drift", kotlin.math.abs(layout.getLineWidth(lineIndex) - line.widthPx) * scale <= 2f)
        assertTrue("$preset top drift", kotlin.math.abs(layout.getLineTop(lineIndex) - line.topPx) * scale <= 2f)
        assertTrue("$preset baseline drift", kotlin.math.abs(layout.getLineBaseline(lineIndex) - line.baselinePx) * scale <= 2f)
      }
    }
  }
}
