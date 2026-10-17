package com.memeget.nativeapp.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.abs

/**
 * Drops video frames that look the same as an earlier one, so a caption isn't written three times for a still
 * picture with sound: of the first 265 videos captioned on the Pixel 5a, 69 got the same caption from all three
 * frames, each costing about 25 s. Strict on purpose: a changed subtitle or a moved face keeps the frame.
 */
internal object FrameLikeness {
    /** Frames are compared as [SIDE]×[SIDE] grids of red, green and blue values. */
    const val SIDE = 128
    /** A pixel changed when any of its colors moved by more than this (of 255); compression noise stays below. */
    private const val CHANGED_LEVEL = 40
    /** Frames look the same when at most this many of the grid's pixels changed (0.1%). */
    private const val MAX_CHANGED_PIXELS = SIDE * SIDE / 1000

    /** [frames] (file URIs of the model's input JPEGs) without the ones that look like an earlier one, in order. */
    fun distinct(frames: List<String>): List<String> {
        if (frames.size < 2) return frames
        val kept = ArrayList<String>(frames.size)
        val keptPixels = ArrayList<IntArray>(frames.size)
        for (frame in frames) {
            // A frame that can't be read is kept: the caption step reports it, as before.
            val pixels = pixels(frame)
            if (pixels == null || keptPixels.none { looksTheSame(it, pixels) }) {
                kept += frame
                if (pixels != null) keptPixels += pixels
            }
        }
        return kept
    }

    /** Both are [SIDE]×[SIDE] grids of packed RGB pixels. */
    fun looksTheSame(a: IntArray, b: IntArray): Boolean {
        require(a.size == b.size) { "Frames must be compared at the same size" }
        var changed = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            if ((abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) > CHANGED_LEVEL ||
                    abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) > CHANGED_LEVEL ||
                    abs((p and 0xFF) - (q and 0xFF)) > CHANGED_LEVEL) &&
                ++changed > MAX_CHANGED_PIXELS
            ) return false
        }
        return true
    }

    private fun pixels(frame: String): IntArray? {
        val path = Uri.parse(frame).path ?: return null
        val decoded = BitmapFactory.decodeFile(path) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, SIDE, SIDE, true)
        try {
            val pixels = IntArray(SIDE * SIDE)
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            return pixels
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
