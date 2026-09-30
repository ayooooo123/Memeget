package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * The reason a cutout attempt produced nothing, as a code the JS side maps to a
 * remedy. These are NOT interchangeable: offline means "connect and retry" (the
 * model downloads on first use), unavailable means "this build cannot do it at
 * all", and failed means "that image did not work". The JS orchestrator raises
 * OFFLINE/MODULE_UNAVAILABLE itself; the native steps below raise the rest.
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
 * One materialized cutout: source pixels with the subject's alpha, cropped to
 * the subject's own bounds and written to disk as a PNG.
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
  /** Fraction of the oriented frame the subject's mask actually covers. */
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

/** The working image a request segments, handed to the JS model by file uri. */
internal data class PreparedSubjectImage(
  val requestId: String,
  val workingUri: String,
  val sourceWidth: Int,
  val sourceHeight: Int,
  val workingWidth: Int,
  val workingHeight: Int,
  val sampleSize: Int,
  val estimatedPeakBytes: Long,
  val ceilingBytes: Long
) {
  fun toMap(): Map<String, Any> = mapOf(
    "requestId" to requestId,
    "workingUri" to workingUri,
    "sourceWidth" to sourceWidth,
    "sourceHeight" to sourceHeight,
    "workingWidth" to workingWidth,
    "workingHeight" to workingHeight,
    "sampleSize" to sampleSize,
    "estimatedPeakBytes" to estimatedPeakBytes,
    "ceilingBytes" to ceilingBytes
  )
}

/**
 * The pixel half of still-image subject cutouts. Segmentation itself runs in JS
 * on react-native-executorch (FastSAM) — no Google Play services — and a request
 * is two native steps around it:
 *
 * 1. [prepare] decodes the source upright at a size the memory ceiling allows,
 *    keeps that working bitmap, and writes a JPEG copy the model can read.
 * 2. [writeCutouts] takes the subject masks JS chose (bit-packed, one per
 *    subject, each cropped to its box) and materializes one PNG per subject plus
 *    a combined one from the kept working bitmap — lossless, alpha feathered by
 *    one pixel so edges aren't stair-stepped.
 *
 * Memory is why the working size is derived FROM [MEMORY_CEILING_BYTES] rather
 * than from the source: a 32 MP photo at full size is the allocation that
 * OOM'd this app before.
 */
internal object MemeStillSubjectSegmenter {
  /** Cache subdirectory holding one directory per segmentation request. */
  const val WORK_DIR = "meme_work_cutout"

  /**
   * Peak transient bytes one cutout request may allocate natively. 96 MB sits
   * under the renderer's own working budget so a cutout taken while an export is
   * warm cannot push the process over.
   */
  const val MEMORY_CEILING_BYTES = 96L * 1024L * 1024L

  /**
   * Longest working edge. The model resizes to its fixed input anyway, so mask
   * detail stops improving well before this; masks come back at working size.
   */
  const val MAX_WORKING_EDGE = 2048

  /** Below this short edge the caller is told the cutout may be rough. */
  const val RECOMMENDED_MIN_EDGE = 512

  /** Per-request subject cap; the studio cannot show more than a few anyway. */
  const val MAX_SUBJECTS = 8

  /**
   * Below this the "subject" is a handful of speckled pixels. Treated as "no
   * subject found", which is not an error.
   */
  private const val MIN_SUBJECT_COVERAGE = 0.001

  /**
   * Decoded ARGB working bitmap + one cutout ARGB + the combined byte mask and
   * model copy, per working pixel, rounded up.
   */
  private const val BYTES_PER_WORKING_PIXEL = 4L + 4L + 4L

  private const val WORKING_JPEG_QUALITY = 95

  /** A finished request's files outlive the studio session by at most this. */
  private const val STALE_REQUEST_MS = 60L * 60L * 1000L

  private class ActiveRequest(val working: WorkingBitmap, val directory: File) {
    val cancelled = AtomicBoolean(false)
  }

