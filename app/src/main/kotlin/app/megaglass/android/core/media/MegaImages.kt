package app.megaglass.android.core.media

import android.content.Context
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.storage.ensureDirectory
import app.megaglass.android.core.storage.privateCacheKey
import app.megaglass.android.core.storage.removeStaleParts
import app.megaglass.android.core.storage.trimImageCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Coil data: the thumbnail (200 px) or preview (1000 px) of a node. */
data class NodeImage(val handle: Long, val preview: Boolean = false, val folderLink: Boolean = false)

/** Coil data: the avatar of a user (e-mail or Base64 user handle). */
data class UserAvatar(val user: String, val version: Int = 0)

/**
 * Disk cache for decrypted thumbnails, previews, and avatars, in the app cache folder.
 * The SDK downloads and decrypts the file attribute; Coil decodes it and keeps the bitmap in memory.
 */
class MegaImages(context: Context, private val mega: Mega) {
    private val generation = AtomicLong()
    private val writes = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Downloads that run now, by cache file. */
    private val inFlight = ConcurrentHashMap<File, Deferred<File>>()

    private val root = File(context.cacheDir, "images")
    private val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val previews = File(root, "previews").apply { mkdirs() }
    private val avatars = File(root, "avatars").apply { mkdirs() }
    /** Images of folder-link nodes. Mega.clearFolderCache deletes them when the link closes. */
    private val links = File(root, "links")

    fun file(image: NodeImage): File {
        val dir = if (image.preview) previews else thumbs
        return File(if (image.folderLink) File(links, dir.name) else dir, MegaApiJava.handleToBase64(image.handle) + ".jpg")
    }

    fun file(avatar: UserAvatar): File = File(avatars, privateCacheKey(avatar.user) + ".jpg")

    suspend fun load(image: NodeImage): File = fetch(file(image), "img") { tmp ->
        val api = if (image.folderLink) mega.folderApi else mega.api
        val node = api.getNodeByHandle(image.handle) ?: throw IOException("Node not found")
        if (image.preview) api.await { getPreview(node, tmp.absolutePath, it) }
        else api.await { getThumbnail(node, tmp.absolutePath, it) }
    }

    suspend fun load(avatar: UserAvatar): File = fetch(file(avatar), "avatar") { tmp ->
        mega.api.await { getUserAvatar(avatar.user, tmp.absolutePath, it) }
    }

    /**
     * Returns [target] from the cache, or downloads it with [download] into a temporary file.
     * The download runs in [scope] and all requests for [target] share it: a cancelled request
     * only stops waiting, and the file still goes into the cache (MEGAGLASS-PERF-002).
     */
    private suspend fun fetch(target: File, prefix: String, download: suspend (tmp: File) -> Unit): File =
        withContext(Dispatchers.IO) {
            val epoch = generation.get()
            if (target.length() > 0) { target.setLastModified(System.currentTimeMillis()); return@withContext target }
            // Start after computeIfAbsent: a handler that runs at once must not change the map in it.
            val job = inFlight.computeIfAbsent(target) {
                scope.async(start = CoroutineStart.LAZY) { store(target, prefix, epoch, download) }
            }
            if (job.start()) job.invokeOnCompletion { inFlight.remove(target, job) }
            job.await()
        }

    private suspend fun store(target: File, prefix: String, epoch: Long, download: suspend (tmp: File) -> Unit): File {
        target.parentFile!!.ensureDirectory()
        val tmp = File.createTempFile(prefix, ".part", target.parentFile)
        try {
            download(tmp)
            if (epoch != generation.get()) throw IOException("Image cache was cleared")
            if (!tmp.renameTo(target)) throw IOException("Cannot write image cache")
            target.setLastModified(System.currentTimeMillis())
        } finally {
            tmp.delete()
        }
        trim()
        return target
    }

    /** Drops cached images of nodes whose file attributes changed. */
    fun invalidate(handle: Long) {
        val name = MegaApiJava.handleToBase64(handle) + ".jpg"
        // A later request downloads the new attribute and does not wait for the old download.
        listOf(File(thumbs, name), File(previews, name)).forEach { inFlight.remove(it); it.delete() }
    }

    fun invalidate(avatar: UserAvatar) {
        inFlight.remove(file(avatar))
        file(avatar).delete()
    }

    fun clear() {
        generation.incrementAndGet()
        inFlight.clear()
        root.deleteRecursively()
        thumbs.mkdirs(); previews.mkdirs(); avatars.mkdirs()
    }

    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun trim() {
        if (writes.incrementAndGet() % 64 != 0L) return
        // Image requests take seconds, so an older file is from a request that did not finish
        // (for example, the process stopped). A request that still waits creates its file again
        // when it finishes.
        removeStaleParts(root, System.currentTimeMillis() - 10 * 60_000)
        trimImageCache(root, 256L * 1024 * 1024)
    }

    private fun result(file: File): FetchResult = SourceFetchResult(
        source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )

    val nodeFetcher = Fetcher.Factory<NodeImage> { data, _, _ -> Fetcher { result(load(data)) } }
    val avatarFetcher = Fetcher.Factory<UserAvatar> { data, _, _ -> Fetcher { result(load(data)) } }

    val nodeKeyer = Keyer<NodeImage> { data, _ -> "node:${data.handle}:${data.preview}:${data.folderLink}" }
    val avatarKeyer = Keyer<UserAvatar> { data, _ -> "avatar:${data.user}:${data.version}" }

    fun register(builder: ImageLoader.Builder): ImageLoader.Builder = builder.components {
        add(nodeKeyer)
        add(avatarKeyer)
        add(nodeFetcher)
        add(avatarFetcher)
    }
}
