package expo.modules.memegetbg

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.TimestampAdjustment
import androidx.media3.effect.TimestampAdjustmentShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

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
 */
@OptIn(UnstableApi::class)
object RetainedRangeComposition {
  /** A half-open source range, in microseconds, that survives the edit. */
  data class Range(val startUs: Long, val endUs: Long) {
    init {
      require(startUs >= 0L) { "startUs must be >= 0, was $startUs" }
      require(endUs > startUs) { "endUs ($endUs) must be greater than startUs ($startUs)" }
    }

    val durationUs: Long get() = endUs - startUs
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
  ): Composition {
    require(ranges.isNotEmpty()) { "At least one retained range is required" }
    ranges.forEach { range ->
      require(range.endUs <= sourceDurationUs) {
        "Range ${range.startUs}..${range.endUs} exceeds source duration $sourceDurationUs"
      }
    }

    val speedProcessor = if (speed == 1f) null else {
      SpeedChangingAudioProcessor(ConstantSpeedProvider(speed))
    }
    val items = ranges.map { range ->
      EditedMediaItem.Builder(clip(uri, range))
        .setDurationUs(sourceDurationUs)
        .setEffects(
          Effects(
            emptyList(),
            if (speedProcessor == null) {
              emptyList()
            } else {
              listOf(SequenceSpeedTimestampEffect(speedProcessor::getSpeedAdjustedTimeAsync))
            }
          )
        )
        .build()
    }

    // EditedMediaItemSequence.Builder(List) is deprecated in favour of Builder(Set<TrackType>),
    // but that overload forces the listed tracks: it would synthesise a silent audio track for
    // sources that have none. Keep the "infer from the items" behaviour.
    @Suppress("DEPRECATION")
    val sequence = EditedMediaItemSequence.Builder(items).build()
    val compositionAudioProcessors = if (speedProcessor == null) audioProcessors else {
      buildList(audioProcessors.size + 1) {
        add(speedProcessor)
        addAll(audioProcessors)
      }
    }
    return Composition.Builder(sequence)
      .setEffects(Effects(compositionAudioProcessors, videoEffects))
      .build()
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

  /**
   * The output duration media3 will produce for [ranges] at [speed], derived with the same
   * `SpeedProviderUtil` arithmetic (and the same flooring) the exporter uses.
   */
  fun expectedOutputDurationUs(ranges: List<Range>, speed: Float = 1f): Long {
    val retainedUs = ranges.sumOf { it.durationUs }
    if (speed == 1f) return retainedUs
    return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(
      ConstantSpeedProvider(speed),
      retainedUs
    )
  }
}
