package expo.modules.memegetbg

import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Native evaluator for a timed overlay's transform track.
 *
 * The editor preview evaluates a layer with `evaluateLayerMotionAt` in
 * src/memeVideoMotionCore.ts, which is itself only `isLayerActiveAt` plus
 * `interpolateTransformKeyframes` from src/memeEditProjectCore.ts. This file is
 * the SAME contract for a native consumer that has to answer the question once
 * per presentation timestamp instead of once per React render:
 *
 *  - outside the active range the layer is ABSENT, not transparent;
 *  - before the first and after the last keyframe the value is CLAMPED to that
 *    keyframe, never extrapolated;
 *  - a `hold` keyframe freezes its own value until the next keyframe's exact
 *    timestamp;
 *  - otherwise every field is linearly interpolated on integer-microsecond
 *    progress and then rounded to 1e-12, in that order.
 *
 * Doubles throughout, deliberately: the TypeScript side is IEEE-754 double, and
 * a Float here would make the two sides disagree in the sixth decimal for no
 * reason at all. [roundGeometry] reproduces JavaScript's `Math.round`, because
 * that rounding is part of the contract rather than cosmetic.
 *
 * A video project's layers always carry a concrete active range and at least one
 * keyframe — `normalizeLayer` fills both in — so this evaluator requires them
 * and rejects a plan that omits them instead of inventing a default.
 *
 * Nothing here renders or exports anything. This is the evaluator a video
 * composition will call; that composition does not exist yet.
 */
internal object MemeDynamicOverlay {
  const val FIXTURE_VERSION = 1

  // Mirrors PROJECT_LIMITS.maxKeyframesPerLayer in src/memeEditProjectCore.ts.
  // A longer track is malformed, and rejecting it keeps a tampered plan from
  // allocating without bound.
  private const val MAX_KEYFRAMES = 256

  // Mirrors MAX_MEDIA_DURATION_US: no track describes more than a day.
  private const val MAX_TIME_US = 24L * 60L * 60L * 1_000_000L

  private const val GEOMETRY_SCALE = 1_000_000_000_000.0

  data class Transform(
    val centerX: Double,
    val centerY: Double,
    val scale: Double,
    val rotationDegrees: Double,
    val opacity: Double
  )

  data class Keyframe(
    val timeUs: Long,
    val centerX: Double,
    val centerY: Double,
    val scale: Double,
    val rotationDegrees: Double,
    val opacity: Double,
    /** `easing == "hold"` on the TypeScript side. */
    val hold: Boolean
  )

  data class Track(
    val activeStartUs: Long,
    val activeEndUs: Long,
    val keyframes: List<Keyframe>
  )

  /**
   * How far two evaluations of the same instant sit apart, in normalized units
   * and degrees. Absent-versus-present is a separate answer from the distance:
   * an evaluator that drew a layer outside its active range would otherwise pass
   * on every sample where the numbers happened to agree.
   */
  data class Drift(val visibilityMatches: Boolean, val maxUnits: Double) {
    fun withinTolerance(toleranceUnits: Double): Boolean =
      visibilityMatches && maxUnits <= toleranceUnits
  }

  fun evaluateAt(track: Track, timeUs: Long): Transform? {
    if (timeUs < track.activeStartUs || timeUs > track.activeEndUs) return null
    val frames = track.keyframes
    if (frames.isEmpty()) return null

    val first = frames[0]
    if (timeUs <= first.timeUs) return first.transform()
    val last = frames[frames.size - 1]
    if (timeUs >= last.timeUs) return last.transform()

    var low = 0
    var high = frames.size - 1
    while (low + 1 < high) {
      val middle = (low + high) / 2
      if (frames[middle].timeUs <= timeUs) low = middle else high = middle
    }
    val left = frames[low]
    val right = frames[high]
    if (left.hold) return left.transform()

    val progress = (timeUs - left.timeUs).toDouble() / (right.timeUs - left.timeUs).toDouble()
    return Transform(
      centerX = roundGeometry(left.centerX + (right.centerX - left.centerX) * progress),
      centerY = roundGeometry(left.centerY + (right.centerY - left.centerY) * progress),
      scale = roundGeometry(left.scale + (right.scale - left.scale) * progress),
      rotationDegrees = roundGeometry(
        left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * progress
      ),
      opacity = roundGeometry(left.opacity + (right.opacity - left.opacity) * progress)
    )
  }

