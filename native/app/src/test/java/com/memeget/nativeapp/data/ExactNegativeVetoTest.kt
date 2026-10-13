package com.memeget.nativeapp.data

import com.memeget.nativeapp.knowledge.LearnCore
import org.junit.Assert.assertFalse
import org.junit.Test

class ExactNegativeVetoTest {

    @Test
    fun testKnnVetoWhenNegativePrototypeIsCloser() {
        val dim = 512
        val query = FloatArray(dim) { 0.1f }
        val queryNorm = FloatBlob.normalize(query)

        // Positive exemplar slightly perturbed from query
        val posVec = query.copyOf()
        posVec[0] += 0.05f
        val posNorm = FloatBlob.normalize(posVec)

        // Negative exemplar identical to query (closer than positive)
        val negVec = query.copyOf()
        val negNorm = FloatBlob.normalize(negVec)

        val head = LearnCore.LabelHead(
            label = "pepe",
            category = "character",
            w = FloatArray(dim),
            b = -10f, // Head itself will reject
            threshold = 0.6f,
            protos = listOf(posNorm),
            negProtos = listOf(negNorm),
            knnMu = 0.70f,
            knnSigma = 0.05f,
        )

        val res = LearnCore.scoreExemplar(head, queryNorm, queryNorm)
        // Since negNorm is closer than posNorm, kNN pathway MUST be vetoed
        assertFalse("kNN pathway must be vetoed by closer negative prototype", res.matched)
    }

}
