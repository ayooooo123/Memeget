package expo.modules.memegetbg

import android.app.Instrumentation
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Media3DeviceGateTest {
  @Test
  fun endpointToleranceChecksEveryPresentTrack() {
    assertFalse(
      Media3DeviceGateProbe.trackEndpointsWithinTolerance(
        expectedEndUs = 2_400_000L,
        videoPresent = true,
        videoEndUs = 2_160_000L,
        audioPresent = true,
        audioEndUs = 2_441_000L
      )
    )
    assertTrue(
      Media3DeviceGateProbe.trackEndpointsWithinTolerance(
        expectedEndUs = 2_400_000L,
        videoPresent = true,
        videoEndUs = 2_310_000L,
        audioPresent = true,
        audioEndUs = 2_441_000L
      )
    )
    assertFalse(
      Media3DeviceGateProbe.trackEndpointsWithinTolerance(
        expectedEndUs = 2_400_000L,
        videoPresent = true,
        videoEndUs = null,
        audioPresent = true,
        audioEndUs = 2_400_000L
      )
    )
    assertFalse(
      Media3DeviceGateProbe.trackEndpointsWithinTolerance(
        expectedEndUs = 2_400_000L,
        videoPresent = true,
        videoEndUs = 2_400_000L,
        audioPresent = true,
        audioEndUs = 2_600_000L
      )
    )
    assertFalse(
      Media3DeviceGateProbe.trackEndpointsWithinTolerance(
        expectedEndUs = 2_400_000L,
        videoPresent = true,
        videoEndUs = 2_400_000L,
        audioPresent = true,
        audioEndUs = null
      )
    )
  }

  @Test
  fun avDriftComparisonUsesRawMicroseconds() {
    assertTrue(Media3DeviceGateProbe.avEndDeltaWithinLimit(0L, 50_000L))
    assertFalse(Media3DeviceGateProbe.avEndDeltaWithinLimit(0L, 50_001L))
  }

  @Test
  fun cancellationEvidenceRequiresActiveResourceAndIssuedCancel() {
    assertFalse(
      Media3DeviceGateProbe.cancellationCleanupPass(
        activeBeforeCancel = false,
        cancelIssued = true,
        partialOutputDeleteSucceeded = true,
        partialOutputExistsAfterCleanup = false,
        resourceReleased = true,
        followUpSucceeded = true,
        leftoverCount = 0
      )
    )
    assertFalse(
      Media3DeviceGateProbe.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = false,
        partialOutputDeleteSucceeded = true,
        partialOutputExistsAfterCleanup = false,
        resourceReleased = true,
        followUpSucceeded = true,
        leftoverCount = 0
      )
    )
    assertTrue(
      Media3DeviceGateProbe.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        partialOutputDeleteSucceeded = true,
        partialOutputExistsAfterCleanup = false,
        resourceReleased = true,
        followUpSucceeded = true,
        leftoverCount = 0
      )
    )
  }

  @Test
  fun recordsPinnedMedia3ContractsOnPhysicalDevice() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val result = Media3DeviceGateProbe.run(instrumentation)
    val output = instrumentation.targetContext.filesDir.resolve("media3-1.9-device-gate.json")
    output.writeText(result.toString(2))
    val downloadUri = publishDownload(instrumentation, output)
    println("MEDIA3_GATE_DOWNLOAD_URI=$downloadUri")
    println("MEDIA3_GATE_PACKAGE=${instrumentation.targetContext.packageName}")
    println("MEDIA3_GATE_PATH=${output.absolutePath}")

    assertTrue("Gate output was not written", output.isFile)
    assertEquals(4, result.getJSONArray("fixtures").length())
    assertTrue("Gate status was not finalized", result.getString("gateStatus") in setOf("PASS", "FAIL"))
  }

  @Test
  fun recordsAvDriftIsolationMatrix() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val result = Media3DeviceGateProbe.runDriftMatrix(instrumentation)
    // Shared Downloads, not getExternalFilesDir: the instrumentation APK is uninstalled after the
    // run, which takes its app-private external dir with it.
    val output = instrumentation.targetContext.filesDir.resolve("media3-av-drift-matrix.json")
    output.writeText(result.toString(2))
    println("MEDIA3_MATRIX_DOWNLOAD_URI=${publishDownload(instrumentation, output)}")

    assertTrue("Matrix output was not written", output.isFile)
    assertEquals(2, result.getJSONArray("fixtures").length())
  }

  /**
   * Copies [file] into shared Downloads and returns its content URI.
   *
   * The delete below only matches rows this install still owns. Gradle uninstalls and reinstalls
   * the instrumentation APK on every connected run, which drops that owner attribution, so an
   * artifact left by an earlier run survives the delete and MediaStore silently de-duplicates
   * this insert into "name (1).json". A host-side pull of the exact name then returns the
   * PREVIOUS run's bytes - which, for the gate JSON, would be committed to
   * docs/editing/media3-1.9-device-gate.json as evidence from a run that never produced it.
   * Read the name back and fail loudly instead. Clearing the device host-side before a run
   * remains the documented contract; this is the backstop for forgetting.
   */
  private fun publishDownload(instrumentation: Instrumentation, file: File): Uri {
    val resolver = instrumentation.targetContext.contentResolver
    resolver.delete(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
      arrayOf(file.name)
    )
    val uri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    ) ?: error("Could not create Downloads/${file.name}")
    resolver.openOutputStream(uri)?.use { stream -> stream.write(file.readBytes()) }
      ?: error("Could not write Downloads/${file.name}")

    val publishedName = resolver.query(
      uri,
      arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
      null,
      null,
      null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    assertEquals(
      "MediaStore de-duplicated Downloads/${file.name}, so a stale artifact from an earlier " +
        "install is still on the device and this run's bytes were written elsewhere. " +
        "Clear it first: adb shell rm -f \"/sdcard/Download/media3-*\"",
      file.name,
      publishedName
    )
    return uri
  }
}
