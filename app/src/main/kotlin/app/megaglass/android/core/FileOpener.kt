package app.megaglass.android.core

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import app.megaglass.android.R
import app.megaglass.android.core.storage.LocalFiles
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferManager
import app.megaglass.android.core.transfers.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaApiJava
import java.io.File

/** A short message for the snackbar. */
data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

/** App-wide snackbar messages. */
class Messenger {
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, actionLabel, onAction))
    }
}

/**
 * Opens or shares cloud files with other apps: downloads them into the cache, then starts
 * the app chooser with content:// URIs (FileProvider), so other apps get temporary read access only.
 */
class FileOpener(
    private val context: Context,
    private val transfers: TransferManager,
    private val files: LocalFiles,
    private val messenger: Messenger,
    scope: CoroutineScope,
) {
    private val _preparing = MutableStateFlow<Set<Long>>(emptySet())
    /** Nodes that are downloading before they open. */
    val preparing: StateFlow<Set<Long>> = _preparing

    private val shareBatch = mutableMapOf<String, MutableSet<String>>()

    init {
        scope.launch {
            transfers.completed.collect { item ->
                when (item.purpose) {
                    Purpose.OPEN -> {
                        _preparing.update { it - item.nodeHandle }
                        if (item.status == TransferStatus.COMPLETED) view(File(item.localPath))
                        else if (item.status == TransferStatus.FAILED) messenger.show(item.error ?: context.getString(R.string.error_title))
                    }
                    Purpose.SHARE -> {
                        _preparing.update { it - item.nodeHandle }
                        if (item.status == TransferStatus.COMPLETED) collectShare(item.appData, File(item.localPath))
                    }
                }
            }
        }
    }

    private fun cacheFile(handle: Long, name: String) =
        File(File(files.openDir, MegaApiJava.handleToBase64(handle)), name)

    /** Opens a cloud file in another app. */
    suspend fun open(handle: Long, name: String, size: Long, folderLink: Boolean = false) {
        val target = cacheFile(handle, name)
        if (target.exists() && target.length() == size) {
            view(target)
            return
        }
        _preparing.update { it + handle }
        transfers.downloadTo(handle, target, Purpose.OPEN, folderLink)
    }

    /** Shares cloud files (as files, not links) with another app. */
    suspend fun share(items: List<Triple<Long, String, Long>>) {
        val batch = Purpose.SHARE + ":" + System.nanoTime()
        val ready = mutableListOf<File>()
        val pending = mutableListOf<Pair<Long, File>>()
        for ((handle, name, size) in items) {
            val target = cacheFile(handle, name)
            if (target.exists() && target.length() == size) ready += target else pending += handle to target
        }
        if (pending.isEmpty()) {
            send(ready)
            return
        }
        synchronized(shareBatch) { shareBatch[batch] = (ready.map { it.absolutePath } + pending.map { "?" + it.second.absolutePath }).toMutableSet() }
        _preparing.update { it + pending.map { p -> p.first } }
        pending.forEach { (handle, target) -> transfers.downloadTo(handle, target, batch) }
    }

    private fun collectShare(appData: String, file: File) {
        val done: List<File>? = synchronized(shareBatch) {
            val set = shareBatch[appData] ?: return
            set.remove("?" + file.absolutePath)
            set.add(file.absolutePath)
            if (set.none { it.startsWith("?") }) {
                shareBatch.remove(appData)
                set.map(::File)
            } else null
        }
        done?.let(::send)
    }

    /** Opens a local file in another app. */
    fun view(file: File) {
        val uri = files.shareUri(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, files.mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            messenger.show(context.getString(R.string.no_app_to_open))
        }
    }

    fun send(list: List<File>) {
        if (list.isEmpty()) return
        val uris = ArrayList(list.map(files::shareUri))
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files.mimeType(list[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares a text (a link). */
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text).setType("text/plain")
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
