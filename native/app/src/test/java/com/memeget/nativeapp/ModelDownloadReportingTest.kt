package com.memeget.nativeapp

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.memeget.nativeapp.inference.ModelArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** How the engine reports a model download that is stopped or fails; what Settings and the coordinator see. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelDownloadReportingTest {
    @get:Rule val temporary = TemporaryFolder()

    private val server = MockWebServer()
    private val bytes = ByteArray(512 * 1024) { (it * 31).toByte() }
    private lateinit var context: Context

    @Before
    fun setup() {
        val filesRoot = temporary.newFolder("files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = filesRoot
        }
        server.start()
    }

    @After
    fun shutdown() {
        server.shutdown()
    }

    private fun artifact() = ModelArtifact(
        id = "test-model",
        filename = "test-model.pte",
        url = server.url("/test-model.pte").toString(),
        sha256Hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        expectedSizeBytes = bytes.size.toLong(),
    )

    @Test(timeout = 30_000)
    fun aStoppedDownloadIsNoModelErrorAndPicksUpWhereItStopped() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val from = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    ?: return MockResponse().setBody(Buffer().write(bytes)).throttleBody(16 * 1024, 250, TimeUnit.MILLISECONDS)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                    .setBody(Buffer().write(bytes, from, bytes.size - from))
            }
        }
        val engine = NativeInferenceEngine(context)
        val download = launch(Dispatchers.Default) { engine.ensureModel(artifact()) }
        withTimeout(10_000L) { engine.progress.first { it.loading && it.fraction > 0f } }

        // Stop, the session limit or a closed screen ends the wait. That is not a failure, and what arrived is kept.
        download.cancelAndJoin()
        assertNull(engine.progress.value.error)
        val partial = File(context.filesDir, "memeget_models/test-model.pte.part").length()
        assertTrue("partial bytes: $partial", partial in 1 until bytes.size)

        val file = engine.ensureModel(artifact())

        assertArrayEquals(bytes, file.readBytes())
        server.takeRequest()
        assertEquals("bytes=$partial-", server.takeRequest().getHeader("Range"))
    }

    @Test(timeout = 30_000)
    fun aDownloadThatFailsSaysWhichModelIsMissingAndWhy() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val engine = NativeInferenceEngine(context)

        val failure = try {
            engine.ensureModel(artifact())
            null
        } catch (unavailable: ModelUnavailableException) {
            unavailable
        }

        // The coordinator keys "wait for the next session" on this type; the reason reaches Settings.
        assertEquals("test-model", requireNotNull(failure).model)
        assertTrue(engine.progress.value.toString(), engine.progress.value.error.orEmpty().contains("503"))
    }
}
