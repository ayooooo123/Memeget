package com.memeget.nativeapp.editor

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/** OpenCV kernels in libmemeget_inference.so (LogoRemoval.cpp). */
internal object LogoRemovalNative {
    init {
        System.loadLibrary("memeget_inference")
    }

    /**
     * Best place for [templ] inside [image] (both 8-bit grey rows), comparing only where [mask] is nonzero when
     * given. Writes x, y of its top-left corner and the normalized correlation (-1..1) to [out]; false when the
     * sizes don't fit.
     */
    external fun nativeMatchTemplate(
        image: ByteArray,
        width: Int,
        height: Int,
        templ: ByteArray,
        templWidth: Int,
        templHeight: Int,
        mask: ByteArray?,
        out: FloatArray,
    ): Boolean

    /**
     * A painted mask over [bitmap] (same size, one byte per pixel) narrowed to the logo's own pixels, reaching past
     * the paint for parts of the logo it missed; [whole] is the whole picture, scaled down, whose colours tell the
     * logo's apart. Null when too little stands out to trust.
     */
    external fun nativeRefineMask(bitmap: Bitmap, mask: ByteArray, whole: Bitmap): ByteArray?

    /**
     * MI-GAN's input [tensor] (4 × [size] × [size]) for [rgb] ([w]×[h], 3 bytes a pixel) with [hole] (nonzero: fill):
     * padded to a square by repeating edge pixels, scaled, concat(mask − 0.5, rgb · mask) channels first.
     */
    external fun nativeMiganInput(rgb: ByteArray, w: Int, h: Int, hole: ByteArray, size: Int, tensor: FloatArray)

    /** [rgb] with its [hole] pixels from MI-GAN's [generated] output (3 × [size] × [size]) scaled back, into [out]. */
    external fun nativeMiganOutput(
        generated: FloatArray, size: Int, rgb: ByteArray, w: Int, h: Int, hole: ByteArray, out: ByteArray,
    )

    private external fun nativeInpaint(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        mask: ByteArray?,
        maskWidth: Int,
        maskHeight: Int,
    ): Boolean

    /**
     * Fills [rect] (bitmap pixels, may run past the bitmap) of a mutable ARGB_8888 [bitmap] from what surrounds
     * it; only where [mask] ([maskWidth]×[maskHeight], stretched over [rect]) is set, when given.
     */
    fun inpaint(bitmap: Bitmap, rect: RectF, mask: ByteArray? = null, maskWidth: Int = 0, maskHeight: Int = 0): Boolean =
        nativeInpaint(
            bitmap,
            floor(rect.left).toInt(),
            floor(rect.top).toInt(),
            ceil(rect.right).toInt(),
            ceil(rect.bottom).toInt(),
            mask,
            maskWidth,
            maskHeight
        )
}
