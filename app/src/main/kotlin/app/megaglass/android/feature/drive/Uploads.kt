package app.megaglass.android.feature.drive

import app.megaglass.android.core.util.catching

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
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
    val account = graph.mega.api.myUserHandle
    val sources = mutableListOf<LocalSource>()
    var unreadable = 0
    for (uri in uris.distinct()) {
        val source = catching { graph.files.resolveForUpload(uri) }.getOrNull()
        if (source == null) unreadable++ else sources += source
    }
    if (unreadable > 0) graph.messenger.show(graph.context.resources.getQuantityString(R.plurals.upload_unreadable, unreadable, unreadable))
    if (sources.isEmpty()) return
    check(graph.session.isReady && graph.mega.api.myUserHandle == account) { "The account changed while preparing this upload" }
    graph.transfers.upload(sources, parent)
    graph.messenger.show(
        graph.context.resources.getQuantityString(R.plurals.upload_started, sources.size, sources.size),
    )
}

/**
 * Starts an upload in the app scope and shows an error as a message: the target folder can be
 * gone (deleted, share removed) when a picker returns.
 */
private fun launchUpload(block: suspend () -> Unit) {
    val graph = MegaGlassApp.graph
    graph.scope.launch {
        catching { block() }.onFailure { graph.messenger.show(it.message ?: graph.context.getString(R.string.error_title)) }
    }
}

@Composable
fun rememberUploader(parent: Long): Uploader {
    val graph = MegaGlassApp.graph
    val context = LocalContext.current
    val resources = LocalResources.current
    val target by rememberUpdatedState(parent)
    val storage = rememberStorageAccess()
    // Saveable: the system can destroy this activity while the camera app is open.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var askTextName by remember { mutableStateOf(false) }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) launchUpload { uploadUris(uris, target) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = graph.files.realPath(uri)
        if (path == null) {
            graph.messenger.show(resources.getString(R.string.folder_upload_needs_access))
            return@rememberLauncherForActivityResult
        }
        launchUpload {
            graph.transfers.upload(listOf(LocalSource(path, File(path).name, temporary = false)), target)
            graph.messenger.show(resources.getString(R.string.folder_upload_started, File(path).name))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoPath?.let(::File)
        if (ok && f != null && f.length() > 0) {
            launchUpload { graph.transfers.upload(listOf(LocalSource(f.absolutePath, f.name, temporary = true)), target) }
        } else if (f != null) {
            f.delete()
            f.parentFile?.delete()
        }
        photoPath = null
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
                launchUpload {
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
                // A captured photo is the only original until its upload succeeds. Cache
                // eviction and a second capture must not remove or replace it. A folder for each
                // capture keeps the file name (the cloud name) clean.
                val dir = File(context.filesDir, "pending-uploads/" + java.util.UUID.randomUUID()).apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val f = File(dir, "IMG_$stamp.jpg")
                photoPath = f.absolutePath
                camera.launch(graph.files.shareUri(f))
            },
            newText = { askTextName = true },
        )
    }
}

@Composable
fun UploadDestinationDialog(uris: List<Uri>, onDismiss: () -> Unit) {
    // The app scope: the dialog leaves the composition before large files finish copying.
    FolderPickerDialog(
        title = pluralStringResource(R.plurals.upload_items, uris.size, uris.size),
        confirmLabel = stringResource(R.string.upload_here),
        onPick = { target -> launchUpload { uploadUris(uris, target) } },
        onDismiss = onDismiss,
    )
}
