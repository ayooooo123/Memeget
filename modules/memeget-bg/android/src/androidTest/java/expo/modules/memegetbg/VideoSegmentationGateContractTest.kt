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
      baselinePssBytes = 400_000_000,
      peakPssBytes = 500_000_000,
      peakPssDeltaBytes = 100_000_000,
      pssDeltaBudgetBytes = 134_217_728,
      qualityPass = true,
      fixtureCount = 3
    )

    assertTrue(VideoSegmentationGateContracts.videoIsolationAccepted(passing, cancellationCleanupPass = true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(runtimeMs = 30_001), true))
    assertFalse(
      VideoSegmentationGateContracts.videoIsolationAccepted(
        passing.copy(peakPssDeltaBytes = passing.pssDeltaBudgetBytes),
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
        decoderClosed = true,
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
        decoderClosed = true,
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
        decoderClosed = true,
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

  @Test
  fun evidenceScheduleRunsEmaAtConfiguredFpsAndCapturesOnePanelPerSecond() {
    val schedules = listOf(8, 12, 15).associateWith { maskFps ->
      VideoSegmentationGateContracts.evidenceSchedule(maskFps, durationSeconds = 10)
    }

    for ((maskFps, schedule) in schedules) {
      assertEquals(maskFps * 10, schedule.size)
      assertEquals(0L, schedule.first().timestampMs)
      assertEquals((maskFps * 10 - 1) * 1000L / maskFps, schedule.last().timestampMs)
      assertEquals((0 until 10).toList(), schedule.mapNotNull { it.panelSecond })
    }
    assertFalse(schedules.getValue(8).map { it.timestampMs } == schedules.getValue(12).map { it.timestampMs })
    assertFalse(schedules.getValue(12).map { it.timestampMs } == schedules.getValue(15).map { it.timestampMs })
  }

  @Test
  fun temporalQualityCompensatesTranslationAndScaleButNotShapeChange() {
    val first = lShapeMask(width = 40, height = 30, left = 4, top = 3, scale = 1)
    val translatedScaled = lShapeMask(width = 80, height = 60, left = 20, top = 12, scale = 2)
    val changed = BooleanArray(40 * 30).also { mask ->
      for (y in 3 until 22) for (x in 4 until 23) mask[y * 40 + x] = true
    }

    assertTrue(VideoSegmentationGateContracts.motionCompensatedIou(first, 40, 30, translatedScaled, 80, 60) > 0.95)
    assertTrue(VideoSegmentationGateContracts.motionCompensatedIou(first, 40, 30, changed, 40, 30) < 0.80)
  }

  @Test
  fun apkPageAlignmentRequiresElfAndZipDataOffset() {
    assertTrue(VideoSegmentationGateContracts.pageAlignmentPass(listOf(16_384L, 16_384L), 32_768L))
    assertFalse(VideoSegmentationGateContracts.pageAlignmentPass(listOf(16_384L, 16_384L), 32_772L))
    assertFalse(VideoSegmentationGateContracts.pageAlignmentPass(listOf(4_096L, 16_384L), 32_768L))
  }

  @Test
  fun provenanceRequiresEveryExpectedBoundaryAndExactValue() {
    val complete = listOf(
      VideoSegmentationGateContracts.ProvenanceBoundary("version", "0.10.29", "0.10.29"),
      VideoSegmentationGateContracts.ProvenanceBoundary("pomDigest", "a".repeat(64), "a".repeat(64)),
      VideoSegmentationGateContracts.ProvenanceBoundary("licenseDigest", "b".repeat(64), "b".repeat(64))
    )
    assertTrue(VideoSegmentationGateContracts.provenanceBoundariesComplete(complete, setOf("version", "pomDigest", "licenseDigest")))
    assertFalse(VideoSegmentationGateContracts.provenanceBoundariesComplete(complete.dropLast(1), setOf("version", "pomDigest", "licenseDigest")))
    assertFalse(
      VideoSegmentationGateContracts.provenanceBoundariesComplete(
        complete.map { if (it.id == "version") it.copy(observed = "latest") else it },
        setOf("version", "pomDigest", "licenseDigest")
      )
    )
  }

  @Test
  fun fixtureFailuresRetainTheirOwnException() {
    val first = VideoSegmentationGateContracts.fixtureFailure("one_person", IllegalStateException("first"))
    val second = VideoSegmentationGateContracts.fixtureFailure("fast_motion", IllegalArgumentException("second"))

    assertEquals("one_person", first.fixtureId)
    assertEquals("first", first.message)
    assertEquals("java.lang.IllegalArgumentException", second.type)
    assertEquals("second", second.message)
  }

  private fun lShapeMask(
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    scale: Int
  ): BooleanArray = BooleanArray(width * height).also { mask ->
    for (y in 0 until 12 * scale) {
      for (x in 0 until 4 * scale) mask[(top + y) * width + left + x] = true
    }
    for (y in 8 * scale until 12 * scale) {
      for (x in 0 until 10 * scale) mask[(top + y) * width + left + x] = true
    }
  }

  private fun observation(size: Int, fps: Int, qualityPass: Boolean) =
    VideoSegmentationGateContracts.MatrixObservation(
      workingSize = size,
      maskFps = fps,
      runtimeMs = 10_000,
      durationMs = 10_000,
      baselinePssBytes = 300_000_000,
      peakPssBytes = 360_000_000,
      peakPssDeltaBytes = 60_000_000,
      pssDeltaBudgetBytes = 134_217_728,
      qualityPass = qualityPass,
      fixtureCount = 3
    )
}
