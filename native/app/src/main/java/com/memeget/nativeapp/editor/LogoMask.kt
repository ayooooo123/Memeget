package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One brush stroke painted over a logo, in working-canvas coordinates; [width] is a share of the canvas's short edge. */
internal data class MaskStroke(val points: List<NormalizedPoint>, val width: Float, val erase: Boolean = false)

/**
 * The pixels a "remove" cover replaces. The user paints over the logo; the paint is narrowed to the pixels that
 * actually stand out from what surrounds them, so the background between and around the logo's strokes is kept.
 * Masks are stored in source orientation (the picture as decoded, before crop, rotation or flips) and turned
 * to the project's orientation when rendered, so later crop or rotation changes keep them on the logo.
 */
internal object LogoMask {
    private const val MAX_EDGE = 512
    private const val CONTEXT_PX = 12

    /**
     * A mask ready to use: its rectangle in working and source coordinates, its source-oriented pixels (what gets
     * replaced) and [trackPixels] (the logo's own pixels, for following it through a video; same size).
     */
    class Built(
        val workingRect: NormalizedRect,
        val sourceRect: NormalizedRect,
        val pixels: ByteArray,
        val trackPixels: ByteArray,
        val width: Int,
        val height: Int,
        val uri: String,
    )

    /** Footage moving under the paint by more than this (median temporal spread of the unpainted margin, of 255). */
    private const val MOTION_LEVEL = 3f
    /**
     * A pixel that varies less than this share of the busier part of the margin's spread (its 80th percentile)
     * holds still: the logo. The margin often mixes a still wall with moving footage, so its median says little.
     */
    private const val STILL_SHARE = 0.45f
    /**
     * Frames looked at around the paused one. Two seconds let moving footage vary far more than a watermark does,
     * even on blurry, heavily compressed reposts (0.6 s didn't separate them on a 360×360 TikTok repost).
     */
    private const val MOTION_WINDOW_US = 2_000_000L
    private const val MOTION_FRAMES = 64

    /**
     * Turns [strokes] into a stored mask (for video, around the frame at [sourceTimeUs]). Null when nothing is
     * painted. On video over moving footage the whole paint is replaced (the footage behind it is rebuilt from
     * other frames) and the logo is the part of the paint that holds still. Elsewhere the paint is narrowed to the
     * pixels that stand out from what surrounds them, with the logo's faint shadow or glow.
     */
    suspend fun build(
        context: Context,
        project: MemeEditProject,
        strokes: List<MaskStroke>,
        sourceTimeUs: Long,
    ): Built? = withContext(Dispatchers.IO) {
        val base = project.base
        val (workW, workH) = ProjectCore.visibleImageDimensions(project.source.width, project.source.height, base)
        val bounds = strokeBounds(strokes, workW, workH) ?: return@withContext null

        // Paint in working orientation at a capped resolution.
        val scale = min(1f, MAX_EDGE / max(bounds.width * workW, bounds.height * workH))
        val paintW = max(1, (bounds.width * workW * scale).roundToInt())
        val paintH = max(1, (bounds.height * workH * scale).roundToInt())
        val painted = rasterize(strokes, bounds, paintW, paintH, min(workW, workH) * scale)
        if (painted.none { it != 0.toByte() }) return@withContext null
        val (sourceMask, maskW, maskH) = unorient(painted, paintW, paintH, base)

        val a = ProjectCore.workingToSource(NormalizedPoint(bounds.x, bounds.y), base)
        val b = ProjectCore.workingToSource(NormalizedPoint(bounds.x + bounds.width, bounds.y + bounds.height), base)
        val sourceRect = NormalizedRect(min(a.x, b.x), min(a.y, b.y), kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))

