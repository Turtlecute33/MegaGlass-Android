package app.megaglass.android.core.storage

import app.megaglass.android.core.util.catching

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * [path] is a file path or a content:// URI, which the SDK reads through FileWrapper.
 * [temporary] sources are private copies removed only after a successful upload.
 */
data class LocalSource(val path: String, val name: String, val temporary: Boolean) {
    val isContentUri: Boolean get() = isContentUri(path)
}

/** The device has not enough free space for a private copy. */
class NoSpaceException : IOException("Not enough free space")

/** Local file system helpers: real paths for content URIs, download folders, sharing. */
class LocalFiles(private val context: Context) {

    val hasAllFilesAccess: Boolean get() = Environment.isExternalStorageManager()

    /** Download/MegaGlass with all-files access, otherwise the app's own external folder. */
    fun defaultDownloadDir(): File =
        if (hasAllFilesAccess) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MegaGlass")
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        }

    fun downloadDir(settings: AppSettings): File =
        (settings.downloadDir?.let(::File)?.takeIf { hasAllFilesAccess } ?: defaultDownloadDir()).apply { mkdirs() }

    /** Folder for files opened with other apps; cleared from the cache settings. */
    val openDir: File get() = File(context.cacheDir, "open").apply { mkdirs() }

    private val uploadTmpDir: File get() = File(context.cacheDir, "uploads").apply { mkdirs() }

    /** Upload copies older than this run belong to an earlier process. */
    private val startedAt = System.currentTimeMillis()

    /**
     * Returns a path the SDK can read for [uri]. With all-files access, a URI from the app's own
     * pickers maps to a real path, which avoids a copy. Other content is copied into the cache.
     * A URI [shared] by another app is always read through the content resolver, so that the
     * sender's access applies: a path, document ID or _data value from the sender can name a
     * file that only this app can read.
     */
    suspend fun resolveForUpload(uri: Uri, shared: Boolean): LocalSource? = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        // "userId@authority" also names the app's own provider.
        if (uri.authority?.substringAfterLast('@') == context.packageName + ".files") return@withContext null
        val name = displayName(uri) ?: "file-${System.currentTimeMillis()}"
        if (!shared) realPath(uri)?.let { path ->
            val f = File(path)
            if (f.isFile && f.canRead() && !isPrivate(f)) return@withContext LocalSource(f.absolutePath, f.name, temporary = false)
        }
        val dir = File(uploadTmpDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, localFileName(name))
        try {
            // A copy that cannot fit fails now, not after it has filled the storage.
            size(uri)?.let { if (it > freeSpace(dir)) throw NoSpaceException() }
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw IOException("Cannot read shared file")
            LocalSource(target.absolutePath, target.name, temporary = true)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if ((e.cause as? ErrnoException)?.errno == OsConstants.ENOSPC) NoSpaceException() else e
        }
    }

    /** Free space for [dir], with the cached data that Android can remove for it. */
    private fun freeSpace(dir: File): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(dir))
    }

    private fun size(uri: Uri): Long? = catching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** A file in the private storage of this app: a share from another app must not upload it. */
    private fun isPrivate(file: File): Boolean {
        return overlapsPrivateStorage(file, context.dataDir) ||
            overlapsPrivateStorage(file, context.createDeviceProtectedStorageContext().dataDir)
    }

    fun displayName(uri: Uri): String? = catching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Real file path behind a content URI, when it can be found. */
    fun realPath(uri: Uri): String? = queryRealPath(uri)?.takeUnless { isPrivate(File(it)) }

    private fun queryRealPath(uri: Uri): String? {
        if (!hasAllFilesAccess) return null
        catching {
            if (DocumentsContract.isDocumentUri(context, uri) && uri.authority == "com.android.externalstorage.documents") {
                val parts = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
                if (parts.size == 2) {
                    val base = if (parts[0].equals("primary", true)) Environment.getExternalStorageDirectory().path
                    else "/storage/${parts[0]}"
                    return "$base/${parts[1]}"
                }
            }
            if (DocumentsContract.isTreeUri(uri) && uri.authority == "com.android.externalstorage.documents") {
                val parts = DocumentsContract.getTreeDocumentId(uri).split(":", limit = 2)
                if (parts.size == 2) {
                    val base = if (parts[0].equals("primary", true)) Environment.getExternalStorageDirectory().path
                    else "/storage/${parts[0]}"
                    return "$base/${parts[1]}"
                }
            }
            if (DocumentsContract.isDocumentUri(context, uri) && uri.authority == "com.android.providers.downloads.documents") {
                downloadsPath(DocumentsContract.getDocumentId(uri))?.let { return it }
            }
            if (DocumentsContract.isTreeUri(uri) && uri.authority == "com.android.providers.downloads.documents") {
                downloadsPath(DocumentsContract.getTreeDocumentId(uri))?.let { return it }
            }
            if (DocumentsContract.isDocumentUri(context, uri) && uri.authority == "com.android.providers.media.documents") {
                val (type, id) = DocumentsContract.getDocumentId(uri).split(":", limit = 2).let { it[0] to it.getOrNull(1) }
                val base = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                }
                id?.toLongOrNull()?.let { dataColumn(android.content.ContentUris.withAppendedId(base, it)) }?.let { return it }
            }
            dataColumn(uri)?.let { return it }
        }
        return null
    }

    /** Downloads provider IDs: "raw:" holds the path, "msf:" is a MediaStore row. */
    private fun downloadsPath(id: String): String? = when {
        id.startsWith("raw:") -> id.removePrefix("raw:")
        id.startsWith("msf:") -> id.removePrefix("msf:").toLongOrNull()
            ?.let { dataColumn(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, it)) }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(uri: Uri): String? = catching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * Removes upload copies that no upload in the SDK queue ([inUse]) reads. Copies of this run
     * stay, for Retry, unless [all]; then only the copies of the last minutes stay.
     */
    fun cleanUploadTemp(inUse: Set<String>, all: Boolean = false) {
        removeUploadCopies(uploadTmpDir, inUse, if (all) System.currentTimeMillis() - 120_000 else startedAt)
    }

    /** Photos from "Take photo", a folder each, until their upload succeeds. */
    val capturesDir: File get() = File(context.filesDir, "pending-uploads")

    /**
     * Moves the photos of earlier runs that no upload reads ([inUse]) to the gallery: their
     * upload failed, was cancelled, or never started. Returns how many it moved.
     */
    fun rescueCaptures(inUse: Set<String>): Int =
        leftoverCaptures(capturesDir, inUse, startedAt).count { catching { moveToGallery(it) }.isSuccess }

    /** Moves a photo from [capturesDir] to Pictures/MegaGlass, where the user owns it. */
    fun moveToGallery(photo: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(photo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MegaGlass")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Cannot save the photo")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("Cannot save the photo")).use { out ->
                photo.inputStream().use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            catching { resolver.delete(uri, null, null) }
            throw e
        }
        photo.delete()
        photo.parentFile?.delete()
    }

    /** content:// URI for a local file, for sharing and "open with". */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
}
