package expo.modules.memegetbg

import android.content.ContentValues
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoSegmentationDeviceGateTest {
  @Test
  fun vendoredModelAndFixturesMatchPinnedProvenance() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val validation = VideoSegmentationDeviceGateProbe.validateAssetProvenance(instrumentation)

    assertTrue(validation.getBoolean("allDigestsMatch"))
    assertTrue(validation.getBoolean("modelProvenanceComplete"))
    assertEquals(3, validation.getJSONArray("fixtures").length())
    assertTrue(validation.getBoolean("allProvenanceBoundariesMatch"))
    assertEquals(0, validation.getJSONArray("failedBoundaries").length())
    assertEquals("single person-confidence mask", validation.getJSONObject("model").getString("observedOutputSemantics"))
  }

  @Test
  fun sequentialDecoderEmitsTargetsWithoutSeekingAndClosesOnFailure() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val source = instrumentation.targetContext.cacheDir.resolve("sequential-decoder-fixture.mp4")
    instrumentation.context.assets.open("video_segmentation_one_person_10s_720p.mp4").use { input ->
      source.outputStream().use { output -> input.copyTo(output) }
    }
    val decoder = SequentialVideoFrameDecoder(source)
    val observedPresentationTimesUs = mutableListOf<Long>()
    try {
      decoder.decodeFrames(
        targetTimestampsMs = VideoSegmentationGateContracts.evidenceSchedule(8, 1).map { it.timestampMs },
        targetWidth = 256,
        targetHeight = 144,
        shouldCancel = { false }
      ) { frame ->
        observedPresentationTimesUs += frame.presentationTimeUs
      }
      assertEquals(8, observedPresentationTimesUs.size)
      assertTrue(observedPresentationTimesUs.zipWithNext().all { (first, second) -> second > first })
      assertEquals(0, decoder.extractorSeekCount)
      assertTrue(decoder.inputSamplesAdvanced > 0)

      var callbackFailureObserved = false
      try {
        SequentialVideoFrameDecoder(source).use { failing ->
          failing.decodeFrames(listOf(0L), 256, 144, { false }) {
            throw IllegalStateException("callback failure")
          }
        }
      } catch (error: IllegalStateException) {
        callbackFailureObserved = error.message == "callback failure"
      }
      assertTrue(callbackFailureObserved)
    } finally {
      decoder.close()
      source.delete()
    }
    assertTrue(decoder.isClosed)
  }

  @Test
  fun sequentialDecoderTimeoutTracksDecodeStallsNotCallbackWork() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val source = instrumentation.targetContext.cacheDir.resolve("sequential-decoder-slow-callback.mp4")
    instrumentation.context.assets.open("video_segmentation_one_person_10s_720p.mp4").use { input ->
      source.outputStream().use { output -> input.copyTo(output) }
    }
    try {
      var frames = 0
      SequentialVideoFrameDecoder(source, stallTimeoutMs = 100L).use { decoder ->
        decoder.decodeFrames(
          VideoSegmentationGateContracts.evidenceSchedule(8, 1).map { it.timestampMs },
          256,
          144,
          { false }
        ) {
          SystemClock.sleep(50)
          frames++
        }
      }
      assertEquals(8, frames)
    } finally {
      source.delete()
    }
  }

  @Test
  fun recordsDeterministicPlaybackEvidenceForReview() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val evidence = VideoSegmentationDeviceGateProbe.generatePlaybackEvidence(instrumentation)
    assertEquals(6, evidence.length())
    for (index in 0 until evidence.length()) {
      val item = evidence.getJSONObject(index)
      val file = instrumentation.targetContext.filesDir.resolve(item.getString("fileName"))
      assertTrue("Missing deterministic playback evidence ${file.name}", file.isFile)
      assertEquals(
        VideoSegmentationGateContracts.FIXED_ZIP_ENTRY_TIME_MS,
        item.getLong("zipEntryTimestampMs")
      )
      publishDownload(file.name, file.readBytes(), "application/zip")
    }
  }

  @Test
  fun recordsPhysicalVideoSegmentationGate() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val result = VideoSegmentationDeviceGateProbe.run(instrumentation)
    val output = instrumentation.targetContext.filesDir.resolve("video-segmentation-device-gate.json")
    output.writeText(result.toString(2))
    publishDownload(output.name, output.readBytes(), "application/json")

    val evidence = result.getJSONArray("maskEvidence")
    for (index in 0 until evidence.length()) {
      val item = evidence.getJSONObject(index)
      val file = instrumentation.targetContext.filesDir.resolve(item.getString("fileName"))
      assertTrue("Missing mask evidence ${file.name}", file.isFile)
      publishDownload(file.name, file.readBytes(), "image/png")
    }

    val playbackEvidence = result.getJSONArray("maskPlaybackEvidence")
    for (index in 0 until playbackEvidence.length()) {
      val item = playbackEvidence.getJSONObject(index)
      val file = instrumentation.targetContext.filesDir.resolve(item.getString("fileName"))
      assertTrue("Missing playback evidence ${file.name}", file.isFile)
      publishDownload(file.name, file.readBytes(), "application/zip")
    }

    println("VIDEO_SEGMENTATION_GATE_PACKAGE=${instrumentation.targetContext.packageName}")
    println("VIDEO_SEGMENTATION_GATE_PATH=${output.absolutePath}")
    println("VIDEO_SEGMENTATION_GATE_STATUS=${result.getString("gateStatus")}")

    assertTrue("Gate output was not written", output.isFile)
    assertFalse("Emulator results must never become the gate", result.getJSONObject("device").getBoolean("emulator"))
    assertEquals("Pixel 9 Pro", result.getJSONObject("device").getString("model"))
    assertEquals(9, result.getJSONArray("matrix").length())
    assertEquals(27, result.getJSONArray("maskEvidence").length())
    for (index in 0 until result.getJSONArray("matrix").length()) {
      val matrix = result.getJSONArray("matrix").getJSONObject(index)
      assertEquals("COMPLETED", matrix.getString("status"))
      assertEquals(3, matrix.getInt("fixtureCount"))
      assertEquals(3, matrix.getInt("completedFixtureCount"))
    }
    assertEquals(3, result.getJSONObject("provenance").getJSONArray("fixtures").length())
    assertEquals(6, result.getJSONArray("maskPlaybackEvidence").length())
    assertTrue(result.getJSONObject("playbackReviews").getBoolean("exactCurrentEvidenceSet"))
    assertEquals(
      "PASS",
      result.getJSONObject("criteria").getJSONObject("matrixComplete").getString("status")
    )
    assertTrue(result.getString("gateStatus") in setOf("PASS", "FAIL"))
    assertTrue(result.getJSONObject("capabilities").has("videoIsolation"))
    assertTrue(result.getJSONObject("capabilities").has("autoTrack"))
    assertTrue(result.getJSONObject("cancellationCleanup").has("status"))
  }

  private fun publishDownload(name: String, bytes: ByteArray, mimeType: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = context.contentResolver
    resolver.delete(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
      arrayOf(name)
    )
    val uri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    ) ?: error("Could not create Downloads/$name")
    resolver.openOutputStream(uri)?.use { it.write(bytes) }
      ?: error("Could not write Downloads/$name")

    // Observed behaviour, reproduced on demand: an artifact left on the device by an
    // earlier run is NOT matched by the delete above, and MediaStore then silently
    // de-duplicates this insert into "name (1).ext". A host-side pull of the exact name
    // returns the PREVIOUS run's bytes, which would be committed as if it were evidence
    // from this run. The gate JSON carries observedAtUtc, but the mask PNGs and playback
    // archives carry no internal timestamp, so nothing about stale output looks wrong.
    //
    // The mechanism is NOT confirmed. "Owner attribution is dropped when Gradle uninstalls
    // and reinstalls the instrumentation APK" is the best theory, but a file planted via
    // adb shell was attributed to this app's uid and the delete still did not match it, so
    // same-package ownership is demonstrably not sufficient. The guard below does not
    // depend on knowing why; it only depends on the rename being observable.
    val publishedName = resolver.query(
      uri,
      arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
      null,
      null,
      null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    assertEquals(
      "MediaStore de-duplicated Downloads/$name, so a stale artifact from an earlier " +
        "run is still on the device and this run's bytes were written elsewhere. " +
        "Publishing is partial and the device is left dirty: Downloads already holds " +
        "this run's gate JSON, which is fresh and references all 33 artifacts, plus " +
        "only the artifacts published before this one, mixed with stale leftovers. " +
        "Pulling that set yields a current-looking JSON backed by incomplete and " +
        "possibly stale evidence. Do not salvage it. Clear the device and rerun: " +
        "adb shell rm -f \"/sdcard/Download/video-segmentation-*\"",
      name,
      publishedName
    )
    println("VIDEO_SEGMENTATION_GATE_DOWNLOAD_URI=$uri")
  }
}