        // Work with a margin of picture around the paint: context, and room to find the parts of the logo the paint
        // missed (a person rarely covers it exactly).
        val pad = max(CONTEXT_PX, (max(maskW, maskH) * MISSED_SHARE).roundToInt())
        val paddedW = maskW + 2 * pad
        val paddedH = maskH + 2 * pad
        val paddedMask = ByteArray(paddedW * paddedH)
        for (y in 0 until maskH) System.arraycopy(sourceMask, y * maskW, paddedMask, (y + pad) * paddedW + pad, maskW)
        val padX = pad * sourceRect.width / maskW
        val padY = pad * sourceRect.height / maskH
        val paddedRect = NormalizedRect(sourceRect.x - padX, sourceRect.y - padY, sourceRect.width + 2 * padX, sourceRect.height + 2 * padY)

        val uri = project.materializedSourceUri ?: project.source.uri
        val motion = if (project.source.kind == MediaEditKind.VIDEO) {
            temporalSpread(context, uri, paddedRect, paddedW, paddedH, sourceTimeUs)
        } else {
            null
        }
        val spread = motion?.first
        val marginValues = spread?.let { s -> s.indices.filter { paddedMask[it].toInt() == 0 }.map { s[it] }.sorted() }
        val margin = marginValues?.let { if (it.isEmpty()) 0f else it[it.size / 2] } ?: 0f
        val busyMargin = marginValues?.let { if (it.isEmpty()) 0f else it[it.size * 4 / 5] } ?: 0f

        val (replaced, logo) = if (motion != null && spread != null && margin > MOTION_LEVEL) {
            // The logo holds still while the footage moves, and its edges stay sharp in the average of the frames
            // (moving edges blur away; flat areas have none). Such shapes that overlap the paint and end inside the
            // margin are the logo, painted or not.
            val still = BooleanArray(spread.size) { spread[it] < STILL_SHARE * busyMargin }
            val sharp = staticEdges(motion.second, paddedW, paddedH)
            val candidate = BooleanArray(spread.size) { still[it] && sharp[it] }
            val found = closeShapes(stillShapesTouchingPaint(candidate, paddedMask, paddedW, paddedH), paddedW, paddedH)
            val shapes = ByteArray(found.size) { if (found[it]) 0xff.toByte() else 0 }
            if (found.count { it } * 20 < paddedMask.count { it.toInt() != 0 }) {
                // Too little held still to trust: replace the whole paint.
                val whole = ByteArray(found.size) { i -> if (paddedMask[i].toInt() != 0 || found[i]) 0xff.toByte() else 0 }
                dilate(whole, paddedW, paddedH, SHADOW_PX) to whole
            } else {
                // Only the logo and its soft shadow (which changes with the footage behind it, so it never holds
                // still) are replaced, with the gaps between its strokes closed up to half a letter: on blurry,
                // compressed video some of a letter's pixels don't test as still, and a missed one stays in sight.
                val letter = medianShapeHeight(found, paddedW, paddedH)
                val closed = closeShapes(found, paddedW, paddedH, (letter / 2).coerceIn(3, MAX_LINK_PX / 2))
                val closedBytes = ByteArray(closed.size) { if (closed[it]) 0xff.toByte() else 0 }
                dilate(closedBytes, paddedW, paddedH, SHADOW_PX) to shapes
            }
        } else {
            val (crop, wholePicture) = sourceCrop(context, project, paddedRect, sourceTimeUs, paddedW, paddedH)
            val refined = try {
                LogoRemovalNative.nativeRefineMask(crop, paddedMask, wholePicture)
            } finally {
                crop.recycle()
                wholePicture.recycle()
            }?.takeIf { mask -> mask.any { it.toInt() != 0 } }
                ?: paddedMask.map { if (it.toInt() != 0) 0xff.toByte() else 0 }.toByteArray()
            refined to refined
        }

