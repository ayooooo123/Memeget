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
import java.io.File
import com.memeget.nativeapp.knowledge.OpenVocabConcepts
import com.memeget.nativeapp.knowledge.LookalikeKernels
import com.memeget.nativeapp.storage.TeachingPackIo
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
    private val evalSampleCache = object : LinkedHashMap<Long, OnDeviceDecisionHead.EvalSample>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, OnDeviceDecisionHead.EvalSample>?): Boolean = size > 128
    }
    private fun decisionHeadFile(): File? = context?.let { File(it.filesDir, "d1_omni_head.bin") }

    private fun d1ModelFile(): File? = context?.let { app ->
        val fname = ModelRegistry.D1_OMNI_DECISION_MODEL.filename
        File(app.filesDir, "memeget_models/$fname").takeIf(File::isFile)
            ?: File(app.filesDir, "models/$fname").takeIf(File::isFile)
    }

    private fun ensureDecisionHeadLocked(): OnDeviceDecisionHead {
        cachedDecisionHead?.let { return it }
        return OnDeviceDecisionHead().also { head ->
            decisionHeadFile()?.let { file -> head.loadFrom(file) }
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
        model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT || model == TeachingPackIo.PACK_MODEL

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
            val d1Scores = if (d1ModelFile() != null) {
                val stubMeme = (if (memeId > 0L) runCatching { store.meme(memeId) }.getOrNull() else null)
                    ?: Meme(id = memeId, uri = "", name = "", kind = "image", ocr = ocr)
                runCatching { scoreWithD1Omni(stubMeme, image) }.getOrNull()
            } else null
            classifyInternal(image, ocr, snapshot, memeId, d1Scores)
        }

    private fun classifyInternal(
        image: FloatArray,
        ocr: String,
        snapshot: ClassificationSnapshot,
        memeId: Long,
        d1Scores: Map<String, Float>? = null,
    ): Pair<List<Tag>, String> {
        val vetoedLabels = findVetoedLabelsFromMap(image, snapshot.negExemplarsByLabel)
        val rawOpenTags = OpenVocabConcepts.load(context)?.classifyLibraryMeme(image, ocr, memeId).orEmpty()
        val openTags = if (!d1Scores.isNullOrEmpty()) {
            rawOpenTags.mapNotNull { t ->
                if (t.scoreDomain == "open-vocab-verified") t
                else d1Scores[t.label]?.takeIf { it >= 0.78f }?.let { p -> t.copy(score = p) }
            }
        } else {
            rawOpenTags
        }
        // 1. Zero-shot prompt tags with anchor bias: full prompt set when open_vocab abstains,
        //    plus high-confidence reaction/emotion/vibe facets even when open_vocab names an entity.
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
        val d1Active = d1ModelFile() != null
        val openConcepts = OpenVocabConcepts.load(context)
        val openActive = d1Active || openConcepts != null
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
        val candidatePoolLimit = if (d1Active) maxOf(limit, 6) else limit
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
        val d1Scores = if (d1Active && mustIncludeSet.isNotEmpty()) {
            runCatching { scoreWithD1Omni(meme, image, mustInclude = mustIncludeSet) }.getOrNull()
        } else null
        val scoredTitle = title?.let { t -> d1Scores?.get(t.label)?.let { p -> t.copy(score = p) } ?: t }
        val asked = if (!d1Scores.isNullOrEmpty()) {
            out.values.map { t -> d1Scores[t.label]?.let { p -> t.copy(score = p) } ?: t }
                .sortedByDescending { it.score }
                .take(limit)
        } else {
            out.values.take(limit)
        }
        if (scoredTitle == null || asked.any { it.label == scoredTitle.label || names?.titleNamed(it.label)?.label?.lowercase() == scoredTitle.label }) asked
        else if (d1Active && scoredTitle.score < (asked.lastOrNull()?.score ?: 0f)) asked
        else asked.take(limit - 1) + scoredTitle
    }

    /**
     * Builds live candidate inputs from [vote] for `d1-omni-600M`, leaving out [memeId]'s own examples so
     * training and evaluation see the same leave-one-out similarity distribution.
     */
    private fun buildD1Candidates(
        vote: VoteState,
        image: FloatArray,
        memeId: Long,
        mustInclude: Set<String> = emptySet(),
        limit: Int = 6,
        ocrAndText: String = "",
    ): List<OnDeviceDecisionHead.CandidateInput> {
        val topRank = LookalikeVote.rank(vote.d1Index, image, excludeMeme = memeId, skipSamePicture = false)
        val voteCounts = HashMap<String, Int>()
        for (v in topRank) voteCounts[v.label] = v.count
        val nearestAll = LookalikeVote.nearestPerLabel(vote.d1Index, image, excludeMeme = memeId)
        val byLabel = LinkedHashMap<String, LookalikeVote.Vote>()
        for (v in topRank + nearestAll) byLabel.putIfAbsent(v.label, v)
        val openByLabel = HashMap<String, OpenVocabConcepts.Hit>()
        for (oh in OpenVocabConcepts.load(context)?.retrieveCandidates(image, ocrAndText = ocrAndText, limit = 8).orEmpty()) {
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
        val sortedLabels = byLabel.values.sortedByDescending { it.cosine }.map { it.label }

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
        for (label in sortedLabels) {
            if (orderedLabels.size >= limit) break
            if (label in orderedLabels) continue
            val fam = OpenVocabConcepts.rootFamily(label)
            val cnt = familyCounts[fam] ?: 0
            if (cnt >= 2) continue
            familyCounts[fam] = cnt + 1
            orderedLabels += label
        }

        return orderedLabels.take(limit).map { label ->
            val v = byLabel[label]
            val oh = openByLabel[label]
            val hasSelfExemplar = v == null && vote.d1Index.examples.any { it.label == label && it.memeId == memeId }
            val posCos = maxOf(v?.cosine ?: 0f, oh?.posCos ?: 0f).let { if (it > 0f) it else if (hasSelfExemplar) 0.78f else 0.50f }
            val cat = oh?.category ?: v?.category ?: vote.d1Index.examples.firstOrNull { it.label == label }?.category ?: "topic"
            val otherSup70 = vote.d1Index.examples.count {
                it.label == label && it.memeId != memeId && FloatBlob.dot(it.vector, image) >= 0.70f
            }
            val sup70 = maxOf(otherSup70, oh?.support70 ?: 0).let { if (it > 0) it else if (hasSelfExemplar) 1 else 0 }
            val vCnt = maxOf(voteCounts[label] ?: 0, oh?.voteCount ?: 0).let { if (it > 0) it else if (hasSelfExemplar) 1 else 0 }
            val negVecs = vote.index.negatives[label].orEmpty()
            var maxNeg = -1f
            for (nv in negVecs) {
                val c = FloatBlob.dot(nv, image)
                if (c < LookalikeVote.SAME_IMAGE_COS && c > maxNeg) maxNeg = c
            }
            OnDeviceDecisionHead.CandidateInput(
                label = label,
                category = cat,
                posCos = posCos,
                seedCos = posCos,
                voteCount = vCnt,
                support70 = sup70,
                negCos = maxNeg,
            )
        }
    }

    /**
     * Scores [meme]'s candidate labels using the on-device `d1-omni-600M` GGUF runner plus the persisted
     * `OnDeviceDecisionHead` (`deltaW`, `deltaB`). Returns null when the GGUF model is not present on disk.
     */
    suspend fun scoreWithD1Omni(
        meme: Meme,
        image: FloatArray,
        mustInclude: Set<String> = emptySet(),
    ): Map<String, Float>? = withContext(Dispatchers.Default) {
        val modelFile = d1ModelFile() ?: return@withContext null
        val vote = ensureVote()
        val cands = buildD1Candidates(vote, image, meme.id, mustInclude = mustInclude, limit = 6, ocrAndText = "${meme.ocr} ${meme.transcript}".trim())
        if (cands.isEmpty()) return@withContext emptyMap()
        decisionHeadMutex.withLock {
            val head = ensureDecisionHeadLocked()
            val cues = meme.tags.filter { !TagMerge.isHumanTag(it) && it.source in setOf("prompt", "vision", "title") }.map { it.label }
            val (prompt, keys) = head.renderPrompt(meme.ocr, meme.transcript, meme.caption, cands, visualCues = cues)
            val pHash = prompt.hashCode()
            val sample = head.cachedExample(meme.id)?.sample?.takeIf { it.optionKeys == keys && it.promptHash == pHash }
                ?: evalSampleCache[meme.id]?.takeIf { it.optionKeys == keys && it.promptHash == pHash }
                ?: head.unpackNativeOutput(NativeBridge.nativeDecide(modelFile.absolutePath, prompt, 2), keys, pHash)
                    .also { evalSampleCache[meme.id] = it }
            head.scoreCandidates(sample)
        }
    }

    /**
     * Extracts `<|mask|>` scorer features for [memeId] via `LlamaDecisionRunner`, refits the on-device
     * residual scorer head (`deltaW`, `deltaB`) across all cached taught memes, and persists `d1_omni_head.bin`.
     */
    suspend fun recordD1Teaching(memeId: Long): Float? = withContext(Dispatchers.Default) {
        val modelFile = d1ModelFile() ?: return@withContext null
        val meme = store.meme(memeId) ?: return@withContext null
        val vec = store.vectors(memeId)?.image?.takeIf { it.size == VECTOR_DIM } ?: return@withContext null
        val confirmedPos = meme.tags.filter(TagMerge::isHumanTag).mapTo(LinkedHashSet()) { TagMerge.normalizeTagLabel(it.label) }
        val confirmedRej = meme.rejectedLabels.mapTo(LinkedHashSet()) { TagMerge.normalizeTagLabel(it) }
        val vote = ensureVote()
        val cands = buildD1Candidates(vote, vec, memeId, mustInclude = confirmedPos + confirmedRej, limit = 6, ocrAndText = "${meme.ocr} ${meme.transcript}".trim())
        if (cands.isEmpty()) return@withContext null
        val hasConfirmedTitle = cands.any { it.label in confirmedPos && (it.category == "tv" || it.category == "movie") }
        if (hasConfirmedTitle) {
            for (c in cands) {
                if ((c.category == "tv" || c.category == "movie") && c.label !in confirmedPos) {
                    confirmedRej += c.label
                }
            }
        }
        decisionHeadMutex.withLock {
            val head = ensureDecisionHeadLocked()
            if (confirmedPos.isEmpty() && confirmedRej.isEmpty()) {
                val loss = head.upsertAndFit(memeId, null)
                decisionHeadFile()?.let { head.saveTo(it) }
                return@withLock loss
            }
            val cues = meme.tags.filter { !TagMerge.isHumanTag(it) && it.source in setOf("prompt", "vision", "title") }.map { it.label }
            val (prompt, keys) = head.renderPrompt(meme.ocr, meme.transcript, meme.caption, cands, visualCues = cues)
            val pHash = prompt.hashCode()
            val sample = head.cachedExample(memeId)?.sample?.takeIf { it.optionKeys == keys && it.promptHash == pHash }
                ?: evalSampleCache[memeId]?.takeIf { it.optionKeys == keys && it.promptHash == pHash }
                ?: head.unpackNativeOutput(NativeBridge.nativeDecide(modelFile.absolutePath, prompt, 2), keys, pHash)
                    .also { evalSampleCache[memeId] = it }
            val ex = OnDeviceDecisionHead.TrainingExample(
                sample = sample,
                confirmedPositives = confirmedPos,
                confirmedRejections = confirmedRej,
            )
            val loss = head.upsertAndFit(memeId, ex)
            decisionHeadFile()?.let { head.saveTo(it) }
            loss
        }
    }

    /**
     * Loads `d1_omni_head.bin` from disk on startup; if not yet initialized and `d1-omni-600M` is installed,
     * adapts the head on the most recent human-labeled meme so the persisted head is ready immediately.
     */
    suspend fun loadPersistedD1Head(): Int = withContext(Dispatchers.IO) {
        runCatching {
            if (store.setting("knowledge.clean_sota_sync") != "v4") {
                val concepts = OpenVocabConcepts.load(context)
                if (concepts != null && concepts.refMemes.isNotEmpty()) {
                    val vecMap = store.imageVectors()
                    val cleanMap = LinkedHashMap<Long, List<Tag>>(vecMap.size)
                    for ((id, vec) in vecMap) {
                        val cTags = concepts.classifyLibraryMeme(vec, memeId = id, allowReferenceMatch = true)
                        if (cTags.isNotEmpty()) cleanMap[id] = cTags
                    }
                    if (cleanMap.isNotEmpty()) {
                        store.importMachineTags(cleanMap)
                        store.setSetting("knowledge.clean_sota_sync", "v4")
                    }
                }
            }
        }
        val count = decisionHeadMutex.withLock {
            ensureDecisionHeadLocked().exampleCount()
        }
        if (d1ModelFile() == null) {
            runCatching { (engine as? NativeInferenceEngine)?.ensureModel(ModelRegistry.D1_OMNI_DECISION_MODEL) }
        }
        count
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

    /**
     * Re-tags the library. A pass that ended early is resumed where it stopped while the labels and teachings behind
     * it are unchanged; after a change it starts over. A change while the pass runs sends it round the library again
     * for the memes it did with the older ones. A meme whose tags come out the same is not written. Passes run one at
     * a time (IndexCoordinator holds its session lock), so the finished set has one writer.
     */
    suspend fun retag(progress: (WorkProgress) -> Unit) = withContext(Dispatchers.Default) {
        progress(WorkProgress(stage = "retag", running = true, detail = "Preparing knowledge…"))
        var snapshot = prepareSnapshot()
        var expectedRevision = store.revision.value
        val total = store.countAll()
        var pass = unfinishedRetag?.takeIf { it.labelRevision == snapshot.vote.labelRevision }
            ?: UnfinishedRetag(snapshot.vote.labelRevision, HashSet())
        // Kept while the pass runs, so a pass cut short leaves what it finished for the next one.
        unfinishedRetag = pass

        do {
            var roundAgain = false
            // Stream bounded rows without materializing the whole library
            store.allMemes().collect { meme ->
                currentCoroutineContext().ensureActive()
                if (store.revision.value != expectedRevision) {
                    // Store was mutated concurrently outside this pass's writes (e.g. user teach/correction)
                    snapshot = prepareSnapshot()
                    expectedRevision = store.revision.value
                    if (snapshot.vote.labelRevision != pass.labelRevision) {
                        // Memes done with the older labels are done again with these, in another round.
                        pass = UnfinishedRetag(snapshot.vote.labelRevision, HashSet())
                        unfinishedRetag = pass
                        roundAgain = true
                    }
                }
                if (meme.id in pass.finished) return@collect

                val vectors = store.vectors(meme.id)
                if (vectors != null && vectors.image.isNotEmpty() && vectors.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
                    val (freshTags, _) = classifyInternal(
                        image = vectors.image,
                        ocr = meme.ocr,
                        snapshot = snapshot,
                        memeId = meme.id,
                    )

                    // Machine tags classification can't regenerate stay unless a nearby "not X" vetoes
                    // them; human-confirmed tags always stay. Recomputed sources are replaced outright.
                    val vetoed = findVetoedLabelsFromMap(vectors.image, snapshot.negExemplarsByLabel)
                    val kept = meme.tags.filter {
                        TagMerge.survivesReclassification(it) &&
                            (TagMerge.isHumanTag(it) || !vetoed.contains(TagMerge.normalizeTagLabel(it.label)))
                    }.map { tag ->
                        if (!TagMerge.isHumanTag(tag) || Titles.isTitleCategory(tag.category)) tag
                        else snapshot.titles.titleNamed(tag.label)?.let { tag.copy(category = it.category) } ?: tag
                    }
                    val merged = TagMerge.mergeDurableTags(freshTags + kept)
                    val terms = extraTermsFor(merged, snapshot.assocMap)
                    // Most memes come out unchanged on a later pass; skipping their writes is what lets a slow
                    // phone finish one. Compared as the store keeps them: without the machine tags and search
                    // words a person rejected on this meme.
                    val rejected = meme.rejectedLabels.mapTo(HashSet()) { TagMerge.normalizeTagLabel(it) }
                    val stored = merged.filter { TagMerge.isHumanTag(it) || TagMerge.normalizeTagLabel(it.label) !in rejected }
                    if (stored.toSet() != meme.tags.toSet() || TagMerge.withoutRejectedTerms(terms, rejected) != meme.extraTerms) {
                        store.updateTags(meme.id, merged, terms)
                        expectedRevision++
                    }
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
        } while (roundAgain)

        unfinishedRetag = null
        progress(WorkProgress(stage = "complete", done = pass.finished.size, total = total, running = false))
    }
}
