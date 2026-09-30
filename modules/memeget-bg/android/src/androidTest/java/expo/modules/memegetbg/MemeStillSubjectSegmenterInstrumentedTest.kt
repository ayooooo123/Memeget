package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native half of still-image subject cutouts: decode + memory plan
 * ([MemeStillSubjectSegmenter.prepare]) and cutout materialization from masks
 * ([MemeStillSubjectSegmenter.writeCutouts]). Segmentation itself runs in JS on
 * react-native-executorch FastSAM, so the masks here are synthetic — what is
 * under test is that the pixels, bounds, alpha and files that come out match
 * the mask that went in.
 */
@RunWith(AndroidJUnit4::class)
class MemeStillSubjectSegmenterInstrumentedTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun requestId(suffix: String): String = "test-$suffix-${System.currentTimeMillis()}"

  private fun sourceImage(name: String, width: Int, height: Int): File {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(20, 120, 220))
    val file = File(context.cacheDir, name)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
  }

  /** A filled rectangle mask of `width` x `height`, packed the way JS sends it. */
  private fun packedRect(width: Int, height: Int): String {
    val packed = ByteArray((width * height + 7) / 8)
    for (i in 0 until width * height) {
      packed[i ushr 3] = (packed[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
    }
    return Base64.encodeToString(packed, Base64.NO_WRAP)
  }

  private fun cutoutBitmap(cutout: SubjectCutout): Bitmap = requireNotNull(
    BitmapFactory.decodeFile(requireNotNull(Uri.parse(cutout.cutoutUri).path))
  ) { "could not decode ${cutout.cutoutUri}" }

  @Test
  fun prepareFitsTheMemoryCeilingAndWritesAReadableModelCopy() {
    val source = sourceImage("cutout-large.png", 4000, 3000)
    val id = requestId("prepare")
    try {
      val prepared = MemeStillSubjectSegmenter.prepare(context, Uri.fromFile(source).toString(), id)
      assertEquals(4000, prepared.sourceWidth)
      assertEquals(3000, prepared.sourceHeight)
      assertTrue(maxOf(prepared.workingWidth, prepared.workingHeight) <= MemeStillSubjectSegmenter.MAX_WORKING_EDGE)
      assertTrue(prepared.estimatedPeakBytes <= prepared.ceilingBytes)
      assertTrue(prepared.workingUri.startsWith("file://"))
      val copy = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(Uri.parse(prepared.workingUri).path, copy)
      assertEquals(prepared.workingWidth, copy.outWidth)
      assertEquals(prepared.workingHeight, copy.outHeight)
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  @Test
  fun writesOneFeatheredCutoutPerMaskPlusACombinedOne() {
    val source = sourceImage("cutout-two.png", 400, 300)
    val id = requestId("write")
    try {
      MemeStillSubjectSegmenter.prepare(context, Uri.fromFile(source).toString(), id)
      val result = MemeStillSubjectSegmenter.writeCutouts(
        id,
        boxes = listOf(40, 30, 100, 200, 250, 60, 80, 120),
        masks = listOf(packedRect(100, 200), packedRect(80, 120)),
        droppedSubjects = 1
      )
      assertEquals(2, result.subjects.size)
      assertEquals(listOf(0, 1), result.subjects.map { it.subjectIndex })
      assertEquals(1, result.droppedSubjects)
      assertNotEquals(result.subjects[0].cutoutUri, result.subjects[1].cutoutUri)

      val first = result.subjects[0]
      // Mask box grown by the one-pixel feather on every side.
      assertEquals(102, first.widthPx)
      assertEquals(202, first.heightPx)
      assertEquals(39.0 / 400, first.bounds.x, 1e-9)
      assertEquals((100.0 * 200) / (400 * 300), first.coverage, 1e-9)
      val pixels = cutoutBitmap(first)
      // The feather ring: a corner touches one mask pixel, an edge three.
      assertEquals((255 + 4) / 9, Color.alpha(pixels.getPixel(0, 0)))
      assertEquals((3 * 255 + 4) / 9, Color.alpha(pixels.getPixel(0, 101)))
      assertEquals(255, Color.alpha(pixels.getPixel(51, 101)))
      assertEquals(Color.rgb(20, 120, 220), pixels.getPixel(51, 101) or (0xFF shl 24))
      pixels.recycle()

      val combined = requireNotNull(result.combined)
      assertNull(combined.subjectIndex)
      assertEquals((100.0 * 200 + 80.0 * 120) / (400 * 300), combined.coverage, 1e-9)
      assertTrue("model copy removed", !File(File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), id), "$id-working.jpg").exists())
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  @Test
  fun noMasksIsNoSubjectNotAFailure() {
    val source = sourceImage("cutout-none.png", 200, 200)
    val id = requestId("none")
    try {
      MemeStillSubjectSegmenter.prepare(context, Uri.fromFile(source).toString(), id)
      val result = MemeStillSubjectSegmenter.writeCutouts(id, emptyList(), emptyList(), 0)
      assertNull(result.combined)
      assertTrue(result.subjects.isEmpty())
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  @Test
  fun cancellationRejectsAndRemovesTheRequestFiles() {
    val source = sourceImage("cutout-cancel.png", 300, 300)
    val id = requestId("cancel")
    val directory = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), id)
    try {
      MemeStillSubjectSegmenter.prepare(context, Uri.fromFile(source).toString(), id)
      MemeStillSubjectSegmenter.requestCancel(id)
      try {
        MemeStillSubjectSegmenter.writeCutouts(id, listOf(0, 0, 50, 50), listOf(packedRect(50, 50)), 0)
        fail("a cancelled request must not produce cutouts")
      } catch (error: SubjectCutoutException) {
        assertEquals(SubjectCutoutFailure.CANCELLED, error.failure)
      }
      assertTrue("request directory removed", !directory.exists())
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  @Test
  fun releasesFinishedRequestsAndSweepsStaleOnes() {
    val source = sourceImage("cutout-cleanup.png", 200, 200)
    val id = requestId("cleanup")
    val directory = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), id)
    MemeStillSubjectSegmenter.prepare(context, Uri.fromFile(source).toString(), id)
    val result = MemeStillSubjectSegmenter.writeCutouts(id, listOf(20, 20, 100, 100), listOf(packedRect(100, 100)), 0)
    val cutoutFile = File(requireNotNull(Uri.parse(requireNotNull(result.combined).cutoutUri).path))
    assertTrue("cutout was written", cutoutFile.isFile)

    val stale = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), "test-stale-orphan")
    assertTrue(stale.mkdirs() || stale.isDirectory)
    FileOutputStream(File(stale, "orphan.png")).use { it.write(ByteArray(64)) }
    assertTrue(stale.setLastModified(System.currentTimeMillis() - 6L * 60 * 60 * 1000))

    assertTrue("sweep counted it", MemeStillSubjectSegmenter.sweepStaleRequests(context, id) >= 1)
    assertTrue("the stale orphan was swept", !stale.exists())
    assertTrue("live request kept", cutoutFile.isFile)

    assertTrue("release removed the request", MemeStillSubjectSegmenter.release(context, id))
    assertTrue("request directory gone", !directory.exists())
    assertTrue("second release is a no-op", !MemeStillSubjectSegmenter.release(context, id))
    source.delete()
  }
}
