package com.brickssoft.locationtracking.provider.hms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.huawei.hms.location.Geofence
import com.huawei.hms.location.GeofenceData

/**
 * Delivers HMS geofence transitions to the GeofenceManager (`Components.get(ctx).geofences`, a
 * `GeofenceTransitionSink`). Declared in the manifest; it only fires for the PendingIntent registered by
 * [HmsGeofenceBackend].
 */
class HmsGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val transitions = parseGeofenceTransitions(intent)
        if (transitions.isEmpty()) return
        val components = Components.get(context)
        deliverAsync(goAsync(), components.scope, TAG, "geofence transitions") {
            components.geofences.onGeofenceTransitions(transitions)
        }
    }

    internal companion object {
        private const val TAG = "LT.HmsGeofenceReceiver"

        /** Transitions in [intent]; empty if it carries no HMS geofence event or reports an error. */
        fun parseGeofenceTransitions(intent: Intent?): List<OsGeofenceTransition> {
            val data = try {
                GeofenceData.getDataFromIntent(intent)
            } catch (e: Exception) {
                Logger.w(TAG, "unreadable geofence event", e)
                null
            } ?: return emptyList()
            return toTransitions(data)
        }

        /** One transition per distinct geofence id, all with the event's action and triggering fix. */
        fun toTransitions(data: GeofenceData): List<OsGeofenceTransition> {
            if (data.isFailure) {
                Logger.w(TAG, "HMS geofence error ${data.errorCode}")
                return emptyList()
            }
            val action = geofenceAction(data.conversion)
            if (action == null) {
                Logger.w(TAG, "ignoring geofence event with conversion ${data.conversion}")
                return emptyList()
            }
            val location = data.convertingLocation?.let(::toTrackedLocation)
            return data.convertingGeofenceList.orEmpty()
                .mapNotNull { it?.uniqueId }
                .distinct()
                .map { id -> OsGeofenceTransition(id, action, location) }
        }

        fun geofenceAction(conversion: Int): GeofenceAction? = when (conversion) {
            Geofence.ENTER_GEOFENCE_CONVERSION -> GeofenceAction.ENTER
            Geofence.EXIT_GEOFENCE_CONVERSION -> GeofenceAction.EXIT
            Geofence.DWELL_GEOFENCE_CONVERSION -> GeofenceAction.DWELL
            else -> null
        }
    }
}
