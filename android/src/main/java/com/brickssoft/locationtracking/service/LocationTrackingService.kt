package com.brickssoft.locationtracking.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The location foreground service (type `location`). It keeps the process in a foreground state while tracking is
 * enabled; the engine does the actual tracking.
 *
 * **Foreground first.** Android requires `startForeground` within a few seconds of `startForegroundService` (the limit
 * is 5 to 10 s, depending on the Android version), also when the main thread is busy; otherwise it crashes the app with
 * `ForegroundServiceDidNotStartInTimeException`. So every start command (and a restart with a null intent) calls
 * `startForeground` before anything touches `Components`, the config store, SharedPreferences, SQLite or JSON. That
 * first notification ([NotificationFactory.buildInitial]) is built from the notification fields the start command
 * carries (channel, priority, title, text, icon, color, copied by the sender from its config) or, for a null intent,
 * from constants and resources; when the service is already in the foreground, the notification it shows is passed
 * again instead. The location service type
 * is passed on API 29+. Only after `startForeground` has returned does the service load its dependencies, on a
 * background coroutine ([AppDispatchers.io], one step at a time), and replace the notification with the configured one
 * (same notification id).
 *
 * **Commands** (the state machine is described in [ServiceCommands]):
 * - A start sent by this process: enter the foreground; if a stop was requested after it (and no start after that
 *   stop), `stopSelf(startId)`; otherwise show the configured notification and follow notification config changes.
 * - A start sent by an earlier process (it died between `startForegroundService` and `onStartCommand`, and Android
 *   delivered the command to the restarted service): enter the foreground, then `engine.restore("restore")` if tracking
 *   is enabled (the engine does nothing when a session already runs), or `stopSelf(startId)` if it is not.
 * - A null intent (`START_STICKY` restart after the process died): enter the foreground, then `engine.restore("restore")`
 *   if tracking is enabled, or `stopSelf(startId)` if it is not.
 * - A stop ([ACTION_STOP], sent with `startService`, so it arrives after every start sent before it): ignored if a
 *   start was sent after it; otherwise `stopSelf(startId)` (Android ignores that when a newer command was sent). A stop
 *   marked as sent with `startForegroundService` first enters the foreground if the service is not in it yet.
 *
 * **Failures.** If `startForeground` throws (for example a background start on Android 12+, or Android 14+ with only
 * while-in-use location permission), the failure is logged, the service stops, and (for start commands) the engine
 * learns about it through `onServiceStartFailed`, which ends a running or enabled session with the `tracking_stop`
 * reason `service_start_failed` (`permission_denied` when foreground location permission is no longer granted).
 *
 * [onTaskRemoved] forwards to `engine.onTerminate()`, which applies `app.stopOnTerminate`.
 */
class LocationTrackingService : Service() {
    @Volatile
    private var deps: ServiceDeps? = null

