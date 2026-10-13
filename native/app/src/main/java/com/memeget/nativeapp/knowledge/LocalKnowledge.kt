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

    // People's labels as a look-alike vote index, rebuilt only when labels or teachings change.
    private val voteMutex = Mutex()
    private var cachedVote: VoteState? = null
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

    private class VoteState(
        val labelRevision: Long,
        val index: LookalikeVote.Index,
        private val labeled: List<LookalikeVote.LabeledMeme>,
    ) {
        /** Labels whose held-out votes held up; measured once per label change, when first needed. */
        val reliable: Set<String> by lazy { LookalikeVote.reliableLabels(index, labeled) }
    }

    // The pinned recipe passed the original encoder's 32-image/8-text parity gate.
    // Compatibility is limited to teaching packs; filename-only sidecars still require reindexing.
    private fun compatibleTeachingModel(model: String): Boolean =
        model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT || model == TeachingPackIo.PACK_MODEL

    private suspend fun ensureVote(): VoteState = voteMutex.withLock {
        val revision = store.labelRevision.value
        cachedVote?.takeIf { it.labelRevision == revision }?.let { return@withLock it }
        val examples = ArrayList<LookalikeVote.Example>()
        val negatives = LinkedHashMap<String, MutableList<FloatArray>>()
        val taught = HashMap<String, MutableList<FloatArray>>()
        for (e in store.exemplars()) {
            if (!compatibleTeachingModel(e.model) || e.vector.size != 512) continue
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
        VoteState(revision, LookalikeVote.Index(examples, negatives), labeled).also { cachedVote = it }
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
    )

    @Volatile private var cachedSnapshot: ClassificationSnapshot? = null

    private suspend fun prepareSnapshot(): ClassificationSnapshot {
        val (labels, anchors) = ensureCuratedLoaded()
        val vote = ensureVote()
        // Teachings change only with the label revision, so their derived maps live as long as the vote.
        cachedSnapshot?.takeIf { it.vote === vote }?.let { return it }
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
            vote = vote,
            exemplars = allExemplars,
            negExemplarsByLabel = negExemplarsByLabel,
            assocMap = assocMap,
        ).also { cachedSnapshot = it }
    }

    /** [memeId] keeps a meme's own labels out of the vote on it. */
    suspend fun classify(image: FloatArray, ocr: String, memeId: Long = LookalikeVote.NO_MEME): Pair<List<Tag>, String> =
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val snapshot = prepareSnapshot()
            classifyInternal(image, ocr, snapshot, memeId)
        }

    private fun classifyInternal(
        image: FloatArray,
        ocr: String,
        snapshot: ClassificationSnapshot,
        memeId: Long,
    ): Pair<List<Tag>, String> {
        val vetoedLabels = findVetoedLabelsFromMap(image, snapshot.negExemplarsByLabel)
        // 1. Zero-shot prompt tags with anchor bias
        val promptTags = LearnCore.classifyPrompts(image, snapshot.labels, snapshot.negativeVecs)

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

        val visualMerged = LearnCore.mergeClassified(promptTags, lookalike.values.toList(), topK = 6)
        val durableMerged = TagMerge.mergeDurableTags(visualMerged + ocrTags, capAuto = 6)
        // Explicit negative veto eliminates vetoed labels across every branch
        val vetoFiltered = durableMerged.filter { !vetoedLabels.contains(TagMerge.normalizeTagLabel(it.label)) }
        return Pair(vetoFiltered, extraTermsFor(vetoFiltered, snapshot.assocMap))
    }

    private fun lookalikeTag(v: LookalikeVote.Vote) = Tag(
        label = v.label,
        category = v.category,
        score = v.cosine,
        source = "exemplar",
        scoreDomain = "lookalike-cosine",
        confirmation = "unreviewed",
        provenance = "learned",
    )

    // Search terms for a final tag list: each label, its association words and context terms.
    private fun extraTermsFor(tags: List<Tag>, assocMap: Map<String, List<String>>): String {
        val terms = LinkedHashSet<String>()
        for (t in tags) {
            val norm = TagMerge.normalizeTagLabel(t.label)
            terms.add(norm)
            for (a in assocMap[norm].orEmpty()) {
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
     * person, or rejected, and labels a closer "not X" contradicts are left out.
     */
    suspend fun suggestFor(meme: Meme, image: FloatArray, limit: Int = 3): List<Tag> = withContext(Dispatchers.Default) {
        val vote = ensureVote()
        val settled = HashSet<String>()
        for (t in meme.tags) if (TagMerge.isHumanTag(t)) settled += TagMerge.normalizeTagLabel(t.label)
        for (label in meme.rejectedLabels) settled += TagMerge.normalizeTagLabel(label)
        val out = LinkedHashMap<String, Tag>()
        val ranked = LookalikeVote.rank(vote.index, image, excludeMeme = meme.id) +
            LookalikeVote.nearestPerLabel(vote.index, image, excludeMeme = meme.id)
        for (v in ranked) {
            if (out.size >= limit) break
            if (v.label in settled || v.label in out || LookalikeVote.contradicted(vote.index, v, image)) continue
            out[v.label] = lookalikeTag(v)
        }
        out.values.toList()
    }

    /** A meme worth labeling next and how many unlabeled look-alikes it stands for. */
    class QueueItem(val meme: Meme, val image: FloatArray, val lookalikes: Int)

    /**
     * Memes to ask about, in the order that spreads labels furthest: repeatedly the meme with the
     * most unlabeled look-alikes (cosine >= [LookalikeVote.MIN_COS]) that no earlier pick covers.
     * Memes a person labeled, and memes that already get a label on their own, are left out.
     */
    suspend fun labelQueue(limit: Int = 60): List<QueueItem> = withContext(Dispatchers.Default) {
        val vote = ensureVote()
        val settled = store.humanLabels().mapTo(HashSet()) { it.memeId }
        val memes = ArrayList<Meme>()
        val images = ArrayList<FloatArray>()
        store.allMemes().collect { meme ->
            currentCoroutineContext().ensureActive()
            if (meme.id in settled) return@collect
            val image = store.vectors(meme.id)
                ?.takeIf { it.model == ModelRegistry.PRIMARY_MODEL_FINGERPRINT && it.image.size == 512 }?.image
                ?: return@collect
            if (LookalikeVote.samePicture(vote.index, image, meme.id).isNotEmpty()) return@collect
            if (LookalikeVote.winner(vote.index, image, meme.id)?.let { it.label in vote.reliable } == true) return@collect
            memes += meme
            images += image
        }
        val neighbours = Array(memes.size) { ArrayList<Int>() }
        for (i in memes.indices) {
            currentCoroutineContext().ensureActive()
            for (j in i + 1 until memes.size) {
                if (FloatBlob.dot(images[i], images[j]) >= LookalikeVote.MIN_COS) {
                    neighbours[i] += j
                    neighbours[j] += i
                }
            }
        }
        val covered = BooleanArray(memes.size)
        val picked = ArrayList<QueueItem>()
        while (picked.size < limit) {
            var best = -1
            var bestGain = 0
            for (i in memes.indices) {
                if (covered[i]) continue
                val gain = 1 + neighbours[i].count { !covered[it] }
                if (gain > bestGain) {
                    best = i
                    bestGain = gain
                }
            }
            if (best < 0) break
            covered[best] = true
            for (j in neighbours[best]) covered[j] = true
            picked += QueueItem(memes[best], images[best], bestGain - 1)
        }
        picked
    }

    /** The category people's examples already give [label]; "topic" for a new label. */
    suspend fun knownCategory(label: String): String {
        val norm = TagMerge.normalizeTagLabel(label)
        return ensureVote().index.examples.firstOrNull { it.label == norm }?.category ?: "topic"
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
                }
                val merged = TagMerge.mergeDurableTags(freshTags + kept, capAuto = 6)
                store.updateTags(meme.id, merged, extraTermsFor(merged, snapshot.assocMap))
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
