package expo.modules.memegetbg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class MemegetBgModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("MemegetBg")

    // Last-modified time (ms since epoch) of a SAF content:// document, read
    // straight off its DocumentFile. This exists because expo-file-system's own
    // APIs don't reliably surface modificationTime for SAF documents (the legacy
    // getInfoAsync never sets it for content:// at all), which is what the meme
    // library needs to order "most recently added first". Returns null when the
    // uri is unreadable or the provider doesn't report a time (lastModified() is
    // 0), so JS can fall back to the index time.
    Function("getModifiedTime") { uriStr: String ->
      val ctx = appContext.reactContext ?: return@Function null
      try {
        val doc = DocumentFile.fromSingleUri(ctx, Uri.parse(uriStr)) ?: return@Function null
        val lm = doc.lastModified()
        if (lm > 0L) lm.toDouble() else null
      } catch (e: Exception) {
        null
      }
    }

    // Battery + thermal snapshot the JS loop polls to decide whether to keep
    // describing. Cheap, synchronous reads.
    Function("getPower") {
      val ctx = appContext.reactContext ?: return@Function null
      val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

      val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) // 0..100, -1 unknown
      val level = if (capacity in 0..100) capacity / 100.0 else -1.0
      val charging = bm.isCharging

      val thermal =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else -1
      val headroom =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          try {
            pm.getThermalHeadroom(0).toDouble()
          } catch (e: Exception) {
            -1.0
          }
        } else {
          -1.0
        }

      mapOf(
        "charging" to charging,
        "level" to level,
        "thermal" to thermal,
        "headroom" to headroom
      )
    }

    Function("startForeground") { title: String, text: String ->
      val ctx = appContext.reactContext ?: return@Function null
      val intent = Intent(ctx, KeepAliveService::class.java).apply {
        putExtra(KeepAliveService.EXTRA_TITLE, title)
        putExtra(KeepAliveService.EXTRA_TEXT, text)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(intent)
      } else {
        ctx.startService(intent)
      }
    }

    Function("stopForeground") {
      val ctx = appContext.reactContext ?: return@Function null
      ctx.stopService(Intent(ctx, KeepAliveService::class.java))
    }
  }
}
