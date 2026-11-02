package com.memeget.nativeapp

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.memeget.nativeapp.inference.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private const val TWO_PI = (2 * PI).toFloat()
/** A grid narrower or shorter than this is repeated to make the globe, so a handful of results still makes a ball. */
private const val MIN_GLOBE_COLS = 24
private const val MIN_GLOBE_ROWS = 12
/** Space left on each side of a tile, as a fraction of its cell. */
private const val GAP = 0.04f
/** Columns across the screen once the globe has fully flattened into the gallery. */
private const val FLAT_COLUMNS = 5.5f
/** Fraction of the shorter screen side the fully zoomed-out globe's radius takes. */
private const val GLOBE_RADIUS = 0.42f
private const val SPIN_SECONDS_PER_TURN = 50f
private const val IDLE_BEFORE_SPIN_MS = 1500L
/** A tile at least this wide (px) draws its sharp picture instead of the atlas thumbnail. */
private const val SHARP_MIN_PX = 72f
/** Between these widths (px) a tile turns from a square crop into the meme's real shape. */
private const val REAL_SHAPE_FROM_PX = 90f
private const val REAL_SHAPE_TO_PX = 240f

private fun wrap(x: Float, m: Int): Float {
    val r = x % m
    return if (r < 0f) r + m else r
}

/** [x] wrapped into [-m/2, m/2): the shortest way round. */
private fun wrapHalf(x: Float, m: Int): Float = wrap(x + m / 2f, m) - m / 2f

private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * Where the globe is looking and how close. The view is a point on the grid ([centerU], [centerV], in cells) and a
 * cell size in pixels ([cell]); everything else follows: zoomed out the grid is wrapped round a sphere, zoomed in it
 * lies flat, and in between it is part way. Outlives the globe leaving the screen, so a return lands in place.
 */
@Stable
internal class MemeGlobeState {
    var centerU by mutableFloatStateOf(0f)
        private set
    var centerV by mutableFloatStateOf(0f)
        private set
    var cell by mutableFloatStateOf(0f)
        private set
    var grid by mutableStateOf(GlobeGrid.Empty)
        private set
    /** The memes [grid]'s cells index into, and the atlas built for them. */
    var gridMemes by mutableStateOf<List<Meme>>(emptyList())
        private set
    var atlas by mutableStateOf<GlobeAtlas?>(null)
        private set
    /** Which memes the layout holds and whether each was still waiting for indexing (see [globeLayoutKey]). */
    internal var layoutSignature: String? = null
    /** The result set (search, filter, folder) on the globe; a new one starts back on the whole globe. */
    var layoutKey by mutableStateOf<Any?>(null)
        private set
    internal var vectors: Map<Long, FloatArray> = emptyMap()
    internal var viewport by mutableStateOf(IntSize.Zero)
    internal var padBottom = 0f
    internal var needsPlacement by mutableStateOf(true)
    internal var touching by mutableStateOf(false)
    internal var animating by mutableStateOf(false)
    internal var lastTouch = 0L
    /** The touch under way began by stopping a fling or a fly-in: it only stops the globe, never opens or flies. */
    internal var touchStoppedMotion = false
    private var motionJob: Job? = null

    /** The globe's grid: the layout, repeated where it's too small to make a ball. */
    val cols get() = grid.cols * ceil(MIN_GLOBE_COLS / grid.cols.toFloat()).toInt().coerceAtLeast(1)
    val rows get() = grid.rows * ceil(MIN_GLOBE_ROWS / grid.rows.toFloat()).toInt().coerceAtLeast(1)

    val centerX get() = viewport.width / 2f
    val centerY get() = (viewport.height - padBottom) / 2f
    /** Cell size at which the whole globe fits on screen. */
    val fitCell get() = GLOBE_RADIUS * min(viewport.width.toFloat(), viewport.height - padBottom) * TWO_PI / cols
    private val morphStart get() = fitCell * 1.2f
    /** Cell size at which the globe has become the flat gallery. */
    val flatCell get() = max(viewport.width / FLAT_COLUMNS, morphStart * 2.5f)
    private val minCell get() = fitCell * 0.6f
    private val maxCell get() = max(viewport.width * 0.95f, flatCell * 1.5f)

