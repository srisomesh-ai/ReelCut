package com.srisomesh.reelcut.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedTextField
import com.srisomesh.reelcut.EditorViewModel
import com.srisomesh.reelcut.engine.Project
import com.srisomesh.reelcut.engine.ProjectStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.srisomesh.reelcut.BuildConfigInfo
import com.srisomesh.reelcut.Library
import com.srisomesh.reelcut.ReelViewModel
import com.srisomesh.reelcut.Screen
import com.srisomesh.reelcut.engine.Looks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(14.dp)

@UnstableApi
@Composable
fun App(vm: ReelViewModel = viewModel(), evm: EditorViewModel = viewModel(), startProject: String? = null) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    LaunchedEffect(Unit) { evm.messages.collect { snack.showSnackbar(it) } }
    LaunchedEffect(startProject) { startProject?.let { vm.openProject(it) } }
    // leaving the editor saves the project first
    val leave: () -> Unit = { if (screen is Screen.Editor) evm.close { vm.refreshLists() }; vm.back() }
    BackHandler(enabled = screen !is Screen.Home) { leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(vm)
                is Screen.Working -> WorkingScreen(s) { vm.back() }
                is Screen.Result -> ResultScreen(vm, s)
                is Screen.Viewer -> ViewerScreen(vm, s.item)
                is Screen.FineTune -> FineTuneScreen(vm, s)
                is Screen.Editor -> {
                    LaunchedEffect(s.id) { evm.open(s.id) }
                    EditorScreen(evm, onBack = leave)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Home ---

@UnstableApi
@Composable
private fun HomeScreen(vm: ReelViewModel) {
    val len by vm.reelLen.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf<Project?>(null) }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.newProject(uris)
    }
    // projects and exported reels together, newest first
    val entries: List<Any> = (projects.map { it to it.updated } + recent.map { it to it.file.lastModified() })
        .sortedByDescending { it.second }.map { it.first }

    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.start(uri) else vm.say("Pick a song to continue")
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        when {
            uris.size >= 2 -> { vm.setMedia(uris); songPicker.launch(arrayOf("audio/*")) }
            uris.isNotEmpty() -> vm.say("Pick at least 2 clips or photos")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ReelCut", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Auto reel card
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                Text("Auto reel", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Pick clips and a song. The beat decides the edit.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30).forEach { s ->
                    FilterChip(
                        selected = len == s,
                        onClick = { vm.reelLen.value = s },
                        label = { Text("${s}s") },
                        shape = RoundedCornerShape(50),
                        colors = chipColors(),
                    )
                }
            }
            Button(
                onClick = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Pick clips and song") }
        }

        // New project card (timeline editor = phase 2)
        Row(
            Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { projectPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ViewAgenda, null)
            Column {
                Text("New project", style = MaterialTheme.typography.titleMedium)
                Text("Edit by hand on a timeline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Recent projects", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isEmpty()) {
            Text(
                "Your projects and exported reels show here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { e ->
                        when (e) {
                            is Project -> ProjectTile(e, Modifier.weight(1f), onClick = { vm.openProject(e.id) }, onLongClick = { projectMenu = e })
                            is Library.Item -> RecentTile(e, Modifier.weight(1f)) { vm.open(e) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    projectMenu?.let { pr ->
        var name by remember(pr.id) { mutableStateOf(pr.name) }
        var confirmDelete by remember(pr.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { projectMenu = null },
            title = { Text(if (confirmDelete) "Delete this project?" else "Project") },
            text = {
                if (confirmDelete) Text("${pr.name} will be removed from ReelCut. Your clips and exported videos stay in the gallery.")
                else OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                if (confirmDelete) TextButton(onClick = { vm.deleteProject(pr); projectMenu = null }) { Text("Delete") }
                else TextButton(onClick = { vm.renameProject(pr, name); projectMenu = null }) { Text("Save name") }
            },
            dismissButton = {
                if (confirmDelete) TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                else TextButton(onClick = { confirmDelete = true }) { Text("Delete project") }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
            title = { Text("ReelCut") },
            text = { Text("Version ${BuildConfigInfo.versionName(LocalContext.current)}\nInternal app. Reels are made on this phone and saved to Movies/ReelCut.") },
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(p: Project, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, p.id, p.updated) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(ProjectStore.cover(ctx, p.id).path)?.asImageBitmap() }
    }
    Box(
        modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26215C))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Icon(
            Icons.Filled.Edit, "Project", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000)).padding(3.dp),
        )
        Text(
            p.name, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(6.dp),
        )
    }
}

@Composable
private fun RecentTile(item: Library.Item, modifier: Modifier, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, item.thumb) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.thumb.path)?.asImageBitmap() }
    }
    Box(modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF111111)).clickable(onClick = onClick)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            item.label, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(6.dp),
        )
    }
}

// ------------------------------------------------------------- Working ---

@Composable
private fun WorkingScreen(s: Screen.Working, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(s.stage, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(8.dp))
        Text("${(s.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("Keep the app open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

// -------------------------------------------------------------- Result ---

@UnstableApi
@Composable
private fun ResultScreen(vm: ReelViewModel, s: Screen.Result) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("${s.lookName} · ${s.bpm} BPM", style = MaterialTheme.typography.titleMedium)
        }
        VideoPlayer(s.exported ?: s.file, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Looks.all.forEach { look ->
                FilterChip(
                    selected = s.lookKey == look.key,
                    onClick = { vm.pickLook(look.key) },
                    label = { Text(look.name) },
                    shape = RoundedCornerShape(50),
                    colors = chipColors(),
                    enabled = !s.exporting,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.anotherEdit() }, modifier = Modifier.weight(1f), enabled = !s.exporting, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Another edit")
            }
            OutlinedButton(onClick = { vm.openFineTune() }, modifier = Modifier.weight(1f), enabled = !s.exporting, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fine-tune")
            }
        }
        when {
            s.exporting -> Column(Modifier.fillMaxWidth().height(50.dp), verticalArrangement = Arrangement.Center) {
                Text("Exporting 1080p… ${(s.exportProgress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { s.exportProgress }, modifier = Modifier.fillMaxWidth())
            }
            s.exported != null -> Button(onClick = { share(ctx, s.exported) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
            }
            else -> Button(onClick = { vm.export() }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Export 1080p")
            }
        }
    }
}

// -------------------------------------------------------------- Viewer ---

@UnstableApi
@Composable
private fun ViewerScreen(vm: ReelViewModel, item: Library.Item) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(item.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.delete(item) }) { Icon(Icons.Filled.Delete, "Delete from app") }
        }
        VideoPlayer(item.file, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)))
        Button(onClick = { share(ctx, item.file) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
        }
    }
}

// ------------------------------------------------------------- helpers ---

@UnstableApi
@Composable
private fun VideoPlayer(file: File, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        modifier = modifier.background(Color.Black),
    )
}

internal fun share(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share reel"))
}
