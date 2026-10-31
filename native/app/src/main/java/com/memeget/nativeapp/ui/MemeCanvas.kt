package com.memeget.nativeapp

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.memeget.nativeapp.storage.MediaIoUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The board is this many screen widths wide: at the starting zoom the rows run off both edges. */
private const val BOARD_SPAN = 2f
/** Aimed row height as a fraction of the screen width. */
private const val ROW_HEIGHT_FRACTION = 0.36f
private const val MAX_ZOOM = 2.5f
/** Rows are composed in blocks of this many, so the composed set changes only at block edges. */
private const val ROW_BLOCK = 4

/**
 * Width over height of each meme's picture, read from file headers once per process. The canvas lays a meme out only
 * once its shape is known, so tiles never jump when a picture loads.
 */
internal object MemeAspects {
    private val cache = ConcurrentHashMap<String, Float>()
    private val io = Dispatchers.IO.limitedParallelism(6)

    // A video's poster is written upright and is far cheaper to read than the container.
    private fun source(meme: Meme): Pair<String, Boolean> =
        if (meme.kind == "video" && meme.thumbUri.isNotBlank()) meme.thumbUri to false else meme.uri to (meme.kind == "video")

    fun cached(meme: Meme): Float? = cache[source(meme).first]

    /** Measures, in list order, every meme whose shape isn't known yet; [onBatch] runs as each batch lands. */
    suspend fun measure(context: Context, memes: List<Meme>, onBatch: () -> Unit) {
        val missing = memes.filter { cached(it) == null }
        for (batch in missing.chunked(24)) {
            coroutineScope {
                batch.map { meme ->
                    async(io) {
                        val (uri, video) = source(meme)
                        // Unreadable files still get a tile: the grid shows them a placeholder too.
                        cache[uri] = MediaIoUtils.uprightAspect(context, Uri.parse(uri), video) ?: 1f
                    }
                }.awaitAll()
            }
            onBatch()
        }
    }
}

/** Pan, zoom and the board last laid out; it outlives the canvas leaving the screen, so a return lands in place. */
@Stable
internal class MemeCanvasState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var board by mutableStateOf(CanvasBoard.Empty)
        internal set
    /** The memes [board]'s tile indexes point into. */
    var boardMemes by mutableStateOf<List<Meme>>(emptyList())
        internal set
    internal var viewport by mutableStateOf(IntSize.Zero)
    internal var padTop = 0f
    internal var padBottom = 0f
    internal var resetKey: Any? = Unset
    internal var needsPlacement = true
    private var flingJob: Job? = null

    /**
     * The tiles to compose, with the board they index. Pan and zoom change every frame of a gesture, so this is worked
     * out where they change, in plain calls, and written only when it moves: reading pan and zoom in composition (or
     * through derivedStateOf or snapshotFlow) cost a recomposition pass or a coroutine hop per frame, which the 5a's
     * interpreted debug build can't afford.
     */
    var renderWindow by mutableStateOf(CanvasBoard.Empty to IntRange.EMPTY)
        private set
    /** Within a screen of the board's last row: time to load more. */
    var nearEnd by mutableStateOf(false)
        private set

    /** Rows on screen plus a margin, widened to whole blocks of rows so the set changes only at block edges. */
    internal fun rangeFor(board: CanvasBoard): IntRange {
        if (zoom <= 0f) return IntRange.EMPTY
        val height = viewport.height / zoom
        val top = -offsetY / zoom
        val rows = board.rowsBetween(top - height / 4f, top + height * 1.25f)
        return if (rows.isEmpty()) IntRange.EMPTY
        else board.tilesOfRows((rows.first / ROW_BLOCK) * ROW_BLOCK..(rows.last / ROW_BLOCK + 1) * ROW_BLOCK - 1)
    }

    private fun refreshWindow() {
        val b = board
        val window = b to rangeFor(b)
        if (window != renderWindow) renderWindow = window
        val end = b.height > 0f && zoom > 0f && (viewport.height - offsetY) / zoom > b.height - viewport.height / zoom
        if (end != nearEnd) nearEnd = end
    }

    private val minZoom get() = if (board.width > 0f) viewport.width / board.width else 1f

    fun screenRect(tile: CanvasTile) = Rect(
        offsetX + tile.x * zoom,
        offsetY + tile.y * zoom,
        offsetX + tile.right * zoom,
        offsetY + tile.bottom * zoom,
    )

    /** Tiles at least partly on screen, with their memes. */
    fun visibleTiles(): List<Pair<CanvasTile, Meme>> {
        if (zoom <= 0f) return emptyList()
        val range = board.tilesBetween(-offsetY / zoom, (viewport.height - offsetY) / zoom)
        val screen = Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
        return range.mapNotNull { i ->
            val tile = board.tiles[i]
            boardMemes.getOrNull(tile.index)?.takeIf { screenRect(tile).overlaps(screen) }?.let { tile to it }
        }
    }

    /** The meme under a point on screen, if any. */
    fun memeAt(point: Offset): Meme? {
        if (zoom <= 0f) return null
        val tile = board.tileAt((point.x - offsetX) / zoom, (point.y - offsetY) / zoom) ?: return null
        return boardMemes.getOrNull(tile.index)
    }

    /** Back to the top at zoom 1, centred on the first row: a short last row (a small folder) starts on screen. */
    internal fun placeAtTop() {
        zoom = 1f
        val firstRowEnd = if (board.rowCount > 1) board.tiles[board.rowStart[1] - 1].right else board.tiles.lastOrNull()?.right ?: board.width
        offsetX = viewport.width / 2f - firstRowEnd / 2f
        offsetY = padTop
        needsPlacement = false
        clamp()
    }

    internal fun transform(pan: Offset, zoomChange: Float, centroid: Offset) {
        val newZoom = (zoom * zoomChange).coerceIn(minZoom, MAX_ZOOM)
        val applied = newZoom / zoom
        offsetX = centroid.x - (centroid.x - offsetX) * applied + pan.x
        offsetY = centroid.y - (centroid.y - offsetY) * applied + pan.y
        zoom = newZoom
        clamp()
    }

    /** Keeps the board covering the screen; a board narrower or shorter than the screen is centred or pinned to the top. */
    internal fun clamp() {
        if (zoom < minZoom) zoom = minZoom
        val width = board.width * zoom
        val height = board.height * zoom
        offsetX = if (width <= viewport.width) (viewport.width - width) / 2f else offsetX.coerceIn(viewport.width - width, 0f)
        val lowest = viewport.height - padBottom - height
        offsetY = if (lowest >= padTop) padTop else offsetY.coerceIn(lowest, padTop)
        refreshWindow()
    }

    /** True while the touch in progress began by stopping a fling: that touch only stops the board. */
    internal var touchStoppedFling = false

    /** Stops a fling; true if one was running. */
    internal fun stopFling(): Boolean {
        val running = flingJob?.isActive == true
        flingJob?.cancel()
        flingJob = null
        return running
    }

    internal fun fling(scope: CoroutineScope, velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        stopFling()
        flingJob = scope.launch {
            launch {
                AnimationState(offsetX, velocity.x).animateDecay(decay) {
                    offsetX = value
                    val before = offsetX
                    clamp()
                    if (offsetX != before) cancelAnimation()
                }
            }
            launch {
                AnimationState(offsetY, velocity.y).animateDecay(decay) {
                    offsetY = value
                    val before = offsetY
                    clamp()
                    if (offsetY != before) cancelAnimation()
                }
            }
        }
    }

    private object Unset
}