    /** 0 on the globe, 1 in the flat gallery. */
    fun flatness(s: Float = cell): Float {
        val lo = ln(morphStart)
        val hi = ln(flatCell)
        return smoothstep((ln(s) - lo) / (hi - lo))
    }

    /** A search globe keeps its best matches facing the viewer, without idle spin, until it's touched. */
    private var holdingFocus by mutableStateOf(false)

    val spinnable get() = !touching && !animating && !holdingFocus && gridMemes.isNotEmpty() && cell > 0f && flatness() < 0.25f

    internal fun publish(grid: GlobeGrid, memes: List<Meme>, signature: String, key: Any?) {
        this.grid = grid
        gridMemes = memes
        layoutSignature = signature
        atlas = GlobeAtlas(memes.size)
        if (key != layoutKey || cell <= 0f) needsPlacement = true
        layoutKey = key
    }

    /** New results that hold exactly the memes on the globe: keep the layout, start back on the whole globe. */
    internal fun adopt(key: Any?) {
        if (key == layoutKey) return
        layoutKey = key
        needsPlacement = true
    }

    /**
     * The same memes came back (a reload after a library write): take their fresh details, in the layout's own order
     * so the grid and atlas still line up. Returns the slots whose picture changed (a video that got its poster),
     * which the atlas must repaint; null if they aren't the same memes.
     */
    internal fun refresh(memes: List<Meme>): List<Int>? {
        val byId = memes.associateBy { it.id }
        val old = gridMemes
        val fresh = old.map { byId[it.id] ?: return null }
        if (fresh != old) gridMemes = fresh
        return fresh.indices.filter { GlobeThumbs.source(fresh[it]) != GlobeThumbs.source(old[it]) }
    }

    /** The whole globe, slowly turning, its focal cell facing the viewer. */
    internal fun place() {
        centerU = grid.focusCol + 0.5f
        centerV = grid.focusRow + 0.5f
        cell = fitCell
        needsPlacement = false
        holdingFocus = grid.ranked
    }

    /** Any touch hands a search globe over to the person: idle spin may start again once it's left alone. */
    internal fun releaseFocus() {
        if (holdingFocus) holdingFocus = false
    }

    internal fun transform(pan: Offset, zoomChange: Float, centroid: Offset) {
        val old = cell
        val new = (old * zoomChange).coerceIn(minCell, maxCell)
        // Flat, the point under the fingers stays put; on the globe a pinch grows the ball about its middle.
        val anchor = flatness(new)
        val ax = centroid.x - centerX
        val ay = centroid.y - centerY
        centerU = wrap(centerU + anchor * (ax / old - ax / new) - pan.x / new, cols)
        centerV = wrap(centerV + anchor * (ay / old - ay / new) - pan.y / new, rows)
        cell = new
    }

    internal fun spin(cells: Float) {
        centerU = wrap(centerU + cells, cols)
    }

    /** Stops a fling or fly-in; true if one was running. */
    fun stopMotion(): Boolean {
        val running = motionJob?.isActive == true
        motionJob?.cancel()
        motionJob = null
        animating = false
        return running
    }

    private fun startMotion(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
        stopMotion()
        animating = true
        val job = scope.launch {
            try {
                // A fling runs each axis in its own child; the motion lasts until both have stopped.
                coroutineScope(block)
            } finally {
                // A motion cut short by the next one leaves the flag to it.
                if (motionJob === coroutineContext[Job]) {
                    animating = false
                    lastTouch = SystemClock.uptimeMillis()
                }
            }
        }
        motionJob = job
    }

