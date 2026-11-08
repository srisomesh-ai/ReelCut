package com.srisomesh.reelcut.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.srisomesh.reelcut.EditorViewModel
import com.srisomesh.reelcut.engine.Project
import com.srisomesh.reelcut.engine.TClip
import com.srisomesh.reelcut.engine.TimelineEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TRACK_TOP_DP = 22      // ruler height above the clips
private const val TRACK_H_DP = 56

@UnstableApi
@Composable
fun EditorScreen(evm: EditorViewModel, onBack: () -> Unit) {
    val st by evm.state.collectAsState()
    val p = st.project
    val playhead by evm.playhead.collectAsState()
    val playing by evm.playing.collectAsState()
    val player by evm.player.collectAsState()
    val exporting by evm.exporting.collectAsState()
    val exported by evm.exported.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) evm.add(uris)
    }

    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening…") }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // top bar: back, name (tap to rename), export
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { renaming = true },
            )
            Button(onClick = { evm.export() }, shape = RoundedCornerShape(10.dp)) { Text("Export") }
        }

        // preview
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (p.clips.isEmpty() || player == null) {
                Text("Add clips to start", color = Color.White, style = MaterialTheme.typography.bodySmall)
            } else {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f),
                )
            }
        }

        // time, play, undo/redo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${fmt(playhead)} / ${fmt(p.total)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { evm.togglePlay() }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(30.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { evm.undo() }, enabled = st.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { evm.redo() }, enabled = st.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            }
        }

        TimelineView(p, st.selected, playhead, evm, Modifier.fillMaxWidth().height((TRACK_TOP_DP + TRACK_H_DP + 14).dp))
        Text(
            "Pinch to zoom · long-press a clip to move it",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Tool(Icons.Filled.ContentCut, "Split") { evm.split() }
            Tool(Icons.Filled.ContentCopy, "Duplicate") { evm.duplicate() }
            Tool(Icons.Filled.Delete, "Delete") { evm.delete() }
            Tool(Icons.Filled.Add, "Add") {
                addPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Project name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { evm.rename(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    exporting?.let { f ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting 1080p") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Text("${(f * 100).roundToInt()}% · keep the app open", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { evm.cancelExport() }) { Text("Cancel") } },
        )
    }

    exported?.let { file ->
        AlertDialog(
            onDismissRequest = { evm.doneExported() },
            title = { Text("Saved to your gallery") },
            text = { Text("Movies/ReelCut · ${p.name}") },
            confirmButton = { TextButton(onClick = { share(ctx, file); evm.doneExported() }) { Text("Share") } },
            dismissButton = { TextButton(onClick = { evm.doneExported() }) { Text("Done") } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(t: Double): String {
    val s = t.coerceAtLeast(0.0)
    val m = floor(s / 60).toInt()
    return "%02d:%04.1f".format(m, s - m * 60)
}

// --------------------------------------------------------------- timeline ---

private data class TrimDrag(val id: Long, val left: Boolean, val dx: Float)
private data class MoveDrag(val id: Long, val x: Float)

/**
 * The playhead stays in the middle; the clips slide under it.
 * Drag = move the playhead, pinch = zoom, tap = select, long-press + drag = move a clip, white edges = trim.
 */
@Composable
private fun TimelineView(p: Project, selected: Long?, playhead: Double, evm: EditorViewModel, modifier: Modifier) {
    val density = LocalDensity.current
    var pps by remember { mutableFloatStateOf(with(density) { 60.dp.toPx() }) }     // pixels per second
    val minPps = with(density) { 8.dp.toPx() }
    val maxPps = with(density) { 600.dp.toPx() }
    var trim by remember { mutableStateOf<TrimDrag?>(null) }
    var moving by remember { mutableStateOf<MoveDrag?>(null) }
    val cur by rememberUpdatedState(Triple(p, playhead, pps))
    val measurer = rememberTextMeasurer()
    val rulerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier.clipToBounds()) {
        val w = with(density) { maxWidth.toPx() }
        val center = w / 2
        val trackTop = with(density) { TRACK_TOP_DP.dp.toPx() }
        val trackH = with(density) { TRACK_H_DP.dp.toPx() }
        val starts = p.starts()
        fun xOf(t: Double) = center + ((t - playhead) * pps).toFloat()
        fun tAt(x: Float, q: Triple<Project, Double, Float>) = q.second + (x - center) / q.third

        // gestures on the whole strip
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val (pp, _, _) = cur
                        val t = tAt(pos.x, cur)
                        val hit = if (t in 0.0..pp.total) pp.clipAt(t)?.first else null
                        evm.select(hit?.let { pp.clips[it].id })
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) pps = (pps * zoom).coerceIn(minPps, maxPps)
                        if (pan.x != 0f) evm.scrubTo(evm.playhead.value - pan.x / pps)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val (pp, _, _) = cur
                            val t = tAt(pos.x, cur)
                            val hit = if (t in 0.0..pp.total) pp.clipAt(t)?.first else null
                            if (hit != null) { evm.select(pp.clips[hit].id); moving = MoveDrag(pp.clips[hit].id, pos.x) }
                        },
                        onDrag = { change, d -> moving?.let { change.consume(); moving = it.copy(x = it.x + d.x) } },
                        onDragEnd = {
                            moving?.let { m ->
                                val (pp, _, _) = cur
                                evm.move(m.id, TimelineEdits.dropIndex(pp, m.id, tAt(m.x, cur)))
                            }
                            moving = null
                        },
                        onDragCancel = { moving = null },
                    )
                }
        )

        // ruler
        Canvas(Modifier.fillMaxWidth().height(TRACK_TOP_DP.dp)) {
            val minGap = 64.dp.toPx()
            val step = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0).firstOrNull { it * pps >= minGap } ?: 120.0
            val t0 = floor((playhead - center / pps) / step) * step
            val t1 = playhead + center / pps
            var t = t0.coerceAtLeast(0.0)
            while (t <= minOf(t1, p.total + step)) {
                val x = xOf(t)
                drawLine(rulerColor, Offset(x, size.height - 6.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                val label = if (step < 1) "%.1fs".format(t) else "${t.roundToInt()}s"
                drawText(measurer, label, Offset(x + 3.dp.toPx(), 0f), TextStyle(fontSize = 10.sp, color = rulerColor))
                t += step
            }
        }

        // clips
        p.clips.forEachIndexed { i, c ->
            var x0 = xOf(starts[i])
            var durPx = (c.dur * pps).toFloat()
            val tr = trim?.takeIf { it.id == c.id }
            if (tr != null) {
                // live trim: the dragged edge follows the finger within what the clip allows
                val delta = tr.dx / pps.toDouble()
                val q = (if (tr.left) TimelineEdits.trimLeft(p, c.id, delta) else TimelineEdits.trimRight(p, c.id, delta))
                val nd = q?.clips?.get(i)?.dur ?: c.dur
                if (tr.left) x0 += ((c.dur - nd) * pps).toFloat()
                durPx = (nd * pps).toFloat()
            }
            if (x0 + durPx < -w || x0 > 2 * w) return@forEachIndexed
            val isSel = c.id == selected
            val isMoving = moving?.id == c.id
            ClipBlock(
                c, durPx, isSel, accent,
                Modifier.offset(x = with(density) { x0.toDp() }, y = TRACK_TOP_DP.dp).alpha(if (isMoving) 0.35f else 1f),
            )
            if (isSel && moving == null) {
                // white trim handles on both edges
                for (left in listOf(true, false)) {
                    val hx = if (left) x0 - with(density) { 16.dp.toPx() } else x0 + durPx
                    Box(
                        Modifier.offset(x = with(density) { hx.toDp() }, y = TRACK_TOP_DP.dp)
                            .width(16.dp).height(TRACK_H_DP.dp)
                            .pointerInput(c.id, left) {
                                var dx = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dx = 0f; trim = TrimDrag(c.id, left, 0f) },
                                    onDragEnd = {
                                        val d = dx / cur.third.toDouble()
                                        trim = null
                                        if (abs(dx) > 1f) evm.trim(c.id, left, if (left) d else d)
                                    },
                                    onDragCancel = { trim = null },
                                ) { change, amount -> change.consume(); dx += amount; trim = TrimDrag(c.id, left, dx) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.width(10.dp).height(TRACK_H_DP.dp - 12.dp).clip(RoundedCornerShape(4.dp)).background(Color.White)
                                .border(BorderStroke(1.dp, accent), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        // the clip being moved, under the finger
        moving?.let { m ->
            val c = p.clips.firstOrNull { it.id == m.id } ?: return@let
            val durPx = (c.dur * pps).toFloat()
            ClipBlock(
                c, durPx, true, accent,
                Modifier.offset(x = with(density) { (m.x - durPx / 2).toDp() }, y = (TRACK_TOP_DP - 6).dp),
            )
        }

        // playhead
        Box(
            Modifier.offset(x = with(density) { (center - 1.dp.toPx()).toDp() }).width(2.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface)
        )
    }
}

@Composable
private fun ClipBlock(c: TClip, widthPx: Float, selected: Boolean, accent: Color, modifier: Modifier) {
    val density = LocalDensity.current
    val wDp = with(density) { widthPx.coerceAtLeast(2f).toDp() }
    val thumbW = 40.dp
    val n = ceil(wDp / thumbW).toInt().coerceIn(1, 40)
    Box(
        modifier.width(wDp).height(TRACK_H_DP.dp).clip(RoundedCornerShape(6.dp))
            .background(if (c.isImage) Color(0xFF534AB7) else Color(0xFF3C3489))
            .then(if (selected) Modifier.border(BorderStroke(2.dp, Color(0xFF85B7EB)), RoundedCornerShape(6.dp)) else Modifier)
    ) {
        Row(Modifier.fillMaxSize()) {
            repeat(n) { k ->
                val t = if (c.isImage) 0.0 else c.inS + c.dur * (k + 0.5) / n
                FrameThumb(c, t, Modifier.width(thumbW).fillMaxHeight())
            }
        }
        if (wDp > 34.dp) {
            Text(
                "%.1fs".format(c.dur), color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(horizontal = 3.dp),
            )
        }
        if (selected) Box(Modifier.fillMaxSize().border(BorderStroke(2.dp, accent), RoundedCornerShape(6.dp)))
    }
}

// --------------------------------------------------------------- thumbs ---

@Composable
private fun FrameThumb(c: TClip, t: Double, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState(EditorThumbs.cached(c, t), c.uri, t) {
        if (value == null) value = withContext(EditorThumbs.io) { EditorThumbs.load(ctx, c, t) }
    }
    Box(modifier) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

/** Small frames for the timeline, one per clip second, two loads at a time. */
private object EditorThumbs {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val io = Dispatchers.IO.limitedParallelism(2)
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(c: TClip, t: Double) = "${c.uri}|${if (c.isImage) 0 else t.roundToInt()}"

    fun cached(c: TClip, t: Double): ImageBitmap? = cache.get(key(c, t))

    fun load(ctx: Context, c: TClip, t: Double): ImageBitmap? {
        val k = key(c, t)
        cache.get(k)?.let { return it }
        val uri = Uri.parse(c.uri)
        val bmp: Bitmap? = try {
            if (c.isImage) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
                var s = 1
                while (b.outWidth / (s * 2) >= 128 && b.outHeight / (s * 2) >= 128) s *= 2
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.getScaledFrameAtTime((t.roundToInt() * 1_000_000L).coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                } finally { r.release() }
            }
        } catch (_: Exception) { null }
        return bmp?.asImageBitmap()?.also { cache.put(k, it) }
    }
}
