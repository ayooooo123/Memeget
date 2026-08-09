package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
  val hasCards: Boolean,
  /**
   * A pre-rendered transparent PNG (text, covers, cutouts and drawings resolved once over a
   * transparent canvas at the output size) to composite over every output frame, or `null` for a
   * clip with no annotations. Built by `buildVideoOverlayRenderPlan` in
   * `src/memeVideoCompositionCore.ts`; `null` keeps the trim/speed/card behaviour byte-identical.
   */
  val overlay: Overlay?,
  /**
   * An added audio track ("music") mixed into the output, or `null` for no change. When present its
   * track is mixed onto the single video output alongside the source audio, which stays governed by
   * [muted]/[volume]: a muted source plus music is a clean replace, an unmuted source plus music is
   * both mixed. Parsed from the optional plan `music` object by [parseMusic]; `null` keeps the
   * audio behaviour byte-identical to a plan that never carried the field.
   */
  val music: Music?
) {
  /**
   * Whether the export is expected to carry audio, given the source, the user's mute, and any
   * added [music]. Music alone forces the expectation: a muted, silent source plus a music track
   * must still come back with audio, so a music-only export that lost its track is caught.
   */
  fun expectsAudio(sourceHasAudio: Boolean): Boolean = (!muted && sourceHasAudio) || music != null

  /**
   * @param sourceHasAudio Read off the file by [sourceHasAudioTrack], not taken from the plan:
   *   `forceAudioTrack` exists to fill a card with silence, and forcing it for a genuinely silent
   *   source would invent an audio track the user never had.
   * @param overlayBitmap The decoded [overlay] PNG, or `null`. Decoded by the caller so the caller
   *   also owns recycling it once the export releases its texture ([decodeOverlayBitmap]).
   * @param music The added audio track to mix in, or `null` for none. Passed by the caller (as
   *   [MemeVideoExporter] does with [VideoExportPlan.music]) so a test can build a music-carrying
   *   composition without a full plan; `null` keeps the audio and sequence list byte-identical.
   */
  fun buildComposition(
    sourceHasAudio: Boolean,
    overlayBitmap: Bitmap? = null,
    music: Music? = null
  ): Composition =
    RetainedRangeComposition.buildTimeline(
      uri = sourceUri,
      sourceDurationUs = sourceDurationUs,
      segments = segments,
      speed = speed,
      sourceHasAudio = sourceHasAudio,
      outputSize = outputSize,
      audioProcessors = audioProcessors(),
      sourceVideoEffects = geometry,
      overlay = overlayBitmap,
      musicUri = music?.uri,
      musicVolume = music?.volume ?: 1f,
      musicStartUs = music?.startUs ?: 0L
    )

  /**
   * Decode the [overlay] PNG, or `null` when the plan carries none.
   *
   * Opened through the ContentResolver, the same way [RetainedRangeComposition] opens a title
   * card, so a `file://` app render and a `content://` grant both work and nothing else can be
   * read. A card that will not decode is an [IOException] rather than a silently overlay-free
   * export - the annotations are the whole point of the overlay.
   */
  fun decodeOverlayBitmap(context: Context): Bitmap? {
    val overlay = overlay ?: return null
    val uri = Uri.parse(overlay.uri)
    // Bounds-only pass FIRST: reject an oversized image by its header before
    // BitmapFactory allocates the full bitmap, so a hand-built plan pointing at
    // a highly compressed huge PNG fails as an IOException instead of an OOM.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) throw IOException("The overlay ${overlay.uri} could not be opened")
        BitmapFactory.decodeStream(input, null, bounds)
      }
    } catch (error: Exception) {
      throw IOException("The overlay could not be read: ${error.message.orEmpty().take(200)}")
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw IOException("The overlay ${overlay.uri} could not be decoded as an image")
    }
    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_OVERLAY_PIXELS) {
      throw IOException(
        "The overlay is ${bounds.outWidth}x${bounds.outHeight}, past the " +
          "${MAX_OVERLAY_PIXELS / 1_000_000}MP the frame processor will upload."
      )
    }
    return try {
      context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) throw IOException("The overlay ${overlay.uri} could not be opened")
        BitmapFactory.decodeStream(
          input,
          null,
          BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        )
      }
    } catch (error: Exception) {
      throw IOException("The overlay could not be decoded: ${error.message.orEmpty().take(200)}")
    } ?: throw IOException("The overlay ${overlay.uri} could not be decoded as an image")
  }

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

  /**
   * A transparent overlay PNG and the frame size it was rendered against. The dimensions come from
   * the plan; the decode's real dimensions drive the fill scale so a downsampled decode still
   * covers the frame.
   */
  data class Overlay(val uri: String, val widthPx: Int, val heightPx: Int)

  /**
   * An added audio track and the linear gain to play it at. The gain is applied on the music item
   * itself (not the composition audio processors, which govern the source), so mixing it in leaves
   * the source's mute/volume untouched.
   */
  data class Music(val uri: String, val volume: Float, val startUs: Long)

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
        hasCards = hasCards,
        overlay = parseOverlay(plan),
        music = parseMusic(plan)
      )
    }

    /** `video.speed`'s window, restated so a hand-written plan cannot outrun the exporter. */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f
    const val MAX_VOLUME = 2f

    /**
     * The overlay is uploaded to a GL texture whole, so it is screened against the same ceiling a
     * title card is (`GlUtil.MAX_BITMAP_DECODING_SIZE`) rather than left to OOM the frame
     * processor mid-export. In practice the overlay is rendered at the output frame size, which is
     * already far below this.
     */
    const val MAX_OVERLAY_PIXELS = RetainedRangeComposition.MAX_CARD_PIXELS

    /**
     * The optional `overlay` object: a pre-rendered transparent PNG the exporter burns over every
     * frame. Absent or `null` leaves the composition overlay-free, which is the pre-existing
     * trim/speed/card behaviour. The dimensions are stated by the plan (the size it was rendered
     * at) but not trusted for scaling - the decode's real dimensions drive the fill.
     */
    private fun parseOverlay(plan: JSONObject): Overlay? {
      val overlay = plan.optJSONObject("overlay") ?: return null
      val uri = overlay.optString("uri")
      if (uri.isBlank()) throw IOException("The composition plan overlay has no uri")
      val width = finiteLong(overlay, "widthPx").toInt()
      val height = finiteLong(overlay, "heightPx").toInt()
      if (width <= 0 || height <= 0) {
        throw IOException("The composition plan overlay is ${width}x$height")
      }
      return Overlay(uri = uri, widthPx = width, heightPx = height)
    }

    /**
     * The optional `music` object: an added audio track mixed into the output. Absent or `null`
     * leaves the audio to the source alone, which is the pre-existing behaviour. A blank uri is an
     * [IOException] rather than a silently music-free export - the added track is the whole point.
     * The gain is coerced into the same `0..MAX_VOLUME` window the source volume uses, so a
     * hand-written plan cannot ask for a level the exporter will not express.
     */
    private fun parseMusic(plan: JSONObject): Music? {
      val music = plan.optJSONObject("music") ?: return null
      val uri = music.optString("uri")
      if (uri.isBlank()) throw IOException("The composition plan music has no uri")
      val volume = finiteDouble(music, "volume", 1.0).toFloat()
      val startUs = music.optLong("startUs", 0L).coerceAtLeast(0L)
      return Music(uri = uri, volume = volume.coerceIn(0f, MAX_VOLUME), startUs = startUs)
    }

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
