package com.memeget.nativeapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPostProcessorTest {

    @Test
    fun testFiltersLowConfidenceAndEmptyText() {
        val raw = arrayOf(
            RawOcrDetection("Valid", 0.85f, 0.1f, 0.1f, 0.2f, 0.05f),
            RawOcrDetection("LowConf", 0.25f, 0.1f, 0.2f, 0.2f, 0.05f), // < 0.3
            RawOcrDetection("   ", 0.90f, 0.1f, 0.3f, 0.2f, 0.05f), // blank
            RawOcrDetection("ZeroW", 0.90f, 0.1f, 0.4f, 0.0f, 0.05f), // width=0
        )

        val regions = OcrPostProcessor.toReadingOrderRegions(raw)

        assertEquals(1, regions.size)
        assertEquals("Valid", regions[0].text)
        assertEquals(0.85f, regions[0].confidence, 0.001f)
    }

    @Test
    fun testReadingOrderSorting() {
        // Two lines:
        // Line 1 (y ≈ 0.1): "World" (x=0.5), "Hello" (x=0.1)
        // Line 2 (y ≈ 0.3): "Meme" (x=0.2)
        val raw = arrayOf(
            RawOcrDetection("World", 0.9f, 0.5f, 0.10f, 0.2f, 0.05f),
            RawOcrDetection("Meme", 0.9f, 0.2f, 0.30f, 0.2f, 0.05f),
            RawOcrDetection("Hello", 0.9f, 0.1f, 0.11f, 0.2f, 0.05f),
        )

        val regions = OcrPostProcessor.toReadingOrderRegions(raw)

        assertEquals(3, regions.size)
        // Line 1: left to right
        assertEquals("Hello", regions[0].text)
        assertEquals("World", regions[1].text)
        // Line 2
        assertEquals("Meme", regions[2].text)
    }

    @Test
    fun testSameLineVerticalOverlap() {
        // Two boxes that overlap vertically by > 50% of the shorter box:
        // box 1: y=0.10, h=0.06 (bottom 0.16)
        // box 2: y=0.12, h=0.06 (bottom 0.18, overlap = 0.16 - 0.12 = 0.04 >= 0.03 = 50%)
        val raw = arrayOf(
            RawOcrDetection("B", 0.8f, 0.4f, 0.12f, 0.1f, 0.06f),
            RawOcrDetection("A", 0.8f, 0.2f, 0.10f, 0.1f, 0.06f),
        )

        val regions = OcrPostProcessor.toReadingOrderRegions(raw)

        assertEquals(2, regions.size)
        assertEquals("A", regions[0].text)
        assertEquals("B", regions[1].text)
    }
}
