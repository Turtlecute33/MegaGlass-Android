package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.FolderInfo
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.showsUploaderImage
import app.megaglass.android.core.media.NodeImage
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.Format
import app.megaglass.android.core.util.whileObserved
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeRow
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.components.folderIcon
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.nav.Rubbish
import app.megaglass.android.ui.nav.Versions
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaShare

data class ShareEntry(val user: String, val access: Int, val pending: Boolean)

data class NodeInfoState(
    val item: NodeItem? = null,
    val missing: Boolean = false,
    val location: String = "",
    val folderInfo: FolderInfo? = null,
    val versions: Int = 0,
    val link: String? = null,
    val shares: List<ShareEntry> = emptyList(),
    val owner: String? = null,
    val description: String = "",
    val mimeType: String = "",
    val inRubbish: Boolean = false,
)

private class InfoRow(val icon: Int, val label: String, val value: String, val onClick: (() -> Unit)? = null)

class NodeInfoViewModel(private val handle: Long) : ViewModel() {
    private val graph = MegaGlassApp.graph
    private val api = graph.mega.api
    private val _state = MutableStateFlow(NodeInfoState())
    val state: StateFlow<NodeInfoState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { graph.nodes.changes.filter { it.touches(handle) }.whileObserved(_state.subscriptionCount).debounce(200).collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val loaded = withContext(Dispatchers.IO) {
            val node = api.getNodeByHandle(handle) ?: return@withContext null
            val item = graph.nodes.item(handle) ?: return@withContext null
            NodeInfoState(
                item = item,
                location = graph.nodes.path(node.parentHandle).joinToString(" / ") { it.name },
                versions = if (item.isFolder) 0 else api.getNumVersions(node),
                link = if (node.isExported) node.publicLink else null,
                shares = if (node.isOutShare || api.isPendingShare(node)) {
                    api.getOutShares(node).map { ShareEntry(it.user ?: "", it.access, it.isPending) }
                } else emptyList(),
                owner = if (node.isInShare) api.getUserFromInShare(node, true)?.email else null,
                description = node.description.orEmpty(),
                mimeType = if (item.isFolder) "" else FileType.mimeType(item.name),
                inRubbish = api.isInRubbish(node),
            )
        }
        if (loaded == null) {
            _state.update { it.copy(missing = true) }
            return@launch
        }
        _state.update { loaded.copy(folderInfo = it.folderInfo) }
        if (loaded.item?.isFolder == true) {
            catching { graph.nodes.folderInfo(handle) }.onSuccess { fi -> _state.update { it.copy(folderInfo = fi) } }
        }
    }

    fun saveDescription(text: String) = viewModelScope.launch {
        catching { graph.nodes.setDescription(handle, text) }.onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
    }
}

