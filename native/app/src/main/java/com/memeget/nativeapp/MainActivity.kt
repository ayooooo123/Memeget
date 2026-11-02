package com.memeget.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import com.memeget.nativeapp.storage.LinkResolver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MemegetApplication).graph
    private var intakeError by mutableStateOf<String?>(null)
    private var incomingSearchQuery by mutableStateOf<String?>(null)
    private var handled = false
    /** The last Settings import whose outcome was shown, so a screen rotation doesn't show it again. */
    private var shownImport = 0L
    private var notificationsAsked = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handled = savedInstanceState?.getBoolean("intakeHandled") ?: false
        shownImport = restoredShownImport(savedInstanceState?.getLong("shownImport"), graph.files.importStatus.value)
        // Rect tracking feeds only onLayoutRectChanged / onVisibilityChanged, which the app doesn't use, and it
        // re-measures every node under a layer whose transform changes: each frame of a canvas pinch or drag
        // re-walked every tile, about a third of the main thread on the 5a.
        @OptIn(ExperimentalComposeUiApi::class)
        ComposeUiFlags.isRectTrackingEnabled = false
        setContent {
            MemegetApp(
                store = graph.store,
                files = graph.files,
                engine = graph.engine,
                knowledge = graph.knowledge,
                cloud = graph.cloud,
                coordinator = graph.coordinator,
                backups = graph.backups,
                incomingSearchQuery = incomingSearchQuery,
                onIncomingSearchConsumed = { incomingSearchQuery = null },
            )
            intakeError?.let { message ->
                AlertDialog(
                    onDismissRequest = { intakeError = null },
                    title = { Text("Import did not finish") },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = { intakeError = null }) { Text("Close") } },
                )
            }
        }
        if (!handled) receive(intent)
        graph.coordinator.schedule()
        lifecycleScope.launch {
            runCatching { graph.knowledge.loadPersistedVerifierHead() }
        }
        // Android 13 hides the indexing notification until the app may post one. Ask the first time there is work
        // to show, as the original app does; once refused twice, Android stops showing the prompt.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (notificationsAsked || Build.VERSION.SDK_INT < 33) return@repeatOnLifecycle
                graph.coordinator.progress.first { it.running }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationsAsked = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        // A Settings import keeps running when Settings closes; its outcome shows on whatever screen is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.files.importStatus.collect { status ->
                    val outcome = status.outcome
                    if (status.running || outcome == null || status.id <= shownImport) return@collect
                    shownImport = status.id
                    if (status.failed) intakeError = outcome
                    else Toast.makeText(this@MainActivity, outcome, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("intakeHandled", handled)
        outState.putLong("shownImport", shownImport)
        super.onSaveInstanceState(outState)
    }

    private fun receive(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.action == Intent.ACTION_PROCESS_TEXT) {
            val selected = incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
            handled = true
            if (selected.isNotEmpty()) {
                incomingSearchQuery = selected.take(400)
            }
            return
        }
        if (incoming.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        if (incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain" && incoming.data == null && !incoming.hasExtra(Intent.EXTRA_STREAM)) {
            val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val sharedSubject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            if (sharedText.isNotEmpty() && LinkResolver.extractUrl(sharedText, sharedSubject) == null) {
                handled = true
                incomingSearchQuery = sharedText.take(400)
                return
            }
        }
        handled = true
        graph.files.startIntentIntake(incoming) { ids ->
            graph.coordinator.start(ids)
        }
    }
}

/**
 * The last Settings import whose outcome a new window should treat as shown. Import ids restart at 0 with the
 * process while saved window state outlives it, so a saved id is capped at what this process has handed out. A new
 * window still shows the outcome of an import that is running, but not one that already ended.
 */
internal fun restoredShownImport(saved: Long?, status: ImportStatus): Long {
    val current = if (status.running) status.id - 1 else status.id
    return minOf(saved ?: current, current)
}
