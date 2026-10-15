package app.megaglass.android.feature.shared

import app.megaglass.android.core.util.catching

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.dateComparator
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.feature.contacts.ContactDirectory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import nz.mega.sdk.MegaShare

/** A row in Shared items: the node and the text under its name. */
@Immutable
data class SharedEntry(val item: NodeItem, val subtitle: String?, val unverified: Boolean = false)

data class SharedState(
    val incoming: List<SharedEntry>? = null,
    val outgoing: List<SharedEntry>? = null,
    val links: List<SharedEntry>? = null,
    val selection: Set<Long> = emptySet(),
) {
    fun list(tab: Int) = when (tab) {
        0 -> incoming
        1 -> outgoing
        else -> links
    }
}

/** Access level as text. */
internal fun accessLabel(access: Int): Int = when (access) {
    MegaShare.ACCESS_FULL -> R.string.share_access_full
    MegaShare.ACCESS_READWRITE -> R.string.share_access_readwrite
    MegaShare.ACCESS_OWNER -> R.string.share_access_owner
    else -> R.string.share_access_read
}

class SharedViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api get() = graph.mega.api
    private val directory = ContactDirectory.instance
    private val _state = MutableStateFlow(SharedState())
    val state: StateFlow<SharedState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            merge(graph.mega.nodes.map { }, directory.changes).debounce(250).collect { load() }
        }
        viewModelScope.launch {
            graph.settings.state.distinctUntilChangedBy { it.sortOrder to it.showHidden }.drop(1).collect { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = graph.settings.current
            val order = s.sortOrder.sdkOrder
            val res = graph.context.resources
            val incoming = catching {
                val shares = withContext(Dispatchers.IO) { api.getInSharesList(order) }
                val unverified = withContext(Dispatchers.IO) { api.getUnverifiedIncomingShares(order).map { it.nodeHandle }.toSet() }
                shares.mapNotNull { share ->
                    val owner = directory.displayName(share.user)
                    withContext(Dispatchers.IO) {
                        val node = api.getNodeByHandle(share.nodeHandle) ?: return@withContext null
                        var item = node.toItem(api, access = share.access, owner = owner)
                        if (!node.isNodeKeyDecrypted) item = item.copy(name = res.getString(R.string.share_undecrypted))
                        SharedEntry(
                            item = item,
                            subtitle = listOf(owner, res.getString(accessLabel(share.access))).joinToString(" · "),
                            unverified = share.nodeHandle in unverified,
                        )
                    }
                }
            }.getOrDefault(emptyList())
            val outgoing = catching {
                val shares = withContext(Dispatchers.IO) { api.getOutShares(order) }
                val unverified = withContext(Dispatchers.IO) { api.getUnverifiedOutgoingShares(order).map { it.nodeHandle }.toSet() }
                shares.groupBy { it.nodeHandle }.mapNotNull { (handle, group) ->
                    val people = group.filter { !it.user.isNullOrBlank() }
                    val subtitle = when (people.size) {
                        0 -> null
                        1 -> people.first().let { p ->
                            directory.displayName(p.user) + if (p.isPending) " · " + res.getString(R.string.share_pending) else ""
                        }
                        else -> res.getQuantityString(R.plurals.share_with_people, people.size, people.size)
                    }
                    withContext(Dispatchers.IO) {
                        val node = api.getNodeByHandle(handle) ?: return@withContext null
                        if (!s.showHidden && (node.isMarkedSensitive || api.isSensitiveInherited(node))) return@withContext null
                        SharedEntry(node.toItem(api), subtitle, unverified = handle in unverified)
                    }
                }
            }.getOrDefault(emptyList())
            val links = catching {
                graph.nodes.publicLinks(s.sortOrder, s.showHidden).map { SharedEntry(it, null) }
            }.getOrDefault(emptyList())
            // Folders by their shown date for Newest and Oldest (see dateComparator).
            val byDate = s.sortOrder.dateComparator()?.let { c -> compareBy<SharedEntry, NodeItem>(c) { it.item } }
            _state.update { st ->
                val all = (incoming + outgoing + links).map { it.item.handle }.toSet()
                st.copy(
                    incoming = byDate?.let(incoming::sortedWith) ?: incoming,
                    outgoing = byDate?.let(outgoing::sortedWith) ?: outgoing,
                    links = links,
                    selection = st.selection.filterTo(HashSet()) { it in all },
                )
            }
        }
    }

    fun toggle(item: NodeItem) = _state.update {
        it.copy(selection = if (item.handle in it.selection) it.selection - item.handle else it.selection + item.handle)
    }

    fun selectAll(tab: Int) = _state.update { st -> st.copy(selection = st.list(tab).orEmpty().map { it.item.handle }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
}
