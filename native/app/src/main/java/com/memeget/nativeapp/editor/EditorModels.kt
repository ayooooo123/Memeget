package com.memeget.nativeapp.editor

import org.json.JSONArray
import org.json.JSONObject

internal enum class MediaEditKind(val wire: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromWire(value: String): MediaEditKind =
            entries.firstOrNull { it.wire == value } ?: IMAGE
    }
}

internal data class NormalizedPoint(val x: Float, val y: Float) {
    fun toMap(): Map<String, Float> = mapOf("x" to x, "y" to y)
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
    }

    companion object {
        val Zero = NormalizedPoint(0f, 0f)
        val Center = NormalizedPoint(0.5f, 0.5f)

        fun fromJson(json: JSONObject): NormalizedPoint =
            NormalizedPoint(
                x = json.optDouble("x", 0.0).toFloat(),
                y = json.optDouble("y", 0.0).toFloat()
            )
    }
}

internal data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun toMap(): Map<String, Float> = mapOf("x" to x, "y" to y, "width" to width, "height" to height)
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
    }

    companion object {
        val Full = NormalizedRect(0f, 0f, 1f, 1f)

        fun fromJson(json: JSONObject): NormalizedRect =
            NormalizedRect(
                x = json.optDouble("x", 0.0).toFloat(),
                y = json.optDouble("y", 0.0).toFloat(),
                width = json.optDouble("width", 1.0).toFloat(),
                height = json.optDouble("height", 1.0).toFloat()
            )
    }
}

internal data class TimeRangeUs(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)

    fun toJson(): JSONObject = JSONObject().apply {
        put("startUs", startUs)
        put("endUs", endUs)
    }

    companion object {
        fun fromJson(json: JSONObject): TimeRangeUs =
            TimeRangeUs(
                startUs = json.optLong("startUs", 0L),
                endUs = json.optLong("endUs", 0L)
            )
    }
}

internal enum class QuarterRotation(val degrees: Int) {
    R0(0),
    R90(90),
    R180(180),
    R270(270);

    fun next(): QuarterRotation = when (this) {
        R0 -> R90
        R90 -> R180
        R180 -> R270
        R270 -> R0
    }

    companion object {
        fun fromDegrees(deg: Int): QuarterRotation = when (((deg % 360) + 360) % 360) {
            90 -> R90
            180 -> R180
            270 -> R270
            else -> R0
        }
    }
}

internal data class BaseTransform(
    val rotation: QuarterRotation = QuarterRotation.R0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: NormalizedRect = NormalizedRect.Full,
    val outputAspect: String = "source" // "source", "free", "1:1", "4:5", "9:16", "16:9"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rotation", rotation.degrees)
        put("flipX", flipX)
        put("flipY", flipY)
        put("crop", crop.toJson())
        put("outputAspect", outputAspect)
    }

    companion object {
        val Default = BaseTransform()

        fun fromJson(json: JSONObject): BaseTransform =
            BaseTransform(
                rotation = QuarterRotation.fromDegrees(json.optInt("rotation", 0)),
                flipX = json.optBoolean("flipX", false),
                flipY = json.optBoolean("flipY", false),
                crop = json.optJSONObject("crop")?.let { NormalizedRect.fromJson(it) } ?: NormalizedRect.Full,
                outputAspect = json.optString("outputAspect", "source")
            )
    }
}

