package app.megaglass.android.core.camera

import app.megaglass.android.core.util.catching

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.megaglass.android.AppGraph
import app.megaglass.android.core.mega.Mega
import app.megaglass.android.core.mega.SessionState
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.transfers.Direction
import app.megaglass.android.core.transfers.Purpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import nz.mega.sdk.MegaError
import java.time.Duration
import java.util.concurrent.TimeUnit

/** What camera uploads are doing now. */
@Immutable
sealed interface CuStatus {
    data object Off : CuStatus
    data object NeedsPermission : CuStatus
    /** Enabled, waiting for the next run (network, charging, or new media). */
    data class Idle(val lastRun: Long) : CuStatus
    data object Scanning : CuStatus
    data class Uploading(val done: Int, val total: Int) : CuStatus
    data class Failed(val message: String) : CuStatus
}

/**
 * Schedules the camera uploads worker and keeps its status.
 * Runs: soon after new photos or videos appear (MediaStore content trigger), on demand
 * ("Upload now"), and every 6 hours as a safety net (MEGAGLASS-PERF-001). The worker itself
 * is [CameraUploadWorker].
 */
object CameraUploads {

    private const val PERIODIC = "cu-periodic"
    private const val TRIGGER = "cu-trigger"
    private const val NOW = "cu-now"
    internal const val KEY_TRIGGERED = "triggered"

    private val _status = MutableStateFlow<CuStatus>(CuStatus.Off)
    val status: StateFlow<CuStatus> = _status.asStateFlow()

    /** The periodic, trigger and "upload now" workers can start together; only one scans and uploads at a time. */
    internal val running = Mutex()

    internal fun setStatus(status: CuStatus) {
        _status.value = status
    }

    /** Starts or stops the schedule when the settings or the session change. Call once at startup. */
    fun install(graph: AppGraph) {
        val context = graph.context
        val prefs = CuPrefs(context)
        graph.scope.launch {
            combine(graph.settings.state, graph.session.state) { s, session -> s to (session == SessionState.Ready) }
                .distinctUntilChanged { a, b ->
                    a.second == b.second && a.first.cameraUploads == b.first.cameraUploads &&
                        a.first.cuWifiOnly == b.first.cuWifiOnly && a.first.cuRequireCharging == b.first.cuRequireCharging &&
                        a.first.cuIncludeVideos == b.first.cuIncludeVideos
                }
                .collect { (s, ready) ->
                    when {
                        !s.cameraUploads -> {
                            cancel(context)
                            _status.value = CuStatus.Off
                        }
                        !hasPermission(context, s.cuIncludeVideos) -> _status.value = CuStatus.NeedsPermission
                        ready -> {
                            schedule(context, s)
                            if (_status.value !is CuStatus.Uploading && _status.value != CuStatus.Scanning) {
                                _status.value = CuStatus.Idle(prefs.lastRun)
                            }
                        }
                        _status.value == CuStatus.Off -> _status.value = CuStatus.Idle(prefs.lastRun)
                    }
                }
        }
        // Uploads can outlive their worker (time limit, changed settings) or run without one
        // (resumed by the SDK after a restart). They also stop when Wi-Fi or charging is lost;
        // the next run queues them again.
        graph.scope.launch {
            graph.transfers.state.sample(POLICY_CHECK_MS).collect { state ->
                if (state.active.none { it.direction == Direction.UPLOAD && it.purpose == Purpose.CAMERA }) return@collect
                val s = graph.settings.current
                if (!s.cameraUploads || (s.cuWifiOnly && isMetered(context)) || (s.cuRequireCharging && !isPluggedIn(context))) {
                    graph.transfers.cancelUploads(Purpose.CAMERA)
                }
            }
        }
    }

    private const val POLICY_CHECK_MS = 5_000L
    private const val SAFETY_NET_HOURS = 6L

    /** Without a network no upload runs, and the next network decides. */
    private fun isMetered(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.activeNetwork != null && connectivity.isActiveNetworkMetered
    }

    private fun isPluggedIn(context: Context) =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    fun hasPermission(context: Context, videos: Boolean): Boolean {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        return images && (!videos || granted(Manifest.permission.READ_MEDIA_VIDEO))
    }

