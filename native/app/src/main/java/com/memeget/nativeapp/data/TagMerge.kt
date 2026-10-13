package com.memeget.nativeapp.data

import com.memeget.nativeapp.Tag

internal object TagMerge {

    private val TAG_RANK = mapOf(
        "manual" to 6,
        "exemplar" to 5,
        "propagated" to 4,
        "ocr" to 3,
        "vision" to 2,
        "prompt" to 1,
        "entity_pack" to 1,
        "cloud" to 1,
    )

    private val DURABLE_SOURCES = setOf(
        "manual",
        "exemplar",
        "propagated",
    )

    fun normalizeTagLabel(label: String): String {
        return label.trim().lowercase()
    }

    fun tagRank(t: Tag): Int {
        if ((t.source == "cloud" || t.source == "cloud_hint") && t.confirmation == "confirmed") return 6
        return TAG_RANK[t.source.lowercase()] ?: 1
    }

    fun isDurableTag(t: Tag): Boolean {
        return DURABLE_SOURCES.contains(t.source.lowercase()) ||
            (t.source == "cloud" || t.source == "cloud_hint") && t.confirmation == "confirmed"
    }

    fun betterTag(a: Tag, b: Tag): Tag {
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

    fun mergeDurableTags(tags: List<Tag>, capAuto: Int = 6): List<Tag> {
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

        val sortedDurable = sortTags(durable)
        val sortedAuto = sortTags(auto).take(capAuto)
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

        val filtered = tags.filter { normalizeTagLabel(it.label) != label }
        val merged = mergeDurableTags(filtered + next)
        val updatedTerms = termsWithLabel(extraTerms, label)
        return Pair(merged, updatedTerms)
    }
}
