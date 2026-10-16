package app.megaglass.android.feature.auth

import app.megaglass.android.ui.components.rememberPasswordStrength
import app.megaglass.android.MegaGlassApp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.R
import app.megaglass.android.feature.settings.NoticeCard
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.theme.AppTheme

@Composable
fun AuthScreen(vm: AuthViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by MegaGlassApp.graph.session.notice.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != AuthStep.WELCOME) { vm.back() }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 8 else it / 8 } + fadeOut())
            },
            label = "authStep",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (step != AuthStep.WELCOME) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { BackButton { vm.back() } }
                }
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                    when (step) {
                        AuthStep.WELCOME -> Welcome(notice, onLogin = { vm.go(AuthStep.LOGIN) }, onSignup = { vm.go(AuthStep.SIGNUP) })
                        // The step of an earlier login stays: a notice after logout can also show here.
                        AuthStep.LOGIN -> Login(state, notice, vm)
                        AuthStep.TWO_FACTOR -> TwoFactor(state, vm)
                        AuthStep.SIGNUP -> Signup(state, vm)
                        AuthStep.CONFIRM_EMAIL -> Info(
                            icon = R.drawable.ic_mail,
                            title = stringResource(R.string.auth_confirm_title),
                            text = stringResource(R.string.auth_confirm_text, state.email),
                            action = stringResource(R.string.auth_resend),
                            onAction = vm::resendConfirmation,
                            secondary = stringResource(R.string.auth_login),
                            onSecondary = { vm.go(AuthStep.LOGIN) },
                            state = state,
                        )
                        AuthStep.FORGOT -> Forgot(state, vm)
                        AuthStep.RESET_SENT -> Info(
                            icon = R.drawable.ic_vpn_key,
                            title = stringResource(R.string.auth_reset_sent_title),
                            text = stringResource(R.string.auth_reset_sent_text, state.email),
                            action = stringResource(R.string.auth_login),
                            onAction = { vm.go(AuthStep.LOGIN) },
                            state = state,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero() {
    Box(
        Modifier.size(132.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(Color(0xFFD21F24)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(180.dp))
    }
}

@Composable
private fun Welcome(notice: String?, onLogin: () -> Unit, onSignup: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Hero()
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (notice != null) {
            Spacer(Modifier.height(24.dp))
            NoticeCard(R.drawable.ic_warning, notice)
        }
        Spacer(Modifier.height(64.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.auth_login), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSignup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.auth_create_account), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(R.drawable.ic_encrypted, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  " + stringResource(R.string.auth_e2ee),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String? = null) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (subtitle != null) {
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ErrorText(error: String?) {
    AnimatedVisibility(error != null) {
        Text(
            error.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
private fun EmailField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.auth_email)) },
        leadingIcon = { Ic(R.drawable.ic_mail) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress + ContentType.Username },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    newPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Ic(R.drawable.ic_password) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Ic(
                    if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password),
                )
            }
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().semantics {
            contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
        },
    )
}

@Composable
private fun Login(state: AuthState, notice: String?, vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = email.contains('@') && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            vm.login(email, password)
        }
    }
    Title(stringResource(R.string.auth_login_title), stringResource(R.string.auth_login_subtitle))
    if (notice != null) {
        NoticeCard(R.drawable.ic_warning, notice)
        Spacer(Modifier.height(24.dp))
    }
    EmailField(email, { email = it })
    Spacer(Modifier.height(12.dp))
    PasswordField(password, { password = it }, stringResource(R.string.auth_password), onDone = submit)
    ErrorText(state.error)
    TextButton(onClick = { vm.go(AuthStep.FORGOT) }) { Text(stringResource(R.string.auth_forgot)) }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(stringResource(R.string.auth_login), state.busy, canSubmit, submit)
}

@Composable
private fun TwoFactor(state: AuthState, vm: AuthViewModel) {
    var code by remember { mutableStateOf("") }
    Title(stringResource(R.string.auth_2fa_title), stringResource(R.string.auth_2fa_subtitle))
    OtpField(code, onChange = {
        code = it
        if (it.length == 6) vm.submitCode(it)
    })
    ErrorText(state.error)
    Spacer(Modifier.height(24.dp))
    PrimaryButton(stringResource(R.string.auth_verify), state.busy, code.length == 6) { vm.submitCode(code) }
}

/** Six boxes for a one-time code; a hidden text field takes the input (and autofill). */
@Composable
fun OtpField(code: String, onChange: (String) -> Unit, length: Int = 6) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = code,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(length)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.focusRequester(focus).semantics { contentType = ContentType.SmsOtpCode },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    val char = code.getOrNull(i)
                    val active = i == code.length
                    Box(
                        Modifier.weight(1f).height(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                if (active) 2.dp else 0.dp,
                                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(char?.toString() ?: "", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun Signup(state: AuthState, vm: AuthViewModel) {
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    val strength = rememberPasswordStrength(password, MegaGlassApp.graph.mega.api)
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = first.isNotBlank() && email.contains('@') && strength >= 1 && password == confirm && accepted
    Title(stringResource(R.string.auth_signup_title), stringResource(R.string.auth_signup_subtitle))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            first, { first = it }, label = { Text(stringResource(R.string.auth_first_name)) }, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentType = ContentType.PersonFirstName },
        )
        OutlinedTextField(
            last, { last = it }, label = { Text(stringResource(R.string.auth_last_name)) }, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentType = ContentType.PersonLastName },
        )
    }
    Spacer(Modifier.height(12.dp))
    EmailField(email, { email = it })
    Spacer(Modifier.height(12.dp))
    PasswordField(password, { password = it }, stringResource(R.string.auth_password), newPassword = true, imeAction = ImeAction.Next)
    StrengthMeter(strength)
    Spacer(Modifier.height(4.dp))
    PasswordField(confirm, { confirm = it }, stringResource(R.string.auth_confirm_password), newPassword = true)
    if (mismatch) {
        Text(stringResource(R.string.auth_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(accepted, { accepted = it })
        Text(
            stringResource(R.string.auth_lose_password_warning),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    ErrorText(state.error)
    Spacer(Modifier.height(16.dp))
    PrimaryButton(stringResource(R.string.auth_create_account), state.busy, valid) { vm.signup(first, last, email, password) }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun StrengthMeter(strength: Int) {
    if (strength < 0) return
    val labels = listOf(R.string.strength_very_weak, R.string.strength_weak, R.string.strength_medium, R.string.strength_good, R.string.strength_strong)
    val colors = listOf(
        MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error, AppTheme.extra.warning, AppTheme.extra.success, AppTheme.extra.success,
    )
    val s = strength.coerceIn(0, 4)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i <= s) colors[s] else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Text("  " + stringResource(labels[s]), style = MaterialTheme.typography.labelMedium, color = colors[s])
    }
}

@Composable
private fun Forgot(state: AuthState, vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    Title(stringResource(R.string.auth_forgot_title), stringResource(R.string.auth_forgot_subtitle))
    EmailField(email, { email = it })
    ErrorText(state.error)
    Spacer(Modifier.height(24.dp))
    PrimaryButton(stringResource(R.string.auth_send_link), state.busy, email.contains('@')) { vm.forgot(email) }
}

@Composable
private fun Info(
    icon: Int,
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    state: AuthState,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Sunny.toShape()),
            contentAlignment = Alignment.Center,
        ) { Ic(icon, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ErrorText(state.error)
        Spacer(Modifier.height(32.dp))
        FilledTonalButton(onClick = onAction, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(action) }
        if (secondary != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondary) }
        }
    }
}
