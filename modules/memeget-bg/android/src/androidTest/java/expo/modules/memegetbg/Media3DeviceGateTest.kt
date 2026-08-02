package expo.modules.memegetbg

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
    val resolver = instrumentation.targetContext.contentResolver
    resolver.delete(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
      arrayOf(output.name)
    )
    val downloadUri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, output.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    ) ?: error("Could not create Downloads gate output")
    resolver.openOutputStream(downloadUri)?.use { stream ->
      stream.write(output.readBytes())
    } ?: error("Could not write Downloads gate output")
    println("MEDIA3_GATE_DOWNLOAD_URI=$downloadUri")
    println("MEDIA3_GATE_PACKAGE=${instrumentation.targetContext.packageName}")
    println("MEDIA3_GATE_PATH=${output.absolutePath}")

    assertTrue("Gate output was not written", output.isFile)
    assertEquals(4, result.getJSONArray("fixtures").length())
    assertTrue("Gate status was not finalized", result.getString("gateStatus") in setOf("PASS", "FAIL"))
  }
}
