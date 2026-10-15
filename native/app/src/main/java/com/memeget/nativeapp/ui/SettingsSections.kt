package com.memeget.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.inference.ModelArtifact
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.storage.MediaIoUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
internal fun EnrichmentSection(
    store: LibraryStore,
    files: LibraryFiles,
    coordinator: IndexCoordinator,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by store.revision.collectAsState()
    val progress by coordinator.progress.collectAsState()

    var enrichmentCounts by remember { mutableStateOf(EnrichmentCounts()) }
    var errorCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var visionEnabled by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(false) }
    var showRedoTranscriptsDialog by remember { mutableStateOf(false) }
    var generatingPosters by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            enrichmentCounts = store.enrichmentCounts()
            errorCounts = store.errorCounts()
            visionEnabled = store.setting("vision.enabled") == "1"
            audioEnabled = store.setting("audio.enabled") == "1"
        }
    }

    SettingsCard {
        Text(
            text = "Captions & Transcripts",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )

        if (progress.running) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Working: ${progress.stage} ${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.Volt,
                )
                OutlinedButton(
                    onClick = { coordinator.cancel() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Stop", color = MemegetColors.Danger)
                }
            }
        }

        if (visionEnabled) {
            Text(
                text = "Captions: ${enrichmentCounts.captionsWaiting} waiting · ${enrichmentCounts.captionsFailed} failed",
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.TextSecondary,
            )
            if (enrichmentCounts.captionsFailed > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { coordinator.start(emptyList()) },
                        enabled = enrichmentCounts.captionsWaiting > 0 && !progress.running,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                    ) {
                        Text(
                            text = "Caption ${enrichmentCounts.captionsWaiting} memes now",
                            fontSize = 13.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                store.requeueFailedEnrichment("vision")
                                coordinator.start(emptyList())
                            }
                        },
                        enabled = !progress.running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "Retry ${enrichmentCounts.captionsFailed} failed",
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                Button(
                    onClick = { coordinator.start(emptyList()) },
                    enabled = enrichmentCounts.captionsWaiting > 0 && !progress.running,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MemegetColors.Volt,
                        contentColor = MemegetColors.Background,
                    ),
                ) {
                    Text("Caption ${enrichmentCounts.captionsWaiting} memes now")
                }
            }
        } else {
            Text(
                text = "Turn on Local AI Captions above to caption memes.",
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.TextMuted,
            )
        }

        if (audioEnabled) {
            Text(
                text = "Transcripts: ${enrichmentCounts.transcriptsWaiting} waiting · ${enrichmentCounts.transcriptsFailed} failed",
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.TextSecondary,
            )
            if (enrichmentCounts.transcriptsFailed > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { coordinator.start(emptyList()) },
                        enabled = enrichmentCounts.transcriptsWaiting > 0 && !progress.running,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                    ) {
                        Text(
                            text = "Transcribe ${enrichmentCounts.transcriptsWaiting} videos now",
                            fontSize = 13.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                store.requeueFailedEnrichment("audio")
                                coordinator.start(emptyList())
                            }
                        },
                        enabled = !progress.running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "Retry ${enrichmentCounts.transcriptsFailed} failed",
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                Button(
                    onClick = { coordinator.start(emptyList()) },
                    enabled = enrichmentCounts.transcriptsWaiting > 0 && !progress.running,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MemegetColors.Volt,
                        contentColor = MemegetColors.Background,
                    ),
                ) {
                    Text("Transcribe ${enrichmentCounts.transcriptsWaiting} videos now")
                }
            }

            OutlinedButton(
                onClick = { showRedoTranscriptsDialog = true },
                enabled = !progress.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Redo all transcripts")
            }
        } else {
            Text(
                text = "Turn on Audio Transcription above to transcribe videos.",
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.TextMuted,
            )
        }

        if (enrichmentCounts.postersMissing > 0) {
            Button(
                onClick = {
                    generatingPosters = true
                    scope.launch {
                        try {
                            val made = withContext(Dispatchers.IO) {
                                // Listed first: each poster is a library write, which must not land mid-read.
                                val waiting = store.allMemes()
                                    .filter { it.kind == "video" && !it.pending && it.thumbUri.isBlank() }
                                    .toList()
                                var count = 0
                                for (meme in waiting) {
                                    try {
                                        // An empty result means no frame could be read.
                                        if (files.thumbnail(meme).isNotBlank()) count++
                                    } catch (_: Exception) {}
                                }
                                count
                            }
                            Toast.makeText(
                                context,
                                "Made $made video poster${if (made == 1) "" else "s"}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } finally {
                            generatingPosters = false
                        }
                    }
                },
                enabled = !generatingPosters,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.SurfaceBright,
                    contentColor = MemegetColors.TextPrimary,
                ),
            ) {
                Text(
                    text = if (generatingPosters) {
                        "Making posters…"
                    } else {
                        "Make ${enrichmentCounts.postersMissing} missing video posters"
                    },
                )
            }
        }

        if (errorCounts.isNotEmpty()) {
            val errString = errorCounts.entries.joinToString(" · ") { "${it.key} ${it.value}" }
            Text(
                text = "Errors: $errString",
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.Danger,
            )
        }
    }

    if (showRedoTranscriptsDialog) {
        ConfirmationDialog(
            title = "Redo all transcripts?",
            message = "Transcribes every video again on this phone. Each transcript stays searchable until its new one is ready.",
            confirmText = "Redo transcripts",
            isDanger = true,
            onDismiss = { showRedoTranscriptsDialog = false },
            onConfirm = {
                showRedoTranscriptsDialog = false
                scope.launch {
                    store.requeueAllTranscripts()
                    coordinator.start(emptyList())
                }
            },
        )
    }
}

