package com.memeget.nativeapp.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.pow

internal object ProjectCore {

    fun clampUnit(value: Float): Float = value.coerceIn(0f, 1f)

    fun clampNormalizedPoint(point: NormalizedPoint): NormalizedPoint =
        NormalizedPoint(clampUnit(point.x), clampUnit(point.y))

    fun clampNormalizedRect(rect: NormalizedRect): NormalizedRect {
        val x = clampUnit(rect.x)
        val y = clampUnit(rect.y)
        val width = min(clampUnit(rect.width), 1f - x)
        val height = min(clampUnit(rect.height), 1f - y)
        return NormalizedRect(x, y, max(0.01f, width), max(0.01f, height))
    }

    fun rotateNormalizedPoint(point: NormalizedPoint, rotation: QuarterRotation): NormalizedPoint =
        when (rotation) {
            QuarterRotation.R0 -> point
            QuarterRotation.R90 -> NormalizedPoint(1f - point.y, point.x)
            QuarterRotation.R180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            QuarterRotation.R270 -> NormalizedPoint(point.y, 1f - point.x)
        }

    fun rotateNormalizedRect(rect: NormalizedRect, rotation: QuarterRotation): NormalizedRect {
        val p1 = rotateNormalizedPoint(NormalizedPoint(rect.x, rect.y), rotation)
        val p2 = rotateNormalizedPoint(NormalizedPoint(rect.x + rect.width, rect.y + rect.height), rotation)
        val minX = min(p1.x, p2.x)
        val minY = min(p1.y, p2.y)
        val maxX = max(p1.x, p2.x)
        val maxY = max(p1.y, p2.y)
        return NormalizedRect(minX, minY, maxX - minX, maxY - minY)
    }

    fun mapPointToCroppedCanvas(point: NormalizedPoint, crop: NormalizedRect): NormalizedPoint {
        val safeW = if (crop.width > 0.0001f) crop.width else 1f
        val safeH = if (crop.height > 0.0001f) crop.height else 1f
        return NormalizedPoint(
            clampUnit((point.x - crop.x) / safeW),
            clampUnit((point.y - crop.y) / safeH)
        )
    }

    fun mapRectToCroppedCanvas(rect: NormalizedRect, crop: NormalizedRect): NormalizedRect {
        val safeW = if (crop.width > 0.0001f) crop.width else 1f
        val safeH = if (crop.height > 0.0001f) crop.height else 1f
        val x = clampUnit((rect.x - crop.x) / safeW)
        val y = clampUnit((rect.y - crop.y) / safeH)
        val width = min(rect.width / safeW, 1f - x)
        val height = min(rect.height / safeH, 1f - y)
        return NormalizedRect(x, y, max(0.001f, width), max(0.001f, height))
    }

    fun transformPointToWorkingCanvas(point: NormalizedPoint, base: BaseTransform): NormalizedPoint {
        var p = point
        if (base.flipX) p = NormalizedPoint(1f - p.x, p.y)
        if (base.flipY) p = NormalizedPoint(p.x, 1f - p.y)
        p = rotateNormalizedPoint(p, base.rotation)
        return mapPointToCroppedCanvas(p, base.crop)
    }

    fun transformRectToWorkingCanvas(rect: NormalizedRect, base: BaseTransform): NormalizedRect {
        var r = rect
        if (base.flipX) r = NormalizedRect(1f - (r.x + r.width), r.y, r.width, r.height)
        if (base.flipY) r = NormalizedRect(r.x, 1f - (r.y + r.height), r.width, r.height)
        r = rotateNormalizedRect(r, base.rotation)
        return mapRectToCroppedCanvas(r, base.crop)
    }

    const val MIN_NORMALIZED_CROP_EDGE = 0.05f
    const val MIN_NORMALIZED_CROP_AREA = 0.01f

    fun normalizeFreeCrop(
        crop: NormalizedRect,
        fallback: NormalizedRect = NormalizedRect.Full
    ): NormalizedRect {
        var width = if (crop.width.isFinite() && crop.width > 0f) crop.width else fallback.width
        var height = if (crop.height.isFinite() && crop.height > 0f) crop.height else fallback.height
        width = width.coerceIn(MIN_NORMALIZED_CROP_EDGE, 1f)
        height = height.coerceIn(MIN_NORMALIZED_CROP_EDGE, 1f)
        if (width * height < MIN_NORMALIZED_CROP_AREA) {
            val expansion = kotlin.math.sqrt(MIN_NORMALIZED_CROP_AREA / (width * height))
            width = (width * expansion).coerceIn(MIN_NORMALIZED_CROP_EDGE, 1f)
            height = (height * expansion).coerceIn(MIN_NORMALIZED_CROP_EDGE, 1f)
        }
        val x = (if (crop.x.isFinite()) crop.x else fallback.x).coerceIn(0f, 1f - width)
        val y = (if (crop.y.isFinite()) crop.y else fallback.y).coerceIn(0f, 1f - height)
        return NormalizedRect(x, y, width, height)
    }

    fun presetAspect(preset: String): Float = when (preset) {
        "1:1" -> 1f
        "4:5" -> 4f / 5f
        "9:16" -> 9f / 16f
        "16:9" -> 16f / 9f
        else -> 1f
    }

    fun orientedImageDimensions(width: Int, height: Int, rotation: QuarterRotation): Pair<Int, Int> =
        if (rotation == QuarterRotation.R90 || rotation == QuarterRotation.R270) Pair(height, width)
        else Pair(width, height)

    fun applyCropPreset(base: BaseTransform, preset: String, sourceWidth: Int, sourceHeight: Int): BaseTransform {
        if (preset == "free") {
            return base.copy(crop = normalizeFreeCrop(base.crop), outputAspect = "free")
        }
        if (preset == "source") {
            return base.copy(crop = NormalizedRect.Full, outputAspect = "source")
        }
        val (orientedW, orientedH) = orientedImageDimensions(sourceWidth, sourceHeight, base.rotation)
        val targetAspect = presetAspect(preset)
        val normalizedRatio = targetAspect * orientedH.toFloat() / orientedW.toFloat()
        val width = if (normalizedRatio <= 1f) normalizedRatio else 1f
        val height = if (normalizedRatio <= 1f) 1f else 1f / normalizedRatio
        return base.copy(
            crop = NormalizedRect(
                x = (1f - width) / 2f,
                y = (1f - height) / 2f,
                width = width,
                height = height
            ),
            outputAspect = preset
        )
    }