  private val active = ConcurrentHashMap<String, ActiveRequest>()

  fun requestCancel(requestId: String) {
    active[requestId]?.cancelled?.set(true)
  }

  /**
   * Step 1. Blocking on purpose: the bridge calls it from an AsyncFunction, so
   * the JS thread is never held.
   */
  fun prepare(context: Context, source: String, requestId: String): PreparedSubjectImage {
    require(requestId.isNotBlank()) { "A cutout request needs an id" }
    require(requestId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
      "Cutout request id must be a filesystem-safe token, got \"$requestId\""
    }
    val directory = File(File(context.cacheDir, WORK_DIR), requestId)
    var working: WorkingBitmap? = null
    try {
      sweepStaleRequests(context, requestId)
      if (!directory.mkdirs() && !directory.isDirectory) {
        throw SubjectCutoutException(
          SubjectCutoutFailure.FAILED,
          "Could not create a working directory for cutout $requestId"
        )
      }
      val decoded = decodeWorkingBitmap(context, source)
      working = decoded
      val modelCopy = File(directory, "$requestId-working.jpg")
      FileOutputStream(modelCopy).use { out ->
        if (!decoded.bitmap.compress(Bitmap.CompressFormat.JPEG, WORKING_JPEG_QUALITY, out)) {
          throw SubjectCutoutException(SubjectCutoutFailure.FAILED, "Could not encode the cutout working image")
        }
      }
      active.put(requestId, ActiveRequest(decoded, directory))?.working?.recycle()
      return PreparedSubjectImage(
        requestId = requestId,
        workingUri = Uri.fromFile(modelCopy).toString(),
        sourceWidth = decoded.sourceWidth,
        sourceHeight = decoded.sourceHeight,
        workingWidth = decoded.bitmap.width,
        workingHeight = decoded.bitmap.height,
        sampleSize = decoded.sampleSize,
        estimatedPeakBytes = decoded.estimatedPeakBytes,
        ceilingBytes = MEMORY_CEILING_BYTES
      )
    } catch (error: Throwable) {
      working?.recycle()
      directory.deleteRecursively()
      throw error
    }
  }