    internal fun fling(scope: CoroutineScope, velocity: Velocity, decay: DecayAnimationSpec<Float>) = startMotion(scope) {
        launch {
            var last = 0f
            AnimationState(0f, velocity.x).animateDecay(decay) {
                centerU = wrap(centerU - (value - last) / cell, cols)
                last = value
            }
        }
        launch {
            var last = 0f
            AnimationState(0f, velocity.y).animateDecay(decay) {
                centerV = wrap(centerV - (value - last) / cell, rows)
                last = value
            }
        }
    }

    /** Swoops down onto the cell at ([u], [v]), arriving in the flat gallery. */
    internal fun flyTo(scope: CoroutineScope, u: Float, v: Float) = startMotion(scope) {
        val fromU = centerU
        val fromV = centerV
        val du = wrapHalf(u - fromU, cols)
        val dv = wrapHalf(v - fromV, rows)
        val fromZoom = ln(cell)
        val toZoom = ln(min(flatCell * 1.15f, maxCell))
        animate(0f, 1f, animationSpec = tween(950, easing = FastOutSlowInEasing)) { p, _ ->
            centerU = wrap(fromU + du * p, cols)
            centerV = wrap(fromV + dv * p, rows)
            cell = exp(fromZoom + (toZoom - fromZoom) * p)
        }
    }
}

/**
 * One frame's atlas mesh, built natively (GlobeKernels.cpp): four vertices per tile, drawn in batches that fit
 * 16-bit indices. It keeps what was drawn where until the next frame, so a tap finds its tile.
 */
private class GlobeMesh {
    var verts = FloatArray(0)
    var texs = FloatArray(0)
    /** Per drawn tile: its item, and its column and row on the globe's grid. */
    var items = IntArray(0)
    var cells = IntArray(0)
    var quads = 0
    /** centerU, centerV, cell px, flatness, centre x, centre y, width, height, tile half-size: the kernel's input. */
    val view = FloatArray(9)
    val indices = ShortArray(BATCH * 6).also { idx ->
        for (q in 0 until BATCH) {
            val b = q * 4
            idx[q * 6] = b.toShort()
            idx[q * 6 + 1] = (b + 1).toShort()
            idx[q * 6 + 2] = (b + 2).toShort()
            idx[q * 6 + 3] = b.toShort()
            idx[q * 6 + 4] = (b + 2).toShort()
            idx[q * 6 + 5] = (b + 3).toShort()
        }
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun ensure(capacity: Int) {
        if (items.size >= capacity) return
        val size = max(capacity, items.size * 2)
        verts = FloatArray(size * 8)
        texs = FloatArray(size * 8)
        items = IntArray(size)
        cells = IntArray(size * 2)
    }

    /** Drops tile [q] by moving the last tile into its place; walk tiles from the end when removing. */
    fun removeAt(q: Int) {
        val last = quads - 1
        if (q != last) {
            System.arraycopy(verts, last * 8, verts, q * 8, 8)
            System.arraycopy(texs, last * 8, texs, q * 8, 8)
            items[q] = items[last]
            cells[q * 2] = cells[last * 2]
            cells[q * 2 + 1] = cells[last * 2 + 1]
        }
        quads = last
    }

    fun draw(canvas: android.graphics.Canvas, atlas: GlobeAtlas) {
        paint.shader = atlas.shader
        var start = 0
        while (start < quads) {
            val count = min(BATCH, quads - start)
            canvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES,
                count * 8, verts, start * 8, texs, start * 8, null, 0,
                indices, 0, count * 6, paint,
            )
            start += count
        }
    }

    companion object {
        const val BATCH = 16_000
    }
}

