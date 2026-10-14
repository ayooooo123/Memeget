package com.memeget.nativeapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps each linked folder's `.memeget-native` backup current without a tap, as the original app keeps `.memeget`:
 * a backup once the library has gone [quietMillis] without a change (labels, teachings, or analysis such as captions
 * and transcripts), and at once when the app leaves the screen with changes not backed up yet. Each change during a
 * long indexing pass pushes the backup back, so it runs once the pass goes quiet.
 */
internal class FolderBackupSync(
    private val changes: Flow<*>,
    private val backupAll: suspend () -> BackupRun,
    /** Records a folder's failure. Called once per failing folder until a backup of it works again. */
    private val logFailure: suspend (Folder, String) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val quietMillis: Long = QUIET_MILLIS,
) {
    private val seen = AtomicLong()
    private val covered = AtomicLong()
    private val writer = Mutex()
    /** Folders whose failure is already logged; guarded by [writer]. Logging changes the library, so repeating it would never let the backup go quiet. */
    private val failing = HashSet<String>()
    private var timer: Job? = null
    private val mutableLastBackupAt = MutableStateFlow(0L)
    /** When a backup last wrote every linked folder, in epoch milliseconds; 0 until one does. */
    val lastBackupAt: StateFlow<Long> = mutableLastBackupAt.asStateFlow()

    fun start(): Job = scope.launch { changes.collect { changed() } }

    private fun changed() {
        seen.incrementAndGet()
        synchronized(this) {
            timer?.cancel()
            timer = scope.launch {
                delay(quietMillis)
                // Its own job: a change arriving while the backup writes restarts the wait, not the write.
                scope.launch { backUp() }
            }
        }
    }

    /** Backs up at once if anything changed since the last backup, as when the app leaves the screen. */
    fun flush() {
        if (seen.get() <= covered.get()) return
        synchronized(this) {
            timer?.cancel()
            timer = null
        }
        scope.launch { backUp() }
    }

    private suspend fun backUp() = writer.withLock {
        val upTo = seen.get()
        if (upTo <= covered.get()) return@withLock
        val run = backupAll()
        for ((folder, reason) in run.failed) {
            if (failing.add(folder.uri)) logFailure(folder, reason)
        }
        failing.retainAll(run.failed.keys.mapTo(HashSet()) { it.uri })
        covered.accumulateAndGet(upTo, ::maxOf)
        if (run.written > 0 && run.failed.isEmpty()) mutableLastBackupAt.value = clock()
    }

    companion object {
        const val QUIET_MILLIS = 30_000L
    }
}
