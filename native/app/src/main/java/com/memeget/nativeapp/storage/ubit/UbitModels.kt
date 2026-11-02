package com.memeget.nativeapp.storage.ubit

import java.util.Locale
import kotlin.math.roundToInt

// Core stream descriptors and format-ranking logic drawn from uBit, dressed tight for Master's Memeget intake.

internal enum class UbitStreamProto {
    HTTP,
    HLS,
    DASH,
}

internal data class UbitByteRange(
    val start: Long,
    val end: Long,
) {
    val length: Long get() = (end - start + 1L).coerceAtLeast(0L)
}

internal data class UbitFormat(
    val id: String,
    val url: String,
    val ext: String = "mp4",
    val proto: UbitStreamProto = UbitStreamProto.HTTP,
    val vcodec: String? = null,
    val acodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val bitrate: Long? = null,
    val filesize: Long? = null,
    val qualityLabel: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val initRange: UbitByteRange? = null,
    val indexRange: UbitByteRange? = null,
    val audioTrackUrl: String? = null,
    val isLive: Boolean = false,
) {
    val hasVideo: Boolean
        get() {
            val v = vcodec?.lowercase(Locale.ROOT)?.trim().orEmpty()
            return if (v.isEmpty() || v == "none") (height ?: 0) > 0 else true
        }

    val hasAudio: Boolean
        get() {
            if (!audioTrackUrl.isNullOrBlank()) return true
            val a = acodec?.lowercase(Locale.ROOT)?.trim().orEmpty()
            return if (a.isEmpty() || a == "none") !hasVideo else true
        }

    val isH264: Boolean
        get() {
            val v = vcodec?.lowercase(Locale.ROOT) ?: return ext.equals("mp4", ignoreCase = true) && hasVideo
            return "avc" in v || "h264" in v
        }

    val isNativeMp4: Boolean
        get() = ext.equals("mp4", ignoreCase = true) || proto == UbitStreamProto.HLS

    val isAac: Boolean
        get() {
            val a = acodec?.lowercase(Locale.ROOT)
                ?: return (ext.equals("m4a", ignoreCase = true) || ext.equals("mp4", ignoreCase = true)) && hasAudio
            return "mp4a" in a || "aac" in a
        }

    val displayQuality: String
        get() {
            if (!qualityLabel.isNullOrBlank()) return qualityLabel
            val h = height ?: 0
            if (h > 0) {
                val f = fps ?: 0.0
                return if (f > 31.0) "${h}p${f.roundToInt()}" else "${h}p"
            }
            if (hasAudio && !hasVideo) {
                val br = bitrate ?: 0L
                return if (br > 0L) "Audio ${br / 1000L}k" else "Audio"
            }
            return id
        }
}

internal data class UbitMediaInfo(
    val id: String,
    val title: String,
    val site: String,
    val webpageUrl: String,
    val author: String? = null,
    val uploadDate: String? = null,
    val description: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    val isLive: Boolean = false,
    val isDrm: Boolean = false,
    val formats: List<UbitFormat> = emptyList(),
    val dashRepresentations: List<UbitDashRepresentation> = emptyList(),
    val imageFallbackUrl: String? = null,
    val imageFallbackMime: String? = null,
    val preferredFileStem: String? = null,
    val sessionCookies: Map<String, Map<String, String>> = emptyMap(),
)

internal data class UbitSelectedStreams(
    val video: UbitFormat?,
    val audio: UbitFormat?,
) {
    val primary: UbitFormat get() = video ?: audio ?: error("Selected stream pair cannot be empty")
    val displayQuality: String get() = video?.displayQuality ?: audio?.displayQuality ?: "video"
}

internal object UbitFormatSelector {

    // Picks the cleanest compatible stream pair up to maxHeight (default 1080p for shared meme clips)
    // so separate adaptive video + audio merge cleanly without needing external FFmpeg.
    fun select(
        formats: List<UbitFormat>,
        maxHeight: Int = 1080,
        preferCompatible: Boolean = true,
    ): UbitSelectedStreams {
        require(formats.isNotEmpty()) { "No playable media streams found at that link." }

        val videoCandidates = formats.filter { it.hasVideo }
        if (videoCandidates.isEmpty()) {
            val bestAudio = pickBestAudio(formats, preferAac = preferCompatible) ?: formats.first()
            return UbitSelectedStreams(video = null, audio = bestAudio)
        }

        val withinCeiling = if (maxHeight > 0) {
            videoCandidates.filter { f -> val h = f.height ?: 0; h <= 0 || h <= maxHeight }
        } else {
            videoCandidates
        }
        val pool = withinCeiling.ifEmpty { videoCandidates }

        val chosenVideo = pool.sortedWith { lhs, rhs ->
            if (preferCompatible && lhs.isNativeMp4 != rhs.isNativeMp4) {
                return@sortedWith if (lhs.isNativeMp4) -1 else 1
            }
            val lh = lhs.height ?: 0
            val rh = rhs.height ?: 0
            if (lh != rh) return@sortedWith rh.compareTo(lh)

            val lfps = (lhs.fps ?: 30.0).roundToInt()
            val rfps = (rhs.fps ?: 30.0).roundToInt()
            if (lfps != rfps) return@sortedWith rfps.compareTo(lfps)

            if (preferCompatible && lhs.isH264 != rhs.isH264) {
                return@sortedWith if (lhs.isH264) -1 else 1
            }

            val lbr = lhs.bitrate ?: lhs.filesize ?: 0L
            val rbr = rhs.bitrate ?: rhs.filesize ?: 0L
            if (lbr != rbr) return@sortedWith rbr.compareTo(lbr)

            if (lhs.hasAudio != rhs.hasAudio) {
                return@sortedWith if (lhs.hasAudio) -1 else 1
            }
            0
        }.first()

        if (chosenVideo.hasAudio) {
            return UbitSelectedStreams(video = chosenVideo, audio = null)
        }

        val wantAac = preferCompatible || chosenVideo.ext.equals("mp4", ignoreCase = true) || chosenVideo.isH264
        val chosenAudio = pickBestAudio(formats, preferAac = wantAac)
        return UbitSelectedStreams(video = chosenVideo, audio = chosenAudio)
    }

    fun pickBestAudio(formats: List<UbitFormat>, preferAac: Boolean): UbitFormat? {
        val audioOnly = formats.filter { it.hasAudio && !it.hasVideo }
        if (audioOnly.isEmpty()) return null
        return audioOnly.sortedWith { lhs, rhs ->
            if (preferAac && lhs.isAac != rhs.isAac) {
                return@sortedWith if (lhs.isAac) -1 else 1
            }
            val lbr = lhs.bitrate ?: lhs.filesize ?: 0L
            val rbr = rhs.bitrate ?: rhs.filesize ?: 0L
            rbr.compareTo(lbr)
        }.first()
    }

    fun sanitizeFileStem(raw: String, fallback: String = "video"): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .replace(Regex("[^a-zA-Z0-9._ -]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(80)
            .trim('_', '.')
        return cleaned.ifBlank { fallback }
    }
}
