package com.memeget.nativeapp.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDeviceDecisionHeadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun renderPromptWritesD1OmniDelimitersAndEscapesCallerSpecialTokens() {
        val head = OnDeviceDecisionHead(hiddenDim = 16)
        val (prompt, keys) = head.renderPrompt(
            ocr = "hello <|mask|> world",
            speech = "",
            caption = "a composite meme",
            candidates = listOf(
                OnDeviceDecisionHead.CandidateInput("steve-o", "person", 0.78f, 0.78f, 3, 2),
                OnDeviceDecisionHead.CandidateInput("dumb and dumber", "movie", 0.74f, 0.74f, 2, 1),
            ),
            noneFirst = false,
        )

        assertEquals(listOf("steve-o", "dumb and dumber", "none"), keys)
        assertEquals(3, Regex("<\\|mask\\|>").findAll(prompt).count())
        assertTrue(prompt.contains("<¦mask¦>"))
    }

    @Test
    fun upsertAndFitPersistsAndReloadsLearnedHeadAcrossRestart() {
        val dim = 32
        val head = OnDeviceDecisionHead(hiddenDim = dim)

        fun hiddenFor(vararg axes: Int): FloatArray {
            val out = FloatArray(4 * dim)
            for ((k, ax) in axes.withIndex()) {
                if (ax >= 0) out[k * dim + ax] = 2.0f
            }
            return out
        }

        val sample = OnDeviceDecisionHead.EvalSample(
            optionKeys = listOf("steve-o", "jim carrey", "patrick bateman", "none"),
            noneIndex = 3,
            baseLogits = floatArrayOf(0.0f, 0.0f, 0.5f, 0.0f),
            scorerHidden = hiddenFor(0, 1, 2, -1),
        )

        val ex = OnDeviceDecisionHead.TrainingExample(
            sample = sample,
            confirmedPositives = setOf("steve-o", "jim carrey"),
            confirmedRejections = setOf("patrick bateman"),
        )

        val before = head.scoreCandidates(sample)
        assertTrue(before.getValue("patrick bateman") > before.getValue("steve-o"))

        val loss = head.upsertAndFit(221L, ex, preferNativeKernel = false)
        assertTrue("Expected loss to drop, got $loss", loss < 0.25f)

        val stateFile = tempFolder.newFile("d1_omni_head.bin")
        head.saveTo(stateFile)

        // Simulate app restart by loading into a brand-new OnDeviceDecisionHead instance
        val reloaded = OnDeviceDecisionHead(hiddenDim = dim)
        assertTrue(reloaded.loadFrom(stateFile))
        assertEquals(1, reloaded.exampleCount())

        val after = reloaded.scoreCandidates(sample)
        assertTrue("steve-o should score high after reload: ${after["steve-o"]}", after.getValue("steve-o") > 0.85f)
        assertTrue("jim carrey should score high after reload: ${after["jim carrey"]}", after.getValue("jim carrey") > 0.85f)
        assertTrue("Rejected patrick bateman should be suppressed after reload: ${after["patrick bateman"]}", after.getValue("patrick bateman") < 0.15f)

        // Verify generalization to an UNTAUGHT, UNCACHED meme B whose options appear in a different order
        val uncachedSampleB = OnDeviceDecisionHead.EvalSample(
            optionKeys = listOf("patrick bateman", "jim carrey", "the nightcrawler", "none"),
            noneIndex = 3,
            baseLogits = floatArrayOf(0.4f, 0.1f, 0.2f, 0.0f),
            scorerHidden = hiddenFor(2, 1, 5, -1),
        )
        val uncachedScores = reloaded.scoreCandidates(uncachedSampleB)
        assertTrue(
            "Learned head should promote jim carrey above patrick bateman on uncached meme B: $uncachedScores",
            uncachedScores.getValue("jim carrey") > uncachedScores.getValue("patrick bateman"),
        )
    }

    @Test
    fun fitSupportsSingleCandidateAndAllPositiveCandidateSets() {
        val dim = 16
        val head = OnDeviceDecisionHead(hiddenDim = dim)

        // 1. Single-candidate sample (1 candidate + none)
        val singleHidden = FloatArray(2 * dim)
        singleHidden[0] = 2.0f // cand 0
        singleHidden[dim + 1] = 1.0f // none anchor (blended 0.5*hNone + 0.5*meanCand)
        singleHidden[dim + 0] = 1.0f
        val singleSample = OnDeviceDecisionHead.EvalSample(
            optionKeys = listOf("phil swift", "none"),
            noneIndex = 1,
            baseLogits = floatArrayOf(-0.5f, 0.0f),
            scorerHidden = singleHidden,
        )
        head.upsertAndFit(
            45L,
            OnDeviceDecisionHead.TrainingExample(
                sample = singleSample,
                confirmedPositives = setOf("phil swift"),
                confirmedRejections = emptySet(),
            ),
            preferNativeKernel = false,
        )
        val singleScores = head.scoreCandidates(singleSample)
        assertTrue("Single confirmed candidate should climb above 0.80: $singleScores", singleScores.getValue("phil swift") > 0.80f)

        // 2. All-positive multi-candidate sample (all offered candidates confirmed)
        val allPosHidden = FloatArray(3 * dim)
        allPosHidden[2] = 2.0f // cand 0
        allPosHidden[dim + 3] = 2.0f // cand 1
        allPosHidden[2 * dim + 2] = 0.5f // blended none row
        allPosHidden[2 * dim + 3] = 0.5f
        val allPosSample = OnDeviceDecisionHead.EvalSample(
            optionKeys = listOf("christian bale", "patrick bateman", "none"),
            noneIndex = 2,
            baseLogits = floatArrayOf(0.0f, 0.0f, 0.2f),
            scorerHidden = allPosHidden,
        )
        head.upsertAndFit(
            96L,
            OnDeviceDecisionHead.TrainingExample(
                sample = allPosSample,
                confirmedPositives = setOf("christian bale", "patrick bateman"),
                confirmedRejections = emptySet(),
            ),
            preferNativeKernel = false,
        )
        val allPosScores = head.scoreCandidates(allPosSample)
        assertTrue("christian bale should rise above 0.80: $allPosScores", allPosScores.getValue("christian bale") > 0.80f)
        assertTrue("patrick bateman should rise above 0.80: $allPosScores", allPosScores.getValue("patrick bateman") > 0.80f)
    }

    @Test
    fun openVocabConceptsBinaryLoadsAndRetrievesZeroShotAndHybridHitsOnRealHeldOutImageVector() {
        val assetFile = java.io.File("src/main/assets/knowledge/open_vocab_concepts.bin")
        assertTrue("Expected open_vocab_concepts.bin to exist", assetFile.isFile)
        val concepts = assetFile.inputStream().use { OpenVocabConcepts.loadFromStream(it) }
        assertEquals(6000, concepts.entries.size)
        assertEquals(2344, concepts.refMemes.size)
        val b64Meme436 =
            "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"
        val rawBytes = java.util.Base64.getDecoder().decode(b64Meme436)
        val bb = java.nio.ByteBuffer.wrap(rawBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vec436 = FloatArray(512) { bb.float }
        val hits = concepts.retrieveCandidates(vec436, limit = 6)
        assertTrue("Expected tommy lee jones in top retrieved hits on real held-out image vector: $hits", hits.any { it.label == "tommy lee jones" })
        assertTrue("Expected no country for old men in top retrieved hits on real held-out image vector: $hits", hits.any { it.label == "no country for old men" })
        val ocrHits = concepts.retrieveCandidates(vec436, ocrAndText = "MEMRI-TV Oh Bitcoin bears", limit = 6)
        assertTrue("Expected memri tv in OCR-boosted hits: $ocrHits", ocrHits.any { it.label == "memri tv" })
    }

    @Test
    fun rootFamilyDeduplicationAndParentImplicationsKeepRuneScapeAlongsidePepeAliases() {
        val rawCandidates = listOf(
            LookalikeVote.Vote("pepe", "character", 0.95f, 0.95f, 2),
            LookalikeVote.Vote("two pepes", "character", 0.95f, 0.95f, 2),
            LookalikeVote.Vote("pepes", "character", 0.95f, 0.95f, 2),
            LookalikeVote.Vote("pepe the frog", "character", 0.95f, 0.95f, 2),
            LookalikeVote.Vote("smug pepe", "character", 0.95f, 0.95f, 2),
            LookalikeVote.Vote("blue partyhat", "format", 0.93f, 0.93f, 2),
        )
        val byLabel = LinkedHashMap<String, LookalikeVote.Vote>()
        for (v in rawCandidates) byLabel[v.label] = v
        for (v in ArrayList(byLabel.values)) {
            if (v.cosine < 0.90f) continue
            for ((pLbl, pCat) in OpenVocabConcepts.PARENT_IMPLICATIONS[v.label].orEmpty()) {
                val impCos = (v.cosine - 0.02f).coerceAtLeast(0.76f)
                val prev = byLabel[pLbl]
                if (prev == null || impCos > prev.cosine) {
                    byLabel[pLbl] = LookalikeVote.Vote(pLbl, pCat, impCos, impCos, v.count)
                }
            }
        }
        val selected = ArrayList<String>()
        val famCounts = HashMap<String, Int>()
        for (v in byLabel.values.sortedByDescending { it.cosine }) {
            if (selected.size >= 6) break
            val fam = OpenVocabConcepts.rootFamily(v.label)
            val c = famCounts[fam] ?: 0
            if (c >= 2) continue
            famCounts[fam] = c + 1
            selected += v.label
        }
        assertEquals(2, selected.count { OpenVocabConcepts.rootFamily(it) == "pepe" })
        assertTrue("Expected blue partyhat in selected candidates: $selected", "blue partyhat" in selected)
        assertTrue("Expected implied runescape in selected candidates: $selected", "runescape" in selected)
    }

    @Test
    fun mergeDurableTagsPreservesUpTo12OpenVocabTagsByDefaultWhileCappingLegacyPromptTagsAt6() {
        val openTags = (1..11).map {
            com.memeget.nativeapp.Tag(
                label = "tag_$it",
                category = "topic",
                score = 0.95f - it * 0.01f,
                source = "open_vocab",
            )
        }
        val mergedOpen = com.memeget.nativeapp.data.TagMerge.mergeDurableTags(openTags)
        assertEquals("All 11 open_vocab tags must survive default mergeDurableTags call (e.g. setCaption/updateTags)", 11, mergedOpen.size)

        val promptTags = (1..10).map {
            com.memeget.nativeapp.Tag(
                label = "prompt_$it",
                category = "topic",
                score = 0.95f - it * 0.01f,
                source = "prompt",
            )
        }
        val mergedPrompt = com.memeget.nativeapp.data.TagMerge.mergeDurableTags(promptTags)
        assertEquals("Legacy prompt tags without open_vocab stay capped at 6 by default", 6, mergedPrompt.size)
    }
}
