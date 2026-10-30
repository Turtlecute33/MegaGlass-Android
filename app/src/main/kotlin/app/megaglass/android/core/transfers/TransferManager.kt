package app.megaglass.android.core.transfers

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.storage.VerifiedFiles
import app.megaglass.android.core.storage.ensureDirectory
import app.megaglass.android.core.storage.localFileName
import app.megaglass.android.core.storage.pathComponent
import app.megaglass.android.core.storage.availableName
import app.megaglass.android.core.storage.deleteUploadCopy
import app.megaglass.android.core.storage.isContentUri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.megaglass.android.core.storage.LocalSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaCancelToken
import nz.mega.sdk.MegaDownloadItemList
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaEvent
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import nz.mega.sdk.MegaUploadOptions
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    const val TEMP_SUFFIX = ":tmp"

    fun of(appData: String?): String = appData?.substringBefore(':').orEmpty()
}

/** A download into the same file runs already, or a cancelled one has not ended in the SDK yet. */
class AlreadyPreparingException : IOException("This file is already being prepared")

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
    /** Hidden viewer downloads still need the background job to stay alive. */
    val internalTransfers: Int = 0,
    val viewerProgress: Map<String, Float> = emptyMap(),
    /** Uploads that are not in the queue yet (local copies), and transfers that the SDK has not started yet. */
    val preparing: Int = 0,
) {
    val totalBytes: Long get() = active.sumOf { it.total }
    val transferredBytes: Long get() = active.sumOf { it.transferred }
    val progress: Float get() = totalBytes.let { if (it > 0) transferredBytes.toFloat() / it else 0f }
    val isIdle: Boolean get() = active.isEmpty() && internalTransfers == 0 && preparing == 0
    val downloads: Int get() = active.count { it.direction == Direction.DOWNLOAD }
    val uploads: Int get() = active.count { it.direction == Direction.UPLOAD }
}

/**
 * Tracks every transfer of the SDK and starts new ones. The SDK runs the transfers
 * (parallel connections, chunk encryption and MACs in native code); this class only keeps a
 * snapshot for the UI and the notification, rebuilt at most [TICK_MS] apart.
 */
