package expo.modules.memegetbg

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal class SequentialVideoFrameDecoder(
  source: File,
  private val stallTimeoutMs: Long = 60_000L
) : Closeable {
  data class Frame(
    val targetTimestampMs: Long,
    val presentationTimeUs: Long,
    val decodeIntervalMs: Double,
    val bitmap: Bitmap
  )

  companion object {
    private const val BUFFER_TIMEOUT_US = 10_000L
  }

  private val extractor = MediaExtractor()
  private var codec: MediaCodec? = null
  private var codecStarted = false
  private var decodeStarted = false

  var isClosed: Boolean = false
    private set
  var extractorSeekCount: Int = 0
    private set
  var inputSamplesAdvanced: Int = 0
    private set
  var decodedSourceFrames: Int = 0
    private set
  var width: Int = 0
    private set
  var height: Int = 0
    private set
  var durationMs: Long = 0L
    private set
  var mime: String = ""
    private set
  var decoderName: String = ""
    private set

  init {
    require(stallTimeoutMs > 0L)
    try {
      extractor.setDataSource(source.absolutePath)
      var videoTrack = -1
      var videoFormat: MediaFormat? = null
      for (index in 0 until extractor.trackCount) {
        val candidate = extractor.getTrackFormat(index)
        val candidateMime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
        if (candidateMime.startsWith("video/")) {
          videoTrack = index
          videoFormat = candidate
          break
        }
      }
      check(videoTrack >= 0 && videoFormat != null) { "No video track in ${source.name}" }
      extractor.selectTrack(videoTrack)
      val format = checkNotNull(videoFormat)
      mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
      width = format.getInteger(MediaFormat.KEY_WIDTH)
      height = format.getInteger(MediaFormat.KEY_HEIGHT)
      durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
        format.getLong(MediaFormat.KEY_DURATION) / 1000L
      } else {
        0L
      }
      format.setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
      )
      codec = MediaCodec.createDecoderByType(mime).also { decoder ->
        decoderName = decoder.name
        decoder.configure(format, null, null, 0)
        decoder.start()
        codecStarted = true
      }
    } catch (error: Throwable) {
      close()
      throw error
    }
  }

  fun decodeFrames(
    targetTimestampsMs: List<Long>,
    targetWidth: Int,
    targetHeight: Int,
    shouldCancel: () -> Boolean,
    onFrame: (Frame) -> Unit
  ) {
    check(!isClosed) { "Decoder is closed" }
    check(!decodeStarted) { "Sequential decoder is single-use" }
    require(targetTimestampsMs.isNotEmpty())
    require(targetTimestampsMs.zipWithNext().all { (first, second) -> second > first })
    require(targetWidth > 0 && targetHeight > 0)
    decodeStarted = true

    val activeCodec = checkNotNull(codec)
    val info = MediaCodec.BufferInfo()
    var inputEnded = false
    var outputEnded = false
    var targetIndex = 0
    var intervalStartNs = SystemClock.elapsedRealtimeNanos()
    var lastProgressMs = SystemClock.elapsedRealtime()

    while (targetIndex < targetTimestampsMs.size && !outputEnded && !shouldCancel()) {
      check(SystemClock.elapsedRealtime() - lastProgressMs <= stallTimeoutMs) {
        "Sequential $mime decode stalled for more than ${stallTimeoutMs}ms"
      }
      if (!inputEnded) {
        val inputIndex = activeCodec.dequeueInputBuffer(BUFFER_TIMEOUT_US)
        if (inputIndex >= 0) {
          val inputBuffer = checkNotNull(activeCodec.getInputBuffer(inputIndex))
          inputBuffer.clear()
          val sampleSize = extractor.readSampleData(inputBuffer, 0)
          if (sampleSize < 0) {
            activeCodec.queueInputBuffer(
              inputIndex,
              0,
              0,
              0L,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            inputEnded = true
          } else {
            val sampleTimeUs = extractor.sampleTime
            activeCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
            extractor.advance()
            inputSamplesAdvanced++
          }
          lastProgressMs = SystemClock.elapsedRealtime()
        }
      }

      val outputIndex = activeCodec.dequeueOutputBuffer(info, BUFFER_TIMEOUT_US)
      when {
        outputIndex >= 0 -> {
          decodedSourceFrames++
          var image: Image? = null
          try {
            if (info.presentationTimeUs >= targetTimestampsMs[targetIndex] * 1000L) {
              image = activeCodec.getOutputImage(outputIndex)
              if (image != null) {
                while (
                  targetIndex < targetTimestampsMs.size &&
                  info.presentationTimeUs >= targetTimestampsMs[targetIndex] * 1000L
                ) {
                  val bitmap = imageToScaledBitmap(image, targetWidth, targetHeight)
                  val decodeIntervalMs =
                    (SystemClock.elapsedRealtimeNanos() - intervalStartNs) / 1_000_000.0
                  try {
                    onFrame(
                      Frame(
                        targetTimestampMs = targetTimestampsMs[targetIndex],
                        presentationTimeUs = info.presentationTimeUs,
                        decodeIntervalMs = decodeIntervalMs,
                        bitmap = bitmap
                      )
                    )
                  } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                  }
                  targetIndex++
                  intervalStartNs = SystemClock.elapsedRealtimeNanos()
                }
              }
            }
          } finally {
            image?.close()
            activeCodec.releaseOutputBuffer(outputIndex, false)
          }
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
          lastProgressMs = SystemClock.elapsedRealtime()
        }
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          lastProgressMs = SystemClock.elapsedRealtime()
        }
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputEnded -> Unit
      }
    }

    if (!shouldCancel()) {
      check(targetIndex == targetTimestampsMs.size) {
        "Sequential decoder emitted $targetIndex/${targetTimestampsMs.size} target frames"
      }
    }
  }

  override fun close() {
    if (isClosed) return
    isClosed = true
    val activeCodec = codec
    codec = null
    if (activeCodec != null) {
      if (codecStarted) {
        runCatching { activeCodec.stop() }
        codecStarted = false
      }
      runCatching { activeCodec.release() }
    }
    runCatching { extractor.release() }
  }

  private fun imageToScaledBitmap(image: Image, targetWidth: Int, targetHeight: Int): Bitmap {
    check(image.format == android.graphics.ImageFormat.YUV_420_888) {
      "Expected YUV_420_888 decoder output, got ${image.format}"
    }
    val crop = image.cropRect
    val sourceWidth = crop.width()
    val sourceHeight = crop.height()
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBase = yPlane.buffer.position()
    val uBase = uPlane.buffer.position()
    val vBase = vPlane.buffer.position()
    val pixels = IntArray(targetWidth * targetHeight)

    for (targetY in 0 until targetHeight) {
      val sourceY = crop.top + min(sourceHeight - 1, targetY * sourceHeight / targetHeight)
      val chromaY = sourceY / 2
      for (targetX in 0 until targetWidth) {
        val sourceX = crop.left + min(sourceWidth - 1, targetX * sourceWidth / targetWidth)
        val chromaX = sourceX / 2
        val y = planeByte(yPlane, yBase, sourceX, sourceY)
        val u = planeByte(uPlane, uBase, chromaX, chromaY)
        val v = planeByte(vPlane, vBase, chromaX, chromaY)
        val c = max(0, y - 16)
        val d = u - 128
        val e = v - 128
        val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        pixels[targetY * targetWidth + targetX] =
          0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
      }
    }
    return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
  }

  private fun planeByte(plane: Image.Plane, base: Int, x: Int, y: Int): Int {
    val index = base + y * plane.rowStride + x * plane.pixelStride
    check(index in 0 until plane.buffer.limit()) {
      "Decoder plane index $index outside ${plane.buffer.limit()}"
    }
    return plane.buffer.get(index).toInt() and 0xff
  }
}