  fun compare(expected: Transform?, actual: Transform?): Drift {
    if (expected == null || actual == null) {
      return Drift(visibilityMatches = expected == null && actual == null, maxUnits = 0.0)
    }
    return Drift(
      visibilityMatches = true,
      maxUnits = maxOf(
        abs(expected.centerX - actual.centerX),
        abs(expected.centerY - actual.centerY),
        abs(expected.scale - actual.scale),
        abs(expected.rotationDegrees - actual.rotationDegrees),
        abs(expected.opacity - actual.opacity)
      )
    )
  }

  /**
   * Reads a track out of the shape src/memeVideoMotionCore.ts serializes. Every
   * number is checked before it is kept: the plan is data, and a malformed one
   * has to fail as an IOException rather than as a silently motionless overlay.
   */
  fun parseTrack(json: JSONObject): Track = try {
    val startUs = boundedTimeUs(json.getLong("activeStartUs"))
    val endUs = boundedTimeUs(json.getLong("activeEndUs"))
    if (startUs >= endUs) {
      throw IOException("Overlay active range ${startUs}us..${endUs}us is empty")
    }
    val array = json.optJSONArray("keyframes") ?: JSONArray()
    if (array.length() == 0) throw IOException("Overlay track carries no keyframes")
    if (array.length() > MAX_KEYFRAMES) {
      throw IOException("Overlay track carries ${array.length()} keyframes, past the $MAX_KEYFRAMES cap")
    }
    val keyframes = ArrayList<Keyframe>(array.length())
    for (index in 0 until array.length()) {
      val frame = array.getJSONObject(index)
      val timeUs = boundedTimeUs(frame.getLong("timeUs"))
      // Sorted and unique is a contract the project reducer already enforces, so
      // a violation means the plan did not come from it.
      if (index > 0 && timeUs <= keyframes[index - 1].timeUs) {
        throw IOException("Overlay keyframes are not sorted and unique at index $index")
      }
      keyframes.add(
        Keyframe(
          timeUs = timeUs,
          centerX = finite(frame, "centerX"),
          centerY = finite(frame, "centerY"),
          scale = finite(frame, "scale"),
          rotationDegrees = finite(frame, "rotationDegrees"),
          opacity = finite(frame, "opacity"),
          hold = frame.optString("easing") == "hold"
        )
      )
    }
    Track(activeStartUs = startUs, activeEndUs = endUs, keyframes = keyframes)
  } catch (error: JSONException) {
    throw IOException("Overlay track is malformed: ${error.message}", error)
  }

  private fun Keyframe.transform(): Transform =
    Transform(centerX, centerY, scale, rotationDegrees, opacity)

  private fun finite(json: JSONObject, key: String): Double {
    val value = json.getDouble(key)
    if (!value.isFinite()) throw IOException("Overlay keyframe field $key is not finite")
    return value
  }

  private fun boundedTimeUs(value: Long): Long {
    if (value < -MAX_TIME_US || value > MAX_TIME_US) {
      throw IOException("Overlay timestamp ${value}us is outside the supported range")
    }
    return value
  }

  /**
   * JavaScript `Math.round(value * 1e12) / 1e12`, including its -0 flattening.
   * Deciding on `scaled - floored >= 0.5` rather than rounding `scaled + 0.5`
   * avoids the double-rounding that would send a value just under a half the
   * wrong way.
   */
  private fun roundGeometry(value: Double): Double {
    val scaled = value * GEOMETRY_SCALE
    if (!scaled.isFinite()) return value
    val floored = floor(scaled)
    val rounded = (if (scaled - floored >= 0.5) floored + 1.0 else floored) / GEOMETRY_SCALE
    return if (rounded == 0.0) 0.0 else rounded
  }
}
