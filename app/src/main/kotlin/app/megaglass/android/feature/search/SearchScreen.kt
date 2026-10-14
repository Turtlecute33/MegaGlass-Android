package app.megaglass.android.feature.search

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.SearchScope
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.feature.drive.NodeActionsHost
import app.megaglass.android.feature.drive.NodeContext
import app.megaglass.android.feature.drive.SelectionToolbar
import app.megaglass.android.feature.drive.rememberNodeActions
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeCollection
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.ROOT
import app.megaglass.android.ui.nav.Search
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaApiJava

enum class TypeFilter(@StringRes val label: Int, val category: Int, val folders: Boolean = false) {
    ALL(R.string.filter_all, MegaApiJava.FILE_TYPE_DEFAULT),
    FOLDERS(R.string.filter_folders, MegaApiJava.FILE_TYPE_DEFAULT, folders = true),
    IMAGES(R.string.images, MegaApiJava.FILE_TYPE_PHOTO),
    VIDEOS(R.string.videos, MegaApiJava.FILE_TYPE_VIDEO),
    AUDIO(R.string.audio, MegaApiJava.FILE_TYPE_AUDIO),
    DOCUMENTS(R.string.documents, MegaApiJava.FILE_TYPE_ALL_DOCS),
    PDF(R.string.filter_pdf, MegaApiJava.FILE_TYPE_PDF),
    ARCHIVES(R.string.filter_archives, MegaApiJava.FILE_TYPE_ARCHIVE),
}

enum class DateFilter(@StringRes val label: Int, val days: Int) {
    ANY(R.string.date_any, 0), TODAY(R.string.date_today, 1), WEEK(R.string.date_week, 7),
    MONTH(R.string.date_month, 30), YEAR(R.string.date_year, 365),
}

data class SearchState(
    val query: String = "",
    val type: TypeFilter = TypeFilter.ALL,
    val date: DateFilter = DateFilter.ANY,
    val inFolder: Boolean = true,
    val results: List<NodeItem>? = emptyList(),
    val searching: Boolean = false,
    val selection: Set<Long> = emptySet(),
) {
    val active get() = query.isNotBlank() || type != TypeFilter.ALL || date != DateFilter.ANY
}

class SearchViewModel(private val parent: Long) : ViewModel() {
    private val graph = MegaGlassApp.graph
    private val _state = MutableStateFlow(SearchState(inFolder = parent != ROOT))
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private val trigger = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(_state, trigger) { s, _ -> s.copy(results = null, searching = false, selection = emptySet()) }
                .debounce(250)
                .collect { s -> search(s) }
        }
        viewModelScope.launch { graph.nodes.changes.debounce(1000).collect { trigger.update { it + 1 } } }
    }

    private var lastKey: Any? = null

    private suspend fun search(s: SearchState) {
        val key = listOf(s.query, s.type, s.date, s.inFolder, trigger.value)
        if (key == lastKey) return
        lastKey = key
        if (!s.active) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        _state.update { it.copy(searching = true) }
        val after = if (s.date.days > 0) System.currentTimeMillis() / 1000 - s.date.days * 86_400L else 0
        val result = runCatching {
            graph.nodes.search(
                query = s.query,
                scope = SearchScope.ALL,
                category = s.type.category,
                parent = if (s.inFolder && parent != ROOT) parent else null,
                modifiedAfter = after,
                foldersOnly = s.type.folders,
                order = if (s.query.isBlank()) SortOrder.MODIFIED_DESC else graph.settings.current.sortOrder,
                showHidden = graph.settings.current.showHidden,
                limit = 500,
            )
        }.getOrDefault(emptyList())
        _state.update { it.copy(results = result, searching = false) }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setType(t: TypeFilter) = _state.update { it.copy(type = t) }
    fun setDate(d: DateFilter) = _state.update { it.copy(date = d) }
    fun setInFolder(v: Boolean) = _state.update { it.copy(inFolder = v) }
    fun toggle(item: NodeItem) = _state.update {
        it.copy(selection = if (item.handle in it.selection) it.selection - item.handle else it.selection + item.handle)
    }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
}

@Composable
fun SearchScreen(route: Search) {
    val vm: SearchViewModel = viewModel(key = "search:${route.parent}") { SearchViewModel(route.parent) }
    val state by vm.state.collectAsStateWithLifecycle()
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val actions = rememberNodeActions(NodeContext.SEARCH, onDone = vm::clearSelection)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val selecting = state.selection.isNotEmpty()
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { BackButton { nav.back() } },
                    title = {
                        TextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    actions = {
                        if (state.query.isNotEmpty()) IconAction(R.drawable.ic_close, stringResource(R.string.clear), { vm.setQuery("") })
                    },
                )
                Filters(state, vm, route.parent != ROOT)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val results = state.results
            when {
                !state.active -> EmptyState(R.drawable.ic_search, stringResource(R.string.search_start), message = stringResource(R.string.search_start_hint))
                results == null || state.searching && results.isEmpty() -> LoadingBox()
                results.isEmpty() -> EmptyState(R.drawable.ic_image_search, stringResource(R.string.no_results))
                else -> NodeCollection(
                    items = results,
                    grid = settings.gridView,
                    selection = state.selection,
                    offline = offline,
                    onClick = { item -> if (selecting) vm.toggle(item) else nav.openNode(item, MediaSource.Folder(item.parentHandle)) },
                    onLongClick = vm::toggle,
                    onMore = actions::openMenu,
                    contentPadding = PaddingValues(bottom = 120.dp + padding.calculateBottomPadding()),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AnimatedVisibility(
                selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SelectionToolbar(results.orEmpty().filter { it.handle in state.selection }, NodeContext.SEARCH, actions)
            }
        }
    }
    NodeActionsHost(actions)
}

@Composable
private fun Filters(state: SearchState, vm: SearchViewModel, hasFolder: Boolean) {
    var dateMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasFolder) {
            item(key = "folder") {
                FilterChip(
                    selected = state.inFolder,
                    onClick = { vm.setInFolder(!state.inFolder) },
                    label = { Text(stringResource(R.string.this_folder)) },
                    leadingIcon = { Ic(R.drawable.ic_folder, null) },
                )
            }
        }
        item(key = "date") {
            Box {
                FilterChip(
                    selected = state.date != DateFilter.ANY,
                    onClick = { dateMenu = true },
                    label = { Text(stringResource(if (state.date == DateFilter.ANY) R.string.modified else state.date.label)) },
                    trailingIcon = { Ic(R.drawable.ic_expand_more, null) },
                )
                DropdownMenu(dateMenu, onDismissRequest = { dateMenu = false }) {
                    DateFilter.entries.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(stringResource(d.label)) },
                            onClick = { vm.setDate(d); dateMenu = false },
                            trailingIcon = if (state.date == d) { { Ic(R.drawable.ic_check) } } else null,
                        )
                    }
                }
            }
        }
        items(TypeFilter.entries, key = { it.name }) { t ->
            FilterChip(
                selected = state.type == t,
                onClick = { vm.setType(t) },
                label = { Text(stringResource(t.label)) },
                leadingIcon = if (state.type == t) { { Ic(R.drawable.ic_check, null) } } else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}