internal data class TextStyle(
    val preset: String = "impact", // "impact", "subtitle", "label", "news", "bubble", "plain"
    val color: String = "#f2f4f8",
    val outlineColor: String = "#0a0b0e",
    val outlineScale: Float = 0.22f,
    val backgroundColor: String? = null,
    val opacity: Float = 1f,
    val align: String = "center", // "left", "center", "right"
    val uppercase: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("preset", preset)
        put("color", color)
        put("outlineColor", outlineColor)
        put("outlineScale", outlineScale.toDouble())
        if (backgroundColor != null) put("backgroundColor", backgroundColor) else put("backgroundColor", JSONObject.NULL)
        put("opacity", opacity.toDouble())
        put("align", align)
        put("uppercase", uppercase)
    }

    companion object {
        fun fromJson(json: JSONObject): TextStyle =
            TextStyle(
                preset = json.optString("preset", "impact"),
                color = json.optString("color", "#f2f4f8"),
                outlineColor = json.optString("outlineColor", "#0a0b0e"),
                outlineScale = json.optDouble("outlineScale", 0.22).toFloat(),
                backgroundColor = if (json.isNull("backgroundColor")) null else json.optString("backgroundColor"),
                opacity = json.optDouble("opacity", 1.0).toFloat(),
                align = json.optString("align", "center"),
                uppercase = json.optBoolean("uppercase", true)
            )
    }
}

internal data class TransformKeyframe(
    val timeUs: Long = 0L,
    val center: NormalizedPoint = NormalizedPoint.Center,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val easing: String = "linear" // "linear", "hold"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeUs", timeUs)
        put("center", center.toJson())
        put("scale", scale.toDouble())
        put("rotationDegrees", rotationDegrees.toDouble())
        put("opacity", opacity.toDouble())
        put("easing", easing)
    }

    companion object {
        fun fromJson(json: JSONObject): TransformKeyframe =
            TransformKeyframe(
                timeUs = json.optLong("timeUs", 0L),
                center = json.optJSONObject("center")?.let { NormalizedPoint.fromJson(it) } ?: NormalizedPoint.Center,
                scale = json.optDouble("scale", 1.0).toFloat(),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
                opacity = json.optDouble("opacity", 1.0).toFloat(),
                easing = json.optString("easing", "linear")
            )
    }
}

internal enum class DrawShape(val wire: String) {
    FREE("free"),
    LINE("line"),
    ARROW("arrow"),
    RECTANGLE("rectangle"),
    ELLIPSE("ellipse");

    companion object {
        fun fromWire(value: String): DrawShape =
            entries.firstOrNull { it.wire == value } ?: FREE
    }
}

internal data class DrawElement(
    val shape: DrawShape = DrawShape.FREE,
    val color: String = "#FF4E42",
    val strokeScale: Float = 0.012f,
    val points: List<NormalizedPoint> = emptyList(),
    val filled: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("shape", shape.wire)
        put("color", color)
        put("strokeScale", strokeScale.toDouble())
        put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
        put("filled", filled)
    }

    companion object {
        fun fromJson(json: JSONObject): DrawElement {
            val ptsArray = json.optJSONArray("points")
            val pts = mutableListOf<NormalizedPoint>()
            if (ptsArray != null) {
                for (i in 0 until ptsArray.length()) {
                    ptsArray.optJSONObject(i)?.let { pts.add(NormalizedPoint.fromJson(it)) }
                }
            }
            return DrawElement(
                shape = DrawShape.fromWire(json.optString("shape", "free")),
                color = json.optString("color", "#FF4E42"),
                strokeScale = json.optDouble("strokeScale", 0.012).toFloat(),
                points = pts,
                filled = json.optBoolean("filled", false)
            )
        }
    }
}

internal data class CoverCorrectionKeyframe(
    val timeUs: Long = 0L,
    val rect: NormalizedRect,
    val mode: String = "solid", // "solid", "pixelate"
    val easing: String = "linear"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeUs", timeUs)
        put("rect", rect.toJson())
        put("mode", mode)
        put("easing", easing)
    }

    companion object {
        fun fromJson(json: JSONObject): CoverCorrectionKeyframe =
            CoverCorrectionKeyframe(
                timeUs = json.optLong("timeUs", 0L),
                rect = json.optJSONObject("rect")?.let { NormalizedRect.fromJson(it) } ?: NormalizedRect.Full,
                mode = json.optString("mode", "solid"),
                easing = json.optString("easing", "linear")
            )
    }
}

internal sealed interface MemeEditLayer {
    val id: String
    val kind: String
    val active: TimeRangeUs?

    fun toJson(): JSONObject

