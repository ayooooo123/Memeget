package com.memeget.nativeapp.data

import com.memeget.nativeapp.FormatGroup
import com.memeget.nativeapp.MemeGroups
import kotlin.math.sqrt

/**
 * Pure clustering algorithm for near-duplicate and shared-template (format) meme detection.
 *
 * Uses Hamming distance on 512-bit RaBit sign codes as a safe O(1) prefilter:
 * for cosine >= 0.86, theoretical mean Hamming distance is 512 * arccos(0.86)/pi ~ 87.3 bits
 * (sigma ~ 8.5 bits). A threshold of 128 bits (~4.78 sigma, cosine ~0.707) safely discards
 * >99.9% of unrelated pairs without dropping valid format edges.
 */
internal object MemeClusters {

    fun group(
        ids: LongArray,
        modifiedAt: LongArray,
        vectors: ByteArray,
        dim: Int = 512,
        ocrKeys: List<String>,
        labels: List<List<String>>,
        rabit64: LongArray? = null,
        hasVector: BooleanArray? = null,
        /** "image" or "video" per meme; null treats every meme as an image. */
        kinds: List<String>? = null,
        /** Normalized transcript per meme ([SearchText.phraseKey]); null treats every transcript as blank. */
        transcriptKeys: List<String>? = null,
    ): MemeGroups {
        val n = ids.size
        if (n == 0) return MemeGroups.Empty
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
            if (rabit64 != null && rabit64.size >= n * 8) {
                rabit64
            } else {
                computeRabit(vectors, n, valid)
            }
        } else null

        fun cosine(i: Int, j: Int): Float {
            val normI = norms[i]
            val normJ = norms[j]
            if (normI <= 0f || normJ <= 0f) return 0f
            val offI = i * dim
            val offJ = j * dim
            var dot = 0
            for (d in 0 until dim) {
                dot += vectors[offI + d].toInt() * vectors[offJ + d].toInt()
            }
            return dot.toFloat() / (normI * normJ)
        }

        // 1. Near-copies: cosine >= 0.97 between memes of the same kind with the same OCR text. A video is a copy
        //    only when its words match too: the picture says nothing about the audio, so two clips that look alike
        //    can say different things. A video without a transcript can't be checked and is never hidden.
        val copyBuckets = HashMap<String, ArrayList<Int>>()
        for (i in 0 until n) {
            if (!valid[i]) continue
            val video = kinds?.get(i) == "video"
            val speech = transcriptKeys?.get(i).orEmpty()
            if (video && speech.isBlank()) continue
            val key = if (video) "video\u0000${ocrKeys[i]}\u0000$speech" else "image\u0000${ocrKeys[i]}"
            copyBuckets.getOrPut(key) { ArrayList() }.add(i)
        }

        val nearCopyDsu = DisjointSet(n)
        for ((_, bucket) in copyBuckets) {
            val bSize = bucket.size
            if (bSize < 2) continue
            for (a in 0 until bSize) {
                val i = bucket[a]
                for (b in a + 1 until bSize) {
                    val j = bucket[b]
                    if (codes != null && hammingDistance(codes, i, j, 70) > 70) continue
                    if (cosine(i, j) >= 0.97f) {
                        nearCopyDsu.union(i, j)
                    }
                }
            }
        }

