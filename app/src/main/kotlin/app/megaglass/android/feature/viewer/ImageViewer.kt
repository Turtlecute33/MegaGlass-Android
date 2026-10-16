package app.megaglass.android.feature.viewer

import app.megaglass.android.core.util.catching

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.media.NodeImage
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.transfers.AlreadyPreparingException
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.util.FileType
import app.megaglass.android.feature.drive.NodeActionsHost
import app.megaglass.android.feature.drive.NodeContext
import app.megaglass.android.feature.drive.rememberNodeActions
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.nav.ImageViewer
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.nav.VideoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaTransfer

/** Originals up to this size download when the user zooms in. Larger files keep the preview. */
private const val MAX_ORIGINAL = 40L * 1024 * 1024

/** Hides the system bars while [hidden]; light icons on the dark viewer. Restores both on exit. */
@Composable
fun ImmersiveSystemBars(hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val controller = remember(activity) { WindowCompat.getInsetsController(activity.window, view) }
    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    DisposableEffect(hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose {}
    }
}

/** Downloads a folder-link node with the account's transfer engine (it shows in Transfers). */
suspend fun downloadFolderLinkNode(handle: Long) = withContext(Dispatchers.IO) {
    val graph = MegaGlassApp.graph
    val node = graph.mega.folderApi.getNodeByHandle(handle) ?: throw MegaException(MegaError.API_ENOENT, "Item not found")
    val authorized = graph.mega.folderApi.authorizeNode(node) ?: node
    val dir = graph.files.downloadDir(graph.settings.current)
    graph.mega.api.startDownload(
        authorized, dir.absolutePath + java.io.File.separator, null, Purpose.DOWNLOAD, false, null,
        MegaTransfer.COLLISION_CHECK_FINGERPRINT, MegaTransfer.COLLISION_RESOLUTION_NEW_WITH_N,
    )
    graph.transfers.onActive?.invoke()
}

@Composable
fun ImageViewerScreen(route: ImageViewer) {
    val nav = LocalNavigator.current
    var entries by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var start by remember { mutableIntStateOf(0) }
    LaunchedEffect(route) {
        val (list, index) = catching { loadEntries(route.source, route.handle, setOf(FileType.IMAGE, FileType.VIDEO)) }
            .getOrDefault(emptyList<MediaEntry>() to 0)
        entries = list
        start = index
    }
    var chrome by rememberSaveable { mutableStateOf(true) }
    ImmersiveSystemBars(hidden = !chrome)

    // The viewer is always dark, whatever the app theme.
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val list = entries
            when {
                list == null -> LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
                list.isEmpty() -> EmptyState(R.drawable.ic_image, stringResource(R.string.viewer_not_found))
                else -> Pager(list, start, chrome, onToggleChrome = { chrome = !chrome }, onBack = { nav.back() })
            }
            if (list.isNullOrEmpty()) {
                Box(Modifier.statusBarsPadding().padding(4.dp)) { IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), { nav.back() }) }
            }
        }
    }
}

@Composable
private fun Pager(list: List<MediaEntry>, start: Int, chrome: Boolean, onToggleChrome: () -> Unit, onBack: () -> Unit) {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val pager = rememberPagerState(initialPage = start) { list.size }
    val actions = rememberNodeActions(NodeContext.CLOUD)
    val current = list.getOrNull(pager.currentPage)

    HorizontalPager(
        state = pager,
        key = { list[it].key },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val entry = list[page]
        if (entry.isVideo) {
            VideoPage(entry, onTap = onToggleChrome) {
                if (entry.file != null) nav.navigate(VideoPlayer(-1, localPath = entry.file.absolutePath))
                else nav.navigate(VideoPlayer(entry.handle, folderLink = entry.folderLink))
            }
        } else {
            ImagePage(entry, onTap = onToggleChrome)
        }
    }

    AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    if (list.size > 1) {
                        Text(
                            stringResource(R.string.viewer_position, pager.currentPage + 1, list.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
                val entry = current
                when {
                    entry == null -> Unit
                    entry.file != null -> {
                        IconAction(R.drawable.ic_share, stringResource(R.string.action_send), { graph.opener.send(listOf(entry.file)) })
                        IconAction(R.drawable.ic_open_in_new, stringResource(R.string.action_open_with), { graph.opener.view(entry.file) })
                    }
                    entry.folderLink -> {
                        IconAction(R.drawable.ic_download, stringResource(R.string.action_download), {
                            storage.ensure {
                                scope.launch {
                                    catching { downloadFolderLinkNode(entry.handle) }
                                        .onSuccess { graph.messenger.show(graph.context.resources.getQuantityString(R.plurals.download_started, 1, 1)) }
                                        .onFailure { graph.messenger.show(it.message ?: graph.context.getString(R.string.error_title)) }
                                }
                            }
                        })
                    }
                    entry.node != null -> {
                        IconAction(R.drawable.ic_info, stringResource(R.string.action_info), { nav.navigate(NodeInfo(entry.handle)) })
                        IconAction(R.drawable.ic_download, stringResource(R.string.action_download), { actions.download(listOf(entry.node)) })
                        IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { actions.openMenu(entry.node) })
                    }
                }
            }
        }
    }
    NodeActionsHost(actions)
}

