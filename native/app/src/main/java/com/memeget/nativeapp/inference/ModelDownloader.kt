package com.memeget.nativeapp.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private data class VerifiedFile(val modified: Long, val size: Long, val sha256: String)
    private val verified = ConcurrentHashMap<String, VerifiedFile>()
    private val downloadMutex = Mutex()
    private val deriveMutex = Mutex()

    suspend fun ensureDownloaded(
        artifact: ModelArtifact,
        modelsDir: File,
        onProgress: ((fraction: Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            currentCoroutineContext().ensureActive()
            val expectedSize = requireNotNull(artifact.expectedSizeBytes) { "Model size must be pinned" }
            val sha256 = requireNotNull(artifact.sha256Hex) { "Model digest must be pinned" }
            require(expectedSize > 0 && sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid model integrity pin" }
            if (!modelsDir.isDirectory && !modelsDir.mkdirs()) throw IOException("Cannot create model directory")
            val target = File(modelsDir, artifact.filename)
            if (isVerified(target, expectedSize, sha256)) {
                onProgress?.invoke(1f)
                return@withLock target
            }
            verified.remove(target.path)

            val part = File(modelsDir, "${artifact.filename}.part")
            val completePart = part.isFile && part.length() == expectedSize && verifySha256(part, sha256)
            if (!completePart) {
                if (part.length() >= expectedSize && !part.delete()) throw IOException("Cannot discard invalid model partial")
                downloadToFile(artifact.url, part, expectedSize, onProgress)
                if (part.length() != expectedSize || !verifySha256(part, sha256)) {
                    part.delete()
                    throw IOException("Model integrity check failed for ${artifact.filename}")
                }
            }
            currentCoroutineContext().ensureActive()
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            verified[target.path] = VerifiedFile(target.lastModified(), target.length(), sha256)
            onProgress?.invoke(1f)
            target
        }
    }

    /**
     * Returns a pinned file produced on the device from other pinned files, such as a converted model.
     * `produce` writes the candidate; it replaces the target only with the pinned size and SHA-256.
     */
    suspend fun ensureDerived(
        artifact: DerivedModelArtifact,
        modelsDir: File,
        produce: suspend (output: File) -> Unit,
    ): File = deriveMutex.withLock {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            require(artifact.expectedSizeBytes > 0 && artifact.sha256Hex.matches(Regex("[a-f0-9]{64}"))) {
                "Invalid model integrity pin"
            }
            if (!modelsDir.isDirectory && !modelsDir.mkdirs()) throw IOException("Cannot create model directory")
            val target = File(modelsDir, artifact.filename)
            if (isVerified(target, artifact.expectedSizeBytes, artifact.sha256Hex)) return@withContext target
            verified.remove(target.path)

            val part = File(modelsDir, "${artifact.filename}.part")
            try {
                part.delete()
                produce(part)
                currentCoroutineContext().ensureActive()
                if (part.length() != artifact.expectedSizeBytes || !verifySha256(part, artifact.sha256Hex)) {
                    throw IOException("Model integrity check failed for ${artifact.filename}")
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                part.delete()
            }
            verified[target.path] = VerifiedFile(target.lastModified(), target.length(), artifact.sha256Hex)
            target
        }
    }

    private suspend fun isVerified(target: File, expectedSize: Long, sha256: String): Boolean {
        val stamp = VerifiedFile(target.lastModified(), target.length(), sha256)
        if (!target.isFile || stamp.size != expectedSize) return false
        if (verified[target.path] != stamp && !verifySha256(target, sha256)) return false
        verified[target.path] = stamp
        return true
    }

    // onCancelling is required: a completion-only callback cannot interrupt a blocked body read.
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadToFile(
        url: String,
        partFile: File,
        expectedSize: Long,
        onProgress: ((fraction: Float) -> Unit)?,
    ) {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        var downloadedBytes = partFile.length()
        val request = Request.Builder().url(url).apply {
            if (downloadedBytes > 0) header("Range", "bytes=$downloadedBytes-")
        }.build()
        val call = client.newCall(request)
        val cancellation = coroutine.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Model download HTTP ${response.code}")
                val append = response.code == 206 && downloadedBytes > 0
                if (response.code == 206) {
                    val range = response.header("Content-Range").orEmpty()
                    if (!range.startsWith("bytes $downloadedBytes-")) throw IOException("Invalid model resume range")
                }
                if (!append) downloadedBytes = 0
                val body = response.body ?: throw IOException("Empty model response")
                if (body.contentLength() > expectedSize - downloadedBytes) throw IOException("Model response exceeds pinned size")
                FileOutputStream(partFile, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > expectedSize - downloadedBytes) throw IOException("Model download exceeds pinned size")
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            val now = System.nanoTime()
                            if (now - lastReport >= 100_000_000L) {
                                lastReport = now
                                onProgress?.invoke(downloadedBytes.toFloat() / expectedSize)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        } catch (failure: IOException) {
            coroutine.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }
    }

    private suspend fun verifySha256(file: File, expectedHex: String): Boolean {
        val coroutine = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expectedHex
    }
}
