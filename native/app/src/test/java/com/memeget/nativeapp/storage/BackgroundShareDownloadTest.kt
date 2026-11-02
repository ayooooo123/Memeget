package com.memeget.nativeapp.storage

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.memeget.nativeapp.LibraryFiles
import com.memeget.nativeapp.SqliteLibraryStore
import expo.modules.memegetbg.KeepAliveLease
import expo.modules.memegetbg.KeepAliveService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Proves that sharing a video link into Memeget acquires KeepAliveLease synchronously, keeps downloading
// when the user immediately swipes Back (destroying the Activity), hands off the lease to indexing without
// dropping KeepAliveService, and releases cleanly once finished.

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundShareDownloadTest {

    @Test
    fun sharedVideoLinkSurvivesImmediateActivityDestructionAndHandsOffKeepAliveLease() {
        val context = RuntimeEnvironment.getApplication()
        while (KeepAliveLease.holderCount() > 0) KeepAliveLease.release(context)

        val store = SqliteLibraryStore(context)
        val files = LibraryFiles(context, store)

        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val intakeSaved = CountDownLatch(1)
        val holderDuringHandoff = AtomicInteger(0)
        val savedIdsRef = AtomicReference<List<Long>>(emptyList())

        val mp4Payload = "BACKGROUND_SURVIVING_YOUTUBE_MP4_PAYLOAD".toByteArray()

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/watch" -> {
                            val base = server.url("").toString().trimEnd('/')
                            MockResponse().setBody("""<html><meta property="og:video" content="$base/slow_clip.mp4"><meta property="og:video:type" content="video/mp4"></html>""")
                        }
                        "/slow_clip.mp4" -> {
                            downloadStarted.countDown()
                            assertTrue("Timed out waiting for Activity destruction before finishing download", releaseDownload.await(5, TimeUnit.SECONDS))
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "video/mp4")
                                .setBody(Buffer().write(mp4Payload))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()

            val activityController = Robolectric.buildActivity(Activity::class.java).create().start().resume()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Watch this clip ${server.url("/watch")}")
            }

            try {
                files.startIntentIntake(shareIntent) { ids ->
                    holderDuringHandoff.set(KeepAliveLease.holderCount())
                    savedIdsRef.set(ids)
                    intakeSaved.countDown()
                }

                // Foreground service must be requested synchronously while Activity is still visible!
                assertEquals("KeepAliveLease must be held immediately upon share receipt", 1, KeepAliveLease.holderCount())
                val startedSvc = shadowOf(context).nextStartedService
                assertNotNull("KeepAliveService start intent must be dispatched", startedSvc)
                assertEquals(KeepAliveService::class.java.name, startedSvc.component?.className)

                // Wait until the HTTP stream is mid-flight, then destroy the Activity (simulating immediate Back swipe to YouTube)
                assertTrue("Download did not start", downloadStarted.await(5, TimeUnit.SECONDS))
                activityController.pause().stop().destroy()

                // Unblock the HTTP response now that the Activity is completely destroyed
                releaseDownload.countDown()

                assertTrue("Background download was cancelled when Activity was destroyed!", intakeSaved.await(5, TimeUnit.SECONDS))
                val ids = savedIdsRef.get()
                assertEquals(1, ids.size)
                assertTrue("onSaved must run while download KeepAliveLease is still held (1 -> 2 -> 1 handoff)", holderDuringHandoff.get() >= 1)

                val savedMeme = runBlocking { store.meme(ids.first()) }
                assertNotNull("Downloaded video must be persisted in SqliteLibraryStore", savedMeme)
                assertEquals("video", savedMeme?.kind)

                // Wait briefly for the finally { KeepAliveLease.release } after onSaved
                val deadline = System.currentTimeMillis() + 2000L
                while (KeepAliveLease.holderCount() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(15)
                }
                assertEquals("KeepAliveLease must return to 0 after completion", 0, KeepAliveLease.holderCount())
            } finally {
                releaseDownload.countDown()
                while (KeepAliveLease.holderCount() > 0) KeepAliveLease.release(context)
            }
        }
    }
}
