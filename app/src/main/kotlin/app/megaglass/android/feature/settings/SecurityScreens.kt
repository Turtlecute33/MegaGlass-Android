package app.megaglass.android.feature.settings

import app.megaglass.android.ui.components.rememberPasswordStrength

import app.megaglass.android.core.util.catching

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.auth.OtpField
import app.megaglass.android.feature.lock.NumberPad
import app.megaglass.android.feature.lock.PinDots
import app.megaglass.android.feature.lock.biometricAvailable
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.copySensitive
import app.megaglass.android.ui.components.rememberDialogAllowed
import app.megaglass.android.ui.nav.ChangeEmail
import app.megaglass.android.ui.nav.ChangePassword
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.PasscodeSetup
import app.megaglass.android.ui.nav.RecoveryKey
import app.megaglass.android.ui.nav.Sessions
import app.megaglass.android.ui.nav.TwoFactorSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaAccountSession
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError

private val lockTimeouts = listOf(0, 30, 60, 300, 1800)

/** A change to the app lock that needs the current passcode first. */
private sealed interface LockChange {
    data object TurnOff : LockChange
    data object NewPasscode : LockChange
    data object Biometric : LockChange
    data class Timeout(val seconds: Int) : LockChange
}

@Composable
private fun timeoutLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.security_timeout_now)
    seconds < 60 -> pluralStringResource(R.plurals.security_seconds, seconds, seconds)
    else -> pluralStringResource(R.plurals.security_minutes, seconds / 60, seconds / 60)
}

/** User text for SDK errors that these screens can show. */
private fun Context.errorText(e: Throwable): String = when ((e as? MegaException)?.code) {
    MegaError.API_ENOENT -> getString(R.string.security_wrong_password)
    MegaError.API_EFAILED, MegaError.API_EEXPIRED -> getString(R.string.security_wrong_code)
    MegaError.API_EEXIST -> getString(R.string.security_email_used)
    MegaError.API_EMASTERONLY -> getString(R.string.security_master_only)
    MegaError.API_ETOOMANY -> getString(R.string.security_too_many)
    else -> e.message ?: getString(R.string.error_title)
}

