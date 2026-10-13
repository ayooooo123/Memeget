package com.memeget.nativeapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutSelectorTest {

    @Test
    fun testPackMaskBitsRowMajorMsbFirst() {
        // 8 pixels: 1, 0, 0, 0, 0, 0, 0, 1 -> 0b10000001 = 0x81 (-127 in signed byte)
        val mask = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 1)
        val packed = CutoutSelector.packMaskBits(mask)

        assertEquals(1, packed.size)
        assertEquals(0x81.toByte(), packed[0])
    }

    @Test
    fun testPackMaskBitsOddLength() {
        // 9 pixels: 1, 1, 0, 0, 0, 0, 0, 0, 1
        // byte 0: 1, 1, 0, 0, 0, 0, 0, 0 -> 0b11000000 = 0xC0
        // byte 1: 1, 0, 0, 0, 0, 0, 0, 0 -> 0b10000000 = 0x80
        val mask = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 1)
        val packed = CutoutSelector.packMaskBits(mask)

        assertEquals(2, packed.size)
        assertEquals(0xC0.toByte(), packed[0])
        assertEquals(0x80.toByte(), packed[1])
    }

    @Test
    fun testDropSpeckleUnderMinCoverage() {
        // Frame 1000 x 1000 = 1,000,000 pixels. MIN_COVERAGE = 0.001 (1000 pixels)
        // Instance with only 10 pixels -> should be dropped
        val tinyMask = ByteArray(10) { 1 }
        val instance = RawFastSamInstance(
            x = 100, y = 100, width = 5, height = 2, score = 0.95f, mask = tinyMask
        )

        val result = CutoutSelector.selectSubjectMasks(
            instances = arrayOf(instance),
            frameWidth = 1000,
            frameHeight = 1000,
        )

        assertTrue(result.subjects.isEmpty())
        assertEquals(0, result.dropped)
    }

    @Test
    fun testDropBackdropTouchingEdges() {
        // Frame 1000 x 1000. Backdrop touches top (y=0), left (x=0), right (x+w >= 990)
        // area = 300 x 1000 = 300,000 (30% coverage > 25% threshold)
        val w = 1000
        val h = 300
        val mask = ByteArray(w * h) { 1 }
        val backdrop = RawFastSamInstance(
            x = 0, y = 0, width = w, height = h, score = 0.90f, mask = mask
        )

        val result = CutoutSelector.selectSubjectMasks(
            instances = arrayOf(backdrop),
            frameWidth = 1000,
            frameHeight = 1000,
        )

        assertTrue(result.subjects.isEmpty())
    }

    @Test
    fun testSelectValidSubjectsAndOverlapSuppression() {
        // Valid subject 1: centered 200x200 (4% coverage)
        val w1 = 200
        val h1 = 200
        val mask1 = ByteArray(w1 * h1) { 1 }
        val subject1 = RawFastSamInstance(
            x = 300, y = 300, width = w1, height = h1, score = 0.85f, mask = mask1
        )

        // Duplicate/overlapping subject 2: slightly smaller box inside subject 1
        val w2 = 180
        val h2 = 180
        val mask2 = ByteArray(w2 * h2) { 1 }
        val subject2 = RawFastSamInstance(
            x = 310, y = 310, width = w2, height = h2, score = 0.80f, mask = mask2
        )

        // Separate subject 3: at 600, 600
        val w3 = 100
        val h3 = 100
        val mask3 = ByteArray(w3 * h3) { 1 }
        val subject3 = RawFastSamInstance(
            x = 600, y = 600, width = w3, height = h3, score = 0.75f, mask = mask3
        )

        val result = CutoutSelector.selectSubjectMasks(
            instances = arrayOf(subject1, subject2, subject3),
            frameWidth = 1000,
            frameHeight = 1000,
        )

        // subject2 is suppressed by subject1
        assertEquals(2, result.subjects.size)
        assertEquals(300, result.subjects[0].x)
        assertEquals(600, result.subjects[1].x)
    }

    @Test
    fun testCapMaxSubjects() {
        val instances = (0 until 12).map { i ->
            val w = 50
            val h = 50
            RawFastSamInstance(
                x = (i % 4) * 100 + 50,
                y = (i / 4) * 100 + 50,
                width = w,
                height = h,
                score = 0.9f - (i * 0.01f),
                mask = ByteArray(w * h) { 1 }
            )
        }.toTypedArray()

        val result = CutoutSelector.selectSubjectMasks(
            instances = instances,
            frameWidth = 1000,
            frameHeight = 1000,
            maxSubjects = 8,
        )

        assertEquals(8, result.subjects.size)
        assertEquals(4, result.dropped)
    }
}
