package com.srisomesh.reelcut

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.srisomesh.reelcut.engine.ClipScanner
import com.srisomesh.reelcut.engine.MediaInfo
import com.srisomesh.reelcut.engine.Pace
import com.srisomesh.reelcut.engine.ReelOptions
import com.srisomesh.reelcut.engine.Shape
import com.srisomesh.reelcut.engine.Plan
import com.srisomesh.reelcut.engine.Project
import com.srisomesh.reelcut.engine.ProjectStore
import com.srisomesh.reelcut.engine.ReelEngine
import com.srisomesh.reelcut.engine.ReelSession
import com.srisomesh.reelcut.engine.Shot
import com.srisomesh.reelcut.engine.ShotEdits
import com.srisomesh.reelcut.engine.YouTube
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
        val pace: String = "",
        val exporting: Boolean = false,
        val exportProgress: Float = 0f,
        val exported: File? = null,
    ) : Screen
    data class Viewer(val item: Library.Item) : Screen
    /** After picking clips + song: music, shape, cut speed, title, then Make reel. [youtube] = the song came from YouTube. */
    data class Setup(val songName: String, val youtube: Boolean = false) : Screen
    /** YouTube song search; [back] = where Back goes (Home or the setup screen). */
    data class YouTube(val back: Screen) : Screen
    /** Timeline editor with project [id] open. */
    data class Editor(val id: String) : Screen
    /** Fine-tune: the shots being edited (not yet applied), the selected one, and the result to go back to. */
    data class FineTune(
        val shots: List<Shot>,
        val sel: Int,
        val grid: DoubleArray,
        val back: Result,
        val changed: Boolean = false,
    ) : Screen
}

