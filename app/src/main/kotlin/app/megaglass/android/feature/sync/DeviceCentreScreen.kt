package app.megaglass.android.feature.sync

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.nav.Backups
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaBackupInfo
import nz.mega.sdk.MegaError
import java.io.File

private enum class Health { OK, SYNCING, PAUSED, ATTENTION, INACTIVE, UNKNOWN }

@Immutable
private data class BackupEntry(
    val id: Long,
    val name: String,
    val type: Int,
    val root: Long,
    val localFolder: String,
    val health: Health,
    val progress: Int,
    /** Seconds since epoch of the last activity. */
    val lastActive: Long,
)

@Immutable
private data class DeviceEntry(
    val id: String,
    val name: String,
    val isThis: Boolean,
    val isMobile: Boolean,
    val entries: List<BackupEntry>,
) {
    val health: Health get() = when {
        entries.any { it.health == Health.ATTENTION } -> Health.ATTENTION
        entries.any { it.health == Health.SYNCING } -> Health.SYNCING
        entries.isNotEmpty() && entries.all { it.health == Health.PAUSED } -> Health.PAUSED
        entries.isNotEmpty() && entries.all { it.health == Health.INACTIVE } -> Health.INACTIVE
        entries.any { it.health == Health.OK } -> Health.OK
        else -> Health.UNKNOWN
    }
}

private fun MegaBackupInfo.health(): Health = when (state()) {
    MegaBackupInfo.BACKUP_STATE_PAUSE_UP, MegaBackupInfo.BACKUP_STATE_PAUSE_DOWN, MegaBackupInfo.BACKUP_STATE_PAUSE_FULL,
    MegaBackupInfo.BACKUP_STATE_TEMPORARY_DISABLED -> Health.PAUSED
    MegaBackupInfo.BACKUP_STATE_FAILED, MegaBackupInfo.BACKUP_STATE_DISABLED -> Health.ATTENTION
    else -> when (status()) {
        MegaBackupInfo.BACKUP_STATUS_UPTODATE -> Health.OK
        MegaBackupInfo.BACKUP_STATUS_SYNCING, MegaBackupInfo.BACKUP_STATUS_PENDING -> Health.SYNCING
        MegaBackupInfo.BACKUP_STATUS_STALLED -> Health.ATTENTION
        MegaBackupInfo.BACKUP_STATUS_INACTIVE -> Health.INACTIVE
        else -> Health.UNKNOWN
    }
}

/** Loads the backups and syncs of all devices of the account, grouped by device. */
private suspend fun loadDevices(): List<DeviceEntry> = withContext(Dispatchers.IO) {
    val api = MegaGlassApp.graph.mega.api
    val list = api.await { getBackupInfo(it) }.megaBackupInfoList ?: return@withContext emptyList()
    val infos = (0 until list.size()).map { list.get(it) }.filter { it.state() != MegaBackupInfo.BACKUP_STATE_DELETED }
    val thisDevice = api.deviceId.orEmpty()
    infos.groupBy { it.deviceId().orEmpty() }.map { (deviceId, items) ->
        val (code, request) = api.awaitResult { getDeviceName(deviceId, it) }
        val stored = if (code == MegaError.API_OK) request.name.orEmpty() else ""
        val isThis = deviceId == thisDevice
        val agent = items.firstNotNullOfOrNull { it.deviceUserAgent() }.orEmpty()
        DeviceEntry(
            id = deviceId,
            name = stored.ifEmpty { if (isThis) "${Build.MANUFACTURER} ${Build.MODEL}" else "" },
            isThis = isThis,
            isMobile = agent.contains("Android", true) || agent.contains("iOS", true) || agent.contains("iPhone", true) ||
                items.any { it.type() == MegaApiJava.BACKUP_TYPE_CAMERA_UPLOADS || it.type() == MegaApiJava.BACKUP_TYPE_MEDIA_UPLOADS },
            entries = items.map { b ->
                BackupEntry(
                    id = b.id(),
                    name = b.name().orEmpty().ifEmpty { File(b.localFolder().orEmpty()).name },
                    type = b.type(),
                    root = b.root(),
                    localFolder = b.localFolder().orEmpty(),
                    health = b.health(),
                    progress = b.progress(),
                    lastActive = maxOf(b.activityTs(), b.lastSync(), b.ts()),
                )
            }.sortedBy { it.name.lowercase() },
        )
    }.sortedWith(compareByDescending<DeviceEntry> { it.isThis }.thenBy { it.name.lowercase() })
}

