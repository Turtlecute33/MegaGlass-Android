package app.megaglass.android.core.mega

import androidx.compose.runtime.Immutable
import app.megaglass.android.core.util.FileType
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaShare

/**
 * Plain snapshot of a MegaNode for the UI. The UI never holds SDK objects:
 * they are JNI handles, and reading their fields on the main thread costs JNI calls.
 */
@Immutable
data class NodeItem(
    val handle: Long,
    val parentHandle: Long,
    val name: String,
    val isFolder: Boolean,
    val type: FileType,
    val size: Long,
    /** Seconds since epoch. */
    val modified: Long,
    val created: Long,
    val hasThumbnail: Boolean,
    val hasPreview: Boolean,
    val isFavourite: Boolean,
    /** MegaNode.NODE_LBL_* or 0. */
    val label: Int,
    val isExported: Boolean,
    val isOutShare: Boolean,
    val isInShare: Boolean,
    val isTakenDown: Boolean,
    val isSensitive: Boolean,
    /** Seconds; -1 when unknown. */
    val duration: Int,
    val folders: Int = 0,
    val files: Int = 0,
    val versions: Int = 0,
    /** For incoming shares: the access level (MegaShare.ACCESS_*). */
    val access: Int = MegaShare.ACCESS_OWNER,
    /** For incoming shares: the owner e-mail. */
    val owner: String? = null,
) {
    val isImage get() = type == FileType.IMAGE
    val isVideo get() = type == FileType.VIDEO
    val canWrite get() = access >= MegaShare.ACCESS_READWRITE
    val canManage get() = access >= MegaShare.ACCESS_FULL
}

/** Maps a node on the calling (background) thread. Folder counts cost two extra SDK calls. */
fun MegaNode.toItem(api: MegaApiJava, withCounts: Boolean = true, access: Int? = null, owner: String? = null): NodeItem {
    val folder = isFolder
    val nodeName = name ?: ""
    return NodeItem(
        handle = handle,
        parentHandle = parentHandle,
        name = nodeName,
        isFolder = folder,
        type = FileType.of(nodeName, folder),
        size = if (folder) 0 else size,
        modified = if (folder) creationTime else modificationTime,
        created = creationTime,
        hasThumbnail = hasThumbnail(),
        hasPreview = hasPreview(),
        isFavourite = isFavourite,
        label = label,
        isExported = isExported,
        isOutShare = isOutShare,
        isInShare = isInShare,
        isTakenDown = isTakenDown,
        isSensitive = isMarkedSensitive,
        duration = if (folder) -1 else duration,
        folders = if (folder && withCounts) api.getNumChildFolders(this) else 0,
        files = if (folder && withCounts) api.getNumChildFiles(this) else 0,
        access = access ?: api.getAccess(this),
        owner = owner,
    )
}
