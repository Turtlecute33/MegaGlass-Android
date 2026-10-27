package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage
import app.megaglass.android.core.util.whileObserved

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.Crumb
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.storage.FolderSorts
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.ui.nav.ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaShare

enum class DriveMode(@StringRes val title: Int) { CLOUD(R.string.cloud_drive), RUBBISH(R.string.rubbish_bin), BACKUPS(R.string.backups) }

data class FolderState(
    val handle: Long = ROOT,
    val title: String = "",
    val path: List<Crumb> = emptyList(),
    val items: List<NodeItem>? = null,
    val error: String? = null,
    val refreshing: Boolean = false,
    val selection: Set<Long> = emptySet(),
    val access: Int = MegaShare.ACCESS_OWNER,
    val isRoot: Boolean = true,
    val sortOrder: SortOrder = FolderSorts.DEFAULT,
    /** The view of this folder: true for the grid; null when the user did not choose one (the default view). */
    val grid: Boolean? = null,
    /** The section at the start of [path]; null inside an incoming share. */
    val top: DriveMode? = null,
) {
    val canWrite get() = access >= MegaShare.ACCESS_READWRITE
    val selectedItems get() = items.orEmpty().filter { it.handle in selection }
}

/** One cloud folder: its items, kept in sync with node updates from the SDK. */
class FolderViewModel(private val mode: DriveMode, requested: Long) : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val nodes = graph.nodes
    private val _state = MutableStateFlow(FolderState())
    val state: StateFlow<FolderState> = _state.asStateFlow()
    private var loadJob: Job? = null

    private val handle: Long = when {
        requested != ROOT -> requested
        mode == DriveMode.RUBBISH -> nodes.rubbishHandle
        mode == DriveMode.BACKUPS -> nodes.vaultHandle
        else -> nodes.rootHandle
    }

    init {
        // The first value loads the folder; later values come from a sort or "show hidden" change.
        viewModelScope.launch {
            combine(graph.folderSorts.flow(handle), graph.settings.state.map { it.showHidden }.distinctUntilChanged()) { order, _ -> order }
                .collect { order ->
                    _state.update { it.copy(sortOrder = order) }
                    load()
                }
        }
        viewModelScope.launch {
            graph.folderSorts.gridFlow(handle).collect { grid -> _state.update { it.copy(grid = grid) } }
        }
        viewModelScope.launch {
            nodes.changes.filter { it.touches(handle) }.whileObserved(_state.subscriptionCount).debounce(150).collect { load() }
        }
    }

    /** Sorts this folder; the order stays for this folder only. */
    fun sort(order: SortOrder) {
        graph.scope.launch { graph.folderSorts.set(handle, order) }
    }

    /** Shows this folder as a grid or a list; the view stays for this folder only. */
    fun setGrid(grid: Boolean) {
        graph.scope.launch { graph.folderSorts.setGrid(handle, grid) }
    }

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        if (refresh) _state.update { it.copy(refreshing = true) }
        loadJob = viewModelScope.launch {
            val s = graph.settings.current
            val order = _state.value.sortOrder
            catching {
                val items = nodes.children(handle, order, s.showHidden)
                val path = nodes.path(handle)
                val self = nodes.item(handle)
                Triple(items, path, self)
            }.onSuccess { (items, path, self) ->
                val handles = items.mapTo(HashSet()) { it.handle }
                val top = section(path.firstOrNull()?.handle)
                val isRoot = top != null && path.size == 1
                _state.update { st ->
                    st.copy(
                        handle = handle,
                        title = self?.name.orEmpty(),
                        path = path,
                        items = items,
                        error = null,
                        refreshing = false,
                        isRoot = isRoot,
                        top = top,
                        access = if (mode == DriveMode.BACKUPS) MegaShare.ACCESS_READ else self?.access ?: MegaShare.ACCESS_OWNER,
                        // Drop selected items that disappeared.
                        selection = st.selection.intersect(handles),
                    )
                }
            }.onFailure { e ->
                // The folder itself is gone (deleted, or the share ended): its old items must not stay.
                _state.update { it.copy(error = graph.context.resources.userMessage(e), refreshing = false, items = emptyList(), selection = emptySet()) }
            }
        }
    }

    /** The section whose top folder is [top]; null for an incoming share. */
    private suspend fun section(top: Long?): DriveMode? = withContext(Dispatchers.IO) {
        when (top) {
            nodes.rootHandle -> DriveMode.CLOUD
            nodes.rubbishHandle -> DriveMode.RUBBISH
            nodes.vaultHandle -> DriveMode.BACKUPS
            else -> null
        }
    }

    fun toggle(item: NodeItem) = _state.update {
        it.copy(selection = if (item.handle in it.selection) it.selection - item.handle else it.selection + item.handle)
    }

    fun selectAll() = _state.update { st -> st.copy(selection = st.items.orEmpty().map { it.handle }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun emptyRubbish() = viewModelScope.launch {
        catching { nodes.emptyRubbish() }.onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
    }

    val rootHandle get() = handle
}
