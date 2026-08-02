package expo.modules.memegetbg

import java.util.zip.ZipEntry

object VideoSegmentationGateContracts {
  const val REQUIRED_FIXTURE_COUNT = 3
  const val MAX_REALTIME_MULTIPLIER = 3L
  const val FIXED_ZIP_ENTRY_TIME_MS = 315_532_800_000L

  val EXPECTED_PROVENANCE_BOUNDARY_IDS = setOf(
    "tasks.group",
    "tasks.artifact",
    "tasks.version",
    "tasks.pomUrl",
    "tasks.pomDigest",
    "tasks.pomCoordinates",
    "tasks.license",
    "tasks.licenseUrl",
    "tasks.licenseDigest",
    "tasks.pomLicense",
    "model.asset",
    "model.version",
    "model.downloadUrl",
    "model.digest",
    "model.license",
    "model.licenseUrl",
    "model.licenseDigest",
    "model.output",
    "model.modelCardUrl",
    "model.modelCardDigest",
    "fixtureSource.personalMedia",
    "fixtureSource.downloadUrl",
    "fixtureSource.digest",
    "fixtureSource.license",
    "fixtureSource.licenseUrl",
    "fixtureSource.licenseDigest",
    "generator.script",
    "generator.scriptDigest",
    "generator.ffmpegVersion",
    "generator.downloadUrl",
    "generator.archiveDigest",
    "generator.binaryDigest",
    "fixtures.exactSet",
    "fixture.one_person",
    "fixture.two_people_crossing_occluding",
    "fixture.fast_motion"
  )

  data class MatrixObservation(
    val workingSize: Int,
    val maskFps: Int,
    val runtimeMs: Long,
    val durationMs: Long,
    val baselinePssBytes: Long,
    val peakPssBytes: Long,
    val peakPssDeltaBytes: Long,
    val pssDeltaBudgetBytes: Long,
    val qualityPass: Boolean,
    val complete: Boolean,
    val completedFixtureCount: Int,
    val playbackReviewPass: Boolean,
    val fixtureCount: Int
  )

  data class EvidenceFrame(
    val timestampMs: Long,
    val panelSecond: Int?
  )

  data class ProvenanceBoundary(
    val id: String,
    val expected: String,
    val observed: String
  )

  data class FixtureFailure(
    val fixtureId: String,
    val type: String,
    val message: String
  )

  fun evidenceSchedule(maskFps: Int, durationSeconds: Int): List<EvidenceFrame> {
    require(maskFps > 0)
    require(durationSeconds > 0)
    return List(maskFps * durationSeconds) { frameIndex ->
      EvidenceFrame(
        timestampMs = frameIndex * 1000L / maskFps,
        panelSecond = if (frameIndex % maskFps == 0) frameIndex / maskFps else null
      )
    }
  }

  fun provenanceComplete(
    version: String,
    downloadUrl: String,
    license: String,
    licenseUrl: String,
    sha256: String
  ): Boolean =
    version.isNotBlank() &&
      !version.contains("latest", ignoreCase = true) &&
      downloadUrl.startsWith("https://") &&
      !downloadUrl.contains("/latest/", ignoreCase = true) &&
      license.isNotBlank() &&
      licenseUrl.startsWith("https://") &&
      sha256.matches(Regex("^[0-9a-f]{64}$"))

  fun videoIsolationAccepted(
    observation: MatrixObservation,
    cancellationCleanupPass: Boolean,
    matrixComplete: Boolean
  ): Boolean =
    matrixComplete &&
      observation.complete &&
      observation.durationMs > 0L &&
      observation.runtimeMs <= observation.durationMs * MAX_REALTIME_MULTIPLIER &&
      observation.peakPssDeltaBytes < observation.pssDeltaBudgetBytes &&
      observation.qualityPass &&
      observation.playbackReviewPass &&
      observation.fixtureCount == REQUIRED_FIXTURE_COUNT &&
      observation.completedFixtureCount == REQUIRED_FIXTURE_COUNT &&
      cancellationCleanupPass

  fun selectSmallestAccepted(
    observations: List<MatrixObservation>,
    cancellationCleanupPass: Boolean,
    matrixComplete: Boolean
  ): MatrixObservation? =
    observations
      .asSequence()
      .filter { videoIsolationAccepted(it, cancellationCleanupPass, matrixComplete) }
      .sortedWith(compareBy<MatrixObservation> { it.workingSize }.thenBy { it.maskFps })
      .firstOrNull()

  fun matrixComplete(observations: List<MatrixObservation>, evidenceCount: Int): Boolean {
    val expectedSettings = setOf(
      256 to 8,
      256 to 12,
      256 to 15,
      384 to 8,
      384 to 12,
      384 to 15,
      512 to 8,
      512 to 12,
      512 to 15
    )
    return observations.map { it.workingSize to it.maskFps }.toSet() == expectedSettings &&
      observations.size == expectedSettings.size &&
      observations.all {
        it.complete &&
          it.fixtureCount == REQUIRED_FIXTURE_COUNT &&
          it.completedFixtureCount == REQUIRED_FIXTURE_COUNT
      } &&
      evidenceCount == expectedSettings.size * REQUIRED_FIXTURE_COUNT
  }

