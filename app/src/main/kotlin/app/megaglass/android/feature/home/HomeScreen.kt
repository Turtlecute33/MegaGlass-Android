package app.megaglass.android.feature.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.RecentBucket
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.drive.NodeActionsHost
import app.megaglass.android.feature.drive.NodeContext
import app.megaglass.android.feature.drive.rememberNodeActions
import app.megaglass.android.ui.components.Avatar
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeRow
import app.megaglass.android.ui.components.NodeThumbnail
import app.megaglass.android.ui.components.UsageBar
import app.megaglass.android.ui.nav.Category
import app.megaglass.android.ui.nav.Favourites
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.Offline
import app.megaglass.android.ui.nav.Rubbish
import app.megaglass.android.ui.nav.Search
import app.megaglass.android.ui.nav.Tab
import app.megaglass.android.ui.theme.AppTheme
import nz.mega.sdk.MegaApiJava
import java.util.Calendar

private class QuickAccess(@StringRes val label: Int, @DrawableRes val icon: Int, val shape: Shape, val color: Color, val open: () -> Unit)

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val graph = MegaGlassApp.graph
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val actions = rememberNodeActions(NodeContext.CLOUD)
    val refresh = rememberPullToRefreshState()

    val extra = AppTheme.extra
    val quick = listOf(
        QuickAccess(R.string.favourites, R.drawable.ic_star_fill, MaterialShapes.Cookie9Sided.toShape(), extra.warning) { nav.navigate(Favourites) },
        QuickAccess(R.string.documents, R.drawable.ic_description, MaterialShapes.Square.toShape(), extra.document) {
            nav.navigate(Category(MegaApiJava.FILE_TYPE_ALL_DOCS))
        },
        QuickAccess(R.string.audio, R.drawable.ic_music_note_fill, MaterialShapes.Circle.toShape(), extra.audio) {
            nav.navigate(Category(MegaApiJava.FILE_TYPE_AUDIO))
        },
        QuickAccess(R.string.videos, R.drawable.ic_movie, MaterialShapes.Pill.toShape(), extra.video) {
            nav.navigate(Category(MegaApiJava.FILE_TYPE_VIDEO))
        },
        QuickAccess(R.string.offline, R.drawable.ic_offline_pin_fill, MaterialShapes.Clover4Leaf.toShape(), extra.success) { nav.navigate(Offline) },
        QuickAccess(R.string.rubbish_bin, R.drawable.ic_delete, MaterialShapes.Slanted.toShape(), extra.other) { nav.navigate(Rubbish()) },
    )
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        state = refresh,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = refresh,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "top") {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    SearchPill(onClick = { nav.navigate(Search()) }, onAvatar = { nav.switchTab(Tab.MENU) }, name = state.firstName)
                    Spacer(Modifier.height(24.dp))
                    Text(greeting(state.firstName), style = MaterialTheme.typography.headlineMedium)
                }
            }
            if (state.storageFraction >= 0.8f) {
                item(key = "storage") { StorageWarning(state.storageUsed, state.storageMax, state.storageFraction) }
            }
            item(key = "quick") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(quick, key = { it.label }) { q -> QuickTile(q) }
                }
            }
            item(key = "recentsTitle") {
                Text(
                    stringResource(R.string.recents),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            val recents = state.recents
            when {
                recents == null -> item(key = "loading") { LoadingBox(Modifier.height(240.dp)) }
                recents.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        R.drawable.ic_history,
                        stringResource(R.string.no_recents),
                        message = stringResource(R.string.no_recents_hint),
                        modifier = Modifier.height(320.dp),
                    )
                }
                else -> {
                    var lastDay = ""
                    recents.forEach { bucket ->
                        val day = dayLabel(bucket.timestamp, today, yesterday)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day:$day:${bucket.id}") {
                                Text(
                                    day,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        item(key = "bucket:${bucket.id}:${bucket.timestamp}") {
                            BucketView(
                                bucket = bucket,
                                offline = offline,
                                onOpen = { item -> nav.openNode(item, MediaSource.Folder(bucket.parentHandle)) },
                                onMore = actions::openMenu,
                                onFolder = { nav.navigate(Folder(bucket.parentHandle)) },
                            )
                        }
                    }
                }
            }
        }
    }
    NodeActionsHost(actions)
}

