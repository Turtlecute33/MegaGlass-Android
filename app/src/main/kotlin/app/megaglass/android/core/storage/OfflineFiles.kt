package app.megaglass.android.core.storage

import java.io.File
import java.io.IOException

/** A download stays in staging until the SDK confirms the complete top-level transfer. */
internal class OfflineFiles(private val root: File) {
    private val pending get() = File(root, ".pending").ensureDirectory()

    fun stage(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(File(pending, id), "data").ensureDirectory()
    }

    fun entry(key: String): File? {
        val dir = File(root, key)
        val marker = File(dir, ".complete")
        if (!marker.isFile) return null
        val name = runCatching { marker.readText() }.getOrNull() ?: return null
        if (name != localFileName(name)) return null
        return File(File(dir, "data"), name).takeIf { it.exists() }
    }

    fun keys(): Set<String> = root.listFiles().orEmpty()
        .filter { !it.name.startsWith('.') && entry(it.name) != null }.map { it.name }.toSet()

    fun commit(id: String, key: String) {
        val staged = File(pending, id)
        val files = File(staged, "data").listFiles().orEmpty()
        if (files.size != 1) throw IOException("Offline download is incomplete")
        File(staged, ".complete").writeText(files.single().name)
        val target = File(root, key)
        // Keep existing data, including copies from earlier versions without completion metadata.
        // It must never be removed just because a replacement download failed.
        if (target.exists()) {
            val backup = File(root, ".previous-$key-${java.util.UUID.randomUUID()}")
            if (!target.renameTo(backup)) throw IOException("Cannot preserve previous offline copy")
            if (!staged.renameTo(target)) {
                backup.renameTo(target)
                throw IOException("Cannot save offline copy")
            }
        } else if (!staged.renameTo(target)) throw IOException("Cannot save offline copy")
    }

    fun discard(id: String) { File(pending, id).deleteRecursively() }
}
