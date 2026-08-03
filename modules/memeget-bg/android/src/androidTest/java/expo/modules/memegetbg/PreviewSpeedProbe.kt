package expo.modules.memegetbg

import android.app.Instrumentation
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Task 4.2 preview honesty measurement.
 *
 * expo-video on Android is a thin wrapper over ExoPlayer: `playbackRate` is written straight to
 * `ExoPlayer.playbackParameters` as `PlaybackParameters(rate, pitch)` (pitch forced to 1 while
 * `preservesPitch` is true, which is its default), and `volume`/`muted` are written straight to
 * `ExoPlayer.volume`. See node_modules/expo-video/android/.../player/VideoPlayer.kt. This probe
 * therefore drives those exact ExoPlayer properties and measures what the preview actually does,
 * rather than assuming the requested rate is the rate the user sees.
 *
 * For each speed the editor offers we measure the media clock against wall clock over a fixed
 * window and report the ratio. A speed only counts as previewable when the observed rate stays
 * within tolerance and the player never rebuffers mid-window.
 */
@OptIn(UnstableApi::class)
object PreviewSpeedProbe {
  /** The speed set the editor exposes; keep in sync with src/memeVideoAudioCore.ts. */
  val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

  private const val ASSET_NAME = "synthetic_15s_720p.mp4"
  private const val WARMUP_MS = 700L
  private const val WINDOW_MS = 4_000L
  private const val SAMPLE_INTERVAL_MS = 50L
  private const val PREPARE_TIMEOUT_MS = 30_000L

  /**
   * Preview is "clean" when the media clock tracks the requested rate this closely. 2% of a 4 s
   * window is 80 ms of accumulated skew, which is already visible against a timeline readout.
   */
  const val RATE_TOLERANCE = 0.02

  /** Pure decision function, unit-tested on device without playing anything. */
  fun previewIsClean(observedRate: Double, requestedSpeed: Double, rebufferCount: Int): Boolean {
    if (requestedSpeed <= 0.0) return false
    if (rebufferCount > 0) return false
    if (!observedRate.isFinite()) return false
    return abs(observedRate / requestedSpeed - 1.0) <= RATE_TOLERANCE
  }

  private data class SpeedMeasurement(
    val speed: Float,
    val appliedSpeed: Float,
    val observedRate: Double,
    val positionAdvanceMs: Long,
    val wallClockMs: Long,
    val rebufferCount: Int,
    val droppedFrames: Int,
    val error: String?
  )

  fun run(instrumentation: Instrumentation): JSONObject {
    val context = instrumentation.targetContext
    val workDir = File(context.cacheDir, "preview-speed-probe").apply {
      deleteRecursively()
      check(mkdirs() || isDirectory)
    }
    val source = File(workDir, ASSET_NAME)
    instrumentation.context.assets.open(ASSET_NAME).use { input ->
      source.outputStream().use { output -> input.copyTo(output) }
    }

    val renderThread = HandlerThread("preview-speed-render").apply { start() }
    val sink = try {
      VideoSink.create(Handler(renderThread.looper))
    } catch (error: Throwable) {
      null
    }

    val measurements = mutableListOf<SpeedMeasurement>()
    val volumeObservations: JSONArray
    try {
      for (speed in SPEEDS) {
        measurements += measureSpeed(context, source, speed, sink)
      }
      volumeObservations = measureVolumeRange(context, source, sink)
    } finally {
      sink?.close()
      renderThread.quitSafely()
      workDir.deleteRecursively()
    }

    val speedJson = JSONArray()
    for (measurement in measurements) {
      val clean = measurement.error == null &&
        previewIsClean(measurement.observedRate, measurement.speed.toDouble(), measurement.rebufferCount)
      speedJson.put(
        JSONObject()
          .put("requestedSpeed", measurement.speed.toDouble())
          .put("appliedPlaybackParametersSpeed", measurement.appliedSpeed.toDouble())
          .put("observedRate", round4(measurement.observedRate))
          .put("relativeError", round4(measurement.observedRate / measurement.speed - 1.0))
          .put("positionAdvanceMs", measurement.positionAdvanceMs)
          .put("wallClockMs", measurement.wallClockMs)
          .put("rebufferCount", measurement.rebufferCount)
          .put("droppedFrames", measurement.droppedFrames)
          .put("previewClean", clean)
          .put("error", measurement.error ?: JSONObject.NULL)
      )
    }

    return JSONObject()
      .put("schemaVersion", 1)
      .put("task", "4.2")
      .put("observedAtUtc", utcNow())
      .put(
        "device",
        JSONObject()
          .put("manufacturer", Build.MANUFACTURER)
          .put("model", Build.MODEL)
          .put("device", Build.DEVICE)
          .put("androidRelease", Build.VERSION.RELEASE)
          .put("apiLevel", Build.VERSION.SDK_INT)
          .put("buildFingerprint", Build.FINGERPRINT)
      )
      .put("media3Version", MediaLibraryInfo.VERSION)
      .put(
        "method",
        JSONObject()
          .put("subject", "androidx.media3.exoplayer.ExoPlayer, the player expo-video wraps")
          .put("speedApi", "ExoPlayer.playbackParameters = PlaybackParameters(speed, 1f)")
          .put("volumeApi", "ExoPlayer.volume")
          .put("asset", ASSET_NAME)
          .put("warmupMs", WARMUP_MS)
          .put("windowMs", WINDOW_MS)
          .put("sampleIntervalMs", SAMPLE_INTERVAL_MS)
          .put("rateTolerance", RATE_TOLERANCE)
          .put("videoSurface", if (sink != null) "ImageReader PRIVATE sink, frames drained" else "none (audio clock only)")
      )
      .put("speeds", speedJson)
      .put("volume", volumeObservations)
  }

