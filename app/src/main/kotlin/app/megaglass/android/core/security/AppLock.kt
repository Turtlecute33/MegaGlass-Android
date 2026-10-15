package app.megaglass.android.core.security

import app.megaglass.android.core.util.catching

import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.megaglass.android.core.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app passcode lock. The passcode is stored only as a salted PBKDF2-SHA256 hash.
 * The app locks when it returns from the background after the configured timeout.
 */
class AppLock(private val settings: Settings) : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(settings.current.passcodeHash != null)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundSince = 0L

    val isEnabled: Boolean get() = settings.current.passcodeHash != null

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val s = settings.current
        if (s.passcodeHash == null || _locked.value || backgroundSince == 0L) return
        if (SystemClock.elapsedRealtime() - backgroundSince >= s.lockTimeout * 1000L) _locked.value = true
    }

    /** Wrong passcodes in a row, also across app restarts. */
    var failures: Int = settings.current.lockFailures
        private set

    /** Checks [passcode] on the lock screen and counts the wrong ones toward the wipe. */
    suspend fun verify(passcode: String): Boolean {
        val ok = matches(passcode)
        failures = settings.update { it.copy(lockFailures = if (ok) 0 else it.lockFailures + 1) }.lockFailures
        return ok
    }

    /**
     * Checks [passcode] without counting: for an unlocked app (Settings), where a wrong entry
     * must not move toward the wipe.
     */
    suspend fun matches(passcode: String): Boolean = withContext(Dispatchers.Default) {
        val stored = settings.current.passcodeHash ?: return@withContext true
        catching {
            val parts = stored.split(":")
            if (parts.size != 2) return@catching false
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val hash = Base64.decode(parts[1], Base64.NO_WRAP)
            salt.size == 16 && hash.size == 32 && MessageDigest.isEqual(hash, derive(passcode, salt))
        }.getOrDefault(false)
    }

    /** After an unlock that did not use the passcode (biometric). */
    suspend fun resetFailures() {
        failures = settings.update { it.copy(lockFailures = 0) }.lockFailures
    }

    fun unlock() {
        _locked.value = false
    }

    /** Sets a new passcode, or turns the lock off with null. */
    suspend fun setPasscode(passcode: String?) {
        val value = passcode?.let {
            withContext(Dispatchers.Default) {
                val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(derive(it, salt), Base64.NO_WRAP)
            }
        }
        failures = 0
        settings.update {
            it.copy(
                passcodeHash = value,
                passcodeLength = passcode?.length ?: it.passcodeLength,
                biometricUnlock = if (value == null) false else it.biometricUnlock,
                lockFailures = 0,
            )
        }
        _locked.value = false
    }

    private fun derive(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
