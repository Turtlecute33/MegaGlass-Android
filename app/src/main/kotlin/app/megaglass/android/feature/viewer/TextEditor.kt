package app.megaglass.android.feature.viewer

import app.megaglass.android.core.util.catching

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.storage.LocalSource
import app.megaglass.android.core.mega.SessionState
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.storage.localFileName
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.util.FileType
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.rememberDialogAllowed
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.TextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaShare
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Files above this size open read-only in a line list, which stays fast for long logs. */
private const val EDIT_LIMIT = 512L * 1024

/**
 * Lines for the read-only view. Very long lines (minified code) split into parts of [max]
 * characters: one Text with millions of characters freezes the screen while it is measured.
 */
internal fun displayLines(text: String, max: Int = 4096): List<String> {
    require(max > 1)
    val out = ArrayList<String>()
    for (line in text.lines()) {
        var start = 0
        while (line.length - start > max) {
            // Keep a surrogate pair together.
            val end = if (Character.isHighSurrogate(line[start + max - 1])) start + max - 1 else start + max
            out += line.substring(start, end)
            start = end
        }
        out += line.substring(start)
    }
    return out
}

/** The text, and true when the file is valid UTF-8. Files in other encodings open read-only, so that a save cannot damage them. */
private fun readText(file: File): Pair<String, Boolean> {
    val bytes = file.readBytes()
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString() to true
    } catch (_: CharacterCodingException) {
        String(bytes, Charsets.UTF_8) to false
    }
}

/**
 * The loaded file and the edits. It is a ViewModel of the entry, so unsaved edits stay while
 * the editor is not on screen (another tab, Transfers).
 */
class TextEditorModel : ViewModel() {
    var title by mutableStateOf("")
    var text by mutableStateOf<String?>(null)
    var canEdit by mutableStateOf(false)
    var parent = nz.mega.sdk.MegaApiJava.INVALID_HANDLE
    /** Each save makes a new node; the old one becomes a version. */
    var handle = nz.mega.sdk.MegaApiJava.INVALID_HANDLE
    val editorState = TextFieldState()
    var original by mutableStateOf("")
    var saving by mutableStateOf(false)
    /** Set by Save in the leave dialog: the editor closes when the save succeeds. */
    var closeAfterSave = false
    /** The entry left the back stack: a save that ends later must not close another editor of the same file. */
    var cleared = false
        private set

    override fun onCleared() { cleared = true }
}

