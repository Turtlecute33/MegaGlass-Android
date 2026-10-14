package app.megaglass.android.core.security

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

    suspend fun verify(passcode: String): Boolean = withContext(Dispatchers.Default) {
        val stored = settings.current.passcodeHash ?: return@withContext true
        val (salt, hash) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        MessageDigest.isEqual(hash, derive(passcode, salt))
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
        settings.update {
            it.copy(
                passcodeHash = value,
                passcodeLength = passcode?.length ?: it.passcodeLength,
                biometricUnlock = if (value == null) false else it.biometricUnlock,
            )
        }
        _locked.value = false
    }

    private fun derive(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
