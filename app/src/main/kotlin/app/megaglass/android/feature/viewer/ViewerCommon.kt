package app.megaglass.android.feature.viewer

import androidx.compose.runtime.Immutable
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.util.FileType
import app.megaglass.android.feature.photos.MediaFilter
import app.megaglass.android.feature.photos.PhotosRepository
import app.megaglass.android.ui.nav.MediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One item a viewer can show: a cloud node (account or folder link) or a local file. */
@Immutable
data class MediaEntry(
    val key: String,
    val handle: Long,
    val name: String,
    val type: FileType,
    val size: Long,
    val hasThumbnail: Boolean,
    val hasPreview: Boolean,
    val folderLink: Boolean,
    /** Set for local files. */
    val file: File? = null,
    /** Set for nodes of the user's account, for the node actions. */
    val node: NodeItem? = null,
) {
    val isLocal get() = file != null
    val isVideo get() = type == FileType.VIDEO
}

private fun NodeItem.toEntry(folderLink: Boolean) = MediaEntry(
    key = "h:$handle",
    handle = handle,
    name = name,
    type = type,
    size = size,
    hasThumbnail = hasThumbnail,
    hasPreview = hasPreview,
    folderLink = folderLink,
    node = if (folderLink) null else this,
)

private fun File.toEntry() = MediaEntry(
    key = "f:$absolutePath",
    handle = MegaApiJava.INVALID_HANDLE,
    name = name,
    type = FileType.of(name),
    size = length(),
    hasThumbnail = false,
    hasPreview = false,
    folderLink = false,
    file = this,
)

/**
 * Items of [source] with a type in [types], and the index of the start item.
 * The start item is [handle] for cloud sources, or the file at MediaSource.Local.path.
 */
suspend fun loadEntries(source: MediaSource, handle: Long, types: Set<FileType>): Pair<List<MediaEntry>, Int> =
    withContext(Dispatchers.IO) {
        val graph = MegaGlassApp.graph
        val settings = graph.settings.current
        val list: List<MediaEntry> = when (source) {
            is MediaSource.Folder -> children(graph.mega.api, source.parent, types, settings.showHidden, folderLink = false)
            is MediaSource.FolderLink -> children(graph.mega.folderApi, source.parent, types, true, folderLink = true)
            MediaSource.Timeline -> PhotosRepository.timeline(MediaFilter.ALL, settings.showHidden)
                .filter { it.node.type in types }.map { it.node.toEntry(false) }
            is MediaSource.Album -> {
                val media = if (source.id < 0) {
                    PhotosRepository.systemAlbum(source.id, PhotosRepository.timeline(MediaFilter.ALL, settings.showHidden))
                } else {
                    PhotosRepository.albumEntries(source.id, settings.showHidden).map { it.media }
                }
                media.filter { it.node.type in types }.map { it.node.toEntry(false) }
            }
            MediaSource.Single -> listOfNotNull(graph.mega.api.getNodeByHandle(handle)?.toItem(graph.mega.api, withCounts = false)?.toEntry(false))
            is MediaSource.Local -> {
                val start = File(source.path)
                val dir = if (start.isDirectory) start else start.parentFile
                dir?.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.startsWith(".") && FileType.of(it.name) in types }
                    .sortedBy { it.name.lowercase() }
                    .map { it.toEntry() }
            }
        }
        val startKey = if (source is MediaSource.Local) "f:" + File(source.path).absolutePath else "h:$handle"
        var index = list.indexOfFirst { it.key == startKey }
        if (index < 0 && source !is MediaSource.Local) {
            // The start item is not in the list (other type or filtered): show it alone.
            val api = if (source is MediaSource.FolderLink) graph.mega.folderApi else graph.mega.api
            val single = api.getNodeByHandle(handle)?.toItem(api, withCounts = false)?.toEntry(source is MediaSource.FolderLink)
            return@withContext if (single != null) listOf(single) to 0 else list to 0
        }
        if (index < 0) index = 0
        list to index
    }

private fun children(api: MegaApiJava, parent: Long, types: Set<FileType>, showHidden: Boolean, folderLink: Boolean): List<MediaEntry> {
    val node = api.getNodeByHandle(parent) ?: return emptyList()
    val order = MegaGlassApp.graph.settings.current.sortOrder.sdkOrder
    return api.getChildren(node, order)
        .asSequence()
        .filter { it.isFile && (showHidden || !it.isMarkedSensitive) && FileType.of(it.name ?: "") in types }
        .map { it.toItem(api, withCounts = false).toEntry(folderLink) }
        .toList()
}

/**
 * Local copies of cloud files for the in-app viewers (originals of images, PDFs, texts), in
 * cache/preview. Account nodes download through the transfer engine (parallel connections);
 * folder-link nodes stream through their own SDK instance.
 */
object PreviewCache {

    private val root get() = File(MegaGlassApp.graph.context.cacheDir, "preview")