private class SharpDraw(
    val image: SharpImage,
    val video: Boolean,
    val item: Int,
    val col: Int,
    val row: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A tile under a tap: its item and its cell on the globe's grid. */
private class GlobeHit(val item: Int, val col: Int, val row: Int)

/** Reusable per-frame scratch for drawing the globe, and what the last frame drew, for taps. */
private class GlobeFrame {
    val mesh = GlobeMesh()
    val sharp = ArrayList<SharpDraw>()
    val sharpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val markPath = Path()
    val matrix = Matrix()
    val src = RectF()
    val dst = RectF()
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeKey = FloatArray(3)

    /** Queues the sharp picture for a tile if it's loaded (starting the load if not); false means draw the thumbnail. */
    fun addSharp(images: GlobeSharpImages, memes: List<Meme>, item: Int, col: Int, row: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        val meme = memes[item]
        val image = images.get(meme) ?: return false
        sharp += SharpDraw(image, meme.kind == "video", item, col, row, l, t, r, b)
        return true
    }

    /**
     * Darkens the ball toward its edge, as if lit from the viewer: one radial gradient over the whole globe instead
     * of a shade per tile. Brightness 0.3 + 0.7·z, where z is the surface's depth at that radius.
     */
    fun drawShade(canvas: android.graphics.Canvas, cx: Float, cy: Float, radius: Float, strength: Float) {
        if (strength <= 0.01f || radius <= 1f) return
        if (shadeKey[0] != cx || shadeKey[1] != cy || shadeKey[2] != radius) {
            shadeKey = floatArrayOf(cx, cy, radius)
            val stops = floatArrayOf(0f, 0.5f, 0.8f, 0.95f, 1f)
            val colors = IntArray(stops.size) { n ->
                val r = stops[n]
                val z = kotlin.math.sqrt(1f - r * r)
                android.graphics.Color.argb(((0.7f - 0.7f * z) * 255f).toInt(), 0, 0, 0)
            }
            shadePaint.shader = android.graphics.RadialGradient(cx, cy, radius, colors, stops, android.graphics.Shader.TileMode.CLAMP)
        }
        shadePaint.alpha = (strength * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, shadePaint)
    }

    /** The tile the last frame drew under [point]; sharp tiles first, as they're drawn on top. */
    fun hitAt(point: Offset): GlobeHit? {
        for (d in sharp) {
            if (point.x >= d.left && point.x <= d.right && point.y >= d.top && point.y <= d.bottom) return GlobeHit(d.item, d.col, d.row)
        }
        val v = mesh.verts
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (q in 0 until mesh.quads) {
            val o = q * 8
            val l = min(v[o], v[o + 6])
            val r = max(v[o + 2], v[o + 4])
            val t = min(v[o + 1], v[o + 3])
            val b = max(v[o + 5], v[o + 7])
            if (point.x < l || point.x > r || point.y < t || point.y > b) continue
            val dx = point.x - (l + r) / 2f
            val dy = point.y - (t + b) / 2f
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = q
            }
        }
        return if (best < 0) null else GlobeHit(mesh.items[best], mesh.cells[best * 2], mesh.cells[best * 2 + 1])
    }
}

/** What the globe lays out: the results, whether more pages are coming, which results they are, and whether they're
 *  a search (best first, laid out around the best match) or a filter (laid out by look-alike). */
private data class GlobeResults(val memes: List<Meme>, val more: Boolean, val key: Any?, val ranked: Boolean)

/**
 * The library as a globe: zoomed out, every meme on a ball you can spin; pinching in flattens it into a gallery
 * that goes on in every direction. Look-alikes sit together; a search's results sit around its best match, which
 * the globe turns to face. Tap the globe to fly down to a spot; tap a meme in the gallery to open it, long-press to
 * copy.
 */
