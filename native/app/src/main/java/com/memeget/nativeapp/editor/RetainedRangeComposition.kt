package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TimestampAdjustment
import androidx.media3.effect.TimestampAdjustmentShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.io.IOException

/**
 * Native override of RetainedRangeComposition supporting HDR tone-mapping when
 * OverlayEffect is passed in videoEffects (for time-aware video overlays).
 */
@OptIn(UnstableApi::class)
object RetainedRangeComposition {
  const val MIN_CARD_DURATION_US = 200_000L
  const val MAX_CARD_DURATION_US = 10_000_000L
  const val DEFAULT_CARD_FRAME_RATE = 30
  const val MAX_SEGMENTS = 96
  const val MAX_CARD_PIXELS = 64_000_000L

  val CARD_MIME_TYPES: Set<String> = setOf(
    MimeTypes.IMAGE_PNG,
    MimeTypes.IMAGE_JPEG,
    MimeTypes.IMAGE_WEBP,
    MimeTypes.IMAGE_BMP,
    MimeTypes.IMAGE_HEIF,
    MimeTypes.IMAGE_HEIC
  )

  sealed interface Segment {
    val timelineDurationUs: Long
  }

  data class Range(val startUs: Long, val endUs: Long) : Segment {
    init {
      require(startUs >= 0L) { "startUs must be >= 0, was $startUs" }
      require(endUs > startUs) { "endUs ($endUs) must be greater than startUs ($startUs)" }
    }

    val durationUs: Long get() = endUs - startUs
    override val timelineDurationUs: Long get() = durationUs
  }

  data class Card(
    val uri: String,
    val mimeType: String,
    val durationUs: Long,
    val frameRate: Int = DEFAULT_CARD_FRAME_RATE
  ) : Segment {
    init {
      require(uri.isNotBlank()) { "A title card needs a uri" }
      require(mimeType in CARD_MIME_TYPES) {
        "$mimeType is not a still image media3 can decode; use one of $CARD_MIME_TYPES"
      }
      require(durationUs in MIN_CARD_DURATION_US..MAX_CARD_DURATION_US) {
        "Card duration ${durationUs}us must be between ${MIN_CARD_DURATION_US}us and ${MAX_CARD_DURATION_US}us"
      }
      require(durationUs % 1_000L == 0L) {
        "Card duration ${durationUs}us must be a whole number of milliseconds"
      }
      require(frameRate > 0) { "frameRate must be positive, was $frameRate" }
    }

    override val timelineDurationUs: Long get() = durationUs
  }

  private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    init {
      require(speed > 0f) { "speed must be positive, was $speed" }
    }

    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET

    override fun equals(other: Any?): Boolean =
      this === other || (other is ConstantSpeedProvider && other.speed == speed)

