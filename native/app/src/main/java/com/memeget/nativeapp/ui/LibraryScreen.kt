package com.memeget.nativeapp

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 90

@Composable
internal fun LibraryScreen(
    store: LibraryStore,
    files: LibraryFiles,
    engine: InferenceEngine,
    knowledge: LocalKnowledge,
    coordinator: IndexCoordinator,
    onOpenViewer: (Meme) -> Unit,
    onOpenRemix: (Meme) -> Unit,
    onOpenSettings: () -> Unit,
    tagSearchRequest: String? = null,
    modifier: Modifier = Modifier,
    onInteractionChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val revision by store.revision.collectAsState()
    val modelProgress by engine.progress.collectAsState()
    val indexProgress by coordinator.progress.collectAsState()

    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<String?>(null) } // null = all, "image", "video"
    var selectedTag by remember { mutableStateOf<String?>(null) }

    var memes by remember { mutableStateOf<List<Meme>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var totalCount by remember { mutableStateOf(0) }
    var folderCount by remember { mutableStateOf(0) }
    var taughtLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var libraryTags by remember { mutableStateOf<List<String>>(emptyList()) }

    var bannerDismissed by remember { mutableStateOf(false) }

    // Multi-select state
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // SAF folder picker launcher
    val linkFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val folder = files.link(uri)
                    Toast.makeText(context, "Linked ${folder.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to link folder: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Refresh counts and labels on revision or mount
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val count = store.count()
            val folders = store.folders()
            val exemplars = store.exemplars()
            val tags = store.tagLabels()
            val taught = exemplars.map { it.label }.distinct()
            withContext(Dispatchers.Main) {
                totalCount = count
                folderCount = folders.size
                taughtLabels = taught
                libraryTags = tags
            }
        }
    }

    // Handle tag search request from viewer or settings
    LaunchedEffect(tagSearchRequest) {
        if (!tagSearchRequest.isNullOrBlank()) {
            query = tagSearchRequest
            kindFilter = null
            selectedTag = null
        }
    }

    var queryVector by remember { mutableStateOf<FloatArray?>(null) }
    var vectorText by remember { mutableStateOf("") }
    var resultKey by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    var resultGeneration by remember { mutableStateOf(0L) }
    var pageJob by remember { mutableStateOf<Job?>(null) }
    var searchCursor by remember { mutableStateOf<SearchCursor?>(null) }
    LaunchedEffect(isSearching) { onInteractionChange(isSearching) }
    DisposableEffect(Unit) { onDispose { onInteractionChange(false) } }

    // A library revision must not collapse the pages the user has already loaded.
    // Reuse unchanged row objects so a metadata write does not invalidate every cell.
    LaunchedEffect(query, kindFilter, selectedTag, revision) {
        resultGeneration++
        val generation = resultGeneration
        pageJob?.cancel()
        isLoadingMore = false
        val key = Triple(query.trim(), kindFilter, selectedTag)
        val previous = if (resultKey == key) memes else emptyList()
        val wanted = maxOf(PAGE_SIZE, previous.size)
        val previousById = previous.associateBy { it.id }
        resultKey = key
        if (previous.isEmpty()) memes = emptyList()
        if (vectorText != key.first) {
            vectorText = key.first
            queryVector = null
        }
        isSearching = key.first.isNotEmpty()
        searchError = null
        fun publish(rows: List<Meme>) {
            memes = rows.map { row -> previousById[row.id]?.takeIf { it == row } ?: row }
            hasMore = rows.size >= wanted
        }
        suspend fun searchWindow(vector: FloatArray?): Pair<List<Meme>, SearchCursor?> = withContext(Dispatchers.IO) {
            val loaded = ArrayList<Meme>(wanted)
            var cursor: SearchCursor? = null
            while (loaded.size < wanted) {
                val page = store.search(key.first, vector, key.second, key.third, minOf(PAGE_SIZE, wanted - loaded.size), cursor)
                loaded.addAll(page.map { it.meme })
                if (page.isEmpty()) break
                val last = page.last()
                cursor = SearchCursor(last.score, last.meme.modifiedAt, last.meme.id)
            }
            loaded to cursor
        }
        try {
            delay(120)
            if (key.first.isEmpty()) {
                val rows = withContext(Dispatchers.IO) {
                    val loaded = ArrayList<Meme>(wanted)
                    var cursor: PageCursor? = null
                    while (loaded.size < wanted) {
                        val page = store.page(minOf(PAGE_SIZE, wanted - loaded.size), cursor, key.second, key.third)
                        loaded.addAll(page)
                        if (page.isEmpty()) break
                        val last = page.last()
                        cursor = PageCursor(last.modifiedAt, last.id)
                    }
                    loaded
                }
                publish(rows)
            } else {
                val lexical = searchWindow(null)
                searchCursor = lexical.second
                publish(lexical.first)
                val vector = queryVector ?: try {
                    engine.embedText(key.first)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    searchError = "Text matches shown; semantic search unavailable: ${failure.message}"
                    null
                }
                queryVector = vector
                if (vector != null) {
                    val hybrid = searchWindow(vector)
                    searchCursor = hybrid.second
                    publish(hybrid.first)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            searchError = failure.message ?: "Library retrieval failed"
        } finally {
            if (generation == resultGeneration) isSearching = false
        }
    }

    val onLoadMore = {
        if (!isLoadingMore && !isSearching && hasMore && memes.isNotEmpty()) {
            val generation = resultGeneration
            val key = Triple(query.trim(), kindFilter, selectedTag)
            val cursor = memes.last().let { PageCursor(it.modifiedAt, it.id) }
            val after = searchCursor
            val vector = queryVector
            isLoadingMore = true
            pageJob = scope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        if (key.first.isEmpty()) store.page(PAGE_SIZE, cursor, key.second, key.third) to null
                        else {
                            val hits = store.search(key.first, vector, key.second, key.third, PAGE_SIZE, after)
                            hits.map { it.meme } to hits.lastOrNull()?.let { SearchCursor(it.score, it.meme.modifiedAt, it.meme.id) }
                        }
                    }
                    val rows = page.first
                    if (generation == resultGeneration && key == Triple(query.trim(), kindFilter, selectedTag)) {
                        searchCursor = page.second
                        val existing = memes.mapTo(HashSet()) { it.id }
                        memes = memes + rows.filter { it.id !in existing }
                        hasMore = rows.size >= PAGE_SIZE
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation == resultGeneration) searchError = failure.message ?: "Next page could not be loaded"
                } finally {
                    if (generation == resultGeneration) isLoadingMore = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MemegetColors.Background),
    ) {
        // Top Fixed Chrome: Brand, Model Status Pill, Settings Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Memeget",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MemegetColors.TextPrimary,
                )
                Text(
                    text = ".",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MemegetColors.Volt,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Model Status Pill
                ModelStatusBadge(progress = modelProgress)

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("⚙", color = MemegetColors.TextSecondary, fontSize = 18.sp)
                }
            }
        }

        // Standalone Install Banner (Explaining side-by-side isolated installation)
        if (!bannerDismissed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MemegetColors.SurfaceVariant)
                    .border(0.5.dp, MemegetColors.Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Standalone native install. React Native Memeget data & app remain untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MemegetColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelSmall,
                        color = MemegetColors.TextMuted,
                        modifier = Modifier
                            .clickable { bannerDismissed = true }
                            .padding(4.dp),
                    )
                }
            }
        }

        // Search Text Field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        text = if (totalCount > 0) "Search $totalCount memes by vibe, tag, text..." else "Search memes...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MemegetColors.TextMuted,
                    )
                },
                leadingIcon = {
                    Text("⌕", color = MemegetColors.TextSecondary, fontSize = 18.sp)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Text("✕", color = MemegetColors.TextSecondary, fontSize = 14.sp)
                        }
                    } else if (isSearching) {
                        CircularProgressIndicator(
                            color = MemegetColors.Volt,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MemegetColors.Volt,
                    unfocusedBorderColor = MemegetColors.Border,
                    focusedContainerColor = MemegetColors.Surface,
                    unfocusedContainerColor = MemegetColors.Surface,
                    cursorColor = MemegetColors.Volt,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Quick Filter Chips (Media kinds & Tags)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Media kind chips
            FilterChip(
                label = "▦ Images",
                active = kindFilter == "image",
                onClick = {
                    kindFilter = if (kindFilter == "image") null else "image"
                },
            )
            FilterChip(
                label = "▶ Videos",
                active = kindFilter == "video",
                onClick = {
                    kindFilter = if (kindFilter == "video") null else "video"
                },
            )

            if (taughtLabels.isNotEmpty() || libraryTags.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                        .background(MemegetColors.Border),
                )
            }

            // Taught concepts first (starred)
            taughtLabels.forEach { label ->
                FilterChip(
                    label = "★ $label",
                    active = selectedTag == label || query.trim() == label,
                    onClick = {
                        if (selectedTag == label) {
                            selectedTag = null
                        } else {
                            selectedTag = label
                            query = ""
                        }
                    },
                )
            }

            // Library tags
            libraryTags.filter { !taughtLabels.contains(it) }.take(20).forEach { label ->
                FilterChip(
                    label = label,
                    active = selectedTag == label || query.trim() == label,
                    onClick = {
                        if (selectedTag == label) {
                            selectedTag = null
                        } else {
                            selectedTag = label
                            query = ""
                        }
                    },
                )
            }
        }

        // Index Coordinator Progress Card (when indexing is running)
        AnimatedVisibility(visible = indexProgress.running) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MemegetColors.SurfaceVariant)
                    .border(0.5.dp, MemegetColors.Border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (indexProgress.total > 0) {
                                "Indexing ${indexProgress.done}/${indexProgress.total} · ${indexProgress.stage}"
                            } else {
                                "Preparing index: ${indexProgress.stage}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MemegetColors.TextPrimary,
                        )
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MemegetColors.Danger,
                            modifier = Modifier
                                .clickable { coordinator.cancel() }
                                .padding(2.dp),
                        )
                    }

                    val progressFraction = if (indexProgress.total > 0) {
                        indexProgress.done.toFloat() / indexProgress.total.toFloat()
                    } else 0f

                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MemegetColors.Volt,
                        trackColor = MemegetColors.Surface,
                    )

                    if (indexProgress.detail.isNotEmpty()) {
                        Text(
                            text = indexProgress.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MemegetColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Multi-select Action Bar
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MemegetColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${selectedIds.size} selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MemegetColors.Volt,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            selectedIds = if (selectedIds.size == memes.size) emptySet() else memes.map { it.id }.toSet()
                        },
                    ) {
                        Text(if (selectedIds.size == memes.size) "Deselect" else "All", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { showBatchTagDialog = true },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Volt,
                            contentColor = MemegetColors.Background,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Tag", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = {
                            val selectedMemes = memes.filter { selectedIds.contains(it.id) }
                            files.share(context, selectedMemes)
                        },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.SurfaceBright,
                            contentColor = MemegetColors.TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Share", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { showBatchDeleteDialog = true },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MemegetColors.Danger,
                            contentColor = MemegetColors.TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Delete", style = MaterialTheme.typography.labelSmall)
                    }

                    IconButton(onClick = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Text("✕", color = MemegetColors.TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }

        // Meme Grid
        MemeGrid(
            memes = memes,
            selectionMode = selectionMode,
            selectedIds = selectedIds,
            activeMemeId = indexProgress.memeId.takeIf { indexProgress.running },
            onItemClick = { meme ->
                if (selectionMode) {
                    selectedIds = if (selectedIds.contains(meme.id)) {
                        selectedIds - meme.id
                    } else {
                        selectedIds + meme.id
                    }
                } else {
                    onOpenViewer(meme)
                }
            },
            onItemLongClick = { meme ->
                if (!selectionMode) {
                    selectionMode = true
                    selectedIds = setOf(meme.id)
                } else {
                    selectedIds = if (selectedIds.contains(meme.id)) {
                        selectedIds - meme.id
                    } else {
                        selectedIds + meme.id
                    }
                }
            },
            onLoadMore = onLoadMore,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            modifier = Modifier.weight(1f),
            headerContent = {
                // Toolbar info & actions
                if (totalCount > 0 && !indexProgress.running) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (query.isNotEmpty()) {
                                "${memes.size} match${if (memes.size == 1) "" else "es"}"
                            } else {
                                "$totalCount meme${if (totalCount == 1) "" else "s"}${if (folderCount > 0) " · $folderCount folder${if (folderCount > 1) "s" else ""}" else ""}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MemegetColors.TextSecondary,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { linkFolderLauncher.launch(null) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("＋ Link", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { coordinator.start() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MemegetColors.Volt,
                                    contentColor = MemegetColors.Background,
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) {
                                Text("⟳ Index", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            emptyContent = {
                if (totalCount == 0 && !indexProgress.running) {
                    // Onboarding 1-2-3 guide
                    OnboardingCard(
                        onLink = { linkFolderLauncher.launch(null) },
                        onIndex = { coordinator.start() },
                        folderCount = folderCount,
                    )
                } else if (memes.isEmpty() && query.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text("¯\\_(ツ)_/¯", fontSize = 28.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No memes matched “$query”",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MemegetColors.TextPrimary,
                        )
                        Text(
                            text = "Try another word, vibe, character, or clear the filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MemegetColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
        )
    }

    // Batch Tag Dialog
    if (showBatchTagDialog) {
        TagDialog(
            targetCount = selectedIds.size,
            allowSpread = true,
            onDismiss = { showBatchTagDialog = false },
            onConfirm = { label, category, spread ->
                showBatchTagDialog = false
                scope.launch {
                    val count = store.manualTag(selectedIds.toList(), label, category, spread)
                    Toast.makeText(context, "Tagged $count memes", Toast.LENGTH_SHORT).show()
                    selectionMode = false
                    selectedIds = emptySet()
                }
            },
        )
    }

    // Batch Delete Dialog
    if (showBatchDeleteDialog) {
        DeleteConfirmationDialog(
            count = selectedIds.size,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirmDeleteIndex = {
                showBatchDeleteDialog = false
                scope.launch {
                    store.delete(selectedIds.toList())
                    Toast.makeText(context, "Removed from index", Toast.LENGTH_SHORT).show()
                    selectionMode = false
                    selectedIds = emptySet()
                }
            },
        )
    }
}

@Composable
private fun ModelStatusBadge(progress: ModelProgress) {
    val toneColor = when {
        progress.error != null -> MemegetColors.Danger
        progress.loading -> MemegetColors.Warning
        else -> MemegetColors.Success
    }

    val label = when {
        progress.error != null -> "model error"
        progress.loading -> {
            val pct = (progress.fraction * 100).toInt()
            if (pct > 0) "model $pct%" else "model loading"
        }
        else -> "on-device"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MemegetColors.Surface)
            .border(0.5.dp, toneColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(toneColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MemegetColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MemegetColors.VoltContainer else MemegetColors.Surface)
            .border(
                width = 0.5.dp,
                color = if (active) MemegetColors.Volt else MemegetColors.Border,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MemegetColors.Volt else MemegetColors.TextSecondary,
        )
    }
}

@Composable
private fun OnboardingCard(
    onLink: () -> Unit,
    onIndex: () -> Unit,
    folderCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MemegetColors.Surface)
            .border(1.dp, MemegetColors.Border, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("🐸", fontSize = 36.sp)
        Text(
            text = "Your stash, searchable",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )
        Text(
            text = "Memeget indexes your local memes entirely on your phone — no uploads, no cloud required. Find anything by vibe, character, format, or words inside.",
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepRow(number = "1", title = "Link your meme folder", completed = folderCount > 0)
            StepRow(number = "2", title = "Index your library on-device", completed = false)
            StepRow(number = "3", title = "Search “sad frog”, “galaxy brain”, anything", completed = false)
        }

        if (folderCount == 0) {
            Button(
                onClick = onLink,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.Volt,
                    contentColor = MemegetColors.Background,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Link Meme Folder", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onIndex,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MemegetColors.Volt,
                    contentColor = MemegetColors.Background,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Index My Memes", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, completed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (completed) MemegetColors.Success else MemegetColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (completed) "✓" else number,
                style = MaterialTheme.typography.labelSmall,
                color = if (completed) Color.Black else MemegetColors.TextSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (completed) MemegetColors.TextPrimary else MemegetColors.TextSecondary,
        )
    }
}
