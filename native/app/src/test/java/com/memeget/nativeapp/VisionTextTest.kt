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
}
