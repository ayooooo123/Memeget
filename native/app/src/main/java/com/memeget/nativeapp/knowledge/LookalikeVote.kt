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

    class Index(val examples: List<Example>, val negatives: Map<String, List<FloatArray>>)

    /** A meme a person labeled, for measuring which labels hold up on memes the vote has not seen. */
    class LabeledMeme(val memeId: Long, val image: FloatArray, val labels: Set<String>, val rejected: Set<String>)

    data class Vote(val label: String, val category: String, val cosine: Float, val weight: Float)

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
        val weight = LinkedHashMap<String, Float>()
        val nearest = HashMap<String, Example>()
        val nearestCos = HashMap<String, Float>()
        for (k in 0 until NEIGHBORS) {
            val example = index.examples.getOrNull(topIndex[k]) ?: break
            weight[example.label] = (weight[example.label] ?: 0f) + topCos[k]
            if (topCos[k] > (nearestCos[example.label] ?: Float.NEGATIVE_INFINITY)) {
                nearestCos[example.label] = topCos[k]
                nearest[example.label] = example
            }
        }
        return weight.entries.sortedByDescending { it.value }.map { (label, w) ->
            Vote(label, nearest.getValue(label).category, nearestCos.getValue(label), w)
        }
    }

    /** The winning label when it clears [MIN_COS] and no "not X" example of it is closer. */
    fun winner(index: Index, image: FloatArray, excludeMeme: Long = NO_MEME, skipSamePicture: Boolean = false): Vote? {
        val top = rank(index, image, excludeMeme, skipSamePicture).firstOrNull() ?: return null
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
     * Labels whose votes held up on people's labeled memes with each meme's own labels and
     * copies left out: a vote for X on a meme labeled X is a hit; on a meme labeled otherwise
     * or marked "not X" it is a miss. Labels without enough votes stay suggestions.
     */
    fun reliableLabels(index: Index, labeled: List<LabeledMeme>): Set<String> {
        val hits = HashMap<String, Int>()
        val misses = HashMap<String, Int>()
        for (meme in labeled) {
            val vote = winner(index, meme.image, excludeMeme = meme.memeId, skipSamePicture = true) ?: continue
            when {
                vote.label in meme.labels -> hits.merge(vote.label, 1, Int::plus)
                vote.label in meme.rejected || meme.labels.isNotEmpty() -> misses.merge(vote.label, 1, Int::plus)
            }
        }
        return (hits.keys + misses.keys).filterTo(HashSet()) { label ->
            val right = hits[label] ?: 0
            val total = right + (misses[label] ?: 0)
            total >= RELIABLE_SUPPORT && right >= RELIABLE_PRECISION * total
        }
    }

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
