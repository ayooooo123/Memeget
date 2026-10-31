package com.memeget.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.memeget.nativeapp.data.SearchText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Height of the dock that stays on screen while the sheet is closed: the title pill and the copy button. */
private val DOCK_HEIGHT = 84.dp
/** The open sheet covers this much of the screen; the meme shrinks into the space above it. */
private const val SHEET_FRACTION = 0.78f
/** A fling faster than this (px/s) opens, closes or dismisses regardless of how far it travelled. */
private const val FLING_VELOCITY = 1200f

/**
 * The viewer's frame: the current meme on a card over a blurred copy of itself, a swipe to the next or previous
 * meme, and everything else about it behind a sheet. Closed, the sheet is a dock with the meme's title and a copy
 * button; swipe up on the meme or the dock, or tap the title, to open it. Swipe down on the meme to close the viewer.
 */
@Composable
internal fun SwipeViewerFrame(
    pages: List<Meme>,
    pagerState: PagerState,
    current: Meme,
    title: String,
    subtitle: String,
    copying: Boolean,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    topActions: @Composable () -> Unit,
    sheetContent: @Composable () -> Unit,
    /** The search or quoted line the viewer was opened for: a video starts near where it's said. */
    searchText: String = "",
    /** Each change closes the sheet. */
    closeSheetSignal: Int = 0,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenHeight = constraints.maxHeight.toFloat()
        val sheetHeight = maxHeight * SHEET_FRACTION
        // How far the sheet sits below its open position: `closedOffset` closed, 0 open.
        val closedOffset = with(density) { (sheetHeight - DOCK_HEIGHT).toPx() }.coerceAtLeast(1f)
        var sheetOffset by remember { mutableFloatStateOf(closedOffset) }
        var sheetOpen by remember { mutableStateOf(false) }
        // A resize (rotation, split screen) keeps a closed sheet closed.
        LaunchedEffect(closedOffset) { if (!sheetOpen) sheetOffset = closedOffset }
        // Pull-down on the meme before letting go closes the viewer.
        var dismissOffset by remember { mutableFloatStateOf(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        fun openness() = 1f - (sheetOffset / closedOffset).coerceIn(0f, 1f)

        fun settleSheet(open: Boolean, velocity: Float = 0f) {
            sheetOpen = open
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(sheetOffset, if (open) 0f else closedOffset, velocity, spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    sheetOffset = value
                }
            }
        }
        fun settleAfterDrag(velocity: Float) {
            when {
                velocity < -FLING_VELOCITY -> settleSheet(true, velocity)
                velocity > FLING_VELOCITY -> settleSheet(false, velocity)
                else -> settleSheet(sheetOffset < closedOffset / 2f, velocity)
            }
        }

        // One vertical drag drives both the sheet and the pull-down: up raises the sheet; down lowers it, and once it
        // is closed, pulls the meme down to dismiss.
        val dragState = rememberDraggableState { delta ->
            settleJob?.cancel()
            if (dismissOffset > 0f || (delta > 0f && sheetOffset >= closedOffset)) {
                dismissOffset = (dismissOffset + delta).coerceAtLeast(0f)
            } else {
                sheetOffset = (sheetOffset + delta).coerceIn(0f, closedOffset)
            }
        }
        fun onDragStopped(velocity: Float) {
            if (dismissOffset > 0f) {
                if (dismissOffset > screenHeight * 0.16f || velocity > FLING_VELOCITY) {
                    onClose()
                } else {
                    settleJob = scope.launch { animate(dismissOffset, 0f, velocity) { value, _ -> dismissOffset = value } }
                }
            } else {
                settleAfterDrag(velocity)
            }
        }
        // Scrolling the sheet's content past its top drags the sheet itself.
        val sheetScroll = remember(closedOffset) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || sheetOffset <= 0f) return Offset.Zero
                    val before = sheetOffset
                    sheetOffset = (sheetOffset + available.y).coerceAtLeast(0f)
                    return Offset(0f, sheetOffset - before)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    val before = sheetOffset
                    sheetOffset = (sheetOffset + available.y).coerceAtMost(closedOffset)
                    return Offset(0f, sheetOffset - before)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheetOffset <= 0f || sheetOffset >= closedOffset) return Velocity.Zero
                    settleAfterDrag(available.y)
                    return available
                }
            }
        }
        BackHandler(enabled = sheetOpen) { settleSheet(false) }
        LaunchedEffect(closeSheetSignal) { if (closeSheetSignal > 0) settleSheet(false) }

        Backdrop(
            current,
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - (dismissOffset / screenHeight * 2f).coerceIn(0f, 0.85f) },
        )

        // The memes. Opening the sheet shrinks the card into the space above it.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = DOCK_HEIGHT + 8.dp)
                .graphicsLayer {
                    val open = openness()
                    val scale = 1f - 0.62f * open
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationY = dismissOffset
                    alpha = 1f - (dismissOffset / screenHeight).coerceIn(0f, 0.5f)
                }
                .draggable(dragState, Orientation.Vertical, onDragStopped = { onDragStopped(it) }),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { page -> pages.getOrNull(page)?.id ?: page },
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                userScrollEnabled = !sheetOpen,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                pages.getOrNull(page)?.let { meme -> MediaCard(meme, active = page == pagerState.settledPage, searchText = searchText) }
            }
            if (sheetOpen) {
                // Tapping the meme while the sheet is open closes the sheet.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settleSheet(false) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircle(onClick = onClose) { Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Box(Modifier.clip(RoundedCornerShape(50)).background(GlassFill).border(1.dp, GlassEdge, RoundedCornerShape(50))) {
                topActions()
            }
        }

        // The sheet: the dock while closed, the meme's details once pulled up.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeight)
                .graphicsLayer { translationY = sheetOffset },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = openness() }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MemegetColors.Surface.copy(alpha = 0.97f)),
            )
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DOCK_HEIGHT)
                        .draggable(dragState, Orientation.Vertical, onDragStopped = { onDragStopped(it) }),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f)),
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(GlassFill)
                                .border(1.dp, GlassEdge, RoundedCornerShape(50))
                                .clickable { settleSheet(!sheetOpen) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        GlassCircle(onClick = onCopy, enabled = !copying, size = 52) {
                            if (copying) {
                                CircularProgressIndicator(color = MemegetColors.Volt, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text("⧉", color = MemegetColors.Volt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // Back at the top for every meme.
                key(current.id) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = openness() }
                            .nestedScroll(sheetScroll)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        sheetContent()
                    }
                }
            }
        }
    }
}

