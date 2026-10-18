package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.data.FloatBlob

/**
 * Spreads people's labels to look-alike memes with a nearest-neighbour vote over image
 * vectors. Measured on a real 2,370-meme library with the owner's 602 labeled memes split
 * in half, each half's cutoff picked on the other: the vote agreed with his labels 89% of
 * the time and labeled 27% of held-out memes, never giving a label he had rejected there.
 * Per-label logistic heads labeled 28% at 57% agreement (10 rejected labels returned), and
 * best-example retrieval 79% at 20% because it hands every meme five labels.
 */
internal object LookalikeVote {
    const val NEIGHBORS = 5
    /** The two halves' independently picked cutoffs were 0.78 and 0.80; the stricter one. */
    const val MIN_COS = 0.80f
    /** Two items show the same picture (indexing's frame-dedup bar). */
    const val SAME_IMAGE_COS = 0.985f
    /** A label applies without review once held-out votes for it were right this often... */
    const val RELIABLE_PRECISION = 0.9f
    /** ...over at least this many held-out votes. */
    const val RELIABLE_SUPPORT = 3
    const val NO_MEME = -1L

    /** A labeled vector: a taught example ([memeId] = [NO_MEME]) or a meme a person labeled. */
    class Example(val vector: FloatArray, val label: String, val category: String, val memeId: Long)

    class Index(val examples: List<Example>, val negatives: Map<String, List<FloatArray>>) {
        internal val dim: Int get() = examples.firstOrNull()?.vector?.size ?: 0
        /** Every example's vector in one row-major array, and each example's owner, for [LookalikeKernels.nearest]. */
        internal val flat: FloatArray by lazy {
            FloatArray(examples.size * dim).also { out ->
                for ((i, e) in examples.withIndex()) {
                    require(e.vector.size == dim) { "examples must share one vector size" }
                    System.arraycopy(e.vector, 0, out, i * dim, dim)
                }
            }
        }
        internal val owners: LongArray by lazy { LongArray(examples.size) { examples[it].memeId } }
    }

    /** A meme a person labeled, for measuring which labels hold up on memes the vote has not seen. */
    class LabeledMeme(val memeId: Long, val image: FloatArray, val labels: Set<String>, val rejected: Set<String>)

    data class Vote(val label: String, val category: String, val cosine: Float, val weight: Float, val count: Int = 1)

    /**
     * Labels among the [NEIGHBORS] nearest examples, by summed cosine; no cutoff. [cosine] is
     * the label's nearest example. [excludeMeme] drops that meme's own labels; [skipSamePicture]
     * drops copies of the queried picture, for held-out measurement.
     */
    fun rank(index: Index, image: FloatArray, excludeMeme: Long = NO_MEME, skipSamePicture: Boolean = false): List<Vote> {
        val topIndex = IntArray(NEIGHBORS) { -1 }
        val topCos = FloatArray(NEIGHBORS) { Float.NEGATIVE_INFINITY }
        for ((i, example) in index.examples.withIndex()) {
            if (excludeMeme != NO_MEME && example.memeId == excludeMeme) continue
            val c = FloatBlob.dot(image, example.vector)
            if (skipSamePicture && c >= SAME_IMAGE_COS) continue
            if (c <= topCos[NEIGHBORS - 1]) continue
            var k = NEIGHBORS - 1
            while (k > 0 && topCos[k - 1] < c) {
                topCos[k] = topCos[k - 1]
                topIndex[k] = topIndex[k - 1]
                k--
            }
            topCos[k] = c
            topIndex[k] = i
        }
        return votesFrom(index, topIndex, topCos, 0)
    }

    /** Labels among the neighbours at [from] in [topIndex]/[topCos], by summed cosine, as [rank] returns them. */
    private fun votesFrom(index: Index, topIndex: IntArray, topCos: FloatArray, from: Int): List<Vote> {
        val weight = LinkedHashMap<String, Float>()
        val counts = HashMap<String, Int>()
        val nearest = HashMap<String, Example>()
        val nearestCos = HashMap<String, Float>()
        for (k in 0 until NEIGHBORS) {
            val example = index.examples.getOrNull(topIndex[from + k]) ?: break
            val c = topCos[from + k]
            weight[example.label] = (weight[example.label] ?: 0f) + c
            counts[example.label] = (counts[example.label] ?: 0) + 1
            if (c > (nearestCos[example.label] ?: Float.NEGATIVE_INFINITY)) {
                nearestCos[example.label] = c
                nearest[example.label] = example
            }
        }
        return weight.entries.sortedByDescending { it.value }.map { (label, w) ->
            Vote(label, nearest.getValue(label).category, nearestCos.getValue(label), w, counts.getValue(label))
        }
    }

    /** The winning label when it clears [MIN_COS] and no "not X" example of it is closer. */
    fun winner(index: Index, image: FloatArray, excludeMeme: Long = NO_MEME, skipSamePicture: Boolean = false): Vote? =
        winnerOf(index, rank(index, image, excludeMeme, skipSamePicture), image, skipSamePicture)

    /** [winner] for each of [images], none of them a labeled meme's own picture, computed together through [kernels]. */
    fun winners(index: Index, images: List<FloatArray>, kernels: LookalikeKernels): List<Vote?> =
        winners(index, images, LongArray(images.size) { NO_MEME }, skipSamePicture = false, kernels)

