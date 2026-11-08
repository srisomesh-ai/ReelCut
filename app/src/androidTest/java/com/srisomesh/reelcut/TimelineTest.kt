package com.srisomesh.reelcut

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srisomesh.reelcut.engine.ProjectStore
import com.srisomesh.reelcut.engine.Renderer
import com.srisomesh.reelcut.engine.TimelineComposer
import com.srisomesh.reelcut.engine.TimelineEdits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Timeline editor on the emulator: makes a project from the test clips, edits it, exports it,
 * plays it in the preview player, and saves it as "test_timeline" so CI can open it for a screenshot.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TimelineTest {

    @Test
    fun editExportAndPreview() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val inDir = File(ctx.cacheDir, "test-in").apply { mkdirs() }
        val names = assets.list("")!!.filter { it.endsWith(".mp4") || it.endsWith(".jpg") }.sorted()
        names.forEach { n -> assets.open(n).use { i -> File(inDir, n).outputStream().use { i.copyTo(it) } } }
        val uris = names.map { Uri.fromFile(File(inDir, it)) }

        // project from all clips + photos, then a few edits
        val clips = ProjectStore.probe(ctx, uris)
        assertEquals("every test file is read", uris.size, clips.size)
        var p = ProjectStore.newProject(ctx, clips).copy(id = "test_timeline", name = "Test timeline")
        val firstVideo = p.clips.first { !it.isImage }
        p = TimelineEdits.trimLeft(p, firstVideo.id, 0.8)!!
        p = TimelineEdits.trimRight(p, firstVideo.id, -0.5)!!
        val photo = p.clips.first { it.isImage }
        p = TimelineEdits.trimRight(p, photo.id, -1.0)!!                // photo 3 s -> 2 s
        p = TimelineEdits.split(p, p.starts()[0] + 1.0)!!.first
        p = TimelineEdits.move(p, p.clips.last().id, 0)!!
        p = TimelineEdits.duplicate(p, p.clips[1].id)!!.first
        p = TimelineEdits.delete(p, p.clips[3].id)!!
        ProjectStore.save(ctx, p)
        ProjectStore.writeCover(ctx, p)
        assertEquals("saved project loads back the same", p, ProjectStore.load(ctx, "test_timeline"))

        // export
        val outDir = File(ctx.filesDir, "test-out").apply { mkdirs() }
        val out = File(outDir, "timeline.mp4")
        val t0 = SystemClock.uptimeMillis()
        Renderer.export(ctx, TimelineComposer.build(p, 720, 1280), out, 720) { }
        val renderMs = SystemClock.uptimeMillis() - t0
        val r = MediaMetadataRetriever()
        r.setDataSource(out.path)
        val outMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        r.release()

        // preview player: must play past 1.5 s without an error
        var player: CompositionPlayer? = null
        var err: PlaybackException? = null
        inst.runOnMainSync {
            player = CompositionPlayer.Builder(ctx).build().apply {
                addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { err = error } })
                setComposition(TimelineComposer.build(p, 720, 1280))
                prepare()
                play()
            }
        }
        var pos = 0L
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until && err == null && pos < 1500) {
            Thread.sleep(200)
            inst.runOnMainSync { pos = player!!.currentPosition }
        }
        inst.runOnMainSync { player!!.release() }

        File(outDir, "timeline.txt").writeText(
            "timeline: clips=${p.clips.size} total=${"%.2f".format(p.total)} outDurMs=$outMs audio=$hasAudio " +
                "renderMs=$renderMs previewPosMs=$pos previewError=${err?.errorCodeName} " +
                "clipLens=${p.clips.joinToString(",") { "%.2f".format(it.dur) + if (it.isImage) "p" else "v" }}\n"
        )
        assertNull("preview error: ${err?.errorCodeName}", err)
        assertTrue("preview plays (pos=$pos)", pos >= 1500)
        assertEquals("export length = timeline length", p.total * 1000, outMs.toDouble(), 150.0)
        assertTrue("export keeps a sound track", hasAudio)
    }
}
