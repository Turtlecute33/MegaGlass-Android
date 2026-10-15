package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeThumbnail
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.invalidNameError
import app.megaglass.android.ui.components.subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaShare

/**
 * Full-screen folder chooser (move, copy, upload, import). Shows Cloud drive and the incoming
 * shares the user can write to. [exclude] hides folders that cannot be the target (the items
 * being moved).
 */
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    exclude: Set<Long> = emptySet(),
    start: Long? = null,
) {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    // Folder chain: empty means the root of the current tab.
    var stack by remember { mutableStateOf(listOf<NodeItem>()) }
    var folders by remember { mutableStateOf<List<NodeItem>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (start != null && start != graph.nodes.rootHandle) {
            val chain = graph.nodes.path(start)
            if (chain.isNotEmpty() && chain.first().handle == graph.nodes.rootHandle) {
                stack = chain.drop(1).mapNotNull { graph.nodes.item(it.handle) }
            }
        }
    }

    val current = stack.lastOrNull()
    LaunchedEffect(tab, current?.handle, reload) {
        folders = null
        folders = withContext(Dispatchers.IO) {
            if (tab == 1 && current == null) {
                api.getInShares(SortOrder.NAME_ASC.sdkOrder)
                    .filter { api.getAccess(it) >= MegaShare.ACCESS_READWRITE }
                    .map { it.toItem(api) }
            } else {
                val parent = current?.handle?.let(api::getNodeByHandle) ?: api.rootNode ?: return@withContext emptyList()
                api.getChildren(parent, SortOrder.NAME_ASC.sdkOrder)
                    .filter { it.isFolder && it.handle !in exclude }
                    .map { it.toItem(api) }
            }
        }
    }

    val target: Long? = current?.handle ?: if (tab == 0) graph.nodes.rootHandle else null
    val canWrite = current == null && tab == 0 || (current?.access ?: MegaShare.ACCESS_OWNER) >= MegaShare.ACCESS_READWRITE

    val goUp = { stack = stack.dropLast(1) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (stack.isNotEmpty()) goUp() else onDismiss() }
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, maxLines = 1)
                                Text(
                                    current?.name ?: stringResource(if (tab == 0) R.string.cloud_drive else R.string.incoming_shares),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = { BackButton { if (stack.isNotEmpty()) goUp() else onDismiss() } },
                        actions = {
                            if (canWrite && target != null) {
                                IconAction(R.drawable.ic_create_new_folder, stringResource(R.string.new_folder), { creating = true })
                            }
                        },
                    )
                    if (stack.isEmpty()) {
                        SecondaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.cloud_drive)) })
                            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.incoming_shares)) })
                        }
                    }
                }
            },
            bottomBar = {
                BottomAppBar {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { target?.let { onPick(it); onDismiss() } },
                        enabled = target != null && canWrite,
                        modifier = Modifier.padding(end = 16.dp),
                    ) { Text(confirmLabel) }
                }
            },
        ) { padding ->
            val list = folders
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    list == null -> LoadingBox()
                    list.isEmpty() -> EmptyState(R.drawable.ic_folder_open, stringResource(R.string.no_folders_here))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.handle }) { f ->
                            ListItem(
                                onClick = { stack = stack + f },
                                leadingContent = { NodeThumbnail(f) },
                                supportingContent = { Text(f.owner ?: f.subtitle()) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }

    if (creating && target != null) {
        val invalid = stringResource(R.string.invalid_name)
        TextInputDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.create),
            onConfirm = { name ->
                scope.launch {
                    catching { graph.nodes.createFolder(target, name) }
                        .onSuccess { h -> graph.nodes.item(h)?.let { stack = stack + it } ?: reload++ }
                        .onFailure { graph.messenger.show(it.message ?: "") }
                }
            },
            onDismiss = { creating = false },
            validate = { invalidNameError(it, invalid) },
        )
    }
}