@Composable
internal fun TaughtConceptsSection(
    store: LibraryStore,
    coordinator: IndexCoordinator,
    onSearchTag: (String) -> Unit,
) {
    val labelRevision by store.labelRevision.collectAsState()
    var exemplars by remember { mutableStateOf<List<Exemplar>>(emptyList()) }
    var tagCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var expanded by remember { mutableStateOf(false) }
    var forgetLabelTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(labelRevision) {
        withContext(Dispatchers.IO) {
            exemplars = store.exemplars()
            tagCounts = store.tagCounts()
        }
    }

    val grouped = remember(exemplars) {
        exemplars.groupBy { TagMerge.normalizeTagLabel(it.label) }
    }

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${grouped.size} taught tag${if (grouped.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MemegetColors.TextPrimary,
            )
            Text(
                text = if (expanded) "▴" else "▾",
                color = MemegetColors.TextSecondary,
                fontSize = 16.sp,
            )
        }

        if (expanded) {
            if (grouped.isEmpty()) {
                Text(
                    text = "No concepts taught yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextMuted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (normLabel, items) ->
                        val displayLabel = items.firstOrNull()?.label ?: normLabel
                        val posCount = items.count { it.positive }
                        val negCount = items.count { !it.positive }
                        val memeCount = tagCounts[normLabel] ?: 0

                        val hasSelf = items.any { it.origin != "pack" }
                        val hasPack = items.any { it.origin == "pack" }
                        val originText = if (hasSelf && hasPack) "you+pack" else if (hasPack) "pack" else "you"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MemegetColors.SurfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = displayLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MemegetColors.TextPrimary,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (hasPack) MemegetColors.Blue.copy(alpha = 0.2f)
                                                else MemegetColors.SurfaceBright
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = originText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (hasPack) MemegetColors.Blue else MemegetColors.TextSecondary,
                                        )
                                    }
                                }
                                Text(
                                    text = "$posCount yes · $negCount not · $memeCount memes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MemegetColors.TextMuted,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onSearchTag(displayLabel) }) {
                                    Text("View", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { forgetLabelTarget = displayLabel }) {
                                    Text(
                                        text = "Forget",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MemegetColors.Danger,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    forgetLabelTarget?.let { label ->
        ConfirmationDialog(
            title = "Forget “$label”?",
            message = "Removes all taught positive and negative exemplars for this concept.",
            confirmText = "Forget",
            isDanger = true,
            onDismiss = { forgetLabelTarget = null },
            onConfirm = {
                forgetLabelTarget = null
                scope.launch {
                    store.forgetLabel(label)
                    coordinator.retag()
                }
            },
        )
    }
}

@Composable
internal fun TeachingPacksSection(
    store: LibraryStore,
    coordinator: IndexCoordinator,
) {
    val labelRevision by store.labelRevision.collectAsState()
    var exemplars by remember { mutableStateOf<List<Exemplar>>(emptyList()) }
    var forgetPackTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(labelRevision) {
        withContext(Dispatchers.IO) {
            exemplars = store.exemplars()
        }
    }

    val packGroups = remember(exemplars) {
        exemplars.filter { it.origin == "pack" && it.pack.isNotBlank() }
            .groupBy { it.pack }
    }

    if (packGroups.isEmpty()) return

    SettingsCard {
        Text(
            text = "Imported Packs",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            packGroups.forEach { (pack, items) ->
                val distinctLabels = items.map { TagMerge.normalizeTagLabel(it.label) }.distinct().size
                val exampleCount = items.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MemegetColors.SurfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$pack: $distinctLabels label${if (distinctLabels == 1) "" else "s"} · $exampleCount example${if (exampleCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MemegetColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { forgetPackTarget = pack }) {
                        Text(
                            text = "✕",
                            color = MemegetColors.Danger,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    forgetPackTarget?.let { pack ->
        val items = packGroups[pack] ?: emptyList()
        val examples = items.size
        ConfirmationDialog(
            title = "Remove “$pack”?",
            message = "Deletes the $examples imported example${if (examples == 1) "" else "s"} from this pack. Your own teaching is untouched.",
            confirmText = "Remove",
            isDanger = true,
            onDismiss = { forgetPackTarget = null },
            onConfirm = {
                forgetPackTarget = null
                scope.launch {
                    store.forgetPack(pack)
                    coordinator.retag()
                }
            },
        )
    }
}

private sealed class TeachingImportStep {
    data object Peeking : TeachingImportStep()
    data class ChooseMode(val fileName: String, val count: Int) : TeachingImportStep()
    data object Importing : TeachingImportStep()
    data class AskRetag(val summary: String) : TeachingImportStep()
}

@Composable
internal fun TeachingImportDialog(
    uri: Uri,
    files: LibraryFiles,
    coordinator: IndexCoordinator,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<TeachingImportStep>(TeachingImportStep.Peeking) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val count = files.peekTeaching(uri)
                val fileName = MediaIoUtils.getDisplayName(context, uri).substringBeforeLast(".").ifBlank { "Teaching pack" }
                step = TeachingImportStep.ChooseMode(fileName, count)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Import failed: ${e.message ?: e.toString()}", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
        }
    }

    when (val currentStep = step) {
        TeachingImportStep.Peeking, TeachingImportStep.Importing -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = {
                    Text(
                        text = if (currentStep is TeachingImportStep.Peeking) "Reading pack…" else "Importing…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.TextPrimary,
                    )
                },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = MemegetColors.Volt)
                    }
                },
                containerColor = MemegetColors.Surface,
            )
        }

        is TeachingImportStep.ChooseMode -> {
            AlertDialog(
                onDismissRequest = onDone,
                title = {
                    Text(
                        text = currentStep.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.TextPrimary,
                    )
                },
                text = {
                    Text(
                        text = "${currentStep.count} example${if (currentStep.count == 1) "" else "s"} ready. Merge into your taught tags, or replace everything with this pack?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MemegetColors.TextSecondary,
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                step = TeachingImportStep.Importing
                                scope.launch {
                                    try {
                                        val res = files.importTeaching(uri, replace = true)
                                        val replacedNote = if (res.removed > 0) "Replaced ${res.removed} · " else ""
                                        val summary = if (res.added > 0) {
                                            "${replacedNote}Imported ${res.added} example${if (res.added == 1) "" else "s"}"
                                        } else {
                                            "Pack already fully imported — nothing new"
                                        }
                                        Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
                                        if (res.added > 0) {
                                            step = TeachingImportStep.AskRetag(summary)
                                        } else {
                                            onDone()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Import failed: ${e.message ?: e.toString()}", Toast.LENGTH_SHORT).show()
                                        onDone()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MemegetColors.Danger,
                                contentColor = MemegetColors.TextPrimary,
                            ),
                        ) {
                            Text("Replace all")
                        }
                        Button(
                            onClick = {
                                step = TeachingImportStep.Importing
                                scope.launch {
                                    try {
                                        val res = files.importTeaching(uri, replace = false)
                                        val summary = if (res.added > 0) {
                                            "Imported ${res.added} example${if (res.added == 1) "" else "s"}"
                                        } else {
                                            "Pack already fully imported — nothing new"
                                        }
                                        Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
                                        if (res.added > 0) {
                                            step = TeachingImportStep.AskRetag(summary)
                                        } else {
                                            onDone()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Import failed: ${e.message ?: e.toString()}", Toast.LENGTH_SHORT).show()
                                        onDone()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MemegetColors.Volt,
                                contentColor = MemegetColors.Background,
                            ),
                        ) {
                            Text("Merge")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDone) {
                        Text("Cancel", color = MemegetColors.TextSecondary)
                    }
                },
                containerColor = MemegetColors.Surface,
            )
        }

        is TeachingImportStep.AskRetag -> {
            AlertDialog(
                onDismissRequest = onDone,
                title = {
                    Text(
                        text = "Teaching imported",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.TextPrimary,
                    )
                },
                text = {
                    Text(
                        text = "${currentStep.summary}. Re-tag your library now to apply it?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MemegetColors.TextSecondary,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // Started at once: closing the dialog cancels this scope, and the request must already
                            // be in by then. The pass itself runs in the coordinator's scope.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                coordinator.retag()
                            }
                            onDone()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                    ) {
                        Text("Re-tag now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDone) {
                        Text("Later", color = MemegetColors.TextSecondary)
                    }
                },
                containerColor = MemegetColors.Surface,
            )
        }
    }
}

private data class RegisteredModelInfo(
    val feature: String,
    /** Artifact id the engine reports while this file downloads. */
    val id: String,
    val filename: String,
    /** The download a converted file is made from; it counts as downloaded until the conversion has run. */
    val sourceFilename: String? = null,
    val expectedBytes: Long,
)

private fun registered(feature: String, artifact: ModelArtifact) =
    RegisteredModelInfo(feature, artifact.id, artifact.filename, null, artifact.expectedSizeBytes ?: 0L)

private val REGISTERED_MODELS = listOf(
    registered("Search: Image Embedding", ModelRegistry.S2_IMAGE),
    registered("Search: Text Embedding", ModelRegistry.S2_TEXT),
    registered("Search: Text Tokenizer", ModelRegistry.S2_TOKENIZER),
    registered("Visual Similarity", ModelRegistry.DINO_MODEL),
    registered("OCR: Text Detection", ModelRegistry.OCR_CRAFT),
    registered("OCR: Text Recognition", ModelRegistry.OCR_CRNN),
    registered("Audio Transcription", ModelRegistry.WHISPER_MODEL),
    registered("Audio Tokenizer", ModelRegistry.WHISPER_TOKENIZER),
    registered("Subject Cutout", ModelRegistry.FAST_SAM),
    registered("AI Captions", ModelRegistry.MINICPM_V47_MODEL),
    RegisteredModelInfo(
        "AI Captions Visual Projector",
        ModelRegistry.MINICPM_V47_PROJECTOR.id,
        ModelRegistry.MINICPM_V47_PROJECTOR_F16.filename,
        ModelRegistry.MINICPM_V47_PROJECTOR.filename,
        ModelRegistry.MINICPM_V47_PROJECTOR_F16.expectedSizeBytes,
    ),
)

/** What one model has on disk: the finished file, or how far an unfinished download got. */
private sealed interface ModelFileState {
    data class Present(val bytes: Long) : ModelFileState
    data class Partial(val fraction: Float) : ModelFileState
    object Absent : ModelFileState
}

private fun megabytes(bytes: Long): Long = (bytes + 524_288L) / (1024L * 1024L)

@Composable
internal fun ModelsSection(progress: ModelProgress) {
    val context = LocalContext.current
    var states by remember { mutableStateOf<List<ModelFileState>>(emptyList()) }

    // Read again whenever a download starts, stops, finishes or fails; a running download's percentage comes from progress.
    LaunchedEffect(progress.name, progress.loading, progress.error) {
        states = withContext(Dispatchers.IO) {
            val modelsDir = File(context.filesDir, "memeget_models")
            REGISTERED_MODELS.map { info ->
                val finished = listOfNotNull(info.filename, info.sourceFilename)
                    .map { File(modelsDir, it) }
                    .firstOrNull { it.isFile && it.length() > 0 }
                val part = File(modelsDir, "${info.sourceFilename ?: info.filename}.part")
                when {
                    finished != null -> ModelFileState.Present(finished.length())
                    part.isFile && part.length() > 0 && info.expectedBytes > 0 ->
                        ModelFileState.Partial(part.length().toFloat() / info.expectedBytes)
                    else -> ModelFileState.Absent
                }
            }
        }
    }

    SettingsCard {
        Text(
            text = "On-Device Models",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )
        Text(
            text = "Each model downloads the first time a feature needs it. A stopped download picks up where it left off.",
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.TextSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            REGISTERED_MODELS.zip(states).forEach { (info, state) ->
                val live = progress.name == info.id
                val (status, color) = when {
                    state is ModelFileState.Present -> "Downloaded (${megabytes(state.bytes)} MB)" to MemegetColors.Success
                    live && progress.loading -> "Downloading ${(progress.fraction * 100).toInt()}%" to MemegetColors.Volt
                    live && progress.error != null -> "Download failed" to MemegetColors.Danger
                    state is ModelFileState.Partial -> "Paused at ${(state.fraction * 100).toInt()}%" to MemegetColors.TextSecondary
                    else -> "Not downloaded yet (${megabytes(info.expectedBytes)} MB)" to MemegetColors.TextMuted
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = info.feature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MemegetColors.TextPrimary,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BackupStatusLine(lastBackupAt: Long, heldFolders: Int) {
    val text = if (lastBackupAt <= 0L) {
        "Backs up to each linked folder by itself after changes"
    } else {
        val now = Calendar.getInstance()
        val backupCal = Calendar.getInstance().apply { timeInMillis = lastBackupAt }
        val isToday = now.get(Calendar.ERA) == backupCal.get(Calendar.ERA) &&
                now.get(Calendar.YEAR) == backupCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == backupCal.get(Calendar.DAY_OF_YEAR)

        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val timeStr = timeFormat.format(Date(lastBackupAt))

        val stamp = if (isToday) {
            timeStr
        } else {
            val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT)
            "${dateFormat.format(Date(lastBackupAt))} $timeStr"
        }
        "Backs up to each linked folder by itself · last at $stamp"
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MemegetColors.TextMuted,
    )
    if (heldFolders > 0) {
        Text(
            text = if (heldFolders == 1) {
                "Paused for 1 folder, so its backup isn't replaced by a library that holds less (after Clear Index, " +
                    "or a restore that didn't finish). Tap Restore or Backup on it to go on."
            } else {
                "Paused for $heldFolders folders, so their backups aren't replaced by a library that holds less (after " +
                    "Clear Index, or a restore that didn't finish). Tap Restore or Backup on each to go on."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.Warning,
        )
    }
}

@Composable
internal fun AboutSection() {
    val context = LocalContext.current

    SettingsCard {
        Text(
            text = "Privacy & About",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )
        Text(
            text = "Your memes stay on this phone. Search runs here, and you share folders with the app one at a time. Cloud features stay off unless you turn them on.",
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.TextSecondary,
        )
        Text(
            text = "Open app settings →",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MemegetColors.Volt,
            modifier = Modifier.clickable {
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
        )
        Text(
            text = "Memeget Native ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MemegetColors.TextMuted,
        )
    }
}

@Composable
internal fun ExportDescribedTagsButton(store: LibraryStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val describedMemes = ArrayList<JSONObject>()
                    store.allMemes().collect { meme ->
                        if (meme.visionState == "done" || meme.caption.isNotBlank()) {
                            val visionTags = meme.tags.filter { it.source == "vision" }.map { it.label }
                            val obj = JSONObject().apply {
                                put("id", meme.id.toString())
                                put("tags", JSONArray(visionTags))
                            }
                            describedMemes.add(obj)
                        }
                    }
                    if (describedMemes.isEmpty()) {
                        0
                    } else {
                        val jsonText = JSONArray().apply { describedMemes.forEach { put(it) } }.toString(2)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(jsonText.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("Could not write to destination")
                        describedMemes.size
                    }
                }
                if (count == 0) {
                    Toast.makeText(
                        context,
                        "Nothing described yet — describe some memes first",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Exported $count described meme${if (count == 1) "" else "s"}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Export failed: ${e.message ?: e.toString()}",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                exporting = false
            }
        }
    }

    OutlinedButton(
        onClick = { launcher.launch("memeget-described-tags-${java.time.LocalDate.now()}.json") },
        modifier = Modifier.fillMaxWidth(),
        enabled = !exporting,
    ) {
        Text(if (exporting) "Exporting…" else "Export described tags")
    }
}
