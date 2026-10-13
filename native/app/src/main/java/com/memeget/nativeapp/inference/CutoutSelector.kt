package com.memeget.nativeapp.inference

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SubjectMaskChoice(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val mask: ByteArray,
    val coverage: Double,
)

internal data class SelectionResult(
    val subjects: List<SubjectMaskChoice>,
    val dropped: Int,
)

internal object CutoutSelector {
    const val MAX_CUTOUT_SUBJECTS = 8
    private const val SUBJECT_MIN_COVERAGE = 0.001
    private const val SUBJECT_MAX_COVERAGE = 0.90
    private const val BACKDROP_EDGES = 3
    private const val BACKDROP_MIN_COVERAGE = 0.25
    private const val SUBJECT_OVERLAP_LIMIT = 0.60

    private data class Candidate(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val mask: ByteArray,
        val coverage: Double,
        val area: Int,
        val score: Float,
    )

    fun selectSubjectMasks(
        instances: Array<RawFastSamInstance>,
        frameWidth: Int,
        frameHeight: Int,
        maxSubjects: Int = MAX_CUTOUT_SUBJECTS,
    ): SelectionResult {
        val framePixels = max(1, frameWidth * frameHeight).toDouble()
        val marginX = frameWidth * 0.01
        val marginY = frameHeight * 0.01

        val candidates = ArrayList<Candidate>()

        for (inst in instances) {
            if (inst.width <= 0 || inst.height <= 0) continue
            if (inst.mask.size < inst.width * inst.height) continue

            var area = 0
            for (b in inst.mask) {
                if (b.toInt() != 0) area++
            }

            val coverage = area / framePixels
            if (coverage < SUBJECT_MIN_COVERAGE || coverage > SUBJECT_MAX_COVERAGE) continue

            val x2 = inst.x + inst.width
            val y2 = inst.y + inst.height

            var edgeCount = 0
            if (inst.x <= marginX) edgeCount++
            if (inst.y <= marginY) edgeCount++
            if (x2 >= frameWidth - marginX) edgeCount++
            if (y2 >= frameHeight - marginY) edgeCount++

            if (edgeCount >= BACKDROP_EDGES && coverage >= BACKDROP_MIN_COVERAGE) continue

            candidates += Candidate(
                x = inst.x,
                y = inst.y,
                width = inst.width,
                height = inst.height,
                mask = inst.mask,
                coverage = coverage,
                area = area,
                score = inst.score,
            )
        }

        candidates.sortWith { a, b ->
            val areaCmp = b.area.compareTo(a.area)
            if (areaCmp != 0) areaCmp else b.score.compareTo(a.score)
        }

        val chosen = ArrayList<Candidate>()
        var dropped = 0

        for (c in candidates) {
            var overlaps = false
            for (k in chosen) {
                if (sharedPixels(c, k) > SUBJECT_OVERLAP_LIMIT * min(c.area, k.area)) {
                    overlaps = true
                    break
                }
            }
            if (overlaps) continue

            if (chosen.size >= maxSubjects) {
                dropped++
                continue
            }
            chosen += c
        }

        val choices = chosen.map {
            SubjectMaskChoice(
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                mask = it.mask,
                coverage = it.coverage,
            )
        }

        return SelectionResult(choices, dropped)
    }

    private fun sharedPixels(a: Candidate, b: Candidate): Int {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)

        if (right <= left || bottom <= top) return 0

        var count = 0
        for (y in top until bottom) {
            val ar = (y - a.y) * a.width - a.x
            val br = (y - b.y) * b.width - b.x
            for (x in left until right) {
                if (a.mask[ar + x].toInt() != 0 && b.mask[br + x].toInt() != 0) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Pack a 0/1 byte mask into bits, row-major, most significant bit first.
     */
    fun packMaskBits(mask: ByteArray): ByteArray {
        val packed = ByteArray((mask.size + 7) / 8)
        for (i in mask.indices) {
            if (mask[i].toInt() != 0) {
                val byteIdx = i shr 3
                val bitIdx = 7 - (i and 7)
                packed[byteIdx] = (packed[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        return packed
    }
}
