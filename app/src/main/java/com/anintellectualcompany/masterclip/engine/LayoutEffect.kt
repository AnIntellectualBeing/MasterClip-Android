package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Draws a [LayoutPlan] on the GPU: crop / face-cam stack / blurred fit, straight to 1080x1920. */
@UnstableApi
class LayoutEffect(private val plan: LayoutPlan) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayoutShaderProgram(plan, useHdr)
}

@UnstableApi
private class LayoutShaderProgram(private val plan: LayoutPlan, private val hdr: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ hdr, /* texturePoolCapacity= */ 1) {

    private val main: GlProgram
    private val blur: GlProgram?
    private var inW = 1
    private var inH = 1
    private var firstUs = Long.MIN_VALUE

    // Small textures for the blurred background (only in FIT_BLUR).
    private val smallW = 135
    private val smallH = 240
    private var texA = 0
    private var fboA = 0
    private var texB = 0
    private var fboB = 0

    init {
        try {
            main = GlProgram(VERTEX, MAIN_FRAGMENT)
            main.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            blur = if (plan.mode == LayoutMode.FIT_BLUR) {
                GlProgram(VERTEX, BLUR_FRAGMENT).also {
                    it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                }
            } else null
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        if (blur != null && texA == 0) {
            try {
                texA = GlUtil.createTexture(smallW, smallH, hdr)
                fboA = GlUtil.createFboForTexture(texA)
                texB = GlUtil.createTexture(smallW, smallH, hdr)
                fboB = GlUtil.createFboForTexture(texB)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(plan.outW, plan.outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val t = presentationTimeUs - firstUs
        try {
            if (blur != null) drawBlurredBackground(inputTexId)
            main.use()
            main.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            main.setSamplerTexIdUniform("uBlurTex", if (texB != 0) texB else inputTexId, 1)
            val bottom = plan.bottomAt(t)
            main.setFloatsUniform("uTop", floatArrayOf(plan.top.x, plan.top.y, plan.top.w, plan.top.h))
            main.setFloatsUniform("uBottom", floatArrayOf(bottom.x, bottom.y, bottom.w, bottom.h))
            main.setFloatUniform("uSplit", if (plan.mode == LayoutMode.STACK) plan.split else 0f)
            main.setFloatUniform("uMode", if (plan.mode == LayoutMode.FIT_BLUR) 1f else 0f)
            main.setFloatsUniform("uFg", fitBox())
            main.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Where the whole frame sits in the output for FIT_BLUR (x, y, w, h as 0..1 of the output, y from top). */
    private fun fitBox(): FloatArray {
        val srcAspect = inW.toFloat() / inH
        val outAspect = plan.outW.toFloat() / plan.outH
        return if (srcAspect >= outAspect) {
            val h = outAspect / srcAspect
            floatArrayOf(0f, (1 - h) / 2, 1f, h)
        } else {
            val w = srcAspect / outAspect
            floatArrayOf((1 - w) / 2, 0f, w, 1f)
        }
    }

    /** Zoomed copy of the frame -> tiny texture, blurred across and then down. Cheap on any phone. */
    private fun drawBlurredBackground(inputTexId: Int) {
        val program = blur ?: return
        val savedFbo = IntArray(1)
        val savedViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)
        val cover = LayoutPlanner.centerCrop(inW, inH, plan.outW.toFloat() / plan.outH)

        GlUtil.focusFramebufferUsingCurrentContext(fboA, smallW, smallH)
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatsUniform("uRect", floatArrayOf(cover.x, cover.y, cover.w, cover.h))
        program.setFloatsUniform("uStep", floatArrayOf(cover.w * 0.012f, 0f))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GlUtil.focusFramebufferUsingCurrentContext(fboB, smallW, smallH)
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", texA, 0)
        program.setFloatsUniform("uRect", floatArrayOf(0f, 0f, 1f, 1f))
        program.setFloatsUniform("uStep", floatArrayOf(0f, 1.6f / smallH))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0])
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
    }

    override fun release() {
        super.release()
        try {
            main.delete()
            blur?.delete()
            if (texA != 0) { GlUtil.deleteFbo(fboA); GlUtil.deleteTexture(texA) }
            if (texB != 0) { GlUtil.deleteFbo(fboB); GlUtil.deleteTexture(texB) }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // Output y runs from the top (o.y = 0 is the top edge). Texture v runs from the bottom.
        private const val MAIN_FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uBlurTex;
uniform vec4 uTop;
uniform vec4 uBottom;
uniform vec4 uFg;
uniform float uSplit;
uniform float uMode;
varying vec2 vTexSamplingCoord;

vec4 sampleBox(vec4 box, vec2 local) {
  vec2 s = vec2(box.x + local.x * box.z, box.y + local.y * box.w);
  return texture2D(uTexSampler, vec2(s.x, 1.0 - s.y));
}

void main() {
  vec2 o = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
  if (uMode > 0.5) {
    vec2 local = (o - uFg.xy) / uFg.zw;
    if (local.x >= 0.0 && local.x <= 1.0 && local.y >= 0.0 && local.y <= 1.0) {
      gl_FragColor = texture2D(uTexSampler, vec2(local.x, 1.0 - local.y));
    } else {
      vec4 b = texture2D(uBlurTex, vTexSamplingCoord);
      gl_FragColor = vec4(b.rgb * 0.62, 1.0);
    }
    return;
  }
  if (o.y < uSplit) {
    gl_FragColor = sampleBox(uTop, vec2(o.x, o.y / uSplit));
  } else {
    float ly = (o.y - uSplit) / (1.0 - uSplit);
    gl_FragColor = sampleBox(uBottom, vec2(o.x, ly));
  }
  // A thin dark line between the face cam and the game.
  if (uSplit > 0.0 && abs(o.y - uSplit) < 0.0016) {
    gl_FragColor = vec4(0.06, 0.05, 0.05, 1.0);
  }
}
"""

        // One direction of a 13-tap gaussian, reading the part [uRect] of the source.
        private const val BLUR_FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec4 uRect;
uniform vec2 uStep;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 o = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
  vec2 s = vec2(uRect.x + o.x * uRect.z, uRect.y + o.y * uRect.w);
  vec2 uv = vec2(s.x, 1.0 - s.y);
  vec2 st = vec2(uStep.x, -uStep.y);
  vec4 c = texture2D(uTexSampler, uv) * 0.1597;
  c += (texture2D(uTexSampler, uv + st * 1.0) + texture2D(uTexSampler, uv - st * 1.0)) * 0.1474;
  c += (texture2D(uTexSampler, uv + st * 2.0) + texture2D(uTexSampler, uv - st * 2.0)) * 0.1158;
  c += (texture2D(uTexSampler, uv + st * 3.0) + texture2D(uTexSampler, uv - st * 3.0)) * 0.0776;
  c += (texture2D(uTexSampler, uv + st * 4.0) + texture2D(uTexSampler, uv - st * 4.0)) * 0.0443;
  c += (texture2D(uTexSampler, uv + st * 5.0) + texture2D(uTexSampler, uv - st * 5.0)) * 0.0216;
  c += (texture2D(uTexSampler, uv + st * 6.0) + texture2D(uTexSampler, uv - st * 6.0)) * 0.0090;
  gl_FragColor = c / 0.9911;
}
"""
    }
}
