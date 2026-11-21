package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawToolCoreTest {

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        DrawElement(shape = DrawShape.RECTANGLE, points = listOf(NormalizedPoint(x0, y0), NormalizedPoint(x1, y1)))

    @Test
    fun hitTestPicksTopmostAndMissesOutsidePadding() {
        val elements = listOf(rect(0.1f, 0.1f, 0.5f, 0.5f), rect(0.3f, 0.3f, 0.7f, 0.7f))
        assertEquals(1, DrawToolCore.hitTestElement(elements, NormalizedPoint(0.4f, 0.4f), 0f, 0f))
        assertEquals(0, DrawToolCore.hitTestElement(elements, NormalizedPoint(0.15f, 0.15f), 0f, 0f))
        assertNull(DrawToolCore.hitTestElement(elements, NormalizedPoint(0.8f, 0.8f), 0.05f, 0.05f))
        assertEquals(1, DrawToolCore.hitTestElement(elements, NormalizedPoint(0.74f, 0.74f), 0.05f, 0.05f))
    }

    @Test
    fun moveClampsBoundsToCanvas() {
        val moved = DrawToolCore.moveElement(rect(0.6f, 0.2f, 0.8f, 0.4f), 0.5f, -0.5f)
        assertEquals(0.8f, moved.points[0].x, 0.0001f)
        assertEquals(1f, moved.points[1].x, 0.0001f)
        assertEquals(0f, moved.points[0].y, 0.0001f)
        assertEquals(0.2f, moved.points[1].y, 0.0001f)
    }

    @Test
    fun resizeScalesFromTopLeftAndClamps() {
        val resized = DrawToolCore.resizeElement(rect(0.2f, 0.2f, 0.4f, 0.4f), 0.4f, 5f)
        assertEquals(0.2f, resized.points[0].x, 0.0001f)
        assertEquals(0.6f, resized.points[1].x, 0.0001f)
        assertEquals(1f, resized.points[1].y, 0.0001f)

        val tiny = DrawToolCore.resizeElement(rect(0.2f, 0.2f, 0.4f, 0.4f), -1f, 0f)
        assertEquals(0.22f, tiny.points[1].x, 0.0001f)
    }

    @Test
    fun resizeKeepsZeroExtentAxisOfStraightLine() {
        val line = DrawElement(shape = DrawShape.LINE, points = listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.3f, 0.5f)))
        val resized = DrawToolCore.resizeElement(line, 0.4f, 0.3f)
        assertEquals(0.5f, resized.points[0].y, 0.0001f)
        assertEquals(0.5f, resized.points[1].y, 0.0001f)
        assertEquals(0.5f, resized.points[1].x, 0.0001f)
    }
}
