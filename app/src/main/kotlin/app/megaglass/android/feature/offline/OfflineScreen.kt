package app.megaglass.android.feature.offline

import app.megaglass.android.core.util.catching

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.storage.OfflineEntry
import app.megaglass.android.core.storage.availableName
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.nav.OfflineFolder
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import java.io.File

/** Files kept on this device. [path] is a folder inside an offline folder, or null for the top level. */
@Composable
fun OfflineScreen(path: String?) {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val handles by graph.offline.handles.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<OfflineEntry>?>(null) }
    var total by remember { mutableStateOf(0L) }
    var reload by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<OfflineEntry?>(null) }
    val folder = path?.let(::File)

    LaunchedEffect(handles, reload) {
        entries = graph.offline.list(folder)
        if (folder == null) total = withContext(Dispatchers.IO) { graph.offline.sizeOnDisk() }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(folder?.name ?: stringResource(R.string.offline_title)) },
                subtitle = if (folder == null && total > 0) { { Text(stringResource(R.string.offline_size, Format.size(total))) } } else null,
                navigationIcon = { BackButton { nav.back() } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val list = entries
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(
                    R.drawable.ic_offline_pin,
                    stringResource(R.string.offline_empty),
                    message = if (folder == null) stringResource(R.string.offline_empty_hint) else null,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(list, key = { it.file.absolutePath }) { entry ->
                        OfflineRow(
                            entry = entry,
                            onClick = {
                                if (!entry.verified) scope.launch {
                                    catching { graph.offline.makeAvailable(listOf(entry.handle)) }
                                        .onFailure { graph.messenger.show(it.message ?: graph.context.getString(R.string.error_title)) }
                                } else if (entry.isFolder) nav.navigate(OfflineFolder(entry.file.absolutePath)) else nav.openLocal(entry.file)
                            },
                            onOpenWith = { graph.opener.view(entry.file) },
                            onSend = { graph.opener.send(listOf(entry.file)) },
                            onSave = {
                                storage.ensure {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            catching {
                                                val dir = graph.files.downloadDir(graph.settings.current)
                                                entry.file.copyRecursively(File(dir, availableName(entry.name, entry.isFolder) { File(dir, it).exists() }))
                                            }.getOrDefault(false)
                                        }
                                        graph.messenger.show(graph.context.getString(if (ok) R.string.saved_to_downloads else R.string.error_title))
                                    }
                                }
                            },
                            onInfo = if (entry.handle != MegaApiJava.INVALID_HANDLE) { { nav.navigate(NodeInfo(entry.handle)) } } else null,
                            onRemove = if (entry.handle != MegaApiJava.INVALID_HANDLE) { { removing = entry } } else null,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    removing?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.offline_remove_title),
            text = stringResource(R.string.offline_remove_text, entry.name),
            confirmLabel = stringResource(R.string.remove),
            onConfirm = { scope.launch { graph.offline.remove(listOf(entry.handle)); reload++ } },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun OfflineRow(
    entry: OfflineEntry,
    onClick: () -> Unit,
    onOpenWith: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onInfo: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        onClick = onClick,
        modifier = modifier,
        leadingContent = {
            if (entry.verified && entry.type == FileType.IMAGE) {
                AsyncImage(
                    model = entry.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(androidx.compose.material3.MaterialTheme.shapes.medium),
                )
            } else {
                FileTypeIcon(entry.type, size = 44.dp, override = if (entry.isFolder) R.drawable.ic_folder_fill else null)
            }
        },
        supportingContent = { Text(if (entry.verified) Format.size(entry.size) + " · " + Format.date(entry.modified / 1000)
            else stringResource(R.string.offline_unverified), maxLines = 1) },
        trailingContent = {
            Box {
                IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (entry.verified && !entry.isFolder) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_open_with)) }, leadingIcon = { Ic(R.drawable.ic_open_in_new) },
                            onClick = { menu = false; onOpenWith() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_send)) }, leadingIcon = { Ic(R.drawable.ic_share) },
                            onClick = { menu = false; onSend() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_to_downloads)) }, leadingIcon = { Ic(R.drawable.ic_download) },
                        onClick = { menu = false; onSave() })
                    if (onInfo != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.show_in_cloud)) }, leadingIcon = { Ic(R.drawable.ic_cloud) },
                            onClick = { menu = false; onInfo() })
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.offline_remove)) }, leadingIcon = { Ic(R.drawable.ic_delete) },
                            onClick = { menu = false; onRemove() })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
}

