package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import expo.modules.memegetbg.AudioExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Frames along a source video, for the timeline's filmstrip. [frames] fills in while [timesUs] stays fixed. */
internal class Filmstrip(val timesUs: LongArray, val frames: List<ImageBitmap?>) {
    /** The decoded frame nearest [us], or null while none is ready. Early on only every few frames are ready. */
    fun nearest(us: Long): ImageBitmap? {
        var best: ImageBitmap? = null
        var bestDistance = Long.MAX_VALUE
        for (i in timesUs.indices) {
            val frame = frames[i] ?: continue
            val distance = abs(timesUs[i] - us)
            if (distance < bestDistance) {
                best = frame
                bestDistance = distance
            }
        }
        return best
    }
}

/**
 * Filmstrip for [uri], one frame per [FILMSTRIP_STEP_US] (at most [FILMSTRIP_MAX_FRAMES]), each [heightPx] tall.
 * Frames decode coarse-to-fine so the whole strip has pictures after the first few.
 */
@Composable
internal fun rememberFilmstrip(uri: String, durationUs: Long, heightPx: Int): State<Filmstrip> {
    val context = LocalContext.current
    val count = (durationUs / FILMSTRIP_STEP_US).toInt().coerceIn(FILMSTRIP_MIN_FRAMES, FILMSTRIP_MAX_FRAMES)
    val times = remember(durationUs, count) {
        LongArray(count) { i -> ((i + 0.5) * durationUs / count).toLong() }
    }
    return produceState(Filmstrip(times, List(count) { null }), uri, times, heightPx) {
        val frames = arrayOfNulls<ImageBitmap>(count)
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                for (index in coarseToFine(count)) {
                    ensureActive()
                    val frame = decodeFrame(retriever, times[index], heightPx, byHeight = true) ?: continue
                    frames[index] = frame.asImageBitmap()
                    value = Filmstrip(times, frames.toList())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A strip without pictures still scrubs; the frame count and times are all it needs.
            } finally {
                retriever.release()
            }
        }
    }
}

/**
 * The source frame at [timeUs] (null shows nothing), decoded exactly and latest-wins: while a finger drags, the
 * frame shown is the newest one the decoder could finish.
 */
@Composable
internal fun rememberSourceFrame(uri: String, timeUs: Long?, longEdgePx: Int = PEEK_LONG_EDGE_PX): ImageBitmap? {
    val context = LocalContext.current
    val wanted = rememberUpdatedState(timeUs)
    var shown by remember(uri) { mutableStateOf<Pair<Long, ImageBitmap>?>(null) }
    LaunchedEffect(uri) {
        val retriever = MediaMetadataRetriever()
        try {
            withContext(Dispatchers.IO) { retriever.setDataSource(context, Uri.parse(uri)) }
            snapshotFlow { wanted.value }.conflate().collect { us ->
                if (us == null) {
                    shown = null
                    return@collect
                }
                if (shown?.first == us) return@collect
                val frame = withContext(Dispatchers.IO) { decodeFrame(retriever, us, longEdgePx, byHeight = false) }
                if (frame != null) shown = us to frame.asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No peek is better than a broken editor; the composition preview still works.
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { retriever.release() }
        }
    }
    return if (timeUs == null) null else shown?.second
}

/** Loudness peaks of a sound, [PEAKS_PER_SECOND] a second, 0..1. Empty when the file has no sound. */
internal class Waveform(val peaks: FloatArray) {
    val durationUs: Long get() = peaks.size * 1_000_000L / PEAKS_PER_SECOND

    /** Loudest peak between two track times. */
    fun peakBetween(fromUs: Long, toUs: Long): Float {
        if (peaks.isEmpty()) return 0f
        val a = (fromUs * PEAKS_PER_SECOND / 1_000_000L).toInt().coerceIn(0, peaks.lastIndex)
        val b = (toUs * PEAKS_PER_SECOND / 1_000_000L).toInt().coerceIn(a, peaks.lastIndex)
        var peak = 0f
        for (i in a..b) peak = max(peak, peaks[i])
        return peak
    }

    companion object {
        const val PEAKS_PER_SECOND = 50
        /** Decoding takes time; past this, the rest of a long track draws flat. */
        private const val MAX_SECONDS = 300.0
        private val cache = LruCache<String, Waveform>(6)

        /**
         * Peaks as the decoder hands out samples, at the track's own rate: nothing but the peaks is kept, so a
         * long or high-rate track costs no more memory than a short one.
         */
        suspend fun load(context: Context, uri: String): Waveform = cache.get(uri) ?: withContext(Dispatchers.Default) {
            val peaks = PeakAccumulator()
            AudioExtractor.decodeMono(context, uri, MAX_SECONDS, peaks)
            Waveform(peaks.normalized())
        }.also { cache.put(uri, it) }
    }
}

/** Max |sample| per 1/[Waveform.PEAKS_PER_SECOND] s bucket; [normalized] scales the loudest bucket to 1. */
private class PeakAccumulator : AudioExtractor.MonoSink {
    private var peaks = FloatArray(Waveform.PEAKS_PER_SECOND * 60)
    private var count = 0
    private var samples = 0L

    override fun accept(sample: Float, sampleRate: Int) {
        val bucket = (samples++ * Waveform.PEAKS_PER_SECOND / sampleRate).toInt()
        if (bucket >= peaks.size) peaks = peaks.copyOf(max(peaks.size * 2, bucket + 1))
        if (bucket >= count) count = bucket + 1
        peaks[bucket] = max(peaks[bucket], abs(sample))
    }

    fun normalized(): FloatArray {
        val out = peaks.copyOf(count)
        val loudest = out.maxOrNull() ?: 0f
        if (loudest > 0f) for (i in out.indices) out[i] /= loudest
        return out
    }
}

private const val FILMSTRIP_STEP_US = 250_000L
private const val FILMSTRIP_MIN_FRAMES = 8
private const val FILMSTRIP_MAX_FRAMES = 96
private const val PEEK_LONG_EDGE_PX = 720

/** 0, n/2, n/4, 3n/4, … : every index once, spread out early. */
private fun coarseToFine(count: Int): List<Int> {
    val order = LinkedHashSet<Int>(count)
    var parts = 1
    while (order.size < count) {
        for (k in 0 until parts) order += ((k.toLong() * count) / parts).toInt()
        parts *= 2
    }
    return order.toList()
}

/** Exact frame at [us], scaled so its height (or long edge) is [sizePx]. MMR hands frames back upright. */
private fun decodeFrame(retriever: MediaMetadataRetriever, us: Long, sizePx: Int, byHeight: Boolean): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
        val frame = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val scale = sizePx.toFloat() / (if (byHeight) frame.height else max(frame.width, frame.height))
        return Bitmap.createScaledBitmap(frame, max(1, (frame.width * scale).roundToInt()), max(1, (frame.height * scale).roundToInt()), true)
            .also { if (it !== frame) frame.recycle() }
    }
    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    val uprightHeight = if (rotation == 90 || rotation == 270) w else h
    val scale = sizePx.toFloat() / (if (byHeight) uprightHeight else max(w, h))
    // The frame keeps its aspect inside a square box whose side is its long edge, whichever way it is turned.
    val longEdge = max(1, (max(w, h) * scale).roundToInt())
    return retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, longEdge, longEdge)
}
