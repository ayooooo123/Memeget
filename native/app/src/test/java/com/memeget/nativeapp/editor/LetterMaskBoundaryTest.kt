package com.memeget.nativeapp.editor

import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A box that ends at the fill must include the selected glyph's outline, not ink on the next line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LetterMaskBoundaryTest {
    @Test fun maskKeepsTheLineOneBlankRowBelowTheSelectionOriginal() {
        val mask = maskWithLineBelow(blankRows = 1)
        assertSelectionAndOwnEdgeMasked(mask)
        assertLowerLineOutsideHole(mask, top = UPPER_TOP + SIZE + 1)
        assertGapRowsOriginal(mask, until = UPPER_TOP + SIZE + 1)
    }

    @Test fun maskKeepsTheLineTwoBlankRowsBelowTheSelectionOriginal() {
        val mask = maskWithLineBelow(blankRows = 2)
        assertSelectionAndOwnEdgeMasked(mask)
        assertLowerLineOutsideHole(mask, top = UPPER_TOP + SIZE + 2)
        assertGapRowsOriginal(mask, until = UPPER_TOP + SIZE + 2)
    }

    /** A tight OCR box around the fill must still remove the stroke that lies just outside it. */
    @Test fun maskKeepsTheSelectedGlyphsOutlineOnATightSelection() {
        val pixels = IntArray(W * H) { BACKGROUND }
        drawH(pixels, UPPER_LEFT, UPPER_TOP, OUTLINE, grow = OUTLINE_WIDTH)
        drawH(pixels, UPPER_LEFT, UPPER_TOP, INK)
        val mask = checkNotNull(LetterMask.find(pixels, W, H, selection())) {
            "The outlined line must still be found"
        }
        assertEquals(INK and 0xFFFFFF, mask.color and 0xFFFFFF)
        assertEquals(OUTLINE and 0xFFFFFF, checkNotNull(mask.edgeColor) and 0xFFFFFF)
        val row = UPPER_TOP + SIZE / 2
        val firstOutside = UPPER_LEFT - OUTLINE_WIDTH
        assertTrue("The outline just outside the tight box must stay masked",
            mask.hole[row * W + firstOutside] != 0.toByte())
        assertTrue("The mask must stop at the outline's outer edge",
            mask.hole[row * W + firstOutside - 1] == 0.toByte())
    }

    @Test fun aRealOutlineRemainsDistinguishableOnASmoothGradient() {
        val pixels = IntArray(W * H) { Color.rgb(20 + 2 * (it % W), 50, 100) }
        drawH(pixels, UPPER_LEFT, UPPER_TOP, OUTLINE, grow = 3)
        drawH(pixels, UPPER_LEFT, UPPER_TOP, INK)
        val mask = checkNotNull(LetterMask.find(pixels, W, H, selection()))
        assertEquals(OUTLINE and 0xFFFFFF, checkNotNull(mask.edgeColor) and 0xFFFFFF)
        assertTrue("A real border must still be drawn on the new text", mask.outlinePx in 2..4)
        assertTrue("The old border must go", mask.hole[(UPPER_TOP + SIZE / 2) * W + UPPER_LEFT - 2] != 0.toByte())
    }

    @Test fun aNearbyLineDoesNotLeaveTheSelectedOutlineOnThePicture() {
        val pixels = IntArray(W * H) { BACKGROUND }
        val lowerTop = UPPER_TOP + SIZE + 6
        drawH(pixels, UPPER_LEFT, UPPER_TOP, OUTLINE, grow = OUTLINE_WIDTH)
        drawH(pixels, UPPER_LEFT, UPPER_TOP, INK)
        drawH(pixels, LOWER_LEFT, lowerTop, INK)
        val mask = checkNotNull(LetterMask.find(pixels, W, H, selection()))
        val nearEdge = (UPPER_TOP + SIZE + 2) * W + UPPER_LEFT + 1
        assertEquals(OUTLINE, pixels[nearEdge])
        assertTrue("The selected outline nearer its own letter must be repaired", mask.hole[nearEdge] != 0.toByte())
        assertLowerLineOutsideHole(mask, lowerTop)
    }

    @Test fun aDenseLineBelowDoesNotHideTheInkOfASparseSelectedLine() {
        val w = 108
        val h = 36
        val box = Rect(6, 6, 102, 30)
        val pixels = IntArray(w * h) { BACKGROUND }
        for (y in 7 until 29) for (x in 9 until 99) {
            if (x < 12 || x >= 96) pixels[y * w + x] = INK
        }
        for (withNeighbour in listOf(false, true)) {
            if (withNeighbour) for (y in 31 until h) for (x in 8 until 100) {
                if ((x - 8) % 18 < 13) pixels[y * w + x] = INK
            }
            val mask = LetterMask.find(pixels, w, h, box)
            assertTrue("The same sparse line must be recognizable with a neighbouring line: $withNeighbour", mask != null)
            assertEquals(INK and 0xffffff, mask!!.color and 0xffffff)
            assertTrue(mask.hole[18 * w + 10] != 0.toByte())
            assertTrue(mask.hole[18 * w + 97] != 0.toByte())
        }
    }

    private fun maskWithLineBelow(blankRows: Int): LetterMask {
        val pixels = IntArray(W * H) { BACKGROUND }
        drawH(pixels, UPPER_LEFT, UPPER_TOP, INK)
        drawH(pixels, LOWER_LEFT, UPPER_TOP + SIZE + blankRows, INK)
        return checkNotNull(LetterMask.find(pixels, W, H, selection())) {
            "The selected line must still be found"
        }
    }

    private fun selection() = Rect(UPPER_LEFT, UPPER_TOP, UPPER_LEFT + SIZE, UPPER_TOP + SIZE)

    /** Every pixel of the chosen line is in the mask, and the hole still reaches just past the box. */
    private fun assertSelectionAndOwnEdgeMasked(mask: LetterMask) {
        assertEquals(INK and 0xFFFFFF, mask.color and 0xFFFFFF)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!hInk(x, y)) continue
            assertTrue("Selected line pixel (${UPPER_LEFT + x},${UPPER_TOP + y}) must be masked",
                mask.hole[(UPPER_TOP + y) * W + UPPER_LEFT + x] != 0.toByte())
        }
        assertTrue("The glyph's edge just outside the box must stay masked",
            mask.hole[(UPPER_TOP - 1) * W + UPPER_LEFT + 1] != 0.toByte())
    }

    /** No pixel of the unselected line may be replaced by the new caption. */
    private fun assertLowerLineOutsideHole(mask: LetterMask, top: Int) {
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (!hInk(x, y)) continue
            assertTrue("Unselected line pixel (${LOWER_LEFT + x},${top + y}) must stay original",
                mask.hole[(top + y) * W + LOWER_LEFT + x] == 0.toByte())
        }
    }

    /** The background between the two lines must not be replaced either. */
    private fun assertGapRowsOriginal(mask: LetterMask, until: Int) {
        for (y in UPPER_TOP + SIZE until until) for (x in 0 until W) {
            assertTrue("Gap pixel ($x,$y) must stay original", mask.hole[y * W + x] == 0.toByte())
        }
    }

    /** The local H of a [SIZE]-pixel square: two stems and a crossbar, 160 of the 256 pixels. */
    private fun hInk(x: Int, y: Int): Boolean {
        if (x in 0 until STEM || x in SIZE - STEM until SIZE) return y in 0 until SIZE
        val barTop = (SIZE - BAR) / 2
        return y in barTop until barTop + BAR
    }

    private fun drawH(pixels: IntArray, left: Int, top: Int, color: Int, grow: Int = 0) {
        val barTop = (SIZE - BAR) / 2
        rect(pixels, left - grow, top - grow, left + STEM + grow, top + SIZE + grow, color)
        rect(pixels, left + SIZE - STEM - grow, top - grow, left + SIZE + grow, top + SIZE + grow, color)
        rect(pixels, left + STEM - grow, top + barTop - grow,
            left + SIZE - STEM + grow, top + barTop + BAR + grow, color)
    }

    private fun rect(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top until bottom) for (x in left until right) {
            if (x in 0 until W && y in 0 until H) pixels[y * W + x] = color
        }
    }

    private companion object {
        const val W = 48
        const val H = 48
        const val SIZE = 16
        const val STEM = 4
        const val BAR = 4
        const val UPPER_LEFT = 8
        const val UPPER_TOP = 8
        const val LOWER_LEFT = 8
        const val OUTLINE_WIDTH = 4

        val BACKGROUND = Color.rgb(36, 64, 96)
        val INK = Color.rgb(255, 255, 0)
        val OUTLINE = Color.rgb(255, 0, 0)
    }
}
