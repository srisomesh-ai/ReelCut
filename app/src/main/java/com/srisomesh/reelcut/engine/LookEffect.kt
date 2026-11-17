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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * What one shot needs to draw itself: look, transition, punches, bass hits.
 * A "tail" ([tOffset] > 0) is the previous shot carrying on under the next one while it dissolves in
 * (the lower video layer); [zoomTail] = it zooms through as it fades.
 */
class ShotFx(
    val style: Style,
    val shot: Shot,
    val isImage: Boolean,
    val dur: Double,
    val hits: List<Hit>,          // relative to the shot start
    val fadeOutAtEnd: Boolean,
    val tOffset: Double = 0.0,
    val zoomTail: Boolean = false,
    val index: Int = 0,
)

@UnstableApi
class LookEffect(private val fx: ShotFx) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LookShaderProgram(fx, useHdr)
}

internal fun ease(x: Double): Double = if (x < 0.5) 2 * x * x else 1 - Math.pow(-2 * x + 2, 2.0) / 2

/** Per clip: zoom / pan / shake / whip / glitch and the look's colour filter (sepia, contrast, saturation, brightness, mono). */
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
        val elapsed = max(0.0, (presentationTimeUs - firstUs) / 1e6)
        val isTail = fx.tOffset > 0
        val t = fx.tOffset + elapsed                  // time since this shot started
        val st = fx.style
        val look = st.look
        val sh = fx.shot
        val p = (t / fx.dur).coerceIn(0.0, 1.0)

        // bass-hit pulses, punch-in on the cut, shake (fast only; none at smooth speed)
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
        if (!st.energetic) pulse *= if (st.smooth) 0.0 else 0.3
        val amp = if ((look.shake || st.heavy) && st.energetic) 16.0 * shake else 0.0
        var offX = amp * sin(t * 91) / 720.0
        var offY = amp * cos(t * 73) / 1280.0

        // photos zoom and drift slowly (Ken Burns); videos breathe a little when not fast
        var zoom = when {
            fx.isImage -> 1.04 + 0.12 * (if (sh.kb > 0) p else 1 - p)
            st.energetic -> 1.0
            else -> 1.0 + 0.05 * p
        }
        if (fx.isImage) {
            offX += sh.panX * 40 * (p - 0.5) / 720.0
            offY += sh.panY * 60 * (p - 0.5) / 1280.0
        }
        zoom += pulse

        var blur = 0.0
        var glitch = 0f
        var seed = 0f
        if (isTail) {
            // zoom-through: the old shot grows as the new one fades in over it
            if (fx.zoomTail) zoom += st.zoomAmt * ease((elapsed / st.zoomLen).coerceIn(0.0, 1.0))
        } else when (sh.trans) {
            Trans.WHIP -> if (t < 0.18) { val e = 1 - ease(t / 0.18); offX += 0.9 * e; blur = 0.012 * e }
            Trans.GLITCH -> if (t < 0.16) { glitch = 1f; seed = (fx.index * 977 + floor(t * 60).toInt()).toFloat() }
            else -> {}   // CUT; FLASH is drawn over the whole reel; FADE / ZOOM use the two video layers
        }

        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uZoom", zoom.toFloat())
            program.setFloatsUniform("uOffset", floatArrayOf(offX.toFloat(), offY.toFloat()))
            program.setFloatUniform("uBlur", blur.toFloat())
            program.setFloatUniform("uGlitch", glitch)
            program.setFloatUniform("uSeed", seed)
            program.setFloatUniform("uSepia", look.sepia)
            program.setFloatUniform("uContrast", look.contrast)
            program.setFloatUniform("uSat", look.saturation)
            program.setFloatUniform("uBright", look.brightness)
            program.setFloatUniform("uMono", if (look.mono) 1f else 0f)
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
        internal const val VERTEX = """
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
uniform float uGlitch;
uniform float uSeed;
uniform float uSepia;
uniform float uContrast;
uniform float uSat;
uniform float uBright;
uniform float uMono;
varying vec2 vTexSamplingCoord;

vec3 samp(vec2 uv) {
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
  return texture2D(uTexSampler, uv).rgb;
}
float h1(float x) { return fract(sin(x) * 43758.5453); }
void main() {
  vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 - uOffset;
  vec3 c = samp(uv);
  if (uBlur > 0.0001) {
    c = (c + samp(uv + vec2(uBlur, 0.0)) + samp(uv - vec2(uBlur, 0.0))
           + samp(uv + vec2(2.0 * uBlur, 0.0)) + samp(uv - vec2(2.0 * uBlur, 0.0))) / 5.0;
  }
  if (uGlitch > 0.5) {
    // glitch cut: 7 slices of the picture slide sideways, a shifted copy glows on top
    float shift = 0.0;
    for (int k = 0; k < 7; k++) {
      float fk = float(k);
      float y0 = h1(uSeed * 1.31 + fk * 3.17);
      float hh = 0.02 + h1(uSeed * 0.71 + fk * 5.73) * 0.08;
      float dx = (h1(uSeed * 0.37 + fk * 9.31) - 0.5) * 0.12;
      if (vTexSamplingCoord.y >= y0 && vTexSamplingCoord.y < y0 + hh) shift = dx;
    }
    c = samp(uv - vec2(shift, 0.0));
    vec3 g = samp(uv - vec2(0.012, 0.0)) * 0.35;
    c = 1.0 - (1.0 - c) * (1.0 - g);
  }
  // colour filter, in the web page's order: sepia, contrast, saturation, brightness (mono = grayscale)
  vec3 sep = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131)));
  c = mix(c, clamp(sep, 0.0, 1.0), uSepia);
  c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = clamp(mix(vec3(l), c, uSat), 0.0, 1.0);
  c = mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), uMono);
  c = clamp(c * uBright, 0.0, 1.0);
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
