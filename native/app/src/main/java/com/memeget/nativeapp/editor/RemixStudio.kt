package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memeget.nativeapp.InferenceEngine
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.LibraryFiles
import expo.modules.memegetbg.MemeImageRenderer
import expo.modules.memegetbg.MemeMediaProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private enum class StudioExportDestination { LIBRARY, CLIPBOARD, DOWNLOADS }

internal sealed interface StudioLoadingState {
    object Probing : StudioLoadingState
    data class PromptRestore(val restored: MemeEditProject, val fresh: MemeEditProject) : StudioLoadingState
    object Ready : StudioLoadingState
    data class Error(val message: String) : StudioLoadingState
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun RemixStudio(
    meme: Meme,
    engine: InferenceEngine,
    files: LibraryFiles,
    onClose: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessionId = remember(meme.id, meme.uri) {
        "session_${meme.id}_${UUID.nameUUIDFromBytes(meme.uri.toByteArray(Charsets.UTF_8))}"
    }

    var loadingState by remember { mutableStateOf<StudioLoadingState>(StudioLoadingState.Probing) }
    var initialProject by remember { mutableStateOf<MemeEditProject?>(null) }
    var history by remember { mutableStateOf<ProjectHistory?>(null) }
    var sourceProbeResult by remember { mutableStateOf<MemeMediaProbe.Result?>(null) }


    // Clean expired editor assets older than 8 days
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EditorAssetStorage.cleanExpiredAssets(context)
        }
    }
    // Probe media and check draft journal on enter
    LaunchedEffect(meme.uri) {
        val probe = withContext(Dispatchers.IO) {
            try {
                MemeMediaProbe.probe(context, meme.uri)
            } catch (_: Exception) {
                null
            }
        }
        sourceProbeResult = probe

        val kind = if (meme.kind == "video" || probe?.kind == "video") MediaEditKind.VIDEO else MediaEditKind.IMAGE
        val isRotated = probe?.rotationDegrees == 90 || probe?.rotationDegrees == 270
        val w = if (isRotated) (probe?.height ?: 1080) else (probe?.width ?: 1080)
        val h = if (isRotated) (probe?.width ?: 1080) else (probe?.height ?: 1080)
        val duration = probe?.durationUs ?: (if (kind == MediaEditKind.VIDEO) 5_000_000L else null)

        val src = MemeEditSource(
            uri = meme.uri,
            name = meme.name,
            kind = kind,
            width = w,
            height = h,
            durationUs = duration
        )

        val defaultProj = if (kind == MediaEditKind.VIDEO) {
            ProjectCore.createDefaultVideoProject(src)
        } else {
            ProjectCore.createDefaultImageProject(src)
        }
        initialProject = defaultProj

        // Check for existing draft using full source identity
        val expectedIdentity = DraftJournalStore.SourceIdentity(
            uri = meme.uri,
            name = meme.name,
            kind = kind.wire,
            width = w,
            height = h,
            durationUs = duration,
            byteSize = probe?.byteSize,
            modifiedTimeMs = probe?.modifiedTimeMs
        )

        val draftResult = DraftJournalStore.restoreDraft(context, sessionId, expectedIdentity)
        if (draftResult.restored && draftResult.project != null) {
            loadingState = StudioLoadingState.PromptRestore(draftResult.project, defaultProj)
        } else {
            history = createProjectHistory(defaultProj)
            loadingState = StudioLoadingState.Ready
        }
    }

    // Debounced autosave (only when in Ready state to never overwrite pending drafts)
    val currentHistory = history
    LaunchedEffect(currentHistory?.present, loadingState) {
        if (loadingState !is StudioLoadingState.Ready) return@LaunchedEffect
        val proj = currentHistory?.present ?: return@LaunchedEffect
        delay(500)
        val probe = sourceProbeResult
        DraftJournalStore.saveDraft(
            context = context,
            sessionId = sessionId,
            project = proj,
            sourceByteSize = probe?.byteSize,
            sourceModifiedTimeMs = probe?.modifiedTimeMs
        )
    }

    // Restore draft prompt dialog
    val promptState = loadingState as? StudioLoadingState.PromptRestore
    if (promptState != null) {
        AlertDialog(
            onDismissRequest = {
                history = createProjectHistory(promptState.fresh)
                loadingState = StudioLoadingState.Ready
            },
            title = { Text("Restore previous edits?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("A previous unsaved editing session for this meme was recovered.", color = Color(0xFF8B949E)) },
            confirmButton = {
                Button(
                    onClick = {
                        history = createProjectHistory(promptState.restored)
                        loadingState = StudioLoadingState.Ready
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
                ) {
                    Text("Restore", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    history = createProjectHistory(promptState.fresh)
                    loadingState = StudioLoadingState.Ready
                }) {
                    Text("Start Fresh", color = Color.White)
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    if (loadingState is StudioLoadingState.Probing || history == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B0E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFFD8FF4A))
        }
        return
    }

    val activeProject = currentHistory!!.present

    // UI state
    var activeTool by remember {
        mutableStateOf(if (activeProject.source.kind == MediaEditKind.VIDEO) "timeline" else "text")
    }
    var selectedLayerId by remember { mutableStateOf<String?>(null) }
    var beforeMode by remember { mutableStateOf(false) }
    var playheadUs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var drawSettings by remember { mutableStateOf(DrawSettings()) }
    var drawOpacity by remember { mutableFloatStateOf(1f) }
    var manualOcrMode by remember { mutableStateOf(false) }
    var manualOcrRegion by remember {
        mutableStateOf(NormalizedRect(0.2f, 0.2f, 0.6f, 0.15f))
    }

    // Export dialog & state
    var showExportDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportStage by remember { mutableStateOf("exporting") }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    val stickerPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val copiedUri = withContext(Dispatchers.IO) {
                        EditorAssetStorage.copyAssetToEditorStorage(context, uri)
                    }
                    val layer = MemeEditLayer.Media(
                        id = "media_${System.currentTimeMillis()}",
                        assetUri = copiedUri.toString(),
                        keyframes = listOf(TransformKeyframe(center = NormalizedPoint(0.5f, 0.5f), scale = 1f))
                    )
                    history = applyProjectAction(currentHistory, ProjectAction.AddLayer(layer))
                    selectedLayerId = layer.id
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to load sticker: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun exportTo(destination: StudioExportDestination) {
        if (isExporting) return
        isExporting = true
        exportProgress = 0f
        exportStage = "rendering"
        exportJob = scope.launch {
            var renderedFile: File? = null
            try {
                val output = renderOutputMedia(context, activeProject) { progress ->
                    exportProgress = progress.fraction
                    exportStage = progress.stage
                }
                renderedFile = output
                val uri = Uri.fromFile(output).toString()
                val name = "Remix_${meme.name.substringBeforeLast('.')}.${output.extension}"
                val isVideo = activeProject.source.kind == MediaEditKind.VIDEO
                when (destination) {
                    StudioExportDestination.LIBRARY -> {
                        val savedId = files.saveNew(uri, name, if (isVideo) "video" else "image")
                        DraftJournalStore.discardDraft(context, sessionId)
                        showExportDialog = false
                        onSaved(savedId)
                    }
                    StudioExportDestination.CLIPBOARD -> {
                        files.copyToClipboard(uri, name)
                        showExportDialog = false
                        val message = if (isVideo) "Video copied — paste in apps that accept videos"
                            else "Meme copied — paste it in another app"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    StudioExportDestination.DOWNLOADS -> {
                        files.exportToDownloads(uri, name, if (isVideo) "video/mp4" else "image/png")
                        DraftJournalStore.discardDraft(context, sessionId)
                        showExportDialog = false
                        Toast.makeText(context, "Exported to Downloads", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, "Export failed: ${failure.message}", Toast.LENGTH_LONG).show()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { renderedFile?.delete() }
                isExporting = false
            }
        }
    }

    // Intercept back button
    BackHandler {
        if (isExporting) {
            exportJob?.cancel()
            showExportDialog = false
            return@BackHandler
        }
        if (currentHistory.past.isNotEmpty()) {
            showDiscardConfirmDialog = true
        } else {
            scope.launch {
                currentHistory.present.let { DraftJournalStore.saveDraft(context, sessionId, it) }
                withContext(Dispatchers.Main) { onClose() }
            }
        }
    }

    // Discard confirm dialog
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = { Text("Exit Editor", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("You have unsaved edits. Discard changes or keep draft for next time?", color = Color(0xFF8B949E)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmDialog = false
                        scope.launch {
                            currentHistory.present.let { DraftJournalStore.saveDraft(context, sessionId, it) }
                            withContext(Dispatchers.Main) { onClose() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
                ) {
                    Text("Keep Draft & Exit", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardConfirmDialog = false
                    scope.launch {
                        DraftJournalStore.discardDraft(context, sessionId)
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }) {
                    Text("Discard", color = Color(0xFFFF5252))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }


    // Export Sheet / Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            title = {
                Text(
                    if (isExporting) "Rendering your remix" else "Export Meme",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            progress = { exportProgress },
                            color = Color(0xFFD8FF4A),
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                        )
                        Text(
                            "$exportStage (${(exportProgress * 100).roundToInt()}%)",
                            color = Color(0xFF8B949E),
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    } else {
                        Text("Choose how you want to export this meme:", color = Color(0xFF8B949E), fontSize = 13.sp)

                        Button(
                            onClick = { exportTo(StudioExportDestination.LIBRARY) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A))
                        ) {
                            Text("Save to Library", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { exportTo(StudioExportDestination.CLIPBOARD) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Copy to Clipboard")
                        }

                        OutlinedButton(
                            onClick = { exportTo(StudioExportDestination.DOWNLOADS) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Save to Downloads")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    if (isExporting) {
                        exportJob?.cancel()
                    }
                    showExportDialog = false
                }) {
                    Text(if (isExporting) "Cancel Export" else "Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // Main Studio Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .safeDrawingPadding()
    ) {
        // --- 1. Top Header Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                if (currentHistory.past.isNotEmpty()) showDiscardConfirmDialog = true
                else {
                    scope.launch {
                        currentHistory.present.let { DraftJournalStore.saveDraft(context, sessionId, it) }
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }
            }) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Before / After Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (beforeMode) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { beforeMode = !beforeMode }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (beforeMode) "ORIGINAL" else "BEFORE",
                        color = if (beforeMode) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Undo
                IconButton(
                    onClick = { history = undoProjectHistory(currentHistory) },
                    enabled = canUndo(currentHistory)
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo(currentHistory)) Color.White else Color(0xFF484F58)
                    )
                }

                // Redo
                IconButton(
                    onClick = { history = redoProjectHistory(currentHistory) },
                    enabled = canRedo(currentHistory)
                ) {
                    Icon(
                        Icons.Default.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo(currentHistory)) Color.White else Color(0xFF484F58)
                    )
                }
            }

            // Export / Save Button
            Button(
                onClick = { showExportDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8FF4A)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Export", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // --- 2. Center Canvas Area ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            StudioCanvas(
                project = activeProject,
                playheadUs = playheadUs,
                selectedLayerId = selectedLayerId,
                activeTool = activeTool,
                beforeMode = beforeMode,
                drawSettings = drawSettings,
                manualCrop = activeTool == "transform",
                manualOcrRegion = if (manualOcrMode) manualOcrRegion else null,
                isPlaying = isPlaying,
                onTogglePlayback = { isPlaying = !isPlaying },
                onSeekPlayhead = { playheadUs = it },
                onBeginTransaction = { history = beginProjectTransaction(checkNotNull(history)) },
                onCommitTransaction = { history = commitProjectTransaction(checkNotNull(history)) },
                onCancelTransaction = { history = cancelProjectTransaction(checkNotNull(history)) },
                onUpdateLayer = { updated ->
                    history = applyProjectAction(checkNotNull(history), ProjectAction.UpdateLayer(updated))
                },
                onSelectLayer = { selectedLayerId = it },
                onUpdateBase = { updatedBase ->
                    history = applyProjectAction(checkNotNull(history), ProjectAction.SetBaseTransform(updatedBase))
                },
                onStartDrawStroke = { startPt ->
                    val liveHistory = checkNotNull(history)
                    val drawLayer = liveHistory.present.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                        ?: MemeEditLayer.Draw(
                            id = "draw_${System.currentTimeMillis()}",
                            elements = emptyList(),
                            opacity = drawOpacity
                        )
                    val hasLayer = liveHistory.present.layers.any { it.id == drawLayer.id }
                    val newElem = DrawElement(
                        shape = drawSettings.shape,
                        color = drawSettings.color,
                        strokeScale = drawSettings.strokeScale,
                        points = listOf(startPt),
                        filled = drawSettings.filled
                    )
                    val updatedLayer = drawLayer.copy(
                        elements = drawLayer.elements + newElem,
                        opacity = drawOpacity
                    )
                    history = if (hasLayer) {
                        applyProjectAction(liveHistory, ProjectAction.UpdateLayer(updatedLayer))
                    } else {
                        applyProjectAction(liveHistory, ProjectAction.AddLayer(updatedLayer))
                    }
                },
                onUpdateDrawStroke = { currentPt ->
                    val liveHistory = checkNotNull(history)
                    val drawLayer = liveHistory.present.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                    if (drawLayer != null && drawLayer.elements.isNotEmpty()) {
                        val lastElem = drawLayer.elements.last()
                        val updatedElem = if (drawSettings.shape == DrawShape.FREE) {
                            val newPts = DrawToolCore.appendFreehandPoint(lastElem.points, currentPt)
                            lastElem.copy(points = newPts)
                        } else {
                            val startPt = lastElem.points.firstOrNull() ?: currentPt
                            lastElem.copy(points = listOf(startPt, currentPt))
                        }
                        val updatedElements = drawLayer.elements.dropLast(1) + updatedElem
                        history = applyProjectAction(liveHistory, ProjectAction.UpdateLayer(drawLayer.copy(elements = updatedElements)))
                    }
                },
                onAppendDrawPoint = { pt ->
                    val liveHistory = checkNotNull(history)
                    val drawLayer = liveHistory.present.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                    if (drawLayer != null && drawLayer.elements.isNotEmpty()) {
                        val lastElem = drawLayer.elements.last()
                        val newPts = DrawToolCore.appendFreehandPoint(lastElem.points, pt)
                        val updatedElements = drawLayer.elements.dropLast(1) + lastElem.copy(points = newPts)
                        history = applyProjectAction(liveHistory, ProjectAction.UpdateLayer(drawLayer.copy(elements = updatedElements)))
                    }
                },
                onUpdateManualOcrRegion = { manualOcrRegion = it }
            )
        }

        // --- 3. Active Tool Panel / Drawer ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            color = Color(0xFF161B22),
            tonalElevation = 4.dp
        ) {
            when (activeTool) {
                "text" -> {
                    val sel = activeProject.layers.find { it.id == selectedLayerId } as? MemeEditLayer.Text
                    TextInspectorPanel(
                        selectedLayer = sel,
                        onUpdateLayer = { updated ->
                            history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(updated))
                        },
                        onAddTextLayer = {
                            val nextId = "text_${System.currentTimeMillis()}"
                            val newLayer = MemeEditLayer.Text(
                                id = nextId,
                                text = "TAP TO EDIT",
                                width = 0.88f,
                                fontSize = 0.118f,
                                style = TextStyle(preset = "impact")
                            )
                            history = applyProjectAction(currentHistory, ProjectAction.AddLayer(newLayer))
                            selectedLayerId = nextId
                        },
                        onDuplicateLayer = {
                            sel?.let {
                                val dupId = "${it.id}_dup_${System.currentTimeMillis()}"
                                history = applyProjectAction(currentHistory, ProjectAction.DuplicateLayer(it.id, dupId))
                                selectedLayerId = dupId
                            }
                        },
                        onDeleteLayer = {
                            sel?.let {
                                history = applyProjectAction(currentHistory, ProjectAction.RemoveLayer(it.id))
                                selectedLayerId = null
                            }
                        }
                    )
                }
                "transform" -> {
                    TransformInspectorPanel(
                        base = activeProject.base,
                        sourceWidth = activeProject.source.width,
                        sourceHeight = activeProject.source.height,
                        onUpdateBase = { newBase ->
                            history = applyProjectAction(currentHistory, ProjectAction.SetBaseTransform(newBase))
                        }
                    )
                }
                "draw" -> {
                    DrawToolPanel(
                        settings = drawSettings,
                        opacity = drawOpacity,
                        onChangeSettings = { drawSettings = it },
                        onChangeOpacity = { newOp ->
                            drawOpacity = newOp
                            val drawLayer = activeProject.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                            if (drawLayer != null) {
                                history = applyProjectAction(currentHistory, ProjectAction.SetDrawOpacity(drawLayer.id, newOp))
                            }
                        },
                        onUndoLast = {
                            val drawLayer = activeProject.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                            if (drawLayer != null && drawLayer.elements.isNotEmpty()) {
                                val dropped = drawLayer.elements.dropLast(1)
                                history = applyProjectAction(currentHistory, ProjectAction.SetDrawElements(drawLayer.id, dropped))
                            }
                        },
                        onClearAll = {
                            val drawLayer = activeProject.layers.find { it is MemeEditLayer.Draw } as? MemeEditLayer.Draw
                            if (drawLayer != null) {
                                history = applyProjectAction(currentHistory, ProjectAction.SetDrawElements(drawLayer.id, emptyList()))
                            }
                        }
                    )
                }
                "replace-text" -> {
                    TextReplaceToolPanel(
                        sourceUri = activeProject.materializedSourceUri ?: activeProject.source.uri,
                        engine = engine,
                        base = activeProject.base,
                        manualMode = manualOcrMode,
                        manualRegion = manualOcrRegion,
                        onToggleManualMode = { manualOcrMode = it },
                        onUpdateManualRegion = { manualOcrRegion = it },
                        onApplyCover = { rect, color ->
                            val layer = MemeEditLayer.Cover(
                                id = "cover_${System.currentTimeMillis()}",
                                rect = rect,
                                mode = "solid",
                                color = color
                            )
                            history = applyProjectAction(currentHistory, ProjectAction.AddLayer(layer))
                            Toast.makeText(context, "Cover applied", Toast.LENGTH_SHORT).show()
                        },
                        onApplyPixelate = { rect, pxSize ->
                            val layer = MemeEditLayer.Cover(
                                id = "pixelate_${System.currentTimeMillis()}",
                                rect = rect,
                                mode = "pixelate",
                                pixelSize = pxSize
                            )
                            history = applyProjectAction(currentHistory, ProjectAction.AddLayer(layer))
                            Toast.makeText(context, "Pixelation applied", Toast.LENGTH_SHORT).show()
                        },
                        onApplyReplace = { rect, coverCol, repText, inferred ->
                            val cover = MemeEditLayer.Cover(
                                id = "cover_${System.currentTimeMillis()}",
                                rect = rect,
                                mode = "solid",
                                color = coverCol
                            )
                            val textStyle = inferred?.let {
                                TextStyle(
                                    preset = it.preset,
                                    color = it.color,
                                    outlineColor = it.outlineColor,
                                    outlineScale = it.outlineScale,
                                    uppercase = it.uppercase
                                )
                            } ?: TextStyle(preset = "impact")

                            val textLayer = MemeEditLayer.Text(
                                id = "text_${System.currentTimeMillis()}",
                                text = repText,
                                width = rect.width.coerceIn(ProjectLimits.MIN_TEXT_WRAP_WIDTH, ProjectLimits.MAX_TEXT_WRAP_WIDTH),
                                fontSize = inferred?.fontSize ?: 0.08f,
                                style = textStyle,
                                keyframes = listOf(TransformKeyframe(
                                    center = NormalizedPoint(rect.x + rect.width / 2f, rect.y + rect.height / 2f)
                                ))
                            )
                            history = applyProjectAction(currentHistory, ProjectAction.AddLayers(listOf(cover, textLayer)))
                            selectedLayerId = textLayer.id
                            Toast.makeText(context, "Text replaced", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                "subject" -> {
                    SubjectCutoutToolPanel(
                        sourceUri = activeProject.materializedSourceUri ?: activeProject.source.uri,
                        engine = engine,
                        backgroundSpec = activeProject.background,
                        onUpdateBackground = { newBg ->
                            history = applyProjectAction(currentHistory, ProjectAction.SetBackground(newBg))
                        },
                        onAddCutoutLayer = addCutout@ { result, subjectIdx, outline, shadow, duplicate ->
                            val cutout = if (subjectIdx != null && subjectIdx in result.subjects.indices) {
                                result.subjects[subjectIdx]
                            } else {
                                result.combined
                            }
                            if (cutout == null || cutout.cutoutUri.isBlank() || cutout.bounds.width <= 0 || cutout.bounds.height <= 0) {
                                Toast.makeText(context, "No subject cutout was produced", Toast.LENGTH_LONG).show()
                                return@addCutout
                            }
                            val sourceCenter = NormalizedPoint(
                                (cutout.bounds.x + cutout.bounds.width / 2).toFloat(),
                                (cutout.bounds.y + cutout.bounds.height / 2).toFloat()
                            )
                            val center = ProjectCore.sourceToWorking(sourceCenter, activeProject.base)
                            val (displayW, displayH) = ProjectCore.visibleImageDimensions(
                                activeProject.source.width, activeProject.source.height, activeProject.base
                            )
                            val bounds = NormalizedRect(
                                0f, 0f,
                                cutout.bounds.width.toFloat() * activeProject.source.width / displayW,
                                cutout.bounds.height.toFloat() * activeProject.source.height / displayH
                            )
                            val mainLayer = MemeEditLayer.Subject(
                                id = "subject_${System.currentTimeMillis()}",
                                bounds = bounds,
                                subjectIndex = subjectIdx,
                                maskTrackId = result.requestId,
                                cutoutUri = cutout.cutoutUri,
                                keyframes = listOf(TransformKeyframe(
                                    center = center,
                                    rotationDegrees = activeProject.base.rotation.degrees.toFloat()
                                )),
                                outlineColor = "#FFFFFF",
                                outlineScale = outline,
                                shadowScale = shadow
                            )
                            if (duplicate) {
                                val duplicateLayer = MemeEditLayer.Subject(
                                    id = "subject_dup_${System.currentTimeMillis()}",
                                    bounds = bounds,
                                    subjectIndex = subjectIdx,
                                    maskTrackId = result.requestId,
                                    cutoutUri = cutout.cutoutUri,
                                    keyframes = listOf(TransformKeyframe(
                                        center = ProjectCore.clampNormalizedPoint(NormalizedPoint(center.x + 0.04f, center.y + 0.04f)),
                                        rotationDegrees = activeProject.base.rotation.degrees.toFloat()
                                    )),
                                    outlineColor = "#FFFFFF",
                                    outlineScale = outline,
                                    shadowScale = 0f
                                )
                                val withDup = applyProjectAction(currentHistory, ProjectAction.AddLayer(duplicateLayer))
                                history = applyProjectAction(withDup, ProjectAction.AddLayer(mainLayer))
                            } else {
                                history = applyProjectAction(currentHistory, ProjectAction.AddLayer(mainLayer))
                            }
                            selectedLayerId = mainLayer.id
                            Toast.makeText(context, "Cutout added as layer", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                "layers" -> {
                    LayersListPanel(
                        layers = activeProject.layers,
                        selectedLayerId = selectedLayerId,
                        onSelectLayer = { selectedLayerId = it },
                        onMoveLayer = { layerId, toIndex ->
                            history = applyProjectAction(currentHistory, ProjectAction.MoveLayer(layerId, toIndex))
                        },
                        onDuplicateLayer = { layerId ->
                            val dupId = "${layerId}_dup_${System.currentTimeMillis()}"
                            history = applyProjectAction(currentHistory, ProjectAction.DuplicateLayer(layerId, dupId))
                            selectedLayerId = dupId
                        },
                        onDeleteLayer = { layerId ->
                            history = applyProjectAction(currentHistory, ProjectAction.RemoveLayer(layerId))
                            if (selectedLayerId == layerId) selectedLayerId = null
                        }
                    )
                }
                "timeline" -> {
                    VideoTimelinePanel(
                        project = activeProject,
                        playheadUs = playheadUs,
                        onScrubPlayhead = { playheadUs = it },
                        onSplitAtPlayhead = {
                            val duration = activeProject.source.durationUs ?: 1_000_000L
                            val ranges = activeProject.video?.retainedRanges ?: listOf(TimeRangeUs(0L, duration))
                            val nextRanges = mutableListOf<TimeRangeUs>()
                            for (r in ranges) {
                                if (playheadUs > r.startUs + 50_000L && playheadUs < r.endUs - 50_000L) {
                                    nextRanges.add(TimeRangeUs(r.startUs, playheadUs))
                                    nextRanges.add(TimeRangeUs(playheadUs, r.endUs))
                                } else {
                                    nextRanges.add(r)
                                }
                            }
                            history = applyProjectAction(currentHistory, ProjectAction.SetVideoRanges(nextRanges))
                        },
                        onTrimRange = { idx, start, end ->
                            val ranges = activeProject.video?.retainedRanges?.toMutableList() ?: mutableListOf()
                            if (idx in ranges.indices) {
                                ranges[idx] = TimeRangeUs(start, end)
                                history = applyProjectAction(currentHistory, ProjectAction.SetVideoRanges(ranges))
                            }
                        },
                        onRemoveSegment = { idx ->
                            val ranges = activeProject.video?.retainedRanges?.toMutableList() ?: mutableListOf()
                            if (idx in ranges.indices && ranges.size > 1) {
                                ranges.removeAt(idx)
                                history = applyProjectAction(currentHistory, ProjectAction.SetVideoRanges(ranges))
                            }
                        },
                        onAddCardWithContent = { text, bgColor, durUs ->
                            val cardFile = File(context.cacheDir, "card_${System.currentTimeMillis()}.png")
                            val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            val bgPaint = android.graphics.Paint().apply {
                                color = try { android.graphics.Color.parseColor(bgColor) } catch (_: Exception) { android.graphics.Color.BLACK }
                                style = android.graphics.Paint.Style.FILL
                            }
                            canvas.drawRect(0f, 0f, 800f, 600f, bgPaint)
                            val textPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                textSize = 52f
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            canvas.drawText(text, 400f, 320f, textPaint)
                            FileOutputStream(cardFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bmp.recycle()
                            val card = InsertedCard(
                                uri = cardFile.toURI().toString(),
                                atUs = playheadUs,
                                durationUs = durUs
                            )
                            history = applyProjectAction(currentHistory, ProjectAction.AddInsertedCard(card))
                            Toast.makeText(context, "Title card added", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                "frames" -> {
                    VideoFrameStripPanel(
                        playheadUs = playheadUs,
                        durationUs = activeProject.source.durationUs ?: 1_000_000L,
                        sourceUri = activeProject.materializedSourceUri ?: activeProject.source.uri,
                        sourceFrameRate = sourceProbeResult?.frameRate,
                        onSeekFrame = { playheadUs = it }
                    )
                }
                "motion" -> {
                    val sel = activeProject.layers.find { it.id == selectedLayerId }
                    VideoMotionToolPanel(
                        selectedLayer = sel,
                        playheadUs = playheadUs,
                        durationUs = activeProject.source.durationUs ?: 1_000_000L,
                        onAddKeyframe = {
                            sel?.let {
                                val updated = ProjectCore.upsertKeyframeAtPlayhead(it, playheadUs)
                                history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(updated))
                            }
                        },
                        onRemoveKeyframe = {
                            if (sel is MemeEditLayer.Text && sel.keyframes.size > 1) {
                                val filtered = sel.keyframes.filter { it.timeUs != playheadUs }
                                history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(sel.copy(keyframes = filtered)))
                            } else if (sel is MemeEditLayer.Subject && sel.keyframes.size > 1) {
                                val filtered = sel.keyframes.filter { it.timeUs != playheadUs }
                                history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(sel.copy(keyframes = filtered)))
                            } else if (sel is MemeEditLayer.Media && sel.keyframes.size > 1) {
                                val filtered = sel.keyframes.filter { it.timeUs != playheadUs }
                                history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(sel.copy(keyframes = filtered)))
                            }
                        },
                        onUpdateLayer = { updated ->
                            history = applyProjectAction(currentHistory, ProjectAction.UpdateLayer(updated))
                        },
                        onSeekPlayhead = { playheadUs = it }
                    )
                }
                "audio" -> {
                    val videoSpec = activeProject.video ?: VideoEditSpec()
                    VideoAudioToolPanel(
                        audioSpec = videoSpec.audio,
                        speed = videoSpec.speed,
                        musicSpec = videoSpec.music,
                        onUpdateAudio = { newAudio ->
                            history = applyProjectAction(currentHistory, ProjectAction.SetVideoAudio(newAudio.muted, newAudio.volume))
                        },
                        onUpdateSpeed = { newSpeed ->
                            history = applyProjectAction(currentHistory, ProjectAction.SetVideoSpeed(newSpeed))
                        },
                        onUpdateMusic = { newMusic ->
                            history = applyProjectAction(currentHistory, ProjectAction.SetVideoMusic(newMusic))
                        }
                    )
                }
            }
        }

        // --- 4. Bottom Tool Rail ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0E1116))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tools = if (activeProject.source.kind == MediaEditKind.VIDEO) {
                listOf(
                    Pair("timeline", "⏱ Timeline"),
                    Pair("frames", "⊞ Frames"),
                    Pair("text", "A Text"),
                    Pair("draw", "✎ Draw"),
                    Pair("motion", "➚ Motion"),
                    Pair("audio", "♪ Audio"),
                    Pair("layers", "▤ Layers")
                )
            } else {
                listOf(
                    Pair("text", "A Text"),
                    Pair("draw", "✎ Draw"),
                    Pair("transform", "⌗ Transform"),
                    Pair("replace-text", "▨ Replace"),
                    Pair("subject", "✂ Cut out"),
                    Pair("layers", "▤ Layers")
                )
            }

            for ((toolId, label) in tools) {
                val selected = activeTool == toolId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFFD8FF4A) else Color(0xFF21262D))
                        .clickable { activeTool = toolId }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Sticker import button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF21262D))
                    .clickable { stickerPickerLauncher.launch("image/*") }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "★ Sticker",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private suspend fun renderOutputMedia(
    context: Context,
    project: MemeEditProject,
    onProgress: (NativeVideoComposition.ExportProgress) -> Unit
): File = withContext(Dispatchers.IO) {
    if (project.source.kind == MediaEditKind.VIDEO) {
        val outcome = NativeVideoComposition.exportVideo(
            context = context,
            project = project,
            onProgress = onProgress
        )
        outcome.file
    } else {
        val plan = RenderPlanBuilder.buildImageRenderPlan(project)
        val renderedUri = MemeImageRenderer.render(context, plan.toString())
        File(java.net.URI(renderedUri))
    }
}
