package com.memeget.nativeapp.editor

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.ceil
import kotlin.math.max

/** One ordered mosaic pass over the actual, already-composited frame. */
@OptIn(UnstableApi::class)
internal class VideoPixelateEffect(
    private val regionProvider: (presentationTimeUs: Long, region: PixelateRegion) -> Boolean
) : GlEffect {
    class PixelateRegion {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var cellPx = 1f
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Mosaic overlays require SDR tone mapping")
        return MosaicProgram(regionProvider)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false

    private class MosaicProgram(
        private val regionProvider: (Long, PixelateRegion) -> Boolean
    ) : BaseGlShaderProgram(false, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT)
        private val region = PixelateRegion()
        private val frameSize = FloatArray(2)
        private val bounds = FloatArray(4)
        private val grid = FloatArray(2)

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            frameSize[0] = inputWidth.toFloat()
            frameSize[1] = inputHeight.toFloat()
            program.setFloatsUniform("uFrameSize", frameSize)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                var enabled = regionProvider(presentationTimeUs, region)
                if (enabled) {
                    bounds[0] = (region.left * frameSize[0]).toInt().toFloat().coerceIn(0f, frameSize[0])
                    bounds[1] = (region.top * frameSize[1]).toInt().toFloat().coerceIn(0f, frameSize[1])
                    bounds[2] = ceil(region.right * frameSize[0]).coerceIn(bounds[0], frameSize[0])
                    bounds[3] = ceil(region.bottom * frameSize[1]).coerceIn(bounds[1], frameSize[1])
                    val width = bounds[2] - bounds[0]
                    val height = bounds[3] - bounds[1]
                    enabled = width > 0f && height > 0f
                    if (enabled) {
                        val cell = max(region.cellPx, RenderPlanBuilder.mosaicCellFloorPx(width.toInt(), height.toInt()).toFloat()).coerceAtLeast(1f)
                        grid[0] = ceil(width / cell).coerceAtLeast(1f)
                        grid[1] = ceil(height / cell).coerceAtLeast(1f)
                    }
                }
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setIntUniform("uEnabled", if (enabled) 1 else 0)
                program.setFloatsUniform("uRegion", bounds)
                program.setFloatsUniform("uGrid", grid)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        override fun release() {
            try {
                super.release()
            } finally {
                program.delete()
            }
        }
    }

    companion object {
        private val VERTEX = """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vUv;
            void main() {
              gl_Position = aFramePosition;
              vUv = aFramePosition.xy * 0.5 + 0.5;
            }
        """.trimIndent()
        private val FRAGMENT = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform int uEnabled;
            uniform vec2 uFrameSize;
            uniform vec4 uRegion;
            uniform vec2 uGrid;
            varying vec2 vUv;
            void main() {
              vec2 point = vec2(vUv.x, 1.0 - vUv.y) * uFrameSize;
              vec2 uv = vUv;
              if (uEnabled == 1 && point.x >= uRegion.x && point.y >= uRegion.y &&
                  point.x < uRegion.z && point.y < uRegion.w) {
                vec2 extent = uRegion.zw - uRegion.xy;
                vec2 index = min(floor((point - uRegion.xy) * uGrid / extent), uGrid - 1.0);
                vec2 start = floor(index * extent / uGrid);
                vec2 end = floor((index + 1.0) * extent / uGrid);
                vec2 samplePoint = uRegion.xy + floor((start + end) * 0.5) + 0.5;
                uv = samplePoint / uFrameSize;
                uv.y = 1.0 - uv.y;
              }
              gl_FragColor = texture2D(uTexSampler, uv);
            }
        """.trimIndent()
    }
}
