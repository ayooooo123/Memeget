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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MemegetApplication).graph
    private var intakeError by mutableStateOf<String?>(null)
    private var handled = false
    private var notificationsAsked = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handled = savedInstanceState?.getBoolean("intakeHandled") ?: false
        setContent {
            MemegetApp(graph.store, graph.files, graph.engine, graph.knowledge, graph.cloud, graph.coordinator, graph.backups)
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
            runCatching { graph.knowledge.loadPersistedD1Head() }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("intakeHandled", handled)
        super.onSaveInstanceState(outState)
    }

    private fun receive(incoming: Intent?) {
        if (incoming == null || incoming.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        lifecycleScope.launch {
            try {
                val before = graph.store.countAll()
                val ids = graph.files.importIntent(incoming)
                handled = true
                if (ids.isNotEmpty()) {
                    val added = graph.store.countAll() - before
                    Toast.makeText(this@MainActivity, intakeMessage(added, ids.distinct().size - added), Toast.LENGTH_LONG).show()
                    graph.coordinator.start(ids)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                intakeError = failure.message ?: "The selected media could not be imported. Already saved items remain in the library."
            }
        }
    }

    /** The original app's wording: a share that was all memes already here says so instead of "Saved 0". */
    private suspend fun intakeMessage(added: Int, duplicates: Int): String {
        if (added <= 0) {
            return if (duplicates <= 1) "✓ Already in your library" else "✓ Already in your library ($duplicates duplicates)"
        }
        val place = graph.store.folders().firstOrNull()?.name?.let { "“$it”" } ?: "your library"
        val note = if (duplicates > 0) " ($duplicates already saved)" else ""
        return "✓ Saved $added to $place — indexing in background$note"
    }
}
