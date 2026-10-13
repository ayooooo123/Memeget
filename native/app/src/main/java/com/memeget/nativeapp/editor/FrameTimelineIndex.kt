package com.memeget.nativeapp.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max

/**
 * High-performance frame timeline index constructed from actual MediaExtractor
 * video sample presentation timestamps (PTS).
 *
 * Provides exact microsecond-accurate frame navigation for CFR and VFR videos,
 * eliminating nominal frame rate approximations and fake 30fps fallbacks.
 */
internal class FrameTimelineIndex private constructor(
    val sourceUri: String,
    val ptsUs: LongArray,
    val durationUs: Long,
    val isVariableFrameRate: Boolean,
    val nominalFps: Double?,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int
) {
    val frameCount: Int get() = ptsUs.size

    /**
     * Binary search to find the frame index at or closest to [targetUs].
     * Returns 0-based index clamped to [0, frameCount - 1].
     */
    fun findFrameIndex(targetUs: Long): Int {
        if (ptsUs.isEmpty()) return 0
        val search = ptsUs.binarySearch(targetUs)
        if (search >= 0) return search
        val insertionPoint = -search - 1
        if (insertionPoint <= 0) return 0
        if (insertionPoint >= ptsUs.size) return ptsUs.size - 1

        val prevDiff = targetUs - ptsUs[insertionPoint - 1]
        val nextDiff = ptsUs[insertionPoint] - targetUs
        return if (nextDiff < prevDiff) insertionPoint else insertionPoint - 1
    }

    /**
     * Retrieves the exact PTS in microseconds for the given 0-based frame index.
     */
    fun getTimestampUs(index: Int): Long {
        if (ptsUs.isEmpty()) return 0L
        return ptsUs[index.coerceIn(0, ptsUs.size - 1)]
    }

    /**
     * Finds the exact timestamp for stepping one frame backwards from [currentUs].
     * Returns null if already at or before the first frame.
     */
    fun previousFrameTimeUs(currentUs: Long): Long? {
        if (ptsUs.isEmpty()) return null
        val search = ptsUs.binarySearch(currentUs)
        val idx = if (search >= 0) {
            search - 1
        } else {
            val insertionPoint = -search - 1
            insertionPoint - 1
        }
        return if (idx in ptsUs.indices) ptsUs[idx] else null
    }

    /**
     * Finds the exact timestamp for stepping one frame forwards from [currentUs].
     * Returns null if already at or after the last frame.
     */
    fun nextFrameTimeUs(currentUs: Long): Long? {
        if (ptsUs.isEmpty()) return null
        val search = ptsUs.binarySearch(currentUs)
        val idx = if (search >= 0) {
            search + 1
        } else {
            val insertionPoint = -search - 1
            insertionPoint
        }
        return if (idx in ptsUs.indices) ptsUs[idx] else null
    }

    companion object {
        const val MAX_SAMPLES = 1_000_000 // Bounded at 1M samples (~8 MiB)


        /**
         * Reads frame timestamps; the editor owns the resulting bounded index.
         * Runs on IO dispatcher with coroutine cancellation checks.
         * Throws actionable IOException if media is missing, corrupt, unsupported, or exceeds bounds.
         */
        suspend fun load(
            context: Context,
            sourceUri: String
        ): FrameTimelineIndex = withContext(Dispatchers.IO) {

            val extractor = MediaExtractor()
            try {
                if (sourceUri.startsWith("content://") || sourceUri.startsWith("android.resource://")) {
                    extractor.setDataSource(context, Uri.parse(sourceUri), null)
                } else if (sourceUri.startsWith("file://")) {
                    extractor.setDataSource(Uri.parse(sourceUri).path ?: sourceUri)
                } else {
                    extractor.setDataSource(sourceUri)
                }
            } catch (e: Exception) {
                extractor.release()
                throw IOException("Cannot read media data source: ${e.message}", e)
            }

            try {
                var videoTrackIndex = -1
                var videoFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                        videoFormat = format
                        break
                    }
                }

                if (videoTrackIndex < 0 || videoFormat == null) {
                    throw IOException("Unsupported media: no video track found in source")
                }

                extractor.selectTrack(videoTrackIndex)

                val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoFormat.getLong(MediaFormat.KEY_DURATION)
                } else 0L

                val nominalFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    } catch (_: Exception) {
                        try {
                            videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                        } catch (_: Exception) {
                            null
                        }
                    }
                } else null

                val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                } else 0

                val width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                    videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                } else 0

                val height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                } else 0

                var rawPts = LongArray(2048)
                var sampleCount = 0
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val pts = extractor.sampleTime
                    if (pts < 0L) break
                    if (sampleCount == MAX_SAMPLES) {
                        throw IOException("Video exceeds maximum supported frame sample limit ($MAX_SAMPLES samples)")
                    }
                    if (sampleCount == rawPts.size) {
                        rawPts = rawPts.copyOf((rawPts.size * 2).coerceAtMost(MAX_SAMPLES))
                    }
                    rawPts[sampleCount++] = pts
                    if (!extractor.advance()) break
                }
                if (sampleCount == 0) {
                    throw IOException("Video track contains no presentation frames")
                }

                // B-frames can arrive out of presentation order. Compact duplicates in place.
                Arrays.sort(rawPts, 0, sampleCount)
                var uniqueCount = 1
                for (i in 1 until sampleCount) {
                    if (rawPts[i] != rawPts[uniqueCount - 1]) rawPts[uniqueCount++] = rawPts[i]
                }
                val sortedUnique = if (uniqueCount == rawPts.size) rawPts else rawPts.copyOf(uniqueCount)

                // Detect Variable Frame Rate (VFR) by checking sample interval variances
                var isVfr = false
                if (sortedUnique.size > 2) {
                    val firstDelta = sortedUnique[1] - sortedUnique[0]
                    for (i in 2 until sortedUnique.size) {
                        val delta = sortedUnique[i] - sortedUnique[i - 1]
                        if (abs(delta - firstDelta) > 2_000L) { // >2ms variance across frames indicates VFR
                            isVfr = true
                            break
                        }
                    }
                }

                val index = FrameTimelineIndex(
                    sourceUri = sourceUri,
                    ptsUs = sortedUnique,
                    durationUs = max(durationUs, sortedUnique.last()),
                    isVariableFrameRate = isVfr,
                    nominalFps = nominalFps,
                    rotationDegrees = rotation,
                    width = width,
                    height = height
                )
                index
            } finally {
                extractor.release()
            }
        }
    }
}
