package com.srisomesh.reelcut.engine

import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlin.math.roundToLong

/** One timeline project as a Media3 composition: the same thing is previewed (CompositionPlayer) and exported. */
@UnstableApi
object TimelineComposer {

    fun build(p: Project, width: Int, height: Int): Composition {
        val items = p.clips.map { c ->
            val effects = Effects(
                emptyList<AudioProcessor>(),
                listOf<Effect>(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)),
            )
            val uri = Uri.parse(c.uri)
            if (c.isImage) {
                val mi = MediaItem.Builder().setUri(uri).setImageDurationMs((c.dur * 1000).roundToLong()).build()
                EditedMediaItem.Builder(mi).setFrameRate(30).setEffects(effects).build()
            } else {
                val mi = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((c.inS * 1000).roundToLong())
                            .setEndPositionMs((c.outS * 1000).roundToLong())
                            .build()
                    )
                    .build()
                // the preview player needs the whole source length to place the clipped part
                EditedMediaItem.Builder(mi).setDurationUs((c.srcDur * 1_000_000).roundToLong()).setEffects(effects).build()
            }
        }
        // photos (and silent videos) get silence so the clips' own sound stays in step
        return Composition.Builder(listOf(EditedMediaItemSequence(items)))
            .experimentalSetForceAudioTrack(true)
            .build()
    }
}
