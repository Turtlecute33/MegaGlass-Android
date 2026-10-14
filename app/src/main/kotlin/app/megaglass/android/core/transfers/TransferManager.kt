package app.megaglass.android.core.transfers

import androidx.compose.runtime.Immutable
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.storage.LocalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaDownloadItemList
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import nz.mega.sdk.MegaUploadOptions
import java.io.File

enum class Direction { DOWNLOAD, UPLOAD }

enum class TransferStatus { QUEUED, ACTIVE, PAUSED, RETRYING, COMPLETING, COMPLETED, FAILED, CANCELLED }

/** Why a transfer was started. Stored in the SDK "appData" field, so it survives restarts. */
object Purpose {
    const val DOWNLOAD = "dl"
    /** Open with another app when done. */
    const val OPEN = "open"
    /** Share with another app when done. */
    const val SHARE = "share"
    /** Make available offline; the node handle follows after a colon. */
    const val OFFLINE = "offline"
    const val UPLOAD = "ul"
    const val CAMERA = "cu"
    /** Saved from the text editor. */
    const val EDIT = "edit"
    /** Copy in the cache for an in-app viewer (PDF, text, full-size image). */
    const val PREVIEW = "preview"

    fun of(appData: String?): String = appData?.substringBefore(':').orEmpty()
}

@Immutable
data class TransferItem(
    val tag: Int,
    val direction: Direction,
    val name: String,
    /** Local path: the file for uploads, the target file for downloads. */
    val localPath: String,
    val nodeHandle: Long,
    val parentHandle: Long,
    val status: TransferStatus,
    val transferred: Long,
    val total: Long,
    val speed: Long,
    val isFolder: Boolean,
    val appData: String,
    val error: String? = null,
    val finishedAt: Long = 0,
    val fileCount: Long = 0,
) {
    val progress: Float get() = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f
    val purpose: String get() = Purpose.of(appData)
    val isFinished get() = status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELLED
}

@Immutable
data class TransfersState(
    val active: List<TransferItem> = emptyList(),
    val finished: List<TransferItem> = emptyList(),
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val downloadsPaused: Boolean = false,
    val uploadsPaused: Boolean = false,
    val overQuota: Boolean = false,
) {
    val totalBytes: Long get() = active.sumOf { it.total }
    val transferredBytes: Long get() = active.sumOf { it.transferred }
    val progress: Float get() = totalBytes.let { if (it > 0) transferredBytes.toFloat() / it else 0f }
    val isIdle: Boolean get() = active.isEmpty()
    val downloads: Int get() = active.count { it.direction == Direction.DOWNLOAD }
    val uploads: Int get() = active.count { it.direction == Direction.UPLOAD }
}

/**
 * Tracks every transfer of the SDK and starts new ones. The SDK runs the transfers
 * (parallel connections, chunk encryption and MACs in native code); this class only keeps a
 * snapshot for the UI and the notification, rebuilt at most [TICK_MS] apart.
 */
class TransferManager(private val mega: Mega, private val scope: CoroutineScope) {

    private val api get() = mega.api

    /** [folderTag] is the key of the folder transfer this file belongs to; 0 for top-level transfers. */
    private class Record(var item: TransferItem, val folderTag: Int)

    private val lock = Any()
    private val records = LinkedHashMap<Int, Record>()
    private val finished = ArrayDeque<TransferItem>()
    @Volatile private var dirty = true
    @Volatile private var overQuota = false

    private val _state = MutableStateFlow(TransfersState())
    val state: StateFlow<TransfersState> = _state.asStateFlow()

    private val _completed = MutableSharedFlow<TransferItem>(extraBufferCapacity = 256)
    /** Every top-level transfer that finished (any status). */
    val completed: SharedFlow<TransferItem> = _completed

    /** Called when transfers start, so the app can keep the process alive (see TransferJobService). */
    var onActive: (() -> Unit)? = null

    /**
     * Transfers of the folder link instance get negative tags, so that the two SDK instances
     * cannot collide and actions go to the right instance.
     */
    private fun key(api: MegaApiJava, tag: Int) = if (api === mega.api) tag else -tag

    /** Keys of batch containers (startDownloadBatch): never listed; their files are top-level items. */
    private val batches = HashSet<Int>()

    /** A batch container has no name of its own; a real folder transfer has the folder name. */
    private fun isBatch(t: MegaTransfer) = t.isFolderTransfer && t.fileName.isNullOrEmpty()

    /** Key of the folder transfer that owns [t], or 0 when [t] is a top-level item. Call with [lock] held. */
    private fun parentOf(api: MegaApiJava, t: MegaTransfer): Int {
        if (t.folderTransferTag == 0) return 0
        val parent = key(api, t.folderTransferTag)
        return if (parent !in batches && records[parent]?.item?.isFolder == true) parent else 0
    }

