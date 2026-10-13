package com.memeget.nativeapp.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCoreTest {

    @Test
    fun testClamping() {
        val pt = ProjectCore.clampNormalizedPoint(NormalizedPoint(-0.5f, 1.5f))
        assertEquals(0f, pt.x, 0.0001f)
        assertEquals(1f, pt.y, 0.0001f)

        val rect = ProjectCore.clampNormalizedRect(NormalizedRect(-0.2f, -0.2f, 1.5f, 1.5f))
        assertEquals(0f, rect.x, 0.0001f)
        assertEquals(0f, rect.y, 0.0001f)
        assertEquals(1f, rect.width, 0.0001f)
        assertEquals(1f, rect.height, 0.0001f)
    }

    @Test
    fun testQuarterRotations() {
        val pt = NormalizedPoint(0.2f, 0.3f)
        val r90 = ProjectCore.rotateNormalizedPoint(pt, QuarterRotation.R90)
        assertEquals(0.7f, r90.x, 0.0001f)
        assertEquals(0.2f, r90.y, 0.0001f)

        val r180 = ProjectCore.rotateNormalizedPoint(pt, QuarterRotation.R180)
        assertEquals(0.8f, r180.x, 0.0001f)
        assertEquals(0.7f, r180.y, 0.0001f)

        val r270 = ProjectCore.rotateNormalizedPoint(pt, QuarterRotation.R270)
        assertEquals(0.3f, r270.x, 0.0001f)
        assertEquals(0.8f, r270.y, 0.0001f)
    }

    @Test
    fun testNormalizedRanges() {
        val ranges = listOf(
            TimeRangeUs(500_000L, 1_000_000L),
            TimeRangeUs(0L, 600_000L) // Overlaps with the first
        )
        val merged = ProjectCore.normalizeRetainedRanges(ranges, 2_000_000L)
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].startUs)
        assertEquals(1_000_000L, merged[0].endUs)
    }

    @Test
    fun touchingSplitRangesRemainIndependentlyEditableWithoutDroppingTime() {
        val split = listOf(TimeRangeUs(0L, 1_000_000L), TimeRangeUs(1_000_000L, 2_000_000L))
        assertEquals(split, ProjectCore.normalizeRetainedRanges(split, 2_000_000L))
    }

    @Test
    fun testTimelineTimeMapping() {
        val ranges = listOf(
            TimeRangeUs(1_000_000L, 2_000_000L), // 1s duration
            TimeRangeUs(3_000_000L, 5_000_000L)  // 2s duration
        )
        val speed = 2.0f // 2x speed -> output duration = 3s / 2 = 1.5s (1_500_000us)

        val outDur = ProjectCore.outputDurationUs(ranges, speed)
        assertEquals(1_500_000L, outDur)

        // At source time 1.5s (inside first range):
        // Retained before = 0. Source offset = 0.5s.
        // Output time = 0.5s / 2 = 0.25s = 250_000us.
        val outTime = ProjectCore.sourceTimeToOutputTimeUs(1_500_000L, ranges, speed)
        assertEquals(250_000L, outTime)

        // Inverse mapping
        val srcTime = ProjectCore.outputTimeToSourceTimeUs(250_000L, ranges, speed)
        assertEquals(1_500_000L, srcTime)

        // Source time in cut region (2.5s) -> should be null
        assertNull(ProjectCore.sourceTimeToOutputTimeUs(2_500_000L, ranges, speed))
    }

    @Test
    fun testKeyframeInterpolation() {
        val keyframes = listOf(
            TransformKeyframe(timeUs = 0L, center = NormalizedPoint(0.2f, 0.2f), scale = 1f, rotationDegrees = 0f, opacity = 1f, easing = "linear"),
            TransformKeyframe(timeUs = 1_000_000L, center = NormalizedPoint(0.8f, 0.8f), scale = 2f, rotationDegrees = 90f, opacity = 0.5f, easing = "linear")
        )

        // Halfway (0.5s)
        val mid = ProjectCore.interpolateTransformKeyframes(keyframes, 500_000L)
        assertNotNull(mid)
        assertEquals(0.5f, mid!!.center.x, 0.001f)
        assertEquals(0.5f, mid.center.y, 0.001f)
        assertEquals(1.5f, mid.scale, 0.001f)
        assertEquals(45f, mid.rotationDegrees, 0.001f)
        assertEquals(0.75f, mid.opacity, 0.001f)

        // Hold easing test
        val holdKfs = listOf(
            TransformKeyframe(timeUs = 0L, center = NormalizedPoint(0.2f, 0.2f), easing = "hold"),
            TransformKeyframe(timeUs = 1_000_000L, center = NormalizedPoint(0.8f, 0.8f), easing = "linear")
        )
        val holdMid = ProjectCore.interpolateTransformKeyframes(holdKfs, 500_000L)
        assertNotNull(holdMid)
        assertEquals(0.2f, holdMid!!.center.x, 0.001f)
    }

    @Test
    fun testProjectHistoryUndoRedo() {
        val source = MemeEditSource("file://test.png", "test.png", MediaEditKind.IMAGE, 100, 100)
        val initial = ProjectCore.createDefaultImageProject(source)
        var history = createProjectHistory(initial)

        assertFalse(canUndo(history))
        assertFalse(canRedo(history))

        // Action 1: Add Layer
        val layer1 = MemeEditLayer.Text(id = "layer1", text = "First")
        history = applyProjectAction(history, ProjectAction.AddLayer(layer1))

        assertTrue(canUndo(history))
        assertFalse(canRedo(history))
        assertEquals(1, history.present.layers.size)

        // Action 2: Add Layer
        val layer2 = MemeEditLayer.Text(id = "layer2", text = "Second")
        history = applyProjectAction(history, ProjectAction.AddLayer(layer2))

        assertEquals(2, history.present.layers.size)

        // Undo
        history = undoProjectHistory(history)
        assertEquals(1, history.present.layers.size)
        assertEquals("layer1", history.present.layers[0].id)
        assertTrue(canRedo(history))

        // Redo
        history = redoProjectHistory(history)
        assertEquals(2, history.present.layers.size)
        assertEquals("layer2", history.present.layers[1].id)
    }

    @Test
    fun testProjectHistoryTransactions() {
        val source = MemeEditSource("file://test.png", "test.png", MediaEditKind.IMAGE, 100, 100)
        val initial = ProjectCore.createDefaultImageProject(source)
        var history = createProjectHistory(initial)

        // Begin transaction
        history = beginProjectTransaction(history)

        // Apply multiple updates during gesture
        for (i in 1..5) {
            val updatedBase = BaseTransform(rotation = QuarterRotation.R90)
            history = applyProjectAction(history, ProjectAction.SetBaseTransform(updatedBase))
        }

        // Commit transaction -> should collapse into single history step
        history = commitProjectTransaction(history)
        assertEquals(1, history.past.size)

        // Undo rewinds the entire transaction to initial
        history = undoProjectHistory(history)
        assertEquals(QuarterRotation.R0, history.present.base.rotation)
    }
    @Test
    fun testPreserveTouchingRangesOnSplit() {
        // Splitting a 0..2s range at 1s produces two touching ranges: [0..1s] and [1s..2s]
        val splitRanges = listOf(
            TimeRangeUs(0L, 1_000_000L),
            TimeRangeUs(1_000_000L, 2_000_000L)
        )
        val normalized = ProjectCore.normalizeRetainedRanges(splitRanges, 2_000_000L)
        // Must NOT collapse touching ranges into a single range
        assertEquals(2, normalized.size)
        assertEquals(0L, normalized[0].startUs)
        assertEquals(1_000_000L, normalized[0].endUs)
        assertEquals(1_000_000L, normalized[1].startUs)
        assertEquals(2_000_000L, normalized[1].endUs)
    }

    @Test
    fun testCompositionSegmentsPlanWithCards() {
        val ranges = listOf(TimeRangeUs(0L, 4_000_000L)) // 4s source
        val cards = listOf(
            InsertedCard(uri = "file:///title.png", atUs = 1_000_000L, durationUs = 2_000_000L) // 2s card at 1s
        )
        val speed = 1.0f

        val segments = ProjectCore.buildCompositionSegments(ranges, cards, speed, 4_000_000L)
        assertEquals(3, segments.size)

        // Segment 0: Source 0..1s
        assertTrue(segments[0] is ProjectCore.CompositionSegment.Source)
        val s0 = segments[0] as ProjectCore.CompositionSegment.Source
        assertEquals(0L, s0.sourceStartUs)
        assertEquals(1_000_000L, s0.sourceEndUs)
        assertEquals(0L, s0.outputStartUs)
        assertEquals(1_000_000L, s0.outputEndUs)

        // Segment 1: Card 2s long
        assertTrue(segments[1] is ProjectCore.CompositionSegment.Card)
        val s1 = segments[1] as ProjectCore.CompositionSegment.Card
        assertEquals("file:///title.png", s1.uri)
        assertEquals(2_000_000L, s1.timelineDurationUs)
        assertEquals(1_000_000L, s1.outputStartUs)
        assertEquals(3_000_000L, s1.outputEndUs)

        // Segment 2: Source 1s..4s
        assertTrue(segments[2] is ProjectCore.CompositionSegment.Source)
        val s2 = segments[2] as ProjectCore.CompositionSegment.Source
        assertEquals(1_000_000L, s2.sourceStartUs)
        assertEquals(4_000_000L, s2.sourceEndUs)
        assertEquals(3_000_000L, s2.outputStartUs)
        assertEquals(6_000_000L, s2.outputEndUs)

        // Timeline time mapping
        // At output 500ms (inside first piece) -> source 500ms
        assertEquals(500_000L, ProjectCore.mapOutputTimeToSourceTime(500_000L, segments, speed))
        // At output 2s (inside card) -> null
        assertNull(ProjectCore.mapOutputTimeToSourceTime(2_000_000L, segments, speed))
        // At output 4s (1s into second piece: 1s + 1s = 2s) -> source 2s
        assertEquals(2_000_000L, ProjectCore.mapOutputTimeToSourceTime(4_000_000L, segments, speed))
    }

    @Test
    fun testApplyCropPreset() {
        val base = BaseTransform()
        // 1920x1080 source
        val square = ProjectCore.applyCropPreset(base, "1:1", 1920, 1080)
        assertEquals("1:1", square.outputAspect)
        // Width should be cropped to match height: 1080 / 1920 = 0.5625
        assertEquals(0.5625f, square.crop.width, 0.001f)
        assertEquals(1.0f, square.crop.height, 0.001f)
        assertEquals((1f - 0.5625f) / 2f, square.crop.x, 0.001f)
    }

    @Test
    fun testLayerRemappingAcrossBaseTransform() {
        val textLayer = MemeEditLayer.Text(
            id = "t1",
            text = "Remap Me",
            width = 0.8f,
            fontSize = 0.1f,
            keyframes = listOf(TransformKeyframe(center = NormalizedPoint(0.5f, 0.5f)))
        )
        val oldBase = BaseTransform()
        val newBase = BaseTransform(rotation = QuarterRotation.R90)

        val remapped = ProjectCore.remapProjectLayers(listOf(textLayer), oldBase, newBase, 1000, 1000)
        assertEquals(1, remapped.size)
        val remText = remapped[0] as MemeEditLayer.Text
        // Center (0.5, 0.5) under 90 deg rotation -> (0.5, 0.5)
        assertEquals(0.5f, remText.keyframes[0].center.x, 0.01f)
        assertEquals(0.5f, remText.keyframes[0].center.y, 0.01f)
    }

    @Test
    fun testAtomicMultiLayerActionAndUndo() {
        val source = MemeEditSource("file://test.png", "test.png", MediaEditKind.IMAGE, 100, 100)
        val initial = ProjectCore.createDefaultImageProject(source)
        var history = createProjectHistory(initial)

        val cover = MemeEditLayer.Cover(id = "c1", rect = NormalizedRect(0.1f, 0.1f, 0.5f, 0.1f))
        val text = MemeEditLayer.Text(id = "t1", text = "Replacement")

        // AddLayers adds BOTH layers atomically in one step
        history = applyProjectAction(history, ProjectAction.AddLayers(listOf(cover, text)))
        assertEquals(2, history.present.layers.size)

        // Single undo removes BOTH layers together
        history = undoProjectHistory(history)
        assertEquals(0, history.present.layers.size)

        // Redo restores BOTH layers together
        history = redoProjectHistory(history)
        assertEquals(2, history.present.layers.size)
    }

    @Test
    fun testStyleInferenceFromColorSamples() {
        val darkBg = "#101010"
        val lightGlyph = "#F0F0F0"
        // 16 samples representing a text box: 4 glyph points, 12 background points
        val samples = listOf(
            lightGlyph, lightGlyph, lightGlyph, lightGlyph,
            darkBg, darkBg, darkBg, darkBg,
            darkBg, darkBg, darkBg, darkBg,
            darkBg, darkBg, darkBg, darkBg
        )
        val style = ProjectCore.inferOriginalTextStyle(
            sampledColors = samples,
            originalText = "CAPTION TEXT",
            regionHeight = 0.15f,
            lineCount = 1
        )
        assertTrue(style.uppercase)
        assertEquals("impact", style.preset)
        assertTrue("Contrast ratio should be high", style.contrast >= 4.0f)
    }
}
