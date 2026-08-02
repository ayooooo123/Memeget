package expo.modules.memegetbg

object VideoSegmentationGateContracts {
  const val REQUIRED_FIXTURE_COUNT = 3
  const val MAX_REALTIME_MULTIPLIER = 3L

  data class MatrixObservation(
    val workingSize: Int,
    val maskFps: Int,
    val runtimeMs: Long,
    val durationMs: Long,
    val peakPssBytes: Long,
    val memoryCeilingBytes: Long,
    val qualityPass: Boolean,
    val fixtureCount: Int
  )

  data class EvidenceFrame(
    val timestampMs: Long,
    val panelSecond: Int?
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
      observation.peakPssBytes < observation.memoryCeilingBytes &&
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
    retrieverClosed: Boolean,
    partialEvidenceDeleted: Boolean,
    followUpSucceeded: Boolean,
    leftovers: Int
  ): Boolean =
    activeBeforeCancel &&
      cancelIssued &&
      workerStopped &&
      segmenterClosed &&
      retrieverClosed &&
      partialEvidenceDeleted &&
      followUpSucceeded &&
      leftovers == 0

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
