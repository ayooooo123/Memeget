package expo.modules.memegetbg

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keep-alive lease is SHARED: the background indexer holds it for as long as it is describing
 * a library, and a video export holds one of its own for a minute at a time. Reference counting is
 * the change that works perfectly in the lane that introduced it and quietly breaks the other
 * holder, so both directions are proved here on the real service rather than reasoned about:
 *
 * * an export starting and finishing must not drop a hold the indexer still owns - the indexer is
 *   the app's core loop, and losing its foreground service mid-run stops a job the user is
 *   watching;
 * * the count must actually reach zero and the service must actually stop - a lease that never
 *   balances leaves a notification and a partial wake lock up until the process dies, which
 *   arrives days later as an untraceable battery complaint.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class KeepAliveLeaseInstrumentedTest {
  private companion object {
    const val SETTLE_TIMEOUT_SECONDS = 120L
  }

  private lateinit var context: Context
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    workDir = File(context.cacheDir, "keep_alive_lease_test").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
    // Every test starts from "nobody is holding it", or the counts below mean nothing.
    assertEquals(0, KeepAliveLease.holderCount())
    ExportTestSupport.awaitKeepAliveService(context, running = false)
  }

  @After
  fun tearDown() {
    while (KeepAliveLease.holderCount() > 0) KeepAliveLease.release(context)
    ExportTestSupport.awaitKeepAliveService(context, running = false)
    workDir.deleteRecursively()
    MemeVideoExporter.exportCacheDir(context).deleteRecursively()
  }

  @Test
  fun anExportDoesNotTakeTheIndexersLeaseWithIt() {
    // The indexer starts describing a library.
    KeepAliveLease.acquire(context, "Memeget", "Describing 2,185 memes", 12, 2_185)
    assertEquals(1, KeepAliveLease.holderCount())
    assertTrue("service did not start", ExportTestSupport.awaitKeepAliveService(context, running = true))

    // The user exports a remix while that runs, and it finishes.
    val outcome = runExportToCompletion("export-during-index")
    assertNotNull(outcome)

    // The indexer's hold survived, and so did its service.
    assertEquals(1, KeepAliveLease.holderCount())
    assertTrue(
      "the export's release stopped the indexer's foreground service",
      ExportTestSupport.keepAliveServiceRunning(context)
    )

    // And it still balances: the indexer letting go is what ends it.
    KeepAliveLease.release(context)
    assertEquals(0, KeepAliveLease.holderCount())
    assertTrue("service did not stop", ExportTestSupport.awaitKeepAliveService(context, running = false))
  }

  @Test
  fun aCancelledExportReturnsItsOwnLeaseAndOnlyItsOwn() {
    KeepAliveLease.acquire(context, "Memeget", "Describing 2,185 memes", 12, 2_185)
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)
    val plan = ExportTestSupport.planJson(
      source,
      ExportTestSupport.SHORT_DURATION_US,
      ExportTestSupport.SHORT_WIDTH,
      ExportTestSupport.SHORT_HEIGHT
    )
    val settled = CountDownLatch(1)
    val result = AtomicReference<Result<MemeVideoExporter.Outcome>?>(null)
    MemeVideoExporter.start(context, "cancel-during-index", plan, {}, {
      result.set(it)
      settled.countDown()
    })
    MemeVideoExporter.cancel("cancel-during-index")
    assertTrue(settled.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS))

    // Exactly its own lease came back: one, not two, and not none.
    assertEquals(1, KeepAliveLease.holderCount())
    assertTrue(ExportTestSupport.keepAliveServiceRunning(context))

    KeepAliveLease.release(context)
    assertTrue(ExportTestSupport.awaitKeepAliveService(context, running = false))
  }

  @Test
  fun theServiceStopsOnlyWhenTheLastHolderLetsGo() {
    KeepAliveLease.acquire(context, "Memeget", "Indexing")
    KeepAliveLease.acquire(context, "Memeget", "Rendering your remix")
    assertEquals(2, KeepAliveLease.holderCount())
    assertTrue(ExportTestSupport.awaitKeepAliveService(context, running = true))

    KeepAliveLease.release(context)
    assertEquals(1, KeepAliveLease.holderCount())
    // Deliberately not a poll-for-false: the point is that it is STILL up a moment later.
    Thread.sleep(750L)
    assertTrue(
      "the first release stopped a service someone still holds",
      ExportTestSupport.keepAliveServiceRunning(context)
    )

    KeepAliveLease.release(context)
    assertEquals(0, KeepAliveLease.holderCount())
    assertTrue(
      "the service outlived its last holder",
      ExportTestSupport.awaitKeepAliveService(context, running = false)
    )
  }

  @Test
  fun progressUpdatesAndOverReleasesCannotUnbalanceTheCount() {
    // The JS indexer pushes progress by re-issuing its start, thousands of times over a library.
    // Counting those would be a lease that never reaches zero.
    KeepAliveLease.acquire(context, "Memeget", "Describing", 0, 2_185)
    repeat(5) { index -> KeepAliveLease.update(context, "Memeget", "Describing", index, 2_185) }
    assertEquals(1, KeepAliveLease.holderCount())

    // The mirror image: a release from a holder that already let go must not eat someone else's.
    KeepAliveLease.release(context)
    assertEquals(0, KeepAliveLease.holderCount())
    KeepAliveLease.release(context)
    KeepAliveLease.release(context)
    assertEquals(0, KeepAliveLease.holderCount())
    assertTrue(ExportTestSupport.awaitKeepAliveService(context, running = false))

    // An update with nobody holding must not resurrect the service either.
    KeepAliveLease.update(context, "Memeget", "Describing", 1, 2_185)
    Thread.sleep(750L)
    assertFalse(ExportTestSupport.keepAliveServiceRunning(context))
    assertEquals(0, KeepAliveLease.holderCount())

    // And after all that, the next real acquire still works: the count is not stuck negative.
    KeepAliveLease.acquire(context, "Memeget", "Describing")
    assertEquals(1, KeepAliveLease.holderCount())
    assertTrue(ExportTestSupport.awaitKeepAliveService(context, running = true))
    KeepAliveLease.release(context)
    assertTrue(ExportTestSupport.awaitKeepAliveService(context, running = false))
  }

  private fun runExportToCompletion(id: String): MemeVideoExporter.Outcome? {
    val source = ExportTestSupport.copyAsset(workDir, ExportTestSupport.SHORT_ASSET)
    val plan = ExportTestSupport.planJson(
      source,
      ExportTestSupport.SHORT_DURATION_US,
      ExportTestSupport.SHORT_WIDTH,
      ExportTestSupport.SHORT_HEIGHT
    )
    val settled = CountDownLatch(1)
    val result = AtomicReference<Result<MemeVideoExporter.Outcome>?>(null)
    MemeVideoExporter.start(context, id, plan, {}, {
      result.set(it)
      settled.countDown()
    })
    assertTrue("export $id did not settle", settled.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    return result.get()?.getOrNull()
  }
}
