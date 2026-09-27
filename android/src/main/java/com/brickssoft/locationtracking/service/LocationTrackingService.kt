package com.brickssoft.locationtracking.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The location foreground service (type `location`). It keeps the process in a foreground state while tracking is
 * enabled; the engine does the actual tracking.
 *
 * - [onStartCommand] enters the foreground immediately, because Android requires `startForeground` within about
 *   5 s of `startForegroundService`. If that fails (for example a background start on Android 12+, or no location
 *   permission on Android 14+), the failure is logged and the service stops.
 * - It returns `START_STICKY`. A null intent means the system restarted the service after the process died:
 *   tracking is restored with `engine.restore("restore")` if it is still enabled; otherwise the service stops.
 * - An [ACTION_STOP] command stops the service. It is queued behind any pending start, so the service never stops
 *   before `startForeground`, which would make the system crash the app.
 * - [onTaskRemoved] forwards to `engine.onTerminate()`, which applies `app.stopOnTerminate`.
 * - While running, changes to the notification config are applied to the posted notification.
 */
class LocationTrackingService : Service() {
    private var deps: ServiceDeps? = null
    private var configJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) { isRunning = true }
        Logger.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Not a startForegroundService() command: no startForeground needed. A newer start keeps it running.
            Logger.i(TAG, "stop requested")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val deps = deps()
        val config = deps?.configStore?.config?.value?.notification ?: NotificationConfig()
        val failure = enterForeground(config)
        if (failure != null || deps == null) {
            // start() already returned true to the engine; tell it, so tracking ends with an audit record instead
            // of looking enabled while no location can be collected in the background.
            if (failure != null && deps != null) {
                val error = "${failure.javaClass.simpleName}: ${failure.message}"
                deps.launchEngine(TAG, "onServiceStartFailed") { onServiceStartFailed(error) }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A config change racing with startForeground may have been overwritten by the older notification.
        if (deps.configStore.config.value.notification != config) refresh(this, deps.configStore)
        if (intent == null) {
            // Null intent: the system restarted the service after the process died (START_STICKY).
            if (!deps.configStore.runtime.value.enabled) {
                Logger.i(TAG, "restarted by the system while tracking is disabled; stopping")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            Logger.i(TAG, "restarted by the system; restoring tracking")
            deps.launchEngine(TAG, "restore") { restore(REASON_RESTORE) }
        }
        if (configJob == null) configJob = watchNotificationConfig(deps)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.i(TAG, "task removed")
        deps()?.launchEngine(TAG, "onTerminate") { onTerminate() }
    }

    override fun onDestroy() {
        configJob?.cancel()
        configJob = null
        synchronized(lock) {
            isRunning = false
            shownConfig = null
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

    private fun deps(): ServiceDeps? {
        deps?.let { return it }
        return try {
            ServiceDeps.from(this).also { deps = it }
        } catch (e: Exception) {
            Logger.e(TAG, "components unavailable", e)
            null
        }
    }

    /** Calls `startForeground` with the configured notification (or the default one). Returns the failure, never throws. */
    private fun enterForeground(config: NotificationConfig): Exception? = try {
        val notification = NotificationFactory(this).buildSafely(config)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID, notification, type)
        synchronized(lock) { shownConfig = config }
        null
    } catch (e: Exception) {
        Logger.e(TAG, "startForeground failed; stopping the service", e)
        e
    }

    /** Re-posts the notification whenever the notification config changes. */
    private fun watchNotificationConfig(deps: ServiceDeps): Job = deps.scope.launch {
        val context: Context = this@LocationTrackingService
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

    companion object {
        private const val TAG = "LT.Service"
        private const val REASON_RESTORE = "restore"

        /** Command that stops the service after any start queued before it. */
        internal const val ACTION_STOP = "com.brickssoft.locationtracking.service.STOP"

        private val lock = Any()

        /** True between the service's `onCreate` and `onDestroy`. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** The config of the notification currently shown; guarded by [lock]. */
        private var shownConfig: NotificationConfig? = null

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
            }
            Logger.d(TAG, "notification updated")
            return true
        }
    }
}
