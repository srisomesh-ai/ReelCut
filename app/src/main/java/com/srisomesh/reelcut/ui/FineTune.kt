package com.srisomesh.reelcut.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.srisomesh.reelcut.ReelViewModel
import com.srisomesh.reelcut.Screen
import com.srisomesh.reelcut.engine.MediaInfo
import com.srisomesh.reelcut.engine.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@UnstableApi
@Composable
fun FineTuneScreen(vm: ReelViewModel, ft: Screen.FineTune) {
    val shot = ft.shots[ft.sel]
    val media = shot.media
    val dur = shot.t1 - shot.t0
    val beats = shot.b1 - shot.b0
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Fine-tune", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "Shot ${ft.sel + 1} of ${ft.shots.size}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ShotPreview(shot, vm.sessionSong, vm.songStart, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)))

        ShotStrip(ft.shots, ft.sel) { vm.selectShot(it) }

        // The scrollable part keeps the buttons reachable on small screens.
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card {
                if (media != null && !media.isImage) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${vm.mediaName(media)} · moment", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("from ${"%.1f".format(shot.src0)}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MomentBar(media, shot.src0, dur) { vm.setMoment(it) }
                    Text("Drag the box to pick which part of the clip plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${vm.mediaName(media)} · photo", style = MaterialTheme.typography.titleSmall)
                    Text("Photos play with a slow zoom. Use Change clip to put a video here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Length", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "$beats ${if (beats == 1) "beat" else "beats"} · ${"%.2f".format(dur)}s",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.changeLength(-1) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("− 1 beat") }
                    OutlinedButton(onClick = { vm.changeLength(1) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ 1 beat") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Change clip", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { vm.removeShot() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove shot", maxLines = 1, softWrap = false)
                }
            }
        }

        Button(
            onClick = { vm.applyFineTune() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Apply changes") }
    }

    if (picking) {
        ClipPicker(vm, current = media, onPick = { vm.setClip(it); picking = false }, onClose = { picking = false })
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// ---------------------------------------------------------- shot strip ---

@Composable
private fun ShotStrip(shots: List<Shot>, sel: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(sel) { state.animateScrollToItem((sel - 2).coerceAtLeast(0)) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().height(66.dp)) {
        itemsIndexed(shots) { i, sh ->
            val selected = i == sel
            Box(
                Modifier.padding(2.dp).width(40.dp).height(62.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222222))
                    .then(if (selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(i) },
            ) {
                Thumb(sh.media, sh.src0 + (sh.t1 - sh.t0) / 2, Modifier.fillMaxSize())
                Text(
                    "%.1f".format(sh.t0), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)),
                )
            }
        }
    }
}

// ---------------------------------------------------------- moment bar ---

/** The whole clip as a bar; the box is the part this shot plays. Drag it (or tap) to move. */
@Composable
private fun MomentBar(media: MediaInfo, src0: Double, shotDur: Double, onChange: (Double) -> Unit) {
    val clipDur = media.dur.coerceAtLeast(0.1)
    val maxStart = (clipDur - shotDur - 0.05).coerceAtLeast(0.0)
    val boxFrac = (shotDur / clipDur).coerceIn(0.04, 1.0).toFloat()
    var dragStart by remember(media, src0) { mutableFloatStateOf(-1f) }   // -1 = not dragging
    val onChangeNow by rememberUpdatedState(onChange)
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val boxPx = widthPx * boxFrac
        val shown = if (dragStart >= 0f) dragStart.toDouble() else src0
        val leftPx = if (maxStart <= 0.0) 0f else ((shown / maxStart) * (widthPx - boxPx)).toFloat()
        fun startAt(xLeft: Float): Double =
            if (widthPx - boxPx <= 0f) 0.0 else ((xLeft / (widthPx - boxPx)).coerceIn(0f, 1f) * maxStart)

        // a few frames of the clip behind the box
        Row(Modifier.fillMaxSize()) {
            repeat(6) { i -> Thumb(media, clipDur * (i + 0.5) / 6, Modifier.weight(1f).fillMaxHeight()) }
        }
        Box(Modifier.fillMaxSize().background(Color(0x55000000)))
        Box(
            Modifier.fillMaxSize()
                .pointerInput(media, maxStart, widthPx) {
                    detectTapGestures { pos -> onChangeNow(startAt(pos.x - boxPx / 2)) }
                }
                .pointerInput(media, maxStart, widthPx) {
                    var x = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> x = pos.x - boxPx / 2; dragStart = startAt(x).toFloat() },
                        onDragEnd = { val v = dragStart.toDouble(); dragStart = -1f; onChangeNow(v) },
                        onDragCancel = { dragStart = -1f },
                    ) { _, dx -> x += dx; dragStart = startAt(x).toFloat() }
                }
        ) {
            Box(
                Modifier.offset(x = with(density) { leftPx.toDp() }).width(with(density) { boxPx.toDp() }).fillMaxHeight()
                    .border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(6.dp))
                    .background(Color(0x22FFFFFF))
            )
        }
    }
}

