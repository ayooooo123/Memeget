package com.memeget.nativeapp.data

import java.util.Locale

/**
 * Bridges online reply slang, reaction beats, and noisy pasted social-media posts into sharp lexical
 * and visual search signals so Master pulls the right reply meme mid-thread without rewording a thing.
 */
internal object SocialReplyIntent {

    data class ReactionCue(
        val synonyms: Set<String>,
        val visualBridge: String? = null,
    )

    data class DistilledQuery(
        val cleanedText: String,
        val embedText: String,
        val exactTerms: List<String>,
        val socialTerms: Set<String>,
        val isPastedPost: Boolean,
        val pureSlangReaction: Boolean = false,
    )

    private val URL_REGEX = Regex("""https?://\S+|www\.\S+|\b(?:x|twitter|t|reddit|tiktok|instagram)\.com/\S+""", RegexOption.IGNORE_CASE)
    private val HANDLE_REGEX = Regex("""@[A-Za-z0-9_]{1,32}""")
    private val HASHTAG_PREFIX_REGEX = Regex("""#(?=[A-Za-z0-9_])""")
    private val SOCIAL_CHROME_REGEX = Regex(
        """\b(?:reposted|retweets?|replying to|quote tweet|translated from \w+|views|bookmarks)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Conversational fluff stripped when someone pastes or selects a full social-media post (`>= 7` words).
    private val POST_FLUFF_WORDS = setOf(
        "the", "and", "for", "that", "this", "with", "from", "have", "has", "had", "not", "but", "what",
        "all", "were", "when", "your", "can", "said", "there", "use", "each", "which", "she", "how",
        "their", "will", "other", "about", "out", "many", "then", "them", "these", "some", "her", "would",
        "make", "like", "him", "into", "time", "look", "two", "more", "go", "see", "no", "way", "could",
        "my", "than", "first", "been", "call", "who", "its", "now", "find", "long", "down", "day", "did",
        "get", "come", "made", "may", "part", "just", "bro", "dude", "man", "literally", "actually",
        "honestly", "really", "very", "still", "even", "also", "only", "going", "gonna", "wanna", "gotta",
        "think", "know", "people", "someone", "everyone", "anyone", "something", "anything", "nothing",
        "because", "while", "after", "before", "where", "being", "getting", "doing", "saying", "making",
        "cant", "dont", "doesnt", "didnt", "isnt", "arent", "wasnt", "werent", "wont", "wouldnt", "shouldnt",
        "youre", "theyre", "were", "hes", "shes", "ive", "youve", "weve", "im", "rt", "via", "lmao", "lmfao",
        "lol", "omg", "ngl", "tbh", "imo", "imho", "idk", "smh", "irl",
    )

    // Pure visual-action filler participles humans type ("looking at phone disgusted") whose IDF weight is
    // lightly discounted (* 0.22) so payload nouns/adjectives ("phone", "disgusted") clear the 0.82 coverage bar,
    // without ever discounting identity nouns like "man" or "guy" ("family guy", "florida man", "chainsaw man").
    val SCAFFOLD_WORDS = setOf(
        "looking", "reacting", "feeling", "trying", "getting", "making", "doing", "being", "having", "showing",
    )

    // 1-word cues that also appear inside ordinary proper nouns/phrases ("real estate", "twin peaks", "no cap");
    // only allowed to fire as reaction slang on short 1-2 word reaction searches.
    private val SHORT_QUERY_ONLY_UNIGRAMS = setOf("real", "cap", "peak", "drama", "waiting", "based", "clown", "doubt")

    // Opaque internet idioms whose literal tokens ("cooked", "too early") mean something unrelated in dictionary English.
    private val OPAQUE_SLANG_PHRASES = setOf(
        "cooked", "im cooked", "we are cooked", "were cooked",
        "joever", "its joever", "its over",
        "celebrating too early", "premature celebration",
        "yap", "yapping", "glazing", "mald", "seethe",
    )

    // Maps online reply slang & conversational moments to caption/tag vocabulary + optional CLIP visual scene prompt.
    private val REACTION_CUES: Map<String, ReactionCue> = mapOf(
        "cooked" to ReactionCue(
            setOf("defeated", "doomed", "despair", "exhausted", "crying", "sad", "finished", "over", "stressed", "head in hands"),
            "exhausted defeated person holding head in hands in despair",
        ),
        "im cooked" to ReactionCue(
            setOf("defeated", "doomed", "despair", "exhausted", "crying", "sad", "stressed"),
            "exhausted defeated person holding head in hands in despair",
        ),
        "we are cooked" to ReactionCue(
            setOf("defeated", "doomed", "despair", "exhausted", "crying", "sad", "stressed"),
            "exhausted defeated person holding head in hands in despair",
        ),
        "were cooked" to ReactionCue(
            setOf("defeated", "doomed", "despair", "exhausted", "crying", "sad", "stressed"),
            "exhausted defeated person holding head in hands in despair",
        ),
        "joever" to ReactionCue(
            setOf("over", "defeated", "doomed", "despair", "sad", "crying", "finished", "doomer"),
            "sad defeated person looking down in despair",
        ),
        "its joever" to ReactionCue(
            setOf("over", "defeated", "doomed", "despair", "sad", "crying", "doomer"),
            "sad defeated person looking down in despair",
        ),
        "its over" to ReactionCue(
            setOf("defeated", "doomed", "despair", "sad", "crying", "doomer", "wojak", "finished"),
            "sad defeated person staring down in despair",
        ),
        "we are back" to ReactionCue(
            setOf("celebrating", "victory", "cheering", "excited", "hyped", "winning", "happy", "triumph"),
            "excited celebration cheering with arms raised in victory",
        ),
        "were back" to ReactionCue(
            setOf("celebrating", "victory", "cheering", "excited", "hyped", "winning", "happy"),
            "excited celebration cheering with arms raised in victory",
        ),
        "so back" to ReactionCue(
            setOf("celebrating", "victory", "cheering", "excited", "hyped", "winning"),
            "excited celebration cheering with arms raised in victory",
        ),
        "copium" to ReactionCue(
            setOf("cope", "coping", "denial", "crying", "mask", "wojak", "delusion", "sad"),
            "crying wojak behind a smiling mask inhaling copium",
        ),
        "cope" to ReactionCue(
            setOf("copium", "coping", "denial", "crying", "mask", "wojak", "delusion"),
            "crying behind a smiling mask in denial",
        ),
        "seethe" to ReactionCue(
            setOf("angry", "rage", "furious", "screaming", "crying", "wojak", "mad"),
            "furious screaming angry face with veins popping",
        ),
        "mald" to ReactionCue(
            setOf("angry", "rage", "bald", "furious", "screaming", "crying", "wojak"),
            "furious screaming angry face",
        ),
        "let him cook" to ReactionCue(
            setOf("cook", "cooking", "chef", "kitchen", "wait", "hold", "fire"),
            "person cooking in a kitchen pointing",
        ),
        "cinema" to ReactionCue(
            setOf("scorsese", "masterpiece", "applause", "clapping", "peak", "hands", "movie"),
            "older man in suit holding both hands up absolute cinema",
        ),
        "absolute cinema" to ReactionCue(
            setOf("scorsese", "cinema", "masterpiece", "applause", "peak", "hands"),
            "older man in suit holding both hands up absolute cinema",
        ),
        "peak" to ReactionCue(
            setOf("cinema", "masterpiece", "applause", "crying", "beautiful", "impressed"),
        ),
        "yap" to ReactionCue(
            setOf("talking", "rambling", "nerd", "pointing", "shut", "speech", "annoying", "blah"),
            "person talking nonstop gesturing wildly",
        ),
        "yapping" to ReactionCue(
            setOf("talking", "rambling", "nerd", "pointing", "shut", "speech", "annoying"),
            "person talking nonstop gesturing wildly",
        ),
        "glazing" to ReactionCue(
            setOf("praising", "worship", "kneeling", "fanboy", "compliment", "obsessed"),
        ),
        "cap" to ReactionCue(
            setOf("doubt", "skeptical", "lying", "liar", "suspicious", "squinting", "bullshit"),
            "skeptical squinting side eye expression",
        ),
        "no cap" to ReactionCue(
            setOf("truth", "facts", "serious", "nodding", "real", "agree"),
        ),
        "doubt" to ReactionCue(
            setOf("skeptical", "squinting", "suspicious", "disbelief", "stare", "detective"),
            "skeptical man in suit squinting in disbelief",
        ),
        "sus" to ReactionCue(
            setOf("suspicious", "side", "eye", "squinting", "staring", "caught", "shocked"),
            "suspicious side eye stare",
        ),
        "side eye" to ReactionCue(
            setOf("suspicious", "glancing", "awkward", "staring", "skeptical", "puppet"),
            "awkward puppet looking sideways side eye",
        ),
        "caught in 4k" to ReactionCue(
            setOf("camera", "caught", "busted", "shocked", "exposed", "suspicious", "photograph"),
            "person holding camera taking photo shocked",
        ),
        "popcorn" to ReactionCue(
            setOf("watching", "drama", "eating", "theater", "entertainment", "smiling"),
            "person eating popcorn watching drama",
        ),
        "drama" to ReactionCue(
            setOf("popcorn", "watching", "fighting", "chaos", "argument", "entertainment"),
        ),
        "aged like milk" to ReactionCue(
            setOf("clown", "wrong", "backfired", "ironic", "foolish", "laughing"),
        ),
        "clown" to ReactionCue(
            setOf("makeup", "circus", "foolish", "bozo", "dumb", "wig"),
            "person putting on clown makeup in mirror",
        ),
        "bozo" to ReactionCue(
            setOf("clown", "laughing", "smoking", "fool", "pointing", "rip"),
        ),
        "skill issue" to ReactionCue(
            setOf("smug", "laughing", "pointing", "grin", "mocking", "superior"),
        ),
        "touch grass" to ReactionCue(
            setOf("computer", "desk", "screen", "outside", "grass", "sunlight", "basement", "nerd"),
        ),
        "rent free" to ReactionCue(
            setOf("obsessed", "thinking", "head", "mind", "crazy", "angry"),
        ),
        "delusional" to ReactionCue(
            setOf("crazy", "conspiracy", "board", "clown", "copium", "insane"),
            "wild-eyed man pointing at conspiracy string board",
        ),
        "conspiracy" to ReactionCue(
            setOf("charlie", "board", "string", "explaining", "crazy", "pointing"),
            "wild-eyed man pointing at red string conspiracy board",
        ),
        "literally me" to ReactionCue(
            setOf("gosling", "lonely", "staring", "night", "doomer", "sad", "drive", "blade runner"),
            "lonely man staring blankly under neon lights at night",
        ),
        "real" to ReactionCue(
            setOf("true", "facts", "nodding", "gosling", "sad", "agree"),
        ),
        "based" to ReactionCue(
            setOf("chad", "gigachad", "nodding", "king", "sigma", "approve", "smug"),
            "muscular gigachad jawline portrait",
        ),
        "cringe" to ReactionCue(
            setOf("disgusted", "grimace", "wincing", "uncomfortable", "awkward", "gross"),
            "person wincing with disgusted grimace expression",
        ),
        "disgusted" to ReactionCue(
            setOf("gross", "grimace", "shocked", "horrified", "phone", "staring", "cringe"),
            "person looking at phone with disgusted horrified face",
        ),
        "bruh" to ReactionCue(
            setOf("unimpressed", "blank", "stare", "deadpan", "disappointed", "tired", "sigh"),
            "unimpressed deadpan blank stare face",
        ),
        "wtf" to ReactionCue(
            setOf("confused", "shocked", "disgusted", "staring", "bewildered", "what"),
            "confused shocked person staring in disbelief",
        ),
        "huh" to ReactionCue(
            setOf("confused", "blinking", "squinting", "bewildered", "question", "cat"),
            "confused squinting blinking expression",
        ),
        "nervous" to ReactionCue(
            setOf("sweating", "anxious", "panic", "worried", "scared", "awkward"),
            "nervous person sweating heavily",
        ),
        "sweating" to ReactionCue(
            setOf("nervous", "anxious", "panic", "worried", "towel", "buttons"),
            "nervous man sweating profusely",
        ),
        "plotting" to ReactionCue(
            setOf("evil", "rubbing", "hands", "scheming", "grin", "smirk", "sinister"),
            "man rubbing hands together with sneaky grin",
        ),
        "this is fine" to ReactionCue(
            setOf("fire", "burning", "dog", "coffee", "chaos", "disaster", "calm"),
            "cartoon dog sitting in room on fire drinking coffee",
        ),
        "waiting" to ReactionCue(
            setOf("skeleton", "bench", "old", "bored", "waiting", "forever"),
            "skeleton sitting on bench waiting",
        ),
        "salute" to ReactionCue(
            setOf("respect", "soldier", "crying", "honor", "farewell"),
            "person saluting with tears of respect",
        ),
        "shut up" to ReactionCue(
            setOf("silence", "quiet", "pointing", "angry", "stop", "talking"),
        ),
        "who asked" to ReactionCue(
            setOf("unimpressed", "bored", "nobody", "care", "stare", "silence"),
        ),
        "live reaction" to ReactionCue(
            setOf("staring", "camera", "blank", "reaction", "portrait"),
        ),
        "celebrating too early" to ReactionCue(
            setOf("champagne", "medal", "award", "celebrating", "toast", "cheering", "tuxedo"),
            "over the top champagne celebration medal cheering",
        ),
        "premature celebration" to ReactionCue(
            setOf("champagne", "medal", "award", "celebrating", "toast", "cheering"),
            "over the top champagne celebration medal cheering",
        ),
        "locked in" to ReactionCue(
            setOf("leaning", "focused", "gamer", "serious", "staring", "intense"),
            "gamer leaning forward intensely focused",
        ),
        "thousand yard stare" to ReactionCue(
            setOf("traumatized", "stare", "staring", "soldier", "haunted", "shocked"),
            "traumatized soldier thousand yard stare",
        ),
    )

    fun distill(rawQuery: String, keepShort: Boolean = false): DistilledQuery {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            return DistilledQuery("", "", emptyList(), emptySet(), false)
        }

        // Strip links, @handles, social chrome, and unwrap #hashtags while preserving words.
        val cleaned = trimmed
            .replace(URL_REGEX, " ")
            .replace(HANDLE_REGEX, " ")
            .replace(HASHTAG_PREFIX_REGEX, "")
            .replace(SOCIAL_CHROME_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { trimmed }

        val normPhrase = SearchText.normalizedConcept(cleaned)
        val rawWords = normPhrase.split(' ').filter { it.isNotEmpty() }
        val isPastedPost = rawWords.size >= 7 || URL_REGEX.containsMatchIn(trimmed) || HANDLE_REGEX.containsMatchIn(trimmed) || '\n' in trimmed

        // Collect social slang & reaction intent cues (full query + 2/3-grams + individual slang tokens).
        val socialWords = LinkedHashSet<String>()
        var visualBridge: String? = null
        var pureSlang = false
        REACTION_CUES[normPhrase]?.let { cue ->
            socialWords.addAll(cue.synonyms)
            if (rawWords.size <= 3 && cue.visualBridge != null) {
                visualBridge = cue.visualBridge
                pureSlang = normPhrase in OPAQUE_SLANG_PHRASES
            }
        }
        val covered = BooleanArray(rawWords.size)
        for (n in 3 downTo 1) {
            if (rawWords.size < n) continue
            for (i in 0..rawWords.size - n) {
                if ((0 until n).any { covered[i + it] }) continue
                val sub = rawWords.subList(i, i + n).joinToString(" ")
                if (n == 1 && sub in SHORT_QUERY_ONLY_UNIGRAMS && rawWords.size > 2) continue
                val cue = REACTION_CUES[sub] ?: continue
                for (k in 0 until n) covered[i + k] = true
                socialWords.addAll(cue.synonyms)
                if (visualBridge == null && rawWords.size <= 2 && cue.visualBridge != null) {
                    visualBridge = cue.visualBridge
                }
            }
        }

        val exactTerms = if (isPastedPost) {
            val salient = rawWords
                .filter { it.length >= 2 && it !in POST_FLUFF_WORDS }
                .distinct()
                .take(8)
            salient.ifEmpty { SearchText.searchTermsForText(cleaned, keepShort = keepShort).take(6) }
        } else {
            SearchText.searchTermsForText(cleaned, keepShort = keepShort)
        }

        socialWords.removeAll(exactTerms.toSet())

        // When the entire short query IS a reaction idiom ("cooked", "joever", "celebrating too early"),
        // embed the clean visual scene bridge directly so literal homographs ("cooked dinner", "too late") don't hijack CLIP!
        val embedText = when {
            pureSlang && visualBridge != null -> "$cleaned, $visualBridge".takeIf { normPhrase in setOf("this is fine", "side eye", "absolute cinema") } ?: visualBridge!!
            visualBridge != null -> "$cleaned, $visualBridge"
            isPastedPost && exactTerms.isNotEmpty() -> exactTerms.joinToString(" ")
            else -> cleaned
        }

        return DistilledQuery(
            cleanedText = cleaned,
            embedText = embedText,
            exactTerms = exactTerms,
            socialTerms = socialWords,
            isPastedPost = isPastedPost,
            pureSlangReaction = pureSlang,
        )
    }
}
