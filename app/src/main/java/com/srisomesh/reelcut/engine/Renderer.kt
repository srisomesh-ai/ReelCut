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
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.VideoCompositorSettings
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
        title: String = "",
        onProgress: (Float) -> Unit,
    ) = export(context, buildComposition(context, a, style, shots, songUri, width, height, title), out, width, onProgress)

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

    private fun buildComposition(
        context: Context, a: SongAnalysis, style: Style, shots: List<Shot>, songUri: Uri, width: Int, height: Int, title: String,
    ): Composition {
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
        val startsMs = LongArray(shots.size).also { for (i in 1 until shots.size) it[i] = it[i - 1] + durs[i - 1] }
        val present = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        fun hitsFor(i: Int) = a.hits.filter { it.t >= shots[i].t0 - 0.3 && it.t < shots[i].t0 + durs[i] / 1000.0 }.map { Hit(it.t - shots[i].t0, it.s) }

        /** One piece of a clip: [fromMs]..+[lenMs] of shot i's media, drawn by [fx]. */
        fun piece(i: Int, fromMs: Long, lenMs: Long, fx: ShotFx): EditedMediaItem {
            val m = shots[i].media!!
            val effects = Effects(emptyList<AudioProcessor>(), listOf<Effect>(present, LookEffect(fx)))
            return if (m.isImage) {
                val mi = MediaItem.Builder().setUri(m.uri).setImageDurationMs(lenMs).build()
                EditedMediaItem.Builder(mi).setFrameRate(30).setEffects(effects).build()
            } else {
                val mi = MediaItem.Builder()
                    .setUri(m.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(fromMs + lenMs).build()
                    )
                    .build()
                EditedMediaItem.Builder(mi).setRemoveAudio(true).setEffects(effects).build()
            }
        }

        // top layer: every shot in order
        val items = shots.mapIndexed { i, sh ->
            piece(i, (sh.src0 * 1000).roundToLong(), durs[i], ShotFx(style, sh, sh.media!!.isImage, durs[i] / 1000.0, hitsFor(i), i == shots.size - 1, index = i))
        }

        // lower layer (only when there are dissolves / zoom-throughs): under each such cut the previous shot carries on
        // while the new one fades in on top; elsewhere a black picture that is never seen
        val blend = { k: Int -> shots[k].trans == Trans.FADE || shots[k].trans == Trans.ZOOM }
        val layered = shots.indices.drop(1).any(blend)
        val fadeMs = LongArray(shots.size)
        val lower = ArrayList<EditedMediaItem>()
        if (layered) {
            val black = blackPicture(context)
            fun filler(ms: Long) {
                if (ms <= 0) return
                val mi = MediaItem.Builder().setUri(Uri.fromFile(black)).setImageDurationMs(ms).build()
                lower.add(EditedMediaItem.Builder(mi).setFrameRate(30).setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(present))).build())
            }
            var gap = 0L
            for (k in shots.indices) {
                if (k == 0 || !blend(k)) { gap += durs[k]; continue }
                val len = ((if (shots[k].trans == Trans.FADE) style.fadeLen else style.zoomLen) * 1000).roundToLong()
                    .coerceAtMost(durs[k] - 100).coerceAtLeast(100)
                fadeMs[k] = len
                // the carry-on starts a little before the cut, so the two layers never meet on the black filler
                val pre = minOf(150L, gap - 34).coerceAtLeast(0)
                filler(gap - pre); gap = 0
                val prev = shots[k - 1]
                val m = prev.media!!
                var from = (prev.src0 * 1000).roundToLong() + durs[k - 1] - pre
                if (!m.isImage && from + pre + len > (m.dur * 1000).toLong() - 40) from = ((m.dur * 1000).toLong() - 40 - len - pre).coerceAtLeast(0)
                val fx = ShotFx(style, prev, m.isImage, durs[k - 1] / 1000.0, hitsFor(k - 1), false,
                    tOffset = (durs[k - 1] - pre) / 1000.0, zoomTail = shots[k].trans == Trans.ZOOM, index = k - 1)
                lower.add(piece(k - 1, from, pre + len, fx))
                gap += durs[k] - len
            }
            filler(gap)
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

        // over the whole reel: the look, then title / VHS stamp, then the fade to black at the end
        val total = totalMs / 1000.0
        val flashAt = shots.indices.filter { shots[it].trans == Trans.FLASH }.map { startsMs[it] / 1000.0 }
        val finish = ArrayList<Effect>()
        finish.add(FinishEffect(style.look, flashAt, total))
        val overlays = ArrayList<TextureOverlay>()
        if (style.look.stamp) overlays.add(ReelOverlays.stamp(width, height))
        if (title.isNotBlank()) overlays.add(ReelOverlays.title(title, style.look, width, height))
        if (overlays.isNotEmpty()) finish.add(OverlayEffect(overlays))
        finish.add(FinishEffect(style.look, emptyList(), total, endFadeOnly = true))

        val sequences = if (layered) {
            listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(lower), EditedMediaItemSequence(listOf(songItem)))
        } else {
            listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(songItem)))
        }
        val builder = Composition.Builder(sequences).setEffects(Effects(emptyList<AudioProcessor>(), finish))
        if (layered) builder.setVideoCompositorSettings(DissolveSettings(startsMs, fadeMs))
        return builder.build()
    }

    /** The top layer (input 0) fades in over the lower one during each dissolve / zoom-through. */
    private class DissolveSettings(private val startsMs: LongArray, private val fadeMs: LongArray) : VideoCompositorSettings {
        private var firstUs = Long.MIN_VALUE
        override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]
        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
            if (inputId != 0) return OverlaySettings.Builder().build()
            val tMs = (presentationTimeUs - firstUs) / 1000.0
            var alpha = 1.0
            for (k in startsMs.indices) {
                val f = fadeMs[k]
                if (f <= 0) continue
                val local = tMs - startsMs[k]
                if (local >= 0 && local < f) { alpha = ease(local / f); break }
            }
            return OverlaySettings.Builder().setAlphaScale(alpha.toFloat()).build()
        }
    }

    /** A small black picture for the parts of the lower layer that are never seen. */
    private fun blackPicture(context: Context): File {
        val f = File(context.cacheDir, "rc_black.png")
        if (!f.exists()) {
            val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.BLACK)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return f
    }
}
