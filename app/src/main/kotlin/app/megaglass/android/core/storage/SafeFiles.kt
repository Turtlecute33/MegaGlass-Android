package app.megaglass.android.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Names supplied by cloud nodes and document providers are single path components. */
fun localFileName(name: String): String =
    name.replace('/', '_').replace('\\', '_').replace('\u0000', '_')
        .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"

fun File.ensureDirectory(): File = apply {
    if (!isDirectory && !mkdirs()) throw IOException("Cannot create folder: $name")
}

/** Includes parent directories: recursively uploading an ancestor also exposes private data. */
fun overlapsPrivateStorage(file: File, privateRoot: File): Boolean = try {
    val source = file.canonicalFile.toPath()
    val root = privateRoot.canonicalFile.toPath()
    source.startsWith(root) || root.startsWith(source)
} catch (_: IOException) { true }

/** Preserve both files on a name collision, including collisions inside a queued batch. */
fun availableName(name: String, folder: Boolean = false, occupied: (String) -> Boolean): String {
    if (!occupied(name)) return name
    val dot = if (folder) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val base = if (dot >= 0) name.substring(0, dot) else name
    val extension = if (dot >= 0) name.substring(dot) else ""
    var number = 1
    while (true) {
        val candidate = "$base (${number++})$extension"
        if (!occupied(candidate)) return candidate
    }
}

/** Commits a completed private file without first deleting the previous copy. */
fun replaceFile(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

fun privateCacheKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Trim copies that can be fetched again, protecting recent readers and active SDK writes. */
fun trimImageCache(root: File, budget: Long, now: Long = System.currentTimeMillis()) {
    val files = root.walkTopDown().filter { it.isFile }.toList()
    var bytes = files.sumOf { it.length() }
    if (bytes <= budget) return
    for (file in files.filter { !it.name.endsWith(".part") && it.lastModified() < now - 120_000 }.sortedBy { it.lastModified() }) {
        val size = file.length()
        if (file.delete()) bytes -= size
        if (bytes <= budget) break
    }
}

/** Only app-owned upload copies may be removed after the SDK confirms success. */
fun deleteUploadCopy(file: File, roots: List<File>) {
    val source = file.canonicalFile
    require(roots.any { source.toPath().startsWith(it.canonicalFile.toPath()) && source != it.canonicalFile }) {
        "Upload source is outside private temporary storage"
    }
    if (source.exists() && (!source.isFile || !source.delete())) throw IOException("Cannot remove upload copy")
    val parent = source.parentFile
    if (parent != null && roots.none { parent == it.canonicalFile } && parent.list()?.isEmpty() == true) parent.delete()
}

/** A byte count alone does not prove that the SDK finished and verified a download. */
object VerifiedFiles {
    private fun marker(file: File): File {
        val key = privateCacheKey(file.name)
        return File(file.parentFile, ".verified-$key")
    }

    fun contains(file: File, size: Long): Boolean =
        file.isFile && file.length() == size && marker(file).isFile &&
            runCatching { marker(file).readText() == "$size:${file.lastModified()}" }.getOrDefault(false)

    fun forget(file: File) { marker(file).delete() }

    fun mark(file: File) {
        if (!file.isFile) throw IOException("Downloaded file is missing")
        val target = marker(file)
        val tmp = File.createTempFile("verified", ".tmp", file.parentFile)
        try {
            tmp.writeText("${file.length()}:${file.lastModified()}")
            replaceFile(tmp, target)
        } finally {
            tmp.delete()
        }
    }
}
