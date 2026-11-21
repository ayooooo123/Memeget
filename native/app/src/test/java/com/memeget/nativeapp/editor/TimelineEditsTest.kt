package com.memeget.nativeapp.editor

import com.memeget.nativeapp.editor.TimelineEdits.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineEditsTest {
    private val s = 1_000_000L
    private val twoRanges = listOf(TimeRangeUs(0L, 2 * s), TimeRangeUs(4 * s, 7 * s))

    @Test
    fun rangeUnderThePlayheadIgnoresCutsAndKeepsTheVeryEnd() {
        assertEquals(0, TimelineEdits.rangeIndexAt(twoRanges, 0L))
        assertNull(TimelineEdits.rangeIndexAt(twoRanges, 2 * s)) // first range's end is where the cut starts
        assertNull(TimelineEdits.rangeIndexAt(twoRanges, 3 * s))
        assertEquals(1, TimelineEdits.rangeIndexAt(twoRanges, 7 * s)) // the last frame of the video
    }

    @Test
    fun splitCutsOnlyTheRangeUnderThePlayhead() {
        assertEquals(
            listOf(TimeRangeUs(0L, 2 * s), TimeRangeUs(4 * s, 5 * s), TimeRangeUs(5 * s, 7 * s)),
            TimelineEdits.splitAt(twoRanges, 5 * s)
        )
        assertNull(TimelineEdits.splitAt(twoRanges, 3 * s))
        assertNull(TimelineEdits.splitAt(twoRanges, 4 * s + TimelineEdits.SPLIT_MARGIN_US - 1))
    }

    @Test
    fun deleteRemovesTheRangeUnderThePlayheadButNeverTheLastOne() {
        assertEquals(listOf(TimeRangeUs(0L, 2 * s)), TimelineEdits.deleteAt(twoRanges, 6 * s))
        assertNull(TimelineEdits.deleteAt(twoRanges, 3 * s))
        assertNull(TimelineEdits.deleteAt(listOf(TimeRangeUs(0L, 7 * s)), s))
    }

    @Test
    fun trimmedEdgesStopAtNeighboursAndMinimumLength() {
        // Dragging the second range's start back past the first range stops where the first one ends.
        assertEquals(
            listOf(TimeRangeUs(0L, 2 * s), TimeRangeUs(2 * s, 7 * s)),
            TimelineEdits.trimEdge(twoRanges, 1, Edge.START, s, 7 * s)
        )
        // Dragging the first range's end past its start leaves the shortest range allowed.
        assertEquals(
            TimeRangeUs(0L, TimelineEdits.MIN_RANGE_US),
            TimelineEdits.trimEdge(twoRanges, 0, Edge.END, -s, 7 * s)[0]
        )
        // The last range's end can grow to the source's end and no further.
        assertEquals(TimeRangeUs(4 * s, 8 * s), TimelineEdits.trimEdge(twoRanges, 1, Edge.END, 9 * s, 8 * s)[1])
    }

    @Test
    fun joiningClosesTheCutBetweenTwoRanges() {
        assertEquals(listOf(TimeRangeUs(0L, 7 * s)), TimelineEdits.joinWithNext(twoRanges, 0))
        assertEquals(twoRanges, TimelineEdits.joinWithNext(twoRanges, 1))
    }

    @Test
    fun layerTimingMovesTheOtherEndOutOfTheWay() {
        val text = MemeEditLayer.Text(id = "t", text = "hi", active = TimeRangeUs(s, 3 * s))

        assertEquals(TimeRangeUs(2 * s, 3 * s), TimelineEdits.setLayerTiming(text, Edge.START, 2 * s, 10 * s).active)
        assertEquals(
            TimeRangeUs(5 * s, 5 * s + TimelineEdits.MIN_RANGE_US),
            TimelineEdits.setLayerTiming(text, Edge.START, 5 * s, 10 * s).active
        )
        assertEquals(TimeRangeUs(s, s + s / 2), TimelineEdits.setLayerTiming(text, Edge.END, s + s / 2, 10 * s).active)
        assertEquals(
            TimeRangeUs(s / 2 - TimelineEdits.MIN_RANGE_US, s / 2),
            TimelineEdits.setLayerTiming(text, Edge.END, s / 2, 10 * s).active
        )
    }

    @Test
    fun layerTimingThatCoversTheWholeVideoIsNoTimingAtAll() {
        val text = MemeEditLayer.Text(id = "t", text = "hi", active = TimeRangeUs(s, 10 * s))

        assertNull(TimelineEdits.setLayerTiming(text, Edge.START, 0L, 10 * s).active)
    }
}
