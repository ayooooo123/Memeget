package com.memeget.nativeapp.inference

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * MobileCLIP-S2's text tokenizer: CLIP's byte-level BPE, read from the pinned `mobileclip_s2_tokenizer.json`,
 * giving the ids its Hugging Face tokenizer gives, between the start and end tokens the text tower expects.
 *
 * The runtime's C++ tokenizer doesn't reproduce this tokenizer. With its ids, all bundled label prompts embedded
 * within a median cosine of 0.98 of one another, about the vector of a lone "a" followed by end tokens, so labels and
 * searches barely depended on their words.
 */
internal class ClipTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val ranks: Map<String, Int>,
) {
    private val cache = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>): Boolean = size > CACHE_WORDS
    }

    /** [CONTEXT_LENGTH] ids: start, at most `CONTEXT_LENGTH - 2` text tokens, end, then zero padding. */
    fun encode(text: String): LongArray {
        val ids = LongArray(CONTEXT_LENGTH)
        ids[0] = START.toLong()
        var n = 1
        val normalized = WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC)).replaceAll(" ").lowercase(Locale.ROOT)
        val words = WORD.matcher(normalized)
        fill@ while (words.find()) {
            for (id in word(words.group())) {
                if (n == CONTEXT_LENGTH - 1) break@fill
                ids[n++] = id.toLong()
            }
        }
        ids[n] = END.toLong()
        return ids
    }

    private fun word(piece: String): IntArray {
        when (piece) {
            START_TEXT -> return intArrayOf(START)
            END_TEXT -> return intArrayOf(END)
        }
        synchronized(cache) { cache[piece] }?.let { return it }
        val bytes = piece.toByteArray(Charsets.UTF_8)
        var symbols = ArrayList<String>(bytes.size)
        for (b in bytes) symbols += BYTE_TO_CHAR[b.toInt() and 0xff].toString()
        symbols[symbols.lastIndex] = symbols.last() + WORD_END
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var first = ""
            var second = ""
            for (i in 0 until symbols.size - 1) {
                val rank = ranks[symbols[i] + " " + symbols[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    first = symbols[i]
                    second = symbols[i + 1]
                }
            }
            if (bestRank == Int.MAX_VALUE) break
            val merged = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == first && symbols[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += symbols[i]
                    i++
                }
            }
            symbols = merged
        }
        // Every byte and its word end is in the vocabulary; anything else maps to the tokenizer's unk_token,
        // <|endoftext|>, as Hugging Face's does.
        val ids = IntArray(symbols.size) { vocab[symbols[it]] ?: END }
        synchronized(cache) { cache[piece] = ids }
        return ids
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        const val START = 49406
        const val END = 49407
        private const val START_TEXT = "<|startoftext|>"
        private const val END_TEXT = "<|endoftext|>"
        private const val WORD_END = "</w>"
        private const val CACHE_WORDS = 4096

        // The tokenizer.json normalizer (NFC, whitespace runs to one space, lowercase) and its pre-tokenizer split.
        // Unicode whitespace is spelled out so the JVM and Android agree: Android's regex rejects
        // UNICODE_CHARACTER_CLASS.
        private const val SPACE = "\\p{Z}\\t\\n\\x0B\\f\\r\\x85"
        private val WHITESPACE: Pattern = Pattern.compile("[$SPACE]+")
        private val WORD: Pattern = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^$SPACE\\p{L}\\p{N}]+",
        )

        /** GPT-2's byte-to-character table, which byte-level BPE vocabularies are written in. */
        private val BYTE_TO_CHAR: CharArray = CharArray(256).also { table ->
            val printable = ('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)
            var next = 256
            for (b in 0 until 256) table[b] = if (b in printable) b.toChar() else (next++).toChar()
        }

        fun load(file: File): ClipTokenizer = parse(file.readText(Charsets.UTF_8))

        fun parse(json: String): ClipTokenizer {
            val model = JSONObject(json).getJSONObject("model")
            require(model.optString("type") == "BPE" && model.optString("end_of_word_suffix") == WORD_END) {
                "Not a CLIP byte-level BPE tokenizer"
            }
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            for (key in vocabJson.keys()) vocab[key] = vocabJson.getInt(key)
            val mergesJson = model.getJSONArray("merges")
            val ranks = HashMap<String, Int>(mergesJson.length() * 2)
            for (i in 0 until mergesJson.length()) {
                // Newer tokenizer.json files write a merge as a pair, older ones as one "a b" string.
                val pair = when (val merge = mergesJson.get(i)) {
                    is JSONArray -> merge.getString(0) + " " + merge.getString(1)
                    else -> merge.toString()
                }
                ranks.putIfAbsent(pair, i)
            }
            require(vocab[START_TEXT] == START && vocab[END_TEXT] == END) { "CLIP start/end tokens are not $START/$END" }
            return ClipTokenizer(vocab, ranks)
        }
    }
}
