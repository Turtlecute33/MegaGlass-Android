package app.megaglass.android.feature.links

/** A mega.nz link, split into what the SDK needs. */
sealed interface MegaLink {
    /** The link to give to the SDK (sub-paths removed for folders). */
    val url: String

    data class File(override val url: String) : MegaLink

    /** [subFolder] and [subFile] are Base64 node handles inside the folder, from /folder/… and /file/… suffixes. */
    data class Folder(override val url: String, val subFolder: String?, val subFile: String?) : MegaLink

    data class Album(override val url: String) : MegaLink

    /** Password protected (#P!); decrypt it first, then parse the result. */
    data class Protected(override val url: String) : MegaLink

    data class Invalid(override val url: String) : MegaLink
}

/** True when the link has no decryption key. */
fun MegaLink.needsKey(): Boolean = when (this) {
    is MegaLink.File -> keyOf(url).isEmpty()
    is MegaLink.Folder -> keyOf(url).isEmpty()
    is MegaLink.Album -> keyOf(url).isEmpty()
    else -> false
}

/** The same link with [key] as its decryption key. */
fun MegaLink.withKey(key: String): MegaLink {
    val k = key.trim().removePrefix("#").removePrefix("!")
    return when (this) {
        is MegaLink.File -> MegaLink.File(replaceKey(url, k))
        is MegaLink.Folder -> copy(url = replaceKey(url, k))
        is MegaLink.Album -> MegaLink.Album(replaceKey(url, k))
        else -> this
    }
}

private fun fragment(url: String) = url.substringAfter('#', "")

private fun keyOf(url: String): String {
    val f = fragment(url)
    return when {
        f.startsWith("F!") || f.startsWith("!") -> f.removePrefix("F").removePrefix("!").split('!').getOrNull(1).orEmpty()
        else -> f.substringBefore('/')
    }
}

private fun replaceKey(url: String, key: String): String {
    val f = fragment(url)
    val base = url.substringBefore('#')
    return when {
        f.startsWith("F!") -> base + "#F!" + f.removePrefix("F!").substringBefore('!') + "!" + key
        f.startsWith("!") -> base + "#!" + f.removePrefix("!").substringBefore('!') + "!" + key
        else -> "$base#$key"
    }
}

/**
 * Parses https://mega.nz links (also mega.co.nz, www., and mega:// forms):
 * /file/h#k, #!h!k, /folder/h#k[/folder/s | /file/s], #F!h!k[!s], /collection/h#k, #P!….
 */
fun parseMegaLink(raw: String): MegaLink {
    var u = raw.trim()
    if (u.startsWith("mega://", ignoreCase = true)) u = "https://mega.nz/" + u.substring(7).removePrefix("/")
    u = u.replace(Regex("^https?://(www\\.)?mega\\.(co\\.nz|nz|io)", RegexOption.IGNORE_CASE), "https://mega.nz")
    val path = u.substringBefore('#')
    val frag = fragment(u)
    return when {
        frag.startsWith("P!") -> MegaLink.Protected(u)
        frag.startsWith("F!") -> {
            val parts = frag.removePrefix("F!").split('!')
            val handle = parts.getOrNull(0).orEmpty()
            if (handle.isEmpty()) return MegaLink.Invalid(u)
            val key = parts.getOrNull(1).orEmpty()
            MegaLink.Folder("https://mega.nz/#F!$handle!$key", parts.getOrNull(2)?.substringBefore('?')?.takeIf { it.isNotEmpty() }, null)
        }
        frag.startsWith("!") -> MegaLink.File(u)
        "/file/" in path -> MegaLink.File(u)
        "/folder/" in path -> {
            val key = frag.substringBefore('/')
            val rest = frag.substringAfter('/', "")
            val base = "$path#$key"
            when {
                rest.startsWith("folder/") -> MegaLink.Folder(base, rest.removePrefix("folder/").substringBefore('/'), null)
                rest.startsWith("file/") -> MegaLink.Folder(base, null, rest.removePrefix("file/").substringBefore('/'))
                else -> MegaLink.Folder(base, null, null)
            }
        }
        "/collection/" in path -> MegaLink.Album(u)
        else -> MegaLink.Invalid(u)
    }
}
