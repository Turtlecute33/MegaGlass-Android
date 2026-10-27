package app.megaglass.android.feature.viewer

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Player state for the UI, refreshed from player events and a short position timer. */
@Stable
class PlayerUi(val player: Player) {
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var duration by mutableLongStateOf(0)
    var position by mutableLongStateOf(0)
    var buffered by mutableLongStateOf(0)
    var speed by mutableFloatStateOf(1f)
    var error by mutableStateOf<String?>(null)
    var currentIndex by mutableIntStateOf(0)
    var itemCount by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    /** Changes when the queue or the current item changes. */
    var queueVersion by mutableIntStateOf(0)

    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                queueVersion++
            }
            update()
        }
        override fun onPlayerError(error: PlaybackException) {
            this@PlayerUi.error = error.message ?: error.errorCodeName
        }
    }

    fun update() {
        isPlaying = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        ended = player.playbackState == Player.STATE_ENDED
        duration = player.duration.coerceAtLeast(0)
        position = player.currentPosition.coerceAtLeast(0)
        buffered = player.bufferedPosition.coerceAtLeast(0)
        speed = player.playbackParameters.speed
        currentIndex = player.currentMediaItemIndex
        itemCount = player.mediaItemCount
        shuffle = player.shuffleModeEnabled
        repeatMode = player.repeatMode
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(route: VideoPlayer) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val graph = MegaGlassApp.graph
    val api = if (route.folderLink) graph.mega.folderApi else graph.mega.api
    var uri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val notFound = stringResource(R.string.viewer_not_found)
    val serverError = stringResource(R.string.viewer_stream_error)

    // Local file, or a link to the SDK streaming server (held while this screen is open).
    DisposableEffect(route, attempt) {
        val hold = ServerHold(api)
        val job = graph.scope.launchIo {
            val path = route.localPath
            if (path != null) {
                title = File(path).name
                uri = Uri.fromFile(File(path))
                return@launchIo
            }
            val node = api.getNodeByHandle(route.handle)
            if (node == null) {
                loadError = notFound
                return@launchIo
            }
            title = node.name ?: ""
            val link = if (hold.acquire()) api.httpServerGetLocalLink(node) else null
            if (link == null) loadError = serverError else uri = Uri.parse(link)
        }
        onDispose {
            job.cancel()
            hold.release()
        }
    }

    val player = remember {
        ExoPlayer.Builder(context)
            // Takes audio focus: background audio and other apps pause while the video plays.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 120_000, 1_500, 3_000).build())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }
    val ui = remember(player) { PlayerUi(player) }
    DisposableEffect(player) {
        player.addListener(ui.listener)
        onDispose {
            player.removeListener(ui.listener)
            player.release()
        }
    }
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        ui.error = null
        player.setMediaItem(MediaItem.fromUri(u))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(ui) {
        while (true) {
            ui.update()
            delay(250)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val activity = LocalActivity.current
    var landscape by remember { mutableStateOf(false) }
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    // The system "Time to take action" setting can make the delay longer.
    val a11y = LocalAccessibilityManager.current
    val hideDelay = a11y?.calculateRecommendedTimeoutMillis(3500, containsIcons = true, containsControls = true) ?: 3500
    val toggleLabel = stringResource(R.string.viewer_toggle_controls)
    LaunchedEffect(controls, ui.isPlaying, touched, hideDelay) {
        if (controls && ui.isPlaying) {
            delay(hideDelay)
            controls = false
        }
    }
    ImmersiveSystemBars(hidden = !controls)

    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) player.seekBack() else player.seekForward()
                            touched++
                        },
                    )
                }
                // The tap above has no semantics: TalkBack gets the same toggle as an action.
                .semantics {
                    contentDescription = title
                    onClick(label = toggleLabel) { controls = !controls; true }
                },
        ) {
            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

            val error = loadError ?: ui.error
            if (error != null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Ic(R.drawable.ic_error, null, Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        loadError = null
                        ui.error = null
                        uri = null
                        attempt++
                    }) { Text(stringResource(R.string.retry)) }
                }
            } else if (ui.buffering || uri == null) {
                LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp), color = Color.White)
            }

            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                            .statusBarsPadding().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), { nav.back() })
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        )
                        SpeedMenu(ui.speed) {
                            player.setPlaybackSpeed(it)
                            touched++
                        }
                        IconAction(R.drawable.ic_screen_rotation, stringResource(R.string.viewer_rotate), {
                            landscape = !landscape
                            touched++
                        })
                    }

                    if (error == null) {
                        Row(
                            Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconAction(R.drawable.ic_replay_10, stringResource(R.string.viewer_back_10), {
                                player.seekBack()
                                touched++
                            })
                            FilledIconButton(
                                onClick = {
                                    if (ui.ended) player.seekTo(0)
                                    if (player.isPlaying) player.pause() else player.play()
                                    touched++
                                },
                                modifier = Modifier.size(80.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
                            ) {
                                Ic(
                                    if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_fill,
                                    stringResource(if (ui.isPlaying) R.string.viewer_pause else R.string.viewer_play),
                                    Modifier.size(44.dp),
                                )
                            }
                            IconAction(R.drawable.ic_forward_10, stringResource(R.string.viewer_forward_10), {
                                player.seekForward()
                                touched++
                            })
                        }
                    }

                    SeekBar(
                        ui = ui,
                        onSeek = {
                            player.seekTo(it)
                            touched++
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Seek slider with the buffered range behind it and the times on both sides. */
@Composable
fun SeekBar(ui: PlayerUi, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, dark: Boolean = true) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.duration
    val fraction = if (dragging) dragValue else if (duration > 0) ui.position.toFloat() / duration else 0f
    Column(modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            LinearProgressIndicator(
                progress = { if (duration > 0) ui.buffered.toFloat() / duration else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp),
                color = (if (dark) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.35f),
                trackColor = Color.Transparent,
                drawStopIndicator = {},
            )
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0,
                colors = if (dark) SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ) else SliderDefaults.colors(),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(
                Format.duration(((if (dragging) dragValue * duration else ui.position.toFloat()) / 1000).toLong()),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) Format.duration(duration / 1000) else "", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Ic(R.drawable.ic_speed, null, Modifier.size(18.dp), Color.White)
            Spacer(Modifier.width(4.dp))
            Text(speedLabel(speed), color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(speedLabel(s)) },
                    onClick = {
                        open = false
                        onSpeed(s)
                    },
                    trailingIcon = if (s == speed) { { Ic(R.drawable.ic_check) } } else null,
                )
            }
        }
    }
}

private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

/** One reference on the streaming server; safe when release comes before acquire finishes. */
private class ServerHold(private val api: nz.mega.sdk.MegaApiJava) {
    private var held = false
    private var released = false

    @Synchronized
    fun acquire(): Boolean {
        if (released) return false
        held = StreamServer.acquire(api)
        return held
    }

    @Synchronized
    fun release() {
        released = true
        if (held) StreamServer.release(api)
        held = false
    }
}

/** Launches [block] on the IO dispatcher of [this] scope. */
private fun CoroutineScope.launchIo(block: suspend CoroutineScope.() -> Unit) = launch(Dispatchers.IO, block = block)
