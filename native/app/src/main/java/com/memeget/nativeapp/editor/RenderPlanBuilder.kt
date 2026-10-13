package com.memeget.nativeapp.editor

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Builds the JSON plan consumed by [expo.modules.memegetbg.MemeImageRenderer].
 *
 * Field layout mirrors `src/memeImageRenderCore.ts` so the still renderer, PNG
 * export, and [NativeImagePreview] share one geometry path. Text measurements
 * are unscaled; the renderer applies keyframe scale once.
 */
internal object RenderPlanBuilder {

    const val MAX_IMAGE_RENDER_PIXELS = 16_000_000L
    const val DEFAULT_PLAN_VERSION = 1
    const val MAX_MOSAIC_CELLS = 65_536L
    const val MEME_MEDIA_LAYER_BASE_WIDTH = 0.28f
    const val CUTOUT_OUTLINE_MAX_FRACTION = 0.06f
    const val CUTOUT_SHADOW_BLUR_MAX_FRACTION = 0.08f
    const val CUTOUT_SHADOW_OFFSET_MAX_FRACTION = 0.05f
    const val CUTOUT_SHADOW_COLOR = "#8C000000"

    data class OutputSizePx(
        val widthPx: Int,
        val heightPx: Int,
        val fullWidthPx: Int = widthPx,
        val fullHeightPx: Int = heightPx,
        val downscaled: Boolean = false,
        val scale: Float = 1f,
        val maxPixels: Long = MAX_IMAGE_RENDER_PIXELS
    )

    data class CutoutPlacement(
        val rectX: Double,
        val rectY: Double,
        val rectW: Double,
        val rectH: Double,
        val rotationDegrees: Float,
        val opacity: Float,
        val outlinePx: Float,
        val shadowBlurPx: Float,
        val shadowOffsetPx: Float
    )

    fun calculateOutputSize(
        sourceWidth: Int,
        sourceHeight: Int,
        base: BaseTransform,
        maxPixels: Long = MAX_IMAGE_RENDER_PIXELS
    ): OutputSizePx {
        val quarterTurn = base.rotation == QuarterRotation.R90 || base.rotation == QuarterRotation.R270
        val orientedW = if (quarterTurn) sourceHeight.toFloat() else sourceWidth.toFloat()
        val orientedH = if (quarterTurn) sourceWidth.toFloat() else sourceHeight.toFloat()

        val crop = normalizeFreeCrop(base.crop)
        val croppedW = max(1f, orientedW * crop.width)
        val croppedH = max(1f, orientedH * crop.height)

        val targetAspect = aspectRatio(base.outputAspect)
        var outW = croppedW
        var outH = croppedH
        if (targetAspect != null) {
            if (targetAspect > croppedW / croppedH) {
                outH = croppedW / targetAspect
            } else {
                outW = croppedH * targetAspect
            }
        }

        val fullWidthPx = max(1, outW.roundToInt())
        val fullHeightPx = max(1, outH.roundToInt())
        val cap = max(1L, maxPixels)
        val total = fullWidthPx.toLong() * fullHeightPx.toLong()
        if (total <= cap) {
            return OutputSizePx(
                widthPx = fullWidthPx,
                heightPx = fullHeightPx,
                fullWidthPx = fullWidthPx,
                fullHeightPx = fullHeightPx,
                downscaled = false,
                scale = 1f,
                maxPixels = cap
            )
        }
        // Round the scale factor first so floor(full * scale) stays inside the cap
        // the same way src/memeImageRenderCore.ts planOutput does.
        val scale = round4(sqrt(cap.toDouble() / total.toDouble())).toFloat()
        var w = max(1, floor(fullWidthPx * scale.toDouble()).toInt())
        var h = max(1, floor(fullHeightPx * scale.toDouble()).toInt())
        while (w.toLong() * h.toLong() > cap && (w > 1 || h > 1)) {
            if (w >= h && w > 1) w-- else if (h > 1) h-- else break
        }
        return OutputSizePx(
            widthPx = w,
            heightPx = h,
            fullWidthPx = fullWidthPx,
            fullHeightPx = fullHeightPx,
            downscaled = true,
            scale = scale,
            maxPixels = cap
        )
    }

