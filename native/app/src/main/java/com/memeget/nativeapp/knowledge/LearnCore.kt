package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.FloatBlob

internal object LearnCore {

    /** A meme this close to a "not X" example is a near-copy of it; X is vetoed there. */
    const val PU_EXCLUDE_COS = 0.85f

    fun classifyPrompts(
        imageVec: FloatArray,
        labels: List<CuratedLabelDef>,
        negativeVecs: List<FloatArray>,
    ): List<Tag> {
        if (labels.isEmpty()) return emptyList()

        var anchorMax = Float.NEGATIVE_INFINITY
        for (anchor in negativeVecs) {
            val c = FloatBlob.dot(imageVec, anchor)
            if (c > anchorMax) anchorMax = c
        }
        val bias = if (anchorMax.isFinite()) Recognition.ANCHOR_BIAS * anchorMax else 0f

        val out = ArrayList<Tag>()
        var show: CuratedLabelDef? = null
        var showMargin = Float.NEGATIVE_INFINITY
        var nextShowMargin = Float.NEGATIVE_INFINITY
        for (def in labels) {
            val margin = FloatBlob.dot(imageVec, def.vec) - bias
            if (def.category == Recognition.SHOW_CATEGORY) {
                if (margin > showMargin) {
                    nextShowMargin = showMargin
                    showMargin = margin
                    show = def
                } else if (margin > nextShowMargin) {
                    nextShowMargin = margin
                }
                continue
            }
            if (margin < Recognition.MIN_LABEL_MARGIN) continue
            out.add(promptTag(def, margin))
        }
        if (show != null && showMargin >= Recognition.SHOW_MIN_MARGIN && showMargin - nextShowMargin >= Recognition.SHOW_MIN_GAP) {
            // A few titles ("spongebob", "star wars") are also older topic labels; the chosen title takes their place,
            // keeping the better of the two scores, so the meme's tag says it is from a show.
            val title = promptTag(show, showMargin)
            val sameName = out.filter { it.label == title.label }
            out.removeAll(sameName)
            out.add(title.copy(score = (sameName.map { it.score } + title.score).max()))
        }
        return out
    }

    private fun promptTag(def: CuratedLabelDef, margin: Float) = Tag(
        label = def.label.lowercase(),
        category = def.category,
        score = Recognition.labelConfidence(margin),
        source = "prompt",
        scoreDomain = "calibrated",
        confirmation = "unreviewed",
        provenance = "bundled",
    )

    data class CandidatePrompt(
        val tag: Tag,
        val margin: Float,
    )

    fun rankPromptCandidates(
        imageVec: FloatArray,
        labels: List<CuratedLabelDef>,
        negativeVecs: List<FloatArray>,
    ): List<CandidatePrompt> {
        if (labels.isEmpty()) return emptyList()

        var anchorMax = Float.NEGATIVE_INFINITY
        for (anchor in negativeVecs) {
            val c = FloatBlob.dot(imageVec, anchor)
            if (c > anchorMax) anchorMax = c
        }
        val bias = if (anchorMax.isFinite()) Recognition.ANCHOR_BIAS * anchorMax else 0f

        val out = ArrayList<CandidatePrompt>(labels.size)
        for (def in labels) {
            val margin = FloatBlob.dot(imageVec, def.vec) - bias
            out.add(CandidatePrompt(tag = promptTag(def, margin), margin = margin))
        }
        return out
    }

    fun mergeClassified(
        fromPrompts: List<Tag>,
        fromExemplars: List<Tag>,
        topK: Int = 3,
    ): List<Tag> {
        val best = LinkedHashMap<String, Tag>()
        for (t in fromPrompts) {
            val cur = best[t.label]
            if (cur == null || t.score > cur.score) best[t.label] = t
        }
        for (t in fromExemplars) {
            best[t.label] = t // exemplar always overrides prompt
        }

        return best.values.sortedWith { a, b ->
            if (a.source != b.source) {
                if (a.source == "exemplar") -1 else 1
            } else {
                b.score.compareTo(a.score)
            }
        }.take(topK)
    }
}
