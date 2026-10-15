package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.AppGraph
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.feature.shared.ShareFolderSheet
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.NodeThumbnail
import app.megaglass.android.ui.components.StorageAccess
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.copySensitive
import app.megaglass.android.ui.components.invalidNameError
import app.megaglass.android.ui.components.labelColor
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.components.subtitle
import app.megaglass.android.ui.nav.AppNavigator
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.nav.Versions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaShare

/** Where the nodes are shown; decides which actions make sense. */
enum class NodeContext { CLOUD, INCOMING, OUTGOING, LINKS, RUBBISH, BACKUPS, SEARCH }

enum class NodeAction(@StringRes val label: Int, @DrawableRes val icon: Int, val destructive: Boolean = false) {
    INFO(R.string.action_info, R.drawable.ic_info),
    DOWNLOAD(R.string.action_download, R.drawable.ic_download),
    OFFLINE(R.string.action_offline, R.drawable.ic_offline_pin),
    GET_LINK(R.string.action_get_link, R.drawable.ic_link),
    REMOVE_LINK(R.string.action_remove_link, R.drawable.ic_link_off),
    SHARE_FOLDER(R.string.action_share_folder, R.drawable.ic_folder_shared),
    SEND(R.string.action_send, R.drawable.ic_share),
    OPEN_WITH(R.string.action_open_with, R.drawable.ic_open_in_new),
    RENAME(R.string.action_rename, R.drawable.ic_drive_file_rename_outline),
    MOVE(R.string.action_move, R.drawable.ic_drive_file_move),
    COPY(R.string.action_copy, R.drawable.ic_content_copy),
    VERSIONS(R.string.action_versions, R.drawable.ic_history),
    FAVOURITE(R.string.action_favourite, R.drawable.ic_star),
    UNFAVOURITE(R.string.action_unfavourite, R.drawable.ic_star_fill),
    HIDE(R.string.action_hide, R.drawable.ic_visibility_off),
    UNHIDE(R.string.action_unhide, R.drawable.ic_visibility),
    LEAVE_SHARE(R.string.action_leave_share, R.drawable.ic_logout, destructive = true),
    RUBBISH(R.string.action_rubbish, R.drawable.ic_delete, destructive = true),
    RESTORE(R.string.action_restore, R.drawable.ic_settings_backup_restore),
    DELETE(R.string.action_delete_forever, R.drawable.ic_delete_forever, destructive = true),
}

/** Actions for one item in the menu sheet, in display order. */
fun actionsFor(item: NodeItem, context: NodeContext, versions: Int, api: MegaApiJava): List<NodeAction> {
    val own = item.access >= MegaShare.ACCESS_OWNER
    val full = item.access >= MegaShare.ACCESS_FULL
    val list = mutableListOf(NodeAction.INFO)
    when (context) {
        NodeContext.RUBBISH -> return list + listOf(NodeAction.RESTORE, NodeAction.DELETE)
        NodeContext.BACKUPS -> return list + listOf(NodeAction.DOWNLOAD, NodeAction.OFFLINE, NodeAction.COPY) +
            if (item.isFolder) emptyList() else listOf(NodeAction.SEND, NodeAction.OPEN_WITH)
        else -> Unit
    }
    if (own) list += if (item.isFavourite) NodeAction.UNFAVOURITE else NodeAction.FAVOURITE
    list += NodeAction.DOWNLOAD
    list += NodeAction.OFFLINE
    if (own && !item.isTakenDown) {
        list += NodeAction.GET_LINK
        if (item.isExported) list += NodeAction.REMOVE_LINK
    }
    if (own && item.isFolder) list += NodeAction.SHARE_FOLDER
    if (!item.isFolder) {
        list += NodeAction.SEND
        list += NodeAction.OPEN_WITH
    }
    if (full) list += NodeAction.RENAME
    if (own) list += NodeAction.MOVE
    list += NodeAction.COPY
    if (!item.isFolder && versions > 1) list += NodeAction.VERSIONS
    if (own) list += if (item.isSensitive) NodeAction.UNHIDE else NodeAction.HIDE
    if (item.isInShare && item.parentHandle == MegaApiJava.INVALID_HANDLE) {
        list += NodeAction.LEAVE_SHARE
    }
    if (own || full) list += NodeAction.RUBBISH
    return list
}

