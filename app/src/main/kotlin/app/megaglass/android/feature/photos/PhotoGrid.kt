package app.megaglass.android.feature.photos

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.megaglass.android.R
import app.megaglass.android.core.media.NodeImage
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.FileTypeIcon
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.theme.AppTheme
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

const val MIN_COLUMNS = 3
const val MAX_COLUMNS = 7

/**
 * Pinch to change the number of grid columns, one step per gesture threshold.
 * Only gestures with two or more pointers are taken, so scrolling is not affected.
 */
fun Modifier.pinchToChangeColumns(columns: () -> Int, onChange: (Int) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var current = columns()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (zoom > 1.25f && current > MIN_COLUMNS) {
                        current--
                        onChange(current)
                        zoom = 1f
                    } else if (zoom < 0.8f && current < MAX_COLUMNS) {
                        current++
                        onChange(current)
                        zoom = 1f
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    },
)

@Composable
fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.photos_today)
        today.minusDays(1) -> stringResource(R.string.photos_yesterday)
        else -> {
            val skeleton = if (day.year == today.year) "EEEMMMd" else "EEEMMMdyyyy"
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
            val date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
            java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        }
    }
}

/** A square photo or video cell. */
@Composable
fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "cellScale")
    val context = LocalContext.current
    val shape = RoundedCornerShape(if (selected) 16.dp else 4.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxSize().scale(scale).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (item.node.hasThumbnail) {
                SubcomposeAsyncImage(
                    model = remember(item.handle) { ImageRequest.Builder(context).data(NodeImage(item.handle)).build() },
                    contentDescription = item.node.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FileTypeIcon(item.node.type) } },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FileTypeIcon(item.node.type) }
            }
            if (item.node.isVideo) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ic(R.drawable.ic_play_arrow_fill, null, Modifier.size(12.dp), Color.White)
                    if (item.node.duration > 0) {
                        Spacer(Modifier.width(2.dp))
                        Text(Format.duration(item.node.duration.toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            if (item.node.isFavourite) {
                Ic(R.drawable.ic_favorite_fill, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp), Color.White)
            }
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Ic(R.drawable.ic_check, null, Modifier.size(14.dp), MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun DayHeader(section: DaySection, selecting: Boolean, allSelected: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dayLabel(section.day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (selecting) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (allSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .combinedClickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    if (allSelected) Ic(R.drawable.ic_check, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/**
 * Day-grouped media grid with sticky day headers.
 * [header] items span the full width above the first day.
 */
@Composable
fun MediaGrid(
    sections: List<DaySection>,
    columns: Int,
    selection: Set<Long>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onToggleDay: ((DaySection) -> Unit)?,
    modifier: Modifier = Modifier,
    state: LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 120.dp),
    onColumnsChange: ((Int) -> Unit)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val selecting = selection.isNotEmpty()
    val latestColumns by rememberUpdatedState(columns)
    val latestOnChange by rememberUpdatedState(onColumnsChange)
    val gridModifier = if (onColumnsChange != null) {
        modifier.pinchToChangeColumns({ latestColumns }) { latestOnChange?.invoke(it) }
    } else modifier
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = gridModifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        header?.invoke(this)
        sections.forEach { section ->
            stickyHeader(key = "day-${section.day}", contentType = "day") {
                DayHeader(
                    section = section,
                    selecting = selecting && onToggleDay != null,
                    allSelected = section.items.all { it.handle in selection },
                    onToggle = { onToggleDay?.invoke(section) },
                )
            }
            items(section.items, key = { it.handle }, contentType = { "media" }) { item ->
                MediaCell(
                    item = item,
                    selected = item.handle in selection,
                    selecting = selecting,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

/** A full-width item for grid headers. */
fun LazyGridScope.fullWidthItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") { content() }
}

/** Album cover tile with name and count. */
@Composable
fun AlbumTile(album: AlbumInfo, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    androidx.compose.foundation.layout.Column(modifier.combinedClickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (album.cover != nz.mega.sdk.MegaApiJava.INVALID_HANDLE) {
                SubcomposeAsyncImage(
                    model = remember(album.cover) { ImageRequest.Builder(context).data(NodeImage(album.cover)).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = { AlbumPlaceholder(album) },
                )
            } else {
                AlbumPlaceholder(album)
            }
            if (album.isExported) {
                Ic(
                    R.drawable.ic_link, stringResource(R.string.link),
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)).padding(4.dp).size(16.dp),
                    Color.White,
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.n_items, album.count, album.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun AlbumPlaceholder(album: AlbumInfo) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Ic(
            when (album.id) {
                app.megaglass.android.ui.nav.Album.FAVOURITES -> R.drawable.ic_favorite
                else -> R.drawable.ic_photo_album
            },
            null,
            Modifier.size(40.dp),
            AppTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
fun albumTitle(album: AlbumInfo): String = when (album.id) {
    app.megaglass.android.ui.nav.Album.FAVOURITES -> stringResource(R.string.album_favourites)
    app.megaglass.android.ui.nav.Album.GIF -> stringResource(R.string.album_gif)
    app.megaglass.android.ui.nav.Album.RAW -> stringResource(R.string.album_raw)
    else -> album.name
}
