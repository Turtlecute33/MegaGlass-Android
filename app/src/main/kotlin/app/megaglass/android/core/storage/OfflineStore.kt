package app.megaglass.android.core.storage

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
)

/**
 * Files and folders kept on the device. Each one lives in filesDir/offline/<handle>/<name>, in
 * app-private storage that other apps cannot read. The folder name is the node handle, so the
 * index is the folder list and no database is needed.
 */
class OfflineStore(context: Context, private val transfers: TransferManager, scope: CoroutineScope) {

    private val root = File(context.filesDir, "offline").apply { mkdirs() }

    private val _handles = MutableStateFlow<Set<Long>>(emptySet())
    /** Handles of the nodes that are available offline (complete downloads only). */
    val handles: StateFlow<Set<Long>> = _handles.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
        scope.launch {
            transfers.completed.collect { item ->
                if (item.purpose == Purpose.OFFLINE) {
                    if (item.status != TransferStatus.COMPLETED) {
                        item.appData.substringAfter(':', "").toLongOrNull()?.let { dirFor(it).deleteRecursively() }
                    }
                    withContext(Dispatchers.IO) { refresh() }
                }
            }
        }
    }

    fun dirFor(handle: Long): File = File(root, MegaApiJava.handleToBase64(handle))

    /** The local copy of a node, if it is available offline. */
    fun fileFor(handle: Long): File? =
        dirFor(handle).listFiles()?.firstOrNull { !it.name.endsWith(".mega") && !it.name.startsWith(".") }

    suspend fun makeAvailable(handles: Collection<Long>) {
        for (h in handles) {
            if (h in _handles.value) continue
            transfers.download(listOf(h), dirFor(h), Purpose.OFFLINE)
        }
    }

    suspend fun remove(handles: Collection<Long>) = withContext(Dispatchers.IO) {
        handles.forEach { dirFor(it).deleteRecursively() }
        refresh()
    }

    /** Top-level offline items, or the content of [folder] inside an offline folder. */
    suspend fun list(folder: File? = null): List<OfflineEntry> = withContext(Dispatchers.IO) {
        if (folder == null) {
            root.listFiles().orEmpty().mapNotNull { dir ->
                val handle = runCatching { MegaApiJava.base64ToHandle(dir.name) }.getOrNull() ?: return@mapNotNull null
                val f = dir.listFiles()?.firstOrNull { !it.name.startsWith(".") } ?: return@mapNotNull null
                entry(f, handle)
            }.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        } else {
            folder.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
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
        root.deleteRecursively()
        root.mkdirs()
        refresh()
    }

    private fun refresh() {
        _handles.value = root.listFiles().orEmpty()
            .filter { dir -> dir.listFiles()?.any { !it.name.startsWith(".") } == true }
            .mapNotNull { runCatching { MegaApiJava.base64ToHandle(it.name) }.getOrNull() }
            .toSet()
    }
}