    fun file(handle: Long, name: String): File =
        File(File(root, MegaApiJava.handleToBase64(handle)), name.replace('/', '_'))

    /** Returns the cached file when it is complete. */
    fun cached(entry: MediaEntry): File? = file(entry.handle, entry.name).takeIf { it.length() == entry.size && entry.size > 0 }

    /** Downloads the node unless it is cached. [onProgress] gets 0..1. */
    suspend fun fetch(handle: Long, name: String, size: Long, folderLink: Boolean, onProgress: (Float) -> Unit = {}): File {
        val target = file(handle, name)
        if (target.length() == size && size > 0) return target
        target.parentFile?.mkdirs()
        return if (folderLink) stream(handle, target, size, onProgress) else transfer(handle, target, onProgress)
    }

    private suspend fun transfer(handle: Long, target: File, onProgress: (Float) -> Unit): File = coroutineScope {
        val transfers = MegaGlassApp.graph.transfers
        val path = target.absolutePath
        // Undispatched: the collectors subscribe before the download starts.
        val done = async(start = CoroutineStart.UNDISPATCHED) {
            transfers.completed.first { it.localPath == path && it.purpose == Purpose.PREVIEW }
        }
        val progress = async(start = CoroutineStart.UNDISPATCHED) {
            transfers.state.collect { s -> s.active.firstOrNull { it.localPath == path }?.let { onProgress(it.progress) } }
        }
        try {
            transfers.downloadTo(handle, target, Purpose.PREVIEW)
            val item = done.await()
            if (item.status != TransferStatus.COMPLETED) throw IOException(item.error ?: "Download failed")
            target
        } finally {
            progress.cancel()
            done.cancel()
        }
    }

    /** Streams a node into [target] without the transfer queue. */
    private suspend fun stream(handle: Long, target: File, size: Long, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val api = MegaGlassApp.graph.mega.folderApi
        val node = api.getNodeByHandle(handle) ?: throw MegaException(MegaError.API_ENOENT, "Item not found")
        val tmp = File(target.parentFile, target.name + ".part")
        val out = FileOutputStream(tmp)
        val cancelled = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine { cont ->
                // A failing write stops the streaming transfer.
                val guarded = object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()))
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (cancelled.isCompleted) throw IOException("Cancelled")
                        out.write(b, off, len)
                    }
                }
                api.startUnbufferedDownload(node, 0, node.size, guarded, object : MegaTransferListenerInterface {
                    override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {}
                    override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
                        if (size > 0) onProgress(transfer.transferredBytes.toFloat() / size)
                    }
                    override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {}
                    override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray) = true
                    override fun onFolderTransferUpdate(
                        api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
                        createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?,
                    ) {}
                    override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
                        if (!cont.isActive) return
                        if (e.errorCode == MegaError.API_OK) cont.resume(Unit)
                        else cont.resumeWithException(MegaException(e.errorCode, e.errorString ?: "Download failed"))
                    }
                })
                cont.invokeOnCancellation { cancelled.complete(Unit) }
            }
            out.close()
            if (!tmp.renameTo(target)) throw IOException("Cannot write cache")
            target
        } finally {
            runCatching { out.close() }
            tmp.delete()
        }
    }
}

/**
 * Reference-counted SDK HTTP server for streaming (127.0.0.1 only, free port).
 * Only links created by this app are served (the SDK default restricted mode).
 */
object StreamServer {
    private val refs = HashMap<MegaApiJava, Int>()
    private val playbackHolds = HashSet<MegaApiJava>()

    @Synchronized
    fun acquire(api: MegaApiJava): Boolean {
        if (api.httpServerIsRunning() == 0) {
            if (!api.httpServerStart(true, 0)) return false
            // A large buffer keeps high bitrate video smooth; the output size limits socket writes.
            api.httpServerSetMaxBufferSize(16 * 1024 * 1024)
            api.httpServerSetMaxOutputSize(512 * 1024)
            api.httpServerEnableFolderServer(false)
            api.httpServerEnableSubtitlesSupport(false)
        }
        refs[api] = (refs[api] ?: 0) + 1
        return true
    }

    @Synchronized
    fun release(api: MegaApiJava) {
        val n = (refs[api] ?: 1) - 1
        if (n <= 0) {
            refs.remove(api)
            api.httpServerStop()
        } else {
            refs[api] = n
        }
    }

    /** Keeps the server of [api] running while background playback uses it. */
    @Synchronized
    fun holdForPlayback(api: MegaApiJava): Boolean {
        if (api in playbackHolds) return api.httpServerIsRunning() != 0
        if (!acquire(api)) return false
        playbackHolds += api
        return true
    }

    @Synchronized
    fun releasePlayback() {
        playbackHolds.forEach(::release)
        playbackHolds.clear()
    }

    fun link(api: MegaApiJava, handle: Long): String? = api.getNodeByHandle(handle)?.let(api::httpServerGetLocalLink)
}
