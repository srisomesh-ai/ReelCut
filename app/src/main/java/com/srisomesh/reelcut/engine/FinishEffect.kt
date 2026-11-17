package com.srisomesh.reelcut.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlaySettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The look over the whole reel, after the clips (and their dissolves) are put together - same order as the web page:
 * tint, colour wash, faded blacks, light leak, vignette, grain, TV lines, flash, bars.
 * [endFadeOnly] = a second pass after the title / stamp that only fades the end to black.
 */
@UnstableApi
class FinishEffect(
    private val look: Look,
    private val flashAt: List<Double>,   // timeline times of flash cuts
    private val total: Double,
    private val endFadeOnly: Boolean = false,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FinishShaderProgram(look, flashAt, total, endFadeOnly, useHdr)
}

@UnstableApi
class FinishShaderProgram(
    private val look: Look,
    private val flashAt: List<Double>,
    private val total: Double,
    private val endFadeOnly: Boolean,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(LookShaderProgram.VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var w = 1080
    private var h = 1920
    private var firstUs = Long.MIN_VALUE
    private val rnd = Random(7)

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun rgb(c: Int) = floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val tt = max(0.0, (presentationTimeUs - firstUs) / 1e6)
        val on = if (endFadeOnly) 0f else 1f

        var flash = 0.0
        if (!endFadeOnly) for (f in flashAt) { val l = tt - f; if (l >= 0 && l < 0.14) flash = 0.75 * (1 - l / 0.14) }
        val dark = if (endFadeOnly && total - tt < 0.5) (1 - (total - tt) / 0.5).coerceIn(0.0, 1.0) else 0.0
        // light leak drifts across the picture (web: x 15-85 %, y 10-40 %)
        val lx = w * (0.15 + 0.7 * (0.5 + 0.5 * sin(tt * 0.6)))
        val ly = h * (0.25 + 0.15 * sin(tt * 0.9))
        val leakA = if (look.leak) 0.28 + 0.12 * sin(tt * 1.7) else 0.0

        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uSize", floatArrayOf(w.toFloat(), h.toFloat()))
            program.setFloatsUniform("uTint", rgb(look.tint))
            program.setFloatUniform("uTintAmt", look.tintAmount * on)
            program.setFloatUniform("uTintMode", look.tintMode.toFloat())
            program.setFloatsUniform("uGradTop", rgb(look.gradTop))
            program.setFloatsUniform("uGradBot", rgb(look.gradBottom))
            program.setFloatUniform("uGradAmt", look.gradAmount * on)
            program.setFloatUniform("uLift", look.lift * on)
            program.setFloatsUniform("uLeakPos", floatArrayOf(lx.toFloat(), ly.toFloat()))
            program.setFloatUniform("uLeakA", (leakA * on).toFloat())
            program.setFloatUniform("uVig", look.vignette * on)
            program.setFloatUniform("uGrain", look.grain * on)
            program.setFloatUniform("uSeed", rnd.nextFloat() * 1000f)
            program.setFloatUniform("uScan", look.scan * on)
            program.setFloatUniform("uFlash", flash.toFloat())
            program.setFloatUniform("uBars", if (look.bars) on else 0f)
            program.setFloatUniform("uDark", dark.toFloat())
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
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform vec3 uTint;
uniform float uTintAmt;
uniform float uTintMode;
uniform vec3 uGradTop;
uniform vec3 uGradBot;
uniform float uGradAmt;
uniform float uLift;
uniform vec2 uLeakPos;
uniform float uLeakA;
uniform float uVig;
uniform float uGrain;
uniform float uSeed;
uniform float uScan;
uniform float uFlash;
uniform float uBars;
uniform float uDark;
varying vec2 vTexSamplingCoord;

vec3 softLight(vec3 b, vec3 s) {
  vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
  vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
  return mix(lo, hi, step(0.5, s));
}
vec3 overlayB(vec3 b, vec3 s) {
  return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
}
vec3 screenB(vec3 b, vec3 s) { return 1.0 - (1.0 - b) * (1.0 - s); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed) * 43758.5453); }

