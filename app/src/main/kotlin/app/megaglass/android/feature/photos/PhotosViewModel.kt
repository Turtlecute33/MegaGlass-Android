package app.megaglass.android.feature.photos

import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.whileObserved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Immutable
data class DaySection(val day: LocalDate, val items: List<MediaItem>)

data class PhotosState(
    /** Every photo and video, newest first; null while loading. */
    val all: List<MediaItem>? = null,
    val filter: MediaFilter = MediaFilter.ALL,
    val sections: List<DaySection> = emptyList(),
    val albums: List<AlbumInfo>? = null,
    val selection: Set<Long> = emptySet(),
    val error: String? = null,
) {
    val visible: List<MediaItem> get() = sections.flatMap { it.items }
}

/** Timeline and albums, kept in sync with node and album updates. */
class PhotosViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val _state = MutableStateFlow(PhotosState())
    val state: StateFlow<PhotosState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        // Each upload changes the nodes: the full timeline loads again only while it shows.
        viewModelScope.launch { graph.nodes.changes.whileObserved(_state.subscriptionCount).debounce(1000).collect { load() } }
        viewModelScope.launch { graph.mega.sets.whileObserved(_state.subscriptionCount).debounce(300).collect { loadAlbums() } }
        viewModelScope.launch {
            graph.settings.state.distinctUntilChangedBy { it.showHidden }.drop(1).collect { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            catching { PhotosRepository.timeline(MediaFilter.ALL, graph.settings.current.showHidden) }
                .onSuccess { all ->
                    val sections = withContext(Dispatchers.Default) { group(all, _state.value.filter) }
                    val handles = all.mapTo(HashSet()) { it.handle }
                    _state.update { st ->
                        st.copy(
                            all = all,
                            sections = sections,
                            error = null,
                            selection = st.selection.filterTo(HashSet()) { it in handles },
                        )
                    }
                    loadAlbums()
                }
                .onFailure { e -> _state.update { it.copy(all = it.all ?: emptyList(), error = graph.context.resources.userMessage(e)) } }
        }
    }

    private suspend fun loadAlbums() {
        val all = _state.value.all ?: return
        val user = catching { PhotosRepository.userAlbums(graph.settings.current.showHidden) }.getOrDefault(emptyList())
        _state.update { it.copy(albums = PhotosRepository.systemAlbums(all) + user) }
    }

    fun setFilter(filter: MediaFilter) = viewModelScope.launch {
        val all = _state.value.all ?: return@launch
        val sections = withContext(Dispatchers.Default) { group(all, filter) }
        _state.update { it.copy(filter = filter, sections = sections, selection = emptySet()) }
    }

    fun toggle(handle: Long) = _state.update {
        it.copy(selection = if (handle in it.selection) it.selection - handle else it.selection + handle)
    }

    /** Selects or clears a whole day. */
    fun toggleDay(section: DaySection) = _state.update { st ->
        val handles = section.items.map { it.handle }
        val all = handles.all { it in st.selection }
        st.copy(selection = if (all) st.selection - handles.toSet() else st.selection + handles)
    }

    fun selectAll() = _state.update { st -> st.copy(selection = st.visible.map { it.handle }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun createAlbum(name: String, withSelection: Boolean, onCreated: (Long) -> Unit) = viewModelScope.launch {
        catching {
            val id = PhotosRepository.createAlbum(name)
            if (withSelection) PhotosRepository.addToAlbum(id, _state.value.selection)
            id
        }.onSuccess {
            clearSelection()
            onCreated(it)
        }.onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
    }

    fun addSelectionTo(albumId: Long, done: (Int) -> Unit) = viewModelScope.launch {
        val handles = _state.value.selection
        catching { PhotosRepository.addToAlbum(albumId, handles) }
            .onSuccess {
                clearSelection()
                done(handles.size)
            }
            .onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
    }

    companion object {
        fun group(all: List<MediaItem>, filter: MediaFilter): List<DaySection> {
            val items = when (filter) {
                MediaFilter.ALL -> all
                MediaFilter.PHOTOS -> all.filter { it.node.type == FileType.IMAGE }
                MediaFilter.VIDEOS -> all.filter { it.node.type == FileType.VIDEO }
            }
            val out = ArrayList<DaySection>()
            var day: LocalDate? = null
            var current = ArrayList<MediaItem>()
            for (m in items) {
                val d = m.day
                if (d != day) {
                    if (day != null) out += DaySection(day, current)
                    day = d
                    current = ArrayList()
                }
                current += m
            }
            if (day != null) out += DaySection(day, current)
            return out
        }
    }
}

data class AlbumState(
    val name: String = "",
    val isSystem: Boolean = false,
    val entries: List<AlbumEntry>? = null,
    val selection: Set<Long> = emptySet(),
    val isExported: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
) {
    val media get() = entries.orEmpty().map { it.media }
}

/** One album: a user album (MegaSet) or, when [isSystem], a system album computed from the timeline. */
class AlbumViewModel(private val id: Long, private val isSystem: Boolean, private val systemName: String) : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val _state = MutableStateFlow(AlbumState(isSystem = isSystem))
    val state: StateFlow<AlbumState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch { graph.mega.sets.whileObserved(_state.subscriptionCount).debounce(300).collect { load() } }
        viewModelScope.launch { graph.nodes.changes.whileObserved(_state.subscriptionCount).debounce(1000).collect { load() } }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        val showHidden = graph.settings.current.showHidden
        if (isSystem) {
            catching { PhotosRepository.timeline(MediaFilter.ALL, showHidden) }
                .onSuccess { all ->
                    val items = PhotosRepository.systemAlbum(id, all).map { AlbumEntry(it.handle, it) }
                    _state.update { it.copy(name = systemName, entries = items, error = null) }
                }
                .onFailure { e -> _state.update { it.copy(name = systemName, entries = it.entries ?: emptyList(), error = graph.context.resources.userMessage(e)) } }
        } else {
            val name = withContext(Dispatchers.IO) { PhotosRepository.albumName(id) }
            if (name == null) {
                _state.update { it.copy(deleted = true, entries = emptyList()) }
                return
            }
            val entries = catching { PhotosRepository.albumEntries(id, showHidden) }.getOrElse { e ->
                _state.update { it.copy(name = name, entries = it.entries ?: emptyList(), error = graph.context.resources.userMessage(e)) }
                return
            }
            val exported = withContext(Dispatchers.IO) { graph.mega.api.isExportedSet(id) }
            val handles = entries.mapTo(HashSet()) { it.media.handle }
            _state.update { st ->
                st.copy(
                    name = name,
                    entries = entries,
                    isExported = exported,
                    selection = st.selection.filterTo(HashSet()) { it in handles },
                    error = null,
                )
            }
        }
    }

    fun toggle(handle: Long) = _state.update {
        it.copy(selection = if (handle in it.selection) it.selection - handle else it.selection + handle)
    }

    fun selectAll() = _state.update { st -> st.copy(selection = st.media.map { it.handle }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        catching { block() }.onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
    }

    fun rename(name: String) = run { PhotosRepository.renameAlbum(id, name) }

    fun delete() = run {
        PhotosRepository.deleteAlbum(id)
        _state.update { it.copy(deleted = true) }
    }

    fun add(handles: Collection<Long>) = run { PhotosRepository.addToAlbum(id, handles) }

    fun removeSelected() = run {
        PhotosRepository.removeFromAlbum(id, _state.value.selection)
        clearSelection()
    }

    fun setCoverFromSelection() = run {
        val handle = _state.value.selection.singleOrNull() ?: return@run
        val element = _state.value.entries.orEmpty().firstOrNull { it.media.handle == handle } ?: return@run
        PhotosRepository.setCover(id, element.elementId)
        clearSelection()
    }

    fun link(onLink: (String) -> Unit) = run { onLink(PhotosRepository.albumLink(id)) }

    fun removeLink() = run {
        PhotosRepository.removeAlbumLink(id)
        graph.messenger.show(graph.context.getString(R.string.link_removed))
    }
}
