package expo.modules.memegetbg

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MemegetBgModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("MemegetBg")

    // Put an actual file — in practice a video, which expo-clipboard can't
    // handle — on the system clipboard as a content:// uri. The file is staged
    // into a dedicated cache subdir (cleared on each copy, so it holds at most
    // one file) because the paste target reads the uri lazily, possibly long
    // after we return; the SAF source uri can't go on the clipboard directly
    // since other apps have no grant to read the user's linked folder.
    // Whether a paste target accepts a video uri is up to that app — many only
    // take text/images, which is why JS keeps the still-frame fallback.
    AsyncFunction("copyFileToClipboard") { uriStr: String, name: String, mimeType: String ->
      val ctx = appContext.reactContext ?: throw IllegalStateException("React context lost")

      val dir = File(ctx.cacheDir, "clipboard")
      dir.mkdirs()
      dir.listFiles()?.forEach { it.delete() }

      // Keep the (sanitized) real filename: FileProvider derives the served
      // MIME type from the extension, and paste targets show the name.
      val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "clip.bin" }
      val out = File(dir, safe)
      val src = if (uriStr.contains("://")) Uri.parse(uriStr) else Uri.fromFile(File(uriStr))
      val input = ctx.contentResolver.openInputStream(src)
        ?: throw IOException("Could not open $uriStr")
      input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }

      val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.memegetclip", out)
      val mime = ctx.contentResolver.getType(contentUri) ?: mimeType
      val clip = ClipData(name, arrayOf(mime), ClipData.Item(contentUri))
      val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(clip)
    }

    // Copy a finished export file (written to the app cache) into the public
    // Downloads folder so it lands there directly, no share-sheet round trip.
    // Uses MediaStore on API 29+ (scoped storage — no permission needed) and the
    // legacy public dir below that. The copy streams through a native buffer, so
    // even a large collection zip never passes through JS/RN memory. Returns the
    // human-readable destination (e.g. "Download/foo.zip").
    AsyncFunction("saveToDownloads") { srcPath: String, name: String, mimeType: String ->
      val ctx = appContext.reactContext ?: throw IllegalStateException("React context unavailable")
      val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "export.bin" }
      val mime = mimeType.ifBlank { "application/octet-stream" }
      val src = if (srcPath.contains("://")) Uri.parse(srcPath) else Uri.fromFile(File(srcPath))
      val resolver = ctx.contentResolver
      val input = resolver.openInputStream(src) ?: throw IOException("Could not open $srcPath")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, safe)
          put(MediaStore.Downloads.MIME_TYPE, mime)
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: throw IOException("MediaStore insert failed")
        input.use { i ->
          val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open output stream")
          out.use { o -> i.copyTo(o) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Download/$safe"
      } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val out = File(dir, safe)
        input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
        out.absolutePath
      }
    }

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

    // Decode the first audio track of a video to mono 16 kHz float32 PCM,
    // written as a raw little-endian file in the cache dir (the JS side reads
    // it and hands the waveform to the on-device STT model). Async because a two-
    // minute clip takes real decode time — expo runs this off the main thread
    // and resolves a Promise in JS. Resolves null when there is no audio track;
    // decode errors reject and the caller marks the video failed.
    AsyncFunction("extractAudio") { source: String, maxSeconds: Double ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      AudioExtractor.extract(ctx, source, maxSeconds)
    }

    // Decode ONE frame of a video via MediaCodec (the player's decode path)
    // and return a file:// jpeg — the poster fallback for streams that
    // MediaMetadataRetriever refuses (which is what both expo-image and
    // expo-video-thumbnails use). Resolves null when truly undecodable.
    AsyncFunction("extractVideoFrame") { source: String, seconds: Double ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      VideoFrameExtractor.extract(ctx, source, seconds)
    }

    // Same job as extractVideoFrame, but through ExoPlayer's (media3) decode
    // pipeline — whose own container parsers read some streams the platform
    // MediaExtractor/MediaMetadataRetriever reject. The last poster rung: if a
    // clip plays in the viewer but no platform decoder can poster it, this can.
    AsyncFunction("extractVideoFramePlayer") { source: String, seconds: Double ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      VideoPlayerFrameExtractor.extract(ctx, source, seconds)
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
