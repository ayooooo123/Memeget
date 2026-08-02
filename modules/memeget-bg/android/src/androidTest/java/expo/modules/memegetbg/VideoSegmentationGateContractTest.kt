package expo.modules.memegetbg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSegmentationGateContractTest {
  @Test
  fun modelAndFixtureRecordsRequirePinnedVersionsLicensesAndSha256Digests() {
    assertTrue(
      VideoSegmentationGateContracts.provenanceComplete(
        version = "1",
        downloadUrl = "https://storage.googleapis.com/model/1/model.tflite",
        license = "Apache-2.0",
        licenseUrl = "https://example.test/LICENSE",
        sha256 = "a".repeat(64)
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.provenanceComplete(
        version = "latest",
        downloadUrl = "https://storage.googleapis.com/model/latest/model.tflite",
        license = "Apache-2.0",
        licenseUrl = "https://example.test/LICENSE",
        sha256 = "a".repeat(64)
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.provenanceComplete(
        version = "1",
        downloadUrl = "https://storage.googleapis.com/model/1/model.tflite",
        license = "",
        licenseUrl = "https://example.test/LICENSE",
        sha256 = "not-a-digest"
      )
    )
  }

  @Test
  fun videoIsolationRequiresQualityThreeTimesRealtimeMemoryAndCleanup() {
    val passing = VideoSegmentationGateContracts.MatrixObservation(
      workingSize = 256,
      maskFps = 12,
      runtimeMs = 30_000,
      durationMs = 10_000,
      peakPssBytes = 400_000_000,
      memoryCeilingBytes = 536_870_912,
      qualityPass = true,
      fixtureCount = 3
    )

    assertTrue(VideoSegmentationGateContracts.videoIsolationAccepted(passing, cancellationCleanupPass = true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(runtimeMs = 30_001), true))
    assertFalse(
      VideoSegmentationGateContracts.videoIsolationAccepted(
        passing.copy(peakPssBytes = passing.memoryCeilingBytes),
        true
      )
    )
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(qualityPass = false), true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(fixtureCount = 2), true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing, cancellationCleanupPass = false))
  }

  @Test
  fun smallestAcceptedConfigurationPrefersWorkingSizeThenMaskFps() {
    val candidates = listOf(
      observation(256, 8, qualityPass = false),
      observation(384, 8, qualityPass = true),
      observation(256, 15, qualityPass = true),
      observation(256, 12, qualityPass = true)
    )

    val selected = VideoSegmentationGateContracts.selectSmallestAccepted(candidates, cancellationCleanupPass = true)

    assertEquals(256, selected?.workingSize)
    assertEquals(12, selected?.maskFps)
    assertNull(
      VideoSegmentationGateContracts.selectSmallestAccepted(
        candidates.map { it.copy(runtimeMs = 30_001) },
        cancellationCleanupPass = true
      )
    )
  }

  @Test
  fun cancellationCleanupRequiresCancellationReleaseDeletionAndFollowUp() {
    assertTrue(
      VideoSegmentationGateContracts.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        workerStopped = true,
        segmenterClosed = true,
        retrieverClosed = true,
        partialEvidenceDeleted = true,
        followUpSucceeded = true,
        leftovers = 0
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        workerStopped = true,
        segmenterClosed = false,
        retrieverClosed = true,
        partialEvidenceDeleted = true,
        followUpSucceeded = true,
        leftovers = 0
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        workerStopped = true,
        segmenterClosed = true,
        retrieverClosed = true,
        partialEvidenceDeleted = true,
        followUpSucceeded = true,
        leftovers = 1
      )
    )
  }

  @Test
  fun autoTrackRequiresActualImplementationAndEveryAdversarialScenario() {
    assertTrue(
      VideoSegmentationGateContracts.autoTrackAccepted(
        actualImplementationAvailable = true,
        crossingPass = true,
        occlusionPass = true,
        cutPass = true,
        subjectJumpCount = 0
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.autoTrackAccepted(
        actualImplementationAvailable = false,
        crossingPass = true,
        occlusionPass = true,
        cutPass = true,
        subjectJumpCount = 0
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.autoTrackAccepted(
        actualImplementationAvailable = true,
        crossingPass = true,
        occlusionPass = false,
        cutPass = true,
        subjectJumpCount = 0
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.autoTrackAccepted(
        actualImplementationAvailable = true,
        crossingPass = true,
        occlusionPass = true,
        cutPass = true,
        subjectJumpCount = 1
      )
    )
  }

  private fun observation(size: Int, fps: Int, qualityPass: Boolean) =
    VideoSegmentationGateContracts.MatrixObservation(
      workingSize = size,
      maskFps = fps,
      runtimeMs = 10_000,
      durationMs = 10_000,
      peakPssBytes = 300_000_000,
      memoryCeilingBytes = 536_870_912,
      qualityPass = qualityPass,
      fixtureCount = 3
    )
}
