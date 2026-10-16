package app.megaglass.android.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.megaglass.android.R
import java.util.UUID

/** A Material Symbols icon from res/drawable. */
@Composable
fun Ic(
    @DrawableRes id: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) = Icon(painterResource(id), contentDescription, modifier, tint)

@Composable
fun IconAction(@DrawableRes id: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Ic(id, label) }
}

@Composable
fun BackButton(onClick: () -> Unit) = IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), onClick)

/** Centered expressive loading indicator. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(Modifier.size(64.dp))
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Empty or error state: an icon in a Material shape, a title, a message, and an optional action. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(32.dp),
) {
    Column(
        modifier.fillMaxSize().padding(contentPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(112.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Ic(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) = EmptyState(
    icon = R.drawable.ic_cloud_off,
    title = stringResource(R.string.error_title),
    message = message,
    actionLabel = if (onRetry != null) stringResource(R.string.retry) else null,
    onAction = onRetry,
    modifier = modifier,
)

/** True while the app lock covers the app. */
val LocalAppLocked = compositionLocalOf { false }

private class Shown { var value = false }

/**
 * False for a dialog or sheet that first appears while the app is locked, until the unlock: its
 * window would be added above the lock window. One that was open before the lock stays.
 */
@Composable
fun rememberDialogAllowed(): Boolean {
    val locked = LocalAppLocked.current
    val shown = remember { Shown() }
    if (!locked) shown.value = true
    return shown.value
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    if (!rememberDialogAllowed()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Ic(it) } },
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * One text field in a dialog (new folder, rename, password...).
 * [validate] returns an error message or null. With [selectBaseName] the file extension is not selected.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    selectBaseName: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> String? = { if (it.isBlank()) "" else null },
) {
    if (!rememberDialogAllowed()) return
    val end = if (selectBaseName && initial.contains('.')) initial.lastIndexOf('.') else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val error = validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (error == null) { onConfirm(value.text.trim()); onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = error != null && error.isNotEmpty(),
                supportingText = if (!error.isNullOrEmpty()) { { Text(error) } } else null,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { Button(onClick = submit, enabled = error == null) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Characters that MEGA does not allow in names. */
fun invalidNameError(name: String, invalidMessage: String): String? = when {
    name.isBlank() -> ""
    name.any { it in "\\/:*?\"<>|" } -> invalidMessage
    name == "." || name == ".." -> invalidMessage
    else -> null
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Thick rounded usage bar, for storage and transfer quota. */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val color = when {
        fraction >= 0.95f -> MaterialTheme.colorScheme.error
        fraction >= 0.8f -> app.megaglass.android.ui.theme.AppTheme.extra.warning
        else -> MaterialTheme.colorScheme.primary
    }
    androidx.compose.material3.LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        gapSize = 4.dp,
        drawStopIndicator = {},
    )
}

/**
 * Copies a secret (a link with its key, a recovery key); Android 13+ hides it in the clipboard preview.
 * With [expire] the clip is removed after [SECRET_CLIP_MS], unless something else was copied since.
 */
fun copySensitive(context: Context, text: String, expire: Boolean = false) {
    val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    val id = UUID.randomUUID().toString()
    val clip = ClipData.newPlainText("MEGA", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        if (expire) putString(CLIP_ID, id)
    }
    clipboard.setPrimaryClip(clip)
    if (!expire) return
    Handler(Looper.getMainLooper()).postDelayed({
        // In the background the clipboard cannot be read (null): clear it anyway, the secret must not stay.
        val current = clipboard.primaryClipDescription
        if (current == null || current.extras?.getString(CLIP_ID) == id) clipboard.clearPrimaryClip()
    }, SECRET_CLIP_MS)
}

private const val SECRET_CLIP_MS = 60_000L
private const val CLIP_ID = "app.megaglass.clip"
