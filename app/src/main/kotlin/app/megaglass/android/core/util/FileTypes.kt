package app.megaglass.android.core.util

import android.webkit.MimeTypeMap

enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, CODE, TEXT, APK, OTHER;

    val isMedia get() = this == IMAGE || this == VIDEO

    companion object {
        private val byExtension: Map<String, FileType> = buildMap {
            fun put(type: FileType, vararg ext: String) = ext.forEach { put(it, type) }
            put(IMAGE, "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "dng",
                "cr2", "cr3", "nef", "arw", "orf", "raf", "rw2", "svg", "ico", "jxl")
            put(VIDEO, "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "mts", "vob", "ogv")
            put(AUDIO, "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aif", "aiff", "alac", "amr", "mid", "midi")
            put(PDF, "pdf")
            put(DOCUMENT, "doc", "docx", "odt", "rtf", "pages", "epub", "mobi")
            put(SPREADSHEET, "xls", "xlsx", "ods", "csv", "tsv", "numbers")
            put(PRESENTATION, "ppt", "pptx", "odp", "key")
            put(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "dmg")
            put(CODE, "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "py", "js", "ts", "tsx", "jsx", "go", "rs",
                "swift", "rb", "php", "sh", "zsh", "bash", "html", "htm", "css", "scss", "xml", "json", "yaml", "yml",
                "toml", "gradle", "sql", "lua", "dart", "cs", "m", "mm", "pl", "r", "ini", "conf", "cfg")
            put(TEXT, "txt", "md", "markdown", "log", "srt", "vtt", "nfo", "rst", "tex")
            put(APK, "apk", "apks", "xapk")
        }

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

        fun of(name: String, isFolder: Boolean = false): FileType =
            if (isFolder) FOLDER else byExtension[extension(name)] ?: OTHER

        fun mimeType(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name)) ?: "application/octet-stream"
    }
}

/** Text-like files that the built-in editor can open. */
fun FileType.isEditableText() = this == FileType.TEXT || this == FileType.CODE
