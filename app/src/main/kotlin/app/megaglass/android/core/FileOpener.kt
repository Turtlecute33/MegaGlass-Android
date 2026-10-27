package app.megaglass.android.core

import app.megaglass.android.core.util.catching

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import app.megaglass.android.R
import app.megaglass.android.core.security.AppLock
import app.megaglass.android.core.storage.LocalFiles
import app.megaglass.android.core.storage.OfflineStore
import app.megaglass.android.core.storage.localFileName
import app.megaglass.android.core.storage.VerifiedFiles
import app.megaglass.android.core.storage.trimImageCache
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferManager
import app.megaglass.android.core.transfers.TransferStatus
import app.megaglass.android.feature.viewer.PreviewCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val offline: OfflineStore,
    private val messenger: Messenger,
    private val lock: AppLock,
    private val scope: CoroutineScope,
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
                        if (item.nodeHandle !in _preparing.value) return@collect
                        _preparing.update { it - item.nodeHandle }
                        if (item.status == TransferStatus.COMPLETED) whenUnlocked {
                            // A logout while it waited removed the file.
                            File(item.localPath).takeIf { it.exists() }?.let(::view)
                        }
                        else if (item.status == TransferStatus.FAILED) messenger.show(item.error ?: context.getString(R.string.error_title))
                    }
                    Purpose.SHARE -> {
                        _preparing.update { it - item.nodeHandle }
                        if (item.status == TransferStatus.COMPLETED) whenUnlocked { collectShare(item.appData, File(item.localPath)) }
                        else {
                            synchronized(shareBatch) { shareBatch.remove(item.appData) }
                            messenger.show(item.error ?: context.getString(R.string.error_title))
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs [block] on the main thread when the app lock is open: a download can end while the
     * lock screen shows, and the app chooser must not open over it. Other completions do not wait.
     */
    private fun whenUnlocked(block: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            lock.locked.first { !it }
            block()
        }
    }

    private fun cacheFile(handle: Long, name: String) =
        File(File(files.openDir, MegaApiJava.handleToBase64(handle)), localFileName(name))

    /**
     * Removes the oldest copies in cache/open over the size limit, before a new open or share
     * (MEGAGLASS-PERF-012). Not while a download or a share batch waits: its files must stay.
     */
    suspend fun trimCopies() {
        if (_preparing.value.isNotEmpty() || synchronized(shareBatch) { shareBatch.isNotEmpty() }) return
        withContext(Dispatchers.IO) { trimImageCache(files.openDir, PreviewCache.BUDGET) }
    }

    /** Opens a cloud file in another app. An offline copy opens without a download (MEGAGLASS-UX-003). */
    suspend fun open(handle: Long, name: String, size: Long, folderLink: Boolean = false) {
        if (handle in _preparing.value) return
        val copy = if (folderLink) null else withContext(Dispatchers.IO) { offline.fileFor(handle) }
        if (copy != null) {
            withContext(Dispatchers.Main) { view(copy) }
            return
        }
        trimCopies()
        val target = cacheFile(handle, name)
        if (withContext(Dispatchers.IO) { VerifiedFiles.contains(target, size) }) {
            withContext(Dispatchers.Main) { view(target) }
            return
        }
        _preparing.update { it + handle }
        try {
            transfers.downloadTo(handle, target, Purpose.OPEN, folderLink)
        } catch (e: Exception) {
            _preparing.update { it - handle }
            throw e
        }
    }

    /** Shares cloud files (as files, not links) with another app. */
    suspend fun share(items: List<Triple<Long, String, Long>>) {
        if (items.any { it.first in _preparing.value }) throw java.io.IOException("A selected file is already being prepared")
        trimCopies()
        val batch = Purpose.SHARE + ":" + System.nanoTime()
        val ready = mutableListOf<File>()
        val pending = mutableListOf<Pair<Long, File>>()
        for ((handle, name, size) in items.distinctBy { it.first }) {
            val target = cacheFile(handle, name)
            if (withContext(Dispatchers.IO) { VerifiedFiles.contains(target, size) }) ready += target else pending += handle to target
        }
        if (pending.isEmpty()) {
            withContext(Dispatchers.Main) { send(ready) }
            return
        }
        synchronized(shareBatch) { shareBatch[batch] = (ready.map { it.absolutePath } + pending.map { "?" + it.second.absolutePath }).toMutableSet() }
        _preparing.update { it + pending.map { p -> p.first } }
        try {
            pending.forEach { (handle, target) -> transfers.downloadTo(handle, target, batch) }
        } catch (e: Exception) {
            synchronized(shareBatch) { shareBatch.remove(batch) }
            _preparing.update { it - pending.map { p -> p.first }.toSet() }
            throw e
        }
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
        // FileProvider only serves the folders in file_paths.xml (for example not a memory card).
        val uri = catching { files.shareUri(file) }.getOrElse {
            messenger.show(context.getString(R.string.no_app_to_open))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, files.mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            lock.expectReturn()
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            messenger.show(context.getString(R.string.no_app_to_open))
        }
    }

    fun send(list: List<File>) {
        if (list.isEmpty()) return
        val uris = catching { ArrayList(list.map(files::shareUri)) }.getOrElse {
            messenger.show(context.getString(R.string.error_title))
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files.mimeType(list[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            lock.expectReturn()
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            messenger.show(context.getString(R.string.no_app_to_open))
        }
    }

    /** Shares a text (a link). */
    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text).setType("text/plain")
        try {
            lock.expectReturn()
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            messenger.show(context.getString(R.string.no_app_to_open))
        }
    }

    fun clear() {
        _preparing.value = emptySet()
        synchronized(shareBatch) { shareBatch.clear() }
    }
}
