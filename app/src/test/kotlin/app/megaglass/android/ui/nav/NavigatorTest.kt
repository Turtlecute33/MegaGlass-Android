package app.megaglass.android.ui.nav

import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.util.FileType
import org.junit.Assert.*
import org.junit.Test

/** MEGAGLASS-UX-011: the back stacks of the tabs. */
class NavigatorTest {
    private val openedOther = ArrayList<Long>()

    private fun navigator(start: Tab = Tab.HOME, onExit: (() -> Unit)? = null) = AppNavigator(
        start,
        openOther = { item, _ -> openedOther += item.handle },
        viewOther = { fail("No local file in these tests") },
        onExit = onExit,
    )

    private val AppNavigator.routes get() = backStack.map { it.route }

    @Test fun opensOnTheRootOfTheStartTab() {
        val nav = navigator(Tab.DRIVE)
        assertEquals(Tab.DRIVE, nav.currentTab)
        assertEquals(listOf(Folder()), nav.routes)
        assertFalse(nav.offlineOnly)
    }

    @Test fun navigateAndBack() {
        val nav = navigator()
        nav.navigate(Transfers)
        nav.navigate(NodeInfo(1))
        assertEquals(listOf(Home, Transfers, NodeInfo(1)), nav.routes)
        assertEquals(NodeInfo(1), nav.currentRoute)
        assertTrue(nav.back())
        assertTrue(nav.back())
        assertEquals(listOf(Home), nav.routes)
        assertFalse(nav.back())
        assertEquals(listOf(Home), nav.routes)
    }

    @Test fun replaceChangesTheTopButKeepsTheRoot() {
        val nav = navigator()
        nav.navigate(NodeInfo(1))
        nav.replace(NodeInfo(2))
        assertEquals(listOf(Home, NodeInfo(2)), nav.routes)
        val root = navigator()
        root.replace(Transfers)
        assertEquals(listOf(Home, Transfers), root.routes)
    }

    @Test fun popToOrNavigatePopsToAnOpenRouteOrOpensIt() {
        val nav = navigator(Tab.DRIVE)
        nav.navigate(Folder(1))
        nav.navigate(Folder(2))
        nav.navigate(NodeInfo(2))
        nav.popToOrNavigate(Folder(1))
        assertEquals(listOf(Folder(), Folder(1)), nav.routes)
        nav.popToOrNavigate(Folder(1))
        assertEquals(listOf(Folder(), Folder(1)), nav.routes)
        nav.popToOrNavigate(Search(1))
        assertEquals(listOf(Folder(), Folder(1), Search(1)), nav.routes)
    }

    @Test fun backFromRemovesOnlyThatRoute() {
        val nav = navigator()
        nav.navigate(NodeInfo(1))
        nav.backFrom(NodeInfo(2))
        assertEquals(listOf(Home, NodeInfo(1)), nav.routes)
        nav.backFrom(NodeInfo(1))
        assertEquals(listOf(Home), nav.routes)
    }

    @Test fun switchTabKeepsTheOtherStacksAndPutsTheCurrentTabLast() {
        val nav = navigator()
        nav.navigate(Transfers)
        nav.switchTab(Tab.DRIVE)
        nav.navigate(Folder(5))
        assertEquals(Tab.DRIVE, nav.currentTab)
        assertEquals(listOf(Home, Transfers, Folder(), Folder(5)), nav.routes)
        nav.switchTab(Tab.HOME)
        assertEquals(listOf(Folder(), Folder(5), Home, Transfers), nav.routes)
    }

    @Test fun switchTabToTheSameTabGoesToItsRoot() {
        val nav = navigator()
        nav.navigate(Transfers)
        nav.navigate(Settings)
        nav.switchTab(Tab.HOME)
        assertEquals(Tab.HOME, nav.currentTab)
        assertEquals(listOf(Home), nav.routes)
    }

    @Test fun backAtATabRootReturnsToTheTabUsedBefore() {
        val nav = navigator()
        nav.switchTab(Tab.MENU)
        nav.switchTab(Tab.DRIVE)
        nav.navigate(Folder(5))
        assertTrue(nav.back())
        assertEquals(listOf(Home, Menu, Folder()), nav.routes)
        assertTrue(nav.back())
        assertEquals(Tab.MENU, nav.currentTab)
        assertEquals(listOf(Home, Menu), nav.routes)
        assertTrue(nav.back())
        assertEquals(Tab.HOME, nav.currentTab)
        assertEquals(listOf(Home), nav.routes)
        assertFalse(nav.back())
    }

    @Test fun aTabThatWasLeftWithBackOpensAgainOnItsRoot() {
        val nav = navigator()
        nav.switchTab(Tab.DRIVE)
        nav.navigate(Folder(5))
        nav.back()
        nav.back()
        nav.switchTab(Tab.DRIVE)
        assertEquals(listOf(Home, Folder()), nav.routes)
    }

    @Test fun theSameRouteInTwoTabsHasTwoEntryIds() {
        val nav = navigator()
        nav.navigate(Transfers)
        nav.switchTab(Tab.DRIVE)
        nav.navigate(Transfers)
        val ids = nav.backStack.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun offlineOnlyOpensOnlyLocalRoutesAndExitsAtTheRoot() {
        var exited = false
        val nav = navigator(onExit = { exited = true })
        assertTrue(nav.offlineOnly)
        assertEquals(listOf(Offline), nav.routes)
        nav.navigate(Transfers)
        nav.navigate(VideoPlayer(1))
        nav.replace(NodeInfo(1))
        assertEquals(listOf(Offline), nav.routes)
        nav.navigate(OfflineFolder("/offline/a"))
        nav.navigate(VideoPlayer(-1, localPath = "/offline/a/clip.mp4"))
        assertEquals(3, nav.routes.size)
        assertTrue(nav.back())
        assertTrue(nav.back())
        assertFalse(exited)
        assertFalse(nav.back())
        assertTrue(exited)
    }

    @Test fun openNodeUsesTheViewersAndOpensOtherFilesOutsideTheApp() {
        val nav = navigator()
        nav.openNode(node(1, FileType.FOLDER, folder = true), MediaSource.Single)
        nav.openNode(node(2, FileType.IMAGE), MediaSource.Folder(1))
        nav.openNode(node(3, FileType.TEXT), MediaSource.Folder(1))
        nav.openNode(node(4, FileType.TEXT, size = AppNavigator.TEXT_EDITOR_LIMIT + 1), MediaSource.Folder(1))
        nav.openNode(node(5, FileType.ARCHIVE), MediaSource.Folder(1))
        assertEquals(
            listOf(Home, Folder(1), ImageViewer(2, MediaSource.Folder(1)), TextEditor(3)),
            nav.routes,
        )
        assertEquals(listOf(4L, 5L), openedOther)
    }

    /** Made-up node of an own folder. */
    private fun node(handle: Long, type: FileType, folder: Boolean = false, size: Long = 10) = NodeItem(
        handle = handle,
        parentHandle = 1,
        name = "item",
        isFolder = folder,
        type = type,
        size = size,
        modified = 0,
        created = 0,
        hasThumbnail = false,
        hasPreview = false,
        isFavourite = false,
        label = 0,
        isExported = false,
        isOutShare = false,
        isInShare = false,
        isTakenDown = false,
        isSensitive = false,
        duration = -1,
    )
}
