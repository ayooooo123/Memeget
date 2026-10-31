package com.memeget.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

class MemeClustersTest {

    private fun makeVector(dim: Int = 512, block: (ByteArray) -> Unit): ByteArray {
        val bytes = ByteArray(dim)
        block(bytes)
        return bytes
    }

    @Test
    fun nearCopiesCollapseToNewestKeeper() {
        // Two memes with identical image vector and same OCR text
        val v = makeVector { bytes ->
            for (i in 0 until 20) bytes[i] = 100
        }
        val vectors = ByteArray(2 * 512)
        System.arraycopy(v, 0, vectors, 0, 512)
        System.arraycopy(v, 0, vectors, 512, 512)

        // Meme 1 is older, Meme 2 is newer
        val ids = longArrayOf(10L, 20L)
        val modifiedAt = longArrayOf(1000L, 2000L)
        val ocrKeys = listOf("distracted boyfriend", "distracted boyfriend")
        val labels = listOf(listOf("meme"), listOf("meme"))

        val groups = MemeClusters.group(
            ids = ids,
            modifiedAt = modifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )

        // Meme 10 should map to the newer keeper 20
        assertEquals(mapOf(10L to 20L), groups.duplicateOf)
        assertFalse("Keeper 20 must not be a key in duplicateOf", groups.duplicateOf.containsKey(20L))
        assertTrue("No format groups formed for duplicate pair", groups.formats.isEmpty())

        // Tie breaking on modifiedAt: higher id wins
        val tieIds = longArrayOf(30L, 40L)
        val tieModifiedAt = longArrayOf(5000L, 5000L)
        val tieGroups = MemeClusters.group(
            ids = tieIds,
            modifiedAt = tieModifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )
        assertEquals(mapOf(30L to 40L), tieGroups.duplicateOf)
    }

    @Test
    fun samePictureDifferentTextFormsFormatGroup() {
        // Three memes with identical image vector but different OCR text
        val v = makeVector { bytes ->
            for (i in 0 until 30) bytes[i] = 90
        }
        val vectors = ByteArray(3 * 512)
        for (i in 0 until 3) {
            System.arraycopy(v, 0, vectors, i * 512, 512)
        }

        val ids = longArrayOf(1L, 2L, 3L)
        val modifiedAt = longArrayOf(100L, 300L, 200L)
        val ocrKeys = listOf("drake nah", "drake yeah", "drake smiling")
        val labels = listOf(
            listOf("Drake", "Rap"),
            listOf("drake", "Music"),
            listOf("Drake", "Celebration"),
        )

        val groups = MemeClusters.group(
            ids = ids,
            modifiedAt = modifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )

        assertTrue("No near-copies when OCR differs", groups.duplicateOf.isEmpty())
        assertEquals("One format group of size 3 should form", 1, groups.formats.size)

        val format = groups.formats[0]
        // Label voting: "drake" is carried by 3/3 (>= 50%); which member's spelling is kept doesn't matter.
        assertEquals("drake", format.label.lowercase())
        // IDs sorted newest first by modified_at: 2 (300L), 3 (200L), 1 (100L)
        assertEquals(listOf(2L, 3L, 1L), format.ids)
    }

    @Test
    fun chainedComponentOver120IsDropped() {
        val n = 121
        val vectors = ByteArray(n * 512)
        val thetaStep = 0.05
        for (i in 0 until n) {
            val angle = i * thetaStep
            vectors[i * 512] = round(100 * cos(angle)).toInt().toByte()
            vectors[i * 512 + 1] = round(100 * sin(angle)).toInt().toByte()
        }

        val ids = LongArray(n) { it + 1L }
        val modifiedAt = LongArray(n) { 1000L + it }
        val ocrKeys = List(n) { "unique text $it" }
        val labels = List(n) { listOf("format") }

        val groups = MemeClusters.group(
            ids = ids,
            modifiedAt = modifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )

        assertTrue(
            "Component of size 121 (> 120) must be dropped as chained",
            groups.formats.isEmpty(),
        )
    }

    @Test
    fun picturesWithIdenticalTextAreNotAFormat() {
        // Three memes with high similarity and identical non-blank OCR text
        val v = makeVector { bytes ->
            for (i in 0 until 25) bytes[i] = 85
        }
        val vectors = ByteArray(3 * 512)
        for (i in 0 until 3) {
            System.arraycopy(v, 0, vectors, i * 512, 512)
        }

        val ids = longArrayOf(1L, 2L, 3L)
        val modifiedAt = longArrayOf(100L, 200L, 300L)
        val ocrKeys = listOf("exact same caption", "exact same caption", "exact same caption")
        val labels = listOf(listOf("meme"), listOf("meme"), listOf("meme"))

        val groups = MemeClusters.group(
            ids = ids,
            modifiedAt = modifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )

        // These collapse into near-copies, not format groups
        assertEquals(mapOf(1L to 3L, 2L to 3L), groups.duplicateOf)
        assertTrue("Pictures with identical text must not form a format group", groups.formats.isEmpty())
    }

    @Test
    fun picturesWithBlankTextAreNotAFormat() {
        // Three memes with high similarity and blank OCR
        val v = makeVector { bytes ->
            for (i in 0 until 25) bytes[i] = 85
        }
        val vectors = ByteArray(3 * 512)
        for (i in 0 until 3) {
            System.arraycopy(v, 0, vectors, i * 512, 512)
        }

        val ids = longArrayOf(10L, 20L, 30L)
        val modifiedAt = longArrayOf(10L, 20L, 30L)
        val ocrKeys = listOf("", "", "")
        val labels = List(3) { emptyList<String>() }

        val groups = MemeClusters.group(
            ids = ids,
            modifiedAt = modifiedAt,
            vectors = vectors,
            ocrKeys = ocrKeys,
            labels = labels,
        )

        // Blank text + high cosine collapses as near-copies
        assertEquals(mapOf(10L to 30L, 20L to 30L), groups.duplicateOf)
        assertTrue("Pictures with blank text must not form a format group", groups.formats.isEmpty())
    }
}
