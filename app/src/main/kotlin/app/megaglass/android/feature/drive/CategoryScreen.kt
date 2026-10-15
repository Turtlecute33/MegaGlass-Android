package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeCollection
import app.megaglass.android.ui.nav.Category
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaApiJava

data class ListState(val items: List<NodeItem>? = null, val selection: Set<Long> = emptySet()) {
    val selectedItems get() = items.orEmpty().filter { it.handle in selection }
}

/** A flat, live list of nodes from one query (a category, or favourites). */
class NodeQueryViewModel(private val query: suspend () -> List<NodeItem>) : ViewModel() {
    private val graph = MegaGlassApp.graph
    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.settings.state.distinctUntilChangedBy { it.sortOrder to it.showHidden }.collect { load() }
        }
        viewModelScope.launch { graph.nodes.changes.debounce(500).collect { load() } }
    }

    private suspend fun load() {
        val items = catching { query() }.getOrDefault(emptyList())
        val handles = items.mapTo(HashSet()) { it.handle }
        _state.update { s -> s.copy(items = items, selection = s.selection.intersect(handles)) }
    }

    fun toggle(item: NodeItem) = _state.update {
        it.copy(selection = if (item.handle in it.selection) it.selection - item.handle else it.selection + item.handle)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
}

@Composable
fun CategoryScreen(route: Category) {
    val graph = MegaGlassApp.graph
    val vm: NodeQueryViewModel = viewModel(key = "category:${route.category}") {
        NodeQueryViewModel {
            val s = graph.settings.current
            graph.nodes.search("", category = route.category, order = if (s.sortOrder == SortOrder.NAME_ASC) SortOrder.MODIFIED_DESC else s.sortOrder, showHidden = s.showHidden)
        }
    }
    val title = stringResource(
        when (route.category) {
            MegaApiJava.FILE_TYPE_AUDIO -> R.string.audio
            MegaApiJava.FILE_TYPE_VIDEO -> R.string.videos
            MegaApiJava.FILE_TYPE_PHOTO -> R.string.images
            else -> R.string.documents
        },
    )
    NodeListScreen(vm, title, R.drawable.ic_inventory_2, stringResource(R.string.category_empty))
}

@Composable
fun FavouritesScreen() {
    val graph = MegaGlassApp.graph
    val vm: NodeQueryViewModel = viewModel(key = "favourites") {
        NodeQueryViewModel { graph.nodes.favourites(graph.settings.current.showHidden) }
    }
    NodeListScreen(vm, stringResource(R.string.favourites), R.drawable.ic_star, stringResource(R.string.favourites_empty))
}

@Composable
private fun NodeListScreen(vm: NodeQueryViewModel, title: String, emptyIcon: Int, emptyText: String) {
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val actions = rememberNodeActions(NodeContext.CLOUD, onDone = vm::clearSelection)
    val selecting = state.selection.isNotEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = state.items?.let { { Text(pluralStringResource(R.plurals.n_items, it.size, it.size)) } },
                navigationIcon = { BackButton { nav.back() } },
                actions = { ViewOptionsActions() },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val items = state.items
            when {
                items == null -> LoadingBox()
                items.isEmpty() -> EmptyState(emptyIcon, emptyText)
                else -> NodeCollection(
                    items = items,
                    grid = settings.gridView,
                    selection = state.selection,
                    offline = offline,
                    onClick = { item -> if (selecting) vm.toggle(item) else nav.openNode(item, MediaSource.Folder(item.parentHandle)) },
                    onLongClick = vm::toggle,
                    onMore = actions::openMenu,
                    contentPadding = PaddingValues(bottom = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AnimatedVisibility(
                selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { SelectionToolbar(state.selectedItems, NodeContext.CLOUD, actions) }
        }
    }
    NodeActionsHost(actions)
}
