package com.memeget.nativeapp.editor

/** Flow-guided video completion in libmemeget_inference.so (VideoCompletion.cpp); one handle per region. */
internal object VideoCompletionNative {
    init {
        System.loadLibrary("memeget_inference")
    }

    /** A job for [frames] crops of [width]×[height]; scratch files go in [scratchDir] and vanish on their own. */
    external fun nativeCreate(width: Int, height: Int, frames: Int, scratchDir: String): Long

    /** Frame [index]'s crop: [rgb] (3 bytes a pixel) and [hole] (nonzero where the logo covers it). */
    external fun nativeAddFrame(handle: Long, index: Int, rgb: ByteArray, hole: ByteArray)

    /** Call once every frame is in, before [nativePropagate]. */
    external fun nativeSeal(handle: Long)

    /** Carries real background through time; returns each frame's count of covered pixels still open. */
    external fun nativePropagate(handle: Long): IntArray

    /** Frame [index] with what is known so far filled in, and [holeOut] marking what is still open. */
    external fun nativeComposite(handle: Long, index: Int, rgbOut: ByteArray, holeOut: ByteArray)

    /** Frame [index] filled completely (after inpainting); it becomes a source for the other frames. */
    external fun nativeSetComplete(handle: Long, index: Int, rgb: ByteArray)

    /** Fills whatever is still open from its surroundings. */
    external fun nativeFinish(handle: Long)

    external fun nativeResult(handle: Long, index: Int, rgbOut: ByteArray)

    external fun nativeCancel(handle: Long)

    external fun nativeDestroy(handle: Long)

    /** RGB (3 bytes a pixel, [outW]×[outH]) of an upright region of a YUV 4:2:0 frame. */
    external fun nativeYuvToRgb(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer,
        yRowStride: Int, yPixelStride: Int, uvRowStride: Int, uvPixelStride: Int,
        cropLeft: Int, cropTop: Int, codedW: Int, codedH: Int, rotation: Int,
        regionLeft: Float, regionTop: Float, regionW: Float, regionH: Float,
        outW: Int, outH: Int, bt709: Boolean, fullRange: Boolean, out: ByteArray,
    )

    /** The upright Y plane sampled to [outW]×[outH]. */
    external fun nativeLuma(
        y: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int,
        cropLeft: Int, cropTop: Int, codedW: Int, codedH: Int, rotation: Int,
        outW: Int, outH: Int, out: ByteArray,
    )
}
