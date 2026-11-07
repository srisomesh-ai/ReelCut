package com.srisomesh.reelcut.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Hit(val t: Double, val s: Double)

/** Everything the edit needs to know about the song. Times are seconds. */
class SongAnalysis(
    val start: Double,          // where the reel starts inside the song
    val len: Double,            // reel length, ends on a beat
    val rel: DoubleArray,       // beat times relative to start
    val energy: DoubleArray,    // energy (0..1) at each of those beats
    val hits: List<Hit>,        // bass hits relative to start
    val bpm: Double,
    val brightness: Double,
    val bass: Double,
    val beatStrength: Double,
    val seed: Int,
    val songDur: Double,
)

/** Beat tracking (Ellis 2007 dynamic programming) and song character, ported from BeatReel. */
object SongAnalyzer {
    const val SR = 22050
    const val HOP = 512
    const val FR = SR.toDouble() / HOP

    fun analyze(x: FloatArray, reelLen: Double): SongAnalysis {
        val songDur = x.size.toDouble() / SR
        if (songDur < 6) throw IllegalArgumentException("This song is too short. Pick a song longer than 6 seconds.")

        val low = biquad(x, lowpass = true, freq = 150.0)
        val high = biquad(x, lowpass = false, freq = 3000.0)
        val rF = frameRms(x); val rL = frameRms(low); val rH = frameRms(high)
        val fF = flux(rF); val fL = flux(rL); val fH = flux(rH)
        val n = rF.size

        val raw = DoubleArray(n) { 0.5 * fL[it] + 0.3 * fF[it] + 0.2 * fH[it] }
        val o = DoubleArray(n)
        var acc = 0.0
        val k = 16
        for (i in 0 until n) {
            acc += raw[i]
            if (i >= k) acc -= raw[i - k]
            o[i] = max(0.0, raw[i] - acc / min(i + 1, k))
        }
        val sd = sqrt(o.sumOf { it * it } / max(1, n)).let { if (it == 0.0) 1.0 else it }
        for (i in 0 until n) o[i] /= sd

        val bf = trackBeats(o)
        if (bf.size < 8) throw IllegalArgumentException("Could not find a clear beat in this song. Try another song.")
        val beats = DoubleArray(bf.size) { bf[it] * HOP.toDouble() / SR }
        val ivals = (1 until beats.size).map { beats[it] - beats[it - 1] }.sorted()
        val bpm = 60.0 / ivals[ivals.size / 2]

        val eRaw = DoubleArray(bf.size) { i ->
            val f = bf[i]
            val g = if (i + 1 < bf.size) bf[i + 1] else min(n, f + 20)
            var s = 0.0
            for (j in f until g) s += ln(1 + 1000 * rF[j])
            s / max(1, g - f)
        }
        val e5 = pct(eRaw, 5.0); val e95 = pct(eRaw, 95.0)
        val energy = DoubleArray(eRaw.size) { ((eRaw[it] - e5) / (e95 - e5 + 1e-9)).coerceIn(0.0, 1.0) }

        val mF = rF.average() + 1e-9
        val brightness = rH.average() / mF
        val bass = rL.average() / mF
        val beatStrength = bf.map { o[it] }.average() / (o.average() + 1e-9)

        // most exciting window of the reel length, starting on a beat
        var bestI = 0
        var bestS = -1.0
        for (i in beats.indices) {
            if (beats[i] + reelLen > beats.last()) break
            var j = i; var s = 0.0; var c = 0
            while (j < beats.size && beats[j] < beats[i] + reelLen) { s += energy[j]; c++; j++ }
            var rise = 0.0
            for (q in i + 4 until j - 3) {
                val up = (energy[q] + energy[q + 1] + energy[q + 2] + energy[q + 3]) / 4
                val before = (energy[q - 1] + energy[q - 2] + energy[q - 3] + energy[q - 4]) / 4
                rise = max(rise, up - before)
            }
            val sc = s / c + 0.8 * rise + 0.05 * min(2.0, o[bf[i]])
            if (sc > bestS) { bestS = sc; bestI = i }
        }
        val start = beats[bestI]
        var lastI = bestI
        while (lastI + 1 < beats.size && beats[lastI + 1] - start <= reelLen + 0.01) lastI++
        var len = beats[lastI] - start
        if (len < reelLen - 1.2) len = min(reelLen, songDur - start - 0.05)
        val rel = DoubleArray(lastI - bestI + 1) { beats[bestI + it] - start }
        val en = DoubleArray(lastI - bestI + 1) { energy[bestI + it] }

        // bass hits inside the window, for pulses and shakes between cuts
        val thr = pct(fL, 92.0)
        val hits = ArrayList<Hit>()
        val f0 = (start * FR).roundToInt()
        val f1 = min(((start + len) * FR).roundToInt(), n - 5)
        for (f in f0 + 2 until f1 - 2) {
            if (f < 4 || fL[f] < thr) continue
            var peak = true
            for (d in -4..4) if (fL[f + d] > fL[f]) { peak = false; break }
            val t = f / FR - start
            if (peak && (hits.isEmpty() || t - hits.last().t > 0.18)) hits.add(Hit(t, fL[f]))
        }
        val hmax = max(1e-9, hits.maxOfOrNull { it.s } ?: 1.0)
        val hitsN = hits.map { Hit(it.t, (it.s / hmax).coerceIn(0.3, 1.0)) }

        var seed = 0
        var i = 0
        while (i < x.size) { seed = seed * 31 + (x[i] * 1e4f).roundToInt(); i += 997 }

        return SongAnalysis(start, len, rel, en, hitsN, bpm, brightness, bass, beatStrength, seed, songDur)
    }

