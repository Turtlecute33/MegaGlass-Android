package app.megaglass.android.core.mega

import android.content.Context
import android.os.Environment
import app.megaglass.android.BuildConfig
import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.invalidationEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mega.privacy.android.data.filewrapper.FileWrapper
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaContactRequest
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaEvent
import nz.mega.sdk.MegaGlassApi
import nz.mega.sdk.MegaListenerInterface
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaRequest
import nz.mega.sdk.MegaRequestListenerInterface
import nz.mega.sdk.MegaSet
import nz.mega.sdk.MegaSetElement
import nz.mega.sdk.MegaSync
import nz.mega.sdk.MegaSyncStats
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import nz.mega.sdk.MegaUser
import nz.mega.sdk.MegaUserAlert
import java.io.File
import java.lang.ref.Reference
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A failed SDK request. [code] is a MegaError.API_* value. [syncError] is a MegaSync error
 * (0 for none) that tells why a sync request failed. [fromSdk] is true when the message is the
 * SDK error text, which is not for users (see userMessage).
 */
class MegaException(
    val code: Int,
    message: String,
    val requestType: Int = -1,
    val syncError: Int = 0,
    val fromSdk: Boolean = false,
) : Exception(message)

/** Nodes changed. [all] means that the whole tree may have changed (for example after a reload). */
class NodeChange(
    val handles: Set<Long>,
    val parents: Set<Long>,
    val all: Boolean,
    /** Nodes whose thumbnail or preview changed. */
    val imagesChanged: Set<Long> = emptySet(),
) {
    fun touches(handle: Long) = all || handle in handles || handle in parents
}

data class SyncChange(val backupId: Long, val stats: MegaSyncStats?)

/**
 * Owns the MEGA SDK instance and turns its listener callbacks into flows and suspend calls.
 * SDK callbacks arrive on the SDK thread (see MegaGlassApi), so listeners only emit and return.
 */
class Mega(context: Context) {
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Public folder login/logout must be serialized across retained navigation entries. */
    val folderSessionLock = Mutex()
    val folderOwner = MutableStateFlow(0L)
    val publicSetLock = Mutex()
    var publicSetOwner = 0L

    // No device model or OS version: the same for every device.
    private val userAgent = "MegaGlass/${BuildConfig.VERSION_NAME} (Android)"
    private val baseDir = File(context.filesDir, "mega").apply { mkdirs() }
    private val folderDir = File(context.cacheDir, "mega-folder")
    private val packageName = context.packageName

    val api: MegaGlassApi = MegaGlassApi(APP_KEY, userAgent, baseDir.absolutePath + File.separator)

    private val folderApiInstance = lazy {
        folderDir.mkdirs()
        MegaGlassApi(APP_KEY, userAgent, folderDir.absolutePath + File.separator).also { it.addTransferListener(folderTransfers) }
    }

    /**
     * A second instance for public folder links, so that browsing one does not touch the account.
     * Its transfers go to the same [transferSink]; TransferManager tells them apart by the api.
     */
    val folderApi: MegaGlassApi by folderApiInstance

