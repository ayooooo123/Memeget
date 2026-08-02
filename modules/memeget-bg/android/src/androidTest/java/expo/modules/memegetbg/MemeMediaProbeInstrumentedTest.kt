package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemeMediaProbeInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun clipboardFile(name: String): File {
    val directory = File(context.cacheDir, "clipboard")
    check(directory.mkdirs() || directory.isDirectory)
    return File(directory, name).also { it.delete() }
  }

  private fun contentUri(file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.memegetclip", file)

  @Test
  fun probesGeneratedImageFromFileAndContentUris() {
    val image = clipboardFile("probe-generated.png")
    val bitmap = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888)
    try {
      bitmap.eraseColor(Color.rgb(12, 34, 56))
      FileOutputStream(image).use { output ->
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
    } finally {
      bitmap.recycle()
    }

    val fileProbe = MemeMediaProbe.probe(context, Uri.fromFile(image).toString())
    val contentProbe = MemeMediaProbe.probe(context, contentUri(image).toString())

    for (probe in listOf(fileProbe, contentProbe)) {
      assertEquals("image", probe.kind)
      assertEquals(37, probe.width)
      assertEquals(23, probe.height)
      assertEquals(0, probe.rotationDegrees)
      assertNull(probe.durationUs)
      assertNull(probe.frameRate)
      assertNull(probe.videoMime)
      assertNull(probe.audioMime)
      assertFalse(probe.hasAudio)
      assertTrue(probe.seekable)
      assertEquals(image.length(), probe.byteSize)
      assertTrue(probe.stableId.matches(Regex("^[a-f0-9]{64}$")))
    }
    assertTrue(fileProbe.modifiedTimeMs != null && fileProbe.modifiedTimeMs > 0L)
    assertNotEquals(fileProbe.stableId, contentProbe.stableId)
  }

  @Test
  fun probesSyntheticH264AacFactsAndReleasesResourcesAcrossRepeatCalls() {
    val video = clipboardFile("probe-synthetic-5s-720p.mp4")
    context.assets.open("synthetic_5s_720p.mp4").use { input ->
      FileOutputStream(video).use { output -> input.copyTo(output) }
    }
    val uri = contentUri(video).toString()
    val descriptorCountBefore = File("/proc/self/fd").list()?.size ?: 0

    var last: MemeMediaProbe.Result? = null
    repeat(32) {
      last = MemeMediaProbe.probe(context, uri)
    }
    val descriptorCountAfter = File("/proc/self/fd").list()?.size ?: 0
    val probe = checkNotNull(last)

    assertEquals("video", probe.kind)
    assertEquals(1_280, probe.width)
    assertEquals(720, probe.height)
    assertEquals(0, probe.rotationDegrees)
    assertTrue(abs(checkNotNull(probe.durationUs) - 5_000_000L) <= 100_000L)
    assertTrue(abs(checkNotNull(probe.frameRate) - 30.0) <= 0.1)
    assertEquals("video/avc", probe.videoMime)
    assertEquals("audio/mp4a-latm", probe.audioMime)
    assertTrue(probe.hasAudio)
    assertTrue(probe.seekable)
    assertEquals(video.length(), probe.byteSize)
    assertTrue(
      "Probe leaked descriptors: before=$descriptorCountBefore after=$descriptorCountAfter",
      descriptorCountAfter <= descriptorCountBefore + 2
    )
  }

  @Test
  fun reportsUnreadableSourcesWithContext() {
    val missing = Uri.fromFile(File(context.cacheDir, "missing-probe-source.mp4")).toString()
    try {
      MemeMediaProbe.probe(context, missing)
      fail("Expected probe failure")
    } catch (error: IOException) {
      assertTrue(error.message.orEmpty().contains("Could not probe media"))
      assertTrue(error.message.orEmpty().contains("missing-probe-source.mp4"))
    }
  }
}