    data class Text(
        override val id: String,
        val text: String,
        val width: Float = 0.88f,
        val fontSize: Float = 0.118f,
        val style: TextStyle = TextStyle(),
        override val active: TimeRangeUs? = null,
        val keyframes: List<TransformKeyframe> = listOf(TransformKeyframe())
    ) : MemeEditLayer {
        override val kind: String get() = "text"

        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("text", text)
            put("width", width.toDouble())
            put("fontSize", fontSize.toDouble())
            put("style", style.toJson())
            if (active != null) put("active", active.toJson()) else put("active", JSONObject.NULL)
            put("keyframes", JSONArray().apply { keyframes.forEach { put(it.toJson()) } })
        }
    }

    data class Cover(
        override val id: String,
        val rect: NormalizedRect,
        val mode: String = "solid", // "solid", "pixelate"
        val color: String = "#000000",
        val pixelSize: Int = 16,
        override val active: TimeRangeUs? = null,
        val corrections: List<CoverCorrectionKeyframe> = emptyList()
    ) : MemeEditLayer {
        override val kind: String get() = "cover"

        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("rect", rect.toJson())
            put("mode", mode)
            put("color", color)
            put("pixelSize", pixelSize)
            if (active != null) put("active", active.toJson()) else put("active", JSONObject.NULL)
            put("corrections", JSONArray().apply { corrections.forEach { put(it.toJson()) } })
        }
    }

    data class Subject(
        override val id: String,
        val bounds: NormalizedRect,
        val subjectIndex: Int? = null,
        val maskTrackId: String = "",
        val cutoutUri: String = "",
        override val active: TimeRangeUs? = null,
        val keyframes: List<TransformKeyframe> = listOf(TransformKeyframe()),
        val outlineColor: String? = null,
        val outlineScale: Float = 0f,
        val shadowScale: Float = 0f
    ) : MemeEditLayer {
        override val kind: String get() = "subject"

        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            if (subjectIndex != null) put("subjectIndex", subjectIndex) else put("subjectIndex", JSONObject.NULL)
            put("maskTrackId", maskTrackId)
            put("cutoutUri", cutoutUri)
            put("bounds", bounds.toJson())
            if (active != null) put("active", active.toJson()) else put("active", JSONObject.NULL)
            put("keyframes", JSONArray().apply { keyframes.forEach { put(it.toJson()) } })
            if (outlineColor != null) put("outlineColor", outlineColor) else put("outlineColor", JSONObject.NULL)
            put("outlineScale", outlineScale.toDouble())
            put("shadowScale", shadowScale.toDouble())
        }
    }

    data class Media(
        override val id: String,
        val assetUri: String,
        val assetKind: String = "image", // "image", "video"
        val fit: String = "contain", // "contain", "cover"
        val targetMaskTrackId: String? = null,
        override val active: TimeRangeUs? = null,
        val keyframes: List<TransformKeyframe> = listOf(TransformKeyframe())
    ) : MemeEditLayer {
        override val kind: String get() = "media"

        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("assetUri", assetUri)
            put("assetKind", assetKind)
            put("fit", fit)
            if (targetMaskTrackId != null) put("targetMaskTrackId", targetMaskTrackId) else put("targetMaskTrackId", JSONObject.NULL)
            if (active != null) put("active", active.toJson()) else put("active", JSONObject.NULL)
            put("keyframes", JSONArray().apply { keyframes.forEach { put(it.toJson()) } })
        }
    }

    data class Draw(
        override val id: String,
        val elements: List<DrawElement> = emptyList(),
        val opacity: Float = 1f,
        override val active: TimeRangeUs? = null
    ) : MemeEditLayer {
        override val kind: String get() = "draw"

        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })
            put("opacity", opacity.toDouble())
            if (active != null) put("active", active.toJson()) else put("active", JSONObject.NULL)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MemeEditLayer? {
            val id = json.optString("id", "")
            if (id.isBlank()) return null
            val active = json.optJSONObject("active")?.let { TimeRangeUs.fromJson(it) }
            return when (json.optString("kind")) {
                "text" -> {
                    val kfArray = json.optJSONArray("keyframes")
                    val kfs = mutableListOf<TransformKeyframe>()
                    if (kfArray != null) {
                        for (i in 0 until kfArray.length()) {
                            kfArray.optJSONObject(i)?.let { kfs.add(TransformKeyframe.fromJson(it)) }
                        }
                    }
                    if (kfs.isEmpty()) kfs.add(TransformKeyframe())
                    Text(
                        id = id,
                        text = json.optString("text", ""),
                        width = json.optDouble("width", 0.88).toFloat(),
                        fontSize = json.optDouble("fontSize", 0.118).toFloat(),
                        style = json.optJSONObject("style")?.let { TextStyle.fromJson(it) } ?: TextStyle(),
                        active = active,
                        keyframes = kfs
                    )
                }
                "cover" -> {
                    val corrArray = json.optJSONArray("corrections")
                    val corrs = mutableListOf<CoverCorrectionKeyframe>()
                    if (corrArray != null) {
                        for (i in 0 until corrArray.length()) {
                            corrArray.optJSONObject(i)?.let { corrs.add(CoverCorrectionKeyframe.fromJson(it)) }
                        }
                    }
                    Cover(
                        id = id,
                        rect = json.optJSONObject("rect")?.let { NormalizedRect.fromJson(it) } ?: NormalizedRect.Full,
                        mode = json.optString("mode", "solid"),
                        color = json.optString("color", "#000000"),
                        pixelSize = json.optInt("pixelSize", 16),
                        active = active,
                        corrections = corrs
                    )
                }
                "subject" -> {
                    val kfArray = json.optJSONArray("keyframes")
                    val kfs = mutableListOf<TransformKeyframe>()
                    if (kfArray != null) {
                        for (i in 0 until kfArray.length()) {
                            kfArray.optJSONObject(i)?.let { kfs.add(TransformKeyframe.fromJson(it)) }
                        }
                    }
                    if (kfs.isEmpty()) kfs.add(TransformKeyframe())
                    Subject(
                        id = id,
                        bounds = NormalizedRect.fromJson(json.getJSONObject("bounds")),
                        subjectIndex = if (json.isNull("subjectIndex")) null else json.optInt("subjectIndex"),
                        maskTrackId = json.optString("maskTrackId", ""),
                        cutoutUri = json.optString("cutoutUri", ""),
                        active = active,
                        keyframes = kfs,
                        outlineColor = if (json.isNull("outlineColor")) null else json.optString("outlineColor"),
                        outlineScale = json.optDouble("outlineScale", 0.0).toFloat(),
                        shadowScale = json.optDouble("shadowScale", 0.0).toFloat()
                    )
                }
                "media" -> {
                    val kfArray = json.optJSONArray("keyframes")
                    val kfs = mutableListOf<TransformKeyframe>()
                    if (kfArray != null) {
                        for (i in 0 until kfArray.length()) {
                            kfArray.optJSONObject(i)?.let { kfs.add(TransformKeyframe.fromJson(it)) }
                        }
                    }
                    if (kfs.isEmpty()) kfs.add(TransformKeyframe())
                    Media(
                        id = id,
                        assetUri = json.optString("assetUri", ""),
                        assetKind = json.optString("assetKind", "image"),
                        fit = json.optString("fit", "contain"),
                        targetMaskTrackId = if (json.isNull("targetMaskTrackId")) null else json.optString("targetMaskTrackId"),
                        active = active,
                        keyframes = kfs
                    )
                }
                "draw" -> {
                    val elemArray = json.optJSONArray("elements")
                    val elems = mutableListOf<DrawElement>()
                    if (elemArray != null) {
                        for (i in 0 until elemArray.length()) {
                            elemArray.optJSONObject(i)?.let { elems.add(DrawElement.fromJson(it)) }
                        }
                    }
                    Draw(
                        id = id,
                        elements = elems,
                        opacity = json.optDouble("opacity", 1.0).toFloat(),
                        active = active
                    )
                }
                else -> null
            }
        }
    }
}

