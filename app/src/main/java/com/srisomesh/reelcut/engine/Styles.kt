package com.srisomesh.reelcut.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A colour look, the same 13 as the ReelCut web page (web/index.html LOOKS).
 * Per clip ([LookShaderProgram]): sepia, contrast, saturation, brightness, mono.
 * Whole reel ([FinishShaderProgram]): tint, colour wash, faded blacks, light leak, vignette, grain, TV lines, flash, bars;
 * the title (with glow) and the VHS stamp are drawn on top ([ReelOverlays]).
 */
data class Look(
    val key: String,
    val name: String,
    val contrast: Float,
    val saturation: Float,
    val brightness: Float,
    val tint: Int,          // 0xRRGGBB
    val tintAmount: Float,
    val mono: Boolean,
    val vignette: Float,
    val flash: Boolean,
    val shake: Boolean,
    val bars: Boolean,
    val sepia: Float = 0f,
    val tintMode: Int = SOFT_LIGHT,
    val gradTop: Int = 0,           // colour wash top -> bottom (soft light); gradAmount 0 = none
    val gradBottom: Int = 0,
    val gradAmount: Float = 0f,
    val lift: Float = 0f,           // faded blacks: darkest level 0..1
    val leak: Boolean = false,      // drifting warm light
    val grain: Float = 0f,
    val scan: Float = 0f,           // TV lines
    val stamp: Boolean = false,     // "PLAY" + time code
    val glitch: Boolean = false,    // glitch cuts
    val glow: Int = 0,              // title glow colour, 0 = none
) {
    companion object {
        const val SOFT_LIGHT = 0
        const val SCREEN = 1
        const val OVERLAY = 2
    }
}

object Looks {
    val all: List<Look> = listOf(
        Look("neon", "Neon Night", 1.15f, 1.55f, 1.03f, 0xFF2BD6, 0.10f, false, 0.45f, flash = true, shake = false, bars = false, glow = 0xFF2BD6),
        Look("street", "Street Punch", 1.30f, 1.20f, 1.00f, 0xFFB000, 0.08f, false, 0.55f, flash = true, shake = true, bars = false, tintMode = Look.OVERLAY),
        Look("teal", "Teal Cinema", 1.20f, 0.88f, 1.00f, 0x009AA6, 0.16f, false, 0.50f, flash = false, shake = false, bars = true),
        Look("mono", "Mono Drama", 1.35f, 0.00f, 1.00f, 0x808080, 0.00f, true, 0.60f, flash = true, shake = false, bars = false),
        Look("golden", "Golden Warm", 1.06f, 1.25f, 1.04f, 0xFF9A3C, 0.12f, false, 0.30f, flash = false, shake = false, bars = false, sepia = 0.18f),
        Look("dreamy", "Dreamy Soft", 0.94f, 1.12f, 1.07f, 0xFFC0D9, 0.10f, false, 0.25f, flash = false, shake = false, bars = false, tintMode = Look.SCREEN),
        Look("vintage", "Vintage Film", 1.05f, 0.90f, 1.02f, 0xFFCF8A, 0.10f, false, 0.55f, flash = false, shake = false, bars = false, sepia = 0.35f, grain = 0.22f, leak = true),
        Look("vhs", "Retro VHS", 1.12f, 1.35f, 1.00f, 0x7B2CFF, 0.07f, false, 0.40f, flash = false, shake = false, bars = false, tintMode = Look.SCREEN, scan = 0.16f, grain = 0.12f, glitch = true, stamp = true),
        Look("pop", "Pop Colour", 1.12f, 1.80f, 1.04f, 0, 0f, false, 0.20f, flash = true, shake = false, bars = false),
        Look("blue", "Cool Blue", 1.12f, 0.95f, 1.02f, 0x2F6BFF, 0.16f, false, 0.45f, flash = false, shake = false, bars = false),
        Look("sunset", "Sunset Glow", 1.05f, 1.25f, 1.04f, 0, 0f, false, 0.35f, flash = false, shake = false, bars = false, gradTop = 0xFF7A45, gradBottom = 0xFF2B8A, gradAmount = 0.2f, leak = true),
        Look("matte", "Matte Film", 0.90f, 0.80f, 1.03f, 0, 0f, false, 0.30f, flash = false, shake = false, bars = false, lift = 34f / 255f, grain = 0.10f),
        Look("bolly", "Bollywood Gold", 1.10f, 1.35f, 1.05f, 0xFFB23C, 0.12f, false, 0.35f, flash = true, shake = false, bars = false, leak = true, glow = 0xFFB23C),
    )
    fun byKey(k: String) = all.first { it.key == k }
}

/** Cut speed (web page "Cut speed"): smooth = ~3-4 s shots with soft dissolves; normal ~1.4-2.6 s; fast = on the beat. */
enum class Pace(val label: String) { AUTO("Auto"), SMOOTH("Smooth"), NORMAL("Normal"), FAST("Fast") }

