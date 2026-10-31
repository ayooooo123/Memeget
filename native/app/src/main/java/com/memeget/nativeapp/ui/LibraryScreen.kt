package com.memeget.nativeapp

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.memeget.nativeapp.data.SocialReplyIntent
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.storage.CameraRollScanner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private const val PAGE_SIZE = 90
private const val UI_PREFS = "memeget_ui"
private const val BANNER_DISMISSED = "libraryBannerDismissed"
private const val BROWSE_MODE = "browseMode"

@Composable
internal fun LibraryScreen(
    store: LibraryStore,
    files: LibraryFiles,
    engine: InferenceEngine,
    knowledge: LocalKnowledge,
    coordinator: IndexCoordinator,
    /**
     * Opens a meme in the viewer; a swipe there moves through [pages], the list it was opened from. [searchText] is
     * the search or quoted line it was opened for.
     */
    onOpenViewer: (meme: Meme, pages: List<Meme>, searchText: String) -> Unit,
    onOpenSettings: () -> Unit,
    // False while Label or Settings shows: the screen stays composed then, but must not take Back.
    active: Boolean = true,
    tagSearchRequest: String? = null,
    onTagSearchApplied: () -> Unit = {},
    // Memes deleted elsewhere (the viewer): dropped from the grid at once instead of at the next reload.
    deletedMemeIds: Set<Long> = emptySet(),
    modifier: Modifier = Modifier,
    onInteractionChange: (Boolean) -> Unit = {},
    /** Saves a teaching: [right] says whether [label] fits the meme. */
    onTeach: suspend (memeId: Long, label: String, category: String, right: Boolean) -> Unit = { _, _, _, _ -> },
    /** Teachings were given here: the library should re-tag so look-alikes learn from them. */
    onTaught: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Library writes come in bursts: an indexing session or a re-tag writes many times a second. Restarting the
    // loads below on every write meant they never finished, so results went stale while work ran (a deleted meme
    // stayed on screen). Reloads now follow writes after a short settle and never cancel a load under way.
    var loadInFlight by remember { mutableStateOf(false) }
    val revision by rememberSettledRevision(store.revision) { loadInFlight }
    val modelProgress by engine.progress.collectAsState()
    val indexProgress by coordinator.progress.collectAsState()

    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<String?>(null) } // null = all, "image", "video"
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var usedOnly by remember { mutableStateOf(false) }
    var memes by remember { mutableStateOf<List<Meme>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    // A search the person started (a new query), not a reload after a library write: only that pauses background
    // work, and only while this tab is in front.
    var userSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var totalCount by remember { mutableStateOf(0) }
    // Rows still waiting for analysis count here, so a freshly imported library keeps its Index button.
    var libraryCount by remember { mutableStateOf(0) }
    var folderCount by remember { mutableStateOf(0) }
    var taughtLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var libraryTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var usedCount by remember { mutableStateOf(0) }
    var copiedMemeId by remember { mutableStateOf<Long?>(null) }
    var copyingMemeId by remember { mutableStateOf<Long?>(null) }

    var isScanning by remember { mutableStateOf(false) }
    var relaxedFromKind by remember { mutableStateOf<String?>(null) }
    var loadingFirstPage by remember { mutableStateOf(false) }
    val onStartIndex: () -> Unit = {
        if (!isScanning) {
            isScanning = true
            scope.launch {
                try {
                    // The scan and session belong to the coordinator: switching tabs only stops this wait.
                    val found = coordinator.scanAndStart()
                    if (found > 0) {
                        Toast.makeText(context, if (found == 1) "Found 1 new meme" else "Found $found new memes", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Toast.makeText(context, "Could not scan: ${e.message ?: e}", Toast.LENGTH_SHORT).show()
                } finally {
                    isScanning = false
                }
            }
        }
    }
    var hasCameraRollAccess by remember { mutableStateOf(CameraRollScanner.hasMediaAccess(context)) }
    val cameraRollPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val granted = CameraRollScanner.hasMediaAccess(context)
        hasCameraRollAccess = granted
        if (granted) {
            onStartIndex()
        } else {
            Toast.makeText(context, "Grant photo access to auto-organize memes from your camera roll", Toast.LENGTH_LONG).show()
        }
    }

    val uiPrefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    var bannerDismissed by remember { mutableStateOf(uiPrefs.getBoolean(BANNER_DISMISSED, false)) }
    var browseMode by remember { mutableStateOf(BrowseMode.fromKey(uiPrefs.getString(BROWSE_MODE, null))) }
    fun selectBrowseMode(mode: BrowseMode) {
        browseMode = mode
        uiPrefs.edit().putString(BROWSE_MODE, mode.key).apply()
    }
    val canvasState = remember { MemeCanvasState() }
    var folders by remember { mutableStateOf<List<MemeFolder>?>(null) }
    // Reaction folders run a search each, so they load once and are kept while the other kinds reload.
    var reactionFolders by remember { mutableStateOf<List<MemeFolder>?>(null) }
    // The folder the canvas was opened on; its filter holds until Back or another filter replaces it.
    var openFolder by remember { mutableStateOf<MemeFolder?>(null) }
    // A quote or format folder shows exactly these memes, in this order.
    var folderIds by remember { mutableStateOf<List<Long>?>(null) }
    // The search the memes on screen answer; changes only when they're published, unlike the query as typed.
    var publishedKey by remember { mutableStateOf<Any?>(null) }
    // Folders reload with the library while they're on screen; the canvas only needs them once, as morph targets.
    LaunchedEffect(revision, browseMode, active) {
        if (!active || browseMode == BrowseMode.Grid) return@LaunchedEffect
        if (browseMode == BrowseMode.Canvas && folders != null) return@LaunchedEffect
        // Each kind loads on its own and shows as soon as it's ready: formats and reactions wait for the search index,
        // which takes a while on a fresh launch, and one failing leaves the others.
        suspend fun show(kind: FolderKind, load: suspend () -> List<MemeFolder>) {
            val loaded = try {
                load()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            folders = folders.orEmpty().filter { it.kind != kind } + loaded
        }
        show(FolderKind.Tag) { loadMemeFolders(store) }
        show(FolderKind.Quote) { loadQuoteFolders(store) }
        show(FolderKind.Reaction) {
            reactionFolders ?: loadReactionFolders(store) { text -> engine.embedText(text) }.also { reactionFolders = it }
        }
        show(FolderKind.Format) { loadFormatFolders(store) }
    }
    // Typing a search on the folders shows its results on the canvas.
    LaunchedEffect(query) {
        if (query.isNotBlank() && browseMode == BrowseMode.Folders) selectBrowseMode(BrowseMode.Canvas)
    }
    // Another filter replacing the open folder's closes it.
    LaunchedEffect(selectedTag, query, folderIds, usedOnly) {
        // A search, tag or the Used chip replaces a folder's fixed list.
        if (folderIds != null && (query.isNotBlank() || selectedTag != null || usedOnly)) folderIds = null
        val folder = openFolder ?: return@LaunchedEffect
        val stillOpen = when (folder.kind) {
            FolderKind.Tag -> selectedTag?.let(TagMerge::normalizeTagLabel) == folder.key
            FolderKind.Quote, FolderKind.Reaction, FolderKind.Format -> folderIds == folder.ids
        }
        if (!stillOpen) openFolder = null
    }
    fun openFolderFilter(folder: MemeFolder) {
        usedOnly = false
        selectedTag = null
        query = ""
        folderIds = null
        when (folder.kind) {
            FolderKind.Tag -> selectedTag = folder.label
            FolderKind.Quote, FolderKind.Reaction, FolderKind.Format -> folderIds = folder.ids
        }
        openFolder = folder
    }
    fun leaveFolder() {
        val folder = openFolder ?: return
        openFolder = null
        when (folder.kind) {
            FolderKind.Tag -> selectedTag = null
            FolderKind.Quote, FolderKind.Reaction, FolderKind.Format -> folderIds = null
        }
    }
    // From the canvas, the folder's filter holds until its tiles have flown home (see the browser's onSettled):
    // reloading the whole library mid-flight stalls the morph.
    fun backToFolders() {
        if (browseMode == BrowseMode.Grid) leaveFolder()
        selectBrowseMode(BrowseMode.Folders)
    }
    // A tag folder being checked: its label and the memes to ask about, in order. The label and ids are saved, so
    // turning the phone mid-check brings the same queue back (FolderReview keeps its answers the same way).
    var checkLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var checkIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var checkMemes by remember { mutableStateOf<List<Meme>?>(null) }
    LaunchedEffect(checkIds) {
        val ids = checkIds
        if (ids == null) {
            checkMemes = null
        } else if (checkMemes?.map { it.id } != ids.toList()) {
            checkMemes = withContext(Dispatchers.IO) { ids.toList().mapNotNull { store.meme(it) } }
        }
    }
    fun startFolderCheck(folder: MemeFolder) {
        scope.launch {
            val toCheck = withContext(Dispatchers.IO) {
                val all = ArrayList<Meme>()
                var cursor: PageCursor? = null
                while (true) {
                    val page = store.page(256, cursor, null, folder.label)
                    all += page
                    if (page.size < 256) break
                    cursor = page.last().let { PageCursor(it.modifiedAt, it.id) }
                }
                // Answers you gave in this app are settled. Imported "confirmed" tags (an old collection, a
                // teaching pack) are someone else's say-so, so they're asked too, after the machine's guesses,
                // which come least certain first.
                all.mapNotNull { meme ->
                    val tag = meme.tags.firstOrNull { TagMerge.normalizeTagLabel(it.label) == folder.key } ?: return@mapNotNull null
                    if (TagMerge.isHumanTag(tag) && tag.provenance == "user") null else meme to tag
                }.sortedWith(compareBy({ TagMerge.isHumanTag(it.second) }, { it.second.score })).map { it.first }
            }
            if (toCheck.isEmpty()) {
                Toast.makeText(context, "Everything in “${folder.label}” is already checked", Toast.LENGTH_SHORT).show()
            } else {
                checkMemes = toCheck
                checkIds = toCheck.map { it.id }.toLongArray()
                checkLabel = folder.label
            }
        }
    }
    BackHandler(enabled = active && browseMode == BrowseMode.Canvas && openFolder != null) { backToFolders() }
    BackHandler(enabled = active && browseMode == BrowseMode.Folders) { selectBrowseMode(BrowseMode.Canvas) }

    // Multi-select state
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    // A selection is of what's on screen: a new search or filter starts over, so Delete never reaches hidden memes.
    LaunchedEffect(query, kindFilter, selectedTag) {
        selectionMode = false
        selectedIds = emptySet()
    }
    BackHandler(enabled = active && selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

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

    // Pre-warm the MobileCLIP-S2 text encoder and the resident search index on mount, side by side. The text tower
    // has its own native lock outside the indexing model lease, so it warms even while background work runs.
    // Searches typed before the index is built use the SQLite/FTS path instead of waiting for it.
    LaunchedEffect(Unit) {
        launch(Dispatchers.Default) { runCatching { engine.warmTextEncoder() } }
        withContext(Dispatchers.IO) { runCatching { store.warmSearchIndex() } }
    }
    LaunchedEffect(hasCameraRollAccess) {
        if (hasCameraRollAccess && !coordinator.progress.value.running) {
            withContext(Dispatchers.IO) {
                runCatching { coordinator.scanAndStart() }
            }
        }
    }

    // Refresh counts and labels on revision or mount without deserializing 1,433 float blobs or 256 full rows.
    LaunchedEffect(revision, active) {
        if (!active) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val count = store.count()
            val all = store.countAll()
            val folders = store.folders()
            val taught = store.taughtLabelNames()
            val tags = store.tagLabels()
            val used = store.copiedCount()
            withContext(Dispatchers.Main) {
                totalCount = count
                libraryCount = all
                folderCount = folders.size
                taughtLabels = taught
                libraryTags = tags
                usedCount = used
                if (used == 0 && usedOnly) usedOnly = false
            }
        }
    }

    // Handle tag search request from viewer or settings
    LaunchedEffect(tagSearchRequest) {
        if (!tagSearchRequest.isNullOrBlank()) {
            query = tagSearchRequest
            kindFilter = null
            selectedTag = null
            usedOnly = false
            onTagSearchApplied()
        }
    }

    // A load under way when a meme is deleted must not put it back.
    var locallyDeletedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val deletedIds by rememberUpdatedState(deletedMemeIds + locallyDeletedIds)
    LaunchedEffect(deletedMemeIds) {
        if (deletedMemeIds.isNotEmpty()) memes = memes.filterNot { it.id in deletedMemeIds }
    }

    var queryVector by remember { mutableStateOf<FloatArray?>(null) }
    var vectorText by remember { mutableStateOf("") }
    var resultKey by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    var resultGeneration by remember { mutableStateOf(0L) }
    var pageJob by remember { mutableStateOf<Job?>(null) }
    var searchCursor by remember { mutableStateOf<SearchCursor?>(null) }
    LaunchedEffect(userSearching, active) { onInteractionChange(active && userSearching) }
    DisposableEffect(Unit) { onDispose { onInteractionChange(false) } }

    // A library revision must not collapse the pages the user has already loaded.
    // Reuse unchanged row objects so a metadata write does not invalidate every cell.
    LaunchedEffect(query, kindFilter, selectedTag, usedOnly, folderIds, revision, active) {
        if (!active) return@LaunchedEffect
        resultGeneration++
        val generation = resultGeneration
        loadInFlight = true
        pageJob?.cancel()
        isLoadingMore = false
        val effectiveTag = when {
            usedOnly -> "__USED__"
            folderIds != null -> "__IDS__:${folderIds.hashCode()}"
            else -> selectedTag
        }
        val key = Triple(query.trim(), kindFilter, effectiveTag)
        userSearching = resultKey != key && key.first.isNotEmpty()
        // Same query, re-run by a library write (a tag edit, or the library-wide re-tag one starts).
        val isRefresh = resultKey == key
        val previous = if (isRefresh) memes else emptyList()
        val wanted = maxOf(PAGE_SIZE, previous.size)
        val previousById = previous.associateBy { it.id }
        // A library write re-runs this search; a search already widened past its media filter stays widened, so
        // the results don't flash empty on every write while indexing runs.
        if (resultKey != key) relaxedFromKind = null
        val searchKind = if (relaxedFromKind != null) null else key.second
        resultKey = key
        // Never blank `memes = emptyList()` on keystroke changes: keep existing tiles mounted with zero flicker
        // while the resident lexical pass (tens of ms on a Pixel 5a) updates.
        if (memes.isEmpty()) {
            loadingFirstPage = true
        }
        if (vectorText != key.first) {
            vectorText = key.first
            queryVector = null
        }
        isSearching = key.first.isNotEmpty()
        searchError = null
        fun publish(rows: List<Meme>, more: Boolean) {
            publishedKey = key
            val fresh = rows.filterNot { it.id in deletedIds }
            // A re-tag rewrites tags across the whole library for minutes, re-running this search every settle with
            // slightly different scores. Re-sorting each time kept the grid shuffling under the user's thumb, so a
            // refreshed search keeps its tiles in place: survivors stay put, vanished rows drop, newcomers append.
            // The plain library (no query) keeps its newest-first order so new memes still land on top.
            val ordered = if (isRefresh && key.first.isNotEmpty() && previous.isNotEmpty()) {
                val byId = fresh.associateBy { it.id }
                val kept = previous.mapNotNull { byId[it.id] }
                val keptIds = kept.mapTo(HashSet(kept.size)) { it.id }
                kept + fresh.filter { it.id !in keptIds }
            } else fresh
            memes = ordered.map { row -> previousById[row.id]?.takeIf { it == row } ?: row }
            hasMore = more
            loadingFirstPage = false
        }
        // Fetch `wanted + 1` in ONE call (`<= 256`) instead of clamping at `PAGE_SIZE = 90` and scanning the whole library twice!
        suspend fun searchWindow(vector: FloatArray?, effectiveKind: String?): Triple<List<Meme>, SearchCursor?, Boolean> = withContext(Dispatchers.IO) {
            val hits = ArrayList<SearchHit>(wanted + 1)
            var cursor: SearchCursor? = null
            while (hits.size <= wanted) {
                val page = store.search(key.first, vector, effectiveKind, selectedTag, minOf(256, wanted + 1 - hits.size), cursor)
                hits.addAll(page)
                if (page.isEmpty() || hits.size > wanted) break
                val last = page.last()
                cursor = SearchCursor(last.score, last.meme.modifiedAt, last.meme.id)
            }
            val more = hits.size > wanted
            val kept = if (more) hits.subList(0, wanted) else hits
            val keptCursor = kept.lastOrNull()?.let { SearchCursor(it.score, it.meme.modifiedAt, it.meme.id) }
            Triple(kept.map { it.meme }, keptCursor, more)
        }
        try {
            if (key.first.isEmpty()) {
                val (rows, more) = withContext(Dispatchers.IO) {
                    if (usedOnly) {
                        val usedRows = store.recentlyCopied(limit = minOf(256, wanted + 1), kind = key.second)
                        if (usedRows.size > wanted) usedRows.subList(0, wanted).toList() to true else usedRows to false
                    } else if (folderIds != null) {
                        folderIds.orEmpty().mapNotNull { store.meme(it) }.filter { key.second == null || it.kind == key.second } to false
                    } else {
                        val loaded = ArrayList<Meme>(wanted + 1)
                        var cursor: PageCursor? = null
                        while (loaded.size <= wanted) {
                            val page = store.page(minOf(256, wanted + 1 - loaded.size), cursor, key.second, selectedTag)
                            loaded.addAll(page)
                            if (page.isEmpty() || loaded.size > wanted) break
                            val last = page.last()
                            cursor = PageCursor(last.modifiedAt, last.id)
                        }
                        if (loaded.size > wanted) loaded.subList(0, wanted).toList() to true else loaded to false
                    }
                }
                publish(rows, more)
            } else {
                val distilled = SocialReplyIntent.distill(key.first)
                val cachedVec = queryVector ?: engine.cachedTextVector(distilled.embedText)
                // Lexical + social-slang pass from the resident RAM index first (tens of ms), then the hybrid pass.
                // With the query vector already known (a refresh, or a query typed before) go straight to hybrid:
                // publishing the lexical order first and then the hybrid order flipped the grid on every re-run.
                var currentResults = emptyList<Meme>()
                if (cachedVec == null) {
                    val lexical = searchWindow(null, searchKind)
                    searchCursor = lexical.second
                    publish(lexical.first, lexical.third)
                    currentResults = lexical.first
                }
                val vector = cachedVec ?: try {
                    // Wait for a brief thumb pause so typing a 12-letter query doesn't queue 10 neural CPU forwards.
                    delay(150)
                    engine.embedText(distilled.embedText)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    searchError = "Text matches shown; semantic search unavailable: ${failure.message}"
                    null
                }
                queryVector = vector
                if (vector != null) {
                    val hybrid = searchWindow(vector, searchKind)
                    searchCursor = hybrid.second
                    publish(hybrid.first, hybrid.third)
                    currentResults = hybrid.first
                }
                if (searchKind != null && currentResults.isEmpty()) {
                    val relaxed = searchWindow(vector, null)
                    if (relaxed.first.isNotEmpty()) {
                        relaxedFromKind = key.second
                        searchCursor = relaxed.second
                        publish(relaxed.first, relaxed.third)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            searchError = failure.message ?: "Library retrieval failed"
        } finally {
            if (generation == resultGeneration) {
                isSearching = false
                userSearching = false
                loadingFirstPage = false
                loadInFlight = false
            }
        }
    }

    val onLoadMore = {
        if (!isLoadingMore && !isSearching && hasMore && memes.isNotEmpty()) {
            val generation = resultGeneration
            val key = Triple(query.trim(), kindFilter, selectedTag)
            val isRelaxed = relaxedFromKind != null
            val effectiveKind = if (isRelaxed) null else key.second
            val cursor = memes.last().let { PageCursor(it.modifiedAt, it.id) }
            val after = searchCursor
            val vector = queryVector
            isLoadingMore = true
            pageJob = scope.launch {
                try {
                    val page = withContext(Dispatchers.IO) {
                        if (key.first.isEmpty()) store.page(PAGE_SIZE, cursor, key.second, key.third) to null
                        else {
                            val hits = store.search(key.first, vector, effectiveKind, key.third, PAGE_SIZE, after)
                            hits.map { it.meme } to hits.lastOrNull()?.let { SearchCursor(it.score, it.meme.modifiedAt, it.meme.id) }
                        }
                    }
                    val rows = page.first
                    if (generation == resultGeneration && key == Triple(query.trim(), kindFilter, selectedTag)) {
                        searchCursor = page.second
                        val existing = memes.mapTo(HashSet()) { it.id }
                        memes = memes + rows.filter { it.id !in existing && it.id !in deletedIds }
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
                            .clickable {
                                bannerDismissed = true
                                uiPrefs.edit().putBoolean(BANNER_DISMISSED, true).apply()
                            }
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
                onValueChange = {
                    query = it
                    if (it.isNotBlank() && usedOnly) usedOnly = false
                },
                placeholder = {
                    Text(
                        text = if (totalCount > 0) "Search vibe, slang, quote, or paste post…" else "Search memes…",
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
                    } else {
                        IconButton(onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                                .orEmpty()
                            if (clip.isNotBlank()) {
                                usedOnly = false
                                selectedTag = null
                                query = clip.take(400)
                            } else {
                                Toast.makeText(context, "Copy a post or reply text first to match memes", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("📋", fontSize = 14.sp)
                        }
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

        // Quick Filter Chips (Media kinds & Tags). A library carries hundreds of taught labels and thousands of tags:
        // a lazy row composes only the chips on screen, and the tag filter runs once per data change, not per keystroke.
        val libraryTagChips = remember(libraryTags, taughtLabels) {
            val taught = taughtLabels.toHashSet()
            libraryTags.asSequence().filter { it !in taught }.take(20).toList()
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Battle-tested replies Master actually copies & fires online
            if (usedCount > 0 || usedOnly) {
                item(key = "used") {
                    FilterChip(
                        label = if (usedCount > 0) "⚡ Used ($usedCount)" else "⚡ Used",
                        active = usedOnly,
                        onClick = {
                            usedOnly = !usedOnly
                            if (usedOnly) {
                                selectedTag = null
                                query = ""
                            }
                        },
                    )
                }
            }

            // Media kind chips
            item(key = "kind:image") {
                FilterChip(
                    label = "▦ Images",
                    active = kindFilter == "image",
                    onClick = {
                        kindFilter = if (kindFilter == "image") null else "image"
                    },
                )
            }
            item(key = "kind:video") {
                FilterChip(
                    label = "▶ Videos",
                    active = kindFilter == "video",
                    onClick = {
                        kindFilter = if (kindFilter == "video") null else "video"
                    },
                )
            }
            if (taughtLabels.isNotEmpty() || libraryTags.isNotEmpty()) {
                item(key = "divider") {
                    Box(
                        modifier = Modifier
                            .height(18.dp)
                            .width(1.dp)
                            .background(MemegetColors.Border),
                    )
                }
            }

            // Taught concepts first (starred)
            items(taughtLabels, key = { "taught:$it" }) { label ->
                FilterChip(
                    label = "★ $label",
                    active = selectedTag == label || query.trim() == label,
                    onClick = {
                        if (selectedTag == label) {
                            selectedTag = null
                        } else {
                            usedOnly = false
                            selectedTag = label
                            query = ""
                        }
                    },
                )
            }

            // Library tags
            items(libraryTagChips, key = { "tag:$it" }) { label ->
                FilterChip(
                    label = label,
                    active = selectedTag == label || query.trim() == label,
                    onClick = {
                        if (selectedTag == label) {
                            selectedTag = null
                        } else {
                            usedOnly = false
                            selectedTag = label
                            query = ""
                        }
                    },
                )
            }
        }

        // Index Coordinator Progress Card (when indexing is running). Hidden while a query is typed: background work
        // pauses for search anyway, and the card popping in and out shoved the results grid a row up or down.
        AnimatedVisibility(visible = indexProgress.running && query.isBlank()) {
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
                            text = indexProgress.headline(),
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
                            selectedIds = if (selectedIds.size == memes.size) emptySet() else memes.mapTo(HashSet()) { it.id }
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
                            scope.launch {
                                try {
                                    files.share(context, selectedMemes)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    val reason = e.message ?: e.toString()
                                    Toast.makeText(context, "Could not share: $reason", Toast.LENGTH_SHORT).show()
                                }
                            }
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

        val quickCopy: (Meme) -> Unit = { meme ->
            if (copyingMemeId == null) {
                copyingMemeId = meme.id
                // The search this copy answers, taken now: the copy can take a while (a WebM is converted), and a
                // search typed meanwhile must not be taught this meme.
                val copiedFrom = query.trim()
                scope.launch {
                    try {
                        files.copyToClipboard(meme.uri, meme.name)
                        runCatching { store.recordCopy(meme.id, copiedFrom) }
                        copiedMemeId = meme.id
                        Toast.makeText(
                            context,
                            if (meme.kind == "video") "✓ Video copied — paste into reply" else "✓ Meme copied — paste into reply",
                            Toast.LENGTH_SHORT,
                        ).show()
                        delay(1400)
                        if (copiedMemeId == meme.id) copiedMemeId = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Toast.makeText(context, "Could not copy: ${failure.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        if (copyingMemeId == meme.id) copyingMemeId = null
                    }
                }
            }
        }
        val switcher: @Composable () -> Unit = {
            BrowseModeSwitcher(
                mode = browseMode,
                onSelect = { mode ->
                    selectionMode = false
                    selectedIds = emptySet()
                    // Folders from a folder's canvas goes back into that folder's stack.
                    if (mode == BrowseMode.Folders && openFolder != null) backToFolders() else selectBrowseMode(mode)
                },
            )
        }
        // The grid also carries the onboarding and empty-result states for every mode.
        val useGrid = browseMode == BrowseMode.Grid || (memes.isEmpty() && (browseMode == BrowseMode.Canvas || libraryCount == 0))
        val showSwitcher = !selectionMode && libraryCount > 0

        // What the viewer was opened for: the search, or the line of an open quote folder. The viewer starts a video
        // where it's said, and a copy made there is remembered against it.
        val viewerText = openFolder?.takeIf { it.kind == FolderKind.Quote }?.query ?: query.trim()

        // Meme Grid
        if (useGrid) MemeGrid(
            memes = memes,
            selectionMode = selectionMode,
            selectedIds = selectedIds,
            activeMemeId = indexProgress.memeId.takeIf { indexProgress.running },
            copiedMemeId = copiedMemeId,
            copyingMemeId = copyingMemeId,
            onQuickCopy = quickCopy,
            bottomBar = switcher.takeIf { showSwitcher },
            onItemClick = { meme ->
                if (selectionMode) {
                    selectedIds = if (selectedIds.contains(meme.id)) {
                        selectedIds - meme.id
                    } else {
                        selectedIds + meme.id
                    }
                } else {
                    // Closing the viewer must not bring the search keyboard back over the results.
                    focusManager.clearFocus()
                    onOpenViewer(meme, memes, viewerText)
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
                if (query.trim().isNotEmpty()) {
                    if (memes.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val q = query.trim()
                            val countStr = if (hasMore) "${memes.size}+ matches" else "${memes.size} match${if (memes.size == 1) "" else "es"}"
                            Text(
                                text = "$countStr for “$q”",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MemegetColors.TextSecondary,
                            )
                            if (relaxedFromKind != null) {
                                val kindLabel = if (relaxedFromKind == "video") "videos" else "images"
                                Text(
                                    text = "No $kindLabel matched “$q” — showing all memes",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MemegetColors.VoltMuted,
                                )
                            }
                        }
                    }
                } else if (libraryCount > 0 && !indexProgress.running) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val waiting = libraryCount - totalCount
                        Text(
                            text = "$libraryCount meme${if (libraryCount == 1) "" else "s"}${if (waiting > 0) " · $waiting to index" else ""}${if (folderCount > 0) " · $folderCount folder${if (folderCount > 1) "s" else ""}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MemegetColors.TextSecondary,
                        )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!hasCameraRollAccess) {
                                    OutlinedButton(
                                        onClick = { cameraRollPermissionLauncher.launch(CameraRollScanner.requiredPermissions()) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("＋ Camera Roll", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (hasCameraRollAccess || folderCount > 0) onStartIndex()
                                        else cameraRollPermissionLauncher.launch(CameraRollScanner.requiredPermissions())
                                    },
                                    enabled = !isScanning,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MemegetColors.Volt,
                                        contentColor = MemegetColors.Background,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (isScanning) "Scanning…" else "⟳ Scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                    }
                }
            },
            emptyContent = {
                if (libraryCount == 0 && !indexProgress.running) {
                    // Onboarding 1-2-3 guide
                    OnboardingCard(
                        onScanCameraRoll = {
                            if (hasCameraRollAccess) onStartIndex()
                            else cameraRollPermissionLauncher.launch(CameraRollScanner.requiredPermissions())
                        },
                        onLink = { linkFolderLauncher.launch(null) },
                        onIndex = onStartIndex,
                        hasCameraRollAccess = hasCameraRollAccess,
                        folderCount = folderCount,
                        isScanning = isScanning,
                    )
                } else if (memes.isEmpty() && query.isNotEmpty() && isSearching) {
                    // Until the first results arrive; "No memes matched" would be a false answer.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        CircularProgressIndicator(color = MemegetColors.Volt, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Searching for “$query”…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MemegetColors.TextSecondary,
                        )
                    }
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
                } else if (memes.isEmpty() && query.isEmpty() && kindFilter != null && !loadingFirstPage) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = if (kindFilter == "video") "▶ No videos here" else "▦ No images here",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MemegetColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the chip again to show everything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MemegetColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
        )
        else MemeBrowser(
            mode = browseMode,
            onModeChange = ::selectBrowseMode,
            memes = memes,
            memesKey = publishedKey,
            canvasState = canvasState,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore,
            onOpen = { meme ->
                focusManager.clearFocus()
                onOpenViewer(meme, memes, viewerText)
            },
            onLongPress = quickCopy,
            folders = folders,
            openFolderKey = openFolder?.key,
            onOpenFolder = { folder ->
                focusManager.clearFocus()
                openFolderFilter(folder)
            },
            onBackToFolders = ::backToFolders,
            onCheckFolder = openFolder?.takeIf { it.kind == FolderKind.Tag }?.let { folder -> { startFolderCheck(folder) } },
            onSettled = { settled -> if (settled == BrowseMode.Folders) leaveFolder() },
            bottomBar = { if (showSwitcher) switcher() },
            modifier = Modifier.weight(1f),
        )
    }

    val label = checkLabel
    val toCheck = checkMemes
    if (label != null && toCheck != null) {
        val labelKey = TagMerge.normalizeTagLabel(label)
        FolderReview(
            label = label,
            memes = toCheck,
            onClose = { answers ->
                checkLabel = null
                checkIds = null
                if (answers.isNotEmpty()) scope.launch {
                    // In order, each finished before the next, and all before the re-tag that learns from them.
                    val failed = withContext(NonCancellable) {
                        answers.count { (meme, right) ->
                            val category = meme.tags.firstOrNull { TagMerge.normalizeTagLabel(it.label) == labelKey }?.category ?: "topic"
                            try {
                                onTeach(meme.id, label, category, right)
                                false
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                true
                            }
                        }
                    }
                    val wrong = answers.count { !it.second }
                    Toast.makeText(
                        context,
                        if (failed > 0) "$failed of ${answers.size} answers couldn't be saved"
                        else if (wrong > 0) "Took $wrong out of “$label”"
                        else "Saved ${answers.size} answers",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (failed < answers.size) onTaught()
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
            onConfirmDelete = {
                showBatchDeleteDialog = false
                val targets = memes.filter { it.id in selectedIds }
                scope.launch {
                    try {
                        val filesDeleted = files.deleteMemes(targets)
                        val gone = targets.mapTo(HashSet()) { it.id }
                        locallyDeletedIds = locallyDeletedIds + gone
                        memes = memes.filterNot { it.id in gone }
                        val removed = if (targets.size == 1) "Deleted 1 meme" else "Deleted ${targets.size} memes"
                        // A file already gone, or one in a folder the app can no longer write, isn't deleted.
                        val kept = targets.size - filesDeleted
                        Toast.makeText(
                            context,
                            if (kept > 0) "$removed ($kept file${if (kept == 1) "" else "s"} could not be deleted)" else removed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        selectionMode = false
                        selectedIds = emptySet()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val reason = e.message ?: e.toString()
                        selectionMode = false
                        selectedIds = emptySet()
                        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
                    }
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
    onScanCameraRoll: () -> Unit,
    onLink: () -> Unit,
    onIndex: () -> Unit,
    hasCameraRollAccess: Boolean,
    folderCount: Int,
    isScanning: Boolean = false,
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
            text = "Search your memes instantly",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MemegetColors.TextPrimary,
        )
        Text(
            text = "Memeget scans your camera roll locally on-device, separates memes from personal photos & receipts, and indexes every format, quote, and reaction for instant search.",
            style = MaterialTheme.typography.bodySmall,
            color = MemegetColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Button(
            onClick = if (hasCameraRollAccess || folderCount > 0) onIndex else onScanCameraRoll,
            enabled = !isScanning,
            colors = ButtonDefaults.buttonColors(
                containerColor = MemegetColors.Volt,
                contentColor = MemegetColors.Background,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when {
                    isScanning -> "Scanning Camera Roll…"
                    hasCameraRollAccess -> "Scan Camera Roll Now"
                    else -> "Scan Camera Roll (100% Local)"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        TextButton(onClick = onLink) {
            Text(
                text = if (folderCount > 0) "Linked folders: $folderCount · Add another" else "Or link a specific meme folder",
                style = MaterialTheme.typography.labelSmall,
                color = MemegetColors.TextMuted,
            )
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

/**
 * [revision] once it settles: a change is passed on [settleMillis] after it, so a burst of writes becomes one
 * reload, and only once [busy] is false, so the load the last change started finishes before the next begins.
 */
@Composable
private fun rememberSettledRevision(revision: StateFlow<Long>, settleMillis: Long = 750L, busy: () -> Boolean): State<Long> {
    val settled = remember(revision) { mutableLongStateOf(revision.value) }
    LaunchedEffect(revision) {
        while (true) {
            revision.first { it != settled.longValue }
            delay(settleMillis)
            snapshotFlow { busy() }.first { !it }
            settled.longValue = revision.value
        }
    }
    return settled
}