    private val folderTransfers = object : MegaTransferListenerInterface {
        override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {
            transferSink?.onTransferStart(api, transfer)
        }
        override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            transferSink?.onTransferFinish(api, transfer, e)
        }
        override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
            transferSink?.onTransferUpdate(api, transfer)
        }
        override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            transferSink?.onTransferTemporaryError(api, transfer, e)
        }
        override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray) = false
        override fun onFolderTransferUpdate(
            api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
            createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?,
        ) {
            transferSink?.onFolderTransferUpdate(
                api, transfer, stage, folderCount, createdFolderCount, fileCount, currentFolder, currentFileLeafName,
            )
        }
    }

    private val _nodes = MutableSharedFlow<NodeChange>(extraBufferCapacity = 64)
    val nodes: SharedFlow<NodeChange> = _nodes
    private val emitNodes = invalidationEmitter(callbackScope, _nodes) { merged, dropped ->
        NodeChange(emptySet(), emptySet(), all = true, imagesChanged = merged?.imagesChanged.orEmpty() + dropped.imagesChanged)
    }

    private val _account = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val account: SharedFlow<Unit> = _account

    private val _users = MutableSharedFlow<List<Long>>(extraBufferCapacity = 16)
    /** Handles of users whose data changed (contacts or the own user). */
    val users: SharedFlow<List<Long>> = _users
    private val emitUsers = invalidationEmitter(callbackScope, _users) { _, _ -> emptyList() }

    private val _alerts = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val alerts: SharedFlow<Unit> = _alerts

    private val _contactRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val contactRequests: SharedFlow<Unit> = _contactRequests

    private val _sets = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Albums (sets) or their elements changed. */
    val sets: SharedFlow<Unit> = _sets

    private val _syncs = MutableSharedFlow<SyncChange>(extraBufferCapacity = 64)
    val syncs: SharedFlow<SyncChange> = _syncs

    private val _events = MutableSharedFlow<MegaEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<MegaEvent> = _events

    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** The session was closed on the server (killed from another device, password changed, ...). */
    val sessionEnded: SharedFlow<Unit> = _sessionEnded

    private val _sdkLoggedOut = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /**
     * The SDK logged out on this device after an error, for example API_ESSL when the network
     * intercepts the connection. The value is the MegaError code. The session is still valid.
     */
    val sdkLoggedOut: SharedFlow<Int> = _sdkLoggedOut

    /** Set when syncs were paused because all-files access is missing. The app shows a notice and resets it. */
    val syncsPausedForAccess = MutableStateFlow(false)

    /**
     * Receives every transfer callback on the SDK thread. Set by TransferManager.
     * A direct callback (not a flow) so that no start or finish event can be dropped.
     */
    @Volatile
    var transferSink: MegaTransferListenerInterface? = null

    /** One listener for everything: every registration copies each object it receives. */
    private val listener = object : MegaListenerInterface {
        override fun onRequestStart(api: MegaApiJava, request: MegaRequest) {}
        override fun onRequestUpdate(api: MegaApiJava, request: MegaRequest) {}
        override fun onRequestFinish(api: MegaApiJava, request: MegaRequest, e: MegaError) {
            // A logout that the SDK starts by itself has the error that caused it; the app's own has none.
            val cause = if (request.type == MegaRequest.TYPE_LOGOUT) request.paramType else MegaError.API_OK
            when {
                e.errorCode == MegaError.API_ESID || cause == MegaError.API_ESID -> _sessionEnded.tryEmit(Unit)
                // A blocked account shows through EVENT_ACCOUNT_BLOCKED.
                cause != MegaError.API_OK && cause != MegaError.API_EBLOCKED -> _sdkLoggedOut.tryEmit(cause)
            }
        }
        override fun onRequestTemporaryError(api: MegaApiJava, request: MegaRequest, e: MegaError) {}

        override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {
            transferSink?.onTransferStart(api, transfer)
        }
        override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            transferSink?.onTransferFinish(api, transfer, e)
        }
        override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
            transferSink?.onTransferUpdate(api, transfer)
        }
        override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
            transferSink?.onTransferTemporaryError(api, transfer, e)
        }
        override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray) = false
        override fun onFolderTransferUpdate(
            api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
            createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?,
        ) {
            transferSink?.onFolderTransferUpdate(
                api, transfer, stage, folderCount, createdFolderCount, fileCount, currentFolder, currentFileLeafName,
            )
        }

        override fun onUsersUpdate(api: MegaApiJava, users: ArrayList<MegaUser>?) {
            emitUsers(users?.map { it.handle }.orEmpty())
        }

        override fun onUserAlertsUpdate(api: MegaApiJava, userAlerts: ArrayList<MegaUserAlert>?) {
            _alerts.tryEmit(Unit)
        }

        override fun onNodesUpdate(api: MegaApiJava, nodeList: ArrayList<MegaNode>?) {
            if (nodeList == null) {
                emitNodes(NodeChange(emptySet(), emptySet(), all = true))
                return
            }
            val handles = HashSet<Long>(nodeList.size)
            val parents = HashSet<Long>()
            val images = HashSet<Long>()
            for (n in nodeList) {
                handles += n.handle
                parents += n.parentHandle
                if (n.hasChanged(MegaNode.CHANGE_TYPE_FILE_ATTRIBUTES.toLong())) images += n.handle
            }
            emitNodes(NodeChange(handles, parents, all = false, imagesChanged = images))
        }

        override fun onAccountUpdate(api: MegaApiJava) {
            _account.tryEmit(Unit)
        }

        override fun onContactRequestsUpdate(api: MegaApiJava, requests: ArrayList<MegaContactRequest>?) {
            _contactRequests.tryEmit(Unit)
        }

        override fun onEvent(api: MegaApiJava, event: MegaEvent?) {
            if (event != null) _events.tryEmit(event)
        }

        override fun onSetsUpdate(api: MegaApiJava, sets: ArrayList<MegaSet>?) {
            _sets.tryEmit(Unit)
        }

        override fun onSetElementsUpdate(api: MegaApiJava, elements: ArrayList<MegaSetElement>?) {
            _sets.tryEmit(Unit)
        }

        override fun onGlobalSyncStateChanged(api: MegaApiJava) {
            _syncs.tryEmit(SyncChange(-1, null))
        }

        override fun onSyncDeleted(api: MegaApiJava, sync: MegaSync) {
            _syncs.tryEmit(SyncChange(sync.backupId, null))
        }

        override fun onSyncStatsUpdated(api: MegaApiJava, syncStats: MegaSyncStats) {
            _syncs.tryEmit(SyncChange(syncStats.backupId, syncStats))
        }

        override fun onSyncStateChanged(api: MegaApiJava, sync: MegaSync) {
            holdSync(sync)
            _syncs.tryEmit(SyncChange(sync.backupId, null))
        }
    }

    init {
        MegaApiJava.setLogLevel(MegaApiJava.LOG_LEVEL_ERROR)
        api.addListener(listener)
        // Saved syncs: before the SDK resumes them.
        api.setSyncAddedListener(::holdSync)
    }

    /** True when a sync of [localPath] needs all-files access: the folder is in shared storage. */
    fun needsAllFilesAccess(localPath: String?): Boolean = FileWrapper.isSharedStorage(localPath, packageName)

    /**
     * Without all-files access, Android shows the folders of shared storage but hides most of
     * their files. A two-way sync would take these files as deleted and remove them from the
     * cloud, so it stays paused until the user gives access again and resumes it.
     */
    private fun holdSync(sync: MegaSync) {
        val state = sync.runState
        if (state == MegaSync.SyncRunningState.RUNSTATE_SUSPENDED.swigValue() ||
            state == MegaSync.SyncRunningState.RUNSTATE_DISABLED.swigValue()) return
        if (!needsAllFilesAccess(sync.localFolder) || Environment.isExternalStorageManager()) return
        api.setSyncState(sync.backupId, MegaSync.SyncRunningState.RUNSTATE_SUSPENDED)
        syncsPausedForAccess.value = true
    }

    /** Pauses the syncs that need all-files access when it is missing. Call when the app comes to the front. */
    fun holdSyncsWithoutAccess() {
        if (Environment.isExternalStorageManager()) return
        api.syncs.keepAlive { list -> (0 until list.size()).forEach { holdSync(list.get(it)) } }
    }

    /**
     * Gives the SDK a random device ID in place of a hash of ANDROID_ID (MEGAGLASS-PRIV-001). The
     * file is in [baseDir], so [clearCaches] deletes it at logout and the next login gets a new ID.
     * Call before each login or session resume, off the main thread.
     */
    fun useDeviceId() {
        val file = File(baseDir, DEVICE_ID_FILE)
        val id = catching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { id -> catching { file.writeText(id) } }
        api.setStatsId(id)
    }

    /**
     * Removes the SDK caches after a logout. A logout on this device only keeps the node cache,
     * which has the names of all files, and the caches of the public folders that were open.
     */
    suspend fun clearCaches() {
        folderSessionLock.withLock {
            folderOwner.value++
            if (folderApiInstance.isInitialized() && folderApi.isLoggedIn != 0) {
                withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { catching { folderApi.await { localLogout(it) } } }
            }
            clearFolderCache()
        }
        baseDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Deletes the SDK files of public folder links: their node cache has the names of all files
     * in the link (MEGAGLASS-PRIV-002). Call only with [folderSessionLock] held and [folderApi]
     * logged out.
     */
    fun clearFolderCache() {
        folderDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** MEGA app key. Empty works; a key from mega.io/developers identifies the app to MEGA. */
        const val APP_KEY = ""
        val INVALID_HANDLE: Long = MegaApiJava.INVALID_HANDLE
        private const val LOGOUT_TIMEOUT_MS = 15_000L
        private const val DEVICE_ID_FILE = "device-id"
    }
}

