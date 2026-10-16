package app.megaglass.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.megaglass.android.core.FileOpener
import app.megaglass.android.core.camera.CameraUploads
import app.megaglass.android.feature.contacts.ContactDirectory
import app.megaglass.android.feature.viewer.PreviewCache
import app.megaglass.android.feature.viewer.StreamServer
import app.megaglass.android.feature.viewer.PlaybackQueue
import app.megaglass.android.feature.viewer.PlaybackService
import android.content.Intent
import app.megaglass.android.core.Messenger
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.security.AppLock
import app.megaglass.android.core.mega.NodeRepository
import app.megaglass.android.core.mega.Session
import app.megaglass.android.core.media.MegaImages
import app.megaglass.android.core.storage.FolderSorts
import app.megaglass.android.core.storage.LocalFiles
import app.megaglass.android.core.storage.OfflineStore
import app.megaglass.android.core.storage.SessionStore
import app.megaglass.android.core.storage.Settings
import app.megaglass.android.core.storage.trimImageCache
import app.megaglass.android.core.transfers.TransferJobService
import app.megaglass.android.core.transfers.TransferManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaEvent
import java.io.File

/** The app's objects, created once. Features get their dependencies from here. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mega = Mega(context)
    val settings = Settings(context, scope)
    val folderSorts = FolderSorts(context)
    val files = LocalFiles(context)
    val images = MegaImages(context, mega)
    val nodes = NodeRepository(mega)
    val transfers = TransferManager(
        mega, scope,
        listOf(File(context.cacheDir, "uploads"), File(context.cacheDir, "camera"), File(context.cacheDir, "edit"), File(context.filesDir, "pending-uploads")),
    )
    val offline = OfflineStore(context, transfers, scope, mega.events.filter { it.type == MegaEvent.EVENT_NODES_CURRENT })
    val session = Session(
        mega, SessionStore(context), settings, scope, onLoggedOut = ::clearUserData,
        onReady = { transfers.beginSession(); mega.holdSyncsWithoutAccess() },
    )
    val messenger = Messenger()
    val opener = FileOpener(context, transfers, files, messenger, scope)
    val lock = AppLock(settings)

    init {
        transfers.onActive = { TransferJobService.schedule(context) }
        transfers.onError = { messenger.show(it) }
        transfers.retryOffline = { offline.makeAvailable(listOf(it)) }
        CameraUploads.install(this)
        // The SDK resumes the uploads of an earlier run when the nodes are current; the copies
        // that no upload reads are left over from failed or cancelled uploads.
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            mega.events.first { it.type == MegaEvent.EVENT_NODES_CURRENT }
            files.cleanUploadTemp(transfers.uploadingPaths())
        }
        // Decrypted copies of cloud files stay only up to a size limit.
        scope.launch(Dispatchers.IO) {
            listOf("preview", "open", "links").forEach { trimImageCache(File(context.cacheDir, it), PreviewCache.BUDGET) }
        }
        // Thumbnails of changed files are stale.
        scope.launch {
            nodes.changes.filter { it.imagesChanged.isNotEmpty() }.collect { change -> change.imagesChanged.forEach(images::invalidate) }
        }
    }

    private suspend fun clearUserData() {
        settings.update { it.copy(cameraUploads = false, cuSince = 0) }
        CameraUploads.cancel(context)
        transfers.endSession()
        opener.clear()
        context.stopService(Intent(context, PlaybackService::class.java))
        StreamServer.releasePlayback()
        PlaybackQueue.clear()
        ContactDirectory.clearIfCreated()
        folderSorts.clear()
        images.clear()
        offline.clear()
        files.openDir.deleteRecursively()
        File(context.cacheDir, "preview").deleteRecursively()
        File(context.cacheDir, "links").deleteRecursively()
        File(context.cacheDir, "edit").deleteRecursively()
        File(context.cacheDir, "uploads").deleteRecursively()
        File(context.cacheDir, "avatar").deleteRecursively()
        coil3.SingletonImageLoader.get(context).memoryCache?.clear()
        mega.clearCaches()
    }
}

class MegaGlassApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // The SDK does local file access through FileWrapper; content URIs need the context.
        mega.privacy.android.data.filewrapper.FileWrapper.init(this)
        graph = AppGraph(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(graph.lock)
        // All-files access can change while the app is in the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                graph.scope.launch(Dispatchers.IO) { graph.mega.holdSyncsWithoutAccess() }
            }
        })
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
