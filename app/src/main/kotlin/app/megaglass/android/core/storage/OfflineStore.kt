package app.megaglass.android.core.storage

import app.megaglass.android.core.util.catching

import android.content.Context
import androidx.compose.runtime.Immutable
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferManager
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.util.FileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import nz.mega.sdk.MegaApiJava
import java.io.File

@Immutable
data class OfflineEntry(
    /** Node handle of the top-level item, or INVALID_HANDLE for items inside an offline folder. */
    val handle: Long,
    val file: File,
    val name: String,
    val isFolder: Boolean,
    val type: FileType,
    val size: Long,
    /** Milliseconds since epoch. */
    val modified: Long,
    val verified: Boolean = true,
)

/**
 * Files and folders kept on the device. Each one lives in filesDir/offline/<handle>/<name>, in
 * app-private storage that other apps cannot read. The folder name is the node handle, so the
 * index is the folder list and no database is needed.
 */
class OfflineStore(
    context: Context,
    private val transfers: TransferManager,
    scope: CoroutineScope,
    /** Emits when the SDK has resumed the transfers of an earlier process (the node tree is current). */
    transfersResumed: Flow<Any>,
) {

    private val root = File(context.filesDir, "offline").apply { mkdirs() }

    private val index = OfflineFiles(root)
    private val operations = Mutex()
    private val pending = mutableMapOf<Long, String>()

    private val _handles = MutableStateFlow<Set<Long>>(emptySet())
    /** Handles of the nodes that are available offline (complete downloads only). */
    val handles: StateFlow<Set<Long>> = _handles.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            operations.withLock {
                catching { index.recover() }
                refresh()
            }
        }
        scope.launch(Dispatchers.IO) {
            transfers.completed.collect { item ->
                if (item.purpose != Purpose.OFFLINE) return@collect
                val (handle, id) = parse(item.appData) ?: return@collect
                // Only the top-level transfer of the node proves that the whole download finished.
                if (item.nodeHandle != handle) return@collect
                operations.withLock {
                    if (pending[handle] == id) pending.remove(handle)
                    // A download that the SDK resumed after a restart is not in [pending]. The
                    // staging key shows that it was not removed since it started.
                    val key = MegaApiJava.handleToBase64(handle)
                    if (item.status == TransferStatus.COMPLETED && index.stagedKey(id) == key) {
                        catching { index.commit(id, key) }
                            .onFailure { transfers.onError?.invoke(it.message ?: "Cannot save offline copy") }
                    }
                    index.discard(id)
                    refresh()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // Conflated: a slow reconcile must not hold back the shared SDK event flow.
            transfersResumed.conflate().collect { catching { reconcile() } }
        }
    }

    /** "offline:<handle>:<id>" */
    private fun parse(appData: String): Pair<Long, String>? {
        val parts = appData.split(':')
        val handle = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val id = parts.getOrNull(2)?.takeIf(OfflineFiles::isSafeId) ?: return null
        return handle to id
    }

    /**
     * After a restart the SDK resumes the files of interrupted downloads, but not their folder
     * transfers. A resumed single-file download continues. A folder downloads again, because
     * its files cannot prove that the folder is complete. Staging that no download uses goes.
     */
    private suspend fun reconcile() = operations.withLock {
        val used = HashSet(pending.values)
        val restart = LinkedHashSet<Long>()
        for (t in transfers.queuedDownloads()) {
            if (t.purpose != Purpose.OFFLINE) continue
            val (handle, id) = parse(t.appData) ?: continue
            if (id in used) continue
            when {
                index.stagedKey(id) != MegaApiJava.handleToBase64(handle) -> transfers.cancel(t.tag)
                t.nodeHandle == handle && !t.isFolder -> {
                    pending.putIfAbsent(handle, id)
                    used += id
                }
                else -> {
                    transfers.cancel(t.tag)
                    restart += handle
                }
            }
        }
        for (id in index.stagedIds()) {
            if (id in used) continue
            index.stagedKey(id)?.let { restart += MegaApiJava.base64ToHandle(it) }
            index.discard(id)
        }
        for (h in restart) {
            if (h !in pending && h !in _handles.value) catching { start(h) }
        }
        refresh()
    }

    /** Call with [operations] held. */
    private suspend fun start(handle: Long) {
        val id = UUID.randomUUID().toString()
        pending[handle] = id
        try {
            transfers.download(listOf(handle), index.stage(id, MegaApiJava.handleToBase64(handle)), "${Purpose.OFFLINE}:$handle:$id")
        } catch (e: Exception) {
            pending.remove(handle)
            index.discard(id)
            throw e
        }
    }

    fun dirFor(handle: Long): File = File(root, MegaApiJava.handleToBase64(handle))

    /** The local copy of a node, if it is available offline. */
    fun fileFor(handle: Long): File? = index.entry(MegaApiJava.handleToBase64(handle))

    suspend fun makeAvailable(handles: Collection<Long>) = withContext(Dispatchers.IO) {
        operations.withLock {
            for (h in handles.distinct()) {
                if (h in _handles.value || h in pending) continue
                start(h)
            }
        }
    }

    suspend fun remove(handles: Collection<Long>) = withContext(Dispatchers.IO) {
        operations.withLock {
            handles.forEach { h ->
                // Invalidate the completion first: a late SDK finish cannot re-create the copy.
                // This includes downloads that the SDK resumed after a restart.
                val key = MegaApiJava.handleToBase64(h)
                pending.remove(h)
                transfers.cancelPurposePrefix("${Purpose.OFFLINE}:$h:")
                index.stagedIds().filter { index.stagedKey(it) == key }.forEach(index::discard)
                index.removePrevious(key)
                if (!dirFor(h).deleteRecursively()) throw java.io.IOException("Cannot remove offline copy")
            }
            refresh()
        }
    }

    /** Top-level offline items, or the content of [folder] inside an offline folder. */
    suspend fun list(folder: File? = null): List<OfflineEntry> = withContext(Dispatchers.IO) {
        if (folder == null) {
            root.listFiles().orEmpty().filter { !it.name.startsWith('.') }.mapNotNull { dir ->
                val key = dir.name
                val handle = MegaApiJava.base64ToHandle(key)
                if (MegaApiJava.handleToBase64(handle) != key) return@mapNotNull null
                val complete = index.entry(key)
                val copy = complete ?: dir.listFiles()?.firstOrNull { it.name != ".complete" } ?: return@mapNotNull null
                entry(copy, handle).copy(verified = complete != null)
            }.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        } else {
            require(folder.canonicalPath.startsWith(root.canonicalPath + File.separator))
            folder.listFiles().orEmpty()
                .map { entry(it, MegaApiJava.INVALID_HANDLE) }
                .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        }
    }

    private fun entry(f: File, handle: Long) = OfflineEntry(
        handle = handle,
        file = f,
        name = f.name,
        isFolder = f.isDirectory,
        type = FileType.of(f.name, f.isDirectory),
        size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length(),
        modified = f.lastModified(),
    )

    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun clear() = withContext(Dispatchers.IO) {
        operations.withLock {
            transfers.cancelPurposePrefix("${Purpose.OFFLINE}:")
            pending.clear()
            if (!root.deleteRecursively()) throw java.io.IOException("Cannot clear offline copies")
            root.ensureDirectory()
            refresh()
        }
    }

    private fun refresh() {
        _handles.value = index.keys().map { MegaApiJava.base64ToHandle(it) }.toSet()
    }
}
