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
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.ui.components.Ic
import kotlinx.coroutines.launch

/** Wrong codes in a row before the app logs out and removes the passcode. */
private const val MAX_ATTEMPTS = 10

/** Wrong codes in a row after which the remaining attempts are shown. */
private const val WARN_AFTER = 5

fun biometricAvailable(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/** Shows the system biometric prompt; calls [onSuccess] on the main thread. */
fun showBiometricPrompt(context: Context, title: String, cancel: String, onSuccess: () -> Unit) {
    val executor = ContextCompat.getMainExecutor(context)
    BiometricPrompt.Builder(context)
        .setTitle(title)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButton(cancel, executor) { _, _ -> }
        .build()
        .authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
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
    var attempts by rememberSaveable { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    val length = settings.passcodeLength
    val biometricReady = remember { biometricAvailable(context) }
    val useBiometric = settings.biometricUnlock && biometricReady
    val promptTitle = stringResource(R.string.lock_biometric_title)
    val promptCancel = stringResource(R.string.lock_use_passcode)
    val biometric = {
        showBiometricPrompt(activity ?: context, promptTitle, promptCancel) { graph.lock.unlock() }
    }

    BackHandler { activity?.moveTaskToBack(true) }
    LaunchedEffect(Unit) { if (useBiometric) biometric() }

    fun check(entered: String) {
        checking = true
        scope.launch {
            val ok = graph.lock.verify(entered)
            checking = false
            if (ok) {
                attempts = 0
                graph.lock.unlock()
                return@launch
            }
            attempts++
            shake++
            code = ""
            if (attempts >= MAX_ATTEMPTS) {
                graph.session.logout()
                graph.lock.setPasscode(null)
            }
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
            val left = MAX_ATTEMPTS - attempts
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
                    if (checking || code.length >= length) return@NumberPad
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
        }
    }
}
