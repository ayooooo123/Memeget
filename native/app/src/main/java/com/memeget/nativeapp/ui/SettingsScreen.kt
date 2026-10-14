package com.memeget.nativeapp

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    store: LibraryStore,
    files: LibraryFiles,
    engine: InferenceEngine,
    knowledge: LocalKnowledge,
    cloud: CloudAssistant,
    coordinator: IndexCoordinator,
    onBack: () -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val revision by store.revision.collectAsState()
    val modelProgress by engine.progress.collectAsState()
    val indexProgress by coordinator.progress.collectAsState()

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var exemplars by remember { mutableStateOf<List<Exemplar>>(emptyList()) }
    var errorLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var memeCount by remember { mutableStateOf(0) }

    // Persisted toggles
    var visionEnabled by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(false) }
    var visualEnabled by remember { mutableStateOf(false) }
    var backgroundEnabled by remember { mutableStateOf(false) }
    var backgroundOnlyCharging by remember { mutableStateOf(false) }
    var backgroundPauseHot by remember { mutableStateOf(false) }
    var backgroundPauseLowBattery by remember { mutableStateOf(false) }

    // Dialogs state
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var showClearIndexDialog by remember { mutableStateOf(false) }
    var showUnlinkDialogForFolder by remember { mutableStateOf<Folder?>(null) }
    var showForgetLabelDialog by remember { mutableStateOf<String?>(null) }
    var showForgetPackDialog by remember { mutableStateOf<String?>(null) }

    var exportCollectionProgress by remember { mutableStateOf<WorkProgress?>(null) }

    // Activity Result Launchers
    val linkFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    files.link(uri)
                    Toast.makeText(context, "Linked folder", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Link failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val added = files.importUri(uri)
                    Toast.makeText(context, "Imported ${added.size} items", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importTeachingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val count = files.importTeaching(uri)
                    Toast.makeText(context, "Imported $count teachings", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import teachings failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val exportCollectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    files.exportCollection(uri) { p ->
                        exportCollectionProgress = p
                    }
                    Toast.makeText(context, "Collection exported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Collection export failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    exportCollectionProgress = null
                }
            }
        }
    }

    val exportTeachingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    files.exportTeaching(uri)
                    Toast.makeText(context, "Teachings pack exported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Teachings export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Load initial settings and store data
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val f = store.folders()
            val e = store.exemplars()
            val errs = store.errors()
            val count = store.count()

            val vEnabled = store.setting("vision.enabled") == "1"
            val aEnabled = store.setting("audio.enabled") == "1"
            val visEnabled = store.setting("visual.enabled") == "1"
            val bgEnabled = store.setting("background.enabled") == "1"
            val bgCharge = store.setting("background.onlyCharging") == "1"
            // Unset means on for these two, as the coordinator reads them.
            val bgHot = store.setting("background.pauseHot") != "0"
            val bgBattery = store.setting("background.pauseLowBattery") != "0"

            withContext(Dispatchers.Main) {
                folders = f
                exemplars = e
                errorLogs = errs
                memeCount = count

                visionEnabled = vEnabled
                audioEnabled = aEnabled
                visualEnabled = visEnabled
                backgroundEnabled = bgEnabled
                backgroundOnlyCharging = bgCharge
                backgroundPauseHot = bgHot
                backgroundPauseLowBattery = bgBattery
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MemegetColors.Background),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MemegetColors.Surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("←", color = MemegetColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MemegetColors.TextPrimary,
            )
        }

        // Scrollable settings body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Standalone Native Install Banner
            SettingsCard {
                Text(
                    text = "Memeget Native Standalone",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.Volt,
                )
                Text(
                    text = "Runs in complete isolation. Existing React Native Memeget installations, database files, and app caches are untouched. Linked folders are read without modifying originals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )
            }

            // Local processing and optional network use
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Core Processing Stays on Device",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.Success,
                    )
                }
                Text(
                    text = "Core embedding, OCR, speech recognition, and search run on this device. Model downloads use the network. Optional cloud assistance sends only the content you explicitly allow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )
            }

            // On-Device Inference Engine Card
            SettingsCard {
                Text(
                    text = "Local Inference Engine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )
                Text(
                    text = "Primary Model: ${engine.primaryModel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )

                if (modelProgress.loading) {
                    val pct = (modelProgress.fraction * 100).toInt()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Loading model: ${modelProgress.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MemegetColors.Volt,
                            )
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MemegetColors.Volt,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { modelProgress.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MemegetColors.Volt,
                            trackColor = MemegetColors.SurfaceVariant,
                        )
                    }
                }

                if (modelProgress.error != null) {
                    Text(
                        text = "Model Error: ${modelProgress.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.Danger,
                    )
                }
            }

            // Local ML Features Opt-in Toggles
            SettingsCard {
                Text(
                    text = "On-Device ML Modules",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )

                ToggleRow(
                    title = "Local AI Captions (VLM)",
                    subtitle = "Describes memes on-device with MiniCPM-V 4.7 1B (about 1.8 GB download on first use).",
                    checked = visionEnabled,
                    onCheckedChange = { checked ->
                        visionEnabled = checked
                        scope.launch { store.setSetting("vision.enabled", if (checked) "1" else "0") }
                    },
                )

                ToggleRow(
                    title = "Audio Transcription (STT)",
                    subtitle = "Transcribes spoken words from video memes on-device using Whisper.",
                    checked = audioEnabled,
                    onCheckedChange = { checked ->
                        audioEnabled = checked
                        scope.launch { store.setSetting("audio.enabled", if (checked) "1" else "0") }
                    },
                )

                ToggleRow(
                    title = "Visual Appearance (DINO)",
                    subtitle = "Dense feature extraction for fine visual similarity search.",
                    checked = visualEnabled,
                    onCheckedChange = { checked ->
                        visualEnabled = checked
                        scope.launch { store.setSetting("visual.enabled", if (checked) "1" else "0") }
                    },
                )
            }

            // Background Throttling & Policy Toggles
            SettingsCard {
                Text(
                    text = "Background Processing & Throttling",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )

                ToggleRow(
                    title = "Enable Background Processing",
                    subtitle = "Indexes and enriches memes in the background when app is idle.",
                    checked = backgroundEnabled,
                    onCheckedChange = { checked ->
                        backgroundEnabled = checked
                        scope.launch { store.setSetting("background.enabled", if (checked) "1" else "0"); coordinator.schedule() }
                    },
                )

                AnimatedVisibility(visible = backgroundEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow(
                            title = "Only while charging",
                            subtitle = "Pause background indexing unless connected to power.",
                            checked = backgroundOnlyCharging,
                            onCheckedChange = { checked ->
                                backgroundOnlyCharging = checked
                                scope.launch { store.setSetting("background.onlyCharging", if (checked) "1" else "0"); coordinator.schedule() }
                            },
                        )

                        ToggleRow(
                            title = "Pause when device is warm",
                            subtitle = "Throttle model runs if thermal headroom is constricted.",
                            checked = backgroundPauseHot,
                            onCheckedChange = { checked ->
                                backgroundPauseHot = checked
                                scope.launch { store.setSetting("background.pauseHot", if (checked) "1" else "0"); coordinator.schedule() }
                            },
                        )

                        ToggleRow(
                            title = "Pause on low battery (<20%)",
                            subtitle = "Prevent battery drain when below 20%.",
                            checked = backgroundPauseLowBattery,
                            onCheckedChange = { checked ->
                                backgroundPauseLowBattery = checked
                                scope.launch { store.setSetting("background.pauseLowBattery", if (checked) "1" else "0"); coordinator.schedule() }
                            },
                        )
                    }
                }

                // Coordinator controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { coordinator.schedule() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Schedule", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                coordinator.runPending()
                                Toast.makeText(context, "Running pending queue", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Run Pending", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            // The toast comes first: retag() returns only when the pass has ended.
                            Toast.makeText(context, "Re-tagging started", Toast.LENGTH_SHORT).show()
                            scope.launch { coordinator.retag() }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Re-tag All", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Linked Folders Card
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Linked Folders (${folders.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.TextPrimary,
                    )
                    Button(
                        onClick = { linkFolderLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("＋ Link Folder", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                if (folders.isEmpty()) {
                    Text(
                        text = "No folders linked yet. Link a directory containing your images and videos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextMuted,
                    )
                } else {
                    folders.forEach { folder ->
                        FolderRow(
                            folder = folder,
                            onRescan = {
                                scope.launch {
                                    val added = files.scan(folder)
                                    Toast.makeText(context, "Scanned: found ${added.size} items", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBackup = {
                                scope.launch {
                                    try {
                                        files.backup(folder)
                                        Toast.makeText(context, "Backed up to .memeget-native", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onRestore = {
                                scope.launch {
                                    try {
                                        val restored = files.restore(folder)
                                        Toast.makeText(context, "Restored $restored items", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onUnlink = {
                                showUnlinkDialogForFolder = folder
                            },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MemegetColors.SurfaceVariant)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = "Restore info: Restores tags, captions, and exemplars from native and legacy .memeget sidecars. SAF permissions, private drafts, and settings are not transferred.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MemegetColors.TextMuted,
                        )
                    }
                }
            }

            // Data Import & Export Card
            SettingsCard {
                Text(
                    text = "Import & Export",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { importFileLauncher.launch(arrayOf("image/*", "video/*", "application/zip")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import Files/ZIP", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { showImportUrlDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import Link", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { importTeachingLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import Teachings", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { exportTeachingLauncher.launch("memeget-teachings.json") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export Teachings", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedButton(
                    onClick = { exportCollectionLauncher.launch("memeget-collection.zip") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export Full Collection ZIP", style = MaterialTheme.typography.labelSmall)
                }

                if (exportCollectionProgress != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MemegetColors.Volt,
                    )
                }
            }

            // Exemplars & Taught Concepts Card
            SettingsCard {
                Text(
                    text = "Taught Concepts (${exemplars.size} exemplars)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )

                val groupedExemplars = exemplars.groupBy { it.label }
                if (groupedExemplars.isEmpty()) {
                    Text(
                        text = "No concepts taught yet. Teach memes as positive or negative exemplars from the viewer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextMuted,
                    )
                } else {
                    groupedExemplars.entries.forEach { (label, items) ->
                        val posCount = items.count { it.positive }
                        val negCount = items.count { !it.positive }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MemegetColors.SurfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MemegetColors.TextPrimary,
                                )
                                Text(
                                    text = "$posCount positive · $negCount negative",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MemegetColors.TextMuted,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onSearchTag(label) }) {
                                    Text("View", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { showForgetLabelDialog = label }) {
                                    Text("Forget", style = MaterialTheme.typography.labelSmall, color = MemegetColors.Danger)
                                }
                            }
                        }
                    }
                }
            }

            // Optional Cloud Escalation
            SettingsCard {
                Text(
                    text = "Cloud Escalation (Optional)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.Blue,
                )
                Text(
                    text = "Off by default. Cloud escalation requires explicit opt-in and consent. Uploads are strictly budgeted and encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )

                // Call the contract-ratified self-contained CloudSettings composable
                CloudSettings(
                    assistant = cloud,
                    onError = { err ->
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    },
                )
            }

            // Diagnostics & Danger Zone Card
            SettingsCard {
                Text(
                    text = "Diagnostics & Maintenance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )

                Text(
                    text = "Indexed memes: $memeCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MemegetColors.TextSecondary,
                )

                if (errorLogs.isNotEmpty()) {
                    Text(
                        text = "Recent Indexing Errors (${errorLogs.size}):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.Danger,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MemegetColors.SurfaceVariant)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        errorLogs.take(5).forEach { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MemegetColors.Danger,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                store.clearErrors()
                                errorLogs = emptyList()
                                Toast.makeText(context, "Errors cleared", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.SurfaceBright,
                            contentColor = MemegetColors.TextPrimary,
                        ),
                    ) {
                        Text("Clear Error Log", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Button(
                    onClick = { showClearIndexDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MemegetColors.Danger,
                        contentColor = MemegetColors.TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear Index", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Import URL Dialog
    if (showImportUrlDialog) {
        ImportUrlDialog(
            onDismiss = { showImportUrlDialog = false },
            onConfirm = { url ->
                showImportUrlDialog = false
                scope.launch {
                    try {
                        val added = files.importLink(url)
                        Toast.makeText(context, "Imported ${added.size} items from link", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Link import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    // Unlink Folder Confirmation
    if (showUnlinkDialogForFolder != null) {
        val folder = showUnlinkDialogForFolder!!
        ConfirmationDialog(
            title = "Unlink “${folder.name}”?",
            message = "Unlinking removes the folder grant. Already indexed memes will remain searchable in your library.",
            confirmText = "Unlink",
            isDanger = true,
            onDismiss = { showUnlinkDialogForFolder = null },
            onConfirm = {
                showUnlinkDialogForFolder = null
                scope.launch {
                    store.removeFolder(folder.uri)
                    folders = store.folders()
                    Toast.makeText(context, "Unlinked ${folder.name}", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // Forget Label Confirmation
    if (showForgetLabelDialog != null) {
        val label = showForgetLabelDialog!!
        ConfirmationDialog(
            title = "Forget “$label”?",
            message = "Removes all taught positive and negative exemplars for this concept.",
            confirmText = "Forget Concept",
            isDanger = true,
            onDismiss = { showForgetLabelDialog = null },
            onConfirm = {
                showForgetLabelDialog = null
                scope.launch {
                    store.forgetLabel(label)
                    exemplars = store.exemplars()
                    Toast.makeText(context, "Forgot $label", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // Clear Index Confirmation
    if (showClearIndexDialog) {
        ConfirmationDialog(
            title = "Clear Search Index?",
            message = "Clears all indexed memes and vectors. Your linked folders, taught exemplars, and app settings are preserved. You can re-index anytime.",
            confirmText = "Clear Index",
            isDanger = true,
            onDismiss = { showClearIndexDialog = false },
            onConfirm = {
                showClearIndexDialog = false
                scope.launch {
                    store.clearIndex()
                    memeCount = store.count()
                    Toast.makeText(context, "Index cleared", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MemegetColors.Surface)
            .border(1.dp, MemegetColors.Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MemegetColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MemegetColors.TextSecondary,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = MemegetColors.Volt,
                uncheckedThumbColor = MemegetColors.TextMuted,
                uncheckedTrackColor = MemegetColors.SurfaceVariant,
            ),
        )
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    onRescan: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onUnlink: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MemegetColors.SurfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MemegetColors.TextPrimary,
            )
            Text(
                text = "Unlink",
                style = MaterialTheme.typography.labelSmall,
                color = MemegetColors.Danger,
                modifier = Modifier.clickable { onUnlink() },
            )
        }

        Text(
            text = folder.uri,
            style = MaterialTheme.typography.labelSmall,
            color = MemegetColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onRescan,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Rescan", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onBackup,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Backup", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Restore", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
