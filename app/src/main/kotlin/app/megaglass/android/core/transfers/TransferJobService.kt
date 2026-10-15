package app.megaglass.android.core.transfers

import app.megaglass.android.core.util.catching

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.megaglass.android.MainActivity
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * A user-initiated data transfer job (Android 14+). While it runs, the system keeps the process
 * alive with no time limit, so the SDK can finish large transfers in the background.
 * The SDK does the transfers; this job only shows progress and ends when the queue is empty.
 */
class TransferJobService : JobService() {

    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val transfers = MegaGlassApp.graph.transfers
        val notifications = TransferNotifications(this)
        setNotification(params, TransferNotifications.ID, notifications.build(transfers.state.value), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        s.launch {
            // Give the SDK a moment to register the transfers that were just queued.
            transfers.state.sample(1000).collect { state ->
                if (state.isIdle) {
                    jobFinished(params, false)
                    s.cancel()
                    return@collect
                }
                setNotification(params, TransferNotifications.ID, notifications.build(state), JOB_END_NOTIFICATION_POLICY_REMOVE)
                val down = state.active.filter { it.direction == Direction.DOWNLOAD }.sumOf { it.transferred }
                val up = state.active.filter { it.direction == Direction.UPLOAD }.sumOf { it.transferred }
                updateTransferredNetworkBytes(params, down, up)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        scope = null
        // The SDK keeps the queue; the next transfer action schedules a new job.
        return false
    }

    companion object {
        private const val JOB_ID = 1001

        /**
         * Schedules the job. A user-initiated job can only start while the app is visible;
         * when it is not, transfers continue for as long as the process lives.
         */
        fun schedule(context: Context) {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                .build()
            catching { scheduler.schedule(job) }
        }
    }
}

/** The ongoing transfers notification. On Android 16+ it is a Live Update with a progress chip. */
class TransferNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun build(state: TransfersState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_TRANSFERS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = (state.progress * 100).toInt()
        val title = when {
            state.downloads > 0 && state.uploads > 0 ->
                context.getString(R.string.notif_transfers_both, state.downloads, state.uploads)
            state.uploads > 0 -> context.resources.getQuantityString(R.plurals.notif_uploading, state.uploads, state.uploads)
            else -> context.resources.getQuantityString(R.plurals.notif_downloading, state.downloads, state.downloads)
        }
        val speed = state.downloadSpeed + state.uploadSpeed
        val text = buildString {
            append(Format.size(state.transferredBytes)).append(" / ").append(Format.size(state.totalBytes))
            if (speed > 0) append(" · ").append(Format.speed(speed))
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(if (state.uploads > state.downloads) R.drawable.ic_cloud_upload else R.drawable.ic_cloud_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgress(percent).setStyledByProgress(true))
            builder.setShortCriticalText("$percent%")
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                builder.setRequestPromotedOngoing(true)
            }
        } else {
            builder.setProgress(100, percent, state.totalBytes <= 0)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL = "transfers"
        const val ID = 1
    }
}

/** Waits until the transfer queue is empty. */
suspend fun TransferManager.awaitIdle() {
    state.first { it.isIdle }
}
