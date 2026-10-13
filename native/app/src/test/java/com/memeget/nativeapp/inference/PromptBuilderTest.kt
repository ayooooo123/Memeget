package com.memeget.nativeapp.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun testCleanTranscriptStripsMusicAndBrackets() {
        val raw = "[Music] Hello world ♪ (laughter) this is a test [Applause] ♫"
        val cleaned = PromptBuilder.cleanTranscript(raw)

        assertEquals("Hello world this is a test", cleaned)
    }

    @Test
    fun testCleanTranscriptEmptyOnSilentDecorations() {
        val raw = "[Music] ♪ ♪ (cough) [Silence]"
        val cleaned = PromptBuilder.cleanTranscript(raw)

        assertEquals("", cleaned)
    }
}