    private fun constraints(s: AppSettings, triggers: Boolean, batteryNotLow: Boolean = false): Constraints {
        val b = Constraints.Builder()
            .setRequiredNetworkType(if (s.cuWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.cuRequireCharging)
            .setRequiresBatteryNotLow(batteryNotLow)
        if (triggers) {
            b.addContentUriTrigger(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            if (s.cuIncludeVideos) b.addContentUriTrigger(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            b.setTriggerContentUpdateDelay(Duration.ofSeconds(10))
            b.setTriggerContentMaxDelay(Duration.ofMinutes(2))
        }
        return b.build()
    }

    fun schedule(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        // Safety net only: the trigger work finds new media. Each run is a full SDK login, so it
        // runs seldom and not on a low battery (MEGAGLASS-PERF-001).
        val periodic = PeriodicWorkRequestBuilder<CameraUploadWorker>(SAFETY_NET_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(s, triggers = false, batteryNotLow = true))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        // Replace the trigger work only when its constraints change: a running upload
        // (which may have started this process) must not be cancelled.
        val prefs = CuPrefs(context)
        val key = "${s.cuWifiOnly}:${s.cuRequireCharging}:${s.cuIncludeVideos}"
        scheduleTrigger(context, s, if (prefs.scheduleKey == key) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
        prefs.scheduleKey = key
    }

    /** One-shot work that waits for new media. The worker enqueues it again after each run. */
    internal fun scheduleTrigger(context: Context, s: AppSettings, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val trigger = OneTimeWorkRequestBuilder<CameraUploadWorker>()
            .setConstraints(constraints(s, triggers = true))
            .setInputData(workDataOf(KEY_TRIGGERED to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, policy, trigger)
    }

    /** Runs a scan and upload now (expedited when quota allows). */
    fun uploadNow(context: Context, s: AppSettings) {
        val now = OneTimeWorkRequestBuilder<CameraUploadWorker>()
            .setConstraints(constraints(s, triggers = false))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, now)
    }

    /** Stops all camera uploads work and forgets the scan state (it belongs to the account). */
    fun cancel(context: Context) {
        CuPrefs(context).reset()
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(TRIGGER)
        wm.cancelUniqueWork(NOW)
    }

    /**
     * The destination folder: the "camera uploads" user attribute if it points to a usable folder,
     * otherwise a "Camera uploads" folder in Cloud drive (created when missing).
     */
    suspend fun ensureFolder(mega: Mega, defaultName: String): Long? = withContext(Dispatchers.IO) {
        val api = mega.api
        val (code, request) = api.awaitResult { getCameraUploadsFolder(it) }
        if (code == MegaError.API_OK) {
            val node = api.getNodeByHandle(request.nodeHandle)
            if (node != null && node.isFolder && !api.isInRubbish(node)) return@withContext node.handle
        }
        val root = api.rootNode ?: return@withContext null
        val existing = api.getChildNode(root, defaultName)?.takeIf { it.isFolder }
        val handle = existing?.handle ?: api.await { createFolder(defaultName, root, it) }.nodeHandle
        catching { api.await { setCameraUploadsFolder(handle, it) } }
        handle
    }

    /** Saves a new destination folder in the user attribute (shared with other MEGA apps). */
    suspend fun setFolder(mega: Mega, handle: Long) = withContext(Dispatchers.IO) {
        mega.api.await { setCameraUploadsFolder(handle, it) }
        Unit
    }

    /** The current destination folder, without creating one. */
    suspend fun currentFolder(mega: Mega): Long? = withContext(Dispatchers.IO) {
        val api = mega.api
        val (code, request) = api.awaitResult { getCameraUploadsFolder(it) }
        if (code != MegaError.API_OK) return@withContext null
        api.getNodeByHandle(request.nodeHandle)?.takeIf { it.isFolder && !api.isInRubbish(it) }?.handle
    }
}

/** Small private state of camera uploads: scan marker and last run. */
internal class CuPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("camera_uploads", Context.MODE_PRIVATE)

    /** MediaStore DATE_ADDED (seconds) of the newest media that is uploaded or skipped. */
    var marker: Long
        get() = prefs.getLong("marker", 0)
        set(value) = prefs.edit().putLong("marker", value).apply()

    /**
     * Media ("dateAdded:id") in the rescan window that is uploaded or skipped already; null
     * before the first run that records it.
     */
    var handled: Set<String>?
        get() = prefs.getStringSet("handled", null)?.toSet()
        set(value) = prefs.edit().putStringSet("handled", value).apply()

    /** Time (ms) of the last complete run. */
    var lastRun: Long
        get() = prefs.getLong("last_run", 0)
        set(value) = prefs.edit().putLong("last_run", value).apply()

    /** Settings value cuSince that [marker] belongs to; a change starts the scan again. */
    var markerSince: Long
        get() = prefs.getLong("marker_since", -1)
        set(value) = prefs.edit().putLong("marker_since", value).apply()

    /** Constraint settings of the scheduled trigger work. */
    var scheduleKey: String
        get() = prefs.getString("schedule_key", "") ?: ""
        set(value) = prefs.edit().putString("schedule_key", value).apply()

    fun reset() = prefs.edit().clear().apply()
}
