package com.memeget.nativeapp.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorVersionMismatchTest {

    @Test
    fun testVectorSerializationRoundTrip() {
        val original = FloatArray(512) { i -> (i * 0.0137f) - 3.5f }
        val bytes = FloatBlob.toBytes(original)
        assertEquals(512 * 4, bytes.size)

        val restored = FloatBlob.fromBytes(bytes)
        assertEquals(512, restored.size)
        assertArrayEquals(original, restored, 0.000001f)
    }

    @Test
    fun testSelectPairVectorsRoutesToVisualOnlyWhenBothMatchActiveModel() {
        val activeVisual = "dinov2-native-r1"
        val dimPrimary = 512
        val dimVisual = 768

        val targetWithVisual = TagPropagation.VisualRecord(
            imageEmbedding = FloatArray(dimPrimary) { 1.0f },
            visualEmbedding = FloatArray(dimVisual) { 2.0f },
            visualModel = activeVisual,
        )

        val candidateWithVisual = TagPropagation.VisualRecord(
            imageEmbedding = FloatArray(dimPrimary) { 1.0f },
            visualEmbedding = FloatArray(dimVisual) { 2.0f },
            visualModel = activeVisual,
        )

        val candidateWithStaleVisual = TagPropagation.VisualRecord(
            imageEmbedding = FloatArray(dimPrimary) { 1.0f },
            visualEmbedding = FloatArray(dimVisual) { 2.0f },
            visualModel = "dinov2-legacy-v0", // Mismatched model
        )

        val candidateWithoutVisual = TagPropagation.VisualRecord(
            imageEmbedding = FloatArray(dimPrimary) { 1.0f },
            visualEmbedding = null,
            visualModel = "",
        )

        // Case 1: Both have active visual model -> VISUAL space
        val pair1 = TagPropagation.selectPairVectors(targetWithVisual, candidateWithVisual, activeVisual)
        assertEquals(TagPropagation.VectorSpace.VISUAL, pair1.space)
        assertEquals(dimVisual, pair1.a.size)
        assertEquals(dimVisual, pair1.b.size)

        // Case 2: Candidate has stale visual model -> falls back to PRIMARY space
        val pair2 = TagPropagation.selectPairVectors(targetWithVisual, candidateWithStaleVisual, activeVisual)
        assertEquals(TagPropagation.VectorSpace.PRIMARY, pair2.space)
        assertEquals(dimPrimary, pair2.a.size)
        assertEquals(dimPrimary, pair2.b.size)

        // Case 3: Candidate has no visual vector -> falls back to PRIMARY space
        val pair3 = TagPropagation.selectPairVectors(targetWithVisual, candidateWithoutVisual, activeVisual)
        assertEquals(TagPropagation.VectorSpace.PRIMARY, pair3.space)
        assertEquals(dimPrimary, pair3.a.size)
        assertEquals(dimPrimary, pair3.b.size)
    }

}
