package app.megaglass.android.feature.drive

import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.util.FileType
import app.megaglass.android.feature.drive.NodeAction.*
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaShare
import org.junit.Assert.assertEquals
import org.junit.Test

/** MEGAGLASS-UX-011: the actions that each access level and each place gets. */
class NodeActionsTest {
    private var nextHandle = 1L

    /** Made-up node. Access below owner means an item of an incoming share. */
    private fun item(
        access: Int = MegaShare.ACCESS_OWNER,
        folder: Boolean = false,
        shareRoot: Boolean = false,
        exported: Boolean = false,
        takenDown: Boolean = false,
        favourite: Boolean = false,
        sensitive: Boolean = false,
    ) = NodeItem(
        handle = nextHandle++,
        parentHandle = if (shareRoot) MegaApiJava.INVALID_HANDLE else 100,
        name = if (folder) "Folder" else "notes.txt",
        isFolder = folder,
        type = if (folder) FileType.FOLDER else FileType.TEXT,
        size = 10,
        modified = 0,
        created = 0,
        hasThumbnail = false,
        hasPreview = false,
        isFavourite = favourite,
        label = 0,
        isExported = exported,
        isOutShare = false,
        isInShare = shareRoot || access < MegaShare.ACCESS_OWNER,
        isTakenDown = takenDown,
        isSensitive = sensitive,
        duration = -1,
        access = access,
    )

    private class One(val name: String, val item: NodeItem, val context: NodeContext, val versions: Int, val expected: List<NodeAction>)

    @Test fun actionsForEachAccessAndPlace() {
        val cases = listOf(
            One(
                "own file", item(), NodeContext.CLOUD, 1,
                listOf(INFO, FAVOURITE, DOWNLOAD, OFFLINE, GET_LINK, SEND, OPEN_WITH, RENAME, MOVE, COPY, HIDE, RUBBISH),
            ),
            One(
                "own exported favourite hidden file with versions",
                item(exported = true, favourite = true, sensitive = true), NodeContext.CLOUD, 3,
                listOf(INFO, UNFAVOURITE, DOWNLOAD, OFFLINE, GET_LINK, REMOVE_LINK, SEND, OPEN_WITH, RENAME, MOVE, COPY, VERSIONS, UNHIDE, RUBBISH),
            ),
            One(
                "own folder", item(folder = true), NodeContext.CLOUD, 3,
                listOf(INFO, FAVOURITE, DOWNLOAD, OFFLINE, GET_LINK, SHARE_FOLDER, RENAME, MOVE, COPY, HIDE, RUBBISH),
            ),
            One(
                "own taken down file", item(exported = true, takenDown = true), NodeContext.CLOUD, 1,
                listOf(INFO, FAVOURITE, DOWNLOAD, OFFLINE, SEND, OPEN_WITH, RENAME, MOVE, COPY, HIDE, RUBBISH),
            ),
            One(
                "full access file", item(MegaShare.ACCESS_FULL), NodeContext.INCOMING, 1,
                listOf(INFO, DOWNLOAD, OFFLINE, SEND, OPEN_WITH, RENAME, COPY, RUBBISH),
            ),
            One(
                "read-write file", item(MegaShare.ACCESS_READWRITE), NodeContext.INCOMING, 1,
                listOf(INFO, DOWNLOAD, OFFLINE, SEND, OPEN_WITH, COPY),
            ),
            One(
                "read-only exported file", item(MegaShare.ACCESS_READ, exported = true), NodeContext.INCOMING, 1,
                listOf(INFO, DOWNLOAD, OFFLINE, SEND, OPEN_WITH, COPY),
            ),
            One(
                "full access share root", item(MegaShare.ACCESS_FULL, folder = true, shareRoot = true), NodeContext.INCOMING, 1,
                listOf(INFO, DOWNLOAD, OFFLINE, RENAME, COPY, LEAVE_SHARE),
            ),
            One(
                "read-only share root", item(MegaShare.ACCESS_READ, folder = true, shareRoot = true), NodeContext.INCOMING, 1,
                listOf(INFO, DOWNLOAD, OFFLINE, COPY, LEAVE_SHARE),
            ),
            One("rubbish bin file", item(exported = true), NodeContext.RUBBISH, 3, listOf(INFO, RESTORE, DELETE)),
            One("backups file", item(), NodeContext.BACKUPS, 3, listOf(INFO, DOWNLOAD, OFFLINE, COPY, SEND, OPEN_WITH)),
            One("backups folder", item(folder = true), NodeContext.BACKUPS, 1, listOf(INFO, DOWNLOAD, OFFLINE, COPY)),
        )
        for (case in cases) assertEquals(case.name, case.expected, actionsFor(case.item, case.context, case.versions))
    }

