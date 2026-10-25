package com.memeget.nativeapp.inference

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-recall voice-band + syllabic-modulation gate in front of Whisper small.en.
 * Strips silent MP4s, pure tones, hum, and dead air in <5 ms on CPU before 448 MB of
 * Whisper weights ever touch RAM, while padding short 0.4-1.0 s one-word outbursts
 * ("bruh", "what?!") past ModelRunner's 1.0 s chunk floor so they actually transcribe.
 */
internal object AudioVad {
    const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz -> 50 frames/sec
    private const val MIN_VOICE_PEAK_RMS = 0.0045f // ~-47 dBFS voice-band ceiling
    private const val MIN_ACTIVE_FRAMES = 14 // >= 280 ms voiced/fricative energy
    private const val PRE_PAD_FRAMES = 15 // 300 ms lead-in preserves initial plosives
    private const val POST_PAD_FRAMES = 20 // 400 ms trail preserves final consonants
    private const val MERGE_GAP_FRAMES = 30 // merge speech separated by <= 600 ms
    private const val PAUSE_INSERT_SAMPLES = 2_560 // 160 ms breath between merged phrases
    private const val WHISPER_MIN_SAMPLES = 16_800 // > 1.0 s required by ModelRunner.cpp

    /**
     * Returns compacted 16 kHz speech PCM ready for Whisper, or an empty array when the
     * clip holds no human speech (silence, steady tone, low ambient hum).
     */
    fun compactForWhisper(pcm: FloatArray): FloatArray {
        val totalFrames = pcm.size / FRAME_SAMPLES
        if (totalFrames < MIN_ACTIVE_FRAMES) return FloatArray(0)

        val voiceRms = FloatArray(totalFrames)
        val logEnergy = FloatArray(totalFrames)
        val zcr = FloatArray(totalFrames)

        // Single-pass 130 Hz - 3,900 Hz voice-band filter + ZCR across 20 ms frames.
        var prevX = 0f
        var hp = 0f
        var bp = 0f
        for (f in 0 until totalFrames) {
            val base = f * FRAME_SAMPLES
            var sumSq = 0f
            var crossings = 0
            var prevSign = 0
            for (i in 0 until FRAME_SAMPLES) {
                val x = pcm[base + i]
                if (!x.isFinite()) continue
                hp = 0.948f * (hp + x - prevX)
                prevX = x
                bp += 0.62f * (hp - bp)
                sumSq += bp * bp
                val sign = if (hp > 1e-4f) 1 else if (hp < -1e-4f) -1 else 0
                if (sign != 0 && prevSign != 0 && sign != prevSign) crossings++
                if (sign != 0) prevSign = sign
            }
            val rms = sqrt(sumSq / FRAME_SAMPLES)
            voiceRms[f] = rms
            logEnergy[f] = ln(rms + 1e-6f)
            zcr[f] = crossings.toFloat() / FRAME_SAMPLES
        }

        val sortedLogE = logEnergy.copyOf().also { it.sort() }
        val p15 = sortedLogE[(totalFrames * 15) / 100]
        val p95 = sortedLogE[(totalFrames * 95) / 100]
        if (exp(p95) < MIN_VOICE_PEAK_RMS) {
            return FloatArray(0)
        }
        val energyFloor = max(0.0040f, min(0.025f, exp(p15) * 2.1f))

        // Check 1-second (50-frame) local syllabic envelope modulation & ZCR spread so steady
        // sine tones (660 Hz test tones), constant synth drones, and flat hum never masquerade as speech.
        val speechFrame = BooleanArray(totalFrames)
        var rawSpeechCount = 0
        val winRadius = 25
        val winBuf = FloatArray(winRadius * 2 + 1)
        for (f in 0 until totalFrames) {
            val rms = voiceRms[f]
            if (rms < energyFloor || zcr[f] > 0.48f || zcr[f] < 0.008f) continue
            val lo = max(0, f - winRadius)
            val hi = min(totalFrames - 1, f + winRadius)
            var count = 0
            var minZ = 1f
            var maxZ = 0f
            for (k in lo..hi) {
                winBuf[count++] = logEnergy[k]
                if (voiceRms[k] >= energyFloor * 0.7f) {
                    if (zcr[k] < minZ) minZ = zcr[k]
                    if (zcr[k] > maxZ) maxZ = zcr[k]
                }
            }
            winBuf.sort(0, count)
            val localLow = winBuf[(count * 15) / 100]
            val localHigh = winBuf[(count * 88) / 100]
            val modDepth = localHigh - localLow
            val zcrSpread = if (maxZ >= minZ) maxZ - minZ else 0f

            // Speech alternates vowels/consonants/pauses (modDepth >= 0.26 or zcrSpread >= 0.025);
            // pure tones & flat noise sit near modDepth ~0 and zcrSpread ~0.
            if (modDepth >= 0.26f || zcrSpread >= 0.025f) {
                speechFrame[f] = true
                rawSpeechCount++
            }
        }

        if (rawSpeechCount < MIN_ACTIVE_FRAMES) {
            return FloatArray(0)
        }

        // Expand collars around active speech frames and merge close intervals.
        val padded = BooleanArray(totalFrames)
        for (f in 0 until totalFrames) {
            if (!speechFrame[f]) continue
            val start = max(0, f - PRE_PAD_FRAMES)
            val end = min(totalFrames - 1, f + POST_PAD_FRAMES)
            for (k in start..end) padded[k] = true
        }

        val intervals = ArrayList<IntRange>()
        var f = 0
        while (f < totalFrames) {
            if (!padded[f]) {
                f++
                continue
            }
            var end = f
            while (end + 1 < totalFrames) {
                if (padded[end + 1]) {
                    end++
                } else {
                    var gap = 1
                    while (end + gap < totalFrames && !padded[end + gap] && gap <= MERGE_GAP_FRAMES) gap++
                    if (end + gap < totalFrames && gap <= MERGE_GAP_FRAMES && padded[end + gap]) {
                        end += gap
                    } else {
                        break
                    }
                }
            }
            intervals += f..end
            f = end + 1
        }

        if (intervals.isEmpty()) return FloatArray(0)

        var outSamples = 0
        for ((idx, range) in intervals.withIndex()) {
            if (idx > 0) outSamples += PAUSE_INSERT_SAMPLES
            val sStart = range.first * FRAME_SAMPLES
            val sEnd = if (range.last == totalFrames - 1) pcm.size else (range.last + 1) * FRAME_SAMPLES
            outSamples += (sEnd - sStart)
        }

        // If almost the entire clip is already continuous speech (>85%), avoid an extra copy unless < 1.05s.
        if (intervals.size == 1 && outSamples >= WHISPER_MIN_SAMPLES && outSamples >= (pcm.size * 85) / 100) {
            return pcm
        }

        val finalLen = max(outSamples, WHISPER_MIN_SAMPLES)
        val out = FloatArray(finalLen)
        var cursor = 0
        for ((idx, range) in intervals.withIndex()) {
            if (idx > 0) cursor += PAUSE_INSERT_SAMPLES
            val sStart = range.first * FRAME_SAMPLES
            val sEnd = if (range.last == totalFrames - 1) pcm.size else (range.last + 1) * FRAME_SAMPLES
            val len = sEnd - sStart
            System.arraycopy(pcm, sStart, out, cursor, len)
            cursor += len
        }
        return out
    }
}
