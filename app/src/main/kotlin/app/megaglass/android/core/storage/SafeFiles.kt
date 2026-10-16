package app.megaglass.android.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun Char.isDirectionMark() =
    this == '\u061C' || this in '\u200B'..'\u200F' || this in '\u202A'..'\u202E' || this in '\u2060'..'\u2069' || this == '\uFEFF'

private fun Char.isControl() = this <= '\u001F' || this in '\u007F'..'\u009F' || this == '\u2028' || this == '\u2029'

/**
 * Whoever uploads a file chooses its name. Direction marks can show "invoice<U+202E>fdp.apk" as
 * "invoicekpa.pdf", and control characters and line breaks can hide a part of a name. Names on
 * screen and on disk drop the marks and replace the controls with U+FFFD.
 */
fun displayName(name: String): String {
    if (name.none { it.isDirectionMark() || it.isControl() }) return name
    return buildString { for (c in name) if (!c.isDirectionMark()) append(if (c.isControl()) '\uFFFD' else c) }
}

/**
 * Names supplied by cloud nodes and document providers are single path components. A name
 * longer than the 255 bytes that Android file systems allow is cut, with space left for the
 * " (1)" of a name collision; the extension stays.
 */
fun localFileName(name: String): String =
    shortName(
        displayName(name).replace('/', '_').replace('\\', '_')
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file",
    )

private const val MAX_NAME_BYTES = 255
private const val SHORT_NAME_BYTES = 240

private fun utf8Length(codePoint: Int) = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

private fun utf8Length(s: String) = s.toByteArray(Charsets.UTF_8).size

private fun shortName(name: String): String {
    if (utf8Length(name) <= MAX_NAME_BYTES) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && utf8Length(name.substring(dot)) <= 16) name.substring(dot) else ""
    val budget = SHORT_NAME_BYTES - utf8Length(extension)
    val base = StringBuilder()
    var bytes = 0
    var i = 0
    // Cut between code points, so that no character is split.
    while (i < name.length - extension.length) {
        val c = name.codePointAt(i)
        if (bytes + utf8Length(c) > budget) break
        base.appendCodePoint(c)
        bytes += utf8Length(c)
        i += Character.charCount(c)
    }
    return base.toString() + extension
}

fun isContentUri(path: String): Boolean = path.startsWith("content://")

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

/**
 * Removes the upload copies in [root] (a folder each) that no path in [inUse] reads and that
 * were last written before [before]: newer copies can still be in preparation.
 */
fun removeUploadCopies(root: File, inUse: Set<String>, before: Long) {
    root.listFiles()?.forEach { dir ->
        val prefix = dir.absolutePath + File.separator
        if (inUse.any { it == dir.absolutePath || it.startsWith(prefix) }) return@forEach
        if (dir.walkTopDown().all { it.lastModified() < before }) dir.deleteRecursively()
    }
}

/**
 * The photos in [root] (a folder each) that no path in [inUse] reads and that were last written
 * before [before]. Such folders without a photo are removed: the camera saved nothing.
 */
fun leftoverCaptures(root: File, inUse: Set<String>, before: Long): List<File> {
    val photos = ArrayList<File>()
    root.listFiles()?.forEach { dir ->
        val prefix = dir.absolutePath + File.separator
        if (inUse.any { it == dir.absolutePath || it.startsWith(prefix) }) return@forEach
        if (!dir.walkTopDown().all { it.lastModified() < before }) return@forEach
        val saved = dir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }
        if (saved.isEmpty()) dir.deleteRecursively() else photos += saved
    }
    return photos
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

/**
 * Removes the ".part" files in [root] last written before [before]. The SDK writes the file of
 * a cancelled image request when the request finishes, after its reader has gone.
 */
fun removeStaleParts(root: File, before: Long) {
    root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < before }.forEach { it.delete() }
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

    fun contains(file: File, size: Long): Boolean {
        if (!file.isFile || file.length() != size) return false
        val marker = marker(file)
        return marker.isFile && runCatching { marker.readText() == "$size:${file.lastModified()}" }.getOrDefault(false)
    }

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
