package app.megaglass.android.feature.transfers

import app.megaglass.android.core.util.catching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.transfers.Direction
import app.megaglass.android.core.transfers.TransferItem
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.transfers.TransfersState
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun TransfersScreen() {
    val graph = MegaGlassApp.graph
    val transfers = graph.transfers
    val state by transfers.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val paused = state.downloadsPaused && state.uploadsPaused

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.transfers)) },
                    navigationIcon = { BackButton { nav.back() } },
                    actions = {
                        if (!state.isIdle) {
                            IconAction(
                                if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                                stringResource(if (paused) R.string.resume_all else R.string.pause_all),
                                { transfers.pauseAll(!paused) },
                            )
                        }
                        Box {
                            IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cancel_all)) },
                                    enabled = !state.isIdle,
                                    leadingIcon = { Ic(R.drawable.ic_cancel) },
                                    onClick = { menu = false; confirmCancel = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_finished)) },
                                    enabled = state.finished.isNotEmpty(),
                                    leadingIcon = { Ic(R.drawable.ic_cleaning_services) },
                                    onClick = { menu = false; transfers.clearHistory() },
                                )
                            }
                        }
                    },
                )
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.transfers_active, state.active.size)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.transfers_finished, state.finished.size)) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (tab == 0) {
                if (state.isIdle) {
                    EmptyState(R.drawable.ic_swap_vert, stringResource(R.string.no_active_transfers))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                        item(key = "summary") { Summary(state) }
                        items(state.active, key = { it.tag }) { item ->
                            ActiveRow(
                                item,
                                onPause = { transfers.pause(item.tag, item.status != TransferStatus.PAUSED) },
                                onCancel = { transfers.cancel(item.tag) },
                                onTop = { transfers.moveToFirst(item.tag) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            } else {
                if (state.finished.isEmpty()) {
                    EmptyState(R.drawable.ic_done_all, stringResource(R.string.no_finished_transfers))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                        items(state.finished, key = { "${it.tag}:${it.finishedAt}" }) { item ->
                            FinishedRow(
                                item,
                                onOpen = {
                                    when {
                                        item.status != TransferStatus.COMPLETED -> Unit
                                        item.direction == Direction.UPLOAD -> nav.navigate(NodeInfo(item.nodeHandle))
                                        item.isFolder -> Unit
                                        File(item.localPath).exists() -> nav.openLocal(File(item.localPath))
                                        else -> graph.messenger.show(graph.context.getString(R.string.file_moved_or_deleted))
                                    }
                                },
                                onRetry = {
                                    scope.launch {
                                        if (!catching { transfers.retry(item) }.getOrDefault(false)) {
                                            graph.messenger.show(graph.context.getString(R.string.retry_not_possible))
                                        }
                                    }
                                },
                                onRemove = { transfers.removeFromHistory(item) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.cancel_all_title),
            text = stringResource(R.string.cancel_all_text),
            confirmLabel = stringResource(R.string.cancel_all),
            destructive = true,
            onConfirm = transfers::cancelAll,
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun Summary(state: TransfersState) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedBlock(R.drawable.ic_arrow_downward, state.downloadSpeed, state.downloads, Modifier.weight(1f))
                SpeedBlock(R.drawable.ic_arrow_upward, state.uploadSpeed, state.uploads, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                Format.size(state.transferredBytes) + " / " + Format.size(state.totalBytes),
                style = MaterialTheme.typography.labelLarge,
            )
            if (state.overQuota) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.transfer_over_quota), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SpeedBlock(icon: Int, speed: Long, count: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) { Ic(icon, null) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(Format.speed(speed), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.n_transfers, count), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TransferIcon(item: TransferItem) {
    Box {
        FileTypeIcon(FileType.of(item.name, item.isFolder), size = 44.dp)
        Box(
            Modifier.size(20.dp).align(Alignment.BottomEnd).offset(4.dp, 4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Ic(
                if (item.direction == Direction.UPLOAD) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward,
                null, Modifier.size(14.dp), MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ActiveRow(item: TransferItem, onPause: () -> Unit, onCancel: () -> Unit, onTop: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier,
        leadingContent = { TransferIcon(item) },
        supportingContent = {
            Column {
                Spacer(Modifier.height(6.dp))
                if (item.status == TransferStatus.ACTIVE) {
                    LinearWavyProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(statusText(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Row {
                IconAction(
                    if (item.status == TransferStatus.PAUSED) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                    stringResource(if (item.status == TransferStatus.PAUSED) R.string.resume else R.string.pause),
                    onPause,
                )
                Box {
                    IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_to_top)) }, leadingIcon = { Ic(R.drawable.ic_north_east) },
                            onClick = { menu = false; onTop() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.cancel_transfer)) }, leadingIcon = { Ic(R.drawable.ic_cancel) },
                            onClick = { menu = false; onCancel() })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
}

@Composable
private fun statusText(item: TransferItem): String = when (item.status) {
    TransferStatus.QUEUED -> stringResource(R.string.status_queued)
    TransferStatus.PAUSED -> stringResource(R.string.status_paused) + " · " + Format.size(item.transferred) + " / " + Format.size(item.total)
    TransferStatus.RETRYING -> stringResource(R.string.status_retrying)
    TransferStatus.COMPLETING -> stringResource(R.string.status_completing)
    else -> buildString {
        append(Format.size(item.transferred)).append(" / ").append(Format.size(item.total))
        if (item.speed > 0) {
            append(" · ").append(Format.speed(item.speed))
            val eta = Format.eta(item.total - item.transferred, item.speed)
            if (eta.isNotEmpty()) append(" · ").append(eta)
        }
        if (item.isFolder && item.fileCount > 0) {
            append(" · ").append(pluralStringResource(R.plurals.n_files, item.fileCount.toInt(), item.fileCount.toInt()))
        }
    }
}

@Composable
private fun FinishedRow(item: TransferItem, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, tint) = when (item.status) {
        TransferStatus.COMPLETED -> R.drawable.ic_check_circle_fill to AppTheme.extra.success
        TransferStatus.CANCELLED -> R.drawable.ic_cancel to MaterialTheme.colorScheme.onSurfaceVariant
        else -> R.drawable.ic_error to MaterialTheme.colorScheme.error
    }
    ListItem(
        onClick = onOpen,
        modifier = modifier,
        leadingContent = { TransferIcon(item) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(icon, null, Modifier.size(16.dp), tint)
                Spacer(Modifier.width(6.dp))
                Text(
                    item.error ?: (Format.size(item.total) + " · " + Format.date(item.finishedAt / 1000)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (item.status != TransferStatus.COMPLETED) IconAction(R.drawable.ic_replay, stringResource(R.string.retry), onRetry)
                IconAction(R.drawable.ic_close, stringResource(R.string.remove_from_list), onRemove)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
}
