package com.memeget.nativeapp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal data class Tag(
    val label: String,
    val category: String = "topic",
    val score: Float = 0f,
    val source: String = "prompt",
    val scoreDomain: String = "uncalibrated",
    val confirmation: String = "unreviewed",
    val provenance: String = "",
)

internal data class Meme(
    val id: Long,
    val uri: String,
    val name: String,
    val kind: String,
    val ocr: String = "",
    val caption: String = "",
    val transcript: String = "",
    val tags: List<Tag> = emptyList(),
    val extraTerms: String = "",
    val thumbUri: String = "",
    val modifiedAt: Long = 0,
    val pending: Boolean = true,
    val visionState: String = "pending",
    val audioState: String = "none",
    val contentHash: String = "",
    val cloudCaption: String = "",
    val cloudTerms: String = "",
    val legacyImported: Boolean = false,
    val cloudObservation: CloudResult? = null,
    val rejectedLabels: List<String> = emptyList(),
    val indexError: String = "",
    /** OCR and a compatible image vector arrived with the media; indexing reuses them once. */
    val analysisImported: Boolean = false,
    /** When the meme was added, or last indexed, in epoch milliseconds. */
    val indexedAt: Long = 0,
)

/** How far each stage of the library's analysis has got. */
internal data class EnrichmentCounts(
    val total: Int = 0,
    /** Analysis done. */
    val indexed: Int = 0,
    /** Waiting for analysis, not counting failures. */
    val waiting: Int = 0,
    val indexFailed: Int = 0,
    /** Analysed memes whose local caption is waiting or failed. */
    val captionsWaiting: Int = 0,
    val captionsFailed: Int = 0,
    /** Analysed memes with a local caption. */
    val captionsDone: Int = 0,
    val videos: Int = 0,
    /** Analysed videos whose transcript is waiting or failed. */
    val transcriptsWaiting: Int = 0,
    val transcriptsFailed: Int = 0,
    /** Analysed videos with a transcript. */
    val transcriptsDone: Int = 0,
    /** Analysed videos without a poster frame. */
    val postersMissing: Int = 0,
)

internal data class VectorRecord(
    val id: Long,
    val image: FloatArray,
    val caption: FloatArray? = null,
    val visual: FloatArray? = null,
    val model: String,
    val visualModel: String = "",
)

