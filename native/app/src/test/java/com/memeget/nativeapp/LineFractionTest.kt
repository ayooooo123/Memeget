package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineFractionTest {
    private val transcript = "So I walked in and said hello. Then I'm home honey, I'm home! And everyone laughed."

    @Test
    fun aLineFoundInTheTranscriptGivesItsPlaceInIt() {
        val fraction = lineFraction(transcript, "I'm home honey")!!
        // Normalized: "so i walked in and said hello then im home honey im home and everyone laughed" (77 chars),
        // with "im home honey" starting at 35.
        assertEquals(35f / 77f, fraction, 0.001f)
    }

    @Test
    fun aLineMatchingOnlyAtItsStartUsesTheLongestMatchingRun() {
        assertEquals(lineFraction(transcript, "I'm home honey")!!, lineFraction(transcript, "I'm home honey where are you")!!, 0f)
    }

    @Test
    fun wordsInsideOtherWordsDoNotMatch() {
        assertNull(lineFraction("the homeowner said hi", "home owner"))
    }

    @Test
    fun aLineNotSaidGivesNothing() {
        assertNull(lineFraction(transcript, "goodbye forever"))
    }
}