@Composable
internal fun MemeGlobe(
    memes: List<Meme>,
    state: MemeGlobeState,
    resetKey: Any?,
    /** [memes] are search results, best first. */
    ranked: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    loadVectors: suspend () -> Map<Long, FloatArray>,
    visible: Boolean,
    onOpen: (Meme) -> Unit,
    onLongPress: (Meme) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    state.padBottom = with(density) { bottomInset.toPx() }

    val currentMemes by rememberUpdatedState(memes)
    val currentHasMore by rememberUpdatedState(hasMore)
    val currentLoading by rememberUpdatedState(isLoadingMore)
    val currentResetKey by rememberUpdatedState(resetKey)
    val currentRanked by rememberUpdatedState(ranked)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentLoadVectors by rememberUpdatedState(loadVectors)
    // The globe holds every result, so it keeps loading pages until there are none left. It waits for the list to
    // grow or the pages to run out, not for the loading flag: a page that lands within a frame never shows it. A
    // request that brings nothing (a failed page, or one held back while a search runs) waits out the timeout
    // before asking again, so a page that keeps failing isn't retried in a tight loop.
    LaunchedEffect(state) {
        while (true) {
            snapshotFlow { currentHasMore && !currentLoading }.first { it }
            val before = currentMemes.size
            currentOnLoadMore()
            withTimeoutOrNull(3_000) {
                snapshotFlow { currentMemes.size != before || !currentHasMore }.first { it }
            }
        }
    }
    // Each complete result list is arranged once, to the end: a library reload arriving mid-layout waits its turn
    // instead of throwing the work away, and a list replaced while it settled is skipped for the newer one.
    LaunchedEffect(state) {
        snapshotFlow { GlobeResults(currentMemes, currentHasMore, currentResetKey, currentRanked) }
            .filter { !it.more }
            .conflate()
            .collect { results ->
                val list = results.memes
                delay(250)
                // A newer list (different content; an equal copy is never emitted again) is next in line.
                if (currentHasMore || list != currentMemes) return@collect
                val signature = globeLayoutKey(list, results.ranked)
                if (signature == state.layoutSignature) {
                    val changed = state.refresh(list)
                    if (changed != null) {
                        state.adopt(currentResetKey)
                        if (changed.isNotEmpty()) state.atlas?.fill(context, state.gridMemes, changed)
                        return@collect
                    }
                }
                val started = SystemClock.uptimeMillis()
                val ids = list.map { it.id }
                val file = java.io.File(context.cacheDir, "globe_layout/$signature.grid")
                // A search's layout is quick to make and different every time, so only look-alike layouts are saved.
                val saved = if (results.ranked) null else withContext(Dispatchers.IO) { readGlobeGrid(file, ids) }
                val grid = saved ?: if (results.ranked) {
                    withContext(Dispatchers.Default) { layoutRankedGlobeGrid(list.size) }
                } else {
                    if (list.any { it.id !in state.vectors }) {
                        state.vectors = runCatching { currentLoadVectors() }.getOrDefault(state.vectors)
                    }
                    val vectors = state.vectors
                    withContext(Dispatchers.Default) { layoutGlobeGrid(list.map { vectors[it.id] }) }
                        .also { withContext(Dispatchers.IO) { writeGlobeGrid(file, it, ids) } }
                }
                val how = if (saved != null) "read" else if (results.ranked) "ranked" else "laid out"
                Log.i("MemeGlobe", "$how ${list.size} memes as ${grid.cols}x${grid.rows} in ${SystemClock.uptimeMillis() - started} ms")
                // The results may have moved on while this ran (another search, a filter): only the current results
                // go on screen, and theirs is next in line. A look-alike layout is still saved for when they return.
                if (currentHasMore || globeLayoutKey(currentMemes, currentRanked) != signature) return@collect
                state.publish(grid, list, signature, currentResetKey)
            }
    }
    val atlas = state.atlas
    LaunchedEffect(atlas) {
        val started = SystemClock.uptimeMillis()
        atlas?.fill(context, state.gridMemes) ?: return@LaunchedEffect
        Log.i("MemeGlobe", "atlas of ${atlas.count} filled in ${SystemClock.uptimeMillis() - started} ms")
    }
    LaunchedEffect(state.needsPlacement, state.viewport, state.grid) {
        if (state.needsPlacement && state.viewport.width > 0 && state.gridMemes.isNotEmpty()) state.place()
    }
    // Left alone, the zoomed-out globe turns slowly; a touch stops it dead, like grabbing it.
    LaunchedEffect(state, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            snapshotFlow { state.spinnable }.first { it }
            val idleFor = SystemClock.uptimeMillis() - state.lastTouch
            if (idleFor < IDLE_BEFORE_SPIN_MS) {
                delay(IDLE_BEFORE_SPIN_MS - idleFor)
                continue
            }
            var speed = 0f
            var last = 0L
            while (state.spinnable) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else (now - last) / 1e9f
                    last = now
                    speed += (state.cols / SPIN_SECONDS_PER_TURN - speed) * min(1f, dt * 0.8f)
                    state.spin(speed * dt)
                }
            }
        }
    }

    val sharp = remember { GlobeSharpImages(context, scope) }
    val frame = remember { GlobeFrame() }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.viewport = it }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { point ->
                        // A globe still showing earlier results (the new ones are loading) is dimmed and inert.
                        if (state.touchStoppedMotion || state.layoutKey != currentResetKey) return@detectTapGestures
                        val hit = frame.hitAt(point) ?: return@detectTapGestures
                        if (state.flatness() < 0.5f) state.flyTo(scope, hit.col + 0.5f, hit.row + 0.5f)
                        else state.gridMemes.getOrNull(hit.item)?.let { currentOnOpen(it) }
                    },
                    onLongPress = { point ->
                        if (state.touchStoppedMotion || state.layoutKey != currentResetKey || state.flatness() < 0.5f) return@detectTapGestures
                        val hit = frame.hitAt(point) ?: return@detectTapGestures
                        state.gridMemes.getOrNull(hit.item)?.let { currentOnLongPress(it) }
                    },
                )
            }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    state.touchStoppedMotion = state.stopMotion()
                    state.touching = true
                    state.releaseFocus()
                    try {
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
                    } finally {
                        state.touching = false
                        state.lastTouch = SystemClock.uptimeMillis()
                    }
                }
            },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (state.layoutKey != currentResetKey) 0.3f else 1f }
                .drawBehind { drawGlobe(state, sharp, frame) },
        )
        if (state.gridMemes.isEmpty() || state.layoutKey != resetKey) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(color = MemegetColors.Volt)
                Text(
                    text = if (hasMore) "Gathering ${memes.size} memes…" else "Arranging ${memes.size} memes by look…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MemegetColors.TextSecondary,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomInset + 24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MemegetColors.Background.copy(alpha = 0.92f)))),
        )
    }
}

