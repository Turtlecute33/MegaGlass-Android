package app.megaglass.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.RecentBucket
import app.megaglass.android.core.mega.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava

data class HomeState(
    val firstName: String = "",
    val recents: List<RecentBucket>? = null,
    val storageUsed: Long = 0,
    val storageMax: Long = 0,
    val refreshing: Boolean = false,
) {
    val storageFraction: Float get() = if (storageMax > 0) storageUsed.toFloat() / storageMax else 0f
}

class HomeViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api = graph.mega.api
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        refresh()
        loadName()
        loadStorage()
        viewModelScope.launch { graph.nodes.changes.debounce(1000).collect { loadRecents() } }
        viewModelScope.launch { graph.mega.account.debounce(1000).collect { loadStorage() } }
        viewModelScope.launch {
            graph.mega.users.filter { api.myUserHandleBinary in it || it.isEmpty() }.collect { loadName() }
        }
        viewModelScope.launch {
            graph.settings.state.distinctUntilChangedBy { it.showHidden }.drop(1).collect { loadRecents() }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = it.recents != null) }
        viewModelScope.launch {
            loadRecents()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun loadRecents() {
        runCatching { graph.nodes.recents(showHidden = graph.settings.current.showHidden) }
            .onSuccess { r -> _state.update { it.copy(recents = r) } }
            .onFailure { _state.update { s -> s.copy(recents = s.recents ?: emptyList()) } }
    }

    private fun loadName() = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { api.await { getUserAttribute(MegaApiJava.USER_ATTR_FIRSTNAME, it) }.text }
        }.onSuccess { name -> _state.update { it.copy(firstName = name.orEmpty()) } }
    }

    private fun loadStorage() = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { api.await { getSpecificAccountDetails(true, false, false, it) }.megaAccountDetails }
        }.onSuccess { d ->
            if (d != null) _state.update { it.copy(storageUsed = d.storageUsed, storageMax = d.storageMax) }
        }
    }
}
