package com.memeget.nativeapp.cloud

import com.memeget.nativeapp.CloudHttpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbabilityValidationTest {

    @Test
    fun testConfidence_UniformDistributionProducesZeroConfidence() {
        // When all options have equal probability 1/n, there is zero informational confidence
        for (n in 2..10) {
            val p = 1.0f / n.toFloat()
            val probs = (0 until n).associate { "opt_$it" to p }
            val conf = CloudHttpEngine.calculateConfidence(probs)
            assertEquals("Uniform distribution for n=$n must produce 0.0 confidence", 0.0f, conf, 1e-4f)
        }
    }

    @Test
    fun testConfidence_AbsoluteCertaintyProducesOneConfidence() {
        for (n in 2..10) {
            val probs = mutableMapOf("opt_0" to 1.0f)
            for (i in 1 until n) {
                probs["opt_$i"] = 0.0f
            }
            val conf = CloudHttpEngine.calculateConfidence(probs)
            assertEquals("Certainty pMax=1.0 for n=$n must produce 1.0 confidence", 1.0f, conf, 1e-4f)
        }
    }

    @Test
    fun testConfidence_TwoOptions() {
        // n = 2: confidence = (2 * pMax - 1) / 1 = 2 * pMax - 1
        // If pMax = 0.75 -> 2 * 0.75 - 1 = 0.5
        val probs = mapOf("opt_0" to 0.75f, "unknown" to 0.25f)
        val conf = CloudHttpEngine.calculateConfidence(probs)
        assertEquals(0.5f, conf, 1e-4f)

        // If pMax = 0.50 (uniform) -> 2 * 0.5 - 1 = 0.0
        val uniformProbs = mapOf("opt_0" to 0.5f, "unknown" to 0.5f)
        assertEquals(0.0f, CloudHttpEngine.calculateConfidence(uniformProbs), 1e-4f)

        // If pMax = 0.60 -> 2 * 0.6 - 1 = 0.2
        val slightProbs = mapOf("opt_0" to 0.60f, "unknown" to 0.40f)
        assertEquals(0.2f, CloudHttpEngine.calculateConfidence(slightProbs), 1e-4f)
    }

    @Test
    fun testConfidence_ThreeOptions() {
        // n = 3: confidence = (3 * pMax - 1) / 2
        // If pMax = 0.6 -> (1.8 - 1) / 2 = 0.4
        val probs = mapOf("opt_0" to 0.60f, "opt_1" to 0.30f, "unknown" to 0.10f)
        val conf = CloudHttpEngine.calculateConfidence(probs)
        assertEquals(0.4f, conf, 1e-4f)
    }


}