@Composable
fun SecuritySettingsScreen() {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var twoFactor by remember { mutableStateOf<Boolean?>(null) }
    var twoFactorFailed by remember { mutableStateOf(false) }
    var twoFactorCheck by remember { mutableIntStateOf(0) }
    var askTimeout by remember { mutableStateOf(false) }
    var lockChange by remember { mutableStateOf<LockChange?>(null) }
    var disable2fa by remember { mutableStateOf(false) }
    val canBiometric = remember { biometricAvailable(context) }
    val passcodeOn = settings.passcodeHash != null

    LaunchedEffect(twoFactorCheck) {
        twoFactorFailed = false
        twoFactor = twoFactorEnabled()
        twoFactorFailed = twoFactor == null
    }

    val lockRows = buildList {
        add(SettingRow.Toggle(R.drawable.ic_lock, stringResource(R.string.security_passcode), stringResource(R.string.security_passcode_summary), passcodeOn) { on ->
            if (on) nav.navigate(PasscodeSetup) else lockChange = LockChange.TurnOff
        })
        if (passcodeOn) {
            add(SettingRow.Link(R.drawable.ic_password, stringResource(R.string.security_change_passcode)) { lockChange = LockChange.NewPasscode })
            add(
                SettingRow.Toggle(
                    R.drawable.ic_fingerprint,
                    stringResource(R.string.security_biometric),
                    stringResource(if (canBiometric) R.string.security_biometric_summary else R.string.security_biometric_unavailable),
                    settings.biometricUnlock && canBiometric,
                    enabled = canBiometric,
                ) { on ->
                    if (on) lockChange = LockChange.Biometric
                    else graph.scope.launch { graph.lock.setBiometric(false) }
                },
            )
            add(SettingRow.Link(R.drawable.ic_lock_clock, stringResource(R.string.security_timeout), value = timeoutLabel(settings.lockTimeout)) {
                askTimeout = true
            })
        }
        add(
            SettingRow.Toggle(R.drawable.ic_no_photography, stringResource(R.string.security_block_screenshots), stringResource(R.string.security_block_screenshots_summary), settings.secureScreen) { on ->
                graph.scope.launch { graph.settings.update { it.copy(secureScreen = on) } }
            },
        )
    }
    val accountRows = listOf(
        if (twoFactorFailed) {
            SettingRow.Link(R.drawable.ic_verified_user, stringResource(R.string.security_2fa), summary = stringResource(R.string.security_2fa_check_failed)) {
                twoFactorCheck++
            }
        } else SettingRow.Toggle(
            R.drawable.ic_verified_user,
            stringResource(R.string.security_2fa),
            when (twoFactor) {
                true -> stringResource(R.string.security_2fa_on)
                false -> stringResource(R.string.security_2fa_off)
                null -> stringResource(R.string.security_checking)
            },
            twoFactor == true,
            enabled = twoFactor != null,
        ) { on -> if (on) nav.navigate(TwoFactorSetup) else disable2fa = true },
        SettingRow.Link(R.drawable.ic_password, stringResource(R.string.security_change_password)) { nav.navigate(ChangePassword) },
        SettingRow.Link(R.drawable.ic_mail, stringResource(R.string.security_change_email)) { nav.navigate(ChangeEmail) },
        SettingRow.Link(R.drawable.ic_vpn_key, stringResource(R.string.security_recovery_key), summary = stringResource(R.string.security_recovery_key_summary)) {
            nav.navigate(RecoveryKey)
        },
        SettingRow.Link(R.drawable.ic_devices, stringResource(R.string.security_sessions), summary = stringResource(R.string.security_sessions_summary)) {
            nav.navigate(Sessions)
        },
    )
    val lockTitle = stringResource(R.string.security_app_lock)
    val accountTitle = stringResource(R.string.security_account)
    SettingsScaffold(title = stringResource(R.string.settings_security)) {
        settingsGroup(lockTitle, lockRows)
        settingsGroup(accountTitle, accountRows)
    }

    if (askTimeout) {
        ChoiceDialog(
            title = stringResource(R.string.security_timeout),
            options = lockTimeouts.map { timeoutLabel(it) },
            selected = lockTimeouts.indexOf(settings.lockTimeout).coerceAtLeast(0),
            onSelect = { i ->
                val seconds = lockTimeouts[i]
                // A longer time keeps the app open longer: as weak as turning the lock off.
                if (seconds > settings.lockTimeout) lockChange = LockChange.Timeout(seconds)
                else graph.scope.launch { graph.settings.update { it.copy(lockTimeout = seconds) } }
            },
            onDismiss = { askTimeout = false },
        )
    }
    lockChange?.let { change ->
        val turnOff = change == LockChange.TurnOff
        TextInputDialog(
            title = stringResource(if (turnOff) R.string.security_passcode_off_title else R.string.lock_subtitle),
            label = stringResource(R.string.security_current_passcode),
            confirmLabel = stringResource(if (turnOff) R.string.security_turn_off else R.string.ok),
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            onConfirm = { pin ->
                scope.launch {
                    catching {
                        if (!graph.lock.confirm(pin)) {
                            graph.messenger.show(resources.getString(R.string.security_wrong_passcode))
                            return@catching
                        }
                        when (change) {
                            LockChange.TurnOff -> graph.lock.setPasscode(null)
                            LockChange.NewPasscode -> nav.navigate(PasscodeSetup)
                            LockChange.Biometric -> graph.lock.setBiometric(true)
                            is LockChange.Timeout -> graph.settings.update { it.copy(lockTimeout = change.seconds) }
                        }
                    }.onFailure { graph.messenger.show(context.errorText(it)) }
                }
            },
            onDismiss = { lockChange = null },
            validate = { if (it.length == settings.passcodeLength) null else "" },
        )
    }
    if (disable2fa) {
        CodeDialog(
            title = stringResource(R.string.security_2fa_disable_title),
            text = stringResource(R.string.security_2fa_disable_text),
            confirm = stringResource(R.string.security_turn_off),
            onCode = { pin ->
                scope.launch {
                    catching { withContext(Dispatchers.IO) { graph.mega.api.await { multiFactorAuthDisable(pin, it) } } }
                        .onSuccess { twoFactor = false }
                        .onFailure { graph.messenger.show(context.errorText(it)) }
                }
            },
            onDismiss = { disable2fa = false },
        )
    }
}

