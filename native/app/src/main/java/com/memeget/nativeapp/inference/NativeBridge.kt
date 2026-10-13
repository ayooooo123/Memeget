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

    external fun nativeEmbedText(
        handle: Long,
        modelPath: String,
        tokenizerPath: String,
        text: String,
        maxTokens: Int,
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
}
