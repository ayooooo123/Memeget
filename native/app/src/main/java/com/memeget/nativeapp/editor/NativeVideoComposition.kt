package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import expo.modules.memegetbg.MemeMediaProbe
import expo.modules.memegetbg.RetainedRangeComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(UnstableApi::class)
internal object NativeVideoComposition {

    data class ExportProgress(
        val fraction: Float,
        val stage: String = "exporting"
    )

    data class ExportOutcome(
        val file: File,
        val durationUs: Long,
        val width: Int,
        val height: Int
    )

    data class VideoSize(val width: Int, val height: Int)

    data class EncoderBounds(
        val maxWidth: Int,
        val maxHeight: Int,
        val widthAlignment: Int = 2,
        val heightAlignment: Int = 2,
        val maxPixels: Long = 1920L * 1080L * 2L,
        val isSupported: (width: Int, height: Int) -> Boolean = { w, h ->
            w in 2..maxWidth && h in 2..maxHeight && (w.toLong() * h.toLong()) <= maxPixels
        }
    )

    fun queryEncoderBounds(context: Context): EncoderBounds {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var maxW = 0
        var maxH = 0
        var alignW = 2
        var alignH = 2
        var foundAny = false

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(MimeTypes.VIDEO_H264, ignoreCase = true)) {
                    val caps = info.getCapabilitiesForType(type)
                    val vCaps = caps.videoCapabilities ?: continue
                    foundAny = true
                    maxW = maxOf(maxW, vCaps.supportedWidths.upper)
                    maxH = maxOf(maxH, vCaps.supportedHeights.upper)
                    alignW = maxOf(alignW, vCaps.widthAlignment)
                    alignH = maxOf(alignH, vCaps.heightAlignment)
                }
            }
        }
        if (!foundAny) {
            throw IOException("No H.264 video encoder found on device")
        }
        val maxPx = minOf(maxW.toLong() * maxH.toLong(), 3840L * 2160L)
        return EncoderBounds(
            maxWidth = maxW,
            maxHeight = maxH,
            widthAlignment = maxOf(2, alignW),
            heightAlignment = maxOf(2, alignH),
            maxPixels = maxPx,
            isSupported = { w, h ->
                val format = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, w, h)
                codecList.findEncoderForFormat(format) != null
            }
        )
    }

    fun calculateVideoOutputDimensions(
        rawWidth: Int,
        rawHeight: Int,
        bounds: EncoderBounds = EncoderBounds(
            maxWidth = 1920,
            maxHeight = 1920,
            widthAlignment = 2,
            heightAlignment = 2,
            maxPixels = 1920L * 1080L * 2L
        )
    ): VideoSize {
        require(rawWidth > 0 && rawHeight > 0) {
            "Video rawWidth ($rawWidth) and rawHeight ($rawHeight) must be positive"
        }
        val alignW = max(2, bounds.widthAlignment)
        val alignH = max(2, bounds.heightAlignment)
        val maxW = bounds.maxWidth
        val maxH = bounds.maxHeight
        val maxPx = bounds.maxPixels

        if (maxW < alignW || maxH < alignH || maxPx < (alignW.toLong() * alignH.toLong())) {
            throw IllegalArgumentException(
                "Encoder bounds (maxWidth=$maxW, maxHeight=$maxH, maxPixels=$maxPx) cannot accommodate minimum alignment ($alignW x $alignH)"
            )
        }

        val scaleW = maxW.toDouble() / rawWidth.toDouble()
        val scaleH = maxH.toDouble() / rawHeight.toDouble()
        val scalePx = kotlin.math.sqrt(maxPx.toDouble() / (rawWidth.toLong() * rawHeight.toLong()).toDouble())
        var currentScale = minOf(1.0, scaleW, scaleH, scalePx)

        while (currentScale > 0.0) {
            var w = (kotlin.math.round((currentScale * rawWidth) / alignW.toDouble()) * alignW).toInt()
            var h = (kotlin.math.round((currentScale * rawHeight) / alignH.toDouble()) * alignH).toInt()

            if (w > maxW) {
                w = (maxW / alignW) * alignW
            }
            if (h > maxH) {
                h = (maxH / alignH) * alignH
            }

            if (w < alignW || h < alignH) {
                break
            }

            val withinPixels = (w.toLong() * h.toLong()) <= maxPx
            if (withinPixels && bounds.isSupported(w, h)) {
                return VideoSize(w, h)
            }

            val nextScaleW = (w - alignW).toDouble() / rawWidth.toDouble()
            val nextScaleH = (h - alignH).toDouble() / rawHeight.toDouble()
            val nextScale = minOf(nextScaleW, nextScaleH)

            if (nextScale >= currentScale || nextScale <= 0.0) {
                break
            }
            currentScale = nextScale
        }

        throw IllegalArgumentException(
            "No supported video output size for dimensions ${rawWidth}x${rawHeight} within encoder bounds (${maxW}x$maxH, align ${alignW}x$alignH, maxPx=$maxPx)"
        )
    }
    /** Media3 retains bitmap inputs until its render thread has released the composition. */
    internal class PreparedVideoComposition internal constructor(
        val composition: Composition,
        val outputWidth: Int,
        val outputHeight: Int,
        val durationUs: Long,
        private val compSegments: List<ProjectCore.CompositionSegment>,
        private val speed: Float
    ) {

        fun outputToSourceTimeUs(outputUs: Long): Long? =
            ProjectCore.mapOutputTimeToSourceTime(outputUs, compSegments, speed)

        fun sourceToOutputTimeUs(sourceUs: Long): Long? =
            mapSourceTimeToOutputTime(sourceUs, compSegments, speed)

    }

    suspend fun prepare(
        context: Context,
        project: MemeEditProject
    ): PreparedVideoComposition = withContext(Dispatchers.IO) {
        val videoSpec = project.video
            ?: throw IllegalArgumentException("Project is not a video project")

        val sourceUri = project.materializedSourceUri ?: project.source.uri
        val probe = try {
            MemeMediaProbe.probe(context, sourceUri)
        } catch (e: Exception) {
            throw IOException("Failed to probe video: ${e.message}", e)
        }

        val sourceDurationUs = probe.durationUs ?: project.source.durationUs ?: 1_000_000L
        val sourceHasAudio = probe.hasAudio

        // Upright display size: rotationDegrees is intrinsic orientation, not user rotation.
        val isRotated = probe.rotationDegrees == 90 || probe.rotationDegrees == 270
        val probeW = if (isRotated) probe.height else probe.width
        val probeH = if (isRotated) probe.width else probe.height
        // Prefer project source dims when they already reflect upright size.
        val sourceW = if (project.source.width > 0) project.source.width else probeW
        val sourceH = if (project.source.height > 0) project.source.height else probeH

        val outSize = RenderPlanBuilder.calculateOutputSize(
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            base = project.base
        )
        val bounds = queryEncoderBounds(context)
        val videoSize = calculateVideoOutputDimensions(
            rawWidth = outSize.widthPx,
            rawHeight = outSize.heightPx,
            bounds = bounds
        )
        val outputWidth = videoSize.width
        val outputHeight = videoSize.height

        val retainedRanges = if (videoSpec.retainedRanges.isNotEmpty()) {
            videoSpec.retainedRanges
        } else {
            listOf(TimeRangeUs(0L, sourceDurationUs))
        }

        val compSegments = ProjectCore.buildCompositionSegments(
            ranges = retainedRanges,
            cards = videoSpec.insertedCards,
            speed = videoSpec.speed,
            durationUs = sourceDurationUs
        )
        if (compSegments.isEmpty()) {
            throw IOException("Composition has no retained ranges")
        }

        val segments = compSegments.map { seg ->
            when (seg) {
                is ProjectCore.CompositionSegment.Source ->
                    RetainedRangeComposition.Range(seg.sourceStartUs, seg.sourceEndUs)
                is ProjectCore.CompositionSegment.Card -> {
                    val mime = titleCardMime(seg.mimeType, seg.uri)
                    val rejections = RetainedRangeComposition.inspectAssets(
                        context,
                        listOf(
                            RetainedRangeComposition.AssetRequirement(
                                uri = seg.uri,
                                role = RetainedRangeComposition.AssetRole.TITLE_CARD,
                                declaredMimeType = mime
                            )
                        )
                    )
                    if (rejections.isNotEmpty()) {
                        throw IOException(rejections.first().reason)
                    }
                    RetainedRangeComposition.Card(
                        uri = seg.uri,
                        mimeType = mime,
                        durationUs = seg.timelineDurationUs
                    )
                }
            }
        }

        val retainedBitmaps = mutableListOf<Bitmap>()
        val cutoutBitmaps = mutableMapOf<String, Bitmap>()
        val mediaBitmaps = mutableMapOf<String, Bitmap>()
        try {
            for (layer in project.layers) {
                when (layer) {
                    is MemeEditLayer.Subject -> {
                        val uriStr = layer.cutoutUri.ifBlank {
                            project.transientMaskTracks[layer.maskTrackId] ?: ""
                        }
                        if (uriStr.isBlank()) throw IOException("Subject cutout is missing for layer ${layer.id}")
                        val bmp = VideoBackgroundEffect.decodeOrientedBitmap(context, uriStr, max(outputWidth, outputHeight))
                        cutoutBitmaps[layer.id] = bmp
                        retainedBitmaps.add(bmp)
                    }
                    is MemeEditLayer.Media -> {
                        if (layer.assetKind == "video") throw IOException("Video stickers are not supported; use a video title card")
                        if (layer.assetUri.isBlank()) throw IOException("Media is missing for layer ${layer.id}")
                        val bmp = VideoBackgroundEffect.decodeOrientedBitmap(context, layer.assetUri, max(outputWidth, outputHeight))
                        mediaBitmaps[layer.id] = bmp
                        retainedBitmaps.add(bmp)
                    }
                    else -> Unit
                }
            }

            val backgroundImage = when {
                project.background.mode == "image" && !project.background.assetUri.isNullOrBlank() -> {
                    val bmp = VideoBackgroundEffect.decodeOrientedBitmap(
                        context,
                        project.background.assetUri!!,
                        maxEdge = max(outputWidth, outputHeight)
                    )
                    retainedBitmaps.add(bmp)
                    bmp
                }
                else -> null
            }

            val geometryEffects = buildGeometryEffects(project.base)
            val bgColor = try {
                Color.parseColor(project.background.color)
            } catch (_: Exception) {
                Color.BLACK
            }
            val backgroundEffect = VideoBackgroundEffect(
                mode = project.background.mode,
                colorArgb = bgColor,
                blurScale = project.background.blurScale,
                imageBitmap = backgroundImage
            )


            val sourceVideoEffects = buildList<Effect> {
                addAll(geometryEffects)
                if (project.background.mode != "source" && project.background.mode != "video") {
                    add(backgroundEffect)
                }
            }

            // Flush ordinary layers around each mosaic so a cover sees exactly the
            // layers below it. A later solid correction remains in the same z-order.
            val compositionVideoEffects = mutableListOf<Effect>()
            val overlayLayers = mutableListOf<MemeEditLayer>()
            fun flushOverlay() {
                if (overlayLayers.isEmpty()) return
                val overlay = TimeAwareVideoOverlay(
                    context = context.applicationContext,
                    layers = overlayLayers.toList(),
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    segments = compSegments,
                    speed = videoSpec.speed,
                    cutoutBitmaps = cutoutBitmaps,
                    mediaBitmaps = mediaBitmaps
                )
                overlay.setCanvasSize(outputWidth, outputHeight)
                compositionVideoEffects.add(OverlayEffect(listOf(overlay)))
                overlayLayers.clear()
            }
            for (layer in project.layers) {
                if (layer is MemeEditLayer.Cover &&
                    (layer.mode == "pixelate" || layer.corrections.any { it.mode == "pixelate" })) {
                    flushOverlay()
                    compositionVideoEffects.add(VideoPixelateEffect { outputUs, region ->
                        val sourceUs = ProjectCore.mapOutputTimeToSourceTime(outputUs, compSegments, videoSpec.speed)
                        if (sourceUs == null || !ProjectCore.isLayerActiveAt(layer, sourceUs)) {
                            false
                        } else {
                            val correction = ProjectCore.interpolateCoverCorrections(layer.corrections, sourceUs)
                            val rect = correction?.first ?: layer.rect
                            if ((correction?.second ?: layer.mode) != "pixelate") {
                                false
                            } else {
                                region.left = rect.x
                                region.top = rect.y
                                region.right = rect.x + rect.width
                                region.bottom = rect.y + rect.height
                                region.cellPx = layer.pixelSize.toFloat()
                                true
                            }
                        }
                    })
                }
                overlayLayers.add(layer)
            }
            flushOverlay()

            val sourceGain = if (videoSpec.audio.muted) 0f else videoSpec.audio.volume.coerceIn(0f, 2f)
            val audioProcessors: List<AudioProcessor> =
                if (sourceGain == 1f) emptyList()
                else listOf(GainProcessor(DefaultGainProvider.Builder(sourceGain).build()))

            val musicUri = videoSpec.music?.uri
            val musicVolume = (videoSpec.music?.volume ?: 1f).coerceIn(0f, 2f)
            val musicStartUs = videoSpec.music?.startUs ?: 0L


            val composition = RetainedRangeComposition.buildTimeline(
                uri = sourceUri,
                sourceDurationUs = sourceDurationUs,
                segments = segments,
                speed = videoSpec.speed,
                sourceHasAudio = sourceHasAudio,
                outputSize = Size(outputWidth, outputHeight),
                audioProcessors = audioProcessors,
                sourceVideoEffects = sourceVideoEffects,
                videoEffects = compositionVideoEffects,
                musicUri = musicUri,
                musicVolume = musicVolume,
                musicStartUs = musicStartUs
            )


            val durationUs = RetainedRangeComposition.expectedOutputDurationUs(segments, videoSpec.speed)

            PreparedVideoComposition(
                composition = composition,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                durationUs = durationUs,
                compSegments = compSegments,
                speed = videoSpec.speed
            )
        } catch (error: Throwable) {
            for (bmp in retainedBitmaps) {
                if (!bmp.isRecycled) bmp.recycle()
            }
            retainedBitmaps.clear()
            throw error
        }
    }

    suspend fun exportVideo(
        context: Context,
        project: MemeEditProject,
        exportId: String = "export_${System.currentTimeMillis()}",
        onProgress: (ExportProgress) -> Unit = {}
    ): ExportOutcome {
        val prepared = prepare(context, project)
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        val outputFile = File(exportDir, "$exportId.mp4")
        if (outputFile.exists()) outputFile.delete()

        return withContext(Dispatchers.Main.immediate) {
            val completionDeferred = CompletableDeferred<ExportOutcome>()
            val progressHolder = ProgressHolder()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        completionDeferred.completeExceptionally(
                            IOException("Encoder produced an empty file")
                        )
                    } else {
                        val finalWidth = if (exportResult.width > 0) exportResult.width else prepared.outputWidth
                        val finalHeight = if (exportResult.height > 0) exportResult.height else prepared.outputHeight
                        val finalDurationUs = if (exportResult.approximateDurationMs > 0L) {
                            exportResult.approximateDurationMs * 1_000L
                        } else {
                            prepared.durationUs
                        }
                        completionDeferred.complete(
                            ExportOutcome(
                                file = outputFile,
                                durationUs = finalDurationUs,
                                width = finalWidth,
                                height = finalHeight
                            )
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (outputFile.exists()) outputFile.delete()
                    completionDeferred.completeExceptionally(exportException)
                }
            }

            val builtTransformer = Transformer.Builder(context)
                .addListener(listener)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            try {
                builtTransformer.start(prepared.composition, outputFile.absolutePath)
                while (!completionDeferred.isCompleted) {
                    val state = builtTransformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val fraction = progressHolder.progress.coerceIn(0, 100) / 100f
                        onProgress(ExportProgress(fraction, "exporting"))
                    }
                    withTimeoutOrNull(100L) {
                        completionDeferred.await()
                    }
                }
                completionDeferred.await()
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    builtTransformer.cancel()
                    if (outputFile.exists()) outputFile.delete()
                }
                throw ce
            } catch (error: Exception) {
                builtTransformer.cancel()
                if (outputFile.exists()) outputFile.delete()
                throw error
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun mapSourceTimeToOutputTime(
        sourceTimeUs: Long,
        segments: List<ProjectCore.CompositionSegment>,
        speed: Float
    ): Long? {
        if (segments.isEmpty() || sourceTimeUs < 0L) return null
        for (seg in segments) {
            when (seg) {
                is ProjectCore.CompositionSegment.Source -> {
                    if (sourceTimeUs in seg.sourceStartUs..seg.sourceEndUs) {
                        val srcOffset = sourceTimeUs - seg.sourceStartUs
                        val outOffset = kotlin.math.round(srcOffset.toDouble() / speed.toDouble()).toLong()
                        return seg.outputStartUs + outOffset
                    }
                }
                is ProjectCore.CompositionSegment.Card -> Unit
            }
        }
        return null
    }


    private fun titleCardMime(declared: String, uri: String): String {
        if (declared in RetainedRangeComposition.CARD_MIME_TYPES) return declared
        val lower = uri.lowercase()
        return when {
            lower.endsWith(".png") -> MimeTypes.IMAGE_PNG
            lower.endsWith(".webp") -> MimeTypes.IMAGE_WEBP
            lower.endsWith(".bmp") -> MimeTypes.IMAGE_BMP
            lower.endsWith(".heic") || lower.endsWith(".heif") -> MimeTypes.IMAGE_HEIF
            else -> MimeTypes.IMAGE_JPEG
        }
    }

    private fun buildGeometryEffects(base: BaseTransform): List<Effect> {
        val effects = mutableListOf<Effect>()
        val rotation = base.rotation.degrees
        if (rotation != 0) {
            // media3 rotates counter-clockwise in a y-up frame; project rotation is clockwise canvas.
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees((360 - rotation).toFloat())
                    .build()
            )
        }
        if (base.flipX || base.flipY) {
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (base.flipX) -1f else 1f, if (base.flipY) -1f else 1f)
                    .build()
            )
        }
        val crop = base.crop
        val fullFrame = crop.x == 0f && crop.y == 0f && crop.width == 1f && crop.height == 1f
        if (!fullFrame && crop.width > 0f && crop.height > 0f) {
            effects.add(
                Crop(
                    /* left = */ 2f * crop.x - 1f,
                    /* right = */ 2f * (crop.x + crop.width) - 1f,
                    /* bottom = */ 1f - 2f * (crop.y + crop.height),
                    /* top = */ 1f - 2f * crop.y
                )
            )
        }
        return effects
    }



    /**
     * Time-aware burn-in for text, solid covers, subjects, media, drawings.
     * Pixelation is handled by [VideoPixelateEffect] on the frame texture.
     */
    private class TimeAwareVideoOverlay(
        private val context: Context,
        private val layers: List<MemeEditLayer>,
        private val outputWidth: Int,
        private val outputHeight: Int,
        private val segments: List<ProjectCore.CompositionSegment>,
        private val speed: Float,
        private val cutoutBitmaps: Map<String, Bitmap>,
        private val mediaBitmaps: Map<String, Bitmap>
    ) : CanvasOverlay(/* useInputFrameSize= */ false) {

        private val clearPaint = Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scratchMatrix = Matrix()
        private val alphaCache = HashMap<String, Bitmap>()
        override fun release() {
            try {
                super.release()
            } finally {
                for (bitmap in alphaCache.values) bitmap.recycle()
                alphaCache.clear()
            }
        }

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            canvas.drawPaint(clearPaint)

            val sourceTimeUs = ProjectCore.mapOutputTimeToSourceTime(presentationTimeUs, segments, speed)
                ?: return

            for (layer in layers) {
                if (!ProjectCore.isLayerActiveAt(layer, sourceTimeUs)) continue
                when (layer) {
                    is MemeEditLayer.Cover -> drawSolidCover(canvas, layer, sourceTimeUs)
                    is MemeEditLayer.Text -> {
                        val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, sourceTimeUs)
                            ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                        TextLayoutBridge.drawTextLayer(
                            canvas = canvas,
                            context = context,
                            layer = layer,
                            transform = transform,
                            canvasWidthPx = outputWidth,
                            canvasHeightPx = outputHeight
                        )
                    }
                    is MemeEditLayer.Subject -> drawSubject(canvas, layer, sourceTimeUs)
                    is MemeEditLayer.Media -> drawMedia(canvas, layer, sourceTimeUs)
                    is MemeEditLayer.Draw -> DrawToolCore.drawLayer(canvas, layer, outputWidth, outputHeight)
                }
            }
        }

        private fun drawSolidCover(canvas: Canvas, layer: MemeEditLayer.Cover, sourceTimeUs: Long) {
            val corr = ProjectCore.interpolateCoverCorrections(layer.corrections, sourceTimeUs)
            val rect = corr?.first ?: layer.rect
            val mode = corr?.second ?: layer.mode
            if (mode == "pixelate") return
            solidPaint.color = try {
                Color.parseColor(layer.color)
            } catch (_: Exception) {
                Color.BLACK
            }
            solidPaint.alpha = 255
            canvas.drawRect(
                rect.x * outputWidth,
                rect.y * outputHeight,
                (rect.x + rect.width) * outputWidth,
                (rect.y + rect.height) * outputHeight,
                solidPaint
            )
        }

        private fun drawSubject(canvas: Canvas, layer: MemeEditLayer.Subject, sourceTimeUs: Long) {
            val cutout = cutoutBitmaps[layer.id] ?: return
            if (cutout.isRecycled) return
            val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, sourceTimeUs)
                ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
            val placement = RenderPlanBuilder.resolveCutoutPlacement(
                outlineColor = layer.outlineColor,
                outlineScale = layer.outlineScale,
                shadowScale = layer.shadowScale,
                transform = transform,
                trackRect = layer.bounds,
                canvasWidthPx = outputWidth,
                canvasHeightPx = outputHeight
            )
            val dest = RectF(
                placement.rectX.toFloat(),
                placement.rectY.toFloat(),
                (placement.rectX + placement.rectW).toFloat(),
                (placement.rectY + placement.rectH).toFloat()
            )
            if (dest.width() <= 0f || dest.height() <= 0f) return
            val opacity = (placement.opacity * 255f).coerceIn(0f, 255f).roundToInt()
            val silhouette = alphaSilhouette(layer.id, cutout)
            val checkpoint = canvas.save()
            try {
                canvas.rotate(placement.rotationDegrees, dest.centerX(), dest.centerY())
                if (silhouette != null && (placement.shadowBlurPx > 0f || placement.shadowOffsetPx > 0f)) {
                    drawCutoutShadow(canvas, silhouette, cutout, dest, placement.shadowBlurPx, placement.shadowOffsetPx, opacity)
                }
                if (silhouette != null && placement.outlinePx > 0f && !layer.outlineColor.isNullOrBlank()) {
                    val outlineColor = try {
                        Color.parseColor(layer.outlineColor)
                    } catch (_: Exception) {
                        Color.WHITE
                    }
                    drawCutoutOutline(canvas, silhouette, cutout, dest, placement.outlinePx, outlineColor, opacity)
                }
                bitmapPaint.alpha = opacity
                canvas.drawBitmap(cutout, null, dest, bitmapPaint)
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }

        private fun alphaSilhouette(layerId: String, cutout: Bitmap): Bitmap? {
            alphaCache[layerId]?.let { if (!it.isRecycled) return it }
            return try {
                cutout.extractAlpha().also { alphaCache[layerId] = it }
            } catch (_: Exception) {
                null
            }
        }

        private fun drawCutoutShadow(
            canvas: Canvas,
            silhouette: Bitmap,
            cutout: Bitmap,
            dest: RectF,
            blurPx: Float,
            offsetPx: Float,
            alpha: Int
        ) {
            effectPaint.reset()
            effectPaint.isAntiAlias = true
            effectPaint.color = 0x8C000000.toInt()
            effectPaint.alpha = (alpha * 0.55f).roundToInt().coerceIn(0, 255)
            effectPaint.maskFilter = if (blurPx >= 0.5f) {
                BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
            scratchMatrix.reset()
            scratchMatrix.setScale(
                dest.width() / max(1, cutout.width).toFloat(),
                dest.height() / max(1, cutout.height).toFloat()
            )
            scratchMatrix.postTranslate(dest.left + offsetPx, dest.top + offsetPx)
            canvas.drawBitmap(silhouette, scratchMatrix, effectPaint)
            effectPaint.maskFilter = null
        }

        private fun drawCutoutOutline(
            canvas: Canvas,
            silhouette: Bitmap,
            cutout: Bitmap,
            dest: RectF,
            radiusPx: Float,
            color: Int,
            alpha: Int
        ) {
            effectPaint.reset()
            effectPaint.isAntiAlias = true
            effectPaint.color = color
            effectPaint.alpha = alpha
            effectPaint.maskFilter = null
            val scaleX = dest.width() / max(1, cutout.width).toFloat()
            val scaleY = dest.height() / max(1, cutout.height).toFloat()
            for (i in 0 until 16) {
                val angle = (Math.PI * 2.0 * i) / 16.0
                scratchMatrix.reset()
                scratchMatrix.setScale(scaleX, scaleY)
                scratchMatrix.postTranslate(
                    dest.left + (cos(angle) * radiusPx).toFloat(),
                    dest.top + (sin(angle) * radiusPx).toFloat()
                )
                canvas.drawBitmap(silhouette, scratchMatrix, effectPaint)
            }
        }

        private fun drawMedia(canvas: Canvas, layer: MemeEditLayer.Media, sourceTimeUs: Long) {
            val mediaBmp = mediaBitmaps[layer.id] ?: return
            if (mediaBmp.isRecycled) return
            val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, sourceTimeUs)
                ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
            val side = outputWidth * RenderPlanBuilder.MEME_MEDIA_LAYER_BASE_WIDTH * max(0.01f, transform.scale)
            val cx = transform.center.x.coerceIn(0f, 1f) * outputWidth
            val cy = transform.center.y.coerceIn(0f, 1f) * outputHeight
            val dest = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
            val fitCover = layer.fit == "cover"
            val scale = if (fitCover) {
                max(dest.width() / mediaBmp.width, dest.height() / mediaBmp.height)
            } else {
                min(dest.width() / mediaBmp.width, dest.height() / mediaBmp.height)
            }
            val drawnW = mediaBmp.width * scale
            val drawnH = mediaBmp.height * scale
            bitmapPaint.alpha = (transform.opacity * 255f).coerceIn(0f, 255f).roundToInt()
            scratchMatrix.reset()
            scratchMatrix.postScale(scale, scale)
            scratchMatrix.postTranslate(dest.centerX() - drawnW / 2f, dest.centerY() - drawnH / 2f)
            val checkpoint = canvas.save()
            try {
                canvas.rotate(transform.rotationDegrees, dest.centerX(), dest.centerY())
                if (fitCover) canvas.clipRect(dest)
                canvas.drawBitmap(mediaBmp, scratchMatrix, bitmapPaint)
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}
