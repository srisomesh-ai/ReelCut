package com.srisomesh.reelcut.engine

import kotlin.math.max

/** The edit that gets rendered: look + shots + the beat grid the shots sit on. */
class Plan(val style: Style, val shots: List<Shot>, val grid: DoubleArray) {
    fun withLook(key: String) = Plan(Styles.withLook(style, key), shots, grid)
}

/**
 * Hand edits from the Fine-tune screen. Every cut stays on the beat grid and the reel keeps its length:
 * changing one shot moves the shots after it, and the last shot gives or takes the difference.
 * Each function returns a new list, or null when the change is not possible.
 */
object ShotEdits {

    /** Shot [k] one beat longer ([d] = +1) or shorter ([d] = -1). */
    fun changeLength(shots: List<Shot>, grid: DoubleArray, k: Int, d: Int): List<Shot>? {
        val n = shots.size
        if (k !in 0 until n || d == 0) return null
        val b = IntArray(n + 1) { if (it < n) shots[it].b0 else shots[n - 1].b1 }
        val end = b[n]
        if (k == n - 1) {
            // last shot: its start moves; the shot before it gives or takes the beat
            if (n == 1) return null
            val nb = b[k] - d
            if (nb <= b[k - 1] || nb >= end) return null
            b[k] = nb
        } else {
            val nb = b[k + 1] + d
            if (nb <= b[k] || nb >= end) return null
            for (j in k + 1 until n) b[j] += d
        }
        val out = ArrayList<Shot>()
        for (j in 0 until n) {
            val b0 = minOf(b[j], end)
            val b1 = if (j == n - 1) end else minOf(b[j + 1], end)
            if (b1 > b0) out.add(shots[j].copy(b0 = b0, b1 = b1))
        }
        return retime(out, grid)
    }

    /** Removes shot [k]; the shot before it (or after it, for the first) fills its time. */
    fun remove(shots: List<Shot>, grid: DoubleArray, k: Int): List<Shot>? {
        if (shots.size < 2 || k !in shots.indices) return null
        val out = shots.toMutableList()
        if (k > 0) out[k - 1] = out[k - 1].copy(b1 = shots[k].b1)
        else out[1] = out[1].copy(b0 = shots[0].b0)
        out.removeAt(k)
        return retime(out, grid)
    }

    /** Which part of the clip plays (seconds into the clip). */
    fun setMoment(shots: List<Shot>, k: Int, src0: Double): List<Shot> =
        shots.mapIndexed { i, s -> if (i == k) clampMoment(s.copy(src0 = src0)) else s }

    /** Another clip or photo for shot [k], starting at [src0]. */
    fun setMedia(shots: List<Shot>, k: Int, media: MediaInfo, src0: Double): List<Shot> =
        shots.mapIndexed { i, s -> if (i == k) clampMoment(s.copy(media = media, src0 = src0)) else s }

    /** Times from the grid (whole frames), and every clip moment kept inside its clip. */
    fun retime(shots: List<Shot>, grid: DoubleArray): List<Shot> =
        shots.map { clampMoment(it.copy(t0 = Styles.frameT(grid[it.b0]), t1 = Styles.frameT(grid[it.b1]))) }

    private fun clampMoment(s: Shot): Shot {
        val m = s.media ?: return s
        if (m.isImage) return if (s.src0 == 0.0) s else s.copy(src0 = 0.0)
        val maxStart = max(0.0, m.dur - (s.t1 - s.t0) - 0.05)
        val v = s.src0.coerceIn(0.0, maxStart)
        return if (v == s.src0) s else s.copy(src0 = v)
    }
}