    fun mosaicCellFloorPx(rectWidth: Int, rectHeight: Int): Int {
        val area = max(0, rectWidth).toLong() * max(0, rectHeight).toLong()
        return max(1, ceil(sqrt(area.toDouble() / MAX_MOSAIC_CELLS.toDouble())).toInt())
    }

    fun resolveCutoutPlacement(
        outlineColor: String?,
        outlineScale: Float,
        shadowScale: Float,
        transform: InterpolatedTransform,
        trackRect: NormalizedRect,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ): CutoutPlacement {
        val scale = max(0.01f, transform.scale)
        val width = max(0f, trackRect.width) * canvasWidthPx * scale
        val height = max(0f, trackRect.height) * canvasHeightPx * scale
        val centerX = canvasWidthPx * transform.center.x.coerceIn(0f, 1f)
        val centerY = canvasHeightPx * transform.center.y.coerceIn(0f, 1f)
        val reference = min(width, height)
        val outline = outlineScale.coerceIn(0f, 1f)
        val shadow = shadowScale.coerceIn(0f, 1f)
        return CutoutPlacement(
            rectX = round1(centerX - width / 2f).toDouble(),
            rectY = round1(centerY - height / 2f).toDouble(),
            rectW = round1(width).toDouble(),
            rectH = round1(height).toDouble(),
            rotationDegrees = round1(transform.rotationDegrees),
            opacity = round4(transform.opacity.coerceIn(0f, 1f)).toFloat(),
            outlinePx = if (outlineColor.isNullOrBlank()) {
                0f
            } else {
                round1(outline * CUTOUT_OUTLINE_MAX_FRACTION * reference)
            },
            shadowBlurPx = round1(shadow * CUTOUT_SHADOW_BLUR_MAX_FRACTION * reference),
            shadowOffsetPx = round1(shadow * CUTOUT_SHADOW_OFFSET_MAX_FRACTION * reference)
        )
    }

    fun buildImageRenderPlan(
        project: MemeEditProject,
        planId: String = "render_${System.currentTimeMillis()}",
        timeUs: Long = 0L,
        maxOutputPixels: Long = MAX_IMAGE_RENDER_PIXELS,
        context: PlanContext = PlanContext.IMAGE
    ): JSONObject {
        val outSize = calculateOutputSize(
            sourceWidth = project.source.width,
            sourceHeight = project.source.height,
            base = project.base,
            maxPixels = maxOutputPixels
        )
        val outW = outSize.widthPx
        val outH = outSize.heightPx
        val shortEdgePx = min(outW, outH).toFloat()

        val root = JSONObject().apply {
            put("version", DEFAULT_PLAN_VERSION)
            put("id", planId)
            put("timeUs", timeUs)
            put("source", sourceJson(project))
            put("output", JSONObject().apply {
                put("widthPx", outW)
                put("heightPx", outH)
                put("fullWidthPx", outSize.fullWidthPx)
                put("fullHeightPx", outSize.fullHeightPx)
                put("downscaled", outSize.downscaled)
                put("scale", outSize.scale.toDouble())
                put("maxPixels", outSize.maxPixels)
            })
            put(
                "background",
                if (context == PlanContext.VIDEO_OVERLAY) {
                    JSONObject().apply {
                        put("mode", "transparent")
                        put("color", project.background.color)
                        put("assetUri", JSONObject.NULL)
                        put("blurScale", 0.0)
                    }
                } else {
                    project.background.toJson()
                }
            )
        }

        val layersArray = JSONArray()
        for (layer in project.layers) {
            if (context == PlanContext.IMAGE && !ProjectCore.isLayerActiveAt(layer, timeUs)) continue
            val planned = planLayer(layer, project, outSize, shortEdgePx, timeUs, context) ?: continue
            layersArray.put(planned)
        }
        root.put("layers", layersArray)
        return root
    }

