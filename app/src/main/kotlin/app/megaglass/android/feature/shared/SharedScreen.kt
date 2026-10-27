package app.megaglass.android.feature.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.feature.drive.NodeActionsHost
import app.megaglass.android.feature.drive.NodeContext
import app.megaglass.android.feature.drive.SelectionToolbar
import app.megaglass.android.feature.drive.ViewOptionsActions
import app.megaglass.android.feature.drive.rememberNodeActions
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeRow
import app.megaglass.android.ui.components.NodeTile
import app.megaglass.android.ui.components.subtitle
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.Shared
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private data class SharedTab(val title: Int, val icon: Int, val context: NodeContext)

private val sharedTabs = listOf(
    SharedTab(R.string.shared_incoming, R.drawable.ic_inbox, NodeContext.INCOMING),
    SharedTab(R.string.shared_outgoing, R.drawable.ic_outbox, NodeContext.OUTGOING),
    SharedTab(R.string.shared_links, R.drawable.ic_link, NodeContext.LINKS),
)

@Composable
fun SharedScreen(route: Shared) {
    val vm: SharedViewModel = viewModel { SharedViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val pager = rememberPagerState(initialPage = route.tab.coerceIn(0, 2)) { sharedTabs.size }
    val scope = rememberCoroutineScope()
    val actions = sharedTabs.map { rememberNodeActions(it.context, onDone = vm::clearSelection) }
    val selecting = state.selection.isNotEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Selection belongs to one tab.
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.drop(1).collect { vm.clearSelection() } }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                if (selecting) {
                    TopAppBar(
                        title = { Text(pluralStringResource(R.plurals.n_selected, state.selection.size, state.selection.size)) },
                        navigationIcon = { IconAction(R.drawable.ic_close, stringResource(R.string.close), vm::clearSelection) },
                        actions = {
                            IconAction(R.drawable.ic_select_all, stringResource(R.string.select_all), { vm.selectAll(pager.currentPage) })
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                } else {
                    MediumFlexibleTopAppBar(
                        title = { Text(stringResource(R.string.shared_title)) },
                        navigationIcon = { BackButton { nav.back() } },
                        actions = { ViewOptionsActions() },
                        scrollBehavior = scroll,
                    )
                }
                PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                    sharedTabs.forEachIndexed { i, tab ->
                        val count = state.list(i)?.size
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(stringResource(tab.title) + if (count != null && count > 0) " ($count)" else "") },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val list = state.list(page)
                val error = state.error(page)
                val tab = sharedTabs[page]
                when {
                    list == null -> LoadingBox()
                    list.isEmpty() && error != null -> ErrorState(error, onRetry = vm::load)
                    list.isEmpty() -> EmptyState(
                        icon = when (page) {
                            0 -> R.drawable.ic_folder_shared
                            1 -> R.drawable.ic_outbox
                            else -> R.drawable.ic_link
                        },
                        title = stringResource(
                            when (page) {
                                0 -> R.string.shared_empty_incoming
                                1 -> R.string.shared_empty_outgoing
                                else -> R.string.shared_empty_links
                            },
                        ),
                        message = stringResource(
                            when (page) {
                                0 -> R.string.shared_empty_incoming_hint
                                1 -> R.string.shared_empty_outgoing_hint
                                else -> R.string.shared_empty_links_hint
                            },
                        ),
                    )
                    else -> SharedList(
                        entries = list,
                        grid = settings.gridView,
                        selection = state.selection,
                        offline = offline,
                        onClick = { entry ->
                            if (selecting) vm.toggle(entry.item)
                            else nav.openNode(entry.item, MediaSource.Folder(entry.item.parentHandle))
                        },
                        onLongClick = { vm.toggle(it.item) },
                        onMore = { actions[page].openMenu(it.item) },
                        bottomPadding = 120.dp + padding.calculateBottomPadding(),
                        tab = tab,
                    )
                }
            }
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val page = pager.currentPage
                val selected = state.list(page).orEmpty().map { it.item }.filter { it.handle in state.selection }
                SelectionToolbar(selected, sharedTabs[page].context, actions[page])
            }
        }
    }

    actions.forEach { NodeActionsHost(it) }
}

@Composable
private fun SharedList(
    entries: List<SharedEntry>,
    grid: Boolean,
    selection: Set<Long>,
    offline: Set<Long>,
    onClick: (SharedEntry) -> Unit,
    onLongClick: (SharedEntry) -> Unit,
    onMore: (SharedEntry) -> Unit,
    bottomPadding: Dp,
    tab: SharedTab,
) {
    val selecting = selection.isNotEmpty()
    val unverifiedText = stringResource(R.string.share_not_verified)
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { "${tab.context}:${it.item.handle}" }) { e ->
                NodeTile(
                    item = e.item,
                    onClick = { onClick(e) },
                    onLongClick = { onLongClick(e) },
                    onMore = { onMore(e) },
                    selected = e.item.handle in selection,
                    selectionMode = selecting,
                    offline = e.item.handle in offline,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
            items(entries, key = { "${tab.context}:${it.item.handle}" }) { e ->
                val base = e.subtitle ?: e.item.subtitle()
                NodeRow(
                    item = e.item,
                    onClick = { onClick(e) },
                    onLongClick = { onLongClick(e) },
                    onMore = { onMore(e) },
                    selected = e.item.handle in selection,
                    selectionMode = selecting,
                    offline = e.item.handle in offline,
                    subtitle = if (e.unverified) "$unverifiedText · $base" else base,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