  /**
   * Step 2. [boxes] is `x, y, width, height` per subject in working pixels (the
   * mask's top-left and size); [masks] is one base64 string per subject holding
   * `width * height` bits, row-major, most significant bit first. Order is the
   * caller's: it becomes each subject's index.
   */
  fun writeCutouts(
    requestId: String,
    boxes: List<Int>,
    masks: List<String>,
    droppedSubjects: Int
  ): SubjectCutoutResult {
    val request = active[requestId] ?: throw SubjectCutoutException(
      SubjectCutoutFailure.FAILED,
      "Cutout request $requestId was not prepared, or was already released"
    )
    try {
      if (boxes.size != masks.size * 4) {
        throw SubjectCutoutException(SubjectCutoutFailure.FAILED, "Each subject mask needs one box")
      }
      val frame = request.working.bitmap
      val frameWidth = frame.width
      val frameHeight = frame.height
      val framePixels = frameWidth.toLong() * frameHeight.toLong()
      val combined = ByteArray(frameWidth * frameHeight)
      val subjects = ArrayList<SubjectCutout>()
      for (index in 0 until min(masks.size, MAX_SUBJECTS)) {
        throwIfCancelled(request.cancelled)
        val originX = boxes[index * 4]
        val originY = boxes[index * 4 + 1]
        val width = boxes[index * 4 + 2]
        val height = boxes[index * 4 + 3]
        if (width <= 0 || height <= 0) continue
        val bits = Base64.decode(masks[index], Base64.DEFAULT)
        if (bits.size.toLong() * 8L < width.toLong() * height.toLong()) {
          throw SubjectCutoutException(SubjectCutoutFailure.FAILED, "Subject mask $index is truncated")
        }
        val isSet = { mx: Int, my: Int ->
          val i = my * width + mx
          (bits[i ushr 3].toInt() and (0x80 ushr (i and 7))) != 0
        }
        for (my in 0 until height) {
          val fy = originY + my
          if (fy < 0 || fy >= frameHeight) continue
          for (mx in 0 until width) {
            val fx = originX + mx
            if (fx < 0 || fx >= frameWidth) continue
            if (isSet(mx, my)) combined[fy * frameWidth + fx] = 1
          }
        }
        materialize(
          directory = request.directory,
          id = "$requestId-subject-$index",
          subjectIndex = index,
          frame = frame,
          originX = originX,
          originY = originY,
          width = width,
          height = height,
          framePixels = framePixels,
          isSet = isSet
        )?.let(subjects::add)
      }
      throwIfCancelled(request.cancelled)
      val combinedCutout = if (subjects.isEmpty()) null else materialize(
        directory = request.directory,
        id = "$requestId-combined",
        subjectIndex = null,
        frame = frame,
        originX = 0,
        originY = 0,
        width = frameWidth,
        height = frameHeight,
        framePixels = framePixels
      ) { mx, my -> combined[my * frameWidth + mx].toInt() != 0 }
      File(request.directory, "$requestId-working.jpg").delete()
      return SubjectCutoutResult(
        requestId = requestId,
        sourceWidth = request.working.sourceWidth,
        sourceHeight = request.working.sourceHeight,
        workingWidth = frameWidth,
        workingHeight = frameHeight,
        sampleSize = request.working.sampleSize,
        estimatedPeakBytes = request.working.estimatedPeakBytes,
        ceilingBytes = MEMORY_CEILING_BYTES,
        directory = Uri.fromFile(request.directory).toString(),
        combined = combinedCutout,
        subjects = subjects,
        droppedSubjects = droppedSubjects + max(0, masks.size - MAX_SUBJECTS)
      )
    } catch (error: Throwable) {
      request.directory.deleteRecursively()
      throw error
    } finally {
      active.remove(requestId, request)
      request.working.recycle()
    }
  }

  /** Delete the files of one request (and drop it if still in flight). */
  fun release(context: Context, requestId: String): Boolean {
    if (requestId.isBlank() || requestId.contains('/') || requestId.contains("..")) return false
    active.remove(requestId)?.working?.recycle()
    val directory = File(File(context.cacheDir, WORK_DIR), requestId)
    if (!directory.exists()) return false
    return directory.deleteRecursively()
  }

  /**
   * Drop request directories nothing can be using any more. Cutouts are cache
   * files a crash can orphan, and an orphaned 16 MP PNG is invisible until the
   * cache is full, so every new request sweeps.
   */
  fun sweepStaleRequests(context: Context, keepRequestId: String? = null): Int {
    val root = File(context.cacheDir, WORK_DIR)
    val entries = root.listFiles() ?: return 0
    val cutoff = System.currentTimeMillis() - STALE_REQUEST_MS
    var removed = 0
    for (entry in entries) {
      if (entry.name == keepRequestId) continue
      if (active.containsKey(entry.name)) continue
      if (entry.lastModified() > cutoff) continue
      if (entry.deleteRecursively()) removed += 1
    }
    return removed
  }

  // --- cutout materialization -----------------------------------------------

