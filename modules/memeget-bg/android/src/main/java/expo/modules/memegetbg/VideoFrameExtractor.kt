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
//
// Pass seconds < 0 for AUTO mode: candidate positions proportional to the
// clip's duration are tried in turn and near-black frames are rejected (mean
// luma read straight off the decoded Y plane — free), because a fixed t=1s
// poster landed on the fade-from-black intro of half the internet's videos.
// The brightest decoded frame is kept as the fallback when everything is dark
// (a genuinely dark clip should still get its poster).
object VideoFrameExtractor {
  private const val TIMEOUT_US = 10_000L
  private const val DEADLINE_NS = 15_000_000_000L // hard cap per file
  private const val MIN_LUMA = 20.0 // 0..255; below this a frame reads as black

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
      val trackMimes = ArrayList<String>()
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        trackMimes.add(mime)
        if (trackIndex < 0 && mime.startsWith("video/")) {
          trackIndex = i
          format = f
        }
      }
      // Report WHAT the container actually held so a genuinely trackless/
      // non-media download (a truncated or error-page ".mp4") is told apart
      // from an audio-only file or an unexpected codec — "no video track"
      // alone can't distinguish a corrupt download from an extractor gap.
      if (trackIndex < 0 || format == null) {
        val summary = if (trackMimes.isEmpty()) "0 tracks" else trackMimes.joinToString(",")
        throw IllegalStateException("no video track ($summary)")
      }
      extractor.selectTrack(trackIndex)
      val mime = format.getString(MediaFormat.KEY_MIME)!!

      val durationUs =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
        else -1L
      val candidatesUs: List<Long> =
        if (seconds >= 0) {
          listOf((seconds * 1_000_000).toLong())
        } else if (durationUs > 0) {
          // A quarter in is usually past the intro; then midway, then near the
          // start for clips whose interesting frame IS the start.
          listOf(durationUs / 4, durationUs / 2, minOf(1_000_000L, durationUs / 10), 0L).distinct()
        } else {
          listOf(1_000_000L, 0L)
        }

      var best: Pair<String, Double>? = null // brightest rejected frame
      var lastErr: String? = null
      for (tUs in candidatesUs) {
        try {
          val (path, luma) = decodeFrameAt(ctx, extractor, format, mime, tUs)
          if (seconds >= 0 || luma >= MIN_LUMA) {
            best?.let { File(it.first.removePrefix("file://")).delete() }
            return path
          }
          if (best == null || luma > best!!.second) {
            best?.let { File(it.first.removePrefix("file://")).delete() }
            best = path to luma
          } else {
            File(path.removePrefix("file://")).delete()
          }
        } catch (e: Exception) {
          lastErr = e.message
        }
      }
      best?.let { return it.first } // everything was dark — dark poster it is
      throw IllegalStateException(lastErr ?: "$mime: no decodable frame")
    } finally {
      extractor.release()
    }
  }

  // Seek to the sync frame at/before tUs and decode a single frame with a
  // fresh codec (decoders don't rewind cleanly). Returns the written JPEG's
  // file:// path plus the frame's mean luma.
  private fun decodeFrameAt(
    ctx: Context,
    extractor: MediaExtractor,
    format: MediaFormat,
    mime: String,
    tUs: Long
  ): Pair<String, Double> {
    extractor.seekTo(tUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

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
              val luma = meanLuma(image)
              val path = writeJpeg(ctx, image)
              codec.releaseOutputBuffer(outIdx, false)
              return (path ?: throw IllegalStateException("$mime frame->jpeg failed")) to luma
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
  }

  // Mean brightness of the Y plane, sampled on an 8px grid — effectively free
  // next to the decode, and exactly the "is this frame black?" signal needed.
  private fun meanLuma(image: Image): Double {
    val y = image.planes[0]
    val buf = y.buffer
    val w = image.width
    val h = image.height
    var sum = 0L
    var n = 0
    var row = 0
    while (row < h) {
      var col = 0
      val base = row * y.rowStride
      while (col < w) {
        sum += (buf.get(base + col * y.pixelStride).toInt() and 0xFF)
        n++
        col += 8
      }
      row += 8
    }
    return if (n == 0) 0.0 else sum.toDouble() / n
  }

  // Flexible YUV_420_888 → NV21 → JPEG. The meme_work_ prefix keeps a leaked
  // file (JS crash between extract and cleanup) inside the launch sweep's net.
  private fun writeJpeg(ctx: Context, image: Image): String? {
    return try {
      val nv21 = yuv420ToNv21(image)
      val out = File(ctx.cacheDir, "meme_work_frame_${System.currentTimeMillis()}_${image.hashCode()}.jpg")
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
