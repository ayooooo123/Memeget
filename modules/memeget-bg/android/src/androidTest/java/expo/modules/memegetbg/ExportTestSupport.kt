package expo.modules.memegetbg

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry

/** Fixtures and plan JSON shared by the export and keep-alive device tests. */
internal object ExportTestSupport {
  /** 3 s of 320x240 with a real AAC track: a full export, cheap enough to run several times. */
  const val SHORT_ASSET = "composition_landscape_3s_240p.mp4"
  const val SHORT_DURATION_US = 3_000_000L
  const val SHORT_WIDTH = 320
  const val SHORT_HEIGHT = 240

  fun copyAsset(workDir: File, name: String): File {
    val target = File(workDir, name)
    if (target.isFile && target.length() > 0L) return target
    InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
      target.outputStream().use { output -> input.copyTo(output) }
    }
    return target
  }

  /** The subset of `memeVideoCompositionCore.ts`'s plan the exporter reads. */
  fun planJson(
    source: File,
    durationUs: Long,
    width: Int,
    height: Int,
    speed: Double = 1.0
  ): String {
    val segment = JSONObject()
      .put("kind", "source")
      .put("index", 0)
      .put("sourceStartUs", 0L)
      .put("sourceEndUs", durationUs)
      .put("timelineDurationUs", durationUs)
      .put("outputStartUs", 0L)
      .put("outputEndUs", durationUs)
    return JSONObject()
      .put("version", VideoExportPlan.SUPPORTED_VERSION)
      .put("id", "instrumented")
      .put(
        "source",
        JSONObject()
          .put("uri", Uri.fromFile(source).toString())
          .put("widthPx", width)
          .put("heightPx", height)
          .put("durationUs", durationUs)
          .put("rotation", 0)
          .put("flipX", false)
          .put("flipY", false)
          .put("crop", JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 1))
      )
      .put(
        "output",
        JSONObject()
          .put("widthPx", width)
          .put("heightPx", height)
          .put("speed", speed)
          .put("durationUs", (durationUs / speed).toLong())
          .put("retainedDurationUs", durationUs)
          .put("cardDurationUs", 0L)
      )
      .put("audio", JSONObject().put("muted", false).put("volume", 1.0))
      .put("segments", JSONArray().put(segment))
      .put("rejections", JSONArray())
      .toString()
  }

  /**
   * Whether [KeepAliveService] is running right now, read from the system rather than from our own
   * bookkeeping - the bookkeeping is what is under test.
   *
   * `getRunningServices` is deprecated for inspecting OTHER apps and returns only our own since
   * API 26, which is exactly the question being asked.
   */
  @Suppress("DEPRECATION")
  fun keepAliveServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return manager.getRunningServices(Int.MAX_VALUE).any {
      it.service.className == KeepAliveService::class.java.name
    }
  }

  /** Wait for the service to reach [running], because starting and stopping are both async. */
  fun awaitKeepAliveService(context: Context, running: Boolean, timeoutMs: Long = 10_000L): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      if (keepAliveServiceRunning(context) == running) return true
      SystemClock.sleep(100L)
    }
    return keepAliveServiceRunning(context) == running
  }
}
