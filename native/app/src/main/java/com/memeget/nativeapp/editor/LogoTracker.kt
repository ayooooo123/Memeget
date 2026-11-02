package com.memeget.nativeapp.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Follows a screen-fixed logo (a platform watermark) through a video. The painted logo is matched against every
 * frame, and each place it shows up in (or its absence) becomes a run of frames.
 */
internal object LogoTracker {
    /** Normalized correlation at or above this counts as the logo (when its own pixels also match, see below). */
    private const val MATCH_THRESHOLD = 0.6f
    /**
     * The logo's own pixels (the most distinct share of the template, [CORE_SHARE]) must also keep their brightness
     * at a match: an overlay looks the same over any footage, while hair or fabric that merely correlates with its
     * shape does not. A match may differ by up to [CORE_DIFF_RATIO] times what the logo differs by near the painted
     * frame, and never less than [MIN_CORE_DIFF] (of 255). Measured on a 360×360 TikTok repost: frames with the
     * logo 4–13 (reference 3.9), without it 41 and up, at correlations of 0.80–0.82.
     */
    private const val CORE_SHARE = 0.4f
    private const val CORE_DIFF_RATIO = 4f
    private const val MIN_CORE_DIFF = 20f
    /** Frames this close to the painted one, matching this well, set what the logo differs by there. */
    private const val REFERENCE_FRAMES = 15
    /** Frames on each side of a sighting where a fading watermark is still taken to show. */
    private const val FADE_FRAMES = 8
    private const val REFERENCE_SCORE = 0.9f
    private const val MIN_TEMPLATE_PX = 8
    /** Threads matching frames while decoding goes on. */
    private const val MATCH_THREADS = 3

    /** Frames [startFrame] until [endFrame] show the logo at [sourceRect] (source coordinates), or not at all. */
    class Run(val startFrame: Int, val endFrame: Int, val sourceRect: NormalizedRect?)

    class Track(
        val video: VideoFrames.Info,
        val runs: List<Run>,
        /** Hold keyframes for the cover layer, in working coordinates. */
        val corrections: List<CoverCorrectionKeyframe>,
    )

    /** The logo held at [sourceRect] for the whole clip ("This spot only"). */
    fun fixed(context: Context, uri: String, sourceRect: NormalizedRect): Track {
        val video = VideoFrames.probe(context, uri)
        return Track(video, listOf(Run(0, video.frameTimesUs.size, sourceRect)), emptyList())
    }

