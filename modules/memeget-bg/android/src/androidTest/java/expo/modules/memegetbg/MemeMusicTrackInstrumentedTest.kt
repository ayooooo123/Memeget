package expo.modules.memegetbg

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device proof for the added music track: a plan carrying a `music` object mixes a second audio
 * sequence onto the single video output, so even a MUTED source comes back with an audible audio
 * track (the "clean replace"), and a plan with no music still exports exactly as before.
 *
 * The music source reuses the shared short clip: `EditedMediaItem.setRemoveVideo(true)` on the
 * music item strips its video, so the same asset stands in for a real music file's audio track and
 * the test needs no second fixture.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MemeMusicTrackInstrumentedTest {
  private companion object {
    const val EXPORT_TIMEOUT_SECONDS = 180L
  }

  private lateinit var context: Context
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    workDir = File(context.cacheDir, "music_track_test").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
  }

  @After
  fun tearDown() {
    workDir.deleteRecursively()
  }

  @Test
  fun aMutedSourceWithMusicStillExportsAnAudioTrack() {
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)

    val basePlan = JSONObject(
      ExportTestSupport.planJson(
        source,
        ExportTestSupport.SHORT_DURATION_US,
        ExportTestSupport.SHORT_WIDTH,
        ExportTestSupport.SHORT_HEIGHT
      )
    )
    // Mute the source and add music: the source contributes silence, the music is the whole track.
    basePlan.getJSONObject("audio").put("muted", true)
    basePlan.put(
      "music",
      JSONObject()
        .put("uri", Uri.fromFile(source).toString())
        .put("volume", 1.0)
    )

    val plan = VideoExportPlan.parse(basePlan.toString())
    val music = requireNotNull(plan.music) { "music did not parse" }
    assertTrue("music volume kept", music.volume == 1f)
    // A muted, silent-if-alone source plus music must still be expected to carry audio.
    assertTrue("music forces the audio expectation", plan.expectsAudio(false))

    val sourceHasAudio = VideoExportPlan.sourceHasAudioTrack(context, plan.sourceUri)
    val output = export(plan.buildComposition(sourceHasAudio, null, plan.music), "muted_with_music.mp4")

    assertTrue("exporter produced a file", output.isFile && output.length() > 0L)
    assertTrue("the music export carries an audio track", hasAudioTrack(output))
  }

  @Test
  fun aPlanWithNoMusicStillExports() {
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)

    val plan = VideoExportPlan.parse(
      ExportTestSupport.planJson(
        source,
        ExportTestSupport.SHORT_DURATION_US,
        ExportTestSupport.SHORT_WIDTH,
        ExportTestSupport.SHORT_HEIGHT
      )
    )
    assertNull("no music object means no music", plan.music)

    val sourceHasAudio = VideoExportPlan.sourceHasAudioTrack(context, plan.sourceUri)
    val output = export(plan.buildComposition(sourceHasAudio, null, plan.music), "no_music.mp4")

    assertTrue("exporter produced a file", output.isFile && output.length() > 0L)
    // The unmuted source has audio, so the music-free export keeps it.
    assertTrue("the source audio survives", hasAudioTrack(output))
  }

  // ---------------------------------------------------------------- helpers

  /** Whether [file] carries at least one audio track with a non-negative, present duration. */
  private fun hasAudioTrack(file: File): Boolean {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      (0 until extractor.trackCount).any { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      }
    } finally {
      extractor.release()
    }
  }

  private fun export(composition: Composition, name: String): File {
    val output = File(workDir, name).apply { delete() }
    val latch = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val transformerRef = AtomicReference<Transformer>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
          override fun onCompleted(composition: Composition, result: ExportResult) {
            latch.countDown()
          }

          override fun onError(
            composition: Composition,
            result: ExportResult,
            exception: ExportException
          ) {
            failure.set(exception)
            latch.countDown()
          }
        })
        .build()
      transformerRef.set(transformer)
      transformer.start(composition, output.absolutePath)
    }
    if (!latch.await(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      instrumentation.runOnMainSync { transformerRef.get().cancel() }
      throw AssertionError("Export of $name timed out after $EXPORT_TIMEOUT_SECONDS seconds")
    }
    failure.get()?.let { throw AssertionError("Export of $name failed", it) }
    check(output.isFile && output.length() > 0L) { "Export of $name produced no bytes" }
    return output
  }
}
