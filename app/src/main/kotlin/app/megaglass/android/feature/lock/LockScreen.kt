package app.megaglass.android.feature.lock

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.megaglass.android.core.util.catching
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.SessionState
import app.megaglass.android.ui.components.Ic
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** Wrong codes in a row before the app logs out and removes the passcode. */
private const val MAX_ATTEMPTS = 10

/** Wrong codes in a row after which the remaining attempts are shown. */
private const val WARN_AFTER = 5

fun biometricAvailable(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/** Shows the system biometric prompt for [cipher]; calls [onSuccess] with it on the main thread. */
fun showBiometricPrompt(context: Context, title: String, cancel: String, cipher: Cipher, onSuccess: (Cipher) -> Unit): CancellationSignal {
    val executor = ContextCompat.getMainExecutor(context)
    val signal = CancellationSignal()
    BiometricPrompt.Builder(context)
        .setTitle(title)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButton(cancel, executor) { _, _ -> }
        .build()
        .authenticate(
            BiometricPrompt.CryptoObject(cipher),
            signal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher ?: return
                    if (!signal.isCanceled) onSuccess(authorized)
                }
            },
        )
    return signal
}

/** Covers the app until the passcode or a strong biometric unlocks it. */
@Composable
fun LockScreen() {
    val graph = MegaGlassApp.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    val attempts = settings.lockFailures
    var checking by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var biometricNotice by remember { mutableStateOf(false) }
    var biometricSignal by remember { mutableStateOf<CancellationSignal?>(null) }
    DisposableEffect(Unit) { onDispose { biometricSignal?.cancel() } }
    val length = settings.passcodeLength
    val biometricReady = remember { biometricAvailable(context) }
    val useBiometric = settings.biometricUnlock && biometricReady
    val promptTitle = stringResource(R.string.lock_biometric_title)
    val promptCancel = stringResource(R.string.lock_use_passcode)
    val biometric: () -> Unit = {
        scope.launch {
            // Keystore not available: the passcode still works.
            val cipher = catching { withContext(Dispatchers.IO) { graph.lock.biometricCipher() } }.getOrElse { return@launch }
            if (cipher == null) {
                // The key is missing (an earlier version) or invalid (the biometrics of the device
                // changed). Only the owner, with the passcode, can turn it on again.
                biometricNotice = true
                graph.scope.launch { graph.lock.setBiometric(false) }
                return@launch
            }
            biometricSignal?.cancel()
            biometricSignal = showBiometricPrompt(activity ?: context, promptTitle, promptCancel, cipher) { authorized ->
                if (graph.lock.unlockWithBiometric(authorized)) graph.scope.launch { graph.lock.resetFailures() }
            }
        }
    }

    // Too many wrong passcodes: log out and delete the local data. In the app scope, so that it
    // completes when this screen closes; the count is stored, so closing the app does not help.
    fun wipe() {
        if (wiping) return
        wiping = true
        biometricSignal?.cancel()
        graph.scope.launch(Dispatchers.Main) {
            try {
                // Photos that did not upload go first: a logout moves them to the gallery.
                val removed = withContext(Dispatchers.IO) { graph.files.capturesDir.deleteRecursively() }
                if (removed) graph.session.logout()
                if (removed && graph.session.state.value == SessionState.LoggedOut) graph.lock.setPasscode(null)
                else checkError = graph.context.getString(R.string.lock_cleanup_failed)
            } finally { wiping = false }
        }
    }

    BackHandler { activity?.moveTaskToBack(true) }
    LaunchedEffect(Unit) {
        if (graph.lock.failures >= MAX_ATTEMPTS) wipe() else if (useBiometric) biometric()
    }

    fun check(entered: String) {
        checking = true
        scope.launch {
            val ok = try {
                catching { graph.lock.verify(entered) }.getOrElse {
                    checkError = graph.context.getString(R.string.lock_check_failed)
                    return@launch
                }
            } finally { checking = false }
            if (ok) {
                graph.lock.unlock()
                return@launch
            }
            shake++
            code = ""
            if (graph.lock.failures >= MAX_ATTEMPTS) wipe()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) { Ic(R.drawable.ic_lock_fill, null, Modifier.size(44.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            val left = (MAX_ATTEMPTS - attempts).coerceAtLeast(0)
            Text(
                if (attempts >= WARN_AFTER) pluralStringResource(R.plurals.lock_attempts_left, left, left)
                else stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (attempts >= WARN_AFTER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            PinDots(length = length, filled = code.length, shakeKey = shake)
            Spacer(Modifier.height(40.dp))
            NumberPad(
                onDigit = { d ->
                    if (checking || wiping || code.length >= length) return@NumberPad
                    code += d
                    if (code.length == length) check(code)
                },
                onDelete = { code = code.dropLast(1) },
                extra = if (useBiometric) {
                    {
                        IconButton(onClick = biometric, modifier = Modifier.size(64.dp)) {
                            Ic(R.drawable.ic_fingerprint, stringResource(R.string.lock_use_biometric), Modifier.size(32.dp))
                        }
                    }
                } else null,
            )
            if (biometricNotice) {
                Text(
                    stringResource(R.string.lock_biometric_reset),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            checkError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                if (attempts >= MAX_ATTEMPTS) TextButton(onClick = { wipe() }, enabled = !wiping) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
