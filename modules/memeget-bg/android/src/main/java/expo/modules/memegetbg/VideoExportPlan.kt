package expo.modules.memegetbg

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import java.io.IOException
import org.json.JSONObject

/**
 * The native half of `src/memeVideoCompositionCore.ts`: one serialized composition plan, parsed
 * into exactly the arguments [RetainedRangeComposition] takes.
 *
 * The plan is the contract. Nothing here re-derives timing, sizing or ordering - JS already
 * resolved all of it and showed the user the result - so a field this parser cannot honour is an
 * [IOException] with a sentence in it, never a substitution. An exporter that quietly drops a
 * crop or a card produces a file the user did not ask for after a minute of encoding, which is the
 * worst possible time to find out.
 */
@OptIn(UnstableApi::class)
internal data class VideoExportPlan(
  val id: String,
  val sourceUri: String,
  val sourceDurationUs: Long,
  val segments: List<RetainedRangeComposition.Segment>,
  val speed: Float,
  val outputSize: Size,
  val expectedOutputDurationUs: Long,
  val muted: Boolean,
  val volume: Float,
  /**
   * Rotation, flips and crop, in the order the still renderer applies them
   * (`MemeImageRenderer.drawTransformedSource`). Empty for an untransformed source, which is the
   * common case and keeps the shader chain to the presentation alone.
   */
  val geometry: List<Effect>,
  val hasCards: Boolean
) {
  /** Whether the export is expected to carry audio, given the source and the user's mute. */
  fun expectsAudio(sourceHasAudio: Boolean): Boolean = !muted && sourceHasAudio

  /**
   * @param sourceHasAudio Read off the file by [sourceHasAudioTrack], not taken from the plan:
   *   `forceAudioTrack` exists to fill a card with silence, and forcing it for a genuinely silent
   *   source would invent an audio track the user never had.
   */
  fun buildComposition(sourceHasAudio: Boolean): Composition = RetainedRangeComposition.buildTimeline(
    uri = sourceUri,
    sourceDurationUs = sourceDurationUs,
    segments = segments,
    speed = speed,
    sourceHasAudio = sourceHasAudio,
    outputSize = outputSize,
    audioProcessors = audioProcessors(),
    sourceVideoEffects = geometry
  )

  /**
   * Mute is a gain of zero, not a removed track.
   *
   * Dropping the track would change the shape of the file - some players show a muted video with
   * no audio track as a broken clip, and pasting one into a chat app that expects audio silently
   * degrades it. A silent track is what the user saw in the preview.
   */
  private fun audioProcessors(): List<AudioProcessor> {
    val gain = if (muted) 0f else volume
    if (gain == 1f) return emptyList()
    return listOf(GainProcessor(DefaultGainProvider.Builder(gain).build()))
  }

  companion object {
    const val SUPPORTED_VERSION = 1

    fun parse(planJson: String): VideoExportPlan {
      val plan = try {
        JSONObject(planJson)
      } catch (error: Exception) {
        throw IOException("The composition plan is not valid JSON: ${error.message.orEmpty().take(200)}")
      }
      val version = plan.optInt("version", -1)
      if (version != SUPPORTED_VERSION) {
        throw IOException(
          "This build reads composition plan version $SUPPORTED_VERSION, and the plan is version " +
            "$version."
        )
      }
      val rejections = plan.optJSONArray("rejections")
      if (rejections != null && rejections.length() > 0) {
        val first = rejections.optJSONObject(0)?.optString("message").orEmpty()
        throw IOException("The plan was refused before it reached the exporter: $first")
      }

      val source = plan.optJSONObject("source")
        ?: throw IOException("The composition plan has no source")
      val sourceUri = source.optString("uri")
      if (sourceUri.isBlank()) throw IOException("The composition plan source has no uri")
      val sourceDurationUs = finiteLong(source, "durationUs")
      if (sourceDurationUs <= 0L) {
        throw IOException("The composition plan source has no usable duration")
      }

      val output = plan.optJSONObject("output")
        ?: throw IOException("The composition plan has no output")
      val width = finiteLong(output, "widthPx").toInt()
      val height = finiteLong(output, "heightPx").toInt()
      if (width <= 0 || height <= 0) {
        throw IOException("The composition plan output is ${width}x$height")
      }
      val speed = finiteDouble(output, "speed", 1.0).toFloat()
      if (speed !in MIN_SPEED..MAX_SPEED) {
        throw IOException("A speed of $speed is outside the $MIN_SPEED..$MAX_SPEED the exporter can express")
      }

      val entries = plan.optJSONArray("segments")
      if (entries == null || entries.length() == 0) {
        throw IOException("The composition plan has no segments to export")
      }
      var hasCards = false
      val segments = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index)
          ?: throw IOException("Segment $index is not an object")
        when (val kind = entry.optString("kind")) {
          "source" -> RetainedRangeComposition.Range(
            startUs = finiteLong(entry, "sourceStartUs"),
            endUs = finiteLong(entry, "sourceEndUs")
          )
          "card" -> {
            hasCards = true
            RetainedRangeComposition.Card(
              uri = entry.optString("uri"),
              mimeType = entry.optString("mimeType"),
              durationUs = finiteLong(entry, "timelineDurationUs"),
              frameRate = finiteDouble(entry, "frameRate", RetainedRangeComposition.DEFAULT_CARD_FRAME_RATE.toDouble())
                .toInt()
            )
          }
          else -> throw IOException("Segment $index has an unknown kind \"$kind\"")
        }
      }

      val audio = plan.optJSONObject("audio")
      val volume = audio?.let { finiteDouble(it, "volume", 1.0).toFloat() } ?: 1f
      if (volume < 0f || volume > MAX_VOLUME) {
        throw IOException("A volume of $volume is outside the 0..$MAX_VOLUME the exporter can express")
      }

      return VideoExportPlan(
        id = plan.optString("id").ifBlank { "meme" },
        sourceUri = sourceUri,
        sourceDurationUs = sourceDurationUs,
        segments = segments,
        speed = speed,
        outputSize = Size(width, height),
        // The plan's own number, not a re-derivation: it is what the studio showed the user, so it
        // is what a truncated export has to be measured against.
        expectedOutputDurationUs = finiteLong(output, "durationUs"),
        muted = audio?.optBoolean("muted") ?: false,
        volume = volume,
        geometry = geometryEffects(source),
        hasCards = hasCards
      )
    }

    /** `video.speed`'s window, restated so a hand-written plan cannot outrun the exporter. */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f
    const val MAX_VOLUME = 2f

    /**
     * Rotation, then flips on the oriented box, then the crop window - the same order and the same
     * normalized coordinates `MemeImageRenderer` uses for stills, so a project exported as a PNG
     * and as an MP4 frames its subject identically.
     *
     * Rotation and flip are separate effects on purpose: a single [ScaleAndRotateTransformation]
     * scales before it rotates, which for a quarter turn is a flip about the *other* axis. Two
     * effects state the order instead of encoding it in a swap nobody would notice was wrong.
     */
    private fun geometryEffects(source: JSONObject): List<Effect> {
      val effects = mutableListOf<Effect>()
      val rotation = ((source.optInt("rotation") % 360) + 360) % 360
      if (rotation != 0) {
        if (rotation % 90 != 0) throw IOException("Only quarter turns are supported, got $rotation")
        // media3 rotates counter-clockwise in a y-up frame; the project's rotation is the
        // clockwise one the canvas applies.
        effects.add(
          ScaleAndRotateTransformation.Builder()
            .setRotationDegrees((360 - rotation).toFloat())
            .build()
        )
      }
      val flipX = source.optBoolean("flipX")
      val flipY = source.optBoolean("flipY")
      if (flipX || flipY) {
        effects.add(
          ScaleAndRotateTransformation.Builder()
            .setScale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)
            .build()
        )
      }
      val crop = source.optJSONObject("crop")
      if (crop != null) {
        val x = finiteDouble(crop, "x", 0.0).toFloat()
        val y = finiteDouble(crop, "y", 0.0).toFloat()
        val cropWidth = finiteDouble(crop, "width", 1.0).toFloat()
        val cropHeight = finiteDouble(crop, "height", 1.0).toFloat()
        if (cropWidth <= 0f || cropHeight <= 0f) {
          throw IOException("The crop window is ${cropWidth}x$cropHeight of the frame")
        }
        val fullFrame = x == 0f && y == 0f && cropWidth == 1f && cropHeight == 1f
        if (!fullFrame) {
          // Normalized top-left rect -> media3's NDC, which runs -1..1 with y pointing up.
          effects.add(
            Crop(
              /* left = */ 2f * x - 1f,
              /* right = */ 2f * (x + cropWidth) - 1f,
              /* bottom = */ 1f - 2f * (y + cropHeight),
              /* top = */ 1f - 2f * y
            )
          )
        }
      }
      return effects
    }

    private fun finiteLong(json: JSONObject, key: String): Long {
      val value = json.optDouble(key, Double.NaN)
      if (!value.isFinite()) throw IOException("The composition plan field \"$key\" is not a number")
      return value.toLong()
    }

    private fun finiteDouble(json: JSONObject, key: String, fallback: Double): Double {
      if (!json.has(key)) return fallback
      val value = json.optDouble(key, Double.NaN)
      if (!value.isFinite()) throw IOException("The composition plan field \"$key\" is not a number")
      return value
    }

    /**
     * Whether [uri] actually carries an audio track.
     *
     * Read from the file rather than trusted from the plan: it decides both whether a card has to
     * be filled with silence and whether an export that came back without audio lost it.
     */
    fun sourceHasAudioTrack(context: Context, uri: String): Boolean {
      val extractor = MediaExtractor()
      return try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        (0 until extractor.trackCount).any { index ->
          extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
      } catch (error: IOException) {
        false
      } catch (error: IllegalArgumentException) {
        false
      } finally {
        extractor.release()
      }
    }
  }
}
