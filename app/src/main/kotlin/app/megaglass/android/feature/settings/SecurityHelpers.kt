package app.megaglass.android.feature.settings

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError

/** True when the account has two-factor authentication; null when the check fails. */
suspend fun twoFactorEnabled(): Boolean? = withContext(Dispatchers.IO) {
    val api = MegaGlassApp.graph.mega.api
    val email = api.myEmail ?: return@withContext null
    val (code, request) = api.awaitResult { multiFactorAuthCheck(email, it) }
    if (code == MegaError.API_OK) request.flag else null
}

/** Blocks screenshots and the recents preview while this screen shows a secret. */
@Composable
fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!MegaGlassApp.graph.settings.current.secureScreen) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

@Composable
fun PasswordInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    newPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    isError: Boolean = false,
    supporting: String? = null,
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
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().semantics {
            contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
        },
    )
}

/** Five bars for MegaApiJava.getPasswordStrength (0 very weak .. 4 strong); nothing for -1. */
@Composable
fun StrengthBars(strength: Int) {
    if (strength < 0) return
    val labels = listOf(R.string.strength_very_weak, R.string.strength_weak, R.string.strength_medium, R.string.strength_good, R.string.strength_strong)
    val colors = listOf(
        MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error, AppTheme.extra.warning, AppTheme.extra.success, AppTheme.extra.success,
    )
    val s = strength.coerceIn(0, 4)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
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

/** Groups a secret in blocks of [size] characters for reading. */
fun groupSecret(secret: String, size: Int = 4): String = secret.chunked(size).joinToString(" ")
