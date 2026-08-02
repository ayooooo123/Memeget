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
      assertFalse(probe.flipX)
      assertFalse(probe.flipY)
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
    assertFalse(probe.flipX)
    assertFalse(probe.flipY)
    assertTrue(abs(checkNotNull(probe.durationUs) - 5_000_000L) <= 100_000L)
    assertTrue(abs(checkNotNull(probe.frameRate) - 30.0) <= 0.1)
    assertEquals("video/avc", probe.videoMime)
    assertEquals("audio/mp4a-latm", probe.audioMime)
    assertTrue(probe.hasAudio)
    assertTrue(probe.seekable)
    assertEquals(video.length(), probe.byteSize)
    val fileProbe = MemeMediaProbe.probe(context, Uri.fromFile(video).toString())
    assertEquals(probe.width, fileProbe.width)
    assertEquals(probe.height, fileProbe.height)
    assertEquals(probe.durationUs, fileProbe.durationUs)
    assertEquals(probe.videoMime, fileProbe.videoMime)
    assertEquals(probe.audioMime, fileProbe.audioMime)
    assertTrue(fileProbe.seekable)
    assertNotEquals(probe.stableId, fileProbe.stableId)
    assertTrue(
      "Probe leaked descriptors: before=$descriptorCountBefore after=$descriptorCountAfter",
      descriptorCountAfter <= descriptorCountBefore + 2
    )
  }

  @Test
  fun preservesExtractorFactsWhenMetadataRetrieverFails() {
    val video = clipboardFile("probe-retriever-failure.mp4")
    context.assets.open("synthetic_5s_720p.mp4").use { input ->
      FileOutputStream(video).use { output -> input.copyTo(output) }
    }

    val probe =
      MemeMediaProbe.probeWithRetriever(context, Uri.fromFile(video).toString()) { _, _ ->
        throw IOException("forced retriever failure")
      }

    assertEquals("video", probe.kind)
    assertEquals(1_280, probe.width)
    assertEquals(720, probe.height)
    assertTrue(abs(checkNotNull(probe.durationUs) - 5_000_000L) <= 100_000L)
    assertTrue(abs(checkNotNull(probe.frameRate) - 30.0) <= 0.1)
    assertEquals("video/avc", probe.videoMime)
    assertEquals("audio/mp4a-latm", probe.audioMime)
    assertTrue(probe.hasAudio)
  }

  @Test
  fun reportsAllExifOrientationsWithoutLosingRotationOrFlipFacts() {
    val expectations =
      listOf(
        Triple(1, Triple(0, false, false), "normal"),
        Triple(2, Triple(0, true, false), "horizontal"),
        Triple(3, Triple(180, false, false), "rotate-180"),
        Triple(4, Triple(0, false, true), "vertical"),
        Triple(5, Triple(90, false, true), "transpose"),
        Triple(6, Triple(90, false, false), "rotate-90"),
        Triple(7, Triple(90, true, false), "transverse"),
        Triple(8, Triple(270, false, false), "rotate-270")
      )
    for ((orientation, transform, label) in expectations) {
      val image = clipboardFile("probe-orientation-$orientation.jpg")
      context.assets.open("probe_orientation_$orientation.jpg").use { input ->
        FileOutputStream(image).use { output -> input.copyTo(output) }
      }
      val probe = MemeMediaProbe.probe(context, Uri.fromFile(image).toString())
      assertEquals(label, transform.first, probe.rotationDegrees)
      assertEquals(label, transform.second, probe.flipX)
      assertEquals(label, transform.third, probe.flipY)
    }
  }

  @Test
  fun reportsSilentVideoWithoutInventingAudio() {
    val video = clipboardFile("probe-silent.mp4")
    context.assets.open("synthetic_silent_1s_240p.mp4").use { input ->
      FileOutputStream(video).use { output -> input.copyTo(output) }
    }

    val probe = MemeMediaProbe.probe(context, Uri.fromFile(video).toString())

    assertEquals("video", probe.kind)
    assertEquals(320, probe.width)
    assertEquals(240, probe.height)
    assertEquals("video/avc", probe.videoMime)
    assertNull(probe.audioMime)
    assertFalse(probe.hasAudio)
    assertTrue(probe.seekable)
  }

  @Test
  fun reportsRotatedVideoDisplayMetadata() {
    val video = clipboardFile("probe-rotated.mp4")
    context.assets.open("synthetic_rotated_1s_240p.mp4").use { input ->
      FileOutputStream(video).use { output -> input.copyTo(output) }
    }

    val probe = MemeMediaProbe.probe(context, Uri.fromFile(video).toString())

    assertEquals("video", probe.kind)
    assertEquals(320, probe.width)
    assertEquals(240, probe.height)
    assertEquals(90, probe.rotationDegrees)
    assertFalse(probe.flipX)
    assertFalse(probe.flipY)
  }

  @Test
  fun reportsStreamingProviderAsNonSeekableWithUnknownFileMetadata() {
    val uri = "content://${context.packageName}.streamprobe/image"

    val probe = MemeMediaProbe.probe(context, uri)

    assertEquals("image", probe.kind)
    assertFalse(probe.seekable)
    assertNull(probe.byteSize)
    assertNull(probe.modifiedTimeMs)
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
