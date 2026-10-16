package app.megaglass.android.feature.links

import app.megaglass.android.core.util.catching

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.mega.keepAlive
import app.megaglass.android.core.mega.sortedFor
import app.megaglass.android.core.mega.showsUploaderImage
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.storage.displayName
import app.megaglass.android.core.storage.localFileName
import app.megaglass.android.core.storage.VerifiedFiles
import app.megaglass.android.core.storage.ensureDirectory
import app.megaglass.android.core.storage.availableName
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaDownloadItemList
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaTransfer
import java.io.File

/** A file of a public link (a file link or an album element), as the UI shows it. */
@Immutable
data class PublicItem(
    val id: Long,
    val name: String,
    val size: Long,
    val type: FileType,
    /** Seconds since epoch. */
    val modified: Long,
    val hasImage: Boolean,
)

sealed interface LinkUi {
    data object Loading : LinkUi
    data class Fetching(val progress: Float?) : LinkUi
    data class NeedPassword(val wrong: Boolean) : LinkUi
    data class NeedKey(val wrong: Boolean) : LinkUi
    data class Failed(val message: String, @DrawableRes val icon: Int) : LinkUi
    data class FileLink(val item: PublicItem) : LinkUi
    data class FolderLink(val rootName: String) : LinkUi
    data class AlbumLink(val name: String, val items: List<PublicItem>) : LinkUi
}

/** Browsing position inside a folder link. */
data class FolderBrowse(
    /** Folders from the link root (excluded) to the current folder. */
    val stack: List<NodeItem> = emptyList(),
    val items: List<NodeItem>? = null,
    val selection: Set<Long> = emptySet(),
)

