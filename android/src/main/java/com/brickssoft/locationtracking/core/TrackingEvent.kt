package com.brickssoft.locationtracking.core

import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record

/**
 * Events forwarded to JS. The JS event name and payload are built by
 * [com.brickssoft.locationtracking.model.EventJson].
 */
sealed interface TrackingEvent {
    /** "location" */
    data class Location(val record: Record) : TrackingEvent

    /** "motionchange" */
    data class MotionChange(val isMoving: Boolean, val record: Record) : TrackingEvent

    /** "activitychange" */
    data class ActivityChange(val activity: ActivitySample) : TrackingEvent

    /** "providerchange" */
    data class ProviderChange(val state: ProviderState) : TrackingEvent

    /** "heartbeat" */
    data class Heartbeat(val record: Record) : TrackingEvent

    /** "geofence"; [extras] is JSON object text. */
    data class Geofence(
        val identifier: String,
        val action: GeofenceAction,
        val record: Record,
        val extras: String?,
    ) : TrackingEvent

    /** "geofenceschange" */
    data class GeofencesChange(val on: List<GeofenceSpec>, val off: List<String>) : TrackingEvent

    /** "http" */
    data class Http(val result: HttpResult) : TrackingEvent

    /** "connectivitychange" */
    data class ConnectivityChange(val connectivity: Connectivity) : TrackingEvent

    /** "powersavechange" */
    data class PowerSaveChange(val isPowerSaveMode: Boolean) : TrackingEvent

    /** "enabledchange" */
    data class EnabledChange(val enabled: Boolean) : TrackingEvent

    /** "notificationaction" */
    data class NotificationAction(val id: String) : TrackingEvent

    /** "authorization"; [responseJson] is JSON object text. */
    data class Authorization(
        val success: Boolean,
        val status: Int,
        val error: String?,
        val responseJson: String?,
    ) : TrackingEvent
}