@Composable
private fun SearchPill(onClick: () -> Unit, onAvatar: () -> Unit, name: String) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val api = MegaGlassApp.graph.mega.api
            Box(Modifier.clip(CircleShape).clickable(onClick = onAvatar)) {
                Avatar(remember { api.myUserHandle }, name.ifEmpty { api.myEmail ?: "?" }, size = 40.dp)
            }
        }
    }
}

@Composable
private fun greeting(name: String): String {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val base = stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
    )
    return if (name.isBlank()) base else "$base, $name"
}

@Composable
private fun QuickTile(q: QuickAccess) {
    val color = q.color
    Column(
        Modifier.width(76.dp).clip(MaterialTheme.shapes.large).clickable(onClick = q.open).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp).background(color.copy(alpha = 0.18f), q.shape), contentAlignment = Alignment.Center) {
            Ic(q.icon, null, Modifier.size(28.dp), color)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(q.label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StorageWarning(used: Long, max: Long, fraction: Float) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (fraction >= 0.95f) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(if (fraction >= 1f) R.string.storage_full else R.string.storage_almost_full),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.storage_used_of, Format.size(used), Format.size(max)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            UsageBar(fraction)
        }
    }
}

@Composable
private fun BucketView(
    bucket: RecentBucket,
    offline: Set<Long>,
    onOpen: (NodeItem) -> Unit,
    onMore: (NodeItem) -> Unit,
    onFolder: () -> Unit,
) {
    val count = bucket.nodes.size
    val headline = when {
        bucket.isUpdate -> pluralStringResource(R.plurals.recent_updated, count, count)
        else -> pluralStringResource(R.plurals.recent_added, count, count)
    }
    if (count == 1) {
        NodeRow(
            item = bucket.nodes.first(),
            onClick = { onOpen(bucket.nodes.first()) },
            onMore = { onMore(bucket.nodes.first()) },
            offline = bucket.nodes.first().handle in offline,
            subtitle = listOfNotNull(bucket.parentName.ifEmpty { null }, bucket.userEmail, Format.date(bucket.timestamp)).joinToString(" · "),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            onClick = onFolder,
            leadingContent = {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie6Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Ic(if (bucket.isMedia) R.drawable.ic_photo_library else R.drawable.ic_folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            },
            supportingContent = {
                Text(
                    listOfNotNull(bucket.parentName.ifEmpty { null }, bucket.userEmail, Format.date(bucket.timestamp)).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) { Text(headline) }
        if (bucket.isMedia) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(bucket.nodes.take(30), key = { it.handle }) { item ->
                    Box(Modifier.clip(MaterialTheme.shapes.medium).clickable { onOpen(item) }) {
                        NodeThumbnail(item, size = 88.dp, cornerFraction = 0.18f)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            bucket.nodes.take(4).forEach { item ->
                NodeRow(item, onClick = { onOpen(item) }, onMore = { onMore(item) }, offline = item.handle in offline,
                    modifier = Modifier.padding(start = 24.dp))
            }
            if (count > 4) {
                Text(
                    stringResource(R.string.and_n_more, count - 4),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onFolder).padding(start = 40.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private fun dayLabel(epochSeconds: Long, today: String, yesterday: String): String {
    val ms = epochSeconds * 1000
    return when {
        android.text.format.DateUtils.isToday(ms) -> today
        android.text.format.DateUtils.isToday(ms + android.text.format.DateUtils.DAY_IN_MILLIS) -> yesterday
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(ms))
    }
}
