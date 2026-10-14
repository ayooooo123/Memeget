package com.memeget.nativeapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderBackupSyncTest {
    private val folder = Folder("content://tree/memes", "memes", 0)

    @Test fun backsUpOnceChangesStopAndAtOnceWhenTheAppLeavesTheScreen() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var runs = 0
        val sync = FolderBackupSync(changes, { runs++; BackupRun(1, emptyMap()) }, { _, _ -> }, backgroundScope, { currentTime }, 30_000)
        sync.start()
        runCurrent()

        changes.emit(Unit)
        advanceTimeBy(20_000)
        changes.emit(Unit)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals("each change restarts the quiet wait", 0, runs)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(50_000L, sync.lastBackupAt.value)

        sync.flush()
        runCurrent()
        assertEquals("nothing changed since the last backup", 1, runs)
        changes.emit(Unit)
        runCurrent()
        sync.flush()
        runCurrent()
        assertEquals("leaving the screen writes unsaved changes at once", 2, runs)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("and the wait it replaced adds no second write", 2, runs)
    }

    @Test fun aFolderThatKeepsFailingIsLoggedOnceAndTheBackupGoesQuiet() = runTest {
        val changes = MutableSharedFlow<Unit>()
        var runs = 0
        val logged = ArrayList<String>()
        val sync = FolderBackupSync(
            changes,
            { runs++; BackupRun(0, mapOf(folder to "Permission denied")) },
            // Like the store, logging the failure is itself a change to the library.
            { _, reason -> logged += reason; changes.emit(Unit) },
            backgroundScope,
            { currentTime },
            30_000,
        )
        sync.start()
        runCurrent()

        changes.emit(Unit)
        advanceTimeBy(10 * 60_000)
        runCurrent()

        assertEquals(listOf("Permission denied"), logged)
        assertEquals("one retry for the change the log made, then quiet", 2, runs)
        assertEquals(0L, sync.lastBackupAt.value)
    }
}
