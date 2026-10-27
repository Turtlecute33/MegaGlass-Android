package app.megaglass.android.feature.photos

import app.megaglass.android.core.util.catching

import androidx.compose.runtime.Immutable
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.cancellable
import app.megaglass.android.core.mega.keepAlive
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.GenerationCache
import app.megaglass.android.ui.nav.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaHandleList
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaSearchFilter
import nz.mega.sdk.MegaSet
import nz.mega.sdk.MegaStringList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MediaFilter { ALL, PHOTOS, VIDEOS }

/** A photo or video with the time it was taken (seconds), for the timeline. */
@Immutable
data class MediaItem(val node: NodeItem, val takenAt: Long) {
    val handle get() = node.handle
    val day: LocalDate get() = mediaDay(takenAt)
}

/** Invalid dates in shared media metadata must not crash the photo timeline. */
internal fun mediaDay(seconds: Long): LocalDate = catching {
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
}.getOrDefault(LocalDate.ofEpochDay(0))

/** 0001-01-01 and 9999-12-31 (UTC), in seconds: every date in this range has a day in any time zone. */
private const val MIN_TAKEN = -62_135_596_800L
private const val MAX_TAKEN = 253_402_300_799L

/**
 * When a photo was taken, in seconds: the capture time, or else the modification time. The
 * uploader sets both, so they can be any value. The range keeps the sort order and the days of
 * the timeline the same: two distinct days never get the same date.
 */
internal fun mediaTakenAt(captureMs: Long, modified: Long): Long =
    (if (captureMs > 0) captureMs / 1000 else modified).coerceIn(MIN_TAKEN, MAX_TAKEN)

@Immutable
data class AlbumInfo(
    /** MegaSet id, or Album.FAVOURITES / GIF / RAW. */
    val id: Long,
    val name: String,
    val count: Int,
    /** Node handle of the cover image, or INVALID_HANDLE. */
    val cover: Long,
    val isSystem: Boolean,
    val isExported: Boolean = false,
)

/** An item of a user album: the set element id and its node. */
@Immutable
data class AlbumEntry(val elementId: Long, val media: MediaItem)

val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "raf", "rw2", "pef", "srw", "x3f", "3fr", "erf", "kdc", "mrw", "nrw", "raw")

/** Photos timeline and albums (MegaSets). Every call runs on Dispatchers.IO. */
object PhotosRepository {

    private val graph get() = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api

    fun MegaNode.toMedia(api: MegaApiJava): MediaItem =
        MediaItem(node = toItem(api, withCounts = false), takenAt = mediaTakenAt(mediaCaptureTimeMs, modificationTime))

    /** Shown in albums: not in the rubbish bin and, unless [showHidden], not hidden. */
    private fun isShown(node: MegaNode, showHidden: Boolean): Boolean =
        !api.isInRubbish(node) && (showHidden || !(node.isMarkedSensitive || api.isSensitiveInherited(node)))

    /** The last timeline, until the next node change (MEGAGLASS-PERF-004). */
    private val timelines = GenerationCache<Pair<MediaFilter, Boolean>, List<MediaItem>>()

    /** Call on each node change: the next [timeline] searches again. */
    fun onNodesChanged() = timelines.invalidate()

    /** Removes the cached timeline (logout). */
    fun clear() = timelines.clear()

    /**
     * All photos and videos in Cloud drive, newest first. The full search is slow for large
     * accounts, so the result stays cached until the next node change.
     */
    suspend fun timeline(filter: MediaFilter, showHidden: Boolean): List<MediaItem> =
        timelines.get(filter to showHidden) { search(filter, showHidden) }

    private suspend fun search(filter: MediaFilter, showHidden: Boolean): List<MediaItem> = withContext(Dispatchers.IO) {
        val f = MegaSearchFilter.createInstance()
        f.byLocation(MegaApiJava.SEARCH_TARGET_ROOTNODE)
        f.byNodeType(MegaNode.TYPE_FILE)
        f.byCategory(
            when (filter) {
                MediaFilter.ALL -> MegaApiJava.FILE_TYPE_ALL_VISUAL_MEDIA
                MediaFilter.PHOTOS -> MegaApiJava.FILE_TYPE_PHOTO
                MediaFilter.VIDEOS -> MegaApiJava.FILE_TYPE_VIDEO
            },
        )
        if (!showHidden) f.bySensitivity(MegaSearchFilter.BOOL_FILTER_ONLY_TRUE)
        // A newer load cancels this one: the search and the mapping stop.
        val context = currentCoroutineContext()
        cancellable { token -> api.search(f, MegaApiJava.ORDER_MEDIATS_DESC, token, null) }
            .asSequence()
            .map { context.ensureActive(); it.toMedia(api) }
            // The SDK category is by extension; keep the types this app can show.
            .filter { it.node.type == FileType.IMAGE || it.node.type == FileType.VIDEO }
            .sortedByDescending { it.takenAt }
            .toList()
    }

