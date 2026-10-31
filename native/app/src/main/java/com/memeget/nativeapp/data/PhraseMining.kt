package com.memeget.nativeapp.data

import kotlin.math.ln

/** A line said or written in more than one meme, normalized as search matches phrases ([SearchText.phraseKey]). */
internal data class SharedPhrase(val phrase: String, val memeIds: List<Long>)

/** Finds lines that recur across memes' texts, so each can be a folder: the words people remember a meme by. */
internal object PhraseMining {
    // Words too common to make a line memorable on their own: a phrase needs at least one word outside this set.
    private val FILLER = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "dont", "for", "from", "get", "go",
        "going", "got", "have", "he", "her", "him", "his", "how", "i", "if", "im", "in", "is", "it", "its", "just",
        "know", "like", "me", "my", "no", "not", "of", "oh", "ok", "okay", "on", "or", "out", "so", "that", "thats",
        "the", "them", "then", "there", "they", "this", "to", "up", "uh", "um", "was", "we", "well", "what", "when",
        "with", "yeah", "yes", "you", "your", "youre",
    )
    /** Texts sharing more of their words than this are one meme saved twice, not two memes quoting a line. */
    private const val SAME_CLIP_OVERLAP = 0.8
    // A short line ending on one of these is a fragment ("the end of", "years old and"), not something said.
    private val DANGLING = setOf("a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "of", "on", "or", "the", "to", "with")
    // Site names stamped on images ("history com", "imgflip com") repeat across memes but are nobody's words.
    private val WATERMARK = setOf("com", "net", "org", "www", "http", "https")

    /**
     * Phrases of at least [minWords] words found in at least [minMemes] different memes. Word runs up to [maxWords]
     * long are matched; overlapping runs shared by the same memes are joined, so a line shows up once, whole.
     *
     * A line's rarity is the sum of its words' inverse document frequency across [texts]. Fragments of everyday
     * speech ("a lot of", "do you think") recur everywhere but score low; lines below [minRarity] are dropped.
     * Best first: memes times rarity, so a memorable line beats a merely frequent one.
     */
    fun sharedPhrases(
        texts: List<Pair<Long, String>>,
        minMemes: Int = 2,
        minWords: Int = 3,
        maxWords: Int = 8,
        minRarity: Double = 5.0,
        limit: Int = 40,
    ): List<SharedPhrase> {
        val words = HashMap<Long, List<String>>(texts.size)
        for ((id, text) in texts) {
            val tokens = SearchText.phraseKey(text).split(' ').filter { it.isNotEmpty() }
            if (tokens.size >= minWords) words[id] = tokens
        }
        val holders = HashMap<String, MutableSet<Long>>()
        for ((id, tokens) in words) {
            val seen = HashSet<String>()
            for (start in tokens.indices) {
                for (n in minWords..maxWords) {
                    if (start + n > tokens.size) break
                    val gram = tokens.subList(start, start + n)
                    if (gram.all { it in FILLER }) continue
                    val key = gram.joinToString(" ")
                    if (seen.add(key)) holders.getOrPut(key) { HashSet() } += id
                }
            }
        }
        val wordSets = HashMap<Long, Set<String>>()
        fun wordSet(id: Long) = wordSets.getOrPut(id) { words.getValue(id).toHashSet() }
        // Memes that share most of their words count once: re-uploads of one clip all "quote" each other.
        fun distinctMemes(ids: Collection<Long>): Int {
            val kept = ArrayList<Long>()
            for (id in ids.sorted()) {
                val mine = wordSet(id)
                val repeat = kept.any { other ->
                    val theirs = wordSet(other)
                    val shared = mine.count { it in theirs }
                    shared.toDouble() / minOf(mine.size, theirs.size) > SAME_CLIP_OVERLAP
                }
                if (!repeat) kept += id
            }
            return kept.size
        }
        val distinctByMemes = HashMap<List<Long>, Int>()
        val groups = holders.entries
            .filter { it.value.size >= minMemes }
            .groupBy({ it.value.sorted() }, { it.key })
            .filterKeys { ids -> distinctByMemes.getOrPut(ids) { distinctMemes(ids) } >= minMemes }
        // Join each group's runs into whole lines, read off the first meme's words: runs shared by exactly the same
        // memes that overlap by two words or more are one line cut into pieces by the matching window.
        val lines = groups.flatMap { (ids, phrases) ->
            val tokens = words.getValue(ids.first())
            val spans = phrases.flatMap { phrase ->
                val gram = phrase.split(' ')
                (0..tokens.size - gram.size).filter { tokens.subList(it, it + gram.size) == gram }.map { it until it + gram.size }
            }.sortedBy { it.first }
            val merged = ArrayList<IntRange>()
            for (span in spans) {
                val last = merged.lastOrNull()
                if (last != null && span.first <= last.last - 1) merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
                else merged += span
            }
            merged.map { Triple(tokens.subList(it.first, it.last + 1).joinToString(" "), ids, distinctByMemes.getValue(ids)) }
        }.distinctBy { it.first }

        val docFreq = HashMap<String, Int>()
        for (tokens in words.values) tokens.toHashSet().forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 }
        fun rarity(line: String) = line.split(' ').sumOf { ln(words.size.toDouble() / (docFreq[it] ?: 1)) }
        val ranked = lines
            .filter { (line) ->
                val lineWords = line.split(' ')
                lineWords.none { it in WATERMARK } && !(lineWords.size <= 3 && lineWords.last() in DANGLING)
            }
            .map { (line, ids, memes) -> Triple(SharedPhrase(line, ids), memes, rarity(line)) }
            .filter { it.third >= minRarity }
            .sortedWith(compareByDescending<Triple<SharedPhrase, Int, Double>> { it.second * it.third }.thenBy { it.first.phrase })
        // A line inside a kept line, found in at most one more meme ("the lion does not" beside "the lion does not
        // concern himself with"), would be the same folder twice.
        val kept = ArrayList<SharedPhrase>()
        for ((phrase) in ranked) {
            if (kept.size == limit) break
            val within = kept.any { other ->
                " ${other.phrase} ".contains(" ${phrase.phrase} ") && phrase.memeIds.count { it !in other.memeIds } <= 1
            }
            if (!within) kept += phrase
        }
        return kept
    }
}
