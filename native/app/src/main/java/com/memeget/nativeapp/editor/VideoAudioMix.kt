package com.memeget.nativeapp.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.MetadataRetriever
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Length of the sound in an audio or video file, as Media3 plays it. Null when the file has no audio track;
 * throws when it can't be read. Call off the main thread.
 *
 * Not [MediaFormat.KEY_DURATION]: for AAC it counts the encoder's priming and padding (1.023 s for a 1.000 s
 * track), and a loop that long drifted 22 ms a loop off the beat on a Pixel 5a. Media3's own extractors read the
 * edit list, as playback does.
 */
internal fun probeAudioTrackUs(context: Context, uri: String): Long? {
    val extractor = MediaExtractor()
    try {
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
        } catch (e: Exception) {
            throw IOException("The file could not be read: ${e.message}", e)
        }
        (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ?: return null
    } finally {
        extractor.release()
    }
    val durationUs = try {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use {
            it.retrieveDurationUs().get(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        }
    } catch (e: Exception) {
        throw IOException("The sound's length could not be read: ${e.message}", e)
    }
    return durationUs.takeIf { it != C.TIME_UNSET && it > 0L } ?: throw IOException("The sound's length is unknown")
}

private const val PROBE_TIMEOUT_S = 10L

/**
 * Where a [VideoMusicSpec] sounds in the edited video, all in the edited video's clock. The track comes in after
 * [leadInUs] of silence and plays as one or more [pieces] (one per loop) until [endUs].
 *
 * @param loopCut a looping track this short would need more than [MAX_PIECES] loops, so it stops at [endUs]
 *   before the video ends. The editor says so; nothing is cut without the user seeing it.
 */
internal data class MusicPlan(
    val leadInUs: Long,
    val pieces: List<Piece>,
    val endUs: Long,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val loopCut: Boolean = false
) {
    /** One play of the track from [clipStartUs] to [clipEndUs] (track time), heard from [outputStartUs] on. */
    data class Piece(val clipStartUs: Long, val clipEndUs: Long, val outputStartUs: Long) {
        val durationUs: Long get() = clipEndUs - clipStartUs
    }

    /** Fade-in and fade-out gain, 0..1, at edited-video time [us]. */
    fun fadeAt(us: Long): Float {
        var gain = 1f
        if (fadeInUs > 0L && us < leadInUs + fadeInUs) {
            gain *= ((us - leadInUs).toFloat() / fadeInUs).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0L && us > endUs - fadeOutUs) {
            gain *= ((endUs - us).toFloat() / fadeOutUs).coerceIn(0f, 1f)
        }
        return gain
    }

    companion object {
        /**
         * Each loop is one Media3 item. 256 covers a 1 s sound over a 4-minute video. On the Pixel 5a an export of
         * 160 loops of a 0.1 s sound kept every click 0.100 s apart to the end, and 256 loops previewed.
         */
        const val MAX_PIECES = 256
        /** Shorter than this is not worth an item: the track's last sliver, or a gap at the video's end. */
        const val MIN_PIECE_US = 50_000L

        /** Null when nothing of the track would be heard in a video [videoDurationUs] long. */
        fun of(spec: VideoMusicSpec, videoDurationUs: Long): MusicPlan? {
            val atUs = spec.atUs.coerceIn(0L, videoDurationUs)
            val trackUs = spec.durationUs?.takeIf { it > 0L } ?: Long.MAX_VALUE
            val inPointUs = spec.startUs.coerceIn(0L, (trackUs - MIN_PIECE_US).coerceAtLeast(0L))
            val pieces = mutableListOf<Piece>()
            var cursorUs = atUs
            while (cursorUs < videoDurationUs && pieces.size < MAX_PIECES) {
                val lengthUs = min(videoDurationUs - cursorUs, trackUs - inPointUs)
                if (lengthUs < MIN_PIECE_US) break
                pieces += Piece(inPointUs, inPointUs + lengthUs, cursorUs)
                cursorUs += lengthUs
                if (!spec.loop) break
            }
            if (pieces.isEmpty()) return null
            val heardUs = cursorUs - atUs
            return MusicPlan(
                leadInUs = atUs,
                pieces = pieces,
                endUs = cursorUs,
                fadeInUs = spec.fadeInUs.coerceIn(0L, heardUs),
                fadeOutUs = spec.fadeOutUs.coerceIn(0L, heardUs),
                loopCut = spec.loop && pieces.size == MAX_PIECES && videoDurationUs - cursorUs >= MIN_PIECE_US
            )
        }
    }
}

/** A volume the editor changes while the composition plays, read by [LiveGain] on the audio thread. */
internal class LiveVolume(initial: Float) {
    @Volatile
    var value: Float = initial
}

/**
 * Gain from a [LiveVolume], optionally shaped by a [MusicPlan]'s fades.
 *
 * [isUnityUntil] always answers a short, finite boundary and never reads the volume again. Media3 1.9's
 * [GainProcessor] asks it only at position 0 (unity to the end of the stream would switch the processor off for
 * good) and right after it read a gain of exactly 1, then requires a valid boundary. The slider can move between
 * those two reads on the audio thread; answering from a second read then threw "Expected a valid end boundary
 * for unity region". So the unity it already saw lasts one 1/20 s chunk and the new volume is heard after that.
 *
 * @param outputStartUs edited-video time of this stream's first sample.
 * @param outputUsPerStreamUs edited-video time per unit of the stream's own time: 1, except for a music piece the
 *   export slows before speeding up the whole mix (see [expo.modules.memegetbg.RetainedRangeComposition]); the
 *   processor hears that piece after the slowdown. Measured on a Pixel 5a at 0.5x.
 */
internal class LiveGain(
    private val volume: LiveVolume,
    private val outputStartUs: Long = 0L,
    private val fades: MusicPlan? = null,
    private val outputUsPerStreamUs: Double = 1.0
) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
        val level = volume.value
        val plan = fades ?: return level
        val streamUs = samplePosition * 1_000_000.0 / sampleRate
        return level * plan.fadeAt(outputStartUs + (streamUs * outputUsPerStreamUs).toLong())
    }

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        val chunkEnd = samplePosition + (sampleRate / UNITY_CHECKS_PER_SECOND).coerceAtLeast(1)
        // Nor past where a fade-out starts, so it isn't heard late.
        val plan = fades?.takeIf { it.fadeOutUs > 0L } ?: return chunkEnd
        val fadeOutStreamUs = (plan.endUs - plan.fadeOutUs - outputStartUs) / outputUsPerStreamUs
        val fadeOutSample = (fadeOutStreamUs * sampleRate / 1_000_000.0).toLong()
        return if (fadeOutSample > samplePosition) minOf(chunkEnd, fadeOutSample) else chunkEnd
    }

    private companion object {
        /** A change made while playing is heard within 1/20 s. */
        const val UNITY_CHECKS_PER_SECOND = 20
    }
}