    /** Media of a system album, filtered from the full timeline. */
    fun systemAlbum(id: Long, all: List<MediaItem>): List<MediaItem> = when (id) {
        Album.FAVOURITES -> all.filter { it.node.isFavourite }
        Album.GIF -> all.filter { it.node.name.substringAfterLast('.', "").equals("gif", true) }
        Album.RAW -> all.filter { it.node.name.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS }
        else -> emptyList()
    }

    /** Favourites always; GIF and RAW only when they have items. */
    fun systemAlbums(all: List<MediaItem>): List<AlbumInfo> =
        listOf(Album.FAVOURITES, Album.GIF, Album.RAW).mapNotNull { id ->
            val items = systemAlbum(id, all)
            if (id != Album.FAVOURITES && items.isEmpty()) null
            else AlbumInfo(id, "", items.size, items.firstOrNull()?.handle ?: MegaApiJava.INVALID_HANDLE, true)
        }

    /** User albums, newest first. The count and the cover use the items that the album page shows. */
    suspend fun userAlbums(showHidden: Boolean): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val sets = api.sets
        sets.keepAlive {
        (0 until sets.size()).mapNotNull { i ->
            val set = sets.get(i)
            if (set.type() != MegaSet.SET_TYPE_ALBUM) return@mapNotNull null
            val elements = api.getSetElements(set.id())
            // Element id to node handle.
            val shown = elements.keepAlive { (0 until it.size()).mapNotNull { j ->
                val e = it.get(j)
                val node = api.getNodeByHandle(e.node())?.takeIf { n -> isShown(n, showHidden) } ?: return@mapNotNull null
                e.id() to node.handle
            } }
            val coverElement = api.getSetCover(set.id())
            val cover = shown.firstOrNull { it.first == coverElement } ?: shown.firstOrNull()
            AlbumInfo(
                id = set.id(),
                name = set.name() ?: "",
                count = shown.size,
                cover = cover?.second ?: MegaApiJava.INVALID_HANDLE,
                isSystem = false,
                isExported = set.isExported,
            ) to set.ts()
        }.sortedByDescending { it.second }.map { it.first }
        }
    }

    /** Items of a user album, newest first. Elements whose node is gone are left out. */
    suspend fun albumEntries(id: Long, showHidden: Boolean): List<AlbumEntry> = withContext(Dispatchers.IO) {
        val elements = api.getSetElements(id)
        elements.keepAlive {
        (0 until elements.size()).mapNotNull { i ->
            val e = elements.get(i)
            val node = api.getNodeByHandle(e.node())?.takeIf { isShown(it, showHidden) } ?: return@mapNotNull null
            AlbumEntry(e.id(), node.toMedia(api))
        }.sortedByDescending { it.media.takenAt }
        }
    }

    fun albumName(id: Long): String? = api.getSet(id)?.name()

    suspend fun createAlbum(name: String): Long = withContext(Dispatchers.IO) {
        api.await { createSet(name, MegaSet.SET_TYPE_ALBUM, it) }.keepAlive { it.megaSet.id() }
    }

    suspend fun renameAlbum(id: Long, name: String) = withContext(Dispatchers.IO) {
        api.await { updateSetName(id, name, it) }
        Unit
    }

    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        api.await { removeSet(id, it) }
        Unit
    }

    /** Adds nodes to an album; nodes already in it are skipped. */
    suspend fun addToAlbum(id: Long, handles: Collection<Long>) = withContext(Dispatchers.IO) {
        val elements = api.getSetElements(id)
        val present = elements.keepAlive { (0 until it.size()).map { i -> it.get(i).node() }.toSet() }
        val list = MegaHandleList.createInstance()
        val names = MegaStringList.createInstance()
        handles.filter { it !in present }.forEach {
            list.addMegaHandle(it)
            names.add("")
        }
        if (list.size() > 0) api.await { createSetElements(id, list, names, it) }
        Unit
    }

    suspend fun removeFromAlbum(id: Long, elementIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val list = MegaHandleList.createInstance()
        elementIds.forEach(list::addMegaHandle)
        if (list.size() > 0) api.await { removeSetElements(id, list, it) }
        Unit
    }

    suspend fun setCover(id: Long, elementId: Long) = withContext(Dispatchers.IO) {
        api.await { putSetCover(id, elementId, it) }
        Unit
    }

    /** Creates (or returns) the public link of an album. */
    suspend fun albumLink(id: Long): String = withContext(Dispatchers.IO) {
        if (!api.isExportedSet(id)) api.await { exportSet(id, it) }
        api.getPublicLinkForExportedSet(id) ?: ""
    }

    suspend fun removeAlbumLink(id: Long) = withContext(Dispatchers.IO) {
        api.await { disableExportSet(id, it) }
        Unit
    }
}
