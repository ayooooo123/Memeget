package com.memeget.nativeapp.editor

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.Util
import androidx.media3.inspector.MediaExtractorCompat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Length of the first audio track in an audio or video file, as Media3 plays it. Null when the file has no audio
 * track; throws when it can't be read. Call off the main thread.
 *
 * The track's own length, not the file's: a video's sound can end before its picture, and loops planned on the
 * picture's length would be mostly silence. Read by Media3's extractors, not [MediaFormat.KEY_DURATION] from the
 * platform: for AAC that counts the encoder's priming and padding (1.023 s for a 1.000 s track), and a loop that
 * long drifted 22 ms a loop off the beat on a Pixel 5a. Media3 applies the edit list, as playback does.
 */
internal fun probeAudioTrackUs(context: Context, uri: String): Long? {
    val extractor = MediaExtractorCompat(context)
    try {
        try {
            extractor.setDataSource(Uri.parse(uri), 0L)
        } catch (e: Exception) {
            throw IOException("The file could not be read: ${e.message}", e)
        }
        val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ?: return null
        val durationUs = if (audio.containsKey(MediaFormat.KEY_DURATION)) audio.getLong(MediaFormat.KEY_DURATION) else 0L
        return durationUs.takeIf { it > 0L } ?: throw IOException("The sound's length is unknown")
    } finally {
        extractor.release()
    }
}

/** [project] with its music's length filled in, for drafts saved before [VideoMusicSpec.durationUs] existed. */
internal fun withMusicLength(context: Context, project: MemeEditProject): MemeEditProject {
    val video = project.video ?: return project
    val music = video.music?.takeIf { it.durationUs == null } ?: return project
    // Unreadable now: left unknown, and building the preview reports the file.
    val trackUs = try {
        probeAudioTrackUs(context, music.uri)
    } catch (_: IOException) {
        null
    } ?: return project
    return project.copy(video = video.copy(music = music.copy(durationUs = trackUs)))
}

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
 * @param streamStartUs the stream clock at its first sample; see [ExactLengthAudioProcessor].
 */
internal class LiveGain(
    private val volume: LiveVolume,
    private val outputStartUs: Long = 0L,
    private val fades: MusicPlan? = null,
    private val outputUsPerStreamUs: Double = 1.0,
    private val streamStartUs: Long = 0L
) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
        val level = volume.value
        val plan = fades ?: return level
        return level * plan.fadeAt(outputUsAt(samplePosition, sampleRate))
    }

    /**
     * Unity never runs into a fade: up to where the fade-out starts, and only the asked sample when it is already
     * inside a fade (where the gain can equal 1 for one sample, at its edge or with the volume above 1).
     */
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        val chunkEnd = samplePosition + (sampleRate / UNITY_CHECKS_PER_SECOND).coerceAtLeast(1)
        val plan = fades ?: return chunkEnd
        val outUs = outputUsAt(samplePosition, sampleRate)
        if (plan.fadeInUs > 0L && outUs < plan.leadInUs + plan.fadeInUs) return samplePosition + 1
        if (plan.fadeOutUs <= 0L) return chunkEnd
        val fadeOutSample = sampleAt(plan.endUs - plan.fadeOutUs, sampleRate)
        return if (fadeOutSample > samplePosition) minOf(chunkEnd, fadeOutSample) else samplePosition + 1
    }

    private fun outputUsAt(samplePosition: Long, sampleRate: Int): Long {
        val streamUs = samplePosition * 1_000_000.0 / sampleRate - streamStartUs
        return outputStartUs + (streamUs * outputUsPerStreamUs).toLong()
    }

    /** First stream sample at or after edited-video time [outputUs]. */
    private fun sampleAt(outputUs: Long, sampleRate: Int): Long {
        val streamUs = (outputUs - outputStartUs) / outputUsPerStreamUs + streamStartUs
        return kotlin.math.ceil(streamUs * sampleRate / 1_000_000.0).toLong()
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
 *
 * @param streamStartUs the stream clock ([AudioProcessor.StreamMetadata.positionOffsetUs]) at the stream's first
 *   sample. Media3 1.9 export starts every item at 0. The player starts the first item of a sequence at its
 *   clip's start in the source file and later items at 0 (ConcatenatingMediaSource2 keeps the first period's
 *   own clock); a seek adds the time into the item either way.
 */
internal class ExactLengthAudioProcessor(
    private val durationUs: Long,
    private val streamStartUs: Long = 0L
) : BaseAudioProcessor() {
    private var framesLeft = 0L
    private var silenceFrames = 0L
    private var silence: ByteBuffer? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val rate = inputAudioFormat.sampleRate
        val intoStreamUs = (streamMetadata.positionOffsetUs - streamStartUs).coerceAtLeast(0L)
        framesLeft = (Util.durationUsToSampleCount(durationUs, rate) -
            Util.durationUsToSampleCount(intoStreamUs, rate)).coerceAtLeast(0L)
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

    /**
     * After the real output has been taken, the silence that makes the stream its full length, at most 0.1 s a
     * call: a piece planned far past its sound (an old draft whose track length was unknown) must not ask for one
     * huge buffer.
     */
    override fun getOutput(): ByteBuffer {
        val output = super.getOutput()
        if (output.hasRemaining() || silenceFrames <= 0L) return output
        val frameBytes = inputAudioFormat.bytesPerFrame
        val chunkFrames = (inputAudioFormat.sampleRate / SILENCE_CHUNKS_PER_SECOND).coerceAtLeast(1)
        // Zero bytes are silence in 16-bit and float PCM. Never written, so every view of it stays silent; and a
        // view, never the buffer replaceOutputBuffer hands out, so no later output overwrites one still unread.
        val zeros = silence?.takeIf { it.capacity() == chunkFrames * frameBytes }
            ?: ByteBuffer.allocateDirect(chunkFrames * frameBytes).also { silence = it }
        val frames = min(silenceFrames, chunkFrames.toLong()).toInt()
        silenceFrames -= frames
        return zeros.duplicate().order(ByteOrder.nativeOrder()).limit(frames * frameBytes) as ByteBuffer
    }

    override fun isEnded(): Boolean = super.isEnded() && silenceFrames <= 0L

    override fun onReset() {
        framesLeft = 0L
        silenceFrames = 0L
        silence = null
    }

    private companion object {
        const val SILENCE_CHUNKS_PER_SECOND = 10
    }
}
