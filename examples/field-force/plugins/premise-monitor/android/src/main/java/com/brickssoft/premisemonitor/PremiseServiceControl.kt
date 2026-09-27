package com.brickssoft.premisemonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Starts and stops [PremiseMonitorService]; an interface so tests can simulate a refused start. */
internal interface PremiseServiceControl {
    /** True from the service's `onCreate` until its `onDestroy`. */
    val isRunning: Boolean

    /**
     * True when the last command sent was a start and the service is running or that start is still on its way
     * (no stop was requested after it). False after a stop request, even while the stop is still queued.
     */
    val isRunningOrStarting: Boolean

    /** Sends a start command; returns the exception if the OS refused it (never throws). */
    fun start(context: Context, premiseId: String, reason: String): Throwable?

    /** Sends a stop command; [reason] becomes the `detail` of `service_stopped`. No-op if not running or starting. */
    fun stop(context: Context, reason: String)
}

/**
 * The real [PremiseServiceControl], the same approach as the tracking plugin's service controller. Called only on
 * PremiseMonitor's `PM-native` thread.
 * - start: `startForegroundService`. On Android 14+ a location foreground service needs a location permission, so
 *   without one the start is refused here (a `SecurityException`) instead of failing inside the service.
 * - stop: an [PremiseMonitorService.ACTION_STOP] command through `startService`, queued behind a pending start (stopping
 *   a service before it called `startForeground` crashes the app); `stopService` if the OS refuses the command.
 * - Commands are delivered in the order they were sent, and `stopSelf(startId)` does not stop the service while a
 *   newer start is queued, so a start sent after a stop keeps the service running.
 */
internal object AndroidServiceControl : PremiseServiceControl {
    /** The last command sent was a start (false after a stop). */
    @Volatile
    private var lastCommandStart = false

    override val isRunning: Boolean get() = PremiseMonitorService.isRunning

    override val isRunningOrStarting: Boolean
        get() = lastCommandStart && (PremiseMonitorService.isRunning || PremiseMonitorService.pendingStarts.get() > 0)

    override fun start(context: Context, premiseId: String, reason: String): Throwable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasLocationPermission(context)) {
            return SecurityException("no location permission: Android 14+ refuses a location foreground service")
        }
        val intent = Intent(context, PremiseMonitorService::class.java)
            .setAction(PremiseMonitorService.ACTION_START)
            .putExtra(PremiseMonitorService.EXTRA_PREMISE_ID, premiseId)
            .putExtra(PremiseMonitorService.EXTRA_REASON, reason)
        PremiseMonitorService.pendingStarts.incrementAndGet()
        return try {
            ContextCompat.startForegroundService(context, intent)
            lastCommandStart = true
            null
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+, background start) or IllegalStateException.
            PremiseMonitorService.pendingStarts.decrementAndGet()
            e
        }
    }

    override fun stop(context: Context, reason: String) {
        lastCommandStart = false
        if (!PremiseMonitorService.isRunning && PremiseMonitorService.pendingStarts.get() <= 0) return
        val command = Intent(context, PremiseMonitorService::class.java)
            .setAction(PremiseMonitorService.ACTION_STOP)
            .putExtra(PremiseMonitorService.EXTRA_REASON, reason)
        try {
            context.startService(command)
            return
        } catch (e: Exception) {
            PmLog.i(TAG, "stop command refused (${e.javaClass.simpleName}); calling stopService")
        }
        try {
            context.stopService(Intent(context, PremiseMonitorService::class.java))
        } catch (e: Exception) {
            PmLog.w(TAG, "could not stop the service", e)
        }
    }

    /** Test support: forgets the last command. */
    internal fun reset() {
        lastCommandStart = false
    }

    private fun hasLocationPermission(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private const val TAG = "PM.ServiceControl"
}