@Composable
private fun ImagePage(entry: MediaEntry, onTap: () -> Unit) {
    val context = LocalContext.current
    val zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
    var original by remember(entry.key) { mutableStateOf(entry.file) }
    var loading by remember(entry.key) { mutableStateOf(false) }
    var failed by remember(entry.key) { mutableStateOf(false) }
    // The check reads the disk: not on the main thread while the user swipes.
    LaunchedEffect(entry.key) {
        if (original == null) withContext(Dispatchers.IO) { PreviewCache.cached(entry) }?.let { original = it }
    }
    val zoomed = (zoom.zoomableState.zoomFraction ?: 0f) > 0.01f
    // The original downloads when the preview is not enough: the user zooms in, or there is no preview.
    val wantOriginal = original == null && !failed && entry.size in 1..MAX_ORIGINAL && (zoomed || !entry.hasPreview)
    // Once started, the download continues when the user zooms out. A restart could collide with
    // the cancelled download, which the SDK releases only later.
    var fetch by remember(entry.key) { mutableStateOf(false) }
    LaunchedEffect(wantOriginal) { if (wantOriginal) fetch = true }
    LaunchedEffect(fetch) {
        if (!fetch) return@LaunchedEffect
        loading = true
        try {
            var result = catching { PreviewCache.fetch(entry.handle, entry.name, entry.size, entry.folderLink) }
            // The download of an earlier visit to this page was cancelled, but has not ended yet.
            var tries = 0
            while (result.exceptionOrNull() is AlreadyPreparingException && tries++ < 20) {
                delay(500)
                result = catching { PreviewCache.fetch(entry.handle, entry.name, entry.size, entry.folderLink) }
            }
            result.onSuccess { original = it }.onFailure { failed = true }
        } finally {
            loading = false
        }
    }
    val previewKey = "node:${entry.handle}:true:${entry.folderLink}"
    val thumbKey = "node:${entry.handle}:false:${entry.folderLink}"
    val model = remember(entry.key, original) {
        val file = original
        when {
            file != null -> ImageRequest.Builder(context).data(file)
                .placeholderMemoryCacheKey(if (entry.hasPreview) previewKey else if (entry.hasThumbnail) thumbKey else null)
                .build()
            entry.hasPreview -> ImageRequest.Builder(context).data(NodeImage(entry.handle, preview = true, folderLink = entry.folderLink))
                .placeholderMemoryCacheKey(if (entry.hasThumbnail) thumbKey else null)
                .build()
            entry.hasThumbnail -> ImageRequest.Builder(context).data(NodeImage(entry.handle, folderLink = entry.folderLink)).build()
            else -> null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (model != null) {
            ZoomableAsyncImage(
                model = model,
                contentDescription = entry.name,
                state = zoom,
                modifier = Modifier.fillMaxSize(),
                onClick = { onTap() },
            )
        } else if (!loading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FileTypeIcon(entry.type, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.viewer_no_preview), color = Color.White.copy(alpha = 0.8f))
            }
        }
        if (loading) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(if (model == null) Alignment.Center else Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) {
                LoadingIndicator(Modifier.size(40.dp), color = Color.White)
            }
        }
    }
}

@Composable
private fun VideoPage(entry: MediaEntry, onTap: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        val data: Any? = when {
            entry.file != null -> null
            entry.hasPreview -> NodeImage(entry.handle, preview = true, folderLink = entry.folderLink)
            entry.hasThumbnail -> NodeImage(entry.handle, folderLink = entry.folderLink)
            else -> null
        }
        if (data != null) {
            AsyncImage(
                model = remember(entry.key) { ImageRequest.Builder(context).data(data).build() },
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FileTypeIcon(FileType.VIDEO, size = 96.dp)
        }
        FilledTonalIconButton(
            onClick = onPlay,
            modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White),
        ) {
            Ic(R.drawable.ic_play_arrow_fill, stringResource(R.string.viewer_play), Modifier.size(44.dp))
        }
    }
}