// ---------------------------------------------------------- clip picker ---

@Composable
private fun ClipPicker(vm: ReelViewModel, current: MediaInfo?, onPick: (MediaInfo) -> Unit, onClose: () -> Unit) {
    val all = vm.pickedMedia
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Change clip") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                all.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            Column(Modifier.weight(1f).clickable { onPick(m) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(9f / 14f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222))
                                        .then(if (m === current) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)) else Modifier)
                                ) {
                                    Thumb(m, if (m.isImage) 0.0 else m.dur / 2, Modifier.fillMaxSize())
                                }
                                Text(vm.mediaName(m), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------- preview ---

/** Plays just this shot (the clip part, or the photo) with its piece of the song, on a loop. */
@UnstableApi
@Composable
private fun ShotPreview(shot: Shot, song: Uri?, songStart: Double, modifier: Modifier) {
    val ctx = LocalContext.current
    val media = shot.media
    val durMs = ((shot.t1 - shot.t0) * 1000).roundToLong()
    val key = listOf(media?.uri, shot.src0, shot.t0, shot.t1)

    val audio = remember(key) {
        song?.let { s ->
            val startMs = ((songStart + shot.t0) * 1000).roundToLong()
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(clipped(s, startMs, startMs + durMs))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare(); playWhenReady = true
            }
        }
    }
    val video = remember(key) {
        if (media == null || media.isImage) null else ExoPlayer.Builder(ctx).build().apply {
            val startMs = (shot.src0 * 1000).roundToLong()
            setMediaItem(clipped(media.uri, startMs, startMs + durMs))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(key) { onDispose { audio?.release(); video?.release() } }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (video != null) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = video },
                modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f),
            )
        } else {
            Thumb(media, 0.0, Modifier.fillMaxHeight().aspectRatio(9f / 16f), large = true)
        }
    }
}

private fun clipped(uri: Uri, startMs: Long, endMs: Long) = MediaItem.Builder()
    .setUri(uri)
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
    .build()

// -------------------------------------------------------------- thumbs ---

@Composable
private fun Thumb(media: MediaInfo?, t: Double, modifier: Modifier, large: Boolean = false) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(Thumbs.cached(media, t, large), media, t, large) {
        if (value == null && media != null) value = withContext(Dispatchers.IO) { Thumbs.load(ctx, media, t, large) }
    }
    Box(modifier.background(Color(0xFF222222))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Small frames of the picked clips, cached by clip + time. */
private object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(m: MediaInfo, t: Double, large: Boolean) =
        "${m.uri}|${if (m.isImage) 0 else (t * 4).roundToInt()}|$large"

    fun cached(m: MediaInfo?, t: Double, large: Boolean): ImageBitmap? = m?.let { cache.get(key(it, t, large)) }

    fun load(ctx: Context, m: MediaInfo, t: Double, large: Boolean): ImageBitmap? {
        val k = key(m, t, large)
        cache.get(k)?.let { return it }
        val size = if (large) 720 else 160
        val bmp: Bitmap? = try {
            if (m.isImage) decodeImage(ctx, m.uri, size) else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, m.uri)
                    r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                } finally { r.release() }
            }
        } catch (_: Exception) { null }
        return bmp?.asImageBitmap()?.also { cache.put(k, it) }
    }

    private fun decodeImage(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
