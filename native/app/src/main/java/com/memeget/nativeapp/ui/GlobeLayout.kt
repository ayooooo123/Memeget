package com.memeget.nativeapp

import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The globe's grid: [cols] by [rows] cells that wrap both ways (a torus), so panning never meets an edge. [cells] holds
 * each cell's item index (row-major), or -1 for an empty cell. Twice as wide as tall, so a cell is square on the globe.
 * The globe first turns to face the focal cell ([focusCol], [focusRow]).
 */
internal class GlobeGrid(
    val cols: Int,
    val rows: Int,
    val cells: IntArray,
    val focusCol: Int = cols / 2,
    val focusRow: Int = rows / 2,
    /** Search results around their best match, which the globe holds facing the viewer until touched. */
    val ranked: Boolean = false,
) {
    companion object {
        val Empty = GlobeGrid(1, 1, intArrayOf(-1))
    }
}

/** Width and height of the grid for [count] items: rows ≈ √(count/2), so the grid is about twice as wide as tall. */
internal fun globeGridSize(count: Int): Pair<Int, Int> {
    if (count <= 0) return 1 to 1
    val rows = max(1, sqrt(count / 2.0).roundToInt())
    return ceil(count / rows.toDouble()).toInt() to rows
}

/**
 * Search results, best first, around a focal point: item 0 takes the middle cell and each next item the nearest free
 * cell, ring by ring, so the best matches sit together where the globe first faces and relevance falls off with
 * distance. Ring cells fill in angle order, so a ring's matches stay side by side.
 */
internal fun layoutRankedGlobeGrid(count: Int): GlobeGrid {
    if (count <= 0) return GlobeGrid.Empty
    val (cols, rows) = globeGridSize(count)
    val focusCol = cols / 2
    val focusRow = rows / 2
    val order = (0 until cols * rows).sortedWith(
        compareBy<Int> { cell ->
            val dx = cell % cols - focusCol
            val dy = cell / cols - focusRow
            dx * dx + dy * dy
        }.thenBy { cell -> kotlin.math.atan2((cell / cols - focusRow).toDouble(), (cell % cols - focusCol).toDouble()) },
    )
    val cells = IntArray(cols * rows) { -1 }
    for (item in 0 until count) cells[order[item]] = item
    return GlobeGrid(cols, rows, cells, focusCol, focusRow, ranked = true)
}

private const val PROJECTED_DIM = 16
private const val SOM_EPOCHS = 16
/** Epochs that search every node for an item's best match; later ones look only near the last match. */
private const val SOM_FULL_SEARCH_EPOCHS = 4

/**
 * Lays [vectors] out on a wrapping grid so look-alikes sit together. Vectors are randomly projected down to a few
 * dimensions, a coarse self-organising map on a torus learns the arrangement, and items are then dealt into rows by
 * their map position and spread along each row in order. Items with no vector land at a spot fixed by their index.
 * The same input always gives the same grid.
 */
internal fun layoutGlobeGrid(vectors: List<FloatArray?>, seed: Long = 7L): GlobeGrid {
    val n = vectors.size
    if (n == 0) return GlobeGrid.Empty
    val (cols, rows) = globeGridSize(n)
    val random = Random(seed)
    val dim = vectors.firstNotNullOfOrNull { it }?.size ?: 0

    // Map position of each item, in cell units.
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    if (dim == 0 || n < 4) {
        for (i in 0 until n) {
            xs[i] = (i % cols) + 0.5f
            ys[i] = (i / cols) + 0.5f
        }
    } else {
        val projected = project(vectors, dim, random)
        val nodeRows = max(2, sqrt(n / 40.0).roundToInt())
        val nodeCols = nodeRows * 2
        val nodes = trainTorusMap(projected, n, nodeCols, nodeRows, random)
        for (i in 0 until n) {
            if (vectors[i] == null) {
                val h = Random(seed xor i.toLong())
                xs[i] = h.nextFloat() * cols
                ys[i] = h.nextFloat() * rows
                continue
            }
            val (nx, ny) = refinedNodePosition(projected, i, nodes, nodeCols, nodeRows)
            xs[i] = (nx / nodeCols * cols).toFloat()
            ys[i] = (ny / nodeRows * rows).toFloat()
        }
    }

    // Deal items into rows by map height, then lay each row out in map order, turned to line up with the map.
    val cells = IntArray(cols * rows) { -1 }
    val byY = (0 until n).sortedWith(compareBy<Int> { ys[it] }.thenBy { it })
    var next = 0
    for (row in 0 until rows) {
        val count = n / rows + if (row < n % rows) 1 else 0
        val members = byY.subList(next, next + count).sortedWith(compareBy<Int> { xs[it] }.thenBy { it })
        next += count
        val shift = bestRowShift(members.map { xs[it] }, cols)
        members.forEachIndexed { j, item -> cells[row * cols + Math.floorMod(j + shift, cols)] = item }
    }
    return GlobeGrid(cols, rows, cells)
}