class PublicLinkViewModel(rawUrl: String) : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api
    private val folderApi: MegaApiJava get() = graph.mega.folderApi
    private val res get() = graph.context.resources
    private val cacheDir = File(graph.context.cacheDir, "links").apply { mkdirs() }

    private val _ui = MutableStateFlow<LinkUi>(LinkUi.Loading)
    val ui: StateFlow<LinkUi> = _ui.asStateFlow()

    private val _folder = MutableStateFlow(FolderBrowse())
    val folder: StateFlow<FolderBrowse> = _folder.asStateFlow()

    private val _preparing = MutableStateFlow<Set<Long>>(emptySet())
    /** Items that are downloading into the cache before they open. */
    val preparing: StateFlow<Set<Long>> = _preparing.asStateFlow()

    private val _openLocal = MutableSharedFlow<File>(extraBufferCapacity = 4)
    /** A cached file that is ready to open in a viewer. */
    val openLocal: SharedFlow<File> = _openLocal

    private var link: MegaLink = parseMegaLink(rawUrl)
    private var fileNode: MegaNode? = null
    private val albumNodes = HashMap<Long, MegaNode>()
    private var usedFolderApi = false
    private var inAlbumPreview = false
    private val imageLocks = HashMap<Long, Mutex>()
    private var openJob: Job? = null
    private var childrenJob: Job? = null
    private var folderOwner: Long? = null
    private var albumOwner: Long? = null

    /** The link with its key, for import. */
    val resolvedUrl: String get() = link.url

    init {
        open()
        viewModelScope.launch {
            graph.mega.folderOwner.collect { owner ->
                if (folderOwner != null && folderOwner != owner) {
                    openJob?.cancel()
                    childrenJob?.cancel()
                    fail("Another folder link is open. Reopen this link to continue.", R.drawable.ic_link_off)
                }
            }
        }
    }

    private fun open() {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            _ui.value = LinkUi.Loading
            when (val l = link) {
                is MegaLink.Protected -> _ui.value = LinkUi.NeedPassword(wrong = false)
                is MegaLink.Invalid -> fail(res.getString(R.string.link_error_invalid), R.drawable.ic_link_off)
                else -> if (l.needsKey()) _ui.value = LinkUi.NeedKey(wrong = false) else load(l)
            }
        }
    }

    fun submitPassword(password: String) = viewModelScope.launch {
        _ui.value = LinkUi.Loading
        val result = catching { api.awaitResult { decryptPasswordProtectedLink(link.url, password, it) } }
            .getOrElse { handle(it); return@launch }
        val (code, request) = result
        if (code != MegaError.API_OK || request.text.isNullOrBlank()) {
            _ui.value = LinkUi.NeedPassword(wrong = true)
            return@launch
        }
        link = parseMegaLink(request.text)
        open()
    }

    fun submitKey(key: String) {
        link = link.withKey(key)
        open()
    }

    private fun fail(message: String, @DrawableRes icon: Int) {
        _ui.value = LinkUi.Failed(message, icon)
    }

    /** Maps link errors to text; wrong keys ask for the key again. */
    private fun handle(e: Throwable) {
        val code = (e as? MegaException)?.code
        when (code) {
            MegaError.API_EKEY, MegaError.API_EINCOMPLETE -> _ui.value = LinkUi.NeedKey(wrong = true)
            MegaError.API_ETOOMANY -> fail(res.getString(R.string.link_error_terminated), R.drawable.ic_block)
            MegaError.API_EBLOCKED -> fail(res.getString(R.string.link_error_taken_down), R.drawable.ic_block)
            MegaError.API_ENOENT, MegaError.API_EEXPIRED -> fail(res.getString(R.string.link_error_not_found), R.drawable.ic_link_off)
            MegaError.API_EARGS -> fail(res.getString(R.string.link_error_invalid), R.drawable.ic_link_off)
            else -> fail(e.message ?: res.getString(R.string.error_title), R.drawable.ic_cloud_off)
        }
    }

    private suspend fun load(l: MegaLink) {
        catching {
            when (l) {
                is MegaLink.File -> loadFile(l)
                is MegaLink.Folder -> loadFolder(l)
                is MegaLink.Album -> loadAlbum(l)
                else -> Unit
            }
        }.onFailure(::handle)
    }

    private suspend fun loadFile(l: MegaLink.File) {
        val request = api.await { getPublicNode(l.url, it) }
        val node = request.publicMegaNode
        if (request.flag || node == null) {
            _ui.value = LinkUi.NeedKey(wrong = true)
            return
        }
        if (node.isTakenDown) {
            fail(res.getString(R.string.link_error_taken_down), R.drawable.ic_block)
            return
        }
        fileNode = node
        _ui.value = LinkUi.FileLink(node.toPublicItem(node.handle))
    }

    private suspend fun loadFolder(l: MegaLink.Folder) = graph.mega.folderSessionLock.withLock {
        usedFolderApi = true
        folderOwner = graph.mega.folderOwner.value + 1
        graph.mega.folderOwner.value = folderOwner!!
        if (folderApi.isLoggedIn != 0) {
            val closed = withTimeoutOrNull(15_000L) { folderApi.await { localLogout(it) }; true }
            if (closed != true) throw java.io.IOException("Could not close the previous folder link")
        }
        _ui.value = LinkUi.Fetching(null)
        folderApi.await { loginToFolder(l.url, it) }
        folderApi.await(onUpdate = { r ->
            if (r.totalBytes > 0) _ui.value = LinkUi.Fetching(r.transferredBytes.toFloat() / r.totalBytes)
        }) { fetchNodes(it) }
        val root = withContext(Dispatchers.IO) { folderApi.rootNode }
        if (root == null || !root.isNodeKeyDecrypted) {
            _ui.value = LinkUi.NeedKey(wrong = true)
            return@withLock
        }
        // Start in the sub-folder of the link, or in the folder that holds the linked file.
        val start = withContext(Dispatchers.IO) {
            val sub = (l.subFolder ?: l.subFile)?.let { folderApi.getNodeByHandle(MegaApiJava.base64ToHandle(it)) }
            val target = when {
                sub == null -> null
                sub.isFolder -> sub
                else -> folderApi.getParentNode(sub)
            }
            generateSequence(target) { folderApi.getParentNode(it) }
                .takeWhile { it.handle != root.handle }
                .map { it.toItem(folderApi, withCounts = false) }
                .toList()
                .reversed()
        }
        _folder.value = FolderBrowse(stack = start)
        _ui.value = LinkUi.FolderLink(displayName(root.name.orEmpty()))
        loadChildren()
    }

    private suspend fun loadAlbum(l: MegaLink.Album) = graph.mega.publicSetLock.withLock {
        albumOwner = ++graph.mega.publicSetOwner
        val request = api.await { fetchPublicSet(l.url, it) }
        inAlbumPreview = true
        // The set and its elements belong to the request: read them before it can be collected.
        val (setName, ids) = request.keepAlive { r ->
            val elements = r.megaSetElementList
            r.megaSet?.name().orEmpty() to (0 until (elements?.size() ?: 0L)).map { elements!!.get(it).id() }
        }
        val gate = Semaphore(6)
        val nodes = coroutineScope {
            ids.map { eid ->
                async {
                    gate.withPermit {
                        catching { api.await { getPreviewElementNode(eid, it) }.publicMegaNode }.getOrNull()?.let { eid to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        nodes.forEach { (eid, node) -> albumNodes[eid] = node }
        _ui.value = LinkUi.AlbumLink(displayName(setName), nodes.map { (eid, node) -> node.toPublicItem(eid) })
    }

    /** A stranger chooses the name and the images of a link. */
    private fun MegaNode.toPublicItem(id: Long): PublicItem {
        val type = FileType.of(name.orEmpty(), isFolder)
        return PublicItem(
            id = id,
            name = displayName(name.orEmpty()),
            size = size,
            type = type,
            modified = modificationTime,
            hasImage = (hasThumbnail() || hasPreview()) && type.showsUploaderImage,
        )
    }

    // Folder link browsing

    fun currentParent(): Long? = _folder.value.stack.lastOrNull()?.handle ?: folderApi.rootNode?.handle

    private fun loadChildren() {
        childrenJob?.cancel()
        childrenJob = viewModelScope.launch {
        val parentHandle = currentParent() ?: return@launch
        val order = graph.settings.current.sortOrder
        val items = withContext(Dispatchers.IO) {
            val parent = folderApi.getNodeByHandle(parentHandle) ?: return@withContext emptyList()
            folderApi.getChildren(parent, order.sdkOrder).map { it.toItem(folderApi) }.sortedFor(order)
        }
        if (currentParent() == parentHandle) _folder.update { it.copy(items = items, selection = emptySet()) }
        }
    }

    fun enter(item: NodeItem) {
        _folder.update { it.copy(stack = it.stack + item, items = null, selection = emptySet()) }
        loadChildren()
    }

    /** Returns false at the root of the link. */
    fun up(): Boolean {
        if (_folder.value.stack.isEmpty()) return false
        _folder.update { it.copy(stack = it.stack.dropLast(1), items = null, selection = emptySet()) }
        loadChildren()
        return true
    }

    fun goTo(depth: Int) {
        _folder.update { it.copy(stack = it.stack.take(depth), items = null, selection = emptySet()) }
        loadChildren()
    }

    fun toggle(item: NodeItem) = _folder.update {
        it.copy(selection = if (item.handle in it.selection) it.selection - item.handle else it.selection + item.handle)
    }

    fun selectAll() = _folder.update { st -> st.copy(selection = st.items.orEmpty().map { it.handle }.toSet()) }

    fun clearSelection() = _folder.update { it.copy(selection = emptySet()) }

    /** Selected items, or the open folder when nothing is selected. */
    private fun folderTargets(): List<MegaNode> {
        check(folderOwner == graph.mega.folderOwner.value) { "Reopen this folder link before continuing" }
        val selection = _folder.value.selection
        return if (selection.isEmpty()) {
            listOfNotNull(currentParent()?.let { folderApi.getNodeByHandle(it) })
        } else {
            selection.mapNotNull { folderApi.getNodeByHandle(it) }
        }
    }

    // Actions

    /** In the app scope: the copies continue, and the result shows, when the user leaves the screen. */
    fun importTo(target: Long) = graph.scope.launch(Dispatchers.Main) {
        catching {
            val parent = withContext(Dispatchers.IO) { api.getNodeByHandle(target) }
                ?: throw MegaException(MegaError.API_ENOENT, "The destination folder is no longer available")
            when (val ui = _ui.value) {
                is LinkUi.FileLink -> {
                    // The import keeps the name of the node, not the name on screen.
                    val name = fileNode?.name ?: ui.item.name
                    if (withContext(Dispatchers.IO) { api.getChildNode(parent, name) } != null) {
                        throw MegaException(MegaError.API_EEXIST, "The destination already contains an item named ${ui.item.name}")
                    }
                    api.await { importFileLink(link.url, parent, it) }
                }
                is LinkUi.FolderLink -> {
                    val nodes = withContext(Dispatchers.IO) { folderTargets().map { folderApi.authorizeNode(it)
                        ?: throw MegaException(MegaError.API_EACCESS, "Cannot import this linked item") } }
                    graph.nodes.copyNodes(nodes, parent)
                    clearSelection()
                }
                is LinkUi.AlbumLink -> {
                    val nodes = ui.items.mapNotNull { albumNodes[it.id] }
                    graph.nodes.copyNodes(nodes, parent)
                }
                else -> return@catching
            }
            graph.messenger.show(res.getString(R.string.link_imported))
        }.onFailure { graph.messenger.show(importError(it)) }
    }

    private fun importError(e: Throwable): String = when ((e as? MegaException)?.code) {
        MegaError.API_EOVERQUOTA -> res.getString(R.string.link_error_quota)
        else -> e.message ?: res.getString(R.string.error_title)
    }

    /** Downloads into the download folder. The main SDK instance runs it, so it shows in Transfers. */
    fun download() = viewModelScope.launch { catching {
        val dir = graph.files.downloadDir(graph.settings.current)
        val count = withContext(Dispatchers.IO) {
            val items = MegaDownloadItemList.createInstance()
            // The files get safe names on disk: the person who shared them chose the names.
            when (val ui = _ui.value) {
                is LinkUi.FileLink -> fileNode?.let { items.add(it, dir.absolutePath + File.separator, localFileName(it.name ?: "file"), Purpose.DOWNLOAD) }
                is LinkUi.AlbumLink -> {
                    val albumDir = File(dir, safeName(ui.name.ifBlank { "Album" })).apply { mkdirs() }
                    ui.items.mapNotNull { albumNodes[it.id] }
                        .forEach { items.add(it, albumDir.absolutePath + File.separator, localFileName(it.name ?: "file"), Purpose.DOWNLOAD) }
                }
                is LinkUi.FolderLink -> folderTargets().forEach { addTree(it, dir, items) }
                else -> Unit
            }
            if (items.size() > 0) {
                api.startDownloadBatch(
                    items, null,
                    MegaTransfer.COLLISION_CHECK_FINGERPRINT,
                    MegaTransfer.COLLISION_RESOLUTION_NEW_WITH_N,
                    null,
                )
            }
            items.size()
        }
        if (count > 0) {
            graph.transfers.onActive?.invoke()
            clearSelection()
            graph.messenger.show(res.getQuantityString(R.plurals.download_started, count, count))
        }
    }.onFailure { graph.messenger.show(it.message ?: res.getString(R.string.error_title)) } }

    /** Adds the files under [node] with their folder structure; each file is authorized for the main instance. */
    private fun addTree(node: MegaNode, dir: File, out: MegaDownloadItemList) {
        val queue = java.util.ArrayDeque<Pair<MegaNode, File>>()
        queue.add(node to dir)
        while (queue.isNotEmpty()) {
            val (current, parent) = queue.removeFirst()
            if (current.isFile) {
                val authorized = folderApi.authorizeNode(current) ?: throw java.io.IOException("Cannot access linked file")
                out.add(authorized, parent.absolutePath + File.separator, localFileName(current.name ?: "file"), Purpose.DOWNLOAD)
                continue
            }
            val name = safeName(current.name.orEmpty().ifBlank { "Folder" })
            val sub = File(parent, availableName(name, folder = true) { File(parent, it).exists() })
            if (!sub.canonicalPath.startsWith(parent.canonicalPath + File.separator)) throw java.io.IOException("Invalid linked folder path")
            sub.ensureDirectory()
            folderApi.getChildren(current).forEach { queue.add(it to sub) }
        }
    }

    private fun safeName(name: String) = localFileName(name).replace(Regex("[:*?\"<>|]"), "_")

    /**
     * Downloads a file into the cache and emits it on [openLocal], for viewers that read local files.
     * [id] is the node handle (file and folder links) or the element id (albums).
     */
    fun openCached(id: Long, purpose: String = Purpose.PREVIEW) = viewModelScope.launch {
        if (id in _preparing.value) return@launch
        val node = when (_ui.value) {
            is LinkUi.FileLink -> fileNode
            is LinkUi.AlbumLink -> albumNodes[id]
            is LinkUi.FolderLink -> withContext(Dispatchers.IO) { folderApi.getNodeByHandle(id)?.let { folderApi.authorizeNode(it) } }
            else -> null
        } ?: return@launch
        val dir = File(if (purpose == Purpose.OPEN) graph.files.openDir else cacheDir, MegaApiJava.handleToBase64(node.handle))
        val target = File(dir, localFileName(node.name.orEmpty()))
        if (withContext(Dispatchers.IO) { VerifiedFiles.contains(target, node.size) }) {
            if (purpose == Purpose.OPEN) graph.opener.view(target) else _openLocal.tryEmit(target)
            return@launch
        }
        dir.mkdirs()
        _preparing.update { it + id }
        val done = launch(start = CoroutineStart.UNDISPATCHED) {
            val item = graph.transfers.completed.first { t -> t.localPath == target.absolutePath && t.purpose == purpose }
            _preparing.update { it - id }
            if (item.status == TransferStatus.COMPLETED) {
                // FileOpener opens OPEN downloads by itself.
            if (purpose == Purpose.OPEN) graph.opener.view(target) else _openLocal.tryEmit(target)
            } else {
                graph.messenger.show(item.error ?: res.getString(R.string.error_title))
            }
        }
        try {
            graph.transfers.downloadPublicNodeTo(node, target, purpose)
        } catch (e: Exception) {
            done.cancel()
            _preparing.update { it - id }
            if (e is kotlinx.coroutines.CancellationException) throw e
            graph.messenger.show(e.message ?: res.getString(R.string.error_title))
        }
    }

    /** Thumbnail (or preview when [large]) of a file or album element, cached on disk. */
    suspend fun image(id: Long, large: Boolean): File? {
        val node = when (_ui.value) {
            is LinkUi.FileLink -> fileNode
            is LinkUi.AlbumLink -> albumNodes[id]
            else -> null
        } ?: return null
        val usePreview = large && node.hasPreview() || !node.hasThumbnail()
        if (usePreview && !node.hasPreview()) return null
        val file = File(cacheDir, MegaApiJava.handleToBase64(node.handle) + if (usePreview) ".p.jpg" else ".t.jpg")
        val lock = synchronized(imageLocks) { imageLocks.getOrPut(id * 2 + if (usePreview) 1 else 0) { Mutex() } }
        return lock.withLock {
            if (file.length() > 0) return@withLock file
            val tmp = File(cacheDir, file.name + ".part")
            val ok = catching {
                if (usePreview) api.await { getPreview(node, tmp.absolutePath, it) }
                else api.await { getThumbnail(node, tmp.absolutePath, it) }
            }.isSuccess
            if (ok && tmp.renameTo(file)) file else null.also { tmp.delete() }
        }
    }

    override fun onCleared() {
        if (usedFolderApi) graph.scope.launch {
            graph.mega.folderSessionLock.withLock {
                if (folderOwner == graph.mega.folderOwner.value) {
                    withTimeoutOrNull(15_000L) { catching { folderApi.await { localLogout(it) } } }
                }
            }
        }
        if (inAlbumPreview) graph.scope.launch {
            graph.mega.publicSetLock.withLock {
                if (albumOwner == graph.mega.publicSetOwner) catching { api.stopPublicSetPreview() }
            }
        }
    }

    companion object {
        /** Viewers that read nodes of a folder link directly (see Navigator.openNode). */
        fun opensInViewer(item: NodeItem): Boolean = item.isFolder || when (item.type) {
            FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF -> true
            FileType.TEXT, FileType.CODE -> item.size <= app.megaglass.android.ui.nav.AppNavigator.TEXT_EDITOR_LIMIT
            else -> false
        }
    }
}
