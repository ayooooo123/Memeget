package expo.modules.memegetbg

import android.content.Context
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
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TimestampAdjustment
import androidx.media3.effect.TimestampAdjustmentShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.memeget.nativeapp.editor.ExactLengthAudioProcessor
import com.memeget.nativeapp.editor.LiveGain
import com.memeget.nativeapp.editor.LiveVolume
import com.memeget.nativeapp.editor.MusicPlan
import java.io.IOException
import kotlin.math.roundToLong

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

  /** An added sound: the track, where it plays ([MusicPlan], edited-video time) and its live volume. */
  internal class Music(
    val uri: String,
    /** Whole track length, which Media3 needs to clip it. */
    val trackDurationUs: Long,
    val plan: MusicPlan,
    val volume: LiveVolume
  )

  /**
   * @param forPlayer build for [androidx.media3.transformer.CompositionPlayer]. Media3 1.9 has no speed that both
   *   preview and export accept (Pixel 5a): the player only takes speed per item, made by
   *   [Effects.createExperimentalSpeedChangingEffect], and Transformer stalls on any per-item speed ("no output
   *   sample written in the last 10000 milliseconds"). So the player gets per-item speed and the export speeds up
   *   the whole mix, with the music slowed by the same factor first. Either way the music keeps its own tempo.
   */
  internal fun buildTimeline(
    uri: String,
    sourceDurationUs: Long,
    segments: List<Segment>,
    speed: Float = 1f,
    sourceHasAudio: Boolean = true,
    outputSize: Size? = null,
    sourceVideoEffects: List<Effect> = emptyList(),
    audioProcessors: List<AudioProcessor> = emptyList(),
    videoEffects: List<Effect> = emptyList(),
    music: Music? = null,
    forPlayer: Boolean
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

    val presentation = outputSize?.let {
      Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT)
    }
    val mixSpeed = if (forPlayer || speed == 1f) null else SpeedChangingAudioProcessor(ConstantSpeedProvider(speed))

    val items = segments.map { segment ->
      val itemSpeed = if (forPlayer && speed != 1f) Effects.createExperimentalSpeedChangingEffect(ConstantSpeedProvider(speed)) else null
      val itemAudio = buildList {
        itemSpeed?.let { add(it.first) }
        // The source's own processors (its volume) stay on its items, away from the music.
        if (segment is Range) addAll(audioProcessors)
      }
      val itemVideo = buildList(2 + sourceVideoEffects.size) {
        itemSpeed?.let { add(it.second) }
        if (mixSpeed != null) add(SequenceSpeedTimestampEffect(mixSpeed::getSpeedAdjustedTimeAsync))
        if (segment is Range) addAll(sourceVideoEffects)
        if (presentation != null) add(presentation)
      }
      when (segment) {
        is Range -> EditedMediaItem.Builder(clip(uri, segment))
          .setDurationUs(sourceDurationUs)
          .setEffects(Effects(itemAudio, itemVideo))
          .build()
        is Card -> EditedMediaItem.Builder(cardItem(segment))
          .setDurationUs(segment.durationUs)
          .setFrameRate(segment.frameRate)
          .setEffects(Effects(itemAudio, itemVideo))
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

    val musicSequence = music?.let {
      musicSequence(it, expectedOutputDurationUs(segments, speed), if (mixSpeed != null) speed else 1f, forPlayer)
    }
    val builder = if (musicSequence == null) {
      Composition.Builder(sequence)
    } else {
      Composition.Builder(sequence, musicSequence)
    }.setEffects(Effects(listOfNotNull(mixSpeed), videoEffects))

    // Tone-map HDR down to SDR whenever an OverlayEffect is present
    if (videoEffects.any { it is OverlayEffect }) {
      builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
    }
    return builder.build()
  }

  /**
   * The music as its own audio sequence, exactly as long as the edited video ([outputUs]). [mixSpeed] is the
   * speed the composition later applies to the whole mix: each piece is first slowed by it, and its gaps
   * lengthened, so the music comes out at its own tempo and in place.
   */
  private fun musicSequence(music: Music, outputUs: Long, mixSpeed: Float, forPlayer: Boolean): EditedMediaItemSequence {
    val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
    fun mixUs(us: Long) = (us * mixSpeed.toDouble()).roundToLong()
    val leadInUs = mixUs(music.plan.leadInUs)
    if (leadInUs > 0L) builder.addGap(leadInUs)
    for ((index, piece) in music.plan.pieces.withIndex()) {
      // The player's first item keeps its clip's own clock (see ExactLengthAudioProcessor); export starts at 0.
      val streamStartUs = if (forPlayer && index == 0 && leadInUs == 0L) piece.clipStartUs else 0L
      val item = MediaItem.Builder()
        .setUri(music.uri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(piece.clipStartUs)
            .setEndPositionUs(piece.clipEndUs)
            .build()
        )
        .build()
      // The slowed piece reaches the gain processor stretched by mixSpeed; it maps that back to the video's clock.
      val gain = LiveGain(music.volume, piece.outputStartUs, music.plan, outputUsPerStreamUs = 1.0 / mixSpeed, streamStartUs = streamStartUs)
      val edited = EditedMediaItem.Builder(item)
        .setRemoveVideo(true)
        .setDurationUs(music.trackDurationUs)
        .setEffects(
          Effects(
            // The trim sees the piece after the slowdown too: mixSpeed times as long in its own clock.
            listOf(ExactLengthAudioProcessor(mixUs(piece.durationUs), streamStartUs), GainProcessor(gain)),
            emptyList()
          )
        )
      if (mixSpeed != 1f) edited.setSpeed(ConstantSpeedProvider(1f / mixSpeed))
      builder.addItem(edited.build())
    }
    // Silence to the end, so the music sequence never decides the export's length.
    val tailUs = mixUs(outputUs - music.plan.endUs)
    if (tailUs >= MusicPlan.MIN_PIECE_US) builder.addGap(tailUs)
    return builder.build()
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
