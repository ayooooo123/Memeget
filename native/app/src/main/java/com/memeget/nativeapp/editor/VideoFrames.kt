package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sequential frame access for logo removal: every frame's time, and a decoder pass that hands out each frame's
 * pixels turned upright by the stored rotation (the orientation the editor calls "source").
 */
internal object VideoFrames {
    /** Frames are matched for tracking with their long edge at this size. */
    const val ANALYSIS_LONG_EDGE = 640
    private const val CODEC_TIMEOUT_US = 10_000L

    class Info(
        val frameTimesUs: LongArray,
        val rotation: Int,
        /** Upright frame size in pixels. */
        val width: Int,
        val height: Int,
        val analysisWidth: Int,
        val analysisHeight: Int,
    )

    /** YUV-to-RGB constants for the decoder's output (BT.601 or BT.709, limited or full range). */
    class Colour(val bt709: Boolean, val fullRange: Boolean)

    fun probe(context: Context, uri: String): Info {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val times = ArrayList<Long>()
            while (true) {
                val time = extractor.sampleTime
                if (time < 0L) break
                times += time
                if (!extractor.advance()) break
            }
            if (times.isEmpty()) throw IOException("The video has no frames")
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            var w = format.getInteger(MediaFormat.KEY_WIDTH)
            var h = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val scale = min(1f, ANALYSIS_LONG_EDGE.toFloat() / max(w, h))
            return Info(
                frameTimesUs = times.toLongArray().also { it.sort() },
                rotation = rotation,
                width = w,
                height = h,
                analysisWidth = max(1, (w * scale).roundToInt()),
                analysisHeight = max(1, (h * scale).roundToInt())
            )
        } finally {
            extractor.release()
        }
    }

    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IOException("The file has no video track")

    /**
     * Decodes forward from the sync frame at or before [fromUs], handing each frame's presentation time and image
     * to [onFrame] in display order until it returns false or the video ends. The image is only valid during the
     * call. One sequential decoder pass: no frame is decoded twice.
     */
    suspend fun decode(
        context: Context,
        uri: String,
        fromUs: Long,
        onFrame: suspend (timeUs: Long, image: Image, colour: Colour) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var colour = colourOf(format)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    colour = colourOf(decoder.outputFormat, colour)
                    continue
                }
                if (outIndex < 0) continue
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var more = true
                if (info.size > 0) {
                    val image = decoder.getOutputImage(outIndex)
                        ?: throw IOException("This phone's decoder doesn't expose video frames")
                    try {
                        more = onFrame(info.presentationTimeUs, image, colour)
                    } finally {
                        image.close()
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                } else {
                    decoder.releaseOutputBuffer(outIndex, false)
                }
                if (ended || !more) return
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun colourOf(format: MediaFormat, fallback: Colour? = null): Colour {
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else null
        val range = if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) format.getInteger(MediaFormat.KEY_COLOR_RANGE) else null
        val bt709 = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> true
            MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> false
            // Untagged video: the GPU path the editor previews and exports through reads it as BT.601 at any size
            // (measured: a BT.709 reading drew the logo patch a few levels off its surroundings).
            else -> fallback?.bt709 ?: false
        }
        val full = when (range) {
            MediaFormat.COLOR_RANGE_FULL -> true
            MediaFormat.COLOR_RANGE_LIMITED -> false
            else -> fallback?.fullRange ?: false
        }
        return Colour(bt709, full)
    }

    /** The frame's Y plane, upright, sampled down to the analysis size. */
    fun luma(image: Image, video: Info): ByteArray {
        val plane = image.planes[0]
        val crop = image.cropRect
        val out = ByteArray(video.analysisWidth * video.analysisHeight)
        VideoCompletionNative.nativeLuma(
            plane.buffer, plane.rowStride, plane.pixelStride,
            crop.left, crop.top, crop.width(), crop.height(), video.rotation,
            video.analysisWidth, video.analysisHeight, out
        )
        return out
    }

    /**
     * RGB of [region] (upright pixels) sampled to [outW]×[outH], 3 bytes a pixel, converted with the decoder's
     * colour constants so it matches what playback shows.
     */
    fun rgb(image: Image, video: Info, colour: Colour, region: Rect, outW: Int, outH: Int): ByteArray {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val crop = image.cropRect
        val quarter = video.rotation == 90 || video.rotation == 270
        // The decoded frame can differ slightly from the probed size; scale the region to it.
        val sx = (if (quarter) crop.height() else crop.width()).toFloat() / video.width
        val sy = (if (quarter) crop.width() else crop.height()).toFloat() / video.height
        val out = ByteArray(outW * outH * 3)
        VideoCompletionNative.nativeYuvToRgb(
            y.buffer, u.buffer, v.buffer, y.rowStride, y.pixelStride, u.rowStride, u.pixelStride,
            crop.left, crop.top, crop.width(), crop.height(), video.rotation,
            region.left * sx, region.top * sy, region.width() * sx, region.height() * sy,
            outW, outH, colour.bt709, colour.fullRange, out
        )
        return out
    }
}
