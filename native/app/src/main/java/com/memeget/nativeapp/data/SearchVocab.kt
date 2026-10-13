package com.memeget.nativeapp.data

import kotlin.math.abs
import kotlin.math.min

internal object FuzzySearch {

    fun typoBudget(len: Int): Int {
        if (len <= 3) return 0
        if (len <= 6) return 1
        return 2
    }

    fun boundedEditDistance(a: String, b: String, maxDist: Int): Int {
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > maxDist) return maxDist + 1
        if (la == 0) return if (lb <= maxDist) lb else maxDist + 1
        if (lb == 0) return if (la <= maxDist) la else maxDist + 1

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val d = min(prev[j] + 1, min(curr[j - 1] + 1, prev[j - 1] + cost))
                curr[j] = d
                if (d < rowMin) rowMin = d
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return if (prev[lb] <= maxDist) prev[lb] else maxDist + 1
    }
}

internal class SearchVocab {

    private data class BkNode(val term: String, val children: MutableMap<Int, BkNode> = HashMap())
    data class Hit(val term: String, val dist: Int, val freq: Int)

    private var root: BkNode? = null
    private val sorted = ArrayList<String>()
    private val freq = HashMap<String, Int>()

    val size: Int get() = freq.size

    fun has(term: String): Boolean = freq.containsKey(term)

    fun addTexts(texts: Iterable<String>) {
        val counts = HashMap<String, Int>()
        for (text in texts) {
            for (match in tokenRegex.findAll(text.lowercase())) {
                val tok = match.value
                if (tok.length >= 2) {
                    counts[tok] = (counts[tok] ?: 0) + 1
                }
            }
        }
        for ((term, count) in counts) {
            val prev = freq[term]
            freq[term] = (prev ?: 0) + count
            if (prev != null) continue

            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < term) lo = mid + 1
                else hi = mid
            }
            sorted.add(lo, term)
            insert(term)
        }
    }

    private fun insert(term: String) {
        val r = root
        if (r == null) {
            root = BkNode(term)
            return
        }
        var node: BkNode = r
        while (true) {
            val d = FuzzySearch.boundedEditDistance(term, node.term, term.length + node.term.length)
            if (d == 0) return
            val child = node.children[d]
            if (child == null) {
                node.children[d] = BkNode(term)
                return
            }
            node = child
        }
    }

    fun search(term: String, maxDist: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val r = root ?: return out
        val stack = ArrayDeque<BkNode>()
        stack.add(r)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val d = FuzzySearch.boundedEditDistance(term, node.term, term.length + node.term.length)
            if (d <= maxDist) {
                out.add(Hit(node.term, d, freq[node.term] ?: 0))
            }
            val lo = d - maxDist
            val hi = d + maxDist
            for ((dist, child) in node.children) {
                if (dist in lo..hi) {
                    stack.add(child)
                }
            }
        }
        return out
    }

    fun corrections(term: String, limit: Int = 2): List<String> {
        if (freq.containsKey(term)) return emptyList()
        val budget = FuzzySearch.typoBudget(term.length)
        if (budget == 0) return emptyList()
        return search(term, budget)
            .filter { it.term != term }
            .sortedWith(compareBy({ it.dist }, { -it.freq }, { it.term }))
            .take(limit)
            .map { it.term }
    }

    fun prefixMatches(prefix: String, limit: Int = 5): List<String> {
        if (prefix.length < 2) return emptyList()
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1
            else hi = mid
        }
        val matches = ArrayList<String>()
        for (i in lo until sorted.size) {
            val t = sorted[i]
            if (!t.startsWith(prefix)) break
            if (t != prefix) matches.add(t)
        }
        return matches
            .sortedWith(compareBy({ -(freq[it] ?: 0) }, { it }))
            .take(limit)
    }

    fun expand(terms: List<String>, lastIsPrefix: Boolean = false): List<String> {
        val seen = HashSet(terms)
        val out = ArrayList<String>()
        val add = { t: String ->
            if (!seen.contains(t)) {
                seen.add(t)
                out.add(t)
            }
        }
        for (i in terms.indices) {
            val term = terms[i]
            for (c in corrections(term)) add(c)
            if (lastIsPrefix && i == terms.size - 1) {
                for (p in prefixMatches(term)) add(p)
            }
        }
        return out
    }

    companion object {
        private val tokenRegex = Regex("[a-z0-9]+")

        fun build(texts: Iterable<String>): SearchVocab {
            val vocab = SearchVocab()
            vocab.addTexts(texts)
            return vocab
        }
    }
}
