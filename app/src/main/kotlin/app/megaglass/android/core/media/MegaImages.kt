package app.megaglass.android.core.media

import android.content.Context
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.mega.await
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/** Coil data: the thumbnail (200 px) or preview (1000 px) of a node. */
data class NodeImage(val handle: Long, val preview: Boolean = false, val folderLink: Boolean = false)

/** Coil data: the avatar of a user (e-mail or Base64 user handle). */
data class UserAvatar(val user: String, val version: Int = 0)

/**
 * Disk cache for decrypted thumbnails, previews, and avatars, in the app cache folder.
 * The SDK downloads and decrypts the file attribute; Coil decodes it and keeps the bitmap in memory.
 */
class MegaImages(context: Context, private val mega: Mega) {

    private val root = File(context.cacheDir, "images")
    private val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val previews = File(root, "previews").apply { mkdirs() }
    private val avatars = File(root, "avatars").apply { mkdirs() }

    fun file(image: NodeImage): File =
        File(if (image.preview) previews else thumbs, MegaApiJava.handleToBase64(image.handle) + ".jpg")

    fun file(avatar: UserAvatar): File = File(avatars, avatar.user.hashCode().toUInt().toString(16) + ".jpg")

    suspend fun load(image: NodeImage): File = withContext(Dispatchers.IO) {
        val target = file(image)
        if (target.length() > 0) return@withContext target
        val api = if (image.folderLink) mega.folderApi else mega.api
        val node = api.getNodeByHandle(image.handle) ?: throw IOException("Node not found")
        val tmp = File.createTempFile("img", ".part", target.parentFile)
        try {
            if (image.preview) api.await { getPreview(node, tmp.absolutePath, it) }
            else api.await { getThumbnail(node, tmp.absolutePath, it) }
            if (!tmp.renameTo(target)) throw IOException("Cannot write image cache")
        } finally {
            tmp.delete()
        }
        target
    }

    suspend fun load(avatar: UserAvatar): File = withContext(Dispatchers.IO) {
        val target = file(avatar)
        if (target.length() > 0) return@withContext target
        val tmp = File.createTempFile("avatar", ".part", target.parentFile)
        try {
            mega.api.await { getUserAvatar(avatar.user, tmp.absolutePath, it) }
            if (!tmp.renameTo(target)) throw IOException("Cannot write image cache")
        } finally {
            tmp.delete()
        }
        target
    }

    /** Drops cached images of nodes whose file attributes changed. */
    fun invalidate(handle: Long) {
        val name = MegaApiJava.handleToBase64(handle) + ".jpg"
        File(thumbs, name).delete()
        File(previews, name).delete()
    }

    fun invalidate(avatar: UserAvatar) {
        file(avatar).delete()
    }

    fun clear() {
        root.deleteRecursively()
        thumbs.mkdirs(); previews.mkdirs(); avatars.mkdirs()
    }

    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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
