package app.megaglass.android.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/** Metadata key of the tab that owns an entry. */
const val TAB_METADATA = "tab"

/**
 * One entry on screen; Back (and its predictive preview) goes only to earlier entries of the
 * same tab. At the root of a tab NavDisplay does not take Back, so MainScaffold can switch tabs
 * and the root of Home closes the app with the system animation.
 */
class TabSceneStrategy : SceneStrategy<StackEntry> {
    override fun SceneStrategyScope<StackEntry>.calculateScene(entries: List<NavEntry<StackEntry>>): Scene<StackEntry> {
        val last = entries.last()
        val tab = last.metadata[TAB_METADATA]
        return TabScene(
            key = last.contentKey,
            entry = last,
            previousEntries = entries.dropLast(1).filter { it.metadata[TAB_METADATA] == tab },
        )
    }
}

private class TabScene(
    override val key: Any,
    val entry: NavEntry<StackEntry>,
    override val previousEntries: List<NavEntry<StackEntry>>,
) : Scene<StackEntry> {
    override val entries: List<NavEntry<StackEntry>> = listOf(entry)
    override val content: @Composable () -> Unit = { entry.Content() }

    override fun equals(other: Any?) = other is TabScene && key == other.key && entry == other.entry &&
        previousEntries == other.previousEntries

    override fun hashCode() = (key.hashCode() * 31 + entry.hashCode()) * 31 + previousEntries.hashCode()
}
