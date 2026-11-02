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

    /**
     * Call once every frame is in, before [nativePropagate]. Each separate piece of a frame's covered area may move a
     * few pixels onto where that piece of the logo really is.
     */
    external fun nativeSeal(handle: Long)

    /** Every pixel any frame covers, after [nativeSeal], into [holeOut]. */
    external fun nativeCoveredUnion(handle: Long, holeOut: ByteArray)

    /**
     * Finds real background for the covered pixels (matching frames, flow, camera motion); a frame keeps it only
     * where it continues the footage across the edge of the area.
     */
    external fun nativePropagate(handle: Long)

    /**
     * Frame [index] with its real fill in [rgbOut], and [holeOut] marking what the fill model must still fill. False
     * when nothing is open.
     */
    external fun nativeFillRequest(handle: Long, index: Int, rgbOut: ByteArray, holeOut: ByteArray): Boolean

    /** Frame [index] filled completely (the fill model's output for [nativeFillRequest]). */
    external fun nativeSetComplete(handle: Long, index: Int, rgb: ByteArray)

    /** Completes the frames the fill model didn't (plain fill) and smooths the fill through time. */
    external fun nativeFinish(handle: Long)

    /** Frame [index], finished. */
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