    private class Many(
        val name: String,
        val items: List<NodeItem>,
        val context: NodeContext,
        val main: List<NodeAction>,
        val extra: List<NodeAction>,
    )

    @Test fun selectionActionsForEachAccessAndPlace() {
        val cases = listOf(
            Many(
                "one own file", listOf(item()), NodeContext.CLOUD,
                listOf(DOWNLOAD, GET_LINK, MOVE, COPY, RUBBISH), listOf(OFFLINE, SEND, FAVOURITE, HIDE),
            ),
            Many(
                "own favourite files, one exported and one hidden",
                listOf(item(exported = true, favourite = true), item(favourite = true, sensitive = true)), NodeContext.CLOUD,
                listOf(DOWNLOAD, MOVE, COPY, RUBBISH), listOf(OFFLINE, SEND, UNFAVOURITE, HIDE, REMOVE_LINK),
            ),
            Many(
                "own hidden file and folder", listOf(item(sensitive = true), item(folder = true, sensitive = true)), NodeContext.CLOUD,
                listOf(DOWNLOAD, MOVE, COPY, RUBBISH), listOf(OFFLINE, FAVOURITE, UNHIDE),
            ),
            Many(
                "full access files", listOf(item(MegaShare.ACCESS_FULL), item(MegaShare.ACCESS_FULL)), NodeContext.INCOMING,
                listOf(DOWNLOAD, COPY, RUBBISH), listOf(OFFLINE, SEND),
            ),
            Many(
                "full access and read-only files", listOf(item(MegaShare.ACCESS_FULL), item(MegaShare.ACCESS_READ)), NodeContext.INCOMING,
                listOf(DOWNLOAD, COPY), listOf(OFFLINE, SEND),
            ),
            Many(
                "one read-only exported file", listOf(item(MegaShare.ACCESS_READ, exported = true)), NodeContext.INCOMING,
                listOf(DOWNLOAD, COPY), listOf(OFFLINE, SEND),
            ),
            Many(
                "full access share root", listOf(item(MegaShare.ACCESS_FULL, folder = true, shareRoot = true)), NodeContext.INCOMING,
                listOf(DOWNLOAD, COPY), listOf(OFFLINE),
            ),
            Many(
                "own and full access files", listOf(item(), item(MegaShare.ACCESS_FULL)), NodeContext.SEARCH,
                listOf(DOWNLOAD, COPY, RUBBISH), listOf(OFFLINE, SEND),
            ),
            Many("rubbish bin files", listOf(item(), item(folder = true)), NodeContext.RUBBISH, listOf(RESTORE, DELETE), emptyList()),
            Many("backups files", listOf(item(), item(folder = true)), NodeContext.BACKUPS, listOf(DOWNLOAD, OFFLINE, COPY), emptyList()),
        )
        for (case in cases) {
            val (main, extra) = selectionActions(case.items, case.context)
            assertEquals("${case.name}: buttons", case.main, main)
            assertEquals("${case.name}: menu", case.extra, extra)
        }
    }
}
