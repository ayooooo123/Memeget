package expo.modules.memegetbg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
      text = "AVATAR kerning\n日本語 fallback test",
      fontFamily = "NotoSans",
      fontWeight = 700,
      fontSizePx = 42f,
      letterSpacingEm = 0f,
      widthPx = 360,
      align = "center"
    )

    assertEquals(2, result.tolerancePx)
    assertFalse(result.lines.map { it.text }.contains(""))
    assertTrue(result.lines.any { it.text.contains("AV") })
    assertTrue(result.lines.any { it.text.contains("日本語") })
  }
}
