package com.memeget.nativeapp.cloud

import com.memeget.nativeapp.CloudPrototypeMemory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudPrototypeMemoryTest {
    @get:Rule val directory = TemporaryFolder()
    private fun vector(axis: Int = 0) = FloatArray(512).apply { this[axis] = 1f }
    private fun hash(value: Int) = value.toString(16).padStart(64, '0')

    @Test fun hintsAreModelBoundWeakEvidenceAndDoNotAliasCallerBuffers() {
        val root = directory.newFolder()
        val memory = CloudPrototypeMemory(root)
        val input = vector()
        memory.addPrototype("doge", "character", input, "encoder-a", hash(1), 0.9f, "cloud:model:prompt")
        input[0] = 0f
        input[1] = 1f
        val restored = CloudPrototypeMemory(root)
        val hint = restored.findHints(vector(), "encoder-a", hash(2)).single()
        assertEquals("doge", hint.label)
        assertEquals("cloud_hint", hint.source)
        assertEquals("unreviewed", hint.confirmation)
        assertEquals("cosine-not-accuracy", hint.scoreDomain)
        assertEquals("cloud:model:prompt", hint.provenance)
        assertTrue(restored.findHints(vector(), "encoder-b", hash(2)).isEmpty())
        assertTrue(restored.findHints(vector(1), "encoder-a", hash(2)).isEmpty())
    }

    @Test fun perLabelEvictionKeepsRecentDiverseExamples() {
        val memory = CloudPrototypeMemory(directory.newFolder())
        for (axis in 0..8) memory.addPrototype("doge", "character", vector(axis), "encoder-a", hash(axis), 0.8f, "cloud:model:prompt")
        assertEquals(8, memory.count())
        assertTrue(memory.findHints(vector(0), "encoder-a").isEmpty())
        assertEquals("doge", memory.findHints(vector(8), "encoder-a").single().label)
    }

    @Test fun expiredOrFutureDatedMemoriesCannotProduceHints() {
        var now = 4_000_000_000L
        val memory = CloudPrototypeMemory(directory.newFolder()) { now }
        memory.addPrototype("pepe", "character", vector(), "encoder-a", hash(1), 0.9f, "cloud:model:prompt")
        now += CloudPrototypeMemory.EXPIRY_MILLIS + 1
        assertTrue(memory.findHints(vector(), "encoder-a").isEmpty())
        memory.addPrototype("pepe", "character", vector(), "encoder-a", hash(1), 0.9f, "cloud:model:prompt")
        now--
        assertTrue(memory.findHints(vector(), "encoder-a").isEmpty())
    }

    @Test fun rejectionSurvivesRestartForgetAndRepeatedCloudObservation() {
        val root = directory.newFolder()
        val memory = CloudPrototypeMemory(root)
        memory.addPrototype("Doge", "character", vector(), "encoder-a", hash(1), 0.9f, "cloud:model:prompt")
        memory.rejectLabel(hash(2), "doge")
        assertTrue(memory.findHints(vector(), "encoder-a", hash(2)).isEmpty())
        assertEquals("Doge", memory.findHints(vector(), "encoder-a", hash(3)).single().label)
        memory.rejectLabel(hash(1), "doge")
        memory.clear()
        val reopened = CloudPrototypeMemory(root)
        reopened.addPrototype("Doge", "character", vector(), "encoder-a", hash(1), 1f, "cloud:model:prompt")
        assertTrue(reopened.findHints(vector(), "encoder-a", hash(3)).isEmpty())
        assertTrue(reopened.isRejected(hash(2), "DOGE"))
    }

    @Test fun invalidVectorsNeverBecomeCandidates() {
        val memory = CloudPrototypeMemory(directory.newFolder())
        for (input in listOf(FloatArray(512), FloatArray(4), vector().apply { this[0] = Float.NaN }, vector().apply { this[0] = 4f })) {
            memory.addPrototype("doge", "character", input, "encoder-a", hash(1), 0.9f, "cloud:model:prompt")
        }
        assertTrue(memory.findHints(vector(), "encoder-a").isEmpty())
    }
}
