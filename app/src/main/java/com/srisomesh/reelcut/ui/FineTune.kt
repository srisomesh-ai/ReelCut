package com.srisomesh.reelcut.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import com.srisomesh.reelcut.engine.Styles
import androidx.media3.exoplayer.SeekParameters
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    val muted by vm.previewMuted.collectAsState()
    // what the user is dragging right now (null = not dragging)
    var live by remember(ft.sel, shot) { mutableStateOf<LiveEdit?>(null) }

    // length of this shot if it gets [d] beats longer (null = not possible); cuts stay on the beat grid
    val g = ft.grid
    val end = ft.shots.last().b1
    val isLast = ft.sel == ft.shots.lastIndex
    val prevB0 = if (ft.sel > 0) ft.shots[ft.sel - 1].b0 else -1
    val durFor: (Int) -> Double? = { d ->
        when {
            d == 0 -> dur
            d < 1 - beats -> null
            !isLast -> { val nb = shot.b1 + d; if (nb >= end) null else Styles.frameT(g[nb]) - Styles.frameT(g[shot.b0]) }
            ft.shots.size == 1 -> null
            else -> { val nb = shot.b0 - d; if (nb <= prevB0 || nb >= shot.b1) null else Styles.frameT(g[shot.b1]) - Styles.frameT(g[nb]) }
        }
    }
    val shownBeats = live?.beats ?: beats
    val shownDur = live?.dur ?: dur

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Fine-tune", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "Shot ${ft.sel + 1} of ${ft.shots.size}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ShotPreview(
            shot, vm.sessionSong, vm.songStart, muted, live?.scrubAt, { vm.toggleMute() },
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        )

        ShotStrip(ft.shots, ft.sel) { vm.selectShot(it) }

        // The scrollable part keeps the buttons reachable on small screens.
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card {
                if (media != null && !media.isImage) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${vm.mediaName(media)} · moment", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("from ${"%.1f".format(live?.src0 ?: shot.src0)}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MomentBar(
                        media, shot.src0, dur, beats, durFor,
                        onLive = { live = it },
                        onMove = { vm.setMoment(it) },
                        onResize = { d, src0 -> vm.resizeShot(d, src0) },
                    )
                    Text("Drag the box to pick the part. Drag its edges to make the shot longer or shorter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${vm.mediaName(media)} · photo", style = MaterialTheme.typography.titleSmall)
                    Text("Photos play with a slow zoom. Use Change clip to put a video here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Length", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "$shownBeats ${if (shownBeats == 1) "beat" else "beats"} · ${"%.2f".format(shownDur)}s",
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
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(80.dp),
    ) {
        itemsIndexed(shots) { i, sh ->
            val selected = i == sel
            Box(
                Modifier.padding(2.dp)
                    .width(if (selected) 50.dp else 40.dp).height(if (selected) 74.dp else 62.dp)
                    .alpha(if (selected) 1f else 0.45f)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222))
                    .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(i) },
            ) {
                Thumb(sh.media, sh.src0 + (sh.t1 - sh.t0) / 2, Modifier.fillMaxSize())
                if (selected) {
                    Text(
                        "Editing", color = Color.White, fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp),
                    )
                }
                Text(
                    "%.1f".format(sh.t0), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)),
                )
            }
        }
    }
}

// ---------------------------------------------------------- moment bar ---

/** While dragging on the clip bar: what the shot would become, and which clip time the preview shows. */
data class LiveEdit(val src0: Double, val dur: Double, val beats: Int, val scrubAt: Double)

private enum class Grab { MOVE, LEFT, RIGHT }

/**
 * The whole clip as a bar; the box is the part this shot plays.
 * Drag the box (or tap) to move it; drag its left/right handle to make the shot longer or shorter, snapped to beats.
 */
