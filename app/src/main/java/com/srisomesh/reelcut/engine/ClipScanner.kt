package com.srisomesh.reelcut.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class Sample(val t: Double, val sharpN: Double, val motN: Double, val light: Double)

/** One picked clip or photo, with the scores of its moments. */
class MediaInfo(val uri: Uri, val isImage: Boolean) {
    var dur: Double = Double.POSITIVE_INFINITY
    var samples: List<Sample> = emptyList()
    var ok = false
    var uses = 0
    val used = ArrayList<Pair<Double, Double>>()
    fun reset() { uses = 0; used.clear() }
}

object ClipScanner {

    fun isImage(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        if (type != null) return type.startsWith("image")
        val p = uri.toString().lowercase()
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".heic")
    }

    suspend fun scan(context: Context, uris: List<Uri>, onProgress: (Float) -> Unit): List<MediaInfo> = coroutineScope {
        val done = AtomicInteger(0)
        val gate = Semaphore(3)
        val list = uris.map { MediaInfo(it, isImage(context, it)) }
        list.map { m ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try { scanOne(context, m) } catch (_: Exception) { m.ok = false }
                    onProgress(done.incrementAndGet().toFloat() / list.size)
                }
            }
        }.awaitAll()
        if (list.none { it.ok }) throw IllegalArgumentException("None of these files could be read. Try other videos or photos.")
        list
    }

    private fun scanOne(context: Context, m: MediaInfo) {
        if (m.isImage) {
            context.contentResolver.openInputStream(m.uri)?.close() ?: return
            m.samples = listOf(Sample(0.0, 0.5, 0.5, 0.8))
            m.ok = true
            return
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, m.uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return
            m.dur = ms / 1000.0
            if (m.dur < 0.4) return
            val k = (m.dur / 0.8).roundToInt().coerceIn(4, 14)
            val sharp = DoubleArray(k); val motion = DoubleArray(k); val bright = DoubleArray(k); val ts = DoubleArray(k)
            var prev: IntArray? = null
            var got = 0
            for (i in 0 until k) {
                val t = (i + 0.5) * m.dur / k
                ts[i] = t
                val frame = r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96)
                    ?: continue
                val small = Bitmap.createScaledBitmap(frame, 48, 48, true)
                val px = IntArray(48 * 48)
                small.getPixels(px, 0, 48, 0, 0, 48, 48)
                if (small !== frame) small.recycle()
                frame.recycle()
                val y = IntArray(px.size) { val c = px[it]; (299 * (c shr 16 and 255) + 587 * (c shr 8 and 255) + 114 * (c and 255)) / 1000 }
                var s = 0.0; var s2 = 0.0; var b = 0.0
                for (row in 1 until 47) for (col in 1 until 47) {
                    val j = row * 48 + col
                    val l = (y[j - 1] + y[j + 1] + y[j - 48] + y[j + 48] - 4 * y[j]).toDouble()
                    s += l; s2 += l * l
                }
                for (v in y) b += v
                val nn = 46.0 * 46.0
                sharp[i] = s2 / nn - (s / nn) * (s / nn)
                bright[i] = b / y.size
                prev?.let { p -> var d = 0.0; for (j in y.indices) d += abs(y[j] - p[j]); motion[i] = d / y.size }
                prev = y
                got++
            }
            if (got == 0) return
            val shN = norm(sharp); val moN = norm(motion)
            m.samples = (0 until k).map { i ->
                Sample(ts[i], shN[i], if (moN[i] > 0.92) 0.7 else moN[i], 1 - (abs(bright[i] - 125) / 125).coerceIn(0.0, 1.0))
            }
            m.ok = true
        } finally {
            r.release()
        }
    }

    private fun norm(a: DoubleArray): DoubleArray {
        val lo = a.min(); val hi = a.max()
        return DoubleArray(a.size) { if (hi - lo < 1e-6) 0.5 else (a[it] - lo) / (hi - lo) }
    }

    /** Gives every shot its clip and the best unused moment in it. */
    fun assign(shots: List<Shot>, media: List<MediaInfo>, r: Random) {
        val ok = media.filter { it.ok }
        ok.forEach { it.reset() }
        var prev: MediaInfo? = null
        var prev2: MediaInfo? = null
        for (sh in shots) {
            val need = (sh.t1 - sh.t0) + 0.1
            var bestScore = Double.NEGATIVE_INFINITY
            var bestM: MediaInfo? = null
            var bestT = 0.0
            for (m in ok) {
                if (ok.size > 2 && (m === prev || m === prev2)) continue
                if (ok.size == 2 && m === prev) continue
                if (m.isImage) {
                    val sc = 0.8 + (if (sh.hot) -0.05 else 0.1) - 0.3 * m.uses + r.nextDouble() * 0.05
                    if (sc > bestScore) { bestScore = sc; bestM = m; bestT = 0.0 }
                    continue
                }
                for (s in m.samples) {
                    val t = (s.t - need / 2).coerceIn(0.0, max(0.0, m.dur - need - 0.05))
                    if (m.used.any { (a, b) -> t < b && t + need > a }) continue
                    val sc = (if (sh.hot) 0.55 * s.motN + 0.3 * s.sharpN else 0.3 * s.motN + 0.55 * s.sharpN) +
                        0.15 * s.light - 0.2 * m.uses + r.nextDouble() * 0.05 - (if (m.dur < need) 0.5 else 0.0)
                    if (sc > bestScore) { bestScore = sc; bestM = m; bestT = t }
                }
            }
            if (bestM == null) {
                ok.forEach { it.used.clear() }
                bestM = ok.firstOrNull { it !== prev } ?: ok[0]
                bestT = 0.0
            }
            sh.media = bestM
            sh.src0 = bestT
            bestM.uses++
            bestM.used.add(bestT to bestT + need)
            prev2 = prev; prev = bestM
        }
    }
}
