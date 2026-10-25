package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.inference.NativeBridge
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.exp

/**
 * Live on-device Positive-Unlabeled + explicit-rejection residual scorer head (`deltaW`, `deltaB`).
 * Sits directly atop the 64-d penultimate identity features of [PairwiseVisualVerifier] (zero 407 MB
 * `d1-omni` GGUF bloat), adapting in <0.1 ms whenever Master confirms or rejects a label and persisting
 * across restarts.
 */
internal class OnDeviceDecisionHead(
    val hiddenDim: Int = HIDDEN_DIM,
) {
    companion object {
        const val HIDDEN_DIM = 64
        private const val FILE_MAGIC = 0x44314844 // "D1HD"
        private const val FILE_VERSION = 3

        private const val DELIM_BOS = "<|startoftext|>"
        private const val DELIM_STATE = "<|reserved_7|>"
        private const val DELIM_Q = "<|reserved_8|>"
        private const val DELIM_OPT = "<|reserved_9|>"
        private const val DELIM_OPT_END = "<|reserved_10|>"
        private const val DELIM_DECIDE = "<|reserved_11|>"
        private const val MARKER = "<|mask|>"
        private val SPECIAL_TOKEN_REGEX = Regex("<\\|([A-Za-z0-9_]+)\\|>")

        const val DEFAULT_INSTRUCTIONS =
            "Score each candidate meme reference, character, person, show, or format against this meme. " +
                "Every candidate that genuinely appears in the meme should score above none."

        fun escapeSpecialTokens(text: String): String =
            SPECIAL_TOKEN_REGEX.replace(text, "<¦$1¦>")

        fun sigmoid(x: Float): Float {
            val clamped = x.coerceIn(-20f, 20f)
            return (1.0 / (1.0 + exp(-clamped.toDouble()))).toFloat()
        }
    }

    data class CandidateInput(
        val label: String,
        val category: String,
        val posCos: Float,
        val seedCos: Float,
        val voteCount: Int,
        val support70: Int,
        val negCos: Float = -1f,
    )

    data class EvalSample(
        val optionKeys: List<String>,
        val noneIndex: Int,
        val baseLogits: FloatArray,
        val scorerHidden: FloatArray,
        val evalMs: Long = 0L,
        val promptHash: Int = 0,
    )

    data class TrainingExample(
        val sample: EvalSample,
        val confirmedPositives: Set<String>,
        val confirmedRejections: Set<String>,
        val hardAbstention: Boolean = false,
        val instanceWeight: Float = 1.0f,
    )

    val deltaW = FloatArray(hiddenDim)
    var deltaB: Float = 0f
    private val examplesByMemeId = LinkedHashMap<Long, TrainingExample>()

    fun reset() {
        deltaW.fill(0f)
        deltaB = 0f
    }

    fun clearAll() {
        reset()
        examplesByMemeId.clear()
    }

    fun exampleCount(): Int = examplesByMemeId.size

    fun cachedExample(memeId: Long): TrainingExample? = examplesByMemeId[memeId]

    /**
     * Updates or removes [memeId]'s training example, refits `deltaW` and `deltaB` across all cached
     * examples (using the `-O3` native kernel when available, falling back to Kotlin in JVM unit tests),
     * and returns the updated training loss.
     */
    fun upsertAndFit(
        memeId: Long,
        example: TrainingExample?,
        epochs: Int = 40,
        learningRate: Float = 2.0f,
        l2Decay: Float = 1e-3f,
        preferNativeKernel: Boolean = true,
    ): Float {
        if (example == null || (example.confirmedPositives.isEmpty() && example.confirmedRejections.isEmpty() && !example.hardAbstention)) {
            examplesByMemeId.remove(memeId)
        } else {
            examplesByMemeId[memeId] = example
        }
        reset()
        val all = examplesByMemeId.values.toList()
        if (all.isEmpty()) return 0f
        if (preferNativeKernel) {
            try {
                return fitViaNativeKernel(all, epochs, learningRate, l2Decay)
            } catch (_: UnsatisfiedLinkError) {
                // JVM unit tests run without libmemeget_inference.so loaded.
            }
        }
        return fit(all, epochs, learningRate, l2Decay)
    }

    private fun fitViaNativeKernel(
        examples: List<TrainingExample>,
        epochs: Int,
        learningRate: Float,
        l2Decay: Float,
    ): Float {
        val numEx = examples.size
        val maxOpts = examples.maxOf { it.sample.optionKeys.size }.coerceAtLeast(2)
        val flatLogits = FloatArray(numEx * maxOpts)
        val flatHidden = FloatArray(numEx * maxOpts * hiddenDim)
        val noneIndices = IntArray(numEx)
        val flatTargets = FloatArray(numEx * maxOpts)
        val flatWeights = FloatArray(numEx * maxOpts)

        for ((i, ex) in examples.withIndex()) {
            val s = ex.sample
            val kCount = s.optionKeys.size
            noneIndices[i] = s.noneIndex
            System.arraycopy(s.baseLogits, 0, flatLogits, i * maxOpts, kCount)
            System.arraycopy(s.scorerHidden, 0, flatHidden, i * maxOpts * hiddenDim, kCount * hiddenDim)
            for (k in 0 until kCount) {
                if (k == s.noneIndex) continue
                val key = s.optionKeys[k]
                when {
                    key in ex.confirmedPositives -> {
                        flatTargets[i * maxOpts + k] = 1f
                        flatWeights[i * maxOpts + k] = 1.5f * ex.instanceWeight
                    }
                    key in ex.confirmedRejections -> {
                        flatTargets[i * maxOpts + k] = 0f
                        flatWeights[i * maxOpts + k] = 2.5f * ex.instanceWeight
                    }
                    ex.hardAbstention -> {
                        flatTargets[i * maxOpts + k] = 0f
                        flatWeights[i * maxOpts + k] = 1.0f * ex.instanceWeight
                    }
                    else -> {
                        flatTargets[i * maxOpts + k] = 0f
                        flatWeights[i * maxOpts + k] = 1.0f * ex.instanceWeight
                    }
                }
            }
        }

        val outBLoss = floatArrayOf(deltaB, 0f)
        NativeBridge.nativeFitDecisionHead(
            flatLogits, flatHidden, noneIndices, flatTargets, flatWeights,
            numEx, maxOpts, hiddenDim, epochs, learningRate, l2Decay, deltaW, outBLoss,
        )
        deltaB = outBLoss[0]
        return outBLoss[1]
    }

    /** Atomically persists `deltaW`, `deltaB`, and all cached `examplesByMemeId` to [file]. */
    fun saveTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(FILE_MAGIC)
            out.writeInt(FILE_VERSION)
            out.writeInt(hiddenDim)
            out.writeFloat(deltaB)
            for (d in 0 until hiddenDim) out.writeFloat(deltaW[d])
            out.writeInt(examplesByMemeId.size)
            for ((memeId, ex) in examplesByMemeId) {
                out.writeLong(memeId)
                val s = ex.sample
                val k = s.optionKeys.size
                out.writeInt(k)
                for (key in s.optionKeys) out.writeUTF(key)
                out.writeInt(s.noneIndex)
                out.writeInt(s.promptHash)
                for (i in 0 until k) out.writeFloat(s.baseLogits[i])
                for (i in 0 until k * hiddenDim) out.writeFloat(s.scorerHidden[i])
                out.writeInt(ex.confirmedPositives.size)
                for (p in ex.confirmedPositives) out.writeUTF(p)
                out.writeInt(ex.confirmedRejections.size)
                for (r in ex.confirmedRejections) out.writeUTF(r)
                out.writeBoolean(ex.hardAbstention)
                out.writeFloat(ex.instanceWeight)
            }
            out.flush()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Loads `deltaW`, `deltaB`, and cached `examplesByMemeId` from [file] if valid. Returns true on success. */
    fun loadFrom(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                if (inp.readInt() != FILE_MAGIC) return false
                if (inp.readInt() != FILE_VERSION) return false
                if (inp.readInt() != hiddenDim) return false
                deltaB = inp.readFloat()
                for (d in 0 until hiddenDim) deltaW[d] = inp.readFloat()
                val count = inp.readInt()
                examplesByMemeId.clear()
                for (idx in 0 until count) {
                    val memeId = inp.readLong()
                    val k = inp.readInt()
                    val keys = ArrayList<String>(k)
                    for (i in 0 until k) keys += inp.readUTF()
                    val noneIdx = inp.readInt()
                    val promptHash = inp.readInt()
                    val logits = FloatArray(k) { inp.readFloat() }
                    val hidden = FloatArray(k * hiddenDim) { inp.readFloat() }
                    val posCount = inp.readInt()
                    val posSet = LinkedHashSet<String>(posCount)
                    for (i in 0 until posCount) posSet += inp.readUTF()
                    val rejCount = inp.readInt()
                    val rejSet = LinkedHashSet<String>(rejCount)
                    for (i in 0 until rejCount) rejSet += inp.readUTF()
                    val hardAbst = inp.readBoolean()
                    val weight = inp.readFloat()
                    examplesByMemeId[memeId] = TrainingExample(
                        sample = EvalSample(keys, noneIdx, logits, hidden, promptHash = promptHash),
                        confirmedPositives = posSet,
                        confirmedRejections = rejSet,
                        hardAbstention = hardAbst,
                        instanceWeight = weight,
                    )
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun renderPrompt(
        ocr: String,
        speech: String,
        caption: String,
        candidates: List<CandidateInput>,
        noneFirst: Boolean = false,
        instructions: String = DEFAULT_INSTRUCTIONS,
        visualCues: List<String> = emptyList(),
    ): Pair<String, List<String>> {
        val stateObj = JSONObject()
        val cleanOcr = escapeSpecialTokens(ocr.trim().replace(Regex("\\s+"), " ").take(80))
        val cleanSpeech = escapeSpecialTokens(speech.trim().replace(Regex("\\s+"), " ").take(80))
        val cleanCap = escapeSpecialTokens(caption.trim().replace(Regex("\\s+"), " ").take(80))
        val cleanCues = visualCues.map { escapeSpecialTokens(it.trim().lowercase()) }.filter { it.isNotEmpty() }.distinct().take(6).joinToString(", ")
        if (cleanOcr.isNotEmpty()) stateObj.put("ocr", cleanOcr)
        if (cleanSpeech.isNotEmpty()) stateObj.put("speech", cleanSpeech)
        else if (cleanCap.isNotEmpty()) stateObj.put("caption", cleanCap)
        if (cleanCues.isNotEmpty()) stateObj.put("cues", cleanCues)
        val sb = StringBuilder()
        sb.append(DELIM_BOS).append(DELIM_STATE).append(escapeSpecialTokens(stateObj.toString()))
        sb.append(DELIM_Q).append(escapeSpecialTokens(instructions))

        val keys = ArrayList<String>(candidates.size + 1)
        fun appendNone() {
            sb.append(DELIM_OPT).append(MARKER)
                .append(" none: Threshold baseline: none of the unconfirmed candidates apply")
                .append(DELIM_OPT_END)
            keys += "none"
        }
        if (noneFirst) appendNone()
        for (c in candidates) {
            val norm = TagMerge.normalizeTagLabel(c.label)
            if (norm.isEmpty()) continue
            val negPart = if (c.negCos > 0.50f) String.format(java.util.Locale.US, ", neg=%.2f", c.negCos) else ""
            val desc = String.format(
                java.util.Locale.US,
                "%s, cos=%.2f, seed=%.2f, vote=%d/5, sup70=%d%s",
                escapeSpecialTokens(c.category.ifBlank { "meme" }),
                c.posCos,
                c.seedCos,
                c.voteCount,
                c.support70,
                negPart,
            )
            sb.append(DELIM_OPT).append(MARKER).append(' ').append(escapeSpecialTokens(norm)).append(": ").append(desc).append(DELIM_OPT_END)
            keys += norm
        }
        if (!noneFirst) appendNone()
        sb.append(DELIM_DECIDE)
        return sb.toString() to keys
    }

    fun unpackNativeOutput(flat: FloatArray, optionKeys: List<String>, promptHash: Int = 0): EvalSample {
        require(flat.size >= 2) { "Native decision output is too short" }
        val k = flat[0].toInt()
        val evalMs = flat[1].toLong()
        require(k == optionKeys.size) { "Expected ${optionKeys.size} option markers, got $k" }
        require(flat.size >= 2 + k + k * hiddenDim) { "Native decision output is missing scorer hidden states" }
        val baseLogits = flat.copyOfRange(2, 2 + k)
        val hidden = flat.copyOfRange(2 + k, 2 + k + k * hiddenDim)
        val noneIdx = optionKeys.indexOf("none").also { require(it >= 0) { "Missing none baseline option" } }
        return EvalSample(optionKeys, noneIdx, baseLogits, hidden, evalMs, promptHash)
    }

    fun scoreCandidates(sample: EvalSample): Map<String, Float> {
        val noneIdx = sample.noneIndex
        val zNone = sample.baseLogits[noneIdx]
        val noneOff = noneIdx * hiddenDim
        val out = LinkedHashMap<String, Float>()
        for (k in sample.optionKeys.indices) {
            if (k == noneIdx) continue
            val key = sample.optionKeys[k]
            val kOff = k * hiddenDim
            var residual = deltaB
            for (d in 0 until hiddenDim) {
                residual += deltaW[d] * (sample.scorerHidden[kOff + d] - sample.scorerHidden[noneOff + d])
            }
            val relLogit = (sample.baseLogits[k] - zNone) + residual
            out[key] = sigmoid(relLogit)
        }
        return out
    }

    fun fit(
        examples: List<TrainingExample>,
        epochs: Int = 15,
        learningRate: Float = 0.05f,
        l2Decay: Float = 1e-3f,
    ): Float {
        if (examples.isEmpty()) return 0f
        val gradW = FloatArray(hiddenDim)
        var lastLoss = 0f

        for (ep in 0 until epochs) {
            val lr = learningRate / (1f + 0.1f * ep)
            var epochLoss = 0f
            var totalWeight = 0f

            for (ex in examples) {
                val s = ex.sample
                val noneIdx = s.noneIndex
                val zNone = s.baseLogits[noneIdx]
                val noneOff = noneIdx * hiddenDim
                for (k in s.optionKeys.indices) {
                    if (k == noneIdx) continue
                    val key = s.optionKeys[k]
                    val target: Float
                    val weight: Float
                    when {
                        key in ex.confirmedPositives -> {
                            target = 1f
                            weight = 1.5f * ex.instanceWeight
                        }
                        key in ex.confirmedRejections -> {
                            target = 0f
                            weight = 2.5f * ex.instanceWeight
                        }
                        ex.hardAbstention -> {
                            target = 0f
                            weight = 1.0f * ex.instanceWeight
                        }
                        else -> {
                            target = 0f
                            weight = 1.0f * ex.instanceWeight
                        }
                    }

                    val kOff = k * hiddenDim
                    var residual = deltaB
                    var normSq = 0f
                    for (d in 0 until hiddenDim) {
                        val dv = s.scorerHidden[kOff + d] - s.scorerHidden[noneOff + d]
                        residual += deltaW[d] * dv
                        normSq += dv * dv
                    }
                    val rel = (s.baseLogits[k] - zNone) + residual
                    val prob = sigmoid(rel)
                    val diff = (prob - target) * weight
                    val step = (lr * diff) / maxOf(4f, normSq)
                    val decay = 1f - lr * l2Decay
                    for (d in 0 until hiddenDim) {
                        deltaW[d] = deltaW[d] * decay - step * (s.scorerHidden[kOff + d] - s.scorerHidden[noneOff + d])
                    }
                    // Keep deltaB at 0 so multi-label positive examples only update candidate-specific directions in deltaW.

                    val clamped = rel.coerceIn(-20f, 20f)
                    val bce = (kotlin.math.ln(1.0 + exp(clamped.toDouble())) - target * clamped).toFloat()
                    epochLoss += bce * weight
                    totalWeight += weight
                }
            }
            lastLoss = if (totalWeight > 0f) epochLoss / totalWeight else 0f
        }
        return lastLoss
    }
}
