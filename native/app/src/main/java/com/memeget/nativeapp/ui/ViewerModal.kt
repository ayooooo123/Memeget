package com.memeget.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewerModal(
    meme: Meme,
    files: LibraryFiles,
    store: LibraryStore,
    cloud: CloudAssistant,
    coordinator: IndexCoordinator,
    onClose: () -> Unit,
    onRemix: (Meme) -> Unit,
    onSearchTag: (String) -> Unit,
    onNavigateToMeme: (Meme) -> Unit,
    onMemeDeleted: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showTeachDialog by remember { mutableStateOf(false) }
    var similarHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var loadingSimilar by remember { mutableStateOf(false) }
    var currentMeme by remember(meme.id) { mutableStateOf(meme) }
    var copying by remember(meme.id) { mutableStateOf(false) }

    // Fetch refreshed meme and similar hits when meme changes
    LaunchedEffect(currentMeme.id) {
        loadingSimilar = true
        withContext(Dispatchers.IO) {
            val fresh = store.meme(currentMeme.id)
            if (fresh != null) {
                withContext(Dispatchers.Main) { currentMeme = fresh }
            }
            val similar = store.similar(currentMeme.id, limit = 24)
            withContext(Dispatchers.Main) {
                similarHits = similar
                loadingSimilar = false
            }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { onRemix(currentMeme) }) {
                            Text("✎", color = MemegetColors.Volt, fontSize = 18.sp)
                        }
                        IconButton(onClick = { files.share(context, listOf(currentMeme)) }) {
                            Text("↗", color = MemegetColors.TextPrimary, fontSize = 18.sp)
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Text("🗑", color = MemegetColors.Danger, fontSize = 16.sp)
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
                                        val mime = if (currentMeme.kind == "video") "video/mp4" else "image/png"
                                        files.exportToDownloads(currentMeme.uri, currentMeme.name, mime)
                                        Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
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

                        Button(
                            onClick = { onRemix(currentMeme) },
                            enabled = !copying,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MemegetColors.Volt,
                                contentColor = MemegetColors.Background,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Text("Remix Studio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
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
                scope.launch {
                    store.manualTag(listOf(currentMeme.id), label, category, spread)
                    val fresh = store.meme(currentMeme.id)
                    if (fresh != null) currentMeme = fresh
                    Toast.makeText(context, "Tag applied", Toast.LENGTH_SHORT).show()
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
                scope.launch {
                    cloud.recordTeaching(currentMeme.id, label, category, positive, associations)
                    val fresh = store.meme(currentMeme.id)
                    if (fresh != null) currentMeme = fresh
                    Toast.makeText(
                        context,
                        if (positive) "Taught positive exemplar" else "Taught negative veto exemplar",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = 1,
            onDismiss = { showDeleteDialog = false },
            onConfirmDeleteIndex = {
                showDeleteDialog = false
                scope.launch {
                    store.delete(listOf(currentMeme.id))
                    onMemeDeleted(currentMeme.id)
                    onClose()
                    Toast.makeText(context, "Removed from index", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun VideoPlayerView(uri: String) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
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

@Composable
private fun TagChip(
    tag: Tag,
    onClick: () -> Unit,
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
            .clickable { onClick() }
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
            text = "(${tag.source})",
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
