package app.megaglass.android.core.security

import app.megaglass.android.core.util.catching

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.megaglass.android.core.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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

    private val grace = ReturnGrace(clock = SystemClock::elapsedRealtime)

    val isEnabled: Boolean get() = settings.current.passcodeHash != null

    /**
     * Call just before the app starts a picker, the camera, a share sheet or a settings page: the
     * return from it does not lock (MEGAGLASS-UX-004). A normal exit to Home or Recents still locks.
     */
    fun expectReturn() = grace.expect()

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        // Each return ends the grace, also when the lock is off or already shows.
        if (grace.consume()) return
        val s = settings.current
        if (s.passcodeHash == null || _locked.value || backgroundSince == 0L) return
        if (SystemClock.elapsedRealtime() - backgroundSince >= s.lockTimeout * 1000L) _locked.value = true
    }

    override fun onPause(owner: LifecycleOwner) = grace.leave()

    override fun onResume(owner: LifecycleOwner) = grace.resume()

    /** Wrong passcodes in a row, also across app restarts. */
    var failures: Int = settings.current.lockFailures
        private set

    /** Checks [passcode] on the lock screen and counts the wrong ones toward the wipe. */
    suspend fun verify(passcode: String): Boolean {
        val ok = matches(passcode)
        failures = settings.update { it.copy(lockFailures = if (ok) 0 else it.lockFailures + 1) }.lockFailures
        return ok
    }

    /** Wrong passcodes in Settings since the last unlock, in memory only. */
    private var settingsMisses = 0

    /**
     * Checks [passcode] in an unlocked app (Settings). A wrong entry does not move toward the
     * wipe, but after [SETTINGS_TRIES] wrong entries the app locks, and the lock screen counts.
     */
    suspend fun confirm(passcode: String): Boolean {
        val ok = matches(passcode)
        if (ok) settingsMisses = 0 else if (++settingsMisses >= SETTINGS_TRIES) lockNow()
        return ok
    }

    /** Locks at once, when a passcode is set. */
    fun lockNow() {
        if (isEnabled) _locked.value = true
    }

    private suspend fun matches(passcode: String): Boolean = withContext(Dispatchers.Default) {
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
        settingsMisses = 0
        _locked.value = false
    }

    /**
     * Turns biometric unlock on or off. On creates a Keystore key that only a strong biometric
     * can use; a fingerprint or face added later makes the key invalid.
     */
    suspend fun setBiometric(on: Boolean) {
        withContext(Dispatchers.IO) {
            if (on) createBiometricKey() else catching { keyStore().deleteEntry(BIOMETRIC_KEY) }
        }
        settings.update { it.copy(biometricUnlock = on) }
    }

    /**
     * The cipher for the biometric prompt, or null when the key is missing or invalid (the
     * biometrics of the device changed). Throws when the Keystore is not available.
     */
    fun biometricCipher(): Cipher? = try {
        val key = keyStore().getKey(BIOMETRIC_KEY, null) as? SecretKey
        key?.let { Cipher.getInstance(BIOMETRIC_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, it) } }
    } catch (_: KeyPermanentlyInvalidatedException) {
        null
    } catch (_: UnrecoverableKeyException) {
        null
    }

    /** Unlocks when the biometric prompt authorized [cipher] (from [biometricCipher]). */
    fun unlockWithBiometric(cipher: Cipher): Boolean {
        val ok = catching { cipher.doFinal(ByteArray(16)) }.isSuccess
        if (ok) unlock()
        return ok
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createBiometricKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(BIOMETRIC_KEY, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        generator.generateKey()
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
        if (value == null) withContext(Dispatchers.IO) { catching { keyStore().deleteEntry(BIOMETRIC_KEY) } }
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

    private companion object {
        const val BIOMETRIC_KEY = "megaglass.lock.biometric"
        const val BIOMETRIC_TRANSFORMATION = "AES/GCM/NoPadding"
        const val SETTINGS_TRIES = 3
    }
}

/**
 * One return to the app without the lock, after the app started another activity on purpose
 * (MEGAGLASS-UX-004). It ends at the first return or after [limitMs], whichever comes first.
 * The calls follow the process lifecycle.
 */
internal class ReturnGrace(private val limitMs: Long = 120_000, private val clock: () -> Long) {
    @Volatile private var until = 0L
    @Volatile private var left = false

    fun expect() {
        left = false
        until = clock() + limitMs
    }

    /** ON_PAUSE: the app is not in the foreground. */
    fun leave() {
        left = true
    }

    /** ON_START: true once, for a return inside the limit. */
    fun consume(): Boolean {
        val end = until
        until = 0L
        return end != 0L && clock() < end
    }

    /**
     * ON_RESUME. After a pause without a stop (a picker that does not cover the app), the grace
     * ends. A grace that starts at this resume (a picker after a settings page) stays.
     */
    fun resume() {
        if (left) until = 0L
    }
}
