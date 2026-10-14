package app.megaglass.android.feature.drive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeCollection
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.invalidNameError
import app.megaglass.android.ui.nav.Backups
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.Rubbish
import app.megaglass.android.ui.nav.Search
import kotlinx.coroutines.launch

@Composable
fun FolderScreen(route: Folder) = DriveFolder(DriveMode.CLOUD, route.handle)

@Composable
fun RubbishScreen(route: Rubbish) = DriveFolder(DriveMode.RUBBISH, route.handle)

@Composable
fun BackupsScreen(route: Backups) = DriveFolder(DriveMode.BACKUPS, route.handle)

@Composable
private fun DriveFolder(mode: DriveMode, handle: Long) {
    val vm: FolderViewModel = viewModel(key = "$mode:$handle") { FolderViewModel(mode, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = when (mode) {
        DriveMode.RUBBISH -> NodeContext.RUBBISH
        DriveMode.BACKUPS -> NodeContext.BACKUPS
        DriveMode.CLOUD -> if (state.access < nz.mega.sdk.MegaShare.ACCESS_OWNER) NodeContext.INCOMING else NodeContext.CLOUD
    }
    val actions = rememberNodeActions(context, onDone = vm::clearSelection)
    val uploader = rememberUploader(vm.rootHandle)
    val selecting = state.selection.isNotEmpty()
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting || fabOpen) {
        if (fabOpen) fabOpen = false else vm.clearSelection()
    }

    val rootTitle = stringResource(
        when (mode) {
            DriveMode.CLOUD -> R.string.cloud_drive
            DriveMode.RUBBISH -> R.string.rubbish_bin
            DriveMode.BACKUPS -> R.string.backups
        },
    )
    val title = if (state.isRoot) rootTitle else state.title
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isTopLevelTab = mode == DriveMode.CLOUD && state.isRoot

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.n_selected, state.selection.size, state.selection.size)) },
                    navigationIcon = { IconAction(R.drawable.ic_close, stringResource(R.string.close), vm::clearSelection) },
                    actions = { IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), vm::selectAll) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                Column {
                    MediumFlexibleTopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        subtitle = state.items?.let { items ->
                            { Text(pluralStringResource(R.plurals.n_items, items.size, items.size)) }
                        },
                        navigationIcon = { if (!isTopLevelTab) BackButton { nav.back() } },
                        actions = {
                            if (mode != DriveMode.RUBBISH) {
                                IconAction(R.drawable.ic_search, stringResource(R.string.search), { nav.navigate(Search(vm.rootHandle)) })
                            }
                            ViewOptionsActions()
                            if (mode == DriveMode.RUBBISH && !state.items.isNullOrEmpty()) {
                                IconAction(R.drawable.ic_delete_forever, stringResource(R.string.empty_rubbish), { confirmEmpty = true })
                            }
                            if (isTopLevelTab) {
                                Box {
                                    IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { overflow = true })
                                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rubbish_bin)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                                            onClick = { overflow = false; nav.navigate(Rubbish()) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.backups)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_backup), null) },
                                            onClick = { overflow = false; nav.navigate(Backups()) },
                                        )
                                    }
                                }
                            }
                        },
                        scrollBehavior = scroll,
                    )
                    Breadcrumbs(state.path, rootTitle, onClick = { crumb ->
                        val root = crumb == state.path.first()
                        nav.popToOrNavigate(
                            when (mode) {
                                DriveMode.RUBBISH -> Rubbish(if (root) app.megaglass.android.ui.nav.ROOT else crumb.handle)
                                DriveMode.BACKUPS -> Backups(if (root) app.megaglass.android.ui.nav.ROOT else crumb.handle)
                                DriveMode.CLOUD -> Folder(if (root && crumb.handle == graph.nodes.rootHandle) app.megaglass.android.ui.nav.ROOT else crumb.handle)
                            },
                        )
                    })
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = mode == DriveMode.CLOUD && state.canWrite && !selecting && state.items != null,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                FloatingActionButtonMenu(
                    expanded = fabOpen,
                    button = {
                        ToggleFloatingActionButton(checked = fabOpen, onCheckedChange = { fabOpen = it }) {
                            Icon(
                                painterResource(if (fabOpen) R.drawable.ic_close else R.drawable.ic_add),
                                contentDescription = stringResource(R.string.add),
                                modifier = Modifier.animateIcon({ checkedProgress }),
                            )
                        }
                    },
                ) {
                    FabItem(R.drawable.ic_upload_file, R.string.upload_files) { fabOpen = false; uploader.files() }
                    FabItem(R.drawable.ic_drive_folder_upload, R.string.upload_folder) { fabOpen = false; uploader.folder() }
                    FabItem(R.drawable.ic_photo_camera, R.string.take_photo) { fabOpen = false; uploader.takePhoto() }
                    FabItem(R.drawable.ic_text_snippet, R.string.new_text_file) { fabOpen = false; uploader.newTextFile() }
                    FabItem(R.drawable.ic_create_new_folder, R.string.new_folder) { fabOpen = false; newFolder = true }
                }
            }
        },
    ) { padding ->
        val items = state.items
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.load(refresh = true) },
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = refresh,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            when {
                items == null -> LoadingBox()
                state.error != null && items.isEmpty() -> ErrorState(state.error!!, onRetry = { vm.load() })
                items.isEmpty() -> EmptyState(
                    icon = when (mode) {
                        DriveMode.RUBBISH -> R.drawable.ic_delete
                        DriveMode.BACKUPS -> R.drawable.ic_backup
                        DriveMode.CLOUD -> if (state.isRoot) R.drawable.ic_cloud_upload else R.drawable.ic_folder_open
                    },
                    title = stringResource(
                        when {
                            mode == DriveMode.RUBBISH -> R.string.empty_rubbish_title
                            mode == DriveMode.BACKUPS -> R.string.empty_backups_title
                            state.isRoot -> R.string.empty_cloud_title
                            else -> R.string.empty_folder_title
                        },
                    ),
                    message = if (mode == DriveMode.CLOUD && state.canWrite) stringResource(R.string.empty_folder_hint) else null,
                    actionLabel = if (mode == DriveMode.CLOUD && state.canWrite) stringResource(R.string.upload_files) else null,
                    onAction = uploader::files,
                )
                else -> NodeCollection(
                    items = items,
                    grid = settings.gridView,
                    selection = state.selection,
                    offline = offline,
                    onClick = { item ->
                        when {
                            selecting -> vm.toggle(item)
                            mode == DriveMode.RUBBISH && item.isFolder -> nav.navigate(Rubbish(item.handle))
                            mode == DriveMode.BACKUPS && item.isFolder -> nav.navigate(Backups(item.handle))
                            mode == DriveMode.RUBBISH -> actions.openMenu(item)
                            else -> nav.openNode(item, MediaSource.Folder(vm.rootHandle))
                        }
                    },
                    onLongClick = vm::toggle,
                    onMore = actions::openMenu,
                    contentPadding = PaddingValues(bottom = 120.dp + padding.calculateBottomPadding()),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SelectionToolbar(state.selectedItems, context, actions)
            }
        }
    }

    NodeActionsHost(actions)

    if (newFolder) {
        val invalid = stringResource(R.string.invalid_name)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        TextInputDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.create),
            onConfirm = { name ->
                scope.launch {
                    runCatching { graph.nodes.createFolder(vm.rootHandle, name) }
                        .onFailure { graph.messenger.show(it.message ?: "") }
                }
            },
            onDismiss = { newFolder = false },
            validate = { name ->
                invalidNameError(name, invalid)
                    ?: if (state.items.orEmpty().any { it.isFolder && it.name.equals(name, ignoreCase = true) }) invalid else null
            },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.empty_rubbish),
            text = stringResource(R.string.empty_rubbish_text),
            confirmLabel = stringResource(R.string.empty_rubbish_confirm),
            destructive = true,
            icon = R.drawable.ic_delete_forever,
            onConfirm = vm::emptyRubbish,
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun androidx.compose.material3.FloatingActionButtonMenuScope.FabItem(icon: Int, label: Int, onClick: () -> Unit) {
    FloatingActionButtonMenuItem(
        onClick = onClick,
        icon = { Icon(painterResource(icon), null) },
        text = { Text(stringResource(label)) },
    )
}