/**
 * Removes the listeners of cancelled requests. This waits for the SDK lock, so it must not run
 * on the thread that cancels, which is often the main thread.
 */
private val listenerRemoval = Executors.newSingleThreadExecutor { Thread(it, "mega-listener-removal").apply { isDaemon = true } }

/**
 * Runs one SDK request and suspends until it finishes.
 * Usage: `api.await { listener -> createFolder(name, parent, listener) }`.
 * [onUpdate] receives progress updates (for example during fetchNodes).
 */
suspend fun MegaApiJava.await(
    onUpdate: ((MegaRequest) -> Unit)? = null,
    call: MegaApiJava.(MegaRequestListenerInterface) -> Unit,
): MegaRequest = suspendCancellableCoroutine { cont ->
    val listener = object : MegaRequestListenerInterface {
        override fun onRequestStart(api: MegaApiJava, request: MegaRequest) {}
        override fun onRequestUpdate(api: MegaApiJava, request: MegaRequest) {
            if (cont.isActive) {
                try {
                    onUpdate?.invoke(request)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                    this@await.removeRequestListener(this)
                }
            }
        }
        override fun onRequestTemporaryError(api: MegaApiJava, request: MegaRequest, e: MegaError) {}
        override fun onRequestFinish(api: MegaApiJava, request: MegaRequest, e: MegaError) {
            if (!cont.isActive) return
            if (e.errorCode == MegaError.API_OK) {
                cont.resume(request)
            } else {
                cont.resumeWithException(MegaException(e.errorCode, e.errorString ?: "Error ${e.errorCode}", request.type, e.syncError, fromSdk = true))
            }
        }
    }
    try {
        call(listener)
    } catch (e: Exception) {
        removeRequestListener(listener)
        if (cont.isActive) cont.resumeWithException(e)
    }
    // Registered after call: cancellation before registration must also remove the delegate.
    cont.invokeOnCancellation { listenerRemoval.execute { removeRequestListener(listener) } }
}

