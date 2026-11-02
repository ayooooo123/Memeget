package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rebuilds the background behind a removed logo. Video: real background carried from the frames that show it
 * (VideoCompletion.cpp), MI-GAN for what no frame shows, the plain fill for anything left. Still images: MI-GAN.
 */
internal object LogoFill {
    /**
     * Scratch space for one region: every frame's crop (3 bytes a pixel) and two half-resolution flows (16 bytes
     * per 4 pixels). Regions are worked at the video's own resolution unless that would exceed this; resampling
     * would blur the rebuilt background against the footage around it.
     */
    private const val SCRATCH_BUDGET_BYTES = 900L * 1024 * 1024
    private const val MAX_REGION_EDGE = 1600
    private const val PATCH_MARGIN_PX = 6
    private const val FEATHER_PX = 3
    // Keyframes inpainted until every frame has a source: what the plain directional fill leaves is ragged and
    // flickers. The cap bounds the time on footage where flow carries keyframes poorly.
    private const val MAX_INPAINT_ROUNDS = 8

    /**
     * Patches for every frame of [track] that shows the logo; [mask] ([maskW]×[maskH], source orientation) is the
     * logo's shape over each run's rectangle. [inpainter] may be null (no fill model on this phone). Returns the
     * patch directory URI. [onProgress] gets 0..1.
     */
    suspend fun video(
        context: Context,
        uri: String,
        track: LogoTracker.Track,
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
        inpainter: MiganInpainter?,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val video = track.video
        val frameCount = video.frameTimesUs.size
        val runOf = arrayOfNulls<NormalizedRect>(frameCount)
        for (run in track.runs) for (f in run.startFrame until run.endFrame) runOf[f] = run.sourceRect
        val positions = track.runs.mapNotNull { it.sourceRect }.distinct()
        if (positions.isEmpty()) throw IOException("The logo wasn't found in the video")
        // Tracking reports a slightly different box whenever the match wobbles by a pixel; each pass decodes and
        // rebuilds the whole video, so overlapping places share one.
        val groups = groupOverlapping(positions)

        val writer = LogoPatches.Writer(context, video.frameTimesUs)
        try {
            for ((regionIndex, group) in groups.withIndex()) {
                val share = 1f / groups.size
                val base = regionIndex * share
                val inGroup = group.toHashSet()
                val crop = regionAround(union(group), video.width, video.height)
                val perPixel = 3.0 + 16.0 / 4.0
                val budgetPixels = SCRATCH_BUDGET_BYTES / (perPixel * frameCount)
                val scale = minOf(
                    1f,
                    MAX_REGION_EDGE.toFloat() / max(crop.width(), crop.height()),
                    kotlin.math.sqrt(budgetPixels / (crop.width().toDouble() * crop.height())).toFloat()
                )
                val cw = max(8, (crop.width() * scale).roundToInt())
                val ch = max(8, (crop.height() * scale).roundToInt())
                // The logo's pixels in this crop for each place it sits (others can overlap the crop too).
                val holes = positions.associateWith { holeInCrop(it, mask, maskW, maskH, crop, cw, ch, video.width, video.height) }
                val empty = ByteArray(cw * ch)

                val handle = VideoCompletionNative.nativeCreate(cw, ch, frameCount, context.cacheDir.path)
                // A watcher child is cancelled the moment Stop is pressed, while this coroutine is still blocked in
                // native code, and raises the native flag the long loops check. It is joined before the handle is
                // destroyed, so the flag is never raised on a freed handle.
                val stop = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        VideoCompletionNative.nativeCancel(handle)
                    }
                }
                try {
                    var decoded = 0
                    // A frame the decoder skips takes the one before, so it never counts as a known black frame.
                    var last = -1
                    var lastRgb = ByteArray(0)
                    var lastHole = empty
                    VideoFrames.decode(context, uri, 0L) { timeUs, image, colour ->
                        val found = video.frameTimesUs.binarySearch(timeUs)
                        val frame = if (found >= 0) found else (-found - 1).coerceAtMost(frameCount - 1)
                        if (frame > last) {
                            val rgb = VideoFrames.rgb(image, video, colour, crop, cw, ch)
                            val hole = runOf[frame]?.let { holes[it] } ?: empty
                            for (gap in last + 1 until frame) {
                                VideoCompletionNative.nativeAddFrame(
                                    handle, gap, if (last >= 0) lastRgb else rgb, if (last >= 0) lastHole else hole
                                )
                            }
                            VideoCompletionNative.nativeAddFrame(handle, frame, rgb, hole)
                            last = frame
                            lastRgb = rgb
                            lastHole = hole
                            decoded++
                            onProgress(base + share * 0.3f * decoded / frameCount)
                        }
                        true
                    }
                    if (last < 0) throw IOException("The video could not be decoded")
                    for (gap in last + 1 until frameCount) VideoCompletionNative.nativeAddFrame(handle, gap, lastRgb, lastHole)
                    VideoCompletionNative.nativeSeal(handle)
                    onProgress(base + share * 0.45f)
                    var open = VideoCompletionNative.nativePropagate(handle)
                    onProgress(base + share * 0.6f)
                    val rgb = ByteArray(cw * ch * 3)
                    val hole = ByteArray(cw * ch)
                    var round = 0
                    while (inpainter != null && round < MAX_INPAINT_ROUNDS && open.any { it > 0 }) {
                        ensureActive()
                        // What no frame shows is invented once, on the frame with the most of it, and carried
                        // through time like real background, so it moves with the footage instead of flickering.
                        val key = open.indices.maxBy { open[it] }
                        VideoCompletionNative.nativeComposite(handle, key, rgb, hole)
                        val filled = try {
                            inpainter.inpaint(rgb, cw, ch, hole)
                        } catch (failure: Exception) {
                            break
                        }
                        VideoCompletionNative.nativeSetComplete(handle, key, filled)
                        open = VideoCompletionNative.nativePropagate(handle)
                        round++
                    }
                    VideoCompletionNative.nativeFinish(handle)
                    onProgress(base + share * 0.9f)

                    val ownHole = ByteArray(cw * ch).also { union ->
                        for (p in group) {
                            val h = holes.getValue(p)
                            for (i in union.indices) if (h[i].toInt() != 0) union[i] = h[i]
                        }
                    }
                    val box = patchBox(ownHole, cw, ch)
                    val alpha = feather(ownHole, cw, ch, box)
                    val regionRect = NormalizedRect(
                        (crop.left + box.left / scale) / video.width,
                        (crop.top + box.top / scale) / video.height,
                        box.width() / scale / video.width,
                        box.height() / scale / video.height
                    )
                    val region = writer.addRegion(regionRect, alpha, box.width(), box.height())
                    for (frame in 0 until frameCount) {
                        if (runOf[frame] !in inGroup) continue
                        ensureActive()
                        VideoCompletionNative.nativeResult(handle, frame, rgb)
                        writer.addFrame(frame, region, cut(rgb, cw, box), box.width(), box.height())
                    }
                } catch (failure: RuntimeException) {
                    if (failure.message == "cancelled") throw CancellationException("Stopped")
                    throw failure
                } finally {
                    withContext(NonCancellable) { stop.cancelAndJoin() }
                    VideoCompletionNative.nativeDestroy(handle)
                }
            }
            onProgress(1f)
            writer.finish()
        } catch (failure: Throwable) {
            writer.abandon()
            throw failure
        }
    }

    /**
     * A one-frame patch for a still image: MI-GAN's fill of the logo painted at [sourceRect] with [mask]. Null when
     * [inpainter] is missing or fails, so the renderer keeps the plain fill.
     */
    suspend fun image(
        context: Context,
        picture: Bitmap,
        sourceRect: NormalizedRect,
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
        inpainter: MiganInpainter,
    ): String? = withContext(Dispatchers.IO) {
        val w = picture.width
        val h = picture.height
        val crop = regionAround(sourceRect, w, h)
        val scale = min(1f, 1024f / max(crop.width(), crop.height()))
        val cw = max(8, (crop.width() * scale).roundToInt())
        val ch = max(8, (crop.height() * scale).roundToInt())
        val cut = Bitmap.createBitmap(picture, crop.left, crop.top, crop.width(), crop.height())
        val scaled = Bitmap.createScaledBitmap(cut, cw, ch, true)
        val argb = IntArray(cw * ch)
        scaled.getPixels(argb, 0, cw, 0, 0, cw, ch)
        if (scaled !== cut) scaled.recycle()
        if (cut !== picture) cut.recycle()
        val rgb = ByteArray(cw * ch * 3)
        for (i in argb.indices) {
            rgb[i * 3] = (argb[i] shr 16).toByte()
            rgb[i * 3 + 1] = (argb[i] shr 8).toByte()
            rgb[i * 3 + 2] = argb[i].toByte()
        }
        val hole = holeInCrop(sourceRect, mask, maskW, maskH, crop, cw, ch, w, h)
        val filled = try {
            inpainter.inpaint(rgb, cw, ch, hole)
        } catch (failure: Exception) {
            return@withContext null
        }
        val box = patchBox(hole, cw, ch)
        val writer = LogoPatches.Writer(context, longArrayOf(0L))
        try {
            val region = writer.addRegion(
                NormalizedRect(
                    (crop.left + box.left / scale) / w,
                    (crop.top + box.top / scale) / h,
                    box.width() / scale / w,
                    box.height() / scale / h
                ),
                feather(hole, cw, ch, box),
                box.width(),
                box.height()
            )
            writer.addFrame(0, region, cut(filled, cw, box), box.width(), box.height(), lossless = true)
            writer.finish()
        } catch (failure: Throwable) {
            writer.abandon()
            throw failure
        }
    }

    /**
     * [rects] gathered into groups of boxes that overlap by at least half of the smaller one (directly or through
     * others): the same place, give or take tracking wobble. A logo that jumps corners stays in separate groups.
     */
    internal fun groupOverlapping(rects: List<NormalizedRect>): List<List<NormalizedRect>> {
        val parent = IntArray(rects.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            val overlapW = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
            val overlapH = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
            if (overlapW <= 0f || overlapH <= 0f) continue
            val smaller = min(a.width * a.height, b.width * b.height)
            if (overlapW * overlapH >= 0.5f * smaller) parent[root(i)] = root(j)
        }
        return rects.indices.groupBy { root(it) }.values.map { members -> members.map { rects[it] } }
    }

    private fun union(rects: List<NormalizedRect>): NormalizedRect {
        val left = rects.minOf { it.x }
        val top = rects.minOf { it.y }
        return NormalizedRect(left, top, rects.maxOf { it.x + it.width } - left, rects.maxOf { it.y + it.height } - top)
    }

    /** A square-ish crop around [rect] with context on every side (what flow and MI-GAN look at), inside the frame. */
    private fun regionAround(rect: NormalizedRect, w: Int, h: Int): Rect {
        val rw = rect.width * w
        val rh = rect.height * h
        val side = min(min(w, h).toFloat(), max(rw, rh) + 2f * max(24f, 0.35f * max(rw, rh)))
        val cx = (rect.x + rect.width / 2f) * w
        val cy = (rect.y + rect.height / 2f) * h
        val sideW = max(side, min(rw + 16f, w.toFloat()))
        val sideH = max(side, min(rh + 16f, h.toFloat()))
        val left = (cx - sideW / 2f).coerceIn(0f, max(0f, w - sideW)).toInt()
        val top = (cy - sideH / 2f).coerceIn(0f, max(0f, h - sideH)).toInt()
        return Rect(left, top, min(w, left + sideW.roundToInt()), min(h, top + sideH.roundToInt()))
    }

    /** [mask] stretched over [rect] (source normalized), sampled at each pixel of a [cw]×[ch] crop of [crop]. */
    private fun holeInCrop(
        rect: NormalizedRect, mask: ByteArray, maskW: Int, maskH: Int,
        crop: Rect, cw: Int, ch: Int, w: Int, h: Int,
    ): ByteArray {
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) {
            val v = (crop.top + (y + 0.5f) * crop.height() / ch) / h
            val my = ((v - rect.y) / rect.height * maskH).toInt()
            if (my !in 0 until maskH) continue
            for (x in 0 until cw) {
                val u = (crop.left + (x + 0.5f) * crop.width() / cw) / w
                val mx = ((u - rect.x) / rect.width * maskW).toInt()
                if (mx !in 0 until maskW) continue
                if ((mask[my * maskW + mx].toInt() and 0xff) > 127) out[y * cw + x] = 1
            }
        }
        return out
    }

    /** The hole's bounds with a margin for the fade, inside the crop. */
    private fun patchBox(hole: ByteArray, w: Int, h: Int): Rect {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (hole[y * w + x].toInt() == 0) continue
            left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
        }
        if (right < 0) return Rect(0, 0, w, h)
        return Rect(
            max(0, left - PATCH_MARGIN_PX), max(0, top - PATCH_MARGIN_PX),
            min(w, right + 1 + PATCH_MARGIN_PX), min(h, bottom + 1 + PATCH_MARGIN_PX)
        )
    }

    /**
     * Alpha for [box]: opaque over the hole and [FEATHER_PX] pixels around it, clear beyond. No partial alpha:
     * Media3 blends overlays as if they were not premultiplied, which darkens every half-transparent pixel into a
     * visible outline. The band around the hole holds the frame's own pixels, so the hard edge falls where the
     * patch and the video already agree.
     */
    private fun feather(hole: ByteArray, w: Int, h: Int, box: Rect): ByteArray {
        val bw = box.width()
        val bh = box.height()
        // Distance (in steps of a pixel, 8-connected) from the hole, capped at FEATHER_PX + 1.
        val far = FEATHER_PX + 1
        val dist = IntArray(bw * bh) { i ->
            val x = box.left + i % bw
            val y = box.top + i / bw
            if (hole[y * w + x].toInt() != 0) 0 else far
        }
        repeat(FEATHER_PX) {
            val next = dist.copyOf()
            for (y in 0 until bh) for (x in 0 until bw) {
                var best = dist[y * bw + x]
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until bw && ny in 0 until bh) best = min(best, dist[ny * bw + nx] + 1)
                }
                next[y * bw + x] = best
            }
            System.arraycopy(next, 0, dist, 0, dist.size)
        }
        return ByteArray(bw * bh) { i -> if (dist[i] <= FEATHER_PX) 0xff.toByte() else 0 }
    }

    private fun cut(rgb: ByteArray, w: Int, box: Rect): ByteArray {
        val out = ByteArray(box.width() * box.height() * 3)
        for (y in 0 until box.height()) {
            System.arraycopy(rgb, ((box.top + y) * w + box.left) * 3, out, y * box.width() * 3, box.width() * 3)
        }
        return out
    }
}