    /**
     * The logo painted at [sourceRect] (source coordinates) with [mask] ([maskWidth]×[maskHeight], source
     * orientation), taken from the frame nearest [atSourceUs] and followed through every frame. Keyframe rectangles
     * keep the painted box's size and may run past the frame edge, so the mask stays aligned with the logo.
     */
    suspend fun track(
        context: Context,
        uri: String,
        base: BaseTransform,
        sourceRect: NormalizedRect,
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        atSourceUs: Long,
        onProgress: (Float) -> Unit,
    ): Track = withContext(Dispatchers.IO) {
        val video = VideoFrames.probe(context, uri)
        val frameTimes = video.frameTimesUs
        val analysisW = video.analysisWidth
        val analysisH = video.analysisHeight

        val templateTimeUs = frameTimes[frameTimes.indices.minBy { abs(frameTimes[it] - atSourceUs) }]
        var first: ByteArray? = null
        VideoFrames.decode(context, uri, templateTimeUs) { timeUs, image, _ ->
            if (timeUs >= templateTimeUs) first = VideoFrames.luma(image, video)
            first == null
        }
        val templateFrame = first ?: throw IOException("The paused frame could not be decoded")

        // The painted box at analysis scale, and the part of it the mask covers: that is what gets matched.
        val boxX = (sourceRect.x * analysisW).roundToInt()
        val boxY = (sourceRect.y * analysisH).roundToInt()
        val boxW = max(1, (sourceRect.width * analysisW).roundToInt())
        val boxH = max(1, (sourceRect.height * analysisH).roundToInt())
        val patch = logoPatch(templateFrame, analysisW, analysisH, boxX, boxY, boxW, boxH, mask, maskWidth, maskHeight)
            ?: throw IOException("The painted logo is too small to track")

        // Every frame is checked: a sampled pass misses a logo that shows up, or moves, between two samples. Frames
        // are matched on a few threads while decoding goes on, and the matches land in decode order, so the result
        // is the same as matching them one by one.
        val core = coreOf(patch)
        val count = frameTimes.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val scores = FloatArray(count) { -1f }
        val coreDiffs = FloatArray(count) { Float.MAX_VALUE }
        class Match(val frame: Int, val x: Float, val y: Float, val score: Float, val coreDiff: Float)
        var decoded = 0
        fun land(match: Match) {
            xs[match.frame] = match.x
            ys[match.frame] = match.y
            scores[match.frame] = match.score
            coreDiffs[match.frame] = match.coreDiff
            decoded++
            onProgress(decoded.toFloat() / count)
        }
        val matchers = Executors.newFixedThreadPool(MATCH_THREADS)
        val pending = ArrayDeque<Future<Match>>()
        try {
            VideoFrames.decode(context, uri, 0L) { timeUs, image, _ ->
                val grey = VideoFrames.luma(image, video)
                val found = frameTimes.binarySearch(timeUs)
                val frame = if (found >= 0) found else (-found - 1).coerceAtMost(count - 1)
                pending.addLast(matchers.submit(Callable {
                    val scratch = FloatArray(3)
                    val searched = LogoRemovalNative.nativeMatchTemplate(
                        grey, analysisW, analysisH, patch.pixels, patch.width, patch.height, patch.mask, scratch
                    )
                    if (!searched) throw IOException("A video frame could not be searched for the logo")
                    Match(
                        frame, scratch[0], scratch[1], scratch[2],
                        coreDifference(grey, analysisW, analysisH, patch, core, scratch[0].roundToInt(), scratch[1].roundToInt())
                    )
                }))
                while (pending.size > 2 * MATCH_THREADS) land(pending.removeFirst().get())
                true
            }
            while (pending.isNotEmpty()) land(pending.removeFirst().get())
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            matchers.shutdownNow()
        }
        val templateIndex = frameTimes.indices.minBy { abs(frameTimes[it] - templateTimeUs) }
        val nearby = (max(0, templateIndex - REFERENCE_FRAMES)..min(count - 1, templateIndex + REFERENCE_FRAMES))
            .filter { scores[it] >= REFERENCE_SCORE && coreDiffs[it] < Float.MAX_VALUE }
            .map { coreDiffs[it] }
            .sorted()
        val reference = if (nearby.isNotEmpty()) nearby[nearby.size / 2] else coreDiffs[templateIndex].takeIf { it < Float.MAX_VALUE } ?: 0f
        val maxCoreDiff = max(MIN_CORE_DIFF, CORE_DIFF_RATIO * reference)
        val matched = List(count) { f ->
            LogoTrackPlanner.Sighting(scores[f] >= MATCH_THRESHOLD && coreDiffs[f] <= maxCoreDiff, xs[f], ys[f])
        }
        // A watermark fades in and out over a few frames when it appears or moves; faint, it no longer matches but
        // still shows (and would be copied into the fill from there). The frames next to each sighting count too.
        val sightings = List(count) { f ->
            if (matched[f].present) return@List matched[f]
            val near = (1..FADE_FRAMES).asSequence()
                .flatMap { d -> sequenceOf(f - d, f + d) }
                .firstOrNull { it in 0 until count && matched[it].present }
            near?.let { matched[it] } ?: matched[f]
        }

        val tolerance = max(2f, 0.15f * min(patch.width, patch.height))
        val segments = LogoTrackPlanner.segments(sightings) { a, b ->
            a.present == b.present && (!a.present || (abs(a.x - b.x) <= tolerance && abs(a.y - b.y) <= tolerance))
        }
        if (segments.none { it.sighting.present }) throw IOException("The logo wasn't found in the video")

        // The painted box, wherever the logo went.
        fun boxAt(sighting: LogoTrackPlanner.Sighting) = NormalizedRect(
            (sighting.x - patch.offsetX) / analysisW,
            (sighting.y - patch.offsetY) / analysisH,
            boxW.toFloat() / analysisW,
            boxH.toFloat() / analysisH
        )
        val runs = segments.mapIndexed { i, segment ->
            val end = if (i + 1 < segments.size) segments[i + 1].startFrame else frameTimes.size
            Run(segment.startFrame, end, if (segment.sighting.present) boxAt(segment.sighting) else null)
        }
        val corrections = LogoTrackPlanner.corrections(segments, frameTimes, sourceRectToWorking(sourceRect, base)) {
            sourceRectToWorking(boxAt(it), base)
        }
        Track(video, runs, corrections)
    }