  fun playbackReviewPass(
    evidenceSha256: String,
    reviewedEvidenceSha256: String,
    verdict: String
  ): Boolean =
    evidenceSha256.matches(Regex("^[0-9a-f]{64}$")) &&
      evidenceSha256 == reviewedEvidenceSha256 &&
      verdict == "PASS"

  fun playbackReviewRecordComplete(verdict: String, defectsCount: Int): Boolean =
    verdict == "PASS" || (verdict == "FAIL" && defectsCount > 0)

  fun cleanupAll(primaryFailure: Throwable?, actions: List<() -> Unit>): Throwable? {
    var failure = primaryFailure
    for (action in actions) {
      try {
        action()
      } catch (cleanupFailure: Throwable) {
        if (failure == null) {
          failure = cleanupFailure
        } else {
          failure.addSuppressed(cleanupFailure)
        }
      }
    }
    return failure
  }

  fun deterministicZipEntry(name: String): ZipEntry =
    ZipEntry(name).apply { time = FIXED_ZIP_ENTRY_TIME_MS }

  fun cancellationCleanupPass(
    activeBeforeCancel: Boolean,
    cancelIssued: Boolean,
    cancellationObserved: Boolean,
    workerErrorAbsent: Boolean,
    workerStopped: Boolean,
    segmenterClosed: Boolean,
    decoderClosed: Boolean,
    partialEvidenceDeleted: Boolean,
    followUpSucceeded: Boolean,
    leftovers: Int
  ): Boolean =
    activeBeforeCancel &&
      cancelIssued &&
      cancellationObserved &&
      workerErrorAbsent &&
      workerStopped &&
      segmenterClosed &&
      decoderClosed &&
      partialEvidenceDeleted &&
      followUpSucceeded &&
      leftovers == 0

  fun pageAlignmentPass(elfLoadAlignments: List<Long>, zipDataOffsetBytes: Long): Boolean =
    elfLoadAlignments.isNotEmpty() &&
      elfLoadAlignments.all { it >= 16_384L } &&
      zipDataOffsetBytes >= 0L &&
      zipDataOffsetBytes % 16_384L == 0L

  fun provenanceBoundariesComplete(boundaries: List<ProvenanceBoundary>): Boolean {
    if (
      boundaries.map { it.id }.toSet() != EXPECTED_PROVENANCE_BOUNDARY_IDS ||
      boundaries.size != EXPECTED_PROVENANCE_BOUNDARY_IDS.size
    ) {
      return false
    }
    return boundaries.all {
      val pinnedUrl = !it.id.endsWith("Url") ||
        (
          it.observed.startsWith("https://") &&
            !it.observed.contains("latest", ignoreCase = true)
          )
      it.expected.isNotBlank() &&
        it.observed == it.expected &&
        !it.observed.contains("latest", ignoreCase = true) &&
        pinnedUrl
    }
  }

  fun fixtureFailure(fixtureId: String, error: Throwable): FixtureFailure =
    FixtureFailure(fixtureId, error.javaClass.name, error.message ?: "")

  fun motionCompensatedIou(
    first: BooleanArray,
    firstWidth: Int,
    firstHeight: Int,
    second: BooleanArray,
    secondWidth: Int,
    secondHeight: Int
  ): Double = binaryIou(
    motionNormalizedMask(first, firstWidth, firstHeight),
    motionNormalizedMask(second, secondWidth, secondHeight)
  )

  fun motionNormalizedMask(
    source: BooleanArray,
    width: Int,
    height: Int,
    normalizedSize: Int = 64
  ): BooleanArray {
    require(source.size == width * height)
    require(width > 0 && height > 0 && normalizedSize > 0)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
      for (x in 0 until width) {
        if (!source[y * width + x]) continue
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, x)
        bottom = maxOf(bottom, y)
      }
    }
    if (right < left || bottom < top) return BooleanArray(normalizedSize * normalizedSize)
    val boundsWidth = right - left + 1
    val boundsHeight = bottom - top + 1
    return BooleanArray(normalizedSize * normalizedSize) { index ->
      val normalizedY = index / normalizedSize
      val normalizedX = index % normalizedSize
      val sourceX = left + (((normalizedX + 0.5) * boundsWidth / normalizedSize).toInt())
        .coerceIn(0, boundsWidth - 1)
      val sourceY = top + (((normalizedY + 0.5) * boundsHeight / normalizedSize).toInt())
        .coerceIn(0, boundsHeight - 1)
      source[sourceY * width + sourceX]
    }
  }

  fun binaryIou(first: BooleanArray, second: BooleanArray): Double {
    require(first.size == second.size)
    var intersection = 0
    var union = 0
    for (index in first.indices) {
      if (first[index] && second[index]) intersection++
      if (first[index] || second[index]) union++
    }
    return if (union == 0) 1.0 else intersection.toDouble() / union
  }

  fun autoTrackAccepted(
    actualImplementationAvailable: Boolean,
    crossingPass: Boolean,
    occlusionPass: Boolean,
    cutPass: Boolean,
    subjectJumpCount: Int
  ): Boolean =
    actualImplementationAvailable &&
      crossingPass &&
      occlusionPass &&
      cutPass &&
      subjectJumpCount == 0
}
