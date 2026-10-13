package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePrecedenceTest {

    @Test
    fun testTagSourceRankHierarchy() {
        val manual = Tag(label = "pepe", source = "manual", score = 0.5f)
        val exemplar = Tag(label = "pepe", source = "exemplar", score = 0.9f)
        val propagated = Tag(label = "pepe", source = "propagated", score = 0.8f)
        val ocr = Tag(label = "pepe", source = "ocr", score = 1.0f)
        val vision = Tag(label = "pepe", source = "vision", score = 0.95f)
        val prompt = Tag(label = "pepe", source = "prompt", score = 0.99f)

        // Manual beats exemplar even if exemplar has higher score
        val better1 = TagMerge.betterTag(manual, exemplar)
        assertEquals("manual", better1.source)

        // Exemplar beats propagated
        val better2 = TagMerge.betterTag(propagated, exemplar)
        assertEquals("exemplar", better2.source)

        // Propagated beats ocr
        val better3 = TagMerge.betterTag(ocr, propagated)
        assertEquals("propagated", better3.source)

        // OCR beats vision
        val better4 = TagMerge.betterTag(ocr, vision)
        assertEquals("ocr", better4.source)

        // Vision beats prompt
        val better5 = TagMerge.betterTag(prompt, vision)
        assertEquals("vision", better5.source)
    }

    @Test
    fun testMergeDurableTagsNeverEvictsDurable() {
        // Create 10 durable tags and 10 auto tags
        val tags = ArrayList<Tag>()
        for (i in 0 until 10) {
            tags.add(Tag(label = "durable_$i", source = "manual", score = 1.0f))
        }
        for (i in 0 until 10) {
            tags.add(Tag(label = "auto_$i", source = "prompt", score = 0.9f - i * 0.05f))
        }

        // Cap auto tags at 6
        val merged = TagMerge.mergeDurableTags(tags, capAuto = 6)

        // All 10 durable tags MUST survive
        val durableCount = merged.count { TagMerge.isDurableTag(it) }
        assertEquals(10, durableCount)

        // Auto tags must be capped at 6
        val autoCount = merged.count { !TagMerge.isDurableTag(it) }
        assertEquals(6, autoCount)

        assertEquals(16, merged.size)
    }

    @Test
    fun testMergeDurableTagsDeduplicationPrefersHigherRank() {
        val tags = listOf(
            Tag(label = "wojak", source = "prompt", score = 0.95f),
            Tag(label = "wojak", source = "manual", score = 0.50f),
            Tag(label = "pepe", source = "ocr", score = 0.80f),
            Tag(label = "pepe", source = "exemplar", score = 0.70f),
        )

        val merged = TagMerge.mergeDurableTags(tags)
        val wojak = merged.find { it.label == "wojak" }
        val pepe = merged.find { it.label == "pepe" }

        assertEquals("manual", wojak?.source)
        assertEquals("exemplar", pepe?.source)
    }

    @Test
    fun testUpsertDurableTagPreservesUserTruth() {
        val initialTags = listOf(
            Tag(label = "pepe", source = "prompt", score = 0.7f),
            Tag(label = "frog", source = "vision", score = 0.6f),
        )
        val initialExtra = "green animal"

        val manualTag = Tag(
            label = "pepe",
            category = "character",
            score = 1.0f,
            source = "manual",
            scoreDomain = "user",
            confirmation = "confirmed",
            provenance = "user",
        )

        val (updatedTags, updatedTerms) = TagMerge.upsertDurableTag(initialTags, initialExtra, manualTag)

        val pepeTag = updatedTags.find { it.label == "pepe" }
        assertEquals("manual", pepeTag?.source)
        assertEquals("confirmed", pepeTag?.confirmation)
        assertEquals("user", pepeTag?.scoreDomain)
        assertTrue(updatedTerms.contains("pepe"))
        assertTrue(updatedTerms.contains("green"))
    }
}
