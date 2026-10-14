package app.megaglass.android.feature.drive

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.storage.LocalSource
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.components.invalidNameError
import app.megaglass.android.ui.components.rememberStorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts uploads into a folder from the system pickers and the camera. */
@Stable
class Uploader internal constructor(
    private val pickFiles: () -> Unit,
    private val pickFolder: () -> Unit,
    private val photo: () -> Unit,
    private val newText: () -> Unit,
) {
    fun files() = pickFiles()
    fun folder() = pickFolder()
    fun takePhoto() = photo()
    fun newTextFile() = newText()
}

/** Uploads content URIs into [parent]; real paths are used when possible (no copy). */
suspend fun uploadUris(uris: List<Uri>, parent: Long) {
    val graph = MegaGlassApp.graph
    val sources = uris.mapNotNull { runCatching { graph.files.resolveForUpload(it) }.getOrNull() }
    if (sources.isEmpty()) return
    graph.transfers.upload(sources, parent)
    graph.messenger.show(
        graph.context.resources.getQuantityString(R.plurals.upload_started, sources.size, sources.size),
    )
}

@Composable
fun rememberUploader(parent: Long): Uploader {
    val graph = MegaGlassApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target by rememberUpdatedState(parent)
    val storage = rememberStorageAccess()
    var photoFile by remember { mutableStateOf<File?>(null) }
    var askTextName by remember { mutableStateOf(false) }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { uploadUris(uris, target) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = graph.files.realPath(uri)
        if (path == null) {
            graph.messenger.show(context.getString(R.string.folder_upload_needs_access))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            graph.transfers.upload(listOf(LocalSource(path, File(path).name, temporary = false)), target)
            graph.messenger.show(context.getString(R.string.folder_upload_started, File(path).name))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        if (ok && f != null && f.length() > 0) {
            scope.launch { graph.transfers.upload(listOf(LocalSource(f.absolutePath, f.name, temporary = true)), target) }
        }
        photoFile = null
    }

    if (askTextName) {
        val invalid = stringResource(R.string.invalid_name)
        TextInputDialog(
            title = stringResource(R.string.new_text_file),
            label = stringResource(R.string.name),
            confirmLabel = stringResource(R.string.create),
            initial = "notes.txt",
            selectBaseName = true,
            onConfirm = { name ->
                scope.launch {
                    val dir = File(context.cacheDir, "uploads/" + System.nanoTime()).apply { mkdirs() }
                    val file = File(dir, name)
                    withContext(Dispatchers.IO) { file.writeText("") }
                    graph.transfers.upload(listOf(LocalSource(file.absolutePath, name, temporary = true)), target)
                }
            },
            onDismiss = { askTextName = false },
            validate = { invalidNameError(it, invalid) },
        )
    }

    return remember {
        Uploader(
            pickFiles = { files.launch(arrayOf("*/*")) },
            pickFolder = { storage.ensure { folder.launch(null) } },
            photo = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val f = File(dir, "IMG_$stamp.jpg")
                photoFile = f
                camera.launch(graph.files.shareUri(f))
            },
            newText = { askTextName = true },
        )
    }
}

@Composable
fun UploadDestinationDialog(uris: List<Uri>, onDismiss: () -> Unit) {
    // The app scope: the dialog leaves the composition before large files finish copying.
    val scope = MegaGlassApp.graph.scope
    FolderPickerDialog(
        title = pluralStringResource(R.plurals.upload_items, uris.size, uris.size),
        confirmLabel = stringResource(R.string.upload_here),
        onPick = { target -> scope.launch { uploadUris(uris, target) } },
        onDismiss = onDismiss,
    )
}
