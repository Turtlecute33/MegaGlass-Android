package app.megaglass.android.feature.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.Crumb
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaShare

private val sortLabels = mapOf(
    SortOrder.NAME_ASC to R.string.sort_name_asc,
    SortOrder.NAME_DESC to R.string.sort_name_desc,
    SortOrder.MODIFIED_DESC to R.string.sort_newest,
    SortOrder.MODIFIED_ASC to R.string.sort_oldest,
    SortOrder.SIZE_DESC to R.string.sort_largest,
    SortOrder.SIZE_ASC to R.string.sort_smallest,
    SortOrder.LABEL to R.string.sort_label,
    SortOrder.FAVOURITE to R.string.sort_favourite,
)

/**
 * Sort and list/grid buttons for top app bars. A folder passes its own [folderSort] and
 * [folderGrid] (null: the default view); other lists (Shared items, categories) use one sort
 * order and one view that are saved in the settings.
 */
@Composable
fun ViewOptionsActions(
    folderSort: SortOrder? = null,
    onFolderSort: (SortOrder) -> Unit = {},
    folderGrid: Boolean? = null,
    onFolderGrid: (Boolean) -> Unit = {},
) {
    val settings = MegaGlassApp.graph.settings
    val s by settings.state.collectAsStateWithLifecycle()
    val current = folderSort ?: s.sortOrder
    val grid = folderGrid ?: s.gridView
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    IconAction(
        if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view,
        stringResource(if (grid) R.string.list_view else R.string.grid_view),
        {
            if (folderSort != null) onFolderGrid(!grid)
            else scope.launch { settings.update { it.copy(gridView = !it.gridView) } }
        },
    )
    Box {
        IconAction(R.drawable.ic_sort, stringResource(R.string.sort), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sortLabels.forEach { (order, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        if (folderSort != null) onFolderSort(order)
                        else scope.launch { settings.update { it.copy(sortOrder = order) } }
                    },
                    trailingIcon = if (current == order) { { Ic(R.drawable.ic_check) } } else null,
                )
            }
        }
    }
}

/** Clickable folder chain under the app bar. [rootLabel] names the first folder; null keeps its own name. */
@Composable
fun Breadcrumbs(path: List<Crumb>, rootLabel: String?, onClick: (Crumb) -> Unit, modifier: Modifier = Modifier) {
    if (path.size < 2) return
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(path, key = { _, c -> c.handle }) { i, crumb ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) Ic(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = i == path.lastIndex
                TextButton(onClick = { onClick(crumb) }, enabled = !last) {
                    Text(
                        if (i == 0 && rootLabel != null) rootLabel else crumb.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Expressive floating toolbar with the main actions for the selected items. */
@Composable
fun SelectionToolbar(
    selected: List<NodeItem>,
    context: NodeContext,
    actions: NodeActionsState,
    modifier: Modifier = Modifier,
) {
    if (selected.isEmpty()) return
    val (main, extra) = selectionActions(selected, context)
    var more by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.navigationBarsPadding().padding(bottom = 16.dp),
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        main.forEach { action ->
            ToolbarButton(action) { actions.run(action, selected) }
        }
        if (extra.isNotEmpty()) {
            Box {
                IconButton(onClick = { more = true }) { Ic(R.drawable.ic_more_vert, stringResource(R.string.more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    extra.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            leadingIcon = { Ic(action.icon) },
                            onClick = {
                                more = false
                                actions.run(action, selected)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The toolbar actions for [selected] in [context]: the buttons and the overflow menu. A pure
 * function, so that unit tests can check the access rules (MEGAGLASS-UX-011).
 */
internal fun selectionActions(selected: List<NodeItem>, context: NodeContext): Pair<List<NodeAction>, List<NodeAction>> {
    val allOwn = selected.all { it.access >= MegaShare.ACCESS_OWNER }
    // ACCESS_OWNER is above ACCESS_FULL, so allFull is also true when allOwn is true.
    val allFull = selected.all { it.access >= MegaShare.ACCESS_FULL }
    val main = buildList {
        when (context) {
            NodeContext.RUBBISH -> { add(NodeAction.RESTORE); add(NodeAction.DELETE) }
            NodeContext.BACKUPS -> { add(NodeAction.DOWNLOAD); add(NodeAction.OFFLINE); add(NodeAction.COPY) }
            else -> {
                add(NodeAction.DOWNLOAD)
                if (allOwn && selected.size == 1) add(NodeAction.GET_LINK)
                if (allOwn) add(NodeAction.MOVE)
                add(NodeAction.COPY)
                if (allFull && selected.none { it.isShareRoot }) add(NodeAction.RUBBISH)
            }
        }
    }
    val extra = buildList {
        if (context != NodeContext.RUBBISH && context != NodeContext.BACKUPS) {
            add(NodeAction.OFFLINE)
            if (selected.none { it.isFolder }) add(NodeAction.SEND)
            if (allOwn) {
                add(if (selected.all { it.isFavourite }) NodeAction.UNFAVOURITE else NodeAction.FAVOURITE)
                add(if (selected.all { it.isSensitive }) NodeAction.UNHIDE else NodeAction.HIDE)
                if (selected.any { it.isExported }) add(NodeAction.REMOVE_LINK)
            }
        }
    }
    return main to extra
}

@Composable
private fun ToolbarButton(action: NodeAction, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(action.label)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Ic(action.icon, stringResource(action.label)) }
    }
}
