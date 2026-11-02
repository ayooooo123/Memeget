package com.memeget.nativeapp.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LogoMaskTest {
    /** Each source pixel lands where the project's own point mapping sends its centre, for every orientation. */
    @Test
    fun orientedMaskFollowsTheProjectsSourceToWorkingMapping() {
        val w = 5
        val h = 3
        for (rotation in QuarterRotation.entries) for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
            val base = BaseTransform(rotation = rotation, flipX = flipX, flipY = flipY)
            for (sy in 0 until h) for (sx in 0 until w) {
                val mask = ByteArray(w * h).also { it[sy * w + sx] = 1 }
                val (out, ow, oh) = LogoMask.orient(mask, w, h, base)
                val centre = ProjectCore.sourceToWorking(NormalizedPoint((sx + 0.5f) / w, (sy + 0.5f) / h), base)
                val expected = (centre.y * oh).toInt() * ow + (centre.x * ow).toInt()
                assertEquals("$base at ($sx,$sy)", expected, out.indexOfFirst { it == 1.toByte() })

                val (back, bw, bh) = LogoMask.unorient(out, ow, oh, base)
                assertEquals(w, bw)
                assertEquals(h, bh)
                assertArrayEquals(mask, back)
            }
        }
    }

    /** Cropping through a masked cover keeps its whole rectangle (the mask is stretched over it) and every keyframe. */
    @Test
    fun croppingThroughAMaskedCoverKeepsTheMaskOnTheSamePixels() {
        val full = BaseTransform()
        val leftHalf = BaseTransform(crop = NormalizedRect(0f, 0f, 0.5f, 1f), outputAspect = "free")
        val cover = MemeEditLayer.Cover(
            id = "logo",
            rect = NormalizedRect(0.4f, 0.4f, 0.2f, 0.1f),
            mode = "remove",
            maskUri = "file:///mask.png",
            corrections = listOf(
                CoverCorrectionKeyframe(0L, NormalizedRect(0.4f, 0.4f, 0.2f, 0.1f), "remove", "hold"),
                CoverCorrectionKeyframe(1_000_000L, NormalizedRect(0.7f, 0.8f, 0.2f, 0.1f), "remove", "hold")
            )
        )
        val cropped = ProjectCore.remapProjectLayers(listOf(cover), full, leftHalf, 1000, 1000).single() as MemeEditLayer.Cover

        // Half the rectangle now lies past the right edge; it is not cut down to the half that remains.
        assertEquals(0.8f, cropped.rect.x, 1e-5f)
        assertEquals(0.4f, cropped.rect.width, 1e-5f)
        // The rectangle's far corner still lands on the same source pixel, so the mask does too.
        val corner = ProjectCore.workingToSource(NormalizedPoint(cropped.rect.x + cropped.rect.width, cropped.rect.y), leftHalf)
        assertEquals(0.6f, corner.x, 1e-5f)
        // A keyframe wholly outside the crop is kept, so the run after it doesn't inherit the one before.
        assertEquals(2, cropped.corrections.size)

        val restored = ProjectCore.remapProjectLayers(listOf(cropped), leftHalf, full, 1000, 1000).single() as MemeEditLayer.Cover
        assertEquals(cover.rect.x, restored.rect.x, 1e-5f)
        assertEquals(cover.rect.width, restored.rect.width, 1e-5f)
        assertEquals(cover.corrections[1].rect.x, restored.corrections[1].rect.x, 1e-5f)
    }

    /** The running-count square filter agrees with checking every neighbour, edges included, at any radius. */
    @Test
    fun squareFilterMatchesTheDirectNeighbourhoodCheck() {
        val random = java.util.Random(7)
        for (trial in 0 until 30) {
            val w = 1 + random.nextInt(23)
            val h = 1 + random.nextInt(19)
            val radius = random.nextInt(12)
            val mask = ByteArray(w * h) { if (random.nextInt(5) == 0) 0xff.toByte() else 0 }
            for (all in listOf(false, true)) {
                val expected = ByteArray(w * h) { i ->
                    val x = i % w
                    val y = i / w
                    var any = false
                    var every = true
                    for (ny in maxOf(0, y - radius)..minOf(h - 1, y + radius)) {
                        for (nx in maxOf(0, x - radius)..minOf(w - 1, x + radius)) {
                            val set = mask[ny * w + nx].toInt() != 0
                            any = any || set
                            every = every && set
                        }
                    }
                    if (if (all) every else any) 0xff.toByte() else 0
                }
                assertArrayEquals("trial $trial all=$all", expected, LogoMask.squareFilter(mask, w, h, radius, all))
            }
        }
    }
}
