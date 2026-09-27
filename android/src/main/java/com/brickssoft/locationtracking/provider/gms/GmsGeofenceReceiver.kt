package com.brickssoft.locationtracking.provider.gms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.GeofenceTransitionSink
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives `GeofencingEvent`s from the PendingIntent of [GmsGeofenceBackend] and delivers one
 * [OsGeofenceTransition] per triggering geofence to the GeofenceManager with `goAsync()` on `Components.scope`.
 */
class GmsGeofenceReceiver internal constructor(
    private val target: (Context) -> ReceiverTarget<GeofenceTransitionSink>,
) : BroadcastReceiver() {
    constructor() : this({ ctx -> Components.get(ctx).let { ReceiverTarget(it.scope, it.geofences) } })

    override fun onReceive(context: Context, intent: Intent) {
        val transitions = parseGeofenceTransitions(intent)
        if (transitions.isEmpty()) return
        Logger.d(TAG, "geofence transitions: ${transitions.joinToString { "${it.id}:${it.action}" }}")
        deliverAsync(context, target, TAG, "geofence delivery") { it.onGeofenceTransitions(transitions) }
    }

    internal companion object {
        private const val TAG = "LT.GmsGeofence"

        fun parseGeofenceTransitions(intent: Intent?): List<OsGeofenceTransition> {
            val event = try {
                intent?.let { GeofencingEvent.fromIntent(it) }
            } catch (e: Exception) {
                Logger.w(TAG, "unreadable geofencing event", e)
                null
            }
            return parseGeofencingEvent(event)
        }

        /**
         * One transition per triggering geofence, all with the event's action and triggering location.
         * Empty (and logged) if [event] is null, carries an error or has an unknown transition.
         */
        fun parseGeofencingEvent(event: GeofencingEvent?): List<OsGeofenceTransition> {
            if (event == null) {
                Logger.w(TAG, "intent carries no geofencing event")
                return emptyList()
            }
            if (event.hasError()) {
                val code = event.errorCode
                // GEOFENCE_NOT_AVAILABLE also means GMS removed every registered geofence; nothing re-adds them
                // until tracking restarts (see the unit report's contract change request).
                val notAvailable = code == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
                val dropped = if (notAvailable) "; all geofences were removed" else ""
                Logger.e(TAG, "geofencing error: ${GmsErrors.describe(code)}$dropped")
                return emptyList()
            }
            val action = mapTransition(event.geofenceTransition) ?: run {
                Logger.w(TAG, "unknown geofence transition ${event.geofenceTransition}")
                return emptyList()
            }
            val location = event.triggeringLocation?.let(TrackedLocation::from)
            return event.triggeringGeofences.orEmpty().map { OsGeofenceTransition(it.requestId, action, location) }
        }

        fun mapTransition(transition: Int): GeofenceAction? = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceAction.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceAction.EXIT
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceAction.DWELL
            else -> null
        }
    }
}
