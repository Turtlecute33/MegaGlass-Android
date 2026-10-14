package app.megaglass.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.megaglass.android.ui.AppRoot
import app.megaglass.android.ui.IncomingAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val incoming = MutableStateFlow<IncomingAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)

        val settings = MegaGlassApp.graph.settings
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                settings.state.distinctUntilChangedBy { it.secureScreen }.collect { s ->
                    if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                ?.let { IncomingAction.Upload(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { IncomingAction.Upload(it) }
            ACTION_TRANSFERS -> IncomingAction.ShowTransfers
            else -> null
        }
    }

    private fun isMegaLink(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host?.lowercase() ?: return u.scheme == "mega"
        return host in setOf("mega.nz", "www.mega.nz", "mega.co.nz", "www.mega.co.nz", "mega.io", "www.mega.io")
    }

    companion object {
        const val ACTION_TRANSFERS = "app.megaglass.android.TRANSFERS"
        private const val EXTRA_DEBUG_OPEN_PATH = "debug_open_path"
    }
}
