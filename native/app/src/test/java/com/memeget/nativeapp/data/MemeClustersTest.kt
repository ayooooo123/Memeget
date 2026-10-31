package com.memeget.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

class MemeClustersTest {
    /** [count] copies of one picture's int8 vector. */
    private fun samePicture(count: Int): ByteArray {
        val vectors = ByteArray(count * 512)
        for (i in 0 until count) for (d in 0 until 30) vectors[i * 512 + d] = 90
        return vectors
    }

    @Test
    fun samePictureWithDifferentWordsIsOneFormatNewestFirst() {
        val formats = MemeClusters.formats(
            ids = longArrayOf(1L, 2L, 3L),
            modifiedAt = longArrayOf(100L, 300L, 200L),
            vectors = samePicture(3),
            ocrKeys = listOf("drake nah", "drake yeah", "drake smiling"),
            labels = listOf(listOf("Drake", "Rap"), listOf("drake", "Music"), listOf("Drake", "Celebration")),
        )
        assertEquals(1, formats.size)
        // "drake" is carried by 3 of 3; which member's spelling is kept doesn't matter.
        assertEquals("drake", formats[0].label.lowercase())
        assertEquals(listOf(2L, 3L, 1L), formats[0].ids)
    }

    @Test
    fun anOriginalAndItsRemixesStayTogether() {
        // A remix keeps the picture and changes the words: it joins its original's format, nothing is hidden.
        val formats = MemeClusters.formats(
            ids = longArrayOf(1L, 2L, 3L),
            modifiedAt = longArrayOf(100L, 200L, 300L),
            vectors = samePicture(3),
            ocrKeys = listOf("", "my remix", "another remix"),
            labels = List(3) { emptyList() },
        )
        assertEquals(listOf(listOf(3L, 2L, 1L)), formats.map { it.ids })
    }

    @Test
    fun picturesWithTheSameWordsAreNotAFormat() {
        for (text in listOf("exact same caption", "")) {
            val formats = MemeClusters.formats(
                ids = longArrayOf(1L, 2L, 3L),
                modifiedAt = longArrayOf(100L, 200L, 300L),
                vectors = samePicture(3),
                ocrKeys = List(3) { text },
                labels = List(3) { emptyList() },
            )
            assertTrue("text \"$text\"", formats.isEmpty())
        }
    }

    @Test
    fun aChainOfLooselySimilarPicturesOver120IsDropped() {
        val n = 121
        val vectors = ByteArray(n * 512)
        for (i in 0 until n) {
            val angle = i * 0.05
            vectors[i * 512] = round(100 * cos(angle)).toInt().toByte()
            vectors[i * 512 + 1] = round(100 * sin(angle)).toInt().toByte()
        }
        val formats = MemeClusters.formats(
            ids = LongArray(n) { it + 1L },
            modifiedAt = LongArray(n) { 1000L + it },
            vectors = vectors,
            ocrKeys = List(n) { "unique text $it" },
            labels = List(n) { listOf("format") },
        )
        assertTrue(formats.isEmpty())
    }
}
