package com.memeget.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseMiningTest {
    @Test
    fun aLineQuotedInTwoClipsComesBackWholeOnce() {
        val phrases = PhraseMining.sharedPhrases(
            listOf(
                1L to "Okay so I'm home honey, I'm home and the dog is barking.",
                2L to "Then he walks in like I'm home honey, I'm home! Classic.",
                3L to "Nothing in common with the others here at all.",
            ),
            minRarity = 0.0,
        )
        assertEquals(listOf(SharedPhrase("im home honey im home", listOf(1L, 2L))), phrases)
    }

    @Test
    fun theSameClipSavedTwiceIsNotAQuote() {
        val clip = "you wake up next to somebody of another race and you are like what"
        val phrases = PhraseMining.sharedPhrases(listOf(1L to clip, 2L to "$clip haha"), minRarity = 0.0)
        assertTrue(phrases.isEmpty())
    }

    @Test
    fun linesOfOnlyFillerWordsAreSkipped() {
        val phrases = PhraseMining.sharedPhrases(
            listOf(1L to "and then i was like you know", 2L to "so and then i was like you know right"),
            minRarity = 0.0,
        )
        assertTrue(phrases.isEmpty())
    }

    @Test
    fun aLineInMoreMemesRanksAboveALongerOneInFewer() {
        val phrases = PhraseMining.sharedPhrases(
            listOf(
                1L to "what the fuck bitch you are retarded",
                2L to "what the fuck bitch you are retarded please stop",
                3L to "oh what the fuck bitch",
                4L to "different clip entirely about cats and dogs",
            ),
            minRarity = 0.0,
        )
        assertEquals("what the fuck bitch", phrases.first().phrase)
        assertEquals(listOf(1L, 2L, 3L), phrases.first().memeIds)
    }

    @Test
    fun everydayFragmentsGiveWayToRareLines() {
        val phrases = PhraseMining.sharedPhrases(
            listOf(
                1L to "there was a lot of rain today",
                2L to "we ate a lot of pizza last night",
                3L to "he said a lot of nonsense again",
                4L to "a lot of people missed the bus",
                5L to "the paranormies were right after all",
                6L to "turns out the paranormies were right",
                7L to "cats sleeping in the sun",
                8L to "nobody expected the spanish inquisition",
                9L to "my phone battery died again",
                10L to "the market crashed hard today",
            ),
        )
        assertEquals(listOf("the paranormies were right"), phrases.map { it.phrase })
    }
}
