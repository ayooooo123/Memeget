package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionTextTest {
    @Test
    fun commaAfterAFieldLabelStartsThatField() {
        // Verbatim MiniCPM-V 4.7 1B reply for a Sopranos still on the Pixel 5a.
        val parsed = VisionText.parse(
            """
            CAPTION: man in car reacting to language with expression, used to hear insults, funny.
            TEXT: Ho! The language on you.
            SUBJECTS, man, car, interior
            TAGS, bald, expression, insult, humor, reaction
            """.trimIndent(),
        )

        assertEquals("Ho! The language on you.", parsed.text)
        assertEquals(
            listOf("man", "car", "interior", "bald", "expression", "insult", "humor", "reaction"),
            parsed.tags.map { it.label },
        )
    }

    @Test
    fun oneLineReplyIsSplitIntoItsFields() {
        // A one-line MiniCPM-V 4.7 1B reply for a video frame, as stored by the app on the Pixel 5a.
        val parsed = VisionText.parse(
            "CAPTION: man in suit gesturing at city backdrop, used to react to news. TEXT: AF mug, suit, red tie. " +
                "SUBJECTS: man, microphone. TAGS: city, suit, gesturing, AF, news.",
        )

        assertEquals("man in suit gesturing at city backdrop, used to react to news.", parsed.caption)
        assertEquals("AF mug, suit, red tie.", parsed.text)
        assertEquals(listOf("man", "microphone", "city", "suit", "gesturing", "af", "news"), parsed.tags.map { it.label })
    }

    @Test
    fun fieldNamesAndPlaceholdersInListsAreNotTags() {
        // List shapes the model wrote in the app on the Pixel 5a.
        val parsed = VisionText.parse(
            """
            CAPTION: Hitler speaking action, feeling intense, used to react to humor, meme format.
            SUBJECTS: characters, objects: kid, steam mw2, twitch.
            TAGS: tag: hitler, none.
            """.trimIndent(),
        )

        assertEquals(listOf("kid", "steam mw2", "twitch", "hitler"), parsed.tags.map { it.label })
    }

    @Test
    fun noneMeansTheImageHasNoText() {
        // Verbatim reply for a Pepe meme without text, from the Pixel 5a command-line run.
        val parsed = VisionText.parse(
            """
            CAPTION: green frog wearing blue shirt waves, expression weary, used to react to overbearing parents.
            TEXT: none
            SUBJECTS: frog, blue shirt
            TAGS: Pepe, green, wave, weary, meme
            """.trimIndent(),
        )

        assertEquals("", parsed.text)
        assertEquals(listOf("frog", "blue shirt", "pepe", "green", "wave", "weary", "meme"), parsed.tags.map { it.label })
    }
}