private fun DrawScope.drawGlobe(state: MemeGlobeState, sharp: GlobeSharpImages, frame: GlobeFrame) {
    // Cleared first, so taps never land on tiles a frame that drew nothing left behind.
    val mesh = frame.mesh
    mesh.quads = 0
    frame.sharp.clear()
    val atlas = state.atlas ?: return
    atlas.version // Redraw as thumbnails land.
    sharp.version
    val memes = state.gridMemes
    val grid = state.grid
    val s = state.cell
    if (memes.isEmpty() || s <= 0f || atlas.count != memes.size) return
    val cols = state.cols
    val rows = state.rows
    val t = state.flatness(s)
    val cx = state.centerX
    val cy = state.centerY
    val radius = s * cols / TWO_PI
    val w = size.width
    val h = size.height
    val trySharp = t > 0.6f
    val flatCount = (ceil(w / s).toInt() + 4) * (ceil(h / s).toInt() + 4)
    mesh.ensure(max(cols * rows, flatCount))
    val view = mesh.view
    view[0] = state.centerU
    view[1] = state.centerV
    view[2] = s
    view[3] = t
    view[4] = cx
    view[5] = cy
    view[6] = w
    view[7] = h
    view[8] = 0.5f - GAP
    mesh.quads = NativeBridge.nativeGlobeMesh(
        grid.cells, grid.cols, grid.rows, cols, rows, atlas.texCoords, view, mesh.verts, mesh.texs, mesh.items, mesh.cells,
    )
    if (trySharp) {
        // Tiles big enough to show the thumbnail's blur draw their sharp picture instead, once it's loaded.
        val v = mesh.verts
        for (q in mesh.quads - 1 downTo 0) {
            val o = q * 8
            val left = min(v[o], v[o + 6])
            val right = max(v[o + 2], v[o + 4])
            if (right - left < SHARP_MIN_PX) continue
            val top = min(v[o + 1], v[o + 3])
            val bottom = max(v[o + 5], v[o + 7])
            if (frame.addSharp(sharp, memes, mesh.items[q], mesh.cells[q * 2], mesh.cells[q * 2 + 1], left, top, right, bottom)) {
                mesh.removeAt(q)
            }
        }
    }

    if (t < 1f) {
        // A faint halo marks the ball's edge; it fades as the globe flattens.
        drawCircle(Color.White.copy(alpha = 0.07f * (1f - t)), radius = radius * 1.03f, center = Offset(cx, cy))
    }

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        mesh.draw(native, atlas)
        if (t < 1f) frame.drawShade(native, cx, cy, radius, (1f - t) * (1f - t))
        for (d in frame.sharp) drawSharp(native, d, t, frame)
    }
}

