package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoPatchesTest {
    @Test
    fun variableFrameRateTimesPickTheFrameOnScreen() {
        // A long first frame, then frames 10 ms apart: slack taken from the first interval jumped two frames ahead.
        val times = longArrayOf(0, 100_000, 110_000, 120_000)
        assertEquals(1, LogoPatches.frameAt(times, 100_000))
        assertEquals(1, LogoPatches.frameAt(times, 105_000))
        assertEquals(2, LogoPatches.frameAt(times, 110_000))
        assertEquals(0, LogoPatches.frameAt(times, 60_000))
        assertEquals(3, LogoPatches.frameAt(times, 500_000))
    }

    @Test
    fun aTimeRoundedJustShortOfAFrameIsThatFrame() {
        val times = longArrayOf(0, 33_333, 66_666, 100_000)
        assertEquals(2, LogoPatches.frameAt(times, 66_665))
        assertEquals(1, LogoPatches.frameAt(times, 66_000))
        // The slack never reaches past a quarter of a short interval.
        val tight = longArrayOf(0, 100_000, 100_200)
        assertEquals(1, LogoPatches.frameAt(tight, 100_140))
        assertEquals(2, LogoPatches.frameAt(tight, 100_160))
    }
}
