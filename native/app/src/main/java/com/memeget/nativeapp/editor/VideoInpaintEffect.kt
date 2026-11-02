package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
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
import kotlin.math.floor

/**
 * Removes a logo or watermark inside one rectangle of the frame. With a [mask] (the logo's own pixels, stretched
 * over the rectangle) only those pixels change: each is filled from the nearest unmasked pixels in sixteen
 * directions, weighted by distance, so the picture between and around the logo's strokes is kept as it is.
 * Without a mask the whole rectangle is filled the same way. Single pass on the GPU, so preview and export stay
 * real-time.
 */
@OptIn(UnstableApi::class)
internal class VideoInpaintEffect(
    private val mask: Triple<ByteArray, Int, Int>?,
    private val regionProvider: (presentationTimeUs: Long, region: InpaintRegion) -> Boolean
) : GlEffect {
    /** The rectangle in normalized frame coordinates; it may run past the frame edge. */
    class InpaintRegion {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Logo removal requires SDR tone mapping")
        return FillProgram(mask, regionProvider)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false

    private class FillProgram(
        mask: Triple<ByteArray, Int, Int>?,
        private val regionProvider: (Long, InpaintRegion) -> Boolean
    ) : BaseGlShaderProgram(false, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT)
        private val region = InpaintRegion()
        private val frameSize = FloatArray(2)
        private val rect = FloatArray(4)
        private val hasMask = mask != null
        private val maskTexture: Int

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            maskTexture = uploadMask(mask)
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
                    rect[0] = floor(region.left * frameSize[0])
                    rect[1] = floor(region.top * frameSize[1])
                    rect[2] = ceil(region.right * frameSize[0])
                    rect[3] = ceil(region.bottom * frameSize[1])
                    enabled = rect[2] > rect[0] && rect[3] > rect[1] &&
                        rect[2] > 0f && rect[3] > 0f && rect[0] < frameSize[0] && rect[1] < frameSize[1]
                }
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uMask", maskTexture, 1)
                program.setIntUniform("uEnabled", if (enabled) 1 else 0)
                program.setIntUniform("uHasMask", if (hasMask) 1 else 0)
                program.setFloatsUniform("uRegion", rect)
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
                GLES20.glDeleteTextures(1, intArrayOf(maskTexture), 0)
            }
        }

        /** The mask as a texture (one opaque pixel when there is none, since the sampler must name a texture). */
        private fun uploadMask(mask: Triple<ByteArray, Int, Int>?): Int {
            val (bytes, w, h) = mask ?: Triple(byteArrayOf(-1), 1, 1)
            val colors = IntArray(w * h) {
                val m = bytes[it].toInt() and 0xff
                (0xff shl 24) or (m shl 16) or (m shl 8) or m
            }
            val bitmap = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            return ids[0]
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

        // Coordinates are top-down pixels. uRegion is the rectangle (left, top, right, bottom), unclamped; the
        // mask texture's first row is the rectangle's top.
        private val FRAGMENT = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uMask;
            uniform int uEnabled;
            uniform int uHasMask;
            uniform vec2 uFrameSize;
            uniform vec4 uRegion;
            varying vec2 vUv;
            vec3 fetch(vec2 p) {
              vec2 uv = clamp(p, vec2(0.5), uFrameSize - 0.5) / uFrameSize;
              uv.y = 1.0 - uv.y;
              return texture2D(uTexSampler, uv).rgb;
            }
            float maskAt(vec2 p) {
              if (p.x < uRegion.x || p.y < uRegion.y || p.x >= uRegion.z || p.y >= uRegion.w) return 0.0;
              if (uHasMask == 0) return 1.0;
              return texture2D(uMask, (p - uRegion.xy) / (uRegion.zw - uRegion.xy)).r;
            }
            void main() {
              vec4 color = texture2D(uTexSampler, vUv);
              vec2 point = vec2(vUv.x, 1.0 - vUv.y) * uFrameSize;
              float m = uEnabled == 1 ? maskAt(point) : 0.0;
              if (m > 0.004) {
                vec2 extent = uRegion.zw - uRegion.xy;
                float stride = max(1.0, max(extent.x, extent.y) / 48.0);
                vec3 samples[16];
                float weight[16];
                vec3 blend = vec3(0.0);
                float weights = 0.0;
                float valid = 0.0;
                for (int d = 0; d < 16; d++) {
                  samples[d] = vec3(0.0);
                  weight[d] = 0.0;
                  float angle = float(d) * 0.39269908;
                  vec2 dir = vec2(cos(angle), sin(angle));
                  for (int s = 1; s <= 48; s++) {
                    float dist = float(s) * stride;
                    vec2 q = point + dir * dist;
                    if (q.x < 0.0 || q.y < 0.0 || q.x >= uFrameSize.x || q.y >= uFrameSize.y) break;
                    if (maskAt(q) < 0.25) {
                      samples[d] = fetch(q + dir);
                      weight[d] = 1.0 / (dist * dist);
                      blend += weight[d] * samples[d];
                      weights += weight[d];
                      valid += 1.0;
                      break;
                    }
                  }
                }
                if (weights > 0.0) {
                  // A flat background (most samples one colour, as on a caption bar) fills with that colour, so
                  // letters cut by the logo end cleanly instead of smearing into it.
                  vec3 best = vec3(0.0);
                  float bestCount = 0.0;
                  for (int i = 0; i < 16; i++) {
                    if (weight[i] <= 0.0) continue;
                    vec3 sum = vec3(0.0);
                    float count = 0.0;
                    for (int j = 0; j < 16; j++) {
                      vec3 gap = abs(samples[i] - samples[j]);
                      if (weight[j] > 0.0 && max(gap.r, max(gap.g, gap.b)) < 0.157) {
                        sum += samples[j];
                        count += 1.0;
                      }
                    }
                    if (count > bestCount) {
                      bestCount = count;
                      best = sum / count;
                    }
                  }
                  vec3 fill = bestCount >= max(1.0, 0.6 * valid) ? best : blend / weights;
                  color.rgb = mix(color.rgb, fill, clamp(m * 1.5, 0.0, 1.0));
                }
              }
              gl_FragColor = color;
            }
        """.trimIndent()
    }
}
