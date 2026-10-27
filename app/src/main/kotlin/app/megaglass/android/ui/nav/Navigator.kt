package app.megaglass.android.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import app.megaglass.android.AppGraph
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.util.FileType
import app.megaglass.android.core.util.isEditableText
import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage
import kotlinx.coroutines.launch
import java.io.File

/** One back stack entry. [id] keeps entries unique when the same route is open in two tabs. */
@Immutable
data class StackEntry(val tab: Tab, val route: Route, val id: Long)

/**
 * Back stacks, one per tab. NavDisplay gets all of them flattened, in the order the tabs were
 * last used, with the current tab last: so the entries (and their ViewModels) of other tabs stay
 * alive, and Back from the root of a tab returns to the previous tab. The app opens on
 * [startTab] (a setting); Back at its root closes the app.
 */
@Stable
class AppNavigator(private val graph: AppGraph, val startTab: Tab) {

    private val stacks = LinkedHashMap<Tab, SnapshotStateList<StackEntry>>()
    private var nextId = 0L

    var currentTab by mutableStateOf(startTab)
        private set

    val backStack = mutableStateListOf<StackEntry>()

    val currentRoute: Route get() = backStack.lastOrNull()?.route ?: Home

    init {
        stacks[startTab] = mutableStateListOf(entry(startTab, startTab.root))
        rebuild()
    }

    private fun entry(tab: Tab, route: Route) = StackEntry(tab, route, nextId++)

    private fun rebuild() {
        val flat = stacks.values.flatten()
        backStack.clear()
        backStack.addAll(flat)
    }

    fun navigate(route: Route) {
        stacks.getValue(currentTab).add(entry(currentTab, route))
        rebuild()
    }

    /** Replaces the top entry of the current tab. */
    fun replace(route: Route) {
        val stack = stacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(entry(currentTab, route))
        rebuild()
    }

    /** Pops back to [route] if it is on the current tab's stack, otherwise opens it. */
    fun popToOrNavigate(route: Route) {
        val stack = stacks.getValue(currentTab)
        val index = stack.indexOfLast { it.route == route }
        if (index >= 0) {
            if (index < stack.lastIndex) {
                stack.removeRange(index + 1, stack.size)
                rebuild()
            }
        } else {
            navigate(route)
        }
    }

    /** Returns false when there is nothing to go back to (the app should close). */
    fun back(): Boolean {
        val stack = stacks.getValue(currentTab)
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            rebuild()
            return true
        }
        if (currentTab != startTab) {
            stacks.remove(currentTab)
            if (startTab !in stacks) stacks[startTab] = mutableStateListOf(entry(startTab, startTab.root))
            currentTab = stacks.keys.last()
            rebuild()
            return true
        }
        return false
    }

    /** Back, only while [route] is on top: for a result that arrives after the user may have left the screen. */
    fun backFrom(route: Route) {
        if (currentRoute == route) back()
    }

    /** Selecting the current tab again goes back to its root. */
    fun switchTab(tab: Tab) {
        if (tab == currentTab) {
            val stack = stacks.getValue(tab)
            if (stack.size > 1) {
                stack.removeRange(1, stack.size)
                rebuild()
            }
            return
        }
        val stack = stacks.remove(tab) ?: mutableStateListOf(entry(tab, tab.root))
        stacks[tab] = stack
        currentTab = tab
        rebuild()
    }

    /** Opens a node: folders navigate, media and documents open in a viewer, other files in another app. */
    fun openNode(item: NodeItem, source: MediaSource, folderLink: Boolean = false) {
        when {
            item.isFolder -> navigate(Folder(item.handle))
            item.type == FileType.IMAGE -> navigate(ImageViewer(item.handle, source))
            item.type == FileType.VIDEO -> navigate(VideoPlayer(item.handle, folderLink))
            item.type == FileType.AUDIO -> navigate(AudioPlayer(item.handle, source))
            item.type == FileType.PDF -> navigate(PdfViewer(item.handle, folderLink = folderLink))
            item.type.isEditableText() && item.size <= TEXT_EDITOR_LIMIT -> navigate(TextEditor(item.handle, folderLink = folderLink))
            else -> graph.scope.launch {
                catching { graph.opener.open(item.handle, item.name, item.size, folderLink) }
                    .onFailure { graph.messenger.show(graph.context.resources.userMessage(it)) }
            }
        }
    }

    /** Opens a local file (offline or downloaded). */
    fun openLocal(file: File) {
        val type = FileType.of(file.name)
        when {
            type == FileType.IMAGE -> navigate(ImageViewer(-1, MediaSource.Local(file.absolutePath)))
            type == FileType.VIDEO -> navigate(VideoPlayer(-1, localPath = file.absolutePath))
            type == FileType.AUDIO -> navigate(AudioPlayer(-1, MediaSource.Local(file.absolutePath), localPath = file.absolutePath))
            type == FileType.PDF -> navigate(PdfViewer(-1, localPath = file.absolutePath))
            type.isEditableText() && file.length() <= TEXT_EDITOR_LIMIT -> navigate(TextEditor(-1, localPath = file.absolutePath))
            else -> graph.opener.view(file)
        }
    }

    fun message(text: String) = graph.messenger.show(text)

    companion object {
        const val TEXT_EDITOR_LIMIT = 5L * 1024 * 1024
    }
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("No AppNavigator") }
