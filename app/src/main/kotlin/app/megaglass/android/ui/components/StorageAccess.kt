package app.megaglass.android.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R

/**
 * Asks for all-files access before an action that writes to shared storage (downloads, folder
 * uploads). Without it, downloads go to the app's own folder instead.
 */
@Stable
class StorageAccess {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var waitingForSettings = false

    val granted: Boolean get() = MegaGlassApp.graph.files.hasAllFilesAccess

    /** Runs [action] now if access is granted, otherwise after the user decides. */
    fun ensure(action: () -> Unit) {
        if (granted) action() else pending = action
    }
}

@Composable
fun rememberStorageAccess(): StorageAccess {
    val access = remember { StorageAccess() }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (access.waitingForSettings) {
            access.waitingForSettings = false
            access.pending?.invoke()
            access.pending = null
        }
    }
    val action = access.pending
    if (action != null && !access.waitingForSettings) {
        AlertDialog(
            onDismissRequest = { access.pending = null },
            icon = { Ic(R.drawable.ic_sd_card) },
            title = { Text(stringResource(R.string.storage_access_title)) },
            text = { Text(stringResource(R.string.storage_access_text)) },
            confirmButton = {
                Button(onClick = {
                    access.waitingForSettings = true
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)),
                    )
                }) { Text(stringResource(R.string.storage_access_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    access.pending = null
                    action()
                }) { Text(stringResource(R.string.storage_access_app_folder)) }
            },
        )
    }
    return access
}