@Composable
private fun MomentBar(
    media: MediaInfo,
    src0: Double,
    shotDur: Double,
    beats: Int,
    durFor: (Int) -> Double?,
    onLive: (LiveEdit?) -> Unit,
    onMove: (Double) -> Unit,
    onResize: (Int, Double?) -> Unit,
) {
    val clipDur = media.dur.coerceAtLeast(0.1)
    val density = LocalDensity.current
    val handleZone = with(density) { 26.dp.toPx() }
    val minBoxPx = with(density) { 14.dp.toPx() }
    var liveSrc0 by remember(media, src0, shotDur) { mutableStateOf<Double?>(null) }
    var liveDur by remember(media, src0, shotDur) { mutableStateOf<Double?>(null) }
    val cb by rememberUpdatedState(Triple(onLive, onMove, onResize))
    val durForNow by rememberUpdatedState(durFor)

    // snap a wanted length to a whole number of beats that fits in the clip
    fun snap(want: Double, maxDur: Double): Pair<Int, Double> {
        var best = 0 to shotDur
        var d = 1 - beats
        while (d <= 64) {
            val v = durForNow(d)
            if (v == null) { if (d > 0) break; d++; continue }
            if (v <= maxDur + 1e-6 && kotlin.math.abs(v - want) < kotlin.math.abs(best.second - want)) best = d to v
            d++
        }
        return best
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val pad = with(density) { 12.dp.toPx() }          // room for the handles at both ends
        val barW = (widthPx - 2 * pad).coerceAtLeast(1f)
        fun xOf(t: Double) = pad + (t / clipDur * barW).toFloat()
        fun tOf(x: Float) = ((x - pad) / barW * clipDur).coerceIn(0.0, clipDur)
        val s0 = liveSrc0 ?: src0
        val d0 = liveDur ?: shotDur
        val left = xOf(s0)
        val right = maxOf(xOf(s0 + d0), left + minBoxPx)

        // the clip's frames, darkened outside the box
        Row(Modifier.padding(horizontal = 12.dp).fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            repeat(6) { i -> Thumb(media, clipDur * (i + 0.5) / 6, Modifier.weight(1f).fillMaxHeight()) }
        }
        Box(Modifier.padding(horizontal = 12.dp).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)))
        // the chosen part: bright box with a grip handle on each edge
        Box(
            Modifier.offset(x = with(density) { left.toDp() }).width(with(density) { (right - left).toDp() }).fillMaxHeight()
                .border(BorderStroke(3.dp, Color(0xFF8AB4F8)), RoundedCornerShape(6.dp)).background(Color(0x40FFFFFF))
        )
        for (edge in listOf(left, right)) {
            Box(
                Modifier.offset(x = with(density) { (edge - 5.dp.toPx()).toDp() }, y = 12.dp)
                    .width(10.dp).height(24.dp).clip(RoundedCornerShape(5.dp)).background(Color.White)
                    .border(BorderStroke(1.dp, Color(0xFF185FA5)), RoundedCornerShape(5.dp))
            )
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(media, src0, shotDur, widthPx) {
                    detectTapGestures { pos ->
                        if (kotlin.math.abs(pos.x - left) > handleZone && kotlin.math.abs(pos.x - right) > handleZone) {
                            val maxStart = (clipDur - shotDur - 0.05).coerceAtLeast(0.0)
                            cb.second((tOf(pos.x) - shotDur / 2).coerceIn(0.0, maxStart))
                        }
                    }
                }
                .pointerInput(media, src0, shotDur, widthPx) {
                    var grab = Grab.MOVE
                    var x = 0f
                    var xStart = 0f
                    var startSrc = src0
                    var resizeD = 0
                    val endT = src0 + shotDur
                    fun report() {
                        val s = liveSrc0 ?: src0; val d = liveDur ?: shotDur
                        val at = when (grab) { Grab.MOVE -> s; Grab.LEFT -> s; Grab.RIGHT -> (s + d - 0.04).coerceAtLeast(s) }
                        cb.first(LiveEdit(s, d, beats + resizeD, at))
                    }
                    fun update() {
                        when (grab) {
                            Grab.MOVE -> {
                                val maxStart = (clipDur - shotDur - 0.05).coerceAtLeast(0.0)
                                liveSrc0 = (startSrc + (x - xStart) / barW * clipDur).coerceIn(0.0, maxStart)
                                liveDur = shotDur
                            }
                            Grab.RIGHT -> {
                                val (d, v) = snap(tOf(x) - src0, clipDur - src0 - 0.05)
                                resizeD = d; liveDur = v; liveSrc0 = src0
                            }
                            Grab.LEFT -> {
                                val (d, v) = snap(endT - tOf(x), endT)
                                resizeD = d; liveDur = v; liveSrc0 = (endT - v).coerceAtLeast(0.0)
                            }
                        }
                        report()
                    }
                    detectDragGestures(
                        onDragStart = { pos ->
                            x = pos.x; xStart = pos.x; resizeD = 0
                            grab = when {
                                pos.x in left..right && right - left > 2 * handleZone -> Grab.MOVE
                                kotlin.math.abs(pos.x - right) <= handleZone && pos.x >= (left + right) / 2 -> Grab.RIGHT
                                kotlin.math.abs(pos.x - left) <= handleZone -> Grab.LEFT
                                kotlin.math.abs(pos.x - right) <= handleZone -> Grab.RIGHT
                                else -> Grab.MOVE
                            }
                            // grabbed outside the box: the box jumps to the finger first
                            startSrc = if (grab == Grab.MOVE && pos.x !in left..right) tOf(pos.x) - shotDur / 2 else src0
                            update()
                        },
                        onDragEnd = {
                            val s = liveSrc0; val g = grab; val d = resizeD
                            liveSrc0 = null; liveDur = null
                            cb.first(null)
                            when (g) {
                                Grab.MOVE -> if (s != null) cb.second(s)
                                Grab.RIGHT -> if (d != 0) cb.third(d, null)
                                Grab.LEFT -> if (d != 0) cb.third(d, s)
                            }
                        },
                        onDragCancel = { liveSrc0 = null; liveDur = null; cb.first(null) },
                    ) { change, drag -> change.consume(); x += drag.x; update() }
                }
        )
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

