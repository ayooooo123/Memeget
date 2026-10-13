package com.memeget.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QueryRankingTest {

    private fun createSyntheticMatchinfo(
        p: Int,
        c: Int,
        triples: List<Triple<Int, Int, Int>>,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(8 + triples.size * 12).order(ByteOrder.nativeOrder())
        buffer.putInt(p)
        buffer.putInt(c)
        for ((tf, allHits, df) in triples) {
            buffer.putInt(tf)
            buffer.putInt(allHits)
            buffer.putInt(df)
        }
        return buffer.array()
    }

    @Test
    fun testFtsColumnWeightsTagsOutrankCaptionAndTranscript() {
        // 1 phrase, 6 columns: name(1.0), ocr(1.2), caption(2.0), transcript(1.8), tags(5.0), extra_terms(4.0)
        val p = 1
        val c = 6

        // Case A: 1 hit in tags (col 4)
        val tagsTriples = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until c) {
            if (i == 4) tagsTriples.add(Triple(1, 10, 5))
            else tagsTriples.add(Triple(0, 0, 0))
        }
        val tagsBlob = createSyntheticMatchinfo(p, c, tagsTriples)
        val tagsScore = SearchRanking.calculateFts4Bm25(tagsBlob, totalDocs = 100)

        // Case B: 1 hit in caption (col 2)
        val captionTriples = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until c) {
            if (i == 2) captionTriples.add(Triple(1, 10, 5))
            else captionTriples.add(Triple(0, 0, 0))
        }
        val captionBlob = createSyntheticMatchinfo(p, c, captionTriples)
        val captionScore = SearchRanking.calculateFts4Bm25(captionBlob, totalDocs = 100)

        // Case C: 1 hit in transcript (col 3)
        val transcriptTriples = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until c) {
            if (i == 3) transcriptTriples.add(Triple(1, 10, 5))
            else transcriptTriples.add(Triple(0, 0, 0))
        }
        val transcriptBlob = createSyntheticMatchinfo(p, c, transcriptTriples)
        val transcriptScore = SearchRanking.calculateFts4Bm25(transcriptBlob, totalDocs = 100)

        // Case D: 1 hit in ocr (col 1)
        val ocrTriples = ArrayList<Triple<Int, Int, Int>>()
        for (i in 0 until c) {
            if (i == 1) ocrTriples.add(Triple(1, 10, 5))
            else ocrTriples.add(Triple(0, 0, 0))
        }
        val ocrBlob = createSyntheticMatchinfo(p, c, ocrTriples)
        val ocrScore = SearchRanking.calculateFts4Bm25(ocrBlob, totalDocs = 100)

        assertTrue("Tags score must outrank caption score", tagsScore > captionScore)
        assertTrue("Caption score must outrank transcript score", captionScore > transcriptScore)
        assertTrue("Transcript score must outrank OCR score", transcriptScore > ocrScore)
    }

    @Test
    fun testHybridSearchScoreCaptionThresholding() {
        val query = FloatBlob.normalize(FloatArray(512) { 0.1f })
        val image = FloatBlob.normalize(FloatArray(512) { 0.1f }) // cos = 1.0

        // Sub-floor caption cosine: cos = 0.50 (< 0.55 floor)
        // Cosine 0.50 vector
        val subFloorCaption = FloatArray(512) { i -> if (i < 128) 0.1f else 0.0f }
        val subFloorNorm = FloatBlob.normalize(subFloorCaption)
        val subFloorCos = FloatBlob.dot(query, subFloorNorm)
        assertTrue("Must be sub-floor", subFloorCos < SearchRanking.CAPTION_COS_FLOOR)

        val scoreSubFloor = SearchRanking.hybridSearchScore(query, image, subFloorNorm)
        val scoreNoCaption = SearchRanking.hybridSearchScore(query, image, null)
        // Sub-floor caption must give 0 boost (identical to no caption)
        assertEquals(scoreNoCaption, scoreSubFloor, 0.0001f)

        // Above-floor caption: cos = 1.0
        val scoreAboveFloor = SearchRanking.hybridSearchScore(query, image, image)
        val expectedBoost = SearchRanking.CAPTION_WEIGHT * (1.0f - SearchRanking.CAPTION_COS_FLOOR)
        assertEquals(scoreNoCaption + expectedBoost, scoreAboveFloor, 0.0001f)
    }

    @Test
    fun testExactPhraseMatchingContiguousWholeTokens() {
        val queryText = "I'm so old"
        val queryKey = SearchText.phraseKey(queryText)
        assertEquals("im so old", queryKey)
        assertEquals(3, SearchText.phraseTokenCount(queryKey))

        // Haystack with transcript having apostrophe
        val haystack1 = "You know, I'm so old that I remember floppy disks."
        val haystackKey1 = SearchText.phraseKey(haystack1)
        assertTrue("Should contain contiguous phrase despite punctuation", SearchText.containsPhrase(haystackKey1, queryKey))

        // Haystack containing tokens out of order
        val haystack2 = "I'm old and so tired."
        val haystackKey2 = SearchText.phraseKey(haystack2)
        assertFalse("Should not match out-of-order tokens", SearchText.containsPhrase(haystackKey2, queryKey))

        // Haystack containing partial word match ("golden old")
        val haystack3 = "A golden oldies song"
        val haystackKey3 = SearchText.phraseKey(haystack3)
        assertFalse("Should not match partial token boundaries", SearchText.containsPhrase(haystackKey3, queryKey))
    }

    @Test
    fun testSearchVocabTypoToleranceAndPrefixExpansion() {
        val texts = listOf(
            "pepe the green frog feelsgoodman rare pepe",
            "wojak feels guy depressed doomer bloomer",
            "gigachad chad jawline sigma",
        )
        val vocab = SearchVocab.build(texts)
        assertTrue(vocab.has("pepe"))
        assertTrue(vocab.has("wojak"))
        assertTrue(vocab.has("gigachad"))

        // Exact term is never corrected
        val pepeCorrections = vocab.corrections("pepe")
        assertTrue(pepeCorrections.isEmpty())

        // Typo within budget: "pepee" (1 edit) -> "pepe"
        val typoCorrections = vocab.corrections("pepee")
        assertTrue("Must correct pepee to pepe", typoCorrections.contains("pepe"))

        // Prefix match: "gigac" -> "gigachad"
        val prefixHits = vocab.prefixMatches("gigac")
        assertTrue("Must find gigachad by prefix", prefixHits.contains("gigachad"))

        // Expand query: non-word is corrected, and mid-word prefix is completed
        val expanded = vocab.expand(listOf("pepee", "giga"), lastIsPrefix = true)
        assertTrue("Must contain corrected term", expanded.contains("pepe"))
        assertTrue("Must contain prefix completion", expanded.contains("gigachad"))
    }

    @Test
    fun testReciprocalRankFusionCombinesSignals() {
        // List 1 (Dense rank): [10, 20, 30]
        val denseList = SearchRanking.RankedList(listOf(10L, 20L, 30L), weight = 1.0f)
        // List 2 (Lexical rank): [20, 10, 40]
        val lexicalList = SearchRanking.RankedList(listOf(20L, 10L, 40L), weight = 2.0f)

        val fused = SearchRanking.reciprocalRankFusion(listOf(denseList, lexicalList))
        // 20 is rank 2 in dense and rank 1 in lexical (weight 2)
        // 10 is rank 1 in dense and rank 2 in lexical
        // Both 10 and 20 should be top 2
        assertTrue(fused.size >= 4)
        val topIds = fused.take(2).map { it.id }.toSet()
        assertTrue(topIds.contains(10L))
        assertTrue(topIds.contains(20L))
    }
}