/**
 * Node actions with their dialogs, shared by every screen that lists cloud nodes.
 * Call [NodeActionsHost] once in the screen to render the sheet and the dialogs.
 */
@Stable
class NodeActionsState internal constructor(
    val context: NodeContext,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val navigator: AppNavigator,
    internal val storage: StorageAccess,
    private val appContext: Context,
    private val onDone: () -> Unit,
) {
    internal var sheetItem by mutableStateOf<NodeItem?>(null)
    internal var sheetVersions by mutableStateOf(0)
    internal var renaming by mutableStateOf<NodeItem?>(null)
    internal var picking by mutableStateOf<Pair<NodeAction, List<NodeItem>>?>(null)
    internal var confirming by mutableStateOf<Pair<NodeAction, List<NodeItem>>?>(null)
    internal var linkFor by mutableStateOf<NodeItem?>(null)
    internal var shareFolder by mutableStateOf<NodeItem?>(null)

    private val nodes get() = graph.nodes
    private val messenger get() = graph.messenger
    private fun str(id: Int, vararg args: Any) = appContext.getString(id, *args)

    fun openMenu(item: NodeItem) {
        sheetItem = item
        sheetVersions = 0
        if (!item.isFolder) scope.launch {
            catching { nodes.versionCount(item.handle) }.onSuccess { versions ->
                if (sheetItem?.handle == item.handle) sheetVersions = versions
            }.onFailure { messenger.show(it.message ?: str(R.string.error_title)) }
        }
    }

    private fun launch(block: suspend () -> Unit) = scope.launch {
        catching { block() }.onFailure { messenger.show(it.message ?: str(R.string.error_title)) }
    }

    fun run(action: NodeAction, items: List<NodeItem>) {
        if (items.isEmpty()) return
        val handles = items.map { it.handle }
        when (action) {
            NodeAction.INFO -> navigator.navigate(NodeInfo(items.first().handle))
            NodeAction.VERSIONS -> navigator.navigate(Versions(items.first().handle))
            NodeAction.DOWNLOAD -> storage.ensure {
                launch {
                    graph.transfers.download(handles, graph.files.downloadDir(graph.settings.current))
                    messenger.show(appContext.resources.getQuantityString(R.plurals.download_started, items.size, items.size))
                }
                onDone()
            }
            NodeAction.OFFLINE -> launch {
                val offline = graph.offline.handles.value
                if (items.all { it.handle in offline }) {
                    graph.offline.remove(handles)
                    messenger.show(str(R.string.offline_removed))
                } else {
                    graph.offline.makeAvailable(handles)
                    messenger.show(str(R.string.offline_started))
                }
                onDone()
            }
            NodeAction.GET_LINK -> linkFor = items.first()
            NodeAction.REMOVE_LINK -> launch {
                nodes.disableLink(handles)
                messenger.show(str(R.string.link_removed))
                onDone()
            }
            NodeAction.SHARE_FOLDER -> shareFolder = items.first()
            NodeAction.SEND -> launch {
                graph.opener.share(items.filter { !it.isFolder }.map { Triple(it.handle, it.name, it.size) })
                onDone()
            }
            NodeAction.OPEN_WITH -> launch { items.first().let { graph.opener.open(it.handle, it.name, it.size) } }
            NodeAction.RENAME -> renaming = items.first()
            NodeAction.MOVE, NodeAction.COPY -> picking = action to items
            NodeAction.FAVOURITE, NodeAction.UNFAVOURITE -> launch {
                nodes.setFavourite(handles, action == NodeAction.FAVOURITE)
                onDone()
            }
            NodeAction.HIDE, NodeAction.UNHIDE -> launch {
                nodes.setHidden(handles, action == NodeAction.HIDE)
                if (action == NodeAction.HIDE) messenger.show(str(R.string.hidden_done))
                onDone()
            }
            NodeAction.RUBBISH -> moveToRubbish(items)
            NodeAction.LEAVE_SHARE, NodeAction.DELETE -> confirming = action to items
            NodeAction.RESTORE -> launch {
                nodes.restore(handles)
                messenger.show(appContext.resources.getQuantityString(R.plurals.restored, items.size, items.size))
                onDone()
            }
        }
    }

    private fun moveToRubbish(items: List<NodeItem>) = launch {
        val parents = items.associate { it.handle to it.parentHandle }
        nodes.moveToRubbish(parents.keys)
        onDone()
        val text = appContext.resources.getQuantityString(R.plurals.moved_to_rubbish, items.size, items.size)
        // From an incoming share the SDK copies the items to the Rubbish bin and deletes the
        // originals, so the handles change and an undo cannot find them.
        if (context == NodeContext.INCOMING) {
            messenger.show(text)
            return@launch
        }
        messenger.show(text, str(R.string.undo)) {
            // App scope: the snackbar can outlive this screen.
            graph.scope.launch {
                catching { parents.entries.groupBy({ it.value }, { it.key }).forEach { (p, h) -> nodes.move(h, p) } }
                    .onFailure { messenger.show(it.message ?: str(R.string.error_title)) }
            }
        }
    }

    internal fun confirmed(action: NodeAction, items: List<NodeItem>) = launch {
        val handles = items.map { it.handle }
        when (action) {
            NodeAction.DELETE -> nodes.deletePermanently(handles)
            // Removing the root of an incoming share leaves the share.
            NodeAction.LEAVE_SHARE -> nodes.deletePermanently(handles)
            else -> Unit
        }
        onDone()
    }

    internal fun picked(action: NodeAction, items: List<NodeItem>, target: Long) = launch {
        val handles = items.map { it.handle }
        if (action == NodeAction.MOVE) nodes.move(handles, target) else nodes.copy(handles, target)
        messenger.show(
            appContext.resources.getQuantityString(
                if (action == NodeAction.MOVE) R.plurals.moved else R.plurals.copied, items.size, items.size,
            ),
        )
        onDone()
    }

    internal fun rename(item: NodeItem, name: String) = launch { nodes.rename(item.handle, name) }

    internal fun copyText(text: String) {
        copySensitive(appContext, text)
        messenger.show(str(R.string.copied))
    }

    internal fun shareText(text: String) = graph.opener.shareText(text)

    /** Downloads into the default folder, for screens with their own download button. */
    fun download(items: List<NodeItem>) = run(NodeAction.DOWNLOAD, items)
}

