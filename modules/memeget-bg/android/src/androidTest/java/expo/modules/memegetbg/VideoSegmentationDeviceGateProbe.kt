package expo.modules.memegetbg

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object VideoSegmentationDeviceGateProbe {
  private const val TASKS_VISION_VERSION = "0.10.29"
  private const val MODEL_ASSET = "selfie_segmenter_float16_v1.tflite"
  private const val PROVENANCE_ASSET = "video_segmentation_provenance.json"
  private const val FIXTURE_DURATION_MS = 10_000L
  private const val MASK_THRESHOLD = 0.5f
  private const val SMOOTHING_CURRENT_WEIGHT = 0.65f
  private const val MIN_FOREGROUND_COVERAGE = 0.002
  private const val MAX_FOREGROUND_COVERAGE = 0.70
  private const val MIN_NON_EMPTY_FRAME_RATIO = 0.95
  private const val MIN_TEMPORAL_IOU_MEAN = 0.55
  private const val MAX_EDGE_PUMPING_P95 = 0.35
  private const val MAX_AREA_PUMPING_P95 = 0.35
  private val WORKING_SIZES = intArrayOf(256, 384, 512)
  private val MASK_FPS = intArrayOf(8, 12, 15)

  private data class FixtureSpec(val id: String, val assetName: String)

  private data class ExtractedMask(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val confidenceMaskCount: Int
  )

  private data class BinaryStats(
    val foregroundPixels: Int,
    val edgePixels: Int,
    val binary: BooleanArray
  )

  private data class FixtureObservation(
    val json: JSONObject,
    val runtimeMs: Long,
    val peakPssBytes: Long,
    val qualityPass: Boolean,
    val evidence: JSONObject
  )

  private data class MatrixResult(
    val json: JSONObject,
    val contract: VideoSegmentationGateContracts.MatrixObservation
  )

  private data class MeasuredMaskSeries(
    val metrics: JSONObject,
    val inferenceLatenciesMs: List<Double>,
    val decodeLatenciesMs: List<Double>,
    val framesProcessed: Int,
    val maskWidth: Int,
    val maskHeight: Int,
    val confidenceMaskCount: Int,
    val evidenceSheet: Bitmap
  )

  private val fixtures = listOf(
    FixtureSpec("one_person", "video_segmentation_one_person_10s_720p.mp4"),
    FixtureSpec("two_people_crossing_occluding", "video_segmentation_two_crossing_10s_720p.mp4"),
    FixtureSpec("fast_motion", "video_segmentation_fast_motion_10s_720p.mp4")
  )

  fun validateAssetProvenance(instrumentation: Instrumentation): JSONObject {
    val assets = instrumentation.context.assets
    val provenance = JSONObject(assets.open(PROVENANCE_ASSET).bufferedReader().use { it.readText() })
    val model = provenance.getJSONObject("model")
    val observedModelDigest = assets.open(model.getString("asset")).use(::sha256)
    val modelDigestMatches = observedModelDigest == model.getString("sha256")
    val modelProvenanceComplete = VideoSegmentationGateContracts.provenanceComplete(
      version = model.getString("version"),
      downloadUrl = model.getString("downloadUrl"),
      license = model.getString("license"),
      licenseUrl = model.getString("licenseUrl"),
      sha256 = model.getString("sha256")
    )

    var allFixtureDigestsMatch = true
    val observedFixtures = JSONArray()
    val fixtureRecords = provenance.getJSONArray("fixtures")
    for (index in 0 until fixtureRecords.length()) {
      val fixture = fixtureRecords.getJSONObject(index)
      val observedDigest = assets.open(fixture.getString("asset")).use(::sha256)
      val matches = observedDigest == fixture.getString("sha256")
      allFixtureDigestsMatch = allFixtureDigestsMatch && matches
      observedFixtures.put(
        JSONObject(fixture.toString())
          .put("observedSha256", observedDigest)
          .put("digestMatches", matches)
      )
    }

    return JSONObject()
      .put("tasksVision", JSONObject(provenance.getJSONObject("tasksVision").toString()))
      .put(
        "model",
        JSONObject(model.toString())
          .put("observedSha256", observedModelDigest)
          .put("digestMatches", modelDigestMatches)
      )
      .put("fixtureSource", JSONObject(provenance.getJSONObject("fixtureSource").toString()))
      .put("generator", JSONObject(provenance.getJSONObject("generator").toString()))
      .put("fixtures", observedFixtures)
      .put("modelProvenanceComplete", modelProvenanceComplete)
      .put("allDigestsMatch", modelDigestMatches && allFixtureDigestsMatch)
  }

  fun run(instrumentation: Instrumentation): JSONObject {
    val targetContext = instrumentation.targetContext
    val provenance = validateAssetProvenance(instrumentation)
    val workDir = File(targetContext.cacheDir, "video_segmentation_device_gate").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
    targetContext.filesDir.listFiles { file -> file.name.startsWith("video-segmentation-mask-") }
      ?.forEach(File::delete)

    val fixtureFiles = linkedMapOf<String, File>()
    for (fixture in fixtures) {
      val file = File(workDir, fixture.assetName)
      instrumentation.context.assets.open(fixture.assetName).use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
      }
      fixtureFiles[fixture.id] = file
    }

    val activityManager = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryCeilingBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
    val matrixResults = mutableListOf<MatrixResult>()
    val maskEvidence = JSONArray()
    val cancellation: JSONObject

    try {
      for (workingSize in WORKING_SIZES) {
        for (maskFps in MASK_FPS) {
          val result = runConfiguration(
            instrumentation,
            targetContext,
            workingSize,
            maskFps,
            memoryCeilingBytes,
            fixtureFiles,
            maskEvidence
          )
          matrixResults += result
        }
      }
      cancellation = runCancellationProbe(
        instrumentation,
        targetContext,
        checkNotNull(fixtureFiles["fast_motion"]),
        workDir
      )
    } finally {
      workDir.deleteRecursively()
    }

    val cancellationPass = cancellation.getString("status") == "PASS"
    for (result in matrixResults) {
      result.json.put(
        "accepted",
        VideoSegmentationGateContracts.videoIsolationAccepted(result.contract, cancellationPass)
      )
    }
    val selected = VideoSegmentationGateContracts.selectSmallestAccepted(
      matrixResults.map(MatrixResult::contract),
      cancellationPass
    )
    val selectedJson = selected?.let { chosen ->
      matrixResults.first {
        it.contract.workingSize == chosen.workingSize && it.contract.maskFps == chosen.maskFps
      }.json
    }
    val videoIsolationAccepted = selected != null
    val tracking = trackingDecision()
    val provenancePass = provenance.getBoolean("allDigestsMatch") &&
      provenance.getBoolean("modelProvenanceComplete") &&
      provenance.getJSONObject("tasksVision").getString("version") == TASKS_VISION_VERSION
    val matrixComplete = matrixResults.size == WORKING_SIZES.size * MASK_FPS.size
    val artifactObservation = nativeArtifactObservation(instrumentation)
    val pageAlignmentPass = artifactObservation.optBoolean("arm64ElfSupports16KiBPages", false)
    val gatePass = provenancePass && matrixComplete && cancellationPass && videoIsolationAccepted && pageAlignmentPass

    return JSONObject()
      .put("schemaVersion", 1)
      .put("task", "0.2")
      .put("observedAtUtc", utcNow())
      .put("gateStatus", if (gatePass) "PASS" else "FAIL")
      .put("device", deviceObservation())
      .put("provenance", provenance)
      .put("nativeArtifact", artifactObservation)
      .put(
        "probe",
        JSONObject()
          .put("runningMode", "VIDEO")
          .put("delegate", "CPU")
          .put("workingSizes", JSONArray(WORKING_SIZES.toList()))
          .put("maskFps", JSONArray(MASK_FPS.toList()))
          .put("maskThreshold", MASK_THRESHOLD.toDouble())
          .put("temporalSmoothing", "EMA current=$SMOOTHING_CURRENT_WEIGHT previous=${1f - SMOOTHING_CURRENT_WEIGHT}")
          .put("peakMemoryMetric", "android.os.Debug.getPss process PSS sampled every 20 ms, including model setup and teardown")
          .put("pipelineRuntimeMetric", "asset decode + scale + VIDEO-mode inference + configured-FPS smoothing + mask metrics + ten contact-sheet panel overlays; PNG encoding excluded")
          .put("latencyMetric", "per-frame MediaPipe segmentForVideo wall-clock latency")
          .put("memoryCeilingBytes", memoryCeilingBytes)
          .put("memoryCeilingSource", "ActivityManager.memoryClass observed on the physical device")
          .put(
            "qualityThresholds",
            JSONObject()
              .put("foregroundCoverageMeanMin", MIN_FOREGROUND_COVERAGE)
              .put("foregroundCoverageMeanMax", MAX_FOREGROUND_COVERAGE)
              .put("nonEmptyFrameRatioMin", MIN_NON_EMPTY_FRAME_RATIO)
              .put("temporalMaskIouMeanMin", MIN_TEMPORAL_IOU_MEAN)
              .put("edgePumpingP95Max", MAX_EDGE_PUMPING_P95)
              .put("areaPumpingP95Max", MAX_AREA_PUMPING_P95)
          )
      )
      .put("matrix", JSONArray().apply { matrixResults.forEach { put(it.json) } })
      .put("maskEvidence", maskEvidence)
      .put("cancellationCleanup", cancellation)
      .put("tracking", tracking)
      .put(
        "capabilities",
        JSONObject()
          .put(
            "videoIsolation",
            JSONObject()
              .put("status", if (videoIsolationAccepted) "ACCEPTED" else "REJECTED")
              .put(
                "reason",
                if (videoIsolationAccepted) {
                  "Smallest observed configuration passes all three fixture quality thresholds, <=3x realtime, measured memory ceiling, and cancellation cleanup."
                } else {
                  "No observed configuration passes all three fixture quality thresholds, <=3x realtime, measured memory ceiling, and cancellation cleanup; no production stub is permitted."
                }
              )
              .put("selectedSettings", selectedJson ?: JSONObject.NULL)
              .put("stillImageIsolation", "OUT_OF_SCOPE_LATER")
          )
          .put(
            "autoTrack",
            JSONObject()
              .put("status", tracking.getString("decision"))
              .put("reason", tracking.getString("reason"))
              .put("manualSparseKeyframes", "PRODUCT_COMPLETE_FALLBACK")
          )
      )
      .put(
        "criteria",
        JSONObject()
          .put("provenance", criterion(provenancePass, "Pinned model/artifact/fixture provenance and SHA-256 digests match packaged assets"))
          .put("matrixComplete", criterion(matrixComplete, "All 256/384/512 x 8/12/15 observations completed"))
          .put("nativePageAlignment", criterion(pageAlignmentPass, "Packaged arm64 MediaPipe ELF PT_LOAD alignments support 16 KiB pages"))
          .put("cancellationCleanup", criterion(cancellationPass, "Cancellation stops work, closes MediaPipe/decoder resources, removes partial evidence, and permits follow-up inference"))
          .put("videoIsolation", criterion(videoIsolationAccepted, "A selected 10 s 720p configuration is <=3x realtime, below the observed memory ceiling, and quality-acceptable on every fixture"))
      )
  }

  private fun runConfiguration(
    instrumentation: Instrumentation,
    context: Context,
    workingSize: Int,
    maskFps: Int,
    memoryCeilingBytes: Long,
    fixtureFiles: Map<String, File>,
    maskEvidence: JSONArray
  ): MatrixResult {
    val fixtureJson = JSONArray()
    val fixtureObservations = mutableListOf<FixtureObservation>()
    var failure: Throwable? = null

    for (fixture in fixtures) {
      val observation = try {
        runFixture(
          instrumentation,
          context,
          fixture,
          checkNotNull(fixtureFiles[fixture.id]),
          workingSize,
          maskFps
        )
      } catch (error: Throwable) {
        failure = failure ?: error
        null
      }
      if (observation == null) {
        fixtureJson.put(
          JSONObject()
            .put("id", fixture.id)
            .put("status", "FAILED")
            .put("error", errorJson(checkNotNull(failure)))
        )
      } else {
        fixtureObservations += observation
        fixtureJson.put(observation.json)
        maskEvidence.put(observation.evidence)
      }
    }

    val allFixturesCompleted = fixtureObservations.size == fixtures.size
    val qualityPass = allFixturesCompleted && fixtureObservations.all(FixtureObservation::qualityPass)
    val worstRuntimeMs = fixtureObservations.maxOfOrNull(FixtureObservation::runtimeMs) ?: FIXTURE_DURATION_MS * 3 + 1
    val peakPssBytes = fixtureObservations.maxOfOrNull(FixtureObservation::peakPssBytes) ?: Long.MAX_VALUE
    val contract = VideoSegmentationGateContracts.MatrixObservation(
      workingSize = workingSize,
      maskFps = maskFps,
      runtimeMs = worstRuntimeMs,
      durationMs = FIXTURE_DURATION_MS,
      peakPssBytes = peakPssBytes,
      memoryCeilingBytes = memoryCeilingBytes,
      qualityPass = qualityPass,
      fixtureCount = fixtureObservations.size
    )
    val json = JSONObject()
      .put("workingSize", workingSize)
      .put("workingFrameWidth", workingSize)
      .put("workingFrameHeight", scaledHeight(workingSize))
      .put("maskFps", maskFps)
      .put("status", if (allFixturesCompleted) "COMPLETED" else "FAILED")
      .put("qualityStatus", if (qualityPass) "PASS" else "FAIL")
      .put("worstPipelineRuntimeMs", worstRuntimeMs)
      .put("worstRuntimeXRealtime", worstRuntimeMs.toDouble() / FIXTURE_DURATION_MS)
      .put("peakPssBytes", if (peakPssBytes == Long.MAX_VALUE) JSONObject.NULL else peakPssBytes)
      .put("memoryCeilingBytes", memoryCeilingBytes)
      .put("underMemoryCeiling", peakPssBytes < memoryCeilingBytes)
      .put("withinThreeTimesRealtime", worstRuntimeMs <= FIXTURE_DURATION_MS * 3)
      .put("fixtures", fixtureJson)
    return MatrixResult(json, contract)
  }

  private fun runFixture(
    instrumentation: Instrumentation,
    context: Context,
    fixture: FixtureSpec,
    source: File,
    workingSize: Int,
    maskFps: Int
  ): FixtureObservation {
    val retriever = MediaMetadataRetriever()
    val peakPssBytes = AtomicLong(currentPssBytes())
    val sampling = AtomicBoolean(true)
    val sampler = thread(name = "segmentation-pss", isDaemon = true) {
      while (sampling.get()) {
        updateMax(peakPssBytes, currentPssBytes())
        SystemClock.sleep(20)
      }
      updateMax(peakPssBytes, currentPssBytes())
    }
    val startMs = SystemClock.elapsedRealtime()
    val segmenter = createSegmenter(instrumentation, context)
    val series: MeasuredMaskSeries
    val observedDurationMs: Long
    val observedWidth: Int
    val observedHeight: Int
    try {
      retriever.setDataSource(source.absolutePath)
      observedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        ?: error("Missing duration for ${fixture.id}")
      observedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        ?: error("Missing width for ${fixture.id}")
      observedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        ?: error("Missing height for ${fixture.id}")
      series = measureMaskSeries(retriever, segmenter, workingSize, maskFps)
    } finally {
      retriever.release()
      segmenter.close()
      sampling.set(false)
      sampler.join(5_000)
    }
    val runtimeMs = SystemClock.elapsedRealtime() - startMs
    val evidence = writeEvidence(
      context,
      fixture,
      workingSize,
      maskFps,
      series.evidenceSheet
    )
    val metrics = series.metrics
    val qualityPass = metrics.getBoolean("qualityPass")
    val json = JSONObject()
      .put("id", fixture.id)
      .put("status", "COMPLETED")
      .put("sourceSha256", sha256(source))
      .put("sourceBytes", source.length())
      .put("observedDurationMs", observedDurationMs)
      .put("observedWidth", observedWidth)
      .put("observedHeight", observedHeight)
      .put("framesProcessed", series.framesProcessed)
      .put("maskWidth", series.maskWidth)
      .put("maskHeight", series.maskHeight)
      .put("confidenceMaskCount", series.confidenceMaskCount)
      .put("pipelineRuntimeMs", runtimeMs)
      .put("runtimeXRealtime", runtimeMs.toDouble() / FIXTURE_DURATION_MS)
      .put("throughputFramesPerSecond", series.framesProcessed * 1000.0 / runtimeMs)
      .put("inferenceLatencyMs", latencyJson(series.inferenceLatenciesMs))
      .put("decodeLatencyMs", latencyJson(series.decodeLatenciesMs))
      .put("peakPssBytes", peakPssBytes.get())
      .put("quality", metrics)
      .put("evidenceFileName", evidence.getString("fileName"))
    return FixtureObservation(json, runtimeMs, peakPssBytes.get(), qualityPass, evidence)
  }

  private fun measureMaskSeries(
    retriever: MediaMetadataRetriever,
    segmenter: ImageSegmenter,
    workingSize: Int,
    maskFps: Int
  ): MeasuredMaskSeries {
    val frameCount = maskFps * (FIXTURE_DURATION_MS / 1000L).toInt()
    val inferenceLatencies = ArrayList<Double>(frameCount)
    val decodeLatencies = ArrayList<Double>(frameCount)
    val coverage = ArrayList<Double>(frameCount)
    val temporalIous = ArrayList<Double>(frameCount - 1)
    val edgePumping = ArrayList<Double>(frameCount - 1)
    val areaPumping = ArrayList<Double>(frameCount - 1)
    var smoothed: FloatArray? = null
    var previousStats: BinaryStats? = null
    var maskWidth = 0
    var maskHeight = 0
    var confidenceMaskCount = 0
    var nonEmptyFrames = 0
    val evidenceSchedule = VideoSegmentationGateContracts.evidenceSchedule(maskFps, durationSeconds = 10)
    val evidenceHeight = scaledHeight(workingSize)
    val evidenceSheet = Bitmap.createBitmap(workingSize * 5, evidenceHeight * 2, Bitmap.Config.ARGB_8888)
    val evidenceCanvas = Canvas(evidenceSheet).apply { drawColor(Color.BLACK) }
    val evidenceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = max(12f, workingSize / 18f)
      setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    for (frameIndex in 0 until frameCount) {
      val timestampMs = frameIndex * 1000L / maskFps
      val evidenceFrame = evidenceSchedule[frameIndex]
      val decodeStartNs = SystemClock.elapsedRealtimeNanos()
      val bitmap = retriever.getScaledFrameAtTime(
        timestampMs * 1000L,
        MediaMetadataRetriever.OPTION_CLOSEST,
        workingSize,
        scaledHeight(workingSize)
      ) ?: error("Decoder returned null at ${timestampMs}ms")
      decodeLatencies += nanosToMillis(SystemClock.elapsedRealtimeNanos() - decodeStartNs)
      val sourceCopy = evidenceFrame.panelSecond?.let {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
      }
      val inferenceStartNs = SystemClock.elapsedRealtimeNanos()
      val extracted = segmentFrame(segmenter, bitmap, timestampMs)
      inferenceLatencies += nanosToMillis(SystemClock.elapsedRealtimeNanos() - inferenceStartNs)
      bitmap.recycle()

      maskWidth = extracted.width
      maskHeight = extracted.height
      confidenceMaskCount = extracted.confidenceMaskCount
      val priorSmoothed = smoothed
      val currentSmoothed = if (priorSmoothed == null) {
        extracted.values.copyOf()
      } else {
        FloatArray(extracted.values.size) { index ->
          SMOOTHING_CURRENT_WEIGHT * extracted.values[index] +
            (1f - SMOOTHING_CURRENT_WEIGHT) * priorSmoothed[index]
        }
      }
      smoothed = currentSmoothed
      val panelSecond = evidenceFrame.panelSecond
      if (panelSecond != null && sourceCopy != null) {
        val overlay = overlayMask(sourceCopy, currentSmoothed, extracted.width, extracted.height)
        val left = (panelSecond % 5) * workingSize
        val top = (panelSecond / 5) * evidenceHeight
        evidenceCanvas.drawBitmap(overlay, left.toFloat(), top.toFloat(), null)
        evidenceCanvas.drawText(
          "${panelSecond}s",
          left + 6f,
          top + evidenceLabelPaint.textSize + 4f,
          evidenceLabelPaint
        )
        overlay.recycle()
        sourceCopy.recycle()
      }
      val stats = binaryStats(currentSmoothed, extracted.width, extracted.height)
      val totalPixels = max(1, extracted.width * extracted.height)
      val frameCoverage = stats.foregroundPixels.toDouble() / totalPixels
      coverage += frameCoverage
      if (stats.foregroundPixels > 0) nonEmptyFrames++

      val priorStats = previousStats
      if (priorStats != null) {
        temporalIous += binaryIou(priorStats.binary, stats.binary)
        edgePumping += relativeChange(priorStats.edgePixels, stats.edgePixels)
        areaPumping += relativeChange(priorStats.foregroundPixels, stats.foregroundPixels)
      }
      previousStats = stats
    }

    val coverageMean = coverage.average()
    val temporalIouMean = temporalIous.averageOrZero()
    val edgePumpingP95 = percentile(edgePumping, 0.95)
    val areaPumpingP95 = percentile(areaPumping, 0.95)
    val nonEmptyFrameRatio = nonEmptyFrames.toDouble() / frameCount
    val coveragePass = coverageMean in MIN_FOREGROUND_COVERAGE..MAX_FOREGROUND_COVERAGE
    val nonEmptyPass = nonEmptyFrameRatio >= MIN_NON_EMPTY_FRAME_RATIO
    val temporalIouPass = temporalIouMean >= MIN_TEMPORAL_IOU_MEAN
    val edgePumpingPass = edgePumpingP95 <= MAX_EDGE_PUMPING_P95
    val areaPumpingPass = areaPumpingP95 <= MAX_AREA_PUMPING_P95
    val qualityPass = coveragePass && nonEmptyPass && temporalIouPass && edgePumpingPass && areaPumpingPass

    val metrics = JSONObject()
      .put("foregroundCoverageMean", coverageMean)
      .put("foregroundCoverageMin", coverage.minOrNull() ?: 0.0)
      .put("foregroundCoverageMax", coverage.maxOrNull() ?: 0.0)
      .put("nonEmptyFrameRatio", nonEmptyFrameRatio)
      .put("temporalMaskIouMean", temporalIouMean)
      .put("temporalMaskIouP05", percentile(temporalIous, 0.05))
      .put("edgePumpingMean", edgePumping.averageOrZero())
      .put("edgePumpingP95", edgePumpingP95)
      .put("areaPumpingMean", areaPumping.averageOrZero())
      .put("areaPumpingP95", areaPumpingP95)
      .put("foregroundCoveragePass", coveragePass)
      .put("nonEmptyFramesPass", nonEmptyPass)
      .put("temporalStabilityPass", temporalIouPass)
      .put("normalPlaybackNoObviousEdgePumping", edgePumpingPass)
      .put("areaStabilityPass", areaPumpingPass)
      .put("qualityPass", qualityPass)
    return MeasuredMaskSeries(
      metrics,
      inferenceLatencies,
      decodeLatencies,
      frameCount,
      maskWidth,
      maskHeight,
      confidenceMaskCount,
      evidenceSheet
    )
  }

  private fun writeEvidence(
    context: Context,
    fixture: FixtureSpec,
    workingSize: Int,
    maskFps: Int,
    sheet: Bitmap
  ): JSONObject {
    val fileName = "video-segmentation-mask-${fixture.id}-${workingSize}-${maskFps}fps.png"
    val file = File(context.filesDir, fileName)
    val encodingStartMs = SystemClock.elapsedRealtime()
    try {
      file.outputStream().use { output ->
        check(sheet.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not write $fileName" }
      }
    } finally {
      sheet.recycle()
    }
    return JSONObject()
      .put("fixtureId", fixture.id)
      .put("workingSize", workingSize)
      .put("maskFps", maskFps)
      .put("fileName", fileName)
      .put("sha256", sha256(file))
      .put("bytes", file.length())
      .put("pngEncodingRuntimeMs", SystemClock.elapsedRealtime() - encodingStartMs)
      .put("format", "PNG 5x2 contact sheet at source seconds 0-9 captured from the measured full configured-FPS series")
      .put("evidenceFramesProcessed", maskFps * 10)
      .put("visualEncoding", "source RGB with configured-FPS EMA-smoothed >=0.5 mask tinted green and one-pixel mask boundary red")
  }

  private fun overlayMask(
    bitmap: Bitmap,
    mask: FloatArray,
    maskWidth: Int,
    maskHeight: Int
  ): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    for (y in 0 until height) {
      val maskY = min(maskHeight - 1, y * maskHeight / height)
      for (x in 0 until width) {
        val maskX = min(maskWidth - 1, x * maskWidth / width)
        val index = maskY * maskWidth + maskX
        if (mask[index] < MASK_THRESHOLD) continue
        val boundary = maskX == 0 || maskY == 0 || maskX == maskWidth - 1 || maskY == maskHeight - 1 ||
          mask[index - 1] < MASK_THRESHOLD || mask[index + 1] < MASK_THRESHOLD ||
          mask[index - maskWidth] < MASK_THRESHOLD || mask[index + maskWidth] < MASK_THRESHOLD
        val pixelIndex = y * width + x
        val source = pixels[pixelIndex]
        pixels[pixelIndex] = if (boundary) {
          Color.rgb(255, 48, 48)
        } else {
          Color.rgb(
            (Color.red(source) * 0.45).roundToInt(),
            min(255, (Color.green(source) * 0.45 + 140).roundToInt()),
            (Color.blue(source) * 0.45).roundToInt()
          )
        }
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun runCancellationProbe(
    instrumentation: Instrumentation,
    context: Context,
    source: File,
    workDir: File
  ): JSONObject {
    val partial = File(workDir, "cancel-partial-mask-evidence.bin")
    val activeLatch = CountDownLatch(1)
    val cancel = AtomicBoolean(false)
    val segmenterClosed = AtomicBoolean(false)
    val retrieverClosed = AtomicBoolean(false)
    val framesProcessed = AtomicInteger(0)
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit {
      val retriever = MediaMetadataRetriever()
      val segmenter = createSegmenter(instrumentation, context)
      try {
        retriever.setDataSource(source.absolutePath)
        partial.writeText("partial segmentation evidence")
        for (frameIndex in 0 until 150) {
          if (cancel.get()) break
          val timestampMs = frameIndex * 1000L / 15
          val bitmap = retriever.getScaledFrameAtTime(
            timestampMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            512,
            scaledHeight(512)
          ) ?: error("Cancellation decoder returned null")
          segmentFrame(segmenter, bitmap, timestampMs)
          bitmap.recycle()
          framesProcessed.incrementAndGet()
          activeLatch.countDown()
        }
      } finally {
        retriever.release()
        retrieverClosed.set(true)
        segmenter.close()
        segmenterClosed.set(true)
        partial.delete()
      }
    }

    val reachedActiveWork = activeLatch.await(30, TimeUnit.SECONDS)
    val activeBeforeCancel = reachedActiveWork && !future.isDone
    val cancelStartMs = SystemClock.elapsedRealtime()
    cancel.set(true)
    val cancelIssued = cancel.get()
    var workerStopped = false
    var workerError: Throwable? = null
    try {
      future.get(30, TimeUnit.SECONDS)
      workerStopped = true
    } catch (error: Throwable) {
      workerError = error
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
    val cancelLatencyMs = SystemClock.elapsedRealtime() - cancelStartMs
    val followUpSucceeded = try {
      val retriever = MediaMetadataRetriever()
      val segmenter = createSegmenter(instrumentation, context)
      try {
        retriever.setDataSource(source.absolutePath)
        val bitmap = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST, 256, scaledHeight(256))
          ?: error("Follow-up decoder returned null")
        val mask = segmentFrame(segmenter, bitmap, 0L)
        bitmap.recycle()
        mask.values.isNotEmpty()
      } finally {
        retriever.release()
        segmenter.close()
      }
    } catch (_: Throwable) {
      false
    }
    val leftovers = workDir.listFiles { file -> file.name.startsWith("cancel-") }?.size ?: 0
    val partialDeleted = !partial.exists()
    val pass = VideoSegmentationGateContracts.cancellationCleanupPass(
      activeBeforeCancel,
      cancelIssued,
      workerStopped,
      segmenterClosed.get(),
      retrieverClosed.get(),
      partialDeleted,
      followUpSucceeded,
      leftovers
    )
    return JSONObject()
      .put("status", if (pass) "PASS" else "FAIL")
      .put("activeBeforeCancel", activeBeforeCancel)
      .put("cancelIssued", cancelIssued)
      .put("framesProcessedBeforeCancel", framesProcessed.get())
      .put("cancelLatencyMs", cancelLatencyMs)
      .put("workerStopped", workerStopped)
      .put("segmenterClosed", segmenterClosed.get())
      .put("retrieverClosed", retrieverClosed.get())
      .put("partialEvidenceDeleted", partialDeleted)
      .put("followUpInferenceSucceeded", followUpSucceeded)
      .put("leftoverCancellationFiles", leftovers)
      .put("workerError", workerError?.let(::errorJson) ?: JSONObject.NULL)
  }

  private fun trackingDecision(): JSONObject {
    val candidateClasses = listOf(
      "com.google.mediapipe.tasks.vision.objecttracker.ObjectTracker",
      "com.google.mediapipe.tasks.vision.tracker.ObjectTracker"
    )
    val classInspection = JSONArray()
    var available = false
    for (candidate in candidateClasses) {
      val present = runCatching { Class.forName(candidate) }.isSuccess
      available = available || present
      classInspection.put(JSONObject().put("className", candidate).put("present", present))
    }
    val resultMethods = com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult::class.java.methods
      .map { it.name }
      .distinct()
      .sorted()
    val exposesIdentity = resultMethods.any { method ->
      method.contains("track", ignoreCase = true) || method.contains("instance", ignoreCase = true)
    }
    val actualImplementationAvailable = available && exposesIdentity
    val accepted = VideoSegmentationGateContracts.autoTrackAccepted(
      actualImplementationAvailable = actualImplementationAvailable,
      crossingPass = false,
      occlusionPass = false,
      cutPass = false,
      subjectJumpCount = 0
    )
    val reason = if (actualImplementationAvailable) {
      "An on-device identity tracker exists but adversarial crossing/occlusion/cut probes did not pass."
    } else {
      "Pinned on-device Tasks Vision exposes only a combined person confidence mask and no subject-identity tracker; crossings, occlusions, and cuts cannot be tested without inventing a stub."
    }
    return JSONObject()
      .put("decision", if (accepted) "ACCEPTED" else "REJECTED_OMITTED")
      .put("actualOnDeviceImplementationAvailable", actualImplementationAvailable)
      .put("candidateClassInspection", classInspection)
      .put("imageSegmenterResultMethods", JSONArray(resultMethods))
      .put("combinedMaskHasSubjectIdentity", exposesIdentity)
      .put(
        "scenarios",
        JSONArray()
          .put(JSONObject().put("id", "crossing").put("fixture", "two_people_crossing_occluding").put("status", "NOT_RUN_NO_IMPLEMENTATION"))
          .put(JSONObject().put("id", "occlusion").put("fixture", "two_people_crossing_occluding").put("status", "NOT_RUN_NO_IMPLEMENTATION"))
          .put(JSONObject().put("id", "cut").put("fixture", "synthetic cut between fixture sources").put("status", "NOT_RUN_NO_IMPLEMENTATION"))
      )
      .put("subjectJumpCount", JSONObject.NULL)
      .put("reason", reason)
  }

  private fun createSegmenter(instrumentation: Instrumentation, context: Context): ImageSegmenter {
    val bytes = instrumentation.context.assets.open(MODEL_ASSET).use { it.readBytes() }
    val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    modelBuffer.put(bytes)
    modelBuffer.rewind()
    val baseOptions = BaseOptions.builder()
      .setModelAssetBuffer(modelBuffer)
      .setDelegate(Delegate.CPU)
      .build()
    val options = ImageSegmenter.ImageSegmenterOptions.builder()
      .setBaseOptions(baseOptions)
      .setRunningMode(RunningMode.VIDEO)
      .setOutputCategoryMask(false)
      .setOutputConfidenceMasks(true)
      .build()
    return ImageSegmenter.createFromOptions(context, options)
  }

  private fun segmentFrame(segmenter: ImageSegmenter, bitmap: Bitmap, timestampMs: Long): ExtractedMask {
    val input = BitmapImageBuilder(bitmap).build()
    try {
      val result = segmenter.segmentForVideo(input, timestampMs)
      val masks = result.confidenceMasks().orElseThrow { IllegalStateException("No confidence masks") }
      try {
        val personMaskIndex = if (masks.size > 1) 1 else 0
        val personMask = masks[personMaskIndex]
        val byteBuffer = ByteBufferExtractor.extract(personMask)
        byteBuffer.rewind()
        val floatBuffer = byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val values = FloatArray(floatBuffer.remaining())
        floatBuffer.get(values)
        check(values.size == personMask.width * personMask.height) {
          "Mask buffer ${values.size} != ${personMask.width}x${personMask.height}"
        }
        return ExtractedMask(values, personMask.width, personMask.height, masks.size)
      } finally {
        masks.forEach { it.close() }
        result.categoryMask().ifPresent { it.close() }
      }
    } finally {
      input.close()
    }
  }

  private fun binaryStats(mask: FloatArray, width: Int, height: Int): BinaryStats {
    val binary = BooleanArray(mask.size) { mask[it] >= MASK_THRESHOLD }
    var foreground = 0
    var edges = 0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val index = y * width + x
        val value = binary[index]
        if (value) foreground++
        if (x + 1 < width && value != binary[index + 1]) edges++
        if (y + 1 < height && value != binary[index + width]) edges++
      }
    }
    return BinaryStats(foreground, edges, binary)
  }

  private fun binaryIou(first: BooleanArray, second: BooleanArray): Double {
    check(first.size == second.size)
    var intersection = 0
    var union = 0
    for (index in first.indices) {
      if (first[index] && second[index]) intersection++
      if (first[index] || second[index]) union++
    }
    return if (union == 0) 1.0 else intersection.toDouble() / union
  }

  private fun nativeArtifactObservation(instrumentation: Instrumentation): JSONObject {
    val libraryName = "libmediapipe_tasks_vision_jni.so"
    val apkPaths = listOf(
      instrumentation.context.applicationInfo.sourceDir,
      instrumentation.targetContext.applicationInfo.sourceDir
    ).distinct()
    val packaged = JSONArray()
    var arm64Alignments = emptyList<Long>()
    for (apkPath in apkPaths) {
      ZipFile(apkPath).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (!entry.name.endsWith("/$libraryName")) continue
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          val alignments = elfLoadAlignments(bytes)
          if (entry.name == "lib/arm64-v8a/$libraryName") arm64Alignments = alignments
          packaged.put(
            JSONObject()
              .put("apk", File(apkPath).name)
              .put("entry", entry.name)
              .put("abi", entry.name.split('/').getOrNull(1) ?: "unknown")
              .put("bytes", bytes.size)
              .put("zipMethod", if (entry.method == java.util.zip.ZipEntry.STORED) "STORED" else "DEFLATED")
              .put("elfLoadSegmentAlignmentsBytes", JSONArray(alignments))
              .put("elfSupports16KiBPages", alignments.isNotEmpty() && alignments.all { it >= 16_384L })
          )
        }
      }
    }
    val installedLibrary = apkPaths.asSequence()
      .map { File(it).parentFile?.resolve("lib/arm64/$libraryName") }
      .filterNotNull()
      .firstOrNull(File::isFile)
    return JSONObject()
      .put("tasksVisionArtifactVersion", TASKS_VISION_VERSION)
      .put("runtimeClassPresent", runCatching { Class.forName("com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter") }.isSuccess)
      .put("packagedNativeLibraries", packaged)
      .put("packagedAbis", JSONArray(packagedAbis(packaged)))
      .put("deviceSupportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
      .put("devicePageSizeBytes", Os.sysconf(OsConstants._SC_PAGESIZE))
      .put("arm64ElfLoadSegmentAlignmentsBytes", JSONArray(arm64Alignments))
      .put("arm64ElfSupports16KiBPages", arm64Alignments.isNotEmpty() && arm64Alignments.all { it >= 16_384L })
      .put("installedArm64LibraryObserved", installedLibrary?.absolutePath ?: JSONObject.NULL)
  }

  private fun packagedAbis(entries: JSONArray): List<String> {
    val result = linkedSetOf<String>()
    for (index in 0 until entries.length()) result += entries.getJSONObject(index).getString("abi")
    return result.toList().sorted()
  }

  private fun elfLoadAlignments(bytes: ByteArray): List<Long> {
    if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1].toInt().toChar() != 'E' ||
      bytes[2].toInt().toChar() != 'L' || bytes[3].toInt().toChar() != 'F') return emptyList()
    val elfClass = bytes[4].toInt()
    val order = if (bytes[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val buffer = ByteBuffer.wrap(bytes).order(order)
    val header = if (elfClass == 2) {
      Triple(buffer.getLong(32), buffer.getShort(54).toInt() and 0xffff, buffer.getShort(56).toInt() and 0xffff)
    } else {
      Triple(buffer.getInt(28).toLong() and 0xffffffffL, buffer.getShort(42).toInt() and 0xffff, buffer.getShort(44).toInt() and 0xffff)
    }
    val alignments = mutableListOf<Long>()
    for (index in 0 until header.third) {
      val offset = header.first + index.toLong() * header.second
      if (offset < 0 || offset + header.second > bytes.size) break
      val position = offset.toInt()
      val type = buffer.getInt(position)
      if (type != 1) continue
      val alignment = if (elfClass == 2) buffer.getLong(position + 48) else buffer.getInt(position + 28).toLong() and 0xffffffffL
      alignments += alignment
    }
    return alignments
  }

  private fun deviceObservation(): JSONObject {
    val fingerprint = Build.FINGERPRINT
    val emulator = fingerprint.startsWith("generic") ||
      fingerprint.contains("emulator", ignoreCase = true) ||
      Build.MODEL.contains("sdk_gphone", ignoreCase = true)
    return JSONObject()
      .put("manufacturer", Build.MANUFACTURER)
      .put("model", Build.MODEL)
      .put("device", Build.DEVICE)
      .put("hardware", Build.HARDWARE)
      .put("androidRelease", Build.VERSION.RELEASE)
      .put("apiLevel", Build.VERSION.SDK_INT)
      .put("buildId", Build.ID)
      .put("buildDisplay", Build.DISPLAY)
      .put("buildIncremental", Build.VERSION.INCREMENTAL)
      .put("securityPatch", Build.VERSION.SECURITY_PATCH)
      .put("buildFingerprint", fingerprint)
      .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
      .put("pageSizeBytes", Os.sysconf(OsConstants._SC_PAGESIZE))
      .put("emulator", emulator)
  }

  private fun criterion(pass: Boolean, description: String): JSONObject =
    JSONObject().put("status", if (pass) "PASS" else "FAIL").put("criterion", description)

  private fun latencyJson(values: List<Double>): JSONObject = JSONObject()
    .put("median", percentile(values, 0.50))
    .put("p95", percentile(values, 0.95))
    .put("max", values.maxOrNull() ?: 0.0)

  private fun percentile(values: List<Double>, percentile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val index = ceil((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
    return sorted[index]
  }

  private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

  private fun relativeChange(first: Int, second: Int): Double =
    abs(first - second).toDouble() / max(1, max(first, second))

  private fun scaledHeight(workingSize: Int): Int = ((workingSize * 720.0 / 1280.0).roundToInt() / 2) * 2

  private fun currentPssBytes(): Long = Debug.getPss().toLong() * 1024L

  private fun updateMax(target: AtomicLong, candidate: Long) {
    while (true) {
      val current = target.get()
      if (candidate <= current || target.compareAndSet(current, candidate)) return
    }
  }

  private fun nanosToMillis(nanos: Long): Double = nanos / 1_000_000.0

  private fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun sha256(file: File): String = file.inputStream().use(::sha256)

  private fun errorJson(error: Throwable): JSONObject = JSONObject()
    .put("type", error.javaClass.name)
    .put("message", error.message ?: "")

  private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }.format(Date())
}
