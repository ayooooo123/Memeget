package com.memeget.nativeapp.inference

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `clip_tokenizer_cases.json` holds ids from Hugging Face `tokenizers` with the pinned MobileCLIP-S2 tokenizer.json
 * (padding zeroed), and the vocab and merges those texts use, in their original merge order. Every case also
 * matched an independent port of this BPE run against the full tokenizer, as did all bundled label prompts.
 */
class ClipTokenizerTest {
    private val fixture = JSONObject(requireNotNull(javaClass.classLoader).getResource("clip_tokenizer_cases.json").readText())
    private val tokenizer = ClipTokenizer.parse(fixture.toString())

    @Test fun encodesLikeTheReferenceTokenizer() {
        // Case, punctuation, hyphens, contractions, digits, Unicode and decomposed accents, emoji, whitespace runs,
        // special-token text, empty text, CJK, and a text cut to fit the 77 positions.
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("ids").let { ids -> LongArray(ids.length()) { ids.getLong(it) } }
            assertArrayEquals(case.getString("text"), expected, tokenizer.encode(case.getString("text")))
        }
    }

    @Test fun refusesATokenizerWithoutClipWordEnds() {
        val whisperLike = """{"model":{"type":"BPE","end_of_word_suffix":null,"vocab":{},"merges":[]}}"""
        assertThrows(IllegalArgumentException::class.java) { ClipTokenizer.parse(whisperLike) }
    }
}