@UnstableApi
class ReelViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("reelcut", android.content.Context.MODE_PRIVATE)
    val screen = MutableStateFlow<Screen>(Screen.Home)

    /** Setup choices; shape and cut speed are remembered, the title is not. */
    val options = MutableStateFlow(
        ReelOptions(
            pace = runCatching { Pace.valueOf(prefs.getString("pace", "AUTO")!!) }.getOrDefault(Pace.AUTO),
            shape = runCatching { Shape.valueOf(prefs.getString("shape", "PORTRAIT")!!) }.getOrDefault(Shape.PORTRAIT),
        )
    )
    private var pendingSong: Uri? = null

    fun setPace(p: Pace) { options.value = options.value.copy(pace = p); prefs.edit().putString("pace", p.name).apply() }
    fun setShape(s: Shape) { options.value = options.value.copy(shape = s); prefs.edit().putString("shape", s.name).apply() }
    fun setTitle(t: String) { options.value = options.value.copy(title = t.take(28)) }

    /** Song picked (a file, or a YouTube song saved to the phone): show the setup screen. */
    fun songPicked(song: Uri, songName: String? = null, youtube: Boolean = false) {
        if (pendingMedia.size < 2) { say("Pick at least 2 clips or photos"); return }
        pendingSong = song
        // a new reel starts without a title; changing the song on the setup screen keeps the typed title
        if (screen.value !is Screen.Setup && (screen.value as? Screen.YouTube)?.back !is Screen.Setup) {
            options.value = options.value.copy(title = "")
        }
        val name = songName ?: runCatching {
            app.contentResolver.query(song, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Your song"
        screen.value = Screen.Setup(name, youtube)
    }

    // -------------------------------------------------------- YouTube songs ---

    data class YtState(
        val query: String = "",
        val searching: Boolean = false,
        val results: List<YouTube.Item> = emptyList(),
        val error: String? = null,
        val downloading: String? = null,    // url of the song being downloaded
        val progress: Float = 0f,
    )
    val yt = MutableStateFlow(YtState())
    private var ytJob: Job? = null

    fun openYouTube() {
        if (pendingMedia.size < 2) { say("Pick at least 2 clips or photos"); return }
        val cur = screen.value
        screen.value = Screen.YouTube(if (cur is Screen.Setup) cur else Screen.Home)
    }

    fun ytQuery(q: String) { yt.value = yt.value.copy(query = q.take(80)) }

    fun ytSearch() {
        val q = yt.value.query.trim()
        if (q.isEmpty()) return
        ytJob?.cancel()
        yt.value = yt.value.copy(searching = true, error = null, results = emptyList())
        ytJob = viewModelScope.launch {
            try {
                val r = YouTube.search(q)
                yt.value = yt.value.copy(searching = false, results = r, error = if (r.isEmpty()) "No songs found. Try other words." else null)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e("ReelCut", "youtube search", e)
                yt.value = yt.value.copy(searching = false, error = "YouTube search is not working right now. Check the internet and try again.")
            }
        }
    }

    /** Tap on a result: download its sound, then use it as the song. */
    fun ytUse(item: YouTube.Item) {
        if (yt.value.downloading != null) return
        ytJob?.cancel()
        yt.value = yt.value.copy(downloading = item.url, progress = 0f, error = null)
        ytJob = viewModelScope.launch {
            try {
                val f = YouTube.download(item, java.io.File(app.cacheDir, "youtube")) { p -> yt.value = yt.value.copy(progress = p) }
                yt.value = yt.value.copy(downloading = null)
                val mins = item.seconds / 60
                songPicked(Uri.fromFile(f), "${item.title} · $mins:${(item.seconds % 60).toString().padStart(2, '0')}", youtube = true)
            } catch (c: CancellationException) {
                yt.value = yt.value.copy(downloading = null)
                throw c
            } catch (e: Exception) {
                Log.e("ReelCut", "youtube download", e)
                yt.value = yt.value.copy(downloading = null)
                say(e.message?.takeIf { e is IllegalArgumentException || e is IllegalStateException } ?: "This song could not be downloaded. Try another one.")
            }
        }
    }
    val reelLen = MutableStateFlow(15)
    val recent = MutableStateFlow(Library.list(app))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var pendingMedia: List<Uri> = emptyList()
    private var session: ReelSession? = null
    private var variation = 0
    private var lookKey: String? = null
    private var plan: Plan? = null
    private var edited = false   // the plan has hand edits from Fine-tune
    private var job: Job? = null

    /** The song and every picked clip/photo, for the Fine-tune screen. */
    val sessionSong: Uri? get() = session?.song
    val songStart: Double get() = session?.analysis?.start ?: 0.0
    val pickedMedia: List<MediaInfo> get() = session?.media?.filter { it.ok }.orEmpty()

    /** "Clip 2" / "Photo 1", numbered in the order they were picked. */
    fun mediaName(m: MediaInfo?): String {
        val all = session?.media ?: return ""
        if (m == null) return ""
        val same = all.filter { it.isImage == m.isImage }
        return (if (m.isImage) "Photo " else "Clip ") + (same.indexOf(m) + 1)
    }

    private fun newPlan() {
        val s = session ?: return
        plan = ReelEngine.plan(s, variation, lookKey, options.value)
        edited = false
    }

    fun say(msg: String) { messages.tryEmit(msg) }

    fun setMedia(uris: List<Uri>) { pendingMedia = uris }

    /** Setup -> Make reel. */
    fun start() {
        val media = pendingMedia
        val song = pendingSong ?: return
        if (media.size < 2) { say("Pick at least 2 clips or photos"); return }
        variation = 0
        lookKey = null
        session = null
        job?.cancel()
        screen.value = Screen.Working("Starting", 0f)
        job = viewModelScope.launch {
            try {
                session = ReelEngine.prepare(app, media, song, reelLen.value.toDouble()) { st, p -> update(st, p) }
                newPlan()
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
        val pl = plan ?: return
        screen.value = Screen.Working("Making your reel", 0.4f)
        withContext(Dispatchers.IO) {
            app.cacheDir.listFiles { f -> f.name.startsWith("preview_") }?.forEach { it.delete() }
        }
        val out = File(app.cacheDir, "preview_${System.currentTimeMillis()}.mp4")
        val o = options.value
        val res = ReelEngine.render(app, s, pl, o.shape.pw, o.shape.ph, out, o.title) { st, p -> update(st, p) }
        screen.value = Screen.Result(out, res.style.look.key, res.style.look.name, s.analysis.bpm.roundToInt(), res.style.pace.label)
    }

    private fun rerender() {
        if (session == null) return
        job?.cancel()
        job = viewModelScope.launch {
            try { renderPreview() } catch (c: CancellationException) { throw c } catch (e: Exception) { fail(e) }
        }
    }

    fun anotherEdit() { variation++; lookKey = null; newPlan(); rerender() }

    fun pickLook(key: String) {
        val r = screen.value as? Screen.Result ?: return
        if (r.exporting || key == r.lookKey) return
        lookKey = key
        // hand edits are kept; only the look changes
        if (edited) plan = plan?.withLook(key) else newPlan()
        rerender()
    }

    fun export() {
        val r = screen.value as? Screen.Result ?: return
        val s = session ?: return
        val pl = plan ?: return
        if (r.exporting) return
        screen.value = r.copy(exporting = true, exportProgress = 0f)
        job = viewModelScope.launch {
            try {
                val out = Library.newFile(app)
                val o = options.value
                ReelEngine.render(app, s, pl, o.shape.w, o.shape.h, out, o.title) { _, p ->
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

    // ---------------------------------------------------------- Fine-tune ---

    fun openFineTune() {
        val r = screen.value as? Screen.Result ?: return
        val pl = plan ?: return
        if (r.exporting) return
        screen.value = Screen.FineTune(pl.shots, 0, pl.grid, r)
    }

    private fun editFt(change: (Screen.FineTune) -> List<Shot>?, failMsg: String? = null) {
        val ft = screen.value as? Screen.FineTune ?: return
        val next = change(ft)
        if (next == null) { failMsg?.let { say(it) }; return }
        screen.value = ft.copy(shots = next, sel = ft.sel.coerceAtMost(next.lastIndex), changed = true)
    }

    fun selectShot(i: Int) {
        val ft = screen.value as? Screen.FineTune ?: return
        if (i in ft.shots.indices) screen.value = ft.copy(sel = i)
    }

    fun changeLength(d: Int) = editFt(
        { ShotEdits.changeLength(it.shots, it.grid, it.sel, d) },
        if (d < 0) "This shot is already 1 beat" else "No room to make this shot longer",
    )

    /** Edge handles: shot [d] beats longer/shorter; [newSrc0] = new clip start when the left edge was dragged. */
    fun resizeShot(d: Int, newSrc0: Double?) = editFt({ ft ->
        var cur = ft.shots
        var done = 0
        val step = if (d > 0) 1 else -1
        while (done < kotlin.math.abs(d)) {
            cur = ShotEdits.changeLength(cur, ft.grid, ft.sel, step) ?: break
            done++
        }
        if (done == 0 && newSrc0 == null) null
        else if (newSrc0 != null) ShotEdits.setMoment(cur, ft.sel, newSrc0) else cur
    }, "No room to change this shot")

    /** Song on/off in the Fine-tune preview (remembered; never affects the exported reel). */
    val previewMuted = MutableStateFlow(prefs.getBoolean("ft_muted", false))
    fun toggleMute() {
        previewMuted.value = !previewMuted.value
        prefs.edit().putBoolean("ft_muted", previewMuted.value).apply()
    }

    fun removeShot() = editFt({ ShotEdits.remove(it.shots, it.grid, it.sel) }, "A reel needs at least one shot")

    fun setMoment(src0: Double) = editFt({ ShotEdits.setMoment(it.shots, it.sel, src0) })

    fun setClip(m: MediaInfo) = editFt({ ft ->
        val sh = ft.shots[ft.sel]
        ShotEdits.setMedia(ft.shots, ft.sel, m, ClipScanner.bestMoment(m, sh.t1 - sh.t0))
    })

    /** For the emulator UI test only: load a made session + plan and open Fine-tune on shot [sel]. */
    fun loadForTest(s: ReelSession, p: Plan, sel: Int) {
        session = s; plan = p
        val r = Screen.Result(File(app.cacheDir, "none.mp4"), p.style.look.key, p.style.look.name, s.analysis.bpm.roundToInt())
        screen.value = Screen.FineTune(p.shots, sel, p.grid, r)
    }

    fun applyFineTune() {
        val ft = screen.value as? Screen.FineTune ?: return
        val pl = plan ?: return
        if (!ft.changed) { screen.value = ft.back; return }
        plan = Plan(pl.style, ft.shots, ft.grid)
        edited = true
        rerender()
    }

    // ------------------------------------------------------- timeline projects ---

    val projects = MutableStateFlow(ProjectStore.list(app))

    fun refreshLists() {
        projects.value = ProjectStore.list(app)
        recent.value = Library.list(app)
    }

    /** Home -> New project: the picked clips become a new project, opened in the editor. */
    fun newProject(uris: List<Uri>) {
        viewModelScope.launch {
            val clips = withContext(Dispatchers.IO) { ProjectStore.probe(app, uris) }
            if (clips.isEmpty()) { say("These files could not be read"); return@launch }
            val p = ProjectStore.newProject(app, clips)
            withContext(Dispatchers.IO) { ProjectStore.save(app, p) }
            projects.value = ProjectStore.list(app)
            screen.value = Screen.Editor(p.id)
            if (clips.size < uris.size) say("${uris.size - clips.size} file(s) could not be read")
        }
    }

    fun openProject(id: String) {
        if (ProjectStore.load(app, id) == null) { say("This project could not be opened"); return }
        screen.value = Screen.Editor(id)
    }

    fun renameProject(p: Project, name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        ProjectStore.save(app, p.copy(name = clean, updated = System.currentTimeMillis()))
        projects.value = ProjectStore.list(app)
    }

    fun deleteProject(p: Project) {
        ProjectStore.delete(app, p.id)
        projects.value = ProjectStore.list(app)
        say("Project deleted")
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
            is Screen.Viewer, is Screen.Setup -> screen.value = Screen.Home
            is Screen.YouTube -> {
                ytJob?.cancel()
                yt.value = yt.value.copy(downloading = null, searching = false)
                screen.value = (screen.value as Screen.YouTube).back
            }
            is Screen.Editor -> { screen.value = Screen.Home; refreshLists() }
            is Screen.FineTune -> {
                val ft = screen.value as Screen.FineTune
                screen.value = ft.back
                if (ft.changed) say("Changes not applied")
            }
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
