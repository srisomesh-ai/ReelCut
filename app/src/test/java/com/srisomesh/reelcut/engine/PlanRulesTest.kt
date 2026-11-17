package com.srisomesh.reelcut.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The automatic shot-length rule and the Fine-tune edits, on made-up songs from 60 to 170 BPM. */
class PlanRulesTest {

    private fun song(bpm: Double, len: Double, strength: Double, seed: Int): SongAnalysis {
        val beat = 60.0 / bpm
        val n = (len / beat).toInt()
        val rel = DoubleArray(n + 1) { it * beat }
        val r = Random(seed)
        val energy = DoubleArray(rel.size) { r.nextDouble() }
        return SongAnalysis(0.0, n * beat, rel, energy, emptyList(), bpm, 0.1, 0.6, strength, seed, 200.0)
    }

    private val songs = buildList {
        for (bpm in listOf(60.0, 72.0, 78.0, 90.0, 100.0, 123.0, 128.0, 140.0, 150.0, 170.0))
            for (len in listOf(10.0, 15.0, 30.0))
                for (strength in listOf(1.0, 11.0)) add(song(bpm, len, strength, (bpm * len).toInt() + strength.toInt()))
    }

    // every song at every cut speed (fast / normal / smooth) and at the automatic one
    private fun plans() = songs.flatMap { a ->
        listOf(Pace.AUTO, Pace.FAST, Pace.NORMAL, Pace.SMOOTH).flatMap { pace ->
            (0 until 3).map { v ->
                val r = Random(a.seed + v * 7919)
                val st = Styles.choose(a, r, null, pace, 6)
                Triple(a, st, Styles.planShots(a, st, r))
            }
        }
    }

    @Test
    fun shotsStayBetweenMinAndMax() {
        val frame = 1 / 30.0 + 1e-6
        for ((a, st, shots) in plans()) {
            for (s in shots) {
                val d = s.t1 - s.t0
                assertTrue("bpm ${a.bpm} ${st.pace}: shot ${"%.2f".format(d)}s shorter than ${st.minShot}", d >= st.minShot - frame)
                assertTrue("bpm ${a.bpm} ${st.pace}: shot ${"%.2f".format(d)}s longer than ${st.maxShot}", d <= st.maxShot + frame)
            }
            if (st.pace == Pace.FAST) assertTrue(st.minShot == Styles.MIN_SHOT && st.maxShot == Styles.MAX_SHOT)
        }
    }

    @Test
    fun smoothIsSlowAndSoft() {
        for ((a, st, shots) in plans().filter { it.second.pace == Pace.SMOOTH }) {
            val avg = shots.sumOf { it.t1 - it.t0 } / shots.size
            assertTrue("bpm ${a.bpm}: smooth shots average ${"%.2f".format(avg)}s", avg >= 2.0)
            assertTrue("smooth has only dissolves / zooms", shots.drop(1).all { it.trans == Trans.FADE || it.trans == Trans.ZOOM })
            assertTrue("no punch-ins at smooth speed", shots.none { it.punch })
        }
    }

    @Test
    fun autoPaceFollowsTheSong() {
        val soft = song(70.0, 15.0, 11.0, 1)                     // slow, dark -> smooth
        assertEquals(Pace.SMOOTH, Styles.songPace(soft))
        val dance = SongAnalysis(0.0, 15.0, soft.rel, soft.energy, emptyList(), 128.0, 0.2, 0.6, 11.0, 1, 200.0, 18.0)
        assertEquals(Pace.FAST, Styles.songPace(dance))
        val mid = SongAnalysis(0.0, 15.0, soft.rel, soft.energy, emptyList(), 105.0, 0.13, 0.6, 11.0, 1, 200.0, 15.0)
        assertEquals(Pace.NORMAL, Styles.songPace(mid))
        // a calm track heard at 172 BPM is felt as 86 -> smooth
        val calm = SongAnalysis(0.0, 15.0, soft.rel, soft.energy, emptyList(), 172.0, 0.10, 0.6, 11.0, 1, 200.0, 15.0)
        assertEquals(Pace.SMOOTH, Styles.songPace(calm))
        assertEquals(13, Looks.all.size)
    }

    @Test
    fun cutsAreOnTheBeatAndLengthIsKept() {
        for ((a, _, shots) in plans()) {
            val g = Styles.grid(a)
            assertEquals(0.0, shots.first().t0, 1e-9)
            assertEquals(Styles.frameT(a.len), shots.last().t1, 1e-9)
            for (i in shots.indices) {
                assertEquals(Styles.frameT(g[shots[i].b0]), shots[i].t0, 1e-9)
                assertEquals(Styles.frameT(g[shots[i].b1]), shots[i].t1, 1e-9)
                if (i > 0) assertEquals(shots[i - 1].b1, shots[i].b0)
            }
        }
    }

    @Test
    fun editsKeepBeatsAndLength() {
        for ((a, _, start) in plans()) {
            val g = Styles.grid(a)
            val end = start.last().t1
            var shots = start
            val r = Random(a.seed)
            repeat(30) {
                val k = r.nextInt(shots.size)
                val next = when (r.nextInt(3)) {
                    0 -> ShotEdits.changeLength(shots, g, k, 1)
                    1 -> ShotEdits.changeLength(shots, g, k, -1)
                    else -> if (shots.size > 3) ShotEdits.remove(shots, g, k) else null
                }
                if (next != null) shots = next
                assertTrue(shots.isNotEmpty())
                assertEquals("reel length kept", end, shots.last().t1, 1e-9)
                assertEquals(0.0, shots.first().t0, 1e-9)
                for (i in shots.indices) {
                    assertTrue("shot at least 1 beat", shots[i].b1 > shots[i].b0)
                    assertEquals(Styles.frameT(g[shots[i].b0]), shots[i].t0, 1e-9)
                    if (i > 0) assertEquals("no gaps", shots[i - 1].b1, shots[i].b0)
                }
            }
        }
    }

    @Test
    fun lengthChangeMovesLaterShots() {
        val a = song(120.0, 15.0, 11.0, 1)
        val r = Random(3)
        val st = Styles.choose(a, r, null)
        val shots = Styles.planShots(a, st, r)
        val g = Styles.grid(a)
        val longer = ShotEdits.changeLength(shots, g, 1, 1)
        assertNotNull(longer)
        longer!!
        assertEquals(shots[1].b1 + 1, longer[1].b1)
        assertEquals(shots[2].b1 + 1, longer[2].b1)          // the next shot moved, same length
        assertEquals(shots[2].b1 - shots[2].b0, longer[2].b1 - longer[2].b0)
        // a 1-beat shot cannot get shorter
        val one = shots.indexOfFirst { it.b1 - it.b0 == 1 }
        if (one >= 0 && one < shots.lastIndex) assertNull(ShotEdits.changeLength(shots, g, one, -1))
    }
}
