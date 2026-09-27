package com.brickssoft.premisemonitor

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * PremiseMonitor's own foreground service (type `location`, channel `premise_monitor`, notification
 * "Inside premise <id>"). It runs while the device is inside the monitored premise: started on ENTER (or by the
 * listener in a new process while `inside` is persisted), stopped on EXIT, `stopMonitoring()` or a `tracking_stop`.
 *
 * - [onStartCommand] calls `startForeground` first, before any other work, because Android requires it within
 *   about 5 s of `startForegroundService` (`ForegroundServiceDidNotStartInTimeException` otherwise). A refused
 *   `startForeground` (for example a background start on Android 14 without while-in-use location access) is
 *   audited as `service_start_failed` and the service stops; it never crashes the app.
 * - It requests no locations: the tracking plugin delivers every fix to [PremiseAuditListener]. The service keeps
 *   the process in a foreground state and shows the user that presence is being audited.
 * - `START_NOT_STICKY`: after a process death the listener restarts it (the tracking plugin restores itself and its
 *   first record reaches the listener), so there is exactly one restart path.
 * - [ACTION_STOP] (sent with `startService`) is queued behind a pending start, so the service never stops before
 *   `startForeground`.
 * - `service_started` is written once per instance after `startForeground` succeeded; `service_stopped` in
 *   [onDestroy], only if it had entered the foreground, with the reason of the last [ACTION_STOP] (`destroyed` when
 *   the service was stopped without one, for example through the `stopService` fallback).
 */
class PremiseMonitorService : Service() {
    private var inForeground = false

    /** The premise of the start command that brought the service into the foreground. */
    private var premiseId: String? = null

    /** Why the service is being stopped (the `service_stopped` detail), from the last [ACTION_STOP] command. */
    private var stopReason: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Not a startForegroundService() command: no startForeground needed. A newer start keeps it running.
            stopReason = intent.getStringExtra(EXTRA_REASON) ?: stopReason
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedPremise = intent?.getStringExtra(EXTRA_PREMISE_ID)
        val failure = enterForeground(requestedPremise)
        if (intent?.action == ACTION_START) pendingStarts.decrementAndGet()
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_RESTART
        if (failure != null) {
            PmLog.w(TAG, "startForeground refused; stopping", failure)
            core()?.onServiceStartFailed(requestedPremise, failure)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopReason = null
        if (!inForeground) {
            inForeground = true
            premiseId = requestedPremise
            core()?.onServiceStarted(requestedPremise, reason)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (inForeground) {
            inForeground = false
            core()?.onServiceStopped(premiseId, stopReason ?: REASON_DESTROYED)
        }
        stopReason = null
        super.onDestroy()
    }

    private fun core(): PremiseMonitorCore? = try {
        PremiseMonitorCore.get(this)
    } catch (e: Exception) {
        PmLog.e(TAG, "PremiseMonitor core unavailable", e)
        null
    }

    /** Returns the failure, never throws. */
    private fun enterForeground(premiseId: String?): Exception? = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(premiseId), type)
        null
    } catch (e: Exception) {
        e
    }

    private fun buildNotification(premiseId: String?): Notification {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.pm_channel_name))
            .setShowBadge(false)
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val title = if (premiseId.isNullOrBlank()) {
            getString(R.string.pm_notification_title_unknown)
        } else {
            getString(R.string.pm_notification_title, premiseId)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.pm_notification_text))
            .setSmallIcon(R.drawable.pm_ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /** Opens the app like the launcher does. */
    private fun contentIntent(): PendingIntent? = try {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.setPackage(null)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "PM.Service"

        const val CHANNEL_ID = "premise_monitor"

        /** Distinct from the tracking plugin's notification (7301). */
        const val NOTIFICATION_ID = 7401

        internal const val ACTION_START = "com.brickssoft.premisemonitor.START"
        internal const val ACTION_STOP = "com.brickssoft.premisemonitor.STOP"
        internal const val EXTRA_PREMISE_ID = "premise_id"
        internal const val EXTRA_REASON = "reason"

        /** `service_started` detail when the system re-delivered a start without our extras. */
        internal const val REASON_RESTART = "restart"

        /** `service_stopped` detail when the service was destroyed without a stop request of ours. */
        internal const val REASON_DESTROYED = "destroyed"

        /** True between the service's `onCreate` and `onDestroy` (in this process). */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            internal set

        /** Start commands sent with `startForegroundService` whose `onStartCommand` has not run yet. */
        internal val pendingStarts = AtomicInteger(0)
    }
}
