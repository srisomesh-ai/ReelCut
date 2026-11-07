package com.srisomesh.reelcut.engine

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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** What one shot needs to draw itself: look, transition, punches, bass hits. */
class ShotFx(
    val style: Style,
    val shot: Shot,
    val isImage: Boolean,
    val dur: Double,
    val hits: List<Hit>,          // relative to the shot start
    val fadeOutAtEnd: Boolean,
)

@UnstableApi
class LookEffect(private val fx: ShotFx) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LookShaderProgram(fx, useHdr)
}

private fun ease(x: Double): Double = if (x < 0.5) 2 * x * x else 1 - Math.pow(-2 * x + 2, 2.0) / 2

@UnstableApi
class LookShaderProgram(private val fx: ShotFx, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1080
    private var height = 1920
    private var firstUs = Long.MIN_VALUE

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth; height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val t = max(0.0, (presentationTimeUs - firstUs) / 1e6)
        val look = fx.style.look
        val sh = fx.shot
        val p = (t / fx.dur).coerceIn(0.0, 1.0)

        // bass-hit pulses, punch-in on the cut, shake
        var pulse = 0.0
        var shake = 0.0
        for (h in fx.hits) {
            val dt = t - h.t
            if (dt >= 0 && dt < 0.3 && h.t > 0.05) {
                pulse += 0.045 * h.s * exp(-dt / 0.08)
                shake += h.s * exp(-dt / 0.09)
            }
        }
        if (sh.punch) pulse += 0.14 * exp(-t / 0.09)
        if (!fx.style.energetic) pulse *= 0.3
        val amp = if ((look.shake || fx.style.heavy) && fx.style.energetic) 16.0 * shake else 0.0
        var offX = amp * sin(t * 91) / 720.0
        var offY = amp * cos(t * 73) / 1280.0

        var zoom = when {
            fx.isImage -> 1.04 + 0.12 * (if (sh.kb > 0) p else 1 - p)
            fx.style.energetic -> 1.0
            else -> 1.0 + 0.05 * p
        }
        if (fx.isImage) {
            offX += sh.panX * 40 * (p - 0.5) / 720.0
            offY += sh.panY * 60 * (p - 0.5) / 1280.0
        }
        zoom += pulse

        var flash = 0.0
        var dark = 0.0
        var blur = 0.0
        when (sh.trans) {
            Trans.FLASH -> if (t < 0.14) flash = 0.75 * (1 - t / 0.14)
            Trans.FADE -> if (t < 0.35) dark = 1 - ease(t / 0.35)
            Trans.ZOOM -> if (t < 0.3) zoom += 0.35 * (1 - ease(t / 0.3))
            Trans.WHIP -> if (t < 0.18) { val e = 1 - ease(t / 0.18); offX += 0.9 * e; blur = 0.012 * e }
            Trans.CUT -> {}
        }
        if (fx.fadeOutAtEnd && fx.dur - t < 0.5) dark = max(dark, 1 - (fx.dur - t) / 0.5)

        val tint = look.tint
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uZoom", zoom.toFloat())
            program.setFloatsUniform("uOffset", floatArrayOf(offX.toFloat(), offY.toFloat()))
            program.setFloatUniform("uBlur", blur.toFloat())
            program.setFloatUniform("uContrast", look.contrast)
            program.setFloatUniform("uSat", look.saturation)
            program.setFloatUniform("uBright", look.brightness)
            program.setFloatsUniform("uTint", floatArrayOf((tint shr 16 and 255) / 255f, (tint shr 8 and 255) / 255f, (tint and 255) / 255f))
            program.setFloatUniform("uTintAmt", look.tintAmount)
            program.setFloatUniform("uMono", if (look.mono) 1f else 0f)
            program.setFloatUniform("uVig", look.vignette)
            program.setFloatUniform("uAspect", width.toFloat() / height)
            program.setFloatUniform("uFlash", flash.toFloat())
            program.setFloatUniform("uDark", dark.coerceIn(0.0, 1.0).toFloat())
            program.setFloatUniform("uBars", if (look.bars) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
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
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform float uZoom;
uniform vec2 uOffset;
uniform float uBlur;
uniform float uContrast;
uniform float uSat;
uniform float uBright;
uniform vec3 uTint;
uniform float uTintAmt;
uniform float uMono;
uniform float uVig;
uniform float uAspect;
uniform float uFlash;
uniform float uDark;
uniform float uBars;
varying vec2 vTexSamplingCoord;

vec3 samp(vec2 uv) {
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
  return texture2D(uTexSampler, uv).rgb;
}
vec3 softLight(vec3 b, vec3 s) {
  vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
  vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
  return mix(lo, hi, step(0.5, s));
}
void main() {
  vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 - uOffset;
  vec3 c = samp(uv);
  if (uBlur > 0.0001) {
    c = (c + samp(uv + vec2(uBlur, 0.0)) + samp(uv - vec2(uBlur, 0.0))
           + samp(uv + vec2(2.0 * uBlur, 0.0)) + samp(uv - vec2(2.0 * uBlur, 0.0))) / 5.0;
  }
  c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = clamp(mix(vec3(l), c, uSat), 0.0, 1.0);
  c = mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), uMono);
  c = clamp(c * uBright, 0.0, 1.0);
  c = mix(c, softLight(c, uTint), uTintAmt);
  vec2 p = (vTexSamplingCoord - 0.5) * vec2(uAspect, 1.0);
  c *= 1.0 - uVig * smoothstep(0.28, 0.72, length(p));
  c = mix(c, vec3(1.0), uFlash);
  float y = vTexSamplingCoord.y;
  float bar = clamp((1.0 - step(0.07, y)) + step(0.93, y), 0.0, 1.0);
  c *= 1.0 - uBars * bar;
  c *= 1.0 - uDark;
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
