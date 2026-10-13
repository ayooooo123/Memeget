package com.memeget.nativeapp.inference

import com.memeget.nativeapp.TextRegion
import kotlin.math.max
import kotlin.math.min

internal object OcrPostProcessor {
    private const val MIN_OCR_SCORE = 0.30f
    private const val SAME_LINE_OVERLAP = 0.50f

    private class LineAcc(
        var top: Float,
        var bottom: Float,
        val detections: ArrayList<RawOcrDetection>,
    )

    fun toReadingOrderRegions(rawDetections: Array<RawOcrDetection>): List<TextRegion> {
        val kept = rawDetections.filter {
            it.text.trim().isNotEmpty() &&
                it.confidence >= MIN_OCR_SCORE &&
                it.width > 0f &&
                it.height > 0f
        }.sortedBy { it.y + (it.height / 2f) }

        val lines = ArrayList<LineAcc>()

        for (d in kept) {
            val dTop = d.y
            val dBottom = d.y + d.height
            val line = lines.lastOrNull()

            if (line != null) {
                val overlap = min(line.bottom, dBottom) - max(line.top, dTop)
                val shorter = min(line.bottom - line.top, dBottom - dTop)

                if (shorter > 0f && overlap >= SAME_LINE_OVERLAP * shorter) {
                    line.detections += d
                    line.top = min(line.top, dTop)
                    line.bottom = max(line.bottom, dBottom)
                    continue
                }
            }

            lines += LineAcc(dTop, dBottom, arrayListOf(d))
        }

        val result = ArrayList<TextRegion>()
        for (line in lines) {
            line.detections.sortBy { it.x }
            for (d in line.detections) {
                result += TextRegion(
                    text = d.text.trim(),
                    confidence = d.confidence,
                    x = d.x,
                    y = d.y,
                    width = d.width,
                    height = d.height,
                )
            }
        }
        return result
    }
}
