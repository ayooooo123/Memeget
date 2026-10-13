package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Exemplar
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.FloatBlob

internal object EntityRetrieve {

    const val DEFAULT_ENTITY_MIN_MARGIN = 0.13f
    const val DEFAULT_TOP_K = 5

    data class EntityHit(
        val label: String,
        val category: String,
        val score: Float,
        val margin: Float,
        val associations: List<String>,
    )

    private data class BestRow(
        val category: String,
        val cos: Float,
        val associations: List<String>,
    )

    fun retrieveEntities(
        imageVec: FloatArray,
        exemplars: List<Exemplar>,
        anchorSim: Float = 0f,
        topK: Int = DEFAULT_TOP_K,
        minMargin: Float = DEFAULT_ENTITY_MIN_MARGIN,
        categories: Set<String>? = null,
    ): List<EntityHit> {
        val bias = Recognition.ANCHOR_BIAS * if (anchorSim.isFinite()) anchorSim else 0f
        val best = LinkedHashMap<String, BestRow>()

        for (ex in exemplars) {
            if (!ex.positive) continue
            if (categories != null && ex.category.trim().lowercase() !in categories) continue
            val cos = FloatBlob.dot(imageVec, ex.vector)
            val prev = best[ex.label]
            if (prev == null || cos > prev.cos) {
                best[ex.label] = BestRow(
                    category = ex.category,
                    cos = cos,
                    associations = ex.associations,
                )
            }
        }

        val hits = ArrayList<EntityHit>()
        for ((label, row) in best) {
            val margin = row.cos - bias
            if (margin < minMargin) continue
            hits.add(
                EntityHit(
                    label = label,
                    category = row.category,
                    score = Recognition.labelConfidence(margin),
                    margin = margin,
                    associations = row.associations,
                )
            )
        }

        hits.sortWith { a, b ->
            val s = b.score.compareTo(a.score)
            if (s != 0) s else b.margin.compareTo(a.margin)
        }
        return hits.take(topK)
    }

    fun entityHitsToTags(hits: List<EntityHit>): List<Tag> {
        return hits.map { h ->
            Tag(
                label = h.label.lowercase(),
                category = h.category,
                score = h.score,
                source = "entity_pack",
                scoreDomain = "calibrated",
                confirmation = "unreviewed",
                provenance = "pack",
            )
        }
    }
}
