package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The reason a cutout attempt produced nothing, as a code the JS side maps to a
 * remedy. These three are NOT interchangeable: offline means "connect and retry",
 * unavailable means "this device/Play services build cannot do it at all", and
 * failed means "that image did not work". A single generic error would send the
 * user looking in the wrong place, so the classification happens here — where the
 * actual exception is — and crosses the bridge verbatim.
 *
 * "No subject found" is deliberately absent: an image with no subject is a
 * successful segmentation with an empty result, not a failure.
 */
internal enum class SubjectCutoutFailure(val code: String) {
  OFFLINE("E_CUTOUT_OFFLINE"),
  MODULE_UNAVAILABLE("E_CUTOUT_MODULE_UNAVAILABLE"),
  CANCELLED("E_CUTOUT_CANCELLED"),
  FAILED("E_CUTOUT_FAILED"),
}

internal class SubjectCutoutException(
  val failure: SubjectCutoutFailure,
  message: String,
  cause: Throwable? = null
) : IOException(message, cause)

/**
 * One materialized cutout: source pixels multiplied by the subject's alpha,
 * cropped to the subject's own bounds and written to disk as a PNG.
 *
 * The bitmap never crosses the bridge. JS holds this reference plus normalized
 * geometry, which is all the renderer needs to place it, and is what keeps a
 * 16 MP alpha channel out of the JS heap.
 */
internal data class SubjectCutout(
  val id: String,
  /** null for the combined "all subjects" cutout. */
  val subjectIndex: Int?,
  val cutoutUri: String,
  /** Where this cutout sits in the oriented source frame, normalized. */
  val bounds: NormalizedImageRect,
  val widthPx: Int,
  val heightPx: Int,
  /** Fraction of the oriented frame the subject's alpha actually covers. */
  val coverage: Double,
  val bytes: Long
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "subjectIndex" to subjectIndex,
    "cutoutUri" to cutoutUri,
    "bounds" to bounds.toMap(),
    "widthPx" to widthPx,
    "heightPx" to heightPx,
    "coverage" to coverage,
    "bytes" to bytes
  )
}

internal data class SubjectCutoutResult(
  val requestId: String,
  val sourceWidth: Int,
  val sourceHeight: Int,
  val workingWidth: Int,
  val workingHeight: Int,
  val sampleSize: Int,
  val estimatedPeakBytes: Long,
  val ceilingBytes: Long,
  val directory: String,
  /** null when the image genuinely has no subject. */
  val combined: SubjectCutout?,
  val subjects: List<SubjectCutout>,
  /** Subjects beyond the per-request cap, reported rather than hidden. */
  val droppedSubjects: Int
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "requestId" to requestId,
    "sourceWidth" to sourceWidth,
    "sourceHeight" to sourceHeight,
    "workingWidth" to workingWidth,
    "workingHeight" to workingHeight,
    "sampleSize" to sampleSize,
    "estimatedPeakBytes" to estimatedPeakBytes,
    "ceilingBytes" to ceilingBytes,
    "directory" to directory,
    "combined" to combined?.toMap(),
    "subjects" to subjects.map(SubjectCutout::toMap),
    "droppedSubjects" to droppedSubjects
  )
}

/**
 * Still-image subject cutouts through ML Kit Subject Segmentation (pinned
 * 16.0.0-beta1, unbundled via Google Play services).
 *
 * Three things make this more than a `process()` call:
 *
 * 1. The model is NOT in the APK. On a sideloaded build Play Store never
 *    prefetches it, so the first attempt has to install the optional module
 *    itself — with progress, because it is a user-visible wait, and with a way
 *    out, because a metered connection is the user's business.
 * 2. Every failure mode needs a different remedy (see [SubjectCutoutFailure]).
 * 3. Memory. A confidence mask is one float per source pixel — 4 bytes, the
 *    same as the decoded ARGB pixel it describes. Segmenting a 32 MP photo at
 *    full size would ask for 128 MB of bitmap plus 128 MB of mask plus the
 *    cutout, which is the allocation that OOM'd this app before. So the working
 *    size is derived FROM the ceiling ([MEMORY_CEILING_BYTES]) rather than from
 *    the source, and the estimate is reported so the caller can show it.
 */