        // Keep everything that is set (the refined mask can reach past the paint), cropped to its bounds.
        var left = paddedW
        var top = paddedH
        var right = -1
        var bottom = -1
        for (y in 0 until paddedH) for (x in 0 until paddedW) {
            if (replaced[y * paddedW + x].toInt() == 0) continue
            left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
        }
        if (right < 0) throw IOException("Nothing under the paint stands out from the picture around it")
        val outW = right - left + 1
        val outH = bottom - top + 1
        fun cropped(mask: ByteArray) = ByteArray(outW * outH).also { out ->
            for (y in 0 until outH) System.arraycopy(mask, (top + y) * paddedW + left, out, y * outW, outW)
        }
        val pixels = cropped(replaced)
        val cellW = paddedRect.width / paddedW
        val cellH = paddedRect.height / paddedH
        val rect = NormalizedRect(paddedRect.x + left * cellW, paddedRect.y + top * cellH, outW * cellW, outH * cellH)
        val saved = save(context, pixels, outW, outH)
        Built(LogoTracker.sourceRectToWorking(rect, base), rect, pixels, cropped(logo), outW, outH, saved)
    }

    /**
     * How much each pixel of [rect] (source coordinates, sampled to [w]×[h]) changes over the frames around
     * [atUs]: the standard deviation of its brightness. A logo holds still while footage moves under it.
     */
    private suspend fun temporalSpread(context: Context, uri: String, rect: NormalizedRect, w: Int, h: Int, atUs: Long): Pair<FloatArray, FloatArray>? {
        val video = VideoFrames.probe(context, uri)
        val region = android.graphics.Rect(
            (rect.x * video.width).roundToInt(),
            (rect.y * video.height).roundToInt(),
            ((rect.x + rect.width) * video.width).roundToInt(),
            ((rect.y + rect.height) * video.height).roundToInt()
        )
        val sum = DoubleArray(w * h)
        val squares = DoubleArray(w * h)
        var frames = 0
        // The window around [atUs], moved to stay whole near the start of the clip.
        val startUs = max(0L, atUs - MOTION_WINDOW_US / 2)
        val endUs = startUs + MOTION_WINDOW_US
        VideoFrames.decode(context, uri, startUs) { timeUs, image, colour ->
            if (timeUs >= startUs) {
                val rgb = VideoFrames.rgb(image, video, colour, region, w, h)
                for (i in 0 until w * h) {
                    val luma = 0.299 * (rgb[i * 3].toInt() and 0xff) + 0.587 * (rgb[i * 3 + 1].toInt() and 0xff) +
                        0.114 * (rgb[i * 3 + 2].toInt() and 0xff)
                    sum[i] += luma
                    squares[i] += luma * luma
                }
                frames++
            }
            frames < MOTION_FRAMES && timeUs < endUs
        }
        if (frames < 4) return null
        val spread = FloatArray(w * h) { i ->
            val mean = sum[i] / frames
            kotlin.math.sqrt(max(0.0, squares[i] / frames - mean * mean)).toFloat()
        }
        return spread to FloatArray(w * h) { (sum[it] / frames).toFloat() }
    }

    /** Pixels within two of a sharp edge in [mean] (the average frame), where only still things stay sharp. */
    private fun staticEdges(mean: FloatArray, w: Int, h: Int): BooleanArray {
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            edge[i] = kotlin.math.abs(mean[i + 1] - mean[i - 1]) + kotlin.math.abs(mean[i + w] - mean[i - w]) > EDGE_LEVEL
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!edge[y * w + x]) continue
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = true
            }
        }
        return out
    }

    /** [shapes] with gaps up to [radius] pixels closed (the inside of thick letters, the space between strokes). */
    private fun closeShapes(shapes: BooleanArray, w: Int, h: Int, radius: Int = 3): BooleanArray {
        val bytes = ByteArray(shapes.size) { if (shapes[it]) 0xff.toByte() else 0 }
        val grown = dilate(bytes, w, h, radius)
        // Erode back: a pixel stays when its whole square neighbourhood (cut at the edges) was reached.
        val eroded = squareFilter(grown, w, h, radius, all = true)
        return BooleanArray(shapes.size) { eroded[it].toInt() != 0 || shapes[it] }
    }

    /**
     * Groups of [still] pixels that overlap [paint] and stay off the border of the [w]×[h] area. Pixels a little
     * apart share a group (at least [LINK_PX], more for bigger letters), so the letters of a word and the lines of a
     * watermark go together. A group that reaches the border carries on beyond it, so it is the picture (a
     * letterbox, a static caption); its pixels under the paint still count.
     */
    private fun stillShapesTouchingPaint(still: BooleanArray, paint: ByteArray, w: Int, h: Int): BooleanArray {
        val stillBytes = ByteArray(still.size) { if (still[it]) 0xff.toByte() else 0 }
        val letter = medianShapeHeight(BooleanArray(still.size) { still[it] && paint[it].toInt() != 0 }, w, h)
        val link = (letter * LINK_PER_LETTER_HEIGHT).roundToInt().coerceIn(LINK_PX, MAX_LINK_PX)
        val group = dilate(stillBytes, w, h, link)
        val seen = BooleanArray(w * h)
        val keep = BooleanArray(w * h)
        val queue = IntArray(w * h)
        for (start in 0 until w * h) {
            if (group[start].toInt() == 0 || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var touchesPaint = false
            var touchesBorder = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (still[p] && paint[p].toInt() != 0) touchesPaint = true
                if (still[p] && (x == 0 || y == 0 || x == w - 1 || y == h - 1)) touchesBorder = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val q = ny * w + nx
                    if (group[q].toInt() != 0 && !seen[q]) {
                        seen[q] = true
                        queue[tail++] = q
                    }
                }
            }
            if (!touchesPaint) continue
            for (i in 0 until tail) {
                val p = queue[i]
                if (still[p] && (!touchesBorder || paint[p].toInt() != 0)) keep[p] = true
            }
        }
        return keep
    }

    /** Median height of the 8-connected shapes of at least four pixels in [mask]; 0 when there are none. */
    private fun medianShapeHeight(mask: BooleanArray, w: Int, h: Int): Int {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val heights = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var top = start / w
            var bottom = top
            while (head < tail) {
                val p = queue[head++]
                val y = p / w
                val x = p % w
                top = min(top, y)
                bottom = max(bottom, y)
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
            if (tail >= 4) heights += bottom - top + 1
        }
        return if (heights.isEmpty()) 0 else heights.sorted()[heights.size / 2]
    }

    /** [mask] grown by a square of [radius] pixels on each side. */
    private fun dilate(mask: ByteArray, w: Int, h: Int, radius: Int): ByteArray = squareFilter(mask, w, h, radius, all = false)

    /**
     * Whether any ([all] false) or every ([all] true) pixel of [mask] within a square of [radius] pixels, cut at the
     * edges, is set. Done as two running-count passes, so the cost doesn't grow with the radius (the radius follows
     * letter size and can be large).
     */
    internal fun squareFilter(mask: ByteArray, w: Int, h: Int, radius: Int, all: Boolean): ByteArray {
        fun pass(src: ByteArray, length: Int, lines: Int, at: (line: Int, i: Int) -> Int): ByteArray {
            val out = ByteArray(src.size)
            val prefix = IntArray(length + 1)
            for (line in 0 until lines) {
                for (i in 0 until length) prefix[i + 1] = prefix[i] + (if (src[at(line, i)].toInt() != 0) 1 else 0)
                for (i in 0 until length) {
                    val from = max(0, i - radius)
                    val to = min(length - 1, i + radius)
                    val set = prefix[to + 1] - prefix[from]
                    val hit = if (all) set == to - from + 1 else set > 0
                    if (hit) out[at(line, i)] = 0xff.toByte()
                }
            }
            return out
        }
        val rows = pass(mask, w, h) { y, x -> y * w + x }
        return pass(rows, h, w) { x, y -> y * w + x }
    }

    private fun median(values: List<Float>): Float =
        if (values.isEmpty()) 0f else values.sorted()[values.size / 2]

    /** Share of the painted area's size searched around it for parts of the logo the paint missed. */
    private const val MISSED_SHARE = 0.5f
    /** Smallest gap (pixels) bridged between still shapes of one logo, such as letters of a word. */
    private const val LINK_PX = 6
    /** Each side reaches this share of a letter's height, so watermark lines a letter and a half apart join. */
    private const val LINK_PER_LETTER_HEIGHT = 0.8f
    /** Largest gap bridged (pixels at mask scale): a still shape taller than about 40 pixels isn't a letter. */
    private const val MAX_LINK_PX = 32
    /** Reach (pixels) of a logo's soft shadow or glow past its still shape. */
    private const val SHADOW_PX = 4
    /** Brightness step (of 255, across two pixels) that counts as an edge in the average frame. */
    private const val EDGE_LEVEL = 40f

    /** One brush stroke whose paint covers [rect] (working coordinates) on a [workW]×[workH] canvas. */
    fun strokeCovering(rect: NormalizedRect, workW: Int, workH: Int): MaskStroke {
        val shortEdge = min(workW, workH).toFloat()
        val pxW = rect.width * workW
        val pxH = rect.height * workH
        return if (pxW >= pxH) {
            val half = pxH / 2f / workW
            val y = rect.y + rect.height / 2f
            MaskStroke(
                listOf(NormalizedPoint(rect.x + half, y), NormalizedPoint(rect.x + rect.width - half, y)),
                pxH / shortEdge
            )
        } else {
            val half = pxW / 2f / workH
            val x = rect.x + rect.width / 2f
            MaskStroke(
                listOf(NormalizedPoint(x, rect.y + half), NormalizedPoint(x, rect.y + rect.height - half)),
                pxW / shortEdge
            )
        }
    }

    /** The painted area's bounding box (strokes widened by half their width), clamped to the canvas. */
    fun strokeBounds(strokes: List<MaskStroke>, workW: Int, workH: Int): NormalizedRect? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val shortEdge = min(workW, workH).toFloat()
        for (stroke in strokes) {
            if (stroke.erase) continue
            val halfX = stroke.width * shortEdge / 2f / workW
            val halfY = stroke.width * shortEdge / 2f / workH
            for (p in stroke.points) {
                left = min(left, p.x - halfX)
                top = min(top, p.y - halfY)
                right = max(right, p.x + halfX)
                bottom = max(bottom, p.y + halfY)
            }
        }
        if (left > right) return null
        val x = left.coerceIn(0f, 1f)
        val y = top.coerceIn(0f, 1f)
        val w = right.coerceIn(x, 1f) - x
        val h = bottom.coerceIn(y, 1f) - y
        return if (w <= 0f || h <= 0f) null else NormalizedRect(x, y, w, h)
    }

    private fun rasterize(strokes: List<MaskStroke>, bounds: NormalizedRect, w: Int, h: Int, shortEdgePx: Float): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val eraser = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            for (stroke in strokes) {
                if (stroke.points.isEmpty()) continue
                paint.color = android.graphics.Color.WHITE
                paint.xfermode = if (stroke.erase) eraser else null
                paint.strokeWidth = max(1f, stroke.width * shortEdgePx)
                fun px(p: NormalizedPoint) = (p.x - bounds.x) / bounds.width * w
                fun py(p: NormalizedPoint) = (p.y - bounds.y) / bounds.height * h
                if (stroke.points.size == 1) {
                    canvas.drawPoint(px(stroke.points[0]), py(stroke.points[0]), paint)
                } else {
                    val path = Path()
                    path.moveTo(px(stroke.points[0]), py(stroke.points[0]))
                    for (i in 1 until stroke.points.size) path.lineTo(px(stroke.points[i]), py(stroke.points[i]))
                    canvas.drawPath(path, paint)
                }
            }
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            return ByteArray(w * h) { (argb[it] ushr 24).toByte() }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The source picture under [sourceRect] (which may run past the frame; that part stays black), at [w]×[h], and
     * the whole picture scaled down to [WHOLE_EDGE] on its long side.
     */
    private fun sourceCrop(
        context: Context,
        project: MemeEditProject,
        sourceRect: NormalizedRect,
        sourceTimeUs: Long,
        w: Int,
        h: Int,
    ): Pair<Bitmap, Bitmap> {
        val uri = project.materializedSourceUri ?: project.source.uri
        val frame = if (project.source.kind == MediaEditKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IOException("The paused frame could not be decoded")
            } finally {
                retriever.release()
            }
        } else {
            VideoBackgroundEffect.decodeOrientedBitmap(context, uri, 4096)
        }
        try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.BLACK)
            // Map the whole frame into the crop's pixel space; the canvas clips what falls outside.
            val sx = w / (sourceRect.width * frame.width)
            val sy = h / (sourceRect.height * frame.height)
            val matrix = android.graphics.Matrix().apply {
                setScale(sx, sy)
                preTranslate(-sourceRect.x * frame.width, -sourceRect.y * frame.height)
            }
            canvas.drawBitmap(frame, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val wholeScale = min(1f, WHOLE_EDGE / max(frame.width, frame.height).toFloat())
            val whole = Bitmap.createBitmap(
                max(1, (frame.width * wholeScale).roundToInt()),
                max(1, (frame.height * wholeScale).roundToInt()),
                Bitmap.Config.ARGB_8888
            )
            Canvas(whole).drawBitmap(
                frame,
                android.graphics.Rect(0, 0, frame.width, frame.height),
                android.graphics.Rect(0, 0, whole.width, whole.height),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return out to whole
        } finally {
            frame.recycle()
        }
    }

    /** Long side (pixels) of the whole picture the paint's colours are compared with. */
    private const val WHOLE_EDGE = 320

    private fun save(context: Context, mask: ByteArray, w: Int, h: Int): String {
        val dir = File(context.filesDir, "editor_assets")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "mask_${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(IntArray(w * h) { ((mask[it].toInt() and 0xff) shl 24) or 0xffffff }, w, h, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }

    /** A stored mask turned to [base]'s orientation, as mask bytes with their width and height. */
    fun loadOriented(context: Context, uri: String, base: BaseTransform): Triple<ByteArray, Int, Int> {
        val bitmap = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("The logo mask is missing")
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return orient(ByteArray(w * h) { (argb[it] ushr 24).toByte() }, w, h, base)
    }

    /** Source orientation to working orientation: rotate clockwise by the base rotation, then flip (as rendered). */
    fun orient(mask: ByteArray, w: Int, h: Int, base: BaseTransform): Triple<ByteArray, Int, Int> {
        val quarter = base.rotation == QuarterRotation.R90 || base.rotation == QuarterRotation.R270
        val ow = if (quarter) h else w
        val oh = if (quarter) w else h
        val out = ByteArray(ow * oh)
        for (sy in 0 until h) for (sx in 0 until w) {
            out[workingIndex(sx, sy, w, h, ow, oh, base)] = mask[sy * w + sx]
        }
        return Triple(out, ow, oh)
    }

    /** Working orientation back to source orientation; the inverse of [orient]. */
    fun unorient(mask: ByteArray, ow: Int, oh: Int, base: BaseTransform): Triple<ByteArray, Int, Int> {
        val quarter = base.rotation == QuarterRotation.R90 || base.rotation == QuarterRotation.R270
        val w = if (quarter) oh else ow
        val h = if (quarter) ow else oh
        val out = ByteArray(w * h)
        for (sy in 0 until h) for (sx in 0 until w) {
            out[sy * w + sx] = mask[workingIndex(sx, sy, w, h, ow, oh, base)]
        }
        return Triple(out, w, h)
    }

    private fun workingIndex(sx: Int, sy: Int, w: Int, h: Int, ow: Int, oh: Int, base: BaseTransform): Int {
        var x: Int
        var y: Int
        when (base.rotation) {
            QuarterRotation.R90 -> { x = h - 1 - sy; y = sx }
            QuarterRotation.R180 -> { x = w - 1 - sx; y = h - 1 - sy }
            QuarterRotation.R270 -> { x = sy; y = w - 1 - sx }
            else -> { x = sx; y = sy }
        }
        if (base.flipX) x = ow - 1 - x
        if (base.flipY) y = oh - 1 - y
        return y * ow + x
    }
}
