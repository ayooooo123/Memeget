package expo.modules.memegetbg

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
object Media3DeviceGateProbe {
  private const val EXPORT_TIMEOUT_SECONDS = 180L
  private const val EXPORT_RUNS = 3
  private const val SPEED = 1.25f
  private const val GAIN = 0.5f
  private const val MAX_AV_DELTA_US = 50_000L
  private const val DURATION_TOLERANCE_US = 150_000L
  private const val CROP_SCALE = 0.8f

  private data class RetainedRange(val startUs: Long, val endUs: Long)

  private data class FixtureSpec(
    val id: String,
    val assetName: String,
    val width: Int,
    val height: Int,
    val nominalDurationUs: Long,
    val ranges: List<RetainedRange>
  )

  private data class MediaProbe(
    val bytes: Long,
    val durationUs: Long,
    val videoEndTimeUs: Long?,
    val audioEndTimeUs: Long?,
    val videoFormatDurationUs: Long?,
    val audioFormatDurationUs: Long?,
    val videoMime: String?,
    val audioMime: String?,
    val width: Int?,
    val height: Int?
  ) {
    val hasAudio: Boolean get() = audioMime != null
    val avEndDeltaUs: Long?
      get() = if (videoEndTimeUs != null && audioEndTimeUs != null) {
        abs(videoEndTimeUs - audioEndTimeUs)
      } else {
        null
      }
  }

  private data class ExportRun(val elapsedMs: Long, val peakPssBytes: Long)

  private data class CompositionHolder(val composition: Composition, val staticBitmap: Bitmap)

  fun run(instrumentation: Instrumentation): JSONObject {
    val context = instrumentation.targetContext
    val workDir = File(context.cacheDir, "media3_device_gate").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }

    val specs = listOf(
      FixtureSpec(
        id = "synthetic_5s_720p",
        assetName = "synthetic_5s_720p.mp4",
        width = 1280,
        height = 720,
        nominalDurationUs = 5_000_000L,
        ranges = listOf(RetainedRange(500_000L, 2_000_000L), RetainedRange(3_000_000L, 4_500_000L))
      ),
      FixtureSpec(
        id = "synthetic_15s_720p",
        assetName = "synthetic_15s_720p.mp4",
        width = 1280,
        height = 720,
        nominalDurationUs = 15_000_000L,
        ranges = listOf(RetainedRange(1_000_000L, 5_000_000L), RetainedRange(10_000_000L, 14_000_000L))
      ),
      FixtureSpec(
        id = "synthetic_5s_1080p",
        assetName = "synthetic_5s_1080p.mp4",
        width = 1920,
        height = 1080,
        nominalDurationUs = 5_000_000L,
        ranges = listOf(RetainedRange(500_000L, 2_000_000L), RetainedRange(3_000_000L, 4_500_000L))
      ),
      FixtureSpec(
        id = "synthetic_15s_1080p",
        assetName = "synthetic_15s_1080p.mp4",
        width = 1920,
        height = 1080,
        nominalDurationUs = 15_000_000L,
        ranges = listOf(RetainedRange(1_000_000L, 5_000_000L), RetainedRange(10_000_000L, 14_000_000L))
      )
    )

    val fixtureFiles = linkedMapOf<String, File>()
    val fixtureResults = mutableListOf<JSONObject>()
    val trimResult: JSONObject
    val cancellationResult: JSONObject

    try {
      for (spec in specs) {
        val file = File(workDir, spec.assetName)
        instrumentation.context.assets.open(spec.assetName).use { input ->
          file.outputStream().use { output -> input.copyTo(output) }
        }
        fixtureFiles[spec.id] = file
      }

      trimResult = observedOperation {
        runTrimProbe(context, checkNotNull(fixtureFiles["synthetic_5s_720p"]), workDir)
      }

      for (spec in specs) {
        val source = checkNotNull(fixtureFiles[spec.id])
        fixtureResults += try {
          runFixture(context, spec, source, workDir)
        } catch (error: Throwable) {
          JSONObject()
            .put("id", spec.id)
            .put("status", "FAILED")
            .put("error", errorJson(error))
        }
      }

      cancellationResult = observedOperation {
        runCancellationProbe(
          instrumentation,
          context,
          checkNotNull(fixtureFiles["synthetic_15s_1080p"]),
          workDir
        )
      }
    } finally {
      workDir.deleteRecursively()
    }

    val fixtures = JSONArray().apply { fixtureResults.forEach(::put) }
    val operations = aggregateOperations(trimResult, fixtureResults, cancellationResult)
    val multiAssetApis = inspectMultiAssetApis()
    val criteria = buildCriteria(fixtureResults, operations, cancellationResult)
    val criteriaPass = criteria.keys().asSequence().all { key ->
      criteria.getJSONObject(key).getString("status") == "PASS"
    }

