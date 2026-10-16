package app.megaglass.android.feature.viewer

import app.megaglass.android.core.util.catching

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.media.NodeImage
import app.megaglass.android.core.util.FileType
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.nav.AudioPlayer
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AudioPlayerScreen(route: AudioPlayer) {
    val context = LocalContext.current
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Saveable: the controller connects again each time the screen comes back (another tab, a
    // viewer on top). Only the first start picks the track; later the playback stays as it is.
    var started by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = catching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    val folderLink = route.source is MediaSource.FolderLink
    val requestedId = route.localPath?.let { "f:" + File(it).absolutePath } ?: "h:${route.handle}"
    val serverError = stringResource(R.string.viewer_stream_error)
    val appName = stringResource(R.string.app_name)
    val emptyText = stringResource(R.string.viewer_not_found)

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        if (started) return@LaunchedEffect
        val existing = (0 until c.mediaItemCount).firstOrNull { PlaybackQueue[c.getMediaItemAt(it).mediaId]?.key == requestedId }
        if (existing != null) {
            // The queue is already playing (the screen opened again): only move to the item.
            if (c.currentMediaItemIndex != existing) c.seekTo(existing, 0)
            c.play()
            started = true
            return@LaunchedEffect
        }
        val source = if (route.localPath != null && route.source !is MediaSource.Local) MediaSource.Local(route.localPath) else route.source
        val (list, index) = catching { loadEntries(source, route.handle, setOf(FileType.AUDIO)) }.getOrDefault(emptyList<MediaEntry>() to 0)
        if (list.isEmpty()) {
            error = emptyText
            return@LaunchedEffect
        }
        val api = if (folderLink) graph.mega.folderApi else graph.mega.api
        // With the app lock on, the notification and the lock screen show no names or covers.
        val hidden = graph.lock.isEnabled
        val items = withContext(Dispatchers.IO) {
            if (list.any { !it.isLocal } && !StreamServer.holdForPlayback(api)) return@withContext null
            list.zip(PlaybackQueue.add(list)).mapIndexed { i, (e, id) ->
                // Artwork: the cached thumbnail; the start item loads its thumbnail now.
                val art = if (e.hasThumbnail && !hidden) {
                    val image = NodeImage(e.handle, folderLink = e.folderLink)
                    val file = graph.images.file(image).takeIf { it.length() > 0 }
                        ?: if (i == index) catching { graph.images.load(image) }.getOrNull() else null
                    file?.let(Uri::fromFile)
                } else null
                val metadata = if (hidden) hiddenMetadata(appName) else MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setDisplayTitle(e.title)
                    .setArtworkUri(art)
                    .setIsPlayable(true)
                    .build()
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(e.file?.let(Uri::fromFile) ?: PlaybackQueue.uri(id))
                    .setMediaMetadata(metadata)
                    .build()
            }
        }
        if (items == null) {
            error = serverError
            return@LaunchedEffect
        }
        c.setMediaItems(items, index.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0)
        c.prepare()
        c.play()
        started = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.viewer_now_playing)) },
                navigationIcon = { BackButton { nav.back() } },
            )
        },
    ) { padding ->
        val c = controller
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> EmptyState(R.drawable.ic_audio_file, error!!)
                c == null -> LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
                else -> AudioContent(c)
            }
        }
    }
}

@Composable
private fun AudioContent(player: Player) {
    val ui = remember(player) { PlayerUi(player) }
    DisposableEffect(player) {
        player.addListener(ui.listener)
        ui.update()
        onDispose { player.removeListener(ui.listener) }
    }
    LaunchedEffect(ui) {
        while (true) {
            ui.update()
            delay(500)
        }
    }
    val version = ui.queueVersion
    val current = remember(version, ui.currentIndex) { player.currentMediaItem }
    val queue = remember(version, ui.itemCount) { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "player") {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Artwork(current, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
                Spacer(Modifier.height(24.dp))
                Text(
                    current?.title().orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.itemCount > 1) {
                    Text(
                        stringResource(R.string.viewer_position, ui.currentIndex + 1, ui.itemCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                SeekBar(ui = ui, onSeek = { player.seekTo(it) }, dark = false)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconToggleButton(checked = ui.shuffle, onCheckedChange = { player.shuffleModeEnabled = it }) {
                        Ic(R.drawable.ic_shuffle, stringResource(R.string.viewer_shuffle),
                            tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { player.seekToPrevious() }) {
                        Ic(R.drawable.ic_skip_previous_fill, stringResource(R.string.viewer_previous), Modifier.size(32.dp))
                    }
                    FilledIconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(88.dp),
                        shape = if (ui.isPlaying) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape(),
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Ic(
                            if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_fill,
                            stringResource(if (ui.isPlaying) R.string.viewer_pause else R.string.viewer_play),
                            Modifier.size(44.dp),
                        )
                    }
                    IconButton(onClick = { player.seekToNext() }) {
                        Ic(R.drawable.ic_skip_next_fill, stringResource(R.string.viewer_next), Modifier.size(32.dp))
                    }
                    IconButton(onClick = {
                        player.repeatMode = when (ui.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }) {
                        Ic(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                            stringResource(R.string.viewer_repeat),
                            tint = if (ui.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (queue.size > 1) {
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_queue_music, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "  " + stringResource(R.string.viewer_queue),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (queue.size > 1) {
            items(queue.withIndex().toList(), key = { it.value.mediaId }) { (i, item) ->
                val playing = i == ui.currentIndex
                ListItem(
                    selected = playing,
                    onClick = {
                        player.seekTo(i, 0)
                        player.play()
                    },
                    leadingContent = {
                        Ic(
                            if (playing) R.drawable.ic_music_note_fill else R.drawable.ic_music_note,
                            null,
                            tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.widthIn(max = 600.dp).padding(horizontal = 8.dp),
                ) {
                    Text(item.title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val MediaEntry.title get() = name.substringBeforeLast('.')

/** The name in the app, also when the session shows a neutral title (app lock). */
private fun MediaItem.title(): String = PlaybackQueue[mediaId]?.title ?: mediaMetadata.title?.toString().orEmpty()

/**
 * A neutral title for the system controls. Empty values also replace the tags of the file
 * (artist, album, embedded cover), which the player adds to the metadata.
 */
private fun hiddenMetadata(title: String) = MediaMetadata.Builder()
    .setTitle(title)
    .setDisplayTitle(title)
    .setArtist("")
    .setAlbumTitle("")
    .setAlbumArtist("")
    .setSubtitle("")
    .setDescription("")
    .setArtworkData(ByteArray(0), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    .setIsPlayable(true)
    .build()

@Composable
private fun Artwork(item: MediaItem?, modifier: Modifier) {
    val context = LocalContext.current
    val image = PlaybackQueue[item?.mediaId]?.takeIf { !it.isLocal && it.hasThumbnail }?.let { NodeImage(it.handle, folderLink = it.folderLink) }
    val shape = MaterialTheme.shapes.extraLarge
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Ic(R.drawable.ic_music_note_fill, null, Modifier.size(96.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    Box(modifier.clip(shape)) {
        if (image != null) {
            SubcomposeAsyncImage(
                model = remember(image) { ImageRequest.Builder(context).data(image).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { fallback() },
                error = { fallback() },
            )
        } else {
            fallback()
        }
    }
}
