package com.memeget.nativeapp.editor

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File

/**
 * MI-GAN (512×512, Places2) on the GPU through LiteRT: invents believable background for the pixels a logo
 * covered where no frame shows what was there. The fp16 graph runs only on the GPU; [open] throws where that
 * isn't available, and callers fall back to the plain fill. A video runs it on every frame, so its buffers are made
 * once, the picture is prepared and written back in native code, and the three steps can overlap across frames:
 * [prepare] and [write] run anywhere, [run] one call at a time.
 */
internal class MiganInpainter private constructor(private val model: CompiledModel) : AutoCloseable {
    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()
    private val tensor = newTensor()

    /**
     * [rgb] ([w]×[h], 3 bytes a pixel) with its [hole] pixels (nonzero) filled; nothing else changes. The model's
     * input is square, so a non-square picture is padded to a square by repeating its edge pixels (the padding is
     * kept, not filled) rather than stretched.
     */
    fun inpaint(rgb: ByteArray, w: Int, h: Int, hole: ByteArray): ByteArray {
        prepare(rgb, w, h, hole, tensor)
        return write(run(tensor), rgb, w, h, hole)
    }

    /** The model's input for [rgb] with [hole] (see [inpaint]) into [into], one of [newTensor]. */
    fun prepare(rgb: ByteArray, w: Int, h: Int, hole: ByteArray, into: FloatArray) =
        LogoRemovalNative.nativeMiganInput(rgb, w, h, hole, SIZE, into)

    /** The model on a prepared input; its output (3 × 512 × 512). One call at a time. */
    fun run(prepared: FloatArray): FloatArray {
        inputs[0].writeFloat(prepared)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    /** [rgb] with its [hole] pixels taken from the model's output [generated]. */
    fun write(generated: FloatArray, rgb: ByteArray, w: Int, h: Int, hole: ByteArray): ByteArray =
        ByteArray(rgb.size).also { LogoRemovalNative.nativeMiganOutput(generated, SIZE, rgb, w, h, hole, it) }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        const val SIZE = 512

        fun newTensor() = FloatArray(4 * SIZE * SIZE)

        fun open(modelFile: File): MiganInpainter {
            val model = CompiledModel.create(modelFile.absolutePath, CompiledModel.Options(Accelerator.GPU), null)
            return try {
                MiganInpainter(model)
            } catch (failure: Throwable) {
                model.close()
                throw failure
            }
        }
    }
}