    fun buildVideoOverlayRenderPlan(
        project: MemeEditProject,
        planId: String = "overlay_${System.currentTimeMillis()}",
        timeUs: Long = 0L,
        maxOutputPixels: Long = MAX_IMAGE_RENDER_PIXELS
    ): JSONObject = buildImageRenderPlan(
        project = project,
        planId = planId,
        timeUs = timeUs,
        maxOutputPixels = maxOutputPixels,
        context = PlanContext.VIDEO_OVERLAY
    )

    enum class PlanContext { IMAGE, VIDEO_OVERLAY }

    private fun planLayer(
        layer: MemeEditLayer,
        project: MemeEditProject,
        outSize: OutputSizePx,
        shortEdgePx: Float,
        timeUs: Long,
        context: PlanContext
    ): JSONObject? {
        val outW = outSize.widthPx
        val outH = outSize.heightPx
        return when (layer) {
            is MemeEditLayer.Draw -> {
                if (layer.elements.isEmpty()) null else drawPlan(layer, outW, outH, shortEdgePx)
            }
            is MemeEditLayer.Cover -> {
                val corr = ProjectCore.interpolateCoverCorrections(layer.corrections, timeUs)
                val mode = corr?.second ?: layer.mode
                if (context == PlanContext.VIDEO_OVERLAY && mode == "pixelate") {
                    JSONObject().apply {
                        put("kind", "unavailable")
                        put("id", layer.id)
                        put("layerKind", "cover")
                        put("reason", "pixelate-video-overlay-unsupported")
                    }
                } else {
                    coverPlan(layer, corr?.first ?: layer.rect, mode, outSize)
                }
            }
            is MemeEditLayer.Text -> {
                val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, timeUs)
                    ?: return null
                textPlan(layer, transform, outW, outH)
            }
            is MemeEditLayer.Subject -> {
                val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, timeUs)
                    ?: return null
                subjectPlan(layer, project, transform, outW, outH)
            }
            is MemeEditLayer.Media -> {
                val transform = ProjectCore.interpolateTransformKeyframes(layer.keyframes, timeUs)
                    ?: return null
                mediaPlan(layer, transform, outW, outH)
            }
        }
    }

    private fun coverPlan(
        layer: MemeEditLayer.Cover,
        rect: NormalizedRect,
        mode: String,
        outSize: OutputSizePx
    ): JSONObject {
        val pxX = round1(rect.x * outSize.widthPx).toInt()
        val pxY = round1(rect.y * outSize.heightPx).toInt()
        val pxW = round1(rect.width * outSize.widthPx).toInt()
        val pxH = round1(rect.height * outSize.heightPx).toInt()
        val requested = max(1, round1(layer.pixelSize * outSize.scale).toInt())
        val cell = max(requested, mosaicCellFloorPx(pxW, pxH))
        return JSONObject().apply {
            put("kind", "cover")
            put("id", layer.id)
            put("rect", JSONObject().apply {
                put("x", pxX)
                put("y", pxY)
                put("width", pxW)
                put("height", pxH)
            })
            put("mode", mode)
            put("color", layer.color)
            put("pixelSizePx", cell)
        }
    }

    private fun textPlan(
        layer: MemeEditLayer.Text,
        transform: InterpolatedTransform,
        outW: Int,
        outH: Int
    ): JSONObject {
        val spec = TextLayoutBridge.getPresetSpec(layer.style.preset)
        // fontSize is normalized against canvas height (memeTextLayoutCore.ts).
        val fontSizePx = max(1f, layer.fontSize * outH.toFloat())
        val wrapWidthPx = max(1f, layer.width * outW.toFloat())
        val centerX = transform.center.x.coerceIn(0f, 1f) * outW.toFloat()
        val centerY = transform.center.y.coerceIn(0f, 1f) * outH.toFloat()
        val strokeWidthPx = if (layer.style.outlineScale > 0.001f) {
            fontSizePx * layer.style.outlineScale
        } else {
            0f
        }
        val paddingX = fontSizePx * spec.paddingXScale
        val paddingY = fontSizePx * spec.paddingYScale
        val radius = fontSizePx * spec.backingRadiusScale
        // Unscaled metrics: renderer applies transform.scale once.
        return JSONObject().apply {
            put("kind", "text")
            put("id", layer.id)
            put("spec", JSONObject().apply {
                put("displayText", if (layer.style.uppercase) layer.text.uppercase() else layer.text)
                put("align", layer.style.align)
                put("canvas", JSONObject().apply {
                    put("centerDip", JSONObject().apply {
                        put("x", centerX.toDouble())
                        put("y", centerY.toDouble())
                    })
                    put("wrapWidthDip", wrapWidthPx.toDouble())
                    put("fontSizeDip", fontSizePx.toDouble())
                })
                put("font", JSONObject().apply {
                    put("family", spec.fontFamily)
                    put("weight", spec.fontWeight.toString())
                    put("letterSpacingEm", spec.letterSpacingEm.toDouble())
                })
                put("transform", JSONObject().apply {
                    put("scale", transform.scale.toDouble())
                    put("rotationDegrees", transform.rotationDegrees.toDouble())
                    put("opacity", transform.opacity.toDouble())
                })
                put("fill", JSONObject().apply {
                    put("color", layer.style.color)
                    put("opacity", layer.style.opacity.toDouble())
                })
                put("outline", JSONObject().apply {
                    put("color", layer.style.outlineColor)
                    put("widthDip", strokeWidthPx.toDouble())
                })
                put("backing", JSONObject().apply {
                    if (layer.style.backgroundColor != null) {
                        put("color", layer.style.backgroundColor)
                    } else {
                        put("color", JSONObject.NULL)
                    }
                    put("radiusDip", radius.toDouble())
                    put("paddingXDip", paddingX.toDouble())
                    put("paddingYDip", paddingY.toDouble())
                })
                put("layout", JSONObject().apply {
                    put("lineHeightDip", (fontSizePx * spec.lineHeightRatio).toDouble())
                })
            })
        }
    }

    private fun subjectPlan(
        layer: MemeEditLayer.Subject,
        project: MemeEditProject,
        transform: InterpolatedTransform,
        outW: Int,
        outH: Int
    ): JSONObject {
        val cutoutUri = layer.cutoutUri.ifBlank {
            project.transientMaskTracks[layer.maskTrackId].orEmpty()
        }
        if (cutoutUri.isBlank()) {
            return JSONObject().apply {
                put("kind", "unavailable")
                put("id", layer.id)
                put("layerKind", "subject")
                put("reason", "subject-mask-missing")
            }
        }
        val placement = resolveCutoutPlacement(
            outlineColor = layer.outlineColor,
            outlineScale = layer.outlineScale,
            shadowScale = layer.shadowScale,
            transform = transform,
            trackRect = layer.bounds,
            canvasWidthPx = outW,
            canvasHeightPx = outH
        )
        return JSONObject().apply {
            put("kind", "subject")
            put("id", layer.id)
            put("cutoutUri", cutoutUri)
            put("rect", JSONObject().apply {
                put("x", placement.rectX)
                put("y", placement.rectY)
                put("width", placement.rectW)
                put("height", placement.rectH)
            })
            put("opacity", placement.opacity.toDouble())
            put("rotationDegrees", placement.rotationDegrees.toDouble())
            put("outlinePx", placement.outlinePx.toDouble())
            if (layer.outlineColor != null) put("outlineColor", layer.outlineColor)
            else put("outlineColor", JSONObject.NULL)
            put("shadowBlurPx", placement.shadowBlurPx.toDouble())
            put("shadowOffsetPx", placement.shadowOffsetPx.toDouble())
            put("shadowColor", CUTOUT_SHADOW_COLOR)
        }
    }

    private fun mediaPlan(
        layer: MemeEditLayer.Media,
        transform: InterpolatedTransform,
        outW: Int,
        outH: Int
    ): JSONObject {
        if (layer.assetKind == "video") {
            return JSONObject().apply {
                put("kind", "unavailable")
                put("id", layer.id)
                put("layerKind", "media")
                put("reason", "video-overlay-unsupported")
            }
        }
        val side = round1(
            outW * MEME_MEDIA_LAYER_BASE_WIDTH * max(0.01f, transform.scale)
        )
        val centerX = outW * transform.center.x.coerceIn(0f, 1f)
        val centerY = outH * transform.center.y.coerceIn(0f, 1f)
        return JSONObject().apply {
            put("kind", "media")
            put("id", layer.id)
            put("assetUri", layer.assetUri)
            put("assetKind", layer.assetKind)
            put("fit", layer.fit)
            put("rect", JSONObject().apply {
                put("x", round1(centerX - side / 2f).toDouble())
                put("y", round1(centerY - side / 2f).toDouble())
                put("width", side.toDouble())
                put("height", side.toDouble())
            })
            put("opacity", transform.opacity.coerceIn(0f, 1f).toDouble())
            put("rotationDegrees", transform.rotationDegrees.toDouble())
        }
    }

    private fun drawPlan(
        layer: MemeEditLayer.Draw,
        outW: Int,
        outH: Int,
        shortEdgePx: Float
    ): JSONObject {
        val elementsArray = JSONArray()
        for (elem in layer.elements) {
            val ptsArray = JSONArray()
            for (p in elem.points) {
                ptsArray.put(JSONObject().apply {
                    put("x", round1(p.x * outW.toFloat()).toDouble())
                    put("y", round1(p.y * outH.toFloat()).toDouble())
                })
            }
            elementsArray.put(JSONObject().apply {
                put("shape", elem.shape.wire)
                put("color", elem.color)
                put("strokeWidthPx", max(1.0, round1(elem.strokeScale * shortEdgePx).toDouble()))
                put("filled", elem.filled)
                put("points", ptsArray)
            })
        }
        return JSONObject().apply {
            put("kind", "draw")
            put("id", layer.id)
            put("opacity", layer.opacity.toDouble())
            put("elements", elementsArray)
        }
    }

    private fun sourceJson(project: MemeEditProject): JSONObject = JSONObject().apply {
        put("uri", project.materializedSourceUri ?: project.source.uri)
        put("widthPx", project.source.width)
        put("heightPx", project.source.height)
        put("rotation", project.base.rotation.degrees)
        put("flipX", project.base.flipX)
        put("flipY", project.base.flipY)
        put("crop", normalizeFreeCrop(project.base.crop).toJson())
    }

    private fun aspectRatio(outputAspect: String): Float? = when (outputAspect) {
        "1:1" -> 1f
        "4:5" -> 4f / 5f
        "9:16" -> 9f / 16f
        "16:9" -> 16f / 9f
        else -> null // "source" / "free"
    }

    private fun normalizeFreeCrop(crop: NormalizedRect): NormalizedRect {
        val width = crop.width.coerceIn(0.01f, 1f)
        val height = crop.height.coerceIn(0.01f, 1f)
        val x = crop.x.coerceIn(0f, 1f - width)
        val y = crop.y.coerceIn(0f, 1f - height)
        return NormalizedRect(x, y, width, height)
    }

    private fun round1(value: Float): Float = round(value.toDouble()).toFloat()
    private fun round4(value: Double): Double = round(value * 10_000.0) / 10_000.0
    private fun round4(value: Float): Double = round4(value.toDouble())
}
