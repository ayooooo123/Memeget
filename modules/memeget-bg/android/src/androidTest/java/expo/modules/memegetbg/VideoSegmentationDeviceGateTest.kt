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
class VideoSegmentationDeviceGateTest {
  @Test
  fun vendoredModelAndFixturesMatchPinnedProvenance() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val validation = VideoSegmentationDeviceGateProbe.validateAssetProvenance(instrumentation)

    assertTrue(validation.getBoolean("allDigestsMatch"))
    assertTrue(validation.getBoolean("modelProvenanceComplete"))
    assertEquals(3, validation.getJSONArray("fixtures").length())
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

    println("VIDEO_SEGMENTATION_GATE_PACKAGE=${instrumentation.targetContext.packageName}")
    println("VIDEO_SEGMENTATION_GATE_PATH=${output.absolutePath}")
    println("VIDEO_SEGMENTATION_GATE_STATUS=${result.getString("gateStatus")}")

    assertTrue("Gate output was not written", output.isFile)
    assertFalse("Emulator results must never become the gate", result.getJSONObject("device").getBoolean("emulator"))
    assertEquals("Pixel 9 Pro", result.getJSONObject("device").getString("model"))
    assertEquals(9, result.getJSONArray("matrix").length())
    assertEquals(3, result.getJSONObject("provenance").getJSONArray("fixtures").length())
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
    println("VIDEO_SEGMENTATION_GATE_DOWNLOAD_URI=$uri")
  }
}
