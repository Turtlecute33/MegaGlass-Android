package app.megaglass.android.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/** Metadata key of the tab that owns an entry. */
const val TAB_METADATA = "tab"

/**
 * One entry on screen. Back (and its predictive preview) goes to the entry before it, which is
 * the previous entry of the same tab or, at the root of a tab, the top of the tab used before
 * (see AppNavigator.back). At the root of the [start] tab NavDisplay does not take Back, so the
 * system closes the app with its own animation.
 *
 * NavDisplay calls onBack once for each entry that is not in previousEntries, so
 * previousEntries must hold all the other entries: one Back pops one entry.
 */
class TabSceneStrategy(private val start: Tab) : SceneStrategy<StackEntry> {
    override fun SceneStrategyScope<StackEntry>.calculateScene(entries: List<NavEntry<StackEntry>>): Scene<StackEntry> {
        val last = entries.last()
        val startName = start.name
        val atStartRoot = last.metadata[TAB_METADATA] == startName &&
            entries.getOrNull(entries.lastIndex - 1)?.metadata?.get(TAB_METADATA) != startName
        return TabScene(
            key = last.contentKey,
            entry = last,
            previousEntries = if (atStartRoot) emptyList() else entries.dropLast(1),
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