internal object MemeStillSubjectSegmenter {
  /** Cache subdirectory holding one directory per segmentation request. */
  const val WORK_DIR = "meme_work_cutout"

  /**
   * Peak transient bytes one cutout request may allocate.
   *
   * 96 MB sits under the renderer's own working budget so a cutout taken while
   * an export is warm cannot push the process over, and it is comfortably above
   * what the working size below actually needs.
   */
  const val MEMORY_CEILING_BYTES = 96L * 1024L * 1024L

  /**
   * Longest working edge. ML Kit's own segmentation runs at a fixed internal
   * resolution, so mask detail stops improving well before this; going higher
   * would buy resampling artifacts and a second full-resolution allocation
   * instead of a better edge. Masks are resampled at export from here.
   */
  const val MAX_WORKING_EDGE = 2048

  /**
   * ML Kit documents 512x512 as the smallest input it segments accurately. We
   * do not upscale to reach it — that invents detail — but the caller is told,
   * so a poor cutout on a thumbnail reads as "small source" instead of "broken".
   */
  const val RECOMMENDED_MIN_EDGE = 512

  /** Per-request subject cap; the studio cannot show more than a few anyway. */
  const val MAX_SUBJECTS = 8

  /** Alpha at or above this counts as subject when measuring bounds/coverage. */
  private const val MASK_THRESHOLD = 0.5f

  /**
   * Below this the "subject" is a handful of speckled pixels — every one of
   * those we have looked at was noise, and offering it as a cutout wastes the
   * user's tap. Treated as "no subject found", which is not an error.
   */
  private const val MIN_SUBJECT_COVERAGE = 0.001

  /** Decoded ARGB + confidence float + cutout ARGB, per working pixel. */
  private const val BYTES_PER_WORKING_PIXEL = 4L + 4L + 4L

  /** A finished request's files outlive the studio session by at most this. */
  private const val STALE_REQUEST_MS = 60L * 60L * 1000L

  /** How long a single ML Kit call may run before it counts as hung. */
  private const val PROCESS_TIMEOUT_MS = 120_000L

  /** How long the optional-module install may run before it counts as stalled. */
  private const val INSTALL_TIMEOUT_MS = 300_000L

  /**
   * How long the "is it already installed" probe may run. Short on purpose: it
   * gates a UI state, and a stalled probe should show the download state rather
   * than a spinner that outlives the user's patience.
   */
  private const val AVAILABILITY_TIMEOUT_MS = 15_000L

  /** Cancellation poll interval while blocked on a Play services task. */
  private const val WAIT_SLICE_MS = 100L

  private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

  /** Progress payloads for the JS download state; see index.ts. */
  internal fun interface ProgressSink {
    fun send(payload: Map<String, Any?>)
  }

  /**
   * Ask Play services whether the segmentation module is already on the device,
   * without triggering an install. Used to decide whether the UI needs to show
   * a download state at all.
   */
  fun moduleInstalled(context: Context): Boolean {
    segmenter(context).use { held ->
      return try {
        awaitTask(
          ModuleInstall.getClient(context).areModulesAvailable(held.segmenter),
          AVAILABILITY_TIMEOUT_MS
        ) { false }.areModulesAvailable()
      } catch (error: Throwable) {
        // An availability probe that cannot run is not a failure to report to
        // the user: the segment call will classify it properly if they ask for
        // a cutout. Report "not installed" so the UI shows the download state.
        false
      }
    }
  }

  fun requestCancel(requestId: String) {
    cancellations[requestId]?.set(true)
  }

  /**
   * Segment [source] and materialize one cutout per subject plus a combined one.
   *
   * Blocking on purpose: the bridge calls it from an AsyncFunction, so the JS
   * thread is never held, and a linear body is the only readable way to express
   * "install the module, then infer, cancellable at every step".
   */
  fun segment(
    context: Context,
    source: String,
    requestId: String,
    progress: ProgressSink
  ): SubjectCutoutResult {
    require(requestId.isNotBlank()) { "A cutout request needs an id" }
    require(requestId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
      "Cutout request id must be a filesystem-safe token, got \"$requestId\""
    }
    val cancelled = AtomicBoolean(false)
    cancellations[requestId] = cancelled
    val directory = File(File(context.cacheDir, WORK_DIR), requestId)
    try {
      sweepStaleRequests(context, requestId)
      if (!directory.mkdirs() && !directory.isDirectory) {
        throw SubjectCutoutException(
          SubjectCutoutFailure.FAILED,
          "Could not create a working directory for cutout $requestId"
        )
      }
      return runSegmentation(context, source, requestId, directory, cancelled, progress)
    } catch (error: Throwable) {
      directory.deleteRecursively()
      throw error
    } finally {
      cancellations.remove(requestId, cancelled)
    }
  }