class Style(
    val look: Look,
    val energetic: Boolean,
    val bright: Boolean,
    val heavy: Boolean,
    val calm: Int,   // beats per cut in calm parts
    val hot: Int,    // beats per cut in loud parts
    val pace: Pace = if (energetic) Pace.FAST else Pace.NORMAL,
    val beat: Double = 0.5,
) {
    val smooth: Boolean get() = pace == Pace.SMOOTH
    /** Dissolve length (smooth: about a third of the shot), zoom-through length and amount. */
    val fadeLen: Double get() = if (smooth) (0.35 * calm * beat).coerceIn(0.6, 1.1) else 0.35
    val zoomLen: Double get() = if (smooth) 0.8 else 0.3
    val zoomAmt: Double get() = if (smooth) 0.25 else 0.6
    /** Shortest / longest shot the automatic edit makes at this pace (s). */
    val minShot: Double get() = when (pace) { Pace.SMOOTH -> 1.2; Pace.NORMAL -> 0.9; else -> Styles.MIN_SHOT }
    val maxShot: Double get() = when (pace) { Pace.SMOOTH -> 4.6; Pace.NORMAL -> 2.7; else -> Styles.MAX_SHOT }
}

enum class Trans { CUT, FLASH, FADE, ZOOM, WHIP, GLITCH }

/** One cut of the reel. [b0]/[b1] are positions on the beat grid ([Styles.grid]), so edits stay on the beat. */
data class Shot(
    val t0: Double,
    val t1: Double,
    val hot: Boolean,
    val trans: Trans,
    val punch: Boolean,
    val kb: Int,
    val panX: Double,
    val panY: Double,
    val b0: Int = 0,
    val b1: Int = 0,
    var media: MediaInfo? = null,
    var src0: Double = 0.0,
)

object Styles {

    /** Shortest and longest shot the automatic edit makes (seconds). */
    const val MIN_SHOT = 0.45
    const val MAX_SHOT = 2.5

    /** Beat times of the reel, plus the reel end if it is not on a beat. Cuts are always on this grid. */
    fun grid(a: SongAnalysis): DoubleArray =
        if (a.rel.isNotEmpty() && a.rel.last() >= a.len - 0.02) a.rel.copyOf() else a.rel + a.len

    /** A grid time rounded to a whole video frame (30 fps). */
    fun frameT(t: Double): Double = (t * 30).roundToInt() / 30.0

    fun withLook(s: Style, key: String) = Style(Looks.byKey(key), s.energetic, s.bright, s.heavy, s.calm, s.hot, s.pace, s.beat)

    /** The tempo a listener feels: soft picked notes heard as a fast beat (e.g. 172) count as half. */
    fun feelBpm(a: SongAnalysis) = if (a.bpm > 140 && a.brightness < 0.12) a.bpm / 2 else a.bpm

    /** Auto cut speed from the song (web page songPace; tuned on 7 tracks of known mood). */
    fun songPace(a: SongAnalysis): Pace {
        val bpm = feelBpm(a)
        if ((a.brightness < 0.09 && a.punch < 14) || (bpm < 90 && a.brightness < 0.12)) return Pace.SMOOTH
        if (bpm >= 118 && (a.brightness >= 0.15 || a.punch >= 16)) return Pace.FAST
        return Pace.NORMAL
    }

