package com.memeget.nativeapp.inference

internal object PromptBuilder {
    const val SYSTEM_PROMPT =
        "You are a meme cataloging engine. You look at a single image and describe it for " +
        "search using four labeled lines. Tag both what is literally happening and what the meme " +
        "MEANS and the moment someone would send it. Output ONLY those lines — no prose, no JSON, no " +
        "markdown, no code fences."

    /** libmtmd's image marker: the image goes where it stands in the user prompt. */
    const val IMAGE_MARKER = "<__media__>"

    /**
     * The fixed instructions come before the image and the meme's own hints after it, so the runner keeps the
     * instructions' state from one meme to the next instead of reading them again (about 3 s a frame on the Pixel
     * 5a). Captioned both ways on a Mac with the app's model files, 16 memes came out no worse in this order.
     */
    fun buildUserPrompt(grounding: String): String {
        return buildString {
            append("Describe this meme so it can be found later by search. People search with a SINGLE ")
            append("word for any aspect — a feeling, an action, a character, a format, or the situation ")
            append("they would send it in — so name each aspect explicitly. Reply with EXACTLY these four ")
            append("lines, each starting with the label in caps, and nothing else:\n")
            append("CAPTION: one sentence, <=20 words: the action taking place, the feeling or mood, the situation it is used to react to, and why it is funny\n")
            append("TEXT: text visible in the image, verbatim; leave blank if none\n")
            append("SUBJECTS: comma-separated main people, characters, or objects\n")
            append("TAGS: 6-12 comma-separated lowercase keywords for how a person would SEARCH for this meme.\n")
            append('\n').append(IMAGE_MARKER)
            if (grounding.isNotBlank()) {
                append("\nContext hints:\n").append(grounding).append("\n")
            }
            append("\nNow describe the image. Be concise.")
        }
    }

    fun cleanTranscript(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[♪♫]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
