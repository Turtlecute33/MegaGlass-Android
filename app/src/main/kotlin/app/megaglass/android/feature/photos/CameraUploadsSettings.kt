package app.megaglass.android.feature.photos

import app.megaglass.android.core.util.catching

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.camera.CameraUploads
import app.megaglass.android.core.camera.CuStatus
import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.drive.FolderPickerDialog
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.components.rememberDialogAllowed
import app.megaglass.android.ui.nav.LocalNavigator
import kotlinx.coroutines.launch

@Composable
fun CameraUploadsSettingsScreen() {
    val graph = MegaGlassApp.graph
    val context = LocalContext.current
    val resources = LocalResources.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val status by CameraUploads.status.collectAsStateWithLifecycle()
    var askExisting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var folderHandle by remember { mutableStateOf<Long?>(null) }
    var folderVersion by remember { mutableIntStateOf(0) }
    val defaultFolder = stringResource(R.string.cu_default_folder)

    LaunchedEffect(folderVersion) {
        val h = catching { CameraUploads.currentFolder(graph.mega) }.getOrNull()
        folderHandle = h
        folderName = h?.let { graph.nodes.item(it)?.name }
    }

    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val images = result[Manifest.permission.READ_MEDIA_IMAGES] == true
        val videos = result[Manifest.permission.READ_MEDIA_VIDEO] == true
        val partial = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        when {
            images && (videos || !settings.cuIncludeVideos) -> askExisting = true
            partial -> graph.messenger.show(resources.getString(R.string.cu_partial_access))
            else -> graph.messenger.show(resources.getString(R.string.cu_permission_denied))
        }
    }
    val requestEnable = {
        if (CameraUploads.hasPermission(context, settings.cuIncludeVideos)) {
            askExisting = true
        } else {
            permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cu_title)) },
                navigationIcon = { BackButton { nav.back() } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (settings.cameraUploads) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_photo_camera, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cu_title), style = MaterialTheme.typography.titleLarge)
                        Text(statusText(status, settings.cameraUploads), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = settings.cameraUploads,
                        onCheckedChange = { on -> if (on) requestEnable() else update { it.copy(cameraUploads = false) } },
                    )
                }
            }
            if (settings.cameraUploads) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    FilledTonalButton(
                        onClick = { CameraUploads.uploadNow(context, settings) },
                        enabled = status !is CuStatus.Uploading && status != CuStatus.Scanning,
                    ) {
                        Ic(R.drawable.ic_sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cu_upload_now))
                    }
                }
            }

            SectionHeader(stringResource(R.string.cu_section_what))
            SwitchRow(R.drawable.ic_videocam, stringResource(R.string.cu_videos), null, settings.cuIncludeVideos) { on ->
                if (on && !CameraUploads.hasPermission(context, true)) {
                    permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
                }
                update { it.copy(cuIncludeVideos = on) }
            }
            SwitchRow(
                R.drawable.ic_badge,
                stringResource(R.string.cu_keep_names),
                stringResource(if (settings.cuKeepFileNames) R.string.cu_keep_names_on else R.string.cu_keep_names_off),
                settings.cuKeepFileNames,
            ) { on -> update { it.copy(cuKeepFileNames = on) } }

            SectionHeader(stringResource(R.string.cu_section_when))
            SwitchRow(R.drawable.ic_wifi, stringResource(R.string.cu_wifi_only), stringResource(R.string.cu_wifi_only_text), settings.cuWifiOnly) { on ->
                update { it.copy(cuWifiOnly = on) }
            }
            SwitchRow(R.drawable.ic_bolt, stringResource(R.string.cu_charging), null, settings.cuRequireCharging) { on ->
                update { it.copy(cuRequireCharging = on) }
            }

            SectionHeader(stringResource(R.string.cu_section_where))
            ListItem(
                onClick = { picking = true },
                leadingContent = { Ic(R.drawable.ic_folder) },
                supportingContent = { Text(folderName ?: stringResource(R.string.cu_folder_default_hint, defaultFolder)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) { Text(stringResource(R.string.cu_destination)) }
            ListItem(
                leadingContent = { Ic(R.drawable.ic_history) },
                supportingContent = {
                    Text(
                        if (settings.cuSince == 0L) stringResource(R.string.cu_existing_all)
                        else stringResource(R.string.cu_existing_since, Format.dateTime(settings.cuSince / 1000)),
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) { Text(stringResource(R.string.cu_existing_title)) }

            Text(
                stringResource(R.string.cu_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (askExisting) {
        ExistingMediaDialog(
            onConfirm = { all ->
                askExisting = false
                scope.launch {
                    graph.settings.update { it.copy(cameraUploads = true, cuSince = if (all) 0 else System.currentTimeMillis()) }
                    CameraUploads.uploadNow(context, graph.settings.current)
                }
            },
            onDismiss = { askExisting = false },
        )
    }
    if (picking) {
        FolderPickerDialog(
            title = stringResource(R.string.cu_destination),
            confirmLabel = stringResource(R.string.cu_use_folder),
            start = folderHandle,
            onPick = { handle ->
                scope.launch {
                    catching { CameraUploads.setFolder(graph.mega, handle) }
                        .onFailure { graph.messenger.show(it.message ?: "") }
                    folderVersion++
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun statusText(status: CuStatus, enabled: Boolean): String = when {
    !enabled -> stringResource(R.string.cu_off)
    status == CuStatus.NeedsPermission -> stringResource(R.string.cu_needs_permission)
    status == CuStatus.Scanning -> stringResource(R.string.cu_scanning)
    status is CuStatus.Uploading -> stringResource(R.string.cu_progress, status.done, status.total)
    status is CuStatus.Failed -> status.message
    status is CuStatus.Idle && status.lastRun > 0 -> stringResource(R.string.cu_last_run, Format.date(status.lastRun / 1000))
    else -> stringResource(R.string.cu_on)
}

@Composable
private fun SwitchRow(icon: Int, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        checked = checked,
        onCheckedChange = onChange,
        leadingContent = { Ic(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(title) }
}

@Composable
private fun ExistingMediaDialog(onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    if (!rememberDialogAllowed()) return
    var all by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_photo_library) },
        title = { Text(stringResource(R.string.cu_existing_title)) },
        text = {
            Column {
                listOf(true to R.string.cu_existing_all, false to R.string.cu_existing_new).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = all == value, onClick = { all = value }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = all == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(all) }) { Text(stringResource(R.string.cu_turn_on)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
