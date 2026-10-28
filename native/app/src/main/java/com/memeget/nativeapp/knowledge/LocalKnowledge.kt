package com.memeget.nativeapp

import android.content.Context
import com.memeget.nativeapp.data.FloatBlob
import com.memeget.nativeapp.data.SearchText
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.knowledge.CuratedLabelDef
import com.memeget.nativeapp.knowledge.CuratedLabels
import com.memeget.nativeapp.knowledge.EntityRetrieve
import com.memeget.nativeapp.knowledge.LearnCore
import com.memeget.nativeapp.knowledge.LookalikeVote
import com.memeget.nativeapp.knowledge.NegativeAnchorDef
import com.memeget.nativeapp.knowledge.Recognition
import com.memeget.nativeapp.knowledge.Titles
import kotlinx.coroutines.Dispatchers
import com.memeget.nativeapp.inference.NativeBridge
import com.memeget.nativeapp.knowledge.OnDeviceDecisionHead
import com.memeget.nativeapp.knowledge.PairwiseVisualVerifier
import java.io.File
import com.memeget.nativeapp.knowledge.OpenVocabConcepts
import com.memeget.nativeapp.knowledge.LookalikeKernels
import com.memeget.nativeapp.storage.TeachingPackIo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
internal class LocalKnowledge(
    private val store: LibraryStore,
    private val engine: InferenceEngine,
    /** Native in the app; JVM tests keep the default, which gives the same results. */
    private val kernels: LookalikeKernels = LookalikeKernels.Kotlin,
) {
    companion object {
        // Shows stay out: the cloud question asks for a format or character, and a show's raw prompt score,
        // ambiguous between titles, would otherwise count as a confident local answer and skip escalation.
        val IDENTITY_CANDIDATE_CATEGORIES = setOf("format", "character", "person", "object", "topic")
        val REACTION_FACET_CATEGORIES = setOf("emotion", "action", "situation", "tone", "setting", "object")
        /** Marks a tag that came from people's labels on a look-alike meme. */
        const val LOOKALIKE_SCORE_DOMAIN = "lookalike-cosine"
        private const val VECTOR_DIM = 512
        /** Memes a re-tag scores together on [retagScoring] before writing their results in order. */
        private const val RETAG_BATCH = 48
        /** Setting: an unfinished re-tag pass, "<[retagFingerprint]>|<finished meme ids, comma-separated>". */
        private const val RETAG_PROGRESS = "retag.progress"
        /** How often a running pass saves [RETAG_PROGRESS]; a restart redoes at most this much. */
        private const val RETAG_SAVE_NANOS = 10_000_000_000L
        /** Broader template/character cluster cosine once 0.80 near-copy clusters are covered. */
        const val CLUSTER_MIN_COS = 0.68f
        /**
         * The queue after its first card was dealt with: [yes] true for "Yes" to an ask card's label,
         * false for "Not this", null for a skip or any other answer. A "Yes" leaves one fewer needed; once
         * none are, or too few of that label's cards remain to get there, its other cards go.
         */
        fun advance(queue: List<QueueItem>, yes: Boolean?): List<QueueItem> {
            val ask = queue.firstOrNull()?.ask ?: return queue.drop(1)
            val rest = queue.drop(1)
            val needed = if (yes == true) ask.needed - 1 else ask.needed
            if (needed <= 0 || rest.count { it.ask?.label == ask.label } < needed) return rest.filter { it.ask?.label != ask.label }
            return rest.map { item ->
                val other = item.ask
                if (other == null || other.label != ask.label || other.needed == needed) item
                else QueueItem(item.meme, item.image, item.lookalikes, Ask(other.label, other.category, other.taggedAs, needed, other.unlocks))
            }
        }
    }

    private var explicitContext: Context? = null

    constructor(context: Context, store: LibraryStore, engine: InferenceEngine) : this(store, engine) {
        this.explicitContext = context
    }

    private val context: Context?
        get() = explicitContext ?: (store as? SqliteLibraryStore)?.context

    // In-memory cache of curated labels and negative anchors
    @Volatile private var cachedLabels: List<CuratedLabelDef>? = null
    @Volatile private var cachedAnchors: List<NegativeAnchorDef>? = null

    // People's labels as a look-alike vote index, rebuilt only when labels or teachings change.
    private val voteMutex = Mutex()
    private val decisionHeadMutex = Mutex()
    private var cachedDecisionHead: OnDeviceDecisionHead? = null
    private fun verifierHeadFile(): File? = context?.let { app ->
        // Clean up legacy 1024-d d1-omni head file if left behind by an older install.
        File(app.filesDir, "d1_omni_head.bin").delete()
        File(app.filesDir, "verifier_head.bin")
    }

    private fun verifier(): PairwiseVisualVerifier? = PairwiseVisualVerifier.load(context)

    private fun ensureDecisionHeadLocked(dim: Int = OnDeviceDecisionHead.HIDDEN_DIM): OnDeviceDecisionHead {
        cachedDecisionHead?.takeIf { it.hiddenDim == dim }?.let { return it }
        return OnDeviceDecisionHead(hiddenDim = dim).also { head ->
            verifierHeadFile()?.let { file -> head.loadFrom(file) }
            cachedDecisionHead = head
        }
    }
    private var cachedVote: VoteState? = null
    private val curatedMutex = Mutex()
    private suspend fun ensureCuratedLoaded(): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>> =
        curatedMutex.withLock {
            val curLabels = cachedLabels
            val curAnchors = cachedAnchors
            if (curLabels != null && curAnchors != null) return@withLock curLabels to curAnchors
            val (loadedLabels, loadedAnchors) = checkNotNull(CuratedLabels.loadLabelVectors(context, engine))
            cachedLabels = loadedLabels
            cachedAnchors = loadedAnchors
            loadedLabels to loadedAnchors
        }

    /**
     * The bundled labels when they are at hand without the text model: in memory or in the cache file. Null while
     * they are being encoded or have yet to be, so a screen that only wants them never waits minutes for an encode.
     */
    private suspend fun curatedIfReady(): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>>? {
        cachedLabels?.let { labels -> cachedAnchors?.let { return labels to it } }
        val app = context ?: return null
        if (!curatedMutex.tryLock()) return null
        try {
            cachedLabels?.let { labels -> cachedAnchors?.let { return labels to it } }
            val loaded = CuratedLabels.loadLabelVectors(app, engine, encodeIfMissing = false) ?: return null
            cachedLabels = loaded.first
            cachedAnchors = loaded.second
            return loaded
        } finally {
            curatedMutex.unlock()
        }
    }

    @Volatile private var bundledGateAnchors: Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>>? = null

    internal suspend fun curatedForGate(): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>>? {
        bundledGateAnchors?.let { return it }
        val app = context
        if (app != null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    app.assets.open("knowledge/meme_gate_anchors.bin").use { raw ->
                        val bytes = raw.readBytes()
                        if (bytes.size >= 16) {
                            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val magic = buf.int
                            val version = buf.int
                            val nPos = buf.int
                            val nNeg = buf.int
                            val expectedBytes = 16 + (nPos + nNeg) * 512 * 4
                            if (magic == 0x4D474741 && version == 1 && nPos in 1..256 && nNeg in 1..256 && bytes.size == expectedBytes) {
                                val posList = ArrayList<CuratedLabelDef>(nPos)
                                for (i in 0 until nPos) {
                                    val v = FloatArray(512) { buf.float }
                                    posList += CuratedLabelDef("meme_anchor_$i", "meme anchor $i", "Format", emptyList(), v, emptyList())
                                }
                                val negList = ArrayList<NegativeAnchorDef>(nNeg)
                                for (i in 0 until nNeg) {
                                    val v = FloatArray(512) { buf.float }
                                    negList += NegativeAnchorDef("ordinary_anchor_$i", v)
                                }
                                posList to negList
                            } else null
                        } else null
                    }
                }.getOrNull()
            }
            if (loaded != null) {
                bundledGateAnchors = loaded
                return loaded
            }
        }
        return curatedIfReady() ?: runCatching { ensureCuratedLoaded() }.getOrNull()
    }
    /** Title names from the bundled labels, read without their vectors: an answer's category needs only names. */
    @Volatile private var titleNames: Titles.Names? = null
    private suspend fun titleNames(): Titles.Names? {
        titleNames?.let { return it }
        val defs = cachedLabels
            ?: context?.let { app -> withContext(Dispatchers.IO) { CuratedLabels.loadDefinitions(app).first } }
            ?: return null
        return Titles.Names(defs.filter { Titles.isTitleCategory(it.category) }).also { titleNames = it }
    }

    private class VoteState(
        val labelRevision: Long,
        var machineRevision: Long,
        val index: LookalikeVote.Index,
        var d1Index: LookalikeVote.Index,
        val labeled: List<LookalikeVote.LabeledMeme>,
        val exemplars: List<Exemplar>,
        private val kernels: LookalikeKernels,
    ) {
        /** Each voted label's held-out record; measured once per label change, when first needed. */
        val heldOut: Map<String, LookalikeVote.Record> by lazy { LookalikeVote.heldOut(index, labeled, kernels) }
        /** Labels whose held-out votes held up. */
        val reliable: Set<String> by lazy { LookalikeVote.reliableLabels(heldOut) }
        /** Memes a person named; a "not this" alone does not name one. */
        val named: Set<Long> by lazy { labeled.filter { it.labels.isNotEmpty() }.mapTo(HashSet()) { it.memeId } }
    }

    // The pinned recipe passed the original encoder's 32-image/8-text parity gate.
    // Compatibility is limited to teaching packs; filename-only sidecars still require reindexing.
    private fun compatibleTeachingModel(model: String): Boolean =
        ModelRegistry.isCompatiblePrimaryModel(model) || model == TeachingPackIo.PACK_MODEL

    private suspend fun buildD1Index(
        humanExamples: List<LookalikeVote.Example>,
        negatives: Map<String, List<FloatArray>>,
        archiveExemplars: List<Exemplar> = emptyList(),
    ): LookalikeVote.Index {
        val d1Examples = ArrayList<LookalikeVote.Example>(humanExamples.size + archiveExemplars.size + 1024)
        d1Examples.addAll(humanExamples)
        val humanPairs = humanExamples.mapTo(HashSet()) { "${it.memeId}:${it.label}" }
        val machineCountByLabel = HashMap<String, Int>()
        for (ae in archiveExemplars) {
            if (ae.vector.size != 512 || !ae.positive) continue
            val label = TagMerge.normalizeTagLabel(ae.label)
            val curCnt = machineCountByLabel[label] ?: 0
            if (label.isEmpty() || curCnt >= 4) continue
            machineCountByLabel[label] = curCnt + 1
            d1Examples += LookalikeVote.Example(ae.vector, label, ae.category, LookalikeVote.NO_MEME)
        }
        for (mRef in store.machineReferenceLabels()) {
            for (t in mRef.labels) {
                val label = TagMerge.normalizeTagLabel(t.label)
                val curCnt = machineCountByLabel[label] ?: 0
                if (label.isEmpty() || curCnt >= 4 || !humanPairs.add("${mRef.memeId}:$label")) continue
                machineCountByLabel[label] = curCnt + 1
                d1Examples += LookalikeVote.Example(mRef.image, label, t.category, mRef.memeId)
            }
        }
        return LookalikeVote.Index(d1Examples, negatives)
    }

    private suspend fun ensureVote(): VoteState = voteMutex.withLock {
        val revision = store.labelRevision.value
        val curMachineRev = store.revision.value
        cachedVote?.takeIf { it.labelRevision == revision }?.let { cached ->
            if (cached.machineRevision != curMachineRev) {
                val archEx = store.exemplars().filter { compatibleTeachingModel(it.model) && it.origin == "archive-d1" }
                cached.d1Index = buildD1Index(cached.index.examples, cached.index.negatives, archEx)
                cached.machineRevision = curMachineRev
            }
            return@withLock cached
        }
        val examples = ArrayList<LookalikeVote.Example>()
        val negatives = LinkedHashMap<String, MutableList<FloatArray>>()
        val taught = HashMap<String, MutableList<FloatArray>>()
        val allCompatible = store.exemplars().filter { compatibleTeachingModel(it.model) }
        val compatibleExemplars = allCompatible.filter { it.origin != "archive-d1" }
        val archiveExemplars = allCompatible.filter { it.origin == "archive-d1" }
        for (e in compatibleExemplars) {
            if (e.vector.size != 512) continue
            val label = TagMerge.normalizeTagLabel(e.label)
            if (e.positive) {
                examples += LookalikeVote.Example(e.vector, label, e.category, LookalikeVote.NO_MEME)
                taught.getOrPut(label) { ArrayList() } += e.vector
            } else {
                negatives.getOrPut(label) { ArrayList() } += e.vector
            }
        }
        val labeled = ArrayList<LookalikeVote.LabeledMeme>()
        for (h in store.humanLabels()) {
            val labels = LinkedHashSet<String>()
            for (t in h.labels) {
                val label = TagMerge.normalizeTagLabel(t.label)
                if (!labels.add(label)) continue
                // A teaching of this exact picture already stands for it in the vote.
                if (taught[label]?.any { FloatBlob.dot(it, h.image) >= LookalikeVote.SAME_IMAGE_COS } == true) continue
                examples += LookalikeVote.Example(h.image, label, t.category, h.memeId)
            }
            val rejected = h.rejected.mapTo(LinkedHashSet()) { TagMerge.normalizeTagLabel(it) }
            for (label in rejected) negatives.getOrPut(label) { ArrayList() } += h.image
            labeled += LookalikeVote.LabeledMeme(h.memeId, h.image, labels, rejected)
        }
        val humanIndex = LookalikeVote.Index(examples, negatives)
        val d1Idx = buildD1Index(examples, negatives, archiveExemplars)
        VoteState(revision, curMachineRev, humanIndex, d1Idx, labeled, compatibleExemplars, kernels).also { cachedVote = it }
    }

    private fun findVetoedLabelsFromMap(image: FloatArray, negByLabel: Map<String, List<FloatArray>>): Set<String> {
        val vetoed = HashSet<String>()
        for ((label, negVecs) in negByLabel) {
            val maxCos = FloatBlob.maxCosTo(image, negVecs)
            if (maxCos >= LearnCore.PU_EXCLUDE_COS) {
                vetoed.add(label)
            }
        }
        return vetoed
    }

    private data class ClassificationSnapshot(
        val labels: List<CuratedLabelDef>,
        val negativeVecs: List<FloatArray>,
        val vote: VoteState,
        val exemplars: List<Exemplar>,
        val negExemplarsByLabel: Map<String, List<FloatArray>>,
        val assocMap: Map<String, List<String>>,
        val titles: Titles,
    )

    @Volatile private var cachedSnapshot: ClassificationSnapshot? = null

    private suspend fun prepareSnapshot(): ClassificationSnapshot = snapshotWith(ensureCuratedLoaded())

    /** The snapshot when the label vectors are at hand without encoding them ([curatedIfReady]), else null. */
    private suspend fun snapshotIfReady(): ClassificationSnapshot? = curatedIfReady()?.let { snapshotWith(it) }

    private suspend fun snapshotWith(curated: Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>>): ClassificationSnapshot {
        val (labels, anchors) = curated
        val vote = ensureVote()
        // Teachings change only with the label revision, so their derived maps live as long as the vote.
        cachedSnapshot?.takeIf { it.vote === vote }?.let { return it }
        val allExemplars = vote.exemplars
        val negativeVecs = anchors.map { it.vec }

        val negExemplarsByLabel = LinkedHashMap<String, MutableList<FloatArray>>()
        for (e in allExemplars) {
            if (!e.positive) {
                val norm = TagMerge.normalizeTagLabel(e.label)
                negExemplarsByLabel.getOrPut(norm) { ArrayList() }.add(e.vector)
            }
        }

        val assocMap = HashMap<String, MutableList<String>>()
        for (l in labels) {
            if (l.associations.isNotEmpty()) {
                assocMap.getOrPut(TagMerge.normalizeTagLabel(l.label)) { ArrayList() }.addAll(l.associations)
            }
        }
        for (e in allExemplars) {
            if (e.associations.isNotEmpty()) {
                assocMap.getOrPut(TagMerge.normalizeTagLabel(e.label)) { ArrayList() }.addAll(e.associations)
            }
        }

        val titles = Titles(
            labels,
            negativeVecs,
            vote.labeled,
            allExemplars.map { Titles.Teaching(it.vector, it.label, it.positive) },
        )

        return ClassificationSnapshot(
            labels = labels,
            negativeVecs = negativeVecs,
            vote = vote,
            exemplars = allExemplars,
            negExemplarsByLabel = negExemplarsByLabel,
            assocMap = assocMap,
            titles = titles,
        ).also { cachedSnapshot = it }
    }

    /** [memeId] keeps a meme's own labels out of the vote on it. */
    suspend fun classify(image: FloatArray, ocr: String, memeId: Long = LookalikeVote.NO_MEME): Pair<List<Tag>, String> =
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val snapshot = prepareSnapshot()
            val dots = OpenVocabConcepts.load(context)?.imageDots(image)
            val verifierScores = scoreWithVerifier(image = image, ocrAndText = ocr, snapshot = snapshot, memeId = memeId, conceptDots = dots)
            classifyInternal(image, ocr, snapshot, memeId, verifierScores, dots)
        }

    private fun classifyInternal(
        image: FloatArray,
        ocr: String,
        snapshot: ClassificationSnapshot,
        memeId: Long,
        verifierScores: Map<String, Float>? = null,
        /** This image's [OpenVocabConcepts.imageDots], shared with [scoreWithVerifier]; computed here when null. */
        conceptDots: OpenVocabConcepts.ImageDots? = null,
    ): Pair<List<Tag>, String> {
        val vetoedLabels = findVetoedLabelsFromMap(image, snapshot.negExemplarsByLabel)
        val openConcepts = OpenVocabConcepts.load(context)
        val candidateHits = openConcepts?.retrieveCandidates(image, ocrAndText = ocr, limit = 8, dots = conceptDots).orEmpty()
        val rawOpenTags = candidateHits
            .filter { it.source == "z_top1" || it.posCos >= 0.78f }
            .map {
                Tag(
                    label = it.label,
                    category = it.category,
                    score = it.posCos,
                    source = "open_vocab",
                    scoreDomain = "open-vocab",
                    confirmation = "unreviewed",
                    provenance = "open-vocab",
                )
            }
        val openTags = if (!verifierScores.isNullOrEmpty()) {
            val hitByLabel = candidateHits.associateBy { it.label }
            val verified = rawOpenTags.mapNotNull { t ->
                val p = verifierScores[t.label] ?: return@mapNotNull t
                val isStrongZ = hitByLabel[t.label]?.source == "z_top1" || t.score >= 0.86f
                if (isStrongZ && p >= 0.25f) t.copy(score = maxOf(t.score, 0.5f * t.score + 0.5f * p))
                else if (p >= 0.52f) t.copy(score = 0.5f * t.score + 0.5f * p)
                else null
            }.toMutableList()
            val existing = verified.mapTo(HashSet()) { it.label }
            // Promote borderline visual/taught candidates when the pairwise visual verifier + live PU head strongly back them.
            for (h in candidateHits) {
                if (h.label in existing || h.posCos < 0.68f) continue
                val p = verifierScores[h.label] ?: continue
                if (p >= 0.82f) {
                    verified += Tag(
                        label = h.label,
                        category = h.category,
                        score = p,
                        source = "open_vocab",
                        scoreDomain = "pairwise-verified",
                        confirmation = "unreviewed",
                        provenance = "pairwise-verifier",
                    )
                    existing += h.label
                }
            }
            verified
        } else {
            rawOpenTags
        }
        val allPromptTags = LearnCore.classifyPrompts(image, snapshot.labels, snapshot.negativeVecs)
        val promptTags = if (openTags.isEmpty()) {
            allPromptTags
        } else {
            allPromptTags.filter {
                it.category.lowercase() in REACTION_FACET_CATEGORIES && it.score >= Recognition.RECOGNIZED_CONFIDENCE
            }
        }
        // 2. People's labels on look-alikes: copies of a labeled picture always; otherwise the vote
        // winner once its label has held up on held-out examples. Unproven labels stay suggestions.
        val index = snapshot.vote.index
        val lookalike = LinkedHashMap<String, Tag>()
        for (v in LookalikeVote.samePicture(index, image, excludeMeme = memeId)) lookalike[v.label] = lookalikeTag(v)
        LookalikeVote.winner(index, image, excludeMeme = memeId)
            ?.takeIf { it.label in snapshot.vote.reliable && it.label !in lookalike }
            ?.let { lookalike[it.label] = lookalikeTag(it) }

        // 3. OCR tags
        val ocrTags = CuratedLabels.ocrTags(ocr)

        var visualMerged = if (openTags.isNotEmpty()) {
            openTags + promptTags + lookalike.values.toList()
        } else {
            LearnCore.mergeClassified(promptTags, lookalike.values.toList(), topK = 6)
        }
        val hasOpenTitle = openTags.any { Titles.isTitleCategory(it.category) }
        if (!hasOpenTitle) {
            snapshot.titles.identify(image, memeId)?.let { hit ->
                val title = hit.tag
                val sameName = visualMerged.filter { snapshot.titles.titleNamed(it.label) == hit.title }
                val spread = sameName.filter { it.source == "exemplar" }.map { it.copy(category = title.category) }
                visualMerged = visualMerged - sameName.toSet() + spread +
                    listOfNotNull(title.takeIf { spread.isEmpty() && !hit.personsTag })
            }
        }
        val cap = if (openTags.isNotEmpty()) 12 else 6
        val durableMerged = TagMerge.mergeDurableTags(visualMerged + ocrTags, capAuto = cap)
        // Explicit negative veto eliminates vetoed labels across every branch
        val vetoFiltered = durableMerged.filter { !vetoedLabels.contains(TagMerge.normalizeTagLabel(it.label)) }
        return Pair(vetoFiltered, extraTermsFor(vetoFiltered, snapshot.assocMap))
    }

    private fun lookalikeTag(v: LookalikeVote.Vote) = Tag(
        label = v.label,
        category = v.category,
        score = v.cosine,
        source = "exemplar",
        scoreDomain = LOOKALIKE_SCORE_DOMAIN,
        confirmation = "unreviewed",
        provenance = "learned",
    )

    // Search terms for a final tag list: each label, its association words and context terms.
    private fun extraTermsFor(tags: List<Tag>, assocMap: Map<String, List<String>>): String {
        val terms = LinkedHashSet<String>()
        for (t in tags) {
            val norm = TagMerge.normalizeTagLabel(t.label)
            terms.add(norm)
            for (w in norm.split(Regex("\\s+"))) {
                if (w.isNotBlank()) terms.add(w)
            }
            for (a in assocMap[norm].orEmpty() + OpenVocabConcepts.parentAliasesFor(norm)) {
                for (w in a.lowercase().split(Regex("\\s+"))) {
                    if (w.isNotBlank()) terms.add(w)
                }
            }
        }
        for (w in SearchText.classificationContextTerms(tags = tags.map { it.label }).split(' ')) {
            if (w.isNotBlank()) terms.add(w)
        }
        return terms.joinToString(" ")
    }

    suspend fun candidateLabels(image: FloatArray, limit: Int = 12): List<Tag> = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val snapshot = prepareSnapshot()
        val labels = snapshot.labels
        val vote = snapshot.vote
        val vetoedLabels = findVetoedLabelsFromMap(image, snapshot.negExemplarsByLabel)
        val negativeVecs = snapshot.negativeVecs

        // Tentative prompt candidates without margin floor suppression
        val promptCandidates = LearnCore.rankPromptCandidates(image, labels, negativeVecs)

        // People's labels among the nearest examples, without the cutoff
        val lookalikeCandidates = LookalikeVote.rank(vote.index, image)

        // Entity retrieval without suppressing below-floor margins
        val anchorMax = if (negativeVecs.isNotEmpty()) FloatBlob.maxCosTo(image, negativeVecs) else 0f
        val entityHits = EntityRetrieve.retrieveEntities(
            imageVec = image,
            exemplars = snapshot.exemplars,
            anchorSim = anchorMax,
            minMargin = Float.NEGATIVE_INFINITY,
            topK = limit,
            categories = IDENTITY_CANDIDATE_CATEGORIES,
        )

        data class ScoredCandidate(val tag: Tag, val rawMargin: Float)
        val candidates = ArrayList<ScoredCandidate>()

        for (cp in promptCandidates) {
            if (cp.tag.category.trim().lowercase() in IDENTITY_CANDIDATE_CATEGORIES) {
                candidates.add(ScoredCandidate(cp.tag, cp.margin))
            }
        }
        for (v in lookalikeCandidates) {
            if (v.category.trim().lowercase() in IDENTITY_CANDIDATE_CATEGORIES) {
                candidates.add(ScoredCandidate(lookalikeTag(v), v.cosine))
            }
        }
        for (eh in entityHits) {
            val tag = Tag(
                label = eh.label.lowercase(),
                category = eh.category,
                score = eh.score,
                source = "entity_pack",
                scoreDomain = "calibrated",
                confirmation = "unreviewed",
                provenance = "pack",
            )
            candidates.add(ScoredCandidate(tag, eh.margin))
        }
        val bestByLabel = LinkedHashMap<String, ScoredCandidate>()
        for (c in candidates) {
            val norm = TagMerge.normalizeTagLabel(c.tag.label)
            if (vetoedLabels.contains(norm)) continue
            val prev = bestByLabel[norm]
            if (prev == null) {
                bestByLabel[norm] = c
            } else {
                val scoreCmp = c.tag.score.compareTo(prev.tag.score)
                if (scoreCmp > 0 || (scoreCmp == 0 && c.rawMargin > prev.rawMargin)) {
                    bestByLabel[norm] = c
                }
            }
        }

        bestByLabel.values.sortedWith { a, b ->
            val scoreCmp = b.tag.score.compareTo(a.tag.score)
            if (scoreCmp != 0) scoreCmp else b.rawMargin.compareTo(a.rawMargin)
        }.take(limit).map { it.tag }
    }

    /**
     * Up to [limit] of people's labels to ask about for this meme: the look-alike vote's labels
     * first, then the nearest examples of other labels. Labels the meme already carries from a
     * person, or rejected, and labels a closer "not X" contradicts are left out. A label that names a
     * TV show or movie is asked about as that title. When a title is a likely answer, no person gave
     * the meme one and no label above asks about it, it takes the last place.
     */
    suspend fun suggestFor(meme: Meme, image: FloatArray, limit: Int = 3): List<Tag> = withContext(Dispatchers.Default) {
        val vote = ensureVote()
        val names = titleNames()
        val settled = HashSet<String>()
        for (t in meme.tags) if (TagMerge.isHumanTag(t)) settled += TagMerge.normalizeTagLabel(t.label)
        for (label in meme.rejectedLabels) settled += TagMerge.normalizeTagLabel(label)
        // Only while the label vectors are at hand: the Label tab never waits minutes for them to be encoded.
        val title = snapshotIfReady()?.titles?.question(image, meme.id)
            ?.let { Tag(it.label.lowercase(), it.category) }
            ?.takeIf { it.label !in settled }
        val out = LinkedHashMap<String, Tag>()
        val pvActive = verifier() != null
        val openConcepts = OpenVocabConcepts.load(context)
        val openActive = pvActive || openConcepts != null
        val retrievalIndex = if (openActive) vote.d1Index else vote.index
        val memeText = "${meme.ocr} ${meme.transcript}".trim()
        val openVotes = openConcepts?.retrieveCandidates(image, ocrAndText = memeText, limit = 6).orEmpty().map {
            LookalikeVote.Vote(it.label, it.category, it.posCos, it.posCos, maxOf(1, it.voteCount))
        }
        val combinedByLabel = LinkedHashMap<String, LookalikeVote.Vote>()
        for (v in openVotes + LookalikeVote.rank(retrievalIndex, image, excludeMeme = meme.id) +
            LookalikeVote.nearestPerLabel(retrievalIndex, image, excludeMeme = meme.id)) {
            val prev = combinedByLabel[v.label]
            if (prev == null || v.cosine > prev.cosine) combinedByLabel[v.label] = v
        }
        if (openActive) {
            val implied = ArrayList<LookalikeVote.Vote>()
            for (v in combinedByLabel.values) {
                if (v.cosine < 0.90f) continue
                val parents = OpenVocabConcepts.PARENT_IMPLICATIONS[v.label] ?: continue
                for ((pLbl, pCat) in parents) {
                    val impCos = (v.cosine - 0.02f).coerceAtLeast(0.76f)
                    val prev = combinedByLabel[pLbl]
                    if (prev == null || impCos > prev.cosine) {
                        implied += LookalikeVote.Vote(pLbl, pCat, impCos, impCos, maxOf(1, v.count))
                    }
                }
            }
            for (iv in implied) {
                val prev = combinedByLabel[iv.label]
                if (prev == null || iv.cosine > prev.cosine) combinedByLabel[iv.label] = iv
            }
        }
        val ranked = combinedByLabel.values.sortedByDescending { it.cosine }
        val candidatePoolLimit = if (pvActive) maxOf(limit, 8) else limit
        val familyCounts = HashMap<String, Int>()
        for (v in ranked) {
            if (out.size >= candidatePoolLimit) break
            if (v.label in settled || v.label in out || LookalikeVote.contradicted(vote.index, v, image)) continue
            if (openActive) {
                val fam = OpenVocabConcepts.rootFamily(v.label)
                val cnt = familyCounts[fam] ?: 0
                if (cnt >= 2) continue
                familyCounts[fam] = cnt + 1
            }
            val named = names?.titleNamed(v.label)
            out[v.label] = lookalikeTag(v).let { if (named != null) it.copy(category = named.category) else it }
        }
        val mustIncludeSet = if (title != null) out.keys + title.label else out.keys
        val pvScores = if (pvActive && mustIncludeSet.isNotEmpty()) {
            val snap = snapshotIfReady()
            runCatching {
                scoreWithVerifier(
                    image = image,
                    ocrAndText = memeText,
                    snapshot = snap,
                    voteOverride = vote,
                    memeId = meme.id,
                    mustInclude = mustIncludeSet,
                )
            }.getOrNull()
        } else null
        val scoredTitle = title?.let { t -> pvScores?.get(t.label)?.let { p -> t.copy(score = p) } ?: t }
        val asked = if (!pvScores.isNullOrEmpty()) {
            out.values.map { t -> pvScores[t.label]?.let { p -> t.copy(score = p) } ?: t }
                .sortedByDescending { it.score }
                .take(limit)
        } else {
            out.values.take(limit)
        }
        if (scoredTitle == null || asked.any { it.label == scoredTitle.label || names?.titleNamed(it.label)?.label?.lowercase() == scoredTitle.label }) asked
        else if (pvActive && scoredTitle.score < (asked.lastOrNull()?.score ?: 0f)) asked
        else asked.take(limit - 1) + scoredTitle
    }

    /**
     * Builds pairwise visual-verifier inputs from [vote], blending leave-one-out human/archive exemplar
     * centroids with [OpenVocabConcepts] prototypes so brand-new 1-shot taught labels and 6,000+ bundled
     * concepts share one fast (<0.1 ms) visual subspace.
     */
    private fun buildVerifierCandidates(
        vote: VoteState,
        image: FloatArray,
        memeId: Long,
        curatedByNormLabel: Map<String, CuratedLabelDef> = emptyMap(),
        mustInclude: Set<String> = emptySet(),
        limit: Int = 12,
        ocrAndText: String = "",
        conceptDots: OpenVocabConcepts.ImageDots? = null,
    ): List<PairwiseVisualVerifier.VerifierCandidate> {
        val openConcepts = OpenVocabConcepts.load(context)
        val topRank = LookalikeVote.rank(vote.d1Index, image, excludeMeme = memeId, skipSamePicture = false)
        val voteCounts = HashMap<String, Int>()
        for (v in topRank) voteCounts[v.label] = v.count
        val nearestAll = LookalikeVote.nearestPerLabel(vote.d1Index, image, excludeMeme = memeId)
        val byLabel = LinkedHashMap<String, LookalikeVote.Vote>()
        for (v in topRank + nearestAll) byLabel.putIfAbsent(v.label, v)
        val openByLabel = HashMap<String, OpenVocabConcepts.Hit>()
        for (oh in openConcepts?.retrieveCandidates(image, ocrAndText = ocrAndText, limit = 10, dots = conceptDots).orEmpty()) {
            openByLabel[oh.label] = oh
            val prev = byLabel[oh.label]
            if (prev == null || oh.posCos > prev.cosine) {
                byLabel[oh.label] = LookalikeVote.Vote(oh.label, oh.category, oh.posCos, oh.posCos, maxOf(prev?.count ?: 0, oh.voteCount))
            }
        }
        val impliedVotes = ArrayList<LookalikeVote.Vote>()
        for (v in byLabel.values) {
            if (v.cosine < 0.90f) continue
            val parents = OpenVocabConcepts.PARENT_IMPLICATIONS[v.label] ?: continue
            for ((pLbl, pCat) in parents) {
                val impCos = (v.cosine - 0.02f).coerceAtLeast(0.76f)
                val prev = byLabel[pLbl]
                if (prev == null || impCos > prev.cosine) {
                    impliedVotes += LookalikeVote.Vote(pLbl, pCat, impCos, impCos, maxOf(1, v.count))
                }
            }
        }
        for (iv in impliedVotes) {
            val prev = byLabel[iv.label]
            if (prev == null || iv.cosine > prev.cosine) byLabel[iv.label] = iv
        }

        val orderedLabels = ArrayList<String>()
        val familyCounts = HashMap<String, Int>()
        for (req in mustInclude) {
            val norm = TagMerge.normalizeTagLabel(req)
            if (norm.isNotEmpty() && norm !in orderedLabels) {
                orderedLabels += norm
                val fam = OpenVocabConcepts.rootFamily(norm)
                familyCounts[fam] = (familyCounts[fam] ?: 0) + 1
            }
        }
        for (label in byLabel.values.sortedByDescending { it.cosine }.map { it.label }) {
            if (orderedLabels.size >= limit) break
            if (label in orderedLabels) continue
            val fam = OpenVocabConcepts.rootFamily(label)
            val cnt = familyCounts[fam] ?: 0
            if (cnt >= 2) continue
            familyCounts[fam] = cnt + 1
            orderedLabels += label
        }

        val entityCats = setOf("person", "character", "movie", "tv", "format")
        val out = ArrayList<PairwiseVisualVerifier.VerifierCandidate>(orderedLabels.size)
        for (label in orderedLabels.take(maxOf(limit, mustInclude.size))) {
            val v = byLabel[label]
            val oh = openByLabel[label]
            val ovEntry = openConcepts?.entryFor(label)
            val curated = curatedByNormLabel[label]

            // Leave-one-out exemplar centroid for labels with taught/human examples
            val exVecs = vote.d1Index.examples.filter { it.label == label && it.memeId != memeId }
            val selfEx = if (exVecs.isEmpty()) vote.d1Index.examples.firstOrNull { it.label == label && it.memeId == memeId } else null
            val protoVec: FloatArray
            val cacheKey: String?
            when {
                exVecs.isNotEmpty() -> {
                    val sum = FloatArray(VECTOR_DIM)
                    for (ex in exVecs) {
                        for (d in 0 until VECTOR_DIM) sum[d] += ex.vector[d]
                    }
                    if (ovEntry != null) {
                        val scale = 0.35f * exVecs.size
                        for (d in 0 until VECTOR_DIM) sum[d] = 0.65f * sum[d] + scale * ovEntry.cHybrid[d]
                    }
                    protoVec = normalizeVec(sum) ?: continue
                    cacheKey = null
                }
                ovEntry != null -> {
                    protoVec = ovEntry.cHybrid
                    cacheKey = "ov:${ovEntry.label}"
                }
                selfEx != null -> {
                    protoVec = selfEx.vector
                    cacheKey = null
                }
                curated != null && curated.vec.size == VECTOR_DIM -> {
                    protoVec = curated.vec
                    cacheKey = "cur:$label"
                }
                else -> continue
            }

            val posCos = maxOf(v?.cosine ?: 0f, oh?.posCos ?: 0f).let {
                if (it > 0f) it else if (selfEx != null) 0.78f else FloatBlob.dot(image, protoVec).coerceAtLeast(0f)
            }
            val cat = (oh?.category ?: ovEntry?.category ?: v?.category ?: curated?.category ?: "topic").lowercase()
            val otherSup70 = exVecs.count { FloatBlob.dot(it.vector, image) >= 0.70f }
            val sup70 = maxOf(otherSup70, oh?.support70 ?: 0).let { if (it > 0) it else if (selfEx != null) 1 else 0 }
            val vCnt = maxOf(voteCounts[label] ?: 0, oh?.voteCount ?: 0).let { if (it > 0) it else if (selfEx != null) 1 else 0 }
            var maxNeg = -1f
            for (nv in vote.index.negatives[label].orEmpty()) {
                val c = FloatBlob.dot(nv, image)
                if (c < LookalikeVote.SAME_IMAGE_COS && c > maxNeg) maxNeg = c
            }
            val isEnt = ovEntry?.isEntity ?: (cat in entityCats)
            val ocrHit = openConcepts?.matchesOcr(label, isEnt, ocrAndText) == true
            out += PairwiseVisualVerifier.VerifierCandidate(
                key = label,
                protoVec = protoVec,
                textVec = ovEntry?.tStock ?: curated?.vec?.takeIf { it.size == VECTOR_DIM },
                cacheKey = cacheKey,
                calPosCos = posCos,
                tagMean = ovEntry?.tagMean ?: 0.20f,
                tagStd = ovEntry?.tagStd ?: 0.045f,
                hasStats = ovEntry != null,
                negCos = maxNeg,
                voteCount = vCnt,
                support70 = sup70,
                ocrHit = ocrHit,
                isEntity = isEnt,
            )
        }
        return out
    }

    private fun normalizeVec(v: FloatArray): FloatArray? {
        var sq = 0.0
        for (x in v) sq += x.toDouble() * x
        val n = kotlin.math.sqrt(sq).toFloat()
        if (!n.isFinite() || n <= 1e-9f) return null
        return FloatArray(v.size) { i -> v[i] / n }
    }

    /**
     * Scores candidate labels in <0.1 ms using the bundled ~543 KB [PairwiseVisualVerifier] plus
     * the persisted [OnDeviceDecisionHead] (`deltaW`). Zero external GGUF download or native RAM pin.
     */
    private suspend fun scoreWithVerifier(
        image: FloatArray,
        ocrAndText: String,
        snapshot: ClassificationSnapshot? = null,
        voteOverride: VoteState? = null,
        memeId: Long = LookalikeVote.NO_MEME,
        mustInclude: Set<String> = emptySet(),
        conceptDots: OpenVocabConcepts.ImageDots? = null,
    ): Map<String, Float>? {
        val pv = verifier() ?: return null
        if (image.size != VECTOR_DIM) return null
        val vote = voteOverride ?: snapshot?.vote ?: ensureVote()
        val curatedMap = snapshot?.labels?.associateBy { TagMerge.normalizeTagLabel(it.label) }.orEmpty()
        val cands = buildVerifierCandidates(
            vote = vote,
            image = image,
            memeId = memeId,
            curatedByNormLabel = curatedMap,
            mustInclude = mustInclude,
            limit = 12,
            ocrAndText = ocrAndText,
            conceptDots = conceptDots,
        )
        if (cands.isEmpty()) return emptyMap()
        return decisionHeadMutex.withLock {
            val head = ensureDecisionHeadLocked(pv.headDim)
            val sample = pv.evaluate(image, cands)
            head.scoreCandidates(sample)
        }
    }

    /**
     * Extracts 64-d penultimate visual-verifier features for [memeId], refits the live residual
     * PU scorer (`deltaW`) across all taught memes in <0.5 ms, and persists `verifier_head.bin`.
     */
    suspend fun recordVerifierTeaching(memeId: Long): Float? = withContext(Dispatchers.Default) {
        val pv = verifier() ?: return@withContext null
        val meme = store.meme(memeId) ?: return@withContext null
        val vec = store.vectors(memeId)?.image?.takeIf { it.size == VECTOR_DIM } ?: return@withContext null
        val confirmedPos = meme.tags.filter(TagMerge::isHumanTag).mapTo(LinkedHashSet()) { TagMerge.normalizeTagLabel(it.label) }
        val confirmedRej = meme.rejectedLabels.mapTo(LinkedHashSet()) { TagMerge.normalizeTagLabel(it) }
        val snap = snapshotIfReady()
        val vote = snap?.vote ?: ensureVote()
        val curatedMap = snap?.labels?.associateBy { TagMerge.normalizeTagLabel(it.label) }.orEmpty()
        val cands = buildVerifierCandidates(
            vote = vote,
            image = vec,
            memeId = memeId,
            curatedByNormLabel = curatedMap,
            mustInclude = confirmedPos + confirmedRej,
            limit = 12,
            ocrAndText = "${meme.ocr} ${meme.transcript}".trim(),
        )
        if (cands.isEmpty()) return@withContext null
        val hasConfirmedTitle = cands.any { label ->
            val norm = label.key
            norm in confirmedPos && snap?.titles?.titleNamed(norm) != null
        }
        if (hasConfirmedTitle && snap != null) {
            for (c in cands) {
                if (snap.titles.titleNamed(c.key) != null && c.key !in confirmedPos) {
                    confirmedRej += c.key
                }
            }
        }
        decisionHeadMutex.withLock {
            val head = ensureDecisionHeadLocked(pv.headDim)
            if (confirmedPos.isEmpty() && confirmedRej.isEmpty()) {
                val loss = head.upsertAndFit(memeId, null)
                verifierHeadFile()?.let { head.saveTo(it) }
                return@withLock loss
            }
            val sample = pv.evaluate(vec, cands)
            val ex = OnDeviceDecisionHead.TrainingExample(
                sample = sample,
                confirmedPositives = confirmedPos,
                confirmedRejections = confirmedRej,
            )
            val loss = head.upsertAndFit(memeId, ex)
            verifierHeadFile()?.let { head.saveTo(it) }
            loss
        }
    }

    /**
     * Warms `verifier_head.bin` and cleans up any retired 407 MB `d1-omni` GGUF artifact on disk —
     * never initiates a network download.
     */
    suspend fun loadPersistedVerifierHead(): Int = withContext(Dispatchers.IO) {
        context?.let { app ->
            for (dirName in listOf("memeget_models", "models")) {
                val dir = File(app.filesDir, dirName)
                ModelRegistry.RETIRED_DECISION_FILES.forEach { File(dir, it).delete() }
            }
        }
        val dim = verifier()?.headDim ?: OnDeviceDecisionHead.HIDDEN_DIM
        decisionHeadMutex.withLock {
            ensureDecisionHeadLocked(dim).exampleCount()
        }
    }

    /**
     * Whether a meme a person tagged only as [taggedAs] also shows [label], the label the vote gives it.
     * [needed] more "Yes" answers like this let [label] apply on its own, labeling the [unlocks] unlabeled
     * memes it wins.
     */
    class Ask(val label: String, val category: String, val taggedAs: List<String>, val needed: Int, val unlocks: Int)

    /** A meme worth labeling next and how many unlabeled look-alikes it stands for, or an [ask] about a labeled one. */
    class QueueItem(val meme: Meme, val image: FloatArray, val lookalikes: Int, val ask: Ask? = null)

    /**
     * Memes to ask about. First [asks]: one "Yes" there can let a label spread to many memes. Then the
     * order that spreads labels furthest: repeatedly the meme with the most unlabeled look-alikes
     * (cosine >= [LookalikeVote.MIN_COS]) that no earlier pick covers. Memes a person named, and memes
     * the last classification already gave one of people's labels, are left out of that part; a
     * "not this" alone does not name a meme, so it stays.
     *
     * Lazy greedy in O(n) memory: a meme's look-alike count bounds what picking it can still cover
     * and covering only lowers it, so a re-counted meme that still tops every other bound is the
     * best pick.
     */
    suspend fun labelQueue(limit: Int = 60): List<QueueItem> = withContext(Dispatchers.Default) {
        // The vote is cached per label change and the first card's suggestions need it anyway.
        val vote = ensureVote()
        val named = vote.named
        val spread = store.idsWithScoreDomain(LOOKALIKE_SCORE_DOMAIN)
        val vectors = store.imageVectors()
        val asks = asks(vote, vectors)
        // Ids and vectors suffice to rank; only the picked memes are loaded whole.
        val ids = vectors.keys.filter { it !in named && it !in spread }.sorted()
        val images = ids.map { vectors.getValue(it) }
        val n = ids.size
        val flat = FloatArray(n * VECTOR_DIM)
        for (i in 0 until n) System.arraycopy(images[i], 0, flat, i * VECTOR_DIM, VECTOR_DIM)
        currentCoroutineContext().ensureActive()
        val covered = BooleanArray(n)
        val picked = ArrayList<QueueItem>()
        suspend fun pickClusters(minCos: Float, includeSingletons: Boolean) {
            if (picked.size >= limit || n == 0) return
            // Each look-alike count bounds what picking that meme can cover.
            val counts = kernels.counts(flat, VECTOR_DIM, minCos)
            val bound = IntArray(n) { if (covered[it]) 0 else 1 + counts[it] }
            val order = PriorityQueue<Int>(maxOf(1, n), compareByDescending<Int> { bound[it] }.thenBy { it })
            for (i in 0 until n) if (!covered[i] && (includeSingletons || bound[i] > 1)) order += i
            while (picked.size < limit) {
                currentCoroutineContext().ensureActive()
                val i = order.poll() ?: break
                if (covered[i]) continue
                val found = kernels.lookalikesOf(flat, VECTOR_DIM, i, covered, minCos)
                val gain = 1 + found.size
                if (!includeSingletons && gain <= 1) continue
                while (order.peek()?.let { covered[it] } == true) order.poll()
                val next = order.peek()
                // Accept only if the exact gain still wins the heap's order, ties going to the lower index.
                if (next != null && (gain < bound[next] || gain == bound[next] && next < i)) {
                    bound[i] = gain
                    order += i
                    continue
                }
                covered[i] = true
                for (j in found) covered[j] = true
                val meme = store.meme(ids[i]) ?: continue // deleted since the read
                picked += QueueItem(meme, images[i], found.size)
            }
        }
        pickClusters(LookalikeVote.MIN_COS, includeSingletons = false)
        pickClusters(CLUSTER_MIN_COS, includeSingletons = true)
        asks + picked
    }

    /**
     * Cards asking whether a meme a person tagged otherwise also shows the label the vote gives it, for
     * labels that so few "Yes" answers would let apply on their own. Labels that would then label the
     * most unlabeled memes come first; each label's memes go from the closest vote down.
     */
    private suspend fun asks(vote: VoteState, vectors: Map<Long, FloatArray>): List<QueueItem> {
        class Candidate(val label: String, val record: LookalikeVote.Record, val needed: Int)
        val candidates = vote.heldOut.mapNotNull { (label, record) ->
            if (record.reliable || record.total < LookalikeVote.RELIABLE_SUPPORT) return@mapNotNull null
            // Each "Yes" turns one of the label's open misses into a hit.
            (1..record.open.size).firstOrNull { record.hits + it >= LookalikeVote.RELIABLE_PRECISION * record.total }
                ?.let { Candidate(label, record, it) }
        }
        if (candidates.isEmpty()) return emptyList()
        currentCoroutineContext().ensureActive()
        val unnamed = vectors.keys.filter { it !in vote.named }.sorted()
        val wins = LookalikeVote.winners(vote.index, unnamed.map { vectors.getValue(it) }, kernels)
            .filterNotNull().groupingBy { it.label }.eachCount()
        val names = titleNames()
        val out = ArrayList<QueueItem>()
        for (c in candidates.sortedWith(compareByDescending<Candidate> { wins[it.label] ?: 0 }.thenBy { it.needed }.thenBy { it.label })) {
            val unlocks = wins[c.label] ?: continue
            // A label that names a TV show or movie is asked about, and saved, as that title.
            val category = names?.titleNamed(c.label)?.category
                ?: vote.index.examples.firstOrNull { it.label == c.label }?.category ?: "topic"
            val cards = c.record.open.sortedByDescending { it.cosine }.mapNotNull { open ->
                val meme = store.meme(open.memeId) ?: return@mapNotNull null // deleted since the vote
                val image = vectors[open.memeId] ?: return@mapNotNull null
                QueueItem(meme, image, 0, Ask(c.label, category, meme.tags.filter(TagMerge::isHumanTag).map { it.label }, c.needed, unlocks))
            }
            if (cards.size >= c.needed) out += cards
        }
        return out
    }

    /** The category for an answer [label]: a TV show's or movie's own, else what people's examples give it; "topic" for a new label. */
    suspend fun knownCategory(label: String): String {
        titleNames()?.titleNamed(label)?.let { return it.category }
        val norm = TagMerge.normalizeTagLabel(label)
        val vote = cachedVote ?: ensureVote()
        return vote.index.examples.firstOrNull { it.label == norm }?.category ?: "topic"
    }

    /** A re-tag pass that ended early (the session limit, a Stop): the label revision it used and the memes it finished. */
    private class UnfinishedRetag(val labelRevision: Long, val finished: HashSet<Long>)
    @Volatile private var unfinishedRetag: UnfinishedRetag? = null

    /** How often a running pass saves [RETAG_PROGRESS]; tests set 0 to save after every batch. */
    internal var retagSaveIntervalNanos: Long = RETAG_SAVE_NANOS

    /**
     * What a pass's finished memes depend on, stable across restarts (the label revision is not): the installed
     * build, the curated labels, the teachings, and each meme people labeled: its labels, its "not this" answers and
     * its picture. Per meme, not per label: a "not X" that moved from one meme to another keeps every count the same.
     */
    private fun retagFingerprint(snap: ClassificationSnapshot): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun put(s: String) {
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        val installed = context?.let { app ->
            runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrNull()
        } ?: 0L
        put("B:$installed")
        for (l in snap.labels) put("L:${l.label}:${l.category}")
        for (x in snap.vote.exemplars.sortedBy { it.id }) put("X:${x.id}:${x.label}:${x.category}:${x.positive}")
        for (e in snap.vote.index.examples.map { "E:${it.memeId}:${it.label}:${it.category}" }.sorted()) put(e)
        val picture = java.nio.ByteBuffer.allocate(VECTOR_DIM * 4)
        for (m in snap.vote.labeled.sortedBy { it.memeId }) {
            put("M:${m.memeId}:${m.labels.sorted()}:${m.rejected.sorted()}")
            picture.clear()
            for (i in 0 until minOf(VECTOR_DIM, m.image.size)) picture.putInt(java.lang.Float.floatToRawIntBits(m.image[i]))
            md.update(picture.array(), 0, picture.position())
        }
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    /** The pass saved in [RETAG_PROGRESS] when it was made under [fingerprint]'s labels, else null. */
    private suspend fun savedRetag(labelRevision: Long, fingerprint: String): UnfinishedRetag? {
        val saved = store.setting(RETAG_PROGRESS).orEmpty()
        val sep = saved.indexOf('|')
        if (sep < 0 || saved.substring(0, sep) != fingerprint) return null
        val ids = saved.substring(sep + 1).split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
        return UnfinishedRetag(labelRevision, ids)
    }

    /** Half the cores, at most 4: a re-tag runs while the person is in the app, so the UI keeps its share. */
    private val retagScoring = Dispatchers.Default.limitedParallelism((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))

    /**
     * Re-tags the library. A pass that ended early is resumed where it stopped while the labels and teachings behind
     * it are unchanged, also after a restart ([RETAG_PROGRESS]); after a change it starts over. A change while the
     * pass runs sends it round the library again for the memes it did with the older ones. A meme whose tags come out
     * the same is not written. Passes run one at a time (IndexCoordinator holds its session lock). Memes are scored
     * [RETAG_BATCH] at a time on [retagScoring] (scoring only reads the snapshot); their writes, the finished set and
     * progress stay on this coroutine, in order.
     */
    suspend fun retag(progress: (WorkProgress) -> Unit) = withContext(Dispatchers.Default) {
        progress(WorkProgress(stage = "retag", running = true, detail = "Preparing knowledge…"))
        var snapshot = prepareSnapshot()
        var expectedRevision = store.revision.value
        val total = store.countAll()
        var fingerprint = retagFingerprint(snapshot)
        var pass = unfinishedRetag?.takeIf { it.labelRevision == snapshot.vote.labelRevision }
            ?: savedRetag(snapshot.vote.labelRevision, fingerprint)
            ?: UnfinishedRetag(snapshot.vote.labelRevision, HashSet())
        // Kept while the pass runs, so a pass cut short leaves what it finished for the next one.
        unfinishedRetag = pass
        var roundAgain = false
        var progressSaved = store.setting(RETAG_PROGRESS).orEmpty().isNotEmpty()
        var savedAt = System.nanoTime()

        // The tags and search words to store for [meme] under [snap]; null when it has no usable vectors or comes out
        // unchanged.
        suspend fun retagged(meme: Meme, snap: ClassificationSnapshot): Pair<List<Tag>, String>? {
            val vectors = store.vectors(meme.id) ?: return null
            if (vectors.image.isEmpty() || !ModelRegistry.isCompatiblePrimaryModel(vectors.model)) return null
            // One scan of the ~6,000 open-vocabulary concepts, shared by the verifier and classification.
            val dots = OpenVocabConcepts.load(context)?.imageDots(vectors.image)
            val verifierScores = scoreWithVerifier(
                image = vectors.image,
                ocrAndText = "${meme.ocr} ${meme.transcript}".trim(),
                snapshot = snap,
                memeId = meme.id,
                conceptDots = dots,
            )
            val (freshTags, _) = classifyInternal(
                image = vectors.image,
                ocr = meme.ocr,
                snapshot = snap,
                memeId = meme.id,
                verifierScores = verifierScores,
                conceptDots = dots,
            )
            // Machine tags classification can't regenerate stay unless a nearby "not X" vetoes
            // them; human-confirmed tags always stay. Recomputed sources are replaced outright.
            val vetoed = findVetoedLabelsFromMap(vectors.image, snap.negExemplarsByLabel)
            val kept = meme.tags.filter {
                TagMerge.survivesReclassification(it) &&
                    (TagMerge.isHumanTag(it) || !vetoed.contains(TagMerge.normalizeTagLabel(it.label)))
            }.map { tag ->
                if (!TagMerge.isHumanTag(tag) || Titles.isTitleCategory(tag.category)) tag
                else snap.titles.titleNamed(tag.label)?.let { tag.copy(category = it.category) } ?: tag
            }
            val merged = TagMerge.mergeDurableTags(freshTags + kept)
            val terms = extraTermsFor(merged, snap.assocMap)
            // Most memes come out unchanged on a later pass; skipping their writes is what lets a slow
            // phone finish one. Compared as the store keeps them: without the machine tags and search
            // words a person rejected on this meme.
            val rejected = meme.rejectedLabels.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it) }
            val stored = merged.filter { TagMerge.isHumanTag(it) || TagMerge.normalizeTagLabel(it.label) !in rejected }
            val changed = stored.toSet() != meme.tags.toSet() || TagMerge.withoutRejectedTerms(terms, rejected) != meme.extraTerms
            return if (changed) merged to terms else null
        }

        // A write outside this pass's own (e.g. user teach/correction) refreshes the snapshot; a teaching that moved
        // the labels starts the pass over, so memes done with the older labels are done again with these.
        suspend fun refreshIfChangedOutside() {
            if (store.revision.value == expectedRevision) return
            snapshot = prepareSnapshot()
            expectedRevision = store.revision.value
            if (snapshot.vote.labelRevision != pass.labelRevision) {
                pass = UnfinishedRetag(snapshot.vote.labelRevision, HashSet())
                unfinishedRetag = pass
                fingerprint = retagFingerprint(snapshot)
                roundAgain = true
            }
        }

        suspend fun runBatch(batch: List<Meme>) {
            currentCoroutineContext().ensureActive()
            refreshIfChangedOutside()
            val todo = batch.filter { it.id !in pass.finished }
            if (todo.isEmpty()) return
            val snap = snapshot
            val results = coroutineScope { todo.map { meme -> async(retagScoring) { retagged(meme, snap) } }.awaitAll() }
            for ((i, meme) in todo.withIndex()) {
                results[i]?.let { (merged, terms) ->
                    store.updateTags(meme.id, merged, terms)
                    expectedRevision++
                }
                pass.finished += meme.id
                val done = pass.finished.size
                if (done % 5 == 0 || done == total) {
                    progress(
                        WorkProgress(
                            stage = "retag",
                            done = done,
                            total = total,
                            detail = "Re-tagging $done / $total",
                            running = true,
                        )
                    )
                }
            }
            if (System.nanoTime() - savedAt >= retagSaveIntervalNanos) {
                store.setSetting(RETAG_PROGRESS, "$fingerprint|${pass.finished.joinToString(",")}")
                expectedRevision++
                progressSaved = true
                savedAt = System.nanoTime()
            }
            // Checked again once the batch is written: a teaching that landed while it was scored or written must still
            // send the pass round again, even when this was the library's last batch.
            refreshIfChangedOutside()
        }

        do {
            roundAgain = false
            // Stream bounded rows without materializing the whole library
            val batch = ArrayList<Meme>(RETAG_BATCH)
            store.allMemes().collect { meme ->
                batch += meme
                if (batch.size >= RETAG_BATCH) {
                    runBatch(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) runBatch(batch)
        } while (roundAgain)

        unfinishedRetag = null
        if (progressSaved) store.setSetting(RETAG_PROGRESS, "")
        progress(WorkProgress(stage = "complete", done = pass.finished.size, total = total, running = false))
    }

    /**
     * Bounded Transductive Template Cluster Propagation (arXiv:2501.06031 frozen-graph + PairwiseVisualVerifier):
     * spreads verified template, character, and show identities across visually matching variations in the library
     * without copying OCR or captions and without a manual Label quiz tab.
     */
    suspend fun propagateClusterIdentities(maxSeeds: Int = 512, maxTargets: Int = 1024): Int = withContext(Dispatchers.Default) {
        val imgVecs = store.imageVectors()
        if (imgVecs.size < 2) return@withContext 0
        val entityCats = setOf("format", "character", "person", "tv", "movie")
        val allRows = ArrayList<Meme>(minOf(imgVecs.size, maxOf(maxSeeds, maxTargets) * 2))
        store.allMemes().collect { m ->
            if (!m.pending && imgVecs.containsKey(m.id) && allRows.size < maxOf(maxSeeds, maxTargets) * 2) {
                allRows += m
            }
        }
        if (allRows.size < 2) return@withContext 0

        fun signSketch64(v: FloatArray): Long {
            var bits = 0L
            val lim = minOf(64, v.size)
            for (b in 0 until lim) {
                if (v[b] >= 0f) bits = bits or (1L shl b)
            }
            return bits
        }

        data class Seed(val memeId: Long, val vec: FloatArray, val sketch: Long, val tag: Tag)
        val rawSeeds = ArrayList<Seed>()
        for (m in allRows) {
            val v = imgVecs[m.id] ?: continue
            val sk = signSketch64(v)
            for (t in m.tags) {
                val cat = t.category.lowercase(java.util.Locale.ROOT)
                if (cat !in entityCats) continue
                if (TagMerge.isHumanTag(t) || (t.source != "propagated" && t.score >= 0.84f)) {
                    rawSeeds += Seed(m.id, v, sk, t)
                }
            }
        }
        if (rawSeeds.isEmpty()) return@withContext 0
        rawSeeds.sortWith(compareByDescending<Seed> { TagMerge.isHumanTag(it.tag) }.thenByDescending { it.tag.score })
        val seeds = rawSeeds.take(maxSeeds)
        val targets = allRows.take(maxTargets)

        val pv = verifier()
        val openConcepts = OpenVocabConcepts.load(context)
        var updatedCount = 0
        for (target in targets) {
            currentCoroutineContext().ensureActive()
            val tv = imgVecs[target.id] ?: continue
            val tSketch = signSketch64(tv)
            val existingNorm = target.tags.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it.label) }
            val rejectedNorm = target.rejectedLabels.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it) }

            val bestByLabel = LinkedHashMap<String, Pair<Seed, Float>>()
            for (s in seeds) {
                if (s.memeId == target.id) continue
                if (java.lang.Long.bitCount(tSketch xor s.sketch) > 20) continue
                val norm = TagMerge.normalizeTagLabel(s.tag.label)
                if (norm.isEmpty() || norm in existingNorm || norm in rejectedNorm) continue
                val cos = FloatBlob.dot(tv, s.vec)
                if (cos >= 0.83f) {
                    val prev = bestByLabel[norm]
                    if (prev == null || cos > prev.second) bestByLabel[norm] = s to cos
                }
            }
            if (bestByLabel.isEmpty()) continue
            val propagated = ArrayList<Tag>(2)
            for ((norm, pair) in bestByLabel.entries.sortedByDescending { it.value.second }.take(3)) {
                val (seed, cos) = pair
                val ov = openConcepts?.entryFor(norm)
                val effectiveProto = if (ov != null && FloatBlob.dot(seed.vec, ov.cHybrid) >= 0.40f) {
                    val sum = FloatArray(VECTOR_DIM) { d -> 0.65f * seed.vec[d] + 0.35f * ov.cHybrid[d] }
                    normalizeVec(sum) ?: seed.vec
                } else {
                    seed.vec
                }
                val verified = if (TagMerge.isHumanTag(seed.tag) && cos >= com.memeget.nativeapp.data.TagPropagation.PROPAGATE_MIN_COS_PRIMARY) {
                    true
                } else if (pv != null) {
                    val sample = pv.evaluate(
                        tv,
                        listOf(
                            PairwiseVisualVerifier.VerifierCandidate(
                                key = norm,
                                protoVec = effectiveProto,
                                textVec = ov?.tStock?.takeIf { FloatBlob.dot(seed.vec, ov.cHybrid) >= 0.40f },
                                calPosCos = cos,
                                tagMean = ov?.tagMean ?: 0.20f,
                                tagStd = ov?.tagStd ?: 0.045f,
                                hasStats = ov != null && FloatBlob.dot(seed.vec, ov.cHybrid) >= 0.40f,
                                voteCount = 2,
                                support70 = 2,
                                isEntity = true,
                            ),
                        ),
                    )
                    OnDeviceDecisionHead.sigmoid(sample.baseLogits[0]) >= 0.65f
                } else cos >= 0.87f

                if (verified) {
                    propagated += Tag(
                        label = norm,
                        category = seed.tag.category,
                        score = cos,
                        source = "propagated",
                        scoreDomain = "cluster-propagation",
                        confirmation = "unreviewed",
                        provenance = "cluster:${seed.memeId}",
                    )
                    if (propagated.size >= 2) break
                }
            }
            if (propagated.isNotEmpty()) {
                val merged = TagMerge.mergeDurableTags(target.tags + propagated)
                var extra = target.extraTerms
                for (pt in propagated) {
                    extra = TagMerge.termsWithLabel(extra, pt.label)
                    for (parent in OpenVocabConcepts.parentAliasesFor(pt.label)) {
                        extra = TagMerge.termsWithLabel(extra, parent)
                    }
                }
                store.updateTags(target.id, merged, extra)
                updatedCount++
            }
        }
        updatedCount
    }
}
