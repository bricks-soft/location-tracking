package com.brickssoft.locationtracking.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.service.ServiceController
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Receives the heartbeat PendingIntent alarms (exact and backup). Runs [HeartbeatScheduler.onAlarm] and, if
 * tracking is enabled but the foreground service is gone (the process was killed), restores tracking with
 * `engine.restore("restore")`: together with `START_STICKY` this is how tracking comes back after task killers.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_HEARTBEAT) return
        val trigger = HeartbeatIntents.triggerOf(intent)
        val pending = goAsync()
        try {
            val c = Components.get(context)
            // invokeOnCompletion also finishes the broadcast if the coroutine is cancelled before it starts.
            c.scope.launch {
                deliver(trigger, c.heartbeat, c.configStore, lazy { c.serviceController }, lazy { c.engine })
            }.invokeOnCompletion { pending.finish() }
        } catch (e: Exception) {
            Logger.e(TAG, "heartbeat alarm delivery failed", e)
            pending.finish()
        }
    }

    internal companion object {
        private const val TAG = DefaultHeartbeatScheduler.TAG

        /**
         * The post-alarm decision: run the heartbeat, then restore tracking if it is enabled while the foreground
         * service is not running. Failures are logged; the restore check runs even if the heartbeat failed.
         */
        suspend fun deliver(
            trigger: HeartbeatTrigger,
            heartbeat: HeartbeatScheduler,
            configStore: ConfigStore,
            serviceController: Lazy<ServiceController>,
            engine: Lazy<TrackingEngine>,
        ) {
            try {
                heartbeat.onAlarm(trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "heartbeat alarm ($trigger) failed", e)
            }
            if (!configStore.runtime.value.enabled || serviceController.value.isRunning) return
            Logger.i(TAG, "tracking is enabled but the service is not running; restoring")
            try {
                engine.value.restore("restore")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "restore after heartbeat alarm failed", e)
            }
        }
    }
}
