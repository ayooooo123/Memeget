package com.memeget.nativeapp.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAudioMixTest {
    @Test
    fun volumeChangeBetweenMedia3sUnityReadsKeepsPlaying() {
        val volume = LiveVolume(1f)
        val live = LiveGain(volume)
        // Media3 reads the gain, sees exactly 1, then asks how long unity lasts. The slider moves in between.
        val racing = object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
                live.getGainFactorAtSamplePosition(samplePosition, sampleRate).also { if (it == 1f) volume.value = 0.5f }

            override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = live.isUnityUntil(samplePosition, sampleRate)
        }
        val processor = GainProcessor(racing)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(4_800 * 2).order(ByteOrder.nativeOrder())
        repeat(4_800) { input.putShort(1_000) }
        input.flip()

        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        assertEquals(4_800, output.remaining())
        // The unity already granted runs its 1/20 s; the new volume is heard right after.
        assertEquals(1_000, output.get(2_399).toInt())
        assertEquals(500, output.get(2_400).toInt())
    }

    @Test
    fun loopPieceStopsOnItsExactLastSampleNotTheCodecsFrame() {
        // 1 s at 44.1 kHz, delivered as whole 1024-sample AAC frames: 44 frames make 1.0216 s.
        val trim = ExactLengthAudioProcessor(1_000_000L)
        trim.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        trim.flush(AudioProcessor.StreamMetadata.DEFAULT)
        // Media3 queues its shared empty buffer too, here on a fresh processor (this threw on the Pixel 5a).
        trim.queueInput(AudioProcessor.EMPTY_BUFFER)
        var out = 0
        repeat(44) {
            val frame = ByteBuffer.allocateDirect(1_024 * 4).order(ByteOrder.nativeOrder())
            trim.queueInput(frame)
            assertEquals(0, frame.remaining())
            out += trim.output.remaining() / 4
        }
        assertEquals(44_100, out)

        trim.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(0, trim.output.remaining())

        // After a seek half-way in, only the rest of the piece is left.
        trim.flush(AudioProcessor.StreamMetadata(500_000L))
        trim.queueInput(AudioProcessor.EMPTY_BUFFER)
        trim.queueInput(ByteBuffer.allocateDirect(30_000 * 4).order(ByteOrder.nativeOrder()))
        assertEquals(22_050, trim.output.remaining() / 4)
    }

    @Test
    fun loopPieceTheDecoderCutShortIsPaddedToItsPlannedLength() {
        // Seen on a Pixel 5a: a 1.000 s AAC track decoded to 44,034 samples.
        val trim = ExactLengthAudioProcessor(1_000_000L)
        trim.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        trim.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val decoded = ByteBuffer.allocateDirect(44_034 * 2).order(ByteOrder.nativeOrder())
        repeat(44_034) { decoded.putShort(7) }
        decoded.flip()
        trim.queueInput(decoded)
        // Media3's pipeline can queue end of stream before the next processor has taken the last output.
        trim.queueEndOfStream()
        // ...and calls it again until the stream has ended.
        trim.queueEndOfStream()
        val content = trim.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val tail = trim.output.order(ByteOrder.nativeOrder()).asShortBuffer()

        assertEquals(44_034, content.remaining())
        assertEquals(7, content.get(44_033).toInt())
        assertEquals(66, tail.remaining())
        assertEquals(0, tail.get(65).toInt())
        assertTrue(trim.isEnded)
    }

    private fun spec(
        durationUs: Long? = 3_000_000L,
        startUs: Long = 0L,
        atUs: Long = 0L,
        loop: Boolean = true,
        fadeInUs: Long = 0L,
        fadeOutUs: Long = 0L
    ) = VideoMusicSpec(
        uri = "file:///music.mp3",
        startUs = startUs,
        atUs = atUs,
        loop = loop,
        fadeInUs = fadeInUs,
        fadeOutUs = fadeOutUs,
        durationUs = durationUs
    )

    @Test
    fun loopingTrackRepeatsFromItsInPointUntilTheVideoEnds() {
        val plan = MusicPlan.of(spec(startUs = 1_000_000L, atUs = 2_000_000L), videoDurationUs = 9_000_000L)!!

        assertEquals(2_000_000L, plan.leadInUs)
        assertEquals(
            listOf(
                MusicPlan.Piece(1_000_000L, 3_000_000L, 2_000_000L),
                MusicPlan.Piece(1_000_000L, 3_000_000L, 4_000_000L),
                MusicPlan.Piece(1_000_000L, 3_000_000L, 6_000_000L),
                MusicPlan.Piece(1_000_000L, 2_000_000L, 8_000_000L)
            ),
            plan.pieces
        )
        assertEquals(9_000_000L, plan.endUs)
    }

    @Test
    fun trackWithoutLoopStopsWhenItEnds() {
        val plan = MusicPlan.of(spec(loop = false), videoDurationUs = 10_000_000L)!!

        assertEquals(listOf(MusicPlan.Piece(0L, 3_000_000L, 0L)), plan.pieces)
        assertEquals(3_000_000L, plan.endUs)
    }

    @Test
    fun trackLongerThanTheVideoIsCutAtTheVideosEnd() {
        val plan = MusicPlan.of(spec(durationUs = 60_000_000L, startUs = 30_000_000L), videoDurationUs = 4_000_000L)!!

        assertEquals(listOf(MusicPlan.Piece(30_000_000L, 34_000_000L, 0L)), plan.pieces)
    }

    @Test
    fun nothingIsPlannedWhenTheTrackWouldComeInAfterTheVideo() {
        assertNull(MusicPlan.of(spec(atUs = 5_000_000L), videoDurationUs = 5_000_000L))
        assertNull(MusicPlan.of(spec(atUs = 4_990_000L), videoDurationUs = 5_000_000L))
    }

    @Test
    fun inPointPastTheTracksEndStillLeavesSomethingToHear() {
        val plan = MusicPlan.of(spec(startUs = 9_000_000L, loop = false), videoDurationUs = 5_000_000L)!!

        val piece = plan.pieces.single()
        assertEquals(3_000_000L, piece.clipEndUs)
        assertTrue(piece.durationUs >= MusicPlan.MIN_PIECE_US)
    }

    @Test
    fun oneSecondLoopCoversATwoMinuteVideo() {
        val plan = MusicPlan.of(spec(durationUs = 1_000_000L), videoDurationUs = 120_000_000L)!!

        assertEquals(120, plan.pieces.size)
        assertEquals(120_000_000L, plan.endUs)
        assertEquals(false, plan.loopCut)
    }

    @Test
    fun loopTooShortForTheVideoSaysWhereItStops() {
        val plan = MusicPlan.of(spec(durationUs = 60_000L), videoDurationUs = 60_000_000L)!!

        assertEquals(MusicPlan.MAX_PIECES, plan.pieces.size)
        assertTrue(plan.loopCut)
        assertEquals(MusicPlan.MAX_PIECES * 60_000L, plan.endUs)
    }

    @Test
    fun aTrackThatSimplyEndsIsNotACutLoop() {
        assertEquals(false, MusicPlan.of(spec(loop = false), videoDurationUs = 60_000_000L)!!.loopCut)
    }

    @Test
    fun fadesFollowTheEditedVideoClock() {
        val plan = MusicPlan.of(
            spec(atUs = 1_000_000L, loop = false, fadeInUs = 1_000_000L, fadeOutUs = 500_000L),
            videoDurationUs = 10_000_000L
        )!!

        assertEquals(0f, plan.fadeAt(1_000_000L), 1e-6f)
        assertEquals(0.5f, plan.fadeAt(1_500_000L), 1e-6f)
        assertEquals(1f, plan.fadeAt(2_500_000L), 1e-6f)
        // The track (3 s from 1 s) ends at 4 s: the fade-out reaches silence there, not at the video's end.
        assertEquals(0.5f, plan.fadeAt(3_750_000L), 1e-6f)
        assertEquals(0f, plan.fadeAt(4_000_000L), 1e-6f)
    }

    @Test
    fun fadesNeverOutlastWhatIsHeard() {
        val plan = MusicPlan.of(spec(loop = false, fadeInUs = 9_000_000L, fadeOutUs = 9_000_000L), 10_000_000L)!!

        assertEquals(3_000_000L, plan.fadeInUs)
        assertEquals(3_000_000L, plan.fadeOutUs)
    }

    @Test
    fun liveGainHearsVolumeChangesAndNeverSwitchesItselfOff() {
        val volume = LiveVolume(1f)
        val gain = LiveGain(volume)

        // Unity "to the end of the stream" would make Media3 drop the processor and miss the next change.
        val unityEnd = gain.isUnityUntil(0L, 48_000)
        assertNotEquals(C.TIME_END_OF_SOURCE, unityEnd)
        assertTrue(unityEnd in 1L..48_000L)

        volume.value = 0.25f
        assertEquals(0.25f, gain.getGainFactorAtSamplePosition(unityEnd, 48_000), 0f)
    }

    @Test
    fun unityStopsWhereTheFadeOutStarts() {
        val plan = MusicPlan.of(spec(loop = false, fadeOutUs = 1_000_000L), videoDurationUs = 10_000_000L)!!
        val gain = LiveGain(LiveVolume(1f), 0L, plan)

        // The track ends at 3 s, so the fade-out starts at 2 s: sample 96,000 at 48 kHz.
        assertEquals(96_000L, gain.isUnityUntil(95_000L, 48_000))
    }

    @Test
    fun liveGainPlacesEachLoopOnTheEditedVideoClock() {
        val plan = MusicPlan.of(spec(loop = true, fadeOutUs = 1_000_000L), videoDurationUs = 6_000_000L)!!
        val secondLoop = plan.pieces[1]
        val gain = LiveGain(LiveVolume(0.8f), secondLoop.outputStartUs, plan)

        // 2.5 s into the second loop is 5.5 s into the video: halfway through the last second's fade-out.
        assertEquals(0.4f, gain.getGainFactorAtSamplePosition(2_500_000L * 48 / 1000, 48_000), 1e-4f)
    }

    @Test
    fun musicJsonRoundTripsAndOldDraftsKeepLooping() {
        val full = spec(startUs = 1L, atUs = 2L, loop = false, fadeInUs = 3L, fadeOutUs = 4L).copy(name = "boom.mp3")
        assertEquals(full, VideoMusicSpec.fromJson(full.toJson()))

        val old = VideoMusicSpec.fromJson(JSONObject("""{"uri":"file:///a.mp3","volume":0.5,"startUs":7}"""))
        assertEquals(VideoMusicSpec(uri = "file:///a.mp3", volume = 0.5f, startUs = 7L), old)
        assertTrue(old.loop)
    }

    @Test
    fun volumeChangesDoNotRebuildThePlayerButTrackChangesDo() {
        val source = MemeEditSource("file:///v.mp4", "v.mp4", MediaEditKind.VIDEO, 720, 1280, 5_000_000L)
        val base = ProjectCore.createDefaultVideoProject(source)
        val withMusic = reduceProject(base, ProjectAction.SetVideoMusic(spec()))
        val key = NativeVideoComposition.rebuildKey(withMusic)

        val louder = reduceProject(withMusic, ProjectAction.SetVideoMusic(spec().copy(volume = 0.3f)))
        val muted = reduceProject(withMusic, ProjectAction.SetVideoAudio(muted = true, volume = 0.5f))
        assertEquals(key, NativeVideoComposition.rebuildKey(louder))
        assertEquals(key, NativeVideoComposition.rebuildKey(muted))

        val moved = reduceProject(withMusic, ProjectAction.SetVideoMusic(spec(atUs = 1_000_000L)))
        assertNotEquals(key, NativeVideoComposition.rebuildKey(moved))
    }
}
