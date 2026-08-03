package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.style.LineHeightSpan
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
  fun absoluteLineHeightSpanDoesNotReferenceApi29StandardSpan() {
    val styled = MemeTextLayout.withAbsoluteLineHeightPx("api surface", 46f)
    val spans = styled.getSpans(0, styled.length, LineHeightSpan::class.java)
    assertTrue("line-height span installed", spans.isNotEmpty())
    assertTrue("pre-29 span implementation", spans.none { it.javaClass.name.contains("LineHeightSpan\$Standard") })
  }

  @Test
  fun freshPreviewCanMeasureBeforeTheFirstPropBatch() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val view = MemeTextPreviewView(context, MemeTextDensity(1f))

    view.measure(
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )

    assertTrue(view.measuredWidth > 0)
    assertTrue(view.measuredHeight > 0)
  }

  @Test
  fun measuresAllPresetRepresentativesWithBundledFontsAndNoFontPadding() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val presets = listOf("impact", "subtitle", "label", "news", "bubble", "plain")
    presets.forEach { preset ->
      val family = if (preset == "impact") "Anton" else "NotoSans"
      val lineHeightDip = if (preset == "impact") 46f else 56f
      val result = MemeTextLayout.measure(
        context = context,
        text = "$preset layout fixture words\n\nblank line kept",
        fontFamily = family,
        fontWeight = if (preset == "plain") 400 else 700,
        fontSizeDip = 48f,
        lineHeightDip = lineHeightDip,
        letterSpacingEm = if (preset == "impact") 0.018f else 0f,
        widthDip = 720f,
        align = if (preset == "news" || preset == "bubble") "left" else "center",
        density = MemeTextDensity(1f)
      )

      assertTrue("$preset should produce at least one line", result.lines.isNotEmpty())
      assertTrue("$preset measured height should be positive", result.heightPx > 0)
      result.lines.forEach { line ->
        assertTrue("$preset line width within wrap", line.widthPx <= 720f)
        assertTrue("$preset baseline below top", line.baselinePx > line.topPx)
        assertTrue("$preset valid line range", line.start <= line.end)
      }
      result.lines.zipWithNext().forEach { (left, right) ->
        assertTrue("$preset line top step uses absolute line height", kotlin.math.abs((right.topPx - left.topPx) - lineHeightDip) <= 1f)
        assertTrue("$preset baseline step uses absolute line height", kotlin.math.abs((right.baselinePx - left.baselinePx) - lineHeightDip) <= 1f)
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
      fontSizeDip = 42f,
      lineHeightDip = 50f,
      letterSpacingEm = 0f,
      widthDip = 360f,
      align = "center",
      density = MemeTextDensity(1f)
    )

    assertEquals(2f, result.tolerancePx, 0f)
    assertTrue(result.lines.map { it.text }.contains(""))
    assertTrue(result.lines.any { it.text.contains("AV") })
    assertTrue(result.lines.any { it.text.contains("日本語") })
    result.lines.zipWithNext().forEach { (left, right) ->
      assertTrue("unicode baseline step", kotlin.math.abs((right.baselinePx - left.baselinePx) - 50f) <= 1f)
    }
  }

  @Test
  fun previewMetricsComeFromDrawnLayoutAndCatchConfigurationDrift() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val density = MemeTextDensity(1f)
    val helper = MemeTextLayout.measure(
      context = context,
      text = "drift\n\nprobe words",
      fontFamily = "Anton",
      fontWeight = 900,
      fontSizeDip = 48f,
      lineHeightDip = 46f,
      letterSpacingEm = 0.018f,
      widthDip = 360f,
      align = "center",
      density = density
    )
    val preview = MemeTextPreviewView(context, density).apply {
      configure(
        text = "drift\n\nprobe words",
        fontFamily = "Anton",
        fontWeight = 900,
        fontSizeDip = 48f,
        lineHeightDip = 46f,
        letterSpacingEm = 0.018f,
        widthDip = 360f,
        align = "center",
        fillColor = android.graphics.Color.WHITE,
        outlineColor = android.graphics.Color.BLACK,
        outlineWidthDip = 8f
      )
    }
    assertEquals("preview initially mirrors helper", helper.heightPx, preview.layoutResultPx().heightPx)
    preview.forceDiagnosticsLineSpacingExtra(32f)
    assertTrue("drawn preview drift is observable", kotlin.math.abs(helper.heightPx - preview.layoutResultPx().heightPx) > 2)
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
      val fontSizeDip = input.getDouble("fontSizeDip").toFloat()
      val lineHeightDip = input.getDouble("lineHeightDip").toFloat()
      val letterSpacingEm = input.getDouble("letterSpacingEm").toFloat()
      val widthDip = input.getDouble("widthDip").toFloat()
      val widthPx = widthDip.toInt()
      val align = input.getString("align")
      val density = MemeTextDensity(1f)

      val result = MemeTextLayout.measure(context, text, fontFamily, fontWeight, fontSizeDip, lineHeightDip, letterSpacingEm, widthDip, align, density)
      val textView = TextView(context).apply {
        includeFontPadding = false
        setText(MemeTextLayout.withAbsoluteLineHeightPx(text, lineHeightDip))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeDip)
        letterSpacing = letterSpacingEm
        typeface = MemeTextLayout.weightedTypeface(context, fontFamily, fontWeight)
        setLineSpacing(0f, 1f)
        textAlignment = if (align == "right") View.TEXT_ALIGNMENT_TEXT_END else if (align == "left") View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_CENTER
      }
      val preview = MemeTextPreviewView(context, density).apply {
        configure(text, fontFamily, fontWeight, fontSizeDip, lineHeightDip, letterSpacingEm, widthDip, align, android.graphics.Color.WHITE, android.graphics.Color.BLACK, 8f)
      }
      assertEquals("$preset native preview line count", result.lines.size, preview.layoutResultPx().lines.size)
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

  @Test
  fun densityConversionIsInjectedAndDoesNotDoubleScaleHeight() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val measured = listOf(1f, 2f, 3f).map { scale ->
      val density = MemeTextDensity(scale)
      val resultPx = MemeTextLayout.measure(
        context = context,
        text = "density\nscale",
        fontFamily = "Anton",
        fontWeight = 900,
        fontSizeDip = 48f,
        lineHeightDip = 45.6f,
        letterSpacingEm = 0.018f,
        widthDip = 320f,
        align = "center",
        density = density
      )
      val resultDip = resultPx.toDip(density)
      assertEquals("DIP round-trip at ${scale}x", 48f, density.pxToDip(density.dipToPx(48f)), 0.0001f)
      assertEquals("width converted exactly once at ${scale}x", 320f, resultDip.widthDip, 0.0001f)
      assertTrue("height is not density-multiplied at ${scale}x", resultDip.heightDip < 100f)
      assertEquals("physical height maps back once at ${scale}x", resultPx.heightPx / scale, resultDip.heightDip, 0.0001f)
      resultDip
    }

    assertEquals(listOf(2, 2, 2), measured.map { it.lines.size })
    measured.forEach { result ->
      assertEquals(45.6f, result.lines[1].topDip - result.lines[0].topDip, 0.51f)
      assertEquals(45.6f, result.lines[1].baselineDip - result.lines[0].baselineDip, 0.51f)
    }
  }

  @Test
  fun propBatchBuildsAndEmitsOnceWhileColorOnlyChangesOnlyRedraw() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val view = MemeTextPreviewView(context, MemeTextDensity(2f))
    view.resetDiagnostics()

    view.setText("one logical batch")
    view.setFontFamily("Anton")
    view.setFontWeight(900)
    view.setFontSizeDip(48f)
    view.setLineHeightDip(45.6f)
    view.setLetterSpacingEm(0.018f)
    view.setWidthDip(320f)
    view.setAlign("center")
    view.setStrokeWidthDip(10f)
    assertEquals(MemeTextPreviewDiagnostics(0, 0, 0), view.diagnostics())

    view.commitPendingProps()
    assertEquals(MemeTextPreviewDiagnostics(1, 1, 1), view.diagnostics())
    view.commitPendingProps()
    assertEquals("empty commit does nothing", MemeTextPreviewDiagnostics(1, 1, 1), view.diagnostics())

    view.setFillColor("#ff0000")
    view.setStrokeColor("#00ff00")
    view.commitPendingProps()
    assertEquals("colors do not rebuild or emit", MemeTextPreviewDiagnostics(1, 1, 2), view.diagnostics())
  }

  @Test
  fun tightImpactLineHeightAndMaximumOutlineStayInsideOuterDrawBounds() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val view = MemeTextPreviewView(context, MemeTextDensity(1f)).apply {
      configure(
        text = "ÁG\npy",
        fontFamily = "Anton",
        fontWeight = 900,
        fontSizeDip = 48f,
        lineHeightDip = 45.6f,
        letterSpacingEm = 0.018f,
        widthDip = 180f,
        align = "center",
        fillColor = Color.WHITE,
        outlineColor = Color.BLACK,
        outlineWidthDip = 48f * 0.22f
      )
    }
    val content = view.layoutResultPx()
    val bounds = view.drawBoundsPx()
    assertEquals("requested baseline step stays exact", 46, content.lines[1].baselinePx - content.lines[0].baselinePx)
    assertTrue("accent overflow retained above", bounds.glyphOverflowTopPx > 0)
    assertTrue("descender overflow retained below", bounds.glyphOverflowBottomPx > 0)
    assertEquals("stroke uses ceil half width", 6, bounds.strokePaddingPx)
    assertEquals(content.heightPx + bounds.glyphOverflowTopPx + bounds.glyphOverflowBottomPx + bounds.strokePaddingPx * 2, bounds.outerHeightPx)

    view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    val guard = 4
    val bitmap = Bitmap.createBitmap(view.measuredWidth + guard * 2, view.measuredHeight + guard * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(guard.toFloat(), guard.toFloat())
    view.draw(canvas)
    var inkPixels = 0
    var inkOutsideBounds = false
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue
        inkPixels += 1
        if (x < guard || x >= guard + view.measuredWidth || y < guard || y >= guard + view.measuredHeight) {
          inkOutsideBounds = true
        }
      }
    }
    assertTrue("bitmap contains rendered glyphs", inkPixels > 0)
    assertFalse("no accent, descender, or outline ink escapes the view", inkOutsideBounds)
  }
}