internal data class BackgroundSpec(
    val mode: String = "source", // "source", "solid", "blurred-source", "image", "video", "transparent"
    val color: String = "#0a0b0e",
    val assetUri: String? = null,
    val blurScale: Float = 0.6f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("color", color)
        if (assetUri != null) put("assetUri", assetUri) else put("assetUri", JSONObject.NULL)
        put("blurScale", blurScale.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): BackgroundSpec =
            BackgroundSpec(
                mode = json.optString("mode", "source"),
                color = json.optString("color", "#0a0b0e"),
                assetUri = if (json.isNull("assetUri")) null else json.optString("assetUri"),
                blurScale = json.optDouble("blurScale", 0.6).toFloat()
            )
    }
}

internal data class InsertedCard(
    val uri: String,
    val atUs: Long,
    val durationUs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("atUs", atUs)
        put("durationUs", durationUs)
    }

    companion object {
        fun fromJson(json: JSONObject): InsertedCard =
            InsertedCard(
                uri = json.optString("uri", ""),
                atUs = json.optLong("atUs", 0L),
                durationUs = json.optLong("durationUs", 1_000_000L)
            )
    }
}

internal data class VideoMusicSpec(
    val uri: String,
    val volume: Float = 1f,
    val startUs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("volume", volume.toDouble())
        put("startUs", startUs)
    }

    companion object {
        fun fromJson(json: JSONObject): VideoMusicSpec =
            VideoMusicSpec(
                uri = json.optString("uri", ""),
                volume = json.optDouble("volume", 1.0).toFloat(),
                startUs = json.optLong("startUs", 0L)
            )
    }
}

