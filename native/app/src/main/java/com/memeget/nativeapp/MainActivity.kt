package com.memeget.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MemegetApplication).graph
    private var intakeError by mutableStateOf<String?>(null)
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handled = savedInstanceState?.getBoolean("intakeHandled") ?: false
        setContent {
            MemegetApp(graph.store, graph.files, graph.engine, graph.knowledge, graph.cloud, graph.coordinator)
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
                val ids = graph.files.importIntent(incoming)
                handled = true
                if (ids.isNotEmpty()) graph.coordinator.start(ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                intakeError = failure.message ?: "The selected media could not be imported. Already saved items remain in the library."
            }
        }
    }
}
