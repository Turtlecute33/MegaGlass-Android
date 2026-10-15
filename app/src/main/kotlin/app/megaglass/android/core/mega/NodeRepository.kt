package app.megaglass.android.core.mega

import androidx.compose.runtime.Immutable
import app.megaglass.android.core.storage.SortOrder
import app.megaglass.android.core.storage.availableName
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
import nz.mega.sdk.MegaShare

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

/**
 * For the modification orders the SDK sorts folders by name, because folders have no
 * modification time. The app shows the creation date of a folder, so here folders sort by that
 * date. Folders stay before files. Null for the other orders, which need no change.
 */
fun SortOrder.dateComparator(): Comparator<NodeItem>? = when (this) {
    SortOrder.MODIFIED_DESC -> compareBy<NodeItem> { !it.isFolder }.thenByDescending { it.modified }
    SortOrder.MODIFIED_ASC -> compareBy<NodeItem> { !it.isFolder }.thenBy { it.modified }
    else -> null
}

/** The items in [order], with folders by date for the modification orders (see dateComparator). */
fun List<NodeItem>.sortedFor(order: SortOrder): List<NodeItem> = order.dateComparator()?.let(::sortedWith) ?: this

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
            .sortedFor(order)
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

    suspend fun move(handles: Collection<Long>, target: Long) = io {
        val dest = require(target)
        val selected = handles.distinct().map(::require)
        val names = HashSet<String>()
        for (node in selected) {
            val name = node.name ?: throw MegaException(MegaError.API_EARGS, "Item has no name")
            val existing = api.getChildNode(dest, name)
            if (!names.add(name) || existing != null && existing.handle != node.handle) {
                throw MegaException(MegaError.API_EEXIST, "The destination already contains an item named $name")
            }
        }
        forEach(handles) { node -> api.await { moveNode(node, dest, it) } }
    }

    suspend fun copy(handles: Collection<Long>, target: Long) = io {
        copyNodes(handles.distinct().map(::require), require(target))
    }

    /** Copies [nodes] into [dest] without replacing an item there. Also public (link) nodes. */
    suspend fun copyNodes(nodes: List<MegaNode>, dest: MegaNode) = io {
        val used = HashSet<String>()
        // Every name is chosen before the first request: a copy in flight is not a child yet.
        val planned = nodes.map { node ->
            node to availableName(node.name.orEmpty(), node.isFolder) { it in used || api.getChildNode(dest, it) != null }.also { used += it }
        }
        batched(planned) { (node, name) -> api.await { copyNode(node, dest, name, it) } }
    }

    suspend fun moveToRubbish(handles: Collection<Long>) = io {
        val dest = require(rubbishHandle)
        val used = HashSet<String>()
        val planned = handles.distinct().map(::require).map { node ->
            // Across share boundaries the SDK uses copy-delete, which can overwrite a file in the
            // target. Preserve existing trash entries with a distinct target name. An ordinary
            // same-account move (null) retains the node and permits duplicate names.
            val name = if (api.getAccess(node) < MegaShare.ACCESS_OWNER) {
                availableName(node.name.orEmpty(), node.isFolder) { it in used || api.getChildNode(dest, it) != null }.also { used += it }
            } else null
            node to name
        }
        batched(planned) { (node, name) ->
            if (name != null) api.await { moveNode(node, dest, name, it) } else api.await { moveNode(node, dest, it) }
        }
    }

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

    /** All versions of a file, newest first. [handle] can be any version (a restore makes a new current one). */
    suspend fun versions(handle: Long): List<NodeItem> = io {
        var node = require(handle)
        // A previous version is a child of the version after it.
        while (true) node = api.getParentNode(node)?.takeIf { it.isFile } ?: break
        api.getVersions(node).map { it.toItem(api, withCounts = false) }
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
        api.await { getFolderInfo(require(handle), it) }.keepAlive { r ->
            val info = r.megaFolderInfo
            FolderInfo(info.numFiles, info.numFolders, info.currentSize, info.numVersions, info.versionsSize)
        }
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
        // ONLY_TRUE excludes sensitive nodes and nodes in sensitive folders (ONLY_FALSE returns only sensitive ones).
        if (!showHidden) filter.bySensitivity(MegaSearchFilter.BOOL_FILTER_ONLY_TRUE)
        if (modifiedAfter > 0) filter.byModificationTime(modifiedAfter, Long.MAX_VALUE / 1000)
        val page = if (limit > 0) nz.mega.sdk.MegaSearchPage.createInstance(0, limit) else null
        api.search(filter, order.sdkOrder, null, page)
            // SEARCH_TARGET_ALL also covers the Rubbish bin; deleted items do not belong in the results.
            .filter { parent != null || scope != SearchScope.ALL || !api.isInRubbish(it) }
            .map { it.toItem(api) }
            .sortedFor(order)
    }

    suspend fun favourites(showHidden: Boolean): List<NodeItem> =
        search("", scope = SearchScope.CLOUD, favouritesOnly = true, showHidden = showHidden, order = SortOrder.MODIFIED_DESC)

    suspend fun recents(days: Long = 30, maxNodes: Long = 500, showHidden: Boolean): List<RecentBucket> = io {
        val request = api.await { getRecentActionsAsync(days, maxNodes, !showHidden, it) }
        request.keepAlive { r -> r.recentActions?.let { toBuckets(it) } } ?: emptyList()
    }

    private fun toBuckets(list: nz.mega.sdk.MegaRecentActionBucketList): List<RecentBucket> {
        val me = api.myEmail
        return (0 until list.size()).map { i ->
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
            .filter { showHidden || !it.isMarkedSensitive && !api.isSensitiveInherited(it) }
            .map { it.toItem(api) }
            .sortedFor(order)
    }

    /** Runs [action] for every node concurrently; the SDK pipelines the requests. */
    private suspend fun forEach(handles: Collection<Long>, action: suspend (MegaNode) -> Unit) = io {
        // Validate the entire selection before any mutation; vanished nodes must not count as success.
        batched(handles.distinct().map(::require), action)
    }

    /** Bounded concurrency: a large selection must not start thousands of requests at once. */
    suspend fun <T> batched(items: List<T>, action: suspend (T) -> Unit) = coroutineScope {
        for (batch in items.chunked(8)) batch.map { item -> async { action(item) } }.awaitAll()
        Unit
    }
}
