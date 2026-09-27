package com.brickssoft.locationtracking.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger

/**
 * Starts and stops [LocationTrackingService].
 *
 * - [start] calls `startForegroundService`. It returns false (and logs) when the OS refuses, for example
 *   `ForegroundServiceStartNotAllowedException` for a background start on Android 12+, or when no location
 *   permission is granted on Android 14+, where the service could not enter the foreground.
 * - [stop] sends [LocationTrackingService.ACTION_STOP] through `startService`, so the stop is queued behind any
 *   pending start. Stopping a service before it has called `startForeground` makes the system crash the app. If
 *   the OS refuses the command (app in the background without a running service), it falls back to `stopService`.
 * - [isRunning] is true between the service's `onCreate` and `onDestroy`.
 * - [refreshNotification] re-posts the notification from the current config while the service runs.
 */
class DefaultServiceController(
    context: Context,
    private val configStore: ConfigStore,
    @Suppress("unused") private val events: EventBus,
) : ServiceController {
    private val context: Context = context.applicationContext ?: context

    override val isRunning: Boolean get() = LocationTrackingService.isRunning

    override fun start(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasLocationPermission()) {
            Logger.w(TAG, "no location permission: Android 14+ does not allow a location foreground service")
            return false
        }
        return try {
            ContextCompat.startForegroundService(context, serviceIntent())
            true
        } catch (e: Exception) {
            if (isForegroundStartNotAllowed(e)) {
                Logger.w(TAG, "the OS refused to start the foreground service from the background", e)
            } else {
                Logger.e(TAG, "could not start the foreground service", e)
            }
            false
        }
    }

    override fun stop() {
        val stopCommand = serviceIntent().setAction(LocationTrackingService.ACTION_STOP)
        try {
            context.startService(stopCommand)
            return
        } catch (e: Exception) {
            // IllegalStateException: background app without a running foreground service.
            Logger.d(TAG, "stop command refused (${e.javaClass.simpleName}); calling stopService")
        }
        try {
            context.stopService(serviceIntent())
        } catch (e: Exception) {
            Logger.w(TAG, "could not stop the foreground service", e)
        }
    }

    override fun refreshNotification() {
        try {
            LocationTrackingService.refresh(context, configStore)
        } catch (e: Exception) {
            Logger.w(TAG, "could not refresh the notification", e)
        }
    }

    private fun serviceIntent(): Intent = Intent(context, LocationTrackingService::class.java)

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    internal companion object {
        private const val TAG = "LT.ServiceController"
        private const val FGS_NOT_ALLOWED = "android.app.ForegroundServiceStartNotAllowedException"

        /**
         * True for `ForegroundServiceStartNotAllowedException` (API 31+). Compared by name: the class does not
         * exist below API 31, so it must not appear in a catch clause or type check.
         */
        fun isForegroundStartNotAllowed(e: Throwable): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e.javaClass.name == FGS_NOT_ALLOWED
    }
}
