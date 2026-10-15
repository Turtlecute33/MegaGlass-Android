package app.megaglass.android.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.TwoFactorRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import nz.mega.sdk.MegaError

enum class AuthStep { WELCOME, LOGIN, TWO_FACTOR, SIGNUP, CONFIRM_EMAIL, FORGOT, RESET_SENT }

data class AuthState(
    val step: AuthStep = AuthStep.WELCOME,
    val busy: Boolean = false,
    val error: String? = null,
    val email: String = "",
)

class AuthViewModel : ViewModel() {

    private val session = MegaGlassApp.graph.session

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    // Kept only in memory, only between the password step and the 2FA step.
    private var pendingPassword: String? = null
    private var pendingName: String = ""

    fun go(step: AuthStep) = _state.update { it.copy(step = step, error = null) }

    fun back(): Boolean {
        if (_state.value.busy) return true
        val s = _state.value
        val target = when (s.step) {
            AuthStep.WELCOME -> return false
            AuthStep.TWO_FACTOR -> AuthStep.LOGIN.also { pendingPassword = null }
            AuthStep.FORGOT, AuthStep.RESET_SENT -> AuthStep.LOGIN
            else -> AuthStep.WELCOME
        }
        go(target)
        return true
    }

    fun login(email: String, password: String) = run {
        try {
            session.login(email, password)
        } catch (_: TwoFactorRequired) {
            pendingPassword = password
            _state.update { it.copy(step = AuthStep.TWO_FACTOR, email = email) }
        }
    }

    fun submitCode(code: String) = run {
        val password = pendingPassword ?: return@run go(AuthStep.LOGIN)
        session.login(_state.value.email, password, code)
        pendingPassword = null
    }

    fun signup(firstName: String, lastName: String, email: String, password: String) = run {
        session.createAccount(email, password, firstName, lastName)
        pendingName = "$firstName $lastName".trim()
        _state.update { it.copy(step = AuthStep.CONFIRM_EMAIL, email = email) }
    }

    fun resendConfirmation() = run {
        session.resendSignupLink(_state.value.email, pendingName)
    }

    fun forgot(email: String) = run {
        session.sendPasswordReset(email)
        _state.update { it.copy(step = AuthStep.RESET_SENT, email = email) }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                _state.update { it.copy(error = "Connection timed out. Try again.") }
            } catch (e: MegaException) {
                _state.update { it.copy(error = message(e)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        pendingPassword = null
        pendingName = ""
    }

    private fun message(e: MegaException): String = when (e.code) {
        MegaError.API_ENOENT -> "Wrong e-mail or password."
        MegaError.API_EFAILED, MegaError.API_EEXPIRED -> "Wrong code. Try again."
        MegaError.API_EEXIST -> "An account with this e-mail already exists."
        MegaError.API_ETOOMANY -> "Too many attempts. Wait a few minutes and try again."
        MegaError.API_EINCOMPLETE -> "This account is not confirmed yet. Check your e-mail."
        MegaError.API_EBLOCKED -> "This account is blocked."
        else -> e.message ?: "Error ${e.code}"
    }
}