    fun visibleImageDimensions(sourceWidth: Int, sourceHeight: Int, base: BaseTransform): Pair<Int, Int> {
        val (orientedW, orientedH) = orientedImageDimensions(sourceWidth, sourceHeight, base.rotation)
        val crop = normalizeFreeCrop(base.crop)
        return Pair(
            max(1, (orientedW.toFloat() * crop.width).toInt()),
            max(1, (orientedH.toFloat() * crop.height).toInt())
        )
    }

    fun rotateSourcePoint(point: NormalizedPoint, rotation: QuarterRotation): NormalizedPoint =
        when (rotation) {
            QuarterRotation.R90 -> NormalizedPoint(1f - point.y, point.x)
            QuarterRotation.R180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            QuarterRotation.R270 -> NormalizedPoint(point.y, 1f - point.x)
            else -> point
        }

    fun unrotateSourcePoint(point: NormalizedPoint, rotation: QuarterRotation): NormalizedPoint =
        when (rotation) {
            QuarterRotation.R90 -> NormalizedPoint(point.y, 1f - point.x)
            QuarterRotation.R180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            QuarterRotation.R270 -> NormalizedPoint(1f - point.y, point.x)
            else -> point
        }

    fun workingToSource(point: NormalizedPoint, base: BaseTransform): NormalizedPoint {
        val crop = normalizeFreeCrop(base.crop)
        var x = crop.x + point.x * crop.width
        var y = crop.y + point.y * crop.height
        if (base.flipX) x = 1f - x
        if (base.flipY) y = 1f - y
        return unrotateSourcePoint(NormalizedPoint(x, y), base.rotation)
    }

    fun sourceToWorking(point: NormalizedPoint, base: BaseTransform): NormalizedPoint {
        val crop = normalizeFreeCrop(base.crop)
        val rotated = rotateSourcePoint(point, base.rotation)
        val x = if (base.flipX) 1f - rotated.x else rotated.x
        val y = if (base.flipY) 1f - rotated.y else rotated.y
        return NormalizedPoint(
            (x - crop.x) / crop.width,
            (y - crop.y) / crop.height
        )
    }

    fun remapPointRaw(point: NormalizedPoint, oldBase: BaseTransform, newBase: BaseTransform): NormalizedPoint =
        sourceToWorking(workingToSource(point, oldBase), newBase)

