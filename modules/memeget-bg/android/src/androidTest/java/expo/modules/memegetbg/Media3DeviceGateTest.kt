package expo.modules.memegetbg

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3DeviceGateTest {
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