/** Asks for the 6-digit authenticator code. */
@Composable
fun CodeDialog(title: String, text: String?, confirm: String, onCode: (String) -> Unit, onDismiss: () -> Unit) {
    if (!rememberDialogAllowed()) return
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_verified_user) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (text != null) Text(text)
                OtpField(code, { code = it })
            }
        },
        confirmButton = {
            Button(onClick = { onCode(code); onDismiss() }, enabled = code.length == 6) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private data class SessionUi(
    val handle: Long,
    val agent: String,
    val ip: String,
    val country: String,
    val lastActive: Long,
    val current: Boolean,
)

@Composable
fun SessionsScreen() {
    val graph = MegaGlassApp.graph
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionUi>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var killAll by remember { mutableStateOf(false) }
    var kill by remember { mutableStateOf<SessionUi?>(null) }

    LaunchedEffect(reload) {
        catching {
            withContext(Dispatchers.IO) {
                val details = graph.mega.api.await { getExtendedAccountDetails(true, false, false, it) }.megaAccountDetails
                (0 until details.numSessions).map { details.getSession(it) }
                    .filter(MegaAccountSession::isAlive)
                    .map { s ->
                        SessionUi(s.handle, s.userAgent.orEmpty(), s.ip.orEmpty(), s.country.orEmpty(), s.mostRecentUsage, s.isCurrent)
                    }
                    .sortedWith(compareByDescending<SessionUi> { it.current }.thenByDescending { it.lastActive })
            }
        }.onSuccess { sessions = it; error = null }
            .onFailure { error = it.message }
    }

    fun close(handle: Long) = scope.launch {
        catching { withContext(Dispatchers.IO) { graph.mega.api.await { killSession(handle, it) } } }
            .onSuccess { graph.messenger.show(resources.getString(R.string.security_session_closed)) }
            .onFailure { graph.messenger.show(context.errorText(it)) }
        reload++
    }

    val list = sessions
    SettingsScaffold(
        title = stringResource(R.string.security_sessions),
        actions = {
            if ((list?.count { !it.current } ?: 0) > 0) {
                TextButton(onClick = { killAll = true }) { Text(stringResource(R.string.security_close_others)) }
            }
        },
    ) {
        when {
            list == null && error != null -> item { EmptyState(R.drawable.ic_cloud_off, stringResource(R.string.error_title), message = error) }
            list == null -> item { Box(Modifier.fillMaxWidth().height(240.dp)) { LoadingBox() } }
            else -> items(list, key = { it.handle }) { s ->
                ListItem(
                    leadingContent = {
                        Ic(
                            when {
                                s.agent.contains("Android", true) || s.agent.contains("iPhone", true) || s.agent.contains("iOS", true) ->
                                    R.drawable.ic_phone_android
                                s.agent.contains("Mozilla", true) -> R.drawable.ic_language
                                else -> R.drawable.ic_computer
                            },
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (s.current) stringResource(R.string.security_this_device) else Format.dateTime(s.lastActive),
                                s.ip.ifEmpty { null },
                                s.country.ifEmpty { null },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = if (!s.current) {
                        { TextButton(onClick = { kill = s }) { Text(stringResource(R.string.logout)) } }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(s.agent.ifEmpty { "?" }, maxLines = 2) }
            }
        }
    }

    if (killAll) {
        ConfirmDialog(
            title = stringResource(R.string.security_close_others_title),
            text = stringResource(R.string.security_close_others_text),
            confirmLabel = stringResource(R.string.security_close_others),
            destructive = true,
            icon = R.drawable.ic_logout,
            onConfirm = { close(MegaApiJava.INVALID_HANDLE) },
            onDismiss = { killAll = false },
        )
    }
    kill?.let { s ->
        ConfirmDialog(
            title = stringResource(R.string.security_close_session_title),
            text = s.agent,
            confirmLabel = stringResource(R.string.logout),
            destructive = true,
            onConfirm = { close(s.handle) },
            onDismiss = { kill = null },
        )
    }
}

@Composable
private fun FormButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shapes = ButtonDefaults.shapes(),
    ) {
        if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ChangePasswordScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askCode by remember { mutableStateOf(false) }
    val strength = rememberPasswordStrength(new, api)
    val mismatch = confirm.isNotEmpty() && confirm != new
    val valid = old.isNotEmpty() && strength >= MegaApiJava.PASSWORD_STRENGTH_WEAK && new == confirm && new != old

    fun change(pin: String?) {
        busy = true
        scope.launch {
            catching {
                withContext(Dispatchers.IO) {
                    if (pin == null) api.await { changePassword(old, new, it) }
                    else api.await { multiFactorAuthChangePassword(old, new, pin, it) }
                }
            }.onSuccess {
                graph.messenger.show(resources.getString(R.string.security_password_changed))
                nav.backFrom(ChangePassword)
            }.onFailure { error = context.errorText(it) }
            busy = false
        }
    }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            val correct = withContext(Dispatchers.IO) { api.checkPassword(old) }
            if (!correct) {
                error = resources.getString(R.string.security_wrong_password)
                busy = false
                return@launch
            }
            val has2fa = twoFactorEnabled()
            busy = false
            when (has2fa) {
                true -> askCode = true
                false -> change(null)
                null -> error = resources.getString(R.string.security_2fa_check_error)
            }
        }
    }

    FormScaffold(stringResource(R.string.security_change_password)) {
        Text(stringResource(R.string.security_change_password_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(24)
        PasswordInput(old, { old = it }, stringResource(R.string.security_current_password))
        Gap(12)
        PasswordInput(new, { new = it }, stringResource(R.string.security_new_password), newPassword = true)
        StrengthBars(strength)
        Gap(4)
        PasswordInput(
            confirm, { confirm = it }, stringResource(R.string.auth_confirm_password), newPassword = true,
            imeAction = ImeAction.Done, onDone = { if (valid) submit() },
            isError = mismatch, supporting = if (mismatch) stringResource(R.string.auth_password_mismatch) else null,
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Gap(24)
        FormButton(stringResource(R.string.security_change_password), busy, valid) { submit() }
        Gap(24)
    }

    if (askCode) {
        CodeDialog(
            title = stringResource(R.string.security_2fa_code_title),
            text = null,
            confirm = stringResource(R.string.security_change_password),
            onCode = { change(it) },
            onDismiss = { askCode = false },
        )
    }
}

@Composable
fun ChangeEmailScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askCode by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    val current = remember { api.myEmail.orEmpty() }
    val valid = email.contains('@') && email.contains('.') && !email.trim().equals(current, ignoreCase = true)

    fun change(pin: String?) {
        busy = true
        error = null
        scope.launch {
            val target = email.trim()
            catching {
                withContext(Dispatchers.IO) {
                    if (pin == null) api.await { changeEmail(target, it) } else api.await { multiFactorAuthChangeEmail(target, pin, it) }
                }
            }.onSuccess { sentTo = target }
                .onFailure { error = context.errorText(it) }
            busy = false
        }
    }

    FormScaffold(stringResource(R.string.security_change_email)) {
        val sent = sentTo
        if (sent != null) {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Sunny.toShape()),
                    contentAlignment = Alignment.Center,
                ) { Ic(R.drawable.ic_mail, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
                Gap(24)
                Text(stringResource(R.string.auth_reset_sent_title), style = MaterialTheme.typography.headlineSmall)
                Gap(12)
                Text(
                    stringResource(R.string.security_email_sent, sent),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@FormScaffold
        }
        Text(stringResource(R.string.security_change_email_text, current), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(24)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.security_new_email)) },
            leadingIcon = { Ic(R.drawable.ic_mail) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Gap(24)
        FormButton(stringResource(R.string.security_send_confirmation), busy, valid) {
            scope.launch {
                when (twoFactorEnabled()) {
                    true -> askCode = true
                    false -> change(null)
                    null -> error = resources.getString(R.string.security_2fa_check_error)
                }
            }
        }
    }

    if (askCode) {
        CodeDialog(
            title = stringResource(R.string.security_2fa_code_title),
            text = null,
            confirm = stringResource(R.string.security_send_confirmation),
            onCode = { change(it) },
            onDismiss = { askCode = false },
        )
    }
}

private enum class TwoFactorStep { INTRO, SECRET, DONE }

@Composable
fun TwoFactorSetupScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(TwoFactorStep.INTRO) }
    var secret by remember { mutableStateOf<String?>(null) }
    var authenticator by remember { mutableStateOf<Pair<String, Intent>?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (step == TwoFactorStep.SECRET) SecureWindow()

    fun start() {
        busy = true
        error = null
        scope.launch {
            catching { withContext(Dispatchers.IO) { api.await { multiFactorAuthGetCode(it) }.text } }
                .onSuccess { secret = it; step = TwoFactorStep.SECRET }
                .onFailure { error = context.errorText(it) }
            busy = false
        }
    }

    fun verify(pin: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            catching { withContext(Dispatchers.IO) { api.await { multiFactorAuthEnable(pin, it) } } }
                .onSuccess {
                    secret = null
                    step = TwoFactorStep.DONE
                }
                .onFailure {
                    error = context.errorText(it)
                    code = ""
                }
            busy = false
        }
    }

    FormScaffold(stringResource(R.string.security_2fa)) {
        AnimatedContent(step, label = "2fa") { s ->
            Column(Modifier.fillMaxWidth()) {
                when (s) {
                    TwoFactorStep.INTRO -> {
                        Hero(R.drawable.ic_verified_user)
                        Text(stringResource(R.string.security_2fa_intro_title), style = MaterialTheme.typography.headlineSmall)
                        Gap(12)
                        Text(stringResource(R.string.security_2fa_intro_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                        Gap(32)
                        FormButton(stringResource(R.string.security_2fa_start), busy, true) { start() }
                    }
                    TwoFactorStep.SECRET -> {
                        val key = secret.orEmpty()
                        Text(stringResource(R.string.security_2fa_step1), style = MaterialTheme.typography.titleMedium)
                        Gap(12)
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                            // Not selectable: a system copy would skip the sensitive flag of the Copy button.
                            Text(
                                groupSecret(key),
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                        Gap(12)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                copySensitive(context, key, expire = true)
                                graph.messenger.show(resources.getString(R.string.security_copied_secret))
                            }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.security_copy))
                            }
                            FilledTonalButton(onClick = {
                                val uri = Uri.parse("otpauth://totp/MEGA:" + Uri.encode(api.myEmail.orEmpty()) + "?secret=" + key + "&issuer=MEGA")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                // Any app can claim otpauth links: the user sees which app gets the key,
                                // also when there is only one (a chooser opens a single app at once).
                                val pm = context.packageManager
                                val apps = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                                when (apps.size) {
                                    0 -> graph.messenger.show(resources.getString(R.string.security_no_authenticator))
                                    1 -> apps[0].activityInfo.let { app ->
                                        authenticator = app.loadLabel(pm).toString() to Intent(intent).setClassName(app.packageName, app.name)
                                    }
                                    else -> context.startActivity(Intent.createChooser(intent, resources.getString(R.string.security_open_authenticator)))
                                }
                            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.security_open_authenticator)) }
                        }
                        Gap(32)
                        Text(stringResource(R.string.security_2fa_step2), style = MaterialTheme.typography.titleMedium)
                        Gap(12)
                        OtpField(code, {
                            code = it
                            if (it.length == 6) verify(it)
                        })
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                        Gap(24)
                        FormButton(stringResource(R.string.auth_verify), busy, code.length == 6) { verify(code) }
                    }
                    TwoFactorStep.DONE -> {
                        Hero(R.drawable.ic_check_circle)
                        Text(stringResource(R.string.security_2fa_done_title), style = MaterialTheme.typography.headlineSmall)
                        Gap(12)
                        Text(stringResource(R.string.security_2fa_done_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Gap(32)
                        FormButton(stringResource(R.string.security_recovery_key), false, true) { nav.replace(RecoveryKey) }
                        TextButton(onClick = { nav.back() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                    }
                }
                Gap(24)
            }
        }
    }

    authenticator?.let { (label, intent) ->
        ConfirmDialog(
            title = stringResource(R.string.security_send_key_to, label),
            text = null,
            confirmLabel = stringResource(R.string.security_open_authenticator),
            onConfirm = {
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    graph.messenger.show(resources.getString(R.string.security_no_authenticator))
                }
            },
            onDismiss = { authenticator = null },
        )
    }
}

@Composable
private fun Hero(icon: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) { Ic(icon, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable
fun RecoveryKeyScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val k = key
        if (uri == null || k == null) return@rememberLauncherForActivityResult
        scope.launch {
            catching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(k.toByteArray()) } ?: error("No output")
                }
            }.onSuccess {
                markExported()
                graph.messenger.show(resources.getString(R.string.security_key_saved))
            }.onFailure { graph.messenger.show(context.errorText(it)) }
        }
    }

    if (key != null) SecureWindow()

    FormScaffold(stringResource(R.string.security_recovery_key)) {
        val k = key
        if (k == null) {
            Hero(R.drawable.ic_vpn_key)
            Text(stringResource(R.string.security_recovery_key_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(24)
            PasswordInput(password, { password = it }, stringResource(R.string.security_current_password), imeAction = ImeAction.Done)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            Gap(24)
            FormButton(stringResource(R.string.security_show_key), busy, password.isNotEmpty()) {
                busy = true
                error = null
                scope.launch {
                    val exported = withContext(Dispatchers.IO) { if (api.checkPassword(password)) api.exportMasterKey() else null }
                    if (exported == null) error = resources.getString(R.string.security_wrong_password)
                    else {
                        key = exported
                        password = ""
                    }
                    busy = false
                }
            }
        } else {
            NoticeCard(R.drawable.ic_warning, stringResource(R.string.security_key_warning), Modifier.padding(top = 8.dp))
            Gap(24)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                // Not selectable: a system copy would skip the sensitive flag of the Copy button.
                Text(
                    groupSecret(k),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Gap(24)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    copySensitive(context, k, expire = true)
                    scope.launch { markExported() }
                    graph.messenger.show(resources.getString(R.string.security_copied_secret))
                }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Ic(R.drawable.ic_content_copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.security_copy))
                }
                Button(onClick = { save.launch("MEGA-RECOVERYKEY.txt") }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Ic(R.drawable.ic_save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save))
                }
            }
            Gap(24)
        }
    }
}

