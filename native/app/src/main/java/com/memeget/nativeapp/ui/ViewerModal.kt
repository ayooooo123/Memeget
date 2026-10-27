package com.memeget.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.knowledge.Titles
import com.memeget.nativeapp.storage.MediaIoUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ViewerModal(
    meme: Meme,
    files: LibraryFiles,
    store: LibraryStore,
    cloud: CloudAssistant,
    coordinator: IndexCoordinator,
    onClose: () -> Unit,
    onSearchTag: (String) -> Unit,
    onNavigateToMeme: (Meme) -> Unit,
    onMemeDeleted: (Long) -> Unit,
    onTaught: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by store.revision.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showTeachDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showConfidenceDialog by remember { mutableStateOf(false) }
    var verdictTag by remember { mutableStateOf<Tag?>(null) }

    var alsoTheseCandidates by remember { mutableStateOf<List<Meme>>(emptyList()) }
    var alsoTheseLabel by remember { mutableStateOf("") }
    var alsoTheseCategory by remember { mutableStateOf("") }
    var alsoTheseSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showAlsoTheseDialog by remember { mutableStateOf(false) }

    var confidenceState by remember { mutableStateOf<ConfidenceState>(ConfidenceState.Loading) }

    var similarHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var loadingSimilar by remember { mutableStateOf(false) }
    var currentMeme by remember(meme.id) { mutableStateOf(meme) }
    var copying by remember(meme.id) { mutableStateOf(false) }

    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    fun checkAndOpenAlsoThese(label: String, category: String) {
        scope.launch {
            try {
                val normLabel = TagMerge.normalizeTagLabel(label)
                val hits = withContext(Dispatchers.IO) {
                    store.similar(currentMeme.id, limit = 24)
                }
                val candidates = hits.filter { hit ->
                    hit.score >= 0.6f &&
                        hit.meme.id != currentMeme.id &&
                        hit.meme.tags.none { TagMerge.normalizeTagLabel(it.label) == normLabel } &&
                        hit.meme.rejectedLabels.none { TagMerge.normalizeTagLabel(it) == normLabel }
                }.map { it.meme }

                if (candidates.isNotEmpty()) {
                    alsoTheseCandidates = candidates
                    alsoTheseLabel = label
                    alsoTheseCategory = category
                    alsoTheseSelected = emptySet()
                    showAlsoTheseDialog = true
                }
            } catch (_: Exception) {
                // Best effort candidate retrieval
            }
        }
    }

    // Live refresh: reload meme when store.revision changes (or currentMeme.id changes)
    LaunchedEffect(currentMeme.id, revision) {
        val fresh = withContext(Dispatchers.IO) {
            store.meme(currentMeme.id)
        }
        if (fresh != null) {
            currentMeme = fresh
        }
    }

    // Keep loading of the similar-memes row keyed on the meme id only
    LaunchedEffect(currentMeme.id) {
        loadingSimilar = true
        val similar = withContext(Dispatchers.IO) {
            store.similar(currentMeme.id, limit = 24)
        }
        similarHits = similar
        loadingSimilar = false
    }

    // Compute taught-label confidence scores when dialog is shown
    LaunchedEffect(showConfidenceDialog, currentMeme.id) {
        if (!showConfidenceDialog) return@LaunchedEffect
        confidenceState = ConfidenceState.Loading
        val (vectorRecord, exemplars) = withContext(Dispatchers.IO) {
            val v = store.vectors(currentMeme.id)
            val e = store.exemplars()
            Pair(v, e)
        }
        if (vectorRecord == null || vectorRecord.image.isEmpty()) {
            confidenceState = ConfidenceState.NoVector
            return@LaunchedEffect
        }
        val entries = withContext(Dispatchers.Default) {
            val memeImage = vectorRecord.image
            val memeModel = vectorRecord.model
            val matchingExemplars = exemplars.filter {
                it.model == memeModel && it.vector.size == memeImage.size
            }
            if (matchingExemplars.isEmpty()) {
                return@withContext emptyList<ConfidenceEntry>()
            }
            val byLabel = matchingExemplars.groupBy { it.label }
            byLabel.map { (label, exs) ->
                var bestPos: Float? = null
                var bestNeg: Float? = null
                for (ex in exs) {
                    val score = dot(memeImage, ex.vector)
                    if (ex.positive) {
                        if (bestPos == null || score > bestPos) bestPos = score
                    } else {
                        if (bestNeg == null || score > bestNeg) bestNeg = score
                    }
                }
                ConfidenceEntry(label = label, bestPositive = bestPos, bestNegative = bestNeg)
            }
                .sortedByDescending { it.bestPositive ?: Float.NEGATIVE_INFINITY }
                .take(15)
        }
        confidenceState = if (entries.isEmpty()) {
            ConfidenceState.NoTeachings
        } else {
            ConfidenceState.Loaded(entries)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MemegetColors.Background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header / Chrome
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MemegetColors.Surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Text(
                            text = "✕",
                            color = MemegetColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = currentMeme.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MemegetColors.TextPrimary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MemegetColors.SurfaceBright)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = currentMeme.kind.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MemegetColors.Volt,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (currentMeme.legacyImported) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Legacy Imported",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MemegetColors.TextMuted,
                                )
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Remix Studio bloat cut — Viewer stays fast: Share, Not-a-Meme Quick-Fix, Delete, More.
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    files.share(context, listOf(currentMeme))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not share: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) {
                            Text("↗", color = MemegetColors.TextPrimary, fontSize = 18.sp)
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Text("🗑", color = MemegetColors.Danger, fontSize = 16.sp)
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Text("⋯", color = MemegetColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                modifier = Modifier.background(MemegetColors.Surface),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Re-run the caption", color = MemegetColors.TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            try {
                                                val enabled = store.setting("vision.enabled") == "1"
                                                if (!enabled) {
                                                    Toast.makeText(context, "Turn on Local AI Captions in Settings first", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    // A session already running keeps the lock, so this start may be
                                                    // dropped; the meme then waits for the next session.
                                                    val busy = coordinator.progress.value.running
                                                    store.setVisionState(currentMeme.id, "pending")
                                                    coordinator.start(emptyList())
                                                    Toast.makeText(context, if (busy) "Caption queued for the next indexing session" else "Caption queued", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not queue caption: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                )
                                if (currentMeme.kind == "video") {
                                    DropdownMenuItem(
                                        text = { Text("Re-run the transcription", color = MemegetColors.TextPrimary) },
                                        onClick = {
                                            showMoreMenu = false
                                            scope.launch {
                                                try {
                                                    val enabled = store.setting("audio.enabled") == "1"
                                                    if (!enabled) {
                                                        Toast.makeText(context, "Turn on Audio Transcription in Settings first", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val busy = coordinator.progress.value.running
                                                        store.setAudioState(currentMeme.id, "pending")
                                                        coordinator.start(emptyList())
                                                        Toast.makeText(context, if (busy) "Transcription queued for the next indexing session" else "Transcription queued", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not queue transcription: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Regenerate the thumbnail", color = MemegetColors.TextPrimary) },
                                        onClick = {
                                            showMoreMenu = false
                                            scope.launch {
                                                try {
                                                    val result = files.thumbnail(currentMeme.copy(thumbUri = ""))
                                                    if (result.isNotBlank()) {
                                                        Toast.makeText(context, "Thumbnail updated", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Could not extract thumbnail", Toast.LENGTH_LONG).show()
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not regenerate thumbnail: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                    )
                                }
                                if (currentMeme.ocr.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Copy the text in this meme", color = MemegetColors.TextPrimary) },
                                        onClick = {
                                            showMoreMenu = false
                                            copyText(context, currentMeme.ocr, "OCR text copied")
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Show taught-label confidence", color = MemegetColors.TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        showConfidenceDialog = true
                                    },
                                )
                            }
                        }
                    }
                }

                // Scrollable content: Media display on top, metadata sheet below
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Media Viewer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 380.dp)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (currentMeme.kind == "video") {
                            VideoPlayerView(uri = currentMeme.uri)
                        } else {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(currentMeme.uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = currentMeme.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                loading = {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = MemegetColors.Volt,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    // Actions Bar below media
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MemegetColors.Surface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!copying) {
                                        copying = true
                                        scope.launch {
                                            try {
                                                files.copyToClipboard(currentMeme.uri, currentMeme.name)
                                                runCatching { store.recordCopy(currentMeme.id) }
                                                val message = if (currentMeme.kind == "video") {
                                                    "Video copied — paste in apps that accept videos"
                                                } else {
                                                    "Meme copied — paste it in another app"
                                                }
                                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                Toast.makeText(context, "Could not copy: ${failure.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                copying = false
                                            }
                                        }
                                    }
                                },
                                enabled = !copying,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(if (copying) "Copying…" else "Copy meme", style = MaterialTheme.typography.labelSmall)
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val mime = MediaIoUtils.mimeForName(currentMeme.name)
                                            files.exportToDownloads(currentMeme.uri, currentMeme.name, mime)
                                            Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Could not export: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !copying,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("Download", style = MaterialTheme.typography.labelSmall)
                            }

                            OutlinedButton(
                                onClick = {
                                    coordinator.start(listOf(currentMeme.id))
                                    Toast.makeText(context, "Re-analysis queued", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !copying,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("Re-analyze", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        store.markNotAMeme(currentMeme.id)
                                        Toast.makeText(context, "Hidden from Memeget — camera roll untouched", Toast.LENGTH_SHORT).show()
                                        onMemeDeleted(currentMeme.id)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not hide item: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !copying,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MemegetColors.Warning),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Not a meme", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Detail Sheet
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Provenance & Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MetadataPill(
                                label = "Vision: ${currentMeme.visionState}",
                                color = if (currentMeme.visionState == "done") MemegetColors.Success else MemegetColors.TextMuted,
                            )
                            if (currentMeme.kind == "video") {
                                MetadataPill(
                                    label = "Audio: ${currentMeme.audioState}",
                                    color = if (currentMeme.audioState == "done") MemegetColors.Success else MemegetColors.TextMuted,
                                )
                            }
                        }

                        // Timestamps
                        val timestampTexts = buildList {
                            if (currentMeme.modifiedAt > 0) {
                                add("File date: ${dateFormat.format(Date(currentMeme.modifiedAt))}")
                            }
                            if (currentMeme.indexedAt > 0) {
                                add("Added: ${dateFormat.format(Date(currentMeme.indexedAt))}")
                            }
                        }
                        if (timestampTexts.isNotEmpty()) {
                            Text(
                                text = timestampTexts.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MemegetColors.TextMuted,
                            )
                        }

                        // Cloud Analysis Section with honest confirmation contract
                        if (currentMeme.cloudObservation != null || currentMeme.cloudCaption.isNotEmpty() ||
                            currentMeme.cloudTerms.isNotEmpty() || currentMeme.tags.any { it.source == "cloud" || it.source == "cloud_hint" }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MemegetColors.SurfaceVariant)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Cloud Analysis",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MemegetColors.Blue,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "Unverified Provider Estimate",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MemegetColors.TextMuted,
                                    )
                                }
                                currentMeme.cloudObservation?.let { observation ->
                                    Text(
                                        text = "${observation.model} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(observation.createdAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemegetColors.TextSecondary,
                                    )
                                }

                                if (currentMeme.cloudCaption.isNotEmpty()) {
                                    Text(
                                        text = currentMeme.cloudCaption,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MemegetColors.TextPrimary,
                                    )
                                }

                                if (currentMeme.cloudTerms.isNotEmpty()) {
                                    Text(
                                        text = "Terms: ${currentMeme.cloudTerms}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MemegetColors.TextSecondary,
                                    )
                                }

                                Text(
                                    text = "Provider predictions are external estimates, not audited identity correctness. You can confirm or reject identity labels.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemegetColors.TextMuted,
                                )

                                currentMeme.tags.filter { it.source == "cloud" || it.source == "cloud_hint" }.forEach { suggestion ->
                                    val confirmed = suggestion.confirmation == "confirmed"
                                    Text(
                                        if (confirmed) "${suggestion.label} · confirmed by you" else suggestion.label,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        "${suggestion.source} · ${suggestion.provenance}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MemegetColors.TextMuted,
                                    )
                                    val actions = if (confirmed) listOf(false to "Reject label")
                                        else listOf(true to "Confirm label", false to "Reject label")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        actions.forEach { (accepted, title) ->
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        try {
                                                            cloud.confirm(currentMeme.id, suggestion.label, accepted)
                                                            store.meme(currentMeme.id)?.let { currentMeme = it }
                                                        } catch (failure: Exception) {
                                                            if (failure is CancellationException) throw failure
                                                            Toast.makeText(context, failure.message ?: "Correction was not saved", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (accepted) MemegetColors.Success else MemegetColors.Danger,
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            ) { Text(title, style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }

                        // Local Caption
                        if (currentMeme.caption.isNotEmpty()) {
                            DetailSection(title = "Local Scene Caption") {
                                Text(
                                    text = currentMeme.caption,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemegetColors.TextPrimary,
                                )
                            }
                        }

                        // OCR Text
                        if (currentMeme.ocr.isNotEmpty()) {
                            DetailSection(
                                title = "Text Inside Image (OCR)",
                                actionLabel = "Copy",
                                onAction = {
                                    copyText(context, currentMeme.ocr, "OCR text copied")
                                },
                            ) {
                                Text(
                                    text = currentMeme.ocr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemegetColors.TextPrimary,
                                )
                            }
                        }

                        // Spoken Transcript (for videos)
                        if (currentMeme.transcript.isNotEmpty()) {
                            DetailSection(
                                title = "Spoken Speech (STT)",
                                actionLabel = "Copy",
                                onAction = {
                                    copyText(context, currentMeme.transcript, "Transcript copied")
                                },
                            ) {
                                Text(
                                    text = currentMeme.transcript,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MemegetColors.TextPrimary,
                                )
                            }
                        }

                        // Tags & Concepts
                        DetailSection(title = "Tags & Concepts") {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                currentMeme.tags.forEach { tag ->
                                    TagChip(
                                        tag = tag,
                                        onClick = {
                                            onClose()
                                            onSearchTag(tag.label)
                                        },
                                        onLongClick = {
                                            verdictTag = tag
                                        },
                                        onRemove = {
                                            scope.launch {
                                                store.removeTag(currentMeme.id, tag.label)
                                                val fresh = store.meme(currentMeme.id)
                                                if (fresh != null) currentMeme = fresh
                                            }
                                        },
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MemegetColors.VoltContainer)
                                        .clickable { showTagDialog = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "+ Add Tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MemegetColors.Volt,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MemegetColors.SurfaceBright)
                                        .clickable { showTeachDialog = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "★ Teach Exemplar",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MemegetColors.TextPrimary,
                                    )
                                }
                            }
                        }

                        // Visually Similar Memes
                        DetailSection(title = "Visually Similar Memes") {
                            if (loadingSimilar) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = MemegetColors.Volt,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else if (similarHits.isEmpty()) {
                                Text(
                                    text = "No similar memes found in current index.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MemegetColors.TextMuted,
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                ) {
                                    items(similarHits, key = { it.meme.id }) { hit ->
                                        SimilarMemeThumbnail(
                                            hit = hit,
                                            onClick = {
                                                currentMeme = hit.meme
                                                onNavigateToMeme(hit.meme)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Tag Dialog
    if (showTagDialog) {
        TagDialog(
            targetCount = 1,
            onDismiss = { showTagDialog = false },
            onConfirm = { label, category, spread ->
                showTagDialog = false
                // Started at once and finished even if the viewer closes: a lost save would also lose its re-tag.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        withContext(NonCancellable) {
                            store.manualTag(listOf(currentMeme.id), label, category, spread)
                            onTaught()
                        }
                        val fresh = store.meme(currentMeme.id)
                        if (fresh != null) currentMeme = fresh
                        Toast.makeText(context, "Tag applied", Toast.LENGTH_SHORT).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not apply tag: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    // Teach Exemplar Dialog
    if (showTeachDialog) {
        TeachDialog(
            initialLabel = currentMeme.tags.firstOrNull()?.label ?: "",
            onDismiss = { showTeachDialog = false },
            onConfirm = { label, category, positive, associations ->
                showTeachDialog = false
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        withContext(NonCancellable) {
                            cloud.recordTeaching(currentMeme.id, label, category, positive, associations)
                            onTaught()
                        }
                        val fresh = store.meme(currentMeme.id)
                        if (fresh != null) currentMeme = fresh
                        Toast.makeText(
                            context,
                            if (positive) "Taught positive exemplar" else "Taught negative veto exemplar",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (positive) {
                            checkAndOpenAlsoThese(label, category)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not teach: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = 1,
            onDismiss = { showDeleteDialog = false },
            onConfirmDelete = {
                showDeleteDialog = false
                scope.launch {
                    try {
                        files.deleteMemes(listOf(currentMeme))
                        onMemeDeleted(currentMeme.id)
                        onClose()
                        Toast.makeText(context, "Meme deleted", Toast.LENGTH_SHORT).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not delete: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    // Tag Verdict Dialog
    if (verdictTag != null) {
        val tag = verdictTag!!
        AlertDialog(
            onDismissRequest = { verdictTag = null },
            title = {
                Text(
                    text = "“${tag.label}”",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )
            },
            text = {
                Text(
                    text = "Is “${tag.label}” right for this meme?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MemegetColors.TextPrimary,
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val targetTag = verdictTag ?: return@Button
                            verdictTag = null
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    withContext(NonCancellable) {
                                        cloud.recordTeaching(currentMeme.id, targetTag.label, targetTag.category, true, emptyList())
                                        onTaught()
                                    }
                                    val fresh = store.meme(currentMeme.id)
                                    if (fresh != null) currentMeme = fresh
                                    Toast.makeText(context, "Taught “${targetTag.label}”", Toast.LENGTH_SHORT).show()
                                    checkAndOpenAlsoThese(targetTag.label, targetTag.category)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not teach: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                    ) {
                        Text("✓ Right — teach it", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val targetTag = verdictTag ?: return@Button
                            verdictTag = null
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    withContext(NonCancellable) {
                                        cloud.recordTeaching(currentMeme.id, targetTag.label, targetTag.category, false, emptyList())
                                        onTaught()
                                    }
                                    val fresh = store.meme(currentMeme.id)
                                    if (fresh != null) currentMeme = fresh
                                    Toast.makeText(context, "Saved: NOT “${targetTag.label}”", Toast.LENGTH_SHORT).show()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not teach: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Danger,
                            contentColor = MemegetColors.TextPrimary,
                        ),
                    ) {
                        Text("✗ Wrong — not this")
                    }

                    TextButton(
                        onClick = { verdictTag = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel", color = MemegetColors.TextSecondary)
                    }
                }
            },
            containerColor = MemegetColors.Surface,
        )
    }

    // "Also these?" Dialog
    if (showAlsoTheseDialog && alsoTheseCandidates.isNotEmpty()) {
        var isSavingAlsoThese by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isSavingAlsoThese) {
                    showAlsoTheseDialog = false
                }
            },
            title = {
                Text(
                    text = "More “$alsoTheseLabel”?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "These look similar. Tap the ones that are also “$alsoTheseLabel” — every confirmation becomes another example and makes the label sharper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            alsoTheseCandidates.forEach { candidate ->
                                val isPicked = alsoTheseSelected.contains(candidate.id)
                                val thumbSource = if (candidate.thumbUri.isNotEmpty()) candidate.thumbUri else candidate.uri

                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MemegetColors.SurfaceVariant)
                                        .border(
                                            width = if (isPicked) 2.dp else 0.5.dp,
                                            color = if (isPicked) MemegetColors.Volt else MemegetColors.SurfaceBright,
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .clickable {
                                            if (!isSavingAlsoThese) {
                                                alsoTheseSelected = if (isPicked) {
                                                    alsoTheseSelected - candidate.id
                                                } else {
                                                    alsoTheseSelected + candidate.id
                                                }
                                            }
                                        },
                                ) {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(thumbSource)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = candidate.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (isPicked) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(MemegetColors.Volt),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "✓",
                                                color = MemegetColors.Background,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toTeach = alsoTheseCandidates.filter { alsoTheseSelected.contains(it.id) }
                        if (toTeach.isEmpty()) return@Button
                        isSavingAlsoThese = true
                        val label = alsoTheseLabel
                        val category = alsoTheseCategory
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                withContext(NonCancellable) {
                                    for (m in toTeach) {
                                        cloud.recordTeaching(m.id, label, category, true, emptyList())
                                    }
                                    onTaught()
                                }
                                val fresh = store.meme(currentMeme.id)
                                if (fresh != null) currentMeme = fresh
                                Toast.makeText(
                                    context,
                                    "Saved “$alsoTheseLabel” from ${toTeach.size} more example${if (toTeach.size == 1) "" else "s"}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showAlsoTheseDialog = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not save examples: ${e.message ?: e.toString()}", Toast.LENGTH_LONG).show()
                            } finally {
                                isSavingAlsoThese = false
                            }
                        }
                    },
                    enabled = !isSavingAlsoThese && alsoTheseSelected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MemegetColors.Volt,
                        contentColor = MemegetColors.Background,
                    ),
                ) {
                    Text(
                        text = if (isSavingAlsoThese) "Saving…" else "Teach ${alsoTheseSelected.size} more",
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlsoTheseDialog = false },
                    enabled = !isSavingAlsoThese,
                ) {
                    Text("Skip", color = MemegetColors.TextSecondary)
                }
            },
            containerColor = MemegetColors.Surface,
        )
    }

    // Taught-label Confidence Dialog
    if (showConfidenceDialog) {
        AlertDialog(
            onDismissRequest = { showConfidenceDialog = false },
            title = {
                Text(
                    text = "Taught-label confidence",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.TextPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Closest taught example for each label. A look-alike takes a label at 0.80 or more when no “not” example is closer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextMuted,
                    )
                    when (val state = confidenceState) {
                        is ConfidenceState.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = MemegetColors.Volt,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        is ConfidenceState.NoVector -> {
                            Text(
                                text = "No stored vector for this meme.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MemegetColors.TextSecondary,
                            )
                        }
                        is ConfidenceState.NoTeachings -> {
                            Text(
                                text = "No matching taught concepts found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MemegetColors.TextSecondary,
                            )
                        }
                        is ConfidenceState.Loaded -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.entries.forEach { entry ->
                                    val posStr = entry.bestPositive?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
                                    val negStr = entry.bestNegative?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
                                    Text(
                                        text = "${entry.label}  $posStr · not $negStr",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MemegetColors.TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfidenceDialog = false }) {
                    Text("Close", color = MemegetColors.Volt)
                }
            },
            containerColor = MemegetColors.Surface,
        )
    }
}

@Composable
private fun VideoPlayerView(uri: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            repeatMode = Player.REPEAT_MODE_ALL
            // Unplugging headphones pauses instead of moving the sound to the speaker.
            setHandleAudioBecomingNoisy(true)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // A looping meme must not keep playing once Memeget leaves the screen; it carries on when Memeget comes back.
    DisposableEffect(exoPlayer, lifecycleOwner) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> if (resumeOnStart) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        // The view outlives one meme (a look-alike opens in place): give it each new player, or it keeps showing
        // the released one while the new clip plays unseen.
        update = { view -> view.player = exoPlayer },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun DetailSection(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MemegetColors.Surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MemegetColors.TextPrimary,
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MemegetColors.Volt,
                    modifier = Modifier.clickable { onAction() },
                )
            }
        }
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    tag: Tag,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val sourceColor = when (tag.source) {
        "manual" -> MemegetColors.Volt
        "cloud" -> MemegetColors.Blue
        "vision" -> MemegetColors.Warning
        else -> MemegetColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MemegetColors.SurfaceVariant)
            .border(0.5.dp, sourceColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = tag.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MemegetColors.TextPrimary,
        )
        Text(
            // A TV show or movie says which it is; other tags say where they came from.
            text = "(${if (Titles.isTitleCategory(tag.category)) categoryName(tag.category) else tag.source})",
            style = MaterialTheme.typography.labelSmall,
            color = sourceColor,
            fontSize = 9.sp,
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelSmall,
            color = MemegetColors.TextMuted,
            modifier = Modifier
                .clickable { onRemove() }
                .padding(start = 2.dp),
        )
    }
}

@Composable
private fun MetadataPill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MemegetColors.Surface)
            .border(0.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SimilarMemeThumbnail(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageSource = remember(hit.meme.thumbUri, hit.meme.uri) {
        if (hit.meme.thumbUri.isNotEmpty()) hit.meme.thumbUri else hit.meme.uri
    }

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MemegetColors.SurfaceVariant)
            .clickable { onClick() },
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageSource)
                .crossfade(true)
                .build(),
            contentDescription = hit.meme.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Cosine similarity badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            val scorePct = (hit.score * 100).toInt().coerceIn(0, 100)
            Text(
                text = "$scorePct%",
                color = MemegetColors.Volt,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun copyText(context: Context, text: String, toastMsg: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Memeget", text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) sum += a[i] * b[i]
    return sum
}

private data class ConfidenceEntry(
    val label: String,
    val bestPositive: Float?,
    val bestNegative: Float?,
)

private sealed interface ConfidenceState {
    object Loading : ConfidenceState
    object NoVector : ConfidenceState
    object NoTeachings : ConfidenceState
    data class Loaded(val entries: List<ConfidenceEntry>) : ConfidenceState
}