@Composable
fun rememberNodeActions(context: NodeContext, onDone: () -> Unit = {}): NodeActionsState {
    val graph = MegaGlassApp.graph
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    val storage = rememberStorageAccess()
    val appContext = LocalContext.current.applicationContext
    return remember(context) { NodeActionsState(context, graph, scope, navigator, storage, appContext, onDone) }
}

@Composable
fun NodeActionsHost(state: NodeActionsState) {
    val graph = MegaGlassApp.graph
    state.sheetItem?.let { item ->
        NodeSheet(item, state) { state.sheetItem = null }
    }
    state.renaming?.let { item ->
        val invalid = stringResource(R.string.invalid_name)
        TextInputDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.action_rename),
            initial = item.name,
            selectBaseName = !item.isFolder,
            onConfirm = { state.rename(item, it) },
            onDismiss = { state.renaming = null },
            validate = { invalidNameError(it, invalid) ?: if (it == item.name) "" else null },
        )
    }
    state.picking?.let { (action, items) ->
        FolderPickerDialog(
            title = pluralStringResource(if (action == NodeAction.MOVE) R.plurals.move_items else R.plurals.copy_items, items.size, items.size),
            confirmLabel = stringResource(if (action == NodeAction.MOVE) R.string.move_here else R.string.copy_here),
            exclude = items.filter { it.isFolder }.map { it.handle }.toSet(),
            start = items.first().parentHandle,
            onPick = { target -> state.picked(action, items, target) },
            onDismiss = { state.picking = null },
        )
    }
    state.confirming?.let { (action, items) ->
        val leave = action == NodeAction.LEAVE_SHARE
        ConfirmDialog(
            title = if (leave) stringResource(R.string.leave_share_title)
            else pluralStringResource(R.plurals.delete_forever_title, items.size, items.size),
            text = stringResource(if (leave) R.string.leave_share_text else R.string.delete_forever_text),
            confirmLabel = stringResource(if (leave) R.string.action_leave_share else R.string.action_delete_forever),
            destructive = true,
            icon = action.icon,
            onConfirm = { state.confirmed(action, items) },
            onDismiss = { state.confirming = null },
        )
    }
    state.linkFor?.let { item -> LinkDialog(item, state) { state.linkFor = null } }
    state.shareFolder?.let { item -> ShareFolderSheet(item.handle) { state.shareFolder = null } }
    // Keeps the offline set fresh for the sheet labels.
    graph.offline.handles.collectAsStateWithLifecycle()
}

