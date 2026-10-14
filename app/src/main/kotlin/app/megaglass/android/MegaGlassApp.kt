package app.megaglass.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import app.megaglass.android.core.FileOpener
import app.megaglass.android.core.camera.CameraUploads
import app.megaglass.android.core.Messenger
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.security.AppLock
import app.megaglass.android.core.mega.NodeRepository
import app.megaglass.android.core.mega.Session
import app.megaglass.android.core.media.MegaImages
import app.megaglass.android.core.storage.LocalFiles
import app.megaglass.android.core.storage.OfflineStore
import app.megaglass.android.core.storage.SessionStore
import app.megaglass.android.core.storage.Settings
import app.megaglass.android.core.transfers.TransferJobService
import app.megaglass.android.core.transfers.TransferManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File

/** The app's objects, created once. Features get their dependencies from here. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mega = Mega(context)
    val settings = Settings(context, scope)
    val files = LocalFiles(context)
    val images = MegaImages(context, mega)
    val nodes = NodeRepository(mega)
    val transfers = TransferManager(mega, scope)
    val offline = OfflineStore(context, transfers, scope)
    val session = Session(mega, SessionStore(context), settings, scope, onLoggedOut = ::clearUserData)
    val messenger = Messenger()
    val opener = FileOpener(context, transfers, files, messenger, scope)
    val lock = AppLock(settings)

    init {
        transfers.onActive = { TransferJobService.schedule(context) }
        CameraUploads.install(this)
        scope.launch(Dispatchers.IO) { files.cleanUploadTemp() }
        // Thumbnails of changed files are stale.
        scope.launch {
            nodes.changes.filter { it.imagesChanged.isNotEmpty() }.collect { change -> change.imagesChanged.forEach(images::invalidate) }
        }
    }

    private suspend fun clearUserData() {
        transfers.clearHistory()
        images.clear()
        offline.clear()
        files.openDir.deleteRecursively()
        File(context.cacheDir, "preview").deleteRecursively()
        settings.update { it.copy(cameraUploads = false, cuSince = 0) }
    }
}

class MegaGlassApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // The SDK does local file access through FileWrapper; content URIs need the context.
        mega.privacy.android.data.filewrapper.FileWrapper.init(this)
        graph = AppGraph(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.lock)
        graph.session.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        graph.images.register(ImageLoader.Builder(context))
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            // The SDK image files are the disk cache.
            .diskCache(null)
            .crossfade(150)
            .build()

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
