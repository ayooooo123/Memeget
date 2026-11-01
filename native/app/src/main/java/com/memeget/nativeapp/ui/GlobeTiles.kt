package com.memeget.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Side of a globe thumbnail in pixels: enough for the zoomed-out globe, where a tile is 15–60 px across. */
internal const val GLOBE_TILE = 40
private const val ATLAS_COLS = 128

/**
 * Tiny square thumbnails for the globe, center-cropped, RGB_565. Kept in memory for the process and on disk across
 * runs, so a reopened globe fills at once instead of decoding every picture again. Keyed by picture source, not
 * meme id: a video that gets its poster later gets a new tile.
 */
internal object GlobeThumbs {
    private val memory = ConcurrentHashMap<String, Bitmap>()

    fun cached(meme: Meme): Bitmap? = memory[source(meme)]

    fun source(meme: Meme) = meme.thumbUri.ifEmpty { meme.uri }

    suspend fun load(context: Context, meme: Meme): Bitmap? {
        val src = source(meme)
        memory[src]?.let { return it }
        val file = File(context.cacheDir, "globe_tiles/${meme.id}_${Integer.toHexString(src.hashCode())}.565")
        val tile = readTile(file) ?: (decodeTile(context, src) ?: decodeWithCoil(context, src))?.also { writeTile(file, it) }
        if (tile != null) memory[src] = tile
        return tile
    }

    private fun readTile(file: File): Bitmap? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size != GLOBE_TILE * GLOBE_TILE * 2) return null
        return Bitmap.createBitmap(GLOBE_TILE, GLOBE_TILE, Bitmap.Config.RGB_565).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
    }

    private fun writeTile(file: File, tile: Bitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            val buffer = ByteBuffer.allocate(tile.byteCount)
            tile.copyPixelsToBuffer(buffer)
            file.writeBytes(buffer.array())
        }
    }

    private fun decodeTile(context: Context, source: String): Bitmap? = runCatching {
        val uri = Uri.parse(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GLOBE_TILE * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val full = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
        cropToTile(full).also { full.recycle() }
    }.getOrNull()

    /** Videos without a poster file and formats BitmapFactory can't read go through the app's image loader. */
    private suspend fun decodeWithCoil(context: Context, source: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(source).size(GLOBE_TILE * 2).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
        cropToTile(result.drawable.toBitmap())
    }.getOrNull()

    private fun cropToTile(full: Bitmap): Bitmap {
        val side = min(full.width, full.height)
        val left = (full.width - side) / 2
        val top = (full.height - side) / 2
        val tile = Bitmap.createBitmap(GLOBE_TILE, GLOBE_TILE, Bitmap.Config.RGB_565)
        Canvas(tile).drawBitmap(full, Rect(left, top, left + side, top + side), Rect(0, 0, GLOBE_TILE, GLOBE_TILE), Paint(Paint.FILTER_BITMAP_FLAG))
        return tile
    }
}

/**
 * Every meme's thumbnail packed into one bitmap, slot = the meme's index in the list it was built for, so the whole
 * globe draws as one textured mesh. Fills in the background; [version] ticks as tiles land.
 */
internal class GlobeAtlas(val count: Int) {
    val cols = min(ATLAS_COLS, max(1, count))
    val rows = max(1, ceil(count / cols.toDouble()).toInt())
    val bitmap: Bitmap = Bitmap.createBitmap(cols * GLOBE_TILE, rows * GLOBE_TILE, Bitmap.Config.RGB_565).apply {
        eraseColor(android.graphics.Color.rgb(0x2A, 0x2A, 0x30))
    }
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    var version by mutableIntStateOf(0)
        private set
    private val canvas = Canvas(bitmap)
    private val lock = Any()

    fun slotLeft(index: Int) = (index % cols) * GLOBE_TILE.toFloat()
    fun slotTop(index: Int) = (index / cols) * GLOBE_TILE.toFloat()
    /**
     * Each slot's texture corners, clockwise from top-left as (x, y) pairs, half a pixel in so filtering never
     * bleeds in a neighbour: copied straight into the mesh, so a frame computes none of it.
     */
    val texCoords = FloatArray(count * 8).also { tex ->
        for (i in 0 until count) {
            val l = slotLeft(i) + 0.5f
            val t = slotTop(i) + 0.5f
            val r = slotLeft(i) + GLOBE_TILE - 0.5f
            val b = slotTop(i) + GLOBE_TILE - 0.5f
            val o = i * 8
            tex[o] = l; tex[o + 1] = t
            tex[o + 2] = r; tex[o + 3] = t
            tex[o + 4] = r; tex[o + 5] = b
            tex[o + 6] = l; tex[o + 7] = b
        }
    }

    private fun put(index: Int, tile: Bitmap) = synchronized(lock) {
        canvas.drawBitmap(tile, slotLeft(index), slotTop(index), null)
    }

    /**
     * Copies cached tiles in at once, then decodes the rest a few at a time, publishing about five times a second.
     * [only] limits it to those slots, for memes whose picture changed after the atlas was built.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fill(context: Context, memes: List<Meme>, only: Collection<Int>? = null) = withContext(Dispatchers.IO) {
        val missing = ArrayList<Int>()
        for (i in only ?: memes.indices) {
            val tile = GlobeThumbs.cached(memes[i])
            if (tile != null) put(i, tile) else missing += i
        }
        withContext(Dispatchers.Main) { version++ }
        var lastPublish = SystemClock.uptimeMillis()
        val gate = Semaphore(4)
        coroutineScope {
            for (i in missing) {
                launch(Dispatchers.IO.limitedParallelism(4)) {
                    gate.withPermit {
                        ensureActive()
                        val tile = GlobeThumbs.load(context, memes[i]) ?: return@withPermit
                        put(i, tile)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPublish > 200) {
                            lastPublish = now
                            withContext(Dispatchers.Main) { version++ }
                        }
                    }
                }
            }
        }
        withContext(Dispatchers.Main) { version++ }
    }
}

/** A sharp picture for a tile seen up close, with a shader to draw it with rounded corners. */
internal class SharpImage(val bitmap: Bitmap) {
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
}

/**
 * Sharper pictures for the tiles on screen once they're big enough to show the atlas's blur; LRU by bytes. Keyed by
 * picture source, like [GlobeThumbs].
 */
internal class GlobeSharpImages(private val context: Context, private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, SharpImage>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SharpImage) = value.bitmap.byteCount
    }
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    var version by mutableIntStateOf(0)
        private set

    /** The sharp picture if loaded; otherwise starts loading it (a few at a time) and returns null. */
    fun get(meme: Meme): SharpImage? {
        val src = GlobeThumbs.source(meme)
        cache.get(src)?.let { return it }
        if (src in inFlight || src in failed || inFlight.size >= 6) return null
        inFlight += src
        scope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context).data(src).size(360).allowHardware(false).build()
                    (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()?.let(::SharpImage)
                }.getOrNull()
            }
            inFlight -= src
            if (image != null) cache.put(src, image) else failed += src
            version++
        }
        return null
    }
}