    fun remapNormalizedPoint(point: NormalizedPoint, oldBase: BaseTransform, newBase: BaseTransform): NormalizedPoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val mapped = remapPointRaw(point, oldBase, newBase)
        val epsilon = 1e-6f
        if (mapped.x < -epsilon || mapped.y < -epsilon || mapped.x > 1f + epsilon || mapped.y > 1f + epsilon) {
            return null
        }
        return NormalizedPoint(mapped.x.coerceIn(0f, 1f), mapped.y.coerceIn(0f, 1f))
    }

    fun remapNormalizedRect(rect: NormalizedRect, oldBase: BaseTransform, newBase: BaseTransform): NormalizedRect? {
        val corners = listOf(
            NormalizedPoint(rect.x, rect.y),
            NormalizedPoint(rect.x + rect.width, rect.y),
            NormalizedPoint(rect.x, rect.y + rect.height),
            NormalizedPoint(rect.x + rect.width, rect.y + rect.height)
        ).map { remapPointRaw(it, oldBase, newBase) }
        val left = max(0f, corners.minOf { it.x })
        val top = max(0f, corners.minOf { it.y })
        val right = min(1f, corners.maxOf { it.x })
        val bottom = min(1f, corners.maxOf { it.y })
        if (right <= left || bottom <= top) return null
        return NormalizedRect(left, top, right - left, bottom - top)
    }

    fun remapVector(vector: NormalizedPoint, oldBase: BaseTransform, newBase: BaseTransform): NormalizedPoint {
        val origin = remapPointRaw(NormalizedPoint(0f, 0f), oldBase, newBase)
        val endpoint = remapPointRaw(vector, oldBase, newBase)
        return NormalizedPoint(endpoint.x - origin.x, endpoint.y - origin.y)
    }

    fun remapKeyframe(
        keyframe: TransformKeyframe,
        oldBase: BaseTransform,
        newBase: BaseTransform,
        oldDisplayW: Int,
        oldDisplayH: Int,
        newDisplayW: Int,
        newDisplayH: Int,
        remapScale: Boolean
    ): TransformKeyframe? {
        val center = remapNormalizedPoint(keyframe.center, oldBase, newBase) ?: return null
        val radians = Math.toRadians(keyframe.rotationDegrees.toDouble())
        val direction = remapVector(
            NormalizedPoint(
                (kotlin.math.cos(radians) / oldDisplayW).toFloat(),
                (kotlin.math.sin(radians) / oldDisplayH).toFloat()
            ),
            oldBase,
            newBase
        )
        val pixelDirectionX = direction.x * newDisplayW.toFloat()
        val pixelDirectionY = direction.y * newDisplayH.toFloat()
        val directionScale = kotlin.math.hypot(pixelDirectionX.toDouble(), pixelDirectionY.toDouble()).toFloat()
        val rotationDelta = ((newBase.rotation.degrees - oldBase.rotation.degrees + 540) % 360) - 180
        return keyframe.copy(
            center = center,
            scale = if (remapScale && directionScale.isFinite() && directionScale > 0f) keyframe.scale * directionScale else keyframe.scale,
            rotationDegrees = (keyframe.rotationDegrees + rotationDelta) % 360f
        )
    }

    fun remapProjectLayers(
        layers: List<MemeEditLayer>,
        oldBase: BaseTransform,
        newBase: BaseTransform,
        sourceW: Int,
        sourceH: Int
    ): List<MemeEditLayer> {
        if (oldBase == newBase) return layers
        val (oldDispW, oldDispH) = visibleImageDimensions(sourceW, sourceH, oldBase)
        val (newDispW, newDispH) = visibleImageDimensions(sourceW, sourceH, newBase)
        val result = mutableListOf<MemeEditLayer>()

        for (layer in layers) {
            when (layer) {
                is MemeEditLayer.Cover -> {
                    val remappedRect = remapNormalizedRect(layer.rect, oldBase, newBase)
                    if (remappedRect != null) {
                        val remappedCorrections = layer.corrections.mapNotNull { corr ->
                            val r = remapNormalizedRect(corr.rect, oldBase, newBase)
                            r?.let { corr.copy(rect = it) }
                        }
                        result.add(layer.copy(rect = remappedRect, corrections = remappedCorrections))
                    }
                }
                is MemeEditLayer.Text -> {
                    val remappedKfs = layer.keyframes.mapNotNull { kf ->
                        remapKeyframe(kf, oldBase, newBase, oldDispW, oldDispH, newDispW, newDispH, remapScale = false)
                    }
                    if (remappedKfs.isNotEmpty()) {
                        val scaleW = oldDispW.toFloat() / newDispW.toFloat()
                        val scaleH = oldDispH.toFloat() / newDispH.toFloat()
                        result.add(
                            layer.copy(
                                width = (layer.width * scaleW).coerceIn(ProjectLimits.MIN_TEXT_WRAP_WIDTH, ProjectLimits.MAX_TEXT_WRAP_WIDTH),
                                fontSize = (layer.fontSize * scaleH).coerceIn(ProjectLimits.MIN_FONT_SIZE, ProjectLimits.MAX_FONT_SIZE),
                                keyframes = remappedKfs
                            )
                        )
                    }
                }
                is MemeEditLayer.Subject -> {
                    val remappedKfs = layer.keyframes.mapNotNull { kf ->
                        remapKeyframe(kf, oldBase, newBase, oldDispW, oldDispH, newDispW, newDispH, remapScale = true)
                    }
                    if (remappedKfs.isNotEmpty()) {
                        result.add(layer.copy(
                            keyframes = remappedKfs,
                            bounds = layer.bounds.copy(
                                width = layer.bounds.width * oldDispW / newDispW,
                                height = layer.bounds.height * oldDispH / newDispH
                            )
                        ))
                    }
                }
                is MemeEditLayer.Media -> {
                    val remappedKfs = layer.keyframes.mapNotNull { kf ->
                        remapKeyframe(kf, oldBase, newBase, oldDispW, oldDispH, newDispW, newDispH, remapScale = true)
                    }
                    if (remappedKfs.isNotEmpty()) {
                        result.add(layer.copy(keyframes = remappedKfs))
                    }
                }
                is MemeEditLayer.Draw -> {
                    val newElements = mutableListOf<DrawElement>()
                    for (elem in layer.elements) {
                        if (elem.shape == DrawShape.FREE) {
                            var run = mutableListOf<NormalizedPoint>()
                            for (pt in elem.points) {
                                val mapped = remapNormalizedPoint(pt, oldBase, newBase)
                                if (mapped != null) run.add(mapped)
                                else {
                                    if (run.isNotEmpty()) {
                                        newElements.add(elem.copy(points = run))
                                        run = mutableListOf()
                                    }
                                }
                            }
                            if (run.isNotEmpty()) newElements.add(elem.copy(points = run))
                        } else {
                            val first = elem.points.firstOrNull()?.let { remapNormalizedPoint(it, oldBase, newBase) }
                            val last = elem.points.lastOrNull()?.let { remapNormalizedPoint(it, oldBase, newBase) }
                            if (first != null && last != null) {
                                newElements.add(elem.copy(points = listOf(first, last)))
                            }
                        }
                    }
                    if (newElements.isNotEmpty()) {
                        result.add(layer.copy(elements = newElements))
                    }
                }
            }
        }
        return result
    }

    // Retained range normalization and duration calculations
    fun normalizeRetainedRanges(ranges: List<TimeRangeUs>, durationUs: Long): List<TimeRangeUs> {
        if (durationUs <= 0L) return emptyList()
        if (ranges.isEmpty()) return listOf(TimeRangeUs(0L, durationUs))

        val sorted = ranges
            .map { TimeRangeUs(it.startUs.coerceIn(0L, durationUs), it.endUs.coerceIn(0L, durationUs)) }
            .filter { it.endUs > it.startUs }
            .sortedBy { it.startUs }

        if (sorted.isEmpty()) return listOf(TimeRangeUs(0L, durationUs))

        val merged = mutableListOf<TimeRangeUs>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startUs < current.endUs) {
                current = TimeRangeUs(current.startUs, max(current.endUs, next.endUs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged.take(ProjectLimits.MAX_RETAINED_RANGES)
    }

    fun outputDurationUs(ranges: List<TimeRangeUs>, speed: Float): Long {
        if (speed <= 0f || ranges.isEmpty()) return 0L
        val retainedUs = ranges.sumOf { it.durationUs }
        return (retainedUs.toDouble() / speed.toDouble()).roundToLong()
    }

    fun sourceTimeToOutputTimeUs(sourceTimeUs: Long, ranges: List<TimeRangeUs>, speed: Float): Long? {
        if (speed <= 0f || ranges.isEmpty()) return null
        var retainedBeforeUs = 0L
        for (i in ranges.indices) {
            val range = ranges[i]
            val isLast = i == ranges.size - 1
            if (sourceTimeUs < range.startUs) return null
            if (sourceTimeUs < range.endUs || (isLast && sourceTimeUs == range.endUs)) {
                return ((retainedBeforeUs + sourceTimeUs - range.startUs).toDouble() / speed.toDouble()).roundToLong()
            }
            retainedBeforeUs += range.durationUs
        }
        return null
    }

    fun outputTimeToSourceTimeUs(outputTimeUs: Long, ranges: List<TimeRangeUs>, speed: Float): Long? {
        if (outputTimeUs < 0L || speed <= 0f || ranges.isEmpty()) return null
        var retainedBeforeUs = 0L
        for (i in ranges.indices) {
            val range = ranges[i]
            val startOutputUs = (retainedBeforeUs.toDouble() / speed.toDouble()).roundToLong()
            if (i > 0 && outputTimeUs == startOutputUs) return range.startUs
            val endOutputUs = ((retainedBeforeUs + range.durationUs).toDouble() / speed.toDouble()).roundToLong()
            val isLast = i == ranges.size - 1
            if (outputTimeUs < endOutputUs || (isLast && outputTimeUs == endOutputUs)) {
                val sourceTimeUs = range.startUs + (outputTimeUs.toDouble() * speed.toDouble() - retainedBeforeUs.toDouble()).roundToLong()
                return sourceTimeUs.coerceIn(range.startUs, range.endUs)
            }
            retainedBeforeUs += range.durationUs
        }
        return null
    }

    sealed interface CompositionSegment {
        val timelineDurationUs: Long
        val outputStartUs: Long
        val outputEndUs: Long

        data class Source(
            val sourceStartUs: Long,
            val sourceEndUs: Long,
            override val timelineDurationUs: Long,
            override val outputStartUs: Long,
            override val outputEndUs: Long
        ) : CompositionSegment

        data class Card(
            val uri: String,
            val mimeType: String,
            override val timelineDurationUs: Long,
            override val outputStartUs: Long,
            override val outputEndUs: Long
        ) : CompositionSegment
    }

    fun buildCompositionSegments(
        ranges: List<TimeRangeUs>,
        cards: List<InsertedCard>,
        speed: Float,
        durationUs: Long
    ): List<CompositionSegment> {
        val normRanges = normalizeRetainedRanges(ranges, durationUs)
        if (normRanges.isEmpty()) return emptyList()

        val cardFreeOutputUs = outputDurationUs(normRanges, speed)

        data class ResolvedCard(
            val card: InsertedCard,
            val mimeType: String,
            val durationUs: Long,
            val boundaryIndex: Int?,
            val rangeIndex: Int,
            val cutSourceUs: Long
        )

        val resolvedCards = cards.mapNotNull { card ->
            val atUs = card.atUs.coerceIn(0L, cardFreeOutputUs)
            val mime = if (card.uri.endsWith(".png", true)) "image/png"
                else if (card.uri.endsWith(".webp", true)) "image/webp"
                else "image/jpeg"
            val dur = card.durationUs.coerceIn(200_000L, 10_000_000L)

            var boundaryIdx: Int? = null
            var rangeIdx = 0
            var cutUs = normRanges[0].startUs

            if (atUs <= 0L) {
                boundaryIdx = 0
                rangeIdx = 0
                cutUs = normRanges[0].startUs
            } else if (atUs >= cardFreeOutputUs) {
                boundaryIdx = normRanges.size
                rangeIdx = normRanges.size - 1
                cutUs = normRanges.last().endUs
            } else {
                val sourceUs = outputTimeToSourceTimeUs(atUs, normRanges, speed) ?: normRanges.last().endUs
                var found = false
                for (i in normRanges.indices) {
                    val r = normRanges[i]
                    if (sourceUs in r.startUs..r.endUs) {
                        rangeIdx = i
                        cutUs = sourceUs
                        if (sourceUs == r.startUs) boundaryIdx = i
                        else if (sourceUs == r.endUs) boundaryIdx = i + 1
                        else boundaryIdx = null
                        found = true
                        break
                    }
                }
                if (!found) {
                    boundaryIdx = normRanges.size
                    rangeIdx = normRanges.size - 1
                    cutUs = normRanges.last().endUs
                }
            }
            ResolvedCard(card, mime, dur, boundaryIdx, rangeIdx, cutUs)
        }.sortedWith(compareBy({ it.card.atUs }, { cards.indexOf(it.card) }))

        val boundaryCards = List(normRanges.size + 1) { mutableListOf<ResolvedCard>() }
        val rangeCuts = List(normRanges.size) { mutableListOf<ResolvedCard>() }

        for (c in resolvedCards) {
            if (c.boundaryIndex != null) boundaryCards[c.boundaryIndex].add(c)
            else rangeCuts[c.rangeIndex].add(c)
        }

        val segments = mutableListOf<CompositionSegment>()
        var timelineUs = 0L

        fun pushSource(startUs: Long, endUs: Long) {
            if (endUs <= startUs) return
            val outStart = (timelineUs.toDouble() / speed.toDouble()).roundToLong()
            val dur = endUs - startUs
            timelineUs += dur
            val outEnd = (timelineUs.toDouble() / speed.toDouble()).roundToLong()
            segments.add(CompositionSegment.Source(startUs, endUs, dur, outStart, outEnd))
        }

        fun pushCard(c: ResolvedCard) {
            val outStart = (timelineUs.toDouble() / speed.toDouble()).roundToLong()
            timelineUs += c.durationUs
            val outEnd = (timelineUs.toDouble() / speed.toDouble()).roundToLong()
            segments.add(CompositionSegment.Card(c.card.uri, c.mimeType, c.durationUs, outStart, outEnd))
        }

        boundaryCards[0].forEach { pushCard(it) }
        for (i in normRanges.indices) {
            val r = normRanges[i]
            var cursorUs = r.startUs
            for (cut in rangeCuts[i]) {
                if (cut.cutSourceUs > cursorUs) {
                    pushSource(cursorUs, cut.cutSourceUs)
                    cursorUs = cut.cutSourceUs
                }
                pushCard(cut)
            }
            if (cursorUs < r.endUs) pushSource(cursorUs, r.endUs)
            boundaryCards[i + 1].forEach { pushCard(it) }
        }

        return segments
    }

    fun mapOutputTimeToSourceTime(outputTimeUs: Long, segments: List<CompositionSegment>, speed: Float): Long? {
        if (segments.isEmpty() || outputTimeUs < 0L) return null
        for (seg in segments) {
            if (outputTimeUs in seg.outputStartUs..seg.outputEndUs) {
                return when (seg) {
                    is CompositionSegment.Source -> {
                        val outOffset = outputTimeUs - seg.outputStartUs
                        val srcOffset = (outOffset.toDouble() * speed.toDouble()).roundToLong()
                        (seg.sourceStartUs + srcOffset).coerceIn(seg.sourceStartUs, seg.sourceEndUs)
                    }
                    is CompositionSegment.Card -> null
                }
            }
        }
        return null
    }

    fun segmentEasingAt(keyframes: List<TransformKeyframe>, timeUs: Long): String {
        var easing = "linear"
        for (kf in keyframes.sortedBy { it.timeUs }) {
            if (kf.timeUs > timeUs) break
            easing = kf.easing
        }
        return easing
    }

    fun upsertKeyframeAtPlayhead(layer: MemeEditLayer, playheadUs: Long): MemeEditLayer {
        when (layer) {
            is MemeEditLayer.Text -> {
                val eval = interpolateTransformKeyframes(layer.keyframes, playheadUs)
                    ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                val existingIdx = layer.keyframes.indexOfFirst { it.timeUs == playheadUs }
                val newKf = TransformKeyframe(
                    timeUs = playheadUs,
                    center = eval.center,
                    scale = eval.scale,
                    rotationDegrees = eval.rotationDegrees,
                    opacity = eval.opacity,
                    easing = if (existingIdx >= 0) layer.keyframes[existingIdx].easing else segmentEasingAt(layer.keyframes, playheadUs)
                )
                val updated = if (existingIdx >= 0) {
                    layer.keyframes.toMutableList().apply { set(existingIdx, newKf) }
                } else {
                    (layer.keyframes + newKf).sortedBy { it.timeUs }
                }
                return layer.copy(keyframes = updated)
            }
            is MemeEditLayer.Subject -> {
                val eval = interpolateTransformKeyframes(layer.keyframes, playheadUs)
                    ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                val existingIdx = layer.keyframes.indexOfFirst { it.timeUs == playheadUs }
                val newKf = TransformKeyframe(
                    timeUs = playheadUs,
                    center = eval.center,
                    scale = eval.scale,
                    rotationDegrees = eval.rotationDegrees,
                    opacity = eval.opacity,
                    easing = if (existingIdx >= 0) layer.keyframes[existingIdx].easing else segmentEasingAt(layer.keyframes, playheadUs)
                )
                val updated = if (existingIdx >= 0) {
                    layer.keyframes.toMutableList().apply { set(existingIdx, newKf) }
                } else {
                    (layer.keyframes + newKf).sortedBy { it.timeUs }
                }
                return layer.copy(keyframes = updated)
            }
            is MemeEditLayer.Media -> {
                val eval = interpolateTransformKeyframes(layer.keyframes, playheadUs)
                    ?: InterpolatedTransform(NormalizedPoint.Center, 1f, 0f, 1f)
                val existingIdx = layer.keyframes.indexOfFirst { it.timeUs == playheadUs }
                val newKf = TransformKeyframe(
                    timeUs = playheadUs,
                    center = eval.center,
                    scale = eval.scale,
                    rotationDegrees = eval.rotationDegrees,
                    opacity = eval.opacity,
                    easing = if (existingIdx >= 0) layer.keyframes[existingIdx].easing else segmentEasingAt(layer.keyframes, playheadUs)
                )
                val updated = if (existingIdx >= 0) {
                    layer.keyframes.toMutableList().apply { set(existingIdx, newKf) }
                } else {
                    (layer.keyframes + newKf).sortedBy { it.timeUs }
                }
                return layer.copy(keyframes = updated)
            }
            else -> return layer
        }
    }

    fun copyKeyframeForward(layer: MemeEditLayer, fromUs: Long, toUs: Long): MemeEditLayer {
        if (toUs <= fromUs) return layer
        val keyframes = when (layer) {
            is MemeEditLayer.Text -> layer.keyframes
            is MemeEditLayer.Subject -> layer.keyframes
            is MemeEditLayer.Media -> layer.keyframes
            else -> return layer
        }
        val active = layer.active
        if (active != null) {
            if (fromUs !in active.startUs..active.endUs || toUs !in active.startUs..active.endUs) {
                return layer
            }
        }
        val transform = interpolateTransformKeyframes(keyframes, fromUs) ?: return layer
        fun insertedInOrder(kfs: List<TransformKeyframe>, inserted: TransformKeyframe): List<TransformKeyframe> {
            val next = kfs.filter { it.timeUs != inserted.timeUs }.toMutableList()
            val idx = next.indexOfFirst { it.timeUs > inserted.timeUs }
            if (idx < 0) next.add(inserted) else next.add(idx, inserted)
            return next
        }
        val fromEasing = segmentEasingAt(keyframes, fromUs)
        val fromKf = TransformKeyframe(
            timeUs = fromUs,
            center = transform.center,
            scale = transform.scale,
            rotationDegrees = transform.rotationDegrees,
            opacity = transform.opacity,
            easing = fromEasing
        )
        val anchored = insertedInOrder(keyframes, fromKf)
        val toEasing = segmentEasingAt(anchored, toUs)
        val toKf = TransformKeyframe(
            timeUs = toUs,
            center = transform.center,
            scale = transform.scale,
            rotationDegrees = transform.rotationDegrees,
            opacity = transform.opacity,
            easing = toEasing
        )
        val finalKfs = insertedInOrder(anchored, toKf)
        return when (layer) {
            is MemeEditLayer.Text -> layer.copy(keyframes = finalKfs)
            is MemeEditLayer.Subject -> layer.copy(keyframes = finalKfs)
            is MemeEditLayer.Media -> layer.copy(keyframes = finalKfs)
            else -> layer
        }
    }

    data class InferredTextStyle(
        val color: String,
        val outlineColor: String,
        val outlineScale: Float,
        val backgroundColor: String,
        val preset: String,
        val uppercase: Boolean,
        val fontSize: Float,
        val contrast: Float
    )

    fun relativeLuminance(hexColor: String): Float {
        val clean = hexColor.trim().removePrefix("#")
        if (clean.length < 6) return 0.5f
        val r = clean.substring(0, 2).toIntOrNull(16)?.div(255f) ?: 0f
        val g = clean.substring(2, 4).toIntOrNull(16)?.div(255f) ?: 0f
        val b = clean.substring(4, 6).toIntOrNull(16)?.div(255f) ?: 0f
        fun adjust(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        return 0.2126f * adjust(r) + 0.7152f * adjust(g) + 0.0722f * adjust(b)
    }

    fun contrastRatio(c1: String, c2: String): Float {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    fun looksAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return false
        return letters == letters.uppercase()
    }

    fun averageHexColor(colors: List<String>): String {
        if (colors.isEmpty()) return "#000000"
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0
        for (c in colors) {
            val clean = c.trim().removePrefix("#")
            if (clean.length >= 6) {
                val r = clean.substring(0, 2).toIntOrNull(16) ?: 0
                val g = clean.substring(2, 4).toIntOrNull(16) ?: 0
                val b = clean.substring(4, 6).toIntOrNull(16) ?: 0
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }
        if (count == 0) return "#000000"
        return String.format("#%02X%02X%02X", sumR / count, sumG / count, sumB / count)
    }

    fun splitGlyphAndBackground(colors: List<String>): Triple<String, String, Float>? {
        val usable = colors.filter { it.trim().removePrefix("#").length >= 6 }
        if (usable.size < 4) return null
        data class Point(val color: String, val luminance: Float)
        val points = usable.map { Point(it, relativeLuminance(it)) }
        var low = points.minOf { it.luminance }
        var high = points.maxOf { it.luminance }
        if (high - low < 0.02f) return null

        for (pass in 0 until 12) {
            val lowGroup = mutableListOf<Float>()
            val highGroup = mutableListOf<Float>()
            for (p in points) {
                if (abs(p.luminance - low) <= abs(p.luminance - high)) lowGroup.add(p.luminance)
                else highGroup.add(p.luminance)
            }
            if (lowGroup.isEmpty() || highGroup.isEmpty()) break
            val nextLow = lowGroup.sum() / lowGroup.size
            val nextHigh = highGroup.sum() / highGroup.size
            if (abs(nextLow - low) < 1e-5f && abs(nextHigh - high) < 1e-5f) {
                low = nextLow
                high = nextHigh
                break
            }
            low = nextLow
            high = nextHigh
        }

        val lowMembers = points.filter { abs(it.luminance - low) <= abs(it.luminance - high) }
        val highMembers = points.filter { abs(it.luminance - low) > abs(it.luminance - high) }
        if (lowMembers.isEmpty() || highMembers.isEmpty()) return null

        val glyphMembers = if (lowMembers.size <= highMembers.size) lowMembers else highMembers
        val bgMembers = if (glyphMembers === lowMembers) highMembers else lowMembers

        val glyph = averageHexColor(glyphMembers.map { it.color })
        val background = averageHexColor(bgMembers.map { it.color })
        return Triple(glyph, background, contrastRatio(glyph, background))
    }

    fun inferOriginalTextStyle(
        sampledColors: List<String>,
        originalText: String,
        regionHeight: Float,
        lineCount: Int
    ): InferredTextStyle {
        val lines = max(1, lineCount)
        val uppercase = looksAllCaps(originalText)
        val fontSize = ((regionHeight / lines) * 0.72f).coerceIn(ProjectLimits.MIN_FONT_SIZE, ProjectLimits.MAX_FONT_SIZE)
        val split = splitGlyphAndBackground(sampledColors)

        if (split == null || split.third < 1.6f) {
            val fallbackBg = split?.second ?: "#000000"
            val darkContrast = contrastRatio("#000000", fallbackBg)
            val lightContrast = contrastRatio("#FFFFFF", fallbackBg)
            val glyphColor = if (darkContrast >= lightContrast) "#000000" else "#FFFFFF"
            val outlineColor = if (glyphColor == "#000000") "#FFFFFF" else "#000000"
            return InferredTextStyle(
                color = glyphColor,
                outlineColor = outlineColor,
                outlineScale = 0.04f,
                backgroundColor = fallbackBg,
                preset = if (uppercase) "impact" else "plain",
                uppercase = uppercase,
                fontSize = fontSize,
                contrast = split?.third ?: 1f
            )
        }

        val glyph = split.first
        val bg = split.second
        val contrast = split.third
        val preset = if (uppercase && contrast >= 4f) "impact" else if (uppercase) "label" else "plain"
        val outlineColor = if (contrastRatio(glyph, "#000000") >= contrastRatio(glyph, "#FFFFFF")) "#000000" else "#FFFFFF"
        val outlineScale = if (preset == "impact") 0.06f else 0.02f
        return InferredTextStyle(
            color = glyph,
            outlineColor = outlineColor,
            outlineScale = outlineScale,
            backgroundColor = bg,
            preset = preset,
            uppercase = uppercase,
            fontSize = fontSize,
            contrast = contrast
        )
    }

    fun isLayerActiveAt(layer: MemeEditLayer, timeUs: Long): Boolean {
        val active = layer.active ?: return true
        return timeUs in active.startUs..active.endUs
    }

    fun interpolateTransformKeyframes(keyframes: List<TransformKeyframe>, timeUs: Long): InterpolatedTransform? {
        if (keyframes.isEmpty()) return null
        if (keyframes.size == 1) {
            val kf = keyframes[0]
            return InterpolatedTransform(kf.center, kf.scale, kf.rotationDegrees, kf.opacity)
        }

        val sorted = keyframes.sortedBy { it.timeUs }
        if (timeUs <= sorted.first().timeUs) {
            val first = sorted.first()
            return InterpolatedTransform(first.center, first.scale, first.rotationDegrees, first.opacity)
        }
        if (timeUs >= sorted.last().timeUs) {
            val last = sorted.last()
            return InterpolatedTransform(last.center, last.scale, last.rotationDegrees, last.opacity)
        }

        var i = 0
        while (i < sorted.size - 1 && sorted[i + 1].timeUs < timeUs) {
            i++
        }
        val kf0 = sorted[i]
        val kf1 = sorted[i + 1]

        if (kf0.easing == "hold") {
            return InterpolatedTransform(kf0.center, kf0.scale, kf0.rotationDegrees, kf0.opacity)
        }

        val dt = (kf1.timeUs - kf0.timeUs).toFloat()
        val t = if (dt > 0f) ((timeUs - kf0.timeUs).toFloat() / dt).coerceIn(0f, 1f) else 0f

        val cx = kf0.center.x + (kf1.center.x - kf0.center.x) * t
        val cy = kf0.center.y + (kf1.center.y - kf0.center.y) * t
        val scale = kf0.scale + (kf1.scale - kf0.scale) * t
        val rot = kf0.rotationDegrees + (kf1.rotationDegrees - kf0.rotationDegrees) * t
        val op = kf0.opacity + (kf1.opacity - kf0.opacity) * t

        return InterpolatedTransform(NormalizedPoint(cx, cy), scale, rot, op)
    }

    fun interpolateCoverCorrections(corrections: List<CoverCorrectionKeyframe>, timeUs: Long): Pair<NormalizedRect, String>? {
        if (corrections.isEmpty()) return null
        val sorted = corrections.sortedBy { it.timeUs }
        if (timeUs <= sorted.first().timeUs) return Pair(sorted.first().rect, sorted.first().mode)
        if (timeUs >= sorted.last().timeUs) return Pair(sorted.last().rect, sorted.last().mode)

        var i = 0
        while (i < sorted.size - 1 && sorted[i + 1].timeUs < timeUs) {
            i++
        }
        val c0 = sorted[i]
        val c1 = sorted[i + 1]
        if (c0.easing == "hold") return Pair(c0.rect, c0.mode)

        val dt = (c1.timeUs - c0.timeUs).toFloat()
        val t = if (dt > 0f) ((timeUs - c0.timeUs).toFloat() / dt).coerceIn(0f, 1f) else 0f

        val rx = c0.rect.x + (c1.rect.x - c0.rect.x) * t
        val ry = c0.rect.y + (c1.rect.y - c0.rect.y) * t
        val rw = c0.rect.width + (c1.rect.width - c0.rect.width) * t
        val rh = c0.rect.height + (c1.rect.height - c0.rect.height) * t

        return Pair(NormalizedRect(rx, ry, rw, rh), c0.mode)
    }

    // Default project factories
    fun createDefaultImageProject(source: MemeEditSource): MemeEditProject =
        MemeEditProject(
            version = 2,
            source = source.copy(kind = MediaEditKind.IMAGE, durationUs = null),
            base = BaseTransform(),
            video = null,
            layers = emptyList(),
            background = BackgroundSpec(mode = "source", color = "#0a0b0e")
        )

    fun createDefaultVideoProject(source: MemeEditSource): MemeEditProject {
        val duration = source.durationUs ?: 1_000_000L
        return MemeEditProject(
            version = 2,
            source = source.copy(kind = MediaEditKind.VIDEO, durationUs = duration),
            base = BaseTransform(),
            video = VideoEditSpec(
                retainedRanges = listOf(TimeRangeUs(0L, duration)),
                speed = 1f,
                audio = VideoAudioSpec(muted = false, volume = 1f)
            ),
            layers = emptyList(),
            background = BackgroundSpec(mode = "source", color = "#0a0b0e")
        )
    }

    // Snapping guides and caption slots
    const val CAPTION_SNAP_TOLERANCE = 0.03f
    val CAPTION_SNAP_X = floatArrayOf(0.5f)
    val CAPTION_SNAP_Y = floatArrayOf(0.12f, 0.5f, 0.88f)

    data class SnappedCenter(
        val center: NormalizedPoint,
        val snappedX: Float?,
        val snappedY: Float?
    )

    fun snapCaptionCenter(center: NormalizedPoint, tolerance: Float = CAPTION_SNAP_TOLERANCE): SnappedCenter {
        var snappedX: Float? = null
        var snappedY: Float? = null
        var cx = center.x
        var cy = center.y

        for (guide in CAPTION_SNAP_X) {
            if (abs(center.x - guide) <= tolerance) {
                cx = guide
                snappedX = guide
                break
            }
        }
        for (guide in CAPTION_SNAP_Y) {
            if (abs(center.y - guide) <= tolerance) {
                cy = guide
                snappedY = guide
                break
            }
        }
        return SnappedCenter(NormalizedPoint(cx, cy), snappedX, snappedY)
    }

    fun captionSlotCenter(slot: String): NormalizedPoint = when (slot) {
        "top" -> NormalizedPoint(0.5f, 0.12f)
        "middle" -> NormalizedPoint(0.5f, 0.5f)
        "bottom" -> NormalizedPoint(0.5f, 0.88f)
        else -> NormalizedPoint(0.5f, 0.88f)
    }

    fun nextCaptionSlot(usedSlots: List<String>): String {
        val used = usedSlots.toSet()
        if ("top" !in used) return "top"
        if ("bottom" !in used) return "bottom"
        if ("middle" !in used) return "middle"
        return "bottom"
    }
}

// Project Actions & Reducer
internal sealed class ProjectAction {
    data class SetBaseTransform(val base: BaseTransform) : ProjectAction()
    data class SetLayers(val layers: List<MemeEditLayer>) : ProjectAction()
    data class AddLayers(val layers: List<MemeEditLayer>) : ProjectAction()
    data class AddLayer(val layer: MemeEditLayer) : ProjectAction()
    data class UpdateLayer(val layer: MemeEditLayer) : ProjectAction()
    data class RemoveLayer(val layerId: String) : ProjectAction()
    data class MoveLayer(val layerId: String, val toIndex: Int) : ProjectAction()
    data class DuplicateLayer(val layerId: String, val newId: String) : ProjectAction()
    data class SetBackground(val background: BackgroundSpec) : ProjectAction()
    data class SetVideoRanges(val ranges: List<TimeRangeUs>) : ProjectAction()
    data class SetVideoSpeed(val speed: Float) : ProjectAction()
    data class SetVideoAudio(val muted: Boolean, val volume: Float) : ProjectAction()
    data class SetVideoMusic(val music: VideoMusicSpec?) : ProjectAction()
    data class AddInsertedCard(val card: InsertedCard) : ProjectAction()
    data class RemoveInsertedCard(val index: Int) : ProjectAction()
    data class SetDrawElements(val layerId: String, val elements: List<DrawElement>) : ProjectAction()
    data class SetDrawOpacity(val layerId: String, val opacity: Float) : ProjectAction()
}

internal fun reduceProject(project: MemeEditProject, action: ProjectAction): MemeEditProject =
    when (action) {
        is ProjectAction.SetBaseTransform -> {
            val remappedLayers = ProjectCore.remapProjectLayers(
                project.layers,
                project.base,
                action.base,
                project.source.width,
                project.source.height
            )
            project.copy(base = action.base, layers = remappedLayers)
        }
        is ProjectAction.AddLayers -> {
            val remaining = ProjectLimits.MAX_LAYERS - project.layers.size
            if (remaining <= 0) project
            else project.copy(layers = project.layers + action.layers.take(remaining))
        }
        is ProjectAction.SetLayers -> project.copy(layers = action.layers.take(ProjectLimits.MAX_LAYERS))
        is ProjectAction.AddLayer -> {
            if (project.layers.size >= ProjectLimits.MAX_LAYERS) project
            else project.copy(layers = project.layers + action.layer)
        }
        is ProjectAction.UpdateLayer -> {
            val updated = project.layers.map { if (it.id == action.layer.id) action.layer else it }
            project.copy(layers = updated)
        }
        is ProjectAction.RemoveLayer -> {
            val filtered = project.layers.filter { it.id != action.layerId }
            project.copy(layers = filtered)
        }
        is ProjectAction.MoveLayer -> {
            val idx = project.layers.indexOfFirst { it.id == action.layerId }
            if (idx < 0) project
            else {
                val mutable = project.layers.toMutableList()
                val item = mutable.removeAt(idx)
                val target = action.toIndex.coerceIn(0, mutable.size)
                mutable.add(target, item)
                project.copy(layers = mutable)
            }
        }
        is ProjectAction.DuplicateLayer -> {
            if (project.layers.size >= ProjectLimits.MAX_LAYERS) project
            else {
                val orig = project.layers.find { it.id == action.layerId }
                if (orig == null) project
                else {
                    val dup = when (orig) {
                        is MemeEditLayer.Text -> orig.copy(
                            id = action.newId,
                            keyframes = orig.keyframes.map {
                                it.copy(center = NormalizedPoint(
                                    (it.center.x + 0.04f).coerceAtMost(0.95f),
                                    (it.center.y + 0.04f).coerceAtMost(0.95f)
                                ))
                            }
                        )
                        is MemeEditLayer.Cover -> orig.copy(
                            id = action.newId,
                            rect = NormalizedRect(
                                (orig.rect.x + 0.04f).coerceAtMost(1f - orig.rect.width),
                                (orig.rect.y + 0.04f).coerceAtMost(1f - orig.rect.height),
                                orig.rect.width,
                                orig.rect.height
                            )
                        )
                        is MemeEditLayer.Subject -> orig.copy(
                            id = action.newId,
                            keyframes = orig.keyframes.map {
                                it.copy(center = NormalizedPoint(
                                    (it.center.x + 0.04f).coerceAtMost(0.95f),
                                    (it.center.y + 0.04f).coerceAtMost(0.95f)
                                ))
                            }
                        )
                        is MemeEditLayer.Media -> orig.copy(
                            id = action.newId,
                            keyframes = orig.keyframes.map {
                                it.copy(center = NormalizedPoint(
                                    (it.center.x + 0.04f).coerceAtMost(0.95f),
                                    (it.center.y + 0.04f).coerceAtMost(0.95f)
                                ))
                            }
                        )
                        is MemeEditLayer.Draw -> orig.copy(id = action.newId)
                    }
                    project.copy(layers = project.layers + dup)
                }
            }
        }
        is ProjectAction.SetBackground -> project.copy(background = action.background)
        is ProjectAction.SetVideoRanges -> {
            val video = project.video ?: VideoEditSpec()
            val normalized = ProjectCore.normalizeRetainedRanges(action.ranges, project.source.durationUs ?: 1_000_000L)
            project.copy(video = video.copy(retainedRanges = normalized))
        }
        is ProjectAction.SetVideoSpeed -> {
            val video = project.video ?: VideoEditSpec()
            val s = action.speed.coerceIn(ProjectLimits.MIN_SPEED, ProjectLimits.MAX_SPEED)
            project.copy(video = video.copy(speed = s))
        }
        is ProjectAction.SetVideoAudio -> {
            val video = project.video ?: VideoEditSpec()
            project.copy(video = video.copy(audio = VideoAudioSpec(action.muted, action.volume.coerceIn(0f, 2f))))
        }
        is ProjectAction.SetVideoMusic -> {
            val video = project.video ?: VideoEditSpec()
            project.copy(video = video.copy(music = action.music))
        }
        is ProjectAction.AddInsertedCard -> {
            val video = project.video ?: VideoEditSpec()
            if (video.insertedCards.size >= ProjectLimits.MAX_INSERTED_CARDS) project
            else project.copy(video = video.copy(insertedCards = video.insertedCards + action.card))
        }
        is ProjectAction.RemoveInsertedCard -> {
            val video = project.video ?: VideoEditSpec()
            if (action.index in video.insertedCards.indices) {
                val mutable = video.insertedCards.toMutableList()
                mutable.removeAt(action.index)
                project.copy(video = video.copy(insertedCards = mutable))
            } else project
        }
        is ProjectAction.SetDrawElements -> {
            val updated = project.layers.map { layer ->
                if (layer.id == action.layerId && layer is MemeEditLayer.Draw) {
                    layer.copy(elements = action.elements.take(ProjectLimits.MAX_DRAW_ELEMENTS))
                } else layer
            }
            project.copy(layers = updated)
        }
        is ProjectAction.SetDrawOpacity -> {
            val updated = project.layers.map { layer ->
                if (layer.id == action.layerId && layer is MemeEditLayer.Draw) {
                    layer.copy(opacity = action.opacity.coerceIn(0f, 1f))
                } else layer
            }
            project.copy(layers = updated)
        }
    }

// History & Transaction Management
internal data class TransactionBaseline(val baseline: MemeEditProject)

internal data class ProjectHistory(
    val past: List<MemeEditProject> = emptyList(),
    val present: MemeEditProject,
    val future: List<MemeEditProject> = emptyList(),
    val transaction: TransactionBaseline? = null
)

internal fun createProjectHistory(project: MemeEditProject): ProjectHistory =
    ProjectHistory(past = emptyList(), present = project, future = emptyList(), transaction = null)

internal fun beginProjectTransaction(history: ProjectHistory): ProjectHistory =
    if (history.transaction != null) history
    else history.copy(transaction = TransactionBaseline(history.present))

internal fun applyProjectAction(history: ProjectHistory, action: ProjectAction): ProjectHistory {
    val nextPresent = reduceProject(history.present, action)
    if (nextPresent == history.present) return history

    return if (history.transaction != null) {
        history.copy(present = nextPresent)
    } else {
        val newPast = (history.past + history.present).takeLast(ProjectLimits.MAX_HISTORY_STATES)
        history.copy(past = newPast, present = nextPresent, future = emptyList())
    }
}

internal fun commitProjectTransaction(history: ProjectHistory): ProjectHistory {
    val tx = history.transaction ?: return history
    if (tx.baseline == history.present) {
        return history.copy(transaction = null)
    }
    val newPast = (history.past + tx.baseline).takeLast(ProjectLimits.MAX_HISTORY_STATES)
    return history.copy(past = newPast, future = emptyList(), transaction = null)
}

internal fun cancelProjectTransaction(history: ProjectHistory): ProjectHistory {
    val tx = history.transaction ?: return history
    return history.copy(present = tx.baseline, transaction = null)
}

internal fun undoProjectHistory(history: ProjectHistory): ProjectHistory {
    if (history.transaction != null || history.past.isEmpty()) return history
    val previous = history.past.last()
    val newPast = history.past.dropLast(1)
    val newFuture = listOf(history.present) + history.future
    return history.copy(past = newPast, present = previous, future = newFuture)
}

internal fun redoProjectHistory(history: ProjectHistory): ProjectHistory {
    if (history.transaction != null || history.future.isEmpty()) return history
    val next = history.future.first()
    val newFuture = history.future.drop(1)
    val newPast = (history.past + history.present).takeLast(ProjectLimits.MAX_HISTORY_STATES)
    return history.copy(past = newPast, present = next, future = newFuture)
}

internal fun canUndo(history: ProjectHistory): Boolean =
    history.transaction != null || history.past.isNotEmpty()

internal fun canRedo(history: ProjectHistory): Boolean =
    history.transaction == null && history.future.isNotEmpty()
