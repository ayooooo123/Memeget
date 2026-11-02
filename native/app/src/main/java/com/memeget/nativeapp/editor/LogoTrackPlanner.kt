package com.memeget.nativeapp.editor

/**
 * Splits a video's frames into runs where a screen-fixed logo sits in one place, or is absent. Watermarks jump
 * between corners instead of gliding, so a run holds one rectangle until the next run starts.
 */
internal object LogoTrackPlanner {
    /** What one frame shows: the logo's top-left corner in analysis pixels when [present]. */
    data class Sighting(val present: Boolean, val x: Float = 0f, val y: Float = 0f)

    data class Segment(val startFrame: Int, val sighting: Sighting)

    /**
     * Runs over every frame's sighting, so a jump, or a logo that shows up for a single frame, starts its own run
     * on the frame it happens. A frame with no sighting (the decoder didn't produce it) continues the run before
     * it; leading frames without one count as no logo.
     */
    fun segments(
        sightings: List<Sighting?>,
        sameSpot: (Sighting, Sighting) -> Boolean,
    ): List<Segment> {
        require(sightings.isNotEmpty()) { "No frames to track" }
        var current = sightings[0] ?: Sighting(false)
        val segments = mutableListOf(Segment(0, current))
        for (frame in 1 until sightings.size) {
            val seen = sightings[frame] ?: continue
            if (!sameSpot(seen, current)) {
                current = seen
                segments += Segment(frame, current)
            }
        }
        return segments
    }

    /**
     * Hold keyframes for a cover layer. Each run after the first starts halfway between its first frame and the
     * frame before, so timestamps rounded by playback or speed changes still land in the right run.
     * [rectOf] maps a present sighting to the layer rectangle; an absent run keeps the last rectangle and draws
     * nothing.
     */
    fun corrections(
        segments: List<Segment>,
        frameTimesUs: LongArray,
        initialRect: NormalizedRect,
        rectOf: (Sighting) -> NormalizedRect,
    ): List<CoverCorrectionKeyframe> {
        var lastRect = initialRect
        return segments.map { segment ->
            val start = segment.startFrame
            val timeUs = if (start == 0) frameTimesUs[0] else (frameTimesUs[start - 1] + frameTimesUs[start]) / 2
            if (segment.sighting.present) lastRect = rectOf(segment.sighting)
            CoverCorrectionKeyframe(
                timeUs = timeUs,
                rect = lastRect,
                mode = if (segment.sighting.present) "remove" else "off",
                easing = "hold"
            )
        }
    }
}
