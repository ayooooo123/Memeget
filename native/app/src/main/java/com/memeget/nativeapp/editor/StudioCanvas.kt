package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.memeget.nativeapp.editor.NativeVideoComposition.PreparedVideoComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val PREVIEW_MAX_PIXELS = 1_000_000L

@OptIn(UnstableApi::class)
@Composable
internal fun StudioCanvas(
    project: MemeEditProject,
    playheadUs: Long,
    selectedLayerId: String?,
    activeTool: String,
    beforeMode: Boolean,
    drawSettings: DrawSettings,
    manualCrop: Boolean,
    manualOcrRegion: NormalizedRect?,
    isPlaying: Boolean = false,
    onTogglePlayback: () -> Unit = {},
    onSeekPlayhead: (Long) -> Unit = {},
    onBeginTransaction: () -> Unit = {},
    onCommitTransaction: () -> Unit = {},
    onCancelTransaction: () -> Unit = {},
    onUpdateLayer: (MemeEditLayer) -> Unit,
    onSelectLayer: (String?) -> Unit,
    onUpdateBase: (BaseTransform) -> Unit,
    onAppendDrawPoint: (NormalizedPoint) -> Unit = {},
    onStartDrawStroke: (NormalizedPoint) -> Unit = onAppendDrawPoint,
    onUpdateDrawStroke: (NormalizedPoint) -> Unit = onAppendDrawPoint,
    onUpdateManualOcrRegion: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVideo = project.source.kind == MediaEditKind.VIDEO
    val uneditedProject = remember(project.source) {
        if (isVideo) ProjectCore.createDefaultVideoProject(project.source)
        else ProjectCore.createDefaultImageProject(project.source)
    }
    val isCropEditing = manualCrop && !beforeMode
    val previewProject = when {
        beforeMode -> uneditedProject
        // Crop handles live in source coordinates. Show the entire source frame
        // while the crop tool is open so the rectangle and the finger agree.
        isCropEditing -> ProjectCore.cropEditingProject(project)
        else -> project
    }
    val previewBase = previewProject.base
    val playheadState = rememberUpdatedState(playheadUs)
    val ocrRegionState = rememberUpdatedState(manualOcrRegion)

    // Media display aspect ratio calculation
    val quarterTurn = previewBase.rotation == QuarterRotation.R90 || previewBase.rotation == QuarterRotation.R270
    val orientedWidth = if (quarterTurn) project.source.height else project.source.width
    val orientedHeight = if (quarterTurn) project.source.width else project.source.height

    val sourceAspect = if (orientedHeight > 0) orientedWidth.toFloat() / orientedHeight.toFloat() else 1f
    val croppedAspect = if (previewBase.crop.height > 0.001f && previewBase.crop.width > 0.001f) {
        (sourceAspect * previewBase.crop.width) / previewBase.crop.height
    } else sourceAspect

    val targetAspect = when (previewBase.outputAspect) {
        "1:1" -> 1f
        "4:5" -> 4f / 5f
        "9:16" -> 9f / 16f
        "16:9" -> 16f / 9f
        else -> croppedAspect
    }

    // Still previews use the PNG renderer. Video uses the same GPU composition
    // when paused, playing, and exporting; no per-seek JPEG encode/decode.
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderedProject by remember { mutableStateOf<MemeEditProject?>(null) }
    var rasterError by remember { mutableStateOf<String?>(null) }
    val isRenderingPreview = !isVideo && rasterError == null && renderedProject != previewProject

    LaunchedEffect(previewProject, isVideo) {
        if (isVideo) return@LaunchedEffect
        rasterError = null
        delay(16)
        try {
            displayedBitmap = NativeImagePreview.render(context, previewProject, PREVIEW_MAX_PIXELS)
            renderedProject = previewProject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            rasterError = error.message ?: "Image preview failed"
        }
    }

    // -------------------------------------------------------------------------
    // 2. Video Playback via CompositionPlayer and PreparedVideoComposition
    // -------------------------------------------------------------------------
    var preparedComp by remember { mutableStateOf<PreparedVideoComposition?>(null) }
    var compositionPlayer by remember { mutableStateOf<CompositionPlayer?>(null) }
    var compositionError by remember { mutableStateOf<String?>(null) }
    var preparedProject by remember { mutableStateOf<MemeEditProject?>(null) }
    val isPreparingComposition = isVideo && compositionError == null && preparedProject != previewProject

    // Prepare Composition off-main whenever project changes
    LaunchedEffect(previewProject, isVideo) {
        if (!isVideo) {
            compositionPlayer?.release()
            compositionPlayer = null
            preparedComp = null
            compositionError = null
            return@LaunchedEffect
        }

        var candidatePlayer: CompositionPlayer? = null
        try {
            compositionError = null
            delay(120)
            val prepared = NativeVideoComposition.prepare(context, previewProject)

            // Media3 owns its render-thread inputs through asynchronous release.
            compositionPlayer?.release()
            compositionPlayer = null

            preparedComp = prepared

            val newPlayer = CompositionPlayer.Builder(context).build()
            candidatePlayer = newPlayer
            newPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (compositionPlayer === newPlayer) compositionError = error.message ?: "Video preview failed"
                }
            })
            newPlayer.setComposition(prepared.composition)
            newPlayer.repeatMode = Player.REPEAT_MODE_ALL
            newPlayer.seekTo((prepared.sourceToOutputTimeUs(playheadUs) ?: 0L) / 1000L)
            newPlayer.playWhenReady = isPlaying
            newPlayer.prepare()
            compositionPlayer = newPlayer
            preparedProject = previewProject
            candidatePlayer = null
        } catch (ce: CancellationException) {
            candidatePlayer?.release()
            throw ce
        } catch (e: Exception) {
            candidatePlayer?.release()
            compositionError = e.message ?: "Failed to build video composition"
        }
    }

    // Bitmap inputs remain referenced by Media3 until its release finishes.
    DisposableEffect(Unit) {
        onDispose {
            compositionPlayer?.release()
            compositionPlayer = null
            preparedComp = null
        }
    }

    // Play / Pause synchronization
    LaunchedEffect(compositionPlayer, isPlaying) {
        compositionPlayer?.playWhenReady = isPlaying
    }

    // Active playback loop updating playhead (advances during play)
    LaunchedEffect(compositionPlayer, isPlaying, preparedComp) {
        val player = compositionPlayer ?: return@LaunchedEffect
        val comp = preparedComp ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect

        while (isPlaying) {
            val currentOutputUs = player.currentPosition * 1000L
            val sourceUs = comp.outputToSourceTimeUs(currentOutputUs)
            if (sourceUs != null) {
                onSeekPlayhead(sourceUs)
            }
            delay(33)
        }
    }

    // Playhead seek synchronization without feedback seek loops
    LaunchedEffect(playheadUs, compositionPlayer, isPlaying, preparedComp) {
        val player = compositionPlayer ?: return@LaunchedEffect
        val comp = preparedComp ?: return@LaunchedEffect
        val targetOutputUs = comp.sourceToOutputTimeUs(playheadUs) ?: return@LaunchedEffect
        val playerPosUs = player.currentPosition * 1000L
        val diffUs = abs(playerPosUs - targetOutputUs)

        if (!isPlaying) {
            // Paused explicit seek is ALWAYS applied, even below 100ms
            player.seekTo(targetOutputUs / 1000L)
        } else {
            // During active playback, only apply seek if discrepancy indicates explicit user scrub
            if (diffUs > 250_000L) {
                player.seekTo(targetOutputUs / 1000L)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. Main Studio Canvas Layout
    // -------------------------------------------------------------------------
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E)),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        if (containerWidth <= 0f || containerHeight <= 0f) return@BoxWithConstraints

        val canvasWidth: Float
        val canvasHeight: Float

        if (containerWidth / containerHeight > targetAspect) {
            canvasHeight = containerHeight
            canvasWidth = canvasHeight * targetAspect
        } else {
            canvasWidth = containerWidth
            canvasHeight = canvasWidth / targetAspect
        }

        val density = LocalDensity.current
        val canvasWidthDp = with(density) { canvasWidth.toDp() }
        val canvasHeightDp = with(density) { canvasHeight.toDp() }

        // Bounded media box containing exact export visual and hit-test overlays
        Box(
            modifier = Modifier
                .size(canvasWidthDp, canvasHeightDp)
                .background(Color.Black)
        ) {
            // Both paused and playing video use the export composition.
            if (isVideo && compositionPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = compositionPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        if (view.player != compositionPlayer) {
                            view.player = compositionPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val bmp = displayedBitmap
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Remix preview",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isRenderingPreview || isPreparingComposition) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFD8FF4A), modifier = Modifier.size(32.dp))
                    }
                }
            }

            if (isRenderingPreview || isPreparingComposition) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
                    color = Color(0xFFD8FF4A),
                    strokeWidth = 2.dp
                )
            }
            val previewError = if (isVideo) compositionError else rasterError
            if (previewError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xEE8B0000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Text(
                        text = "Preview unavailable: $previewError",
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                }
            }

            // -----------------------------------------------------------------
            // 4. Canvas Tap Selection Hit-Testing
            // -----------------------------------------------------------------
            if (activeTool != "draw" && !beforeMode) {
                // Read through state, not restart keys: playback moves the playhead every 33 ms, and restarting
                // the detector that often cancelled taps made layers hard to select while the video played.
                val layersState = rememberUpdatedState(project.layers)
                val canvasSizeState = rememberUpdatedState(canvasWidth to canvasHeight)
                val onSelectState = rememberUpdatedState(onSelectLayer)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val tapX = offset.x
                                val tapY = offset.y
                                val playheadUs = playheadState.value
                                val (canvasWidth, canvasHeight) = canvasSizeState.value
                                var hitId: String? = null

                                // Hit-test active layers in reverse order (topmost layer first)
                                for (layer in layersState.value.reversed()) {
                                    if (!ProjectCore.isLayerActiveAt(layer, playheadUs)) continue
                                    when (layer) {
                                        is MemeEditLayer.Text -> {
                                            val t = ProjectCore.interpolateTransformKeyframes(layer.keyframes, playheadUs)
                                                ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                                            val cx = t.center.x * canvasWidth
                                            val cy = t.center.y * canvasHeight
                                            val bw = layer.width * canvasWidth * t.scale
                                            val bh = (layer.fontSize * min(canvasWidth, canvasHeight) * 2.5f) * t.scale
                                            if (tapX in (cx - bw / 2f)..(cx + bw / 2f) && tapY in (cy - bh / 2f)..(cy + bh / 2f)) {
                                                hitId = layer.id
                                                break
                                            }
                                        }
                                        is MemeEditLayer.Cover -> {
                                            val left = layer.rect.x * canvasWidth
                                            val top = layer.rect.y * canvasHeight
                                            val right = (layer.rect.x + layer.rect.width) * canvasWidth
                                            val bottom = (layer.rect.y + layer.rect.height) * canvasHeight
                                            if (tapX in left..right && tapY in top..bottom) {
                                                hitId = layer.id
                                                break
                                            }
                                        }
                                        is MemeEditLayer.Subject -> {
                                            val t = ProjectCore.interpolateTransformKeyframes(layer.keyframes, playheadUs)
                                                ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                                            val cx = t.center.x * canvasWidth
                                            val cy = t.center.y * canvasHeight
                                            val radians = -Math.toRadians(t.rotationDegrees.toDouble())
                                            val dx = tapX - cx
                                            val dy = tapY - cy
                                            val localX = dx * cos(radians) - dy * sin(radians)
                                            val localY = dx * sin(radians) + dy * cos(radians)
                                            val width = layer.bounds.width * canvasWidth * t.scale
                                            val height = layer.bounds.height * canvasHeight * t.scale
                                            if (abs(localX) <= width / 2f && abs(localY) <= height / 2f) {
                                                hitId = layer.id
                                                break
                                            }
                                        }
                                        is MemeEditLayer.Media -> {
                                            val t = ProjectCore.interpolateTransformKeyframes(layer.keyframes, playheadUs)
                                                ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                                            val cx = t.center.x * canvasWidth
                                            val cy = t.center.y * canvasHeight
                                            val dim = min(canvasWidth, canvasHeight) * 0.5f * t.scale
                                            if (tapX in (cx - dim / 2f)..(cx + dim / 2f) && tapY in (cy - dim / 2f)..(cy + dim / 2f)) {
                                                hitId = layer.id
                                                break
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                onSelectState.value(hitId)
                            }
                        }
                )
            }

            // -----------------------------------------------------------------
            // 5. Live Drawing Gesture Overlay & Immediate Touch Feedback
            // -----------------------------------------------------------------
            if (activeTool == "draw" && !beforeMode) {
                var activePoints by remember { mutableStateOf<List<NormalizedPoint>>(emptyList()) }

                // Live stroke visualization Canvas during active finger drag
                if (activePoints.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeColor = try {
                            Color(android.graphics.Color.parseColor(drawSettings.color))
                        } catch (_: Exception) {
                            Color(0xFFFF4E42)
                        }
                        val strokeWidthPx = max(2f, drawSettings.strokeScale * min(size.width, size.height))

                        when (drawSettings.shape) {
                            DrawShape.FREE -> {
                                if (activePoints.size > 1) {
                                    val path = Path().apply {
                                        moveTo(activePoints[0].x * size.width, activePoints[0].y * size.height)
                                        for (i in 1 until activePoints.size) {
                                            lineTo(activePoints[i].x * size.width, activePoints[i].y * size.height)
                                        }
                                    }
                                    drawPath(path, strokeColor, style = Stroke(width = strokeWidthPx))
                                }
                            }
                            DrawShape.LINE, DrawShape.ARROW -> {
                                val p0 = activePoints.first()
                                val p1 = activePoints.last()
                                val start = Offset(p0.x * size.width, p0.y * size.height)
                                val end = Offset(p1.x * size.width, p1.y * size.height)
                                drawLine(strokeColor, start, end, strokeWidth = strokeWidthPx)
                                if (drawSettings.shape == DrawShape.ARROW) {
                                    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                                    val arrowLen = max(16f, strokeWidthPx * 4f)
                                    val arrowAngle = Math.PI / 6.0
                                    val x1 = end.x - (arrowLen * cos(angle - arrowAngle)).toFloat()
                                    val y1 = end.y - (arrowLen * sin(angle - arrowAngle)).toFloat()
                                    val x2 = end.x - (arrowLen * cos(angle + arrowAngle)).toFloat()
                                    val y2 = end.y - (arrowLen * sin(angle + arrowAngle)).toFloat()
                                    drawLine(strokeColor, end, Offset(x1, y1), strokeWidth = strokeWidthPx)
                                    drawLine(strokeColor, end, Offset(x2, y2), strokeWidth = strokeWidthPx)
                                }
                            }
                            DrawShape.RECTANGLE -> {
                                val p0 = activePoints.first()
                                val p1 = activePoints.last()
                                val left = min(p0.x, p1.x) * size.width
                                val top = min(p0.y, p1.y) * size.height
                                val right = max(p0.x, p1.x) * size.width
                                val bottom = max(p0.y, p1.y) * size.height
                                drawRect(
                                    color = strokeColor,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top),
                                    style = if (drawSettings.filled) Fill else Stroke(width = strokeWidthPx)
                                )
                            }
                            DrawShape.ELLIPSE -> {
                                val p0 = activePoints.first()
                                val p1 = activePoints.last()
                                val left = min(p0.x, p1.x) * size.width
                                val top = min(p0.y, p1.y) * size.height
                                val right = max(p0.x, p1.x) * size.width
                                val bottom = max(p0.y, p1.y) * size.height
                                drawOval(
                                    color = strokeColor,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top),
                                    style = if (drawSettings.filled) Fill else Stroke(width = strokeWidthPx)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(drawSettings) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onBeginTransaction()
                                    val pt = NormalizedPoint(
                                        (offset.x / canvasWidth).coerceIn(0f, 1f),
                                        (offset.y / canvasHeight).coerceIn(0f, 1f)
                                    )
                                    activePoints = listOf(pt)
                                    onStartDrawStroke(pt)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pt = NormalizedPoint(
                                        (change.position.x / canvasWidth).coerceIn(0f, 1f),
                                        (change.position.y / canvasHeight).coerceIn(0f, 1f)
                                    )
                                    activePoints = if (drawSettings.shape == DrawShape.FREE) {
                                        DrawToolCore.appendFreehandPoint(activePoints, pt)
                                    } else {
                                        val first = activePoints.firstOrNull() ?: pt
                                        listOf(first, pt)
                                    }
                                    onUpdateDrawStroke(pt)
                                },
                                onDragEnd = {
                                    activePoints = emptyList()
                                    onCommitTransaction()
                                },
                                onDragCancel = {
                                    activePoints = emptyList()
                                    onCancelTransaction()
                                }
                            )
                        }
                )
            }

            // -----------------------------------------------------------------
            // 6. Interactive Selected Layer Handles (Move, Resize, Rotate)
            // -----------------------------------------------------------------
            val selectedLayerState = rememberUpdatedState(
                if (!beforeMode && selectedLayerId != null) project.layers.find { it.id == selectedLayerId } else null
            )
            val selectedLayer = selectedLayerState.value

            if (selectedLayer != null) {
                when (selectedLayer) {
                    is MemeEditLayer.Text -> {
                        val transform = ProjectCore.interpolateTransformKeyframes(selectedLayer.keyframes, playheadUs)
                            ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                        val centerXPx = transform.center.x * canvasWidth
                        val centerYPx = transform.center.y * canvasHeight

                        val boxWidthPx = selectedLayer.width * canvasWidth * transform.scale
                        val boxHeightPx = (selectedLayer.fontSize * min(canvasWidth, canvasHeight) * 2.5f) * transform.scale

                        val leftDp = with(density) { (centerXPx - boxWidthPx / 2f).toDp() }
                        val topDp = with(density) { (centerYPx - boxHeightPx / 2f).toDp() }
                        val widthDp = with(density) { boxWidthPx.toDp() }
                        val heightDp = with(density) { boxHeightPx.toDp() }

                        var startCenter by remember { mutableStateOf(NormalizedPoint.Center) }
                        var startScale by remember { mutableFloatStateOf(1f) }
                        var startRotation by remember { mutableFloatStateOf(0f) }
                        var accumDeltaX by remember { mutableFloatStateOf(0f) }
                        var accumDeltaY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .offset(leftDp, topDp)
                                .size(widthDp, heightDp)
                                .border(1.5.dp, Color(0xFFD8FF4A))
                                .pointerInput(selectedLayer.id) {
                                    var gestureLayer = selectedLayer
                                    var gestureTimeUs = playheadUs
                                    detectDragGestures(
                                        onDragStart = {
                                            onBeginTransaction()
                                            gestureTimeUs = playheadState.value
                                            val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                checkNotNull(selectedLayerState.value), gestureTimeUs
                                            ) as MemeEditLayer.Text
                                            gestureLayer = withKf
                                            onUpdateLayer(withKf)
                                            val currentT = ProjectCore.interpolateTransformKeyframes(
                                                withKf.keyframes,
                                                gestureTimeUs
                                            ) ?: transform
                                            startCenter = currentT.center
                                            accumDeltaX = 0f
                                            accumDeltaY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumDeltaX += dragAmount.x
                                            accumDeltaY += dragAmount.y
                                            val newX = (startCenter.x + accumDeltaX / canvasWidth).coerceIn(0.02f, 0.98f)
                                            val newY = (startCenter.y + accumDeltaY / canvasHeight).coerceIn(0.02f, 0.98f)
                                            val snapped = ProjectCore.snapCaptionCenter(NormalizedPoint(newX, newY))
                                            val updatedKeyframes = gestureLayer.keyframes.map {
                                                if (it.timeUs == gestureTimeUs) it.copy(center = snapped.center) else it
                                            }
                                            onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                        },
                                        onDragEnd = { onCommitTransaction() },
                                        onDragCancel = { onCancelTransaction() }
                                    )
                                }
                        ) {
                            // Resize Handle (bottom-right)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(24.dp)
                                    .offset(12.dp, 12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFD8FF4A))
                                    .pointerInput(selectedLayer.id) {
                                        var gestureLayer = selectedLayer
                                        var gestureTimeUs = playheadUs
                                        detectDragGestures(
                                            onDragStart = {
                                                onBeginTransaction()
                                                gestureTimeUs = playheadState.value
                                                val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                    checkNotNull(selectedLayerState.value), gestureTimeUs
                                                ) as MemeEditLayer.Text
                                                gestureLayer = withKf
                                                onUpdateLayer(withKf)
                                                val currentT = ProjectCore.interpolateTransformKeyframes(
                                                    withKf.keyframes,
                                                    gestureTimeUs
                                                ) ?: transform
                                                startScale = currentT.scale
                                                accumDeltaX = 0f
                                                accumDeltaY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accumDeltaX += dragAmount.x
                                                accumDeltaY += dragAmount.y
                                                val deltaScale = (accumDeltaX + accumDeltaY) / (canvasWidth * 0.5f)
                                                val newScale = (startScale + deltaScale).coerceIn(0.1f, 5f)
                                                val updatedKeyframes = gestureLayer.keyframes.map {
                                                    if (it.timeUs == gestureTimeUs) it.copy(scale = newScale) else it
                                                }
                                                onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                            },
                                            onDragEnd = { onCommitTransaction() },
                                            onDragCancel = { onCancelTransaction() }
                                        )
                                    }
                            )

                            // Rotate Handle (top-center)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .size(24.dp)
                                    .offset(0.dp, (-20).dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .pointerInput(selectedLayer.id) {
                                        var gestureLayer = selectedLayer
                                        var gestureTimeUs = playheadUs
                                        detectDragGestures(
                                            onDragStart = {
                                                onBeginTransaction()
                                                gestureTimeUs = playheadState.value
                                                val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                    checkNotNull(selectedLayerState.value), gestureTimeUs
                                                ) as MemeEditLayer.Text
                                                gestureLayer = withKf
                                                onUpdateLayer(withKf)
                                                val currentT = ProjectCore.interpolateTransformKeyframes(
                                                    withKf.keyframes,
                                                    gestureTimeUs
                                                ) ?: transform
                                                startRotation = currentT.rotationDegrees
                                                accumDeltaX = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accumDeltaX += dragAmount.x
                                                val newRot = (startRotation + accumDeltaX * 0.5f) % 360f
                                                val updatedKeyframes = gestureLayer.keyframes.map {
                                                    if (it.timeUs == gestureTimeUs) it.copy(rotationDegrees = newRot) else it
                                                }
                                                onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                            },
                                            onDragEnd = { onCommitTransaction() },
                                            onDragCancel = { onCancelTransaction() }
                                        )
                                    }
                            )
                        }
                    }
                    is MemeEditLayer.Cover -> {
                        val rect = selectedLayer.rect
                        val leftDp = with(density) { (rect.x * canvasWidth).toDp() }
                        val topDp = with(density) { (rect.y * canvasHeight).toDp() }
                        val widthDp = with(density) { (rect.width * canvasWidth).toDp() }
                        val heightDp = with(density) { (rect.height * canvasHeight).toDp() }

                        var startRect by remember { mutableStateOf(NormalizedRect.Full) }
                        var accumDeltaX by remember { mutableFloatStateOf(0f) }
                        var accumDeltaY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .offset(leftDp, topDp)
                                .size(widthDp, heightDp)
                                .border(1.5.dp, Color(0xFF58A6FF))
                                .pointerInput(selectedLayer.id) {
                                    var gestureLayer = selectedLayer
                                    detectDragGestures(
                                        onDragStart = {
                                            onBeginTransaction()
                                            gestureLayer = selectedLayerState.value as MemeEditLayer.Cover
                                            startRect = gestureLayer.rect
                                            accumDeltaX = 0f
                                            accumDeltaY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumDeltaX += dragAmount.x
                                            accumDeltaY += dragAmount.y
                                            val newX = (startRect.x + accumDeltaX / canvasWidth).coerceIn(0f, 1f - startRect.width)
                                            val newY = (startRect.y + accumDeltaY / canvasHeight).coerceIn(0f, 1f - startRect.height)
                                            onUpdateLayer(gestureLayer.copy(rect = startRect.copy(x = newX, y = newY)))
                                        },
                                        onDragEnd = { onCommitTransaction() },
                                        onDragCancel = { onCancelTransaction() }
                                    )
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .offset(10.dp, 10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF58A6FF))
                                    .pointerInput(selectedLayer.id) {
                                        var gestureLayer = selectedLayer
                                        detectDragGestures(
                                            onDragStart = {
                                                onBeginTransaction()
                                                gestureLayer = selectedLayerState.value as MemeEditLayer.Cover
                                                startRect = gestureLayer.rect
                                                accumDeltaX = 0f
                                                accumDeltaY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accumDeltaX += dragAmount.x
                                                accumDeltaY += dragAmount.y
                                                val newW = (startRect.width + accumDeltaX / canvasWidth).coerceIn(0.02f, 1f - startRect.x)
                                                val newH = (startRect.height + accumDeltaY / canvasHeight).coerceIn(0.02f, 1f - startRect.y)
                                                onUpdateLayer(gestureLayer.copy(rect = startRect.copy(width = newW, height = newH)))
                                            },
                                            onDragEnd = { onCommitTransaction() },
                                            onDragCancel = { onCancelTransaction() }
                                        )
                                    }
                            )
                        }
                    }
                    is MemeEditLayer.Subject -> {
                        val transform = ProjectCore.interpolateTransformKeyframes(selectedLayer.keyframes, playheadUs)
                            ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                        val centerXPx = transform.center.x * canvasWidth
                        val centerYPx = transform.center.y * canvasHeight
                        val radians = Math.toRadians(transform.rotationDegrees.toDouble())
                        val width = selectedLayer.bounds.width * canvasWidth * transform.scale
                        val height = selectedLayer.bounds.height * canvasHeight * transform.scale
                        val boxWidth = (abs(cos(radians)) * width + abs(sin(radians)) * height).toFloat()
                        val boxHeight = (abs(sin(radians)) * width + abs(cos(radians)) * height).toFloat()
                        val baseDim = max(boxWidth, boxHeight)
                        val leftDp = with(density) { (centerXPx - boxWidth / 2f).toDp() }
                        val topDp = with(density) { (centerYPx - boxHeight / 2f).toDp() }
                        val widthDp = with(density) { boxWidth.toDp() }
                        val heightDp = with(density) { boxHeight.toDp() }

                        var startCenter by remember { mutableStateOf(NormalizedPoint.Center) }
                        var startScale by remember { mutableFloatStateOf(1f) }
                        var accumDeltaX by remember { mutableFloatStateOf(0f) }
                        var accumDeltaY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .offset(leftDp, topDp)
                                .size(widthDp, heightDp)
                                .border(1.5.dp, Color(0xFF3FB950))
                                .pointerInput(selectedLayer.id) {
                                    var gestureLayer = selectedLayer
                                    var gestureTimeUs = playheadUs
                                    detectDragGestures(
                                        onDragStart = {
                                            onBeginTransaction()
                                            gestureTimeUs = playheadState.value
                                            val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                checkNotNull(selectedLayerState.value), gestureTimeUs
                                            ) as MemeEditLayer.Subject
                                            gestureLayer = withKf
                                            onUpdateLayer(withKf)
                                            val currentT = ProjectCore.interpolateTransformKeyframes(
                                                withKf.keyframes,
                                                gestureTimeUs
                                            ) ?: transform
                                            startCenter = currentT.center
                                            accumDeltaX = 0f
                                            accumDeltaY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumDeltaX += dragAmount.x
                                            accumDeltaY += dragAmount.y
                                            val newX = (startCenter.x + accumDeltaX / canvasWidth).coerceIn(0.02f, 0.98f)
                                            val newY = (startCenter.y + accumDeltaY / canvasHeight).coerceIn(0.02f, 0.98f)
                                            val updatedKeyframes = gestureLayer.keyframes.map {
                                                if (it.timeUs == gestureTimeUs) it.copy(center = NormalizedPoint(newX, newY)) else it
                                            }
                                            onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                        },
                                        onDragEnd = { onCommitTransaction() },
                                        onDragCancel = { onCancelTransaction() }
                                    )
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(24.dp)
                                    .offset(12.dp, 12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3FB950))
                                    .pointerInput(selectedLayer.id) {
                                        var gestureLayer = selectedLayer
                                        var gestureTimeUs = playheadUs
                                        detectDragGestures(
                                            onDragStart = {
                                                onBeginTransaction()
                                                gestureTimeUs = playheadState.value
                                                val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                    checkNotNull(selectedLayerState.value), gestureTimeUs
                                                ) as MemeEditLayer.Subject
                                                gestureLayer = withKf
                                                onUpdateLayer(withKf)
                                                val currentT = ProjectCore.interpolateTransformKeyframes(
                                                    withKf.keyframes,
                                                    gestureTimeUs
                                                ) ?: transform
                                                startScale = currentT.scale
                                                accumDeltaX = 0f
                                                accumDeltaY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accumDeltaX += dragAmount.x
                                                accumDeltaY += dragAmount.y
                                                val deltaScale = (accumDeltaX + accumDeltaY) / (canvasWidth * 0.5f)
                                                val newScale = (startScale + deltaScale).coerceIn(0.1f, 5f)
                                                val updatedKeyframes = gestureLayer.keyframes.map {
                                                    if (it.timeUs == gestureTimeUs) it.copy(scale = newScale) else it
                                                }
                                                onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                            },
                                            onDragEnd = { onCommitTransaction() },
                                            onDragCancel = { onCancelTransaction() }
                                        )
                                    }
                            )
                        }
                    }
                    is MemeEditLayer.Media -> {
                        val transform = ProjectCore.interpolateTransformKeyframes(selectedLayer.keyframes, playheadUs)
                            ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                        val centerXPx = transform.center.x * canvasWidth
                        val centerYPx = transform.center.y * canvasHeight
                        val baseDim = min(canvasWidth, canvasHeight) * 0.5f * transform.scale

                        val leftDp = with(density) { (centerXPx - baseDim / 2f).toDp() }
                        val topDp = with(density) { (centerYPx - baseDim / 2f).toDp() }
                        val sizeDp = with(density) { baseDim.toDp() }

                        var startCenter by remember { mutableStateOf(NormalizedPoint.Center) }
                        var startScale by remember { mutableFloatStateOf(1f) }
                        var accumDeltaX by remember { mutableFloatStateOf(0f) }
                        var accumDeltaY by remember { mutableFloatStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .offset(leftDp, topDp)
                                .size(sizeDp)
                                .border(1.5.dp, Color(0xFFE36209))
                                .pointerInput(selectedLayer.id) {
                                    var gestureLayer = selectedLayer
                                    var gestureTimeUs = playheadUs
                                    detectDragGestures(
                                        onDragStart = {
                                            onBeginTransaction()
                                            gestureTimeUs = playheadState.value
                                            val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                checkNotNull(selectedLayerState.value), gestureTimeUs
                                            ) as MemeEditLayer.Media
                                            gestureLayer = withKf
                                            onUpdateLayer(withKf)
                                            val currentT = ProjectCore.interpolateTransformKeyframes(
                                                withKf.keyframes,
                                                gestureTimeUs
                                            ) ?: transform
                                            startCenter = currentT.center
                                            accumDeltaX = 0f
                                            accumDeltaY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumDeltaX += dragAmount.x
                                            accumDeltaY += dragAmount.y
                                            val newX = (startCenter.x + accumDeltaX / canvasWidth).coerceIn(0.02f, 0.98f)
                                            val newY = (startCenter.y + accumDeltaY / canvasHeight).coerceIn(0.02f, 0.98f)
                                            val updatedKeyframes = gestureLayer.keyframes.map {
                                                if (it.timeUs == gestureTimeUs) it.copy(center = NormalizedPoint(newX, newY)) else it
                                            }
                                            onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                        },
                                        onDragEnd = { onCommitTransaction() },
                                        onDragCancel = { onCancelTransaction() }
                                    )
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(24.dp)
                                    .offset(12.dp, 12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE36209))
                                    .pointerInput(selectedLayer.id) {
                                        var gestureLayer = selectedLayer
                                        var gestureTimeUs = playheadUs
                                        detectDragGestures(
                                            onDragStart = {
                                                onBeginTransaction()
                                                gestureTimeUs = playheadState.value
                                                val withKf = ProjectCore.upsertKeyframeAtPlayhead(
                                                    checkNotNull(selectedLayerState.value), gestureTimeUs
                                                ) as MemeEditLayer.Media
                                                gestureLayer = withKf
                                                onUpdateLayer(withKf)
                                                val currentT = ProjectCore.interpolateTransformKeyframes(
                                                    withKf.keyframes,
                                                    gestureTimeUs
                                                ) ?: transform
                                                startScale = currentT.scale
                                                accumDeltaX = 0f
                                                accumDeltaY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accumDeltaX += dragAmount.x
                                                accumDeltaY += dragAmount.y
                                                val deltaScale = (accumDeltaX + accumDeltaY) / (canvasWidth * 0.5f)
                                                val newScale = (startScale + deltaScale).coerceIn(0.1f, 5f)
                                                val updatedKeyframes = gestureLayer.keyframes.map {
                                                    if (it.timeUs == gestureTimeUs) it.copy(scale = newScale) else it
                                                }
                                                onUpdateLayer(gestureLayer.copy(keyframes = updatedKeyframes))
                                            },
                                            onDragEnd = { onCommitTransaction() },
                                            onDragCancel = { onCancelTransaction() }
                                        )
                                    }
                            )
                        }
                    }
                    else -> Unit
                }
            }

            // -----------------------------------------------------------------
            // 7. Free-Crop Interaction Handles
            // -----------------------------------------------------------------
            if (isCropEditing) {
                CropOverlay(
                    base = project.base,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    onUpdateBase = onUpdateBase,
                    onBeginTransaction = onBeginTransaction,
                    onCommitTransaction = onCommitTransaction,
                    onCancelTransaction = onCancelTransaction
                )
            }

            // -----------------------------------------------------------------
            // 8. Manual OCR Selection Region Box
            // -----------------------------------------------------------------
            if (activeTool == "replace-text" && manualOcrRegion != null && !beforeMode) {
                val rect = manualOcrRegion
                val leftDp = with(density) { (rect.x * canvasWidth).toDp() }
                val topDp = with(density) { (rect.y * canvasHeight).toDp() }
                val widthDp = with(density) { (rect.width * canvasWidth).toDp() }
                val heightDp = with(density) { (rect.height * canvasHeight).toDp() }

                var startRect by remember { mutableStateOf(NormalizedRect.Full) }
                var accumDeltaX by remember { mutableFloatStateOf(0f) }
                var accumDeltaY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .offset(leftDp, topDp)
                        .size(widthDp, heightDp)
                        .border(2.dp, Color(0xFFFFCC00))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    startRect = checkNotNull(ocrRegionState.value)
                                    accumDeltaX = 0f
                                    accumDeltaY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumDeltaX += dragAmount.x
                                    accumDeltaY += dragAmount.y
                                    val newX = (startRect.x + accumDeltaX / canvasWidth).coerceIn(0f, 1f - startRect.width)
                                    val newY = (startRect.y + accumDeltaY / canvasHeight).coerceIn(0f, 1f - startRect.height)
                                    onUpdateManualOcrRegion(startRect.copy(x = newX, y = newY))
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .offset(10.dp, 10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFCC00))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        startRect = checkNotNull(ocrRegionState.value)
                                        accumDeltaX = 0f
                                        accumDeltaY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        accumDeltaX += dragAmount.x
                                        accumDeltaY += dragAmount.y
                                        val newW = (startRect.width + accumDeltaX / canvasWidth).coerceIn(0.05f, 1f - startRect.x)
                                        val newH = (startRect.height + accumDeltaY / canvasHeight).coerceIn(0.05f, 1f - startRect.y)
                                        onUpdateManualOcrRegion(startRect.copy(width = newW, height = newH))
                                    }
                                )
                            }
                    )
                }
            }

            // -----------------------------------------------------------------
            // 9. Video Play/Pause Floating Overlay
            // -----------------------------------------------------------------
            if (isVideo && !beforeMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    IconButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC161B22))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFFD8FF4A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Crop handles live in source coordinates on the uncropped preview. Dragging a
 * corner resizes, dragging the frame moves the whole rectangle, and everything
 * outside the rectangle is dimmed so the cut is obvious before export.
 */
