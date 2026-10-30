package app.megaglass.android.feature.photos

import app.megaglass.android.core.util.catching

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.camera.CameraUploads
import app.megaglass.android.core.camera.CuStatus
import app.megaglass.android.feature.drive.NodeActionsHost
import app.megaglass.android.feature.drive.NodeContext
import app.megaglass.android.feature.drive.SelectionToolbar
import app.megaglass.android.feature.drive.rememberNodeActions
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.copySensitive
import app.megaglass.android.ui.components.rememberDialogAllowed
import app.megaglass.android.ui.nav.Album
import app.megaglass.android.ui.nav.AppNavigator
import app.megaglass.android.ui.nav.CameraUploadsSettings
import app.megaglass.android.ui.nav.ImageViewer
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.VideoPlayer
import kotlinx.coroutines.launch

/** Opens a photo in the viewer, a video in the player. */
private fun AppNavigator.openMedia(item: MediaItem, source: MediaSource) {
    if (item.node.isVideo) navigate(VideoPlayer(item.handle)) else navigate(ImageViewer(item.handle, source))
}

@Composable
fun PhotosScreen() {
    val vm: PhotosViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val actions = rememberNodeActions(NodeContext.CLOUD, onDone = vm::clearSelection)
    val selecting = state.selection.isNotEmpty()
    var addToAlbum by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.n_selected, state.selection.size, state.selection.size)) },
                    navigationIcon = { IconAction(R.drawable.ic_close, stringResource(R.string.close), vm::clearSelection) },
                    actions = {
                        IconAction(R.drawable.ic_photo_album, stringResource(R.string.photos_add_to_album), { addToAlbum = true })
                        IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), vm::selectAll)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.tab_photos)) },
                        navigationIcon = { BackButton { nav.back() } },
                        actions = {
                            IconAction(R.drawable.ic_photo_camera, stringResource(R.string.cu_title), { nav.navigate(CameraUploadsSettings) })
                        },
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        listOf(R.string.photos_timeline, R.string.photos_albums).forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = tab == i,
                                onClick = { tab = i },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (tab == 0 || selecting) {
                Timeline(
                    state = state,
                    columns = settings.photosGridColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
                    onColumns = { c -> scope.launch { graph.settings.update { it.copy(photosGridColumns = c) } } },
                    onFilter = { vm.setFilter(it) },
                    onClick = { item -> if (selecting) vm.toggle(item.handle) else nav.openMedia(item, MediaSource.Timeline) },
                    onLongClick = { vm.toggle(it.handle) },
                    onToggleDay = vm::toggleDay,
                    onCameraUploads = { nav.navigate(CameraUploadsSettings) },
                    onRetry = vm::load,
                    contentPadding = PaddingValues(bottom = 120.dp + padding.calculateBottomPadding()),
                )
            } else {
                Albums(
                    albums = state.albums,
                    onOpen = { nav.navigate(Album(it.id, it.isSystem)) },
                    onNew = { newAlbum = false },
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp + padding.calculateBottomPadding()),
                )
            }
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SelectionToolbar(state.all.orEmpty().filter { it.handle in state.selection }.map { it.node }, NodeContext.CLOUD, actions)
            }
        }
    }

    NodeActionsHost(actions)

    if (addToAlbum) {
        AddToAlbumSheet(
            albums = state.albums.orEmpty().filter { !it.isSystem },
            onPick = { album ->
                addToAlbum = false
                vm.addSelectionTo(album.id) { n ->
                    graph.messenger.show(graph.context.resources.getQuantityString(R.plurals.photos_added, n, n, album.name))
                }
            },
            onNew = {
                addToAlbum = false
                newAlbum = true
            },
            onDismiss = { addToAlbum = false },
        )
    }
    newAlbum?.let { withSelection ->
        TextInputDialog(
            title = stringResource(R.string.album_new),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.create),
            onConfirm = { name -> vm.createAlbum(name, withSelection) { id -> nav.navigate(Album(id)) } },
            onDismiss = { newAlbum = null },
        )
    }
}

@Composable
private fun Timeline(
    state: PhotosState,
    columns: Int,
    onColumns: (Int) -> Unit,
    onFilter: (MediaFilter) -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onToggleDay: (DaySection) -> Unit,
    onCameraUploads: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
) {
    val all = state.all
    when {
        all == null -> LoadingBox()
        // A failed load is not an empty timeline (MEGAGLASS-UX-005).
        all.isEmpty() && state.error != null -> ErrorState(state.error, onRetry = onRetry)
        all.isEmpty() -> EmptyState(
            icon = R.drawable.ic_photo_library,
            title = stringResource(R.string.photos_empty_title),
            message = stringResource(R.string.photos_empty_text),
            actionLabel = stringResource(R.string.cu_title),
            onAction = onCameraUploads,
        )
        else -> MediaGrid(
            sections = state.sections,
            columns = columns,
            selection = state.selection,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleDay = onToggleDay,
            onColumnsChange = onColumns,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            header = {
                fullWidthItem("filters") { FilterRow(state.filter, onFilter) }
                fullWidthItem("cu") { CameraUploadsCard(onCameraUploads) }
            },
        )
    }
}

