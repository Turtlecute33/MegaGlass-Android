package app.megaglass.android.core.mega

import android.content.Context
import android.os.Build
import app.megaglass.android.BuildConfig
import app.megaglass.android.core.util.invalidationEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failed SDK request. [code] is a MegaError.API_* value. */
class MegaException(val code: Int, message: String, val requestType: Int = -1) : Exception(message)

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

    private val userAgent = "MegaGlass/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
    private val baseDir = File(context.filesDir, "mega").apply { mkdirs() }

    val api: MegaGlassApi = MegaGlassApi(APP_KEY, userAgent, baseDir.absolutePath + File.separator)

    /**
     * A second instance for public folder links, so that browsing one does not touch the account.
     * Its transfers go to the same [transferSink]; TransferManager tells them apart by the api.
     */
    val folderApi: MegaGlassApi by lazy {
        val dir = File(context.cacheDir, "mega-folder").apply { mkdirs() }
        MegaGlassApi(APP_KEY, userAgent, dir.absolutePath + File.separator).also { it.addTransferListener(folderTransfers) }
    }

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
            val expired = e.errorCode == MegaError.API_ESID ||
                (request.type == MegaRequest.TYPE_LOGOUT && request.paramType == MegaError.API_ESID)
            if (expired) _sessionEnded.tryEmit(Unit)
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
            _syncs.tryEmit(SyncChange(sync.backupId, null))
        }
    }

    init {
        MegaApiJava.setLogLevel(MegaApiJava.LOG_LEVEL_ERROR)
        api.addListener(listener)
    }

    companion object {
        /** MEGA app key. Empty works; a key from mega.io/developers identifies the app to MEGA. */
        const val APP_KEY = ""
        val INVALID_HANDLE: Long = MegaApiJava.INVALID_HANDLE
    }
}

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
                cont.resumeWithException(MegaException(e.errorCode, e.errorString ?: "Error ${e.errorCode}", request.type))
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
    cont.invokeOnCancellation { removeRequestListener(listener) }
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
    cont.invokeOnCancellation { removeRequestListener(listener) }
}
