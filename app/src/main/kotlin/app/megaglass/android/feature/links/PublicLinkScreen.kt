package app.megaglass.android.feature.links

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.Crumb
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.drive.Breadcrumbs
import app.megaglass.android.feature.drive.FolderPickerDialog
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeCollection
import app.megaglass.android.ui.components.NoLearning
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.PublicLink
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun PublicLinkScreen(route: PublicLink) {
    val vm: PublicLinkViewModel = viewModel(key = route.url) { PublicLinkViewModel(route.url) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val storage = rememberStorageAccess()
    var picking by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.openLocal.collect { nav.openLocal(it) } }

    val inFolder = ui is LinkUi.FolderLink
    val selecting = inFolder && folder.selection.isNotEmpty()
    BackHandler(enabled = inFolder && (selecting || folder.stack.isNotEmpty())) {
        if (selecting) vm.clearSelection() else vm.up()
    }

    val title = when (val u = ui) {
        is LinkUi.FileLink -> u.item.name
        is LinkUi.FolderLink -> folder.stack.lastOrNull()?.name ?: u.rootName
        is LinkUi.AlbumLink -> u.name
        else -> stringResource(R.string.link_title)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.n_selected, folder.selection.size, folder.selection.size)) },
                    navigationIcon = { IconAction(R.drawable.ic_close, stringResource(R.string.close), vm::clearSelection) },
                    actions = { IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), vm::selectAll) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ui is LinkUi.FolderLink || ui is LinkUi.AlbumLink) {
                                Text(
                                    stringResource(if (ui is LinkUi.AlbumLink) R.string.link_album else R.string.link_folder),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = { BackButton { if (inFolder && folder.stack.isNotEmpty()) vm.up() else nav.back() } },
                    actions = {
                        if (ui is LinkUi.FolderLink || ui is LinkUi.AlbumLink) {
                            IconAction(R.drawable.ic_download, stringResource(R.string.link_download_all), { storage.ensure { vm.download() } })
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = (ui is LinkUi.FolderLink && !selecting) || ui is LinkUi.AlbumLink,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.link_import)) },
                    icon = { Ic(R.drawable.ic_cloud_upload) },
                    onClick = { picking = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val u = ui) {
                LinkUi.Loading -> LoadingBox()
                is LinkUi.Fetching -> FetchingFolder(u.progress)
                is LinkUi.NeedPassword -> SecretPrompt(
                    title = stringResource(R.string.link_password_title),
                    text = stringResource(R.string.link_password_text),
                    label = stringResource(R.string.link_password),
                    wrong = u.wrong,
                    wrongText = stringResource(R.string.link_password_wrong),
                    password = true,
                    onSubmit = vm::submitPassword,
                )
                is LinkUi.NeedKey -> SecretPrompt(
                    title = stringResource(R.string.link_key_title),
                    text = stringResource(R.string.link_key_text),
                    label = stringResource(R.string.link_key),
                    wrong = u.wrong,
                    wrongText = stringResource(R.string.link_key_wrong),
                    password = false,
                    onSubmit = vm::submitKey,
                )
                is LinkUi.Failed -> EmptyState(
                    icon = u.icon,
                    title = stringResource(R.string.link_unavailable),
                    message = u.message,
                    actionLabel = stringResource(R.string.close),
                    onAction = { nav.back() },
                )
                is LinkUi.FileLink -> FileCard(
                    item = u.item,
                    vm = vm,
                    preparing = u.item.id in preparing,
                    onImport = { picking = true },
                    onDownload = { storage.ensure { vm.download() } },
                )
                is LinkUi.AlbumLink -> AlbumGrid(u, vm, preparing)
                is LinkUi.FolderLink -> {
                    Column(Modifier.fillMaxSize()) {
                        val crumbs = listOf(Crumb(-1, u.rootName)) + folder.stack.map { Crumb(it.handle, it.name) }
                        Breadcrumbs(crumbs, u.rootName, onClick = { crumb -> vm.goTo(crumbs.indexOf(crumb)) })
                        val items = folder.items
                        when {
                            items == null -> LoadingBox()
                            items.isEmpty() -> EmptyState(R.drawable.ic_folder_open, stringResource(R.string.empty_folder_title))
                            else -> NodeCollection(
                                items = items,
                                grid = MegaGlassApp.graph.settings.current.gridView,
                                selection = folder.selection,
                                folderLink = true,
                                onClick = { item ->
                                    when {
                                        selecting -> vm.toggle(item)
                                        item.isFolder -> vm.enter(item)
                                        PublicLinkViewModel.opensInViewer(item) ->
                                            nav.openNode(item, MediaSource.FolderLink(vm.currentParent() ?: item.parentHandle), folderLink = true)
                                        else -> vm.openCached(item.handle, Purpose.OPEN)
                                    }
                                },
                                onLongClick = vm::toggle,
                                onMore = null,
                                contentPadding = PaddingValues(bottom = 120.dp + padding.calculateBottomPadding()),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = selecting,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        HorizontalFloatingToolbar(
                            expanded = true,
                            modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp),
                            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                        ) {
                            IconButton(onClick = { storage.ensure { vm.download() } }) {
                                Ic(R.drawable.ic_download, stringResource(R.string.action_download))
                            }
                            IconButton(onClick = { picking = true }) {
                                Ic(R.drawable.ic_cloud_upload, stringResource(R.string.link_import))
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        FolderPickerDialog(
            title = stringResource(R.string.link_import_title),
            confirmLabel = stringResource(R.string.link_import_here),
            onPick = vm::importTo,
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FetchingFolder(progress: Float?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingIndicator(Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.link_loading_folder), style = MaterialTheme.typography.titleMedium)
        if (progress != null) {
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
        }
    }
}

@Composable
private fun SecretPrompt(
    title: String,
    text: String,
    label: String,
    wrong: Boolean,
    wrongText: String,
    password: Boolean,
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie7Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) { Ic(if (password) R.drawable.ic_lock else R.drawable.ic_key, null, Modifier.size(44.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        // Ascii keeps the key visible and the layout as it is; the keyboard must not learn the key.
        NoLearning {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) { { Text(wrongText) } } else null,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSubmit(value) }),
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(value) },
            enabled = value.isNotBlank(),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.link_unlock)) }
    }
}

/** Thumbnail or preview of a public file, or its type icon. */
@Composable
private fun PublicImage(item: PublicItem, vm: PublicLinkViewModel, large: Boolean, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp) {
    val file by produceState<File?>(null, item.id, large) { value = if (item.hasImage) vm.image(item.id, large) else null }
    val f = file
    if (f != null) {
        AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            FileTypeIcon(item.type, size = iconSize)
        }
    }
}

