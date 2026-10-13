package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.FloatBlob
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object LearnCore {

    const val EXEMPLAR_PROB_THRESHOLD = 0.60f
    const val PU_EXCLUDE_COS = 0.85f
    const val HARD_NEG_LO = 0.65f
    const val THRESHOLD_MARGIN = 0.05f
    const val THRESHOLD_CAP = 0.92f

    const val KNN_MIN_COS = 0.88f
    const val KNN_MIN_Z = 3.0f
    const val KNN_MIN_BG = 8

    data class LabelHead(
        val label: String,
        val category: String,
        val w: FloatArray,
        val b: Float,
        val threshold: Float,
        val protos: List<FloatArray>,
        val negProtos: List<FloatArray>,
        val knnMu: Float,
        val knnSigma: Float,
    )

    fun headProb(w: FloatArray, b: Float, x: FloatArray): Float {
        var z = b
        val n = minOf(w.size, x.size)
        for (i in 0 until n) {
            z += w[i] * x[i]
        }
        if (z < -30f) return 0f
        if (z > 30f) return 1f
        return 1.0f / (1.0f + exp(-z))
    }

    fun fitLogistic(
        positives: List<FloatArray>,
        negatives: List<FloatArray>,
        iters: Int = 250,
        lr: Float = 0.5f,
        l2: Float = 5e-3f,
    ): Pair<FloatArray, Float> {
        val dim = if (positives.isNotEmpty()) positives[0].size else if (negatives.isNotEmpty()) negatives[0].size else 512
        val w = FloatArray(dim)
        var b = 0f

        val nPos = positives.size
        val nNeg = negatives.size
        val total = (nPos + nNeg).toFloat().coerceAtLeast(1f)
        val wPos = if (nPos > 0) total / (2f * nPos) else 0f
        val wNeg = if (nNeg > 0) total / (2f * nNeg) else 0f

        val gw = FloatArray(dim)
        for (it in 0 until iters) {
            gw.fill(0f)
            var gb = 0f

            fun accum(x: FloatArray, y: Float, cw: Float) {
                var z = b
                val len = minOf(dim, x.size)
                for (i in 0 until len) z += w[i] * x[i]
                val p = if (z < -30f) 0f else if (z > 30f) 1f else 1.0f / (1.0f + exp(-z))
                val g = cw * (p - y)
                for (i in 0 until len) gw[i] += g * x[i]
                gb += g
            }

            for (x in positives) accum(x, 1f, wPos)
            for (x in negatives) accum(x, 0f, wNeg)

            var gmax = 0f
            for (i in 0 until dim) {
                val g = gw[i] / total + l2 * w[i]
                val ag = abs(g)
                if (ag > gmax) gmax = ag
                w[i] -= lr * g
            }
            b -= lr * (gb / total)

            if (lr * gmax < 1e-4f) break
        }
        return Pair(w, b)
    }

    fun trainLabelModel(
        label: String,
        category: String,
        posRaw: List<FloatArray>,
        posCentered: List<FloatArray>,
        negRaw: List<FloatArray>,
        negCentered: List<FloatArray>,
        backgroundRaw: List<FloatArray>,
        backgroundCentered: List<FloatArray>,
        otherPosRaw: List<FloatArray>,
        otherPosCentered: List<FloatArray>,
    ): LabelHead {
        val keptBg = ArrayList<FloatArray>()
        val bgMaxCos = ArrayList<Float>()
        for (i in backgroundRaw.indices) {
            val c = FloatBlob.maxCosTo(backgroundRaw[i], posRaw)
            if (c >= PU_EXCLUDE_COS) continue
            keptBg.add(backgroundCentered[i])
            bgMaxCos.add(c)
            if (c >= HARD_NEG_LO) {
                keptBg.add(backgroundCentered[i])
            }
        }

        val keptOther = ArrayList<FloatArray>()
        for (i in otherPosRaw.indices) {
            if (FloatBlob.maxCosTo(otherPosRaw[i], posRaw) >= PU_EXCLUDE_COS) continue
            keptOther.add(otherPosCentered[i])
        }

        val negBoost = max(1, (keptBg.size.toFloat() / 25f).roundToInt())
        val corrections = ArrayList<FloatArray>()
        for (n in negCentered) {
            for (k in 0 until negBoost) {
                corrections.add(n)
            }
        }

        val allNegatives = ArrayList<FloatArray>(keptBg.size + keptOther.size + corrections.size)
        allNegatives.addAll(keptBg)
        allNegatives.addAll(keptOther)
        allNegatives.addAll(corrections)

        val (w, b) = fitLogistic(posCentered, allNegatives)

        var maxBgProb = 0f
        for (x in keptBg) {
            val p = headProb(w, b, x)
            if (p > maxBgProb) maxBgProb = p
        }
        val threshold = min(
            THRESHOLD_CAP,
            max(EXEMPLAR_PROB_THRESHOLD, maxBgProb + THRESHOLD_MARGIN)
        )

        var knnMu = 0f
        var knnSigma = 0f
        if (bgMaxCos.size >= KNN_MIN_BG) {
            var sum = 0f
            for (c in bgMaxCos) sum += c
            knnMu = sum / bgMaxCos.size
            var v = 0f
            for (c in bgMaxCos) v += (c - knnMu) * (c - knnMu)
            knnSigma = sqrt(v / bgMaxCos.size)
        }

        return LabelHead(
            label = label,
            category = category,
            w = w,
            b = b,
            threshold = threshold,
            protos = posRaw,
            negProtos = negRaw,
            knnMu = knnMu,
            knnSigma = knnSigma,
        )
    }

    data class ExemplarResult(
        val prob: Float,
        val matched: Boolean,
    )

    fun scoreExemplar(
        head: LabelHead,
        raw: FloatArray,
        centered: FloatArray,
    ): ExemplarResult {
        var prob = headProb(head.w, head.b, centered)
        var matched = prob >= head.threshold

        if (head.knnSigma > 0f && head.protos.isNotEmpty()) {
            val pos = FloatBlob.maxCosTo(raw, head.protos)
            val neg = if (head.negProtos.isNotEmpty()) FloatBlob.maxCosTo(raw, head.negProtos) else -1f
            if (pos > neg && pos >= KNN_MIN_COS) {
                val z = (pos - head.knnMu) / head.knnSigma
                if (z >= KNN_MIN_Z) {
                    matched = true
                    val pKnn = 1.0f / (1.0f + exp(-(z - KNN_MIN_Z + 0.5f)))
                    if (pKnn > prob) prob = pKnn
                }
            }
        }
        return ExemplarResult(prob, matched)
    }

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
        for (def in labels) {
            val margin = FloatBlob.dot(imageVec, def.vec) - bias
            if (margin < Recognition.MIN_LABEL_MARGIN) continue
            out.add(
                Tag(
                    label = def.label.lowercase(),
                    category = def.category,
                    score = Recognition.labelConfidence(margin),
                    source = "prompt",
                    scoreDomain = "calibrated",
                    confirmation = "unreviewed",
                    provenance = "bundled",
                )
            )
        }
        return out
    }

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
            val score = Recognition.labelConfidence(margin)
            out.add(
                CandidatePrompt(
                    tag = Tag(
                        label = def.label.lowercase(),
                        category = def.category,
                        score = score,
                        source = "prompt",
                        scoreDomain = "calibrated",
                        confirmation = "unreviewed",
                        provenance = "bundled",
                    ),
                    margin = margin,
                )
            )
        }
        return out
    }

    fun rankExemplarCandidates(
        imageVec: FloatArray,
        heads: List<LabelHead>,
        mean: FloatArray?,
    ): List<CandidatePrompt> {
        if (heads.isEmpty()) return emptyList()
        val centered = if (mean != null && mean.size == imageVec.size) {
            val out = FloatArray(imageVec.size)
            for (i in imageVec.indices) out[i] = imageVec[i] - mean[i]
            out
        } else {
            imageVec
        }

        val out = ArrayList<CandidatePrompt>(heads.size)
        for (h in heads) {
            val res = scoreExemplar(h, imageVec, centered)
            if (res.prob > 0.01f || res.matched) {
                out.add(
                    CandidatePrompt(
                        tag = Tag(
                            label = h.label.lowercase(),
                            category = h.category,
                            score = res.prob,
                            source = "exemplar",
                            scoreDomain = "head",
                            confirmation = "unreviewed",
                            provenance = "learned",
                        ),
                        margin = res.prob,
                    )
                )
            }
        }
        return out
    }

    fun classifyExemplars(
        imageVec: FloatArray,
        heads: List<LabelHead>,
        mean: FloatArray?,
    ): List<Tag> {
        if (heads.isEmpty()) return emptyList()
        val centered = if (mean != null && mean.size == imageVec.size) {
            val out = FloatArray(imageVec.size)
            for (i in imageVec.indices) out[i] = imageVec[i] - mean[i]
            out
        } else {
            imageVec
        }

        val out = ArrayList<Tag>()
        for (h in heads) {
            val res = scoreExemplar(h, imageVec, centered)
            if (res.matched) {
                out.add(
                    Tag(
                        label = h.label.lowercase(),
                        category = h.category,
                        score = res.prob,
                        source = "exemplar",
                        scoreDomain = "head",
                        confirmation = "unreviewed",
                        provenance = "learned",
                    )
                )
            }
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
