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

/**
 * The library's words and how often each occurs, for typo corrections and prefix completions.
 * Built from counts the full-text index already keeps, so nothing re-reads or re-tokenizes memes.
 */
internal class SearchVocab private constructor(private val freq: Map<String, Int>) {

    private val sorted: List<String> = freq.keys.sorted()
    /** Words grouped by length: a correction only differs in length by at most its edit budget. */
    private val byLength: Map<Int, List<String>> = freq.keys.groupBy { it.length }

    val size: Int get() = freq.size

    fun has(term: String): Boolean = freq.containsKey(term)

    /** Up to [limit] known words within the term's edit budget: nearest first, then most frequent, then alphabetical. */
    fun corrections(term: String, limit: Int = 2): List<String> {
        if (freq.containsKey(term)) return emptyList()
        val budget = FuzzySearch.typoBudget(term.length)
        if (budget == 0) return emptyList()
        data class Hit(val term: String, val dist: Int, val freq: Int)
        val hits = ArrayList<Hit>()
        for (len in term.length - budget..term.length + budget) {
            for (candidate in byLength[len] ?: continue) {
                val d = FuzzySearch.boundedEditDistance(term, candidate, budget)
                if (d <= budget) hits += Hit(candidate, d, freq[candidate] ?: 0)
            }
        }
        return hits
            .sortedWith(compareBy<Hit>({ it.dist }, { -it.freq }, { it.term }))
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
        private val wordRegex = Regex("[a-z0-9]{2,}")

        /** Words of two or more ASCII letters or digits, with how often each occurs; others are ignored. */
        fun fromCounts(counts: Map<String, Int>): SearchVocab =
            SearchVocab(counts.filterKeys { wordRegex.matches(it) })
    }
}
