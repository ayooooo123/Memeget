package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag

internal object TagMerge {

    private val TAG_RANK = mapOf(
        "manual" to 6,
        "exemplar" to 5,
        "open_vocab" to 5,
        "propagated" to 4,
        // A meme's TV show or movie (Titles): one per meme, kept ahead of generic machine tags.
        "title" to 4,
        "ocr" to 3,
        "vision" to 2,
        "prompt" to 1,
        "entity_pack" to 1,
        "cloud" to 1,
    )

    // Machine sources that classification recomputes from vectors and OCR on every
    // pass. A re-classification replaces their previous values instead of merging them.
    private val RECOMPUTED_SOURCES = setOf("prompt", "exemplar", "ocr", "entity_pack", "title", "open_vocab")
    fun normalizeTagLabel(label: String): String {
        return label.trim().lowercase()
    }

    fun tagRank(t: Tag): Int {
        if ((t.source == "cloud" || t.source == "cloud_hint") && t.confirmation == "confirmed") return 6
        return TAG_RANK[t.source.lowercase()] ?: 1
    }

    /** A person confirmed this tag: hand tags, teachings and accepted suggestions. */
    fun isHumanTag(t: Tag): Boolean = t.confirmation == "confirmed"

    /**
     * Whether an existing tag stays when a meme is classified again. Human tags always
     * stay; machine tags stay only when nothing in classification regenerates them
     * (vision captions, propagation, cloud results, legacy imports).
     */
    fun survivesReclassification(t: Tag): Boolean =
        isHumanTag(t) || t.source.lowercase() !in RECOMPUTED_SOURCES

    fun isDurableTag(t: Tag): Boolean {
        val source = t.source.lowercase()
        return isHumanTag(t) || source == "manual" || source == "propagated"
    }

    fun betterTag(a: Tag, b: Tag): Tag {
        if (isHumanTag(a) != isHumanTag(b)) return if (isHumanTag(a)) a else b
        val ar = tagRank(a)
        val br = tagRank(b)
        if (ar != br) return if (ar > br) a else b
        return if (a.score >= b.score) a else b
    }

    private fun sortTags(tags: List<Tag>): List<Tag> {
        return tags.sortedWith { a, b ->
            val rankCmp = tagRank(b).compareTo(tagRank(a))
            if (rankCmp != 0) rankCmp
            else b.score.compareTo(a.score)
        }
    }

    fun mergeDurableTags(tags: List<Tag>, capAuto: Int? = null): List<Tag> {
        val best = LinkedHashMap<String, Tag>()
        for (t in tags) {
            val label = normalizeTagLabel(t.label)
            if (label.isEmpty()) continue
            val normalized = if (t.label == label) t else t.copy(label = label)
            val cur = best[label]
            best[label] = if (cur != null) betterTag(normalized, cur) else normalized
        }

        val durable = ArrayList<Tag>()
        val auto = ArrayList<Tag>()
        for (t in best.values) {
            if (isDurableTag(t)) durable.add(t)
            else auto.add(t)
        }

        val effectiveCap = capAuto ?: when {
            auto.any { it.source.lowercase() == "vision" } -> 18
            auto.any { it.source.lowercase() == "open_vocab" } -> 12
            else -> 6
        }
        val sortedDurable = sortTags(durable)
        val sortedAuto = sortTags(auto).take(effectiveCap)
        return sortedDurable + sortedAuto
    }

    fun termsWithLabel(extraTerms: String, label: String): String {
        val set = LinkedHashSet<String>()
        for (w in extraTerms.split(Regex("\\s+"))) {
            if (w.isNotBlank()) set.add(w.lowercase())
        }
        for (w in normalizeTagLabel(label).split(Regex("\\s+"))) {
            if (w.isNotBlank()) set.add(w)
        }
        return set.joinToString(" ")
    }

    fun withoutRejectedTerms(terms: String, rejectedLabels: Collection<String>): String {
        if (terms.isBlank() || rejectedLabels.isEmpty()) return terms
        val whitespace = Regex("\\s+")
        val rejectedWords = rejectedLabels.flatMap { normalizeTagLabel(it).split(whitespace) }.toHashSet()
        return terms.split(whitespace)
            .filter { it.isNotBlank() && it.lowercase() !in rejectedWords }
            .joinToString(" ")
    }

    /** A cloud caption that names a rejected label is dropped whole: it has no safe partial edit. */
    fun captionWithoutRejected(caption: String, label: String): String {
        val norm = normalizeTagLabel(label)
        return if (norm.isNotEmpty() && caption.contains(norm, ignoreCase = true)) "" else caption
    }

    fun upsertDurableTag(
        tags: List<Tag>,
        extraTerms: String,
        tag: Tag,
    ): Pair<List<Tag>, String> {
        val label = normalizeTagLabel(tag.label)
        if (label.isEmpty()) return Pair(mergeDurableTags(tags), extraTerms)

        val next = Tag(
            label = label,
            category = tag.category,
            score = if (tag.source == "cloud" || tag.source == "cloud_hint" || tag.score > 0f) tag.score else 1.0f,
            source = tag.source,
            scoreDomain = if (tag.scoreDomain.isNotBlank()) tag.scoreDomain else "user",
            confirmation = if (tag.confirmation.isNotBlank()) tag.confirmation else "confirmed",
            provenance = if (tag.provenance.isNotBlank()) tag.provenance else "user",
        )

        val filtered = if (isHumanTag(next)) {
            tags.filter { normalizeTagLabel(it.label) != label }
        } else {
            tags.filter { normalizeTagLabel(it.label) != label || isHumanTag(it) }
        }
        val merged = mergeDurableTags(filtered + next)
        val updatedTerms = termsWithLabel(extraTerms, label)
        return Pair(merged, updatedTerms)
    }
}