@Composable
private fun CropOverlay(
    base: BaseTransform,
    canvasWidth: Float,
    canvasHeight: Float,
    onUpdateBase: (BaseTransform) -> Unit,
    onBeginTransaction: () -> Unit,
    onCommitTransaction: () -> Unit,
    onCancelTransaction: () -> Unit
) {
    val density = LocalDensity.current
    val latestBase = rememberUpdatedState(base)
    val crop = base.crop
    val minEdge = ProjectCore.MIN_NORMALIZED_CROP_EDGE

    val leftDp = with(density) { (crop.x * canvasWidth).toDp() }
    val topDp = with(density) { (crop.y * canvasHeight).toDp() }
    val widthDp = with(density) { (crop.width * canvasWidth).toDp() }
    val heightDp = with(density) { (crop.height * canvasHeight).toDp() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = crop.x * size.width
        val top = crop.y * size.height
        val right = (crop.x + crop.width) * size.width
        val bottom = (crop.y + crop.height) * size.height
        val shade = Color(0xA6000000)
        drawRect(shade, topLeft = Offset(0f, 0f), size = Size(size.width, top))
        drawRect(shade, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(shade, topLeft = Offset(0f, top), size = Size(left, bottom - top))
        drawRect(shade, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
    }

    Box(
        modifier = Modifier
            .offset(leftDp, topDp)
            .size(widthDp, heightDp)
            .border(2.dp, Color(0xFFD8FF4A))
            .pointerInput(canvasWidth, canvasHeight) {
                var gestureBase = latestBase.value
                var startCrop = NormalizedRect.Full
                var accumDeltaX = 0f
                var accumDeltaY = 0f
                detectDragGestures(
                    onDragStart = {
                        onBeginTransaction()
                        gestureBase = latestBase.value
                        startCrop = gestureBase.crop
                        accumDeltaX = 0f
                        accumDeltaY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumDeltaX += dragAmount.x
                        accumDeltaY += dragAmount.y
                        val newX = (startCrop.x + accumDeltaX / canvasWidth)
                            .coerceIn(0f, 1f - startCrop.width)
                        val newY = (startCrop.y + accumDeltaY / canvasHeight)
                            .coerceIn(0f, 1f - startCrop.height)
                        onUpdateBase(
                            gestureBase.copy(
                                crop = startCrop.copy(x = newX, y = newY),
                                outputAspect = "free"
                            )
                        )
                    },
                    onDragEnd = { onCommitTransaction() },
                    onDragCancel = { onCancelTransaction() }
                )
            }
    ) {
        for (corner in CropCorner.entries) {
            CropHandle(
                corner = corner,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                minEdge = minEdge,
                latestBase = { latestBase.value },
                onBeginTransaction = onBeginTransaction,
                onUpdateBase = onUpdateBase,
                onCommitTransaction = onCommitTransaction,
                onCancelTransaction = onCancelTransaction
            )
        }
    }
}

@Composable
private fun BoxScope.CropHandle(
    corner: CropCorner,
    canvasWidth: Float,
    canvasHeight: Float,
    minEdge: Float,
    latestBase: () -> BaseTransform,
    onBeginTransaction: () -> Unit,
    onUpdateBase: (BaseTransform) -> Unit,
    onCommitTransaction: () -> Unit,
    onCancelTransaction: () -> Unit
) {
    val alignment = when (corner) {
        CropCorner.TOP_LEFT -> Alignment.TopStart
        CropCorner.TOP_RIGHT -> Alignment.TopEnd
        CropCorner.BOTTOM_LEFT -> Alignment.BottomStart
        CropCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
    }
    val offset = when (corner) {
        CropCorner.TOP_LEFT -> Offset(-10f, -10f)
        CropCorner.TOP_RIGHT -> Offset(10f, -10f)
        CropCorner.BOTTOM_LEFT -> Offset(-10f, 10f)
        CropCorner.BOTTOM_RIGHT -> Offset(10f, 10f)
    }

    Box(
        modifier = Modifier
            .align(alignment)
            .size(20.dp)
            .offset(offset.x.dp, offset.y.dp)
            .clip(CircleShape)
            .background(Color(0xFFD8FF4A))
            // Handles sit on the canvas edge; without this a drag from the left or
            // right edge is eaten by the system back gesture and exits the studio.
            .systemGestureExclusion()
            .pointerInput(corner, canvasWidth, canvasHeight) {
                var gestureBase = latestBase()
                var startCrop = NormalizedRect.Full
                var accumDeltaX = 0f
                var accumDeltaY = 0f
                detectDragGestures(
                    onDragStart = {
                        onBeginTransaction()
                        gestureBase = latestBase()
                        startCrop = gestureBase.crop
                        accumDeltaX = 0f
                        accumDeltaY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumDeltaX += dragAmount.x
                        accumDeltaY += dragAmount.y
                        val dx = accumDeltaX / canvasWidth
                        val dy = accumDeltaY / canvasHeight
                        val startRight = startCrop.x + startCrop.width
                        val startBottom = startCrop.y + startCrop.height
                        val rect = when (corner) {
                            CropCorner.TOP_LEFT -> {
                                val newX = (startCrop.x + dx).coerceIn(0f, startRight - minEdge)
                                val newY = (startCrop.y + dy).coerceIn(0f, startBottom - minEdge)
                                NormalizedRect(newX, newY, startRight - newX, startBottom - newY)
                            }
                            CropCorner.TOP_RIGHT -> {
                                val newRight = (startRight + dx).coerceIn(startCrop.x + minEdge, 1f)
                                val newY = (startCrop.y + dy).coerceIn(0f, startBottom - minEdge)
                                NormalizedRect(startCrop.x, newY, newRight - startCrop.x, startBottom - newY)
                            }
                            CropCorner.BOTTOM_LEFT -> {
                                val newX = (startCrop.x + dx).coerceIn(0f, startRight - minEdge)
                                val newBottom = (startBottom + dy).coerceIn(startCrop.y + minEdge, 1f)
                                NormalizedRect(newX, startCrop.y, startRight - newX, newBottom - startCrop.y)
                            }
                            CropCorner.BOTTOM_RIGHT -> {
                                val newRight = (startRight + dx).coerceIn(startCrop.x + minEdge, 1f)
                                val newBottom = (startBottom + dy).coerceIn(startCrop.y + minEdge, 1f)
                                NormalizedRect(startCrop.x, startCrop.y, newRight - startCrop.x, newBottom - startCrop.y)
                            }
                        }
                        onUpdateBase(
                            gestureBase.copy(
                                crop = ProjectCore.normalizeFreeCrop(rect),
                                outputAspect = "free"
                            )
                        )
                    },
                    onDragEnd = { onCommitTransaction() },
                    onDragCancel = { onCancelTransaction() }
                )
            }
    )
}