/** Tells MEGA that the user has a copy of the recovery key, so it stops the reminders. */
private suspend fun markExported() {
    catching { withContext(Dispatchers.IO) { MegaGlassApp.graph.mega.api.await { masterKeyExported(it) } } }
}

private enum class PasscodeStep { ENTER, CONFIRM }

@Composable
fun PasscodeSetupScreen() {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var length by remember { mutableIntStateOf(graph.settings.current.passcodeLength.takeIf { it == 4 || it == 6 } ?: 4) }
    var step by remember { mutableStateOf(PasscodeStep.ENTER) }
    var first by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }

    fun entered(value: String) {
        when (step) {
            PasscodeStep.ENTER -> {
                first = value
                code = ""
                step = PasscodeStep.CONFIRM
            }
            PasscodeStep.CONFIRM -> {
                if (value == first) {
                    // App scope: the passcode is saved also when the screen closes meanwhile.
                    graph.scope.launch(Dispatchers.Main) {
                        graph.lock.setPasscode(value)
                        graph.messenger.show(resources.getString(R.string.security_passcode_set))
                        nav.backFrom(PasscodeSetup)
                    }
                } else {
                    shake++
                    code = ""
                    first = ""
                    step = PasscodeStep.ENTER
                    graph.messenger.show(resources.getString(R.string.security_passcode_mismatch))
                }
            }
        }
    }

    androidx.compose.material3.Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) { BackButton { nav.back() } }
            Gap(16)
            Text(
                stringResource(if (step == PasscodeStep.ENTER) R.string.security_passcode_new else R.string.security_passcode_confirm),
                style = MaterialTheme.typography.headlineSmall,
            )
            Gap(8)
            Text(
                pluralStringResource(R.plurals.security_passcode_digits, length, length),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step == PasscodeStep.ENTER) {
                TextButton(onClick = {
                    length = if (length == 4) 6 else 4
                    code = ""
                }) { Text(stringResource(if (length == 4) R.string.security_use_6 else R.string.security_use_4)) }
            } else {
                Gap(48)
            }
            Gap(24)
            PinDots(length = length, filled = code.length, shakeKey = shake)
            Gap(40)
            NumberPad(
                onDigit = { d ->
                    if (code.length >= length) return@NumberPad
                    code += d
                    if (code.length == length) entered(code)
                },
                onDelete = { code = code.dropLast(1) },
            )
            Gap(24)
        }
    }
}
