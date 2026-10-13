package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.inference.ModelRegistry

internal object TagPropagation {

    const val PROPAGATE_MIN_COS_VISUAL = 0.70f
    const val PROPAGATE_MIN_COS_PRIMARY = 0.88f
    const val PROPAGATE_MAX_TARGETS = 40

    data class VisualRecord(
        val imageEmbedding: FloatArray,
        val imageModel: String = "",
        val visualEmbedding: FloatArray? = null,
        val visualModel: String = "",
    )

    data class Candidate(
        val id: Long,
        val hasDurableLabel: Boolean,
        val record: VisualRecord,
    )

    data class PropagationHit(
        val id: Long,
        val score: Float,
        val margin: Float,
    )

    enum class VectorSpace {
        PRIMARY,
        VISUAL,
    }

    data class SelectedPair(
        val a: FloatArray,
        val b: FloatArray,
        val space: VectorSpace,
    )

    private fun hasActiveVisual(record: VisualRecord, activeVisualModel: String): Boolean {
        return activeVisualModel.isNotEmpty() &&
            record.visualEmbedding != null &&
            record.visualEmbedding.isNotEmpty() &&
            record.visualModel == activeVisualModel
    }

    fun selectPairVectors(
        target: VisualRecord,
        candidate: VisualRecord,
        activeVisualModel: String = "dinov2-native-r1",
        activePrimaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
    ): SelectedPair {
        if (hasActiveVisual(target, activeVisualModel) && hasActiveVisual(candidate, activeVisualModel)) {
            return SelectedPair(target.visualEmbedding!!, candidate.visualEmbedding!!, VectorSpace.VISUAL)
        }
        if (target.imageModel.isNotBlank() && activePrimaryModel.isNotBlank() && target.imageModel != activePrimaryModel) {
            return SelectedPair(FloatArray(0), FloatArray(0), VectorSpace.PRIMARY)
        }
        if (candidate.imageModel.isNotBlank() && activePrimaryModel.isNotBlank() && candidate.imageModel != activePrimaryModel) {
            return SelectedPair(FloatArray(0), FloatArray(0), VectorSpace.PRIMARY)
        }
        return SelectedPair(target.imageEmbedding, candidate.imageEmbedding, VectorSpace.PRIMARY)
    }

    fun scorePropagationCandidate(
        sources: List<VisualRecord>,
        candidate: Candidate,
        activeVisualModel: String = "dinov2-native-r1",
    ): PropagationHit? {
        if (candidate.hasDurableLabel) return null
        if (candidate.record.imageEmbedding.isEmpty()) return null

        var best: PropagationHit? = null
        for (source in sources) {
            if (source.imageEmbedding.isEmpty()) continue
            val (a, b, space) = selectPairVectors(source, candidate.record, activeVisualModel)
            if (a.size != b.size) continue
            val score = FloatBlob.dot(a, b)
            val threshold = if (space == VectorSpace.VISUAL) PROPAGATE_MIN_COS_VISUAL else PROPAGATE_MIN_COS_PRIMARY
            val margin = score - threshold
            if (margin >= 0f && (best == null || margin > best.margin)) {
                best = PropagationHit(id = candidate.id, score = score, margin = margin)
            }
        }
        return best
    }

    fun rankPropagationHits(hits: List<PropagationHit>): List<PropagationHit> {
        return hits.sortedByDescending { it.margin }.take(PROPAGATE_MAX_TARGETS)
    }

    fun propagatedTag(label: String, score: Float): Tag {
        return Tag(
            label = TagMerge.normalizeTagLabel(label),
            category = "topic",
            score = score,
            source = "propagated",
            scoreDomain = "cosine",
            confirmation = "unreviewed",
            provenance = "propagated",
        )
    }
}