/** A sharp tile: a square crop at first, opening up into the meme's real shape as it grows on screen. */
private fun drawSharp(canvas: android.graphics.Canvas, d: SharpDraw, t: Float, frame: GlobeFrame) {
    val bitmap = d.image.bitmap
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val cellW = d.right - d.left
    val cellH = d.bottom - d.top
    val real = smoothstep((cellW - REAL_SHAPE_FROM_PX) / (REAL_SHAPE_TO_PX - REAL_SHAPE_FROM_PX)) * t
    val side = min(bw, bh)
    val cropL = (bw - side) / 2f
    val cropT = (bh - side) / 2f
    frame.src.set(cropL * (1f - real), cropT * (1f - real), bw - cropL * (1f - real), bh - cropT * (1f - real))
    val aspect = bw / bh
    val fitW = if (aspect >= cellW / cellH) cellW else cellH * aspect
    val fitH = if (aspect >= cellW / cellH) cellW / aspect else cellH
    val midX = (d.left + d.right) / 2f
    val midY = (d.top + d.bottom) / 2f
    val dstW = cellW + (fitW - cellW) * real
    val dstH = cellH + (fitH - cellH) * real
    frame.dst.set(midX - dstW / 2f, midY - dstH / 2f, midX + dstW / 2f, midY + dstH / 2f)
    frame.matrix.setRectToRect(frame.src, frame.dst, Matrix.ScaleToFit.FILL)
    d.image.shader.setLocalMatrix(frame.matrix)
    frame.sharpPaint.shader = d.image.shader
    val corner = min(dstW, dstH) * 0.07f
    canvas.drawRoundRect(frame.dst, corner, corner, frame.sharpPaint)
    if (d.video) drawPlayMark(canvas, frame)
}

private fun drawPlayMark(canvas: android.graphics.Canvas, frame: GlobeFrame) {
    val box = frame.dst
    val badge = min(box.width(), box.height()) * 0.16f
    if (badge < 10f) return
    val inset = badge * 0.33f
    val left = box.right - inset - badge
    val top = box.bottom - inset - badge
    frame.markPaint.color = android.graphics.Color.argb(150, 0, 0, 0)
    canvas.drawRoundRect(left, top, left + badge, top + badge, badge * 0.28f, badge * 0.28f, frame.markPaint)
    frame.markPath.reset()
    frame.markPath.moveTo(left + badge * 0.36f, top + badge * 0.27f)
    frame.markPath.lineTo(left + badge * 0.36f, top + badge * 0.73f)
    frame.markPath.lineTo(left + badge * 0.74f, top + badge * 0.5f)
    frame.markPath.close()
    frame.markPaint.color = android.graphics.Color.WHITE
    canvas.drawPath(frame.markPath, frame.markPaint)
}
