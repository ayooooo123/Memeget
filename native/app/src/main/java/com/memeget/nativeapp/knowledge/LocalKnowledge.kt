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
import com.memeget.nativeapp.knowledge.NegativeAnchorDef
import kotlinx.coroutines.Dispatchers
import com.memeget.nativeapp.storage.TeachingPackIo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
internal class LocalKnowledge(
    private val store: LibraryStore,
    private val engine: InferenceEngine,
) {
    companion object {
        val IDENTITY_CANDIDATE_CATEGORIES = setOf("format", "character", "person", "object", "topic")
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

    // Cache of trained exemplar heads and background mean
    private var cachedHeads: List<LearnCore.LabelHead> = emptyList()
    private var cachedMean: FloatArray? = null
    private var cachedTrainingKey: String = ""
    private var cachedTrainingRevision = -1L
    private val trainingMutex = Mutex()
    private val curatedMutex = Mutex()
    private suspend fun ensureCuratedLoaded(): Pair<List<CuratedLabelDef>, List<NegativeAnchorDef>> =
        curatedMutex.withLock {
            val curLabels = cachedLabels
            val curAnchors = cachedAnchors
            if (curLabels != null && curAnchors != null) return@withLock curLabels to curAnchors
            val (loadedLabels, loadedAnchors) = CuratedLabels.loadLabelVectors(context, engine)
            cachedLabels = loadedLabels
            cachedAnchors = loadedAnchors
            loadedLabels to loadedAnchors
        }

    private suspend fun getManualPositives(): Map<String, List<FloatArray>> {
        val sqliteStore = store as? SqliteLibraryStore
        if (sqliteStore != null) {
            return sqliteStore.getManualPositiveVectors()
        }
        return emptyMap()
    }

    private suspend fun getManualTagFingerprint(): String {
        val sqliteStore = store as? SqliteLibraryStore
        if (sqliteStore != null) {
            return sqliteStore.getManualTagStamp()
        }
        return ""
    }

    private fun computeTrainingCacheKey(
        exemplars: List<Exemplar>,
        corpusCount: Int,
        revision: Long,
        manualTagStamp: String,
    ): String {
        val sb = StringBuilder()
        sb.append(ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        sb.append(':').append(exemplars.size)
        sb.append(':').append(corpusCount)
        sb.append(':').append(revision)
        sb.append(':').append(manualTagStamp)
        for (e in exemplars) {
            sb.append(';').append(e.id).append(',').append(if (e.positive) 1 else 0).append(',').append(e.label)
        }
        return sb.toString()
    }

    // The pinned recipe passed the original encoder's 32-image/8-text parity gate.
    // Compatibility is limited to teaching packs; filename-only sidecars still require reindexing.
    private fun compatibleTeachingModel(model: String): Boolean =
        model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT || model == TeachingPackIo.PACK_MODEL

    private suspend fun ensureTrainedHeads(): Pair<List<LearnCore.LabelHead>, FloatArray?> =
        trainingMutex.withLock { loadTrainedHeads() }

    private suspend fun loadTrainedHeads(): Pair<List<LearnCore.LabelHead>, FloatArray?> {
        val revision = store.revision.value
        if (revision == cachedTrainingRevision && (cachedHeads.isNotEmpty() || cachedTrainingKey.isNotEmpty())) {
            return cachedHeads to cachedMean
        }
        val allExemplars = store.exemplars()
        val activeExemplars = allExemplars.filter { compatibleTeachingModel(it.model) }
        val corpusCount = store.count()
        val manualTagStamp = getManualTagFingerprint()
        val currentKey = computeTrainingCacheKey(activeExemplars, corpusCount, revision, manualTagStamp)

        if (currentKey == cachedTrainingKey && (cachedHeads.isNotEmpty() || cachedTrainingKey.isNotEmpty())) {
            cachedTrainingRevision = revision
            return Pair(cachedHeads, cachedMean)
        }

        val manualPositives = getManualPositives()
        if (activeExemplars.isEmpty() && manualPositives.isEmpty()) {
            cachedHeads = emptyList()
            cachedMean = null
            cachedTrainingKey = currentKey
            cachedTrainingRevision = revision
            return Pair(emptyList(), null)
        }
        val sample = store.vectorSample(250)
        val dim = if (activeExemplars.isNotEmpty()) activeExemplars[0].vector.size else 512
        val mean = FloatArray(dim)
        if (sample.isNotEmpty()) {
            for (vr in sample) {
                val v = vr.image
                val len = minOf(dim, v.size)
                for (i in 0 until len) mean[i] += v[i]
            }
            for (i in 0 until dim) mean[i] /= sample.size.toFloat()
        }

        fun center(v: FloatArray): FloatArray {
            val out = FloatArray(dim)
            val len = minOf(dim, v.size)
            for (i in 0 until len) out[i] = v[i] - mean[i]
            return out
        }

        val backgroundRaw = sample.map { it.image }
        val backgroundCentered = backgroundRaw.map { center(it) }

        data class Group(
            val category: String,
            val posRaw: MutableList<FloatArray> = ArrayList(),
            val posCentered: MutableList<FloatArray> = ArrayList(),
            val negRaw: MutableList<FloatArray> = ArrayList(),
            val negCentered: MutableList<FloatArray> = ArrayList(),
        )

        val byLabel = LinkedHashMap<String, Group>()
        for (e in activeExemplars) {
            val normLabel = TagMerge.normalizeTagLabel(e.label)
            val g = byLabel.getOrPut(normLabel) { Group(e.category) }
            if (e.positive) {
                g.posRaw.add(e.vector)
                g.posCentered.add(center(e.vector))
            } else {
                g.negRaw.add(e.vector)
                g.negCentered.add(center(e.vector))
            }
        }

        // Fold bounded human-confirmed inputs; unreviewed and legacy hints never train.
        for ((label, vectors) in manualPositives) {
            val normLabel = TagMerge.normalizeTagLabel(label)
            val g = byLabel.getOrPut(normLabel) { Group(category = "user") }
            for (v in vectors) {
                if (g.posRaw.size >= 16) break
                g.posRaw.add(v)
                g.posCentered.add(center(v))
            }
        }
        val heads = ArrayList<LearnCore.LabelHead>()
        for ((label, g) in byLabel) {
            currentCoroutineContext().ensureActive()
            if (g.posRaw.isEmpty()) continue
            val otherPosRaw = ArrayList<FloatArray>()
            val otherPosCentered = ArrayList<FloatArray>()
            for ((l2, g2) in byLabel) {
                if (l2 == label) continue
                otherPosRaw.addAll(g2.posRaw)
                otherPosCentered.addAll(g2.posCentered)
            }

            val head = LearnCore.trainLabelModel(
                label = label,
                category = g.category,
                posRaw = g.posRaw,
                posCentered = g.posCentered,
                negRaw = g.negRaw,
                negCentered = g.negCentered,
                backgroundRaw = backgroundRaw,
                backgroundCentered = backgroundCentered,
                otherPosRaw = otherPosRaw,
                otherPosCentered = otherPosCentered,
            )
            heads.add(head)
        }

        cachedHeads = heads
        cachedMean = mean
        cachedTrainingKey = currentKey
        cachedTrainingRevision = revision
        return Pair(heads, mean)
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

    private fun findVetoedLabels(image: FloatArray, exemplars: List<Exemplar>): Set<String> {
        val negByLabel = LinkedHashMap<String, MutableList<FloatArray>>()
        for (e in exemplars) {
            if (!e.positive) {
                val norm = TagMerge.normalizeTagLabel(e.label)
                negByLabel.getOrPut(norm) { ArrayList() }.add(e.vector)
            }
        }
        return findVetoedLabelsFromMap(image, negByLabel)
    }

    private data class ClassificationSnapshot(
        val labels: List<CuratedLabelDef>,
        val negativeVecs: List<FloatArray>,
        val heads: List<LearnCore.LabelHead>,
        val mean: FloatArray?,
        val allExemplars: List<Exemplar>,
        val negExemplarsByLabel: Map<String, List<FloatArray>>,
        val assocMap: Map<String, List<String>>,
    )

    private suspend fun prepareSnapshot(): ClassificationSnapshot {
        val (labels, anchors) = ensureCuratedLoaded()
        val (heads, mean) = ensureTrainedHeads()
        val allExemplars = store.exemplars().filter { compatibleTeachingModel(it.model) }
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

        return ClassificationSnapshot(
            labels = labels,
            negativeVecs = negativeVecs,
            heads = heads,
            mean = mean,
            allExemplars = allExemplars,
            negExemplarsByLabel = negExemplarsByLabel,
            assocMap = assocMap,
        )
    }

    suspend fun classify(image: FloatArray, ocr: String): Pair<List<Tag>, String> = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val snapshot = prepareSnapshot()
        classifyInternal(image, ocr, snapshot)
    }

    private fun classifyInternal(
        image: FloatArray,
        ocr: String,
        snapshot: ClassificationSnapshot,
    ): Pair<List<Tag>, String> {
        val vetoedLabels = findVetoedLabelsFromMap(image, snapshot.negExemplarsByLabel)
        // 1. Zero-shot prompt tags with anchor bias
        val promptTags = LearnCore.classifyPrompts(image, snapshot.labels, snapshot.negativeVecs)

        // 2. Few-shot exemplar tags
        val exemplarTags = LearnCore.classifyExemplars(image, snapshot.heads, snapshot.mean)

        // 3. OCR tags
        val ocrTags = CuratedLabels.ocrTags(ocr)

        // 4. Entity pack retrieval
        val anchorMax = if (snapshot.negativeVecs.isNotEmpty()) FloatBlob.maxCosTo(image, snapshot.negativeVecs) else 0f
        val entityHits = EntityRetrieve.retrieveEntities(
            imageVec = image,
            exemplars = snapshot.allExemplars,
            anchorSim = anchorMax,
        )
        val entityTags = EntityRetrieve.entityHitsToTags(entityHits)

        // Merge visual and exemplar classifications
        val visualMerged = LearnCore.mergeClassified(promptTags, exemplarTags, topK = 6)
        val initialTags = visualMerged + ocrTags + entityTags

        // Apply durable merging and cap auto tags at 6
        val durableMerged = TagMerge.mergeDurableTags(initialTags, capAuto = 6)

        // CRITICAL: Explicit negative veto eliminates vetoed labels across ALL branches
        val vetoFiltered = durableMerged.filter { !vetoedLabels.contains(TagMerge.normalizeTagLabel(it.label)) }

        // Build world knowledge extra terms from associations
        val extraTermsSet = LinkedHashSet<String>()
        for (t in vetoFiltered) {
            val norm = TagMerge.normalizeTagLabel(t.label)
            extraTermsSet.add(norm)
            val assocs = snapshot.assocMap[norm]
            if (assocs != null) {
                for (a in assocs) {
                    for (w in a.lowercase().split(Regex("\\s+"))) {
                        if (w.isNotBlank()) extraTermsSet.add(w)
                    }
                }
            }
        }

        val tagNames = vetoFiltered.map { it.label }
        val contextTerms = SearchText.classificationContextTerms(tags = tagNames)
        for (w in contextTerms.split(' ')) {
            if (w.isNotBlank()) extraTermsSet.add(w)
        }

        val extraTerms = extraTermsSet.joinToString(" ")
        return Pair(vetoFiltered, extraTerms)
    }

    suspend fun candidateLabels(image: FloatArray, limit: Int = 12): List<Tag> = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val (labels, anchors) = ensureCuratedLoaded()
        val (heads, mean) = ensureTrainedHeads()
        val allExemplars = store.exemplars().filter { compatibleTeachingModel(it.model) }
        val vetoedLabels = findVetoedLabels(image, allExemplars)

        val negativeVecs = anchors.map { it.vec }

        // Tentative prompt candidates without margin floor suppression
        val promptCandidates = LearnCore.rankPromptCandidates(image, labels, negativeVecs)

        // Tentative exemplar candidates
        val exemplarCandidates = LearnCore.rankExemplarCandidates(image, heads, mean)

        // Entity retrieval without suppressing below-floor margins
        val anchorMax = if (negativeVecs.isNotEmpty()) FloatBlob.maxCosTo(image, negativeVecs) else 0f
        val entityHits = EntityRetrieve.retrieveEntities(
            imageVec = image,
            exemplars = allExemplars,
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
        for (ec in exemplarCandidates) {
            if (ec.tag.category.trim().lowercase() in IDENTITY_CANDIDATE_CATEGORIES) {
                candidates.add(ScoredCandidate(ec.tag, ec.margin))
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

    suspend fun retag(progress: (WorkProgress) -> Unit) = withContext(Dispatchers.Default) {
        progress(WorkProgress(stage = "retag", running = true, detail = "Preparing knowledge…"))
        var snapshot = prepareSnapshot()
        var expectedRevision = store.revision.value
        val total = store.count()
        var done = 0

        // Stream bounded rows without materializing the whole library
        store.allMemes().collect { meme ->
            currentCoroutineContext().ensureActive()
            val currentRevision = store.revision.value
            if (currentRevision != expectedRevision) {
                // Store was mutated concurrently outside this pass's writes (e.g. user teach/correction)
                snapshot = prepareSnapshot()
                expectedRevision = store.revision.value
            }

            val vectors = store.vectors(meme.id)
            if (vectors != null && vectors.image.isNotEmpty() && vectors.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT) {
                val (freshTags, freshExtra) = classifyInternal(
                    image = vectors.image,
                    ocr = meme.ocr,
                    snapshot = snapshot,
                )

                // Preserve user-owned tags (manual, exemplar, propagated) and vision tags
                val keptTags = meme.tags.filter {
                    it.source == "manual" || it.source == "exemplar" || it.source == "propagated" || it.source == "vision"
                }

                // Explicit negative teachings also veto kept tags if they conflict
                val vetoed = findVetoedLabelsFromMap(vectors.image, snapshot.negExemplarsByLabel)
                val filteredKept = keptTags.filter { !vetoed.contains(TagMerge.normalizeTagLabel(it.label)) }

                val merged = TagMerge.mergeDurableTags(freshTags + filteredKept, capAuto = 6)
                val combinedExtra = TagMerge.termsWithLabel(freshExtra, meme.extraTerms)

                store.updateTags(meme.id, merged, combinedExtra)
                expectedRevision++
            }
            done++
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

        progress(WorkProgress(stage = "complete", done = done, total = total, running = false))
    }
}
