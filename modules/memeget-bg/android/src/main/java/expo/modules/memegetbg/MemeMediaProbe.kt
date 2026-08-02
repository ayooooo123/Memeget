package expo.modules.memegetbg

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object MemeMediaProbe {
  data class Result(
    val kind: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationUs: Long?,
    val frameRate: Double?,
    val videoMime: String?,
    val audioMime: String?,
    val hasAudio: Boolean,
    val seekable: Boolean,
    val byteSize: Long?,
    val modifiedTimeMs: Long?,
    val stableId: String,
    val displayName: String?
  ) {
    fun toMap(): Map<String, Any?> =
      mapOf(
        "kind" to kind,
        "width" to width,
        "height" to height,
        "rotationDegrees" to rotationDegrees,
        "durationUs" to durationUs,
        "frameRate" to frameRate,
        "videoMime" to videoMime,
        "audioMime" to audioMime,
        "hasAudio" to hasAudio,
        "seekable" to seekable,
        "byteSize" to byteSize,
        "modifiedTimeMs" to modifiedTimeMs,
        "stableId" to stableId,
        "displayName" to displayName
      )
  }

  private data class SourceFacts(
    val seekable: Boolean,
    val byteSize: Long?,
    val modifiedTimeMs: Long?,
    val stableId: String,
    val displayName: String?
  )

  private data class RetrieverFacts(
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int?,
    val durationUs: Long?,
    val frameRate: Double?
  )

  fun probe(context: Context, source: String): Result {
    val uri = sourceUri(source)
    try {
      val facts = sourceFacts(context, uri)
      probeImage(context, uri, facts)?.let { return it }
      return probeVideo(context, uri, facts)
    } catch (error: Throwable) {
      if (error is IOException && error.message.orEmpty().startsWith("Could not probe media")) {
        throw error
      }
      val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
      throw IOException("Could not probe media '$source': $detail", error)
    }
  }

  private fun sourceUri(source: String): Uri {
    if (source.isBlank()) throw IOException("Could not probe media: source is empty")
    return if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))
  }

  private fun sourceFacts(context: Context, uri: Uri): SourceFacts {
    val normalizedIdentity =
      if (uri.scheme == "file") {
        val file = File(checkNotNull(uri.path) { "File URI has no path" }).canonicalFile
        if (!file.isFile) throw IOException("Source does not exist or is not a file")
        file.toURI().normalize().toString()
      } else {
        // Opening once up front makes missing/revoked content grants fail with a
        // readable probe error instead of a misleading "unsupported media".
        context.contentResolver.openFileDescriptor(uri, "r")?.use { }
          ?: throw IOException("Content provider returned no file descriptor")
        uri.normalizeScheme().toString()
      }
    return SourceFacts(
      seekable = isSeekable(context, uri),
      byteSize = readByteSize(context, uri),
      modifiedTimeMs = readModifiedTimeMs(context, uri),
      stableId = sha256(normalizedIdentity),
      displayName = readDisplayName(context, uri)
    )
  }

  private fun probeImage(context: Context, uri: Uri, facts: SourceFacts): Result? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val input = context.contentResolver.openInputStream(uri)
      ?: throw IOException("Could not open source stream")
    input.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return Result(
      kind = "image",
      width = options.outWidth,
      height = options.outHeight,
      rotationDegrees = readImageRotation(context, uri),
      durationUs = null,
      frameRate = null,
      videoMime = null,
      audioMime = null,
      hasAudio = false,
      seekable = facts.seekable,
      byteSize = facts.byteSize,
      modifiedTimeMs = facts.modifiedTimeMs,
      stableId = facts.stableId,
      displayName = facts.displayName
    )
  }

  private fun readImageRotation(context: Context, uri: Uri): Int {
    return try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        when (
          ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
          )
        ) {
          ExifInterface.ORIENTATION_ROTATE_90,
          ExifInterface.ORIENTATION_TRANSPOSE -> 90
          ExifInterface.ORIENTATION_ROTATE_180,
          ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
          ExifInterface.ORIENTATION_ROTATE_270,
          ExifInterface.ORIENTATION_TRANSVERSE -> 270
          else -> 0
        }
      } ?: 0
    } catch (_: IOException) {
      0
    }
  }

  private fun probeVideo(context: Context, uri: Uri, facts: SourceFacts): Result {
    val extractor = MediaExtractor()
    var videoMime: String? = null
    var audioMime: String? = null
    var width: Int? = null
    var height: Int? = null
    var rotation: Int? = null
    var durationUs: Long? = null
    var frameRate: Double? = null
    try {
      extractor.setDataSource(context, uri, null)
      for (trackIndex in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        val trackDuration = format.longOrNull(MediaFormat.KEY_DURATION)
        if (trackDuration != null && trackDuration > 0L) {
          durationUs = maxOf(durationUs ?: 0L, trackDuration)
        }
        when {
          mime.startsWith("video/") && videoMime == null -> {
            videoMime = mime
            width = format.intOrNull(MediaFormat.KEY_WIDTH)
            height = format.intOrNull(MediaFormat.KEY_HEIGHT)
            rotation = format.intOrNull(MediaFormat.KEY_ROTATION)
            frameRate = format.numberAsDouble(MediaFormat.KEY_FRAME_RATE)
          }
          mime.startsWith("audio/") && audioMime == null -> audioMime = mime
        }
      }
    } finally {
      extractor.release()
    }
    if (videoMime == null) throw IOException("No video track or decodable image was found")

    val retriever = readRetrieverFacts(context, uri)
    width = width?.takeIf { it > 0 } ?: retriever.width
    height = height?.takeIf { it > 0 } ?: retriever.height
    durationUs = durationUs?.takeIf { it > 0L } ?: retriever.durationUs
    rotation = rotation ?: retriever.rotationDegrees
    frameRate = frameRate?.takeIf { it > 0.0 && it.isFinite() } ?: retriever.frameRate
    if (width == null || width <= 0 || height == null || height <= 0) {
      throw IOException("Video dimensions are unavailable")
    }

    return Result(
      kind = "video",
      width = width,
      height = height,
      rotationDegrees = normalizeRotation(rotation ?: 0),
      durationUs = durationUs,
      frameRate = frameRate,
      videoMime = videoMime,
      audioMime = audioMime,
      hasAudio = audioMime != null,
      seekable = facts.seekable,
      byteSize = facts.byteSize,
      modifiedTimeMs = facts.modifiedTimeMs,
      stableId = facts.stableId,
      displayName = facts.displayName
    )
  }

  private fun readRetrieverFacts(context: Context, uri: Uri): RetrieverFacts {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      return RetrieverFacts(
        width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
        height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
        rotationDegrees = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
        durationUs =
          retriever.longMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { milliseconds ->
            Math.multiplyExact(milliseconds, 1_000L)
          },
        frameRate =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 && it.isFinite() }
      )
    } finally {
      retriever.release()
    }
  }

  private fun MediaMetadataRetriever.intMetadata(key: Int): Int? =
    extractMetadata(key)?.toIntOrNull()

  private fun MediaMetadataRetriever.longMetadata(key: Int): Long? =
    extractMetadata(key)?.toLongOrNull()

  private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

  private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

  private fun MediaFormat.numberAsDouble(key: String): Double? {
    if (!containsKey(key)) return null
    return runCatching { getFloat(key).toDouble() }.getOrNull()
      ?: runCatching { getInteger(key).toDouble() }.getOrNull()
  }

  private fun normalizeRotation(value: Int): Int {
    val normalized = ((value % 360) + 360) % 360
    return when (normalized) {
      in 45 until 135 -> 90
      in 135 until 225 -> 180
      in 225 until 315 -> 270
      else -> 0
    }
  }

  private fun isSeekable(context: Context, uri: Uri): Boolean {
    return try {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        true
      } ?: false
    } catch (_: Exception) {
      false
    }
  }

  private fun readByteSize(context: Context, uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.length()?.takeIf { it >= 0L }
    val queried = queryLong(context, uri, OpenableColumns.SIZE, multiplier = 1L)
    if (queried != null && queried >= 0L) return queried
    return try {
      context.contentResolver.openFileDescriptor(uri, "r")?.use(ParcelFileDescriptor::getStatSize)
        ?.takeIf { it >= 0L }
    } catch (_: Exception) {
      null
    }
  }

  private fun readModifiedTimeMs(context: Context, uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.lastModified()?.takeIf { it > 0L }
    return queryLong(
      context,
      uri,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      multiplier = 1L
    ) ?: queryLong(context, uri, MediaStore.MediaColumns.DATE_MODIFIED, multiplier = 1_000L)
  }

  private fun readDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path?.let(::File)?.name
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
      null
    }
  }

  private fun queryLong(
    context: Context,
    uri: Uri,
    column: String,
    multiplier: Long
  ): Long? {
    return try {
      context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
        val raw = cursor.getLong(0)
        if (raw <= 0L) null else Math.multiplyExact(raw, multiplier)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
