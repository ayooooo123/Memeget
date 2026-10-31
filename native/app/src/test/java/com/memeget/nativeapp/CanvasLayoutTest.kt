package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CanvasLayoutTest {
    private val width = 800f
    private val rowHeight = 150f
    private val gap = 6f

    private fun randomAspects(count: Int, seed: Int) = Random(seed).let { rng -> List(count) { 0.3f + rng.nextFloat() * 3.5f } }

    @Test
    fun everyRowSpansTheBoardAndTilesKeepTheirRowsHeight() {
        val board = layoutCanvasBoard(randomAspects(300, 1), width, rowHeight, gap, complete = false)
        for (row in 0 until board.rowCount) {
            val end = if (row + 1 < board.rowCount) board.rowStart[row + 1] else board.tiles.size
            val tiles = board.tiles.subList(board.rowStart[row], end)
            assertEquals(0f, tiles.first().x, 0.01f)
            assertEquals(width, tiles.last().right, 0.01f)
            tiles.zipWithNext().forEach { (a, b) -> assertEquals(gap, b.x - a.right, 0.01f) }
            tiles.forEach { assertEquals(board.rowBottom[row] - board.rowTop[row], it.height, 0.01f) }
        }
    }

    @Test
    fun appendingItemsNeverMovesPlacedTiles() {
        val aspects = randomAspects(400, 2)
        val before = layoutCanvasBoard(aspects.subList(0, 137), width, rowHeight, gap, complete = false)
        val after = layoutCanvasBoard(aspects, width, rowHeight, gap, complete = false)
        assertTrue(before.tiles.isNotEmpty())
        before.tiles.forEachIndexed { i, tile ->
            val same = after.tiles[i]
            assertEquals(tile.index, same.index)
            assertEquals(tile.x, same.x, 0f)
            assertEquals(tile.y, same.y, 0f)
            assertEquals(tile.width, same.width, 0f)
        }
    }

    @Test
    fun shortLastRowWaitsUntilTheListIsComplete() {
        // Seven squares: the first five make a row; the last two fill 306 px of an 800 px row, not a full row.
        val aspects = List(7) { 1f }
        val pending = layoutCanvasBoard(aspects, width, rowHeight, gap, complete = false)
        val done = layoutCanvasBoard(aspects, width, rowHeight, gap, complete = true)
        assertTrue(pending.tiles.size < aspects.size)
        assertEquals(aspects.size, done.tiles.size)
        assertEquals(rowHeight, done.tiles.last().height, 0f)
    }

    @Test
    fun extremeAspectsCannotOwnARow() {
        // A 20:1 banner is clamped to 3:1, so it shares its row instead of shrinking it to a sliver.
        val board = layoutCanvasBoard(listOf(20f, 1f, 1f, 1f), width, rowHeight, gap, complete = true)
        assertTrue(board.tiles.first().height > rowHeight * 0.6f)
    }

    @Test
    fun tilesBetweenReturnsOnlyOverlappingRows() {
        val board = layoutCanvasBoard(randomAspects(300, 3), width, rowHeight, gap, complete = false)
        val top = board.rowTop[10] + 1f
        val bottom = board.rowTop[14] + 1f
        val range = board.tilesBetween(top, bottom)
        assertEquals(board.rowStart[10], range.first)
        assertEquals(board.rowStart[15] - 1, range.last)
        assertTrue(board.tilesBetween(board.height + 10f, board.height + 500f).isEmpty())
    }
}
