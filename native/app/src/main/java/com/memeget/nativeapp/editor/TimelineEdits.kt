package com.memeget.nativeapp.editor

/**
 * Cut, trim and timing edits on a video project's source timeline. Pure, so the timeline panel only decides
 * where the finger is and these decide what that does to the project.
 */
internal object TimelineEdits {
    /** Shortest kept range and shortest layer timing a drag or button can leave. */
    const val MIN_RANGE_US = 100_000L
    /** A split this close to a range's edge would leave a sliver; it is refused. */
    const val SPLIT_MARGIN_US = 50_000L

    enum class Edge { START, END }

    fun durationUs(project: MemeEditProject): Long = project.source.durationUs ?: 1_000_000L

    /** Kept ranges, sorted and never empty for a video with a length. */
    fun ranges(project: MemeEditProject): List<TimeRangeUs> =
        ProjectCore.normalizeRetainedRanges(project.video?.retainedRanges.orEmpty(), durationUs(project))

    /** The edited video's segments: kept ranges and title cards in play order, with their edited-video times. */
    fun segments(project: MemeEditProject): List<ProjectCore.CompositionSegment> {
        val video = project.video ?: VideoEditSpec()
        return ProjectCore.buildCompositionSegments(ranges(project), video.insertedCards, video.speed, durationUs(project))
    }

    fun outputDurationUs(segments: List<ProjectCore.CompositionSegment>): Long = segments.lastOrNull()?.outputEndUs ?: 0L

    /** Index of the kept range playing at source time [us], or null when [us] is in a cut. */
    fun rangeIndexAt(ranges: List<TimeRangeUs>, us: Long): Int? {
        val index = ranges.indexOfFirst { us >= it.startUs && us < it.endUs }
        if (index >= 0) return index
        return ranges.lastIndex.takeIf { it >= 0 && us == ranges[it].endUs }
    }

    /** Ranges with the one under [us] cut in two there; null when [us] is in a cut or too near an edge. */
    fun splitAt(ranges: List<TimeRangeUs>, us: Long): List<TimeRangeUs>? {
        val index = rangeIndexAt(ranges, us) ?: return null
        val range = ranges[index]
        if (us - range.startUs < SPLIT_MARGIN_US || range.endUs - us < SPLIT_MARGIN_US) return null
        return ranges.take(index) + TimeRangeUs(range.startUs, us) + TimeRangeUs(us, range.endUs) + ranges.drop(index + 1)
    }

    /** Ranges without the one under [us]; null when it is the only one or [us] is in a cut. */
    fun deleteAt(ranges: List<TimeRangeUs>, us: Long): List<TimeRangeUs>? {
        if (ranges.size < 2) return null
        val index = rangeIndexAt(ranges, us) ?: return null
        return ranges.filterIndexed { i, _ -> i != index }
    }

    /**
     * Ranges with one [edge] of range [index] moved to [toUs], kept between its neighbours (touching is allowed)
     * and at least [MIN_RANGE_US] long.
     */
    fun trimEdge(ranges: List<TimeRangeUs>, index: Int, edge: Edge, toUs: Long, durationUs: Long): List<TimeRangeUs> {
        val range = ranges.getOrNull(index) ?: return ranges
        val moved = when (edge) {
            Edge.START -> {
                val floor = ranges.getOrNull(index - 1)?.endUs ?: 0L
                TimeRangeUs(toUs.coerceIn(floor, (range.endUs - MIN_RANGE_US).coerceAtLeast(floor)), range.endUs)
            }
            Edge.END -> {
                val ceiling = ranges.getOrNull(index + 1)?.startUs ?: durationUs
                TimeRangeUs(range.startUs, toUs.coerceIn((range.startUs + MIN_RANGE_US).coerceAtMost(ceiling), ceiling))
            }
        }
        return ranges.mapIndexed { i, r -> if (i == index) moved else r }
    }

    /** Ranges with the cut between range [index] and the next one closed, joining them. */
    fun joinWithNext(ranges: List<TimeRangeUs>, index: Int): List<TimeRangeUs> {
        val range = ranges.getOrNull(index) ?: return ranges
        val next = ranges.getOrNull(index + 1) ?: return ranges
        return ranges.take(index) + TimeRangeUs(range.startUs, next.endUs) + ranges.drop(index + 2)
    }

    /**
     * [layer] shown from (START) or until (END) source time [us]. The other end stays put unless that would leave
     * less than [MIN_RANGE_US]; then it moves out of the way.
     */
    fun setLayerTiming(layer: MemeEditLayer, edge: Edge, us: Long, durationUs: Long): MemeEditLayer {
        val current = layer.active ?: TimeRangeUs(0L, durationUs)
        val at = us.coerceIn(0L, durationUs)
        val range = when (edge) {
            Edge.START -> {
                val start = at.coerceAtMost((durationUs - MIN_RANGE_US).coerceAtLeast(0L))
                TimeRangeUs(start, current.endUs.coerceAtLeast(start + MIN_RANGE_US).coerceAtMost(durationUs))
            }
            Edge.END -> {
                val end = at.coerceAtLeast(MIN_RANGE_US.coerceAtMost(durationUs))
                TimeRangeUs(current.startUs.coerceAtMost(end - MIN_RANGE_US).coerceAtLeast(0L), end)
            }
        }
        return withActive(layer, range.takeUnless { it.startUs == 0L && it.endUs == durationUs })
    }

    fun withActive(layer: MemeEditLayer, active: TimeRangeUs?): MemeEditLayer = when (layer) {
        is MemeEditLayer.Text -> layer.copy(active = active)
        is MemeEditLayer.Cover -> layer.copy(active = active)
        is MemeEditLayer.Subject -> layer.copy(active = active)
        is MemeEditLayer.Media -> layer.copy(active = active)
        is MemeEditLayer.Draw -> layer.copy(active = active)
    }
}
