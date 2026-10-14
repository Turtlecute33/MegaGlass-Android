package app.megaglass.android.core.mega

import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.storage.SessionStore
import app.megaglass.android.core.storage.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaEvent
import nz.mega.sdk.MegaTransfer

sealed interface SessionState {
    /** Reading the saved session. */
    data object Starting : SessionState
    data object LoggedOut : SessionState
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
) {
    private val api get() = mega.api
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isReady get() = _state.value == SessionState.Ready

    init {
        scope.launch {
            mega.sessionEnded.collect {
                if (_state.value == SessionState.Ready) clearLocal()
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
            settings.state.distinctUntilChangedBy { it.fastTransfers to it.httpsTransfers }
                .collect { applyTransferSettings(it) }
        }
    }

    /** Resumes the saved session, if there is one. Safe to call more than once. */
    fun start() {
        if (_state.value is SessionState.Failed) _state.value = SessionState.Starting
        if (_state.value != SessionState.Starting) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.load() }
            if (saved == null) {
                _state.value = SessionState.LoggedOut
                return@launch
            }
            runCatching {
                _state.value = SessionState.Loading(SessionState.Stage.LOGIN, null)
                api.await { fastLogin(saved, it) }
                fetchNodes()
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

    /** Throws [TwoFactorRequired] when a code is needed; call again with [pin]. */
    suspend fun login(email: String, password: String, pin: String? = null) {
        _state.value = SessionState.Loading(SessionState.Stage.LOGIN, null)
        try {
            if (pin == null) {
                api.await { login(email.trim(), password, it) }
            } else {
                api.await { multiFactorAuthLogin(email.trim(), password, pin, it) }
            }
        } catch (e: MegaException) {
            _state.value = SessionState.LoggedOut
            if (e.code == MegaError.API_EMFAREQUIRED) throw TwoFactorRequired()
            throw e
        }
        fetchNodes()
    }

    private suspend fun fetchNodes() {
        _state.value = SessionState.Loading(SessionState.Stage.FETCH_NODES, null)
        api.await(onUpdate = { r ->
            val total = r.totalBytes
            if (total > 0) {
                _state.value = SessionState.Loading(SessionState.Stage.FETCH_NODES, r.transferredBytes.toFloat() / total)
            }
        }) { fetchNodes(it) }
        api.dumpSession()?.let { withContext(Dispatchers.IO) { store.save(it) } }
        applyTransferSettings(settings.current)
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

    suspend fun logout() {
        runCatching { api.await { logout(it) } }
        clearLocal()
    }

    private suspend fun clearLocal() {
        withContext(Dispatchers.IO) { store.clear() }
        runCatching { onLoggedOut() }
        _state.value = SessionState.LoggedOut
    }

    private fun applyTransferSettings(s: AppSettings) {
        if (api.isLoggedIn == 0) return
        val down = if (s.fastTransfers) 8 else 4
        val up = if (s.fastTransfers) 8 else 3
        api.setMaxConnections(MegaTransfer.TYPE_DOWNLOAD, down)
        api.setMaxConnections(MegaTransfer.TYPE_UPLOAD, up)
        api.useHttpsOnly(s.httpsTransfers)
    }
}
