package com.brickssoft.locationtracking.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent

/** Turns a tap on a notification action button into a `notificationaction` event (`{ id }`). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val events = try {
            ServiceDeps.from(context).events
        } catch (e: Exception) {
            Logger.e(TAG, "components unavailable", e)
            return
        }
        dispatch(intent, events)
    }

    internal companion object {
        private const val TAG = "LT.NotificationAction"

        /** Emits [TrackingEvent.NotificationAction] for a valid action intent; returns false otherwise. */
        fun dispatch(intent: Intent, events: EventBus): Boolean {
            if (intent.action != Constants.ACTION_NOTIFICATION_ACTION) {
                Logger.w(TAG, "unexpected action ${intent.action}")
                return false
            }
            val id = intent.getStringExtra(Constants.EXTRA_ACTION_ID)
            if (id.isNullOrEmpty()) {
                Logger.w(TAG, "notification action without an id")
                return false
            }
            Logger.d(TAG, "notification action '$id'")
            events.emit(TrackingEvent.NotificationAction(id))
            return true
        }
    }
}