  /** Delete the files of one request. Called when a cutout stops being used. */
  fun release(context: Context, requestId: String): Boolean {
    if (requestId.isBlank() || requestId.contains('/') || requestId.contains("..")) return false
    val directory = File(File(context.cacheDir, WORK_DIR), requestId)
    if (!directory.exists()) return false
    return directory.deleteRecursively()
  }

  /**
   * Drop request directories nothing can be using any more.
   *
   * Cutouts are cache files a crash can orphan, and an orphaned 16 MP PNG is
   * invisible until the cache is full, so every new request sweeps.
   */
  fun sweepStaleRequests(context: Context, keepRequestId: String? = null): Int {
    val root = File(context.cacheDir, WORK_DIR)
    val entries = root.listFiles() ?: return 0
    val cutoff = System.currentTimeMillis() - STALE_REQUEST_MS
    var removed = 0
    for (entry in entries) {
      if (entry.name == keepRequestId) continue
      if (cancellations.containsKey(entry.name)) continue
      if (entry.lastModified() > cutoff) continue
      if (entry.deleteRecursively()) removed += 1
    }
    return removed
  }

  // --- segmentation ---------------------------------------------------------

  private fun runSegmentation(
    context: Context,
    source: String,
    requestId: String,
    directory: File,
    cancelled: AtomicBoolean,
    progress: ProgressSink
  ): SubjectCutoutResult {
    val working = decodeWorkingBitmap(context, source)
    try {
      throwIfCancelled(cancelled)
      segmenter(context).use { held ->
        val sink = ProgressSink { payload ->
          progress.send(payload + ("requestId" to requestId))
        }
        ensureModuleInstalled(context, held.segmenter, cancelled, sink)
        sink.send(mapOf("phase" to "segmenting"))
        val result = awaitTask(
          held.segmenter.process(InputImage.fromBitmap(working.bitmap, 0)),
          PROCESS_TIMEOUT_MS
        ) { cancelled.get() }
        throwIfCancelled(cancelled)

        val frameWidth = working.bitmap.width
        val frameHeight = working.bitmap.height
        val subjects = ArrayList<SubjectCutout>()
        val available = result.subjects
        for ((index, subject) in available.withIndex()) {
          if (index >= MAX_SUBJECTS) break
          throwIfCancelled(cancelled)
          val subjectWidth = subject.width
          val subjectHeight = subject.height
          if (subjectWidth <= 0 || subjectHeight <= 0) continue
          val measured = measure(
            subject.confidenceMask,
            subjectWidth,
            subjectHeight,
            frameWidth.toLong() * frameHeight.toLong()
          )
          if (measured == null || measured.coverage < MIN_SUBJECT_COVERAGE) continue
          val bitmap = subject.bitmap ?: maskedCopy(
            working.bitmap,
            subject.confidenceMask,
            subject.startX,
            subject.startY,
            subjectWidth,
            subjectHeight
          ) ?: continue
          val bounds = MemeTextDetector.normalizePixelRect(
            subject.startX + measured.left,
            subject.startY + measured.top,
            subject.startX + measured.right,
            subject.startY + measured.bottom,
            frameWidth,
            frameHeight
          ) ?: continue
          subjects.add(
            writeCutout(
              directory = directory,
              id = "$requestId-subject-$index",
              subjectIndex = index,
              bitmap = bitmap,
              crop = PixelBounds(measured.left, measured.top, measured.right, measured.bottom),
              bounds = bounds,
              coverage = measured.coverage,
              ownsBitmap = subject.bitmap == null
            )
          )
        }

        val combinedMeasured = measure(
          result.foregroundConfidenceMask,
          frameWidth,
          frameHeight,
          frameWidth.toLong() * frameHeight.toLong()
        )
        val combined = if (combinedMeasured == null || combinedMeasured.coverage < MIN_SUBJECT_COVERAGE) {
          null
        } else {
          val foreground = result.foregroundBitmap ?: maskedCopy(
            working.bitmap,
            result.foregroundConfidenceMask,
            0,
            0,
            frameWidth,
            frameHeight
          )
          val bounds = MemeTextDetector.normalizePixelRect(
            combinedMeasured.left,
            combinedMeasured.top,
            combinedMeasured.right,
            combinedMeasured.bottom,
            frameWidth,
            frameHeight
          )
          if (foreground == null || bounds == null) {
            null
          } else {
            writeCutout(
              directory = directory,
              id = "$requestId-combined",
              subjectIndex = null,
              bitmap = foreground,
              crop = PixelBounds(
                combinedMeasured.left,
                combinedMeasured.top,
                combinedMeasured.right,
                combinedMeasured.bottom
              ),
              bounds = bounds,
              coverage = combinedMeasured.coverage,
              ownsBitmap = result.foregroundBitmap == null
            )
          }
        }

        return SubjectCutoutResult(
          requestId = requestId,
          sourceWidth = working.sourceWidth,
          sourceHeight = working.sourceHeight,
          workingWidth = frameWidth,
          workingHeight = frameHeight,
          sampleSize = working.sampleSize,
          estimatedPeakBytes = working.estimatedPeakBytes,
          ceilingBytes = MEMORY_CEILING_BYTES,
          directory = Uri.fromFile(directory).toString(),
          combined = combined,
          subjects = subjects,
          droppedSubjects = max(0, available.size - MAX_SUBJECTS)
        )
      }
    } finally {
      working.recycle()
    }
  }

