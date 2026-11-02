package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoTrackPlannerTest {
    private val absent = LogoTrackPlanner.Sighting(false)
    private val topLeft = LogoTrackPlanner.Sighting(true, 10f, 12f)
    private val bottomRight = LogoTrackPlanner.Sighting(true, 300f, 580f)
    private val same = { a: LogoTrackPlanner.Sighting, b: LogoTrackPlanner.Sighting ->
        a.present == b.present && (!a.present || (kotlin.math.abs(a.x - b.x) <= 2f && kotlin.math.abs(a.y - b.y) <= 2f))
    }

    /** Logo absent for 13 frames, top-left until frame 160, bottom-right after; the outro hides it again at 290. */
    private fun truth(frame: Int) = when {
        frame < 13 -> absent
        frame <= 160 -> topLeft.copy(x = topLeft.x + (frame % 2)) // compression jitter stays one spot
        frame < 290 -> bottomRight
        else -> absent
    }

    @Test
    fun aBriefJumpBetweenTwoMatchingFramesGetsItsOwnRun() {
        // The case a 7-frame sampling stride missed: frames 0 and 7 agree, 2–4 show the logo elsewhere.
        val frames = listOf(topLeft, topLeft, bottomRight, bottomRight, bottomRight, topLeft, topLeft, topLeft)
        val segments = LogoTrackPlanner.segments(frames, same)
        assertEquals(listOf(0, 2, 5), segments.map { it.startFrame })
        assertEquals(listOf(topLeft, bottomRight, topLeft), segments.map { it.sighting })

        val oneFrame = LogoTrackPlanner.segments(listOf(absent, absent, topLeft, absent), same)
        assertEquals(listOf(0, 2, 3), oneFrame.map { it.startFrame })
    }

    @Test
    fun framesTheDecoderSkippedContinueTheRunBeforeThem() {
        val segments = LogoTrackPlanner.segments(listOf(null, topLeft, null, null, bottomRight, null), same)
        assertEquals(listOf(0, 1, 4), segments.map { it.startFrame })
        assertEquals(listOf(false, true, true), segments.map { it.sighting.present })
    }

    @Test
    fun holdKeyframesSwitchRectanglesBetweenTheTwoFramesOfAJump() {
        val frameUs = 33_333L
        val times = LongArray(300) { it * frameUs }
        val segments = LogoTrackPlanner.segments(List(300) { truth(it) }, same)
        assertEquals(listOf(0, 13, 161, 290), segments.map { it.startFrame })
        val initial = NormalizedRect(0.4f, 0.4f, 0.2f, 0.1f)
        val corrections = LogoTrackPlanner.corrections(segments, times, initial) { s ->
            NormalizedRect(s.x / 360f, s.y / 640f, 0.2f, 0.05f)
        }

        fun at(frame: Int) = ProjectCore.interpolateCoverCorrections(corrections, times[frame])!!
        assertEquals("off", at(12).second)
        assertEquals("remove", at(13).second)
        assertEquals(11f / 360f, at(160).first.x, 1e-4f) // the run's first sighting (frame 13, jittered by one)
        assertEquals(300f / 360f, at(161).first.x, 1e-4f)
        assertEquals("remove", at(289).second)
        assertEquals("off", at(290).second)
        // The outro keeps the last rectangle but draws nothing.
        assertEquals(300f / 360f, at(299).first.x, 1e-4f)
    }
}
