package app.megaglass.android.feature.sync

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaSync
import nz.mega.sdk.MegaSyncStall
import java.io.File

enum class SyncRunState { RUNNING, PAUSED, LOADING, DISABLED }

@Immutable
data class SyncItem(
    val backupId: Long,
    val name: String,
    val localPath: String,
    val remoteHandle: Long,
    val remotePath: String,
    val state: SyncRunState,
    /** Readable SDK error, or null when the sync has no error. */
    val error: String?,
)

@Immutable
data class SyncActivity(val scanning: Boolean, val syncing: Boolean, val uploads: Int, val downloads: Int)

@Immutable
data class StallItem(val reason: String, val localPath: String, val cloudPath: String)

data class SyncsState(
    val syncs: List<SyncItem>? = null,
    val activity: Map<Long, SyncActivity> = emptyMap(),
    val stalled: Boolean = false,
)

/** Two-way syncs of this device. The SDK runs them while the app process is alive. */
class SyncViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api get() = graph.mega.api

    private val _state = MutableStateFlow(SyncsState())
    val state: StateFlow<SyncsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            graph.mega.syncs.collect { change ->
                val stats = change.stats
                if (stats != null) {
                    _state.update {
                        it.copy(
                            activity = it.activity + (stats.backupId to SyncActivity(
                                stats.isScanning, stats.isSyncing, stats.uploadCount, stats.downloadCount,
                            )),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            graph.mega.syncs.filter { it.stats == null }.debounce(300).collect { load() }
        }
    }

    fun load() = viewModelScope.launch {
        val (items, stalled) = withContext(Dispatchers.IO) {
            val list = api.syncs
            (0 until list.size()).map { list.get(it).toItem() } to api.isSyncStalled
        }
        _state.update { it.copy(syncs = items, stalled = stalled) }
    }

    private fun MegaSync.toItem(): SyncItem {
        val run = runState
        return SyncItem(
            backupId = backupId,
            name = name ?: File(localFolder.orEmpty()).name,
            localPath = localFolder.orEmpty(),
            remoteHandle = megaHandle,
            remotePath = api.getNodePathByHandle(megaHandle) ?: lastKnownMegaFolder.orEmpty(),
            state = when (run) {
                MegaSync.SyncRunningState.RUNSTATE_RUNNING.swigValue() -> SyncRunState.RUNNING
                MegaSync.SyncRunningState.RUNSTATE_SUSPENDED.swigValue() -> SyncRunState.PAUSED
                MegaSync.SyncRunningState.RUNSTATE_PENDING.swigValue(),
                MegaSync.SyncRunningState.RUNSTATE_LOADING.swigValue() -> SyncRunState.LOADING
                else -> SyncRunState.DISABLED
            },
            error = if (error != 0) MegaSync.getMegaSyncErrorCode(error) else null,
        )
    }

    fun pause(item: SyncItem) = act { api.pauseSync(item.backupId) }

    fun resume(item: SyncItem) = act { api.resumeSync(item.backupId) }

    fun remove(item: SyncItem) = act { api.removeSync(item.backupId) }

    private fun act(block: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { block() }
        load()
    }

    /** Adds a two-way sync between a local folder and a cloud folder. Returns an error text or null. */
    suspend fun add(localPath: String, remoteHandle: Long, name: String): String? = runCatching {
        withContext(Dispatchers.IO) {
            api.await { syncFolder(MegaSync.SyncType.TYPE_TWOWAY, localPath, name, remoteHandle, null, it) }
        }
    }.fold(onSuccess = { load(); null }, onFailure = { it.message })

    /** Items that the sync cannot resolve alone (name clashes, permissions, ...). */
    suspend fun stalls(): List<StallItem> = withContext(Dispatchers.IO) {
        val list = runCatching { api.await { requestMegaSyncStallList(it) }.megaSyncStallList }.getOrNull()
            ?: return@withContext emptyList()
        (0 until list.size()).map { i ->
            val s = list.get(i)
            StallItem(
                reason = stallReason(s.reason()),
                localPath = if (s.pathCount(false) > 0) s.path(false, 0).orEmpty() else "",
                cloudPath = if (s.pathCount(true) > 0) s.path(true, 0).orEmpty() else "",
            )
        }
    }

    /** "LocalAndRemoteChangedSinceLastSyncedState_userMustChoose" -> "Local and remote changed since last synced state, user must choose". */
    private fun stallReason(reason: MegaSyncStall.SyncStallReason): String {
        val words = reason.toString()
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .lowercase()
            .trim()
        return words.replaceFirstChar { it.uppercase() }
    }
}
