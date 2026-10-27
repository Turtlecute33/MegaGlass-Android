package app.megaglass.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.media.NodeImage
import app.megaglass.android.core.media.UserAvatar
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.theme.AppTheme
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import nz.mega.sdk.MegaNode

@DrawableRes
fun FileType.icon(): Int = when (this) {
    FileType.FOLDER -> R.drawable.ic_folder_fill
    FileType.IMAGE -> R.drawable.ic_image
    FileType.VIDEO -> R.drawable.ic_movie
    FileType.AUDIO -> R.drawable.ic_music_note_fill
    FileType.PDF -> R.drawable.ic_picture_as_pdf
    FileType.DOCUMENT -> R.drawable.ic_description
    FileType.SPREADSHEET -> R.drawable.ic_table_chart
    FileType.PRESENTATION -> R.drawable.ic_slideshow
    FileType.ARCHIVE -> R.drawable.ic_folder_zip
    FileType.CODE -> R.drawable.ic_code
    FileType.TEXT -> R.drawable.ic_article
    FileType.APK -> R.drawable.ic_android
    FileType.OTHER -> R.drawable.ic_draft
}

@Composable
fun FileType.color(): Color {
    val x = AppTheme.extra
    return when (this) {
        FileType.FOLDER -> x.folder
        FileType.IMAGE -> x.image
        FileType.VIDEO -> x.video
        FileType.AUDIO -> x.audio
        FileType.PDF -> x.pdf
        FileType.DOCUMENT, FileType.TEXT -> x.document
        FileType.SPREADSHEET -> x.spreadsheet
        FileType.PRESENTATION -> x.presentation
        FileType.ARCHIVE -> x.archive
        FileType.CODE -> x.code
        FileType.APK -> x.success
        FileType.OTHER -> x.other
    }
}

@Composable
fun labelColor(label: Int): Color = when (label) {
    MegaNode.NODE_LBL_RED -> Color(0xFFE53935)
    MegaNode.NODE_LBL_ORANGE -> Color(0xFFFB8C00)
    MegaNode.NODE_LBL_YELLOW -> Color(0xFFFDD835)
    MegaNode.NODE_LBL_GREEN -> Color(0xFF43A047)
    MegaNode.NODE_LBL_BLUE -> Color(0xFF1E88E5)
    MegaNode.NODE_LBL_PURPLE -> Color(0xFF8E24AA)
    MegaNode.NODE_LBL_GREY -> Color(0xFF757575)
    else -> Color.Transparent
}

/** Name of a colour label, for TalkBack: the colour alone does not tell it. */
@Composable
fun labelName(label: Int): String = when (label) {
    MegaNode.NODE_LBL_RED -> stringResource(R.string.label_red)
    MegaNode.NODE_LBL_ORANGE -> stringResource(R.string.label_orange)
    MegaNode.NODE_LBL_YELLOW -> stringResource(R.string.label_yellow)
    MegaNode.NODE_LBL_GREEN -> stringResource(R.string.label_green)
    MegaNode.NODE_LBL_BLUE -> stringResource(R.string.label_blue)
    MegaNode.NODE_LBL_PURPLE -> stringResource(R.string.label_purple)
    MegaNode.NODE_LBL_GREY -> stringResource(R.string.label_grey)
    else -> ""
}

/** A tinted square with the file type glyph. */
@Composable
fun FileTypeIcon(type: FileType, modifier: Modifier = Modifier, size: Dp = 40.dp, @DrawableRes override: Int? = null) {
    val color = type.color()
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Ic(override ?: type.icon(), null, Modifier.size(size * 0.55f), tint = color)
    }
}

@DrawableRes
fun NodeItem.folderIcon(): Int = when {
    isInShare -> R.drawable.ic_folder_shared_fill
    isOutShare -> R.drawable.ic_folder_shared_fill
    else -> R.drawable.ic_folder_fill
}

