package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanBuilderTest {

    @Test
    fun testOutputSizeCalculation() {
        val base = BaseTransform(outputAspect = "1:1")
        val size = RenderPlanBuilder.calculateOutputSize(
            sourceWidth = 1920,
            sourceHeight = 1080,
            base = base
        )
        // For 1:1 aspect, width and height should match
        assertEquals(size.widthPx, size.heightPx)
    }

    @Test
    fun test16MPCapSafety() {
        val base = BaseTransform()
        // Degenerate 100MP image
        val size = RenderPlanBuilder.calculateOutputSize(
            sourceWidth = 10_000,
            sourceHeight = 10_000,
            base = base,
            maxPixels = RenderPlanBuilder.MAX_IMAGE_RENDER_PIXELS
        )
        val area = size.widthPx.toLong() * size.heightPx.toLong()
        assertTrue(area <= RenderPlanBuilder.MAX_IMAGE_RENDER_PIXELS)
    }

    @Test
    fun test6000x4000CappedStrictlyWithin16MP() {
        val base = BaseTransform()
        val size = RenderPlanBuilder.calculateOutputSize(
            sourceWidth = 6000,
            sourceHeight = 4000,
            base = base,
            maxPixels = RenderPlanBuilder.MAX_IMAGE_RENDER_PIXELS
        )
        val area = size.widthPx.toLong() * size.heightPx.toLong()
        assertTrue("Area $area must be <= 16,000,000", area <= 16_000_000L)
        assertTrue("Width should be scaled down", size.widthPx <= 4899)
        assertTrue("Height should be scaled down", size.heightPx <= 3266)
    }

    @Test
    fun testTextMeasurementsUnscaledForRendererScale() {
        val source = MemeEditSource("file://image.jpg", "image.jpg", MediaEditKind.IMAGE, 1000, 1000)
        val project = ProjectCore.createDefaultImageProject(source).copy(
            layers = listOf(
                MemeEditLayer.Text(
                    id = "txt_scale",
                    text = "HELLO",
                    width = 0.5f,
                    fontSize = 0.1f,
                    keyframes = listOf(TransformKeyframe(scale = 2.0f))
                )
            )
        )

        val planJson = RenderPlanBuilder.buildImageRenderPlan(project)
        val textSpec = planJson.getJSONArray("layers").getJSONObject(0).getJSONObject("spec")
        val canvas = textSpec.getJSONObject("canvas")
        val transform = textSpec.getJSONObject("transform")

        // fontSizeDip should be 0.1 * 1000 = 100, unscaled by transform.scale
        assertEquals(100.0, canvas.getDouble("fontSizeDip"), 0.01)
        // wrapWidthDip should be 0.5 * 1000 = 500, unscaled by transform.scale
        assertEquals(500.0, canvas.getDouble("wrapWidthDip"), 0.01)
        // transform.scale should carry the 2.0 scale for MemeImageRenderer to apply once
        assertEquals(2.0, transform.getDouble("scale"), 0.01)
    }
    @Test
    fun testImageRenderPlanStructure() {
        val source = MemeEditSource("file://image.jpg", "image.jpg", MediaEditKind.IMAGE, 1200, 800)
        var project = ProjectCore.createDefaultImageProject(source)

        val textLayer = MemeEditLayer.Text(
            id = "txt1",
            text = "SAMPLE MEME",
            style = TextStyle(preset = "impact")
        )
        val coverLayer = MemeEditLayer.Cover(
            id = "cov1",
            rect = NormalizedRect(0.1f, 0.1f, 0.3f, 0.1f),
            mode = "pixelate",
            pixelSize = 24
        )
        val drawLayer = MemeEditLayer.Draw(
            id = "drw1",
            elements = listOf(
                DrawElement(shape = DrawShape.LINE, color = "#FF4E42", points = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f)))
            )
        )

        project = project.copy(layers = listOf(textLayer, coverLayer, drawLayer))

        val planJson = RenderPlanBuilder.buildImageRenderPlan(project)
        assertEquals(1, planJson.getInt("version"))

        val output = planJson.getJSONObject("output")
        assertEquals(1200, output.getInt("widthPx"))
        assertEquals(800, output.getInt("heightPx"))

        val layers = planJson.getJSONArray("layers")
        assertEquals(3, layers.length())

        val l0 = layers.getJSONObject(0)
        assertEquals("text", l0.getString("kind"))
        assertEquals("SAMPLE MEME", l0.getJSONObject("spec").getString("displayText"))

        val l1 = layers.getJSONObject(1)
        assertEquals("cover", l1.getString("kind"))
        assertEquals("pixelate", l1.getString("mode"))

        val l2 = layers.getJSONObject(2)
        assertEquals("draw", l2.getString("kind"))
    }

    @Test
    fun testVideoOverlayRenderPlanTransparent() {
        val source = MemeEditSource("file://video.mp4", "video.mp4", MediaEditKind.VIDEO, 1920, 1080, 5_000_000L)
        val project = ProjectCore.createDefaultVideoProject(source)

        val overlayPlan = RenderPlanBuilder.buildVideoOverlayRenderPlan(project)
        val bg = overlayPlan.getJSONObject("background")
        assertEquals("transparent", bg.getString("mode"))
    }
}