/**
 * Runs [block] while this request stays alive. The objects that a request returns (recentActions,
 * megaFolderInfo, megaSet, ...) and their items belong to the request: the SDK frees them when
 * the request object is collected, even while they are still in use.
 */
inline fun <T, R> T.keepAlive(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        Reference.reachabilityFence(this)
    }

/** Same as [await], but returns the SDK error code instead of throwing. */
suspend fun MegaApiJava.awaitResult(
    call: MegaApiJava.(MegaRequestListenerInterface) -> Unit,
): Pair<Int, MegaRequest> = suspendCancellableCoroutine { cont ->
    val listener = object : MegaRequestListenerInterface {
        override fun onRequestStart(api: MegaApiJava, request: MegaRequest) {}
        override fun onRequestUpdate(api: MegaApiJava, request: MegaRequest) {}
        override fun onRequestTemporaryError(api: MegaApiJava, request: MegaRequest, e: MegaError) {}
        override fun onRequestFinish(api: MegaApiJava, request: MegaRequest, e: MegaError) {
            if (cont.isActive) cont.resume(e.errorCode to request)
        }
    }
    try {
        call(listener)
    } catch (e: Exception) {
        removeRequestListener(listener)
        if (cont.isActive) cont.resumeWithException(e)
    }
    cont.invokeOnCancellation { listenerRemoval.execute { removeRequestListener(listener) } }
}
