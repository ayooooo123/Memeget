package expo.modules.memegetbg

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.RandomAccessFile
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
  private const val MODEL_SHA256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"
  private const val TASKS_VISION_POM_SHA256 = "718c0702d999da9581753b883e3ebdad993e1ba01bfd1e7715187f9050906428"
  private const val APACHE_LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
  private const val MODEL_CARD_SHA256 = "ef2e8d350891ba71699ac1e079658e697fc8d48c0f9253922990e907ecdade60"
  private const val FIXTURE_SOURCE_SHA256 = "45cddc9490be69345cbdab64ca583be65987e864ca408038e648db99e10516cf"
  private const val GENERATOR_SHA256 = "e2d1f046c00f4e7fd1bbaf6775ebd079266ba0e61f95c2ad5fdf8da8404f7a0b"
  private const val MAX_PSS_DELTA_BYTES = 134_217_728L
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
  private val PLAYBACK_SETTINGS = listOf(256 to 8, 512 to 15)
  private const val PLAYBACK_REVIEW_ASSET = "video_segmentation_playback_reviews.json"

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
    val baselinePssBytes: Long,
    val peakPssBytes: Long,
    val qualityPass: Boolean,
    val peakPssDeltaBytes: Long,
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
    val decoderName: String,
    val decodedSourceFrames: Int,
    val inputSamplesAdvanced: Int,
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
    val tasksVision = provenance.getJSONObject("tasksVision")
    val model = provenance.getJSONObject("model")
    val fixtureSource = provenance.getJSONObject("fixtureSource")
    val generator = provenance.getJSONObject("generator")
    val pomText = assets.open(tasksVision.getString("pomAsset")).bufferedReader().use { it.readText() }
    val observedPomDigest = assets.open(tasksVision.getString("pomAsset")).use(::sha256)
    val observedDependencyLicenseDigest = assets.open(tasksVision.getString("licenseAsset")).use(::sha256)
    val observedModelDigest = assets.open(model.getString("asset")).use(::sha256)
    val observedModelLicenseDigest = assets.open(model.getString("licenseAsset")).use(::sha256)
    val observedModelCardDigest = assets.open(model.getString("modelCardAsset")).use(::sha256)
    val observedFixtureLicenseDigest = assets.open(fixtureSource.getString("licenseAsset")).use(::sha256)
    val observedGeneratorDigest = assets.open(generator.getString("scriptAsset")).use(::sha256)
    val boundaries = mutableListOf<VideoSegmentationGateContracts.ProvenanceBoundary>()

    fun boundary(id: String, expected: String, observed: String) {
      boundaries += VideoSegmentationGateContracts.ProvenanceBoundary(id, expected, observed)
    }

    boundary("tasks.group", "com.google.mediapipe", tasksVision.getString("group"))
    boundary("tasks.artifact", "tasks-vision", tasksVision.getString("artifact"))
    boundary("tasks.version", TASKS_VISION_VERSION, tasksVision.getString("version"))
    boundary(
      "tasks.pomUrl",
      "https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/0.10.29/tasks-vision-0.10.29.pom",
      tasksVision.getString("pomUrl")
    )
    boundary("tasks.pomDigest", TASKS_VISION_POM_SHA256, observedPomDigest)
    boundary(
      "tasks.pomCoordinates",
      "com.google.mediapipe:tasks-vision:0.10.29",
      if (
        pomText.contains("<groupId>com.google.mediapipe</groupId>") &&
        pomText.contains("<artifactId>tasks-vision</artifactId>") &&
        pomText.contains("<version>0.10.29</version>")
      ) "com.google.mediapipe:tasks-vision:0.10.29" else "INVALID"
    )
    boundary("tasks.license", "Apache-2.0", tasksVision.getString("license"))
    boundary("tasks.licenseUrl", "https://www.apache.org/licenses/LICENSE-2.0.txt", tasksVision.getString("licenseUrl"))
    boundary("tasks.licenseDigest", APACHE_LICENSE_SHA256, observedDependencyLicenseDigest)
    boundary(
      "tasks.pomLicense",
      "Apache-2.0",
      if (pomText.contains("The Apache Software License, Version 2.0")) "Apache-2.0" else "INVALID"
    )

    boundary("model.asset", MODEL_ASSET, model.getString("asset"))
    boundary("model.version", "1", model.getString("version"))
    boundary(
      "model.downloadUrl",
      "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite",
      model.getString("downloadUrl")
    )
    boundary("model.digest", MODEL_SHA256, observedModelDigest)
    boundary("model.license", "Apache-2.0", model.getString("license"))
    boundary("model.licenseDigest", APACHE_LICENSE_SHA256, observedModelLicenseDigest)
    boundary("model.output", "single person-confidence mask", model.getString("output"))
    boundary(
      "model.modelCardUrl",
      "https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf",
      model.getString("modelCardUrl")
    )
    boundary("model.modelCardDigest", MODEL_CARD_SHA256, observedModelCardDigest)

    boundary("fixtureSource.personalMedia", "false", fixtureSource.getBoolean("personalMedia").toString())
    boundary(
      "fixtureSource.downloadUrl",
      "https://raw.githubusercontent.com/opencv/opencv/4.12.0/samples/data/vtest.avi",
      fixtureSource.getString("downloadUrl")
    )
    boundary("fixtureSource.digest", FIXTURE_SOURCE_SHA256, fixtureSource.getString("sha256"))
    boundary("fixtureSource.license", "Apache-2.0", fixtureSource.getString("license"))
    boundary(
      "fixtureSource.licenseUrl",
      "https://raw.githubusercontent.com/opencv/opencv/4.12.0/LICENSE",
      fixtureSource.getString("licenseUrl")
    )
    boundary("fixtureSource.licenseDigest", APACHE_LICENSE_SHA256, observedFixtureLicenseDigest)

    boundary("generator.script", "fixtures/generate-video-segmentation-fixtures.sh", generator.getString("script"))
    boundary("generator.scriptDigest", GENERATOR_SHA256, observedGeneratorDigest)
    boundary("generator.ffmpegVersion", "8.1.2-tessus", generator.getString("ffmpegVersion"))
    boundary("generator.archiveDigest", "e91df72a1ee7c26606f90dd2dd4dcccc6a75140ff9ea6fdd50faae828b82ba69", generator.getString("archiveSha256"))
    boundary("generator.binaryDigest", "60725ea0467ccaf900bf294d3567c302a802dc661f03bdde6aa7ecc9ccf05c4f", generator.getString("binarySha256"))

    val expectedFixtures = linkedMapOf(
      "one_person" to Pair(
        "video_segmentation_one_person_10s_720p.mp4",
        "4cae38ceb3c6ff8faab4026b5ff3cb8907ca381e4b44bec47815471e6446453f"
      ),
      "two_people_crossing_occluding" to Pair(
        "video_segmentation_two_crossing_10s_720p.mp4",
        "12c6173e83dc4ede1b4c016b16612df7b16932243c09cbe1d681a4c1735166d3"
      ),
      "fast_motion" to Pair(
        "video_segmentation_fast_motion_10s_720p.mp4",
        "f4ff899061b78f5fb204daf68ffe25289d1eba84528ed424e83255995d8b0f67"
      )
    )
    val fixtureRecords = provenance.getJSONArray("fixtures")
    val observedFixtures = JSONArray()
    val observedFixtureKeys = mutableListOf<String>()
    for (index in 0 until fixtureRecords.length()) {
      val fixture = fixtureRecords.getJSONObject(index)
      val id = fixture.getString("id")
      val asset = fixture.getString("asset")
      val observedDigest = assets.open(asset).use(::sha256)
      observedFixtureKeys += "$id|$asset|$observedDigest"
      observedFixtures.put(
        JSONObject(fixture.toString())
          .put("observedSha256", observedDigest)
          .put("digestMatches", observedDigest == fixture.getString("sha256"))
      )
    }
    val expectedFixtureKeys = expectedFixtures.map { (id, value) -> "$id|${value.first}|${value.second}" }
    boundary("fixtures.exactSet", expectedFixtureKeys.sorted().joinToString(","), observedFixtureKeys.sorted().joinToString(","))
    for ((id, value) in expectedFixtures) {
      val expectedKey = "$id|${value.first}|${value.second}"
      val observedKey = observedFixtureKeys.singleOrNull { it.startsWith("$id|") } ?: "MISSING_OR_DUPLICATE"
      boundary("fixture.$id", expectedKey, observedKey)
    }

    val allBoundariesMatch =
      VideoSegmentationGateContracts.provenanceBoundariesComplete(boundaries)
    val failedBoundaries = boundaries.filter { it.expected != it.observed }
    val boundariesJson = JSONArray().apply {
      boundaries.forEach {
        put(
          JSONObject()
            .put("id", it.id)
            .put("expected", it.expected)
            .put("observed", it.observed)
            .put("status", if (it.expected == it.observed) "PASS" else "FAIL")
        )
      }
    }

    return JSONObject()
      .put(
        "tasksVision",
        JSONObject(tasksVision.toString())
          .put("observedPomSha256", observedPomDigest)
          .put("observedLicenseSha256", observedDependencyLicenseDigest)
      )
      .put(
        "model",
        JSONObject(model.toString())
          .put("observedSha256", observedModelDigest)
          .put("digestMatches", observedModelDigest == MODEL_SHA256)
          .put("observedLicenseSha256", observedModelLicenseDigest)
          .put("observedModelCardSha256", observedModelCardDigest)
          .put("observedOutputSemantics", model.getString("output"))
      )
      .put(
        "fixtureSource",
        JSONObject(fixtureSource.toString())
          .put("observedLicenseSha256", observedFixtureLicenseDigest)
      )
      .put(
        "generator",
        JSONObject(generator.toString())
          .put("observedScriptSha256", observedGeneratorDigest)
      )
      .put("fixtures", observedFixtures)
      .put("provenanceBoundaries", boundariesJson)
      .put("failedBoundaries", JSONArray(failedBoundaries.map { it.id }))
      .put("modelProvenanceComplete", allBoundariesMatch)
      .put("allDigestsMatch", allBoundariesMatch)
      .put("allProvenanceBoundariesMatch", allBoundariesMatch)
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
    targetContext.filesDir.listFiles { file -> file.name.startsWith("video-segmentation-playback-") }
      ?.forEach(File::delete)

    val fixtureFiles = linkedMapOf<String, File>()
    for (fixture in fixtures) {
      val file = File(workDir, fixture.assetName)
      instrumentation.context.assets.open(fixture.assetName).use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
      }
      fixtureFiles[fixture.id] = file
    }

    val matrixResults = mutableListOf<MatrixResult>()
    val maskEvidence = JSONArray()
    val maskPlaybackEvidence = JSONArray()
    val cancellation: JSONObject

    try {
      for (workingSize in WORKING_SIZES) {
        for (maskFps in MASK_FPS) {
          val result = runConfiguration(
            instrumentation,
            targetContext,
            workingSize,
            maskFps,
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
      for ((workingSize, maskFps) in PLAYBACK_SETTINGS) {
        for (fixture in fixtures) {
          maskPlaybackEvidence.put(
            renderPlaybackEvidenceSequence(
              instrumentation,
              targetContext,
              fixture,
              checkNotNull(fixtureFiles[fixture.id]),
              workingSize,
              maskFps
            )
          )
        }
      }
    } finally {
      workDir.deleteRecursively()
    }

    val cancellationPass = cancellation.getString("status") == "PASS"
    val playbackReviews = resolvePlaybackReviews(instrumentation, maskPlaybackEvidence)
    val reviewPassBySetting = playbackReviewPassBySetting(playbackReviews)
    for (index in matrixResults.indices) {
      val result = matrixResults[index]
      val setting = result.contract.workingSize to result.contract.maskFps
      matrixResults[index] = result.copy(
        contract = result.contract.copy(playbackReviewPass = reviewPassBySetting[setting] == true)
      )
    }
    val matrixComplete = VideoSegmentationGateContracts.matrixComplete(
      matrixResults.map(MatrixResult::contract),
      maskEvidence.length()
    )
    for (result in matrixResults) {
      result.json
        .put("playbackReviewPass", result.contract.playbackReviewPass)
        .put(
          "accepted",
          VideoSegmentationGateContracts.videoIsolationAccepted(
            result.contract,
            cancellationPass,
            matrixComplete
          )
        )
    }
    val selected = VideoSegmentationGateContracts.selectSmallestAccepted(
      matrixResults.map(MatrixResult::contract),
      cancellationPass,
      matrixComplete
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
    val playbackReviewsComplete = playbackReviews.getBoolean("exactCurrentEvidenceSet") &&
      playbackReviews.getBoolean("allRecordsComplete")
    val artifactObservation = nativeArtifactObservation(instrumentation)
    val pageAlignmentPass = artifactObservation.optBoolean("arm64ApkPageAlignmentPass", false)
    val gatePass = provenancePass && matrixComplete && cancellationPass && videoIsolationAccepted &&
      pageAlignmentPass && playbackReviewsComplete

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
          .put("decoder", "single-pass MediaExtractor + MediaCodec flexible-YUV decoder; no seeks")
          .put("peakMemoryMetric", "baseline and peak android.os.Debug.getPss process PSS sampled every 20 ms; capability uses peak-minus-baseline delta")
          .put("pipelineRuntimeMetric", "single-pass decode + scale + VIDEO-mode inference + configured-FPS smoothing + motion-normalized mask metrics + ten contact-sheet panel overlays; PNG encoding excluded")
          .put("latencyMetric", "per-frame MediaPipe segmentForVideo wall-clock latency; sequential decode interval between sampled frames")
          .put("pssDeltaBudgetBytes", MAX_PSS_DELTA_BYTES)
          .put("pssDeltaBudgetSource", "Task 0.2 explicit 128 MiB process-PSS growth budget")
          .put(
            "qualityThresholds",
            JSONObject()
              .put("foregroundCoverageMeanMin", MIN_FOREGROUND_COVERAGE)
              .put("foregroundCoverageMeanMax", MAX_FOREGROUND_COVERAGE)
              .put("nonEmptyFrameRatioMin", MIN_NON_EMPTY_FRAME_RATIO)
              .put("motionCompensatedMaskIouMeanMin", MIN_TEMPORAL_IOU_MEAN)
              .put("motionNormalizedBoundaryPumpingP95Max", MAX_EDGE_PUMPING_P95)
              .put("motionNormalizedAreaPumpingP95Max", MAX_AREA_PUMPING_P95)
          )
      )
      .put("matrix", JSONArray().apply { matrixResults.forEach { put(it.json) } })
      .put("maskEvidence", maskEvidence)
      .put("maskPlaybackEvidence", maskPlaybackEvidence)
      .put("playbackReviews", playbackReviews)
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
                  "Smallest observed configuration passes all three fixture motion-aware quality thresholds, <=3x realtime, the 128 MiB PSS-delta budget, and cancellation cleanup."
                } else {
                  "No observed configuration passes all three fixture motion-aware quality thresholds, <=3x realtime, the 128 MiB PSS-delta budget, and cancellation cleanup; no production stub is permitted."
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
          .put("provenance", criterion(provenancePass, "Every pinned dependency/model/fixture/generator/license boundary and packaged SHA-256 digest matches"))
          .put("matrixComplete", criterion(matrixComplete, "All 256/384/512 x 8/12/15 sequential-decoder observations completed"))
          .put("nativePageAlignment", criterion(pageAlignmentPass, "Packaged arm64 MediaPipe ELF PT_LOAD and actual APK ZIP data offset are both 16 KiB aligned"))
          .put("cancellationCleanup", criterion(cancellationPass, "Cancellation stops work, closes MediaPipe/sequential-decoder resources, removes partial evidence, and permits follow-up inference"))
          .put("videoIsolation", criterion(videoIsolationAccepted, "A selected 10 s 720p configuration is <=3x realtime, under the 128 MiB peak-PSS-delta budget, and motion-aware quality-acceptable on every fixture"))
          .put(
            "playbackReview",
            criterion(
              playbackReviewsComplete,
              "Every current playback evidence archive has exactly one complete review record bound to its SHA-256"
            )
          )
      )
  }

  private fun runConfiguration(
    instrumentation: Instrumentation,
    context: Context,
    workingSize: Int,
    maskFps: Int,
    fixtureFiles: Map<String, File>,
    maskEvidence: JSONArray
  ): MatrixResult {
    val fixtureJson = JSONArray()
    val fixtureObservations = mutableListOf<FixtureObservation>()

    for (fixture in fixtures) {
      try {
        val observation = runFixture(
          instrumentation,
          context,
          fixture,
          checkNotNull(fixtureFiles[fixture.id]),
          workingSize,
          maskFps
        )
        fixtureObservations += observation
        fixtureJson.put(observation.json)
        maskEvidence.put(observation.evidence)
      } catch (error: Throwable) {
        val failure = VideoSegmentationGateContracts.fixtureFailure(fixture.id, error)
        fixtureJson.put(
          JSONObject()
            .put("id", failure.fixtureId)
            .put("status", "FAILED")
            .put(
              "error",
              JSONObject()
                .put("type", failure.type)
                .put("message", failure.message)
            )
        )
      }
    }

    val allFixturesCompleted = fixtureObservations.size == fixtures.size
    val qualityPass = allFixturesCompleted && fixtureObservations.all(FixtureObservation::qualityPass)
    val worstRuntimeMs = fixtureObservations.maxOfOrNull(FixtureObservation::runtimeMs) ?: FIXTURE_DURATION_MS * 3 + 1
    val peakPssBytes = fixtureObservations.maxOfOrNull(FixtureObservation::peakPssBytes) ?: Long.MAX_VALUE
    val worstPssDelta = fixtureObservations.maxByOrNull(FixtureObservation::peakPssDeltaBytes)
    val peakPssDeltaBytes = worstPssDelta?.peakPssDeltaBytes ?: Long.MAX_VALUE
    val baselinePssBytes = worstPssDelta?.baselinePssBytes ?: Long.MAX_VALUE
    val contract = VideoSegmentationGateContracts.MatrixObservation(
      workingSize = workingSize,
      maskFps = maskFps,
      runtimeMs = worstRuntimeMs,
      durationMs = FIXTURE_DURATION_MS,
      baselinePssBytes = baselinePssBytes,
      peakPssBytes = worstPssDelta?.peakPssBytes ?: Long.MAX_VALUE,
      peakPssDeltaBytes = peakPssDeltaBytes,
      pssDeltaBudgetBytes = MAX_PSS_DELTA_BYTES,
      qualityPass = qualityPass,
      complete = allFixturesCompleted,
      completedFixtureCount = fixtureObservations.size,
      playbackReviewPass = false,
      fixtureCount = fixtureJson.length()
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
      .put("baselinePssBytesForWorstDelta", if (baselinePssBytes == Long.MAX_VALUE) JSONObject.NULL else baselinePssBytes)
      .put("peakPssDeltaBytes", if (peakPssDeltaBytes == Long.MAX_VALUE) JSONObject.NULL else peakPssDeltaBytes)
      .put("pssDeltaBudgetBytes", MAX_PSS_DELTA_BYTES)
      .put("underPssDeltaBudget", peakPssDeltaBytes < MAX_PSS_DELTA_BYTES)
      .put("withinThreeTimesRealtime", worstRuntimeMs <= FIXTURE_DURATION_MS * 3)
      .put("fixtureCount", fixtureJson.length())
      .put("completedFixtureCount", fixtureObservations.size)
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
    val baselinePssBytes = currentPssBytes()
    val peakPssBytes = AtomicLong(baselinePssBytes)
    val sampling = AtomicBoolean(true)
    val sampler = thread(name = "segmentation-pss", isDaemon = true) {
      while (sampling.get()) {
        updateMax(peakPssBytes, currentPssBytes())
        SystemClock.sleep(20)
      }
      updateMax(peakPssBytes, currentPssBytes())
    }
    val startMs = SystemClock.elapsedRealtime()
    var decoder: SequentialVideoFrameDecoder? = null
    var segmenter: ImageSegmenter? = null
    val series: MeasuredMaskSeries
    val observedDurationMs: Long
    val observedWidth: Int
    val observedHeight: Int
    try {
      decoder = SequentialVideoFrameDecoder(source)
      observedDurationMs = decoder.durationMs
      observedWidth = decoder.width
      observedHeight = decoder.height
      segmenter = createSegmenter(instrumentation, context)
      series = measureMaskSeries(decoder, segmenter, workingSize, maskFps)
    } finally {
      runCatching { segmenter?.close() }
      runCatching { decoder?.close() }
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
    val peak = peakPssBytes.get()
    val peakDelta = max(0L, peak - baselinePssBytes)
    val json = JSONObject()
      .put("id", fixture.id)
      .put("status", "COMPLETED")
      .put("sourceSha256", sha256(source))
      .put("sourceBytes", source.length())
      .put("observedDurationMs", observedDurationMs)
      .put("observedWidth", observedWidth)
      .put("observedHeight", observedHeight)
      .put("decoder", "MediaExtractor+MediaCodec sequential")
      .put("decoderName", series.decoderName)
      .put("extractorSeekCount", 0)
      .put("inputSamplesAdvanced", series.inputSamplesAdvanced)
      .put("decodedSourceFrames", series.decodedSourceFrames)
      .put("framesProcessed", series.framesProcessed)
      .put("maskWidth", series.maskWidth)
      .put("maskHeight", series.maskHeight)
      .put("confidenceMaskCount", series.confidenceMaskCount)
      .put("pipelineRuntimeMs", runtimeMs)
      .put("runtimeXRealtime", runtimeMs.toDouble() / FIXTURE_DURATION_MS)
      .put("throughputFramesPerSecond", series.framesProcessed * 1000.0 / runtimeMs)
      .put("inferenceLatencyMs", latencyJson(series.inferenceLatenciesMs))
      .put("sequentialDecodeIntervalMs", latencyJson(series.decodeLatenciesMs))
      .put("baselinePssBytes", baselinePssBytes)
      .put("peakPssBytes", peak)
      .put("peakPssDeltaBytes", peakDelta)
      .put("pssDeltaBudgetBytes", MAX_PSS_DELTA_BYTES)
      .put("underPssDeltaBudget", peakDelta < MAX_PSS_DELTA_BYTES)
      .put("quality", metrics)
      .put("evidenceFileName", evidence.getString("fileName"))
    return FixtureObservation(
      json = json,
      runtimeMs = runtimeMs,
      baselinePssBytes = baselinePssBytes,
      peakPssBytes = peak,
      qualityPass = qualityPass,
      peakPssDeltaBytes = peakDelta,
      evidence = evidence
    )
  }

  private fun measureMaskSeries(
    decoder: SequentialVideoFrameDecoder,
    segmenter: ImageSegmenter,
    workingSize: Int,
    maskFps: Int
  ): MeasuredMaskSeries {
    val schedule = VideoSegmentationGateContracts.evidenceSchedule(maskFps, durationSeconds = 10)
    val frameCount = schedule.size
    val inferenceLatencies = ArrayList<Double>(frameCount)
    val decodeLatencies = ArrayList<Double>(frameCount)
    val presentationOffsetsMs = ArrayList<Double>(frameCount)
    val coverage = ArrayList<Double>(frameCount)
    val rawTemporalIous = ArrayList<Double>(frameCount - 1)
    val motionCompensatedIous = ArrayList<Double>(frameCount - 1)
    val normalizedBoundaryPumping = ArrayList<Double>(frameCount - 1)
    val normalizedAreaPumping = ArrayList<Double>(frameCount - 1)
    var smoothed: FloatArray? = null
    var previousStats: BinaryStats? = null
    var previousNormalizedStats: BinaryStats? = null
    var maskWidth = 0
    var maskHeight = 0
    var confidenceMaskCount = 0
    var nonEmptyFrames = 0
    var processedFrames = 0
    val evidenceHeight = scaledHeight(workingSize)
    val evidenceSheet = Bitmap.createBitmap(workingSize * 5, evidenceHeight * 2, Bitmap.Config.ARGB_8888)
    val evidenceCanvas = Canvas(evidenceSheet).apply { drawColor(Color.BLACK) }
    val evidenceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = max(12f, workingSize / 18f)
      setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    try {
      decoder.decodeFrames(
        targetTimestampsMs = schedule.map { it.timestampMs },
        targetWidth = workingSize,
        targetHeight = evidenceHeight,
        shouldCancel = { false }
      ) { frame ->
        val evidenceFrame = schedule[processedFrames]
        decodeLatencies += frame.decodeIntervalMs
        presentationOffsetsMs += (frame.presentationTimeUs / 1000.0) - frame.targetTimestampMs
        var sourceCopy: Bitmap? = evidenceFrame.panelSecond?.let {
          frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
          val inferenceStartNs = SystemClock.elapsedRealtimeNanos()
          val extracted = segmentFrame(segmenter, frame.bitmap, frame.targetTimestampMs)
          inferenceLatencies += nanosToMillis(SystemClock.elapsedRealtimeNanos() - inferenceStartNs)
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
            var overlay: Bitmap? = null
            try {
              overlay = overlayMask(sourceCopy, currentSmoothed, extracted.width, extracted.height)
              val left = (panelSecond % 5) * workingSize
              val top = (panelSecond / 5) * evidenceHeight
              evidenceCanvas.drawBitmap(overlay, left.toFloat(), top.toFloat(), null)
              evidenceCanvas.drawText(
                "${panelSecond}s",
                left + 6f,
                top + evidenceLabelPaint.textSize + 4f,
                evidenceLabelPaint
              )
            } finally {
              overlay?.let { if (!it.isRecycled) it.recycle() }
            }
          }

          val stats = binaryStats(currentSmoothed, extracted.width, extracted.height)
          val normalized = VideoSegmentationGateContracts.motionNormalizedMask(
            stats.binary,
            extracted.width,
            extracted.height
          )
          val normalizedStats = binaryStats(normalized, 64, 64)
          val totalPixels = max(1, extracted.width * extracted.height)
          coverage += stats.foregroundPixels.toDouble() / totalPixels
          if (stats.foregroundPixels > 0) nonEmptyFrames++

          val priorStats = previousStats
          val priorNormalized = previousNormalizedStats
          if (priorStats != null && priorNormalized != null) {
            rawTemporalIous += VideoSegmentationGateContracts.binaryIou(priorStats.binary, stats.binary)
            motionCompensatedIous +=
              VideoSegmentationGateContracts.binaryIou(priorNormalized.binary, normalizedStats.binary)
            normalizedBoundaryPumping += relativeChange(priorNormalized.edgePixels, normalizedStats.edgePixels)
            normalizedAreaPumping +=
              relativeChange(priorNormalized.foregroundPixels, normalizedStats.foregroundPixels)
          }
          previousStats = stats
          previousNormalizedStats = normalizedStats
          processedFrames++
        } finally {
          sourceCopy?.let { if (!it.isRecycled) it.recycle() }
          if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
          sourceCopy = null
        }
      }

      val coverageMean = coverage.average()
      val motionCompensatedIouMean = motionCompensatedIous.averageOrZero()
      val boundaryPumpingP95 = percentile(normalizedBoundaryPumping, 0.95)
      val areaPumpingP95 = percentile(normalizedAreaPumping, 0.95)
      val nonEmptyFrameRatio = nonEmptyFrames.toDouble() / frameCount
      val coveragePass = coverageMean in MIN_FOREGROUND_COVERAGE..MAX_FOREGROUND_COVERAGE
      val nonEmptyPass = nonEmptyFrameRatio >= MIN_NON_EMPTY_FRAME_RATIO
      val temporalIouPass = motionCompensatedIouMean >= MIN_TEMPORAL_IOU_MEAN
      val boundaryPumpingPass = boundaryPumpingP95 <= MAX_EDGE_PUMPING_P95
      val areaPumpingPass = areaPumpingP95 <= MAX_AREA_PUMPING_P95
      val qualityPass =
        coveragePass && nonEmptyPass && temporalIouPass && boundaryPumpingPass && areaPumpingPass

      val metrics = JSONObject()
        .put("foregroundCoverageMean", coverageMean)
        .put("foregroundCoverageMin", coverage.minOrNull() ?: 0.0)
        .put("foregroundCoverageMax", coverage.maxOrNull() ?: 0.0)
        .put("nonEmptyFrameRatio", nonEmptyFrameRatio)
        .put("motionCompensation", "translate and scale each binary mask bounding box into a 64x64 normalized coordinate space")
        .put("motionCompensatedMaskIouMean", motionCompensatedIouMean)
        .put("motionCompensatedMaskIouP05", percentile(motionCompensatedIous, 0.05))
        .put("motionNormalizedBoundaryPumpingMean", normalizedBoundaryPumping.averageOrZero())
        .put("motionNormalizedBoundaryPumpingP95", boundaryPumpingP95)
        .put("motionNormalizedAreaPumpingMean", normalizedAreaPumping.averageOrZero())
        .put("motionNormalizedAreaPumpingP95", areaPumpingP95)
        .put("rawTemporalMaskIouMeanDiagnosticOnly", rawTemporalIous.averageOrZero())
        .put("presentationTimestampOffsetMs", latencyJson(presentationOffsetsMs))
        .put("foregroundCoveragePass", coveragePass)
        .put("nonEmptyFramesPass", nonEmptyPass)
        .put("motionCompensatedTemporalStabilityPass", temporalIouPass)
        .put("normalPlaybackNoObviousEdgePumping", boundaryPumpingPass)
        .put("motionNormalizedAreaStabilityPass", areaPumpingPass)
        .put("qualityPass", qualityPass)
      return MeasuredMaskSeries(
        metrics = metrics,
        inferenceLatenciesMs = inferenceLatencies,
        decodeLatenciesMs = decodeLatencies,
        framesProcessed = processedFrames,
        maskWidth = maskWidth,
        maskHeight = maskHeight,
        confidenceMaskCount = confidenceMaskCount,
        decoderName = decoder.decoderName,
        decodedSourceFrames = decoder.decodedSourceFrames,
        inputSamplesAdvanced = decoder.inputSamplesAdvanced,
        evidenceSheet = evidenceSheet
      )
    } catch (error: Throwable) {
      if (!evidenceSheet.isRecycled) evidenceSheet.recycle()
      throw error
    }
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
      try {
        file.outputStream().use { output ->
          check(sheet.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not write $fileName" }
        }
      } catch (error: Throwable) {
        file.delete()
        throw error
      }
    } finally {
      if (!sheet.isRecycled) sheet.recycle()
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

  private fun renderPlaybackEvidenceSequence(
    instrumentation: Instrumentation,
    context: Context,
    fixture: FixtureSpec,
    source: File,
    workingSize: Int,
    maskFps: Int
  ): JSONObject {
    val schedule = VideoSegmentationGateContracts.evidenceSchedule(maskFps, durationSeconds = 10)
    val fileName = "video-segmentation-playback-${fixture.id}-${workingSize}-${maskFps}fps.zip"
    val file = File(context.filesDir, fileName).apply { delete() }
    val startMs = SystemClock.elapsedRealtime()
    var decoder: SequentialVideoFrameDecoder? = null
    var segmenter: ImageSegmenter? = null
    var framesWritten = 0
    try {
      decoder = SequentialVideoFrameDecoder(source)
      segmenter = createSegmenter(instrumentation, context)
      ZipOutputStream(file.outputStream().buffered()).use { zip ->
        var smoothed: FloatArray? = null
        decoder.decodeFrames(
          targetTimestampsMs = schedule.map { it.timestampMs },
          targetWidth = workingSize,
          targetHeight = scaledHeight(workingSize),
          shouldCancel = { false }
        ) { frame ->
          var sourceCopy: Bitmap? = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
          var overlay: Bitmap? = null
          try {
            val extracted = segmentFrame(checkNotNull(segmenter), frame.bitmap, frame.targetTimestampMs)
            val prior = smoothed
            val current = if (prior == null) {
              extracted.values.copyOf()
            } else {
              FloatArray(extracted.values.size) { index ->
                SMOOTHING_CURRENT_WEIGHT * extracted.values[index] +
                  (1f - SMOOTHING_CURRENT_WEIGHT) * prior[index]
              }
            }
            smoothed = current
            overlay = overlayMask(checkNotNull(sourceCopy), current, extracted.width, extracted.height)
            zip.putNextEntry(
              VideoSegmentationGateContracts.deterministicZipEntry(
                "frame_${framesWritten.toString().padStart(4, '0')}.jpg"
              )
            )
            check(overlay.compress(Bitmap.CompressFormat.JPEG, 75, zip)) {
              "Could not encode playback frame $framesWritten"
            }
            zip.closeEntry()
            framesWritten++
          } finally {
            overlay?.let { if (!it.isRecycled) it.recycle() }
            sourceCopy?.let { if (!it.isRecycled) it.recycle() }
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            sourceCopy = null
          }
        }
        val manifest = JSONObject()
          .put("fixtureId", fixture.id)
          .put("workingSize", workingSize)
          .put("playbackFps", maskFps)
          .put("frameCount", framesWritten)
          .put("durationMs", FIXTURE_DURATION_MS)
          .put("framePattern", "frame_%04d.jpg")
        zip.putNextEntry(VideoSegmentationGateContracts.deterministicZipEntry("manifest.json"))
        zip.write(manifest.toString(2).toByteArray())
        zip.closeEntry()
      }
      check(framesWritten == schedule.size)
      return JSONObject()
        .put("fixtureId", fixture.id)
        .put("workingSize", workingSize)
        .put("maskFps", maskFps)
        .put("fileName", fileName)
        .put("sha256", sha256(file))
        .put("bytes", file.length())
        .put("frameCount", framesWritten)
        .put("durationMs", FIXTURE_DURATION_MS)
        .put("zipEntryTimestampMs", VideoSegmentationGateContracts.FIXED_ZIP_ENTRY_TIME_MS)
        .put("generationRuntimeMs", SystemClock.elapsedRealtime() - startMs)
        .put("format", "ZIP JPEG frame sequence with manifest.json")
        .put("playbackCommand", "ffmpeg -framerate $maskFps -i frame_%04d.jpg -c:v libx264 -pix_fmt yuv420p evidence.mp4")
        .put("visualEncoding", "source RGB with full-cadence EMA-smoothed >=0.5 mask tinted green and boundary red")
    } catch (error: Throwable) {
      file.delete()
      throw error
    } finally {
      runCatching { segmenter?.close() }
      runCatching { decoder?.close() }
    }
  }

  fun generatePlaybackEvidence(instrumentation: Instrumentation): JSONArray {
    val targetContext = instrumentation.targetContext
    val workDir = File(targetContext.cacheDir, "video_segmentation_playback_evidence").apply {
      deleteRecursively()
      check(mkdirs()) { "Could not create $absolutePath" }
    }
    targetContext.filesDir.listFiles { file -> file.name.startsWith("video-segmentation-playback-") }
      ?.forEach(File::delete)
    val evidence = JSONArray()
    try {
      val fixtureFiles = linkedMapOf<String, File>()
      for (fixture in fixtures) {
        val file = File(workDir, fixture.assetName)
        instrumentation.context.assets.open(fixture.assetName).use { input ->
          file.outputStream().use { output -> input.copyTo(output) }
        }
        fixtureFiles[fixture.id] = file
      }
      for ((workingSize, maskFps) in PLAYBACK_SETTINGS) {
        for (fixture in fixtures) {
          evidence.put(
            renderPlaybackEvidenceSequence(
              instrumentation,
              targetContext,
              fixture,
              checkNotNull(fixtureFiles[fixture.id]),
              workingSize,
              maskFps
            )
          )
        }
      }
    } finally {
      workDir.deleteRecursively()
    }
    return evidence
  }

  private fun resolvePlaybackReviews(
    instrumentation: Instrumentation,
    playbackEvidence: JSONArray
  ): JSONObject {
    val manifest = JSONObject(
      instrumentation.context.assets.open(PLAYBACK_REVIEW_ASSET).bufferedReader().use { it.readText() }
    )
    val reviewArray = manifest.getJSONArray("reviews")
    val reviewsByFile = linkedMapOf<String, JSONObject>()
    var duplicateReviewFiles = 0
    for (index in 0 until reviewArray.length()) {
      val review = reviewArray.getJSONObject(index)
      if (reviewsByFile.put(review.getString("fileName"), review) != null) duplicateReviewFiles++
    }

    val records = JSONArray()
    val evidenceFiles = linkedSetOf<String>()
    var allRecordsComplete = duplicateReviewFiles == 0
    var allReviewsPass = playbackEvidence.length() > 0
    for (index in 0 until playbackEvidence.length()) {
      val evidence = playbackEvidence.getJSONObject(index)
      val fileName = evidence.getString("fileName")
      evidenceFiles += fileName
      val review = reviewsByFile[fileName]
      val verdict = review?.getString("verdict") ?: "UNREVIEWED"
      val defects = review?.optJSONArray("observedDefects") ?: JSONArray()
      val reviewedSha256 = review?.optString("sha256").orEmpty()
      val evidenceSha256 = evidence.getString("sha256")
      val recordComplete = review != null &&
        VideoSegmentationGateContracts.playbackReviewRecordComplete(verdict, defects.length())
      val reviewPass = recordComplete &&
        VideoSegmentationGateContracts.playbackReviewPass(evidenceSha256, reviewedSha256, verdict)
      if (!recordComplete) allRecordsComplete = false
      if (!reviewPass) allReviewsPass = false
      records.put(
        JSONObject()
          .put("fileName", fileName)
          .put("fixtureId", evidence.getString("fixtureId"))
          .put("workingSize", evidence.getInt("workingSize"))
          .put("maskFps", evidence.getInt("maskFps"))
          .put("evidenceSha256", evidenceSha256)
          .put("reviewedEvidenceSha256", reviewedSha256)
          .put("hashBound", evidenceSha256 == reviewedSha256)
          .put("verdict", verdict)
          .put("observedDefects", defects)
          .put("recordComplete", recordComplete)
          .put("reviewPass", reviewPass)
      )
    }

    return JSONObject()
      .put("schemaVersion", manifest.optInt("schemaVersion", 0))
      .put("reviewedAtUtc", manifest.optString("reviewedAtUtc"))
      .put("reviewMethod", manifest.optString("reviewMethod"))
      .put("personalMedia", manifest.optBoolean("personalMedia", false))
      .put("reviewedFileCount", reviewArray.length())
      .put("duplicateReviewFiles", duplicateReviewFiles)
      .put(
        "exactCurrentEvidenceSet",
        duplicateReviewFiles == 0 &&
          reviewArray.length() == playbackEvidence.length() &&
          reviewsByFile.keys == evidenceFiles
      )
      .put("allRecordsComplete", allRecordsComplete)
      .put("allReviewsPass", allReviewsPass)
      .put("records", records)
  }

  private fun playbackReviewPassBySetting(
    playbackReviews: JSONObject
  ): Map<Pair<Int, Int>, Boolean> {
    val records = playbackReviews.getJSONArray("records")
    val totals = mutableMapOf<Pair<Int, Int>, Int>()
    val passes = mutableMapOf<Pair<Int, Int>, Int>()
    for (index in 0 until records.length()) {
      val record = records.getJSONObject(index)
      val setting = record.getInt("workingSize") to record.getInt("maskFps")
      totals[setting] = (totals[setting] ?: 0) + 1
      if (record.getBoolean("reviewPass")) passes[setting] = (passes[setting] ?: 0) + 1
    }
    return totals.mapValues { (setting, total) ->
      total == VideoSegmentationGateContracts.REQUIRED_FIXTURE_COUNT &&
        passes[setting] == VideoSegmentationGateContracts.REQUIRED_FIXTURE_COUNT
    }
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
    val decoderClosed = AtomicBoolean(false)
    val framesProcessed = AtomicInteger(0)
    val cancellationSchedule = VideoSegmentationGateContracts.evidenceSchedule(15, 10)
      .map { it.timestampMs }
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit {
      var decoder: SequentialVideoFrameDecoder? = null
      var segmenter: ImageSegmenter? = null
      try {
        decoder = SequentialVideoFrameDecoder(source)
        segmenter = createSegmenter(instrumentation, context)
        partial.writeText("partial segmentation evidence")
        decoder.decodeFrames(
          targetTimestampsMs = cancellationSchedule,
          targetWidth = 512,
          targetHeight = scaledHeight(512),
          shouldCancel = { cancel.get() }
        ) { frame ->
          try {
            segmentFrame(checkNotNull(segmenter), frame.bitmap, frame.targetTimestampMs)
            framesProcessed.incrementAndGet()
            activeLatch.countDown()
          } finally {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
          }
        }
      } finally {
        runCatching { segmenter?.close() }
        segmenterClosed.set(true)
        runCatching { decoder?.close() }
        decoderClosed.set(true)
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
      SequentialVideoFrameDecoder(source).use { decoder ->
        val segmenter = createSegmenter(instrumentation, context)
        try {
          var maskObserved = false
          decoder.decodeFrames(listOf(0L), 256, scaledHeight(256), { false }) { frame ->
            try {
              maskObserved = segmentFrame(segmenter, frame.bitmap, frame.targetTimestampMs).values.isNotEmpty()
            } finally {
              if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            }
          }
          maskObserved
        } finally {
          segmenter.close()
        }
      }
    } catch (_: Throwable) {
      false
    }
    val leftovers = workDir.listFiles { file -> file.name.startsWith("cancel-") }?.size ?: 0
    val partialDeleted = !partial.exists()
    val framesProcessedTotal = framesProcessed.get()
    val workerErrorAbsent = workerError == null
    val cancellationObserved = reachedActiveWork &&
      framesProcessedTotal in 1 until cancellationSchedule.size
    val pass = VideoSegmentationGateContracts.cancellationCleanupPass(
      activeBeforeCancel,
      cancelIssued,
      cancellationObserved,
      workerErrorAbsent,
      workerStopped,
      segmenterClosed.get(),
      decoderClosed.get(),
      partialDeleted,
      followUpSucceeded,
      leftovers
    )
    return JSONObject()
      .put("status", if (pass) "PASS" else "FAIL")
      .put("activeBeforeCancel", activeBeforeCancel)
      .put("cancelIssued", cancelIssued)
      .put("cancellationObserved", cancellationObserved)
      .put("scheduledFrameCount", cancellationSchedule.size)
      .put("framesProcessedBeforeCancel", framesProcessedTotal)
      .put("cancelLatencyMs", cancelLatencyMs)
      .put("workerStopped", workerStopped)
      .put("workerErrorAbsent", workerErrorAbsent)
      .put("segmenterClosed", segmenterClosed.get())
      .put("sequentialDecoderClosed", decoderClosed.get())
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

  private fun binaryStats(mask: FloatArray, width: Int, height: Int): BinaryStats =
    binaryStats(BooleanArray(mask.size) { mask[it] >= MASK_THRESHOLD }, width, height)

  private fun binaryStats(binary: BooleanArray, width: Int, height: Int): BinaryStats {
    check(binary.size == width * height)
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


  private fun nativeArtifactObservation(instrumentation: Instrumentation): JSONObject {
    val libraryName = "libmediapipe_tasks_vision_jni.so"
    val apkPaths = listOf(
      instrumentation.context.applicationInfo.sourceDir,
      instrumentation.targetContext.applicationInfo.sourceDir
    ).distinct()
    val packaged = JSONArray()
    var arm64Alignments = emptyList<Long>()
    var arm64ZipDataOffset = -1L
    var arm64ApkSha256: String? = null
    for (apkPath in apkPaths) {
      val apkFile = File(apkPath)
      val apkSha256 = sha256(apkFile)
      ZipFile(apkFile).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (!entry.name.endsWith("/$libraryName")) continue
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          val alignments = elfLoadAlignments(bytes)
          val dataOffset = zipEntryDataOffset(apkFile, entry.name)
          val pagePass = VideoSegmentationGateContracts.pageAlignmentPass(alignments, dataOffset)
          if (entry.name == "lib/arm64-v8a/$libraryName") {
            arm64Alignments = alignments
            arm64ZipDataOffset = dataOffset
            arm64ApkSha256 = apkSha256
          }
          packaged.put(
            JSONObject()
              .put("apk", apkFile.name)
              .put("apkSha256", apkSha256)
              .put("entry", entry.name)
              .put("abi", entry.name.split('/').getOrNull(1) ?: "unknown")
              .put("bytes", bytes.size)
              .put("zipMethod", if (entry.method == ZipEntry.STORED) "STORED" else "DEFLATED")
              .put("zipDataOffsetBytes", dataOffset)
              .put("zipDataOffsetModulo16384", if (dataOffset >= 0) dataOffset % 16_384L else JSONObject.NULL)
              .put("zipDataOffsetAligned16KiB", dataOffset >= 0 && dataOffset % 16_384L == 0L)
              .put("elfLoadSegmentAlignmentsBytes", JSONArray(alignments))
              .put("elfSupports16KiBPages", alignments.isNotEmpty() && alignments.all { it >= 16_384L })
              .put("apkPageAlignmentPass", pagePass)
          )
        }
      }
    }
    val installedLibrary = apkPaths.asSequence()
      .map { File(it).parentFile?.resolve("lib/arm64/$libraryName") }
      .filterNotNull()
      .firstOrNull(File::isFile)
    val arm64PagePass =
      VideoSegmentationGateContracts.pageAlignmentPass(arm64Alignments, arm64ZipDataOffset)
    return JSONObject()
      .put("tasksVisionArtifactVersion", TASKS_VISION_VERSION)
      .put("runtimeClassPresent", runCatching { Class.forName("com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter") }.isSuccess)
      .put("packagedNativeLibraries", packaged)
      .put("packagedAbis", JSONArray(packagedAbis(packaged)))
      .put("deviceSupportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
      .put("devicePageSizeBytes", Os.sysconf(OsConstants._SC_PAGESIZE))
      .put("arm64ApkSha256", arm64ApkSha256 ?: JSONObject.NULL)
      .put("arm64ElfLoadSegmentAlignmentsBytes", JSONArray(arm64Alignments))
      .put("arm64ElfSupports16KiBPages", arm64Alignments.isNotEmpty() && arm64Alignments.all { it >= 16_384L })
      .put("arm64ZipDataOffsetBytes", arm64ZipDataOffset)
      .put("arm64ZipDataOffsetModulo16384", if (arm64ZipDataOffset >= 0) arm64ZipDataOffset % 16_384L else JSONObject.NULL)
      .put("arm64ZipDataOffsetAligned16KiB", arm64ZipDataOffset >= 0 && arm64ZipDataOffset % 16_384L == 0L)
      .put("arm64ApkPageAlignmentPass", arm64PagePass)
      .put("installedArm64LibraryObserved", installedLibrary?.absolutePath ?: JSONObject.NULL)
  }

  private fun zipEntryDataOffset(apk: File, targetEntry: String): Long {
    RandomAccessFile(apk, "r").use { file ->
      val tailSize = min(file.length(), 65_557L).toInt()
      val tail = ByteArray(tailSize)
      file.seek(file.length() - tailSize)
      file.readFully(tail)
      var eocdIndex = tailSize - 22
      while (eocdIndex >= 0) {
        if (
          tail[eocdIndex] == 0x50.toByte() &&
          tail[eocdIndex + 1] == 0x4b.toByte() &&
          tail[eocdIndex + 2] == 0x05.toByte() &&
          tail[eocdIndex + 3] == 0x06.toByte()
        ) break
        eocdIndex--
      }
      if (eocdIndex < 0) return -1L
      val entryCount = littleUnsignedShort(tail, eocdIndex + 10)
      var centralOffset = littleUnsignedInt(tail, eocdIndex + 16)
      repeat(entryCount) {
        file.seek(centralOffset)
        val header = ByteArray(46)
        file.readFully(header)
        if (littleUnsignedInt(header, 0) != 0x02014b50L) return -1L
        val nameLength = littleUnsignedShort(header, 28)
        val extraLength = littleUnsignedShort(header, 30)
        val commentLength = littleUnsignedShort(header, 32)
        val localHeaderOffset = littleUnsignedInt(header, 42)
        val nameBytes = ByteArray(nameLength)
        file.readFully(nameBytes)
        val entryName = nameBytes.toString(Charsets.UTF_8)
        if (entryName == targetEntry) {
          file.seek(localHeaderOffset)
          val localHeader = ByteArray(30)
          file.readFully(localHeader)
          if (littleUnsignedInt(localHeader, 0) != 0x04034b50L) return -1L
          val localNameLength = littleUnsignedShort(localHeader, 26)
          val localExtraLength = littleUnsignedShort(localHeader, 28)
          return localHeaderOffset + 30L + localNameLength + localExtraLength
        }
        centralOffset += 46L + nameLength + extraLength + commentLength
      }
      return -1L
    }
  }

  private fun littleUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

  private fun littleUnsignedInt(bytes: ByteArray, offset: Int): Long =
    littleUnsignedShort(bytes, offset).toLong() or
      (littleUnsignedShort(bytes, offset + 2).toLong() shl 16)

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
