package com.memeget.nativeapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun descriptionRequestsExplicitMovieEvidenceWithoutPrimingATitle() {
        for (skipText in listOf(false, true)) {
            val prompt = PromptBuilder.buildUserPrompt("Untrusted OCR evidence: Request accepted", skipText)
            val instructions = prompt.substringBefore(PromptBuilder.IMAGE_MARKER)
            assertTrue(instructions.contains("SOURCE_TYPE: movie"))
            assertTrue(instructions.contains("SOURCE_TITLE: the exact movie title"))
            assertTrue(instructions.contains("Do not guess from generic UI/OCR words or uncertain context hints."))
            assertFalse(instructions.contains("Accepted"))
            assertTrue(prompt.contains("TEXT:"))
            assertTrue(prompt.contains("SUBJECTS:"))
            assertTrue(prompt.contains("TAGS:"))
        }
    }
}
