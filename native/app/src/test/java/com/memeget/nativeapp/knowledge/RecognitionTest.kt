package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionTest {
    @Test fun calibratedBaselineNameDoesNotRequireAnIdentityCategory() {
        val name = Tag("spongebob", "topic", 0.77f, "prompt", scoreDomain = "calibrated")
        assertEquals("recognized", Recognition.recognitionTier(listOf(name)))
    }

    @Test fun confirmingAnEmotionDoesNotConfirmAnIdentity() {
        val emotion = Tag("happy", "emotion", 1f, "manual", confirmation = "confirmed")
        assertEquals("unknown", Recognition.recognitionTier(listOf(emotion)))
        val format = Tag("stonks", "format", 1f, "manual", confirmation = "confirmed")
        assertEquals("recognized", Recognition.recognitionTier(listOf(emotion, format)))
    }

    @Test fun cloudIdentityRequiresHumanReviewWithoutReinterpretingProviderProbability() {
        for (source in listOf("cloud", "cloud_hint")) {
            val prediction = Tag("format", "format", 0.01f, source,
                "provider-choice-probability", "unreviewed", "cloud:model:prompt")
            assertEquals("unknown", Recognition.recognitionTier(listOf(prediction)))
            val reviewed = prediction.copy(confirmation = "confirmed")
            assertEquals("recognized", Recognition.recognitionTier(listOf(reviewed)))
            assertEquals("unknown", Recognition.recognitionTier(listOf(reviewed.copy(category = "emotion"))))
            assertEquals("unknown", Recognition.recognitionTier(listOf(reviewed.copy(provenance = "legacy-sidecar"))))
        }
    }

    @Test fun untrustedEvidenceCannotPromoteAWeakNativeResult() {
        val weak = Tag("wojak", "character", 0.3f, "prompt", scoreDomain = "calibrated")
        val uncertain = listOf(
            Tag("cloud guess", "character", 0.99f, "cloud"),
            Tag("offline guess", "character", 0.99f, "cloud_hint"),
            Tag("vision guess", "character", 0.99f, "vision"),
            Tag("imported name", "character", 0.99f, "prompt", provenance = "legacy-sidecar"),
            Tag("rejected name", "character", 0.99f, "prompt", confirmation = "rejected"),
        )
        assertEquals("weak", Recognition.recognitionTier(uncertain + weak))
    }
}
