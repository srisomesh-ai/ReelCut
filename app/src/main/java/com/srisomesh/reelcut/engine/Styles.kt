package com.srisomesh.reelcut.engine

import kotlin.math.roundToInt
import kotlin.random.Random

/** A colour look. Values are applied by [LookShaderProgram]. */
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
)

object Looks {
    val all: List<Look> = listOf(
        Look("street", "Street Punch", 1.30f, 1.20f, 1.00f, 0xFFB000, 0.08f, false, 0.55f, flash = true, shake = true, bars = false),
        Look("neon", "Neon Night", 1.15f, 1.55f, 1.03f, 0xFF2BD6, 0.10f, false, 0.45f, flash = true, shake = false, bars = false),
        Look("teal", "Teal Cinema", 1.20f, 0.88f, 1.00f, 0x009AA6, 0.16f, false, 0.50f, flash = false, shake = false, bars = true),
        Look("golden", "Golden Warm", 1.06f, 1.25f, 1.04f, 0xFF9A3C, 0.12f, false, 0.30f, flash = false, shake = false, bars = false),
        Look("mono", "Mono Drama", 1.35f, 0.00f, 1.00f, 0x808080, 0.00f, true, 0.60f, flash = true, shake = false, bars = false),
        Look("dreamy", "Dreamy Soft", 0.94f, 1.12f, 1.07f, 0xFFC0D9, 0.10f, false, 0.25f, flash = false, shake = false, bars = false),
    )
    fun byKey(k: String) = all.first { it.key == k }
}

class Style(
    val look: Look,
    val energetic: Boolean,
    val bright: Boolean,
    val heavy: Boolean,
    val calm: Int,   // beats per cut in calm parts
    val hot: Int,    // beats per cut in loud parts
)

enum class Trans { CUT, FLASH, FADE, ZOOM, WHIP }

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

    fun withLook(s: Style, key: String) = Style(Looks.byKey(key), s.energetic, s.bright, s.heavy, s.calm, s.hot)

    /** The song's profile picks the look and the cutting rhythm. */
    fun choose(a: SongAnalysis, r: Random, forcedLook: String?): Style {
        val energetic = a.bpm >= 100 && a.beatStrength >= 1.6
        val bright = a.brightness >= 0.14
        val heavy = a.bass >= 0.5
        val pool = when {
            energetic && bright -> if (heavy) listOf("street", "neon") else listOf("neon", "golden")
            energetic -> if (heavy) listOf("street", "mono") else listOf("teal", "mono")
            bright -> listOf("golden", "dreamy")
            else -> listOf("teal", "mono")
        }
        val roll = r.nextDouble()
        val key = forcedLook ?: if (roll < 0.65) pool[0] else pool[1]
        val beat = 60.0 / a.bpm
        // most beats per cut that stay under maxSec, but never shorter than MIN_SHOT (very fast songs cut every 2 beats)
        fun fit(maxSec: Double) = listOf(4, 2, 1).firstOrNull { it * beat <= maxSec && it * beat >= MIN_SHOT }
            ?: listOf(1, 2, 4).firstOrNull { it * beat >= MIN_SHOT } ?: 4
        val calm = fit(if (energetic) 1.0 else 2.4)
        val hot = fit(if (energetic) 0.5 else 1.2)
        return Style(Looks.byKey(key), energetic, bright, heavy, calm, hot)
    }

    fun planShots(a: SongAnalysis, s: Style, r: Random): List<Shot> {
        val hotTh = SongAnalyzer.pct(a.energy, 55.0)
        val g = grid(a)
        val end = g.lastIndex
        val cuts = arrayListOf(0)            // grid positions
        val hots = arrayListOf<Boolean>()
        // too long a span: cut it on beats so no shot is longer than MAX_SHOT
        fun addSplit(to: Int, hot: Boolean) {
            while (g[to] - g[cuts.last()] > MAX_SHOT) {
                val from = cuts.last()
                var pick = -1
                for (j in from + 1 until to) {
                    val d = g[j] - g[from]
                    if (d >= MIN_SHOT && d <= MAX_SHOT && g[to] - g[j] >= MIN_SHOT) pick = j
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
            if (g[nx] - g[cuts.last()] < MIN_SHOT) continue
            addSplit(nx, hot)
            if (nx == end) break
        }
        if (cuts.last() < end) {
            if (g[end] - g[cuts.last()] < MIN_SHOT && cuts.size > 1) {
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
                trans = if (s.energetic) {
                    if (hot) (if (s.look.flash && r.nextDouble() < 0.5) Trans.FLASH else Trans.CUT)
                    else (if (r.nextDouble() < 0.3) Trans.WHIP else Trans.CUT)
                } else {
                    if (r.nextDouble() < 0.7) Trans.FADE else Trans.ZOOM
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
