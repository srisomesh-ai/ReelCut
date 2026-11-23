package com.srisomesh.reelcut.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.srisomesh.reelcut.ReelViewModel
import com.srisomesh.reelcut.engine.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** YouTube song search (owner, 23 Nov): type a song name, ▶ to hear it, tap a row to use it. */
@UnstableApi
@Composable
fun YouTubeScreen(vm: ReelViewModel) {
    val st by vm.yt.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var playing by remember { mutableStateOf<String?>(null) }     // url of the song being heard
    var loadingPlay by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) playing = null }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    // the preview stops while a song downloads
    LaunchedEffect(st.downloading) { if (st.downloading != null) { player.stop(); playing = null } }

    fun preview(item: YouTube.Item) {
        if (playing == item.url || loadingPlay == item.url) { player.stop(); playing = null; loadingPlay = null; return }
        player.stop()
        playing = null
        loadingPlay = item.url
        scope.launch {
            try {
                val a = YouTube.audio(item.url)
                if (loadingPlay != item.url) return@launch
                val src = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory().setUserAgent(a.userAgent))
                    .createMediaSource(MediaItem.fromUri(a.url))
                player.setMediaSource(src)
                player.prepare()
                player.play()
                playing = item.url
            } catch (e: Exception) {
                vm.say("This song cannot be played. Try another one.")
            } finally {
                if (loadingPlay == item.url) loadingPlay = null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("YouTube", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = st.query,
            onValueChange = { vm.ytQuery(it) },
            singleLine = true,
            placeholder = { Text("Song name") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.ytSearch() }),
            enabled = st.downloading == null,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            st.searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            st.error != null -> Text(st.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            st.results.isEmpty() -> Text(
                "Type a song name and press search.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(st.results, key = { it.url }) { item ->
                val busy = st.downloading
                val mine = busy == item.url
                Column(Modifier.alpha(if (busy != null && !mine) 0.4f else 1f)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = busy == null) { vm.ytUse(item) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WebThumb(item.thumb, Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(item.channel, duration(item.seconds)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = busy == null) { preview(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loadingPlay == item.url) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(
                                if (playing == item.url) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                if (playing == item.url) "Stop" else "Hear it",
                            )
                        }
                    }
                    if (mine) {
                        Text(
                            "Getting the song… ${(st.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        )
                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
        if (st.results.isNotEmpty()) {
            Text("▶ hear it · tap a row to use it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun duration(s: Long): String = if (s <= 0) "" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

/** A picture from the web (YouTube thumbnails), cached. */
@Composable
private fun WebThumb(url: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(url?.let { WebThumbs.cache.get(it) }, url) {
        if (value == null && url != null) value = withContext(Dispatchers.IO) { WebThumbs.load(url) }
    }
    Box(modifier.background(Color(0xFF222222))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private object WebThumbs {
    val cache = LruCache<String, ImageBitmap>(80)
    fun load(url: String): ImageBitmap? = try {
        YouTube.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            val b = r.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()?.also { cache.put(url, it) }
        }
    } catch (_: Exception) { null }
}
