package com.memeget.nativeapp

import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import expo.modules.memegetbg.KeepAliveLease
import expo.modules.memegetbg.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android 15's six-hour `dataSync` limit: the service must stop itself, and its holders must hear about it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeepAliveTimeoutTest {
    @Test
    fun theSixHourTimeoutStopsTheServiceAndTellsItsHolders() {
        val context = RuntimeEnvironment.getApplication()
        KeepAliveLease.acquire(context, "Memeget Native", "Indexing saved media")
        val start = requireNotNull(shadowOf(context).nextStartedService)
        val controller = Robolectric.buildService(KeepAliveService::class.java, start).create().startCommand(0, 1)
        var told = 0
        val listener: () -> Unit = { told++ }
        KeepAliveLease.addLostListener(listener)
        try {
            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

            // Not stopping within seconds is a fatal error for the whole app.
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(shadowOf(controller.get()).isForegroundStopped)
            assertEquals(1, told)

            // The service is gone: progress updates and the final release must not start it again from the
            // background, where Android refuses it.
            KeepAliveLease.update(context, "Memeget Native", "Still working")
            KeepAliveLease.release(context)
            assertNull(shadowOf(context).nextStartedService)
            assertEquals(0, KeepAliveLease.holderCount())
        } finally {
            KeepAliveLease.removeLostListener(listener)
            while (KeepAliveLease.holderCount() > 0) KeepAliveLease.release(context)
        }
    }

    @Test
    fun aNewLeaseAfterTheTimeoutStartsTheServiceAgain() {
        val context = RuntimeEnvironment.getApplication()
        KeepAliveLease.acquire(context, "Memeget Native", "Indexing saved media")
        val controller = Robolectric.buildService(KeepAliveService::class.java, shadowOf(context).nextStartedService).create().startCommand(0, 1)
        controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        KeepAliveLease.release(context)

        // Opening the app resets Android's budget; the next session asks for the service again.
        KeepAliveLease.acquire(context, "Memeget Native", "Indexing saved media")
        try {
            val again: Intent? = shadowOf(context).nextStartedService
            assertEquals(KeepAliveService::class.java.name, again?.component?.className)
        } finally {
            KeepAliveLease.release(context)
        }
    }
}
