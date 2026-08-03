package expo.modules.memegetbg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for still-image subject cutouts (Task 3.2).
 *
 * Real ML Kit, real Play services, real photographs of people. The fixtures are
 * frames pulled on-device out of the pedestrian clips already committed for the
 * Task 0.2 gate (public OpenCV sample footage, provenance in
 * assets/video_segmentation_provenance.json) — no new binary fixtures, no
 * maintainer or user media, and nothing synthetic pretending to be a subject.
 *
 * What this cannot do by itself is turn the radio off. The airplane-mode half of
 * the acceptance is driven from the host between two runs of
 * [segmentsWithoutAnyNetwork]; the run itself asserts that no download was
 * needed, which is only true if the model really was reused from disk.
 *
 * Every assertion is on observed output. `Log.i(TAG, ...)` lines carry the
 * numbers into the evidence document.
 */
@RunWith(AndroidJUnit4::class)
class MemeStillSubjectSegmenterInstrumentedTest {
  private companion object {
    const val TAG = "MemeStillCutout"
    const val ONE_PERSON = "video_segmentation_one_person_10s_720p.mp4"
    const val TWO_PEOPLE = "video_segmentation_two_crossing_10s_720p.mp4"
  }

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  private fun copyAsset(name: String): File {
    val file = File(context.cacheDir, "cutout-fixture-$name")
    if (file.isFile && file.length() > 0) return file
    instrumentation.context.assets.open(name).use { input ->
      FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    return file
  }

  /**
   * A still frame of a real person, decoded through the app's own MediaCodec
   * path so the fixture is a photograph rather than a drawing.
   */
  private fun frameOf(asset: String, seconds: Double, name: String): File {
    val clip = copyAsset(asset)
    val framePath = VideoFrameExtractor.extract(context, Uri.fromFile(clip).toString(), seconds)
    val frame = File(requireNotNull(Uri.parse(framePath).path) { "extractor returned $framePath" })
    assertTrue("extracted a frame from $asset", frame.isFile && frame.length() > 0)
    val target = File(context.cacheDir, name)
    frame.copyTo(target, overwrite = true)
    frame.delete()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(target.absolutePath, bounds)
    Log.i(TAG, "fixture $name = ${bounds.outWidth}x${bounds.outHeight} from $asset at ${seconds}s")
    assertTrue("frame has pixels", bounds.outWidth > 0 && bounds.outHeight > 0)
    return target
  }

  private class RecordedProgress {
    val phases: MutableList<String> = Collections.synchronizedList(ArrayList())
    var lastTotalBytes: Long = 0
    var lastDownloadedBytes: Long = 0

    val sink = MemeStillSubjectSegmenter.ProgressSink { payload ->
      val phase = payload["phase"]?.toString().orEmpty()
      phases.add(phase)
      (payload["totalBytes"] as? Long)?.let { if (it > 0) lastTotalBytes = it }
      (payload["bytesDownloaded"] as? Long)?.let { if (it > lastDownloadedBytes) lastDownloadedBytes = it }
    }

    fun sawDownload(): Boolean = phases.contains("downloading")
  }

  private fun requestId(suffix: String): String =
    "test-${suffix}-${System.currentTimeMillis()}"

  private fun cutoutBitmap(cutout: SubjectCutout): Bitmap = requireNotNull(
    BitmapFactory.decodeFile(
      requireNotNull(Uri.parse(cutout.cutoutUri).path),
      BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    )
  ) { "could not decode ${cutout.cutoutUri}" }

  private fun describe(result: SubjectCutoutResult): String = JSONObject()
    .put("sourceWidth", result.sourceWidth)
    .put("sourceHeight", result.sourceHeight)
    .put("workingWidth", result.workingWidth)
    .put("workingHeight", result.workingHeight)
    .put("sampleSize", result.sampleSize)
    .put("estimatedPeakBytes", result.estimatedPeakBytes)
    .put("ceilingBytes", result.ceilingBytes)
    .put("combinedCoverage", result.combined?.coverage ?: -1.0)
    .put("subjectCount", result.subjects.size)
    .put("droppedSubjects", result.droppedSubjects)
    .put(
      "subjects",
      JSONArray().apply {
        result.subjects.forEach { subject ->
          put(
            JSONObject()
              .put("index", subject.subjectIndex ?: -1)
              .put("coverage", subject.coverage)
              .put("widthPx", subject.widthPx)
              .put("heightPx", subject.heightPx)
              .put("bytes", subject.bytes)
          )
        }
      }
    )
    .toString()

  private fun assertPlausibleCutout(label: String, cutout: SubjectCutout, result: SubjectCutoutResult) {
    assertTrue("$label: coverage is a fraction", cutout.coverage > 0.0 && cutout.coverage <= 1.0)
    assertTrue("$label: bounds inside the frame", cutout.bounds.x >= 0.0 && cutout.bounds.y >= 0.0)
    assertTrue(
      "$label: bounds do not overflow",
      cutout.bounds.x + cutout.bounds.width <= 1.0001 &&
        cutout.bounds.y + cutout.bounds.height <= 1.0001
    )
    assertTrue("$label: bounds have area", cutout.bounds.width > 0.0 && cutout.bounds.height > 0.0)
    assertTrue("$label: pixels exist", cutout.widthPx > 0 && cutout.heightPx > 0)
    assertTrue(
      "$label: cutout no larger than the working frame",
      cutout.widthPx <= result.workingWidth && cutout.heightPx <= result.workingHeight
    )
    assertTrue("$label: file has bytes", cutout.bytes > 0)
    assertTrue("$label: file exists", File(requireNotNull(Uri.parse(cutout.cutoutUri).path)).isFile)
  }

  /**
   * The first-download path plus the memory ceiling. On a device that already
   * holds the model this run reuses it; the download is exercised for real by
   * whichever run happens first on a fresh Play services state, and the log line
   * records which one this was.
   */
  @Test
  fun segmentsARealSubjectWithinTheStatedMemoryCeiling() {
    val source = frameOf(ONE_PERSON, 3.0, "cutout-one-person.jpg")
    val installedBefore = MemeStillSubjectSegmenter.moduleInstalled(context)
    val progress = RecordedProgress()
    val id = requestId("first")
    val started = System.currentTimeMillis()
    val result = MemeStillSubjectSegmenter.segment(
      context,
      Uri.fromFile(source).toString(),
      id,
      progress.sink
    )
    val elapsed = System.currentTimeMillis() - started
    try {
      Log.i(
        TAG,
        "first-run installedBefore=$installedBefore sawDownload=${progress.sawDownload()} " +
          "downloadBytes=${progress.lastDownloadedBytes}/${progress.lastTotalBytes} " +
          "elapsedMs=$elapsed result=${describe(result)}"
      )
      // The model download is a one-time state, so it can only be observed on a
      // device that did not have it. Both outcomes are recorded, and either way
      // the segmenter reached inference.
      assertTrue("segmentation ran", progress.phases.contains("segmenting"))
      if (!installedBefore) {
        assertTrue("a missing module means a download happened", progress.sawDownload())
      }

      val combined = requireNotNull(result.combined) { "no subject found in a frame of a person" }
      assertPlausibleCutout("combined", combined, result)
      assertTrue("at least one subject", result.subjects.isNotEmpty())
      result.subjects.forEach { subject -> assertPlausibleCutout("subject", subject, result) }

      // The ceiling is the point of the working-size derivation, not decoration.
      assertEquals(
        "ceiling reported",
        MemeStillSubjectSegmenter.MEMORY_CEILING_BYTES,
        result.ceilingBytes
      )
      assertTrue(
        "estimate ${result.estimatedPeakBytes} within ceiling ${result.ceilingBytes}",
        result.estimatedPeakBytes <= result.ceilingBytes
      )
      assertTrue(
        "working edge ${result.workingWidth}x${result.workingHeight} within the cap",
        maxOf(result.workingWidth, result.workingHeight) <= MemeStillSubjectSegmenter.MAX_WORKING_EDGE
      )
      assertEquals(
        "estimate matches the working size",
        MemeStillSubjectSegmenter.estimatedPeak(result.workingWidth, result.workingHeight),
        result.estimatedPeakBytes
      )
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  /**
   * Airplane-mode reuse. The host turns the radios off around this run; passing
   * without a download proves the model is on disk and the feature is offline
   * after its one download, which is the promise the UI makes.
   */
  @Test
  fun segmentsWithoutAnyNetwork() {
    val source = frameOf(ONE_PERSON, 5.0, "cutout-offline.jpg")
    val progress = RecordedProgress()
    val id = requestId("offline")
    val result = MemeStillSubjectSegmenter.segment(
      context,
      Uri.fromFile(source).toString(),
      id,
      progress.sink
    )
    try {
      Log.i(
        TAG,
        "offline-run sawDownload=${progress.sawDownload()} result=${describe(result)}"
      )
      assertTrue("no download was needed", !progress.sawDownload())
      val combined = requireNotNull(result.combined) { "no subject found offline" }
      assertPlausibleCutout("offline combined", combined, result)
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  /**
   * The alpha edge, which is what separates a cutout from a rectangle: the
   * boundary has to carry partial alpha, and the inside has to be genuinely
   * opaque source pixels rather than a flat fill.
   */
  @Test
  fun producesASoftAlphaEdgeAtTheCutoutBoundary() {
    val source = frameOf(ONE_PERSON, 3.0, "cutout-alpha-edge.jpg")
    val id = requestId("alpha")
    val result = MemeStillSubjectSegmenter.segment(
      context,
      Uri.fromFile(source).toString(),
      id,
      MemeStillSubjectSegmenter.ProgressSink { }
    )
    try {
      val combined = requireNotNull(result.combined) { "no subject found" }
      val bitmap = cutoutBitmap(combined)
      try {
        assertTrue("cutout carries an alpha channel", bitmap.hasAlpha())
        var transparent = 0L
        var partial = 0L
        var opaque = 0L
        var opaqueColours = 0
        val distinct = HashSet<Int>()
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
          bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
          for (x in 0 until bitmap.width) {
            when (val alpha = Color.alpha(row[x])) {
              0 -> transparent += 1
              255 -> {
                opaque += 1
                if (distinct.size < 64) distinct.add(row[x] and 0x00F8F8F8)
              }
              else -> {
                partial += 1
                assertTrue("alpha stays in range", alpha in 1..254)
              }
            }
          }
        }
        opaqueColours = distinct.size
        Log.i(
          TAG,
          "alpha-edge ${bitmap.width}x${bitmap.height} transparent=$transparent " +
            "partial=$partial opaque=$opaque distinctOpaqueColours=$opaqueColours"
        )
        assertTrue("the cutout has holes: $transparent transparent pixels", transparent > 0)
        assertTrue("the cutout has a subject: $opaque opaque pixels", opaque > 0)
        // A hard 0/255 mask is the failure this looks for — it is what a
        // thresholded mask produces, and it shows as jagged edges in the export.
        assertTrue("the boundary is antialiased: $partial partial pixels", partial > 0)
        // Flat fill would mean the mask was applied to the wrong bitmap.
        assertTrue("subject pixels come from the photo: $opaqueColours colours", opaqueColours > 4)
      } finally {
        bitmap.recycle()
      }
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  /**
   * Multiple subjects, each addressable on its own. This is the frame the Task
   * 0.2 gate used for crossing pedestrians, so it genuinely contains more than
   * one person.
   */
  @Test
  fun separatesMultipleSubjectsIntoIndividualCutouts() {
    val source = frameOf(TWO_PEOPLE, 4.0, "cutout-two-people.jpg")
    val id = requestId("multi")
    val result = MemeStillSubjectSegmenter.segment(
      context,
      Uri.fromFile(source).toString(),
      id,
      MemeStillSubjectSegmenter.ProgressSink { }
    )
    try {
      Log.i(TAG, "multi-subject result=${describe(result)}")
      val combined = requireNotNull(result.combined) { "no subject found in a crowd frame" }
      assertTrue(
        "found more than one subject, got ${result.subjects.size}",
        result.subjects.size >= 2
      )
      val indices = result.subjects.map { it.subjectIndex }
      assertEquals("subject indices are distinct", indices.toSet().size, indices.size)
      val uris = result.subjects.map(SubjectCutout::cutoutUri)
      assertEquals("each subject has its own file", uris.toSet().size, uris.size)
      result.subjects.forEach { subject ->
        assertPlausibleCutout("subject ${subject.subjectIndex}", subject, result)
        // A per-subject cutout covers less of the frame than all of them do.
        assertTrue(
          "subject ${subject.subjectIndex} coverage ${subject.coverage} " +
            "under combined ${combined.coverage}",
          subject.coverage <= combined.coverage + 1e-6
        )
      }
      // Two subjects in the same place would mean the split did nothing.
      val first = result.subjects[0].bounds
      val second = result.subjects[1].bounds
      assertNotEquals("subjects sit in different places", first.toMap(), second.toMap())
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  /**
   * Cancellation, and what it leaves behind. Not interruptible mid-inference, so
   * what is being verified is that the request stops, reports itself cancelled,
   * and removes its working directory — the temp-cleanup half of the acceptance.
   */
  @Test
  fun cancellationStopsTheRunAndRemovesItsFiles() {
    val source = frameOf(ONE_PERSON, 3.0, "cutout-cancel.jpg")
    val id = requestId("cancel")
    val directory = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), id)
    val failure = AtomicReference<Throwable?>(null)
    val finished = CountDownLatch(1)
    val worker = Thread {
      try {
        MemeStillSubjectSegmenter.segment(
          context,
          Uri.fromFile(source).toString(),
          id,
          MemeStillSubjectSegmenter.ProgressSink { }
        )
      } catch (error: Throwable) {
        failure.set(error)
      } finally {
        finished.countDown()
      }
    }
    try {
      worker.start()
      // Cancel while the run is still setting up; the checkpoints do the rest.
      Thread.sleep(50)
      MemeStillSubjectSegmenter.requestCancel(id)
      assertTrue("cancelled run finished", finished.await(120, TimeUnit.SECONDS))
      val error = failure.get()
      Log.i(TAG, "cancel error=${error?.javaClass?.simpleName} message=${error?.message}")
      assertTrue(
        "cancellation surfaced as a cutout failure, got $error",
        error is SubjectCutoutException
      )
      assertEquals(
        "cancellation is reported as cancellation",
        SubjectCutoutFailure.CANCELLED,
        (error as SubjectCutoutException).failure
      )
      assertTrue("working directory removed: $directory", !directory.exists())
    } finally {
      MemeStillSubjectSegmenter.release(context, id)
      source.delete()
    }
  }

  /**
   * Temp cleanup: an explicit release removes a finished request, and a stale
   * directory a crash left behind is swept by the next request rather than
   * living in the cache forever.
   */
  @Test
  fun releasesFinishedRequestsAndSweepsStaleOnes() {
    val source = frameOf(ONE_PERSON, 3.0, "cutout-cleanup.jpg")
    val id = requestId("cleanup")
    val directory = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), id)
    val result = MemeStillSubjectSegmenter.segment(
      context,
      Uri.fromFile(source).toString(),
      id,
      MemeStillSubjectSegmenter.ProgressSink { }
    )
    val combined = requireNotNull(result.combined) { "no subject found" }
    val cutoutFile = File(requireNotNull(Uri.parse(combined.cutoutUri).path))
    assertTrue("cutout was written", cutoutFile.isFile)

    // A directory a crash orphaned an hour ago.
    val stale = File(File(context.cacheDir, MemeStillSubjectSegmenter.WORK_DIR), "test-stale-orphan")
    assertTrue("stale directory created", stale.mkdirs() || stale.isDirectory)
    val orphan = File(stale, "orphan.png")
    FileOutputStream(orphan).use { stream -> stream.write(ByteArray(64)) }
    assertTrue(
      "stale timestamp applied",
      stale.setLastModified(System.currentTimeMillis() - 6L * 60 * 60 * 1000)
    )

    val sweptCount = MemeStillSubjectSegmenter.sweepStaleRequests(context, id)
    Log.i(TAG, "cleanup swept=$sweptCount staleGone=${!stale.exists()}")
    assertTrue("the stale orphan was swept", !stale.exists())
    assertTrue("sweep counted it", sweptCount >= 1)
    // The live request survived the sweep that removed the orphan.
    assertTrue("live request kept", cutoutFile.isFile)

    assertTrue("release removed the request", MemeStillSubjectSegmenter.release(context, id))
    assertTrue("cutout file gone", !cutoutFile.exists())
    assertTrue("request directory gone", !directory.exists())
    // Releasing twice is not an error; there is simply nothing left.
    assertTrue("second release is a no-op", !MemeStillSubjectSegmenter.release(context, id))
    assertNull("nothing left to clean up", directory.listFiles())
    source.delete()
  }
}