    return JSONObject()
      .put("schemaVersion", 1)
      .put("task", "0.1")
      .put("observedAtUtc", utcNow())
      .put("gateStatus", if (criteriaPass) "PASS" else "FAIL")
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
      .put(
        "media3Runtime",
        JSONObject()
          .put("version", MediaLibraryInfo.VERSION)
          .put("versionInt", MediaLibraryInfo.VERSION_INT)
          .put("registeredModules", MediaLibraryInfo.registeredModules())
          .put("requiredVersion", "1.9.0")
          .put("exactVersionObserved", MediaLibraryInfo.VERSION == "1.9.0")
      )
      .put(
        "probe",
        JSONObject()
          .put("exportRunsPerFixture", EXPORT_RUNS)
          .put("speedFactor", SPEED.toDouble())
          .put("audioGainFactor", GAIN.toDouble())
          .put("cropNdc", JSONArray(listOf(-0.8, 0.8, -0.8, 0.8)))
          .put("peakMemoryMetric", "android.os.Debug.getPss process PSS sampled every 40 ms")
          .put("exportRateMetric", "measured output duration / wall-clock export duration")
      )
      .put("multiAssetApis", multiAssetApis)
      .put("operations", operations)
      .put("criteria", criteria)
      .put("fixtures", fixtures)
  }

  private fun runTrimProbe(context: Context, source: File, workDir: File): JSONObject {
    val output = File(workDir, "trim_probe.mp4").apply { delete() }
    val mediaItem = clippedMediaItem(source, 500_000L, 4_500_000L)
    val edited = EditedMediaItem.Builder(mediaItem).build()
    return try {
      val run = export(context, edited, output)
      val probe = inspectMedia(output)
      val durationPass = abs(probe.durationUs - 4_000_000L) <= DURATION_TOLERANCE_US
      val audioPass = probe.audioMime == MimeTypes.AUDIO_AAC
      val avDeltaUs = probe.avEndDeltaUs
      val driftPass = avDeltaUs != null && avDeltaUs <= MAX_AV_DELTA_US
      JSONObject()
        .put("status", if (durationPass && audioPass && driftPass) "PASS" else "FAILED")
        .put("sourceStartMs", 500)
        .put("sourceEndMs", 4_500)
        .put("expectedOutputDurationMs", 4_000)
        .put("observedOutputDurationMs", microsToMillis(probe.durationUs))
        .put("audioMime", probe.audioMime ?: JSONObject.NULL)
        .put("videoMime", probe.videoMime ?: JSONObject.NULL)
        .put("avEndTimeDeltaMs", avDeltaUs?.let(::microsToMillisExact) ?: JSONObject.NULL)
        .put("outputBytes", probe.bytes)
        .put("wallTimeMs", run.elapsedMs)
        .put("peakPssBytes", run.peakPssBytes)
        .put("durationWithin150ms", durationPass)
        .put("aacPreserved", audioPass)
        .put("avDeltaWithin50ms", driftPass)
    } finally {
      output.delete()
    }
  }

  private fun runFixture(
    context: Context,
    spec: FixtureSpec,
    source: File,
    workDir: File
  ): JSONObject {
    val input = inspectMedia(source)
    val inputRms = audioRms(context, source, 1.5)
    val retainedDurationUs = spec.ranges.sumOf { it.endUs - it.startUs }
    val expectedOutputDurationUs = (retainedDurationUs / SPEED).toLong()
    val runJson = JSONArray()
    val successfulRuns = mutableListOf<ExportRun>()
    var representative: File? = null

    repeat(EXPORT_RUNS) { index ->
      val output = File(workDir, "${spec.id}_run_${index + 1}.mp4").apply { delete() }
      val holder = buildCombinedComposition(source, spec, expectedOutputDurationUs)
      try {
        val run = export(context, holder.composition, output)
        successfulRuns += run
        representative?.delete()
        representative = output
        runJson.put(
          JSONObject()
            .put("run", index + 1)
            .put("status", "PASS")
            .put("wallTimeMs", run.elapsedMs)
            .put("peakPssBytes", run.peakPssBytes)
        )
      } catch (error: Throwable) {
        output.delete()
        runJson.put(
          JSONObject()
            .put("run", index + 1)
            .put("status", "FAILED")
            .put("error", errorJson(error))
        )
      } finally {
        if (!holder.staticBitmap.isRecycled) holder.staticBitmap.recycle()
      }
    }

    val output = representative
    if (output == null || successfulRuns.size != EXPORT_RUNS) {
      output?.delete()
      return JSONObject()
        .put("id", spec.id)
        .put("status", "FAILED")
        .put("synthetic", true)
        .put("runs", runJson)
        .put("error", "Fewer than $EXPORT_RUNS exports completed")
    }

    return try {
      val exported = inspectMedia(output)
      val outputRms = audioRms(context, output, 1.5)
      val volumeRatio = if (inputRms != null && outputRms != null && inputRms > 0.0) {
        outputRms / inputRms
      } else {
        null
      }
      val earlyColors = overlayColors(output, expectedOutputDurationUs / 4L)
      val lateColors = overlayColors(output, expectedOutputDurationUs * 3L / 4L)
      val expectedWidth = (spec.width * CROP_SCALE).roundToInt()
      val expectedHeight = (spec.height * CROP_SCALE).roundToInt()
      val rates = successfulRuns.map { exported.durationUs / 1_000.0 / max(1L, it.elapsedMs) }
      val medianRate = median(rates)
      val exportedAvDeltaUs = exported.avEndDeltaUs
      val trackEndpointsPass = trackEndpointsWithinTolerance(
        expectedEndUs = expectedOutputDurationUs,
        videoPresent = exported.videoMime != null,
        videoEndUs = exported.videoEndTimeUs,
        audioPresent = exported.audioMime != null,
        audioEndUs = exported.audioEndTimeUs
      )

      val checks = JSONObject()
        .put("twoRetainedRanges", trackEndpointsPass)
        .put("h264Output", exported.videoMime == MimeTypes.VIDEO_H264)
        .put("aacPreserved", exported.audioMime == MimeTypes.AUDIO_AAC)
        .put("avDeltaWithin50ms", exportedAvDeltaUs != null && avEndDeltaWithinLimit(exported.videoEndTimeUs, exported.audioEndTimeUs))
        .put("volumeApplied", volumeRatio != null && volumeRatio in 0.40..0.60)
        .put("speedApplied", trackEndpointsPass)
        .put("staticOverlayObserved", earlyColors.getBoolean("staticMagentaObserved") && lateColors.getBoolean("staticMagentaObserved"))
        .put("timestampOverlayObserved", earlyColors.getBoolean("dynamicCyanObserved") && lateColors.getBoolean("dynamicYellowObserved"))
        .put("cropObserved", exported.width == expectedWidth && exported.height == expectedHeight)
      val passed = checks.keys().asSequence().all(checks::getBoolean)

      JSONObject()
        .put("id", spec.id)
        .put("status", if (passed) "PASS" else "FAILED")
        .put("synthetic", true)
        .put("fixtureSha256", sha256(source))
        .put(
          "original",
          mediaProbeJson(input)
            .put("nominalDurationMs", microsToMillis(spec.nominalDurationUs))
            .put("audioRms", inputRms ?: JSONObject.NULL)
        )
        .put(
          "edit",
          JSONObject()
            .put(
              "retainedRangesMs",
              JSONArray().apply {
                spec.ranges.forEach { range ->
                  put(JSONArray(listOf(microsToMillis(range.startUs), microsToMillis(range.endUs))))
                }
              }
            )
            .put("retainedSourceDurationMs", microsToMillis(retainedDurationUs))
            .put("speedFactor", SPEED.toDouble())
            .put("gainFactor", GAIN.toDouble())
            .put("expectedOutputDurationMs", microsToMillis(expectedOutputDurationUs))
            .put("cropExpectedWidth", expectedWidth)
            .put("cropExpectedHeight", expectedHeight)
        )
        .put(
          "output",
          mediaProbeJson(exported)
            .put("audioRms", outputRms ?: JSONObject.NULL)
            .put("observedVolumeRmsRatio", volumeRatio ?: JSONObject.NULL)
            .put("medianExportRateXRealtime", medianRate)
            .put("peakPssBytes", successfulRuns.maxOf { it.peakPssBytes })
        )
        .put("overlayEvidence", JSONObject().put("earlyFrame", earlyColors).put("lateFrame", lateColors))
        .put("runs", runJson)
        .put("checks", checks)
    } finally {
      output.delete()
    }
  }

  private fun buildCombinedComposition(
    source: File,
    spec: FixtureSpec,
    expectedOutputDurationUs: Long
  ): CompositionHolder {
    val speedProvider = object : SpeedProvider {
      override fun getSpeed(timeUs: Long): Float = SPEED
      override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }
    val items = spec.ranges.map { range ->
      EditedMediaItem.Builder(clippedMediaItem(source, range.startUs, range.endUs))
        .setSpeed(speedProvider)
        .build()
    }
    val sequence = EditedMediaItemSequence.Builder(items).build()

    val expectedWidth = (spec.width * CROP_SCALE).roundToInt()
    val expectedHeight = (spec.height * CROP_SCALE).roundToInt()
    val staticBitmap = Bitmap.createBitmap(expectedWidth, expectedHeight, Bitmap.Config.ARGB_8888)
    Canvas(staticBitmap).apply {
      drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      drawRect(0f, 0f, expectedWidth / 8f, expectedHeight.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
      })
    }
    val staticOverlay = BitmapOverlay.createStaticBitmapOverlay(staticBitmap)
    val dynamicPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dynamicOverlay = object : CanvasOverlay(true) {
      override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        dynamicPaint.color = if (presentationTimeUs < expectedOutputDurationUs / 2L) Color.CYAN else Color.YELLOW
        canvas.drawRect(canvas.width * 7f / 8f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), dynamicPaint)
      }
    }
    val effects = Effects(
      listOf(GainProcessor(DefaultGainProvider.Builder(GAIN).build())),
      listOf<Effect>(
        Crop(-CROP_SCALE, CROP_SCALE, -CROP_SCALE, CROP_SCALE),
        OverlayEffect(listOf(staticOverlay, dynamicOverlay))
      )
    )
    return CompositionHolder(Composition.Builder(sequence).setEffects(effects).build(), staticBitmap)
  }

  private fun runCancellationProbe(
    instrumentation: Instrumentation,
    context: Context,
    source: File,
    workDir: File
  ): JSONObject {
    val output = File(workDir, "cancel_probe.mp4").apply { delete() }
    val followUp = File(workDir, "cancel_follow_up.mp4").apply { delete() }
    val edited = EditedMediaItem.Builder(MediaItem.fromUri(source.toURI().toString()))
      .setEffects(Effects(emptyList(), listOf(Crop(-0.9f, 0.9f, -0.9f, 0.9f))))
      .build()
    val transformerRef = AtomicReference<Transformer>()
    val completed = AtomicBoolean(false)
    val failed = AtomicReference<Throwable?>()

    try {
      instrumentation.runOnMainSync {
        val transformer = Transformer.Builder(context)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
              completed.set(true)
            }

            override fun onError(
              composition: Composition,
              result: ExportResult,
              exception: ExportException
            ) {
              failed.set(exception)
            }
          })
          .build()
        transformerRef.set(transformer)
        transformer.start(edited, output.absolutePath)
      }

      SystemClock.sleep(200L)
      val activeBeforeCancel = mediaResourceManagerHasPid(instrumentation, Process.myPid())
      val cancelIssued = AtomicBoolean(false)
      val cancelStart = SystemClock.elapsedRealtime()
      instrumentation.runOnMainSync {
        if (!completed.get() && failed.get() == null) {
          transformerRef.get().cancel()
          cancelIssued.set(true)
        }
      }
      val cancelLatencyMs = SystemClock.elapsedRealtime() - cancelStart
      SystemClock.sleep(400L)

      val partialExistedBeforeCleanup = output.exists()
      val partialDeleteSucceeded = !output.exists() || output.delete()
      val partialExistsAfterCleanup = output.exists()
      val resourceReleased = waitForMediaResourcesReleased(instrumentation, Process.myPid(), 5_000L)
      val followUpSucceeded = try {
        val item = EditedMediaItem.Builder(clippedMediaItem(source, 0L, 1_000_000L)).build()
        export(context, item, followUp)
        val probe = inspectMedia(followUp)
        probe.videoMime == MimeTypes.VIDEO_H264 && probe.audioMime == MimeTypes.AUDIO_AAC
      } catch (_: Throwable) {
        false
      } finally {
        followUp.delete()
      }
      val leftovers = workDir.listFiles()
        ?.filter { it.name.startsWith("cancel_") }
        ?.map { it.name }
        .orEmpty()
      val passed = cancellationCleanupPass(
        activeBeforeCancel = activeBeforeCancel,
        cancelIssued = cancelIssued.get(),
        partialOutputDeleteSucceeded = partialDeleteSucceeded,
        partialOutputExistsAfterCleanup = partialExistsAfterCleanup,
        resourceReleased = resourceReleased,
        followUpSucceeded = followUpSucceeded,
        leftoverCount = leftovers.size
      )

      return JSONObject()
        .put("status", if (passed) "PASS" else "FAILED")
        .put("cancelIssued", cancelIssued.get())
        .put("cancelLatencyMs", cancelLatencyMs)
        .put("partialOutputExistedBeforeExplicitCleanup", partialExistedBeforeCleanup)
        .put("partialOutputDeleteSucceeded", partialDeleteSucceeded)
        .put("partialOutputExistsAfterCleanup", partialExistsAfterCleanup)
        .put("mediaResourceManagerReportedActiveBeforeCancel", activeBeforeCancel)
        .put("mediaResourceManagerReportedReleasedAfterCancel", resourceReleased)
        .put("followUpH264AacExportSucceeded", followUpSucceeded)
        .put("leftoverProbeFiles", JSONArray(leftovers))
    } finally {
      try {
        val transformer = transformerRef.get()
        if (transformer != null) {
          instrumentation.runOnMainSync {
            if (!completed.get() && failed.get() == null) {
              transformer.cancel()
            }
          }
        }
      } finally {
        output.delete()
        followUp.delete()
      }
    }
  }

  private fun export(context: Context, item: EditedMediaItem, output: File): ExportRun {
    return exportInternal(context, output) { transformer -> transformer.start(item, output.absolutePath) }
  }

  private fun export(context: Context, composition: Composition, output: File): ExportRun {
    return exportInternal(context, output) { transformer -> transformer.start(composition, output.absolutePath) }
  }

  private fun exportInternal(
    context: Context,
    output: File,
    start: (Transformer) -> Unit
  ): ExportRun {
    check(!output.exists()) { "Output already exists: $output" }
    val latch = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val transformerRef = AtomicReference<Transformer>()
    val sampling = AtomicBoolean(true)
    val peakPss = AtomicLong(Debug.getPss().toLong() * 1_024L)
    val sampler = Thread({
      while (sampling.get()) {
        peakPss.accumulateAndGet(Debug.getPss().toLong() * 1_024L, ::maxOf)
        SystemClock.sleep(40L)
      }
    }, "Media3GatePssSampler").apply { start() }
    val startedNs = SystemClock.elapsedRealtimeNanos()

    try {
      val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
      instrumentation.runOnMainSync {
        val transformer = Transformer.Builder(context)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
              latch.countDown()
            }

            override fun onError(
              composition: Composition,
              result: ExportResult,
              exception: ExportException
            ) {
              failure.set(exception)
              latch.countDown()
            }
          })
          .build()
        transformerRef.set(transformer)
        start(transformer)
      }
      if (!latch.await(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        instrumentation.runOnMainSync { transformerRef.get().cancel() }
        throw IllegalStateException("Export timed out after $EXPORT_TIMEOUT_SECONDS seconds")
      }
      failure.get()?.let { throw it }
      check(output.isFile && output.length() > 0L) { "Transformer completed without a non-empty output" }
      val elapsedMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L)
      return ExportRun(elapsedMs, peakPss.get())
    } finally {
      sampling.set(false)
      sampler.join(1_000L)
    }
  }

  private fun clippedMediaItem(source: File, startUs: Long, endUs: Long): MediaItem =
    MediaItem.Builder()
      .setUri(source.toURI().toString())
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionUs(startUs)
          .setEndPositionUs(endUs)
          .build()
      )
      .build()

  private fun inspectMedia(file: File): MediaProbe {
    val extractor = MediaExtractor()
    var videoTrackIndex: Int? = null
    var audioTrackIndex: Int? = null
    var videoFormatDurationUs: Long? = null
    var audioFormatDurationUs: Long? = null
    var videoMime: String? = null
    var audioMime: String? = null
    var width: Int? = null
    var height: Int? = null
    try {
      extractor.setDataSource(file.absolutePath)
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        val formatDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
          format.getLong(MediaFormat.KEY_DURATION)
        } else {
          null
        }
        when {
          mime.startsWith("video/") && videoMime == null -> {
            videoTrackIndex = index
            videoMime = mime
            videoFormatDurationUs = formatDurationUs
            if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
            if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
          }
          mime.startsWith("audio/") && audioMime == null -> {
            audioTrackIndex = index
            audioMime = mime
            audioFormatDurationUs = formatDurationUs
          }
        }
      }
    } finally {
      extractor.release()
    }

    val videoEndTimeUs = videoTrackIndex?.let { sampleEndTimeUs(file, it) }
    val audioEndTimeUs = audioTrackIndex?.let { sampleEndTimeUs(file, it) }
    val metadataDurationUs = MediaMetadataRetriever().let { retriever ->
      try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1_000L)
      } finally {
        retriever.release()
      }
    }
    val durationUs = listOfNotNull(videoEndTimeUs, audioEndTimeUs, metadataDurationUs).maxOrNull()
      ?: error("No duration metadata for $file")
    return MediaProbe(
      bytes = file.length(),
      durationUs = durationUs,
      videoEndTimeUs = videoEndTimeUs,
      audioEndTimeUs = audioEndTimeUs,
      videoFormatDurationUs = videoFormatDurationUs,
      audioFormatDurationUs = audioFormatDurationUs,
      videoMime = videoMime,
      audioMime = audioMime,
      width = width,
      height = height
    )
  }

  private fun sampleEndTimeUs(file: File, trackIndex: Int): Long? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      extractor.selectTrack(trackIndex)
      val timestamps = ArrayList<Long>()
      val sampleBuffer = ByteBuffer.allocate(1 shl 20)
      while (extractor.readSampleData(sampleBuffer, 0) >= 0) {
        timestamps += extractor.sampleTime
        sampleBuffer.clear()
        if (!extractor.advance()) break
      }
      if (timestamps.isEmpty()) {
        null
      } else {
        val sorted = timestamps.distinct().sorted()
        val intervals = sorted.zipWithNext { first, second -> second - first }.filter { it > 0L }
        val finalSampleDurationUs = if (intervals.isEmpty()) 0L else medianLong(intervals)
        sorted.last() + finalSampleDurationUs
      }
    } finally {
      extractor.release()
    }
  }

  private fun mediaProbeJson(probe: MediaProbe): JSONObject = JSONObject()
    .put("durationMs", microsToMillis(probe.durationUs))
    .put("videoEndTimeMs", probe.videoEndTimeUs?.let(::microsToMillis) ?: JSONObject.NULL)
    .put("audioEndTimeMs", probe.audioEndTimeUs?.let(::microsToMillis) ?: JSONObject.NULL)
    .put("avEndTimeDeltaMs", probe.avEndDeltaUs?.let(::microsToMillisExact) ?: JSONObject.NULL)
    .put("videoFormatDurationMs", probe.videoFormatDurationUs?.let(::microsToMillis) ?: JSONObject.NULL)
    .put("audioFormatDurationMs", probe.audioFormatDurationUs?.let(::microsToMillis) ?: JSONObject.NULL)
    .put("bytes", probe.bytes)
    .put("hasAudio", probe.hasAudio)
    .put("videoMime", probe.videoMime ?: JSONObject.NULL)
    .put("audioMime", probe.audioMime ?: JSONObject.NULL)
    .put("width", probe.width ?: JSONObject.NULL)
    .put("height", probe.height ?: JSONObject.NULL)

  private fun audioRms(context: Context, file: File, maxSeconds: Double): Double? {
    val result = AudioExtractor.extract(context, file.absolutePath, maxSeconds) ?: return null
    val pcm = File((result["path"] as String).removePrefix("file://"))
    return try {
      val floats = ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
      var count = 0L
      var sumSquares = 0.0
      while (floats.hasRemaining()) {
        val sample = floats.get().toDouble()
        sumSquares += sample * sample
        count++
      }
      if (count == 0L) null else sqrt(sumSquares / count)
    } finally {
      pcm.delete()
    }
  }

  private fun overlayColors(file: File, timeUs: Long): JSONObject {
    val retriever = MediaMetadataRetriever()
    val frame = try {
      retriever.setDataSource(file.absolutePath)
      retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        ?: error("Could not decode frame at $timeUs us")
    } finally {
      retriever.release()
    }
    return try {
      val left = averageColor(frame, frame.width / 16, frame.height / 2)
      val right = averageColor(frame, frame.width * 15 / 16, frame.height / 2)
      JSONObject()
        .put("timeMs", microsToMillis(timeUs))
        .put("leftStripRgb", JSONArray(listOf(Color.red(left), Color.green(left), Color.blue(left))))
        .put("rightStripRgb", JSONArray(listOf(Color.red(right), Color.green(right), Color.blue(right))))
        .put("staticMagentaObserved", Color.red(left) > 160 && Color.blue(left) > 160 && Color.green(left) < 120)
        .put("dynamicCyanObserved", Color.green(right) > 160 && Color.blue(right) > 160 && Color.red(right) < 120)
        .put("dynamicYellowObserved", Color.red(right) > 160 && Color.green(right) > 160 && Color.blue(right) < 120)
    } finally {
      frame.recycle()
    }
  }

  private fun averageColor(bitmap: Bitmap, centerX: Int, centerY: Int): Int {
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (dy in -3..3) {
      for (dx in -3..3) {
        val color = bitmap.getPixel(
          (centerX + dx).coerceIn(0, bitmap.width - 1),
          (centerY + dy).coerceIn(0, bitmap.height - 1)
        )
        red += Color.red(color)
        green += Color.green(color)
        blue += Color.blue(color)
        count++
      }
    }
    return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
  }

  private fun inspectMultiAssetApis(): JSONObject {
    fun constructor(className: String, vararg parameters: Class<*>): Boolean = try {
      Class.forName(className).getConstructor(*parameters)
      true
    } catch (_: ReflectiveOperationException) {
      false
    }

    fun method(className: String, name: String, vararg parameters: Class<*>): Boolean = try {
      Class.forName(className).getMethod(name, *parameters)
      true
    } catch (_: ReflectiveOperationException) {
      false
    }

    val compositionBuilderList = constructor(
      "androidx.media3.transformer.Composition\$Builder",
      List::class.java
    )
    val sequenceBuilderList = constructor(
      "androidx.media3.transformer.EditedMediaItemSequence\$Builder",
      List::class.java
    )
    val sequenceAddItem = method(
      "androidx.media3.transformer.EditedMediaItemSequence\$Builder",
      "addItem",
      EditedMediaItem::class.java
    )
    val sequenceAddGap = method(
      "androidx.media3.transformer.EditedMediaItemSequence\$Builder",
      "addGap",
      java.lang.Long.TYPE
    )
    val transformerCompositionStart = method(
      "androidx.media3.transformer.Transformer",
      "start",
      Composition::class.java,
      String::class.java
    )
    val compositionPlayer = try {
      Class.forName("androidx.media3.transformer.CompositionPlayer")
      true
    } catch (_: ClassNotFoundException) {
      false
    }

    return JSONObject()
      .put("inspection", "reflection against classes loaded in the physical-device probe APK")
      .put(
        "present",
        JSONArray()
          .put(apiJson("Composition.Builder(List<EditedMediaItemSequence>)", compositionBuilderList))
          .put(apiJson("EditedMediaItemSequence.Builder(List<EditedMediaItem>)", sequenceBuilderList))
          .put(apiJson("EditedMediaItemSequence.Builder.addItem(EditedMediaItem)", sequenceAddItem))
          .put(apiJson("EditedMediaItemSequence.Builder.addGap(long)", sequenceAddGap))
          .put(apiJson("Transformer.start(Composition, String)", transformerCompositionStart))
          .put(apiJson("CompositionPlayer", compositionPlayer))
      )
      .put(
        "allInspectedApisPresent",
        compositionBuilderList && sequenceBuilderList && sequenceAddItem && sequenceAddGap &&
          transformerCompositionStart && compositionPlayer
      )
  }

  private fun apiJson(signature: String, exists: Boolean): JSONObject = JSONObject()
    .put("signature", signature)
    .put("exists", exists)

  private fun aggregateOperations(
    trim: JSONObject,
    fixtures: List<JSONObject>,
    cancellation: JSONObject
  ): JSONObject {
    fun allFixtureChecks(key: String): Boolean = fixtures.size == 4 && fixtures.all { fixture ->
      fixture.optJSONObject("checks")?.optBoolean(key, false) == true
    }
    fun outcome(passed: Boolean, evidence: String): JSONObject = JSONObject()
      .put("status", if (passed) "PASS" else "FAILED")
      .put("evidence", evidence)

    return JSONObject()
      .put("trim", trim)
      .put("twoRetainedRanges", outcome(allFixtureChecks("twoRetainedRanges"), "two clipped EditedMediaItems concatenated and every present track end is within 150 ms of expected for every fixture"))
      .put("h264Output", outcome(allFixtureChecks("h264Output"), "MediaExtractor output MIME is video/avc for every fixture"))
      .put("aacPreservation", outcome(allFixtureChecks("aacPreserved"), "MediaExtractor output MIME is audio/mp4a-latm for every unmuted fixture"))
      .put("volume", outcome(allFixtureChecks("volumeApplied"), "decoded output/input PCM RMS ratio is between 0.40 and 0.60 for requested gain 0.50"))
      .put("speed", outcome(allFixtureChecks("speedApplied"), "every present track end matches retained duration divided by 1.25 within 150 ms"))
      .put("staticOverlay", outcome(allFixtureChecks("staticOverlayObserved"), "magenta static strip sampled in early and late decoded frames"))
      .put("timestampAwareOverlay", outcome(allFixtureChecks("timestampOverlayObserved"), "right strip changes from cyan to yellow across decoded output timestamps"))
      .put("crop", outcome(allFixtureChecks("cropObserved"), "MediaExtractor output dimensions equal the requested 0.8 NDC crop"))
      .put("cancellation", cancellation)
  }

  private fun buildCriteria(
    fixtures: List<JSONObject>,
    operations: JSONObject,
    cancellation: JSONObject
  ): JSONObject {
    val completeFixtures = fixtures.size == 4 && fixtures.all { fixture ->
      val runs = fixture.optJSONArray("runs") ?: return@all false
      runs.length() == EXPORT_RUNS && (0 until runs.length()).all { index ->
        runs.getJSONObject(index).optString("status") == "PASS"
      }
    }
    val audioPass = fixtures.size == 4 && fixtures.all {
      it.optJSONObject("checks")?.optBoolean("aacPreserved", false) == true
    }
    val deltasMs = fixtures.mapNotNull {
      it.optJSONObject("output")?.let { output ->
        if (output.isNull("avEndTimeDeltaMs")) null else output.optDouble("avEndTimeDeltaMs")
      }
    }
    val driftPass = fixtures.size == 4 && fixtures.all {
      it.optJSONObject("checks")?.optBoolean("avDeltaWithin50ms", false) == true
    }
    val no1080Crash = fixtures.filter { it.optString("id").contains("1080p") }
      .let { selected ->
        selected.size == 2 && selected.all { fixture ->
          val runs = fixture.optJSONArray("runs") ?: return@all false
          runs.length() == EXPORT_RUNS && (0 until runs.length()).all { index ->
            runs.getJSONObject(index).optString("status") == "PASS"
          }
        }
      }
    val requiredOperationNames = listOf(
      "trim",
      "twoRetainedRanges",
      "h264Output",
      "aacPreservation",
      "volume",
      "speed",
      "staticOverlay",
      "timestampAwareOverlay",
      "crop",
      "cancellation"
    )
    val allOperations = requiredOperationNames.all {
      operations.getJSONObject(it).getString("status") == "PASS"
    }
    val exactVersion = MediaLibraryInfo.VERSION == "1.9.0"

    fun criterion(description: String, passed: Boolean, observed: Any): JSONObject = JSONObject()
      .put("criterion", description)
      .put("status", if (passed) "PASS" else "FAIL")
      .put("observed", observed)

    return JSONObject()
      .put(
        "exactMedia3Version",
        criterion("Runtime Media3 version must equal 1.9.0", exactVersion, MediaLibraryInfo.VERSION)
      )
      .put(
        "audioPreservation",
        criterion("Unmuted exports must retain AAC audio", audioPass, "$audioPass across ${fixtures.size} fixtures")
      )
      .put(
        "avDrift",
        criterion("Every output A/V end-time delta must be <= 50 ms", driftPass, JSONArray(deltasMs))
      )
      .put(
        "cancellationCleanup",
        criterion(
          "Cancellation must leave no probe file or active media-resource-manager entry and a follow-up codec export must succeed",
          cancellation.optString("status") == "PASS",
          cancellation
        )
      )
      .put(
        "no1080pCrash",
        criterion("Both 1080p fixture exports must complete without a crash", no1080Crash, no1080Crash)
      )
      .put(
        "requiredOperations",
        criterion("Every required operation must have passing device evidence", allOperations, requiredOperationNames.joinToString(","))
      )
      .put(
        "fixtureMatrix",
        criterion("5 s and 15 s fixtures at 720p and 1080p must each complete three exports", completeFixtures, fixtures.size)
      )
  }

  private fun observedOperation(block: () -> JSONObject): JSONObject = try {
    block()
  } catch (error: Throwable) {
    JSONObject().put("status", "FAILED").put("error", errorJson(error))
  }

  private fun errorJson(error: Throwable): JSONObject = JSONObject()
    .put("type", error.javaClass.name)
    .put("message", error.message ?: "")

  private fun mediaResourceManagerHasPid(instrumentation: Instrumentation, pid: Int): Boolean {
    val output = shell(instrumentation, "dumpsys media.resource_manager")
    val activeSection = output.substringAfter("Processes:", "")
      .substringBefore("Process Pid override:", "")
    return Regex("(^|\\D)$pid(\\D|$)").containsMatchIn(activeSection)
  }

  private fun waitForMediaResourcesReleased(
    instrumentation: Instrumentation,
    pid: Int,
    timeoutMs: Long
  ): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    do {
      if (!mediaResourceManagerHasPid(instrumentation, pid)) return true
      SystemClock.sleep(100L)
    } while (SystemClock.elapsedRealtime() < deadline)
    return !mediaResourceManagerHasPid(instrumentation, pid)
  }

  private fun shell(instrumentation: Instrumentation, command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1_024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
  internal fun trackEndpointsWithinTolerance(
    expectedEndUs: Long,
    videoPresent: Boolean,
    videoEndUs: Long?,
    audioPresent: Boolean,
    audioEndUs: Long?
  ): Boolean {
    val videoPass = !videoPresent ||
      (videoEndUs != null && abs(videoEndUs - expectedEndUs) <= DURATION_TOLERANCE_US)
    val audioPass = !audioPresent ||
      (audioEndUs != null && abs(audioEndUs - expectedEndUs) <= DURATION_TOLERANCE_US)
    return videoPass && audioPass
  }

  internal fun avEndDeltaWithinLimit(videoEndUs: Long?, audioEndUs: Long?): Boolean =
    videoEndUs != null && audioEndUs != null && abs(videoEndUs - audioEndUs) <= MAX_AV_DELTA_US

  internal fun cancellationCleanupPass(
    activeBeforeCancel: Boolean,
    cancelIssued: Boolean,
    partialOutputDeleteSucceeded: Boolean,
    partialOutputExistsAfterCleanup: Boolean,
    resourceReleased: Boolean,
    followUpSucceeded: Boolean,
    leftoverCount: Int
  ): Boolean = activeBeforeCancel &&
    cancelIssued &&
    partialOutputDeleteSucceeded &&
    !partialOutputExistsAfterCleanup &&
    resourceReleased &&
    followUpSucceeded &&
    leftoverCount == 0


  private fun median(values: List<Double>): Double {
    check(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }
  private fun medianLong(values: List<Long>): Long {
    check(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2L
  }


  private fun microsToMillis(value: Long): Long = value / 1_000L
  private fun microsToMillisExact(value: Long): Double = value / 1_000.0

  private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date())
  }
}
