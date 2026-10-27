package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag

internal object SearchText {

    private const val CONTEXT_CONCEPT_LIMIT = 8
    private const val CONTEXT_PHRASE_LIMIT = 24
    private val WORD_REGEX = Regex("[a-z0-9]+")
    private val APOSTROPHE_REGEX = Regex("['\u2019]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    // Dry little filler words we strip out of multi-word queries so 2-letter filth like "ai", "tv", "fr", "gm", and "up" stays wet and live on both passes.
    private val MULTI_WORD_STOPWORDS = setOf(
        "a", "i", "an", "as", "at", "be", "by", "do", "go", "he", "if",
        "in", "is", "it", "me", "my", "of", "on", "or", "so", "to", "us", "we", "the",
    )
    fun cleanTerm(term: String): String {
        return term.trim().lowercase().replace(WHITESPACE_REGEX, " ")
    }

    /**
     * The `[a-z0-9]+` runs of [s] lowercased, joined by single spaces; with [dropApostrophes], `'` and `’` vanish
     * instead of splitting a word ("I'm" → "im"). One pass, no regex: the resident index runs this dozens of times
     * per meme, and per-call Regex compilation cost ~2.2 s of a 2,344-meme cold index build on a Pixel 5a (~80 ms now).
     */
    private fun alnumRuns(s: String, dropApostrophes: Boolean): String {
        val lower = s.lowercase()
        val out = StringBuilder(lower.length)
        var pendingSpace = false
        for (c in lower) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(c)
            } else if (!(dropApostrophes && (c == '\'' || c == '\u2019'))) {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    fun normalizedConcept(raw: String): String = alnumRuns(raw, dropApostrophes = false)

    private fun addUniqueTerm(out: MutableList<String>, seen: MutableSet<String>, raw: String) {
        val term = normalizedConcept(raw)
        if (term.isEmpty() || seen.contains(term)) return
        seen.add(term)
        out.add(term)
    }

    fun classificationContextTerms(
        tags: List<String>,
        subjects: List<String> = emptyList(),
        caption: String = "",
    ): String {
        val concepts = ArrayList<String>()
        val seen = HashSet<String>()
        for (term in tags + subjects) {
            addUniqueTerm(concepts, seen, term)
            if (concepts.size >= CONTEXT_CONCEPT_LIMIT) break
        }

        val context = ArrayList<String>()
        val contextSeen = HashSet<String>()
        val headConcepts = concepts.take(5)
        for (tag in headConcepts) {
            for (other in concepts) {
                if (tag == other) continue
                addUniqueTerm(context, contextSeen, "$tag $other")
                if (context.size >= CONTEXT_PHRASE_LIMIT) return context.joinToString(" ")
            }
        }

        val normCaption = normalizedConcept(caption)
        val captionWords = normCaption.split(' ').filter { it.length > 3 }
        for (i in 0 until captionWords.size - 1) {
            addUniqueTerm(context, contextSeen, "${captionWords[i]} ${captionWords[i + 1]}")
            if (context.size >= CONTEXT_PHRASE_LIMIT) break
        }
        return context.joinToString(" ")
    }

    fun mergeOcrTexts(primaryOcr: String, vlmOcr: String): String {
        val p = primaryOcr.trim()
        val v = vlmOcr.trim()
        if (v.isEmpty()) return p
        if (p.isEmpty()) return v
        val normP = phraseKey(p)
        val extraLines = v.lineSequence()
            .map { it.trim() }
            .filter { line ->
                val key = phraseKey(line)
                key.isNotEmpty() && !containsPhrase(normP, key)
            }
            .toList()
        return if (extraLines.isEmpty()) p else "$p\n${extraLines.joinToString("\n")}"
    }

    fun assembleSearchText(
        ocr: String,
        name: String,
        caption: String,
        transcript: String,
        tagLabels: List<String>,
        extraTerms: String,
        cloudCaption: String = "",
        cloudTerms: String = "",
        vlmOcr: String = "",
    ): String {
        val labels = tagLabels.joinToString("") { " $it" }
        val labelContext = classificationContextTerms(tags = tagLabels)
        return buildString {
            append(ocr)
            if (vlmOcr.isNotBlank()) {
                append(' ')
                append(vlmOcr)
            }
            append(' ')
            append(name)
            append(' ')
            append(caption)
            append(' ')
            append(transcript)
            append(labels)
            append(' ')
            append(extraTerms)
            append(' ')
            append(labelContext)
            if (cloudCaption.isNotBlank()) {
                append(' ')
                append(cloudCaption)
            }
            if (cloudTerms.isNotBlank()) {
                append(' ')
                append(cloudTerms)
            }
        }.lowercase()
    }

    fun captionSearchText(caption: String, tags: List<Tag>, extraTerms: String): String {
        val tagsStr = tags.joinToString(" ") { it.label }
        return "$caption $tagsStr $extraTerms".replace(WHITESPACE_REGEX, " ").trim()
    }

    fun phraseKey(s: String): String = alnumRuns(s, dropApostrophes = true)

    fun phraseTokenCount(key: String): Int {
        if (key.isBlank()) return 0
        return key.split(' ').filter { it.isNotBlank() }.size
    }

    fun containsPhrase(haystackKey: String, queryKey: String): Boolean {
        if (queryKey.isBlank()) return false
        val paddedHaystack = " $haystackKey "
        val paddedQuery = " $queryKey "
        return paddedHaystack.contains(paddedQuery)
    }

    fun wordTokenSet(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        val out = HashSet<String>()
        addAlnumRuns(lower, dropApostrophes = false, out)
        if (lower.indexOf('\'') >= 0 || lower.indexOf('\u2019') >= 0) addAlnumRuns(lower, dropApostrophes = true, out)
        return out
    }

    /** Adds each `[a-z0-9]+` run of the already-lowercased [lower] to [out]; see [alnumRuns] for why not a Regex. */
    private fun addAlnumRuns(lower: String, dropApostrophes: Boolean, out: MutableSet<String>) {
        val token = StringBuilder()
        for (c in lower) {
            if (c in 'a'..'z' || c in '0'..'9') {
                token.append(c)
            } else if (!(dropApostrophes && (c == '\'' || c == '\u2019')) && token.isNotEmpty()) {
                out.add(token.toString())
                token.setLength(0)
            }
        }
        if (token.isNotEmpty()) out.add(token.toString())
    }

    fun searchTermsForText(queryText: String, keepShort: Boolean = false): List<String> {
        if (queryText.isBlank()) return emptyList()
        val collapsed = APOSTROPHE_REGEX.replace(queryText.lowercase(), "")
        val minLen = if (keepShort) 1 else 2
        val raw = WORD_REGEX.findAll(collapsed)
            .map { it.value }
            .filter { it.length >= minLen }
            .distinct()
            .toList()
        if (raw.size <= 1 || keepShort) return raw
        val meaningful = raw.filter { it !in MULTI_WORD_STOPWORDS }
        return meaningful.ifEmpty { raw }
    }
}
