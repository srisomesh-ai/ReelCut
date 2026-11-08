package com.srisomesh.reelcut.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/** Turns the planned shots into an MP4 with the phone's hardware encoder (Media3 Transformer). */
@UnstableApi
object Renderer {

    suspend fun render(
        context: Context,
        a: SongAnalysis,
        style: Style,
        shots: List<Shot>,
        songUri: Uri,
        out: File,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit,
    ) = export(context, buildComposition(a, style, shots, songUri, width, height), out, width, onProgress)

    /** Encodes any composition to an MP4 (auto reels and timeline projects). */
    suspend fun export(
        context: Context,
        composition: Composition,
        out: File,
        width: Int,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val bitrate = if (width >= 1080) 14_000_000 else 6_000_000
        coroutineScope {
            var transformer: Transformer? = null
            val poll = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    delay(250)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val tr = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                .setEnableFallback(true)
                                .build()
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer = tr
                    val main = Handler(Looper.getMainLooper())
                    cont.invokeOnCancellation { main.post { tr.cancel() } }
                    tr.start(composition, out.absolutePath)
                }
                onProgress(1f)
            } finally {
                poll.cancel()
            }
        }
    }

    private fun buildComposition(a: SongAnalysis, style: Style, shots: List<Shot>, songUri: Uri, width: Int, height: Int): Composition {
        // Shot lengths in ms. A clip shorter than its shot gives the rest to the next shot, so cuts stay on time.
        val durs = LongArray(shots.size) { ((shots[it].t1 - shots[it].t0) * 1000).roundToLong() }
        for (i in shots.indices) {
            val m = shots[i].media ?: continue
            if (m.isImage) continue
            val avail = ((m.dur - shots[i].src0) * 1000).toLong() - 40
            if (durs[i] > avail && avail > 150) {
                val short = durs[i] - avail
                durs[i] = avail
                if (i + 1 < durs.size) durs[i + 1] += short
            }
        }
        val items = shots.mapIndexed { i, sh ->
            val m = sh.media!!
            val durMs = durs[i]
            val hits = a.hits.filter { it.t >= sh.t0 - 0.3 && it.t < sh.t0 + durMs / 1000.0 }.map { Hit(it.t - sh.t0, it.s) }
            val fx = ShotFx(style, sh, m.isImage, durMs / 1000.0, hits, fadeOutAtEnd = i == shots.size - 1)
            val effects = Effects(
                emptyList<AudioProcessor>(),
                listOf<Effect>(
                    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                    LookEffect(fx),
                )
            )
            if (m.isImage) {
                val mi = MediaItem.Builder().setUri(m.uri).setImageDurationMs(durMs).build()
                EditedMediaItem.Builder(mi).setFrameRate(30).setEffects(effects).build()
            } else {
                val startMs = (sh.src0 * 1000).roundToLong()
                val mi = MediaItem.Builder()
                    .setUri(m.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(startMs + durMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mi).setRemoveAudio(true).setEffects(effects).build()
            }
        }
        val totalMs = durs.sum()
        val songStart = (a.start * 1000).roundToLong()
        val song = MediaItem.Builder()
            .setUri(songUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(songStart)
                    .setEndPositionMs(songStart + totalMs)
                    .build()
            )
            .build()
        val songItem = EditedMediaItem.Builder(song)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(FadeAudioProcessor(totalMs * 1000)), emptyList()))
            .build()
        return Composition.Builder(
            listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(songItem)))
        ).build()
    }
}