@Composable
private fun NodeSheet(item: NodeItem, state: NodeActionsState, onDismiss: () -> Unit) {
    val graph = MegaGlassApp.graph
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val actions = remember(item, state.sheetVersions) { actionsFor(item, state.context, state.sheetVersions, graph.mega.api) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NodeThumbnail(item, size = 52.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Text(item.subtitle(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.access >= MegaShare.ACCESS_OWNER && state.context != NodeContext.RUBBISH && state.context != NodeContext.BACKUPS) {
                LabelRow(item.label) { label ->
                    scope.launch { catching { graph.nodes.setLabel(listOf(item.handle), label) } }
                    onDismiss()
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            actions.forEach { action ->
                val isOffline = action == NodeAction.OFFLINE && item.handle in offline
                ListItem(
                    onClick = {
                        onDismiss()
                        state.run(action, listOf(item))
                    },
                    leadingContent = {
                        Ic(action.icon, null, tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = if (action == NodeAction.OFFLINE) {
                        { Switch(checked = isOffline, onCheckedChange = { onDismiss(); state.run(action, listOf(item)) }) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) {
                    Text(
                        stringResource(action.label),
                        color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LabelRow(current: Int, onPick: (Int) -> Unit) {
    val labels = listOf(
        MegaNode.NODE_LBL_RED, MegaNode.NODE_LBL_ORANGE, MegaNode.NODE_LBL_YELLOW, MegaNode.NODE_LBL_GREEN,
        MegaNode.NODE_LBL_BLUE, MegaNode.NODE_LBL_PURPLE, MegaNode.NODE_LBL_GREY,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEach { l ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(labelColor(l)).clickable { onPick(if (current == l) 0 else l) },
                contentAlignment = Alignment.Center,
            ) {
                if (current == l) Ic(R.drawable.ic_check, stringResource(R.string.remove_label), Modifier.size(18.dp), Color.White)
            }
        }
    }
}

/** Creates the public link and shows it, with copy and share, and the key as a separate option. */
@Composable
private fun LinkDialog(item: NodeItem, state: NodeActionsState, onDismiss: () -> Unit) {
    val graph = MegaGlassApp.graph
    var link by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var separateKey by remember { mutableStateOf(false) }
    LaunchedEffect(item.handle) {
        catching { graph.nodes.exportLink(item.handle) }
            .onSuccess { link = it }
            .onFailure { error = it.message }
    }
    val full = link
    val withoutKey = full?.substringBefore('#')
    val key = full?.substringAfter('#', "")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_link) },
        title = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        text = {
            Column {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    full == null -> Text(stringResource(R.string.creating_link))
                    else -> {
                        Text(
                            if (separateKey) withoutKey!! else full,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
                        )
                        if (separateKey) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.decryption_key), style = MaterialTheme.typography.labelLarge)
                            Text(
                                key!!,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
                            )
                            TextButton(onClick = { state.copyText(key) }) { Text(stringResource(R.string.copy_key)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.separate_key), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(separateKey, { separateKey = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (full != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.shareText(if (separateKey) withoutKey!! else full) }) { Text(stringResource(R.string.action_send)) }
                    Button(onClick = { state.copyText(if (separateKey) withoutKey!! else full) }) { Text(stringResource(R.string.copy_link)) }
                }
            } else {
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (full != null) { { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } } } else null,
    )
}
