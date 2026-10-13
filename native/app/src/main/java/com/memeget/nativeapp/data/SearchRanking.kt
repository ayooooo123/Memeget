package com.memeget.nativeapp.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal object SearchRanking {

    const val CAPTION_COS_FLOOR = 0.55f
    const val CAPTION_WEIGHT = 0.9f

    const val LEXICAL_WEIGHT = 0.35f
    const val ALL_TERMS_BOOST = 0.6f
    const val EXPANDED_LEXICAL_WEIGHT = 0.22f

    const val TAG_EXACT_WEIGHT = 2.5f
    const val TAG_ALL_TERMS_BOOST = 3.0f

    val FTS_COLUMN_WEIGHTS = floatArrayOf(1.0f, 1.2f, 2.0f, 1.8f, 5.0f, 4.0f)

    data class DenseRankedEntry(
        val id: Long,
        val score: Float,
        val modifiedAt: Long = 0L,
    )

    data class RankedList(
        val ids: List<Long>,
        val weight: Float = 1.0f,
    )

    data class FusedRank(
        val id: Long,
        val score: Float,
    )

    fun hybridSearchScore(
        queryVec: FloatArray,
        imageVec: FloatArray,
        captionVec: FloatArray?,
    ): Float {
        val imageScore = FloatBlob.dot(queryVec, imageVec)
        if (captionVec == null || captionVec.isEmpty()) return imageScore

        val captionScore = FloatBlob.dot(queryVec, captionVec)
        val captionMargin = max(0f, captionScore - CAPTION_COS_FLOOR)
        return imageScore + CAPTION_WEIGHT * captionMargin
    }

    fun scoreEntry(
        queryVec: FloatArray?,
        exactTerms: List<String>,
        expandedTerms: List<String>,
        imageVec: FloatArray,
        captionVec: FloatArray?,
        searchText: String,
    ): Float {
        var score = if (queryVec != null) {
            hybridSearchScore(queryVec, imageVec, captionVec)
        } else {
            0f
        }

        if (exactTerms.isNotEmpty()) {
            var matched = 0
            for (t in exactTerms) {
                if (searchText.contains(t)) matched++
            }
            score += LEXICAL_WEIGHT * (matched.toFloat() / exactTerms.size)
            if (matched == exactTerms.size) score += ALL_TERMS_BOOST
        }

        if (expandedTerms.isNotEmpty()) {
            var matched = 0
            for (t in expandedTerms) {
                if (searchText.contains(t)) matched++
            }
            if (matched > 0) {
                score += EXPANDED_LEXICAL_WEIGHT * min(1.0f, matched.toFloat() / 2f)
            }
        }
        return score
    }

    fun tagTermScore(tagLabels: List<String>, exactTerms: List<String>): Float {
        if (exactTerms.isEmpty() || tagLabels.isEmpty()) return 0f
        val hay = tagLabels.joinToString(" ") { it.lowercase() }
        var matched = 0
        for (term in exactTerms) {
            if (hay.contains(term)) matched++
        }
        if (matched == 0) return 0f
        var score = TAG_EXACT_WEIGHT * (matched.toFloat() / exactTerms.size)
        if (matched == exactTerms.size) score += TAG_ALL_TERMS_BOOST
        return score
    }

    fun calculateFts4Bm25(
        matchinfoBlob: ByteArray?,
        weights: FloatArray = FTS_COLUMN_WEIGHTS,
        totalDocs: Int = 100,
    ): Float {
        if (matchinfoBlob == null || matchinfoBlob.size < 8) return 0f
        val buffer = ByteBuffer.wrap(matchinfoBlob).order(ByteOrder.nativeOrder())
        val p = buffer.int // number of phrases
        val c = buffer.int // number of columns
        if (p <= 0 || c <= 0) return 0f

        val n = max(1, totalDocs)
        var totalScore = 0f
        var offset = 8

        for (phraseIndex in 0 until p) {
            for (colIndex in 0 until c) {
                if (offset + 12 > matchinfoBlob.size) break
                val termFreq = buffer.int
                val allHits = buffer.int
                val docFreq = buffer.int
                offset += 12

                if (termFreq > 0) {
                    val w = if (colIndex < weights.size) weights[colIndex] else 1.0f
                    val idf = ln(1.0 + (n - docFreq + 0.5) / (docFreq + 0.5)).toFloat()
                    val tf = termFreq.toFloat()
                    val tfPart = tf / (tf + 1.2f)
                    totalScore += w * tfPart * max(0.1f, idf)
                }
            }
        }
        return totalScore
    }

    fun quoteFtsTerm(term: String): String {
        return "\"" + term.replace("\"", "\"\"") + "\""
    }

    fun buildFtsMatchQuery(exactTerms: List<String>, expandedTerms: List<String> = emptyList()): String {
        val terms = ArrayList<String>()
        val seen = HashSet<String>()
        for (t in exactTerms) {
            val c = t.trim().lowercase()
            if (c.isNotEmpty() && seen.add(c)) terms.add(c)
        }
        for (t in expandedTerms) {
            val c = t.trim().lowercase()
            if (c.isNotEmpty() && seen.add(c)) terms.add(c)
        }
        if (terms.isEmpty()) return ""
        return terms.joinToString(" OR ") { quoteFtsTerm(it) }
    }

    fun reciprocalRankFusion(lists: List<RankedList>, k: Int = 60): List<FusedRank> {
        val scores = HashMap<Long, Float>()
        val order = HashMap<Long, Int>()
        var firstSeen = 0

        for (list in lists) {
            val weight = list.weight
            for (i in list.ids.indices) {
                val id = list.ids[i]
                if (!order.containsKey(id)) {
                    order[id] = firstSeen++
                }
                val prev = scores[id] ?: 0f
                scores[id] = prev + weight / (k + i + 1)
            }
        }

        return scores.map { (id, score) -> FusedRank(id, score) }
            .sortedWith { a, b ->
                val scoreCmp = b.score.compareTo(a.score)
                if (scoreCmp != 0) scoreCmp
                else (order[a.id] ?: 0).compareTo(order[b.id] ?: 0)
            }
    }

    fun fuseDenseAndLexicalRanks(
        dense: List<DenseRankedEntry>,
        lexicalIds: List<Long>,
    ): List<FusedRank> {
        val lists = ArrayList<RankedList>()
        lists.add(RankedList(dense.map { it.id }, weight = 1.0f))
        if (lexicalIds.isNotEmpty()) {
            lists.add(RankedList(lexicalIds, weight = 2.0f))
        }

        val fused = reciprocalRankFusion(lists)
        val denseById = dense.associateBy { it.id }

        return fused.sortedWith { a, b ->
            val scoreCmp = b.score.compareTo(a.score)
            if (scoreCmp != 0) {
                scoreCmp
            } else {
                val denseA = denseById[a.id]
                val denseB = denseById[b.id]
                val denseScoreCmp = (denseB?.score ?: 0f).compareTo(denseA?.score ?: 0f)
                if (denseScoreCmp != 0) {
                    denseScoreCmp
                } else {
                    val modA = denseA?.modifiedAt ?: 0L
                    val modB = denseB?.modifiedAt ?: 0L
                    val modCmp = modB.compareTo(modA)
                    if (modCmp != 0) modCmp else b.id.compareTo(a.id)
                }
            }
        }
    }
}
