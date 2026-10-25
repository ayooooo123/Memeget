package com.memeget.nativeapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
internal fun MemeGrid(
    memes: List<Meme>,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    activeMemeId: Long?,
    onItemClick: (Meme) -> Unit,
    onItemLongClick: (Meme) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    modifier: Modifier = Modifier,
    headerContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    copiedMemeId: Long? = null,
    copyingMemeId: Long? = null,
    onQuickCopy: ((Meme) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val showTopButton by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 30
        }
    }

    // Detect when user scrolls near the end to trigger infinite keyset load
    val shouldLoadMore by remember(hasMore, isLoadingMore, memes.size) {
        derivedStateOf {
            if (!hasMore || isLoadingMore || memes.isEmpty()) {
                false
            } else {
                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = gridState.layoutInfo.totalItemsCount
                lastVisibleIndex >= totalItems - 12
            }
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    if (memes.isEmpty() && !isLoadingMore && emptyContent != null) {
        Column(modifier = modifier.fillMaxSize()) {
            headerContent?.invoke()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                emptyContent()
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
            if (headerContent != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    headerContent()
                }
            }

            items(
                items = memes,
                key = { it.id },
            ) { meme ->
                val isSelected = selectedIds.contains(meme.id)
                MemeGridCell(
                    meme = meme,
                    selectionMode = selectionMode,
                    isSelected = isSelected,
                    analyzing = activeMemeId == meme.id,
                    justCopied = copiedMemeId == meme.id,
                    copying = copyingMemeId == meme.id,
                    onClick = { onItemClick(meme) },
                    onLongClick = { onItemLongClick(meme) },
                    onQuickCopy = onQuickCopy?.let { cb -> { cb(meme) } },
                    // The semantic pass reorders text matches cleanly: tiles glide to their new places
                    // instead of jumping, and a deleted meme fades out.
                    modifier = Modifier.animateItem(),
                )
            }

            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MemegetColors.Volt,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                        )
                    }
                }
            }
        }
            AnimatedVisibility(
                visible = !selectionMode && showTopButton,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MemegetColors.SurfaceBright)
                        .border(1.dp, MemegetColors.Border, RoundedCornerShape(20.dp))
                        .clickable {
                            coroutineScope.launch {
                                gridState.animateScrollToItem(0)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↑ Top",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MemegetColors.Volt,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemeGridCell(
    meme: Meme,
    selectionMode: Boolean,
    isSelected: Boolean,
    analyzing: Boolean,
    justCopied: Boolean = false,
    copying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onQuickCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageSource = remember(meme.thumbUri, meme.uri) {
        if (meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MemegetColors.SurfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageSource)
                .crossfade(150)
                .build(),
            contentDescription = meme.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MemegetColors.Border,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            error = {
                // Fallback stub for missing/undecodable posters
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MemegetColors.SurfaceVariant)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (meme.kind == "video") "🎞" else "🖼",
                            fontSize = 20.sp,
                        )
                        Text(
                            text = meme.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MemegetColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
        )

        // Video badge
        if (meme.kind == "video") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        val analysisStatus = when {
            analyzing -> "Analyzing"
            meme.indexError.isNotBlank() -> "Analysis failed"
            meme.pending -> "Queued"
            else -> null
        }
        if (analysisStatus != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (analyzing) {
                    CircularProgressIndicator(
                        color = MemegetColors.Volt,
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
                Text(
                    text = analysisStatus,
                    color = if (!analyzing && meme.indexError.isNotBlank()) MaterialTheme.colorScheme.error else Color.White,
                    fontSize = 9.sp,
                )
            }
        }

        // 1-tap social-reply Copy pill in top-right when browsing/searching outside multi-select
        if (!selectionMode && onQuickCopy != null && !meme.pending) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (justCopied) MemegetColors.Volt else Color.Black.copy(alpha = 0.68f))
                    .border(
                        width = 0.8.dp,
                        color = if (justCopied) MemegetColors.Volt else Color.White.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                    .clickable(enabled = !copying) { onQuickCopy() },
                contentAlignment = Alignment.Center,
            ) {
                if (copying) {
                    CircularProgressIndicator(
                        color = MemegetColors.Volt,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                } else {
                    Text(
                        text = if (justCopied) "✓" else "⧉",
                        color = if (justCopied) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Multi-select overlay and check circle
        if (selectionMode) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MemegetColors.Volt.copy(alpha = 0.28f)),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MemegetColors.Volt else Color.Black.copy(alpha = 0.5f)
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) MemegetColors.Volt else Color.White.copy(alpha = 0.8f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Text(
                        text = "✓",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}