@Composable
private fun healthLabel(h: Health, progress: Int = 0): Pair<String, Color> = when (h) {
    Health.OK -> stringResource(R.string.device_up_to_date) to AppTheme.extra.success
    Health.SYNCING -> (if (progress in 1..99) stringResource(R.string.device_syncing_percent, progress) else stringResource(R.string.device_syncing)) to
        MaterialTheme.colorScheme.primary
    Health.PAUSED -> stringResource(R.string.sync_paused) to MaterialTheme.colorScheme.onSurfaceVariant
    Health.ATTENTION -> stringResource(R.string.device_attention) to MaterialTheme.colorScheme.error
    Health.INACTIVE -> stringResource(R.string.device_inactive) to MaterialTheme.colorScheme.onSurfaceVariant
    Health.UNKNOWN -> stringResource(R.string.device_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun typeLabel(type: Int): String = stringResource(
    when (type) {
        MegaApiJava.BACKUP_TYPE_TWO_WAY_SYNC -> R.string.device_type_sync
        MegaApiJava.BACKUP_TYPE_UP_SYNC -> R.string.device_type_upload_sync
        MegaApiJava.BACKUP_TYPE_DOWN_SYNC -> R.string.device_type_download_sync
        MegaApiJava.BACKUP_TYPE_CAMERA_UPLOADS -> R.string.device_type_camera
        MegaApiJava.BACKUP_TYPE_MEDIA_UPLOADS -> R.string.device_type_media
        MegaApiJava.BACKUP_TYPE_BACKUP_UPLOAD -> R.string.device_type_backup
        else -> R.string.device_unknown
    },
)

@Composable
fun DeviceCentreScreen() {
    val nav = LocalNavigator.current
    val graph = MegaGlassApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var openDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<DeviceEntry?>(null) }

    LaunchedEffect(reload) {
        runCatching { loadDevices() }
            .onSuccess { devices = it; error = null }
            .onFailure { error = it.message }
        refreshing = false
    }

    val current = devices?.firstOrNull { it.id == openDevice }
    BackHandler(enabled = current != null) { openDevice = null }

    val unnamed = stringResource(R.string.device_unnamed)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(current?.name?.ifEmpty { unnamed } ?: stringResource(R.string.menu_device_centre), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = current?.let { d -> { Text(pluralStringResource(R.plurals.device_items, d.entries.size, d.entries.size)) } },
                navigationIcon = { BackButton { if (current != null) openDevice = null else nav.back() } },
                actions = {
                    if (current?.isThis == true) {
                        IconAction(R.drawable.ic_edit, stringResource(R.string.device_rename), { renaming = current })
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            val list = devices
            when {
                list == null && error != null -> ErrorState(error!!, onRetry = { reload++ })
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(R.drawable.ic_devices, stringResource(R.string.device_empty_title), message = stringResource(R.string.device_empty_text))
                else -> AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        if (targetState != null) (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
                        else (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    },
                    label = "device",
                ) { device ->
                    if (device == null) {
                        DeviceList(list, unnamed, padding) { openDevice = it.id }
                    } else {
                        BackupList(device, padding) { entry ->
                            if (entry.root == MegaApiJava.INVALID_HANDLE || graph.mega.api.getNodeByHandle(entry.root) == null) {
                                graph.messenger.show(context.getString(R.string.device_folder_missing))
                            } else if (entry.type == MegaApiJava.BACKUP_TYPE_BACKUP_UPLOAD) {
                                nav.navigate(Backups(entry.root))
                            } else {
                                nav.navigate(Folder(entry.root))
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { device ->
        TextInputDialog(
            title = stringResource(R.string.device_rename),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.save),
            initial = device.name,
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { graph.mega.api.await { setDeviceName(device.id, name, it) } }
                    }.onFailure { graph.messenger.show(it.message.orEmpty()) }
                    reload++
                }
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun DeviceList(devices: List<DeviceEntry>, unnamed: String, padding: PaddingValues, onOpen: (DeviceEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
        itemsIndexed(devices, key = { _, d -> d.id }) { i, d ->
            val (status, color) = healthLabel(d.health)
            SegmentedListItem(
                onClick = { onOpen(d) },
                shapes = ListItemDefaults.segmentedShapes(i, devices.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = ListItemDefaults.SegmentedGap / 2),
                leadingContent = {
                    Box(
                        Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie6Sided.toShape()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Ic(
                            if (d.isMobile) R.drawable.ic_phone_android else R.drawable.ic_computer,
                            null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                },
                supportingContent = {
                    Column {
                        Text(status, color = color)
                        Text(pluralStringResource(R.plurals.device_items, d.entries.size, d.entries.size))
                    }
                },
                trailingContent = if (d.isThis) {
                    { AssistChip(onClick = { onOpen(d) }, label = { Text(stringResource(R.string.device_this)) }) }
                } else null,
            ) { Text(d.name.ifEmpty { unnamed }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun BackupList(device: DeviceEntry, padding: PaddingValues, onOpen: (BackupEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
        itemsIndexed(device.entries, key = { _, e -> e.id }) { i, e ->
            val (status, color) = healthLabel(e.health, e.progress)
            SegmentedListItem(
                onClick = { onOpen(e) },
                shapes = ListItemDefaults.segmentedShapes(i, device.entries.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = ListItemDefaults.SegmentedGap / 2),
                leadingContent = {
                    Ic(
                        when (e.type) {
                            MegaApiJava.BACKUP_TYPE_CAMERA_UPLOADS, MegaApiJava.BACKUP_TYPE_MEDIA_UPLOADS -> R.drawable.ic_photo_camera
                            MegaApiJava.BACKUP_TYPE_BACKUP_UPLOAD -> R.drawable.ic_backup
                            else -> R.drawable.ic_cloud_sync
                        },
                        null,
                        tint = color,
                    )
                },
                overlineContent = { Text(typeLabel(e.type)) },
                supportingContent = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status, color = color)
                            if (e.lastActive > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text("· " + Format.date(e.lastActive))
                            }
                        }
                        if (e.localFolder.isNotEmpty()) {
                            Text(e.localFolder, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                trailingContent = { Ic(R.drawable.ic_chevron_right, null) },
            ) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
