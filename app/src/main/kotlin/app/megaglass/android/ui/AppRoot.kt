package app.megaglass.android.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.SessionState
import app.megaglass.android.feature.auth.AuthScreen
import app.megaglass.android.feature.drive.UploadDestinationDialog
import app.megaglass.android.feature.lock.LockScreen
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.nav.AppNavigator
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MainScaffold
import app.megaglass.android.ui.nav.Folder
import app.megaglass.android.ui.nav.PublicLink
import app.megaglass.android.ui.nav.SettingsAbout
import app.megaglass.android.ui.nav.Transfers
import app.megaglass.android.ui.theme.MegaGlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Something another app asked for: open a MEGA link, upload shared files, show transfers. */
sealed interface IncomingAction {
    data class OpenLink(val url: String) : IncomingAction
    data class Upload(val uris: List<Uri>) : IncomingAction
    data object ShowTransfers : IncomingAction
    /** Debug builds only: a cloud path such as "/Tests". */
    data class OpenPath(val path: String) : IncomingAction
}

@Composable
fun AppRoot(incoming: MutableStateFlow<IncomingAction?>) {
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val session by graph.session.state.collectAsStateWithLifecycle()
    val locked by graph.lock.locked.collectAsStateWithLifecycle()

    MegaGlassTheme(settings) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = session.screenKey(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "session",
                ) { key ->
                    when (key) {
                        "starting" -> Box(Modifier.fillMaxSize())
                        "out" -> AuthScreen()
                        "loading" -> LoadingSession(session as? SessionState.Loading)
                        "blocked" -> BlockedScreen((session as? SessionState.Blocked)?.message.orEmpty())
                        "failed" -> ResumeFailedScreen((session as? SessionState.Failed)?.message.orEmpty())
                        else -> ReadyContent(incoming)
                    }
                }
                AnimatedVisibility(locked && graph.lock.isEnabled, enter = fadeIn(), exit = fadeOut()) {
                    LockScreen()
                }
            }
        }
    }
}

private fun SessionState.screenKey() = when (this) {
    SessionState.Starting -> "starting"
    SessionState.LoggedOut -> "out"
    is SessionState.Loading -> "loading"
    is SessionState.Blocked -> "blocked"
    is SessionState.Failed -> "failed"
    SessionState.Ready -> "ready"
}

@Composable
private fun ReadyContent(incoming: MutableStateFlow<IncomingAction?>) {
    val graph = MegaGlassApp.graph
    val navigator = remember { AppNavigator(graph) }
    val action by incoming.collectAsStateWithLifecycle()
    var uploadUris by remember { mutableStateOf<List<Uri>?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    LaunchedEffect(action) {
        when (val a = action) {
            is IncomingAction.OpenLink -> navigator.navigate(PublicLink(a.url))
            is IncomingAction.Upload -> uploadUris = a.uris
            IncomingAction.ShowTransfers -> navigator.navigate(Transfers)
            is IncomingAction.OpenPath -> {
                val api = MegaGlassApp.graph.mega.api
                val node = withContext(Dispatchers.IO) { api.getNodeByPath(a.path, api.rootNode) }
                val found = node != null && node.isFolder
                // A neutral screen when the folder is missing, so that no account content shows.
                navigator.navigate(if (found) Folder(node.handle) else SettingsAbout)
                android.util.Log.i("MegaGlassDebug", "open path: found=$found")
            }
            null -> return@LaunchedEffect
        }
        incoming.value = null
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        MainScaffold(navigator)
        uploadUris?.let { uris ->
            UploadDestinationDialog(uris = uris, onDismiss = { uploadUris = null })
        }
    }
}

@Composable
private fun LoadingSession(state: SessionState.Loading?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (state?.stage == SessionState.Stage.FETCH_NODES) R.string.loading_files else R.string.logging_in),
            style = MaterialTheme.typography.titleMedium,
        )
        val progress = state?.progress
        if (progress != null) {
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
        }
    }
}

@Composable
private fun ResumeFailedScreen(message: String) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) { Ic(R.drawable.ic_cloud_off, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.resume_failed), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { MegaGlassApp.graph.session.start() }) { Text(stringResource(R.string.retry)) }
        androidx.compose.material3.TextButton(onClick = { scope.launch { MegaGlassApp.graph.session.logout() } }) {
            Text(stringResource(R.string.logout))
        }
    }
}

@Composable
private fun BlockedScreen(message: String) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) { Ic(R.drawable.ic_block, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onErrorContainer) }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.account_blocked), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { scope.launch { MegaGlassApp.graph.session.logout() } }) { Text(stringResource(R.string.logout)) }
    }
}
