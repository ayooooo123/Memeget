package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoCompositionTest {

    @Test
    fun testRetainedRangesTimingAtNormalSpeed() {
        val ranges = listOf(
            TimeRangeUs(0L, 2_000_000L),       // 0s..2s (2s)
            TimeRangeUs(4_000_000L, 7_000_000L) // 4s..7s (3s)
        )
        val speed = 1.0f

        // Total output duration: 2s + 3s = 5s
        assertEquals(5_000_000L, ProjectCore.outputDurationUs(ranges, speed))

        // In first segment
        assertEquals(1_000_000L, ProjectCore.sourceTimeToOutputTimeUs(1_000_000L, ranges, speed))
        assertEquals(1_000_000L, ProjectCore.outputTimeToSourceTimeUs(1_000_000L, ranges, speed))

        // In cut middle (3s in source) -> should return null
        assertNull(ProjectCore.sourceTimeToOutputTimeUs(3_000_000L, ranges, speed))

        // In second segment: 5s source -> 2s (first segment) + 1s (into second segment) = 3s output
        assertEquals(3_000_000L, ProjectCore.sourceTimeToOutputTimeUs(5_000_000L, ranges, speed))
        assertEquals(5_000_000L, ProjectCore.outputTimeToSourceTimeUs(3_000_000L, ranges, speed))
    }

    @Test
    fun testRetainedRangesTimingAtHalfSpeed() {
        val ranges = listOf(TimeRangeUs(0L, 2_000_000L)) // 2s duration
        val speed = 0.5f // 0.5x speed -> 4s output

        assertEquals(4_000_000L, ProjectCore.outputDurationUs(ranges, speed))

        // Source 1s -> Output 2s
        assertEquals(2_000_000L, ProjectCore.sourceTimeToOutputTimeUs(1_000_000L, ranges, speed))
        assertEquals(1_000_000L, ProjectCore.outputTimeToSourceTimeUs(2_000_000L, ranges, speed))
    }

    @Test
    fun testRetainedRangesTimingAtDoubleSpeed() {
        val ranges = listOf(TimeRangeUs(0L, 4_000_000L)) // 4s duration
        val speed = 2.0f // 2x speed -> 2s output

        assertEquals(2_000_000L, ProjectCore.outputDurationUs(ranges, speed))

        // Source 2s -> Output 1s
        assertEquals(1_000_000L, ProjectCore.sourceTimeToOutputTimeUs(2_000_000L, ranges, speed))
        assertEquals(2_000_000L, ProjectCore.outputTimeToSourceTimeUs(1_000_000L, ranges, speed))
    }

    @Test
    fun testVideoOutputDimensionsOddInputProducesEvenOutput() {
        val base = BaseTransform()
        val outSize = RenderPlanBuilder.calculateOutputSize(721, 1281, base)
        val size = NativeVideoComposition.calculateVideoOutputDimensions(
            rawWidth = outSize.widthPx,
            rawHeight = outSize.heightPx
        )
        // Must be even for H.264
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
        assertEquals(720, size.width)
        assertEquals(1280, size.height)

        val originalAspect = 721.0 / 1281.0
        val outputAspect = size.width.toDouble() / size.height.toDouble()
        assertTrue(kotlin.math.abs(originalAspect - outputAspect) < 0.002)
    }

    @Test
    fun testVideoOutputDimensionsBoundedWithinEncoderCaps() {
        val base = BaseTransform()
        val bounds = NativeVideoComposition.EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1080,
            widthAlignment = 2,
            heightAlignment = 2,
            maxPixels = 1920L * 1080L
        )
        // Degenerate 6000x4000 source
        val outSize = RenderPlanBuilder.calculateOutputSize(6000, 4000, base)
        val size = NativeVideoComposition.calculateVideoOutputDimensions(
            rawWidth = outSize.widthPx,
            rawHeight = outSize.heightPx,
            bounds = bounds
        )
        assertTrue(size.width <= 1920)
        assertTrue(size.height <= 1080)
        assertTrue(size.width.toLong() * size.height.toLong() <= 1920L * 1080L)
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
        // Aspect ratio 6000/4000 = 1.5; 1620 / 1080 = 1.5
        assertEquals(1620, size.width)
        assertEquals(1080, size.height)
    }


    @Test
    fun testVideoOutputDimensionsTargetAspect1To1() {
        val base = BaseTransform(outputAspect = "1:1")
        val outSize = RenderPlanBuilder.calculateOutputSize(1921, 1081, base)
        val size = NativeVideoComposition.calculateVideoOutputDimensions(
            rawWidth = outSize.widthPx,
            rawHeight = outSize.heightPx
        )
        // For 1:1 aspect, width and height must match and be even
        assertEquals(size.width, size.height)
        assertEquals(0, size.width % 2)
        assertEquals(1080, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun testVideoOutputDimensionsCustomAlignment() {
        val bounds = NativeVideoComposition.EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1920,
            widthAlignment = 16,
            heightAlignment = 16,
            maxPixels = 1920L * 1080L
        )
        val size = NativeVideoComposition.calculateVideoOutputDimensions(
            rawWidth = 1000,
            rawHeight = 1000,
            bounds = bounds
        )
        assertEquals(0, size.width % 16)
        assertEquals(0, size.height % 16)
        assertTrue(size.width <= 1920)
        assertTrue(size.height <= 1920)
    }

    @Test
    fun testVideoOutputDimensionsExtremeNarrowPortraitTerminatesAndRespectsBounds() {
        val bounds = NativeVideoComposition.EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1080,
            widthAlignment = 2,
            heightAlignment = 2,
            maxPixels = 1920L * 1080L
        )
        // 10x10000 extreme portrait: must terminate without hanging and satisfy all bounds
        try {
            val size = NativeVideoComposition.calculateVideoOutputDimensions(
                rawWidth = 10,
                rawHeight = 10_000,
                bounds = bounds
            )
            assertTrue(size.width <= 1920)
            assertTrue(size.height <= 1080)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
            assertTrue(size.width >= 2)
            assertTrue(size.height >= 2)
        } catch (e: IllegalArgumentException) {
            // Explicit failure when dimensions cannot fit alignment and bounds is also valid
            assertTrue(e.message?.contains("No supported video output size") == true)
        }
    }

    @Test
    fun testVideoOutputDimensionsNarrowCropWithMacroblockAlignment() {
        val bounds = NativeVideoComposition.EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1080,
            widthAlignment = 16,
            heightAlignment = 16,
            maxPixels = 1920L * 1080L
        )
        // 32x1920 narrow crop with 16-pixel alignment
        try {
            val size = NativeVideoComposition.calculateVideoOutputDimensions(
                rawWidth = 32,
                rawHeight = 1920,
                bounds = bounds
            )
            assertEquals(0, size.width % 16)
            assertEquals(0, size.height % 16)
            assertTrue(size.width <= 1920)
            assertTrue(size.height <= 1080)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("No supported video output size") == true)
        }
    }

    @Test
    fun testVideoOutputDimensionsExtremeNarrowCropThrowsExplicitlyWhenCannotAlign() {
        val bounds = NativeVideoComposition.EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1080,
            widthAlignment = 16,
            heightAlignment = 16,
            maxPixels = 1920L * 1080L
        )
        // 4x4000: initial scale max 1080/4000 = 0.27; 0.27 * 4 = 1.08 < alignW(16).
        // It is impossible to align to 16 without exceeding 1080 height. Must fail explicitly!
        try {
            NativeVideoComposition.calculateVideoOutputDimensions(
                rawWidth = 4,
                rawHeight = 4000,
                bounds = bounds
            )
            fail("Expected IllegalArgumentException for impossible alignment")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("No supported video output size") == true)
        }
    }
}