@Composable
private fun FilterRow(filter: MediaFilter, onFilter: (MediaFilter) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            MediaFilter.ALL to R.string.photos_filter_all,
            MediaFilter.PHOTOS to R.string.photos_filter_photos,
            MediaFilter.VIDEOS to R.string.photos_filter_videos,
        ).forEach { (f, label) ->
            FilterChip(
                selected = filter == f,
                onClick = { onFilter(f) },
                label = { Text(stringResource(label)) },
                leadingIcon = if (filter == f) { { Ic(R.drawable.ic_check, null, Modifier.size(18.dp)) } } else null,
            )
        }
    }
}

/** Camera uploads status: a call to action when off, progress when uploading. */
@Composable
fun CameraUploadsCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val status by CameraUploads.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = MegaGlassApp.graph
    val s = status
    if (s is CuStatus.Idle) {
        Row(
            modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ic(R.drawable.ic_cloud_done, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.cu_up_to_date),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val error = s is CuStatus.Failed || s == CuStatus.NeedsPermission
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(
                    when {
                        s == CuStatus.Off -> R.drawable.ic_backup
                        error -> R.drawable.ic_warning
                        else -> R.drawable.ic_cloud_upload
                    },
                    null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (s) {
                            CuStatus.Off -> stringResource(R.string.cu_card_off_title)
                            CuStatus.NeedsPermission -> stringResource(R.string.cu_needs_permission)
                            CuStatus.Scanning -> stringResource(R.string.cu_scanning)
                            is CuStatus.Uploading -> stringResource(R.string.cu_progress, s.done, s.total)
                            is CuStatus.Failed -> s.message
                            is CuStatus.Idle -> ""
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (s == CuStatus.Off) {
                        Text(stringResource(R.string.cu_card_off_text), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when (s) {
                    CuStatus.Off -> FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.cu_turn_on)) }
                    is CuStatus.Failed -> TextButton(onClick = { CameraUploads.uploadNow(context, graph.settings.current) }) {
                        Text(stringResource(R.string.retry))
                    }
                    else -> Unit
                }
            }
            if (s == CuStatus.Scanning) {
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            } else if (s is CuStatus.Uploading && s.total > 0) {
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Albums(
    albums: List<AlbumInfo>?,
    onOpen: (AlbumInfo) -> Unit,
    onNew: () -> Unit,
    contentPadding: PaddingValues,
) {
    if (albums == null) {
        LoadingBox()
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "new") { NewAlbumTile(onNew) }
        items(albums, key = { it.id }) { album ->
            AlbumTile(album, albumTitle(album), onClick = { onOpen(album) })
        }
    }
}

@Composable
private fun NewAlbumTile(onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Ic(R.drawable.ic_add, null, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            stringResource(R.string.album_new),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

@Composable
private fun AddToAlbumSheet(
    albums: List<AlbumInfo>,
    onPick: (AlbumInfo) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!rememberDialogAllowed()) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.photos_add_to_album),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                ListItem(
                    onClick = onNew,
                    leadingContent = { Ic(R.drawable.ic_add) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(stringResource(R.string.album_new)) }
            }
            items(albums, key = { it.id }) { album ->
                ListItem(
                    onClick = { onPick(album) },
                    leadingContent = { Ic(R.drawable.ic_photo_album) },
                    supportingContent = { Text(pluralStringResource(R.plurals.n_items, album.count, album.count)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun AlbumScreen(route: Album) {
    val systemName = when {
        !route.system -> ""
        route.id == Album.FAVOURITES -> stringResource(R.string.album_favourites)
        route.id == Album.GIF -> stringResource(R.string.album_gif)
        route.id == Album.RAW -> stringResource(R.string.album_raw)
        else -> ""
    }
    val vm: AlbumViewModel = viewModel(key = "album:${route.system}:${route.id}") { AlbumViewModel(route.id, route.system, systemName) }
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val actions = rememberNodeActions(NodeContext.CLOUD, onDone = vm::clearSelection)
    val selecting = state.selection.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removingLink by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selecting) { vm.clearSelection() }
    LaunchedEffect(state.deleted) { if (state.deleted) nav.backFrom(route) }

    val sections = remember(state.entries) { PhotosViewModel.group(state.media, MediaFilter.ALL) }
    val user = !state.isSystem

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.n_selected, state.selection.size, state.selection.size)) },
                    navigationIcon = { IconAction(R.drawable.ic_close, stringResource(R.string.close), vm::clearSelection) },
                    actions = {
                        if (user && state.selection.size == 1) {
                            IconAction(R.drawable.ic_image, stringResource(R.string.album_set_cover), vm::setCoverFromSelection)
                        }
                        if (user) IconAction(R.drawable.ic_remove_selection, stringResource(R.string.album_remove_items), vm::removeSelected)
                        IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), vm::selectAll)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.entries?.let {
                                Text(
                                    pluralStringResource(R.plurals.n_items, it.size, it.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { BackButton { nav.back() } },
                    actions = {
                        if (user) {
                            IconAction(R.drawable.ic_add_photo_alternate, stringResource(R.string.album_add_items), { picking = true })
                            IconAction(R.drawable.ic_link, stringResource(R.string.album_share_link), { vm.link { link = it } })
                            Box {
                                IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_rename)) },
                                        leadingIcon = { Ic(R.drawable.ic_edit) },
                                        onClick = {
                                            menu = false
                                            renaming = true
                                        },
                                    )
                                    if (state.isExported) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_remove_link)) },
                                            leadingIcon = { Ic(R.drawable.ic_link_off) },
                                            onClick = {
                                                menu = false
                                                removingLink = true
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.album_delete), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Ic(R.drawable.ic_delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menu = false
                                            deleting = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val entries = state.entries
            val error = state.error
            when {
                entries == null -> LoadingBox()
                entries.isEmpty() && error != null -> ErrorState(error, onRetry = vm::load)
                entries.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_photo_album,
                    title = stringResource(R.string.album_empty),
                    actionLabel = if (user) stringResource(R.string.album_add_items) else null,
                    onAction = { picking = true },
                )
                else -> MediaGrid(
                    sections = sections,
                    columns = settings.photosGridColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
                    selection = state.selection,
                    onClick = { item -> if (selecting) vm.toggle(item.handle) else nav.openMedia(item, MediaSource.Album(route.id, route.system)) },
                    onLongClick = { vm.toggle(it.handle) },
                    onToggleDay = null,
                    onColumnsChange = { c -> scope.launch { graph.settings.update { it.copy(photosGridColumns = c) } } },
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
                SelectionToolbar(state.media.filter { it.handle in state.selection }.map { it.node }, NodeContext.CLOUD, actions)
            }
        }
    }

    NodeActionsHost(actions)

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.action_rename),
            initial = state.name,
            onConfirm = { vm.rename(it) },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.album_delete_title),
            text = stringResource(R.string.album_delete_text),
            confirmLabel = stringResource(R.string.album_delete),
            destructive = true,
            icon = R.drawable.ic_delete,
            onConfirm = { vm.delete() },
            onDismiss = { deleting = false },
        )
    }
    // A removed link stops working for everyone who has it (MEGAGLASS-UX-012).
    if (removingLink) {
        ConfirmDialog(
            title = stringResource(R.string.action_remove_link),
            text = pluralStringResource(R.plurals.remove_link_text, 1),
            confirmLabel = stringResource(R.string.action_remove_link),
            destructive = true,
            icon = R.drawable.ic_link_off,
            onConfirm = { vm.removeLink() },
            onDismiss = { removingLink = false },
        )
    }
    if (picking) {
        MediaPickerSheet(
            exclude = state.media.map { it.handle }.toSet(),
            onPick = { handles -> vm.add(handles) },
            onDismiss = { picking = false },
        )
    }
    link?.let { AlbumLinkDialog(it) { link = null } }
}

