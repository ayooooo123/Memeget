package com.memeget.nativeapp.editor

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/** The original ink, and the slightly wider hole that includes its antialiasing and outline. */
internal class LetterMask(
    val ink: ByteArray,
    val hole: ByteArray,
    val color: Int,
    val edgeColor: Int? = null,
    val outlinePx: Int = 0,
    val shadowOffset: Point? = null,
) {
    companion object {
        private fun bin(c: Int) = ((c ushr 12) and 0xf00) or ((c ushr 8) and 0xf0) or ((c ushr 4) and 15)
        private fun channel(c: Int, shift: Int) = (c ushr shift) and 255
        private fun distance(a: Int, b: Int) = maxOf(abs(channel(a, 16) - channel(b, 16)),
            abs(channel(a, 8) - channel(b, 8)), abs(channel(a, 0) - channel(b, 0)))

        /** A repeated pigment under the letters, but not in the surrounding strip. No confident pigment: no edit. */
        fun find(argb: IntArray, w: Int, h: Int, target: Rect): LetterMask? {
            val inside = IntArray(4096)
            val outside = IntArray(4096)
            val edges = IntArray(4096)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val c = argb[i]
                val b = bin(c)
                if (!target.contains(x, y)) { outside[b]++; continue }
                inside[b]++
                // A band of a smooth gradient has a frequent colour but no stroke edge.
                if ((x >= 2 && distance(c, argb[i - 2]) > 32) || (x + 2 < w && distance(c, argb[i + 2]) > 32) ||
                    (y >= 2 && distance(c, argb[i - 2*w]) > 32) || (y + 2 < h && distance(c, argb[i + 2*w]) > 32)) edges[b]++
            }
            val area = target.width() * target.height()
            val margin = argb.size - area
            if (margin == 0 || area < 4) return null
            // Discount colours that occur at the same density outside the selection (including a flat background).
            fun pigment(counts: IntArray, n: Int, requireEdge: Boolean = false): Int {
                var best = -1
                var score = max(3.0, n * 0.014)
                for (i in counts.indices) {
                    if (requireEdge && edges[i] < max(2, counts[i] / 20)) continue
                    val excess = counts[i] - outside[i].toDouble() * n / margin
                    if (excess > score) { best = i; score = excess }
                }
                return best
            }
            val primary = pigment(inside, area, requireEdge = true)
            if (primary < 0) return null
            fun sample(bucket: Int, region: Rect, rim: ByteArray? = null): Int {
                // Median within the bin: antialiasing need not move a solid ink colour by a shade.
                val levels = IntArray(48)
                var count = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    val i = y * w + x
                    val c = argb[i]
                    if (bin(c) != bucket || (rim != null && rim[i] == 0.toByte())) continue
                    levels[c and 15]++
                    levels[16 + ((c ushr 8) and 15)]++
                    levels[32 + ((c ushr 16) and 15)]++
                    count++
                }
                fun median(start: Int): Int {
                    var rank = count / 2
                    for (v in 0..15) {
                        rank -= levels[start + v]
                        if (rank < 0) return v
                    }
                    error("Empty ink sample")
                }
                return (((bucket and 0xf00) shl 12) or (median(32) shl 16) or
                    ((bucket and 0xf0) shl 8) or (median(16) shl 8) or ((bucket and 15) shl 4) or median(0))
            }
            fun mark(color: Int): ByteArray {
                val mask = ByteArray(argb.size)
                for (y in target.top until target.bottom) for (x in target.left until target.right) {
                    val i = y * w + x
                    if (distance(argb[i], color) <= 32) mask[i] = -1
                }
                return mask
            }
            var color = sample(primary, target)
            var ink = mark(color)
            val amount = ink.count { it != 0.toByte() }
            if (amount < 4 || amount > area * 0.8f) return null
            val edge = LogoMask.squareFilter(ink, w, h, 2, all = false)
            inside.fill(0)
            var edgeSize = 0
            for (i in edge.indices) if (edge[i] != 0.toByte() && ink[i] == 0.toByte()) {
                if (distance(argb[i], color) > 48) inside[bin(argb[i])]++
                edgeSize++
            }
            val second = pigment(inside, edgeSize)
            if (second < 0 || inside[second] < edgeSize * 0.18f) return LetterMask(ink, edge, color)
            var edgeColor = sample(second, Rect(0, 0, w, h), edge)
            // A thick outline can contain more pixels than the fill. The inner colour borders the
            // outer colour on nearly every side; the outer colour also borders the background.
            val outerShare = inside[second].toFloat() / edgeSize
            if (outerShare < 0.65f) {
                val other = mark(edgeColor)
                val rim = LogoMask.squareFilter(other, w, h, 2, all = false)
                var adjacent = 0
                var total = 0
                for (i in rim.indices) if (rim[i] != 0.toByte() && other[i] == 0.toByte()) {
                    total++
                    if (ink[i] != 0.toByte()) adjacent++
                }
                if (adjacent > total * max(0.60f, outerShare + 0.18f)) {
                    ink = other
                    val previous = color
                    color = edgeColor
                    edgeColor = previous
                }
            }
            val reach = (minOf(target.height(), target.width()) / 7).coerceIn(2, 8)
            val depth = distanceFromInk(ink, w, h, reach + 3)
            val rings = IntArray(reach + 1)
            val matches = IntArray(reach + 1)
            for (i in depth.indices) {
                val d = depth[i].toInt()
                if (d !in 1..reach) continue
                rings[d]++
                if (distance(argb[i], edgeColor) <= 24) matches[d]++
            }
            var thickness = 0
            for (d in 1..reach) {
                if (rings[d] == 0 || matches[d] < rings[d] * 0.2f) break
                thickness = d
            }
            val shadow = fitShadow(argb, depth, thickness, edgeColor, w, reach)
            if (shadow != null) {
                val decorated = ink.copyOf()
                for (y in maxOf(0, -shadow.y) until minOf(h, h - shadow.y)) {
                    for (x in maxOf(0, -shadow.x) until minOf(w, w - shadow.x)) {
                        if (ink[y * w + x] != 0.toByte()) decorated[(y + shadow.y) * w + x + shadow.x] = -1
                    }
                }
                return LetterMask(ink, LogoMask.squareFilter(decorated, w, h, 2, all = false),
                    color, edgeColor, shadowOffset = shadow)
            }
            for (i in depth.indices) depth[i] = if (depth[i] <= thickness + 2) -1 else 0
            return LetterMask(ink, depth, color, edgeColor, thickness)
        }

        /** Distance in eight directions. Two passes give all stroke widths without growing a new mask per radius. */
        private fun distanceFromInk(ink: ByteArray, w: Int, h: Int, cap: Int): ByteArray {
            val depth = ByteArray(ink.size) { if (ink[it] != 0.toByte()) 0 else cap.toByte() }
            fun sweep(xs: IntProgression, ys: IntProgression, step: Int) {
                for (y in ys) for (x in xs) {
                    val i = y * w + x
                    var d = depth[i].toInt()
                    if (d == 0) continue
                    if (x - step in 0 until w) d = minOf(d, 1 + depth[i - step])
                    if (y - step in 0 until h) {
                        val previous = i - step * w
                        d = minOf(d, 1 + depth[previous])
                        if (x > 0) d = minOf(d, 1 + depth[previous - 1])
                        if (x + 1 < w) d = minOf(d, 1 + depth[previous + 1])
                    }
                    depth[i] = d.toByte()
                }
            }
            sweep(0 until w, 0 until h, 1)
            sweep(w - 1 downTo 0, h - 1 downTo 0, -1)
            return depth
        }

        /** Compare the visible edge pigment with a stroke and with a translated copy of the ink.
         * Only test pixels outside the fill. Use evenly spaced samples to bound work on large captions. */
        private fun fitShadow(
            pixels: IntArray, depth: ByteArray, thickness: Int, pigment: Int, w: Int, reach: Int,
        ): Point? {
            val exterior = depth.count { it.toInt() in 1..reach }
            val stride = (exterior + 2047) / 2048
            if (stride == 0) return null
            val points = IntArray((exterior + stride - 1) / stride)
            val painted = BooleanArray(points.size)
            var seen = 0
            var n = 0
            var observed = 0
            var outlined = 0
            var overlap = 0
            for (i in depth.indices) {
                if (depth[i].toInt() !in 1..reach || seen++ % stride != 0) continue
                points[n] = i
                painted[n] = distance(pixels[i], pigment) <= 24
                if (painted[n]) observed++
                if (depth[i] <= thickness) {
                    outlined++
                    if (painted[n]) overlap++
                }
                n++
            }
            if (observed < 4) return null
            var best = max(0.68f, 2f * overlap / (observed + outlined) + 0.12f)
            if (best >= 1f) return null
            var result: Point? = null
            for (dy in -reach..reach) for (dx in -reach..reach) {
                if (dx == 0 && dy == 0) continue
                var hits = 0
                var predicted = 0
                for (k in points.indices) {
                    val i = points[k]
                    val x = i % w - dx
                    val j = i - dy * w - dx
                    if (x !in 0 until w || j !in depth.indices || depth[j] != 0.toByte()) continue
                    predicted++
                    if (painted[k]) hits++
                }
                val score = 2f * hits / (observed + predicted)
                if (score > best) {
                    best = score
                    result = Point(dx, dy)
                }
            }
            return result
        }
    }
}
