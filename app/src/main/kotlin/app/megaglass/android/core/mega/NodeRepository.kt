package app.megaglass.android.core.mega

import androidx.compose.runtime.Immutable
import app.megaglass.android.core.storage.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaSearchFilter

@Immutable
data class Crumb(val handle: Long, val name: String)

@Immutable
data class FolderInfo(val files: Int, val folders: Int, val size: Long, val versions: Int, val versionsSize: Long)

@Immutable
data class RecentBucket(
    val id: String,
    val timestamp: Long,
    /** Null when the actions are the user's own. */
    val userEmail: String?,
    val parentHandle: Long,
    val parentName: String,
    val isUpdate: Boolean,
    val isMedia: Boolean,
    val nodes: List<NodeItem>,
)

/** Where a search looks. */
enum class SearchScope(val sdkLocation: Int) {
    CLOUD(MegaApiJava.SEARCH_TARGET_ROOTNODE),
    INCOMING(MegaApiJava.SEARCH_TARGET_INSHARE),
    OUTGOING(MegaApiJava.SEARCH_TARGET_OUTSHARE),
    LINKS(MegaApiJava.SEARCH_TARGET_PUBLICLINK),
    ALL(MegaApiJava.SEARCH_TARGET_ALL),
}

/** File and folder operations. Every call runs on Dispatchers.IO. */
class NodeRepository(private val mega: Mega) {

    private val api: MegaApiJava get() = mega.api

    val changes: Flow<NodeChange> get() = mega.nodes

    val rootHandle: Long get() = api.rootNode?.handle ?: Mega.INVALID_HANDLE
    val rubbishHandle: Long get() = api.rubbishNode?.handle ?: Mega.INVALID_HANDLE
    val vaultHandle: Long get() = api.vaultNode?.handle ?: Mega.INVALID_HANDLE

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun require(handle: Long): MegaNode =
        api.getNodeByHandle(handle) ?: throw MegaException(MegaError.API_ENOENT, "Item not found")

    suspend fun node(handle: Long): MegaNode? = io { api.getNodeByHandle(handle) }

    suspend fun item(handle: Long): NodeItem? = io { api.getNodeByHandle(handle)?.toItem(api) }

    suspend fun children(parent: Long, order: SortOrder, showHidden: Boolean): List<NodeItem> = io {
        val node = require(parent)
        val inherited = parent != rootHandle && api.isSensitiveInherited(node)
        api.getChildren(node, order.sdkOrder)
            .asSequence()
            .filter { showHidden || inherited || !it.isMarkedSensitive }
            .map { it.toItem(api) }
            .toList()
    }

    /** Folder chain from the top (Cloud drive, Rubbish bin, or an incoming share) to [handle]. */
    suspend fun path(handle: Long): List<Crumb> = io {
        val out = ArrayList<Crumb>()
        var n: MegaNode? = api.getNodeByHandle(handle)
        while (n != null) {
            out += Crumb(n.handle, n.name ?: "")
            if (n.isInShare) break
            n = api.getParentNode(n)
        }
        out.reverse()
        out
    }

    suspend fun createFolder(parent: Long, name: String): Long = io {
        api.await { createFolder(name, require(parent), it) }.nodeHandle
    }

    suspend fun rename(handle: Long, name: String) {
        io { api.await { renameNode(require(handle), name, it) } }
    }

    suspend fun move(handles: Collection<Long>, target: Long) = forEach(handles) { node ->
        val dest = require(target)
        api.await { moveNode(node, dest, it) }
    }

    suspend fun copy(handles: Collection<Long>, target: Long) = forEach(handles) { node ->
        val dest = require(target)
        api.await { copyNode(node, dest, it) }
    }

    suspend fun moveToRubbish(handles: Collection<Long>) = move(handles, rubbishHandle)

    suspend fun deletePermanently(handles: Collection<Long>) = forEach(handles) { node ->
        api.await { remove(node, it) }
    }

    /** Moves items out of the Rubbish bin to where they were, or to Cloud drive. */
    suspend fun restore(handles: Collection<Long>) = forEach(handles) { node ->
        val original = api.getNodeByHandle(node.restoreHandle)
        val dest = original?.takeIf { !api.isInRubbish(it) } ?: api.rootNode
        api.await { moveNode(node, dest, it) }
    }

    suspend fun emptyRubbish() {
        io { api.await { cleanRubbishBin(it) } }
    }

    suspend fun setFavourite(handles: Collection<Long>, favourite: Boolean) = forEach(handles) { node ->
        api.await { setNodeFavourite(node, favourite, it) }
    }

    /** [label] is MegaNode.NODE_LBL_*; 0 removes the label. */
    suspend fun setLabel(handles: Collection<Long>, label: Int) = forEach(handles) { node ->
        if (label == 0) api.await { resetNodeLabel(node, it) } else api.await { setNodeLabel(node, label, it) }
    }

    suspend fun setHidden(handles: Collection<Long>, hidden: Boolean) = forEach(handles) { node ->
        api.await { setNodeSensitive(node, hidden, it) }
    }

    suspend fun setDescription(handle: Long, description: String) {
        io { api.await { setNodeDescription(require(handle), description.ifBlank { null }, it) } }
    }

