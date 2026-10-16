package app.megaglass.android.core.camera

import app.megaglass.android.core.util.catching

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import app.megaglass.android.MainActivity
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.SessionState
import app.megaglass.android.core.storage.LocalSource
import app.megaglass.android.core.storage.isContentUri
import app.megaglass.android.core.transfers.Purpose
import app.megaglass.android.core.transfers.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uploads new photos and videos from the camera folders (DCIM) to the camera uploads folder.
 * Files are read through redacted MediaStore URIs (no copies, no location data); a file is
 * skipped when a node with the same fingerprint already exists in the cloud. A marker
 * (MediaStore DATE_ADDED) keeps later runs short.
 */
class CameraUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Media(val id: Long, val uri: Uri, val path: String, val name: String, val added: Long, val taken: Long) {
        val key get() = "$added:$id"
    }

    /** [source] is what the SDK reads, [name] the name in the cloud. */
    private class Upload(val media: Media, val source: String, val name: String)

    private class Selection(
        val upload: List<Upload>,
        /** In the cloud already, or gone from the device. */
        val handled: List<Media>,
        /** Exists but cannot be read now: the marker stays before it, so that a later run tries again. */
        val unreadable: List<Media>,
        /** Media of an earlier run that is still uploading: a second upload would fail as a duplicate. */
        val busy: List<Media>,
    )

    override suspend fun doWork(): Result {
        val result = uploadOnce()
        // Retry already keeps this work enqueued. Appending a trigger on every retry builds an
        // ever-growing dependency chain that cannot run until the original work succeeds.
        // Every success path returns the public, empty-output success result.
        if (result == Result.success() && inputData.getBoolean(CameraUploads.KEY_TRIGGERED, false) &&
            MegaGlassApp.graph.settings.current.cameraUploads) {
            CameraUploads.scheduleTrigger(applicationContext, MegaGlassApp.graph.settings.current, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return result
    }

    private suspend fun uploadOnce(): Result {
        val graph = MegaGlassApp.graph
        val context = applicationContext
        val prefs = CuPrefs(context)
        val settings = graph.settings.current
        var queued = false
        try {
            if (!settings.cameraUploads) return Result.success()
            if (!CameraUploads.hasPermission(context, settings.cuIncludeVideos)) {
                CameraUploads.setStatus(CuStatus.NeedsPermission)
                return Result.success()
            }

            // A cold process logs in from the saved session (MegaGlassApp starts it).
            graph.session.start()
            val session = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                graph.session.state.first { it == SessionState.Ready || it == SessionState.LoggedOut || it is SessionState.Blocked || it is SessionState.Failed }
            }
            if (session != SessionState.Ready) return if (session == null || session is SessionState.Failed) Result.retry() else Result.success()

            // Another worker that runs now uploads the same media: wait for it, then scan again.
            CameraUploads.running.withLock {
                CameraUploads.setStatus(CuStatus.Scanning)
                val folder = CameraUploads.ensureFolder(graph.mega, context.getString(R.string.cu_default_folder))
                    ?: return Result.retry()

                if (prefs.markerSince != settings.cuSince) {
                    prefs.marker = 0
                    prefs.handled = emptySet()
                    prefs.markerSince = settings.cuSince
                }
                // The scan overlaps the last hours: media added in the same second as the marker, or
                // published later (a long video is pending while it records), still gets found.
                val since = maxOf(prefs.marker - RESCAN_WINDOW_S, settings.cuSince / 1000)
                val media = withContext(Dispatchers.IO) {
                    buildList {
                        addAll(query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), since))
                        if (settings.cuIncludeVideos) addAll(query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), since))
                    }.sortedBy { it.added }
                }

                // Earlier versions uploaded from the file path, with the location data. Such uploads,
                // resumed from the SDK queue, stop; this run queues their media again without it.
                withContext(Dispatchers.IO) { graph.transfers.cancelUploads(Purpose.CAMERA) { !isContentUri(it) } }
                val inQueue = graph.transfers.uploadingPaths()
                val handled = prefs.handled
                val selection = withContext(Dispatchers.IO) {
                    selectNew(media, folder, settings.cuKeepFileNames, prefs.marker, handled, inQueue)
                }
                val pending = selection.upload
                val busy = selection.busy

                val uploaded = mutableSetOf<String>()
                val failed = mutableSetOf<String>()
                if (pending.isNotEmpty()) coroutineScope {
                    catching { setForeground(foregroundInfo(0, pending.size)) }
                    CameraUploads.setStatus(CuStatus.Uploading(0, pending.size))
                    val paths = pending.map { it.source }.toSet()
                    // Listen before starting (undispatched: subscribed at once), so that no finish event is missed.
                    val collector = async(start = CoroutineStart.UNDISPATCHED) {
                        graph.transfers.completed
                            .filter { it.purpose == Purpose.CAMERA && it.localPath in paths }
                            .takeWhile { item ->
                                if (item.status == TransferStatus.COMPLETED) uploaded += item.localPath else failed += item.localPath
                                val done = uploaded.size + failed.size
                                CameraUploads.setStatus(CuStatus.Uploading(done, pending.size))
                                catching { setForeground(foregroundInfo(done, pending.size)) }
                                done < pending.size
                            }
                            .collect {}
                    }
                    queued = true
                    graph.transfers.upload(
                        pending.map { LocalSource(it.source, it.name, temporary = false) },
                        folder,
                        Purpose.CAMERA,
                    )
                    withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { collector.await() }
                    collector.cancel()
                }

                // The marker moves up to just before the oldest media that did not upload.
                val missing = pending.filter { it.source !in uploaded }.map { it.media } + busy + selection.unreadable
                val oldestMissing = missing.minOfOrNull { it.added }
                (oldestMissing?.minus(1) ?: media.maxOfOrNull { it.added })?.let { prefs.marker = maxOf(prefs.marker, it) }
                // The rescan window finds this media again. Remembered, it is not uploaded again
                // after the user deletes it from the cloud.
                val done = selection.handled + pending.filter { it.source in uploaded }.map { it.media }
                val windowStart = prefs.marker - RESCAN_WINDOW_S
                prefs.handled = (handled.orEmpty() + done.map { it.key })
                    .filterTo(HashSet()) { (it.substringBefore(':').toLongOrNull() ?: 0) >= windowStart }

                val notUploaded = failed.size + selection.unreadable.size
                if (notUploaded > 0) {
                    CameraUploads.setStatus(CuStatus.Failed(context.resources.getQuantityString(R.plurals.cu_failed, notUploaded, notUploaded)))
                    return Result.retry()
                }
                if (oldestMissing != null) {
                    if (pending.isEmpty()) CameraUploads.setStatus(CuStatus.Uploading(0, busy.size))
                    return Result.retry()
                }
                finish(prefs)
                return Result.success()
            }
        } catch (e: CancellationException) {
            // Wi-Fi or charging was lost: the queued uploads stop too, so that the settings also
            // hold for them. The next run queues them again. After other stops (time limit) the
            // queue continues, and CameraUploads stops it when the settings no longer hold.
            if (queued && (stopReason in CONSTRAINT_STOPS || !graph.settings.current.cameraUploads)) {
                withContext(NonCancellable) { graph.transfers.cancelUploads(Purpose.CAMERA) }
            }
            throw e
        } catch (e: Exception) {
            CameraUploads.setStatus(CuStatus.Failed(e.message ?: context.getString(R.string.cu_error)))
            return Result.retry()
        }
    }

    private fun finish(prefs: CuPrefs) {
        prefs.lastRun = System.currentTimeMillis()
        CameraUploads.setStatus(CuStatus.Idle(prefs.lastRun))
    }

    /** Media in DCIM added after [since] (seconds), without screenshots and screen recordings. */
    private fun query(uri: Uri, since: Long): List<Media> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.SIZE} > 0 AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/%' AND " +
            // Samsung and Xiaomi save them in DCIM/Screenshots, DCIM/Screen recordings and DCIM/ScreenRecorder.
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE 'DCIM/Screen%'"
        val out = ArrayList<Media>()
        val cursor: Cursor = applicationContext.contentResolver.query(
            uri, projection, selection, arrayOf(since.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        ) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                val id = c.getLong(0)
                out += Media(id, ContentUris.withAppendedId(uri, id), path, c.getString(2) ?: File(path).name, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /**
     * Media that is not in the cloud yet, with the name to upload it under. Media in [handled]
     * was uploaded or skipped by an earlier run. Without that record (before the first run),
     * media up to [marker] counts as handled. Other media up to [marker] is new to this app
     * (published late), but is skipped when a copy is in the Rubbish bin.
     */
    private fun selectNew(
        media: List<Media>, folder: Long, keepNames: Boolean, marker: Long, handled: Set<String>?, inQueue: Set<String>,
    ): Selection {
        val api = MegaGlassApp.graph.mega.api
        val parent = api.getNodeByHandle(folder) ?: return Selection(emptyList(), emptyList(), emptyList(), emptyList())
        val used = HashSet<String>()
        val out = ArrayList<Upload>()
        val skipped = ArrayList<Media>()
        val unreadable = ArrayList<Media>()
        val busy = ArrayList<Media>()
        for (m in media) {
            if (handled?.contains(m.key) ?: (m.added <= marker)) { skipped += m; continue }
            // A MediaStore row of a deleted file: there is nothing to upload.
            if (!File(m.path).exists()) { skipped += m; continue }
            val source = redacted(m)
            if (source == null) { unreadable += m; continue }
            // In the queue from an earlier run.
            if (source in inQueue) { busy += m; continue }
            // The fingerprint of what is uploaded: without the location data.
            val fingerprint = api.getFingerprint(source)
            if (fingerprint == null) { unreadable += m; continue }
            // Earlier versions uploaded the original file, which has another fingerprint. The file
            // is read only on this device for it.
            val existing = api.getNodeByFingerprint(fingerprint, parent)
                ?: if (File(m.path).canRead()) api.getFingerprint(m.path)?.let { api.getNodeByFingerprint(it, parent) } else null
            if (existing != null && (m.added <= marker || !api.isInRubbish(existing))) { skipped += m; continue }
            var name = if (keepNames) m.name else datedName(m)
            // A name that is in the folder already gets a suffix: with the same name the upload
            // would replace that file (or make it a hidden version). Two items taken in the same
            // second, or two cameras with the same file names, are different files.
            var n = 1
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            while (name in used || api.getChildNode(parent, name) != null) {
                name = "${base}_$n" + if (ext.isNotEmpty()) ".$ext" else ""
                n++
            }
            used += name
            out += Upload(m, source, name)
        }
        return Selection(out, skipped, unreadable, busy)
    }

    /**
     * A URI of [m] that Android always reads without the location data (photo EXIF, video
     * metadata). A path read, or a plain MediaStore URI, keeps it for an app with all-files access.
     */
    private fun redacted(m: Media): String? =
        catching { MediaStore.getRedactedUri(applicationContext.contentResolver, m.uri) }.getOrNull()?.toString()

    /** "2026-10-14 13.05.22.jpg", the naming scheme of MEGA camera uploads. */
    private fun datedName(m: Media): String {
        val time = if (m.taken > 0) m.taken else m.added * 1000
        val ext = m.name.substringAfterLast('.', "jpg").lowercase()
        return SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(time)) + "." + ext
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.cu_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_photo_camera)
            .setContentTitle(context.getString(R.string.cu_notification_title))
            .setContentText(if (total > 0) context.getString(R.string.cu_progress, done, total) else context.getString(R.string.cu_scanning))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL = "camera_uploads"
        const val NOTIFICATION_ID = 2
        const val SESSION_TIMEOUT_MS = 90_000L
        /** Six hours, in seconds. */
        const val RESCAN_WINDOW_S = 6 * 60 * 60L
        val CONSTRAINT_STOPS = setOf(
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
        )
        /** Long uploads continue in the SDK queue after this; the next run checks again. */
        const val UPLOAD_TIMEOUT_MS = 5 * 60 * 60 * 1000L
    }
}