internal data class Folder(val uri: String, val name: String, val addedAt: Long)
internal data class Exemplar(
    val id: Long,
    val label: String,
    val category: String,
    val vector: FloatArray,
    val positive: Boolean,
    val associations: List<String>,
    val sourceUri: String,
    val model: String,
    val origin: String = "self",
    val pack: String = "",
)
internal data class SearchHit(val meme: Meme, val score: Float)
internal data class PageCursor(val modifiedAt: Long, val id: Long)
internal data class SearchCursor(val score: Float, val modifiedAt: Long, val id: Long)
internal data class TextRegion(
    val text: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
/**
 * While captioning or transcribing, [done] and [total] count the whole library's captions or transcripts, not this
 * session's, so the bar shows how far the library has got.
 */
internal data class WorkProgress(
    val stage: String = "idle",
    val done: Int = 0,
    val total: Int = 0,
    val detail: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val memeId: Long? = null,
)

/** The line the progress card and Settings show for running work. */
internal fun WorkProgress.headline(): String = when {
    total > 0 && stage in CAPTION_STAGES -> "Captions: ${total - done} left"
    total > 0 && stage == "local transcription" -> "Transcripts: ${total - done} left"
    total > 0 -> "Indexing $done/$total · $stage"
    else -> "Preparing index: $stage"
}

private val CAPTION_STAGES = setOf("local description", "caption embedding")
/** An item the app was killed on: [retried] when it went back to the queue, false when it was its second kill. */
internal data class KilledRecovery(val id: Long, val retried: Boolean)
internal data class ModelProgress(
    val name: String = "",
    val fraction: Float = 0f,
    val loading: Boolean = false,
    val error: String? = null,
)
internal data class LocalAnalysis(
    val image: FloatArray,
    val ocr: String,
    val tags: List<Tag>,
    val extraTerms: String,
)
/** Analysis a collection export carried for exactly these media bytes. */
internal data class ImportedAnalysis(
    val humanTags: List<Tag>,
    val ocr: String,
    val transcript: String,
    /** Compatible image vector for these bytes, or null when the export had none. */
    val image: FloatArray?,
)
/**
 * A meme people labeled, with its image vector: confirmed tags (excluding filename-only
 * legacy restores) and "not X" rejections.
 */
internal data class HumanLabeled(
    val memeId: Long,
    val image: FloatArray,
    val labels: List<Tag>,
    val rejected: List<String>,
)
internal data class CloudEvidence(
    val memeId: Long,
    val contentHash: String,
    val ocr: String,
    val caption: String,
    val transcript: String,
    val candidates: List<Tag>,
    val imageVector: FloatArray,
    val model: String,
)
internal data class CloudResult(
    val contentHash: String,
    val model: String,
    val caption: String,
    val tags: List<Tag>,
    val terms: String,
    val probabilities: Map<String, Float>,
    val providerConfidence: Float?,
    val schemaVersion: Int = 1,
    val promptHash: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expectedEvidence: String = "",
    val embeddingModel: String = "",
    val expectedVectorHash: String = "",
)

/**
 * Setting listing, one per line, the linked folders whose automatic backup is held: their backup holds more than the
 * library does (after Clear Index) or may be only half restored, so writing the library over it would lose data.
 * Restoring a folder, or backing it up by hand, releases it.
 */
internal const val BACKUP_HOLD_SETTING = "backup.hold"

/** IO belongs to implementations; callers may invoke from lifecycle-scoped UI jobs. */
internal interface LibraryStore {
    val revision: StateFlow<Long>
    /** Changes when people's labels, teachings or the vectors behind them may have changed. */
    val labelRevision: StateFlow<Long>
    suspend fun humanLabels(): List<HumanLabeled>
    /** Every meme's primary-model image vector, in one read. */
    suspend fun imageVectors(): Map<Long, FloatArray>
    /** Ids of memes holding a tag from [scoreDomain], read without parsing every meme's tags. */
    suspend fun idsWithScoreDomain(scoreDomain: String): Set<Long>
    suspend fun folders(): List<Folder>
    suspend fun addFolder(uri: String, name: String)
    suspend fun removeFolder(uri: String)
    suspend fun insertPending(uri: String, name: String, kind: String, modifiedAt: Long = 0, contentHash: String = ""): Long
    suspend fun meme(id: Long): Meme?
    suspend fun memeByUri(uri: String): Meme?
    suspend fun page(limit: Int = 90, after: PageCursor? = null, kind: String? = null, tag: String? = null): List<Meme>
    suspend fun search(text: String, vector: FloatArray?, kind: String? = null, tag: String? = null, limit: Int = 90, after: SearchCursor? = null): List<SearchHit>
    suspend fun similar(id: Long, limit: Int = 24): List<SearchHit>
    suspend fun vectors(id: Long): VectorRecord?
    suspend fun vectorSample(limit: Int = 512): List<VectorRecord>
    suspend fun pending(limit: Int = 32): List<Meme>
    fun allMemes(): Flow<Meme>
    suspend fun completeIndex(id: Long, analysis: LocalAnalysis, model: String, thumbUri: String)
    suspend fun setCaption(id: Long, caption: String, tags: List<Tag>, vector: FloatArray?, state: String = "done")
    suspend fun setTranscript(id: Long, text: String, state: String = "done")
    /** Changes only a video's transcription state; its transcript stays as it is. */
    suspend fun setAudioState(id: Long, state: String)
    /** Changes only a meme's caption state; its caption and tags stay as they are. */
    suspend fun setVisionState(id: Long, state: String)
    suspend fun enrichmentCounts(): EnrichmentCounts
    /** Captioned memes whose caption has no search vector under [model] yet, at most [limit]. */
    suspend fun captionsWithoutVectors(model: String, limit: Int): List<Long>
    /**
     * After the app was killed mid-caption ([stage] "vision") or mid-transcript ("audio"): the item named by setting
     * [inFlightKey], if its marker still says failed, goes back to pending unless setting [onceKey] already lists it.
     * The state, the strike and the cleared in-flight setting are written in one transaction. Null when nothing was
     * stranded.
     */
    suspend fun recoverKilled(stage: String, inFlightKey: String, onceKey: String): KilledRecovery?
    /**
     * Queues every analysed meme whose caption ([stage] "vision") or every video whose transcript ([stage] "audio")
     * failed for another attempt. Returns how many.
     */
    suspend fun requeueFailedEnrichment(stage: String): Int
    /** Marks every video for transcription again, whatever model made its transcript. Transcripts stay until replaced. */
    suspend fun requeueAllTranscripts(): Int
    suspend fun setVisual(id: Long, vector: FloatArray, model: String)
    suspend fun setThumb(id: Long, uri: String)
    suspend fun setContentHash(id: Long, hash: String)
    suspend fun requeue(ids: List<Long>)
    /** Queues every item whose last analysis failed for another attempt; returns how many. */
    suspend fun requeueFailed(): Int
    /**
     * Marks every video for transcription again and records [model] as the speech model, in one transaction, unless
     * [model] is already recorded. Only the audio state changes: transcripts stay until new ones replace them.
     * Returns how many videos were marked.
     */
    suspend fun requeueTranscripts(model: String): Int
    suspend fun markIndexFailed(id: Long, reason: String)
    suspend fun setCloud(id: Long, result: CloudResult): Boolean
    suspend fun forgetCloud()
    suspend fun manualTag(ids: List<Long>, label: String, category: String = "topic", spread: Boolean = false): Int
    suspend fun removeTag(id: Long, label: String)
    suspend fun teach(
        id: Long,
        label: String,
        category: String,
        positive: Boolean,
        associations: List<String> = emptyList(),
        confirmedCloudTag: Tag? = null,
    )
    suspend fun exemplars(): List<Exemplar>
    suspend fun importExemplars(items: List<Exemplar>, pack: String, asSelf: Boolean = false): Int
    suspend fun forgetLabel(label: String)
    suspend fun forgetPack(pack: String)
    /**
     * Removes every taught example, the user's own and imported ones, and imports [items], in one transaction so a
     * failure leaves the old ones. Tags already on memes stay. Returns (removed, added).
     */
    suspend fun replaceExemplars(items: List<Exemplar>, pack: String): Pair<Int, Int>
    /**
     * Replaces the meme's machine tags with [tags] and its association terms with [extraTerms].
     * Human-confirmed tags are kept whatever the caller passes; per-meme rejections remove
     * matching tags and terms.
     */
    suspend fun updateTags(id: Long, tags: List<Tag>, extraTerms: String)
    /** Adds imported human tags and fills OCR, transcript and a missing vector. */
    suspend fun applyImportedAnalysis(id: Long, analysis: ImportedAnalysis)
    /**
     * Attaches taught examples to the memes they show: a meme whose image vector matches a
     * "this is X" example gets X as a confirmed tag; a "not X" example adds a rejection.
     * Returns the number of memes changed.
     */
    suspend fun relinkTeachings(): Int
    suspend fun delete(ids: List<Long>)
    suspend fun clearIndex()
    suspend fun setting(key: String): String?
    suspend fun setSetting(key: String, value: String)
    suspend fun addError(name: String, stage: String, reason: String)
    suspend fun errors(): List<String>
    /** How many logged errors each stage has. */
    suspend fun errorCounts(): Map<String, Int>
    suspend fun clearErrors()
    /** Memes whose analysis is done. */
    suspend fun count(): Int
    /** Every meme in the library, whether or not its analysis is done. */
    suspend fun countAll(): Int
    suspend fun tagLabels(): List<String>
    /** How many memes carry each tag, keyed by normalized label, whatever the tag's source. */
    suspend fun tagCounts(): Map<String, Int>
    suspend fun restore(meme: Meme, vectors: VectorRecord?): Boolean
}

/** One process-owned engine. Cancellation must not release in-use native buffers. */
internal interface InferenceEngine : AutoCloseable {
    val progress: StateFlow<ModelProgress>
    val primaryModel: String
    suspend fun embedImage(uri: String): FloatArray
    suspend fun embedText(text: String): FloatArray
    suspend fun embedVisual(uri: String): FloatArray
    suspend fun recognize(uri: String): List<TextRegion>
    suspend fun describe(uri: String, grounding: String): String
    suspend fun transcribe(uri: String): String
    suspend fun segment(uri: String, requestId: String): expo.modules.memegetbg.SubjectCutoutResult
    suspend fun unload()
    fun cancel()
    override fun close()
}

/**
 * A model a call needs could not be downloaded or prepared ([model] is its artifact id). The item that asked is not
 * at fault: its work waits for a later session instead of failing, and a partial download resumes then.
 */
internal class ModelUnavailableException(val model: String, message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)
