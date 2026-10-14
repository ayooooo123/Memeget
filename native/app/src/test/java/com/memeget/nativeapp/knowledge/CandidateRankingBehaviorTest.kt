package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.CloudResult
import com.memeget.nativeapp.EnrichmentCounts
import com.memeget.nativeapp.Exemplar
import com.memeget.nativeapp.Folder
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.LibraryStore
import com.memeget.nativeapp.HumanLabeled
import com.memeget.nativeapp.ImportedAnalysis
import com.memeget.nativeapp.LocalAnalysis
import com.memeget.nativeapp.LocalKnowledge
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.ModelProgress
import com.memeget.nativeapp.PageCursor
import com.memeget.nativeapp.SearchCursor
import com.memeget.nativeapp.SearchHit
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.TextRegion
import com.memeget.nativeapp.VectorRecord
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.storage.TeachingPackIo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRankingBehaviorTest {

    private fun unitVector(dim: Int, index: Int): FloatArray {
        val v = FloatArray(dim)
        v[index] = 1.0f
        return v
    }

    private fun makeUnitProbe(dim: Int, a: Float, b: Float, c: Float): FloatArray {
        val v = FloatArray(dim)
        v[0] = a
        v[1] = b
        v[2] = c
        val sumSquares = a * a + b * b + c * c
        require(sumSquares <= 1.0f) { "Coordinates must have sum of squares <= 1.0" }
        v[3] = kotlin.math.sqrt(1.0f - sumSquares)
        return v
    }

    private fun injectCuratedLabels(
        knowledge: LocalKnowledge,
        labels: List<CuratedLabelDef>,
        anchors: List<NegativeAnchorDef> = emptyList(),
    ) {
        val labelsField = LocalKnowledge::class.java.getDeclaredField("cachedLabels").apply { isAccessible = true }
        val anchorsField = LocalKnowledge::class.java.getDeclaredField("cachedAnchors").apply { isAccessible = true }
        labelsField.set(knowledge, labels)
        anchorsField.set(knowledge, anchors)
    }

    private class TestStore : LibraryStore {
        val memes = LinkedHashMap<Long, Meme>()
        val vectors = HashMap<Long, VectorRecord>()
        val exemplarList = ArrayList<Exemplar>()
        private val _revision = MutableStateFlow(1L)
        override val revision: StateFlow<Long> = _revision
        override val labelRevision: StateFlow<Long> = _revision
        override suspend fun humanLabels(): List<HumanLabeled> = memes.values.mapNotNull { m ->
            val image = vectors[m.id]?.image ?: return@mapNotNull null
            val labels = m.tags.filter { it.confirmation == "confirmed" }
            if (labels.isEmpty() && m.rejectedLabels.isEmpty()) null else HumanLabeled(m.id, image, labels, m.rejectedLabels)
        }
        override suspend fun imageVectors(): Map<Long, FloatArray> = vectors.mapValues { it.value.image }
        override suspend fun idsWithScoreDomain(scoreDomain: String): Set<Long> =
            memes.values.filter { m -> m.tags.any { it.scoreDomain == scoreDomain } }.mapTo(HashSet()) { it.id }
        override suspend fun requeueFailed(): Int = 0

        /** Like the real store, a new teaching changes the label revision. */
        fun addTeaching(e: Exemplar) {
            exemplarList.add(e)
            _revision.value++
        }

        override suspend fun exemplars(): List<Exemplar> = ArrayList(exemplarList)
        override suspend fun count(): Int = memes.size
        override suspend fun countAll(): Int = memes.size
        override suspend fun vectorSample(limit: Int): List<VectorRecord> = vectors.values.take(limit)
        override suspend fun vectors(id: Long): VectorRecord? = vectors[id]

        override fun allMemes(): Flow<Meme> = flow {
            for (m in ArrayList(memes.values)) {
                emit(m)
            }
        }

        override suspend fun updateTags(id: Long, tags: List<Tag>, extraTerms: String) {
            val m = memes[id]
            if (m != null) {
                memes[id] = m.copy(tags = tags, extraTerms = extraTerms)
            }
            _revision.value++
        }

        override suspend fun meme(id: Long): Meme? = memes[id]
        override suspend fun teach(id: Long, label: String, category: String, positive: Boolean, associations: List<String>, confirmedCloudTag: Tag?) {
            val vec = vectors[id]?.image ?: FloatArray(512)
            exemplarList.add(
                Exemplar(
                    id = exemplarList.size + 1L,
                    label = label,
                    category = category,
                    vector = vec,
                    positive = positive,
                    associations = associations,
                    sourceUri = "",
                    model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
                )
            )
            _revision.value++
        }

        override suspend fun folders(): List<Folder> = emptyList()
        override suspend fun addFolder(uri: String, name: String) {}
        override suspend fun removeFolder(uri: String) {}
        override suspend fun insertPending(uri: String, name: String, kind: String, modifiedAt: Long, contentHash: String): Long = 0L
        override suspend fun memeByUri(uri: String): Meme? = null
        override suspend fun page(limit: Int, after: PageCursor?, kind: String?, tag: String?): List<Meme> = emptyList()
        override suspend fun search(text: String, vector: FloatArray?, kind: String?, tag: String?, limit: Int, after: SearchCursor?): List<SearchHit> = emptyList()
        override suspend fun similar(id: Long, limit: Int): List<SearchHit> = emptyList()
        override suspend fun pending(limit: Int): List<Meme> = emptyList()
        override suspend fun completeIndex(id: Long, analysis: LocalAnalysis, model: String, thumbUri: String) {}
        override suspend fun setCaption(id: Long, caption: String, tags: List<Tag>, vector: FloatArray?, state: String) {}
        override suspend fun setTranscript(id: Long, text: String, state: String) {}
        override suspend fun setAudioState(id: Long, state: String) {}
        override suspend fun setVisionState(id: Long, state: String) {}
        override suspend fun enrichmentCounts(): EnrichmentCounts = EnrichmentCounts()
        override suspend fun requeueFailedEnrichment(stage: String): Int = 0
        override suspend fun requeueAllTranscripts(): Int = 0
        override suspend fun setVisual(id: Long, vector: FloatArray, model: String) {}
        override suspend fun setThumb(id: Long, uri: String) {}
        override suspend fun setContentHash(id: Long, hash: String) {}
        override suspend fun requeue(ids: List<Long>) {}
        override suspend fun requeueTranscripts(model: String): Int = 0
        override suspend fun markIndexFailed(id: Long, reason: String) {}
        override suspend fun setCloud(id: Long, result: CloudResult): Boolean = true
        override suspend fun forgetCloud() {}
        override suspend fun manualTag(ids: List<Long>, label: String, category: String, spread: Boolean): Int = 0
        override suspend fun removeTag(id: Long, label: String) {}
        override suspend fun importExemplars(items: List<Exemplar>, pack: String, asSelf: Boolean): Int = 0
        override suspend fun applyImportedAnalysis(id: Long, analysis: ImportedAnalysis) {}
        override suspend fun relinkTeachings(): Int = 0
        override suspend fun forgetLabel(label: String) {}
        override suspend fun forgetPack(pack: String) {}
        override suspend fun clearExemplars(): Int = 0
        override suspend fun delete(ids: List<Long>) {}
        override suspend fun clearIndex() {}
        override suspend fun setting(key: String): String? = null
        override suspend fun setSetting(key: String, value: String) {}
        override suspend fun addError(name: String, stage: String, reason: String) {}
        override suspend fun errors(): List<String> = emptyList()
        override suspend fun errorCounts(): Map<String, Int> = emptyMap()
        override suspend fun clearErrors() {}
        override suspend fun tagLabels(): List<String> = emptyList()
        override suspend fun tagCounts(): Map<String, Int> = emptyMap()
        override suspend fun restore(meme: Meme, vectors: VectorRecord?): Boolean = true
    }

    private class TestEngine : InferenceEngine {
        override val progress: StateFlow<ModelProgress> = MutableStateFlow(ModelProgress())
        override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        override suspend fun embedImage(uri: String): FloatArray = FloatArray(512)
        override suspend fun embedText(text: String): FloatArray = FloatArray(512)
        override suspend fun embedVisual(uri: String): FloatArray = FloatArray(768)
        override suspend fun recognize(uri: String): List<TextRegion> = emptyList()
        override suspend fun describe(uri: String, grounding: String): String = ""
        override suspend fun transcribe(uri: String): String = ""
        override suspend fun segment(uri: String, requestId: String): expo.modules.memegetbg.SubjectCutoutResult {
            throw UnsupportedOperationException()
        }
        override suspend fun unload() {}
        override fun cancel() {}
        override fun close() {}
    }

    @Test
    fun legacyTeachingCompatibilityPreservesVetoesAndRejectsForeignEncoders() = runBlocking {
        val probe = unitVector(512, 0)
        val store = TestStore()
        fun example(id: Long, label: String, model: String, positive: Boolean) = Exemplar(
            id, label, "object", probe, positive, listOf("migration"), "", model, "pack",
        )
        store.exemplarList += listOf(
            example(1L, "legacy subject", TeachingPackIo.PACK_MODEL, true),
            example(2L, "rejected subject", TeachingPackIo.PACK_MODEL, false),
            example(3L, "foreign subject", "different-encoder", true),
            example(4L, "legacy subject", "different-encoder", false),
        )
        val knowledge = LocalKnowledge(store, TestEngine())
        injectCuratedLabels(knowledge, listOf(
            CuratedLabelDef("rejected subject", "rejected subject", "object", emptyList(), probe),
        ))

        val (tags, terms) = knowledge.classify(probe, "")
        assertEquals(listOf("legacy subject"), tags.map { it.label })
        assertTrue(terms.contains("migration"))
        assertEquals(listOf("legacy subject"), knowledge.candidateLabels(probe).map { it.label })
        assertEquals(TeachingPackIo.PACK_MODEL, store.exemplars().first().model)
    }

    @Test
    fun belowFloorImage_yieldsNoLocalFacts_butYieldsTentativeOptionsWithRawMarginOrder() = runBlocking {
        val dim = 512
        val store = TestStore()
        val engine = TestEngine()
        val knowledge = LocalKnowledge(store, engine)

        // Setup curated labels along orthogonal unit axes
        val labelA = CuratedLabelDef("pepe", "a pepe frog", "character", listOf("frog"), unitVector(dim, 0))
        val labelB = CuratedLabelDef("wojak", "a wojak face", "character", listOf("feels"), unitVector(dim, 1))
        val labelC = CuratedLabelDef("doge", "a doge shiba", "character", listOf("dog"), unitVector(dim, 2))
        injectCuratedLabels(knowledge, listOf(labelA, labelB, labelC))

        // Production unit-norm probe vector with margins strictly below the 0.13 floor:
        // dot(probe, labelA) = 0.10f
        // dot(probe, labelB) = 0.08f
        // dot(probe, labelC) = 0.05f
        val probe = makeUnitProbe(dim, 0.10f, 0.08f, 0.05f)

        // 1. Emitted local classification MUST be empty (no local facts for below-floor image)
        val (localTags, _) = knowledge.classify(probe, ocr = "")
        assertTrue("Local facts must be empty for truly below-floor image", localTags.isEmpty())
        assertEquals("Recognition tier must be unknown when no local facts exist", "unknown", Recognition.recognitionTier(localTags))

        // 2. Candidate labels MUST yield tentative options
        val candidates = knowledge.candidateLabels(probe, limit = 12)
        assertEquals("Must return 3 candidate options", 3, candidates.size)

        // 3. Calibrated probabilities must all tie at the floor (0.03f)
        for (c in candidates) {
            assertEquals("Calibrated score must tie at MIN_CONFIDENCE (0.03f)", 0.03f, c.score, 1e-4f)
        }

        // 4. Raw-margin ordering must be strictly preserved: labelA (0.10) > labelB (0.08) > labelC (0.05)
        assertEquals("pepe", candidates[0].label)
        assertEquals("wojak", candidates[1].label)
        assertEquals("doge", candidates[2].label)
    }

    @Test
    fun belowFloorImage_negativeTeachingVetoesCandidateOption() = runBlocking {
        val dim = 512
        val store = TestStore()
        val engine = TestEngine()
        val knowledge = LocalKnowledge(store, engine)

        val labelA = CuratedLabelDef("pepe", "a pepe frog", "character", listOf("frog"), unitVector(dim, 0))
        val labelB = CuratedLabelDef("wojak", "a wojak face", "character", listOf("feels"), unitVector(dim, 1))
        val labelC = CuratedLabelDef("doge", "a doge shiba", "character", listOf("dog"), unitVector(dim, 2))
        injectCuratedLabels(knowledge, listOf(labelA, labelB, labelC))

        val probe = makeUnitProbe(dim, 0.10f, 0.08f, 0.05f)

        // Initial check without veto: pepe is top candidate
        val beforeVeto = knowledge.candidateLabels(probe, limit = 12)
        assertEquals("pepe", beforeVeto[0].label)

        // Add an explicit negative teaching for "pepe" with identical unit vector:
        // dot(probe, probe) = 1.0f >= PU_EXCLUDE_COS (0.85f)
        store.addTeaching(
            Exemplar(
                id = 99L,
                label = "pepe",
                category = "character",
                vector = probe.copyOf(),
                positive = false,
                associations = emptyList(),
                sourceUri = "",
                model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
            )
        )

        // Negative veto MUST eliminate "pepe" from candidate ranking
        val afterVeto = knowledge.candidateLabels(probe, limit = 12)
        assertEquals("Must have 2 candidates after negative veto", 2, afterVeto.size)
        assertFalse("Vetoed label 'pepe' must not appear in candidate options", afterVeto.any { it.label == "pepe" })
        assertEquals("Top candidate must now be wojak", "wojak", afterVeto[0].label)
        assertEquals("Second candidate must now be doge", "doge", afterVeto[1].label)
    }

    @Test
    fun retag_streamsAllMemes_preservesManualTags_andAppliesNegativeVetoTransition() = runBlocking {
        val dim = 512
        val store = TestStore()
        val engine = TestEngine()
        val knowledge = LocalKnowledge(store, engine)

        val labelA = CuratedLabelDef("pepe", "a pepe frog", "character", listOf("frog"), unitVector(dim, 0))
        injectCuratedLabels(knowledge, listOf(labelA))

        // Populate store with 3 memes:
        // Meme 1 has a user-confirmed manual tag and stale auto tag
        val manualTag = Tag(
            label = "favorite_meme",
            category = "user",
            score = 1.0f,
            source = "manual",
            scoreDomain = "user",
            confirmation = "confirmed",
        )
        val staleAutoTag = Tag(
            label = "stale_tag",
            category = "topic",
            score = 0.2f,
            source = "prompt",
        )

        val probe1 = makeUnitProbe(dim, 0.10f, 0.02f, 0.01f)
        // Meme 2 and Meme 3 have high similarity to labelA (0.25 margin > 0.13 floor)
        val probeStrong = unitVector(dim, 0)

        store.memes[1L] = Meme(
            id = 1L,
            uri = "file:///img_1.jpg",
            name = "img_1.jpg",
            kind = "image",
            ocr = "",
            tags = listOf(manualTag, staleAutoTag),
        )
        store.vectors[1L] = VectorRecord(id = 1L, image = probe1, model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)

        store.memes[2L] = Meme(
            id = 2L,
            uri = "file:///img_2.jpg",
            name = "img_2.jpg",
            kind = "image",
            ocr = "",
            tags = listOf(staleAutoTag),
        )
        store.vectors[2L] = VectorRecord(id = 2L, image = probeStrong, model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)

        store.memes[3L] = Meme(
            id = 3L,
            uri = "file:///img_3.jpg",
            name = "img_3.jpg",
            kind = "image",
            ocr = "",
            tags = listOf(staleAutoTag),
        )
        store.vectors[3L] = VectorRecord(id = 3L, image = probeStrong, model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)

        var completed = false
        knowledge.retag { progress ->
            if (progress.stage == "complete") completed = true
        }
        assertTrue("Retag pass must complete", completed)

        // Verify Meme 1 retained user manual tag and stale auto tag was cleared
        val meme1 = store.memes[1L]!!
        assertTrue("User manual tag must be preserved after retag", meme1.tags.any { it.label == "favorite_meme" && it.source == "manual" })
        assertFalse("Stale auto tag must be replaced", meme1.tags.any { it.label == "stale_tag" })

        // Verify Meme 2 and 3 received the fresh "pepe" auto tag
        val meme2 = store.memes[2L]!!
        assertTrue("Meme 2 must be classified with fresh pepe tag", meme2.tags.any { it.label == "pepe" })
        val meme3 = store.memes[3L]!!
        assertTrue("Meme 3 must be classified with fresh pepe tag", meme3.tags.any { it.label == "pepe" })

        // Transition test: add an explicit negative teaching for "pepe" matching probeStrong
        store.exemplarList.add(
            Exemplar(
                id = 999L,
                label = "pepe",
                category = "character",
                vector = probeStrong.copyOf(),
                positive = false,
                associations = emptyList(),
                sourceUri = "",
                model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT,
            )
        )

        // Run retag again: the negative teaching MUST veto "pepe" on Memes 2 and 3
        knowledge.retag {}

        val meme2AfterVeto = store.memes[2L]!!
        assertFalse("Meme 2 must have pepe tag vetoed by negative teaching", meme2AfterVeto.tags.any { it.label == "pepe" })
        val meme3AfterVeto = store.memes[3L]!!
        assertFalse("Meme 3 must have pepe tag vetoed by negative teaching", meme3AfterVeto.tags.any { it.label == "pepe" })

        // Meme 1 still retains its user manual tag
        val meme1AfterVeto = store.memes[1L]!!
        assertTrue("Meme 1 manual tag remains durable after negative teaching transition", meme1AfterVeto.tags.any { it.label == "favorite_meme" })
    }

    @Test
    fun pureCandidatePromptRanking_preservesBelowFloorMargins() {
        val dim = 512
        val labels = listOf(
            CuratedLabelDef("pepe", "a pepe frog", "character", listOf("frog"), unitVector(dim, 0)),
            CuratedLabelDef("wojak", "a wojak face", "character", listOf("feels"), unitVector(dim, 1)),
        )

        val probe = makeUnitProbe(dim, 0.09f, 0.04f, 0.01f)

        val promptCandidates = LearnCore.rankPromptCandidates(probe, labels, emptyList())
        assertEquals(2, promptCandidates.size)

        // Both margins are strictly below the 0.13 threshold
        assertEquals(0.09f, promptCandidates[0].margin, 1e-5f)
        assertEquals(0.04f, promptCandidates[1].margin, 1e-5f)

        // Both calibrated scores are at the 0.03 floor
        assertEquals(0.03f, promptCandidates[0].tag.score, 1e-5f)
        assertEquals(0.03f, promptCandidates[1].tag.score, 1e-5f)

        // Contrast with classifyPrompts which drops BOTH because 0.09 < 0.13 and 0.04 < 0.13
        val classified = LearnCore.classifyPrompts(probe, labels, emptyList())
        assertTrue("classifyPrompts must suppress all below-floor items", classified.isEmpty())
    }

    @Test
    fun emotionDominance_doesNotCrowdOutIdentityOptions() = runBlocking {
        val dim = 512
        val store = TestStore()
        val engine = TestEngine()
        val knowledge = LocalKnowledge(store, engine)

        // Setup curated labels:
        // High-confidence emotion and action labels
        val emotionLabel = CuratedLabelDef("wholesome", "wholesome content", "emotion", emptyList(), unitVector(dim, 0))
        val actionLabel = CuratedLabelDef("screaming", "screaming loudly", "action", emptyList(), unitVector(dim, 1))
        // Identity options with lower similarity
        val identityA = CuratedLabelDef("pepe", "a pepe frog", "character", listOf("frog"), unitVector(dim, 2))
        val identityB = CuratedLabelDef("wojak", "a wojak face", "character", listOf("feels"), unitVector(dim, 3))
        injectCuratedLabels(knowledge, listOf(emotionLabel, actionLabel, identityA, identityB))

        // Construct probe vector where:
        // dot with emotion = 0.35 (very high)
        // dot with action = 0.30 (high)
        // dot with pepe = 0.10 (below floor)
        // dot with wojak = 0.08 (below floor)
        val probe = FloatArray(dim)
        probe[0] = 0.35f
        probe[1] = 0.30f
        probe[2] = 0.10f
        probe[3] = 0.08f
        val sumSquares = 0.35f * 0.35f + 0.30f * 0.30f + 0.10f * 0.10f + 0.08f * 0.08f
        probe[4] = kotlin.math.sqrt(1.0f - sumSquares)

        // 1. Verify candidateLabels filters out emotion and action BEFORE top-k,
        // so they do not crowd out identity options even when requesting limit = 2
        val candidates = knowledge.candidateLabels(probe, limit = 2)
        assertEquals(2, candidates.size)
        assertEquals("pepe", candidates[0].label)
        assertEquals("wojak", candidates[1].label)
        assertFalse("Emotion labels must not crowd out identity options", candidates.any { it.category == "emotion" })
        assertFalse("Action labels must not crowd out identity options", candidates.any { it.category == "action" })
    }

    @Test fun entityMoodMatchesDoNotConsumeIdentityCandidateLimit() = runBlocking {
        val store = TestStore()
        val knowledge = LocalKnowledge(store, TestEngine())
        injectCuratedLabels(knowledge, listOf(
            CuratedLabelDef("weaker format", "a format", "format", emptyList(), unitVector(512, 2)),
        ))
        listOf(
            Triple("mood one", "emotion", 0),
            Triple("mood two", "emotion", 0),
            Triple("target identity", "character", 1),
        ).forEachIndexed { index, (label, category, axis) ->
            store.exemplarList += Exemplar(index + 1L, label, category, unitVector(512, axis),
                true, emptyList(), "synthetic:$index", ModelRegistry.PRIMARY_MODEL_FINGERPRINT, origin = "pack")
        }
        val candidates = knowledge.candidateLabels(makeUnitProbe(512, 0.8f, 0.2f, 0.18f), limit = 1)
        assertEquals(listOf("target identity"), candidates.map { it.label })
    }

    @Test fun labelQueueAsksFirstAboutTheMemesThatCoverTheMostUnnamedLookalikes() = runBlocking {
        val store = TestStore()
        fun look(axis: Int, wobble: Int? = null) = FloatArray(512).apply {
            this[axis] = 1f
            if (wobble != null) this[wobble] = 0.3f
            val norm = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
            for (k in indices) this[k] /= norm
        }
        fun add(id: Long, image: FloatArray, tags: List<Tag> = emptyList(), rejected: List<String> = emptyList()) {
            store.memes[id] = Meme(id = id, uri = "file:///m$id.jpg", name = "m$id.jpg", kind = "image", ocr = "", tags = tags, rejectedLabels = rejected)
            store.vectors[id] = VectorRecord(id = id, image = image, model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        }
        // Cluster A: four unnamed look-alikes, one a person named, one the vote already labeled.
        add(1, look(0)); add(2, look(0, 5)); add(3, look(0, 6)); add(4, look(0, 7))
        add(5, look(0, 8), tags = listOf(Tag("pepe", "character", 1f, "manual", "user", "confirmed", "user")))
        add(6, look(0, 9), tags = listOf(Tag("pepe", "character", 0.9f, "exemplar", LocalKnowledge.LOOKALIKE_SCORE_DOMAIN, "unreviewed", "learned")))
        // Cluster B: three unnamed; a "not this" alone does not name meme 9.
        add(7, look(1)); add(8, look(1, 10)); add(9, look(1, 11), rejected = listOf("wojak"))
        add(10, look(2))

        val queue = LocalKnowledge(store, TestEngine()).labelQueue()

        assertEquals(listOf(1L, 7L, 10L), queue.map { it.meme.id })
        assertEquals(listOf(3, 2, 0), queue.map { it.lookalikes })
    }

    @Test fun labelQueueFirstAsksAboutTaggedMemesWhoseYesLetsALabelSpread() = runBlocking {
        val store = TestStore()
        fun look(axis: Int, wobble: Int) = FloatArray(512).apply {
            this[axis] = 1f
            this[wobble] = 0.3f
            val norm = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
            for (k in indices) this[k] /= norm
        }
        fun add(id: Long, image: FloatArray, vararg labels: String) {
            store.memes[id] = Meme(id = id, uri = "file:///m$id.jpg", name = "m$id.jpg", kind = "image", ocr = "",
                tags = labels.map { Tag(it, "character", 1f, "manual", "user", "confirmed", "user") })
            store.vectors[id] = VectorRecord(id = id, image = image, model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        }
        // Nine pepe memes; two that show the same thing but were tagged only by what they show; four
        // unlabeled look-alikes; and an unlabeled pair far from every label.
        for (id in 1L..9L) add(id, look(0, 10 + id.toInt()), "pepe")
        add(10, look(0, 30), "tired")
        add(11, look(0, 31), "hug")
        for (id in 20L..23L) add(id, look(0, 30 + id.toInt()))
        add(60, look(1, 61)); add(61, look(1, 62))

        val queue = LocalKnowledge(store, TestEngine()).labelQueue()

        // pepe's held-out votes are right 9 times in 11, the misses being memes 10 and 11; one Yes lifts it to 90%.
        assertEquals(listOf(10L, 11L, 20L, 60L), queue.map { it.meme.id })
        val asks = queue.mapNotNull { it.ask }
        assertEquals(listOf("pepe", "pepe"), asks.map { it.label })
        assertEquals(listOf(1, 1), asks.map { it.needed })
        assertEquals(listOf(4, 4), asks.map { it.unlocks })
        assertEquals(listOf(listOf("tired"), listOf("hug")), asks.map { it.taggedAs })

        // "Yes" on meme 10: pepe now spreads on its own, so nothing asks about it, and its look-alikes get it.
        val ten = store.memes.getValue(10)
        store.memes[10] = ten.copy(tags = ten.tags + Tag("pepe", "character", 1f, "manual", "user", "confirmed", "user"))
        val after = LocalKnowledge(store, TestEngine()).also { injectCuratedLabels(it, emptyList()) }
        assertTrue(after.labelQueue().none { it.ask != null })
        assertTrue(after.classify(store.vectors.getValue(20).image, "", 20L).first.any { it.label == "pepe" })
    }
}
