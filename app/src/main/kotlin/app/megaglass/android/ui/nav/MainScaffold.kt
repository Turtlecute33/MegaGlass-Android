package app.megaglass.android.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.Ic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

private data class TabInfo(val tab: Tab, val label: Int, val icon: Int, val selectedIcon: Int)

private val tabs = listOf(
    TabInfo(Tab.HOME, R.string.tab_home, R.drawable.ic_home, R.drawable.ic_home_fill),
    TabInfo(Tab.DRIVE, R.string.tab_drive, R.drawable.ic_cloud, R.drawable.ic_cloud_fill),
    TabInfo(Tab.PHOTOS, R.string.tab_photos, R.drawable.ic_photo_library, R.drawable.ic_photo_library_fill),
    TabInfo(Tab.SHARED, R.string.tab_shared, R.drawable.ic_folder_shared, R.drawable.ic_folder_shared_fill),
    TabInfo(Tab.MENU, R.string.tab_menu, R.drawable.ic_account_circle, R.drawable.ic_account_circle_fill),
)

@Composable
fun MainScaffold(nav: AppNavigator) {
    val graph = MegaGlassApp.graph
    val route = nav.currentRoute
    val fullScreen = route is FullScreen
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        graph.messenger.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, m.actionLabel, withDismissAction = m.actionLabel == null)
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    // Badge on the account tab: unread notifications and incoming contact requests.
    var badge by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        suspend fun count() = withContext(Dispatchers.IO) {
            graph.mega.api.numUnreadUserAlerts + graph.mega.api.incomingContactRequests.size
        }
        badge = count()
        merge(graph.mega.alerts, graph.mega.contactRequests).collect { badge = count() }
    }

    val adaptive = currentWindowAdaptiveInfo()
    val suiteType = if (fullScreen) NavigationSuiteType.None
    else NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptive)

    // At the root of a tab other than Home, Back returns to the previous tab.
    BackHandler(enabled = nav.currentTab != Tab.HOME && nav.isAtTabRoot) { nav.back() }

    NavigationSuiteScaffold(
        navigationSuiteType = suiteType,
        navigationItems = {
            tabs.forEach { t ->
                val selected = nav.currentTab == t.tab
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { nav.switchTab(t.tab) },
                    icon = { Ic(if (selected) t.selectedIcon else t.icon) },
                    label = { Text(stringResource(t.label)) },
                    navigationSuiteType = suiteType,
                    badge = if (t.tab == Tab.MENU && badge > 0) { { Badge { Text(badge.toString()) } } } else null,
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = nav.backStack,
                onBack = { nav.back() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                sceneStrategies = remember { listOf(TabSceneStrategy()) },
                transitionSpec = { forward() },
                popTransitionSpec = { backward() },
                predictivePopTransitionSpec = { backward() },
                entryProvider = { e ->
                    NavEntry(e, contentKey = e.id, metadata = mapOf(TAB_METADATA to e.tab.name)) { RouteContent(it.route) }
                },
            )
            TransferPill(
                visible = !fullScreen && route != Transfers,
                onClick = { nav.navigate(Transfers) },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        }
    }
}

private fun forward(): ContentTransform =
    (slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(220, delayMillis = 60))) togetherWith
        (slideOutHorizontally(tween(320)) { -it / 12 } + fadeOut(tween(160)))

private fun backward(): ContentTransform =
    (slideInHorizontally(tween(320)) { -it / 12 } + fadeIn(tween(220))) togetherWith
        (slideOutHorizontally(tween(320)) { it / 6 } + fadeOut(tween(160)) + scaleOut(tween(320), targetScale = 0.96f))

/** Small floating progress for running transfers; opens the Transfers screen. */
@Composable
private fun TransferPill(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state by MegaGlassApp.graph.transfers.state.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = visible && !state.isIdle,
        modifier = modifier,
        enter = scaleIn() + fadeIn() + slideInVertically { it },
        exit = scaleOut() + fadeOut() + slideOutVertically { it },
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.size(36.dp))
                    val up = state.uploads > state.downloads
                    Ic(if (up) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                val speed = state.downloadSpeed + state.uploadSpeed
                Text(
                    "${(state.progress * 100).toInt()}%" + if (speed > 0) " · ${Format.speed(speed)}" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
