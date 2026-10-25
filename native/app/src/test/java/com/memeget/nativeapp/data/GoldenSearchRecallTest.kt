package com.memeget.nativeapp.data

import android.app.Application
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.LocalAnalysis
import com.memeget.nativeapp.LocalKnowledge
import com.memeget.nativeapp.ModelProgress
import com.memeget.nativeapp.SqliteLibraryStore
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.TextRegion
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.knowledge.OpenVocabConcepts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sqrt

/**
 * Collared end-to-end recall & ranking gate over the real 595-meme holdout (`../tools/eval/golden.json`) —
 * fucks every substring trap, 2-letter drop, VLM tag wipeout, and uncalibrated score blowup shut on real SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoldenSearchRecallTest {

    private lateinit var store: SqliteLibraryStore
    private val model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = SqliteLibraryStore(context)
    }

    private fun unitVec(vararg entries: Pair<Int, Float>): FloatArray {
        val v = FloatArray(512)
        for ((i, f) in entries) v[i] = f
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun locateFile(candidates: List<String>): File? =
        candidates.map(::File).firstOrNull { it.isFile }

    @Test
    fun golden595HoldoutMeetsMultiTermAndAspectRecallGates() = runBlocking {
        val goldenFile = locateFile(
            listOf(
                "../tools/eval/golden.json",
                "../../tools/eval/golden.json",
                "tools/eval/golden.json",
            )
        ) ?: return@runBlocking
        val openVocabFile = locateFile(
            listOf(
                "src/main/assets/knowledge/open_vocab_concepts.bin",
                "app/src/main/assets/knowledge/open_vocab_concepts.bin",
                "native/app/src/main/assets/knowledge/open_vocab_concepts.bin",
            )
        )
        val openConcepts = openVocabFile?.inputStream()?.use { OpenVocabConcepts.loadFromStream(it) }

        val root = JSONObject(goldenFile.readText(Charsets.UTF_8))
        val memesJson = root.getJSONArray("memes")
        val queriesJson = root.getJSONArray("queries")
        val aspectsJson = root.optJSONArray("aspects")

        val storeIdByGoldenId = HashMap<String, Long>(memesJson.length() * 2)
        for (i in 0 until memesJson.length()) {
            val m = memesJson.getJSONObject(i)
            val gid = m.getString("id")
            val vecArr = m.getJSONArray("imageVec")
            val imageVec = FloatArray(vecArr.length()) { k -> vecArr.getDouble(k).toFloat() }
            val ocr = m.optString("searchText", "")

            // Leak-free classification on unseen holdout memes (`allowReferenceMatch = false`)
            val autoTags = openConcepts?.classifyLibraryMeme(
                image = imageVec,
                ocrAndText = ocr,
                allowReferenceMatch = false,
            ).orEmpty()
            val extra = buildString {
                for (t in autoTags) {
                    append(t.label).append(' ')
                    for (alias in OpenVocabConcepts.parentAliasesFor(t.label)) {
                        append(alias).append(' ')
                    }
                }
            }.trim()

            val dbId = store.insertPending("file:///golden/$gid", gid, "image", 1_000L + i, "h$i".padEnd(64, '0'))
            store.completeIndex(dbId, LocalAnalysis(imageVec, ocr, autoTags, extra), model, "")
            storeIdByGoldenId[gid] = dbId
        }

        // 1. Multi-term retrieval benchmark across all 595 holdout queries
        var r1 = 0
        var r5 = 0
        var r10 = 0
        var mrrSum = 0.0
        val totalQueries = queriesJson.length()
        for (qIdx in 0 until totalQueries) {
            val qObj = queriesJson.getJSONObject(qIdx)
            val queryText = qObj.getString("query").replace(",", " ")
            val expectedDbId = storeIdByGoldenId[qObj.getString("expectedId")] ?: continue
            val qVecArr = qObj.getJSONArray("queryVec")
            val queryVec = FloatArray(qVecArr.length()) { k -> qVecArr.getDouble(k).toFloat() }

            val hits = store.search(queryText, queryVec, limit = 10)
            val rankIndex = hits.indexOfFirst { it.meme.id == expectedDbId }
            if (rankIndex >= 0) {
                val rank = rankIndex + 1
                if (rank <= 1) r1++
                if (rank <= 5) r5++
                if (rank <= 10) r10++
                mrrSum += 1.0 / rank
            }
        }

        val recallAt1 = r1.toDouble() / totalQueries
        val recallAt5 = r5.toDouble() / totalQueries
        val recallAt10 = r10.toDouble() / totalQueries
        val mrr = mrrSum / totalQueries

        println(
            String.format(
                java.util.Locale.US,
                "[Golden 595 Retrieval] R@1=%.1f%% R@5=%.1f%% R@10=%.1f%% MRR=%.3f",
                recallAt1 * 100.0,
                recallAt5 * 100.0,
                recallAt10 * 100.0,
                mrr,
            )
        )

        // Old broken Native pipeline choked at R@1=28.7%, R@5=46.6%, R@10=58.8%, MRR=0.384; bare S2 sits at 47.9%.
        // Master's calibrated hybrid engine must clear 49% R@1 and 0.58 MRR without breaking a sweat.
        assertTrue("Recall@1 ($recallAt1) must exceed 0.49", recallAt1 >= 0.49)
        assertTrue("Recall@5 ($recallAt5) must exceed 0.67", recallAt5 >= 0.67)
        assertTrue("Recall@10 ($recallAt10) must exceed 0.74", recallAt10 >= 0.74)
        assertTrue("MRR ($mrr) must exceed 0.58", mrr >= 0.58)

        // 2. Single-word & short aspect benchmark across all 52 multi-relevant queries
        if (aspectsJson != null && aspectsJson.length() > 0) {
            var apSum = 0.0
            var aspectCount = 0
            for (aIdx in 0 until aspectsJson.length()) {
                val aObj = aspectsJson.getJSONObject(aIdx)
                val aQuery = aObj.getString("query")
                val qVecArr = aObj.getJSONArray("queryVec")
                val aVec = FloatArray(qVecArr.length()) { k -> qVecArr.getDouble(k).toFloat() }
                val relArr = aObj.getJSONArray("relevantIds")
                val relevant = HashSet<Long>()
                for (r in 0 until relArr.length()) {
                    storeIdByGoldenId[relArr.getString(r)]?.let(relevant::add)
                }
                if (relevant.isEmpty()) continue
                aspectCount++

                val hits = store.search(aQuery, aVec, limit = 256)
                var found = 0
                var precSum = 0.0
                for ((idx, hit) in hits.withIndex()) {
                    if (hit.meme.id in relevant) {
                        found++
                        precSum += found.toDouble() / (idx + 1)
                    }
                }
                apSum += precSum / relevant.size
            }
            val map = apSum / aspectCount.coerceAtLeast(1)
            println(String.format(java.util.Locale.US, "[Golden %d Aspects] MAP=%.3f", aspectCount, map))
            // Pure dense S2 is 0.159; hybrid aspect MAP must substantially beat dense-only.
            assertTrue("Aspect MAP ($map) must exceed 0.21", map >= 0.21)
        }
    }

    @Test
    fun substringNoiseAndTrailingPrefixDoNotOutrankVisualOrExactMatches() = runBlocking {
        // Meme 1 has substring traps ("what", "that", "chat", "cartoon", "cardboard") but weak visual match
        val trap = store.insertPending("file:///trap.png", "chat_cardboard.png", "image", 10L, "1".repeat(64))
        store.completeIndex(
            trap,
            LocalAnalysis(
                image = unitVec(1 to 1f),
                ocr = "what is that chat about cartoon cardboard",
                tags = listOf(Tag("cartoon", "topic", 0.9f, "prompt"), Tag("cardboard", "object", 0.9f, "vision")),
                extraTerms = "what chat cartoon cardboard",
            ),
            model,
            "",
        )
        // Meme 2 visually depicts a hat / car cleanly along axis 0
        val visualTarget = store.insertPending("file:///target.png", "pic.png", "image", 11L, "2".repeat(64))
        store.completeIndex(
            visualTarget,
            LocalAnalysis(image = unitVec(0 to 0.35f, 2 to 0.936f), ocr = "", tags = emptyList(), extraTerms = ""),
            model,
            "",
        )

        val queryVec = unitVec(0 to 1f)
        // Query "hat" must NOT hand substring boosts to "what"/"that"/"chat"
        val hatHits = store.search("hat", queryVec, limit = 5)
        assertEquals("Visual hat match must beat substring 'what/chat' trap", visualTarget, hatHits.first().meme.id)

        // Query "car" (no trailing space!) must NOT dump prefix "cardboard"/"cartoon" into full BM25 ahead of visual car
        val carHits = store.search("car", queryVec, limit = 5)
        assertEquals("Visual car match must beat prefix 'cardboard/cartoon' trap", visualTarget, carHits.first().meme.id)
    }

    @Test
    fun twoLetterQueriesStayConsistentAcrossLexicalAndSemanticPasses() = runBlocking {
        val aiMeme = store.insertPending("file:///ai.png", "meme1.png", "image", 10L, "a".repeat(64))
        store.completeIndex(
            aiMeme,
            LocalAnalysis(
                image = unitVec(0 to 0.20f, 3 to 0.98f),
                ocr = "when ai takes your job",
                tags = listOf(Tag("ai", "topic", 0.9f, "vision")),
                extraTerms = "ai",
            ),
            model,
            "",
        )
        val distractor = store.insertPending("file:///other.png", "meme2.png", "image", 20L, "b".repeat(64))
        store.completeIndex(
            distractor,
            LocalAnalysis(image = unitVec(0 to 0.28f, 4 to 0.96f), ocr = "random picture", tags = emptyList(), extraTerms = ""),
            model,
            "",
        )

        // Pass 1 (lexical, vector = null)
        val lexicalHits = store.search("ai", null, limit = 5)
        assertEquals(listOf(aiMeme), lexicalHits.map { it.meme.id })

        // Pass 2 (semantic, vector != null): "ai" MUST NOT vanish on the semantic pass!
        val semanticHits = store.search("ai", unitVec(0 to 1f), limit = 5)
        assertEquals("Exact 2-letter 'ai' meme must remain #1 on semantic pass", aiMeme, semanticHits.first().meme.id)
    }

    @Test
    fun vlmOcrAndVisionKeywordsSurviveOpenVocabAndRetagging() = runBlocking {
        val id = store.insertPending("file:///sopranos.png", "frame.png", "image", 50L, "c".repeat(64))
        store.completeIndex(
            id,
            LocalAnalysis(
                image = unitVec(5 to 1f),
                ocr = "", // CRAFT+CRNN missed the stylized subtitle
                tags = listOf(Tag("tony soprano", "character", 0.91f, "open_vocab", "open-vocab")),
                extraTerms = "tony soprano sopranos",
            ),
            model,
            "",
        )

        // Local VLM captioner runs and extracts verbatim subtitle + reaction tags
        store.setCaption(
            id = id,
            caption = "man in car reacting with shock to foul language",
            tags = listOf(
                Tag("insult", "topic", 0f, "vision", "local-generative-unscored"),
                Tag("shocked", "topic", 0f, "vision", "local-generative-unscored"),
                Tag("offended", "topic", 0f, "vision", "local-generative-unscored"),
            ),
            vector = unitVec(6 to 1f),
            state = "done",
            vlmOcr = "Ho! The language on you.",
        )

        val knowledge = LocalKnowledge(store, StubEngine())
        for (field in listOf("cachedLabels", "cachedAnchors")) {
            LocalKnowledge::class.java.getDeclaredField(field).apply { isAccessible = true }.set(knowledge, emptyList<Any>())
        }
        knowledge.retag {}

        val saved = requireNotNull(store.meme(id))
        assertEquals("Ho! The language on you.", saved.vlmOcr)
        assertTrue("VLM vision tags must survive alongside open_vocab and across retag", saved.tags.any { it.label == "insult" && it.source == "vision" })
        assertTrue("VLM vision tags must survive alongside open_vocab and across retag", saved.tags.any { it.label == "shocked" && it.source == "vision" })

        // Both the VLM OCR quote and VLM reaction tag must retrieve the meme in lexical AND semantic search!
        assertEquals(listOf(id), store.search("language on you", null).map { it.meme.id })
        assertEquals(id, store.search("language on you", unitVec(9 to 1f)).first().meme.id)
        assertEquals(listOf(id), store.search("insult", null).map { it.meme.id })
    }

    @Test
    fun multiSceneVideoPunchlineMatchesViaSceneVectors() = runBlocking {
        // Video has 3 distinct scenes: axes 10, 11, and punchline axis 0. Pooled average dilutes axis 0 to ~0.577,
        // while a distractor still sits at 0.65 along axis 0. Scene max-sim recovers the 1.0 punchline match!
        val pooled = unitVec(10 to 1f, 11 to 1f, 0 to 1f)
        val scenes = listOf(unitVec(10 to 1f), unitVec(11 to 1f), unitVec(0 to 1f))
        val videoId = store.insertPending("file:///clip.mp4", "clip.mp4", "video", 10L, "d".repeat(64))
        store.completeIndex(videoId, LocalAnalysis(pooled, "", emptyList(), "", scenes), model, "")

        val stillId = store.insertPending("file:///still.png", "still.png", "image", 20L, "e".repeat(64))
        store.completeIndex(stillId, LocalAnalysis(unitVec(0 to 0.65f, 12 to 0.76f), "", emptyList(), ""), model, "")

        val hits = store.search("punchline", unitVec(0 to 1f), limit = 5)
        assertEquals("Video whose final scene matches query must beat diluted still", videoId, hits.first().meme.id)
    }

    @Test
    fun socialReplySlangAndPastedPostDistillationSurfaceMatchingMemes() = runBlocking {
        val defeatedId = store.insertPending("file:///despair.png", "bench.png", "image", 10L, "f".repeat(64))
        store.completeIndex(
            defeatedId,
            LocalAnalysis(
                image = unitVec(20 to 1f),
                ocr = "",
                tags = listOf(Tag("despair", "emotion", 0.9f, "vision"), Tag("exhausted", "emotion", 0.9f, "vision")),
                extraTerms = "despair exhausted defeated",
            ),
            model,
            "",
        )
        val scorseseId = store.insertPending("file:///scorsese.png", "hands.png", "image", 20L, "9".repeat(64))
        store.completeIndex(
            scorseseId,
            LocalAnalysis(
                image = unitVec(21 to 1f),
                ocr = "",
                tags = listOf(Tag("scorsese", "person", 0.92f, "open_vocab"), Tag("masterpiece", "topic", 0.8f, "vision")),
                extraTerms = "scorsese masterpiece applause",
            ),
            model,
            "",
        )
        val aiRivalryId = store.insertPending("file:///anthropic.png", "tower.png", "image", 30L, "8".repeat(64))
        store.completeIndex(
            aiRivalryId,
            LocalAnalysis(
                image = unitVec(22 to 1f),
                ocr = "Anthropic blindsiding OpenAI",
                tags = listOf(Tag("ai", "topic", 0.9f, "vision")),
                extraTerms = "anthropic openai ai",
            ),
            model,
            "",
        )

        // Slang "cooked" and "joever" match "despair / exhausted" even without the literal slang word on the meme
        assertEquals(listOf(defeatedId), store.search("cooked", null).map { it.meme.id })
        assertEquals(defeatedId, store.search("joever", unitVec(25 to 1f)).first().meme.id)
        val distilledCooked = SocialReplyIntent.distill("cooked")
        assertTrue("Short slang must bridge to visual scene description", distilledCooked.embedText.contains("despair"))

        // Slang "cinema" surfaces Scorsese meme on instant 0ms lexical pass
        assertEquals(listOf(scorseseId), store.search("cinema", null).map { it.meme.id })

        // Pasting a messy X/Twitter post strips @handles, URLs, and conversational fluff to match topical reply meme #1
        val messyTweet = "@someguy bro Anthropic just dropped another AI model while OpenAI is still promising next week lmao https://x.com/foo/status/12345"
        val distilledTweet = SocialReplyIntent.distill(messyTweet)
        assertTrue(distilledTweet.isPastedPost)
        assertTrue(distilledTweet.exactTerms.contains("anthropic"))
        assertTrue(distilledTweet.exactTerms.none { it.startsWith("http") || it == "someguy" || it == "bro" })
        assertEquals(aiRivalryId, store.search(messyTweet, unitVec(26 to 1f)).first().meme.id)
    }

    @Test
    fun copyHistoryTracksRecentlyUsedAndBreaksTiesTowardGoToReactions() = runBlocking {
        val firstWojak = store.insertPending("file:///w1.png", "w1.png", "image", 100L, "5".repeat(64))
        val goToWojak = store.insertPending("file:///w2.png", "w2.png", "image", 100L, "6".repeat(64))
        val sharedVec = unitVec(30 to 0.30f, 31 to 0.954f)
        store.completeIndex(firstWojak, LocalAnalysis(sharedVec, "", listOf(Tag("wojak", "character", 0.9f, "open_vocab")), "wojak"), model, "")
        store.completeIndex(goToWojak, LocalAnalysis(sharedVec, "", listOf(Tag("wojak", "character", 0.9f, "open_vocab")), "wojak"), model, "")

        // Copy goToWojak three times as a go-to online reply
        repeat(3) { store.recordCopy(goToWojak) }

        val recent = store.recentlyCopied(limit = 10)
        assertEquals(listOf(goToWojak), recent.map { it.id })
        assertEquals(3, recent.single().copyCount)

        // Among visually identical Wojaks, Master's battle-tested go-to reaction rises to #1
        val hits = store.search("wojak", unitVec(30 to 1f), limit = 5)
        assertEquals("Frequently copied reaction meme must break ties to #1", goToWojak, hits.first().meme.id)
    }

    @Test
    fun fieldTieredCoverageAndSlangGuardsBeatCaptionHubDistractorsAndPreserveResidentIndex() = runBlocking {
        // 1. Multi-term query with scaffolding word ("looking at phone disgusted"):
        // Meme A has real tags ['disgusted', 'cell phone'] + caption; Meme B is a mirror selfie whose captionVec has higher raw text-text cosine!
        val disgustedPhone = store.insertPending("file:///disgust.png", "disgust.png", "image", 50L, "7".repeat(64))
        store.completeIndex(
            disgustedPhone,
            LocalAnalysis(
                image = unitVec(40 to 0.24f, 41 to 0.97f),
                ocr = "",
                tags = listOf(Tag("cell phone", "object", 0.9f, "open_vocab")),
                extraTerms = "cell phone",
            ),
            model,
            "",
        )
        store.manualTag(listOf(disgustedPhone), "disgusted", "emotion")
        store.setCaption(disgustedPhone, "man talking on phone reacts to cringe", emptyList(), unitVec(40 to 0.72f, 42 to 0.69f))
        val selfieDistractor = store.insertPending("file:///selfie.png", "selfie.png", "image", 60L, "4".repeat(64))
        store.completeIndex(
            selfieDistractor,
            LocalAnalysis(
                image = unitVec(40 to 0.25f, 43 to 0.968f),
                ocr = "",
                tags = listOf(Tag("mirror selfie", "topic", 0.9f, "open_vocab"), Tag("cell phone", "object", 0.9f, "open_vocab")),
                extraTerms = "mirror selfie cell phone",
            ),
            model,
            "",
        )
        store.setCaption(selfieDistractor, "man using phone in mirror", emptyList(), unitVec(40 to 0.84f, 44 to 0.54f))

        val qVec = unitVec(40 to 1f)
        val hits = store.search("looking at phone disgusted", qVec, limit = 5)
        assertEquals("True disgusted + phone meme must outrank single-noun phone selfie caption hub", disgustedPhone, hits.first().meme.id)
        // 2. N-gram overlap & short-query unigram guards:
        val noCap = SocialReplyIntent.distill("no cap")
        assertFalse("'no cap' must NOT trigger 'cap' lying/bullshit synonyms: ${noCap.socialTerms}", "liar" in noCap.socialTerms || "bullshit" in noCap.socialTerms)
        assertTrue("'no cap' keeps agreement synonyms", "facts" in noCap.socialTerms || "truth" in noCap.socialTerms)

        val realEstate = SocialReplyIntent.distill("its free real estate")
        assertFalse("Multi-word proper phrase 'its free real estate' must NOT trigger 1-gram 'real' -> gosling: ${realEstate.socialTerms}", "gosling" in realEstate.socialTerms)

        // 3. Surgical resident index maintenance across setSetting and recordCopy:
        store.warmSearchIndex()
        val residentField = SqliteLibraryStore::class.java.getDeclaredField("cachedResidentIndex").apply { isAccessible = true }
        val indexBefore = requireNotNull(residentField.get(store))
        store.setSetting("vision.inFlight", "42")
        assertTrue("Background setSetting must NOT evict the resident search index", residentField.get(store) === indexBefore)
        store.recordCopy(disgustedPhone)
        assertTrue("recordCopy must patch copyCount in-place without dropping resident search index", residentField.get(store) === indexBefore)
        assertEquals(1, store.copiedCount())
    }

    private class StubEngine : InferenceEngine {
        override val progress: StateFlow<ModelProgress> = MutableStateFlow(ModelProgress())
        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        override suspend fun embedImage(uri: String): FloatArray = FloatArray(512)
        override suspend fun embedText(text: String): FloatArray = FloatArray(512)
        override suspend fun embedVisual(uri: String): FloatArray = FloatArray(768)
        override suspend fun recognize(uri: String): List<TextRegion> = emptyList()
        override suspend fun describe(uri: String, grounding: String): String = ""
        override suspend fun transcribe(uri: String): String = ""
        override suspend fun segment(uri: String, requestId: String): expo.modules.memegetbg.SubjectCutoutResult =
            throw UnsupportedOperationException()
        override suspend fun unload() {}
        override fun cancel() {}
        override fun close() {}
    }
}
