package com.memeget.nativeapp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by remember { mutableStateOf(AppTab.Library) }
    var activeViewerMeme by remember { mutableStateOf<Meme?>(null) }
    var activeRemixMeme by remember { mutableStateOf<Meme?>(null) }
    var tagSearchRequest by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(searching, activeRemixMeme) {
        coordinator.setInteractive(searching || activeRemixMeme != null)
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.setInteractive(false) }
    }

    // Resume pending indexing if pending memes exist after activity cycle
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pendingList = store.pending(limit = 1)
            if (pendingList.isNotEmpty()) {
                coordinator.runPending()
            }
        }
    }

    MemegetTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // Bottom Tab Navigation Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MemegetColors.Surface)
                        .navigationBarsPadding(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MemegetColors.Border),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabItem(
                            label = "Library",
                            glyph = "▦",
                            selected = currentTab == AppTab.Library,
                            onClick = {
                                currentTab = AppTab.Library
                                tagSearchRequest = null
                            },
                        )

                        TabItem(
                            label = "Settings",
                            glyph = "⚙",
                            selected = currentTab == AppTab.Settings,
                            onClick = { currentTab = AppTab.Settings },
                        )
                    }
                }
            },
            containerColor = MemegetColors.Background,
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tab_transition",
                ) { tab ->
                    when (tab) {
                        AppTab.Library -> {
                            LibraryScreen(
                                store = store,
                                files = files,
                                engine = engine,
                                knowledge = knowledge,
                                coordinator = coordinator,
                                onOpenViewer = { meme -> activeViewerMeme = meme },
                                onOpenRemix = { meme -> activeRemixMeme = meme },
                                onOpenSettings = { currentTab = AppTab.Settings },
                                tagSearchRequest = tagSearchRequest,
                                onInteractionChange = { searching = it },
                            )
                        }
                        AppTab.Settings -> {
                            SettingsScreen(
                                store = store,
                                files = files,
                                engine = engine,
                                knowledge = knowledge,
                                cloud = cloud,
                                coordinator = coordinator,
                                onBack = { currentTab = AppTab.Library },
                                onSearchTag = { tag ->
                                    tagSearchRequest = tag
                                    currentTab = AppTab.Library
                                },
                            )
                        }
                    }
                }
            }
        }

        // Active Meme Fullscreen Viewer Overlay
        activeViewerMeme?.let { meme ->
            ViewerModal(
                meme = meme,
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
                    currentTab = AppTab.Library
                },
                onNavigateToMeme = { nextMeme ->
                    activeViewerMeme = nextMeme
                },
                onMemeDeleted = {
                    activeViewerMeme = null
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
                                activeViewerMeme = savedMeme
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    glyph: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = glyph,
            fontSize = 18.sp,
            color = if (selected) MemegetColors.Volt else MemegetColors.TextMuted,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MemegetColors.Volt else MemegetColors.TextMuted,
        )
    }
}
