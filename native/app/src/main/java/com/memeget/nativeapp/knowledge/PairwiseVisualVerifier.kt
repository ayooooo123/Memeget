package com.memeget.nativeapp.knowledge

import android.content.Context
import com.memeget.nativeapp.data.FloatBlob
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compact (~543 KB FP16) pairwise visual-concept verifier (`assets/knowledge/pairwise_verifier.bin`)
 * trained over 10.7k multi-label memes + hard look-alike negatives. Projects both the 512-d
 * MobileCLIP-S2 image vector and each candidate's 512-d prototype into a 192-d discriminative
 * identity subspace (`u * w`, `|u - w|`, plus 11 retrieval/modality features), emitting both a
 * calibrated base logit and 64-d penultimate features for live Positive-Unlabeled adaptation in
 * [OnDeviceDecisionHead] — replacing the 407 MB text-only `d1-omni` GGUF in <0.1 ms on CPU.
 */
internal class PairwiseVisualVerifier private constructor(
    val inDim: Int,
    val projDim: Int,
    val scalarDim: Int,
    val h1Dim: Int,
    val headDim: Int,
    private val wProj: FloatArray,
    private val lnProjW: FloatArray,
    private val lnProjB: FloatArray,
    private val wFc1: FloatArray,
    private val bFc1: FloatArray,
    private val wFc2: FloatArray,
    private val bFc2: FloatArray,
    private val wOut: FloatArray,
    private val bOut: Float,
) {
    val pairDim: Int = projDim * 2 + scalarDim
    private val cachedCandProj = ConcurrentHashMap<String, FloatArray>(512)

    data class VerifierCandidate(
        val key: String,
        val protoVec: FloatArray,
        val textVec: FloatArray? = null,
        val cacheKey: String? = null,
        val calPosCos: Float,
        val tagMean: Float = 0.20f,
        val tagStd: Float = 0.045f,
        val hasStats: Boolean = false,
        val negCos: Float = -1f,
        val voteCount: Int = 0,
        val support70: Int = 0,
        val ocrHit: Boolean = false,
        val isEntity: Boolean = false,
    )

    fun projectVector(vec: FloatArray, cacheKey: String? = null): FloatArray {
        if (cacheKey != null) {
            cachedCandProj[cacheKey]?.let { return it }
        }
        val raw = FloatArray(projDim)
        for (r in 0 until projDim) {
            val rowOff = r * inDim
            var acc = 0f
            for (c in 0 until inDim) acc += wProj[rowOff + c] * vec[c]
            raw[r] = acc
        }
        val normed = layerNormInPlace(raw, lnProjW, lnProjB)
        if (cacheKey != null && cachedCandProj.size < 8192) {
            cachedCandProj[cacheKey] = normed
        }
        return normed
    }

    fun projectImage(vImg: FloatArray): FloatArray = projectVector(vImg, cacheKey = null)

    fun projectCandidate(vCand: FloatArray, cacheKey: String? = null): FloatArray = projectVector(vCand, cacheKey = cacheKey)

    /**
     * Scores [candidates] against [vImg] in one pass (~0.08 ms for 10 candidates) and packages
     * the base logits + 64-d penultimate features with a zeroed `none` anchor for [OnDeviceDecisionHead].
     */
    fun evaluate(vImg: FloatArray, candidates: List<VerifierCandidate>, promptHash: Int = 0): OnDeviceDecisionHead.EvalSample {
        require(vImg.size == inDim) { "Expected $inDim-d image vector, got ${vImg.size}" }
        val u = projectImage(vImg)
        val kCount = candidates.size
        val totalOpts = kCount + 1
        val optionKeys = ArrayList<String>(totalOpts)
        val baseLogits = FloatArray(totalOpts)
        val scorerHidden = FloatArray(totalOpts * headDim)
        val pair = FloatArray(pairDim)
        val h1 = FloatArray(h1Dim)

        for (k in 0 until kCount) {
            val c = candidates[k]
            optionKeys += c.key
            val w = projectCandidate(c.protoVec, c.cacheKey)
            for (d in 0 until projDim) {
                val ud = u[d]
                val wd = w[d]
                pair[d] = ud * wd
                pair[projDim + d] = abs(ud - wd)
            }
            val rawVisCos = FloatBlob.dot(vImg, c.protoVec)
            val txtCos = if (c.textVec != null && c.textVec.size == inDim) FloatBlob.dot(vImg, c.textVec) else rawVisCos
            val zScaled = if (c.hasStats) {
                val z = (txtCos - c.tagMean) / max(c.tagStd, 0.025f)
                z.coerceIn(-4f, 8f) * 0.125f
            } else {
                ((txtCos - 0.20f) / 0.045f).coerceIn(-4f, 8f) * 0.125f
            }
            val hasNeg = c.negCos > 0.30f
            val negClamped = if (hasNeg) c.negCos else 0f
            val negMargin = if (hasNeg) (c.negCos - rawVisCos).coerceIn(-1f, 1f) else -0.5f

            val sBase = projDim * 2
            pair[sBase + 0] = rawVisCos
            pair[sBase + 1] = c.calPosCos
            pair[sBase + 2] = txtCos
            pair[sBase + 3] = zScaled
            pair[sBase + 4] = if (hasNeg) 1f else 0f
            pair[sBase + 5] = negClamped
            pair[sBase + 6] = negMargin
            pair[sBase + 7] = min(1f, c.voteCount / 5f)
            pair[sBase + 8] = min(1f, c.support70 / 5f)
            pair[sBase + 9] = if (c.ocrHit) 1f else 0f
            pair[sBase + 10] = if (c.isEntity) 1f else 0f

            for (i in 0 until h1Dim) {
                val rowOff = i * pairDim
                var sum = bFc1[i]
                for (j in 0 until pairDim) sum += wFc1[rowOff + j] * pair[j]
                h1[i] = silu(sum)
            }

            var logit = bOut
            val h2Off = k * headDim
            for (i in 0 until headDim) {
                val rowOff = i * h1Dim
                var sum = bFc2[i]
                for (j in 0 until h1Dim) sum += wFc2[rowOff + j] * h1[j]
                val act = silu(sum)
                scorerHidden[h2Off + i] = act
                logit += wOut[i] * act
            }
            baseLogits[k] = logit
        }

        // Baseline `none` option sits at 0 logit and 0 hidden vector so relLogit = baseLogits[k] + deltaW · h2_k.
        optionKeys += "none"
        baseLogits[kCount] = 0f
        return OnDeviceDecisionHead.EvalSample(
            optionKeys = optionKeys,
            noneIndex = kCount,
            baseLogits = baseLogits,
            scorerHidden = scorerHidden,
            evalMs = 0L,
            promptHash = promptHash,
        )
    }

    private fun layerNormInPlace(x: FloatArray, gamma: FloatArray, beta: FloatArray): FloatArray {
        val n = x.size
        var mean = 0f
        for (v in x) mean += v
        mean /= n
        var varSum = 0f
        for (v in x) {
            val d = v - mean
            varSum += d * d
        }
        val invStd = 1f / sqrt(varSum / n + 1e-5f)
        for (i in 0 until n) {
            x[i] = (x[i] - mean) * invStd * gamma[i] + beta[i]
        }
        return x
    }

    private fun silu(x: Float): Float {
        if (x >= 20f) return x
        if (x <= -20f) return 0f
        return x / (1f + exp(-x))
    }

    companion object {
        const val MAGIC = 0x4D475056 // 'MGPV'
        const val VERSION = 2
        const val ASSET_PATH = "knowledge/pairwise_verifier.bin"

        @Volatile private var cached: PairwiseVisualVerifier? = null

        fun load(context: Context?): PairwiseVisualVerifier? {
            cached?.let { return it }
            val app = context ?: return null
            return synchronized(this) {
                cached?.let { return@synchronized it }
                runCatching {
                    app.assets.open(ASSET_PATH).use { stream ->
                        loadFromStream(stream).also { cached = it }
                    }
                }.getOrNull()
            }
        }

        fun loadFromStream(raw: java.io.InputStream): PairwiseVisualVerifier {
            DataInputStream(raw.buffered()).use { inp ->
                require(inp.readInt() == MAGIC) { "Invalid pairwise_verifier magic" }
                require(inp.readInt() == VERSION) { "Unsupported pairwise_verifier version" }
                val inDim = inp.readInt()
                val projDim = inp.readInt()
                val scalarDim = inp.readInt()
                val h1Dim = inp.readInt()
                val headDim = inp.readInt()
                require(inDim == 512 && projDim in 32..512 && scalarDim == 11 && h1Dim in 32..512 && headDim in 16..256) {
                    "Unexpected pairwise_verifier dimensions: $inDim/$projDim/$scalarDim/$h1Dim/$headDim"
                }
                val pairDim = projDim * 2 + scalarDim

                fun readFp16Array(count: Int): FloatArray {
                    val buf = ByteArray(count * 2)
                    inp.readFully(buf)
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
                    return FloatArray(count) { halfToFloat(bb.short.toInt() and 0xFFFF) }
                }

                val wProj = readFp16Array(projDim * inDim)
                val lnProjW = readFp16Array(projDim)
                val lnProjB = readFp16Array(projDim)
                val wFc1 = readFp16Array(h1Dim * pairDim)
                val bFc1 = readFp16Array(h1Dim)
                val wFc2 = readFp16Array(headDim * h1Dim)
                val bFc2 = readFp16Array(headDim)
                val wOut = readFp16Array(headDim)
                val bOut = readFp16Array(1)[0]

                return PairwiseVisualVerifier(
                    inDim = inDim,
                    projDim = projDim,
                    scalarDim = scalarDim,
                    h1Dim = h1Dim,
                    headDim = headDim,
                    wProj = wProj, lnProjW = lnProjW, lnProjB = lnProjB,
                    wFc1 = wFc1, bFc1 = bFc1,
                    wFc2 = wFc2, bFc2 = bFc2,
                    wOut = wOut, bOut = bOut,
                )
            }
        }

        private fun halfToFloat(hbits: Int): Float {
            var mant = hbits and 0x03ff
            var exp = hbits and 0x7c00
            if (exp == 0x7c00) {
                exp = 0x3fc00
            } else if (exp != 0) {
                exp += 0x1c000
            } else if (mant != 0) {
                exp = 0x1c400
                do {
                    mant = mant shl 1
                    exp -= 0x400
                } while ((mant and 0x400) == 0)
                mant = mant and 0x3ff
            }
            return Float.fromBits(((hbits and 0x8000) shl 16) or ((exp or mant) shl 13))
        }
    }
}