@Composable
fun NodeInfoScreen(route: NodeInfo) {
    val vm: NodeInfoViewModel = viewModel(key = "info:${route.handle}") { NodeInfoViewModel(route.handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val graph = MegaGlassApp.graph
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val item = state.item
    val own = (item?.access ?: MegaShare.ACCESS_OWNER) >= MegaShare.ACCESS_OWNER
    val actions = rememberNodeActions(
        when {
            state.inRubbish -> NodeContext.RUBBISH
            own -> NodeContext.CLOUD
            else -> NodeContext.INCOMING
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_info)) },
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    if (item != null && own && !state.inRubbish) {
                        IconAction(
                            if (item.isFavourite) R.drawable.ic_star_fill else R.drawable.ic_star,
                            stringResource(if (item.isFavourite) R.string.action_unfavourite else R.string.action_favourite),
                            { actions.run(if (item.isFavourite) NodeAction.UNFAVOURITE else NodeAction.FAVOURITE, listOf(item)) },
                        )
                    }
                    if (item != null) IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { actions.openMenu(item) })
                },
            )
        },
    ) { padding ->
        when {
            state.missing -> EmptyState(R.drawable.ic_error, stringResource(R.string.item_not_found), Modifier.padding(padding))
            item == null -> LoadingBox(Modifier.padding(padding))
            else -> {
                val rows = detailRows(state, item) { nav.navigate(if (state.inRubbish) Rubbish(item.parentHandle) else Folder(item.parentHandle)) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp)) {
                    item(key = "header") { Header(item) }
                    item(key = "details") {
                        SectionHeader(stringResource(R.string.details))
                        InfoGroup(rows)
                    }
                    if (state.versions > 1) {
                        item(key = "versions") {
                            ListItem(
                                onClick = { nav.navigate(Versions(item.handle)) },
                                leadingContent = { Ic(R.drawable.ic_history) },
                                trailingContent = { Ic(R.drawable.ic_chevron_right) },
                                supportingContent = { Text(pluralStringResource(R.plurals.n_versions, state.versions - 1, state.versions - 1)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) { Text(stringResource(R.string.action_versions)) }
                        }
                    }
                    item(key = "sharing") {
                        SectionHeader(stringResource(R.string.sharing))
                        LinkRow(state.link, item, actions, canCreate = !state.inRubbish)
                        state.shares.forEach { s ->
                            ListItem(
                                leadingContent = { Ic(R.drawable.ic_person) },
                                supportingContent = { Text(accessLabel(s.access) + if (s.pending) " · " + stringResource(R.string.pending) else "") },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) { Text(s.user, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    item(key = "offline") {
                        ListItem(
                            leadingContent = { Ic(R.drawable.ic_offline_pin) },
                            trailingContent = { Switch(item.handle in offline, { actions.run(NodeAction.OFFLINE, listOf(item)) }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        ) { Text(stringResource(R.string.action_offline)) }
                    }
                    if (own) {
                        item(key = "description") {
                            SectionHeader(stringResource(R.string.description))
                            DescriptionEditor(state.description, vm::saveDescription)
                        }
                    }
                }
            }
        }
    }
    NodeActionsHost(actions)
}

@Composable
private fun detailRows(state: NodeInfoState, item: NodeItem, openLocation: () -> Unit): List<InfoRow> = buildList {
    add(InfoRow(R.drawable.ic_draft, stringResource(R.string.info_type), if (item.isFolder) stringResource(R.string.info_folder) else state.mimeType))
    val size = if (item.isFolder) state.folderInfo?.size else item.size
    if (size != null) add(InfoRow(R.drawable.ic_storage, stringResource(R.string.info_size), Format.size(size)))
    state.folderInfo?.let { fi ->
        add(
            InfoRow(
                R.drawable.ic_folder_copy, stringResource(R.string.info_contains),
                pluralStringResource(R.plurals.n_folders, fi.folders, fi.folders) + ", " + pluralStringResource(R.plurals.n_files, fi.files, fi.files),
            ),
        )
        if (fi.versions > 0) {
            add(InfoRow(R.drawable.ic_history, stringResource(R.string.action_versions), "${fi.versions} · ${Format.size(fi.versionsSize)}"))
        }
    }
    add(InfoRow(R.drawable.ic_folder_open, stringResource(R.string.info_location), state.location.ifEmpty { "/" }, openLocation))
    add(InfoRow(R.drawable.ic_schedule, stringResource(R.string.info_created), Format.dateTime(item.created)))
    if (!item.isFolder) add(InfoRow(R.drawable.ic_edit, stringResource(R.string.info_modified), Format.dateTime(item.modified)))
    if (item.duration > 0) add(InfoRow(R.drawable.ic_timer, stringResource(R.string.info_duration), Format.duration(item.duration.toLong())))
    state.owner?.let { add(InfoRow(R.drawable.ic_person, stringResource(R.string.info_owner), it)) }
}

@Composable
fun accessLabel(access: Int): String = stringResource(
    when (access) {
        MegaShare.ACCESS_READ -> R.string.access_read
        MegaShare.ACCESS_READWRITE -> R.string.access_readwrite
        MegaShare.ACCESS_FULL -> R.string.access_full
        else -> R.string.access_owner
    },
)

@Composable
private fun Header(item: NodeItem) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!item.isFolder && item.hasPreview && item.type.showsUploaderImage) {
            val context = LocalContext.current
            AsyncImage(
                model = remember(item.handle) { ImageRequest.Builder(context).data(NodeImage(item.handle, preview = true)).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        } else {
            Box(Modifier.padding(vertical = 16.dp)) {
                FileTypeIcon(item.type, size = 112.dp, override = if (item.isFolder) item.folderIcon() else null)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(item.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InfoGroup(rows: List<InfoRow>) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            SegmentedListItem(
                onClick = { row.onClick?.invoke() },
                enabled = row.onClick != null,
                shapes = ListItemDefaults.segmentedShapes(index = i, count = rows.size),
                leadingContent = { Ic(row.icon) },
                trailingContent = if (row.onClick != null) { { Ic(R.drawable.ic_chevron_right) } } else null,
                supportingContent = { Text(row.value) },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) { Text(row.label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun LinkRow(link: String?, item: NodeItem, actions: NodeActionsState, canCreate: Boolean) {
    ListItem(
        onClick = { actions.run(NodeAction.GET_LINK, listOf(item)) },
        enabled = canCreate && item.access >= MegaShare.ACCESS_OWNER,
        leadingContent = { Ic(R.drawable.ic_link) },
        supportingContent = { Text(link ?: stringResource(R.string.no_link), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (link != null) {
            { TextButton(onClick = { actions.run(NodeAction.REMOVE_LINK, listOf(item)) }) { Text(stringResource(R.string.action_remove_link)) } }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(stringResource(R.string.link)) }
}

@Composable
private fun DescriptionEditor(initial: String, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(300) },
            placeholder = { Text(stringResource(R.string.description_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (text != initial) {
            Button(onClick = { onSave(text) }, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
fun VersionsScreen(route: Versions) {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    var versions by remember { mutableStateOf<List<NodeItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NodeItem?>(null) }
    // The current version: route.handle becomes a previous version after a restore, and can then be deleted.
    var anchor by remember { mutableLongStateOf(route.handle) }
    val actions = rememberNodeActions(NodeContext.CLOUD)
    LaunchedEffect(reload) {
        versions = catching { graph.nodes.versions(anchor) }.getOrDefault(emptyList())
        versions?.firstOrNull()?.let { anchor = it.handle }
    }
    LaunchedEffect(Unit) { graph.nodes.changes.debounce(300).collect { reload++ } }
    // App scope: "Delete previous versions" must not stop halfway when the user leaves the screen.
    val act = { block: suspend () -> Unit ->
        graph.scope.launch(Dispatchers.Main) {
            catching { block() }.onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
            reload++
        }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_versions)) },
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    if ((versions?.size ?: 0) > 1) {
                        IconAction(R.drawable.ic_delete_forever, stringResource(R.string.delete_previous_versions), { confirmAll = true })
                    }
                },
            )
        },
    ) { padding ->
        val list = versions
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(R.drawable.ic_history, stringResource(R.string.item_not_found), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 48.dp)) {
                items(list, key = { it.handle }) { v ->
                    val current = v.handle == list.first().handle
                    Column {
                        NodeRow(
                            item = v,
                            onClick = { nav.openNode(v, MediaSource.Single) },
                            subtitle = (if (current) stringResource(R.string.current_version) + " · " else "") +
                                Format.size(v.size) + " · " + Format.dateTime(v.modified),
                        )
                        Row(Modifier.padding(start = 72.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { actions.download(listOf(v)) }) { Text(stringResource(R.string.action_download)) }
                            if (!current) {
                                TextButton(onClick = { act { graph.nodes.restoreVersion(v.handle) } }) { Text(stringResource(R.string.action_restore)) }
                                TextButton(onClick = { confirmDelete = v }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
    NodeActionsHost(actions)
    if (confirmAll) {
        ConfirmDialog(
            title = stringResource(R.string.delete_previous_versions),
            text = stringResource(R.string.delete_previous_versions_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { act { versions.orEmpty().drop(1).forEach { graph.nodes.removeVersion(it.handle) } } },
            onDismiss = { confirmAll = false },
        )
    }
    confirmDelete?.let { v ->
        ConfirmDialog(
            title = stringResource(R.string.delete_version),
            text = stringResource(R.string.delete_version_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { act { graph.nodes.removeVersion(v.handle) } },
            onDismiss = { confirmDelete = null },
        )
    }
}
