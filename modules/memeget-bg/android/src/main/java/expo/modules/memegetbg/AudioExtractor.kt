package expo.modules.memegetbg

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

// Decodes the first audio track of a video/audio file to mono 16 kHz float32
// PCM and writes it as a raw little-endian file in the app cache dir, ready for
// the JS side to hand to the on-device STT model (which wants a 16 kHz mono
// waveform). Exists because nothing in the Expo/JS layer can decode AAC/Opus
// audio — MediaExtractor + MediaCodec is the platform way.
object AudioExtractor {
  private const val TIMEOUT_US = 10_000L
  const val TARGET_RATE = 16_000

  // Growable float accumulator — avoids boxing and repeated array copies while
  // collecting a few million samples.
  private class FloatAcc {
    var data = FloatArray(1 shl 20)
    var size = 0
    fun add(v: Float) {
      if (size == data.size) data = data.copyOf(data.size * 2)
      data[size++] = v
    }
  }

  // Returns null when the source has no audio track. Throws on decode errors —
  // the module wrapper surfaces those as a rejected promise.
  fun extract(ctx: Context, source: String, maxSeconds: Double): Map<String, Any>? {
    val extractor = MediaExtractor()
    try {
      if (source.startsWith("content://")) {
        extractor.setDataSource(ctx, Uri.parse(source), null)
      } else {
        extractor.setDataSource(source.removePrefix("file://"))
      }

      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
          trackIndex = i
          format = f
          break
        }
      }
      if (trackIndex < 0 || format == null) return null

      extractor.selectTrack(trackIndex)
      val mime = format.getString(MediaFormat.KEY_MIME)!!
      val codec = MediaCodec.createDecoderByType(mime)
      val (mono, sampleRate) = try {
        codec.configure(format, null, null, 0)
        codec.start()
        decodeToMono(extractor, codec, format, maxSeconds)
      } finally {
        try {
          codec.stop()
        } catch (_: Exception) {}
        codec.release()
      }

      val resampled = resample(mono.data, mono.size, sampleRate, TARGET_RATE)
      val outFile = File.createTempFile("audio_pcm_", ".f32", ctx.cacheDir)
      writeFloatsLE(outFile, resampled)
      return mapOf(
        "path" to "file://${outFile.absolutePath}",
        "sampleRate" to TARGET_RATE,
        "samples" to resampled.size,
        "durationSec" to resampled.size.toDouble() / TARGET_RATE
      )
    } finally {
      extractor.release()
    }
  }

  // Standard MediaCodec pull loop: feed encoded samples, drain PCM buffers,
  // downmix each to mono on the fly. Returns the samples plus the (possibly
  // format-changed) source sample rate.
  private fun decodeToMono(
    extractor: MediaExtractor,
    codec: MediaCodec,
    inputFormat: MediaFormat,
    maxSeconds: Double
  ): Pair<FloatAcc, Int> {
    val mono = FloatAcc()
    var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var pcmFloat = false
    val maxInputUs = (maxSeconds * 1_000_000).toLong()
    val info = MediaCodec.BufferInfo()
    var sawInputEOS = false
    var sawOutputEOS = false

    while (!sawOutputEOS) {
      if (!sawInputEOS) {
        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
          val buf = codec.getInputBuffer(inIdx)!!
          val n = extractor.readSampleData(buf, 0)
          // Past the cap (or out of data): signal EOS so the codec flushes what
          // it has instead of decoding a long video to the end.
          if (n < 0 || extractor.sampleTime > maxInputUs) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sawInputEOS = true
          } else {
            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }

      val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
      when {
        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val f = codec.outputFormat
          sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        }
        outIdx >= 0 -> {
          val buf = codec.getOutputBuffer(outIdx)!!
          buf.position(info.offset)
          buf.limit(info.offset + info.size)
          val ch = if (channels > 0) channels else 1
          if (pcmFloat) {
            val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = fb.remaining() / ch
            for (i in 0 until frames) {
              var s = 0f
              for (c in 0 until ch) s += fb.get(i * ch + c)
              mono.add(s / ch)
            }
          } else {
            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = sb.remaining() / ch
            for (i in 0 until frames) {
              var s = 0f
              for (c in 0 until ch) s += sb.get(i * ch + c) / 32768f
              mono.add(s / ch)
            }
          }
          codec.releaseOutputBuffer(outIdx, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
          // Belt-and-braces output cap in case sample timestamps were unreliable.
          if (mono.size >= (maxSeconds * sampleRate).toLong()) sawOutputEOS = true
        }
      }
    }
    return Pair(mono, sampleRate)
  }

  // Linear-interpolation resample. Plenty for feeding a speech model at 16 kHz;
  // avoids shipping a DSP library.
  private fun resample(src: FloatArray, n: Int, srcRate: Int, dstRate: Int): FloatArray {
    if (n == 0) return FloatArray(0)
    if (srcRate == dstRate) return src.copyOf(n)
    val outLen = ((n.toLong() * dstRate) / srcRate).toInt()
    val out = FloatArray(outLen)
    val ratio = srcRate.toDouble() / dstRate
    for (i in 0 until outLen) {
      val pos = i * ratio
      val i0 = min(pos.toInt(), n - 1)
      val i1 = min(i0 + 1, n - 1)
      val frac = (pos - i0).toFloat()
      out[i] = src[i0] + (src[i1] - src[i0]) * frac
    }
    return out
  }

  private fun writeFloatsLE(file: File, data: FloatArray) {
    val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in data) bb.putFloat(v)
    FileOutputStream(file).use { it.write(bb.array()) }
  }
}
