package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The rebuilt background behind a removed logo, frame by frame: for each frame that showed the logo, a patch of
 * the source picture (source coordinates, so crops and rotations keep it in place) with an alpha mask that fades
 * at its edge. Stored as a directory of JPEG frames, PNG alpha masks and `index.json`.
 */
internal class LogoPatches private constructor(
    private val dir: File,
    private val frameTimesUs: LongArray,
    private val regions: List<Region>,
    private val frames: Map<Int, Pair<Int, String>>,
) {
    /** A patch's place in the source picture (normalized) and its alpha file. */
    class Region(val rect: NormalizedRect, val alphaFile: String)

    private val alphaCache = HashMap<String, IntArray>()
    private var cachedFrame = -1
    private var cachedBitmap: Bitmap? = null
    private val matrix = Matrix()
    /**
     * Smooth scaling still, but each drawn pixel's alpha snapped to fully on or off. Media3 blends the overlay canvas
     * as straight alpha while Android draws it premultiplied, so any half-transparent pixel at the patch's edge came
     * out too dark: a thin outline of the removed logo's shape in preview and export. The colour matrix works on
     * unpremultiplied values, so the colours stay right.
     */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, ALPHA_SNAP, 127.5f - ALPHA_SNAP * 127.5f,
                )
            )
        )
    }
    private val src = FloatArray(6)
    private val dst = FloatArray(6)

    /** The frame shown at [sourceUs] (see [frameAt] in the companion). */
    fun frameAt(sourceUs: Long): Int = frameAt(frameTimesUs, sourceUs)

    /** Draws frame [frame]'s patch onto a working canvas of [outW]×[outH] under [base]; nothing when it has none. */
    fun draw(canvas: Canvas, frame: Int, base: BaseTransform, outW: Int, outH: Int) {
        val (regionIndex, file) = frames[frame] ?: return
        val region = regions[regionIndex]
        val bitmap = bitmapFor(frame, region, file) ?: return
        val r = region.rect
        val corners = listOf(
            NormalizedPoint(r.x, r.y),
            NormalizedPoint(r.x + r.width, r.y),
            NormalizedPoint(r.x, r.y + r.height)
        ).map { ProjectCore.sourceToWorking(it, base) }
        src[0] = 0f; src[1] = 0f
        src[2] = bitmap.width.toFloat(); src[3] = 0f
        src[4] = 0f; src[5] = bitmap.height.toFloat()
        for (i in 0 until 3) {
            dst[i * 2] = corners[i].x * outW
            dst[i * 2 + 1] = corners[i].y * outH
        }
        matrix.setPolyToPoly(src, 0, dst, 0, 3)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun bitmapFor(frame: Int, region: Region, file: String): Bitmap? {
        if (frame == cachedFrame) return cachedBitmap
        val rgb = BitmapFactory.decodeFile(File(dir, file).path) ?: return null
        val w = rgb.width
        val h = rgb.height
        val alpha = alphaCache.getOrPut(region.alphaFile) {
            val mask = BitmapFactory.decodeFile(File(dir, region.alphaFile).path)
            IntArray(w * h).also { out ->
                if (mask != null) {
                    val scaled = if (mask.width == w && mask.height == h) mask else Bitmap.createScaledBitmap(mask, w, h, true)
                    scaled.getPixels(out, 0, w, 0, 0, w, h)
                    if (scaled !== mask) scaled.recycle()
                    mask.recycle()
                }
            }
        }
        val pixels = IntArray(w * h)
        rgb.getPixels(pixels, 0, w, 0, 0, w, h)
        rgb.recycle()
        for (i in pixels.indices) pixels[i] = (alpha[i] and 0xff000000.toInt()) or (pixels[i] and 0xffffff)
        cachedBitmap?.recycle()
        cachedBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        cachedFrame = frame
        return cachedBitmap
    }

    fun release() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedFrame = -1
    }

    /** Builds a patch directory frame by frame. */
    class Writer(context: Context, private val frameTimesUs: LongArray) {
        val dir = File(File(context.filesDir, "editor_assets"), "patch_${UUID.randomUUID()}").apply { mkdirs() }
        private val regions = JSONArray()
        private val frames = JSONArray()

        /** A region whose patches share [alpha] (0..255 a pixel, [w]×[h]); returns its index. */
        fun addRegion(rect: NormalizedRect, alpha: ByteArray, w: Int, h: Int): Int {
            val name = "a${regions.length()}.png"
            val bitmap = Bitmap.createBitmap(IntArray(w * h) { (alpha[it].toInt() and 0xff) shl 24 or 0xffffff }, w, h, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            regions.put(JSONObject().put("rect", rect.toJson()).put("alpha", name))
            return regions.length() - 1
        }

        /** Frame [frame]'s patch for [region]: [rgb] ([w]×[h], 3 bytes a pixel); lossless for stills. */
        fun addFrame(frame: Int, region: Int, rgb: ByteArray, w: Int, h: Int, lossless: Boolean = false) {
            val name = if (lossless) "f_$frame.png" else "f_%05d.jpg".format(frame)
            val bitmap = Bitmap.createBitmap(IntArray(w * h) {
                (0xff shl 24) or ((rgb[it * 3].toInt() and 0xff) shl 16) or
                    ((rgb[it * 3 + 1].toInt() and 0xff) shl 8) or (rgb[it * 3 + 2].toInt() and 0xff)
            }, w, h, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(File(dir, name)).use {
                    bitmap.compress(if (lossless) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (lossless) 100 else 95, it)
                }
            } finally {
                bitmap.recycle()
            }
            frames.put(JSONObject().put("i", frame).put("r", region).put("f", name))
        }

        /** Writes the index; returns the directory's URI for [MemeEditLayer.Cover.patchUri]. */
        fun finish(): String {
            val index = JSONObject()
                .put("version", 1)
                .put("frameTimesUs", JSONArray().apply { frameTimesUs.forEach { put(it) } })
                .put("regions", regions)
                .put("frames", frames)
            File(dir, "index.json").writeText(index.toString())
            return Uri.fromFile(dir).toString()
        }

        fun abandon() {
            dir.deleteRecursively()
        }
    }

    companion object {
        /** Slack (µs) for a time the player rounded to just before a frame's own timestamp (time bases round to µs). */
        private const val TIME_SLACK_US = 100L
        /** Steepness of the alpha snap: alpha within 1/[ALPHA_SNAP] of half on either side is all that stays partial. */
        private const val ALPHA_SNAP = 64f

        /**
         * The frame of [frameTimesUs] (sorted start times) shown at [sourceUs]: the one that starts exactly then,
         * else the one whose interval contains it. A time a hair short of the next frame's start (rounding, at most
         * [TIME_SLACK_US] or a quarter of that interval) counts as that frame. Frame rates may vary, so no slack is
         * taken from any one interval.
         */
        fun frameAt(frameTimesUs: LongArray, sourceUs: Long): Int {
            if (frameTimesUs.size < 2) return 0
            val found = frameTimesUs.binarySearch(sourceUs)
            if (found >= 0) return found
            val next = -found - 1
            if (next in 1 until frameTimesUs.size) {
                val slack = minOf(TIME_SLACK_US, (frameTimesUs[next] - frameTimesUs[next - 1]) / 4)
                if (frameTimesUs[next] - sourceUs <= slack) return next
            } else if (next == 0) {
                return 0
            }
            return (next - 1).coerceIn(0, frameTimesUs.size - 1)
        }

        fun load(uri: String): LogoPatches {
            val dir = File(Uri.parse(uri).path ?: throw java.io.IOException("Bad patch location"))
            val index = JSONObject(File(dir, "index.json").readText())
            val times = index.getJSONArray("frameTimesUs").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val regions = index.getJSONArray("regions").let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    Region(NormalizedRect.fromJson(o.getJSONObject("rect")), o.getString("alpha"))
                }
            }
            val frames = HashMap<Int, Pair<Int, String>>()
            index.getJSONArray("frames").let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    frames[o.getInt("i")] = o.getInt("r") to o.getString("f")
                }
            }
            return LogoPatches(dir, times, regions, frames)
        }
    }
}