    private fun biquad(x: FloatArray, lowpass: Boolean, freq: Double): FloatArray {
        val w0 = 2 * PI * freq / SR
        val alpha = sin(w0) / (2 * 0.707)
        val cw = cos(w0)
        val b0: Double; val b1: Double; val b2: Double
        if (lowpass) { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
        else { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
        val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0; val na1 = a1 / a0; val na2 = a2 / a0
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val yi = nb0 * xi + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            y[i] = yi.toFloat()
        }
        return y
    }

    private fun frameRms(x: FloatArray): DoubleArray {
        val n = x.size / HOP
        return DoubleArray(n) { i ->
            var s = 0.0
            val o = i * HOP
            for (j in 0 until HOP) { val v = x[o + j]; s += v * v }
            sqrt(s / HOP)
        }
    }

    private fun flux(r: DoubleArray): DoubleArray {
        val f = DoubleArray(r.size)
        if (r.isEmpty()) return f
        var prev = ln(1 + 1000 * r[0])
        for (i in 1 until r.size) {
            val c = ln(1 + 1000 * r[i])
            f[i] = max(0.0, c - prev)
            prev = c
        }
        return f
    }

    private fun trackBeats(o: DoubleArray): IntArray {
        val n = o.size
        var best = -1.0
        var bestL = 0
        val scores = HashMap<Int, Double>()
        val lo = floor(FR * 60 / 185).toInt()
        val hi = ceil(FR * 60 / 68).toInt()
        for (l in lo..hi) {
            if (l >= n) break
            var s = 0.0
            for (i in 0 until n - l) s += o[i] * o[i + l]
            s /= (n - l)
            val bpm = 60 * FR / l
            val w = exp(-0.5 * (log2(bpm / 120) / 0.9).pow(2))
            scores[l] = s * w
            if (s * w > best) { best = s * w; bestL = l }
        }
        if (bestL == 0) return IntArray(0)
        var p = bestL.toDouble()
        val a = scores[bestL - 1]; val c = scores[bestL + 1]
        if (a != null && c != null) {
            val d = a - 2 * best + c
            if (d < 0) p = bestL + 0.5 * (a - c) / d
        }
        val cum = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val alpha = 100.0
        val far = (2 * p).roundToInt()
        val near = (p / 2).roundToInt()
        for (t in 0 until n) {
            var m = 0.0
            var arg = -1
            val s0 = max(0, t - far)
            val s1 = t - near
            for (q in s0..s1) {
                val v = cum[q] - alpha * ln((t - q) / p).pow(2)
                if (arg < 0 || v > m) { m = v; arg = q }
            }
            cum[t] = o[t] + (if (arg >= 0) max(0.0, m) else 0.0)
            back[t] = if (arg >= 0 && m > 0) arg else -1
        }
        var end = n - 1
        var bv = -1.0
        for (t in max(0, n - p.roundToInt()) until n) if (cum[t] > bv) { bv = cum[t]; end = t }
        val out = ArrayList<Int>()
        var t = end
        while (t >= 0) { out.add(t); t = back[t] }
        out.reverse()
        return out.toIntArray()
    }

    fun pct(arr: DoubleArray, p: Double): Double {
        if (arr.isEmpty()) return 0.0
        val s = arr.sortedArray()
        return s[min(s.size - 1, floor(p / 100 * s.size).toInt())]
    }
}
