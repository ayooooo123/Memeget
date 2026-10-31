package com.memeget.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.memeget.nativeapp.editor.RemixStudio

internal enum class AppTab {
    Library,
    Settings,
}

@Composable
internal fun MemegetApp(
    store: LibraryStore,
    files: LibraryFiles,
    engine: InferenceEngine,
    knowledge: LocalKnowledge,
    cloud: CloudAssistant,
    coordinator: IndexCoordinator,
    backups: FolderBackupSync,
    incomingSearchQuery: String? = null,
    onIncomingSearchConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by remember { mutableStateOf(AppTab.Library) }
    var activeViewerMeme by remember { mutableStateOf<Meme?>(null) }
    // The list the viewer opened from: a sideways swipe moves through it.
    var viewerPages by remember { mutableStateOf<List<Meme>>(emptyList()) }
    // The search or quoted line the viewer was opened for.
    var viewerSearchText by remember { mutableStateOf("") }
    var activeRemixMeme by remember { mutableStateOf<Meme?>(null) }
    // Set by a tag tap in the viewer/Settings or by highlighting/sharing a social post from another app.
    var tagSearchRequest by remember { mutableStateOf<String?>(null) }
    var deletedMemeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(incomingSearchQuery) {
        if (!incomingSearchQuery.isNullOrBlank()) {
            activeViewerMeme = null
            activeRemixMeme = null
            currentTab = AppTab.Library
            tagSearchRequest = incomingSearchQuery
            onIncomingSearchConsumed()
        }
    }
    fun selectTab(tab: AppTab) {
        // A search field focused on one tab must not bring its keyboard back on another.
        focusManager.clearFocus()
        currentTab = tab
    }
    // Back from Label or Settings returns to the library instead of leaving the app.
    BackHandler(enabled = currentTab != AppTab.Library) { selectTab(AppTab.Library) }
    var searching by remember { mutableStateOf(false) }
    // Answers in the Label tab spread to look-alikes on a re-tag once the tab is left, and teachings in the viewer
    // once it closes, as the original app applies a teaching shortly after it. The app scope owns each save, so
    // leaving mid-save neither cancels the answer nor misses that re-tag.
    var labelsChanged by remember { mutableStateOf(false) }
    val viewerOpen = activeViewerMeme != null
    LaunchedEffect(labelsChanged, viewerOpen) {
        if (!viewerOpen && labelsChanged) {
            labelsChanged = false
            scope.launch { coordinator.retag() }
        }
    }
    // The editor pauses background work like a search does, so renders and previews get the CPU.
    LaunchedEffect(searching, activeRemixMeme) {
        coordinator.setInteractive(searching || activeRemixMeme != null)
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.setInteractive(false) }
    }

    // Resume pending indexing after an activity cycle. The session belongs to the coordinator, so a configuration
    // change or closing the app's screen stops neither it nor a model download it started.
    LaunchedEffect(Unit) {
        runCatching { knowledge.loadPersistedVerifierHead() }
        coordinator.resumePending()
    }

    MemegetTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MemegetColors.Background,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Lets imePadding below add only the part of the keyboard the bottom bar doesn't cover.
                    .consumeWindowInsets(innerPadding),
            ) {
                // The library stays composed while Label or Settings shows, so its search, filters, loaded pages and
                // scroll position are still there on return. Hidden, it is measured but not placed: not drawn, not
                // touchable, and its Back handling is off.
                val libraryActive = currentTab == AppTab.Library
                Box(modifier = Modifier.fillMaxSize().placedOnlyWhen(libraryActive)) {
                    LibraryScreen(
                        store = store,
                        files = files,
                        engine = engine,
                        knowledge = knowledge,
                        coordinator = coordinator,
                        onOpenViewer = { meme, pages, searchText ->
                            viewerPages = pages
                            viewerSearchText = searchText
                            activeViewerMeme = meme
                        },
                        onOpenSettings = { selectTab(AppTab.Settings) },
                        active = libraryActive,
                        tagSearchRequest = tagSearchRequest,
                        onTagSearchApplied = { tagSearchRequest = null },
                        deletedMemeIds = deletedMemeIds,
                        onInteractionChange = { searching = it },
                        onTeach = { memeId, label, category, right ->
                            cloud.recordTeaching(memeId, label, category, right, emptyList())
                            runCatching { knowledge.recordVerifierTeaching(memeId) }
                        },
                        onTaught = { labelsChanged = true },
                    )
                }
                when (currentTab) {
                    AppTab.Library -> Unit
                    AppTab.Settings -> {
                        SettingsScreen(
                            store = store,
                            files = files,
                            engine = engine,
                            knowledge = knowledge,
                            cloud = cloud,
                            coordinator = coordinator,
                            onBack = { selectTab(AppTab.Library) },
                            onSearchTag = { tag ->
                                tagSearchRequest = tag
                                selectTab(AppTab.Library)
                            },
                            lastBackupAt = backups.lastBackupAt,
                        )
                    }
                }
            }
        }

        // Active Meme Fullscreen Viewer Overlay
        activeViewerMeme?.let { meme ->
            ViewerModal(
                meme = meme,
                pages = viewerPages,
                searchText = viewerSearchText,
                files = files,
                store = store,
                cloud = cloud,
                coordinator = coordinator,
                onClose = { activeViewerMeme = null },
                onRemix = { m ->
                    activeViewerMeme = null
                    activeRemixMeme = m
                },
                onSearchTag = { tag ->
                    activeViewerMeme = null
                    tagSearchRequest = tag
                    selectTab(AppTab.Library)
                },
                onNavigateToMeme = { nextMeme ->
                    activeViewerMeme = nextMeme
                },
                onMemeDeleted = { id ->
                    activeViewerMeme = null
                    deletedMemeIds = deletedMemeIds + id
                },
                onTaught = {
                    labelsChanged = true
                    scope.launch { runCatching { knowledge.recordVerifierTeaching(meme.id) } }
                },
            )
        }

        // Keep library state while isolating editor input and accessibility.
        activeRemixMeme?.let { meme ->
            Dialog(
                onDismissRequest = { activeRemixMeme = null },
                properties = DialogProperties(
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                RemixStudio(
                    meme = meme,
                    engine = engine,
                    files = files,
                    onClose = { activeRemixMeme = null },
                    onSaved = { newMemeId ->
                        activeRemixMeme = null
                        scope.launch {
                            snackbarHostState.showSnackbar("Remix saved to library")
                            val savedMeme = store.meme(newMemeId)
                            if (savedMeme != null) {
                                viewerPages = listOf(savedMeme)
                                activeViewerMeme = savedMeme
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Measures the content but places it only when [visible]; unplaced, it is neither drawn nor hit-tested. */
internal fun Modifier.placedOnlyWhen(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.place(0, 0)
    }
}

// Dead-simple single-screen navigation: TabItem & bottom bar bloat stripped clean.