private val GlassFill = Color(0xFF1B2028).copy(alpha = 0.72f)
private val GlassEdge = Color.White.copy(alpha = 0.10f)

@Composable
private fun GlassCircle(onClick: () -> Unit, enabled: Boolean = true, size: Int = 40, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(GlassFill)
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) { content() }
}

/** The meme on a rounded card shaped like it. Only the settled page plays its video; neighbours show the poster. */
@Composable
private fun MediaCard(meme: Meme, active: Boolean, searchText: String) {
    val context = LocalContext.current
    var aspect by remember(meme.id) { mutableStateOf(MemeAspects.cached(meme)) }
    // Opened from the grid, a meme's shape may not be measured yet; a playing video never reports one to the card.
    LaunchedEffect(meme.id) {
        if (aspect == null) {
            MemeAspects.measure(context, listOf(meme)) {}
            aspect = MemeAspects.cached(meme)
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        val shape = aspect?.let { Modifier.aspectRatio(it.coerceIn(0.2f, 5f)) } ?: Modifier.fillMaxSize()
        Box(
            Modifier
                .then(shape)
                .clip(RoundedCornerShape(20.dp))
                .background(MemegetColors.SurfaceVariant),
        ) {
            if (meme.kind == "video" && active) {
                val startAt = remember(meme.id, searchText) { lineFraction(meme.transcript, searchText) }
                VideoPlayerView(uri = meme.uri, startAt = startAt)
            } else {
                val source = if (meme.kind == "video" && meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri
                val request = remember(source) { ImageRequest.Builder(context).data(source).crossfade(120).build() }
                AsyncImage(
                    model = request,
                    contentDescription = meme.name,
                    contentScale = if (aspect != null) ContentScale.Crop else ContentScale.Fit,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (aspect == null && size.width > 0f && size.height > 0f) aspect = size.width / size.height
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Where [line] is said in a clip, as a fraction of the way through it, estimated from where it falls in the
 * [transcript]: transcripts carry no timings, and a short clip's speech runs at a steady enough pace for this to land
 * within a second or two. Falls back to the longest run of the line's first words; null when nothing matches.
 */
internal fun lineFraction(transcript: String, line: String): Float? {
    val text = " ${SearchText.phraseKey(transcript)} "
    val words = SearchText.phraseKey(line).split(' ').filter { it.isNotEmpty() }
    if (text.isBlank() || words.isEmpty()) return null
    for (n in words.size downTo minOf(2, words.size)) {
        val at = text.indexOf(" ${words.take(n).joinToString(" ")} ")
        if (at >= 0) return at.toFloat() / (text.length - 2)
    }
    return null
}

/** The current meme, tiny and blurred, filling the screen behind the card: each meme tints its own viewer. */
@Composable
private fun Backdrop(meme: Meme, modifier: Modifier) {
    val context = LocalContext.current
    val source = if (meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri
    // A small decode: it is blurred anyway, and a full-size one would cost memory on every swipe.
    val request = remember(source) { ImageRequest.Builder(context).data(source).size(96).crossfade(250).build() }
    Box(modifier) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(40.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.75f)))),
        )
    }
}
