package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag

internal object Recognition {

    const val ANCHOR_BIAS = 0.5f
    const val MIN_LABEL_MARGIN = 0.13f
    const val RECOGNIZED_CONFIDENCE = 0.56f

    /** Bundled TV shows and movies. They compete: a picture gets at most the one it matches best (LearnCore). */
    const val SHOW_CATEGORY = "show"
    /**
     * The best title applies at this anchored margin or more, and only this far ahead of the next title: similar
     * art styles and actors pull a picture toward several titles at once. Chosen offline on one library's vectors.
     */
    const val SHOW_MIN_MARGIN = 0.17f
    const val SHOW_MIN_GAP = 0.02f

    private val CALIBRATION = arrayOf(
        0.115f to 0.05f,
        0.140f to 0.14f,
        0.160f to 0.26f,
        0.180f to 0.38f,
        0.200f to 0.74f,
        0.225f to 0.86f,
    )

    private const val MIN_CONFIDENCE = 0.03f
    private const val MAX_CONFIDENCE = 0.90f

    fun labelConfidence(margin: Float): Float {
        val first = CALIBRATION[0]
        if (margin <= first.first) return MIN_CONFIDENCE
        for (i in 1 until CALIBRATION.size) {
            val (x1, y1) = CALIBRATION[i]
            if (margin <= x1) {
                val (x0, y0) = CALIBRATION[i - 1]
                return y0 + ((margin - x0) / (x1 - x0)) * (y1 - y0)
            }
        }
        return MAX_CONFIDENCE
    }

    private val IDENTITY_CATEGORIES = setOf(
        "format",
        "character",
        "person",
        "identity",
    )

    fun isIdentityCategory(category: String): Boolean {
        return IDENTITY_CATEGORIES.contains(category.trim().lowercase())
    }

    fun isExplicitAssertion(tag: Tag): Boolean {
        val src = tag.source.lowercase()
        return src == "manual" || src == "ocr" ||
            (src == "exemplar" || src == "cloud" || src == "cloud_hint") && tag.confirmation == "confirmed"
    }

    fun recognitionTier(tags: List<Tag>): String {
        var best = 0f
        for (t in tags) {
            if (t.confirmation == "rejected" || t.provenance.startsWith("legacy-")) continue
            val src = t.source.lowercase()
            if (isExplicitAssertion(t)) {
                if (isIdentityCategory(t.category)) return "recognized"
                // Confirming a mood does not confirm who or what the image depicts.
                continue
            }
            if (src == "vision" || src == "cloud" || src == "cloud_hint") continue
            // Bundled baseline names also use "topic"; category is not a confidence gate.
            if (t.score > best) best = t.score
        }
        if (best >= RECOGNIZED_CONFIDENCE) return "recognized"
        return if (best > 0f) "weak" else "unknown"
    }
}
