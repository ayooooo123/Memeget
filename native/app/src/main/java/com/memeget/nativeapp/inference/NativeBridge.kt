package com.memeget.nativeapp.inference

/**
 * Low-level JNI bindings to libmemeget_inference.so (ExecuTorch 0.9.2 runtime plus llama.cpp b11588 for MiniCPM-V).
 */
internal class RawOcrDetection(
    val text: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal class RawFastSamInstance(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val score: Float,
    val mask: ByteArray,
)

internal object NativeBridge {
    init {
        System.loadLibrary("memeget_inference")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeResetCancel(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeDiagnostics(handle: Long): String

    external fun nativeEmbedImage(
        handle: Long,
        modelPath: String,
        imagePath: String,
        inputSize: Int,
        normalize: Boolean,
    ): FloatArray

    /** [tokenIds]: [ClipTokenizer.CONTEXT_LENGTH] MobileCLIP-S2 ids from [ClipTokenizer.encode]. */
    external fun nativeEmbedTextTokens(
        handle: Long,
        modelPath: String,
        tokenIds: LongArray,
    ): FloatArray

    external fun nativeRecognize(
        handle: Long,
        detectorPath: String,
        recognizerPath: String,
        imagePath: String,
    ): Array<RawOcrDetection>

    external fun nativeTranscribe(
        handle: Long,
        modelPath: String,
        tokenizerPath: String,
        pcmSamples: FloatArray,
    ): String

    external fun nativeSegment(
        handle: Long,
        modelPath: String,
        imagePath: String,
        maxInstances: Int,
        confThresh: Float,
        iouThresh: Float,
    ): Array<RawFastSamInstance>

    external fun nativeDescribe(
        handle: Long,
        modelPath: String,
        projectorPath: String,
        imagePath: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        maxImagePixels: Int,
    ): String

    /** Writes an F16 copy of a BF16 GGUF projector; returns the number of converted tensors. */
    external fun nativeConvertProjector(
        handle: Long,
        sourcePath: String,
        destinationPath: String,
    ): Int

    /** For each row of [dim] floats in [vectors], how many other rows reach [minCos] with it. */
    external fun nativeLookalikeCounts(vectors: FloatArray, dim: Int, minCos: Float): IntArray

    /** Rows other than [row], not marked in [covered], that reach [minCos] with row [row]. */
    external fun nativeLookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray

    /** See [com.memeget.nativeapp.knowledge.LookalikeKernels.nearest]. */
    external fun nativeNearest(
        queries: FloatArray, queryOwners: LongArray, examples: FloatArray, exampleOwners: LongArray,
        dim: Int, k: Int, skipAtOrAbove: Float, outIndex: IntArray, outDot: FloatArray,
    )

    /**
     * Evaluates a rendered d1-omni prompt in one non-causal pass (`LlamaDecisionRunner`).
     * Returns `[numOptions, evalMs, baseLogits[0..K-1], scorerHidden[0..K*1024-1]]`.
     */
    external fun nativeDecide(modelPath: String, renderedPrompt: String, threads: Int): FloatArray

    external fun nativeFitDecisionHead(
        baseLogits: FloatArray,
        scorerHidden: FloatArray,
        noneIndices: IntArray,
        targets: FloatArray,
        weights: FloatArray,
        numExamples: Int,
        numOptions: Int,
        hiddenDim: Int,
        epochs: Int,
        learningRate: Float,
        l2Decay: Float,
        deltaW: FloatArray,
        outDeltaBAndLoss: FloatArray,
    )
}