@Composable
fun TextEditorScreen(route: TextEditor) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val m = viewModel {
        TextEditorModel().apply {
            title = route.localPath?.let { File(it).name }.orEmpty()
            handle = route.handle
        }
    }
    var title by m::title
    var text by m::text
    var canEdit by m::canEdit
    var parent by m::parent
    val editorState = m.editorState
    var original by m::original
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var saving by m::saving
    var attempt by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(route, attempt) {
        if (text != null) return@LaunchedEffect
        catching {
            val file = if (route.localPath != null) {
                File(route.localPath)
            } else {
                val api = if (route.folderLink) graph.mega.folderApi else graph.mega.api
                val node = withContext(Dispatchers.IO) { api.getNodeByHandle(route.handle) }
                    ?: throw IllegalStateException(resources.getString(R.string.viewer_not_found))
                title = node.name ?: ""
                parent = node.parentHandle
                canEdit = !route.folderLink && withContext(Dispatchers.IO) { api.getAccess(node) } >= MegaShare.ACCESS_READWRITE
                PreviewCache.fetch(route.handle, node.name ?: "file.txt", node.size, route.folderLink) { progress = it }
            }
            if (file.length() > EDIT_LIMIT) canEdit = false
            withContext(Dispatchers.IO) { readText(file) }
        }.onSuccess { (content, utf8) ->
            if (!utf8) canEdit = false
            if (canEdit) editorState.edit { replace(0, length, content) }
            original = content
            text = content
        }.onFailure { error = it.message ?: resources.getString(R.string.error_title) }
    }
    LaunchedEffect(editorState, original) {
        snapshotFlow { editorState.text.toString() }.collect { dirty = canEdit && it != original }
    }

    val save: () -> Unit = save@{
        if (!dirty || saving) return@save
        saving = true
        val content = editorState.text.toString()
        val savedTitle = title
        val savedParent = parent
        val savedHandle = m.handle
        if (graph.transfers.state.value.uploadsPaused) graph.messenger.show(resources.getString(R.string.editor_waiting_paused))
        // The save outlives the screen; edits become clean only after the SDK confirms success.
        graph.scope.launch(Dispatchers.Main) {
            val saveJob = currentCoroutineContext().job
            val sessionMonitor = graph.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                graph.session.state.first { it != SessionState.Ready }
                saveJob.cancel()
            }
            try {
                catching {
                    withContext(Dispatchers.IO) {
                        val api = graph.mega.api
                        val destination = api.getNodeByHandle(savedParent) ?: throw java.io.IOException("The destination folder is no longer available")
                        if (api.getChildNode(destination, savedTitle)?.handle != savedHandle) {
                            throw java.io.IOException("This file changed or moved. Reopen it before saving.")
                        }
                    }
                    val dir = File(context.cacheDir, "edit/" + java.util.UUID.randomUUID()).apply { mkdirs() }
                    val file = File(dir, localFileName(savedTitle))
                    withContext(Dispatchers.IO) { file.writeText(content) }
                    coroutineScope {
                        val done = async(start = CoroutineStart.UNDISPATCHED) {
                            graph.transfers.completed.first { it.purpose == Purpose.EDIT && it.localPath == file.absolutePath }
                        }
                        // Temporary: a failed copy stays for Retry in Transfers and is removed when that succeeds.
                        graph.transfers.upload(listOf(LocalSource(file.absolutePath, savedTitle, temporary = true)), savedParent, Purpose.EDIT, startFirst = true)
                        val item = done.await()
                        if (item.status != TransferStatus.COMPLETED) throw java.io.IOException(item.error ?: "Save failed")
                        if (item.nodeHandle != nz.mega.sdk.MegaApiJava.INVALID_HANDLE) m.handle = item.nodeHandle
                    }
                    original = content
                    dirty = editorState.text.toString() != content
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    // With file versions off, the server deletes the previous content.
                    val versioned = catching {
                        val (code, request) = withContext(Dispatchers.IO) { graph.mega.api.awaitResult { getFileVersionsOption(it) } }
                        code == MegaError.API_ENOENT || code == MegaError.API_OK && !request.flag
                    }.getOrDefault(false)
                    graph.messenger.show(resources.getString(if (versioned) R.string.editor_saved_version else R.string.editor_saved, savedTitle))
                    if (m.closeAfterSave && !m.cleared && !dirty) nav.backFrom(route)
                }.onFailure { graph.messenger.show(it.message ?: resources.getString(R.string.error_title)) }
            } finally {
                sessionMonitor.cancel()
                saving = false
                m.closeAfterSave = false
            }
        }
    }

    BackHandler(enabled = dirty) { askDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            stringResource(
                                when {
                                    saving -> R.string.editor_saving
                                    dirty -> R.string.editor_unsaved
                                    canEdit -> R.string.editor_editable
                                    else -> R.string.editor_read_only
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton { if (dirty) askDiscard = true else nav.back() } },
                actions = {
                    if (canEdit) IconAction(R.drawable.ic_save, stringResource(R.string.save), save, enabled = dirty && !saving)
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val t = text
            val mono = FileType.of(title) == FileType.CODE
            val style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when {
                error != null -> ErrorState(error!!, onRetry = {
                    error = null
                    attempt++
                })
                t == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator()
                    if (progress > 0f) LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.padding(top = 16.dp))
                }
                canEdit -> BasicTextField(
                    state = editorState,
                    // The save sends the text of this moment: later edits would be lost when the editor closes after it.
                    readOnly = saving,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                else -> {
                    val lines = remember(t) { displayLines(t) }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                            itemsIndexed(lines) { _, line -> Text(line, style = style) }
                        }
                    }
                }
            }
        }
    }

    if (askDiscard && rememberDialogAllowed()) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            icon = { Ic(R.drawable.ic_edit) },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_text)) },
            confirmButton = {
                // The editor closes only after the save succeeds; on an error it stays open with the edits.
                // A save that runs already closes the editor when it ends.
                Button(onClick = {
                    askDiscard = false
                    m.closeAfterSave = true
                    save()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDiscard = false
                    dirty = false
                    m.closeAfterSave = false
                    nav.back()
                }) { Text(stringResource(R.string.editor_discard)) }
            },
        )
    }
}
