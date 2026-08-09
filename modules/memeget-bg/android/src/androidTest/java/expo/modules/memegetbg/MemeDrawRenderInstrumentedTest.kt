package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof for the draw-layer renderer ([MemeImageRenderer.drawDraw], the still export and the
 * video-overlay path both flow through it).
 *
 * The plan is built here in output pixels, exactly the shape `drawPlan` in
 * `src/memeImageRenderCore.ts` emits, over a TRANSPARENT background so "did this shape paint a
 * pixel here" and "did it leave the canvas alone there" are both answerable off the alpha channel.
 * Each shape is placed in its own region so a stray pixel from one cannot be read as another.
 */
@RunWith(AndroidJUnit4::class)
class MemeDrawRenderInstrumentedTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val created = mutableListOf<File>()

  @After
  fun tearDown() {
    created.forEach { it.delete() }
    created.clear()
  }

  private fun render(elements: JSONArray, opacity: Double = 1.0, size: Int = 200): Bitmap {
    val layer = JSONObject()
      .put("kind", "draw")
      .put("id", "draw-1")
      .put("opacity", opacity)
      .put("elements", elements)
    val plan = JSONObject()
      .put("version", MemeImageRenderer.PLAN_VERSION)
      .put("id", "draw-test")
      .put("output", JSONObject().put("widthPx", size).put("heightPx", size))
      .put("background", JSONObject().put("mode", "transparent"))
      // Present but unused by a transparent background; renderPlan reads the key.
      .put("source", JSONObject())
      .put("layers", JSONArray().put(layer))
      .toString()
    val path = MemeImageRenderer.render(context, plan)
    val file = File(requireNotNull(Uri.parse(path).path) { "renderer returned $path" })
    created += file
    assertTrue("renderer wrote $path", file.isFile && file.length() > 0L)
    return requireNotNull(
      BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
      )
    ) { "could not decode $path" }
  }

  private fun point(x: Int, y: Int): JSONObject = JSONObject().put("x", x).put("y", y)

  private fun element(
    shape: String,
    color: String,
    strokeWidthPx: Int,
    filled: Boolean,
    vararg points: JSONObject
  ): JSONObject = JSONObject()
    .put("shape", shape)
    .put("color", color)
    .put("strokeWidthPx", strokeWidthPx)
    .put("filled", filled)
    .put("points", JSONArray().apply { points.forEach { put(it) } })

  @Test
  fun drawsEveryShapeWhereThePlanPlacesItAndNowhereElse() {
    val elements = JSONArray()
      // Filled rectangle in the top-left quadrant.
      .put(element("rectangle", "#ff0000", 4, true, point(20, 20), point(80, 80)))
      // Filled ellipse in the top-right quadrant.
      .put(element("ellipse", "#00ff00", 4, true, point(120, 20), point(180, 80)))
      // Horizontal line, lower-left.
      .put(element("line", "#0000ff", 8, false, point(20, 120), point(80, 120)))
      // Horizontal arrow, lower-right.
      .put(element("arrow", "#ffff00", 8, false, point(120, 120), point(180, 120)))
      // Freehand V, bottom-left; its middle vertex sits at (50, 185).
      .put(element("free", "#ff00ff", 8, false, point(20, 165), point(50, 185), point(80, 165)))
      // Single-point free stroke -> a filled dot, bottom-right.
      .put(element("free", "#00ffff", 24, false, point(150, 175)))

    val bitmap = render(elements)
    try {
      // Rectangle: a filled interior pixel is opaque red.
      assertEquals("rectangle interior alpha", 255, Color.alpha(bitmap.getPixel(50, 50)))
      assertEquals("rectangle interior red", 255, Color.red(bitmap.getPixel(50, 50)))
      assertEquals("rectangle interior green", 0, Color.green(bitmap.getPixel(50, 50)))

      // Ellipse: centre of the oval is opaque green.
      assertTrue("ellipse centre painted", Color.alpha(bitmap.getPixel(150, 50)) > 0)
      assertTrue("ellipse centre green", Color.green(bitmap.getPixel(150, 50)) > 200)

      // Line, arrow shaft, freehand vertex and the dot each paint their own point.
      assertTrue("line painted", Color.alpha(bitmap.getPixel(50, 120)) > 0)
      assertTrue("arrow shaft painted", Color.alpha(bitmap.getPixel(150, 120)) > 0)
      assertTrue("freehand vertex painted", Color.alpha(bitmap.getPixel(50, 185)) > 0)
      assertTrue("dot centre painted", Color.alpha(bitmap.getPixel(150, 175)) > 0)

      // The arrowhead's upper leg runs from the tip (180,120) up-left; (165,112) sits on that
      // leg, well above the horizontal shaft, so only the head could have painted it.
      assertTrue("arrowhead painted", Color.alpha(bitmap.getPixel(165, 112)) > 0)

      // A corner and the dead centre between the shapes are never touched: the
      // alpha channel of a transparent-background export survives untouched.
      assertEquals("corner untouched", 0, Color.alpha(bitmap.getPixel(2, 2)))
      assertEquals("gap between shapes untouched", 0, Color.alpha(bitmap.getPixel(100, 100)))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun layerOpacityMultipliesEachElementsAlpha() {
    val elements = JSONArray()
      .put(element("rectangle", "#ff0000", 4, true, point(20, 20), point(180, 180)))
    val bitmap = render(elements, opacity = 0.5)
    try {
      // Opaque red at 50% layer opacity: alpha halves, the channel is unchanged.
      val alpha = Color.alpha(bitmap.getPixel(100, 100))
      assertTrue("half-opacity alpha ~127, was $alpha", alpha in 120..134)
      assertEquals("colour under the alpha is still red", 255, Color.red(bitmap.getPixel(100, 100)))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun zeroLayerOpacityDrawsNothing() {
    val elements = JSONArray()
      .put(element("rectangle", "#ff0000", 4, true, point(20, 20), point(180, 180)))
    val bitmap = render(elements, opacity = 0.0)
    try {
      assertEquals("nothing painted at 0 opacity", 0, Color.alpha(bitmap.getPixel(100, 100)))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun aNonFiniteCoordinateFailsAsAnIOException() {
    // Built as a raw string because org.json refuses to `put` a NaN. A hand-tampered plan is
    // exactly the case finiteFloat defends against; the render must not silently succeed with a
    // background-only export.
    val plan = """
      {
        "version": ${MemeImageRenderer.PLAN_VERSION},
        "output": { "widthPx": 100, "heightPx": 100 },
        "background": { "mode": "transparent" },
        "source": {},
        "layers": [
          {
            "kind": "draw",
            "id": "d",
            "opacity": 1.0,
            "elements": [
              {
                "shape": "line",
                "color": "#ffffff",
                "strokeWidthPx": 4,
                "filled": false,
                "points": [ { "x": NaN, "y": 10 }, { "x": 50, "y": 50 } ]
              }
            ]
          }
        ]
      }
    """.trimIndent()
    try {
      MemeImageRenderer.render(context, plan)
      throw AssertionError("Expected a non-finite coordinate to be rejected")
    } catch (error: java.io.IOException) {
      // Expected: a malformed plan surfaces as the IOException the bridge advertises.
    }
  }
}
