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
      complete = true,
      completedFixtureCount = 3,
      playbackReviewPass = true,
      fixtureCount = 3
    )

    assertTrue(
      VideoSegmentationGateContracts.videoIsolationAccepted(
        passing,
        cancellationCleanupPass = true,
        matrixComplete = true
      )
    )
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(runtimeMs = 30_001), true, true))
    assertFalse(
      VideoSegmentationGateContracts.videoIsolationAccepted(
        passing.copy(peakPssDeltaBytes = passing.pssDeltaBudgetBytes),
        true,
        true
      )
    )
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(qualityPass = false), true, true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(fixtureCount = 2), true, true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(complete = false), true, true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing.copy(playbackReviewPass = false), true, true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing, cancellationCleanupPass = false, true))
    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(passing, true, matrixComplete = false))
  }

  @Test
  fun smallestAcceptedConfigurationPrefersWorkingSizeThenMaskFps() {
    val candidates = listOf(
      observation(256, 8, qualityPass = false),
      observation(384, 8, qualityPass = true),
      observation(256, 15, qualityPass = true),
      observation(256, 12, qualityPass = true)
    )

    val selected = VideoSegmentationGateContracts.selectSmallestAccepted(
      candidates,
      cancellationCleanupPass = true,
      matrixComplete = true
    )

    assertEquals(256, selected?.workingSize)
    assertEquals(12, selected?.maskFps)
    assertNull(
      VideoSegmentationGateContracts.selectSmallestAccepted(
        candidates.map { it.copy(runtimeMs = 30_001) },
        cancellationCleanupPass = true,
        matrixComplete = true
      )
    )
  }

  @Test
  fun matrixCompletionRequiresExactGridEveryFixtureCompletedAndEveryEvidenceRecord() {
    val complete = listOf(256, 384, 512).flatMap { size ->
      listOf(8, 12, 15).map { fps -> observation(size, fps, qualityPass = true) }
    }

    assertTrue(VideoSegmentationGateContracts.matrixComplete(complete, evidenceCount = 27))
    assertFalse(VideoSegmentationGateContracts.matrixComplete(complete.dropLast(1), evidenceCount = 27))
    assertFalse(
      VideoSegmentationGateContracts.matrixComplete(
        complete.mapIndexed { index, item -> if (index == 0) item.copy(complete = false) else item },
        evidenceCount = 27
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.matrixComplete(
        complete.mapIndexed { index, item ->
          if (index == 0) item.copy(completedFixtureCount = 2) else item
        },
        evidenceCount = 27
      )
    )
    assertFalse(VideoSegmentationGateContracts.matrixComplete(complete, evidenceCount = 26))
  }

  @Test
  fun playbackReviewMustPassAndBindTheCurrentEvidenceDigest() {
    val digest = "a".repeat(64)
    assertTrue(VideoSegmentationGateContracts.playbackReviewPass(digest, digest, "PASS"))
    assertFalse(VideoSegmentationGateContracts.playbackReviewPass(digest, "b".repeat(64), "PASS"))
    assertFalse(VideoSegmentationGateContracts.playbackReviewPass(digest, digest, "FAIL"))
    assertFalse(VideoSegmentationGateContracts.playbackReviewPass(digest, "", "PASS"))
    assertTrue(VideoSegmentationGateContracts.playbackReviewRecordComplete("PASS", defectsCount = 0))
    assertTrue(VideoSegmentationGateContracts.playbackReviewRecordComplete("FAIL", defectsCount = 1))
    assertFalse(VideoSegmentationGateContracts.playbackReviewRecordComplete("FAIL", defectsCount = 0))
    assertFalse(VideoSegmentationGateContracts.playbackReviewRecordComplete("UNKNOWN", defectsCount = 1))
  }

  @Test
  fun quantitativelyEligibleConfigurationWithoutCurrentPlaybackReviewRemainsPending() {
    val pending = observation(256, 8, qualityPass = true).copy(playbackReviewPass = false)

    assertFalse(VideoSegmentationGateContracts.videoIsolationAccepted(pending, true, true))
    assertNull(VideoSegmentationGateContracts.selectSmallestAccepted(listOf(pending), true, true))
  }


  @Test
  fun nestedCleanupAttemptsEveryActionAndRetainsAllFailures() {
    val actionsRun = mutableListOf<String>()
    val workFailure = IllegalStateException("work")
    val result = VideoSegmentationGateContracts.cleanupAll(
      workFailure,
      listOf(
        {
          actionsRun += "segmenter"
          throw IllegalStateException("segmenter close")
        },
        {
          actionsRun += "decoder"
          throw IllegalStateException("decoder close")
        },
        { actionsRun += "sampler" }
      )
    )

    assertTrue(result === workFailure)
    assertEquals(listOf("segmenter", "decoder", "sampler"), actionsRun)
    assertEquals(2, result?.suppressed?.size)
  }

  @Test
  fun playbackZipEntriesUseOneFixedTimestamp() {
    val first = VideoSegmentationGateContracts.deterministicZipEntry("frame_0000.jpg")
    val second = VideoSegmentationGateContracts.deterministicZipEntry("manifest.json")

    assertEquals(VideoSegmentationGateContracts.FIXED_ZIP_ENTRY_TIME_MS, first.time)
    assertEquals(first.time, second.time)
  }

  @Test
  fun cancellationCleanupRequiresCancellationReleaseDeletionAndFollowUp() {
    assertTrue(
      VideoSegmentationGateContracts.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        cancellationObserved = true,
        workerErrorAbsent = true,
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
        cancellationObserved = true,
        workerErrorAbsent = true,
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
        cancellationObserved = true,
        workerErrorAbsent = true,
        workerStopped = true,
        segmenterClosed = true,
        decoderClosed = true,
        partialEvidenceDeleted = true,
        followUpSucceeded = true,
        leftovers = 1
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.cancellationCleanupPass(
        activeBeforeCancel = true,
        cancelIssued = true,
        cancellationObserved = false,
        workerStopped = true,
        workerErrorAbsent = true,
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
        cancellationObserved = true,
        workerStopped = true,
        workerErrorAbsent = false,
        segmenterClosed = true,
        decoderClosed = true,
        partialEvidenceDeleted = true,
        followUpSucceeded = true,
        leftovers = 0
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
  fun provenanceRequiresTheFixedBoundarySetAndPinnedUrls() {
    fun completeBoundaries() =
      VideoSegmentationGateContracts.EXPECTED_PROVENANCE_BOUNDARY_IDS.map { id ->
        val value = if (id.endsWith("Url")) "https://example.test/pinned/1" else "pinned"
        VideoSegmentationGateContracts.ProvenanceBoundary(id, value, value)
      }

    val complete = completeBoundaries()
    assertTrue(VideoSegmentationGateContracts.provenanceBoundariesComplete(complete))
    assertFalse(VideoSegmentationGateContracts.provenanceBoundariesComplete(complete.dropLast(1)))
    assertFalse(
      VideoSegmentationGateContracts.provenanceBoundariesComplete(
        complete + VideoSegmentationGateContracts.ProvenanceBoundary("unexpected", "x", "x")
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.provenanceBoundariesComplete(
        complete.map {
          if (it.id == "generator.downloadUrl") it.copy(observed = "") else it
        }
      )
    )
    assertFalse(
      VideoSegmentationGateContracts.provenanceBoundariesComplete(
        complete.map {
          if (it.id == "model.licenseUrl") {
            it.copy(observed = "https://example.test/latest/LICENSE")
          } else {
            it
          }
        }
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
      complete = true,
      completedFixtureCount = 3,
      playbackReviewPass = true,
      fixtureCount = 3
    )
}