/**
 * Plays just this shot (the clip part, or the photo) with its piece of the song, on a loop.
 * While the user drags on the clip bar ([scrubAt] set) it shows that exact frame instead.
 */
@UnstableApi
@Composable
private fun ShotPreview(
    shot: Shot,
    song: Uri?,
    songStart: Double,
    muted: Boolean,
    scrubAt: Double?,
    onToggleMute: () -> Unit,
    modifier: Modifier,
) {
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

    // whole clip, paused, for showing the exact frame under the finger
    val scrub = remember(media?.uri) {
        if (media == null || media.isImage) null else ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(media.uri))
            volume = 0f
            setSeekParameters(SeekParameters.EXACT)
            playWhenReady = false
            prepare()
        }
    }
    val seeker = remember(scrub) { Seeker(scrub) }
    DisposableEffect(scrub) { onDispose { scrub?.release() } }

    LaunchedEffect(audio, muted) { audio?.volume = if (muted) 0f else 1f }
    val scrubbing = scrubAt != null && scrub != null
    LaunchedEffect(scrubbing, key) {
        if (scrubbing) { video?.pause(); audio?.pause() }
        else { video?.seekTo(0); audio?.seekTo(0); video?.play(); audio?.play() }
    }
    LaunchedEffect(scrubAt) { scrubAt?.let { seeker.request((it * 1000).roundToLong()) } }

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
                update = { it.player = if (scrubbing) scrub else video },
                modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f),
            )
        } else {
            Thumb(media, 0.0, Modifier.fillMaxHeight().aspectRatio(9f / 16f), large = true)
        }
        if (muted) {
            Text(
                "Sound off", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0x8C000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp).clip(CircleShape).background(Color(0x8C000000)),
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                if (muted) "Turn sound on" else "Turn sound off", tint = Color.White,
            )
        }
    }
}

/** Seeks one at a time: while a seek is running only the newest wanted position is kept. */
private class Seeker(private val p: ExoPlayer?) : Player.Listener {
    private var busySince = 0L
    private var pending = -1L
    init { p?.addListener(this) }

    fun request(ms: Long) {
        val pl = p ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (busySince != 0L && now - busySince < 400) { pending = ms; return }
        busySince = now
        pl.seekTo(ms)
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && busySince != 0L) {
            busySince = 0L
            if (pending >= 0) { val t = pending; pending = -1; request(t) }
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