    private val sink = object : MegaTransferListenerInterface {
        override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {
            if (skip(transfer)) return
            val k = key(api, transfer.tag)
            val first = synchronized(lock) {
                if (isBatch(transfer)) {
                    batches += k
                    return@synchronized false
                }
                records[k] = Record(transfer.toItem(k), parentOf(api, transfer))
                records.size == 1
            }
            dirty = true
            // Once per busy period: a folder transfer starts thousands of files.
            if (first) onActive?.invoke()
        }

        override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
            if (skip(transfer)) return
            val k = key(api, transfer.tag)
            synchronized(lock) {
                if (k in batches || isBatch(transfer)) return
                val r = records[k]
                if (r == null) records[k] = Record(transfer.toItem(k), parentOf(api, transfer))
                else r.item = transfer.toItem(k, r.item.fileCount)
            }
            dirty = true
        }

        override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            if (e.errorCode == MegaError.API_EOVERQUOTA) overQuota = true
            onTransferUpdate(api, transfer)
        }

        override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            if (skip(transfer)) return
            val error = if (e.errorCode == MegaError.API_OK) null else e.errorString
            val k = key(api, transfer.tag)
            var done: TransferItem? = null
            synchronized(lock) {
                if (batches.remove(k) || isBatch(transfer)) return@synchronized
                val r = records.remove(k)
                val item = transfer.toItem(k, r?.item?.fileCount ?: 0).copy(
                    status = when {
                        transfer.state == MegaTransfer.STATE_CANCELLED -> TransferStatus.CANCELLED
                        error != null -> TransferStatus.FAILED
                        else -> TransferStatus.COMPLETED
                    },
                    error = error,
                    finishedAt = System.currentTimeMillis(),
                    transferred = if (error == null) transfer.totalBytes else transfer.transferredBytes,
                )
                if ((r?.folderTag ?: parentOf(api, transfer)) == 0) {
                    finished.addFirst(item)
                    while (finished.size > MAX_HISTORY) finished.removeLast()
                    done = item
                }
                if (records.isEmpty()) overQuota = false
            }
            dirty = true
            done?.let { _completed.tryEmit(it) }
        }

        override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray) = false

        override fun onFolderTransferUpdate(
            api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
            createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?,
        ) {
            val k = key(api, transfer.tag)
            synchronized(lock) {
                val r = records[k] ?: return
                r.item = transfer.toItem(k, fileCount)
            }
            dirty = true
        }
    }

    init {
        mega.transferSink = sink
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (dirty) {
                    dirty = false
                    _state.value = snapshot()
                }
                delay(TICK_MS)
            }
        }
    }

    /** Streaming and local HTTP server transfers are not user transfers. */
    private fun skip(t: MegaTransfer) = t.isStreamingTransfer ||
        t.type == MegaTransfer.TYPE_LOCAL_HTTP_DOWNLOAD || t.type == MegaTransfer.TYPE_LOCAL_TCP_DOWNLOAD

    private fun MegaTransfer.toItem(key: Int, fileCount: Long = 0) = TransferItem(
        tag = key,
        direction = if (type == MegaTransfer.TYPE_UPLOAD) Direction.UPLOAD else Direction.DOWNLOAD,
        name = fileName ?: "",
        localPath = path ?: "",
        nodeHandle = nodeHandle,
        parentHandle = parentHandle,
        status = when (state) {
            MegaTransfer.STATE_QUEUED, MegaTransfer.STATE_NONE -> TransferStatus.QUEUED
            MegaTransfer.STATE_ACTIVE -> TransferStatus.ACTIVE
            MegaTransfer.STATE_PAUSED -> TransferStatus.PAUSED
            MegaTransfer.STATE_RETRYING -> TransferStatus.RETRYING
            MegaTransfer.STATE_COMPLETING -> TransferStatus.COMPLETING
            MegaTransfer.STATE_COMPLETED -> TransferStatus.COMPLETED
            MegaTransfer.STATE_CANCELLED -> TransferStatus.CANCELLED
            else -> TransferStatus.FAILED
        },
        transferred = transferredBytes,
        total = totalBytes,
        speed = speed,
        isFolder = isFolderTransfer,
        appData = appData ?: "",
        fileCount = fileCount,
    )

    /** Top-level items only: the files of a folder transfer add their bytes to the folder item. */
    private fun snapshot(): TransfersState {
        val active: List<TransferItem>
        val history: List<TransferItem>
        synchronized(lock) {
            val folders = HashMap<Int, LongArray>()
            for (r in records.values) {
                if (r.folderTag != 0) {
                    val acc = folders.getOrPut(r.folderTag) { LongArray(3) }
                    acc[0] += r.item.transferred
                    acc[1] += r.item.total
                    acc[2] += r.item.speed
                }
            }
            // Viewer copies (Purpose.PREVIEW) are part of opening a file, not user transfers.
            active = records.values.filter { it.folderTag == 0 && it.item.purpose != Purpose.PREVIEW }.map { r ->
                val acc = folders[r.item.tag]
                if (r.item.isFolder && acc != null && acc[1] > r.item.total) {
                    r.item.copy(transferred = acc[0], total = acc[1], speed = acc[2])
                } else r.item
            }
            history = finished.filter { it.purpose != Purpose.PREVIEW }
        }
        return TransfersState(
            active = active,
            finished = history,
            downloadSpeed = api.currentDownloadSpeed.toLong(),
            uploadSpeed = api.currentUploadSpeed.toLong(),
            downloadsPaused = api.areTransfersPaused(MegaTransfer.TYPE_DOWNLOAD),
            uploadsPaused = api.areTransfersPaused(MegaTransfer.TYPE_UPLOAD),
            overQuota = overQuota,
        )
    }

    /**
     * Downloads nodes into [dir]. Files that already exist with the same content are skipped;
     * different files with the same name get a " (1)" suffix.
     */
    suspend fun download(handles: Collection<Long>, dir: File, purpose: String = Purpose.DOWNLOAD, startFirst: Boolean = false) =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val items = MegaDownloadItemList.createInstance()
            val path = dir.absolutePath + File.separator
            for (h in handles) {
                val node = api.getNodeByHandle(h) ?: continue
                val data = if (purpose == Purpose.OFFLINE) "${Purpose.OFFLINE}:$h" else purpose
                items.add(node, path, null, data, startFirst)
            }
            if (items.size() == 0) return@withContext
            api.startDownloadBatch(
                items, null,
                MegaTransfer.COLLISION_CHECK_FINGERPRINT,
                MegaTransfer.COLLISION_RESOLUTION_NEW_WITH_N,
                null,
            )
            onActive?.invoke()
        }

    /** Downloads one node to an exact file path (overwriting), for previews and the editor. */
    suspend fun downloadTo(handle: Long, target: File, purpose: String, folderLink: Boolean = false) =
        withContext(Dispatchers.IO) {
            val source = if (folderLink) mega.folderApi else api
            val node = source.getNodeByHandle(handle) ?: throw MegaException(MegaError.API_ENOENT, "Item not found")
            target.parentFile?.mkdirs()
            source.startDownload(
                node, target.parentFile!!.absolutePath + File.separator, target.name, purpose, true, null,
                MegaTransfer.COLLISION_CHECK_ASSUMEDIFFERENT, MegaTransfer.COLLISION_RESOLUTION_OVERWRITE,
            )
            onActive?.invoke()
        }

    /** Uploads local files or folders into [parent]. A folder uploads with all its contents. */
    suspend fun upload(sources: List<LocalSource>, parent: Long, purpose: String = Purpose.UPLOAD, startFirst: Boolean = false) =
        withContext(Dispatchers.IO) {
            val node = api.getNodeByHandle(parent) ?: throw MegaException(MegaError.API_ENOENT, "Folder not found")
            for (s in sources) {
                val options = MegaUploadOptions.createInstance().apply {
                    setAppData(purpose)
                    setIsSourceTemporary(s.temporary)
                    setStartFirst(startFirst)
                    if (s.name != File(s.path).name) setFileName(s.name)
                }
                api.startUpload(s.path, node, null, options)
            }
            if (sources.isNotEmpty()) onActive?.invoke()
        }

    fun pauseAll(pause: Boolean) {
        api.pauseTransfers(pause)
        dirty = true
    }

    private fun owner(tag: Int): Pair<MegaApiJava, Int> = if (tag >= 0) api to tag else mega.folderApi to -tag

    fun pause(tag: Int, pause: Boolean) {
        val (a, t) = owner(tag)
        a.pauseTransferByTag(t, pause, null)
        dirty = true
    }

    fun cancel(tag: Int) {
        val (a, t) = owner(tag)
        a.cancelTransferByTag(t)
    }

    fun cancelAll() {
        api.cancelTransfers(MegaTransfer.TYPE_DOWNLOAD)
        api.cancelTransfers(MegaTransfer.TYPE_UPLOAD)
    }

    fun moveToFirst(tag: Int) {
        val (a, t) = owner(tag)
        a.moveTransferToFirstByTag(t)
    }

    /** Starts a failed or cancelled transfer again. */
    suspend fun retry(item: TransferItem) {
        removeFromHistory(item)
        if (item.direction == Direction.DOWNLOAD) {
            val file = File(item.localPath)
            val dir = if (item.isFolder) file.parentFile else file.parentFile
            if (dir != null) download(listOf(item.nodeHandle), dir, item.purpose.ifEmpty { Purpose.DOWNLOAD })
        } else {
            val f = File(item.localPath)
            if (f.exists()) upload(listOf(LocalSource(f.absolutePath, item.name, false)), item.parentHandle, item.appData)
        }
    }

    fun removeFromHistory(item: TransferItem) {
        synchronized(lock) { finished.removeAll { it.tag == item.tag && it.finishedAt == item.finishedAt } }
        dirty = true
    }

    fun clearHistory() {
        synchronized(lock) { finished.clear() }
        dirty = true
    }

    private companion object {
        const val TICK_MS = 250L
        const val MAX_HISTORY = 300
    }
}
