package com.memeget.nativeapp.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** Bundled show and movie titles compete: a picture gets at most the one it clearly matches best. */
class ShowCategoryTest {
    private val image = FloatArray(16).also { it[0] = 1f }

    /** A label whose prompt vector has cosine [cos] with [image] (no anchors, so cosine is the margin). */
    private fun label(name: String, category: String, cos: Float, axis: Int) = CuratedLabelDef(
        name, "a scene from $name", category, emptyList(),
        FloatArray(16).also { it[0] = cos; it[axis] = sqrt(1f - cos * cos) },
    )

    private fun tagged(vararg labels: CuratedLabelDef) =
        LearnCore.classifyPrompts(image, labels.toList(), emptyList()).associate { it.label to it.category }

    @Test fun onlyTheClearWinnerAmongShowsIsTagged() {
        val tags = tagged(
            label("The Sopranos", "show", 0.22f, 1),
            label("Goodfellas", "show", 0.18f, 2),
            label("The Departed", "show", 0.15f, 3),
            label("Tony Soprano", "character", 0.15f, 4),
        )
        assertEquals(mapOf("the sopranos" to "show", "tony soprano" to "character"), tags)
    }

    @Test fun showsTooCloseToCallAreLeftOut() {
        val tags = tagged(
            label("Family Guy", "show", 0.20f, 1),
            label("American Dad", "show", 0.19f, 2),
            label("Cartoon", "topic", 0.15f, 3),
        )
        assertEquals(mapOf("cartoon" to "topic"), tags)
    }

    @Test fun aShowNeedsAStrongerMatchThanOtherLabels() {
        // 0.15 clears the floor every other label uses, but not the show bar.
        val tags = tagged(
            label("Breaking Bad", "show", 0.15f, 1),
            label("Desert", "setting", 0.15f, 2),
        )
        assertEquals(mapOf("desert" to "setting"), tags)
    }

    @Test fun theChosenTitleReplacesATopicLabelOfTheSameName() {
        val tags = LearnCore.classifyPrompts(
            image,
            listOf(
                label("Spongebob", "topic", 0.25f, 1),
                label("SpongeBob", "show", 0.22f, 2),
                label("Rugrats", "show", 0.15f, 3),
            ),
            emptyList(),
        )
        assertEquals(1, tags.size)
        assertEquals("spongebob", tags.single().label)
        assertEquals("show", tags.single().category)
        assertEquals(Recognition.labelConfidence(0.25f), tags.single().score, 0f)
    }
}
