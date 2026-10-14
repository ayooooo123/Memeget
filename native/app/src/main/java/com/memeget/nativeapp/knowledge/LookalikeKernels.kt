package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.inference.NativeBridge

/**
 * Cosine work over unit rows of [dim] floats, flattened. The Label queue and the look-alike vote run
 * it over every meme, so on a phone it runs in native code ([Native]); [Kotlin] keeps the same
 * results for JVM tests.
 */
internal interface LookalikeKernels {
    /** For each row, how many other rows reach [minCos] with it. */
    fun counts(vectors: FloatArray, dim: Int, minCos: Float): IntArray

    /** Rows other than [row], not marked in [covered], that reach [minCos] with row [row]. */
    fun lookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray

    /**
     * For each row of [queries], the [k] rows of [examples] with the highest dot product, best first,
     * into [outIndex] and [outDot] at row * k; -1 and -Infinity fill slots fewer rows reach. An example
     * is passed over when its owner equals the query's (owners below 0 never match), or when its dot
     * product reaches [skipAtOrAbove]. Ties keep the lower example index first.
     */
    fun nearest(
        queries: FloatArray, queryOwners: LongArray, examples: FloatArray, exampleOwners: LongArray,
        dim: Int, k: Int, skipAtOrAbove: Float, outIndex: IntArray, outDot: FloatArray,
    )

    object Native : LookalikeKernels {
        override fun counts(vectors: FloatArray, dim: Int, minCos: Float): IntArray =
            NativeBridge.nativeLookalikeCounts(vectors, dim, minCos)

        override fun lookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray =
            NativeBridge.nativeLookalikesOf(vectors, dim, row, covered, minCos)

        override fun nearest(
            queries: FloatArray, queryOwners: LongArray, examples: FloatArray, exampleOwners: LongArray,
            dim: Int, k: Int, skipAtOrAbove: Float, outIndex: IntArray, outDot: FloatArray,
        ) = NativeBridge.nativeNearest(queries, queryOwners, examples, exampleOwners, dim, k, skipAtOrAbove, outIndex, outDot)
    }

    object Kotlin : LookalikeKernels {
        private fun dot(v: FloatArray, a: Int, b: Int, dim: Int): Float {
            var s = 0f
            for (k in 0 until dim) s += v[a + k] * v[b + k]
            return s
        }

        override fun counts(vectors: FloatArray, dim: Int, minCos: Float): IntArray {
            val n = vectors.size / dim
            val counts = IntArray(n)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (dot(vectors, i * dim, j * dim, dim) >= minCos) {
                        counts[i]++
                        counts[j]++
                    }
                }
            }
            return counts
        }

        override fun lookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray {
            val n = vectors.size / dim
            val found = ArrayList<Int>()
            for (j in 0 until n) {
                if (j != row && !covered[j] && dot(vectors, row * dim, j * dim, dim) >= minCos) found += j
            }
            return found.toIntArray()
        }

        override fun nearest(
            queries: FloatArray, queryOwners: LongArray, examples: FloatArray, exampleOwners: LongArray,
            dim: Int, k: Int, skipAtOrAbove: Float, outIndex: IntArray, outDot: FloatArray,
        ) {
            for (q in queryOwners.indices) {
                val base = q * k
                outIndex.fill(-1, base, base + k)
                outDot.fill(Float.NEGATIVE_INFINITY, base, base + k)
                for (j in exampleOwners.indices) {
                    if (queryOwners[q] >= 0 && exampleOwners[j] == queryOwners[q]) continue
                    var c = 0f
                    for (d in 0 until dim) c += queries[q * dim + d] * examples[j * dim + d]
                    if (c >= skipAtOrAbove || c <= outDot[base + k - 1]) continue
                    var s = k - 1
                    while (s > 0 && outDot[base + s - 1] < c) {
                        outDot[base + s] = outDot[base + s - 1]
                        outIndex[base + s] = outIndex[base + s - 1]
                        s--
                    }
                    outDot[base + s] = c
                    outIndex[base + s] = j
                }
            }
        }
    }
}