    /** The song's profile picks the look and the cutting rhythm. */
    /**
     * Look + cut rhythm for a song (web page chooseStyle). [pacePick] = the Cut speed button (AUTO = from the song);
     * [mediaCount] = picked clips/photos, used to share the reel between them at smooth / normal speed.
     */
    fun choose(a: SongAnalysis, r: Random, forcedLook: String?, pacePick: Pace = Pace.AUTO, mediaCount: Int = 6): Style {
        val pace = if (pacePick == Pace.AUTO) songPace(a) else pacePick
        val energetic = pace == Pace.FAST
        val smooth = pace == Pace.SMOOTH
        val bright = a.brightness >= 0.14
        val heavy = a.bass >= 0.5
        val pool = when {
            energetic && bright -> if (heavy) listOf("street", "neon", "pop", "bolly") else listOf("neon", "golden", "pop", "sunset")
            energetic -> if (heavy) listOf("street", "mono", "vhs") else listOf("teal", "mono", "blue", "vhs")
            bright -> listOf("golden", "dreamy", "sunset", "vintage")
            else -> listOf("teal", "mono", "matte", "blue")
        }
        val roll = r.nextDouble()
        val pick = if (roll < 0.5) pool[0] else pool[1 + (r.nextDouble() * (pool.size - 1)).toInt().coerceAtMost(pool.size - 2)]
        val key = forcedLook ?: pick
        val beat = 60.0 / a.bpm
        // fast: most beats per cut that stay under maxSec, never shorter than MIN_SHOT (very fast songs cut every 2 beats)
        fun fit(maxSec: Double) = listOf(4, 2, 1).firstOrNull { it * beat <= maxSec && it * beat >= MIN_SHOT }
            ?: listOf(1, 2, 4).firstOrNull { it * beat >= MIN_SHOT } ?: 4
        // smooth / normal: the beat count nearest a target length (the reel shared by the clips)
        fun near(t: Double, opts: List<Int>) = opts.minByOrNull { abs(it * beat - t) } ?: opts[0]
        val share = a.len / mediaCount.coerceAtLeast(1)
        val calm: Int
        val hot: Int
        when {
            energetic -> { calm = fit(1.0); hot = fit(0.5) }
            // smooth: never under ~2.4 s (at 120+ BPM, 4 beats is only ~1.9 s, so it takes 8)
            smooth -> { calm = near(share.coerceIn(2.6, 4.5), listOf(4, 8, 12, 16).filter { it * beat >= 2.4 }.ifEmpty { listOf(16) }); hot = calm }
            else -> {
                calm = near(share.coerceIn(1.4, 2.6), listOf(2, 4, 8))
                hot = if (calm > 1 && calm / 2 * beat >= 0.9) calm / 2 else calm
            }
        }
        return Style(Looks.byKey(key), energetic, bright, heavy, calm, hot, pace, beat)
    }

    fun planShots(a: SongAnalysis, s: Style, r: Random): List<Shot> {
        val hotTh = SongAnalyzer.pct(a.energy, 55.0)
        val g = grid(a)
        val end = g.lastIndex
        val cuts = arrayListOf(0)            // grid positions
        val hots = arrayListOf<Boolean>()
        // too long a span: cut it on beats so no shot is longer than MAX_SHOT
        fun addSplit(to: Int, hot: Boolean) {
            while (g[to] - g[cuts.last()] > s.maxShot) {
                val from = cuts.last()
                var pick = -1
                for (j in from + 1 until to) {
                    val d = g[j] - g[from]
                    if (d >= s.minShot && d <= s.maxShot && g[to] - g[j] >= s.minShot) pick = j
                }
                if (pick < 0) break
                cuts.add(pick); hots.add(hot)
            }
            cuts.add(to); hots.add(hot)
        }
        var i = 0
        while (i < a.rel.size) {
            val hot = a.energy[i] >= hotTh
            i += if (hot) s.hot else s.calm
            val nx = if (i < a.rel.size) i else end
            if (g[nx] - g[cuts.last()] < s.minShot) continue
            addSplit(nx, hot)
            if (nx == end) break
        }
        if (cuts.last() < end) {
            if (g[end] - g[cuts.last()] < s.minShot && cuts.size > 1) {
                // a too-short tail joins the shot before it (split again if that gets too long)
                cuts.removeAt(cuts.lastIndex)
                addSplit(end, hots.removeAt(hots.lastIndex))
            } else addSplit(end, false)
        }
        val frames = cuts.map { (g[it] * 30).roundToInt() }
        val shots = ArrayList<Shot>()
        for (k in 0 until frames.size - 1) {
            if (frames[k + 1] <= frames[k]) continue
            val t0 = frames[k] / 30.0
            val t1 = frames[k + 1] / 30.0
            val hot = hots.getOrElse(k) { false }
            var trans = Trans.CUT
            if (k > 0) {
                // web page planShots: fast = flashes / glitches / whips on the beat; smooth = soft dissolves and gentle
                // zooms only; normal = mostly dissolves
                trans = when {
                    s.energetic -> if (hot) {
                        if (s.look.flash && r.nextDouble() < 0.5) Trans.FLASH
                        else if (s.look.glitch && r.nextDouble() < 0.6) Trans.GLITCH else Trans.CUT
                    } else {
                        if (r.nextDouble() < 0.3) Trans.WHIP
                        else if (s.look.glitch && r.nextDouble() < 0.3) Trans.GLITCH else Trans.CUT
                    }
                    s.smooth -> if (r.nextDouble() < 0.8) Trans.FADE else Trans.ZOOM
                    else -> if (s.look.glitch && r.nextDouble() < 0.3) Trans.GLITCH
                        else if (r.nextDouble() < 0.7) Trans.FADE else Trans.ZOOM
                }
            }
            shots.add(
                Shot(
                    t0, t1, hot, trans,
                    b0 = cuts[k], b1 = cuts[k + 1],
                    punch = s.energetic && (hot || k % 2 == 0),
                    kb = if (r.nextDouble() < 0.5) 1 else -1,
                    panX = r.nextDouble() * 2 - 1,
                    panY = r.nextDouble() * 2 - 1,
                )
            )
        }
        return shots
    }
}
