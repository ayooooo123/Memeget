package com.memeget.nativeapp

import kotlin.math.abs
import kotlin.math.ln

/** One tile on the canvas board, in board pixels. [index] points into the list the board was laid out from. */
internal class CanvasTile(val index: Int, val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
}

/**
 * Tiles in justified rows: every row spans the board's full width, its tiles share one height and keep their aspect
 * ratios. [rowStart] holds each row's first tile index into [tiles], [rowTop]/[rowBottom] its vertical extent.
 */
internal class CanvasBoard(
    val width: Float,
    val height: Float,
    val tiles: List<CanvasTile>,
    val rowStart: IntArray,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
) {
    val rowCount get() = rowStart.size

    /** Tiles of rows that overlap [top, bottom] in board pixels, as a range into [tiles]. */
    fun tilesBetween(top: Float, bottom: Float): IntRange = tilesOfRows(rowsBetween(top, bottom))

    /** Rows that overlap [top, bottom] in board pixels. */
    fun rowsBetween(top: Float, bottom: Float): IntRange {
        if (rowCount == 0 || bottom < 0f || top > height) return IntRange.EMPTY
        // First row whose bottom reaches `top`, last row whose top is above `bottom`.
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rowBottom[mid] < top) lo = mid + 1 else hi = mid
        }
        val firstRow = lo
        if (rowTop[firstRow] > bottom) return IntRange.EMPTY
        hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowTop[mid] > bottom) hi = mid - 1 else lo = mid
        }
        return firstRow..lo
    }

    /** Tiles of [rows], clamped to the rows that exist, as a range into [tiles]. */
    fun tilesOfRows(rows: IntRange): IntRange {
        val first = rows.first.coerceAtLeast(0)
        val last = rows.last.coerceAtMost(rowCount - 1)
        if (rows.isEmpty() || first > last) return IntRange.EMPTY
        val end = if (last + 1 < rowCount) rowStart[last + 1] else tiles.size
        return rowStart[first] until end
    }

    /** The tile under a point in board pixels, if any. */
    fun tileAt(x: Float, y: Float): CanvasTile? {
        for (i in tilesBetween(y, y)) {
            val tile = tiles[i]
            if (x >= tile.x && x <= tile.right && y >= tile.y && y <= tile.bottom) return tile
        }
        return null
    }

    companion object {
        val Empty = CanvasBoard(0f, 0f, emptyList(), IntArray(0), FloatArray(0), FloatArray(0))
    }
}

/** Extreme pictures are clamped so a long screenshot or a banner can't take a whole row. */
internal const val MIN_TILE_ASPECT = 0.4f
internal const val MAX_TILE_ASPECT = 3f

/**
 * Packs [aspects] (width over height) into justified rows [boardWidth] wide, aiming for [rowHeight]. A row ends at
 * whichever break leaves its height closer to [rowHeight]. A final row too short to fill the width is held back
 * unless [complete]: more items may still arrive, and holding it means appending never moves a tile already placed.
 */
internal fun layoutCanvasBoard(
    aspects: List<Float>,
    boardWidth: Float,
    rowHeight: Float,
    gap: Float,
    complete: Boolean,
): CanvasBoard {
    if (aspects.isEmpty() || boardWidth <= 0f || rowHeight <= 0f) return CanvasBoard.Empty
    val tiles = ArrayList<CanvasTile>(aspects.size)
    val rowStart = ArrayList<Int>()
    val rowTop = ArrayList<Float>()
    val rowBottom = ArrayList<Float>()
    fun aspect(i: Int) = aspects[i].takeIf { it.isFinite() && it > 0f }?.coerceIn(MIN_TILE_ASPECT, MAX_TILE_ASPECT) ?: 1f
    // Height at which items [from, until) exactly span the board.
    fun fitHeight(from: Int, until: Int, sum: Float) = (boardWidth - (until - from - 1) * gap) / sum

    var y = 0f
    var i = 0
    while (i < aspects.size) {
        var sum = 0f
        var j = i
        var full = false
        while (j < aspects.size) {
            sum += aspect(j)
            j++
            if (sum * rowHeight + (j - i - 1) * gap >= boardWidth) {
                full = true
                break
            }
        }
        if (!full && !complete) break
        var end = j
        var height = if (full) fitHeight(i, j, sum) else rowHeight
        if (full && j - i > 1) {
            // Dropping the item that overflowed makes the row taller than aimed; keep whichever is nearer.
            val shorterSum = sum - aspect(j - 1)
            val taller = fitHeight(i, j - 1, shorterSum)
            if (abs(ln(taller / rowHeight)) < abs(ln(height / rowHeight))) {
                end = j - 1
                height = taller
            }
        }
        rowStart += tiles.size
        rowTop += y
        rowBottom += y + height
        var x = 0f
        for (k in i until end) {
            val width = if (full && k == end - 1) boardWidth - x else aspect(k) * height
            tiles += CanvasTile(k, x, y, width, height)
            x += width + gap
        }
        y += height + gap
        i = end
    }
    val height = if (tiles.isEmpty()) 0f else y - gap
    return CanvasBoard(boardWidth, height, tiles, rowStart.toIntArray(), rowTop.toFloatArray(), rowBottom.toFloatArray())
}