void main() {
  vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  vec2 px = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uSize;   // pixels from the top-left
  float L = max(uSize.x, uSize.y);

  // tint: soft light / screen / overlay
  vec3 tinted = uTintMode < 0.5 ? softLight(c, uTint) : (uTintMode < 1.5 ? screenB(c, uTint) : overlayB(c, uTint));
  c = mix(c, tinted, uTintAmt);
  // colour wash top -> bottom
  vec3 g = mix(uGradTop, uGradBot, px.y / uSize.y);
  c = mix(c, softLight(c, g), uGradAmt);
  // faded blacks
  c = max(c, vec3(uLift));
  // light leak: a warm glow that drifts
  float r = distance(px, uLeakPos) / (0.45 * L);
  vec4 lk = r < 0.45 ? mix(vec4(1.0, 0.667, 0.353, 0.9), vec4(1.0, 0.353, 0.235, 0.35), r / 0.45)
                     : mix(vec4(1.0, 0.353, 0.235, 0.35), vec4(1.0, 0.235, 0.235, 0.0), clamp((r - 0.45) / 0.55, 0.0, 1.0));
  c = screenB(c, lk.rgb * lk.a * uLeakA);
  // vignette
  float d = distance(px, uSize * 0.5) / L;
  c *= 1.0 - uVig * clamp((d - 0.28) / 0.44, 0.0, 1.0);
  // film grain
  float n = hash(floor(px / 1.5));
  c = mix(c, overlayB(c, vec3(n)), uGrain);
  // TV lines
  float period = max(2.0, floor(uSize.y / 320.0 + 0.5));
  c *= 1.0 - uScan * step(mod(px.y, period), period * 0.5 - 0.01);
  // flash on the beat, cinema bars, end fade
  c = mix(c, vec3(1.0), uFlash);
  float bar = clamp((1.0 - step(0.07, vTexSamplingCoord.y)) + step(0.93, vTexSamplingCoord.y), 0.0, 1.0);
  c *= 1.0 - uBars * bar;
  c *= 1.0 - uDark;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}

/** The title (first 2.4 s, with glow) and the VHS "PLAY ▶" + time code, drawn over the reel. */
@UnstableApi
object ReelOverlays {

    /** Title: one picture; it fades in and out by the overlay's alpha. */
    fun title(text: String, look: Look, w: Int, h: Int): BitmapOverlay {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val u = min(w, h) / 720f
        val t = text.trim().uppercase()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var fs = 84f * u
        paint.textSize = fs
        while (paint.measureText(t) > w * 0.86f && fs > 30f) { fs -= 4f; paint.textSize = fs }
        val y = h * 0.74f - (paint.descent() + paint.ascent()) / 2
        // dark outline, then the glowing white text
        val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = 8f * u; color = Color.argb(140, 0, 0, 0) }
        cv.drawText(t, w / 2f, y, stroke)
        paint.color = Color.WHITE
        if (look.glow != 0) paint.setShadowLayer(30f * u, 0f, 0f, look.glow or 0xFF000000.toInt())
        cv.drawText(t, w / 2f, y, paint)
        return object : BitmapOverlay() {
            private var first = Long.MIN_VALUE
            override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                if (first == Long.MIN_VALUE) first = presentationTimeUs
                val tt = (presentationTimeUs - first) / 1e6
                val a = (tt * 4).coerceIn(0.0, 1.0) * ((2.4 - tt) * 3).coerceIn(0.0, 1.0)
                return OverlaySettings.Builder().setAlphaScale(a.toFloat()).build()
            }
        }
    }

    /** "PLAY ▶" top left and 00:00:SS bottom right, a new picture each second. */
    fun stamp(w: Int, h: Int): BitmapOverlay = object : BitmapOverlay() {
        private var first = Long.MIN_VALUE
        private var sec = -1
        private var bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (first == Long.MIN_VALUE) first = presentationTimeUs
            val s = ((presentationTimeUs - first) / 1_000_000L).toInt()
            if (s != sec) {
                sec = s
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(b)
                val u = min(w, h) / 720f
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = (34 * u).roundToInt().toFloat()
                    color = Color.argb(230, 255, 255, 255)
                    setShadowLayer(6f * u, 0f, 0f, Color.argb(153, 0, 0, 0))
                }
                p.textAlign = Paint.Align.LEFT
                cv.drawText("PLAY ▶", 36 * u, 40 * u - p.ascent(), p)
                p.textAlign = Paint.Align.RIGHT
                cv.drawText("00:00:" + s.toString().padStart(2, '0'), w - 36 * u, h - 40 * u - p.descent(), p)
                bmp = b
            }
            return bmp
        }
    }
}
