package app.megaglass.android

import android.content.Intent
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.megaglass.android.feature.links.findMegaLink
import app.megaglass.android.ui.AppRoot
import app.megaglass.android.ui.IncomingAction
import app.megaglass.android.ui.theme.edgeToEdge
import app.megaglass.android.ui.theme.isDark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val incoming = MutableStateFlow<IncomingAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = MegaGlassApp.graph.settings
        // The bar icons follow the app theme, also after a system dark-mode change (MEGAGLASS-UX-007).
        edgeToEdge { res -> settings.current.themeMode.isDark(res.configuration.isNightModeActive) }
        super.onCreate(savedInstanceState)
        // The lock grace after a picker follows the pause and the resume of this activity (MEGAGLASS-UX-004).
        lifecycle.addObserver(MegaGlassApp.graph.lock.activityObserver)
        if (savedInstanceState == null) handle(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                settings.state.distinctUntilChangedBy { it.secureScreen to (it.passcodeHash != null) }.collect { s ->
                    if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    // With the passcode on, Recents shows no snapshot of the content.
                    setRecentsScreenshotEnabled(s.passcodeHash == null)
                }
            }
        }

        setContent { AppRoot(incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri != null && isMegaLink(uri.toString()) &&
            (listOf("recover", "confirm", "verify").any { uri.fragment.orEmpty().startsWith(it) } ||
                listOf("/recover", "/register", "/confirm").any { uri.path.orEmpty().startsWith(it) })) {
            // These account flows are implemented by MEGA's website. The root-path link filter
            // also matches them, so explicitly exclude this activity to prevent a redirect loop.
            val browser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
            try { startActivity(browser) } catch (_: ActivityNotFoundException) {
                android.widget.Toast.makeText(this, R.string.no_app_to_open, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        // Debug builds only: open one cloud folder by path, for tests that must not show other content.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_OPEN_PATH)?.let {
                incoming.value = IncomingAction.OpenPath(it)
                return
            }
        }
        incoming.value = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(::isMegaLink)?.let { IncomingAction.OpenLink(it) }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { IncomingAction.Upload(listOf(it)) } ?: sharedText(intent)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { IncomingAction.Upload(it) }
            ACTION_TRANSFERS -> IncomingAction.ShowTransfers
            else -> null
        }
    }

    /** A shared text, for example a link from a browser or a chat: opens the first MEGA link in it. */
    private fun sharedText(intent: Intent): IncomingAction? {
        val link = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::findMegaLink)
        if (link == null) android.widget.Toast.makeText(this, R.string.share_nothing, android.widget.Toast.LENGTH_LONG).show()
        return link?.let { IncomingAction.OpenLink(it) }
    }

    private fun isMegaLink(url: String): Boolean {
        val u = Uri.parse(url)
        if (u.scheme?.lowercase() !in setOf("http", "https", "mega")) return false
        if (u.scheme.equals("mega", ignoreCase = true)) return true
        val host = u.host?.lowercase() ?: return false
        return host in setOf("mega.nz", "www.mega.nz", "mega.co.nz", "www.mega.co.nz", "mega.io", "www.mega.io")
    }

    companion object {
        const val ACTION_TRANSFERS = "app.megaglass.android.TRANSFERS"
        private const val EXTRA_DEBUG_OPEN_PATH = "debug_open_path"
    }
}