/**
 * Makes its stream exactly [durationUs] long: drops what comes after, and pads with silence at the end of the
 * stream when the decoder gave less. A loop's length then is its planned length, so loops stay on the beat. On a
 * Pixel 5a a 1.000 s AAC track decoded to 0.9985 s (Media3 trims the priming but the content is 66 samples
 * short); without this each loop came 1.5 ms early, 180 ms over 120 loops.
 */
internal class ExactLengthAudioProcessor(private val durationUs: Long) : BaseAudioProcessor() {
    private var framesLeft = 0L
    private var silenceFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val rate = inputAudioFormat.sampleRate
        framesLeft = (Util.durationUsToSampleCount(durationUs, rate) -
            Util.durationUsToSampleCount(streamMetadata.positionOffsetUs, rate)).coerceAtLeast(0L)
        silenceFrames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = inputAudioFormat.bytesPerFrame
        val keepFrames = min((inputBuffer.remaining() / frameBytes).toLong(), framesLeft)
        val end = inputBuffer.limit()
        // Nothing to keep: copying would hand Media3's shared empty buffer to itself, which throws.
        if (keepFrames > 0L) {
            val keepBytes = (keepFrames * frameBytes).toInt()
            val output = replaceOutputBuffer(keepBytes)
            inputBuffer.limit(inputBuffer.position() + keepBytes)
            output.put(inputBuffer)
            inputBuffer.limit(end)
            output.flip()
            framesLeft -= keepFrames
        }
        // The rest is past the end: consumed, never heard.
        inputBuffer.position(end)
    }

    override fun onQueueEndOfStream() {
        // Not written here: the last real output may still be waiting for the next processor, and
        // replaceOutputBuffer would overwrite it (that lost a whole buffer a loop on the Pixel 5a).
        // Media3 calls this again until the stream has ended, so it adds rather than sets: a second call before
        // the silence went out must not cancel it (that happened at 0.25x on the Pixel 5a).
        silenceFrames += framesLeft
        framesLeft = 0L
    }

    /** After the real output has been taken, the silence that makes the stream its full length. */
    override fun getOutput(): ByteBuffer {
        val output = super.getOutput()
        if (output.hasRemaining() || silenceFrames <= 0L) return output
        // A new buffer, never the one handed out before; zero bytes are silence in 16-bit and float PCM.
        val silence = ByteBuffer.allocateDirect((silenceFrames * inputAudioFormat.bytesPerFrame).toInt())
            .order(ByteOrder.nativeOrder())
        silenceFrames = 0L
        return silence
    }

    override fun isEnded(): Boolean = super.isEnded() && silenceFrames <= 0L

    override fun onReset() {
        framesLeft = 0L
        silenceFrames = 0L
    }
}
