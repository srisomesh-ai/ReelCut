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

    /** Same session + variation + look always gives the same edit, so the export matches the preview. */
    suspend fun render(
        context: Context,
        session: ReelSession,
        variation: Int,
        forcedLook: String?,
        width: Int,
        height: Int,
        out: File,
        progress: (String, Float) -> Unit,
    ): ReelResult {
        val r = Random(session.analysis.seed + variation * 7919)
        val style = Styles.choose(session.analysis, r, forcedLook)
        val shots = Styles.planShots(session.analysis, style, r)
        ClipScanner.assign(shots, session.media, r)
        progress("Making your reel", 0.4f)
        Renderer.render(context, session.analysis, style, shots, session.song, out, width, height) { f ->
            progress("Making your reel", 0.4f + 0.6f * f)
        }
        return ReelResult(out, style, shots.size)
    }
}