internal data class VideoAudioSpec(
    val muted: Boolean = false,
    val volume: Float = 1f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("muted", muted)
        put("volume", volume.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): VideoAudioSpec =
            VideoAudioSpec(
                muted = json.optBoolean("muted", false),
                volume = json.optDouble("volume", 1.0).toFloat()
            )
    }
}

internal data class VideoEditSpec(
    val retainedRanges: List<TimeRangeUs> = emptyList(),
    val speed: Float = 1f,
    val audio: VideoAudioSpec = VideoAudioSpec(),
    val insertedCards: List<InsertedCard> = emptyList(),
    val music: VideoMusicSpec? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("retainedRanges", JSONArray().apply { retainedRanges.forEach { put(it.toJson()) } })
        put("speed", speed.toDouble())
        put("audio", audio.toJson())
        put("insertedCards", JSONArray().apply { insertedCards.forEach { put(it.toJson()) } })
        if (music != null) put("music", music.toJson()) else put("music", JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): VideoEditSpec {
            val rrArray = json.optJSONArray("retainedRanges")
            val rr = mutableListOf<TimeRangeUs>()
            if (rrArray != null) {
                for (i in 0 until rrArray.length()) {
                    rrArray.optJSONObject(i)?.let { rr.add(TimeRangeUs.fromJson(it)) }
                }
            }
            val icArray = json.optJSONArray("insertedCards")
            val ic = mutableListOf<InsertedCard>()
            if (icArray != null) {
                for (i in 0 until icArray.length()) {
                    icArray.optJSONObject(i)?.let { ic.add(InsertedCard.fromJson(it)) }
                }
            }
            return VideoEditSpec(
                retainedRanges = rr,
                speed = json.optDouble("speed", 1.0).toFloat(),
                audio = json.optJSONObject("audio")?.let { VideoAudioSpec.fromJson(it) } ?: VideoAudioSpec(),
                insertedCards = ic,
                music = if (json.isNull("music")) null else json.optJSONObject("music")?.let { VideoMusicSpec.fromJson(it) }
            )
        }
    }
}

internal data class MemeEditSource(
    val uri: String,
    val name: String,
    val kind: MediaEditKind,
    val width: Int,
    val height: Int,
    val durationUs: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        put("kind", kind.wire)
        put("width", width)
        put("height", height)
        if (durationUs != null) put("durationUs", durationUs) else put("durationUs", JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): MemeEditSource =
            MemeEditSource(
                uri = json.optString("uri", ""),
                name = json.optString("name", ""),
                kind = MediaEditKind.fromWire(json.optString("kind", "image")),
                width = json.optInt("width", 1),
                height = json.optInt("height", 1),
                durationUs = if (json.isNull("durationUs")) null else json.optLong("durationUs")
            )
    }
}