    /** Guards [configJob] and [destroyed]. */
    private val instanceLock = Any()
    private var configJob: Job? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Logger.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = ServiceCommand.parse(intent)
        return if (command.kind == ServiceCommand.Kind.STOP) onStopCommand(command, startId) else onStart(command, startId)
    }

    private fun onStart(command: ServiceCommand, startId: Int): Int {
        val failure = enterForeground(command.notification)
        if (command.fromThisProcess) ServiceCommands.startHandled(command.seq)
        if (failure != null) {
            stopSelf(startId)
            // start() already returned true to the engine; tell it, so tracking ends with an audit record instead of
            // looking enabled while no location can be collected in the background.
            val error = "${failure.javaClass.simpleName}: ${failure.message}"
            offMain("reporting the start failure") { deps ->
                deps.launchEngine(TAG, "onServiceStartFailed") { onServiceStartFailed(error) }
            }
            return START_NOT_STICKY
        }
        if (command.fromThisProcess && ServiceCommands.isStoppedAfter(command.seq)) {
            // Stop racing start: the service entered the foreground, so it may stop now. If the stop command is still
            // queued, Android ignores this stopSelf (the stop command has a newer start id) and the stop command stops it.
            Logger.i(TAG, "a stop was requested after this start; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        offMain("starting", onUnavailable = { stopSelf(startId) }) { deps -> afterForeground(deps, command, startId) }
        return START_STICKY
    }

    private fun onStopCommand(command: ServiceCommand, startId: Int): Int {
        if (command.foregroundRequired && !isRunning) {
            // Sent with startForegroundService(): Android requires startForeground before the service stops. The
            // plugin's controller sends stops with startService(); this guard keeps any other sender safe.
            enterForeground(null)
        }
        if (command.fromThisProcess && ServiceCommands.isStartedAfter(command.seq)) {
            Logger.d(TAG, "stop ignored: a start was sent after it")
            return START_STICKY
        }
        Logger.i(TAG, "stop requested")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Runs off the main thread after `startForeground` succeeded for a start command. */
    private fun afterForeground(deps: ServiceDeps, command: ServiceCommand, startId: Int) {
        if (!command.fromThisProcess) {
            val origin = if (command.kind == ServiceCommand.Kind.RESTART) {
                "restarted by the system"
            } else {
                "start command from an earlier process"
            }
            if (!deps.configStore.runtime.value.enabled) {
                Logger.i(TAG, "$origin while tracking is disabled; stopping")
                stopSelf(startId)
                return
            }
            Logger.i(TAG, "$origin; restoring tracking")
            // Does nothing if a session already runs in this process.
            deps.launchEngine(TAG, "restore") { restore(REASON_RESTORE) }
        }
        refresh(this, deps.configStore)
        watchNotificationConfig(deps)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.i(TAG, "task removed")
        offMain("task removal") { deps -> deps.launchEngine(TAG, "onTerminate") { onTerminate() } }
    }

    override fun onDestroy() {
        synchronized(instanceLock) {
            destroyed = true
            configJob?.cancel()
            configJob = null
        }
        synchronized(lock) {
            isRunning = false
            shownConfig = null
            shownNotification = null
        }
        // The system removes the foreground notification before onDestroy; drop a copy that a concurrent refresh
        // may have re-posted in between. No refresh can post after isRunning is false.
        try {
            NotificationManagerCompat.from(this).cancel(Constants.NOTIFICATION_ID)
        } catch (e: Exception) {
            Logger.w(TAG, "could not cancel the notification", e)
        }
        Logger.d(TAG, "destroyed")
        super.onDestroy()
    }

    /**
     * Calls `startForeground` (location type on API 29+) with the notification already shown, or with the initial
     * notification ([NotificationFactory.buildInitial] of [spec], the notification fields carried by the command).
     * Sets [isRunning] on success and clears it on failure. Returns the failure, never throws.
     */
    private fun enterForeground(spec: NotificationConfig?): Exception? = try {
        val shown = synchronized(lock) { if (isRunning) shownNotification else null }
        val initial = if (shown == null) NotificationFactory(this).buildInitial(spec) else null
        val notification = initial?.notification ?: checkNotNull(shown)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID, notification, type)
        synchronized(lock) {
            isRunning = true
            if (initial != null) {
                shownConfig = initial.config
                shownNotification = initial.notification
            }
        }
        null
    } catch (e: Exception) {
        synchronized(lock) {
            isRunning = false
            shownConfig = null
            shownNotification = null
        }
        Logger.e(TAG, "startForeground failed; stopping the service", e)
        e
    }

    /** Re-posts the notification whenever the notification config changes (once per service instance). */
    private fun watchNotificationConfig(deps: ServiceDeps) {
        synchronized(instanceLock) {
            if (destroyed || configJob != null) return
            val context: Context = this
            configJob = deps.scope.launch {
                deps.configStore.config
                    .map { it.notification }
                    .distinctUntilChanged()
                    .collect {
                        try {
                            refresh(context, deps.configStore)
                        } catch (e: Exception) {
                            Logger.w(TAG, "could not update the notification", e)
                        }
                    }
            }
        }
    }

    /**
     * Runs [block] with the dependencies on the background dispatcher. The dependencies are resolved there on first
     * use (this loads `Components`). If they cannot be resolved, the failure is logged and [onUnavailable] runs.
     */
    private fun offMain(what: String, onUnavailable: () -> Unit = {}, block: suspend (ServiceDeps) -> Unit) {
        backgroundScope.launch(dispatcherOverride ?: defaultDispatcher) {
            val resolved = deps ?: try {
                ServiceDeps.from(this@LocationTrackingService).also { deps = it }
            } catch (e: Exception) {
                Logger.e(TAG, "components unavailable ($what)", e)
                null
            }
            if (resolved == null) {
                onUnavailable()
                return@launch
            }
            try {
                block(resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "$what failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "LT.Service"
        private const val REASON_RESTORE = "restore"

        /** Command that stops the service after any start queued before it. */
        internal const val ACTION_STOP = "com.brickssoft.locationtracking.service.STOP"

        private val lock = Any()

        /**
         * True while the service is in the foreground: set after `startForeground` succeeded, cleared in `onDestroy`
         * and when `startForeground` fails. [DefaultServiceController.isRunning] reports it.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** The config of the notification currently shown; guarded by [lock]. */
        private var shownConfig: NotificationConfig? = null

        /** The notification currently shown; guarded by [lock]. */
        private var shownNotification: Notification? = null

        /** Test hook: the dispatcher of the service's background steps instead of [defaultDispatcher]. */
        @VisibleForTesting
        @Volatile
        internal var dispatcherOverride: CoroutineDispatcher? = null

        /** One background step at a time, in the order the commands arrived. */
        private val defaultDispatcher: CoroutineDispatcher by lazy { AppDispatchers.DEFAULT.io.limitedParallelism(1) }

        /** Outlives service instances, so a failure report is not cancelled by the service's own `onDestroy`. */
        private val backgroundScope = CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, t -> Logger.e(TAG, "uncaught", t) },
        )

        /**
         * Re-posts the notification from the current notification config of [configStore], if the service is
         * running and that config differs from the one shown. Returns true if it posted.
         *
         * Nothing is posted once the service is destroyed, so no notification can outlive it. If the config changes
         * while the notification is being built, the stale one is dropped; the service's config watcher posts the
         * newer one.
         */
        @SuppressLint("MissingPermission") // without POST_NOTIFICATIONS the system hides it; nothing to handle
        internal fun refresh(context: Context, configStore: ConfigStore): Boolean {
            val config = configStore.config.value.notification
            synchronized(lock) { if (!isRunning || config == shownConfig) return false }
            val notification = NotificationFactory(context).buildSafely(config)
            synchronized(lock) {
                if (!isRunning || config == shownConfig || config != configStore.config.value.notification) return false
                NotificationManagerCompat.from(context).notify(Constants.NOTIFICATION_ID, notification)
                shownConfig = config
                shownNotification = notification
            }
            Logger.d(TAG, "notification updated")
            return true
        }
    }
}
