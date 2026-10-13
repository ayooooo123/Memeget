package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import android.media.ExifInterface
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Replaces or blurs the transformed source frame so video background modes match
 * the still renderer: solid, transparent-as-black, blurred-source, image cover,
 * or passthrough source.
 */
@OptIn(UnstableApi::class)
internal class VideoBackgroundEffect(
    private val mode: String,
    private val colorArgb: Int,
    private val blurScale: Float,
    private val imageBitmap: Bitmap?
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) {
            throw VideoFrameProcessingException(
                "VideoBackgroundEffect requires SDR frames; enable HDR tone-mapping first"
            )
        }
        return when (mode) {
            "source" -> PassthroughShaderProgram()
            "solid", "transparent" -> SolidShaderProgram(if (mode == "transparent") Color.BLACK else colorArgb)
            "blurred-source" -> BlurShaderProgram(blurScale.coerceIn(0f, 1f))
            "image" -> {
                val bmp = imageBitmap
                    ?: throw VideoFrameProcessingException("Background image mode has no bitmap")
                ImageCoverShaderProgram(bmp)
            }
            "video" -> PassthroughShaderProgram()
            else -> PassthroughShaderProgram()
        }
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        mode == "source" || mode == "video" || (mode == "blurred-source" && blurScale <= 0f)

    companion object {
        private const val MAX_BLUR_DOWNSCALE = 31f
        private const val MIN_BLUR_EDGE = 24

        fun decodeOrientedBitmap(context: Context, uri: String, maxEdge: Int): Bitmap {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri)).use { stream ->
                if (stream == null) throw java.io.IOException("Could not open background image")
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw java.io.IOException("Background image could not be decoded")
            }
            var sample = 1
            while (max(options.outWidth / sample, options.outHeight / sample) > maxEdge) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { stream ->
                if (stream == null) throw java.io.IOException("Could not open background image")
                BitmapFactory.decodeStream(stream, null, decode)
                    ?: throw java.io.IOException("Background image decode failed")
            }
            try {
                val orientation = context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.setRotate(90f)
                        matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.setRotate(270f)
                        matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                    else -> return bitmap
                }
                val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (upright !== bitmap) bitmap.recycle()
                return upright
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

        private val VERTEX = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """.trimIndent()

        private fun identityProgram(fragment: String): GlProgram {
            val program = GlProgram(VERTEX, fragment)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            return program
        }
    }

    private class PassthroughShaderProgram : BaseGlShaderProgram(false, 1) {
        private val glProgram = identityProgram(
            """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }
            """.trimIndent()
        )

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private class SolidShaderProgram(colorArgb: Int) : BaseGlShaderProgram(false, 1) {
        private val glProgram = identityProgram(
            """
            #version 100
            precision mediump float;
            uniform vec4 uColor;
            varying vec2 vTexSamplingCoord;
            void main() { gl_FragColor = uColor; }
            """.trimIndent()
        )

        init {
            val r = Color.red(colorArgb) / 255f
            val g = Color.green(colorArgb) / 255f
            val b = Color.blue(colorArgb) / 255f
            val a = Color.alpha(colorArgb) / 255f
            glProgram.setFloatsUniform("uColor", floatArrayOf(r, g, b, a))
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    /**
     * Downscale-sample then stretch: same visual recipe as
     * [expo.modules.memegetbg.MemeImageRenderer]'s blurred-source background.
     */
    private class BlurShaderProgram(private val blurScale: Float) : BaseGlShaderProgram(false, 1) {
        private val glProgram = identityProgram(
            """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uSmallSize;
            varying vec2 vTexSamplingCoord;
            void main() {
              vec2 coordinate = vTexSamplingCoord * uSmallSize - 0.5;
              vec2 base = floor(coordinate);
              vec2 weight = fract(coordinate);
              vec2 low = clamp((base + 0.5) / uSmallSize, 0.0, 1.0);
              vec2 high = clamp((base + 1.5) / uSmallSize, 0.0, 1.0);
              vec4 lower = mix(texture2D(uTexSampler, low),
                               texture2D(uTexSampler, vec2(high.x, low.y)), weight.x);
              vec4 upper = mix(texture2D(uTexSampler, vec2(low.x, high.y)),
                               texture2D(uTexSampler, high), weight.x);
              gl_FragColor = mix(lower, upper, weight.y);
            }
            """.trimIndent()
        )

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val factor = 1f + blurScale * MAX_BLUR_DOWNSCALE
            val smallW = max(MIN_BLUR_EDGE, (inputWidth / factor).roundToInt()).coerceAtMost(inputWidth)
            val smallH = max(MIN_BLUR_EDGE, (inputHeight / factor).roundToInt()).coerceAtMost(inputHeight)
            glProgram.setFloatsUniform("uSmallSize", floatArrayOf(smallW.toFloat(), smallH.toFloat()))
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private class ImageCoverShaderProgram(private val bitmap: Bitmap) : BaseGlShaderProgram(false, 1) {
        private val glProgram = identityProgram(
            """
            #version 100
            precision mediump float;
            uniform sampler2D uImageSampler;
            uniform vec2 uScale;
            varying vec2 vTexSamplingCoord;
            void main() {
              // Cover-fit: scale image so both axes fill, center crop.
              vec2 uv = (vTexSamplingCoord - 0.5) * uScale + 0.5;
              uv.y = 1.0 - uv.y;
              gl_FragColor = texture2D(uImageSampler, uv);
            }
            """.trimIndent()
        )
        private var imageTexId = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (imageTexId == 0) {
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                imageTexId = textures[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }
            val frameAspect = inputWidth.toFloat() / max(1, inputHeight)
            val imageAspect = bitmap.width.toFloat() / max(1, bitmap.height)
            // Cover scale in UV space.
            val scaleX: Float
            val scaleY: Float
            if (imageAspect > frameAspect) {
                scaleY = 1f
                scaleX = frameAspect / imageAspect
            } else {
                scaleX = 1f
                scaleY = imageAspect / frameAspect
            }
            glProgram.setFloatsUniform("uScale", floatArrayOf(scaleX, scaleY))
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uImageSampler", imageTexId, 1)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            if (imageTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0)
                imageTexId = 0
            }
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }
}
