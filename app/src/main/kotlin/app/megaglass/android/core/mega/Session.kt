package app.megaglass.android.core.mega

import app.megaglass.android.core.util.catching

import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.storage.SessionStore
import app.megaglass.android.core.storage.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaEvent
import nz.mega.sdk.MegaTransfer
import java.util.concurrent.atomic.AtomicLong

sealed interface SessionState {
    /** Reading the saved session. */
    data object Starting : SessionState
    data object LoggedOut : SessionState
    /** Local data is being removed; account views and login must stay hidden. */
    data object Closing : SessionState
    /** Logging in or loading the file tree; [progress] is 0..1 or null when unknown. */
    data class Loading(val stage: Stage, val progress: Float?) : SessionState
    data object Ready : SessionState
    data class Blocked(val message: String) : SessionState
    /** The saved session could not be resumed (for example no network); it is kept for a retry. */
    data class Failed(val message: String) : SessionState

    enum class Stage { LOGIN, FETCH_NODES }
}

/** Thrown by [Session.login] when the account has two-factor authentication. */
class TwoFactorRequired : Exception("Two-factor authentication code required")

/** Login, logout, and the saved session. */
class Session(
    private val mega: Mega,
    private val store: SessionStore,
    private val settings: Settings,
    private val scope: CoroutineScope,
    /** Deletes local data (caches, offline files) after a logout. */
    private val onLoggedOut: suspend () -> Unit,
    private val onReady: () -> Unit = {},
) {
    private val api get() = mega.api
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isReady get() = _state.value == SessionState.Ready

    private val _notice = MutableStateFlow<String?>(null)
    /** Shown on the login screen: the last logout could not end the session on the server. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    @Volatile private var startJob: Job? = null
    private val lifecycle = Mutex()
    @Volatile private var authJob: Job? = null
    @Volatile private var cleanupRequired = false

    init {
        scope.launch {
            mega.sessionEnded.collect {
                if (_state.value == SessionState.Ready || _state.value is SessionState.Blocked) logout(localOnly = true)
            }
        }
        scope.launch {
            // The saved session stays valid, so Retry logs in again.
            mega.sdkLoggedOut.collect { code ->
                _state.compareAndSet(SessionState.Ready, SessionState.Failed(if (code == MegaError.API_ESSL) INTERCEPTED else SDK_LOGGED_OUT))
            }
        }
        scope.launch {
            mega.events.collect { event ->
                if (event.type == MegaEvent.EVENT_ACCOUNT_BLOCKED) {
                    _state.value = SessionState.Blocked(event.text ?: "This account is blocked.")
                }
            }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.fastTransfers }
                .collect { applyTransferSettings(it) }
        }
    }

    /** Resumes the saved session, if there is one. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (startJob?.isActive == true) return
        if (_state.value is SessionState.Failed) _state.value = SessionState.Starting
        // A second fastLogin (for example from the camera upload worker) would abort the first one.
        if (_state.value != SessionState.Starting) return
        startJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            lifecycle.withLock {
                if (cleanupRequired) { clearLocal(); return@launch }
                val saved = catching { withContext(Dispatchers.IO) { store.load() } }.getOrElse {
                    _state.value = SessionState.Failed("Cannot read the saved session. Try again.")
                    return@launch
                }
                if (saved == null) {
                    clearLocal()
                    return@launch
                }
                catching {
                    _state.value = SessionState.Loading(SessionState.Stage.LOGIN, null)
                    try {
                        val sent = withTimeoutOrNull(LOGIN_TIMEOUT_MS) { api.await { fastLogin(saved, it) } }
                        if (sent == null) throw MegaException(MegaError.API_EAGAIN, TIMED_OUT)
                        fetchNodes()
                    } catch (e: Exception) {
                        // A timed-out request continues in the SDK, and Retry would send a second
                        // login over it. A local logout stops it and keeps the saved session.
                        if (e !is CancellationException) abortLogin()
                        throw e
                    }
                }.onFailure { e ->
                    // Without a network the saved session stays: the SDK can still open the local cache.
                    if (e is MegaException && (e.code == MegaError.API_ESID || e.code == MegaError.API_ENOENT)) {
                        clearLocal()
                    } else {
                        _state.value = SessionState.Failed(e.message ?: "")
                    }
                }
            }
        }
        startJob!!.start()
    }

    /** Throws [TwoFactorRequired] when a code is needed; call again with [pin]. */
    suspend fun login(email: String, password: String, pin: String? = null) = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        lifecycle.withLock {
            check(_state.value == SessionState.LoggedOut) { "Another session operation is in progress" }
            _notice.value = null
            _state.value = SessionState.Loading(SessionState.Stage.LOGIN, null)
            try {
                val sent = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
                    if (pin == null) {
                        api.await { login(email.trim(), password, it) }
                    } else {
                        api.await { multiFactorAuthLogin(email.trim(), password, pin, it) }
                    }
                }
                if (sent == null) {
                    // The SDK request continues; without this a late success logs in behind the login screen.
                    abortLogin()
                    throw MegaException(MegaError.API_EAGAIN, TIMED_OUT)
                }
            } catch (e: Exception) {
                if (_state.value != SessionState.Closing) _state.value = SessionState.LoggedOut
                if (e is MegaException && e.code == MegaError.API_EMFAREQUIRED) throw TwoFactorRequired()
                throw e
            }
            try {
                fetchNodes()
            } catch (e: Throwable) {
                // Back to the login screen with the error, not a spinner without a way out.
                abortLogin()
                if (_state.value != SessionState.Closing) _state.value = SessionState.LoggedOut
                throw e
            }
        }
    }.also { authJob = it; it.start() }.await()

    private suspend fun abortLogin() {
        withContext(NonCancellable) { withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { catching { api.await { localLogout(it) } } } }
    }

    /**
     * Large accounts can load for minutes, so only a load without progress for [FETCH_STALL_MS]
     * times out (for example no network and no local cache).
     */
    private suspend fun fetchNodes() {
        _state.value = SessionState.Loading(SessionState.Stage.FETCH_NODES, null)
        val progressAt = AtomicLong(System.nanoTime())
        coroutineScope {
            val watchdog = launch {
                while (System.nanoTime() - progressAt.get() < FETCH_STALL_MS * 1_000_000) delay(1_000)
                throw MegaException(MegaError.API_EAGAIN, TIMED_OUT)
            }
            try {
                api.await(onUpdate = { r ->
                    progressAt.set(System.nanoTime())
                    val total = r.totalBytes
                    if (total > 0) {
                        _state.value = SessionState.Loading(SessionState.Stage.FETCH_NODES, r.transferredBytes.toFloat() / total)
                    }
                }) { fetchNodes(it) }
            } finally {
                watchdog.cancel()
            }
        }
        api.dumpSession()?.let { withContext(Dispatchers.IO) { store.save(it) } }
        applyTransferSettings(settings.current)
        onReady()
        _state.value = SessionState.Ready
    }

    suspend fun createAccount(email: String, password: String, firstName: String, lastName: String) {
        api.await { createAccount(email.trim(), password, firstName.trim(), lastName.trim(), it) }
    }

    suspend fun resendSignupLink(email: String, name: String) {
        api.await { resendSignupLink(email.trim(), name, it) }
    }

    /** Sends the password reset e-mail. The reset itself happens on mega.nz with the recovery key. */
    suspend fun sendPasswordReset(email: String) {
        api.await { resetPassword(email.trim(), true, it) }
    }

    /**
     * Logs out on the server, or only on this device when the server does not answer in time
     * (no network). Runs in the app scope, so that leaving the screen does not stop it halfway.
     */
    suspend fun logout(localOnly: Boolean = false) = scope.async(Dispatchers.IO) {
        cleanupRequired = true
        _state.value = SessionState.Closing
        startJob?.cancelAndJoin()
        authJob?.cancelAndJoin()
        lifecycle.withLock {
            // Without a login in the SDK (for example after a failed resume) a logout cannot reach the server.
            val remote = if (localOnly || api.isLoggedIn == 0) null
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { catching { api.await { logout(it) } } }
            if (remote?.isSuccess != true) {
                withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { catching { api.await { localLogout(it) } } }
                if (!localOnly) _notice.value = SERVER_SESSION_ACTIVE
            }
            clearLocal()
        }
    }.await()

    private suspend fun clearLocal() {
        cleanupRequired = true
        try {
            withContext(Dispatchers.IO) { store.clear() }
            onLoggedOut()
            cleanupRequired = false
            _state.value = SessionState.LoggedOut
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = SessionState.Failed("Could not remove local account data. Try logging out again.")
        }
    }

    private fun applyTransferSettings(s: AppSettings) {
        if (api.isLoggedIn == 0) return
        val down = if (s.fastTransfers) 8 else 4
        val up = if (s.fastTransfers) 8 else 3
        api.setMaxConnections(MegaTransfer.TYPE_DOWNLOAD, down)
        api.setMaxConnections(MegaTransfer.TYPE_UPLOAD, up)
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 15_000L
        const val LOGIN_TIMEOUT_MS = 90_000L
        const val FETCH_STALL_MS = 180_000L
        const val TIMED_OUT = "Connection timed out. Try again."
        const val INTERCEPTED = "The secure connection to MEGA failed. The network can block or change the " +
            "connection, for example a Wi-Fi login page. Try again, or use a different network."
        const val SDK_LOGGED_OUT = "The connection to MEGA stopped because of an error. Try again."
        const val SERVER_SESSION_ACTIVE = "The session could not be ended on the MEGA server. You are logged out on " +
            "this device, but the session stays active on the server until it expires. To end it now, close it in " +
            "MEGA on the web (Settings > Security > Session history)."
    }
}
