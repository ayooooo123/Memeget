package com.memeget.nativeapp.editor

import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import kotlin.math.max

/**
 * MI-GAN (512×512, Places2) on the GPU through LiteRT: invents believable background for the pixels a logo
 * covered where no frame shows what was there. The fp16 graph runs only on the GPU; [open] throws where that
 * isn't available, and callers fall back to the plain fill.
 */
internal class MiganInpainter private constructor(private val model: CompiledModel) : AutoCloseable {

    /**
     * [rgb] ([w]×[h], 3 bytes a pixel) with its [hole] pixels (nonzero) filled; nothing else changes. The model's
     * input is square, so a non-square picture is padded to a square by repeating its edge pixels (the padding is
     * kept, not filled) rather than stretched.
     */
    fun inpaint(rgb: ByteArray, w: Int, h: Int, hole: ByteArray): ByteArray {
        val side = max(w, h)
        val offsetX = (side - w) / 2
        val offsetY = (side - h) / 2
        val squareRgb = IntArray(side * side)
        val squareHole = IntArray(side * side)
        for (y in 0 until side) {
            val sy = (y - offsetY).coerceIn(0, h - 1)
            for (x in 0 until side) {
                val sx = (x - offsetX).coerceIn(0, w - 1)
                val i = sy * w + sx
                squareRgb[y * side + x] = (0xff shl 24) or ((rgb[i * 3].toInt() and 0xff) shl 16) or
                    ((rgb[i * 3 + 1].toInt() and 0xff) shl 8) or (rgb[i * 3 + 2].toInt() and 0xff)
                val inside = y - offsetY in 0 until h && x - offsetX in 0 until w
                squareHole[y * side + x] = if (inside && hole[i].toInt() != 0) -1 else (0xff shl 24)
            }
        }
        val input = Bitmap.createBitmap(squareRgb, side, side, Bitmap.Config.ARGB_8888)
        val holeBitmap = Bitmap.createBitmap(squareHole, side, side, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(input, SIZE, SIZE, true)
        val scaledHole = Bitmap.createScaledBitmap(holeBitmap, SIZE, SIZE, true)
        input.recycle()
        holeBitmap.recycle()
        val pixels = IntArray(SIZE * SIZE)
        val holePixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        scaledHole.getPixels(holePixels, 0, SIZE, 0, 0, SIZE, SIZE)
        scaled.recycle()
        scaledHole.recycle()

        // concat(mask − 0.5, rgb · mask), channels first; mask is 1 to keep, 0 to fill; rgb in [−1, 1].
        val plane = SIZE * SIZE
        val tensor = FloatArray(4 * plane)
        for (i in 0 until plane) {
            val keep = if ((holePixels[i] and 0xff) > 0) 0f else 1f
            val c = pixels[i]
            tensor[i] = keep - 0.5f
            tensor[plane + i] = ((c shr 16 and 0xff) / 127.5f - 1f) * keep
            tensor[2 * plane + i] = ((c shr 8 and 0xff) / 127.5f - 1f) * keep
            tensor[3 * plane + i] = ((c and 0xff) / 127.5f - 1f) * keep
        }
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        val generated = try {
            inputs[0].writeFloat(tensor)
            model.run(inputs, outputs)
            outputs[0].readFloat()
        } finally {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
        }

        fun channel(v: Float) = ((v + 1f) * 127.5f).toInt().coerceIn(0, 255)
        val out = Bitmap.createBitmap(IntArray(plane) { i ->
            (0xff shl 24) or (channel(generated[i]) shl 16) or
                (channel(generated[plane + i]) shl 8) or channel(generated[2 * plane + i])
        }, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val back = Bitmap.createScaledBitmap(out, side, side, true)
        out.recycle()
        val filled = IntArray(w * h)
        back.getPixels(filled, 0, w, offsetX, offsetY, w, h)
        back.recycle()

        val result = rgb.copyOf()
        for (i in 0 until w * h) {
            if (hole[i].toInt() == 0) continue
            val c = filled[i]
            result[i * 3] = (c shr 16).toByte()
            result[i * 3 + 1] = (c shr 8).toByte()
            result[i * 3 + 2] = c.toByte()
        }
        return result
    }

    override fun close() = model.close()

    companion object {
        const val SIZE = 512

        fun open(modelFile: File): MiganInpainter =
            MiganInpainter(CompiledModel.create(modelFile.absolutePath, CompiledModel.Options(Accelerator.GPU), null))
    }
}
