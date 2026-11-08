package com.srisomesh.reelcut

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srisomesh.reelcut.engine.ReelEngine
import com.srisomesh.reelcut.engine.Plan
import com.srisomesh.reelcut.engine.ShotEdits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Makes real reels on the emulator from the clips in androidTest/assets, with a fast and a slow song.
 * CI pulls files/test-out/ so the videos can be checked frame by frame.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RenderTest {

    @Test
    fun makesReels() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val inDir = File(ctx.cacheDir, "test-in").apply { mkdirs() }
        val outDir = File(ctx.filesDir, "test-out").apply { mkdirs(); listFiles()?.filter { it.name.startsWith("reel_") }?.forEach { it.delete() } }
        val names = assets.list("")!!.filter { it.endsWith(".mp4") || it.endsWith(".jpg") || it.endsWith(".mp3") || it.endsWith(".m4a") }
        names.forEach { n -> assets.open(n).use { i -> File(inDir, n).outputStream().use { i.copyTo(it) } } }
        val media = names.filter { it.endsWith(".mp4") || it.endsWith(".jpg") }.sorted().map { Uri.fromFile(File(inDir, it)) }
        val report = StringBuilder()

        val runs = listOf(
            Triple("song_fast.mp3", 15.0, null as String?),
            Triple("song_slow.m4a", 15.0, null as String?),
            Triple("song_fast.mp3", 10.0, "mono"),
            Triple("song_fast.mp3", 15.0, "edited"),   // fine-tuned by hand: +1 beat, -1 beat, one shot removed, moment moved
        )
        for ((i, run) in runs.withIndex()) {
            val (songName, len, look) = run
            val t0 = System.currentTimeMillis()
            val session = ReelEngine.prepare(ctx, media, Uri.fromFile(File(inDir, songName)), len) { s, p -> Log.i("RCTEST", "$s $p") }
            val t1 = System.currentTimeMillis()
            val out = File(outDir, "reel_${i + 1}.mp4")
            var plan = ReelEngine.plan(session, 0, if (look == "edited") null else look)
            if (look == "edited") {
                var sh = plan.shots
                sh = ShotEdits.changeLength(sh, plan.grid, 1, 1) ?: error("+1 beat failed")
                sh = ShotEdits.changeLength(sh, plan.grid, 4, -1) ?: error("-1 beat failed")
                sh = ShotEdits.remove(sh, plan.grid, 6) ?: error("remove failed")
                sh = ShotEdits.setMoment(sh, 2, 99.0)   // clamps to the clip end
                plan = Plan(plan.style, sh, plan.grid)
            }
            val lens = plan.shots.map { it.t1 - it.t0 }
            val res = ReelEngine.render(ctx, session, plan, 720, 1280, out) { _, _ -> }
            val t2 = System.currentTimeMillis()
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(out.path)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            mmr.release()
            val a = session.analysis
            val line = "reel_${i + 1}: song=$songName len=$len look=${res.style.look.name} energetic=${res.style.energetic} " +
                "bpm=${"%.1f".format(a.bpm)} beatStrength=${"%.2f".format(a.beatStrength)} brightness=${"%.3f".format(a.brightness)} " +
                "bass=${"%.3f".format(a.bass)} start=${"%.2f".format(a.start)} reelLen=${"%.2f".format(a.len)} " +
                "beats=${a.rel.joinToString(",") { "%.2f".format(it) }} shots=${res.shots} " +
                "shotMin=${"%.2f".format(lens.min())} shotMax=${"%.2f".format(lens.max())} cuts=${plan.shots.joinToString(",") { "%.2f".format(it.t1) }} " +
                "outDurMs=$durMs bytes=${out.length()} analyseMs=${t1 - t0} renderMs=${t2 - t1}"
            Log.i("RCTEST", line)
            report.appendLine(line)
            assertTrue("reel ${i + 1} too small", out.length() > 50_000)
            assertTrue("reel ${i + 1} wrong length $durMs", kotlin.math.abs(durMs - a.len * 1000) < 700)
        }
        File(outDir, "report.txt").writeText(report.toString())
    }
}
