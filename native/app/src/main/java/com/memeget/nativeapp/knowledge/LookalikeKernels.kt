package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.inference.NativeBridge

/**
 * Cosine thresholding over [vectors]: n unit rows of [dim] floats, flattened. The Label queue runs it
 * over every unlabeled meme, so on a phone it runs in native code ([Native]); [Kotlin] keeps the same
 * results for JVM tests.
 */
internal interface LookalikeKernels {
    /** For each row, how many other rows reach [minCos] with it. */
    fun counts(vectors: FloatArray, dim: Int, minCos: Float): IntArray

    /** Rows other than [row], not marked in [covered], that reach [minCos] with row [row]. */
    fun lookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray

    object Native : LookalikeKernels {
        override fun counts(vectors: FloatArray, dim: Int, minCos: Float): IntArray =
            NativeBridge.nativeLookalikeCounts(vectors, dim, minCos)

        override fun lookalikesOf(vectors: FloatArray, dim: Int, row: Int, covered: BooleanArray, minCos: Float): IntArray =
            NativeBridge.nativeLookalikesOf(vectors, dim, row, covered, minCos)
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
    }
}