@Composable
private fun FileCard(item: PublicItem, vm: PublicLinkViewModel, preparing: Boolean, onImport: () -> Unit, onDownload: () -> Unit) {
    val viewable = item.type in setOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF, FileType.TEXT, FileType.CODE)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Box(contentAlignment = Alignment.Center) {
            PublicImage(
                item, vm, large = true,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 360.dp).aspectRatio(4f / 3f)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(enabled = !preparing) { vm.openCached(item.id, if (viewable) Purpose.PREVIEW else Purpose.OPEN) },
                iconSize = 120.dp,
            )
            if (item.type == FileType.VIDEO && item.hasImage) {
                Ic(R.drawable.ic_play_circle_fill, null, Modifier.size(64.dp), Color.White)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(item.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(Format.size(item.size), Format.dateTime(item.modified)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Ic(R.drawable.ic_cloud_upload)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_import))
            }
            FilledTonalButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Ic(R.drawable.ic_download)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_download))
            }
            OutlinedButton(
                onClick = { vm.openCached(item.id, if (viewable) Purpose.PREVIEW else Purpose.OPEN) },
                enabled = !preparing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Ic(if (viewable) R.drawable.ic_visibility else R.drawable.ic_open_in_new)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (viewable) R.string.link_view else R.string.action_open_with))
            }
            AnimatedVisibility(preparing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.link_preparing), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_encrypted, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  " + stringResource(R.string.link_e2ee),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AlbumGrid(album: LinkUi.AlbumLink, vm: PublicLinkViewModel, preparing: Set<Long>) {
    if (album.items.isEmpty()) {
        EmptyState(R.drawable.ic_photo_album, stringResource(R.string.link_album_empty))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(album.items, key = { it.id }) { item ->
            Surface(shape = MaterialTheme.shapes.small, modifier = Modifier.aspectRatio(1f)) {
                Box(contentAlignment = Alignment.Center) {
                    PublicImage(
                        item, vm, large = false,
                        modifier = Modifier.fillMaxSize().clickable { vm.openCached(item.id) },
                        iconSize = 48.dp,
                    )
                    if (item.type == FileType.VIDEO) Ic(R.drawable.ic_play_circle_fill, null, Modifier.size(32.dp), Color.White)
                    if (item.id in preparing) LoadingIndicator(Modifier.size(40.dp))
                }
            }
        }
    }
}