        val nearComponents = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until n) {
            if (!valid[i]) continue
            val root = nearCopyDsu.find(i)
            nearComponents.getOrPut(root) { ArrayList() }.add(i)
        }

        val duplicateOf = HashMap<Long, Long>()
        val keeperIndices = ArrayList<Int>(nearComponents.size)

        for ((_, members) in nearComponents) {
            if (members.isEmpty()) continue
            var keeperIdx = members[0]
            for (k in 1 until members.size) {
                val m = members[k]
                if (modifiedAt[m] > modifiedAt[keeperIdx] ||
                    (modifiedAt[m] == modifiedAt[keeperIdx] && ids[m] > ids[keeperIdx])
                ) {
                    keeperIdx = m
                }
            }
            keeperIndices.add(keeperIdx)
            val keeperId = ids[keeperIdx]
            for (m in members) {
                if (m != keeperIdx) {
                    duplicateOf[ids[m]] = keeperId
                }
            }
        }

        // 2. Formats: among keepers, cosine >= 0.86 where OCR keys differ and at least one is non-blank
        val kCount = keeperIndices.size
        val formatDsu = DisjointSet(kCount)
        for (a in 0 until kCount) {
            val i = keeperIndices[a]
            val ocrI = ocrKeys[i]
            for (b in a + 1 until kCount) {
                val j = keeperIndices[b]
                val ocrJ = ocrKeys[j]
                if (ocrI == ocrJ || (ocrI.isEmpty() && ocrJ.isEmpty())) continue
                if (codes != null && hammingDistance(codes, i, j, 128) > 128) continue
                if (cosine(i, j) >= 0.86f) {
                    formatDsu.union(a, b)
                }
            }
        }

        val formatComponents = HashMap<Int, ArrayList<Int>>()
        for (a in 0 until kCount) {
            val root = formatDsu.find(a)
            formatComponents.getOrPut(root) { ArrayList() }.add(a)
        }

        val formatGroups = ArrayList<FormatGroup>()
        for ((_, members) in formatComponents) {
            if (members.size < 3 || members.size > 120) continue
            val sortedKeepers = members.map { keeperIndices[it] }.sortedWith(
                compareByDescending<Int> { modifiedAt[it] }.thenByDescending { ids[it] }
            )
            val memberIds = sortedKeepers.map { ids[it] }

            val tagCounts = LinkedHashMap<String, Int>()
            val originalLabels = HashMap<String, String>()
            for (kIdx in sortedKeepers) {
                val seenNorm = HashSet<String>()
                for (rawLabel in labels[kIdx]) {
                    val norm = TagMerge.normalizeTagLabel(rawLabel)
                    if (norm.isNotEmpty() && seenNorm.add(norm)) {
                        tagCounts[norm] = (tagCounts[norm] ?: 0) + 1
                        if (!originalLabels.containsKey(norm)) {
                            originalLabels[norm] = rawLabel
                        }
                    }
                }
            }

            var bestNorm: String? = null
            var bestCount = 0
            for ((norm, count) in tagCounts) {
                if (count > bestCount) {
                    bestCount = count
                    bestNorm = norm
                }
            }

            val groupLabel = if (bestNorm != null && bestCount * 2 >= members.size) {
                originalLabels[bestNorm] ?: ""
            } else {
                ""
            }

            formatGroups.add(FormatGroup(label = groupLabel, ids = memberIds))
        }

        formatGroups.sortWith(
            compareByDescending<FormatGroup> { it.ids.size }
                .thenByDescending { it.ids.firstOrNull() ?: 0L }
        )

        return MemeGroups(duplicateOf = duplicateOf, formats = formatGroups)
    }

    private fun computeRabit(vectors: ByteArray, n: Int, valid: BooleanArray): LongArray {
        val dst = LongArray(n * 8)
        for (i in 0 until n) {
            if (!valid[i]) continue
            val vOff = i * 512
            val rOff = i * 8
            for (d in 0 until 512) {
                if (vectors[vOff + d] >= 0) {
                    dst[rOff + (d ushr 6)] = dst[rOff + (d ushr 6)] or (1L shl (d and 63))
                }
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
        val parent = IntArray(n) { it }
        val rank = IntArray(n)

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

        fun union(x: Int, y: Int): Boolean {
            val rootX = find(x)
            val rootY = find(y)
            if (rootX == rootY) return false
            if (rank[rootX] < rank[rootY]) {
                parent[rootX] = rootY
            } else if (rank[rootX] > rank[rootY]) {
                parent[rootY] = rootX
            } else {
                parent[rootY] = rootX
                rank[rootX]++
            }
            return true
        }
    }
}
