package expo.modules.memegetbg

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

// Decodes ONE frame of a video with MediaExtractor + MediaCodec — the same
// hardware decode path the player uses — and writes it as a JPEG in the app
// cache dir. Exists because both expo-image's grid thumbnails and
// expo-video-thumbnails go through MediaMetadataRetriever, which refuses some
// perfectly playable streams ("mp4 gif" style files rendered permanently blank
// tiles). If the player can show it, this can poster it.
object VideoFrameExtractor {
  private const val TIMEOUT_US = 10_000L
  private const val DEADLINE_NS = 15_000_000_000L // hard cap per file

  // Returns a file:// path to the JPEG. Throws with a SPECIFIC reason on any
  // failure (no video track, no decoder for the codec, decode timeout…) — the
  // JS poster ladder records these into the diagnostics list, which is the
  // only way to learn WHY a file resists postering on a device we can't
  // attach a debugger to.
  fun extract(ctx: Context, source: String, seconds: Double): String {
    val extractor = MediaExtractor()
    try {
      try {
        if (source.startsWith("content://")) {
          extractor.setDataSource(ctx, Uri.parse(source), null)
        } else {
          extractor.setDataSource(source.removePrefix("file://"))
        }
      } catch (e: Exception) {
        throw IllegalStateException("open failed: ${e.message}")
      }

      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/")) {
          trackIndex = i
          format = f
          break
        }
      }
      if (trackIndex < 0 || format == null) throw IllegalStateException("no video track")

      extractor.selectTrack(trackIndex)
      // Land on the sync frame at/before the requested time; a sub-second clip
      // simply clamps to its first (only) sync frame.
      extractor.seekTo((seconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

      val mime = format.getString(MediaFormat.KEY_MIME)!!
      val codec = try {
        MediaCodec.createDecoderByType(mime)
      } catch (e: Exception) {
        throw IllegalStateException("no decoder for $mime")
      }
      try {
        // ByteBuffer output in flexible YUV so the frame is CPU-readable.
        format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT,
          MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        try {
          codec.configure(format, null, null, 0)
          codec.start()
        } catch (e: Exception) {
          throw IllegalStateException("$mime configure failed: ${e.message}")
        }

        val deadline = System.nanoTime() + DEADLINE_NS
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (System.nanoTime() < deadline) {
          if (!inputDone) {
            val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
              val buf = codec.getInputBuffer(inIdx)!!
              val n = extractor.readSampleData(buf, 0)
              if (n < 0) {
                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                extractor.advance()
              }
            }
          }

          val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
          if (outIdx >= 0) {
            if (info.size > 0) {
              val image = codec.getOutputImage(outIdx)
              if (image != null) {
                val path = writeJpeg(ctx, image)
                codec.releaseOutputBuffer(outIdx, false)
                return path ?: throw IllegalStateException("$mime frame->jpeg failed")
              }
            }
            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            codec.releaseOutputBuffer(outIdx, false)
            if (eos) throw IllegalStateException("$mime stream ended with no decodable frame")
          }
        }
        throw IllegalStateException("$mime decode timeout")
      } finally {
        try {
          codec.stop()
        } catch (e: Exception) {
          // already errored/unstarted — release below is what matters
        }
        codec.release()
      }
    } finally {
      extractor.release()
    }
  }

  // Flexible YUV_420_888 → NV21 → JPEG. The meme_work_ prefix keeps a leaked
  // file (JS crash between extract and cleanup) inside the launch sweep's net.
  private fun writeJpeg(ctx: Context, image: Image): String? {
    return try {
      val nv21 = yuv420ToNv21(image)
      val out = File(ctx.cacheDir, "meme_work_frame_${System.currentTimeMillis()}.jpg")
      FileOutputStream(out).use { fos ->
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
          .compressToJpeg(Rect(0, 0, image.width, image.height), 85, fos)
      }
      "file://${out.absolutePath}"
    } catch (e: Exception) {
      null
    } finally {
      image.close()
    }
  }

  // Copies the three flexible-YUV planes into NV21 (Y then interleaved VU),
  // honoring each plane's row/pixel stride — decoders pad rows freely.
  private fun yuv420ToNv21(image: Image): ByteArray {
    val w = image.width
    val h = image.height
    val ySize = w * h
    val out = ByteArray(ySize + ySize / 2)

    val yPlane = image.planes[0]
    val yBuf = yPlane.buffer
    var pos = 0
    if (yPlane.pixelStride == 1 && yPlane.rowStride == w) {
      yBuf.position(0)
      yBuf.get(out, 0, ySize)
      pos = ySize
    } else {
      for (row in 0 until h) {
        yBuf.position(row * yPlane.rowStride)
        yBuf.get(out, pos, w)
        pos += w
      }
    }

    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val cw = w / 2
    val ch = h / 2
    for (row in 0 until ch) {
      for (col in 0 until cw) {
        out[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
        out[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
      }
    }
    return out
  }
}