/**
 * The library as one board of justified rows, wider than the screen: drag in any direction, pinch to zoom out to the
 * whole width or in for detail. Only tiles near the screen are composed; panning moves their layers without
 * re-measuring or recomposing.
 */
@Composable
internal fun MemeCanvas(
    memes: List<Meme>,
    state: MemeCanvasState,
    resetKey: Any?,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (Meme) -> Unit,
    onLongPress: (Meme) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val gap = with(density) { 6.dp.toPx() }
    state.padTop = with(density) { 8.dp.toPx() }
    state.padBottom = with(density) { bottomInset.toPx() }

    var measuredVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(memes) { MemeAspects.measure(context, memes) { measuredVersion++ } }
    val viewportWidth = state.viewport.width
    // The board covers the memes whose shapes are known, in list order; the rest join as they're measured.
    val (board, measuredAll) = remember(memes, measuredVersion, viewportWidth, hasMore) {
        val known = ArrayList<Float>(memes.size)
        for (meme in memes) known += MemeAspects.cached(meme) ?: break
        val all = known.size == memes.size
        val laidOut = if (viewportWidth <= 0) CanvasBoard.Empty else layoutCanvasBoard(
            aspects = known,
            boardWidth = viewportWidth * BOARD_SPAN,
            rowHeight = viewportWidth * ROW_HEIGHT_FRACTION,
            gap = gap,
            complete = !hasMore && all,
        )
        laidOut to all
    }
    SideEffect {
        state.board = board
        state.boardMemes = memes
        if (state.resetKey != resetKey) {
            state.resetKey = resetKey
            state.needsPlacement = true
        }
        if (board.width > 0f) {
            if (state.needsPlacement) state.placeAtTop() else state.clamp()
        }
    }

    val canLoadMore = hasMore && !isLoadingMore && measuredAll
    val nearEnd = state.nearEnd
    LaunchedEffect(nearEnd, canLoadMore) {
        if (nearEnd && canLoadMore) onLoadMore()
    }

    // The state's window indexes the board it was worked out for; a board just laid out gets one of its own at once.
    val window = state.renderWindow
    val renderRange = if (window.first === board) window.second else Snapshot.withoutReadObservation { state.rangeFor(board) }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.viewport = it }
            // One tap handler for the whole board: tiles carry no pointer input of their own, which keeps them cheap
            // to compose while a pinch or a fling brings new rows in.
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { point -> if (!state.touchStoppedFling) state.memeAt(point)?.let { currentOnOpen(it) } },
                    onLongPress = { point -> if (!state.touchStoppedFling) state.memeAt(point)?.let { currentOnLongPress(it) } },
                )
            }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    state.touchStoppedFling = state.stopFling()
                    val tracker = VelocityTracker()
                    var pastSlop = false
                    var pan = Offset.Zero
                    var zoom = 1f
                    var pointers = 1
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed != pointers) {
                            // A finger landing or lifting jumps the centroid; velocity across it would be noise.
                            tracker.resetTracking()
                            pointers = pressed
                        }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (!pastSlop) {
                            zoom *= zoomChange
                            pan += panChange
                            val span = abs(1f - zoom) * event.calculateCentroidSize(useCurrent = false)
                            pastSlop = span > viewConfiguration.touchSlop || pan.getDistance() > viewConfiguration.touchSlop
                        }
                        if (pastSlop) {
                            state.transform(panChange, zoomChange, event.calculateCentroid(useCurrent = false))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (pointers == 1) {
                            event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                    }
                    if (pastSlop && pointers == 1) state.fling(scope, tracker.calculateVelocity(), decay)
                }
            },
    ) {
        val range = renderRange
        Layout(
            content = {
                for (i in range) {
                    val meme = memes.getOrNull(board.tiles[i].index) ?: continue
                    key(meme.id) { CanvasTileView(meme) }
                }
            },
            // The whole board is one layer: pan and zoom change only its transform, so moving the board re-runs no
            // composition, layout or drawing of the tiles.
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = state.zoom
                    scaleY = state.zoom
                    translationX = state.offsetX
                    translationY = state.offsetY
                },
        ) { measurables, _ ->
            val tiles = range.mapNotNull { i -> board.tiles[i].takeIf { it.index < memes.size } }
            val placeables = measurables.mapIndexed { n, measurable ->
                val tile = tiles[n]
                // Edges rounded from the board's float positions, so neighbours never overlap or open a seam.
                val left = tile.x.roundToInt()
                val top = tile.y.roundToInt()
                measurable.measure(Constraints.fixed(maxOf(1, tile.right.roundToInt() - left), maxOf(1, tile.bottom.roundToInt() - top)))
            }
            layout(board.width.roundToInt().coerceAtLeast(1), board.height.roundToInt().coerceAtLeast(1)) {
                placeables.forEachIndexed { n, placeable -> placeable.place(tiles[n].x.roundToInt(), tiles[n].y.roundToInt()) }
            }
        }
        // The board slides under the chrome instead of ending at a hard edge.
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .background(Brush.verticalGradient(listOf(MemegetColors.Background, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomInset + 24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MemegetColors.Background.copy(alpha = 0.92f)))),
        )
    }
}

