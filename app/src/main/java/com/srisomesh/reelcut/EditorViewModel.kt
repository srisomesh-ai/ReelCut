package com.srisomesh.reelcut

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.srisomesh.reelcut.engine.Project
import com.srisomesh.reelcut.engine.ProjectStore
import com.srisomesh.reelcut.engine.Renderer
import com.srisomesh.reelcut.engine.TimelineComposer
import com.srisomesh.reelcut.engine.TimelineEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/** Timeline editor (phase 2a): one open project, its undo history, the live preview and export. */
@UnstableApi
class EditorViewModel(private val app: Application) : AndroidViewModel(app) {

    data class State(
        val project: Project? = null,
        val selected: Long? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
    )

    val state = MutableStateFlow(State())
    val playhead = MutableStateFlow(0.0)            // seconds on the timeline
    val playing = MutableStateFlow(false)
    val player = MutableStateFlow<CompositionPlayer?>(null)
    val exporting = MutableStateFlow<Float?>(null)  // export progress 0..1, null = not exporting
    val exported = MutableStateFlow<File?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var tick: Job? = null
    private var exportJob: Job? = null
    private var lastSeek = 0L

    private val project get() = state.value.project

    fun say(m: String) { messages.tryEmit(m) }

    // ------------------------------------------------------------ open / close ---

    fun open(id: String) {
        if (project?.id == id) return
        val p = ProjectStore.load(app, id) ?: run { say("This project could not be opened"); return }
        undo.clear(); redo.clear()
        state.value = State(p, p.clips.firstOrNull()?.id)
        playhead.value = 0.0
        exported.value = null
        rebuild()
    }

