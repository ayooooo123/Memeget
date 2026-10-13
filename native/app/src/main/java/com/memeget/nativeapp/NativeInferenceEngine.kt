package com.memeget.nativeapp

import android.content.Context
import android.net.Uri
import android.util.Base64
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        File(context.filesDir, "memeget_models").apply { mkdirs() }
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

    override suspend fun embedText(text: String): FloatArray = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val textModelFile = ensureModel(ModelRegistry.S2_TEXT)
        val tokenizerFile = ensureModel(ModelRegistry.S2_TOKENIZER)

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            val vec = NativeBridge.nativeEmbedText(
                handle = handle,
                modelPath = textModelFile.absolutePath,
                tokenizerPath = tokenizerFile.absolutePath,
                text = text,
                maxTokens = 77,
            )
            if (vec.size != 512 || !vec.all(Float::isFinite)) {
                throw IllegalStateException("Invalid MobileCLIP-S2 text embedding returned (size=${vec.size})")
            }
            vec
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

            // Prepare upright copy matching editor coordinate space
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

    override suspend fun describe(uri: String, grounding: String): String = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val modelFile = ensureModel(ModelRegistry.MINICPM_V47_MODEL)
        val projectorFile = ensureProjector()

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            withLocalPath(uri) { localPath ->
                NativeBridge.nativeDescribe(
                    handle = handle,
                    modelPath = modelFile.absolutePath,
                    projectorPath = projectorFile.absolutePath,
                    imagePath = localPath,
                    systemPrompt = PromptBuilder.SYSTEM_PROMPT,
                    userPrompt = PromptBuilder.buildUserPrompt(grounding),
                    maxTokens = 320,
                    temperature = 0.1f,
                    maxImagePixels = DESCRIBE_MAX_PIXELS,
                )
            }
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

        // Step 2: Whisper STT inference
        val whisperModelFile = ensureModel(ModelRegistry.WHISPER_MODEL)
        val tokenizerFile = ensureModel(ModelRegistry.WHISPER_TOKENIZER)

        engineMutex.withLock {
            val handle = activeHandle()
            NativeBridge.nativeResetCancel(handle)

            val rawTranscript = NativeBridge.nativeTranscribe(
                handle = handle,
                modelPath = whisperModelFile.absolutePath,
                tokenizerPath = tokenizerFile.absolutePath,
                pcmSamples = pcmFloats,
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

    private suspend fun ensureModel(artifact: ModelArtifact): File {
        _progress.value = ModelProgress(name = artifact.id, fraction = 0f, loading = true)
        return try {
            val file = downloader.ensureDownloaded(artifact, modelsDir) { fraction ->
                _progress.value = ModelProgress(name = artifact.id, fraction = fraction, loading = true)
            }
            _progress.value = ModelProgress(name = artifact.id, fraction = 1f, loading = false)
            file
        } catch (cancelled: CancellationException) {
            _progress.value = ModelProgress(name = artifact.id, loading = false, error = "Cancelled")
            throw cancelled
        } catch (failure: Exception) {
            val err = failure.message ?: "Failed to download model ${artifact.filename}"
            _progress.value = ModelProgress(name = artifact.id, loading = false, error = err)
            throw IOException(err, failure)
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
        } catch (cancelled: CancellationException) {
            _progress.value = ModelProgress(name = artifact.id, loading = false, error = "Cancelled")
            throw cancelled
        } catch (failure: Exception) {
            val err = failure.message ?: "Failed to prepare model ${artifact.filename}"
            _progress.value = ModelProgress(name = artifact.id, loading = false, error = err)
            throw IOException(err, failure)
        }
        _progress.value = ModelProgress(name = artifact.id, fraction = 1f, loading = false)
        // The BF16 download is only conversion input; the verified F16 copy replaces it on disk.
        File(modelsDir, artifact.source.filename).delete()
        return projector
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

    private companion object {
        /** MiniCPM-V's 448x448 slice area: larger images would add a full encoder pass per extra slice. */
        const val DESCRIBE_MAX_PIXELS = 448 * 448
    }
}