/** Unit-length random projection of every vector to [PROJECTED_DIM] dimensions; rows of missing vectors stay zero. */
private fun project(vectors: List<FloatArray?>, dim: Int, random: Random): FloatArray {
    val matrix = FloatArray(dim * PROJECTED_DIM) { random.nextGaussian().toFloat() }
    val out = FloatArray(vectors.size * PROJECTED_DIM)
    for ((i, v) in vectors.withIndex()) {
        if (v == null || v.size != dim) continue
        val base = i * PROJECTED_DIM
        for (k in 0 until dim) {
            val x = v[k]
            if (x == 0f) continue
            val row = k * PROJECTED_DIM
            for (p in 0 until PROJECTED_DIM) out[base + p] += x * matrix[row + p]
        }
        normalize(out, base)
    }
    return out
}

private fun normalize(a: FloatArray, base: Int) {
    var sum = 0f
    for (p in 0 until PROJECTED_DIM) sum += a[base + p] * a[base + p]
    if (sum <= 0f) return
    val inv = 1f / sqrt(sum)
    for (p in 0 until PROJECTED_DIM) a[base + p] *= inv
}

private fun hasVector(projected: FloatArray, i: Int): Boolean {
    val base = i * PROJECTED_DIM
    for (p in 0 until PROJECTED_DIM) if (projected[base + p] != 0f) return true
    return false
}

/** Batch self-organising map on a [cols] by [rows] torus; returns node weights, row-major. */
private fun trainTorusMap(projected: FloatArray, n: Int, cols: Int, rows: Int, random: Random): FloatArray {
    val count = cols * rows
    val nodes = FloatArray(count * PROJECTED_DIM)
    val withVectors = (0 until n).filter { hasVector(projected, it) }
    for (k in 0 until count) {
        val src = withVectors[random.nextInt(withVectors.size)] * PROJECTED_DIM
        for (p in 0 until PROJECTED_DIM) nodes[k * PROJECTED_DIM + p] = projected[src + p] + (random.nextGaussian() * 0.05).toFloat()
        normalize(nodes, k * PROJECTED_DIM)
    }
    val sums = FloatArray(count * PROJECTED_DIM)
    val hits = FloatArray(count)
    val bmus = IntArray(n)
    val startSigma = max(cols, rows) / 3f
    val endSigma = 0.6f
    for (epoch in 0 until SOM_EPOCHS) {
        val sigma = startSigma * Math.pow((endSigma / startSigma).toDouble(), epoch / (SOM_EPOCHS - 1.0)).toFloat()
        sums.fill(0f)
        hits.fill(0f)
        for (i in withVectors) {
            val bmu = if (epoch < SOM_FULL_SEARCH_EPOCHS) bestNode(projected, i, nodes, count)
            else bestNodeNear(projected, i, nodes, cols, rows, bmus[i])
            bmus[i] = bmu
            hits[bmu] += 1f
            val src = i * PROJECTED_DIM
            val dst = bmu * PROJECTED_DIM
            for (p in 0 until PROJECTED_DIM) sums[dst + p] += projected[src + p]
        }
        val twoSigmaSq = 2f * sigma * sigma
        val reach = ceil(sigma * 3f).toInt()
        val weight = FloatArray(PROJECTED_DIM)
        for (j in 0 until count) {
            val jx = j % cols
            val jy = j / cols
            weight.fill(0f)
            var total = 0f
            for (dy in -min(reach, rows / 2)..min(reach, (rows - 1) / 2)) {
                for (dx in -min(reach, cols / 2)..min(reach, (cols - 1) / 2)) {
                    val k = Math.floorMod(jy + dy, rows) * cols + Math.floorMod(jx + dx, cols)
                    if (hits[k] == 0f) continue
                    val h = exp(-(dx * dx + dy * dy) / twoSigmaSq)
                    total += h * hits[k]
                    val base = k * PROJECTED_DIM
                    for (p in 0 until PROJECTED_DIM) weight[p] += h * sums[base + p]
                }
            }
            if (total < 1e-6f) continue
            val base = j * PROJECTED_DIM
            for (p in 0 until PROJECTED_DIM) nodes[base + p] = weight[p] / total
            normalize(nodes, base)
        }
    }
    return nodes
}

private fun similarity(projected: FloatArray, i: Int, nodes: FloatArray, k: Int): Float {
    val a = i * PROJECTED_DIM
    val b = k * PROJECTED_DIM
    var dot = 0f
    for (p in 0 until PROJECTED_DIM) dot += projected[a + p] * nodes[b + p]
    return dot
}