    /** Saves, writes the cover picture, frees the player; [done] runs when the cover is written. */
    fun close(done: () -> Unit) {
        pause()
        player.value?.release(); player.value = null
        val p = project ?: return done()
        state.value = State()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProjectStore.save(app, p); ProjectStore.writeCover(app, p) }
            done()
        }
    }

    override fun onCleared() { player.value?.release() }

    // ---------------------------------------------------------------- preview ---

    /** New preview player for the current project, kept at the playhead. */
    private fun rebuild() {
        val wasPlaying = playing.value
        pause()
        player.value?.release()
        val p = project
        if (p == null || p.clips.isEmpty()) { player.value = null; playhead.value = 0.0; return }
        playhead.value = playhead.value.coerceIn(0.0, p.total)
        val pl = CompositionPlayer.Builder(app).build()
        pl.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("ReelCut", "preview error", error)
                say("Preview problem: ${error.errorCodeName}")
                playing.value = false
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) { playing.value = false; tick?.cancel(); playhead.value = project?.total ?: 0.0 }
            }
        })
        pl.setComposition(TimelineComposer.build(p, 720, 1280))
        pl.prepare()
        pl.seekTo((playhead.value * 1000).roundToLong())
        player.value = pl
        if (wasPlaying) play()
    }

    fun togglePlay() = if (playing.value) pause() else play()

    fun play() {
        val pl = player.value ?: return
        val p = project ?: return
        if (playhead.value >= p.total - 0.05) { playhead.value = 0.0; pl.seekTo(0) }
        pl.play()
        playing.value = true
        tick?.cancel()
        tick = viewModelScope.launch {
            while (isActive && playing.value) {
                playhead.value = pl.currentPosition / 1000.0
                delay(40)
            }
        }
    }

    fun pause() {
        player.value?.pause()
        playing.value = false
        tick?.cancel()
    }

    /** Moves the playhead (timeline drag); the preview follows, at most every 80 ms while dragging. */
    fun scrubTo(t: Double, final: Boolean = false) {
        val p = project ?: return
        if (playing.value) pause()
        playhead.value = t.coerceIn(0.0, p.total)
        val now = SystemClock.uptimeMillis()
        if (final || now - lastSeek > 80) {
            lastSeek = now
            player.value?.seekTo((playhead.value * 1000).roundToLong())
        }
    }

    // ------------------------------------------------------------------ edits ---

    private fun commit(next: Project, select: Long? = state.value.selected) {
        val cur = project ?: return
        undo.addLast(cur); if (undo.size > 60) undo.removeFirst()
        redo.clear()
        set(next.copy(updated = System.currentTimeMillis()), select)
    }

    private fun set(p: Project, select: Long?) {
        val sel = select?.takeIf { id -> p.clips.any { it.id == id } }
        state.value = State(p, sel, undo.isNotEmpty(), redo.isNotEmpty())
        viewModelScope.launch(Dispatchers.IO) { ProjectStore.save(app, p) }
        rebuild()
    }

    fun select(id: Long?) { state.value = state.value.copy(selected = id) }

    private fun selectedOrSay(): Long? = state.value.selected ?: run { say("Tap a clip first"); null }

    fun trim(id: Long, left: Boolean, delta: Double) {
        val p = project ?: return
        val n = (if (left) TimelineEdits.trimLeft(p, id, delta) else TimelineEdits.trimRight(p, id, delta)) ?: return
        commit(n, id)
        // show the new edge in the preview
        val i = n.indexOf(id)
        val s = n.starts()[i]
        playhead.value = if (left) s else s + n.clips[i].dur - 0.04
    }

    fun split() {
        val p = project ?: return
        val (n, newId) = TimelineEdits.split(p, playhead.value)
            ?: run { say("Move the line inside a clip, away from its ends"); return }
        commit(n, newId)
    }

    fun duplicate() {
        val p = project ?: return
        val id = selectedOrSay() ?: return
        val (n, newId) = TimelineEdits.duplicate(p, id) ?: return
        commit(n, newId)
    }

    fun delete() {
        val p = project ?: return
        val id = selectedOrSay() ?: return
        val i = p.indexOf(id)
        val n = TimelineEdits.delete(p, id) ?: return
        commit(n, n.clips.getOrNull(i)?.id ?: n.clips.lastOrNull()?.id)
    }

    fun move(id: Long, to: Int) {
        val p = project ?: return
        val n = TimelineEdits.move(p, id, to) ?: return
        commit(n, id)
    }

    fun add(uris: List<Uri>) {
        if (project == null) return
        viewModelScope.launch {
            val clips = withContext(Dispatchers.IO) { ProjectStore.probe(app, uris) }
            if (clips.isEmpty()) { say("These files could not be read"); return@launch }
            val cur = project ?: return@launch
            val at = state.value.selected?.let { cur.indexOf(it) + 1 } ?: cur.clips.size
            val (n, first) = TimelineEdits.add(cur, at, clips) ?: return@launch
            commit(n, first)
            if (clips.size < uris.size) say("${uris.size - clips.size} file(s) could not be read")
        }
    }

    fun rename(name: String) {
        val p = project ?: return
        val clean = name.trim().take(40)
        if (clean.isEmpty() || clean == p.name) return
        commit(p.copy(name = clean))
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        project?.let { redo.addLast(it) }
        set(prev, state.value.selected)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        project?.let { undo.addLast(it) }
        set(next, state.value.selected)
    }

    // ----------------------------------------------------------------- export ---

    fun export() {
        val p = project ?: return
        if (p.clips.isEmpty()) { say("Add clips first"); return }
        if (exporting.value != null) return
        pause()
        exporting.value = 0f
        exportJob = viewModelScope.launch {
            try {
                val out = Library.newFile(app)
                Renderer.export(app, TimelineComposer.build(p, 1080, 1920), out, 1080) { f -> exporting.value = f }
                withContext(Dispatchers.IO) {
                    Library.finish(out, p.name)
                    Library.saveToGallery(app, out)
                }
                exported.value = out
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e("ReelCut", "project export failed", e)
                say("Export failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                exporting.value = null
            }
        }
    }

    fun cancelExport() { exportJob?.cancel(); exporting.value = null }

    fun doneExported() { exported.value = null }
}
