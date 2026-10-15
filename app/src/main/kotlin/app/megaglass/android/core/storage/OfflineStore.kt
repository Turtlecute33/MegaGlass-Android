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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class OfflineStore(context: Context, private val transfers: TransferManager, scope: CoroutineScope) {

    private val root = File(context.filesDir, "offline").apply { mkdirs() }

    private val index = OfflineFiles(root)
    private val operations = Mutex()
    private val pending = mutableMapOf<Long, String>()

    private val _handles = MutableStateFlow<Set<Long>>(emptySet())
    /** Handles of the nodes that are available offline (complete downloads only). */
    val handles: StateFlow<Set<Long>> = _handles.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
        scope.launch(Dispatchers.IO) {
            transfers.completed.collect { item ->
                if (item.purpose != Purpose.OFFLINE) return@collect
                val parts = item.appData.split(':')
                val handle = parts.getOrNull(1)?.toLongOrNull() ?: return@collect
                val id = parts.getOrNull(2) ?: return@collect
                operations.withLock {
                    if (pending[handle] != id) return@withLock
                    pending.remove(handle)
                    if (item.status == TransferStatus.COMPLETED) {
                        catching { index.commit(id, MegaApiJava.handleToBase64(handle)) }
                            .onFailure { transfers.onError?.invoke(it.message ?: "Cannot save offline copy") }
                    }
                    index.discard(id)
                    refresh()
                }
            }
        }
    }

    fun dirFor(handle: Long): File = File(root, MegaApiJava.handleToBase64(handle))

    /** The local copy of a node, if it is available offline. */
    fun fileFor(handle: Long): File? = index.entry(MegaApiJava.handleToBase64(handle))

    suspend fun makeAvailable(handles: Collection<Long>) = withContext(Dispatchers.IO) {
        operations.withLock {
            for (h in handles.distinct()) {
                if (h in _handles.value || h in pending) continue
                val id = UUID.randomUUID().toString()
                pending[h] = id
                try {
                    transfers.download(listOf(h), index.stage(id), "${Purpose.OFFLINE}:$h:$id")
                } catch (e: Exception) {
                    pending.remove(h)
                    index.discard(id)
                    throw e
                }
            }
        }
    }

    suspend fun remove(handles: Collection<Long>) = withContext(Dispatchers.IO) {
        operations.withLock {
            handles.forEach { h ->
                // Invalidate the completion first: a late SDK finish cannot re-create the copy.
                pending.remove(h)?.let { id ->
                    transfers.cancelPurpose("${Purpose.OFFLINE}:$h:$id")
                    index.discard(id)
                }
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
            pending.forEach { (h, id) -> transfers.cancelPurpose("${Purpose.OFFLINE}:$h:$id") }
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
