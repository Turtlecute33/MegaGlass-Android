package app.megaglass.android.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.storage.LocalSource
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.core.util.FileType
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.TextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaShare
import java.io.File
import java.nio.charset.CodingErrorAction

/** Files above this size open read-only in a line list, which stays fast for long logs. */
private const val EDIT_LIMIT = 512L * 1024

private fun readText(file: File): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return file.inputStream().use { input -> decoder.decode(java.nio.ByteBuffer.wrap(input.readBytes())).toString() }
}

@Composable
fun TextEditorScreen(route: TextEditor) {
    val context = LocalContext.current
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    var title by remember { mutableStateOf(route.localPath?.let { File(it).name }.orEmpty()) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canEdit by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf(nz.mega.sdk.MegaApiJava.INVALID_HANDLE) }
    var saving by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val editorState = remember { TextFieldState() }
    var original by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(route, attempt) {
        runCatching {
            val file = if (route.localPath != null) {
                File(route.localPath)
            } else {
                val api = if (route.folderLink) graph.mega.folderApi else graph.mega.api
                val node = withContext(Dispatchers.IO) { api.getNodeByHandle(route.handle) }
                    ?: throw IllegalStateException(context.getString(R.string.viewer_not_found))
                title = node.name ?: ""
                parent = node.parentHandle
                canEdit = !route.folderLink && withContext(Dispatchers.IO) { api.getAccess(node) } >= MegaShare.ACCESS_READWRITE
                PreviewCache.fetch(route.handle, node.name ?: "file.txt", node.size, route.folderLink) { progress = it }
            }
            if (file.length() > EDIT_LIMIT) canEdit = false
            withContext(Dispatchers.IO) { readText(file) }
        }.onSuccess {
            text = it
            original = it
            if (canEdit) editorState.edit { replace(0, length, it) }
        }.onFailure { error = it.message ?: context.getString(R.string.error_title) }
    }
    LaunchedEffect(editorState, original) {
        snapshotFlow { editorState.text.toString() }.collect { dirty = canEdit && it != original }
    }

    val save: () -> Unit = save@{
        if (!dirty || saving) return@save
        saving = true
        // App scope: the save continues when the screen closes right after.
        graph.scope.launch {
            runCatching {
                val content = editorState.text.toString()
                val dir = File(context.cacheDir, "edit/" + System.nanoTime()).apply { mkdirs() }
                val file = File(dir, title)
                withContext(Dispatchers.IO) { file.writeText(content) }
                // Same name in the same folder: MEGA keeps the old file as a version.
                val done = launch {
                    val item = graph.transfers.completed.first { it.purpose == Purpose.EDIT && it.localPath == file.absolutePath }
                    graph.messenger.show(
                        if (item.status == TransferStatus.COMPLETED) context.getString(R.string.editor_saved, title)
                        else item.error ?: context.getString(R.string.error_title),
                    )
                }
                graph.transfers.upload(listOf(LocalSource(file.absolutePath, title, temporary = true)), parent, Purpose.EDIT, startFirst = true)
                original = content
                dirty = false
                done
            }.onFailure { graph.messenger.show(it.message ?: context.getString(R.string.error_title)) }
            saving = false
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
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
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
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                else -> {
                    val lines = remember(t) { t.lines() }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                            itemsIndexed(lines) { _, line -> Text(line, style = style) }
                        }
                    }
                }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            icon = { Ic(R.drawable.ic_edit) },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_text)) },
            confirmButton = {
                Button(onClick = {
                    askDiscard = false
                    save()
                    nav.back()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDiscard = false
                    dirty = false
                    nav.back()
                }) { Text(stringResource(R.string.editor_discard)) }
            },
        )
    }
}
