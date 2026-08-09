package expo.modules.memegetbg

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MemegetBgModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("MemegetBg")

    // Download/segmentation progress for still-image cutouts. Emitted from a
    // background thread while `segmentImageSubjects` is in flight, because the
    // one-time model download is a user-visible wait that has to be cancellable
    // rather than a spinner with no end in sight.
    //
    // `onVideoExportProgress` is the same idea for a video export, tagged with the caller's
    // `exportId` so a stale subscription cannot drive the current run's progress bar.
    Events("onSubjectSegmentationProgress", "onVideoExportProgress")

    View(MemeTextPreviewView::class) {
      Events("onMetrics")
      Prop("text") { view: MemeTextPreviewView, value: String -> view.setText(value) }
      Prop("fontFamily") { view: MemeTextPreviewView, value: String -> view.setFontFamily(value) }
      Prop("fontWeight") { view: MemeTextPreviewView, value: Int -> view.setFontWeight(value) }
      Prop("fontSizeDip") { view: MemeTextPreviewView, value: Double -> view.setFontSizeDip(value.toFloat()) }
      Prop("lineHeightDip") { view: MemeTextPreviewView, value: Double -> view.setLineHeightDip(value.toFloat()) }
      Prop("letterSpacingEm") { view: MemeTextPreviewView, value: Double -> view.setLetterSpacingEm(value.toFloat()) }
      Prop("widthDip") { view: MemeTextPreviewView, value: Double -> view.setWidthDip(value.toFloat()) }
      Prop("align") { view: MemeTextPreviewView, value: String -> view.setAlign(value) }
      Prop("fillColor") { view: MemeTextPreviewView, value: String -> view.setFillColor(value) }
      Prop("strokeColor") { view: MemeTextPreviewView, value: String -> view.setStrokeColor(value) }
      Prop("strokeWidthDip") { view: MemeTextPreviewView, value: Double -> view.setStrokeWidthDip(value.toFloat()) }
      Prop("opacity") { view: MemeTextPreviewView, value: Double -> view.setOpacity(value.toFloat()) }
      OnViewDidUpdateProps { view: MemeTextPreviewView -> view.commitPendingProps() }
    }

    // Live preview of a draw layer, rendered through the same MemeDrawPrimitives
    // as the export so a stroke lands identically in the editor and the PNG/clip.
    // Elements arrive in NORMALIZED coordinates with a strokeScale; the view
    // resolves them against its own bounds at draw time.
    View(MemeDrawPreviewView::class) {
      Prop("elementsJson") { view: MemeDrawPreviewView, value: String -> view.setElementsJson(value) }
      Prop("opacity") { view: MemeDrawPreviewView, value: Double -> view.setOpacity(value.toFloat()) }
      OnViewDidUpdateProps { view: MemeDrawPreviewView -> view.commitPendingProps() }
    }

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

    // Render a finished variation into app cache. Images use Bitmap/Canvas;
    // videos use Media3 Transformer and settle the JS promise only after its
    // callback reports a complete MP4 file.
    AsyncFunction("renderMemeVariation") {
      source: String,
      kind: String,
      topText: String,
      bottomText: String,
      coverTop: Boolean,
      coverBottom: Boolean,
      promise: Promise ->
      val ctx = appContext.reactContext
        ?: return@AsyncFunction promise.reject("E_CONTEXT", "React context unavailable", null)
      if (kind == "video") {
        Handler(Looper.getMainLooper()).post {
          MemeMediaEditor.exportVideo(
            ctx,
            source,
            topText,
            bottomText,
            onSuccess = promise::resolve,
            onError = { error -> promise.reject("E_VIDEO_EXPORT", error.message, error) }
          )
        }
      } else {
        try {
          promise.resolve(
            MemeMediaEditor.renderImage(ctx, source, topText, bottomText, coverTop, coverBottom)
          )
        } catch (error: Throwable) {
          promise.reject("E_IMAGE_EXPORT", error.message, error)
        }
      }
    }

    // Render a full-resolution still from a structured edit project. The plan
    // JSON is produced by src/memeImageRenderCore.ts, which resolves every
    // layer into output pixels first — this call only decodes, draws and
    // encodes. AsyncFunction keeps the decode/encode off the JS thread; real
    // failures reject so the studio can show why the export did not happen.
    AsyncFunction("renderImageProject") { planJson: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeImageRenderer.render(ctx, planJson)
    }

    // Screen the assets a video composition plan (src/memeVideoCompositionCore.ts) wants to pull
    // in — title cards today, replacement clips and music beds as they land. Returns one entry per
    // asset the composition cannot honour, each with a sentence the studio can show. An empty list
    // means every asset checked out. Async because it opens and header-decodes real files.
    AsyncFunction("inspectCompositionAssets") { requirementsJson: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      RetainedRangeComposition
        .inspectAssets(ctx, compositionAssetRequirements(requirementsJson))
        .map { rejection ->
          mapOf(
            "uri" to rejection.uri,
            "role" to rejection.role.name,
            "reason" to rejection.reason
          )
        }
    }

    // WebM is playable in Memeget but not accepted by several mobile paste
    // targets. Produce genuine H.264/AAC MP4 bytes before clipboard staging.
    AsyncFunction("transcodeVideoToMp4") { source: String, promise: Promise ->
      val ctx = appContext.reactContext
        ?: return@AsyncFunction promise.reject("E_CONTEXT", "React context unavailable", null)
      Handler(Looper.getMainLooper()).post {
        MemeMediaEditor.exportVideo(
          ctx,
          source,
          topText = "",
          bottomText = "",
          onSuccess = promise::resolve,
          onError = { error -> promise.reject("E_VIDEO_TRANSCODE", error.message, error) }
        )
      }
    }

    // The real video export: a media3 Transformer run over the composition plan built by
    // src/memeVideoCompositionCore.ts. Progress arrives as `onVideoExportProgress`; this promise
    // settles exactly once - resolved only for a verified, complete file, rejected for a failure
    // or a cancel - because a second resolve turns one render into two memes.
    AsyncFunction("exportVideoProject") { planJson: String, exportId: String, promise: Promise ->
      val ctx = appContext.reactContext
        ?: return@AsyncFunction promise.reject("E_CONTEXT", "React context unavailable", null)
      MemeVideoExporter.start(
        ctx,
        exportId,
        planJson,
        onProgress = { progress ->
          this@MemegetBgModule.sendEvent(
            "onVideoExportProgress",
            mapOf(
              "exportId" to exportId,
              "stage" to progress.stage,
              "progress" to progress.fraction,
              "detail" to progress.detail
            )
          )
        },
        onSettled = { result ->
          result.fold(
            onSuccess = { outcome ->
              promise.resolve(mapOf("path" to outcome.uri, "warnings" to outcome.warnings))
            },
            onFailure = { error ->
              // A cancel is not an error the user needs a red toast for, and once the message has
              // crossed the bridge the code is the only thing that still tells them apart.
              val code = if (error is MemeVideoExporter.CancelledException) {
                "E_VIDEO_EXPORT_CANCELLED"
              } else {
                "E_VIDEO_EXPORT"
              }
              promise.reject(code, error.message ?: error.toString(), error)
            }
          )
        }
      )
    }

    // Ask a running export to stop. False means there was nothing to cancel; the export's own
    // promise is what reports that the cancel finished releasing everything.
    Function("cancelVideoExport") { exportId: String -> MemeVideoExporter.cancel(exportId) }


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

    // Last-modified time (ms since epoch) for a SAF content:// document/tree,
    // read straight off DocumentFile. Child document URIs are queried with
    // fromSingleUri; folder tree URIs must use fromTreeUri or AndroidX logs
    // "Unsupported Uri .../tree/..." and returns no metadata.
    Function("getModifiedTime") { uriStr: String ->
      val ctx = appContext.reactContext ?: return@Function null
      try {
        val uri = Uri.parse(uriStr)
        val path = uri.path.orEmpty()
        val doc =
          if (path.contains("/tree/") && !path.contains("/document/")) {
            DocumentFile.fromTreeUri(ctx, uri)
          } else {
            DocumentFile.fromSingleUri(ctx, uri)
          } ?: return@Function null
        val lm = doc.lastModified()
        if (lm > 0L) lm.toDouble() else null
      } catch (e: Exception) {
        null
      }
    }

    // Inspect local file:// or content:// media without materializing or
    // uploading it. AsyncFunction runs off the JS thread and propagates real
    // probe failures as rejected promises.
    AsyncFunction("probeMedia") { source: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeMediaProbe.probe(ctx, source).toMap()
    }

    // Detect real local-image text using the same pinned ML Kit stack as
    // expo-text-extractor. The detector honors EXIF before recognition and
    // returns normalized block/line/element geometry.
    AsyncFunction("detectTextRegions") { source: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeTextDetector.detect(ctx, source).toMap()
    }

    // Whether the optional ML Kit subject segmentation module is already on the
    // device. Cheap probe that does NOT trigger an install, so the studio can
    // decide up front whether the user is about to wait for a download.
    AsyncFunction("subjectSegmentationModuleInstalled") {
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeStillSubjectSegmenter.moduleInstalled(ctx)
    }

    // Segment the subjects of a local still image and materialize one cutout PNG
    // per subject plus a combined one, all inside a per-request cache directory
    // the caller releases. Rejects with the segmenter's own failure code
    // (E_CUTOUT_OFFLINE / E_CUTOUT_MODULE_UNAVAILABLE / E_CUTOUT_CANCELLED /
    // E_CUTOUT_FAILED) so JS can offer the right remedy; an image with no
    // subject RESOLVES with a null combined cutout, because that is not a
    // failure.
    AsyncFunction("segmentImageSubjects") { source: String, requestId: String, promise: Promise ->
      val ctx = appContext.reactContext
        ?: return@AsyncFunction promise.reject("E_CONTEXT", "React context unavailable", null)
      try {
        promise.resolve(
          MemeStillSubjectSegmenter.segment(ctx, source, requestId) { payload ->
            this@MemegetBgModule.sendEvent("onSubjectSegmentationProgress", payload)
          }.toMap()
        )
      } catch (error: SubjectCutoutException) {
        promise.reject(error.failure.code, error.message, error)
      } catch (error: Throwable) {
        promise.reject(SubjectCutoutFailure.FAILED.code, error.message, error)
      }
    }

    // Ask an in-flight request to stop. Returns immediately: the run itself
    // rejects with E_CUTOUT_CANCELLED once it reaches its next checkpoint.
    Function("cancelSubjectSegmentation") { requestId: String ->
      MemeStillSubjectSegmenter.requestCancel(requestId)
    }

    // Delete one request's cutout files. Called when the studio drops a cutout
    // or supersedes it — these are full-resolution PNGs in the cache and nothing
    // else knows they became garbage.
    AsyncFunction("releaseSubjectCutouts") { requestId: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeStillSubjectSegmenter.release(ctx, requestId)
    }

    // Drop cutout directories old enough that no session can be using them.
    AsyncFunction("sweepSubjectCutouts") {
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeStillSubjectSegmenter.sweepStaleRequests(ctx)
    }

    // Sample a bounded ring outside a normalized region on a downsampled,
    // EXIF-oriented bitmap. Real decode/sample errors reject.
    AsyncFunction("sampleImageBorderColor") {
      source: String,
      x: Double,
      y: Double,
      width: Double,
      height: Double ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeTextDetector.sampleBorderColor(
        ctx,
        source,
        NormalizedImageRect(x, y, width, height)
      ).toMap()
    }

    AsyncFunction("sampleImagePixelGrid") {
      source: String,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      pixelSize: Int ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      MemeTextDetector.samplePixelGrid(
        ctx,
        source,
        NormalizedImageRect(x, y, width, height),
        pixelSize
      ).toMap()
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

    AsyncFunction("measureMemeTextLayout") {
      text: String,
      fontFamily: String,
      fontWeight: Int,
      fontSizeDip: Double,
      lineHeightDip: Double,
      letterSpacingEm: Double,
      widthDip: Double,
      align: String ->
      val ctx = appContext.reactContext
        ?: throw IllegalStateException("React context unavailable")
      val density = MemeTextDensity(ctx.resources.displayMetrics.density)
      MemeTextLayout.measure(
        context = ctx,
        text = text,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSizeDip = fontSizeDip.toFloat(),
        lineHeightDip = lineHeightDip.toFloat(),
        letterSpacingEm = letterSpacingEm.toFloat(),
        widthDip = widthDip.toFloat(),
        align = align,
        density = density
      ).toDip(density).toMap()
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

    // One lease for the JS caller, held through as many progress updates as it likes. The count
    // lives in KeepAliveLease because the video exporter holds a lease of its own: without it,
    // whichever of the two finished first would stop the service out from under the other.
    Function("startForeground") { title: String, text: String, progress: Int, total: Int ->
      val ctx = appContext.reactContext ?: return@Function null
      if (jsKeepAlive) {
        KeepAliveLease.update(ctx, title, text, progress, total)
      } else {
        jsKeepAlive = true
        KeepAliveLease.acquire(ctx, title, text, progress, total)
      }
    }

    Function("stopForeground") {
      val ctx = appContext.reactContext ?: return@Function null
      releaseJsKeepAlive(ctx)
    }

    // A JS reload destroys the module without calling stopForeground, and a lease nobody holds a
    // reference to keeps the notification up until the process dies.
    OnDestroy {
      appContext.reactContext?.let { releaseJsKeepAlive(it) }
    }
  }

  // Whether the JS side is currently holding its single lease.
  private var jsKeepAlive = false

  private fun releaseJsKeepAlive(context: Context) {
    if (!jsKeepAlive) return
    jsKeepAlive = false
    KeepAliveLease.release(context)
  }

  // The JS side owns the plan shape, so the bridge only marshals: `[{uri, role, mimeType?}]`.
  // Bounded by the composition's own item ceiling — a caller that hands over an unbounded list is
  // asking this to header-decode an unbounded number of files on a background thread.
  private fun compositionAssetRequirements(
    requirementsJson: String
  ): List<RetainedRangeComposition.AssetRequirement> {
    val entries = JSONArray(requirementsJson)
    require(entries.length() <= RetainedRangeComposition.MAX_SEGMENTS) {
      "At most ${RetainedRangeComposition.MAX_SEGMENTS} assets can be inspected at once, " +
        "got ${entries.length()}"
    }
    return (0 until entries.length()).map { index ->
      val entry = entries.getJSONObject(index)
      RetainedRangeComposition.AssetRequirement(
        uri = entry.getString("uri"),
        role = RetainedRangeComposition.AssetRole.valueOf(entry.getString("role")),
        declaredMimeType = entry.optString("mimeType").ifBlank { null }
      )
    }
  }
}
