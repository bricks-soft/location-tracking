package com.brickssoft.locationtracking.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.SystemClockImpl

/**
 * Starts and stops [LocationTrackingService]. The start/stop state machine is described in [ServiceCommands].
 *
 * - [start] sends a start command with `startForegroundService` (`startService` below API 26). It returns false
 *   (and logs), without sending anything, when:
 *   - Android 14+ and no location permission is granted (the service could not enter the foreground);
 *   - Android 14+, the service is not in the foreground, no background location permission and no visible activity
 *     ([WhileInUseStartCheck]): Android would throw `SecurityException` from `startForeground`, because while-in-use
 *     location access does not apply in the background. (A start while the service is already in the foreground
 *     keeps the service's while-in-use access, so it is not checked.)
 *
 *   It also returns false when Android refuses the start, for example `ForegroundServiceStartNotAllowedException` for a
 *   background start on Android 12+, or when the service is not found. When a start is already pending (sent, not yet
 *   in the foreground) and no stop was requested after it, it returns true without sending a second start.
 *
 *   The engine treats false as before: `start()` records `tracking_stop` with reason `permission_denied` and rejects
 *   with `PERMISSION_DENIED`; `restore()` records `tracking_stop` with reason `service_start_failed`.
 * - [stop] sends a stop command with `startService`, so the stop is handled after every start sent before it (each of
 *   which enters the foreground first). If Android refuses the stop command, `stopService` is used only when no start
 *   is pending; while a start is pending, the service stops itself right after it has entered the foreground. So
 *   `stopService` never races a pending start (that race makes the system crash the app with
 *   `ForegroundServiceDidNotStartInTimeException`).
 * - Each accepted start records the current boot count in [BootCountStore] (read by [BootReceiver]'s boot count gate).
 * - [isRunning] is true while the service is in the foreground: from a successful `startForeground` until the
 *   service is destroyed or `startForeground` fails.
 * - [refreshNotification] re-posts the notification from the current config while the service runs.
 */
class DefaultServiceController(
    context: Context,
    private val configStore: ConfigStore,
    @Suppress("unused") private val events: EventBus,
    private val clock: Clock = SystemClockImpl(context),
) : ServiceController {
    private val context: Context = context.applicationContext ?: context
    private val whileInUse = WhileInUseStartCheck(this.context, clock)
    private val bootCounts = BootCountStore(this.context)

    /** Serializes [start] and [stop], so the sequence numbers reach Android in order. */
    private val sendLock = Any()

    override val isRunning: Boolean get() = LocationTrackingService.isRunning

    override fun start(): Boolean = synchronized(sendLock) { startLocked() }

    private fun startLocked(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasLocationPermission()) {
            Logger.w(TAG, "no location permission: Android 14+ does not allow a location foreground service")
            return false
        }
        if (ServiceCommands.isStartPendingAndLatest) {
            Logger.d(TAG, "a start is already pending; not sending another")
            return true
        }
        if (!isRunning) {
            whileInUse.refusalReason()?.let { reason ->
                Logger.w(TAG, "$reason: the location foreground service would be refused; not starting it")
                return false
            }
        }
        val seq = ServiceCommands.nextSeq()
        val command = ServiceCommands.stamp(serviceIntent(), seq, foregroundRequired = true)
        ServiceCommands.putNotification(command, configStore.config.value.notification)
        return try {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(command)
            } else {
                context.startService(command)
            }
            if (component == null) {
                // Never delivered, so it must not count as pending.
                Logger.e(TAG, "the foreground service was not found (is it declared in the manifest?)")
                return false
            }
            ServiceCommands.startSent(seq)
            markServiceStarted()
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
        synchronized(sendLock) { stopLocked() }
    }

    private fun stopLocked() {
        val seq = ServiceCommands.nextSeq()
        ServiceCommands.stopRequested(seq)
        val stopCommand =
            ServiceCommands.stamp(serviceIntent().setAction(LocationTrackingService.ACTION_STOP), seq, foregroundRequired = false)
        try {
            context.startService(stopCommand)
            return
        } catch (e: Exception) {
            // IllegalStateException: an app in the background may not start a service that is not running.
            Logger.d(TAG, "stop command refused (${e.javaClass.simpleName})")
        }
        if (ServiceCommands.isStartPending) {
            Logger.i(TAG, "a start is pending: the service stops itself after it has entered the foreground")
            return
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

    /** Lets [BootReceiver] ignore a later boot broadcast of this boot (a session already ran in this boot). */
    private fun markServiceStarted() {
        try {
            val bootCount = clock.bootCount()
            if (bootCount >= 0) bootCounts.setServiceStarted(bootCount)
        } catch (e: Exception) {
            Logger.w(TAG, "could not record the boot count of this start", e)
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
