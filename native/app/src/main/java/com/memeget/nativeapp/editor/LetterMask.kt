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
            var primary = pigment(inside, area, requireEdge = true)
            if (primary < 0 || inside[primary] > area * 0.8f) {
                // A dense neighbouring line can put more letter colour in one margin than in the
                // selected line. Discount that side only if the other margins agree it is not background.
                primary = pigmentBesideLine(argb, w, h, target, inside, outside, edges)
            }
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
            if (second < 0 || inside[second] < edgeSize * 0.18f) {
                return LetterMask(ink, protectNeighbourInk(edge, argb, w, h, target, color, 2, ink), color)
            }
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
            // A tight box can end in the middle of a thick outline. Look beyond the
            // repair budget for its outer edge, without extending the repair there.
            val evidenceReach = reach + 2
            val depth = distanceFromInk(ink, w, h, evidenceReach + 3)
            val rings = IntArray(reach + 1)
            val matches = IntArray(reach + 1)
            var outerEdges = 0
            val requiredEdges = maxOf(3, edgeSize / 16)
            for (i in depth.indices) {
                val d = depth[i].toInt()
                if (d !in 1..evidenceReach) continue
                if (d <= reach) rings[d]++
                if (distance(argb[i], edgeColor) > 24) continue
                if (d <= reach) matches[d]++
                // A real outline or shadow ends at a visible transition away from the ink.
                // On a smooth gradient the adjoining background continues past the putative border.
                if (outerEdges < requiredEdges) {
                    val x = i % w
                    fun changesOutside(j: Int) = depth[j] > d && distance(argb[i], argb[j]) > 32
                    if ((x >= 2 && changesOutside(i - 2)) || (x + 2 < w && changesOutside(i + 2)) ||
                        (i >= 2 * w && changesOutside(i - 2 * w)) || (i + 2 * w < argb.size && changesOutside(i + 2 * w))) outerEdges++
                }
            }
            val hasEdge = outerEdges >= requiredEdges
            var thickness = 0
            if (hasEdge) for (d in 1..reach) {
                if (rings[d] == 0 || matches[d] < rings[d] * 0.2f) break
                thickness = d
            }
            // A colour that fills most of the margin is not evidence of an outline in a gap.
            val decoration = edgeColor.takeIf { hasEdge && outside[bin(it)] * 2 < margin }
            val shadow = if (hasEdge) fitShadow(argb, depth, thickness, edgeColor, w, reach) else null
            if (shadow != null) {
                val decorated = ink.copyOf()
                for (y in maxOf(0, -shadow.y) until minOf(h, h - shadow.y)) {
                    for (x in maxOf(0, -shadow.x) until minOf(w, w - shadow.x)) {
                        if (ink[y * w + x] != 0.toByte()) decorated[(y + shadow.y) * w + x + shadow.x] = -1
                    }
                }
                return LetterMask(ink,
                    protectNeighbourInk(LogoMask.squareFilter(decorated, w, h, 2, all = false),
                        argb, w, h, target, color, 2, ink, decoration),
                    color, edgeColor, shadowOffset = shadow)
            }
            for (i in depth.indices) depth[i] = if (depth[i] <= thickness + 2) -1 else 0
            return LetterMask(ink, protectNeighbourInk(depth, argb, w, h, target, color, thickness + 2, ink, decoration),
                color, edgeColor.takeIf { hasEdge }, thickness)
        }

        private fun pigmentBesideLine(argb: IntArray, w: Int, h: Int, box: Rect, inside: IntArray,
                                      outside: IntArray, edges: IntArray): Int {
            if (edges.none { it >= 2 }) return -1
            val area = box.width() * box.height()
            val margin = argb.size - area
            val side = IntArray(inside.size)
            var best = -1
            var score = max(3.0, area * 0.014)
            fun consider(left: Int, top: Int, right: Int, bottom: Int) {
                val size = (bottom - top) * (right - left)
                val remaining = margin - size
                if (size == 0 || remaining < maxOf(1, margin / 3)) return
                side.fill(0)
                for (y in top until bottom) for (x in left until right) side[bin(argb[y * w + x])]++
                for (i in inside.indices) {
                    if (inside[i] < 4 || inside[i] > area * 0.8f || edges[i] < max(2, inside[i] / 20)) continue
                    if (side[i] < 4 || side[i] * 10 < outside[i] * 9) continue
                    val excess = inside[i] - (outside[i] - side[i]).toDouble() * area / remaining
                    if (excess > score) { score = excess; best = i }
                }
            }
            consider(0, 0, w, box.top)
            consider(0, box.bottom, w, h)
            consider(0, box.top, box.left, box.bottom)
            consider(box.right, box.top, w, box.bottom)
            return best
        }

        /** Protect nearby runs of the fill colour outside the box, unless they connect to a selected glyph. */
        private fun protectNeighbourInk(
            hole: ByteArray, argb: IntArray, w: Int, h: Int, target: Rect, fill: Int, reach: Int, ink: ByteArray,
            decoration: Int? = null,
        ): ByteArray {
            var outside: ByteArray? = null
            var count = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (target.contains(x, y)) continue
                val i = y * w + x
                if (distance(argb[i], fill) > 32) continue
                val labels = outside ?: ByteArray(argb.size).also { outside = it }
                labels[i] = -1
                count++
            }
            val labels = outside ?: return hole
            val queue = IntArray(count)
            var tail = 0
            fun connect(x: Int, y: Int) {
                for (row in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (col in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                    val i = row * w + col
                    if (labels[i] < 0) {
                        labels[i] = 1
                        queue[tail++] = i
                    }
                }
            }
            // Only fill on the four edges can extend outside. Traverse its connected exterior pixels.
            fun seed(x: Int, y: Int) { if (ink[y * w + x] != 0.toByte()) connect(x, y) }
            for (x in target.left until target.right) { seed(x, target.top); seed(x, target.bottom - 1) }
            for (y in target.top + 1 until target.bottom - 1) { seed(target.left, y); seed(target.right - 1, y) }
            var head = 0
            while (head < tail) {
                val i = queue[head++]
                connect(i % w, i / w)
            }
            if (tail == count) return hole
            val near = distanceFromInk(labels, w, h, reach + 1)
            fun nearerSelectedInk(x: Int, y: Int, otherDistance: Int): Boolean {
                val r = otherDistance - 1
                for (row in maxOf(target.top, y - r)..minOf(target.bottom - 1, y + r))
                    for (col in maxOf(target.left, x - r)..minOf(target.right - 1, x + r))
                        if (ink[row * w + col] != 0.toByte()) return true
                return false
            }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (hole[i] == 0.toByte() || target.contains(x, y) || labels[i] > 0 || near[i] > reach) continue
                // Where the bands meet, keep a real outline pixel only on the selected glyph's side.
                if (decoration != null && near[i] > 0 && distance(argb[i], decoration) <= 32 &&
                    nearerSelectedInk(x, y, near[i].toInt())) continue
                hole[i] = 0
            }
            return hole
        }

        /** Eight-way distance to a negative (ink) pixel. Positive labels are not seeds. */
        private fun distanceFromInk(ink: ByteArray, w: Int, h: Int, cap: Int): ByteArray {
            val depth = ByteArray(ink.size) { if (ink[it] < 0) 0 else cap.toByte() }
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