  /**
   * One cutout PNG from a binary mask whose (0,0) sits at ([originX],[originY])
   * in the working frame. Alpha is the share of set pixels in each pixel's 3x3
   * neighbourhood: 255 inside, a one-pixel ramp at the edge. Null when the mask
   * covers too little of the frame to be a subject.
   */
  private inline fun materialize(
    directory: File,
    id: String,
    subjectIndex: Int?,
    frame: Bitmap,
    originX: Int,
    originY: Int,
    width: Int,
    height: Int,
    framePixels: Long,
    isSet: (Int, Int) -> Boolean
  ): SubjectCutout? {
    val frameWidth = frame.width
    val frameHeight = frame.height
    // Tight bounds and coverage, in mask coordinates, of the part inside the frame.
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var covered = 0L
    for (my in 0 until height) {
      val fy = originY + my
      if (fy < 0 || fy >= frameHeight) continue
      for (mx in 0 until width) {
        val fx = originX + mx
        if (fx < 0 || fx >= frameWidth || !isSet(mx, my)) continue
        covered += 1
        if (mx < left) left = mx
        if (mx > right) right = mx
        if (my < top) top = my
        if (my > bottom) bottom = my
      }
    }
    if (right < left || bottom < top) return null
    val coverage = covered.toDouble() / framePixels.toDouble()
    if (coverage < MIN_SUBJECT_COVERAGE) return null

    // Region in frame pixels: the tight box grown by the one-pixel feather.
    val regionLeft = max(0, originX + left - 1)
    val regionTop = max(0, originY + top - 1)
    val regionRight = min(frameWidth, originX + right + 2)
    val regionBottom = min(frameHeight, originY + bottom + 2)
    val regionWidth = regionRight - regionLeft
    val regionHeight = regionBottom - regionTop
    val pixels = IntArray(regionWidth * regionHeight)
    val sourceRow = IntArray(regionWidth)
    for (ry in 0 until regionHeight) {
      val fy = regionTop + ry
      frame.getPixels(sourceRow, 0, regionWidth, regionLeft, fy, regionWidth, 1)
      for (rx in 0 until regionWidth) {
        val fx = regionLeft + rx
        var set = 0
        for (dy in -1..1) {
          val my = fy + dy - originY
          if (my < 0 || my >= height || fy + dy < 0 || fy + dy >= frameHeight) continue
          for (dx in -1..1) {
            val mx = fx + dx - originX
            if (mx < 0 || mx >= width || fx + dx < 0 || fx + dx >= frameWidth) continue
            if (isSet(mx, my)) set += 1
          }
        }
        if (set == 0) continue
        val alpha = (set * 255 + 4) / 9
        pixels[ry * regionWidth + rx] = (sourceRow[rx] and 0x00FFFFFF) or (alpha shl 24)
      }
    }
    val bounds = MemeTextDetector.normalizePixelRect(
      regionLeft,
      regionTop,
      regionRight,
      regionBottom,
      frameWidth,
      frameHeight
    ) ?: return null
    val cutout = Bitmap.createBitmap(pixels, regionWidth, regionHeight, Bitmap.Config.ARGB_8888)
    val file = File(directory, "$id.png")
    try {
      FileOutputStream(file).use { out ->
        if (!cutout.compress(Bitmap.CompressFormat.PNG, 100, out)) {
          throw SubjectCutoutException(SubjectCutoutFailure.FAILED, "Could not encode the cutout for $id")
        }
      }
    } finally {
      cutout.recycle()
    }
    return SubjectCutout(
      id = id,
      subjectIndex = subjectIndex,
      cutoutUri = Uri.fromFile(file).toString(),
      bounds = bounds,
      widthPx = regionWidth,
      heightPx = regionHeight,
      coverage = coverage,
      bytes = file.length()
    )
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
   * Decode [source] upright, at a size the memory ceiling allows. EXIF is
   * applied for real: the model segments the pixels it is given, so a sideways
   * photo would be segmented sideways.
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

  /** Transient bytes one request needs at its peak (see [BYTES_PER_WORKING_PIXEL]). */
  fun estimatedPeak(width: Int, height: Int): Long =
    max(1L, width.toLong()) * max(1L, height.toLong()) * BYTES_PER_WORKING_PIXEL

  // --- plumbing -------------------------------------------------------------

  private fun throwIfCancelled(cancelled: AtomicBoolean) {
    if (cancelled.get()) {
      throw SubjectCutoutException(SubjectCutoutFailure.CANCELLED, "Cutout cancelled")
    }
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
