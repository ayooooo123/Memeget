package com.memeget.nativeapp.data

import com.memeget.nativeapp.FormatGroup
import kotlin.math.sqrt

/**
 * Format groups: memes that share one picture with different words on it (a template, or an original and its
 * remixes). Uses Hamming distance on 512-bit sign codes as a prefilter before the exact int8 cosine: for cosine
 * >= 0.86 the expected Hamming distance is 512 * arccos(0.86) / pi ~ 87 bits (sigma ~ 8.5), so a 128-bit cut
 * (~4.8 sigma, cosine ~0.71) discards nearly all unrelated pairs without dropping real edges.
 */
internal object MemeClusters {
    private const val FORMAT_COS = 0.86f
    private const val PREFILTER_BITS = 128
    private const val MIN_GROUP = 3
    /** A larger component is a chain of loosely similar pictures, not one template. */
    private const val MAX_GROUP = 120

    fun formats(
        ids: LongArray,
        modifiedAt: LongArray,
        vectors: ByteArray,
        dim: Int = 512,
        ocrKeys: List<String>,
        labels: List<List<String>>,
        rabit64: LongArray? = null,
        hasVector: BooleanArray? = null,
    ): List<FormatGroup> {
        val n = ids.size
        if (n == 0) return emptyList()
        require(modifiedAt.size == n) { "modifiedAt size ${modifiedAt.size} != ids size $n" }
        require(ocrKeys.size == n) { "ocrKeys size ${ocrKeys.size} != ids size $n" }
        require(labels.size == n) { "labels size ${labels.size} != ids size $n" }
        require(vectors.size >= n * dim) { "vectors size ${vectors.size} < required ${n * dim}" }

        val norms = FloatArray(n)
        val valid = BooleanArray(n)
        for (i in 0 until n) {
            if (hasVector != null && !hasVector[i]) continue
            val off = i * dim
            var sumSq = 0.0
            for (d in 0 until dim) {
                val b = vectors[off + d].toInt()
                sumSq += b * b
            }
            if (sumSq > 0.0) {
                norms[i] = sqrt(sumSq).toFloat()
                valid[i] = true
            }
        }
        val codes: LongArray? = if (dim == 512) {
            if (rabit64 != null && rabit64.size >= n * 8) rabit64 else computeSignCodes(vectors, n, valid)
        } else null

        fun cosine(i: Int, j: Int): Float {
            val offI = i * dim
            val offJ = j * dim
            var dot = 0
            for (d in 0 until dim) dot += vectors[offI + d].toInt() * vectors[offJ + d].toInt()
            return dot.toFloat() / (norms[i] * norms[j])
        }

        // Same picture, different words: an edge needs the OCR text to differ, and at least one meme to have some.
        val sets = DisjointSet(n)
        for (i in 0 until n) {
            if (!valid[i]) continue
            for (j in i + 1 until n) {
                if (!valid[j]) continue
                val ocrI = ocrKeys[i]
                val ocrJ = ocrKeys[j]
                if (ocrI == ocrJ) continue
                if (codes != null && hammingDistance(codes, i, j, PREFILTER_BITS) > PREFILTER_BITS) continue
                if (cosine(i, j) >= FORMAT_COS) sets.union(i, j)
            }
        }
        val components = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until n) if (valid[i]) components.getOrPut(sets.find(i)) { ArrayList() }.add(i)

        val groups = ArrayList<FormatGroup>()
        for (members in components.values) {
            if (members.size < MIN_GROUP || members.size > MAX_GROUP) continue
            val newestFirst = members.sortedWith(compareByDescending<Int> { modifiedAt[it] }.thenByDescending { ids[it] })
            groups += FormatGroup(label = sharedLabel(newestFirst, labels), ids = newestFirst.map { ids[it] })
        }
        groups.sortWith(compareByDescending<FormatGroup> { it.ids.size }.thenByDescending { it.ids.first() })
        return groups
    }

    /** The tag at least half the members carry (the most common such), as one member spells it; else "". */
    private fun sharedLabel(members: List<Int>, labels: List<List<String>>): String {
        val counts = LinkedHashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (m in members) {
            val seen = HashSet<String>()
            for (raw in labels[m]) {
                val key = TagMerge.normalizeTagLabel(raw)
                if (key.isNotEmpty() && seen.add(key)) {
                    counts[key] = (counts[key] ?: 0) + 1
                    spelling.putIfAbsent(key, raw)
                }
            }
        }
        val best = counts.maxByOrNull { it.value } ?: return ""
        return if (best.value * 2 >= members.size) spelling.getValue(best.key) else ""
    }

    private fun computeSignCodes(vectors: ByteArray, n: Int, valid: BooleanArray): LongArray {
        val dst = LongArray(n * 8)
        for (i in 0 until n) {
            if (!valid[i]) continue
            val vOff = i * 512
            val rOff = i * 8
            for (d in 0 until 512) {
                if (vectors[vOff + d] >= 0) dst[rOff + (d ushr 6)] = dst[rOff + (d ushr 6)] or (1L shl (d and 63))
            }
        }
        return dst
    }

    private fun hammingDistance(codes: LongArray, aIdx: Int, bIdx: Int, maxAllowed: Int): Int {
        val aOff = aIdx * 8
        val bOff = bIdx * 8
        var dist = 0
        for (w in 0 until 8) {
            dist += java.lang.Long.bitCount(codes[aOff + w] xor codes[bOff + w])
            if (dist > maxAllowed) return dist
        }
        return dist
    }

    private class DisjointSet(n: Int) {
        private val parent = IntArray(n) { it }
        private val rank = IntArray(n)

        fun find(x: Int): Int {
            var root = x
            while (root != parent[root]) root = parent[root]
            var curr = x
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(x: Int, y: Int) {
            val rootX = find(x)
            val rootY = find(y)
            if (rootX == rootY) return
            when {
                rank[rootX] < rank[rootY] -> parent[rootX] = rootY
                rank[rootX] > rank[rootY] -> parent[rootY] = rootX
                else -> {
                    parent[rootY] = rootX
                    rank[rootX]++
                }
            }
        }
    }
}
