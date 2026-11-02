package com.memeget.nativeapp.editor

import android.content.Context
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.TextRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds likely watermarks without being told where they are. On video a watermark is the detailed shape that
 * holds still while the footage moves; on a still image it is small text near an edge (an @handle).
 * Boxes are source-normalized, best first.
 */
internal object LogoFinder {
    private const val WINDOW_US = 1_000_000L
    private const val MAX_FRAMES = 40
    /**
     * A pixel holds still when it varies less than this share of the frame's typical variation. Compression noise
     * and blur let a watermark's thin strokes vary by about half of it on low-quality clips (measured on a 360×360
     * TikTok repost: icon 3.0, handle 5.4, footage 8.0).
     */
    private const val STILL_SHARE = 0.45f
    /** Footage that moves less than this (median spread, of 255) gives no stillness to go by. */
    private const val MIN_MOTION = 1.5f
    private const val EDGE_LEVEL = 40
    private const val MIN_EDGE_DENSITY = 0.08f
    private const val MERGE_GAP_PX = 10
    private const val PAD_PX = 6
    private const val MAX_RESULTS = 3

    private class Box(var left: Int, var top: Int, var right: Int, var bottom: Int, var edges: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    /**
     * Likely watermarks in the video around [atUs]: still, detailed shapes over moving footage, or else (footage
     * that barely moves gives no stillness to go by) watermark-like text in the frame at [atUs].
     */
    suspend fun inVideo(context: Context, engine: InferenceEngine, uri: String, atUs: Long): List<NormalizedRect> =
        stillShapes(context, uri, atUs).ifEmpty { textInFrame(context, engine, uri, atUs) }

    private suspend fun textInFrame(context: Context, engine: InferenceEngine, uri: String, atUs: Long): List<NormalizedRect> {
        val file = withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(context, android.net.Uri.parse(uri))
                retriever.getFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            } ?: return@withContext null
            try {
                java.io.File.createTempFile("logo_frame_", ".jpg", context.cacheDir).also { out ->
                    out.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                }
            } finally {
                frame.recycle()
            }
        } ?: return emptyList()
        return try {
            inImage(engine, android.net.Uri.fromFile(file).toString())
        } finally {
            file.delete()
        }
    }

    private suspend fun stillShapes(context: Context, uri: String, atUs: Long): List<NormalizedRect> = withContext(Dispatchers.IO) {
        val video = VideoFrames.probe(context, uri)
        val w = video.analysisWidth
        val h = video.analysisHeight
        val sum = DoubleArray(w * h)
        val squares = DoubleArray(w * h)
        var frames = 0
        VideoFrames.decode(context, uri, max(0L, atUs - WINDOW_US)) { timeUs, image, _ ->
            if (timeUs >= atUs - WINDOW_US) {
                val grey = VideoFrames.luma(image, video)
                for (i in grey.indices) {
                    val v = (grey[i].toInt() and 0xff).toDouble()
                    sum[i] += v
                    squares[i] += v * v
                }
                frames++
            }
            frames < MAX_FRAMES && timeUs < atUs + WINDOW_US
        }
        if (frames < 6) return@withContext emptyList()
        val mean = FloatArray(w * h) { (sum[it] / frames).toFloat() }
        val spread = FloatArray(w * h) { i ->
            val m = sum[i] / frames
            sqrt(max(0.0, squares[i] / frames - m * m)).toFloat()
        }
        // Flat areas barely change even while the camera moves; the busier part of the frame says how much it does.
        val typical = spread.copyOf().also { it.sort() }[spread.size * 4 / 5]
        if (typical < MIN_MOTION) return@withContext emptyList()
        // Detailed (edged) pixels that hold still.
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = abs(mean[i + 1] - mean[i - 1])
            val gy = abs(mean[i + w] - mean[i - w])
            edge[i] = gx + gy > EDGE_LEVEL
        }
        val still = BooleanArray(w * h) { spread[it] < max(1f, STILL_SHARE * typical) }
        val candidate = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!still[y * w + x]) continue
            var near = false
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && edge[ny * w + nx]) near = true
            }
            candidate[y * w + x] = near
        }
        val boxes = merge(components(candidate, edge, w, h))
            .filter { it.width < w * 0.7f && it.height < h * 0.3f && it.width * it.height >= 60 }
            .filter { it.edges.toFloat() / (it.width * it.height) >= MIN_EDGE_DENSITY }
        boxes.sortedByDescending { it.edges }.take(MAX_RESULTS).map { b ->
            NormalizedRect(
                max(0, b.left - PAD_PX).toFloat() / w,
                max(0, b.top - PAD_PX).toFloat() / h,
                (min(w, b.right + 1 + PAD_PX) - max(0, b.left - PAD_PX)).toFloat() / w,
                (min(h, b.bottom + 1 + PAD_PX) - max(0, b.top - PAD_PX)).toFloat() / h
            )
        }
    }

    /**
     * Text that looks like a watermark: an @handle anywhere, or small text near an edge, together with the lines
     * stacked right against it (a "TikTok" line above the handle).
     */
    suspend fun inImage(engine: InferenceEngine, uri: String): List<NormalizedRect> {
        val regions = engine.recognize(uri)
        val seeds = regions.filter { r ->
            val cx = r.x + r.width / 2f
            val cy = r.y + r.height / 2f
            val nearEdge = cx < 0.3f || cx > 0.7f || cy < 0.15f || cy > 0.85f
            r.text.contains('@') || (r.height < 0.06f && nearEdge && r.width < 0.6f)
        }.sortedByDescending { if (it.text.contains('@')) 1 else 0 }.take(MAX_RESULTS)
        // Each seed grows by the lines stacked against it; a seed taken into an earlier group adds nothing new.
        val absorbed = HashSet<TextRegion>()
        val out = ArrayList<NormalizedRect>()
        for (seed in seeds) {
            if (!absorbed.add(seed)) continue
            var group = NormalizedRect(seed.x, seed.y, seed.width, seed.height)
            var grew = true
            while (grew) {
                grew = false
                for (r in regions) {
                    if (r in absorbed || !stackedAgainst(group, r)) continue
                    absorbed += r
                    group = union(group, NormalizedRect(r.x, r.y, r.width, r.height))
                    grew = true
                }
            }
            val pad = seed.height * 0.4f
            out += NormalizedRect(
                (group.x - pad).coerceIn(0f, 1f), (group.y - pad).coerceIn(0f, 1f),
                (group.width + 2 * pad).coerceAtMost(1f), (group.height + 2 * pad).coerceAtMost(1f)
            )
        }
        return out
    }

    /** Whether line [r] sits right above or below [group]: within one and a half line heights, overlapping across. */
    private fun stackedAgainst(group: NormalizedRect, r: TextRegion): Boolean {
        val lineHeight = max(r.height, min(group.height, r.height * 3f))
        val gapY = max(r.y - (group.y + group.height), group.y - (r.y + r.height))
        val overlapX = min(group.x + group.width, r.x + r.width) - max(group.x, r.x)
        return gapY <= lineHeight * 1.5f && overlapX > -lineHeight
    }

    private fun union(a: NormalizedRect, b: NormalizedRect): NormalizedRect {
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        return NormalizedRect(left, top, max(a.x + a.width, b.x + b.width) - left, max(a.y + a.height, b.y + b.height) - top)
    }

    private fun components(mask: BooleanArray, edge: BooleanArray, w: Int, h: Int): List<Box> {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = ArrayList<Box>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            val box = Box(start % w, start / w, start % w, start / w, 0)
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                box.left = min(box.left, x); box.right = max(box.right, x)
                box.top = min(box.top, y); box.bottom = max(box.bottom, y)
                if (edge[p]) box.edges++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val q = ny * w + nx
                    if (mask[q] && !seen[q]) {
                        seen[q] = true
                        queue[tail++] = q
                    }
                }
            }
            out += box
        }
        return out
    }

    /** Joins boxes closer than [MERGE_GAP_PX] (a logo and its @handle, the letters of a word). */
    private fun merge(boxes: List<Box>): List<Box> {
        val list = boxes.map { Box(it.left, it.top, it.right, it.bottom, it.edges) }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                val gapX = max(0, max(a.left, b.left) - min(a.right, b.right))
                val gapY = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
                if (gapX <= MERGE_GAP_PX && gapY <= MERGE_GAP_PX) {
                    a.left = min(a.left, b.left); a.top = min(a.top, b.top)
                    a.right = max(a.right, b.right); a.bottom = max(a.bottom, b.bottom)
                    a.edges += b.edges
                    list.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return list
    }
}
