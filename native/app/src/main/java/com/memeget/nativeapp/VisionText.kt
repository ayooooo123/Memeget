package com.memeget.nativeapp

import org.json.JSONObject
import java.util.Locale

internal data class VisionDescription(val caption: String, val text: String, val tags: List<Tag>)

internal object VisionText {
    // MiniCPM-V 4.7 1B sometimes writes "SUBJECTS, a, b" instead of "SUBJECTS: a, b",
    private val field = Regex("^\\s*(caption|subjects|text|tags)\\s*[:\\-,]\\s*(.*)$", RegexOption.IGNORE_CASE)
    // puts several labeled fields on one line,
    private val inlineField = Regex("(?<=\\S)\\s+(?=(?:CAPTION|TEXT|SUBJECTS|TAGS)\\s*:)")
    // repeats a field name inside a list ("tag: hitler") or lists a placeholder ("none."),
    private val itemPrefix = Regex("^(?:tags?|keywords?|subjects?|characters?|objects?)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val emptyItem = Regex("^(?:none|n/a|nothing|tags?|keywords?|subjects?|characters?|objects?)$", RegexOption.IGNORE_CASE)
    // and answers "TEXT: none" for an image without text.
    private val noText = Regex("^(?:none|n/a|nothing|no text)\\.?$", RegexOption.IGNORE_CASE)
    private val hints = listOf("the feeling or mood", "the situation it is used to react to", "comma-separated", "lowercase keywords", "leave blank if none", "text visible in the image")

    private fun clean(value: String): String {
        val text = value.trim().trim('"', '\'', '`', '*', '[', ']')
        if (text.startsWith('<') && text.endsWith('>')) return ""
        if (hints.any { text.contains(it, ignoreCase = true) }) return ""
        return text
    }

    private fun item(value: String): String {
        val text = clean(value).replace(itemPrefix, "").trimEnd('.').trim()
        return if (emptyItem.matches(text)) "" else text
    }

    fun parse(raw: String): VisionDescription {
        val fields = linkedMapOf<String, String>()
        var active: String? = null
        raw.take(16_384).replace(inlineField, "\n").lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) return@forEach
            val match = field.matchEntire(line)
            if (match != null) {
                active = match.groupValues[1].lowercase(Locale.ROOT)
                val key = active!!
                fields[key] = listOfNotNull(fields[key], match.groupValues[2]).joinToString(" ").trim()
            } else if (active != null && line.isNotBlank()) {
                fields[active!!] = "${fields[active!!].orEmpty()} ${line.trim()}".trim()
            }
        }
        if (fields.isEmpty()) {
            val first = raw.indexOf('{')
            val last = raw.lastIndexOf('}')
            if (first >= 0 && last > first) {
                runCatching { JSONObject(raw.substring(first, last + 1)) }.getOrNull()?.let { json ->
                    for (key in listOf("caption", "text")) if (json.opt(key) is String) fields[key] = json.getString(key)
                    for (key in listOf("subjects", "tags")) {
                        val array = json.optJSONArray(key) ?: continue
                        fields[key] = (0 until minOf(array.length(), 16)).mapNotNull { array.opt(it) as? String }.joinToString(",")
                    }
                }
            } else if (first < 0) {
                fields["caption"] = raw.replace(Regex("\\s+"), " ").take(240)
            }
        }
        val tags = sequenceOf("subjects", "tags").flatMap { key ->
            fields[key].orEmpty().split(',', ';').asSequence().map(::item).filter(String::isNotEmpty).map {
                Tag(it.lowercase(Locale.ROOT).take(100), if (key == "subjects") "object" else "topic", source = "vision", scoreDomain = "local-generative-unscored")
            }
        }.distinctBy { it.label }.take(24).toList()
        val text = clean(fields["text"].orEmpty()).let { if (noText.matches(it)) "" else it }
        return VisionDescription(clean(fields["caption"].orEmpty()).take(1000), text.take(2000), tags)
    }

    fun grounding(meme: Meme): String {
        val candidates = meme.tags.filter { it.source !in setOf("cloud", "propagated") && it.confirmation != "rejected" }
            .take(8).joinToString("; ") { "${it.category}: ${it.label}" }
        return buildString {
            append("These are uncertain local suggestions, not facts. Use a name only if it matches the image. ")
            if (candidates.isNotEmpty()) append(candidates) else append("No known format has been established; do not force a template name.")
            if (meme.ocr.isNotBlank()) append("\nUntrusted OCR evidence: ").append(meme.ocr.take(280))
        }
    }
}