private val TileShape = RoundedCornerShape(12.dp)

/** A canvas tile: the picture, clipped round, with a play mark drawn on videos. No text or pointer input of its own. */
@Composable
internal fun CanvasTileView(meme: Meme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val video = meme.kind == "video"
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(TileShape)
            .background(MemegetColors.SurfaceVariant)
            .then(if (video) Modifier.drawWithContent { drawContent(); drawPlayMark() } else Modifier),
    ) {
        MemeTileImage(context, if (meme.thumbUri.isNotEmpty()) meme.thumbUri else meme.uri, meme.name)
    }
}

private fun DrawScope.drawPlayMark() {
    val badge = 18.dp.toPx()
    val inset = 6.dp.toPx()
    val left = size.width - inset - badge
    val top = size.height - inset - badge
    drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(left, top), Size(badge, badge), CornerRadius(5.dp.toPx()))
    val triangle = Path().apply {
        moveTo(left + badge * 0.36f, top + badge * 0.27f)
        lineTo(left + badge * 0.36f, top + badge * 0.73f)
        lineTo(left + badge * 0.74f, top + badge * 0.5f)
        close()
    }
    drawPath(triangle, Color.White)
}

/**
 * The same request everywhere a tile is drawn, so the morph's copies come straight from the memory cache. No
 * crossfade: one starts on first draw, so a tile loaded while the canvas was hidden would fade in from empty on return.
 */
@Composable
internal fun MemeTileImage(context: Context, source: String, name: String) {
    val request = remember(source) { ImageRequest.Builder(context).data(source).crossfade(false).build() }
    AsyncImage(
        model = request,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
