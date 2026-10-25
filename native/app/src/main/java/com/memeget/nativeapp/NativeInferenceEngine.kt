package com.memeget.nativeapp

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.memeget.nativeapp.inference.ClipTokenizer
import com.memeget.nativeapp.inference.CutoutSelector
import com.memeget.nativeapp.inference.ModelArtifact
import com.memeget.nativeapp.inference.ModelDownloader
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.inference.NativeBridge
import com.memeget.nativeapp.inference.OcrPostProcessor
import com.memeget.nativeapp.inference.PromptBuilder
import expo.modules.memegetbg.AudioExtractor
import expo.modules.memegetbg.MemeStillSubjectSegmenter
import expo.modules.memegetbg.MemeTextDetector
import expo.modules.memegetbg.SubjectCutoutResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class NativeInferenceEngine(
    private val context: Context,
) : InferenceEngine {

    private val nativeHandle = AtomicLong(0L)

    private val engineMutex = Mutex()
    private val _progress = MutableStateFlow(ModelProgress())
    override val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

    override val primaryModel: String = ModelRegistry.PRIMARY_MODEL_FINGERPRINT

    private val downloader = ModelDownloader()
    private val modelsDir: File by lazy {
        File(context.filesDir, "memeget_models").apply {
            mkdirs()
            // Reclaim 407 MB immediately on phones that pulled the retired text-only d1-omni GGUF.
            ModelRegistry.RETIRED_DECISION_FILES.forEach { File(this, it).delete() }
        }
    }

    override suspend fun embedImage(uri: String): FloatArray = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val imageModelFile = ensureModel(ModelRegistry.S2_IMAGE)

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            withLocalPath(uri) { localPath ->
                val vec = NativeBridge.nativeEmbedImage(
                    handle = handle,
                    modelPath = imageModelFile.absolutePath,
                    imagePath = localPath,
                    inputSize = 256,
                    normalize = true,
                )
                if (vec.size != 512 || !vec.all(Float::isFinite)) {
                    throw IllegalStateException("Invalid MobileCLIP-S2 image embedding returned (size=${vec.size})")
                }
                vec
            }
        }
    }

    // Keeps Master's last 64 reaction queries hot in RAM so re-searching "cooked" or backspacing never reloads 254 MB on CPU.
    private val queryVectorCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 64
    }

    private fun normalizeQueryKey(text: String): String =
        text.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    override fun cachedTextVector(text: String): FloatArray? {
        val cacheKey = normalizeQueryKey(text)
        if (cacheKey.isEmpty() || cacheKey.length > 160) return null
        return synchronized(queryVectorCache) { queryVectorCache[cacheKey]?.copyOf() }
    }

    override suspend fun warmTextEncoder() = withContext(Dispatchers.Default) {
        val textModelFile = File(modelsDir, ModelRegistry.S2_TEXT.filename).takeIf {
            it.isFile && it.length() == ModelRegistry.S2_TEXT.expectedSizeBytes
        } ?: return@withContext
        val tokenizerFile = File(modelsDir, ModelRegistry.S2_TOKENIZER.filename).takeIf {
            it.isFile && it.length() == ModelRegistry.S2_TOKENIZER.expectedSizeBytes
        } ?: return@withContext
        val tokenIds = runCatching { clipTokenizer(tokenizerFile).encode("meme") }.getOrNull() ?: return@withContext
        // Never block behind an active background caption/OCR pass; warm only while the engine is free.
        if (!engineMutex.tryLock()) return@withContext
        try {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)
            NativeBridge.nativeEmbedTextTokens(handle, textModelFile.absolutePath, tokenIds)
        } catch (_: Throwable) {
        } finally {
            engineMutex.unlock()
        }
    }

    override suspend fun embedText(text: String): FloatArray = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val cacheKey = normalizeQueryKey(text)
        if (cacheKey.isNotEmpty() && cacheKey.length <= 160) {
            synchronized(queryVectorCache) { queryVectorCache[cacheKey] }?.let { return@withContext it.copyOf() }
        }
        val textModelFile = ensureModel(ModelRegistry.S2_TEXT)
        val tokenizerFile = ensureModel(ModelRegistry.S2_TOKENIZER)
        val tokenIds = clipTokenizer(tokenizerFile).encode(text)

        val computed = engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            val vec = NativeBridge.nativeEmbedTextTokens(
                handle = handle,
                modelPath = textModelFile.absolutePath,
                tokenIds = tokenIds,
            )
            if (vec.size != 512 || !vec.all(Float::isFinite)) {
                throw IllegalStateException("Invalid MobileCLIP-S2 text embedding returned (size=${vec.size})")
            }
            vec
        }
        if (cacheKey.isNotEmpty() && cacheKey.length <= 160) {
            synchronized(queryVectorCache) { queryVectorCache[cacheKey] = computed.copyOf() }
        }
        computed
    }

    @Volatile private var loadedClipTokenizer: Pair<String, ClipTokenizer>? = null

    private fun clipTokenizer(file: File): ClipTokenizer {
        loadedClipTokenizer?.takeIf { it.first == file.absolutePath }?.let { return it.second }
        synchronized(this) {
            loadedClipTokenizer?.takeIf { it.first == file.absolutePath }?.let { return it.second }
            return ClipTokenizer.load(file).also { loadedClipTokenizer = file.absolutePath to it }
        }
    }

    override suspend fun embedVisual(uri: String): FloatArray = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val modelFile = ensureModel(ModelRegistry.DINO_MODEL)
        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            withLocalPath(uri) { localPath ->
                val vec = NativeBridge.nativeEmbedImage(
                    handle = handle,
                    modelPath = modelFile.absolutePath,
                    imagePath = localPath,
                    inputSize = 224,
                    normalize = true,
                )
                if (vec.size != 768 || !vec.all(Float::isFinite)) {
                    throw IllegalStateException("Invalid DINOv2 visual embedding returned (size=${vec.size})")
                }
                vec
            }
        }
    }

    override suspend fun recognize(uri: String): List<TextRegion> = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val craftFile = ensureModel(ModelRegistry.OCR_CRAFT)
        val crnnFile = ensureModel(ModelRegistry.OCR_CRNN)

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            val alreadyUprightModelJpeg = uri.startsWith("file://") &&
                (Uri.parse(uri).path?.let { p ->
                    val f = File(p)
                    f.isFile && f.name.startsWith("upright_")
                } == true)
            if (alreadyUprightModelJpeg) {
                val directPath = Uri.parse(uri).path ?: uri.removePrefix("file://")
                val raw = NativeBridge.nativeRecognize(
                    handle = handle,
                    detectorPath = craftFile.absolutePath,
                    recognizerPath = crnnFile.absolutePath,
                    imagePath = directPath,
                )
                OcrPostProcessor.toReadingOrderRegions(raw)
            } else {
                // External/editor URI: prepare upright copy matching editor coordinate space
                val prepared = MemeTextDetector.prepareForTextDetection(context, uri)
                try {
                    val preparedFile = Uri.parse(prepared.uri).path ?: prepared.uri
                    val raw = NativeBridge.nativeRecognize(
                        handle = handle,
                        detectorPath = craftFile.absolutePath,
                        recognizerPath = crnnFile.absolutePath,
                        imagePath = preparedFile,
                    )
                    OcrPostProcessor.toReadingOrderRegions(raw)
                } finally {
                    runCatching {
                        Uri.parse(prepared.uri).path?.let { File(it).delete() }
                    }
                }
            }
        }
    }

    override suspend fun describe(uri: String, grounding: String): String = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val modelFile = ensureModel(ModelRegistry.MINICPM_V47_MODEL)
        val projectorFile = ensureProjector()

        try {
            engineMutex.withLock {
                val handle = activeHandle()
                NativeBridge.nativeResetCancel(handle)

                withLocalPath(uri) { localPath ->
                    val skipVerbatimText = grounding.contains("Untrusted OCR evidence:") &&
                        PromptBuilder.hasSubstantialOcr(grounding.substringAfter("Untrusted OCR evidence:", ""))
                    val pixelBudget = if (!skipVerbatimText && isTallStrip(localPath)) {
                        DESCRIBE_TALL_STRIP_MAX_PIXELS
                    } else {
                        DESCRIBE_MAX_PIXELS
                    }
                    NativeBridge.nativeDescribe(
                        handle = handle,
                        modelPath = modelFile.absolutePath,
                        projectorPath = projectorFile.absolutePath,
                        imagePath = localPath,
                        systemPrompt = PromptBuilder.SYSTEM_PROMPT,
                        userPrompt = PromptBuilder.buildUserPrompt(grounding, skipVerbatimText = skipVerbatimText),
                        maxTokens = if (skipVerbatimText) 112 else 160,
                        temperature = 0.1f,
                        maxImagePixels = pixelBudget,
                    )
                }
            }
        } catch (failure: RuntimeException) {
            // cancel() makes the native call fail as an ordinary exception; report that as cancellation.
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    override suspend fun transcribe(uri: String): String = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()

        // Step 1: Extract 16 kHz mono float32 PCM using native MediaCodec AudioExtractor
        val extracted = AudioExtractor.extract(context, uri, 120.0)
            ?: return@withContext "" // null means no audio track: silent video

        val pcmPath = extracted["path"] as? String ?: return@withContext ""
        val pcmFile = Uri.parse(pcmPath).path?.let(::File) ?: File(pcmPath.removePrefix("file://"))

        val pcmFloats = try {
            if (!pcmFile.exists() || pcmFile.length() < 6400 * 4) {
                return@withContext "" // Under 0.4s intelligible speech
            }
            readFloatsFromFile(pcmFile)
        } finally {
            pcmFile.delete()
        }

        if (pcmFloats.size < 6400) {
            return@withContext ""
        }

        // Gate out silent MP4s, pure tones, hum, and dead air in <5 ms before touching the 448 MB Whisper weights.
        val speechPcm = com.memeget.nativeapp.inference.AudioVad.compactForWhisper(pcmFloats)
        if (speechPcm.isEmpty()) {
            return@withContext ""
        }

        // Step 2: Whisper STT inference over voiced windows only
        val whisperModelFile = ensureModel(ModelRegistry.WHISPER_MODEL)
        val tokenizerFile = ensureModel(ModelRegistry.WHISPER_TOKENIZER)
        ModelRegistry.RETIRED_WHISPER_FILES.forEach { File(modelsDir, it).delete() }

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            val rawTranscript = NativeBridge.nativeTranscribe(
                handle = handle,
                modelPath = whisperModelFile.absolutePath,
                tokenizerPath = tokenizerFile.absolutePath,
                pcmSamples = speechPcm,
            )
            PromptBuilder.cleanTranscript(rawTranscript)
        }
    }

    override suspend fun segment(uri: String, requestId: String): SubjectCutoutResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val fastSamFile = ensureModel(ModelRegistry.FAST_SAM)

        engineMutex.withLock {
            currentCoroutineContext().ensureActive()
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)
            val prepared = MemeStillSubjectSegmenter.prepare(context, uri, requestId)
            try {
                val workingPath = Uri.parse(prepared.workingUri).path ?: prepared.workingUri.removePrefix("file://")
                val rawInstances = NativeBridge.nativeSegment(
                    handle = handle,
                    modelPath = fastSamFile.absolutePath,
                    imagePath = workingPath,
                    maxInstances = 24,
                    confThresh = 0.5f,
                    iouThresh = 0.9f,
                )
                currentCoroutineContext().ensureActive()

                val selection = CutoutSelector.selectSubjectMasks(
                    instances = rawInstances,
                    frameWidth = prepared.workingWidth,
                    frameHeight = prepared.workingHeight,
                )

                val boxes = selection.subjects.flatMap { listOf(it.x, it.y, it.width, it.height) }
                val masks = selection.subjects.map {
                    val packed = CutoutSelector.packMaskBits(it.mask)
                    Base64.encodeToString(packed, Base64.NO_WRAP)
                }

                MemeStillSubjectSegmenter.writeCutouts(
                    requestId = requestId,
                    boxes = boxes,
                    masks = masks,
                    droppedSubjects = selection.dropped,
                )
            } catch (failure: Throwable) {
                MemeStillSubjectSegmenter.release(context, requestId)
                throw failure
            }
        }
    }

    override suspend fun unload() = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val handle = nativeHandle.get()
            if (handle > 0L) NativeBridge.nativeUnload(handle)
        }
    }

    override suspend fun unloadHeavy() = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            val handle = nativeHandle.get()
            if (handle > 0L) NativeBridge.nativeUnloadHeavy(handle)
        }
    }

    override fun cancel() {
        val handle = nativeHandle.get()
        if (handle > 0L) {
            NativeBridge.nativeCancel(handle)
        }
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(-1L)
        if (handle > 0L) {
            NativeBridge.nativeCancel(handle)
            NativeBridge.nativeDestroy(handle)
        }
    }

    fun diagnostics(): Map<String, Any> {
        val handle = nativeHandle.get()
        val nativeDiag = if (handle > 0L) NativeBridge.nativeDiagnostics(handle) else if (handle < 0L) "closed" else "uninitialized"
        val downloadedModels = modelsDir.listFiles()?.map { "${it.name}:${it.length()}" } ?: emptyList()
        return mapOf(
            "primaryModel" to primaryModel,
            "nativeHandle" to handle,
            "nativeDiagnostics" to nativeDiag,
            "modelsDirectory" to modelsDir.absolutePath,
            "cachedModels" to downloadedModels,
        )
    }

    /**
     * Downloads [artifact] or finds it verified on disk. A stop (Stop, the session limit, a screen closing) is not a
     * model error: the partial file stays and the next call resumes it. A failure is a [ModelUnavailableException] that
     * names the model, so callers wait for it instead of blaming the item that asked.
     */
    internal suspend fun ensureModel(artifact: ModelArtifact): File {
        _progress.value = ModelProgress(name = artifact.id, fraction = 0f, loading = true)
        return try {
            val file = downloader.ensureDownloaded(artifact, modelsDir) { fraction ->
                _progress.value = ModelProgress(name = artifact.id, fraction = fraction, loading = true)
            }
            _progress.value = ModelProgress(name = artifact.id, fraction = 1f, loading = false)
            file
        } catch (cancelled: CancellationException) {
            settle(artifact.id)
            throw cancelled
        } catch (failure: Exception) {
            throw unavailable(artifact.id, "Couldn't download ${artifact.filename}", failure)
        }
    }

    /** The F16 projector, converted once from the pinned BF16 download (ModelRegistry.MINICPM_V47_PROJECTOR_F16). */
    private suspend fun ensureProjector(): File {
        val artifact = ModelRegistry.MINICPM_V47_PROJECTOR_F16
        val projector = try {
            downloader.ensureDerived(artifact, modelsDir) { output ->
                val source = ensureModel(artifact.source)
                _progress.value = ModelProgress(name = artifact.id, fraction = 0f, loading = true)
                engineMutex.withLock {
                    val handle = activeHandle()
                    NativeBridge.nativeResetCancel(handle)
                    NativeBridge.nativeConvertProjector(handle, source.absolutePath, output.absolutePath)
                }
            }
        } catch (failure: Exception) {
            // A stopped conversion fails as an ordinary exception; it is a stop, not a model error.
            if (failure is CancellationException || !currentCoroutineContext().isActive) {
                settle(artifact.id)
                throw failure as? CancellationException
                    ?: CancellationException("Model preparation stopped").apply { initCause(failure) }
            }
            // A failed BF16 download has already reported itself.
            if (failure is ModelUnavailableException) throw failure
            throw unavailable(artifact.id, "Couldn't prepare ${artifact.filename}", failure)
        }
        _progress.value = ModelProgress(name = artifact.id, fraction = 1f, loading = false)
        // The BF16 download is only conversion input; the verified F16 copy replaces it on disk.
        File(modelsDir, artifact.source.filename).delete()
        return projector
    }

    /** A stopped download or conversion leaves no status behind, unless another model has reported since. */
    private fun settle(id: String) {
        _progress.update { if (it.name == id) ModelProgress() else it }
    }

    private fun unavailable(id: String, what: String, failure: Exception): ModelUnavailableException {
        val error = "$what: ${failure.message ?: failure.javaClass.simpleName}"
        _progress.value = ModelProgress(name = id, loading = false, error = error)
        return ModelUnavailableException(id, error, failure)
    }

    /** Called under engineMutex on a worker dispatcher; browsing never loads the native runtime. */
    private suspend fun activeHandle(): Long {
        currentCoroutineContext().ensureActive()
        val current = nativeHandle.get()
        check(current >= 0L) { "NativeInferenceEngine is closed" }
        if (current > 0L) return current
        val created = NativeBridge.nativeCreate()
        check(created > 0L) { "Native inference initialization failed" }
        if (!nativeHandle.compareAndSet(0L, created)) {
            NativeBridge.nativeDestroy(created)
            throw IllegalStateException("NativeInferenceEngine is closed")
        }
        currentCoroutineContext().ensureActive()
        return created
    }

    private suspend fun <T> withLocalPath(uri: String, block: (String) -> T): T {
        if (uri.startsWith("/")) {
            return block(uri)
        }
        if (uri.startsWith("file://")) {
            val path = Uri.parse(uri).path ?: uri.removePrefix("file://")
            return block(path)
        }

        // Handle content:// URIs by copying to a temporary file
        return withContext(Dispatchers.IO) {
            val parsedUri = Uri.parse(uri)
            val tempDir = File(context.cacheDir, "inference_temp").apply { mkdirs() }
            val tempFile = File(tempDir, "source_${UUID.randomUUID()}.tmp")
            try {
                context.contentResolver.openInputStream(parsedUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Could not open input stream for $uri")
                block(tempFile.absolutePath)
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun readFloatsFromFile(file: File): FloatArray {
        val bytes = file.readBytes()
        val numFloats = bytes.size / 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(numFloats)
        for (i in 0 until numFloats) {
            floats[i] = buffer.float
        }
        return floats
    }

    private fun isTallStrip(path: String): Boolean {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return false
        val ratio = maxOf(w, h).toFloat() / minOf(w, h).toFloat()
        return ratio >= 1.85f
    }

    private companion object {
        /** MiniCPM-V's 448x448 slice area: larger images would add a full encoder pass per extra slice. */
        const val DESCRIBE_MAX_PIXELS = 448 * 448
        /** Two slices for tall un-OCRed tweet/greentext strips so 3-px text doesn't mush into unreadability. */
        const val DESCRIBE_TALL_STRIP_MAX_PIXELS = 448 * 896
    }
}
