package expo.modules.memegetbg

object VideoSegmentationGateContracts {
  const val REQUIRED_FIXTURE_COUNT = 3
  const val MAX_REALTIME_MULTIPLIER = 3L

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
    cancellationCleanupPass: Boolean
  ): Boolean =
    observation.durationMs > 0L &&
      observation.runtimeMs <= observation.durationMs * MAX_REALTIME_MULTIPLIER &&
      observation.peakPssDeltaBytes < observation.pssDeltaBudgetBytes &&
      observation.qualityPass &&
      observation.fixtureCount == REQUIRED_FIXTURE_COUNT &&
      cancellationCleanupPass

  fun selectSmallestAccepted(
    observations: List<MatrixObservation>,
    cancellationCleanupPass: Boolean
  ): MatrixObservation? =
    observations
      .asSequence()
      .filter { videoIsolationAccepted(it, cancellationCleanupPass) }
      .sortedWith(compareBy<MatrixObservation> { it.workingSize }.thenBy { it.maskFps })
      .firstOrNull()

  fun cancellationCleanupPass(
    activeBeforeCancel: Boolean,
    cancelIssued: Boolean,
    workerStopped: Boolean,
    segmenterClosed: Boolean,
    decoderClosed: Boolean,
    partialEvidenceDeleted: Boolean,
    followUpSucceeded: Boolean,
    leftovers: Int
  ): Boolean =
    activeBeforeCancel &&
      cancelIssued &&
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

  fun provenanceBoundariesComplete(
    boundaries: List<ProvenanceBoundary>,
    requiredIds: Set<String>
  ): Boolean {
    if (boundaries.map { it.id }.toSet() != requiredIds || boundaries.size != requiredIds.size) return false
    return boundaries.all {
      it.expected.isNotBlank() &&
        it.observed == it.expected &&
        !it.observed.contains("latest", ignoreCase = true)
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
