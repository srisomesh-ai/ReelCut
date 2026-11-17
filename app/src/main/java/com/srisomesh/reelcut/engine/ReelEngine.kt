package com.srisomesh.reelcut.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** The song and clips, analysed once; every edit (style, variation, export) reuses this. */
class ReelSession(val song: Uri, val analysis: SongAnalysis, val media: List<MediaInfo>)

class ReelResult(val file: File, val style: Style, val shots: Int)

/** Reel shape: export size and the lighter preview size (multiples of 16 where the web page needed them). */
enum class Shape(val label: String, val w: Int, val h: Int, val pw: Int, val ph: Int) {
    PORTRAIT("9:16", 1080, 1920, 720, 1280),
    FOUR_FIVE("4:5", 1088, 1360, 832, 1040),
    SQUARE("1:1", 1088, 1088, 960, 960),
    WIDE("16:9", 1920, 1080, 1280, 720),
}

/** Choices from the setup screen. */
data class ReelOptions(val pace: Pace = Pace.AUTO, val shape: Shape = Shape.PORTRAIT, val title: String = "")

@UnstableApi
object ReelEngine {

    suspend fun prepare(
        context: Context,
        media: List<Uri>,
        song: Uri,
        reelLen: Double,
        progress: (String, Float) -> Unit,
    ): ReelSession {
        progress("Listening to the song", 0.03f)
        val analysis = withContext(Dispatchers.Default) {
            val pcm = AudioDecoder.decodeMono(context, song, SongAnalyzer.SR, 480.0)
            progress("Finding the beats", 0.10f)
            SongAnalyzer.analyze(pcm, reelLen)
        }
        progress("Finding the best moments", 0.15f)
        val scanned = ClipScanner.scan(context, media) { f -> progress("Finding the best moments", 0.15f + 0.25f * f) }
        return ReelSession(song, analysis, scanned)
    }

    /** Same session + variation + look always gives the same plan, so the export matches the preview. */
    fun plan(session: ReelSession, variation: Int, forcedLook: String?, opts: ReelOptions = ReelOptions()): Plan {
        val r = Random(session.analysis.seed + variation * 7919)
        val style = Styles.choose(session.analysis, r, forcedLook, opts.pace, session.media.count { it.ok })
        val shots = Styles.planShots(session.analysis, style, r)
        ClipScanner.assign(shots, session.media, r)
        return Plan(style, shots, Styles.grid(session.analysis))
    }

    /** Renders a plan (automatic or fine-tuned by hand). */
    suspend fun render(
        context: Context,
        session: ReelSession,
        plan: Plan,
        width: Int,
        height: Int,
        out: File,
        title: String = "",
        progress: (String, Float) -> Unit,
    ): ReelResult {
        progress("Making your reel", 0.4f)
        Renderer.render(context, session.analysis, plan.style, plan.shots, session.song, out, width, height, title) { f ->
            progress("Making your reel", 0.4f + 0.6f * f)
        }
        return ReelResult(out, plan.style, plan.shots.size)
    }

    suspend fun render(
        context: Context,
        session: ReelSession,
        variation: Int,
        forcedLook: String?,
        width: Int,
        height: Int,
        out: File,
        progress: (String, Float) -> Unit,
    ): ReelResult = render(context, session, plan(session, variation, forcedLook), width, height, out, progress = progress)
}