private fun bestNode(projected: FloatArray, i: Int, nodes: FloatArray, count: Int): Int {
    var best = 0
    var bestScore = Float.NEGATIVE_INFINITY
    for (k in 0 until count) {
        val score = similarity(projected, i, nodes, k)
        if (score > bestScore) {
            bestScore = score
            best = k
        }
    }
    return best
}

/** Best node within two steps (wrapping) of [around]: once the map has settled, a match only drifts locally. */
private fun bestNodeNear(projected: FloatArray, i: Int, nodes: FloatArray, cols: Int, rows: Int, around: Int): Int {
    val ax = around % cols
    val ay = around / cols
    var best = around
    var bestScore = Float.NEGATIVE_INFINITY
    for (dy in -min(2, rows / 2)..min(2, (rows - 1) / 2)) {
        for (dx in -min(2, cols / 2)..min(2, (cols - 1) / 2)) {
            val k = Math.floorMod(ay + dy, rows) * cols + Math.floorMod(ax + dx, cols)
            val score = similarity(projected, i, nodes, k)
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
    }
    return best
}

/** The item's best node, nudged toward the closer neighbour on each axis, as continuous node coordinates. */
private fun refinedNodePosition(projected: FloatArray, i: Int, nodes: FloatArray, cols: Int, rows: Int): Pair<Double, Double> {
    val bmu = bestNode(projected, i, nodes, cols * rows)
    val bx = bmu % cols
    val by = bmu / cols
    fun at(x: Int, y: Int) = 1f - similarity(projected, i, nodes, Math.floorMod(y, rows) * cols + Math.floorMod(x, cols))
    val centre = at(bx, by)
    fun offset(minus: Float, plus: Float): Double {
        val curve = minus + plus - 2f * centre
        return if (curve <= 1e-6f) 0.0 else ((minus - plus) / (2f * curve)).toDouble().coerceIn(-0.5, 0.5)
    }
    val x = bx + 0.5 + offset(at(bx - 1, by), at(bx + 1, by))
    val y = by + 0.5 + offset(at(bx, by - 1), at(bx, by + 1))
    return wrapDouble(x, cols.toDouble()) to wrapDouble(y, rows.toDouble())
}

private fun wrapDouble(x: Double, m: Double): Double = ((x % m) + m) % m

/** The rotation of a row of [sorted] map positions that best lines each item's column up with its position. */
private fun bestRowShift(sorted: List<Float>, cols: Int): Int {
    var best = 0
    var bestCost = Double.MAX_VALUE
    for (shift in 0 until cols) {
        var cost = 0.0
        for ((j, x) in sorted.withIndex()) {
            val col = Math.floorMod(j + shift, cols) + 0.5
            val d = Math.abs(col - x)
            cost += min(d, cols - d)
        }
        if (cost < bestCost) {
            bestCost = cost
            best = shift
        }
    }
    return best
}

/** Saves [grid] as meme ids, so the same set of memes reopens on the same globe without laying it out again. */
internal fun writeGlobeGrid(file: java.io.File, grid: GlobeGrid, ids: List<Long>) {
    runCatching {
        file.parentFile?.mkdirs()
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(grid.cols)
            out.writeInt(grid.rows)
            for (item in grid.cells) out.writeLong(if (item < 0) -1L else ids[item])
        }
    }
}

/** A grid saved by [writeGlobeGrid], indexed into [ids]; null if missing, unreadable, or not for exactly these ids. */
internal fun readGlobeGrid(file: java.io.File, ids: List<Long>): GlobeGrid? = runCatching {
    if (!file.isFile) return null
    val index = HashMap<Long, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }
    java.io.DataInputStream(file.inputStream().buffered()).use { input ->
        val cols = input.readInt()
        val rows = input.readInt()
        if (cols <= 0 || rows <= 0 || cols.toLong() * rows > 1_000_000L) return null
        var placed = 0
        val cells = IntArray(cols * rows) {
            val id = input.readLong()
            if (id == -1L) -1 else (index[id] ?: return null).also { placed++ }
        }
        if (placed != ids.size) null else GlobeGrid(cols, rows, cells)
    }
}.getOrNull()

/**
 * Names a layout. A look-alike layout is named by the set of memes it holds, in any order, and which of them were
 * still waiting for indexing: a meme laid out before its picture was read sits at a random spot, so when its vector
 * arrives the key changes and the globe is laid out again with it among its look-alikes. A [ranked] layout (search
 * results around their best match) is named by the memes in order, since order is where they sit.
 */
internal fun globeLayoutKey(memes: List<Meme>, ranked: Boolean): String {
    var h = 1125899906842597L
    if (ranked) {
        for (meme in memes) h = 31 * h + meme.id
        return "r${memes.size}_${java.lang.Long.toHexString(h)}"
    }
    for (key in memes.map { it.id * 2 + if (it.pending) 1 else 0 }.sorted()) h = 31 * h + key
    return "${memes.size}_${java.lang.Long.toHexString(h)}"
}
