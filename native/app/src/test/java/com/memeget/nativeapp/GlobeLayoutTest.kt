package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeLayoutTest {
    private fun cellOf(grid: GlobeGrid, item: Int) = grid.cells.indexOf(item)

    private fun distanceSq(grid: GlobeGrid, item: Int): Int {
        val cell = cellOf(grid, item)
        val dx = cell % grid.cols - grid.focusCol
        val dy = cell / grid.cols - grid.focusRow
        return dx * dx + dy * dy
    }

    @Test
    fun bestMatchSitsOnTheFocalCellAndRelevanceFallsOffOutward() {
        val grid = layoutRankedGlobeGrid(2344)
        assertEquals(grid.focusRow * grid.cols + grid.focusCol, cellOf(grid, 0))
        for (item in 1 until 2344) {
            assertTrue("item $item sits nearer the focus than item ${item - 1}", distanceSq(grid, item) >= distanceSq(grid, item - 1))
        }
    }

    @Test
    fun everyResultHasExactlyOneCell() {
        for (count in listOf(1, 7, 8, 90, 2344)) {
            val grid = layoutRankedGlobeGrid(count)
            val placed = grid.cells.filter { it >= 0 }
            assertEquals(count, placed.size)
            assertEquals((0 until count).toSet(), placed.toSet())
        }
    }
}