  private class HeldSegmenter(val segmenter: SubjectSegmenter) : AutoCloseable {
    override fun close() = segmenter.close()
  }

  private fun segmenter(context: Context): HeldSegmenter {
    // Requesting the bitmaps as well as the masks costs nothing extra to
    // produce (ML Kit already has the alpha) and saves us compositing a second
    // full-frame ARGB copy per subject in this process.
    val subjectOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
      .enableConfidenceMask()
      .enableSubjectBitmap()
      .build()
    val options = SubjectSegmenterOptions.Builder()
      .enableForegroundConfidenceMask()
      .enableForegroundBitmap()
      .enableMultipleSubjects(subjectOptions)
      .build()
    return try {
      HeldSegmenter(SubjectSegmentation.getClient(options))
    } catch (error: Throwable) {
      throw classify(error, "Subject cutouts are unavailable on this device")
    }
  }

  /**
   * Make sure the optional Play services module is on the device, downloading it
   * with progress if it is not.
   *
   * A sideloaded build never gets the install-time prefetch the manifest
   * declaration asks for, so this path is the normal one here, not a fallback.
   */
  private fun ensureModuleInstalled(
    context: Context,
    segmenter: SubjectSegmenter,
    cancelled: AtomicBoolean,
    progress: ProgressSink
  ) {
    val client = ModuleInstall.getClient(context)
    val availability = try {
      awaitTask(client.areModulesAvailable(segmenter), AVAILABILITY_TIMEOUT_MS) { cancelled.get() }
    } catch (error: Throwable) {
      throw classify(error, "Could not check whether the cutout model is installed")
    }
    if (availability.areModulesAvailable()) return

    progress.send(mapOf("phase" to "downloading", "bytesDownloaded" to 0, "totalBytes" to 0))
    val state = AtomicReference(InstallState.STATE_UNKNOWN)
    val settled = CountDownLatch(1)
    val listener = InstallStatusListener { update: ModuleInstallStatusUpdate ->
      state.set(update.installState)
      val info = update.progressInfo
      if (info != null) {
        progress.send(
          mapOf(
            "phase" to "downloading",
            "bytesDownloaded" to info.bytesDownloaded,
            "totalBytes" to info.totalBytesToDownload
          )
        )
      }
      when (update.installState) {
        InstallState.STATE_COMPLETED,
        InstallState.STATE_FAILED,
        InstallState.STATE_CANCELED -> settled.countDown()
        else -> Unit
      }
    }
    val request = ModuleInstallRequest.newBuilder()
      .addApi(segmenter)
      .setListener(listener)
      .build()
    val response = try {
      awaitTask(client.installModules(request), INSTALL_TIMEOUT_MS) { cancelled.get() }
    } catch (error: Throwable) {
      client.unregisterListener(listener)
      throw classify(error, "Could not start the cutout model download")
    }
    if (response.areModulesAlreadyInstalled()) {
      client.unregisterListener(listener)
      return
    }
    try {
      val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
      while (settled.count > 0L) {
        if (cancelled.get()) {
          // The documented way to stop a pending install. Only while it IS
          // pending: after completion this would tell Play services to reclaim
          // a model the user just paid for in bandwidth.
          if (state.get() != InstallState.STATE_COMPLETED) {
            runCatching { client.releaseModules(segmenter) }
          }
          throw SubjectCutoutException(
            SubjectCutoutFailure.CANCELLED,
            "Cutout model download cancelled"
          )
        }
        if (System.currentTimeMillis() > deadline) {
          throw SubjectCutoutException(
            SubjectCutoutFailure.OFFLINE,
            "The cutout model download did not finish. Check the connection and try again."
          )
        }
        settled.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
      }
      when (state.get()) {
        InstallState.STATE_COMPLETED -> Unit
        InstallState.STATE_CANCELED -> throw SubjectCutoutException(
          SubjectCutoutFailure.CANCELLED,
          "Cutout model download cancelled"
        )
        else -> throw SubjectCutoutException(
          SubjectCutoutFailure.OFFLINE,
          "The cutout model could not be downloaded. Check the connection and try again."
        )
      }
    } finally {
      client.unregisterListener(listener)
    }
  }

