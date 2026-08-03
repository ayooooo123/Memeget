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
class PreviewSpeedTest {
  @Test
  fun cleanPreviewRequiresRateFidelityAndNoRebuffering() {
    assertTrue(PreviewSpeedProbe.previewIsClean(1.25, 1.25, 0))
    assertTrue(PreviewSpeedProbe.previewIsClean(1.27, 1.25, 0))
    assertFalse("a rebuffer means the user saw a stall", PreviewSpeedProbe.previewIsClean(1.25, 1.25, 1))
    assertFalse("3.2% slow is not a faithful preview", PreviewSpeedProbe.previewIsClean(1.21, 1.25, 0))
    assertFalse(PreviewSpeedProbe.previewIsClean(Double.NaN, 1.0, 0))
    assertFalse(PreviewSpeedProbe.previewIsClean(1.0, 0.0, 0))
  }

  @Test
  fun measuresEveryEditorSpeedOnThisDevice() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val result = PreviewSpeedProbe.run(instrumentation)
    val output = instrumentation.targetContext.filesDir.resolve("video-preview-speed.json")
    output.writeText(result.toString(2))
    // Shared Downloads, not getExternalFilesDir: the instrumentation APK is uninstalled after the
    // run. Downloads de-duplicates a repeated DISPLAY_NAME into "name (1).json", so the caller
    // must delete the old file before re-running.
    val resolver = instrumentation.targetContext.contentResolver
    val downloadUri = resolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, output.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    ) ?: error("Could not create Downloads preview-speed output")
    resolver.openOutputStream(downloadUri)?.use { stream -> stream.write(output.readBytes()) }
      ?: error("Could not write Downloads preview-speed output")
    println("PREVIEW_SPEED_DOWNLOAD_URI=$downloadUri")
    println("PREVIEW_SPEED_PATH=${output.absolutePath}")
    println("PREVIEW_SPEED_RESULT=${result}")

    assertEquals(PreviewSpeedProbe.SPEEDS.size, result.getJSONArray("speeds").length())
    assertTrue("Volume behaviour was not observed", result.getJSONArray("volume").length() > 0)
  }
}
