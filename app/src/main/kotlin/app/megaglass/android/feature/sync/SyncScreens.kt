package app.megaglass.android.feature.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.storage.displayName
import app.megaglass.android.feature.drive.FolderPickerDialog
import app.megaglass.android.feature.settings.NoticeCard
import app.megaglass.android.feature.settings.SettingsScaffold
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.rememberDialogAllowed
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Add-sync flow: local folder first, then the cloud folder, then the name. */
private sealed interface AddStep {
    data class PickCloud(val local: String) : AddStep
    data class Confirm(val local: String, val remote: Long) : AddStep
}

@Composable
fun SyncsScreen(vm: SyncViewModel = viewModel()) {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()
    val storage = rememberStorageAccess()
    var step by remember { mutableStateOf<AddStep?>(null) }
    var removing by remember { mutableStateOf<SyncItem?>(null) }
    var stalls by remember { mutableStateOf<List<StallItem>?>(null) }
    val scope = rememberCoroutineScope()

    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = graph.files.realPath(uri)
        if (path == null) graph.messenger.show(resources.getString(if (graph.files.hasAllFilesAccess) R.string.folder_not_local else R.string.sync_needs_access))
        else step = AddStep.PickCloud(path)
    }
    val startAdd = {
        storage.ensure {
            if (storage.granted) {
                graph.lock.expectReturn()
                pickLocal.launch(null)
            } else graph.messenger.show(resources.getString(R.string.sync_needs_access))
        }
    }
    // All-files access can change in the system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }

    val syncs = state.syncs
    SettingsScaffold(
        title = stringResource(R.string.menu_sync),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = startAdd,
                icon = { Ic(R.drawable.ic_add) },
                text = { Text(stringResource(R.string.sync_add)) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) {
        item(key = "info") {
            NoticeCard(R.drawable.ic_cloud_sync, stringResource(R.string.sync_info), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (state.accessMissing) {
            item(key = "access") {
                Card(
                    onClick = { storage.ensure { vm.load() } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_sd_card, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.sync_access_paused),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (state.stalled) {
            item(key = "stalled") {
                Card(
                    onClick = { scope.launch { stalls = vm.stalls() } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_sync_problem, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.sync_stalled),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Ic(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        when {
            syncs == null -> item { Box(Modifier.fillMaxWidth().height(240.dp)) { LoadingBox() } }
            syncs.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    EmptyState(
                        R.drawable.ic_sync,
                        stringResource(R.string.sync_empty_title),
                        message = stringResource(R.string.sync_empty_text),
                    )
                }
            }
            else -> items(syncs, key = { it.backupId }) { item ->
                SyncCard(
                    item = item,
                    activity = state.activity[item.backupId],
                    onOpenCloud = { nav.navigate(Folder(item.remoteHandle)) },
                    onPause = { vm.pause(item) },
                    // Resume only with all-files access (see Mega.holdSyncsWithoutAccess).
                    onResume = { if (item.needsAccess) storage.ensure { vm.resume(item) } else vm.resume(item) },
                    onRemove = { removing = item },
                )
            }
        }
        item(key = "fabSpace") { Spacer(Modifier.height(88.dp)) }
    }

    when (val s = step) {
        is AddStep.PickCloud -> FolderPickerDialog(
            title = stringResource(R.string.sync_pick_cloud),
            confirmLabel = stringResource(R.string.sync_use_folder),
            onPick = { remote -> step = AddStep.Confirm(s.local, remote) },
            onDismiss = { if (step is AddStep.PickCloud) step = null },
        )
        is AddStep.Confirm -> {
            var name by remember(s) { mutableStateOf(File(s.local).name) }
            var busy by remember(s) { mutableStateOf(false) }
            var error by remember(s) { mutableStateOf<String?>(null) }
            // The SDK lock: not on the main thread.
            val remotePath by produceState("", s) { value = withContext(Dispatchers.IO) { displayName(graph.mega.api.getNodePathByHandle(s.remote).orEmpty()) } }
            if (rememberDialogAllowed()) AlertDialog(
                onDismissRequest = { if (!busy) step = null },
                icon = { Ic(R.drawable.ic_cloud_sync) },
                title = { Text(stringResource(R.string.sync_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PathRow(R.drawable.ic_phone_android, s.local)
                        PathRow(R.drawable.ic_cloud, remotePath)
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                error = vm.add(s.local, s.remote, name.trim())
                                busy = false
                                if (error == null) step = null
                            }
                        },
                        enabled = name.isNotBlank() && !busy,
                    ) {
                        if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text(stringResource(R.string.sync_start))
                    }
                },
                dismissButton = { TextButton(onClick = { step = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
            )
        }
        null -> Unit
    }

    removing?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.sync_remove_title),
            text = stringResource(R.string.sync_remove_text),
            confirmLabel = stringResource(R.string.sync_remove),
            destructive = true,
            icon = R.drawable.ic_sync_disabled,
            onConfirm = { vm.remove(item) },
            onDismiss = { removing = null },
        )
    }

    stalls?.takeIf { rememberDialogAllowed() }?.let { list ->
        ModalBottomSheet(onDismissRequest = { stalls = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.sync_stalled_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) Text(stringResource(R.string.sync_stalled_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { stall ->
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(stall.reason, style = MaterialTheme.typography.titleSmall)
                        if (stall.localPath.isNotEmpty()) PathRow(R.drawable.ic_phone_android, stall.localPath)
                        if (stall.cloudPath.isNotEmpty()) PathRow(R.drawable.ic_cloud, stall.cloudPath)
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PathRow(icon: Int, path: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Ic(icon, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
private fun SyncCard(
    item: SyncItem,
    activity: SyncActivity?,
    onOpenCloud: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val busy = activity != null && (activity.scanning || activity.syncing)
    val (statusText, statusColor) = when {
        item.error != null -> item.error to MaterialTheme.colorScheme.error
        item.state == SyncRunState.PAUSED -> stringResource(R.string.sync_paused) to MaterialTheme.colorScheme.onSurfaceVariant
        item.state == SyncRunState.LOADING -> stringResource(R.string.sync_loading) to MaterialTheme.colorScheme.onSurfaceVariant
        item.state == SyncRunState.DISABLED -> stringResource(R.string.sync_disabled) to MaterialTheme.colorScheme.error
        activity?.scanning == true -> stringResource(R.string.sync_scanning) to MaterialTheme.colorScheme.primary
        activity?.syncing == true ->
            stringResource(R.string.sync_syncing, activity.uploads, activity.downloads) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.sync_up_to_date) to AppTheme.extra.success
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(
                    when {
                        item.error != null || item.state == SyncRunState.DISABLED -> R.drawable.ic_sync_problem
                        item.state == SyncRunState.PAUSED -> R.drawable.ic_pause_circle
                        else -> R.drawable.ic_cloud_sync
                    },
                    null,
                    tint = statusColor,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                Box {
                    IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (item.state == SyncRunState.PAUSED || item.state == SyncRunState.DISABLED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_resume)) },
                                leadingIcon = { Ic(R.drawable.ic_play_arrow) },
                                onClick = { menu = false; onResume() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_pause)) },
                                leadingIcon = { Ic(R.drawable.ic_pause) },
                                onClick = { menu = false; onPause() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_open_cloud)) },
                            leadingIcon = { Ic(R.drawable.ic_cloud) },
                            onClick = { menu = false; onOpenCloud() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Ic(R.drawable.ic_sync_disabled, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 4.dp)) {
                PathRow(R.drawable.ic_phone_android, item.localPath)
                PathRow(R.drawable.ic_cloud, item.remotePath)
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