  // --- mask geometry --------------------------------------------------------

  private class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

  private class MaskMeasurement(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val coverage: Double
  )

  /**
   * Tight bounds and coverage of a confidence mask.
   *
   * Coverage is measured against the FRAME, not the mask, so a subject box that
   * happens to be small does not read as high coverage — the studio uses it to
   * order subjects and to decide whether anything was found at all.
   */
  private fun measure(
    mask: FloatBuffer?,
    width: Int,
    height: Int,
    framePixels: Long
  ): MaskMeasurement? {
    if (mask == null || width <= 0 || height <= 0 || framePixels <= 0L) return null
    val available = mask.limit() - mask.position()
    if (available < width * height) return null
    val base = mask.position()
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var covered = 0L
    for (y in 0 until height) {
      val row = base + y * width
      for (x in 0 until width) {
        if (mask.get(row + x) < MASK_THRESHOLD) continue
        covered += 1
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
      }
    }
    if (right < left || bottom < top) return null
    return MaskMeasurement(
      left = left,
      top = top,
      right = right + 1,
      bottom = bottom + 1,
      coverage = covered.toDouble() / framePixels.toDouble()
    )
  }

  /**
   * Fallback cutout for the case ML Kit hands back a mask but no bitmap.
   *
   * Allocates one subject-sized ARGB bitmap — bounded by the subject box, not
   * the frame — and multiplies the source pixels by the mask alpha.
   */
  private fun maskedCopy(
    frame: Bitmap,
    mask: FloatBuffer?,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int
  ): Bitmap? {
    if (mask == null || width <= 0 || height <= 0) return null
    val available = mask.limit() - mask.position()
    if (available < width * height) return null
    val base = mask.position()
    val pixels = IntArray(width * height)
    val sourceRow = IntArray(width)
    for (y in 0 until height) {
      val frameY = startY + y
      if (frameY < 0 || frameY >= frame.height) continue
      val readWidth = min(width, frame.width - startX)
      if (readWidth <= 0) continue
      frame.getPixels(sourceRow, 0, width, startX, frameY, readWidth, 1)
      val row = base + y * width
      for (x in 0 until readWidth) {
        val confidence = mask.get(row + x)
        if (confidence < MASK_THRESHOLD) continue
        val pixel = sourceRow[x]
        val alpha = (confidence.coerceIn(0f, 1f) * 255f).roundToInt()
        pixels[y * width + x] = (pixel and 0x00FFFFFF) or (alpha shl 24)
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun writeCutout(
    directory: File,
    id: String,
    subjectIndex: Int?,
    bitmap: Bitmap,
    crop: PixelBounds,
    bounds: NormalizedImageRect,
    coverage: Double,
    ownsBitmap: Boolean
  ): SubjectCutout {
    // ML Kit's own bitmaps are already the subject box, so the crop here only
    // trims the transparent margin the mask bounds proved is empty.
    val left = crop.left.coerceIn(0, max(0, bitmap.width - 1))
    val top = crop.top.coerceIn(0, max(0, bitmap.height - 1))
    val right = crop.right.coerceIn(left + 1, bitmap.width)
    val bottom = crop.bottom.coerceIn(top + 1, bitmap.height)
    val trimmed = if (left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height) {
      bitmap
    } else {
      Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
    val file = File(directory, "$id.png")
    try {
      FileOutputStream(file).use { out ->
        if (!trimmed.compress(Bitmap.CompressFormat.PNG, 100, out)) {
          throw SubjectCutoutException(
            SubjectCutoutFailure.FAILED,
            "Could not encode the cutout for $id"
          )
        }
      }
      return SubjectCutout(
        id = id,
        subjectIndex = subjectIndex,
        cutoutUri = Uri.fromFile(file).toString(),
        bounds = bounds,
        widthPx = trimmed.width,
        heightPx = trimmed.height,
        coverage = coverage,
        bytes = file.length()
      )
    } finally {
      if (trimmed !== bitmap) trimmed.recycle()
      if (ownsBitmap) bitmap.recycle()
    }
  }

  // --- decode ---------------------------------------------------------------

  private class WorkingBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sampleSize: Int,
    val estimatedPeakBytes: Long
  ) {
    fun recycle() {
      if (!bitmap.isRecycled) bitmap.recycle()
    }
  }

  /**
   * Decode [source] upright, at a size the memory ceiling allows.
   *
   * EXIF is applied for real here, unlike the renderer's matrix trick: ML Kit
   * segments the pixels it is given, so a sideways photo would be segmented
   * sideways. The oriented copy is why the sample size is chosen against
   * [MEMORY_CEILING_BYTES] with the copy counted in.
   */
  private fun decodeWorkingBitmap(context: Context, source: String): WorkingBitmap {
    val uri = readableUri(source)
    val orientation = try {
      openStream(context, uri).use { input ->
        ExifInterface(input).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL
        )
      }
    } catch (error: Throwable) {
      throw SubjectCutoutException(
        SubjectCutoutFailure.FAILED,
        "Could not read the image orientation for $source",
        error
      )
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream(context, uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw SubjectCutoutException(
        SubjectCutoutFailure.FAILED,
        "Could not read the image dimensions for $source"
      )
    }

    var sampleSize = 1
    while (true) {
      val width = max(1, bounds.outWidth / sampleSize)
      val height = max(1, bounds.outHeight / sampleSize)
      val withinEdge = max(width, height) <= MAX_WORKING_EDGE
      val withinCeiling = estimatedPeak(width, height) <= MEMORY_CEILING_BYTES
      if (withinEdge && withinCeiling) break
      if (width <= 1 && height <= 1) {
        throw SubjectCutoutException(
          SubjectCutoutFailure.FAILED,
          "Could not fit $source into the ${MEMORY_CEILING_BYTES / (1024 * 1024)} MB cutout budget"
        )
      }
      sampleSize *= 2
    }

    val decoded = openStream(context, uri).use { input ->
      BitmapFactory.decodeStream(
        input,
        null,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      )
    } ?: throw SubjectCutoutException(
      SubjectCutoutFailure.FAILED,
      "Could not decode $source"
    )
    val oriented = try {
      MemeTextDetector.orientBitmapForExif(decoded, orientation)
    } catch (error: Throwable) {
      decoded.recycle()
      throw SubjectCutoutException(
        SubjectCutoutFailure.FAILED,
        "Could not orient $source",
        error
      )
    }
    if (oriented !== decoded) decoded.recycle()
    val swapsAxes = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
      orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_270
    return WorkingBitmap(
      bitmap = oriented,
      sourceWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth,
      sourceHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight,
      sampleSize = sampleSize,
      estimatedPeakBytes = estimatedPeak(oriented.width, oriented.height)
    )
  }

  /**
   * Transient bytes one request needs at its peak: the oriented working bitmap,
   * the confidence mask covering it, and one cutout of the same size.
   */
  fun estimatedPeak(width: Int, height: Int): Long =
    max(1L, width.toLong()) * max(1L, height.toLong()) * BYTES_PER_WORKING_PIXEL

  // --- plumbing -------------------------------------------------------------

  private fun throwIfCancelled(cancelled: AtomicBoolean) {
    if (cancelled.get()) {
      throw SubjectCutoutException(SubjectCutoutFailure.CANCELLED, "Cutout cancelled")
    }
  }

  /**
   * Await a Play services task while staying responsive to cancellation.
   *
   * `Tasks.await` cannot be interrupted from another thread, and neither ML Kit
   * inference nor a module install exposes a cancel — so the wait is sliced and
   * the caller's cancel flag is checked between slices. Abandoning the wait is
   * the honest amount of cancellation available: the segmenter is closed on the
   * way out, which is what actually releases the work.
   */
  private fun <T> awaitTask(
    task: Task<T>,
    timeoutMs: Long,
    cancelled: () -> Boolean
  ): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!task.isComplete) {
      if (cancelled()) {
        throw SubjectCutoutException(SubjectCutoutFailure.CANCELLED, "Cutout cancelled")
      }
      if (System.currentTimeMillis() > deadline) {
        throw SubjectCutoutException(
          SubjectCutoutFailure.FAILED,
          "The cutout model did not respond within ${timeoutMs / 1000} s"
        )
      }
      Thread.sleep(WAIT_SLICE_MS)
    }
    val error = task.exception
    if (error != null) throw classify(error, "Subject segmentation failed")
    if (!task.isSuccessful) {
      throw SubjectCutoutException(SubjectCutoutFailure.CANCELLED, "Cutout cancelled")
    }
    @Suppress("UNCHECKED_CAST")
    return task.result as T
  }

  /**
   * Map a Play services / ML Kit failure onto the three remedies.
   *
   * Codes, not message matching: ML Kit's strings are not API, and getting this
   * wrong means telling an offline user their device is unsupported.
   */
  private fun classify(error: Throwable, context: String): SubjectCutoutException {
    if (error is SubjectCutoutException) return error
    val detail = error.message ?: error.javaClass.simpleName
    if (error is MlKitException) {
      val failure = when (error.errorCode) {
        MlKitException.NETWORK_ISSUE -> SubjectCutoutFailure.OFFLINE
        MlKitException.UNAVAILABLE,
        MlKitException.UNIMPLEMENTED,
        MlKitException.PERMISSION_DENIED -> SubjectCutoutFailure.MODULE_UNAVAILABLE
        MlKitException.CANCELLED -> SubjectCutoutFailure.CANCELLED
        else -> SubjectCutoutFailure.FAILED
      }
      return SubjectCutoutException(failure, "$context: $detail", error)
    }
    if (error is ApiException) {
      val failure = when (error.statusCode) {
        CommonStatusCodes.NETWORK_ERROR ->
          SubjectCutoutFailure.OFFLINE
        CommonStatusCodes.API_NOT_CONNECTED,
        CommonStatusCodes.DEVELOPER_ERROR ->
          SubjectCutoutFailure.MODULE_UNAVAILABLE
        CommonStatusCodes.CANCELED ->
          SubjectCutoutFailure.CANCELLED
        else -> SubjectCutoutFailure.FAILED
      }
      return SubjectCutoutException(failure, "$context: $detail", error)
    }
    if (error is NoClassDefFoundError || error is UnsatisfiedLinkError) {
      return SubjectCutoutException(
        SubjectCutoutFailure.MODULE_UNAVAILABLE,
        "$context: $detail",
        error
      )
    }
    return SubjectCutoutException(SubjectCutoutFailure.FAILED, "$context: $detail", error)
  }

  private fun readableUri(source: String): Uri =
    if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))

  private fun openStream(context: Context, uri: Uri): InputStream {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val path = uri.path ?: throw SubjectCutoutException(
        SubjectCutoutFailure.FAILED,
        "Cutout source $uri has no path"
      )
      return FileInputStream(File(path))
    }
    return context.contentResolver.openInputStream(uri) ?: throw SubjectCutoutException(
      SubjectCutoutFailure.FAILED,
      "Could not open the cutout source $uri"
    )
  }
}