/** Thumbnail if the node has one (see showsThumbnail), otherwise the type icon. */
@Composable
fun NodeThumbnail(
    item: NodeItem,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    folderLink: Boolean = false,
    cornerFraction: Float = 0.28f,
) {
    if (item.isFolder || !item.showsThumbnail) {
        FileTypeIcon(item.type, modifier, size, if (item.isFolder) item.folderIcon() else null)
        return
    }
    val context = LocalContext.current
    val request = remember(item.handle, folderLink) {
        ImageRequest.Builder(context).data(NodeImage(item.handle, folderLink = folderLink)).build()
    }
    // The icon stays behind the image while it loads and after an error. AsyncImage does not
    // subcompose, which keeps fast list scrolls smooth (MEGAGLASS-PERF-005).
    Box(modifier.size(size).clip(RoundedCornerShape(size * cornerFraction))) {
        FileTypeIcon(item.type, Modifier.fillMaxSize(), size)
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** "3 folders · 12 files" for folders, "1.2 MB · 3 Oct" for files. */
@Composable
fun NodeItem.subtitle(): String = if (isFolder) {
    when {
        folders == 0 && files == 0 -> stringResource(R.string.empty_folder)
        folders == 0 -> pluralStringResource(R.plurals.n_files, files, files)
        files == 0 -> pluralStringResource(R.plurals.n_folders, folders, folders)
        else -> pluralStringResource(R.plurals.n_folders, folders, folders) + " · " + pluralStringResource(R.plurals.n_files, files, files)
    }
} else {
    Format.size(size) + " · " + Format.date(modified)
}

/** Small status badges: label, favourite, link, offline. */
@Composable
fun NodeBadges(item: NodeItem, offline: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.label != 0) {
            val name = labelName(item.label)
            Box(Modifier.size(10.dp).clip(CircleShape).background(labelColor(item.label)).semantics { contentDescription = name })
        }
        if (item.isFavourite) Ic(R.drawable.ic_star_fill, stringResource(R.string.favourite), Modifier.size(16.dp), AppTheme.extra.warning)
        if (item.isExported) Ic(R.drawable.ic_link, stringResource(R.string.link), Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
        if (offline) Ic(R.drawable.ic_offline_pin_fill, stringResource(R.string.offline), Modifier.size(16.dp), AppTheme.extra.success)
        if (item.isTakenDown) Ic(R.drawable.ic_block, null, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
    }
}

@Composable
fun NodeRow(
    item: NodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    offline: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    folderLink: Boolean = false,
    subtitle: String? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.graphicsLayer { alpha = if (item.isSensitive) 0.55f else 1f },
        leadingContent = {
            Box {
                NodeThumbnail(item, size = 44.dp, folderLink = folderLink)
                if (item.isVideo && item.showsThumbnail) {
                    Ic(R.drawable.ic_play_circle_fill, null, Modifier.size(18.dp).align(Alignment.Center), Color.White)
                }
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NodeBadges(item, offline)
                if (item.label != 0 || item.isFavourite || item.isExported || offline || item.isTakenDown) Spacer(Modifier.width(6.dp))
                Text(subtitle ?: item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = when {
            selectionMode -> { { Checkbox(checked = selected, onCheckedChange = { onClick() }) } }
            onMore != null -> { { IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), onMore) } }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
fun NodeTile(
    item: NodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    offline: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    folderLink: Boolean = false,
) {
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, label = "tileScale")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .scale(scale)
            .clip(MaterialTheme.shapes.large)
            .semantics { if (selectionMode) this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (item.isSensitive) 0.55f else 1f },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp)) {
                if (item.isFolder || !item.showsThumbnail) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FileTypeIcon(item.type, size = 72.dp, override = if (item.isFolder) item.folderIcon() else null)
                    }
                } else {
                    val context = LocalContext.current
                    AsyncImage(
                        model = remember(item.handle) { ImageRequest.Builder(context).data(NodeImage(item.handle, folderLink = folderLink)).build() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                    )
                    if (item.isVideo) {
                        Ic(R.drawable.ic_play_circle_fill, null, Modifier.size(32.dp).align(Alignment.Center), Color.White)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(selectionMode, Modifier.align(Alignment.TopStart), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        Modifier.padding(4.dp).size(24.dp).clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Ic(R.drawable.ic_check, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp, end = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    NodeBadges(item, offline)
                }
                if (onMore != null && !selectionMode) {
                    IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) { Ic(R.drawable.ic_more_vert, stringResource(R.string.more)) }
                }
            }
        }
    }
}

/**
 * A list or grid of nodes with selection. Keys are node handles, so items animate on changes
 * and keep their scroll position.
 */
@Composable
fun NodeCollection(
    items: List<NodeItem>,
    grid: Boolean,
    selection: Set<Long>,
    onClick: (NodeItem) -> Unit,
    onLongClick: (NodeItem) -> Unit,
    onMore: ((NodeItem) -> Unit)?,
    modifier: Modifier = Modifier,
    offline: Set<Long> = emptySet(),
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    folderLink: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val selecting = selection.isNotEmpty()
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            state = gridState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (header != null) item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
            items(items, key = { it.handle }, contentType = { if (it.isFolder) 0 else 1 }) { item ->
                NodeTile(
                    item = item,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onMore = onMore?.let { { it(item) } },
                    selected = item.handle in selection,
                    selectionMode = selecting,
                    offline = item.handle in offline,
                    folderLink = folderLink,
                    modifier = Modifier.animateItem(),
                )
            }
            if (footer != null) item(key = "footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { footer() }
        }
    } else {
        LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
            if (header != null) item(key = "header") { header() }
            items(items, key = { it.handle }, contentType = { if (it.isFolder) 0 else 1 }) { item ->
                NodeRow(
                    item = item,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onMore = onMore?.let { { it(item) } },
                    selected = item.handle in selection,
                    selectionMode = selecting,
                    offline = item.handle in offline,
                    folderLink = folderLink,
                    modifier = Modifier.animateItem(),
                )
            }
            if (footer != null) item(key = "footer") { footer() }
        }
    }
}

/** Avatar image with a coloured initial as fallback. */
@Composable
fun Avatar(user: String?, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, version: Int = 0, color: Color? = null) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val bg = color ?: MaterialTheme.colorScheme.primaryContainer
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(initial, style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    if (user == null) {
        Box(modifier) { fallback() }
        return
    }
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = remember(user, version) { ImageRequest.Builder(context).data(UserAvatar(user, version)).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
        loading = { fallback() },
        error = { fallback() },
    )
}
