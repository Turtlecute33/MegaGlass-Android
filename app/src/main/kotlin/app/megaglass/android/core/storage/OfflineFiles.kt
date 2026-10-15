package app.megaglass.android.core.storage

import java.io.File
import java.io.IOException

/** A download stays in staging until the SDK confirms the complete top-level transfer. */
internal class OfflineFiles(private val root: File) {
    private val pending get() = File(root, ".pending").ensureDirectory()

    private fun staged(id: String): File {
        require(isSafeId(id))
        return File(pending, id)
    }

    /** Staging for download [id] of node [key]. The key file shows that the download was not removed since. */
    fun stage(id: String, key: String): File {
        val dir = staged(id).ensureDirectory()
        File(dir, KEY_FILE).writeText(key)
        return File(dir, "data").ensureDirectory()
    }

    /** Node key of a staged download, or null when it was discarded. */
    fun stagedKey(id: String): String? = runCatching { File(staged(id), KEY_FILE).readText() }.getOrNull()

    fun stagedIds(): List<String> = pending.list().orEmpty().filter(::isSafeId)

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
        val staged = staged(id)
        val files = File(staged, "data").listFiles().orEmpty()
        if (files.size != 1) throw IOException("Offline download is incomplete")
        File(staged, ".complete").writeText(files.single().name)
        File(staged, KEY_FILE).delete()
        val target = File(root, key)
        // Keep existing data, including copies from earlier versions without completion metadata,
        // until the new copy is in place. It must not be lost because a replacement failed.
        if (target.exists()) {
            val backup = File(root, "$PREVIOUS$key-${java.util.UUID.randomUUID()}")
            if (!target.renameTo(backup)) throw IOException("Cannot preserve previous offline copy")
            if (!staged.renameTo(target)) {
                backup.renameTo(target)
                throw IOException("Cannot save offline copy")
            }
            backup.deleteRecursively()
        } else if (!staged.renameTo(target)) throw IOException("Cannot save offline copy")
    }

    fun discard(id: String) { staged(id).deleteRecursively() }

    /** Removes the previous copies of [key] that an interrupted [commit] left. */
    fun removePrevious(key: String) {
        root.listFiles().orEmpty().filter { it.name.startsWith("$PREVIOUS$key-") }.forEach { it.deleteRecursively() }
    }

    /**
     * A process stop between the two renames of [commit] leaves only the previous copy: it goes
     * back. A stop after them leaves a previous copy that is not needed any more.
     */
    fun recover() {
        for (backup in root.listFiles().orEmpty().filter { it.name.startsWith(PREVIOUS) }) {
            // ".previous-<key>-<36-character UUID>"
            val key = backup.name.removePrefix(PREVIOUS).dropLast(37)
            val target = File(root, key)
            if (key.isNotEmpty() && !key.startsWith('.') && key == localFileName(key) && !target.exists()) {
                if (!backup.renameTo(target)) throw IOException("Cannot restore offline copy")
            } else {
                backup.deleteRecursively()
            }
        }
    }

    companion object {
        private const val KEY_FILE = ".key"
        private const val PREVIOUS = ".previous-"

        fun isSafeId(id: String) = id.matches(Regex("[A-Za-z0-9_-]+"))
    }
}