internal data class MemeEditProject(
    val version: Int = 2,
    val source: MemeEditSource,
    val base: BaseTransform = BaseTransform(),
    val video: VideoEditSpec? = null,
    val layers: List<MemeEditLayer> = emptyList(),
    val background: BackgroundSpec = BackgroundSpec(),
    val transientMaskTracks: Map<String, String> = emptyMap(),
    val materializedSourceUri: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("source", source.toJson())
        put("base", base.toJson())
        if (video != null) put("video", video.toJson()) else put("video", JSONObject.NULL)
        put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
        put("background", background.toJson())
        put("transient", JSONObject().apply {
            put("maskTracks", JSONObject().apply { transientMaskTracks.forEach { (k, v) -> put(k, v) } })
            if (materializedSourceUri != null) put("materializedSourceUri", materializedSourceUri)
            else put("materializedSourceUri", JSONObject.NULL)
        })
    }

    fun serializeJson(): String = toJson().toString(2)

    companion object {
        fun fromJson(json: JSONObject): MemeEditProject {
            val sourceObj = json.getJSONObject("source")
            val baseObj = json.optJSONObject("base")
            val videoObj = json.optJSONObject("video")
            val layersArray = json.optJSONArray("layers")
            val bgObj = json.optJSONObject("background")
            val transientObj = json.optJSONObject("transient")

            val layersList = mutableListOf<MemeEditLayer>()
            if (layersArray != null) {
                for (i in 0 until layersArray.length()) {
                    layersArray.optJSONObject(i)?.let { layerJson ->
                        MemeEditLayer.fromJson(layerJson)?.let { layersList.add(it) }
                    }
                }
            }

            val maskTracksMap = mutableMapOf<String, String>()
            val mtJson = transientObj?.optJSONObject("maskTracks")
            if (mtJson != null) {
                val keys = mtJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    maskTracksMap[k] = mtJson.optString(k, "")
                }
            }

            return MemeEditProject(
                version = json.optInt("version", 2),
                source = MemeEditSource.fromJson(sourceObj),
                base = if (baseObj != null) BaseTransform.fromJson(baseObj) else BaseTransform(),
                video = if (videoObj != null && !json.isNull("video")) VideoEditSpec.fromJson(videoObj) else null,
                layers = layersList,
                background = if (bgObj != null) BackgroundSpec.fromJson(bgObj) else BackgroundSpec(),
                transientMaskTracks = maskTracksMap,
                materializedSourceUri = if (transientObj != null && !transientObj.isNull("materializedSourceUri"))
                    transientObj.optString("materializedSourceUri") else null
            )
        }

        fun parseJson(jsonString: String): MemeEditProject =
            fromJson(JSONObject(jsonString))
    }
}

internal data class InterpolatedTransform(
    val center: NormalizedPoint,
    val scale: Float,
    val rotationDegrees: Float,
    val opacity: Float
)

internal object ProjectLimits {
    const val MAX_LAYERS = 64
    const val MAX_RETAINED_RANGES = 32
    const val MAX_KEYFRAMES_PER_LAYER = 256
    const val MAX_INSERTED_CARDS = 32
    const val MAX_DRAW_ELEMENTS = 512
    const val MAX_POINTS_PER_DRAW_ELEMENT = 1024
    const val MAX_DRAW_POINTS_PER_LAYER = 8192
    const val MAX_HISTORY_STATES = 30
    const val MAX_IMAGE_RENDER_PIXELS = 16_000_000L
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f
    const val MIN_TEXT_WRAP_WIDTH = 0.12f
    const val MAX_TEXT_WRAP_WIDTH = 1.0f
    const val MIN_FONT_SIZE = 0.035f
    const val MAX_FONT_SIZE = 0.2f
}