    /** Creates or updates the public link. [expireTime] is seconds since epoch, 0 for none (Pro only). */
    suspend fun exportLink(handle: Long, expireTime: Int = 0): String = io {
        val node = require(handle)
        val request = if (expireTime > 0) api.await { exportNode(node, expireTime, it) } else api.await { exportNode(node, it) }
        request.link
    }

    suspend fun disableLink(handles: Collection<Long>) = forEach(handles) { node ->
        api.await { disableExport(node, it) }
    }

    /** Protects a link with a password (Pro only). Returns the new #P! link. */
    suspend fun encryptLink(link: String, password: String): String = io {
        api.await { encryptLinkWithPassword(link, password, it) }.text
    }

    suspend fun versions(handle: Long): List<NodeItem> = io {
        api.getVersions(require(handle)).map { it.toItem(api, withCounts = false) }
    }

    suspend fun versionCount(handle: Long): Int = io {
        api.getNodeByHandle(handle)?.let { api.getNumVersions(it) } ?: 0
    }

    suspend fun restoreVersion(handle: Long) {
        io { api.await { restoreVersion(require(handle), it) } }
    }

    suspend fun removeVersion(handle: Long) {
        io { api.await { removeVersion(require(handle), it) } }
    }

    suspend fun removeAllVersions() {
        io { api.await { removeVersions(it) } }
    }

    suspend fun folderInfo(handle: Long): FolderInfo = io {
        val info = api.await { getFolderInfo(require(handle), it) }.megaFolderInfo
        FolderInfo(info.numFiles, info.numFolders, info.currentSize, info.numVersions, info.versionsSize)
    }

    suspend fun importLink(link: String, parent: Long): Long = io {
        api.await { importFileLink(link, require(parent), it) }.nodeHandle
    }

    /**
     * Searches by name. [category] is MegaApiJava.FILE_TYPE_* (or FILE_TYPE_DEFAULT for all).
     * [parent] limits the search to one folder tree.
     */
    suspend fun search(
        query: String,
        scope: SearchScope = SearchScope.CLOUD,
        category: Int = MegaApiJava.FILE_TYPE_DEFAULT,
        parent: Long? = null,
        modifiedAfter: Long = 0,
        foldersOnly: Boolean = false,
        favouritesOnly: Boolean = false,
        order: SortOrder = SortOrder.NAME_ASC,
        showHidden: Boolean = false,
        limit: Long = 0,
    ): List<NodeItem> = io {
        val filter = MegaSearchFilter.createInstance()
        if (query.isNotBlank()) filter.byName(query.trim())
        if (parent != null) filter.byLocationHandle(parent) else filter.byLocation(scope.sdkLocation)
        if (category != MegaApiJava.FILE_TYPE_DEFAULT) filter.byCategory(category)
        if (foldersOnly) filter.byNodeType(MegaNode.TYPE_FOLDER)
        if (favouritesOnly) filter.byFavourite(MegaSearchFilter.BOOL_FILTER_ONLY_TRUE)
        if (!showHidden) filter.bySensitivity(MegaSearchFilter.BOOL_FILTER_ONLY_FALSE)
        if (modifiedAfter > 0) filter.byModificationTime(modifiedAfter, Long.MAX_VALUE / 1000)
        val page = if (limit > 0) nz.mega.sdk.MegaSearchPage.createInstance(0, limit) else null
        api.search(filter, order.sdkOrder, null, page).map { it.toItem(api) }
    }

    suspend fun favourites(showHidden: Boolean): List<NodeItem> =
        search("", scope = SearchScope.CLOUD, favouritesOnly = true, showHidden = showHidden, order = SortOrder.MODIFIED_DESC)

    suspend fun recents(days: Long = 30, maxNodes: Long = 500, showHidden: Boolean): List<RecentBucket> = io {
        val list = api.await { getRecentActionsAsync(days, maxNodes, !showHidden, it) }.recentActions ?: return@io emptyList()
        val me = api.myEmail
        (0 until list.size()).map { i ->
            val b = list.get(i)
            val nodes = b.nodes
            val items = (0 until nodes.size()).map { nodes.get(it).toItem(api, withCounts = false) }
            val parent = api.getNodeByHandle(b.parentHandle)
            RecentBucket(
                id = b.id ?: "$i",
                timestamp = b.timestamp,
                userEmail = b.userEmail?.takeIf { it != me },
                parentHandle = b.parentHandle,
                parentName = parent?.name ?: "",
                isUpdate = b.isUpdate,
                isMedia = b.isMedia,
                nodes = items,
            )
        }
    }

    suspend fun publicLinks(order: SortOrder, showHidden: Boolean): List<NodeItem> = io {
        api.getPublicLinks(order.sdkOrder)
            .filter { showHidden || !it.isMarkedSensitive }
            .map { it.toItem(api) }
    }

    /** Runs [action] for every node concurrently; the SDK pipelines the requests. */
    private suspend fun forEach(handles: Collection<Long>, action: suspend (MegaNode) -> Unit) = io {
        coroutineScope {
            handles.mapNotNull { api.getNodeByHandle(it) }.map { node -> async { action(node) } }.awaitAll()
        }
        Unit
    }
}