    override fun hashCode(): Int = speed.hashCode()
  }

  private class SequenceSpeedTimestampEffect(
    private val timestampMap: TimestampAdjustment.TimestampMap
  ) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
      TimestampAdjustmentShaderProgram(timestampMap)
  }

  fun build(
    uri: String,
    sourceDurationUs: Long,
    ranges: List<Range>,
    speed: Float = 1f,
    audioProcessors: List<AudioProcessor> = emptyList(),
    videoEffects: List<Effect> = emptyList()
  ): Composition = buildTimeline(
    uri = uri,
    sourceDurationUs = sourceDurationUs,
    segments = ranges,
    speed = speed,
    audioProcessors = audioProcessors,
    videoEffects = videoEffects
  )

  fun buildTimeline(
    uri: String,
    sourceDurationUs: Long,
    segments: List<Segment>,
    speed: Float = 1f,
    sourceHasAudio: Boolean = true,
    outputSize: Size? = null,
    sourceVideoEffects: List<Effect> = emptyList(),
    audioProcessors: List<AudioProcessor> = emptyList(),
    videoEffects: List<Effect> = emptyList(),
    overlay: Bitmap? = null,
    musicUri: String? = null,
    musicVolume: Float = 1f,
    musicStartUs: Long = 0
  ): Composition {
    require(segments.isNotEmpty()) { "At least one segment is required" }
    require(segments.size <= MAX_SEGMENTS) {
      "A composition may hold at most $MAX_SEGMENTS segments, got ${segments.size}"
    }
    var hasRange = false
    var hasCard = false
    for (segment in segments) {
      when (segment) {
        is Range -> {
          hasRange = true
          require(segment.endUs <= sourceDurationUs) {
            "Range ${segment.startUs}..${segment.endUs} exceeds source duration $sourceDurationUs"
          }
        }
        is Card -> hasCard = true
      }
    }
    require(hasRange) { "At least one retained range is required" }
    if (hasCard) {
      requireNotNull(outputSize) {
        "Title cards need an explicit outputSize; otherwise the first item's frame decides the resolution of the whole export"
      }
    }
    outputSize?.let {
      require(it.width > 0 && it.height > 0) { "outputSize must be positive, was $it" }
    }

    val speedProcessor = if (speed == 1f) null else {
      SpeedChangingAudioProcessor(ConstantSpeedProvider(speed))
    }
    val presentation = outputSize?.let {
      Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT)
    }

    val mixMusic = musicUri != null
    val sourceItemAudioProcessors = if (mixMusic) audioProcessors else emptyList()
    val items = segments.map { segment ->
      val itemEffects = buildList(2 + sourceVideoEffects.size) {
        if (speedProcessor != null) {
          add(SequenceSpeedTimestampEffect(speedProcessor::getSpeedAdjustedTimeAsync))
        }
        if (segment is Range) addAll(sourceVideoEffects)
        if (presentation != null) add(presentation)
      }
      when (segment) {
        is Range -> EditedMediaItem.Builder(clip(uri, segment))
          .setDurationUs(sourceDurationUs)
          .setEffects(Effects(sourceItemAudioProcessors, itemEffects))
          .build()
        is Card -> EditedMediaItem.Builder(cardItem(segment))
          .setDurationUs(segment.durationUs)
          .setFrameRate(segment.frameRate)
          .setEffects(Effects(emptyList(), itemEffects))
          .build()
      }
    }

    @Suppress("DEPRECATION")
    val sequenceBuilder = EditedMediaItemSequence.Builder(items)
    if (hasCard && sourceHasAudio) {
      @Suppress("DEPRECATION")
      sequenceBuilder.experimentalSetForceAudioTrack(true)
    }
    val sequence = sequenceBuilder.build()

    val musicSequence = musicUri?.let { source ->
      val gain = GainProcessor(DefaultGainProvider.Builder(musicVolume).build())
      val mediaItem = if (musicStartUs > 0L) {
        MediaItem.Builder()
          .setUri(source)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(musicStartUs / 1000L).build()
          )
          .build()
      } else {
        MediaItem.fromUri(source)
      }
      val musicItem = EditedMediaItem.Builder(mediaItem)
        .setRemoveVideo(true)
        .setEffects(Effects(listOf(gain), emptyList()))
        .build()
      @Suppress("DEPRECATION")
      EditedMediaItemSequence.Builder(musicItem)
        .setIsLooping(true)
        .build()
    }

    val compositionSourceProcessors = if (mixMusic) emptyList() else audioProcessors
    val compositionAudioProcessors = if (speedProcessor == null) compositionSourceProcessors else {
      buildList(compositionSourceProcessors.size + 1) {
        add(speedProcessor)
        addAll(compositionSourceProcessors)
      }
    }

    val compositionVideoEffects = if (overlay == null) videoEffects else {
      buildList(videoEffects.size + 1) {
        addAll(videoEffects)
        add(overlayEffect(overlay, outputSize))
      }
    }

    val builder = if (musicSequence == null) {
      Composition.Builder(sequence)
    } else {
      Composition.Builder(sequence, musicSequence)
    }.setEffects(Effects(compositionAudioProcessors, compositionVideoEffects))

    // Tone-map HDR down to SDR whenever an overlay bitmap OR OverlayEffect is present
    if (overlay != null || videoEffects.any { it is OverlayEffect }) {
      builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
    }
    return builder.build()
  }

  private fun overlayEffect(bitmap: Bitmap, outputSize: Size?): OverlayEffect {
    val overlay = if (outputSize == null) {
      BitmapOverlay.createStaticBitmapOverlay(bitmap)
    } else {
      val settings = StaticOverlaySettings.Builder()
        .setScale(
          outputSize.width.toFloat() / bitmap.width.toFloat(),
          outputSize.height.toFloat() / bitmap.height.toFloat()
        )
        .build()
      BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
    }
    return OverlayEffect(listOf(overlay))
  }

  private fun clip(uri: String, range: Range): MediaItem =
    MediaItem.Builder()
      .setUri(uri)
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionUs(range.startUs)
          .setEndPositionUs(range.endUs)
          .build()
      )
      .build()

  private fun cardItem(card: Card): MediaItem =
    MediaItem.Builder()
      .setUri(card.uri)
      .setMimeType(card.mimeType)
      .setImageDurationMs(card.durationUs / 1_000L)
      .build()

  fun expectedOutputDurationUs(segments: List<Segment>, speed: Float = 1f): Long {
    val timelineUs = segments.sumOf { it.timelineDurationUs }
    if (speed == 1f) return timelineUs
    return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(
      ConstantSpeedProvider(speed),
      timelineUs
    )
  }

  enum class AssetRole { TITLE_CARD, REPLACEMENT_VIDEO, REPLACEMENT_AUDIO }

  data class AssetRequirement(
    val uri: String,
    val role: AssetRole,
    val declaredMimeType: String? = null
  )

  data class AssetRejection(val uri: String, val role: AssetRole, val reason: String)

  fun inspectAssets(
    context: Context,
    requirements: List<AssetRequirement>
  ): List<AssetRejection> = requirements.mapNotNull { requirement ->
    val reason = when (requirement.role) {
      AssetRole.TITLE_CARD -> titleCardRejection(context, requirement)
      AssetRole.REPLACEMENT_VIDEO -> trackRejection(context, requirement.uri, "video/", "video")
      AssetRole.REPLACEMENT_AUDIO -> trackRejection(context, requirement.uri, "audio/", "audio")
    }
    reason?.let { AssetRejection(requirement.uri, requirement.role, it) }
  }

  private fun titleCardRejection(context: Context, requirement: AssetRequirement): String? {
    val declared = requirement.declaredMimeType
      ?: return "No MIME type was declared, so the exporter cannot tell what to decode."
    if (declared !in CARD_MIME_TYPES) {
      return "$declared is not a still image media3 can decode. Re-encode the card as PNG, JPEG, WebP, BMP or HEIF."
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      val stream = context.contentResolver.openInputStream(Uri.parse(requirement.uri))
        ?: return "The asset could not be opened for decoding."
      stream.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (error: Exception) {
      return "The asset could not be opened: ${error.message.orEmpty().take(200)}"
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) {
      return "The bytes could not be decoded as a still image."
    }
    val sniffed = options.outMimeType
    if (sniffed != null && normalizedImageMime(sniffed) != normalizedImageMime(declared)) {
      return "The bytes are $sniffed, not the declared $declared."
    }
    val pixels = options.outWidth.toLong() * options.outHeight.toLong()
    if (pixels > MAX_CARD_PIXELS) {
      return "The card is ${options.outWidth}x${options.outHeight}, past the ${MAX_CARD_PIXELS / 1_000_000}MP the frame processor will upload."
    }
    return null
  }

  private fun normalizedImageMime(mimeType: String): String =
    if (mimeType.equals(MimeTypes.IMAGE_HEIC, ignoreCase = true)) {
      MimeTypes.IMAGE_HEIF
    } else {
      mimeType.lowercase()
    }

  private fun trackRejection(
    context: Context,
    uri: String,
    mimePrefix: String,
    label: String
  ): String? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(context, Uri.parse(uri), null)
      var format: MediaFormat? = null
      for (index in 0 until extractor.trackCount) {
        val candidate = extractor.getTrackFormat(index)
        val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(mimePrefix)) {
          format = candidate
          break
        }
      }
      if (format == null) {
        return "The asset has no $label track, so using it here would silently drop the $label."
      }
      val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
      if (!decoderExists(format, mime, mimePrefix)) {
        "This device has no decoder for $mime."
      } else {
        null
      }
    } catch (error: IOException) {
      "The asset could not be opened: ${error.message.orEmpty().take(200)}"
    } catch (error: IllegalArgumentException) {
      "The asset could not be opened: ${error.message.orEmpty().take(200)}"
    } finally {
      extractor.release()
    }
  }

  private fun decoderExists(format: MediaFormat, mime: String, mimePrefix: String): Boolean {
    val probe = if (mimePrefix == "video/") {
      MediaFormat.createVideoFormat(
        mime,
        format.intOr(MediaFormat.KEY_WIDTH, 640),
        format.intOr(MediaFormat.KEY_HEIGHT, 480)
      )
    } else {
      MediaFormat.createAudioFormat(
        mime,
        format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100),
        format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
      )
    }
    return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null
  }

  private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}