  private fun measureSpeed(
    context: Context,
    source: File,
    speed: Float,
    sink: VideoSink?
  ): SpeedMeasurement {
    val rebuffers = AtomicInteger(0)
    val dropped = AtomicInteger(0)
    val appliedSpeed = AtomicReference(speed)
    val failure = AtomicReference<String?>(null)
    var positionAdvanceMs = 0L
    var wallClockMs = 0L

    withPlayer(context, source, sink, failure) { player, handler ->
      var counting = false
      val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
          if (counting && state == Player.STATE_BUFFERING) rebuffers.incrementAndGet()
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
          appliedSpeed.set(parameters.speed)
        }
      }
      val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
          eventTime: AnalyticsListener.EventTime,
          droppedFrames: Int,
          elapsedMs: Long
        ) {
          dropped.addAndGet(droppedFrames)
        }
      }
      onMain(handler) {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.playbackParameters = PlaybackParameters(speed, 1f)
        player.seekTo(0L)
        player.play()
      }

      // Let the renderers settle before the window opens; the first hundred milliseconds after
      // play() are dominated by codec warm-up, not by the requested rate.
      SystemClock.sleep(WARMUP_MS)

      val startPosition = readLong(handler) { player.currentPosition }
      val startWall = SystemClock.elapsedRealtime()
      counting = true
      var elapsed = 0L
      while (elapsed < WINDOW_MS) {
        SystemClock.sleep(SAMPLE_INTERVAL_MS)
        elapsed = SystemClock.elapsedRealtime() - startWall
      }
      val endPosition = readLong(handler) { player.currentPosition }
      val endWall = SystemClock.elapsedRealtime()
      counting = false
      onMain(handler) {
        player.pause()
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
      }
      positionAdvanceMs = endPosition - startPosition
      wallClockMs = endWall - startWall
    }

    val observedRate = if (wallClockMs > 0L) positionAdvanceMs.toDouble() / wallClockMs.toDouble() else Double.NaN
    return SpeedMeasurement(
      speed = speed,
      appliedSpeed = appliedSpeed.get(),
      observedRate = observedRate,
      positionAdvanceMs = positionAdvanceMs,
      wallClockMs = wallClockMs,
      rebufferCount = rebuffers.get(),
      droppedFrames = dropped.get(),
      error = failure.get()
    )
  }

  /**
   * The editor models volume as 0..200%. ExoPlayer's volume is documented as 0..1 unity gain, so
   * the question that decides the UI copy is what the player reports back after a >1 write.
   */
  private fun measureVolumeRange(context: Context, source: File, sink: VideoSink?): JSONArray {
    val results = JSONArray()
    val failure = AtomicReference<String?>(null)
    withPlayer(context, source, sink, failure) { player, handler ->
      for (requested in floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f)) {
        val applied = readFloat(handler) {
          player.volume = requested
          player.volume
        }
        results.put(
          JSONObject()
            .put("requested", requested.toDouble())
            .put("appliedByPlayer", round4(applied.toDouble()))
            .put("honored", abs(applied - requested) < 1e-4)
        )
      }
      val mutedVolume = readFloat(handler) {
        player.volume = 1f
        player.volume = 0f
        player.volume
      }
      results.put(
        JSONObject()
          .put("requested", 0.0)
          .put("appliedByPlayer", round4(mutedVolume.toDouble()))
          .put("honored", mutedVolume == 0f)
          .put("note", "mute path: expo-video writes 0f and restores userVolume on unmute")
      )
    }
    if (failure.get() != null) {
      results.put(JSONObject().put("error", failure.get()))
    }
    return results
  }

  private fun withPlayer(
    context: Context,
    source: File,
    sink: VideoSink?,
    failure: AtomicReference<String?>,
    body: (ExoPlayer, Handler) -> Unit
  ) {
    val handler = Handler(Looper.getMainLooper())
    val playerRef = AtomicReference<ExoPlayer?>(null)
    val ready = CountDownLatch(1)
    val playerError = AtomicReference<String?>(null)
    onMain(handler) {
      val player = ExoPlayer.Builder(context).build()
      playerRef.set(player)
      player.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
          if (state == Player.STATE_READY) ready.countDown()
        }

        override fun onPlayerError(error: PlaybackException) {
          playerError.set("${error.errorCodeName}: ${error.message}")
          ready.countDown()
        }
      })
      sink?.surface?.let { player.setVideoSurface(it) }
      player.setMediaItem(MediaItem.fromUri(source.toURI().toString()))
      player.prepare()
    }
    val player = checkNotNull(playerRef.get()) { "Player was not constructed" }
    try {
      if (!ready.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        failure.set("Timed out waiting for STATE_READY")
        return
      }
      playerError.get()?.let {
        failure.set(it)
        return
      }
      body(player, handler)
      playerError.get()?.let { failure.set(it) }
    } finally {
      onMain(handler) {
        player.clearVideoSurface()
        player.release()
      }
    }
  }

  /**
   * Headless video sink. Without a surface ExoPlayer disables the video renderer entirely and the
   * media clock is driven by audio alone, which would flatter the measurement. An ImageReader in
   * PRIVATE format accepts decoder output and we drain every frame so the codec never stalls on a
   * full buffer queue.
   */
  private class VideoSink private constructor(private val reader: ImageReader) {
    val surface: android.view.Surface get() = reader.surface

    fun close() {
      reader.close()
    }

    companion object {
      fun create(handler: Handler): VideoSink {
        val reader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ImageReader.newInstance(
            1280,
            720,
            ImageFormat.PRIVATE,
            4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
          )
        } else {
          ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4)
        }
        reader.setOnImageAvailableListener({ source ->
          while (true) {
            val image = try {
              source.acquireNextImage()
            } catch (error: IllegalStateException) {
              null
            } ?: break
            image.close()
          }
        }, handler)
        return VideoSink(reader)
      }
    }
  }

  private fun onMain(handler: Handler, block: () -> Unit) {
    if (Looper.myLooper() == handler.looper) {
      block()
      return
    }
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    handler.post {
      try {
        block()
      } catch (error: Throwable) {
        thrown.set(error)
      } finally {
        done.countDown()
      }
    }
    check(done.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Main-thread block timed out" }
    thrown.get()?.let { throw it }
  }

  private fun readLong(handler: Handler, block: () -> Long): Long {
    val value = AtomicLong(0L)
    onMain(handler) { value.set(block()) }
    return value.get()
  }

  private fun readFloat(handler: Handler, block: () -> Float): Float {
    val value = AtomicReference(0f)
    onMain(handler) { value.set(block()) }
    return value.get()
  }

  private fun round4(value: Double): Double =
    if (value.isFinite()) Math.round(value * 10_000.0) / 10_000.0 else -1.0

  private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date())
  }
}
