package com.memeget.nativeapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal class CloudAssistant(
    val context: Context,
    val store: LibraryStore,
    val engine: InferenceEngine,
    private val http: CloudHttpEngine = CloudHttpEngine(),
) {
    companion object {
        // Include tv & movie alongside format/character/person/object/topic so Clef can disambiguate shows & films too!
        val IDENTITY_CANDIDATE_CATEGORIES = setOf("format", "character", "person", "tv", "movie", "object", "topic")
    }

    private val directory = File(context.filesDir, "cloud_vault").apply { mkdirs() }
    private val configFile = File(directory, "cloud_config.json")
    private val observationsFile = File(directory, "cloud_observations.json")
    private val vault = CloudSecretsVault(directory)
    private val ledger = CloudLedger(directory)
    private val memory = CloudPrototypeMemory(directory)
    private val operation = Mutex()
    private val configuration = Mutex()
    private val sendGate = Any()
    private val mutableProgress = MutableStateFlow(WorkProgress())
    val progress: StateFlow<WorkProgress> = mutableProgress.asStateFlow()
    @Volatile private var cached = loadConfig()
    @Volatile private var cancellationEpoch = 0L
    private val bundledCandidateLabels: Set<String> by lazy {
        val labels = context.assets.open("knowledge/label-prompts.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("labels")
        }
        buildSet {
            for (index in 0 until labels.length()) add(labels.getJSONObject(index).getString("label").trim().lowercase(java.util.Locale.ROOT))
        }
    }

    /**
     * Called automatically by indexing; master consent alone never authorizes automatic calls.
     * [candidates] is computed only when this meme is eligible, so indexing pays nothing with cloud off.
     */
    suspend fun consider(meme: Meme, candidates: suspend () -> List<Tag>): CloudResult? =
        if (eligible(cached, meme, manual = false)) analyze(meme, candidates(), manual = false) else null

    suspend fun analyze(meme: Meme, candidates: List<Tag>, manual: Boolean = true): CloudResult? = withContext(Dispatchers.IO) {
        val consent = cached
        if (!eligible(consent, meme, manual)) return@withContext null
        if (!operation.tryLock()) return@withContext null
        val epoch = cancellationEpoch
        try {
            currentCoroutineContext().ensureActive()
            if (!networkAllowed(consent)) return@withContext null
            val revision = consent.consentRevision
            val vectorsBefore = store.vectors(meme.id)
            val expectedEvidence = cloudEvidenceStamp(meme)
            val expectedVectorHash = cloudVectorStamp(vectorsBefore)
            val identityCandidates = candidates.filter {
                it.category.trim().lowercase(java.util.Locale.ROOT) in IDENTITY_CANDIDATE_CATEGORIES &&
                    it.source !in setOf("cloud", "cloud_hint", "propagated") && it.confirmation != "rejected" &&
                    meme.rejectedLabels.none { rejected -> rejected.equals(it.label, true) } &&
                    (consent.consentTextState || it.source == "prompt" && it.label.trim().lowercase(java.util.Locale.ROOT) in bundledCandidateLabels)
            }
            if (!manual && meme.tags.any {
                it.category.trim().lowercase(java.util.Locale.ROOT) in IDENTITY_CANDIDATE_CATEGORIES &&
                    it.confirmation == "confirmed" && !it.provenance.startsWith("legacy-") &&
                    (it.source == "manual" || it.source == "exemplar" || it.source == "cloud" || it.source == "cloud_hint")
            }) return@withContext null
            // This is a local retrieval-strength routing heuristic, not a claim of identity accuracy.
            if (!manual && identityCandidates.any { it.source == "prompt" && it.score >= consent.autoEscalateThreshold }) return@withContext null
            val authorized = { cached.enabled && cached.consentRevision == revision && cancellationEpoch == epoch && networkAllowed(consent) }
            val payload = CloudPayloadPrep.prepare(context, meme, consent)
            if (!authorized()) return@withContext null
            val decisionRequest = if (identityCandidates.isEmpty()) null else http.prepareDecision(consent, payload, identityCandidates)
            val clairicRequest = if (consent.provider == CloudProviderType.CLOUDFLARE_CLEF && consent.clairicExtractionEnabled &&
                (consent.consentTextState || (consent.consentVisualMedia && payload.imagesBase64DataUris.isNotEmpty()))
            ) runCatching { http.prepareClairicExtraction(consent, payload) }.getOrNull() else null
            val canCaption = consent.captionEndpointEnabled && consent.consentVisualMedia && payload.imagesBase64DataUris.isNotEmpty()
            if (decisionRequest == null && clairicRequest == null && !canCaption) return@withContext null

            var decision: DecisionResult? = null
            var clairic: ClairicExtractionResult? = null
            var caption = ""
            mutableProgress.value = WorkProgress("cloud analysis", running = true, detail = "Running System-1 / System-2 hybrid identification")
            if (clairicRequest != null) {
                val key = vault.getApiKey(primaryBinding(consent))
                if (key != null) {
                    runCatching {
                        val rawClairic = send(clairicRequest, key, consent, authorized)
                        clairic = http.parseClairicExtraction(rawClairic)
                    }
                }
            }
            if (decisionRequest != null) {
                val key = vault.getApiKey(primaryBinding(consent)) ?: throw IllegalStateException("Configure a key for the selected decision endpoint")
                val response = send(decisionRequest, key, consent, authorized)
                decision = http.parseDecision(response, decisionRequest)
            }
            // System-2 Open-World VLM fires automatically when Clef abstains ("unknown"), when zero local candidates existed,
            // or on manual request so obscure composite remixes get full two-domain naming without wasting calls on solved memes.
            val clefSolved = decision != null && !decision.isUnknown && decision.calculatedConfidence >= 0.55f
            val captionRequest = if (canCaption && (manual || !clefSolved)) {
                http.prepareCaption(consent, payload.imagesBase64DataUris.first(), structuredTwoDomain = true)
            } else null
            val cacheKey = CloudPayloadPrep.computeBytesSha256((payload.sourceContentHash + "|" + engine.primaryModel + "|" + decisionRequest?.url + "|" + decisionRequest?.promptHash + "|" + clairicRequest?.promptHash + "|" + captionRequest?.url + "|" + captionRequest?.promptHash).toByteArray())
            if (observationSeen(cacheKey)) return@withContext null
            if (captionRequest != null) {
                val key = vault.getJudgeKey(captionBinding(consent)) ?: throw IllegalStateException("Configure a separate key for the caption endpoint")
                val response = send(captionRequest, key, consent, authorized)
                caption = http.parseCaption(response)
            }
            val twoDomain = if (caption.isNotBlank()) com.memeget.nativeapp.knowledge.MemeGateEvaluator.parseTwoDomainDescription(caption, sourceTag = "cloud") else null
            val result = configuration.withLock {
                if (!authorized()) return@withLock null
                val current = store.meme(meme.id) ?: return@withLock null
                if (current.contentHash != payload.sourceContentHash || cloudEvidenceStamp(current) != expectedEvidence) return@withLock null
                val answer = decision
                val selected = answer?.let { decisionRequest?.choices?.get(it.choice) }
                val clefTags = if (answer != null && !answer.isUnknown && selected != null && !memory.isRejected(payload.sourceContentHash, selected.label)) {
                    listOf(Tag(selected.label, selected.category, answer.probabilities.getValue(answer.choice), "cloud", "provider-choice-probability", "unreviewed", "cloud:${answer.model}:${answer.promptHash}"))
                } else emptyList()
                val vlmTags = twoDomain?.tags.orEmpty().filterNot { vt ->
                    memory.isRejected(payload.sourceContentHash, vt.label) || clefTags.any { it.label.equals(vt.label, ignoreCase = true) }
                }
                val tags = (clefTags + vlmTags).take(8)
                val clairicTerms = buildList {
                    clairic?.extractedHandlesAndBrands?.let(::addAll)
                    clairic?.extractedOcrLines?.let(::addAll)
                }.joinToString(" ")
                val finalCaption = twoDomain?.combinedCaption?.ifBlank { caption } ?: caption
                val finalTerms = listOf(
                    tags.joinToString(" ") { it.label },
                    twoDomain?.searchTerms.orEmpty(),
                    clairicTerms,
                ).filter { it.isNotBlank() }.joinToString(" ")
                val result = CloudResult(
                    contentHash = payload.sourceContentHash,
                    model = listOfNotNull(clairic?.model, answer?.model, captionRequest?.model).joinToString(" + "),
                    caption = finalCaption,
                    tags = tags,
                    terms = finalTerms,
                    probabilities = answer?.probabilities.orEmpty(),
                    providerConfidence = answer?.providerConfidence,
                    promptHash = cacheKey,
                    expectedEvidence = expectedEvidence,
                    embeddingModel = vectorsBefore?.model.orEmpty(),
                    expectedVectorHash = expectedVectorHash,
                )
                if (!store.setCloud(meme.id, result)) return@withLock null
                val vectors = store.vectors(meme.id)
                if (vectors?.model == engine.primaryModel && cloudVectorStamp(vectors) == expectedVectorHash) {
                    if (answer != null && !answer.isUnknown) {
                        clefTags.forEach { tag -> memory.addPrototype(tag.label, tag.category, vectors.image, vectors.model, payload.sourceContentHash, answer.calculatedConfidence, tag.provenance) }
                    }
                    vlmTags.filter { it.category == "format" && it.score >= 0.75f }.take(2).forEach { tag ->
                        memory.addPrototype(tag.label, tag.category, vectors.image, vectors.model, payload.sourceContentHash, 0.72f, tag.provenance)
                    }
                }
                rememberObservation(cacheKey)
                result
            }
            mutableProgress.value = WorkProgress("idle", done = if (result == null) 0 else 1, total = 1)
            result
        } catch (cancelled: CancellationException) {
            http.cancelAll()
            mutableProgress.value = WorkProgress("idle")
            throw cancelled
        } catch (failure: Exception) {
            mutableProgress.value = WorkProgress("unavailable", error = failure.message ?: "Optional cloud analysis unavailable")
            null
        } finally { operation.unlock() }
    }

    private suspend fun send(request: CloudWireRequest, key: String, config: CloudConfig, authorized: () -> Boolean): String {
        currentCoroutineContext().ensureActive()
        if (!authorized()) throw IllegalStateException("Cloud consent was revoked")
        val estimate = when {
            request.caption || config.provider == CloudProviderType.SELFHOST_SYSTEMONE -> {
                require(config.acknowledgeProviderBudget) { "This endpoint is unpriced: set a provider-side spending cap and acknowledge it in settings" }
                0.0
            }
            config.provider == CloudProviderType.TYPESAFE_SYSTEMONE -> 32768 * 0.042 / 1_000_000
            request.model == "clairic" -> 16384 * 0.10 / 1_000_000
            config.cloudflareModel == "clef-flash" -> 24576 * 0.038 / 1_000_000
            else -> 65536 * 0.24 / 1_000_000
        }
        // Reserve exact serialized outbound bytes plus the maximum allowed response, for EVERY call.
        val reservation = ledger.reserve(config, request.body.size.toLong() + CloudPayloadPrep.MAX_RESPONSE_BYTES, estimate)
        try {
            val response = http.execute(request, key, sendGate, authorized)
            ledger.commit(reservation)
            return response
        } catch (failure: Throwable) {
            ledger.abort(reservation)
            throw failure
        }
    }

    suspend fun saveConfig(config: CloudConfig, apiKey: String?, judgeKey: String?) = withContext(Dispatchers.IO) {
        configuration.withLock {
            val old = cached
            val revision = old.consentRevision + 1
            // Persist revocation before validation or credential writes. A failed settings save
            // must not resurrect the previous upload permission after a process restart.
            synchronized(sendGate) {
                cached = old.copy(enabled = false, consentRevision = revision)
                cancellationEpoch++
                http.cancelAll()
            }
            writeCloudAtomic(configFile, cached.toJson().toByteArray(Charsets.UTF_8))
            require(config.dailyRequestLimit in 1..500 && config.dailyByteLimit in 1..100L * 1024 * 1024)
            require(config.dailyCostBudgetUsd.isFinite() && config.dailyCostBudgetUsd > 0 && config.autoEscalateThreshold.isFinite() && config.autoEscalateThreshold in 0f..1f)
            if (config.enabled) require(config.consentTextState || config.consentVisualMedia) { "Select which evidence may leave the device" }
            val newlyEnabled = config.enabled && (!old.enabled || config.automaticEscalation && !old.automaticEscalation)
            val afterId = if (newlyEnabled) store.allMemes().fold(0L) { largest, meme -> maxOf(largest, meme.id) } else old.newItemsAfterId
            val updated = config.copy(consentRevision = revision, consentTimestamp = if (newlyEnabled) System.currentTimeMillis() else old.consentTimestamp, newItemsAfterId = afterId)
            if (apiKey != null || judgeKey != null) vault.saveSecrets(apiKey, judgeKey, primaryBinding(updated), captionBinding(updated))
            if (updated.enabled) {
                if (updated.provider == CloudProviderType.TYPESAFE_SYSTEMONE) require(updated.consentTextState) { "JEV can judge text evidence only" }
                require(vault.getApiKey(primaryBinding(updated)) != null) { "Configure a key for this decision endpoint" }
                if (updated.captionEndpointEnabled) require(vault.getJudgeKey(captionBinding(updated)) != null) { "Configure a separate caption-endpoint key" }
            }
            writeCloudAtomic(configFile, updated.toJson().toByteArray(Charsets.UTF_8))
            synchronized(sendGate) { cached = updated }
        }
    }

    suspend fun config(): CloudConfig = withContext(Dispatchers.IO) {
        val value = cached
        value.copy(hasApiKey = vault.getApiKey(primaryBinding(value)) != null, hasJudgeKey = vault.getJudgeKey(captionBinding(value)) != null)
    }

    fun cancel() {
        synchronized(sendGate) { cancellationEpoch++; http.cancelAll() }
    }

    suspend fun forget() = withContext(Dispatchers.IO) {
        configuration.withLock {
            cancel()
            memory.clear()
            store.forgetCloud()
            observationsFile.delete()
            // Spending reservations and user rejection records deliberately survive forgetting observations.
            mutableProgress.value = WorkProgress("idle")
        }
    }

    suspend fun confirm(memeId: Long, label: String, accepted: Boolean) = withContext(Dispatchers.IO) {
        configuration.withLock {
            val meme = store.meme(memeId) ?: return@withLock
            val observed = requireNotNull(meme.tags.firstOrNull {
                it.label.equals(label, true) && it.source in setOf("cloud", "cloud_hint")
            }) { "This label is no longer a cloud suggestion" }
            if (accepted && observed.confirmation == "confirmed") return@withLock
            val category = observed.category
            store.teach(memeId, label, category, accepted, confirmedCloudTag = observed.takeIf { accepted })
            if (!accepted) {
                if (meme.contentHash.length == 64) memory.rejectLabel(meme.contentHash, label)
            }
        }
    }

    suspend fun recordTeaching(memeId: Long, label: String, category: String, positive: Boolean, associations: List<String>) = withContext(Dispatchers.IO) {
        configuration.withLock {
            val meme = store.meme(memeId) ?: return@withLock
            store.teach(memeId, label, category, positive, associations)
            if (!positive && meme.contentHash.length == 64) memory.rejectLabel(meme.contentHash, label)
        }
    }

    suspend fun candidateHints(vector: FloatArray, limit: Int = 8, contentHash: String = ""): List<Tag> = withContext(Dispatchers.IO) {
        // Upload consent is irrelevant to use of knowledge already on this device.
        memory.findHints(vector, engine.primaryModel, contentHash, limit)
    }

    suspend fun analyzeExisting() = withContext(Dispatchers.IO) {
        val config = cached
        require(config.enabled && config.includeExisting) { "Existing-library scope requires separate consent" }
        val oldest = System.currentTimeMillis() - CloudPrototypeMemory.EXPIRY_MILLIS
        store.allMemes().filter { it.cloudObservation == null || it.cloudObservation.createdAt < oldest }
            .take(config.dailyRequestLimit).collect { meme ->
            currentCoroutineContext().ensureActive()
            val candidates = meme.tags.filter { it.source !in setOf("cloud", "cloud_hint") }
            analyze(meme, candidates, manual = true)
        }
    }

    suspend fun getLedgerSnapshot(): LedgerSnapshot = withContext(Dispatchers.IO) { ledger.getSnapshot(cached) }

    private fun eligible(config: CloudConfig, meme: Meme, manual: Boolean): Boolean = config.enabled &&
        (config.consentTextState || config.consentVisualMedia) && (manual || config.automaticEscalation &&
            (config.includeExisting || meme.id > config.newItemsAfterId))

    private fun networkAllowed(config: CloudConfig): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!config.unmeteredOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    private fun primaryBinding(config: CloudConfig): String = when (config.provider) {
        CloudProviderType.TYPESAFE_SYSTEMONE -> "https://api.typesafe.ai/v1/systemone"
        CloudProviderType.CLOUDFLARE_CLEF -> "https://api.cloudflare.com/${config.cloudflareAccountId}"
        CloudProviderType.SELFHOST_SYSTEMONE -> config.selfhostUrl.trim()
    }
    private fun captionBinding(config: CloudConfig) = config.captionEndpointUrl.trim().trimEnd('/')

    private fun loadConfig(): CloudConfig = if (!configFile.exists()) CloudConfig() else runCatching {
        require(configFile.length() <= 16_384)
        CloudConfig.fromJson(configFile.readText(Charsets.UTF_8))
    }.getOrDefault(CloudConfig())

    private fun observations(): JSONObject {
        if (!observationsFile.exists()) return JSONObject()
        if (observationsFile.length() > 256 * 1024) return JSONObject()
        return runCatching { JSONObject(observationsFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }
    private fun observationSeen(key: String): Boolean {
        val timestamp = observations().optLong(key, 0)
        val now = System.currentTimeMillis()
        return timestamp > 0 && now >= timestamp && now - timestamp < CloudPrototypeMemory.EXPIRY_MILLIS
    }
    private fun rememberObservation(key: String) {
        val data = observations()
        data.put(key, System.currentTimeMillis())
        if (data.length() > 2048) {
            data.keys().asSequence().toList().sortedBy { data.optLong(it) }.take(data.length() - 2048).forEach(data::remove)
        }
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024)
        writeCloudAtomic(observationsFile, bytes)
    }
}

@Composable
internal fun CloudSettings(assistant: CloudAssistant, onError: (String) -> Unit) = CloudSettingsPanel(assistant, onError)