/** Full-height sheet to select photos and videos from the timeline. */
@Composable
private fun MediaPickerSheet(exclude: Set<Long>, onPick: (Set<Long>) -> Unit, onDismiss: () -> Unit) {
    if (!rememberDialogAllowed()) return
    val graph = MegaGlassApp.graph
    var sections by remember { mutableStateOf<List<DaySection>?>(null) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(Unit) {
        val all = catching { PhotosRepository.timeline(MediaFilter.ALL, graph.settings.current.showHidden) }.getOrDefault(emptyList())
        sections = PhotosViewModel.group(all.filter { it.handle !in exclude }, MediaFilter.ALL)
    }
    val toggle = { m: MediaItem -> selection = if (m.handle in selection) selection - m.handle else selection + m.handle }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selection.isEmpty()) stringResource(R.string.album_add_items)
                    else pluralStringResource(R.plurals.n_selected, selection.size, selection.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onPick(selection)
                        onDismiss()
                    },
                    enabled = selection.isNotEmpty(),
                ) { Text(stringResource(R.string.add)) }
            }
            val s = sections
            when {
                s == null -> LoadingBox()
                s.isEmpty() -> EmptyState(R.drawable.ic_photo_library, stringResource(R.string.photos_empty_title))
                else -> MediaGrid(
                    sections = s,
                    columns = 4,
                    selection = selection,
                    onClick = toggle,
                    onLongClick = toggle,
                    onToggleDay = { day ->
                        val h = day.items.map { it.handle }
                        selection = if (h.all { it in selection }) selection - h.toSet() else selection + h
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumLinkDialog(link: String, onDismiss: () -> Unit) {
    if (!rememberDialogAllowed()) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = MegaGlassApp.graph
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_link) },
        title = { Text(stringResource(R.string.album_share_link)) },
        text = {
            Text(
                link,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { graph.opener.shareText(link) }) { Text(stringResource(R.string.action_send)) }
                Button(onClick = {
                    copySensitive(context, link)
                    graph.messenger.show(resources.getString(R.string.copied))
                }) { Text(stringResource(R.string.copy_link)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
