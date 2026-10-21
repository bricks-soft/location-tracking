package com.brickssoft.locationtracking.core

import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent

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

    /**
     * "enabledchange". [reason] is the `tracking_start` reason when tracking was switched on, the `tracking_stop` reason
     * when it was switched off (the same values as the audit records), or null.
     */
    data class EnabledChange(val enabled: Boolean, val reason: String? = null) : TrackingEvent

    /** "notificationaction" */
    data class NotificationAction(val id: String) : TrackingEvent

    /**
     * "locationerror": the location request on [backend] failed (recommendation 3). The engine retries it after
     * [retryInMs] ms (null: no retry is scheduled, for example tracking stopped).
     */
    data class LocationError(
        val code: ErrorCode,
        val message: String,
        val backend: ProviderKind,
        val retryInMs: Long?,
    ) : TrackingEvent

    /**
     * "storagefull": the record [uuid] ([event]) could not be stored because the device storage is full
     * (recommendation 16); [diskFreeBytes] is the free space of the app's storage, or null if unknown.
     */
    data class StorageFull(val uuid: String, val event: RecordEvent, val diskFreeBytes: Long?) : TrackingEvent

    /** "authorization"; [responseJson] is JSON object text. */
    data class Authorization(
        val success: Boolean,
        val status: Int,
        val error: String?,
        val responseJson: String?,
    ) : TrackingEvent
}
