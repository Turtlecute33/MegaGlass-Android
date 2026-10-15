package app.megaglass.android.core.storage

import app.megaglass.android.core.util.catching

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** [temporary] sources are private copies removed only after a successful upload. */
data class LocalSource(val path: String, val name: String, val temporary: Boolean)

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

    /**
     * Returns a path the SDK can read for [uri]. With all-files access most URIs map to a real
     * path, which avoids a copy. Otherwise the content is copied into the cache.
     */
    suspend fun resolveForUpload(uri: Uri): LocalSource? = withContext(Dispatchers.IO) {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == context.packageName + ".files") return@withContext null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: return@withContext null)
            if (!f.isFile || !f.canRead() || isPrivate(f)) return@withContext null
            return@withContext LocalSource(f.absolutePath, f.name, temporary = false)
        }
        val name = displayName(uri) ?: "file-${System.currentTimeMillis()}"
        realPath(uri)?.let { path ->
            val f = File(path)
            if (f.isFile && f.canRead() && !isPrivate(f)) return@withContext LocalSource(f.absolutePath, f.name, temporary = false)
        }
        val dir = File(uploadTmpDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, localFileName(name))
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw java.io.IOException("Cannot read shared file")
            LocalSource(target.absolutePath, target.name, temporary = true)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

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
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) return id.removePrefix("raw:")
                if (id.startsWith("msf:")) {
                    dataColumn(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id.removePrefix("msf:").toLong()))?.let { return it }
                }
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

    @Suppress("DEPRECATION")
    private fun dataColumn(uri: Uri): String? = catching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Removes empty folders left after successful copied-upload cleanup. */
    fun cleanUploadTemp() {
        uploadTmpDir.listFiles()?.forEach { dir -> if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete() }
    }

    /** content:// URI for a local file, for sharing and "open with". */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
}
