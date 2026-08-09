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
 * Builds the "keep these source ranges, then change the playback speed" [Composition] used by the
 * clip editor.
 *
 * ## Why the speed is not set with `EditedMediaItem.Builder.setSpeed`
 *
 * media3 1.9.0 scales a sequence's per-item video offset twice when the speed lives on the item:
 *
 * * `Transformer#addSpeedChangingEffects` installs a `TimestampAdjustment` as the item's *first*
 *   video effect, backed by `SpeedChangingAudioProcessor#getSpeedAdjustedTimeAsync`, which
 *   integrates the speed curve from time zero.
 * * `VideoSampleExporter.VideoGraphInput#onMediaItemChanged` accumulates the item offset as
 *   `EditedMediaItem#getDurationAfterEffectsApplied(durationUs)` - which, with a speed provider
 *   set, is already divided by the speed - and passes it as `offsetToAddUs` to
 *   `VideoFrameProcessor#registerInputStream`, whose contract is that the offset "is added to the
 *   frame timestamps *before* processing".
 *
 * Item `i`'s frames therefore land at `offset/speed + sourceTime/speed` instead of
 * `offset + sourceTime/speed`. Audio does not share the defect: `SequenceAssetLoader` reports
 * `positionOffsetUs = 0` for every item, so each item's audio restarts at sample 0 and the mixer
 * concatenates already-scaled item outputs. The result is a video track short by
 * `(1 - 1/speed) * offset` against a correct-length audio track.
 *
 * ## What this builder does instead
 *
 * * The [SpeedChangingAudioProcessor] is a **composition** audio processor, so it runs once on the
 *   concatenated mix rather than once per item.
 * * Each item carries its own [SequenceSpeedTimestampEffect], which maps video timestamps through
 *   that same processor - the identical arithmetic, on the identical global timeline.
 * * That effect reports the *identity* for `getDurationAfterEffectApplied`, so the per-item offset
 *   media3 adds before the effect chain stays unscaled and the single division inside the effect
 *   produces `offset/speed + sourceTime/speed` - the correct, once-scaled result.
 *
 * A per-item effect instance is also load-bearing: `DefaultVideoFrameProcessor#configure` only
 * rebuilds the shader chain when the new stream's effect list differs from the active one. A
 * single shared timestamp effect is reused across the item boundary and the frame processor
 * deadlocks on the first frame of the second item (observed as
 * `ExportException.ERROR_CODE_MUXING_TIMEOUT`).
 *
 * ## Export only
 *
 * When [speed] is not `1f` the result is for `Transformer` export, not for `CompositionPlayer`
 * preview. `CompositionPlayer#setComposition` rejects any composition-level speed changing effect
 * ("CompositionPlayer only allows speed changing effects created from
 * Effects#createExperimentalSpeedChangingEffect() placed as first effects within an
 * EditedMediaItem"), and `TimestampAdjustmentShaderProgram#flush` throws outright. Preview must
 * use `EditedMediaItem.Builder#setSpeed`, which `CompositionPlayer` rewrites into a
 * `SpeedChangingMediaSource` and which does not exhibit the export defect above.
 *
 * ## Title cards and the output frame
 *
 * [buildTimeline] interleaves static image [Card]s between retained [Range]s. Two consequences
 * fall out of the "one global timeline" design above and are load-bearing:
 *
 * * A card's duration is a **timeline** duration, scaled by [speed] like everything else. Cards
 *   ride the same [SpeedChangingAudioProcessor] and the same per-item timestamp effect as the
 *   source segments, so exempting them would reintroduce exactly the two-timebase mistake this
 *   file exists to avoid.
 * * media3 sizes the encoder from the FIRST item's output frame. A leading card would therefore
 *   dictate the resolution of the whole export, and a card of a different aspect would silently
 *   restretch every source segment behind it. Whenever a card is present, `outputSize` is
 *   required and a single shared [Presentation] pins every item - source and card alike - to that
 *   frame, which is also what keeps a rotated source rotated across every seam.
 */
@OptIn(UnstableApi::class)
object RetainedRangeComposition {
  /** A title card is a beat, not a second clip. */
  const val MIN_CARD_DURATION_US = 200_000L
  const val MAX_CARD_DURATION_US = 10_000_000L

  /** [androidx.media3.transformer.ImageAssetLoader] refuses an image item without a frame rate. */
  const val DEFAULT_CARD_FRAME_RATE = 30

  /**
   * Every retained range can be split at most once per inserted card, so 32 ranges and 32 cards
   * (`PROJECT_LIMITS` in `memeEditProjectCore.ts`) bound the item list at 96. Each item is a
   * separate asset loader and shader-chain rebuild; an unbounded list is an unbounded export.
   */
  const val MAX_SEGMENTS = 96

  /**
   * A card is uploaded to a GL texture whole. `GlUtil.MAX_BITMAP_DECODING_SIZE` caps what media3's
   * bitmap loader will hand over; screen for it first so an oversized card is a sentence rather
   * than an out-of-memory kill mid-export.
   */
  const val MAX_CARD_PIXELS = 64_000_000L

  /**
   * The still-image types media3's `DefaultAssetLoaderFactory` routes to `ImageAssetLoader` AND
   * `BitmapFactory` decodes without a plugin. `image/gif` and `image/svg+xml` are deliberately
   * absent: media3 names them, but a GIF would collapse to one frame of an animation and an SVG
   * has no platform decoder. Substituting either is exactly the silent failure this rejects.
   */
  val CARD_MIME_TYPES: Set<String> = setOf(
    MimeTypes.IMAGE_PNG,
    MimeTypes.IMAGE_JPEG,
    MimeTypes.IMAGE_WEBP,
    MimeTypes.IMAGE_BMP,
    MimeTypes.IMAGE_HEIF,
    MimeTypes.IMAGE_HEIC
  )

  /** One item on the composition timeline. */
  sealed interface Segment {
    /** Length on the pre-speed timeline, in microseconds. */
    val timelineDurationUs: Long
  }

  /** A half-open source range, in microseconds, that survives the edit. */
  data class Range(val startUs: Long, val endUs: Long) : Segment {
    init {
      require(startUs >= 0L) { "startUs must be >= 0, was $startUs" }
      require(endUs > startUs) { "endUs ($endUs) must be greater than startUs ($startUs)" }
    }

    val durationUs: Long get() = endUs - startUs

    override val timelineDurationUs: Long get() = durationUs
  }

  /**
   * A static image held on screen for [durationUs] of timeline.
   *
   * @param mimeType Stated, never sniffed: `DefaultAssetLoaderFactory` only asks the
   *   ContentResolver for `content://` URIs and otherwise guesses from the file extension, so a
   *   card whose name lies would be loaded as a video and fail deep inside the exporter.
   * @param durationUs Must be a whole number of milliseconds: `MediaItem.Builder#setImageDurationMs`
   *   - the only way to make media3 treat a URI as a still image - takes milliseconds, and
   *   rounding here rather than there keeps [expectedOutputDurationUs] exact.
   */
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
        "Card duration ${durationUs}us must be between ${MIN_CARD_DURATION_US}us and " +
          "${MAX_CARD_DURATION_US}us"
      }
      require(durationUs % 1_000L == 0L) {
        "Card duration ${durationUs}us must be a whole number of milliseconds"
      }
      require(frameRate > 0) { "frameRate must be positive, was $frameRate" }
    }

    override val timelineDurationUs: Long get() = durationUs
  }

  /** The constant-rate [SpeedProvider] media3 1.9.0 does not ship. */
  private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    init {
      require(speed > 0f) { "speed must be positive, was $speed" }
    }

    override fun getSpeed(timeUs: Long): Float = speed

    // C.TIME_UNSET, not C.TIME_END_OF_SOURCE: SpeedProviderUtil reads TIME_UNSET as "no further
    // change" and would loop forever on the negative TIME_END_OF_SOURCE sentinel.
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET

    override fun equals(other: Any?): Boolean =
      this === other || (other is ConstantSpeedProvider && other.speed == speed)

    override fun hashCode(): Int = speed.hashCode()
  }

  /**
   * Applies a sequence-global timestamp map to one item's video frames.
   *
   * Deliberately *not* media3's [TimestampAdjustment]: that effect reports
   * `durationUs / speed` from `getDurationAfterEffectApplied`, which would make the sequence scale
   * the following items' offsets before this effect scales them again. Keeping the reported
   * duration unchanged leaves the offset in the source timebase, where the map expects it.
   *
   * Intentionally has no `equals` override - each item needs a distinct instance so the frame
   * processor rebuilds its shader chain at every item boundary.
   */
  private class SequenceSpeedTimestampEffect(
    private val timestampMap: TimestampAdjustment.TimestampMap
  ) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
      TimestampAdjustmentShaderProgram(timestampMap)
  }

  /**
   * The range-only composition: unchanged behaviour, and the shape every existing caller uses.
   *
   * @param uri The source URI, as accepted by [MediaItem.Builder.setUri].
   * @param sourceDurationUs The full, unclipped duration of [uri]. media3 wants the *whole* item
   *   duration here, not the retained duration: `EditedMediaItem#getPresentationDurationUs`
   *   asserts `clippingConfiguration.endPositionUs <= durationUs`.
   * @param ranges The source ranges to keep, in order.
   * @param speed Playback speed multiplier; `1f` leaves the timeline untouched.
   * @param audioProcessors Extra composition audio processors, applied after the speed change.
   * @param videoEffects Extra composition video effects, applied after the speed change so they
   *   observe output timestamps.
   */
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

  /**
   * The full timeline: retained [Range]s in order, with static [Card]s wherever the plan put them.
   *
   * @param segments Composition order. At least one [Range] is required - a card-only "video" is
   *   a slideshow, not an edit of [uri].
   * @param sourceHasAudio Whether [uri] actually carries an audio track. Cards have none, so a
   *   card that leads the sequence would leave media3 with no audio consumer to reuse; forcing the
   *   track fills the card with silence instead. Forcing it for a genuinely silent source would
   *   invent an audio track the user never had, so this is not defaulted from "there is a card".
   * @param outputSize The frame every item is presented into. Required whenever a card is present
   *   (see the class comment); `null` keeps the pre-card behaviour of letting the source decide.
   * @param sourceVideoEffects Effects that describe the SOURCE frame - the project's rotation,
   *   flips and crop - applied to [Range] items only, before the shared [Presentation]. A [Card]
   *   is authored for the output frame the plan already chose, so cropping it would cut away part
   *   of an image the user positioned deliberately.
   * @param overlay A transparent bitmap composited over EVERY output frame, scaled to fill the
   *   output frame - the burn-in path for text, covers, cutouts and drawings on an exported clip.
   *   Appended to the composition video effect chain so it runs on the composited output; `null`
   *   leaves that chain exactly as it was, so a clip with no annotations is byte-identical. The
   *   caller owns the bitmap and must keep it alive until the export releases its texture.
   * @param musicUri An added audio track mixed onto the single video output as a SECOND sequence,
   *   or `null` for none. The source audio (sequence one) is untouched by this: a muted source
   *   plus music is a clean replace, an unmuted source plus music is both mixed. The music sequence
   *   carries no video and is looped to fill the output duration. `null` leaves the sequence list
   *   and [Composition] exactly as before, so a music-free export is byte-identical.
   * @param musicVolume Linear gain applied to [musicUri] on the music item itself, never on the
   *   composition audio processors, so the source's own gain is unaffected. Ignored when
   *   [musicUri] is `null`.
   */
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
        "Title cards need an explicit outputSize; otherwise the first item's frame decides the " +
          "resolution of the whole export"
      }
    }
    outputSize?.let {
      require(it.width > 0 && it.height > 0) { "outputSize must be positive, was $it" }
    }

    val speedProcessor = if (speed == 1f) null else {
      SpeedChangingAudioProcessor(ConstantSpeedProvider(speed))
    }
    // One shared instance on purpose. `DefaultVideoFrameProcessor#configure` compares effect lists
    // to decide whether to rebuild the shader chain, and at `speed == 1f` the pre-card code left
    // that list identical across items. Handing every item the same Presentation keeps it that
    // way; the speed case already differs per item through its own timestamp effect.
    val presentation = outputSize?.let {
      Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT)
    }
    // Where the source gain ([audioProcessors]) is applied depends on whether music is mixed in.
    // media3 runs a Composition's audio processors ONCE on the mixer output - the combined audio
    // of every sequence (AudioSampleExporter builds one AudioGraph over all inputs). With music as
    // a second sequence, a composition-level source gain would therefore also scale the music, so
    // a muted source (gain 0) would silence the music too instead of the "music only" clean
    // replace the plan promises. When music is present the source gain moves onto the source items
    // themselves, before the mix; with no music it stays a composition processor, exactly as
    // before, so a music-free export is byte-identical. A constant gain commutes with the
    // concatenation and the speed change, so this move is inaudible for the source itself.
    val mixMusic = musicUri != null
    val sourceItemAudioProcessors = if (mixMusic) audioProcessors else emptyList()
    val items = segments.map { segment ->
      val itemEffects = buildList(2 + sourceVideoEffects.size) {
        if (speedProcessor != null) {
          add(SequenceSpeedTimestampEffect(speedProcessor::getSpeedAdjustedTimeAsync))
        }
        // Shared instances across items, like the presentation below: the source geometry is the
        // same frame-to-frame, so an equal effect list keeps the shader chain from rebuilding.
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

    // EditedMediaItemSequence.Builder(List) is deprecated in favour of Builder(Set<TrackType>),
    // but that overload forces the listed tracks: it would synthesise a silent audio track for
    // sources that have none. Keep the "infer from the items" behaviour.
    @Suppress("DEPRECATION")
    val sequenceBuilder = EditedMediaItemSequence.Builder(items)
    if (hasCard && sourceHasAudio) {
      @Suppress("DEPRECATION")
      sequenceBuilder.experimentalSetForceAudioTrack(true)
    }
    val sequence = sequenceBuilder.build()
    // The added music, mixed in as a SECOND sequence so media3's audio mixer lays it over the
    // single video output. Its gain rides the music item itself so it is set before the mix,
    // independent of the source gain (which for a music mix has moved onto the source items for the
    // same reason). The sequence loops to fill the output: a looping sequence plays until the
    // longest non-looping sequence - the video - ends (EditedMediaItemSequence.Builder.setIsLooping,
    // media3 1.9.0). The item removes any video the music file might carry so it contributes audio
    // only.
    val musicSequence = musicUri?.let { source ->
      val gain = GainProcessor(DefaultGainProvider.Builder(musicVolume).build())
      // Start the track at the chosen in-point, then loop on from there.
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
    // Composition audio processors run post-mix. The speed change belongs here (it maps the whole
    // concatenated timeline; see the class comment). The source gain is here too UNLESS music is
    // mixed in, in which case it has moved onto the source items so it does not also scale the
    // music. With no music this is the pre-existing list, so the audio is byte-identical.
    val compositionSourceProcessors = if (mixMusic) emptyList() else audioProcessors
    val compositionAudioProcessors = if (speedProcessor == null) compositionSourceProcessors else {
      buildList(compositionSourceProcessors.size + 1) {
        add(speedProcessor)
        addAll(compositionSourceProcessors)
      }
    }
    // A null overlay leaves the video effect chain untouched, so an annotation-free export is
    // byte-identical to before. When present it runs LAST on the composited output frame, over
    // the source (or card) the Presentation already pinned to `outputSize`.
    val compositionVideoEffects = if (overlay == null) videoEffects else {
      buildList(videoEffects.size + 1) {
        addAll(videoEffects)
        add(overlayEffect(overlay, outputSize))
      }
    }
    // A null music sequence leaves the composition a single video sequence, exactly as before.
    val builder = if (musicSequence == null) {
      Composition.Builder(sequence)
    } else {
      Composition.Builder(sequence, musicSequence)
    }.setEffects(Effects(compositionAudioProcessors, compositionVideoEffects))
    // An SDR bitmap overlay's shader cannot run over an HDR frame, so when an
    // overlay is present ask media3 to tone-map an HDR input down to SDR first;
    // without an overlay the input's dynamic range is left untouched.
    if (overlay != null) builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
    return builder.build()
  }

  /**
   * A [BitmapOverlay] scaled to fill the output frame.
   *
   * media3 maps an overlay's own pixels 1:1 onto the frame and centres it, so filling a frame of
   * [outputSize] is a scale of frame/bitmap on each axis. The overlay is rendered at the output
   * size, so the two scales match and nothing is stretched; deriving them from the DECODE's real
   * size rather than the plan's stated size keeps the fill exact even if the bitmap came back
   * downsampled. With no [outputSize] the overlay keeps its native 1:1 mapping.
   */
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

  // setImageDurationMs is what routes the item to ImageAssetLoader at all:
  // DefaultAssetLoaderFactory checks `localConfiguration.imageDurationMs != C.TIME_UNSET` before
  // it will treat a URI as a still.
  private fun cardItem(card: Card): MediaItem =
    MediaItem.Builder()
      .setUri(card.uri)
      .setMimeType(card.mimeType)
      .setImageDurationMs(card.durationUs / 1_000L)
      .build()

  /**
   * The output duration media3 will produce for [segments] at [speed], derived with the same
   * `SpeedProviderUtil` arithmetic (and the same flooring) the exporter uses. Cards count on the
   * same timeline as the ranges - see the class comment.
   */
  fun expectedOutputDurationUs(segments: List<Segment>, speed: Float = 1f): Long {
    val timelineUs = segments.sumOf { it.timelineDurationUs }
    if (speed == 1f) return timelineUs
    return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(
      ConstantSpeedProvider(speed),
      timelineUs
    )
  }

  /** What an asset would be used for; each role has its own compatibility contract. */
  enum class AssetRole { TITLE_CARD, REPLACEMENT_VIDEO, REPLACEMENT_AUDIO }

  /**
   * @param declaredMimeType What the caller says the asset is. Stated rather than sniffed so a
   *   mismatch between the claim and the bytes is a rejection instead of a substitution.
   */
  data class AssetRequirement(
    val uri: String,
    val role: AssetRole,
    val declaredMimeType: String? = null
  )

  data class AssetRejection(val uri: String, val role: AssetRole, val reason: String)

  /**
   * Everything about [requirements] the composition cannot honour, in the order they were given.
   *
   * The exporter has no honest fallback for a bad asset: media3 would either fail deep inside a
   * frame processor with a decode error, or - worse - quietly produce something the user did not
   * ask for (one frame of a GIF, a video with its audio dropped). Screening here turns both into a
   * sentence the studio can show before anything is encoded.
   */
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
      return "$declared is not a still image media3 can decode. Re-encode the card as PNG, JPEG, " +
        "WebP, BMP or HEIF."
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
      return "The card is ${options.outWidth}x${options.outHeight}, past the " +
        "${MAX_CARD_PIXELS / 1_000_000}MP the frame processor will upload."
    }
    return null
  }

  // HEIF and HEIC are the same container; BitmapFactory reports whichever the file claims.
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

  // A minimal format on purpose: findDecoderForFormat rejects an extractor format outright when it
  // carries a float KEY_FRAME_RATE, which every mp4 video track does.
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