    /** What gets matched: grey pixels, their mask, and where they sit inside the painted box. */
    private class Patch(
        val pixels: ByteArray,
        val mask: ByteArray,
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    /**
     * The painted logo in [frame]: the box at ([boxX], [boxY], [boxW]×[boxH]) with [mask] stretched over it,
     * cropped to the masked pixels' bounds inside the frame. Matching only those pixels keeps the footage around
     * the logo from dragging the score down where the logo moves, and the crop lets it match against a frame
     * edge. Null when the masked area is too small to match.
     */
    private fun logoPatch(
        frame: ByteArray,
        frameW: Int,
        frameH: Int,
        boxX: Int,
        boxY: Int,
        boxW: Int,
        boxH: Int,
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
    ): Patch? {
        fun masked(x: Int, y: Int): Boolean {
            val mx = (x * maskW / boxW).coerceIn(0, maskW - 1)
            val my = (y * maskH / boxH).coerceIn(0, maskH - 1)
            return (mask[my * maskW + mx].toInt() and 0xff) > 127
        }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until boxH) {
            if (boxY + y !in 0 until frameH) continue
            for (x in 0 until boxW) {
                if (boxX + x !in 0 until frameW || !masked(x, y)) continue
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        if (maxX < 0 || w < MIN_TEMPLATE_PX || h < MIN_TEMPLATE_PX) return null
        val pixels = ByteArray(w * h)
        val patchMask = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = frame[(boxY + minY + y) * frameW + boxX + minX + x]
                if (masked(minX + x, minY + y)) patchMask[y * w + x] = 0xff.toByte()
            }
        }
        return Patch(pixels, patchMask, w, h, minX, minY)
    }

    /** Indices of [patch]'s logo pixels that stand out most from the template's typical masked value. */
    private fun coreOf(patch: Patch): IntArray {
        val masked = patch.mask.indices.filter { patch.mask[it].toInt() != 0 }
        if (masked.isEmpty()) return IntArray(0)
        val values = masked.map { patch.pixels[it].toInt() and 0xff }
        val median = values.sorted()[values.size / 2]
        val byDistance = masked.indices.sortedByDescending { abs(values[it] - median) }
        val keep = max(1, (masked.size * CORE_SHARE).roundToInt())
        return IntArray(keep) { masked[byDistance[it]] }
    }

    /** Mean brightness difference over [core] between [patch] and [grey] with the patch's corner at ([x], [y]). */
    private fun coreDifference(grey: ByteArray, w: Int, h: Int, patch: Patch, core: IntArray, x: Int, y: Int): Float {
        if (core.isEmpty() || x < 0 || y < 0 || x + patch.width > w || y + patch.height > h) return Float.MAX_VALUE
        var sum = 0L
        for (p in core) {
            val px = p % patch.width
            val py = p / patch.width
            sum += abs((grey[(y + py) * w + x + px].toInt() and 0xff) - (patch.pixels[p].toInt() and 0xff))
        }
        return sum.toFloat() / core.size
    }

    /** Corners mapped and re-ordered; not clamped, so a box past the frame edge keeps its size. */
    fun sourceRectToWorking(rect: NormalizedRect, base: BaseTransform): NormalizedRect {
        val a = ProjectCore.sourceToWorking(NormalizedPoint(rect.x, rect.y), base)
        val b = ProjectCore.sourceToWorking(NormalizedPoint(rect.x + rect.width, rect.y + rect.height), base)
        return NormalizedRect(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
    }
}
