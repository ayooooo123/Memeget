package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag

internal object SearchText {

    private const val CONTEXT_CONCEPT_LIMIT = 8
    private const val CONTEXT_PHRASE_LIMIT = 24

    fun cleanTerm(term: String): String {
        return term.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    fun normalizedConcept(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

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

    fun assembleSearchText(
        ocr: String,
        name: String,
        caption: String,
        transcript: String,
        tagLabels: List<String>,
        extraTerms: String,
        cloudCaption: String = "",
        cloudTerms: String = "",
    ): String {
        val labels = tagLabels.joinToString("") { " $it" }
        val labelContext = classificationContextTerms(tags = tagLabels)
        return buildString {
            append(ocr)
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
        return "$caption $tagsStr $extraTerms".replace(Regex("\\s+"), " ").trim()
    }

    fun phraseKey(s: String): String {
        return s.lowercase()
            .replace(Regex("['\u2019]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

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

    fun searchTermsForText(queryText: String, keepShort: Boolean = false): List<String> {
        return queryText.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { if (keepShort) it.isNotEmpty() else it.length > 2 }
    }
}
