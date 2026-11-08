package com.srisomesh.reelcut

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srisomesh.reelcut.engine.ReelEngine
import com.srisomesh.reelcut.ui.App
import com.srisomesh.reelcut.ui.ReelCutTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the Fine-tune screen on a real session, saves a screenshot, and checks that "+ 1 beat" keeps the reel length. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FineTuneUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun fineTuneScreen() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val assets = inst.context.assets
        val inDir = File(ctx.cacheDir, "test-in").apply { mkdirs() }
        val names = assets.list("")!!.filter { it.endsWith(".mp4") || it.endsWith(".jpg") || it.endsWith(".mp3") }
        names.forEach { n -> assets.open(n).use { i -> File(inDir, n).outputStream().use { i.copyTo(it) } } }
        val media = names.filter { it.endsWith(".mp4") || it.endsWith(".jpg") }.sorted().map { Uri.fromFile(File(inDir, it)) }

        val session = runBlocking { ReelEngine.prepare(ctx, media, Uri.fromFile(File(inDir, "song_fast.mp3")), 15.0) { _, _ -> } }
        val plan = ReelEngine.plan(session, 0, null)
        // open on a video shot (not the last) so the screenshot shows the moment bar
        val sel = plan.shots.indices.first { it >= 3 && it < plan.shots.lastIndex && plan.shots[it].media?.isImage == false }

        lateinit var vm: ReelViewModel
        inst.runOnMainSync {
            vm = ReelViewModel(ctx.applicationContext as Application)
            vm.loadForTest(session, plan, sel)
        }
        rule.setContent { ReelCutTheme { App(vm) } }
        rule.waitForIdle()
        Thread.sleep(2500)   // thumbnails + preview load
        rule.waitForIdle()

        val out = File(ctx.filesDir, "test-out").apply { mkdirs() }
        File(out, "finetune.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val before = (vm.screen.value as Screen.FineTune).shots
        rule.onNodeWithText("+ 1 beat").performClick()
        rule.waitForIdle()
        val ft = vm.screen.value as Screen.FineTune
        assertTrue("edit should be marked", ft.changed)
        assertEquals("selected shot one beat longer", before[sel].b1 + 1, ft.shots[sel].b1)
        assertEquals("reel keeps its length", before.last().t1, ft.shots.last().t1, 0.001)
    }
}
