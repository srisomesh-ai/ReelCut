package com.srisomesh.reelcut

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.srisomesh.reelcut.engine.ReelEngine
import com.srisomesh.reelcut.engine.ReelSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    data class Working(val stage: String, val progress: Float) : Screen
    data class Result(
        val file: File,
        val lookKey: String,
        val lookName: String,
        val bpm: Int,
        val exporting: Boolean = false,
        val exportProgress: Float = 0f,
        val exported: File? = null,
    ) : Screen
    data class Viewer(val item: Library.Item) : Screen
}

@UnstableApi
class ReelViewModel(private val app: Application) : AndroidViewModel(app) {

    val screen = MutableStateFlow<Screen>(Screen.Home)
    val reelLen = MutableStateFlow(15)
    val recent = MutableStateFlow(Library.list(app))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var pendingMedia: List<Uri> = emptyList()
    private var session: ReelSession? = null
    private var variation = 0
    private var lookKey: String? = null
    private var job: Job? = null

    fun say(msg: String) { messages.tryEmit(msg) }

    fun setMedia(uris: List<Uri>) { pendingMedia = uris }

    fun start(song: Uri) {
        val media = pendingMedia
        if (media.size < 2) { say("Pick at least 2 clips or photos"); return }
        variation = 0
        lookKey = null
        session = null
        job?.cancel()
        screen.value = Screen.Working("Starting", 0f)
        job = viewModelScope.launch {
            try {
                session = ReelEngine.prepare(app, media, song, reelLen.value.toDouble()) { st, p -> update(st, p) }
                renderPreview()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun update(stage: String, p: Float) {
        if (screen.value is Screen.Working) screen.value = Screen.Working(stage, p)
    }

    private suspend fun renderPreview() {
        val s = session ?: return
        screen.value = Screen.Working("Making your reel", 0.4f)
        withContext(Dispatchers.IO) {
            app.cacheDir.listFiles { f -> f.name.startsWith("preview_") }?.forEach { it.delete() }
        }
        val out = File(app.cacheDir, "preview_${System.currentTimeMillis()}.mp4")
        val res = ReelEngine.render(app, s, variation, lookKey, 720, 1280, out) { st, p -> update(st, p) }
        screen.value = Screen.Result(out, res.style.look.key, res.style.look.name, s.analysis.bpm.roundToInt())
    }

    private fun rerender() {
        if (session == null) return
        job?.cancel()
        job = viewModelScope.launch {
            try { renderPreview() } catch (c: CancellationException) { throw c } catch (e: Exception) { fail(e) }
        }
    }

    fun anotherEdit() { variation++; lookKey = null; rerender() }

    fun pickLook(key: String) {
        val r = screen.value as? Screen.Result ?: return
        if (r.exporting || key == r.lookKey) return
        lookKey = key
        rerender()
    }

    fun export() {
        val r = screen.value as? Screen.Result ?: return
        val s = session ?: return
        if (r.exporting) return
        screen.value = r.copy(exporting = true, exportProgress = 0f)
        job = viewModelScope.launch {
            try {
                val out = Library.newFile(app)
                ReelEngine.render(app, s, variation, lookKey, 1080, 1920, out) { _, p ->
                    val cur = screen.value
                    if (cur is Screen.Result && cur.exporting) screen.value = cur.copy(exportProgress = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f))
                }
                withContext(Dispatchers.IO) {
                    Library.finish(out, "${r.lookName} · ${reelLenLabel()}")
                    Library.saveToGallery(app, out)
                }
                recent.value = Library.list(app)
                val cur = screen.value
                if (cur is Screen.Result) screen.value = cur.copy(exporting = false, exported = out)
                say("Saved to your gallery (Movies/ReelCut)")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e("ReelCut", "export failed", e)
                val cur = screen.value
                if (cur is Screen.Result) screen.value = cur.copy(exporting = false)
                say("Export failed: ${friendly(e)}")
            }
        }
    }

    private fun reelLenLabel(): String {
        val s = session ?: return ""
        return "${s.analysis.len.roundToInt()}s"
    }

    fun open(item: Library.Item) { screen.value = Screen.Viewer(item) }

    fun delete(item: Library.Item) {
        Library.delete(item)
        recent.value = Library.list(app)
        screen.value = Screen.Home
        say("Reel deleted from the app")
    }

    fun back() {
        when (screen.value) {
            is Screen.Working, is Screen.Result -> { job?.cancel(); screen.value = Screen.Home }
            is Screen.Viewer -> screen.value = Screen.Home
            Screen.Home -> {}
        }
    }

    private fun fail(e: Exception) {
        Log.e("ReelCut", "failed", e)
        screen.value = Screen.Home
        say(friendly(e))
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return if (e is IllegalArgumentException && m.isNotBlank()) m
        else "Could not make the reel. Try other clips or another song. (${e.javaClass.simpleName})"
    }
}