class TransferManager(
    private val mega: Mega,
    /** For the error text of failed transfers. */
    private val res: Resources,
    private val scope: CoroutineScope,
    private val temporaryRoots: List<File> = emptyList(),
) {

    private val api get() = mega.api

    /** [folderTag] is the key of the folder transfer this file belongs to; 0 for top-level transfers. */
    private class Record(var item: TransferItem, val folderTag: Int)

    private val lock = Any()
    private val records = LinkedHashMap<Int, Record>()
    private val finished = ArrayDeque<TransferItem>()
    /** A change since the last snapshot. Without changes the snapshot loop waits and does not poll (MEGAGLASS-PERF-009). */
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private fun markDirty() { changed.trySend(Unit) }
    @Volatile private var overQuota = false

    private val _state = MutableStateFlow(TransfersState())
    val state: StateFlow<TransfersState> = _state.asStateFlow()

    /** Same as `state.value.isIdle`, without the delay of the snapshot. */
    val isIdleNow: Boolean get() = synchronized(lock) { records.isEmpty() } && pending.get() == 0

    private val _resumed = MutableStateFlow(false)
    /**
     * The nodes of the session are current, so the SDK has resumed the queue of an earlier run.
     * Before that, the queue can look empty although transfers wait to resume.
     */
    val resumed: StateFlow<Boolean> = _resumed.asStateFlow()

    /**
     * Work that the queue does not show yet: an upload while its local copy is made, and a
     * transfer from its start request until the SDK reports it. The transfer job counts it.
     */
    private val pending = AtomicInteger()

    private val _completed = MutableSharedFlow<TransferItem>(extraBufferCapacity = 256)
    // SDK callbacks cannot suspend. A single drain preserves completion order without dropping
    // events when an observer is writing metadata or updating a foreground notification.
    private val completionQueue = Channel<Pair<Long, TransferItem>>(Channel.UNLIMITED)
    private val sessionEpoch = AtomicLong()
    @Volatile private var accepting = true
    /** Every top-level transfer that finished (any status). */
    val completed: SharedFlow<TransferItem> = _completed

    /** Called when transfers start, so the app can keep the process alive (see TransferJobService). */
    var onActive: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var retryOffline: (suspend (Long) -> Unit)? = null

    /** One unit of [pending] work. [done] counts once, and not after the session ended. */
    private inner class Pending {
        init { pending.incrementAndGet(); markDirty() }
        private val session = sessionEpoch.get()
        private val open = AtomicBoolean(true)

        fun done() {
            if (open.getAndSet(false) && session == sessionEpoch.get()) {
                pending.updateAndGet { maxOf(0, it - 1) }
                markDirty()
            }
        }
    }

    /**
     * Runs [block], which makes the local copies for uploads, as pending work: the transfer job
     * starts now, while the app is visible, and does not end before the uploads are queued.
     */
    suspend fun <T> preparing(block: suspend () -> T): T {
        val work = Pending()
        onActive?.invoke()
        try {
            return block()
        } finally {
            work.done()
        }
    }

    private val targets = HashSet<String>()
    private val tokens = HashMap<Int, MegaCancelToken>()
    private val uploadGate = Mutex()
    private val reservedNames = HashSet<Pair<Long, String>>()

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
        if (t.folderTransferTag <= 0) return 0
        val parent = key(api, t.folderTransferTag)
        return if (parent !in batches && records[parent]?.item?.isFolder == true) parent else 0
    }

    private val sink = object : MegaTransferListenerInterface {
        override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {
            if (!accepting || skip(transfer)) return
            // Earlier versions uploaded camera media from the file path, with the location data.
            // The SDK resumes such uploads after the nodes are current; they stop here, and the
            // next camera uploads run queues the media again without it.
            if (transfer.type == MegaTransfer.TYPE_UPLOAD && Purpose.of(transfer.appData) == Purpose.CAMERA &&
                !isContentUri(transfer.path.orEmpty())) api.cancelTransferByTag(transfer.tag)
            val k = key(api, transfer.tag)
            val first = synchronized(lock) {
                if (isBatch(transfer)) {
                    batches += k
                    return@synchronized false
                }
                val parent = parentOf(api, transfer)
                records[k] = Record(transfer.toItem(k), parent)
                // A file of a folder transfer stops with its folder. The files of a batch share
                // its token too, but each is its own item: Cancel stops only that file (by tag).
                if (parent != 0) tokens[parent]?.let { tokens[k] = it }
                records.size == 1
            }
            markDirty()
            // Once per busy period: a folder transfer starts thousands of files.
            if (first) onActive?.invoke()
        }

        override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
            if (!accepting || skip(transfer)) return
            val k = key(api, transfer.tag)
            synchronized(lock) {
                if (k in batches || isBatch(transfer)) return
                val r = records[k]
                if (r == null) records[k] = Record(transfer.toItem(k), parentOf(api, transfer))
                else r.item = transfer.toItem(k, r.item.fileCount)
            }
            markDirty()
        }

        override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            if (e.errorCode == MegaError.API_EOVERQUOTA) overQuota = true
            onTransferUpdate(api, transfer)
        }

        override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            if (!accepting || skip(transfer)) return
            val error = if (e.errorCode == MegaError.API_OK) null
            else res.userMessage(MegaException(e.errorCode, e.errorString.orEmpty(), fromSdk = true))
            val k = key(api, transfer.tag)
            var done: TransferItem? = null
            synchronized(lock) {
                if (batches.remove(k) || isBatch(transfer)) return@synchronized
                val r = records.remove(k)
                tokens.remove(k)
                targets.remove(transfer.path)
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
            markDirty()
            done?.let { completionQueue.trySend(sessionEpoch.get() to it) }
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
            markDirty()
        }
    }

    init {
        mega.transferSink = sink
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mega.events.collect { if (it.type == MegaEvent.EVENT_NODES_CURRENT) _resumed.value = true }
        }
        scope.launch(Dispatchers.IO) {
            for ((epoch, item) in completionQueue) {
                if (!accepting || epoch != sessionEpoch.get()) continue
                if (item.direction == Direction.UPLOAD && item.status == TransferStatus.COMPLETED && !item.isFolder &&
                    item.appData.endsWith(Purpose.TEMP_SUFFIX)) {
                    catching { deleteUploadCopy(File(item.localPath), temporaryRoots) }
                        .onFailure { onError?.invoke("Upload completed, but its local copy could not be removed") }
                }
                var result = item
                if (item.status == TransferStatus.COMPLETED && item.direction == Direction.DOWNLOAD && !item.isFolder &&
                    item.purpose in setOf(Purpose.PREVIEW, Purpose.OPEN, Purpose.SHARE)) {
                    try {
                        val file = File(item.localPath)
                        if (!file.isFile || file.length() != item.total) throw IOException("Downloaded file is incomplete")
                        // The SDK sets the date of the cloud file. The cache trim needs the download time:
                        // it removes the oldest copies first and keeps the copies of the last minutes (MEGAGLASS-PERF-012).
                        file.setLastModified(System.currentTimeMillis())
                        VerifiedFiles.mark(file)
                    } catch (e: Exception) {
                        result = item.copy(status = TransferStatus.FAILED, error = res.userMessage(e))
                    }
                }
                if (!accepting || epoch != sessionEpoch.get()) continue
                if (result != item) synchronized(lock) {
                    finished.removeAll { it.tag == item.tag && it.finishedAt == item.finishedAt }
                    finished.addFirst(result)
                    while (finished.size > MAX_HISTORY) finished.removeLast()
                    markDirty()
                }
                _completed.emit(result)
            }
        }
        markDirty()
        scope.launch(Dispatchers.Default) {
            // A change during a snapshot sends again, so the next snapshot comes after TICK_MS.
            for (signal in changed) {
                val epoch = sessionEpoch.get()
                val current = snapshot()
                synchronized(lock) {
                    if (accepting && epoch == sessionEpoch.get()) _state.value = current
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
            internalTransfers = synchronized(lock) { records.values.count { it.folderTag == 0 && it.item.purpose == Purpose.PREVIEW } },
            viewerProgress = synchronized(lock) { records.values.filter { it.item.purpose == Purpose.PREVIEW }.associate { it.item.localPath to it.item.progress } },
            preparing = pending.get(),
        )
    }

    /**
     * Downloads nodes into [dir]. Files that already exist with the same content are skipped;
     * different files with the same name get a " (1)" suffix.
     */
    suspend fun download(handles: Collection<Long>, dir: File, purpose: String = Purpose.DOWNLOAD, startFirst: Boolean = false) =
        withContext(Dispatchers.IO) {
            check(accepting) { "The session has ended" }
            dir.ensureDirectory()
            val path = dir.absolutePath + File.separator
            val nodes = handles.distinct().map { h -> api.getNodeByHandle(h) ?: throw MegaException(MegaError.API_ENOENT, "Item not found") }
            if (nodes.isEmpty()) return@withContext
            fun appData(node: MegaNode) = if (purpose == Purpose.OFFLINE) "${Purpose.OFFLINE}:${node.handle}" else purpose
            val token = MegaCancelToken.createInstance()
            val work = Pending()
            try {
                if (nodes.size == 1) {
                    val node = nodes.single()
                    // A batch folder's appData belongs to every file, and its unnamed container has
                    // no appData. A single-node transfer reports completion for the whole folder.
                    api.startDownload(node, path, localFileName(node.name ?: "file"), appData(node), startFirst, token,
                        MegaTransfer.COLLISION_CHECK_FINGERPRINT, MegaTransfer.COLLISION_RESOLUTION_NEW_WITH_N,
                        cancellationListener(token, work = work))
                } else {
                    // The selected items get safe names; the SDK names the files inside folders.
                    val items = MegaDownloadItemList.createInstance()
                    for (node in nodes) items.add(node, path, localFileName(node.name ?: "file"), appData(node), startFirst)
                    api.startDownloadBatch(items, token,
                        MegaTransfer.COLLISION_CHECK_FINGERPRINT, MegaTransfer.COLLISION_RESOLUTION_NEW_WITH_N,
                        cancellationListener(token, work = work))
                }
            } catch (e: Exception) {
                work.done()
                throw e
            }
            onActive?.invoke()
        }

    /** Downloads one node to an exact private cache path. */
    suspend fun downloadTo(handle: Long, target: File, purpose: String, folderLink: Boolean = false) =
        withContext(Dispatchers.IO) {
            val source = if (folderLink) mega.folderApi else api
            val node = source.getNodeByHandle(handle) ?: throw MegaException(MegaError.API_ENOENT, "Item not found")
            downloadNodeTo(source, node, target, purpose)
        }

    /** Public nodes are copied by the SDK and need not be present in the account tree. */
    suspend fun downloadPublicNodeTo(node: MegaNode, target: File, purpose: String) = withContext(Dispatchers.IO) {
        downloadNodeTo(api, node, target, purpose)
    }

    private fun downloadNodeTo(source: MegaApiJava, node: MegaNode, target: File, purpose: String) {
        check(accepting) { "The session has ended" }
        val parent = target.parentFile ?: throw IOException("Invalid download destination")
        parent.ensureDirectory()
        val path = target.absolutePath
        if (!synchronized(lock) { targets.add(path) }) throw AlreadyPreparingException()
        val work = Pending()
        try {
            VerifiedFiles.forget(target)
            val token = MegaCancelToken.createInstance()
            source.startDownload(node, parent.absolutePath + File.separator, target.name, purpose, true, token,
                MegaTransfer.COLLISION_CHECK_ASSUMEDIFFERENT, MegaTransfer.COLLISION_RESOLUTION_OVERWRITE,
                cancellationListener(token, work = work))
        } catch (e: Exception) {
            work.done()
            synchronized(lock) { targets.remove(path) }
            throw e
        }
        onActive?.invoke()
    }

    /** Uploads local files or folders into [parent]. A folder uploads with all its contents. */
    suspend fun upload(sources: List<LocalSource>, parent: Long, purpose: String = Purpose.UPLOAD, startFirst: Boolean = false) =
        withContext(Dispatchers.IO) { uploadGate.withLock {
            check(accepting) { "The session has ended" }
            val node = api.getNodeByHandle(parent) ?: throw MegaException(MegaError.API_ENOENT, "Folder not found")
            if (!node.isFolder) throw IOException("Upload destination is not a folder")
            sources.forEach { s ->
                val file = File(s.path)
                if (!s.isContentUri && (!file.exists() || !file.canRead())) throw IOException("Cannot read upload source: ${s.name}")
            }
            for (s in sources) {
                // The exact name: a Save in the text editor must replace the file of that name.
                val requested = pathComponent(s.name)
                // A Save in the text editor explicitly replaces a file. Other uploads preserve
                // existing names even if the account has file versioning disabled.
                val name = if (Purpose.of(purpose) == Purpose.EDIT) requested else availableName(requested, File(s.path).isDirectory) { candidate ->
                    api.getChildNode(node, candidate) != null || synchronized(lock) { parent to candidate in reservedNames }
                }
                val reservation = parent to name
                synchronized(lock) { reservedNames += reservation }
                val options = MegaUploadOptions().apply {
                    setAppData(if (s.temporary && !purpose.endsWith(Purpose.TEMP_SUFFIX)) purpose + Purpose.TEMP_SUFFIX else purpose)
                    // The SDK deletes temporary sources on failure and cancellation too. Keep
                    // them for Retry, and remove private copies on confirmed completion above.
                    setIsSourceTemporary(false)
                    setStartFirst(startFirst)
                    setFileName(name)
                }
                val token = MegaCancelToken.createInstance()
                val work = Pending()
                try {
                    api.startUpload(s.path, node, token, options, cancellationListener(token, reservation, work))
                } catch (e: Exception) {
                    work.done()
                    synchronized(lock) { reservedNames.remove(reservation) }
                    throw e
                }
            }
            if (sources.isNotEmpty()) onActive?.invoke()
        } }

    fun pauseAll(pause: Boolean) {
        scope.launch(Dispatchers.IO) {
            catching { api.await { pauseTransfers(pause, it) } }.onFailure { onError?.invoke(res.userMessage(it)) }
            if (synchronized(lock) { records.keys.any { it < 0 } }) {
                catching { mega.folderApi.await { pauseTransfers(pause, it) } }.onFailure { onError?.invoke(res.userMessage(it)) }
            }
            markDirty()
        }
    }

    private fun owner(tag: Int): Pair<MegaApiJava, Int> = if (tag >= 0) api to tag else mega.folderApi to -tag

    fun pause(tag: Int, pause: Boolean) {
        val (a, t) = owner(tag)
        scope.launch(Dispatchers.IO) {
            catching { a.await { pauseTransferByTag(t, pause, it) } }.onFailure { onError?.invoke(res.userMessage(it)) }
            markDirty()
        }
    }

    fun cancel(tag: Int) {
        synchronized(lock) { tokens[tag] }?.let { it.cancel(); return }
        val (a, t) = owner(tag)
        a.cancelTransferByTag(t)
    }

    /** Cancels the downloads into [localPath]: viewer downloads, which the Transfers screen does not list. */
    fun cancelDownloadTo(localPath: String) {
        synchronized(lock) { records.filterValues { it.item.localPath == localPath }.keys.toList() }.forEach(::cancel)
    }

    /** Cancels the uploads started for [purpose]: camera uploads stop when their work stops (Wi-Fi lost, not charging). */
    fun cancelUploads(purpose: String) {
        synchronized(lock) { records.filterValues { it.item.direction == Direction.UPLOAD && it.item.purpose == purpose }.keys.toList() }.forEach(::cancel)
    }

    /** Cancels the uploads for [purpose] in the SDK queue whose local path matches, also those resumed from an earlier process. */
    fun cancelUploads(purpose: String, path: (String) -> Boolean) {
        api.getTransfers(MegaTransfer.TYPE_UPLOAD)?.filter { Purpose.of(it.appData) == purpose && path(it.path.orEmpty()) }
            ?.forEach { cancel(key(api, it.tag)) }
    }

    fun cancelPurposePrefix(prefix: String) {
        synchronized(lock) { records.filterValues { it.item.appData.startsWith(prefix) }.keys.toList() }.forEach(::cancel)
    }

    /** Local paths of the uploads in the queue. */
    fun uploadingPaths(): Set<String> = api.getTransfers(MegaTransfer.TYPE_UPLOAD)?.mapNotNullTo(HashSet()) { it.path }.orEmpty()

    /** The file downloads in the SDK queue, also those resumed from an earlier process. */
    fun queuedDownloads(): List<TransferItem> =
        api.getTransfers(MegaTransfer.TYPE_DOWNLOAD)?.map { it.toItem(key(api, it.tag)) }.orEmpty()

    fun cancelAll() {
        synchronized(lock) { (records.keys + batches).toList() }.forEach(::cancel)
    }

    /**
     * Cancel tokens avoid the SDK's documented deadlock during folder enumeration. [work] ends
     * when the transfer is in the queue that the transfer job watches, or when it ends.
     */
    private fun cancellationListener(token: MegaCancelToken? = null, reservation: Pair<Long, String>? = null, work: Pending? = null) = object : MegaTransferListenerInterface {
        override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {
            val owned = token ?: transfer.cancelToken
            if (owned != null) synchronized(lock) { tokens[key(api, transfer.tag)] = owned }
            // The listed items of a batch start after its unlisted container: it stays pending to its end.
            if (!isBatch(transfer)) work?.done()
        }
        override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            synchronized(lock) {
                tokens.remove(key(api, transfer.tag)); targets.remove(transfer.path)
                if (reservation != null) reservedNames.remove(reservation)
            }
            work?.done()
        }
        override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {}
        override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {}
        override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray) = false
        override fun onFolderTransferUpdate(api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
            createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?) {}
    }

    fun moveToFirst(tag: Int) {
        val (a, t) = owner(tag)
        a.moveTransferToFirstByTag(t)
    }

    /**
     * Starts a failed or cancelled transfer again. Returns false, and keeps the item in the list,
     * when it cannot start: the node is not in the account (link downloads), the source file of
     * an upload is gone, or the target folder is gone.
     */
    suspend fun retry(item: TransferItem): Boolean {
        if (item.tag < 0) return false
        val ready = withContext(Dispatchers.IO) {
            if (item.direction == Direction.DOWNLOAD) {
                File(item.localPath).parentFile != null && api.getNodeByHandle(item.nodeHandle) != null
            } else {
                (isContentUri(item.localPath) || File(item.localPath).exists()) && api.getNodeByHandle(item.parentHandle) != null
            }
        }
        if (!ready) return false
        if (item.direction == Direction.DOWNLOAD && item.purpose == Purpose.OFFLINE) {
            val retry = retryOffline ?: return false
            val handle = item.appData.substringAfter(':', "").substringBefore(':').toLongOrNull() ?: item.nodeHandle
            retry(handle)
            removeFromHistory(item)
            return true
        }
        if (item.direction == Direction.DOWNLOAD) {
            download(listOf(item.nodeHandle), File(item.localPath).parentFile!!, item.purpose.ifEmpty { Purpose.DOWNLOAD })
        } else {
            upload(listOf(LocalSource(item.localPath, item.name, item.appData.endsWith(Purpose.TEMP_SUFFIX))), item.parentHandle, item.appData)
        }
        removeFromHistory(item)
        return true
    }

    fun removeFromHistory(item: TransferItem) {
        synchronized(lock) { finished.removeAll { it.tag == item.tag && it.finishedAt == item.finishedAt } }
        markDirty()
    }

    fun clearHistory() {
        synchronized(lock) { finished.clear() }
        markDirty()
    }

    fun beginSession() { accepting = true }

    fun endSession() {
        accepting = false
        _resumed.value = false
        sessionEpoch.incrementAndGet()
        pending.set(0)
        cancelAll()
        synchronized(lock) {
            records.clear(); finished.clear(); batches.clear(); targets.clear(); reservedNames.clear(); tokens.clear()
        }
        overQuota = false
        _state.value = TransfersState()
        markDirty()
    }

    private companion object {
        const val TICK_MS = 250L
        const val MAX_HISTORY = 300
    }
}
