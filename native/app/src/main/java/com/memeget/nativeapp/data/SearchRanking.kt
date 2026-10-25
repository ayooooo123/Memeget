package com.memeget.nativeapp.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal object SearchRanking {

    const val CAPTION_COS_FLOOR = 0.55f
    const val CAPTION_WEIGHT = 0.9f

    const val CONCEPT_PROTO_FLOOR = 0.50f
    const val CONCEPT_PROTO_WEIGHT = 0.45f

    const val LEXICAL_WEIGHT = 0.35f
    const val ALL_TERMS_BOOST = 0.6f
    const val EXPANDED_LEXICAL_WEIGHT = 0.22f

    const val TAG_EXACT_WEIGHT = 2.5f
    const val TAG_ALL_TERMS_BOOST = 3.0f

    // Lexical weights scaled tight to MobileCLIP's ~0.15-0.35 cosine throb so a sloppy partial word never fucks a real visual hit out of #1.
    const val DENSE_DOC_COV_WEIGHT = 0.05f
    const val DENSE_TAG_COV_WEIGHT = 0.06f
    const val DENSE_DOC_FULL_BOOST = 0.22f
    const val DENSE_TAG_FULL_BOOST = 0.30f
    const val DENSE_PHRASE_BOOST = 0.45f
    const val DENSE_SEMANTIC_EXPAND_BOOST = 0.045f
    const val DENSE_TYPO_FALLBACK_BOOST = 0.08f
    const val DENSE_PREFIX_TIEBREAK = 0.015f

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

    fun maxSceneCosine(queryVec: FloatArray, scenesFlat: FloatArray?, dim: Int = 512): Float {
        if (scenesFlat == null || scenesFlat.size < dim || queryVec.size < dim) return Float.NEGATIVE_INFINITY
        val count = scenesFlat.size / dim
        var best = Float.NEGATIVE_INFINITY
        for (s in 0 until count) {
            val base = s * dim
            var dot = 0f
            for (i in 0 until dim) {
                dot += queryVec[i] * scenesFlat[base + i]
            }
            if (dot > best) best = dot
        }
        return best
    }

    fun hybridSearchScore(
        queryVec: FloatArray,
        imageVec: FloatArray,
        captionVec: FloatArray?,
        scenesFlat: FloatArray? = null,
    ): Float {
        val baseImageScore = FloatBlob.dot(queryVec, imageVec)
        val bestScene = maxSceneCosine(queryVec, scenesFlat, queryVec.size.coerceAtLeast(1))
        val imageScore = if (bestScene.isFinite() && bestScene > baseImageScore) {
            max(baseImageScore, 0.25f * baseImageScore + 0.75f * bestScene)
        } else {
            baseImageScore
        }
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

        val docWords = if (exactTerms.isNotEmpty() || expandedTerms.isNotEmpty()) {
            SearchText.wordTokenSet(searchText)
        } else emptySet()

        if (exactTerms.isNotEmpty()) {
            var matched = 0
            for (t in exactTerms) {
                if (t in docWords) matched++
            }
            if (matched > 0) {
                score += LEXICAL_WEIGHT * (matched.toFloat() / exactTerms.size)
                if (matched == exactTerms.size) score += ALL_TERMS_BOOST
            }
        }

        if (expandedTerms.isNotEmpty()) {
            var matched = 0
            for (t in expandedTerms) {
                if (t in docWords) matched++
            }
            if (matched > 0) {
                score += EXPANDED_LEXICAL_WEIGHT * min(1.0f, matched.toFloat() / 2f)
            }
        }
        return score
    }

    fun tagTermScore(tagLabels: List<String>, exactTerms: List<String>): Float {
        if (exactTerms.isEmpty() || tagLabels.isEmpty()) return 0f
        val tagWords = SearchText.wordTokenSet(tagLabels.joinToString(" "))
        var matched = 0
        for (term in exactTerms) {
            if (term in tagWords) matched++
        }
        if (matched == 0) return 0f
        var score = TAG_EXACT_WEIGHT * (matched.toFloat() / exactTerms.size)
        if (matched == exactTerms.size) score += TAG_ALL_TERMS_BOOST
        return score
    }

    /**
     * Spreads dense cosine, concept prototypes, scene punchlines, and IDF-weighted whole-word hits into one score
     * calibrated for Master's search bar: partial generic labels ("pepe", "man") stay on their knees while complete
     * aspect or spoken-quote matches ride straight to the top.
     */
    fun calibratedSearchScore(
        queryVec: FloatArray?,
        conceptProto: FloatArray?,
        exactTerms: List<String>,
        exactTermWeights: FloatArray,
        semanticTerms: Set<String>,
        typoTerms: List<String>,
        prefixTerms: List<String>,
        imageVec: FloatArray,
        captionVec: FloatArray?,
        scenesFlat: FloatArray?,
        document: String,
        tagLabels: List<String>,
        bm25Exact: Float,
        phraseMatched: Boolean,
        precomputedDocWords: Set<String>? = null,
        precomputedTagWords: Set<String>? = null,
        copyCount: Int = 0,
    ): Float {
        val docWords = precomputedDocWords ?: SearchText.wordTokenSet(document)
        val tagWords = precomputedTagWords ?: if (tagLabels.isNotEmpty()) SearchText.wordTokenSet(tagLabels.joinToString(" ")) else emptySet()
        val usageTiebreak = if (copyCount > 0) min(0.045f, 0.016f * ln(1f + copyCount.toFloat())) else 0f

        if (queryVec == null || queryVec.isEmpty()) {
            // Fast first-stroke pass before the text tower cums: whole-word exact > social slang > typo rescue > prefix tease.
            var lexScore = 0f
            var exactMatched = 0
            for (t in exactTerms) {
                if (t in docWords) exactMatched++
            }
            if (exactMatched > 0 && exactTerms.isNotEmpty()) {
                lexScore += LEXICAL_WEIGHT * (exactMatched.toFloat() / exactTerms.size)
                if (exactMatched == exactTerms.size) lexScore += ALL_TERMS_BOOST
            }
            lexScore += tagTermScore(tagLabels, exactTerms)
            if (bm25Exact > 0f && exactMatched > 0) {
                lexScore += bm25Exact * 0.5f
            }
            if (phraseMatched) lexScore += 10f
            if (exactMatched == 0) {
                val socialHits = semanticTerms.count { it in docWords }
                if (socialHits > 0) {
                    lexScore += 0.30f * min(1.0f, socialHits.toFloat() / 2f)
                } else {
                    val typoHits = typoTerms.count { it in docWords }
                    if (typoHits > 0) {
                        lexScore += EXPANDED_LEXICAL_WEIGHT * min(1.0f, typoHits.toFloat())
                    } else {
                        val prefixHits = prefixTerms.count { it in docWords }
                        if (prefixHits > 0) lexScore += 0.08f * min(1.0f, prefixHits.toFloat())
                    }
                }
            }
            if (lexScore > 0f && usageTiebreak > 0f) {
                lexScore += usageTiebreak
            }
            return lexScore
        }

        // Dense + modality-bridged concept prototype + scene max-sim + caption margin
        var score = if (imageVec.isNotEmpty()) {
            var dense = hybridSearchScore(queryVec, imageVec, captionVec, scenesFlat)
            if (conceptProto != null && conceptProto.size == imageVec.size) {
                val protoSim = max(FloatBlob.dot(imageVec, conceptProto), maxSceneCosine(conceptProto, scenesFlat, imageVec.size))
                dense += CONCEPT_PROTO_WEIGHT * max(0f, protoSim - CONCEPT_PROTO_FLOOR)
            }
            dense
        } else {
            0f
        }

        var matchedLexicalOrExpansion = false
        if (exactTerms.isNotEmpty()) {
            var totalW = 0f
            var docW = 0f
            var tagW = 0f
            for (i in exactTerms.indices) {
                val t = exactTerms[i]
                val w = if (i < exactTermWeights.size) exactTermWeights[i] else 1f
                totalW += w
                if (t in docWords) docW += w
                if (t in tagWords) tagW += w
            }
            if (totalW > 0f && docW > 0f) {
                matchedLexicalOrExpansion = true
                val covDoc = docW / totalW
                val covTag = tagW / totalW
                score += DENSE_DOC_COV_WEIGHT * covDoc + DENSE_TAG_COV_WEIGHT * covTag
                if (docW >= 0.999f * totalW) score += DENSE_DOC_FULL_BOOST
                if (tagW >= 0.999f * totalW) score += DENSE_TAG_FULL_BOOST
                if (bm25Exact > 0f) {
                    score += 0.015f * min(3.0f, bm25Exact)
                }
            } else {
                // Fallback recall when literal query words didn't appear verbatim (social slang, aliases, typos)
                if (semanticTerms.isNotEmpty() && semanticTerms.any { it in docWords }) {
                    matchedLexicalOrExpansion = true
                    score += DENSE_SEMANTIC_EXPAND_BOOST
                    if (semanticTerms.any { it in tagWords }) score += 0.025f
                }
                if (typoTerms.isNotEmpty() && typoTerms.any { it in docWords }) {
                    matchedLexicalOrExpansion = true
                    score += DENSE_TYPO_FALLBACK_BOOST
                } else if (prefixTerms.isNotEmpty() && prefixTerms.any { it in docWords }) {
                    score += DENSE_PREFIX_TIEBREAK
                }
            }
        }

        if (phraseMatched) {
            matchedLexicalOrExpansion = true
            score += DENSE_PHRASE_BOOST
        }
        if (usageTiebreak > 0f && (matchedLexicalOrExpansion || score >= 0.19f)) {
            score += usageTiebreak
        }
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