    private fun winners(
        index: Index, images: List<FloatArray>, owners: LongArray, skipSamePicture: Boolean, kernels: LookalikeKernels,
    ): List<Vote?> {
        if (images.isEmpty() || index.examples.isEmpty()) return List(images.size) { null }
        val dim = index.dim
        val queries = FloatArray(images.size * dim)
        for ((i, image) in images.withIndex()) {
            require(image.size == dim) { "vectors must match the examples' size" }
            System.arraycopy(image, 0, queries, i * dim, dim)
        }
        val topIndex = IntArray(images.size * NEIGHBORS)
        val topCos = FloatArray(images.size * NEIGHBORS)
        kernels.nearest(queries, owners, index.flat, index.owners, dim, NEIGHBORS,
            if (skipSamePicture) SAME_IMAGE_COS else Float.POSITIVE_INFINITY, topIndex, topCos)
        return images.indices.map { winnerOf(index, votesFrom(index, topIndex, topCos, it * NEIGHBORS), images[it], skipSamePicture) }
    }

    private fun winnerOf(index: Index, votes: List<Vote>, image: FloatArray, skipSamePicture: Boolean): Vote? {
        val top = votes.firstOrNull() ?: return null
        if (top.cosine < MIN_COS) return null
        return top.takeIf { nearestNegative(index, it.label, image, skipSamePicture) < it.cosine }
    }

    /** Labels of examples showing this same picture, unless a "not X" of that picture says otherwise. */
    fun samePicture(index: Index, image: FloatArray, excludeMeme: Long = NO_MEME): List<Vote> {
        val found = LinkedHashMap<String, Vote>()
        for (example in index.examples) {
            if (excludeMeme != NO_MEME && example.memeId == excludeMeme) continue
            val c = FloatBlob.dot(image, example.vector)
            if (c >= SAME_IMAGE_COS && c > (found[example.label]?.cosine ?: Float.NEGATIVE_INFINITY)) {
                found[example.label] = Vote(example.label, example.category, c, c)
            }
        }
        return found.values.filter { nearestNegative(index, it.label, image, skipSamePicture = false) < it.cosine }
    }

    /** Each label's nearest example, closest first: candidates beyond the five neighbours. */
    fun nearestPerLabel(index: Index, image: FloatArray, excludeMeme: Long = NO_MEME): List<Vote> {
        val best = LinkedHashMap<String, Vote>()
        for (example in index.examples) {
            if (excludeMeme != NO_MEME && example.memeId == excludeMeme) continue
            val c = FloatBlob.dot(image, example.vector)
            if (c > (best[example.label]?.cosine ?: Float.NEGATIVE_INFINITY)) {
                best[example.label] = Vote(example.label, example.category, c, c)
            }
        }
        return best.values.sortedByDescending { it.cosine }
    }

    /** A "not X" example of the vote's label is at least as close as its nearest X example. */
    fun contradicted(index: Index, vote: Vote, image: FloatArray): Boolean =
        nearestNegative(index, vote.label, image, skipSamePicture = false) >= vote.cosine

    /**
     * How a label's held-out votes went. [open] are its misses on memes a person tagged with other
     * labels without rejecting this one: votes nobody judged, since people often tag what a meme shows
     * rather than everything in it. A "Yes" on one turns a miss into a hit.
     */
    class Record(val hits: Int, val misses: Int, val open: List<Open>) {
        val total: Int get() = hits + misses
        val reliable: Boolean get() = total >= RELIABLE_SUPPORT && hits >= RELIABLE_PRECISION * total
    }

    class Open(val memeId: Long, val cosine: Float)

    /**
     * Each voted label's record over people's labeled memes, with each meme's own labels and copies
     * left out: a vote for X on a meme labeled X is a hit; on a meme labeled otherwise or marked
     * "not X" it is a miss.
     */
    fun heldOut(index: Index, labeled: List<LabeledMeme>, kernels: LookalikeKernels): Map<String, Record> {
        val votes = winners(index, labeled.map { it.image }, LongArray(labeled.size) { labeled[it].memeId }, skipSamePicture = true, kernels)
        val hits = HashMap<String, Int>()
        val misses = HashMap<String, Int>()
        val open = HashMap<String, MutableList<Open>>()
        for ((i, meme) in labeled.withIndex()) {
            val vote = votes[i] ?: continue
            when {
                vote.label in meme.labels -> hits.merge(vote.label, 1, Int::plus)
                vote.label in meme.rejected -> misses.merge(vote.label, 1, Int::plus)
                meme.labels.isNotEmpty() -> {
                    misses.merge(vote.label, 1, Int::plus)
                    open.getOrPut(vote.label) { ArrayList() } += Open(meme.memeId, vote.cosine)
                }
            }
        }
        return (hits.keys + misses.keys).associateWith { Record(hits[it] ?: 0, misses[it] ?: 0, open[it].orEmpty()) }
    }

    /** Labels that apply without review: their held-out votes were right often enough. Others stay suggestions. */
    fun reliableLabels(records: Map<String, Record>): Set<String> = records.filterValues { it.reliable }.keys

    private fun nearestNegative(index: Index, label: String, image: FloatArray, skipSamePicture: Boolean): Float {
        var best = Float.NEGATIVE_INFINITY
        for (vector in index.negatives[label].orEmpty()) {
            val c = FloatBlob.dot(image, vector)
            if (skipSamePicture && c >= SAME_IMAGE_COS) continue
            if (c > best) best = c
        }
        return best
    }
}
