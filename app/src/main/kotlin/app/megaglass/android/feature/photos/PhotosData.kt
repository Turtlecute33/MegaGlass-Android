package app.megaglass.android.feature.photos

import androidx.compose.runtime.Immutable
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.util.FileType
import app.megaglass.android.ui.nav.Album
import kotlinx.coroutines.Dispatchers
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
    val day: LocalDate get() = Instant.ofEpochSecond(takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
}

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

    fun MegaNode.toMedia(api: MegaApiJava): MediaItem {
        val captured = mediaCaptureTimeMs
        return MediaItem(
            node = toItem(api, withCounts = false),
            takenAt = if (captured > 0) captured / 1000 else modificationTime,
        )
    }

    /** All photos and videos in Cloud drive, newest first. */
    suspend fun timeline(filter: MediaFilter, showHidden: Boolean): List<MediaItem> = withContext(Dispatchers.IO) {
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
        if (!showHidden) f.bySensitivity(MegaSearchFilter.BOOL_FILTER_ONLY_FALSE)
        api.search(f, MegaApiJava.ORDER_MEDIATS_DESC, null, null)
            .asSequence()
            .map { it.toMedia(api) }
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

    /** User albums, newest first. */
    suspend fun userAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val sets = api.sets
        (0 until sets.size()).mapNotNull { i ->
            val set = sets.get(i)
            if (set.type() != MegaSet.SET_TYPE_ALBUM) return@mapNotNull null
            val elements = api.getSetElements(set.id())
            val count = elements.size().toInt()
            val coverElement = api.getSetCover(set.id())
            val coverNode = if (coverElement != MegaApiJava.INVALID_HANDLE) api.getSetElement(set.id(), coverElement)?.node() else null
            val cover = coverNode ?: (0 until count).asSequence()
                .map { elements.get(it.toLong()).node() }
                .firstOrNull { api.getNodeByHandle(it) != null }
            AlbumInfo(
                id = set.id(),
                name = set.name() ?: "",
                count = count,
                cover = cover ?: MegaApiJava.INVALID_HANDLE,
                isSystem = false,
                isExported = set.isExported,
            ) to set.ts()
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Items of a user album, newest first. Elements whose node is gone are left out. */
    suspend fun albumEntries(id: Long, showHidden: Boolean): List<AlbumEntry> = withContext(Dispatchers.IO) {
        val elements = api.getSetElements(id)
        (0 until elements.size()).mapNotNull { i ->
            val e = elements.get(i)
            val node = api.getNodeByHandle(e.node()) ?: return@mapNotNull null
            if (api.isInRubbish(node)) return@mapNotNull null
            if (!showHidden && node.isMarkedSensitive) return@mapNotNull null
            AlbumEntry(e.id(), node.toMedia(api))
        }.sortedByDescending { it.media.takenAt }
    }

    fun albumName(id: Long): String? = api.getSet(id)?.name()

    suspend fun createAlbum(name: String): Long = withContext(Dispatchers.IO) {
        api.await { createSet(name, MegaSet.SET_TYPE_ALBUM, it) }.megaSet.id()
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
        val present = (0 until elements.size()).map { elements.get(it).node() }.toSet()
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
