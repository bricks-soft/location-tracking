package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import org.json.JSONObject

sealed interface FilterResult {
    data class Accepted(val location: TrackedLocation) : FilterResult

    data class Rejected(val reason: String) : FilterResult
}

/** Accuracy / implied-speed / identical / mock filters and optional Kalman smoothing. */
interface LocationProcessor {
    fun process(raw: TrackedLocation, isMoving: Boolean): FilterResult

    fun reset()
}

/** Distance accumulator; persists its value via `configStore.updateRuntime`. */
interface Odometer {
    val value: Double

    fun onLocation(location: TrackedLocation)

    fun set(value: Double)

    fun reset()
}

/** Builds records with uuid, clock/boot metadata, runtime state, battery and backend. */
interface RecordFactory {
    /** Merges `persistence.extras`; reads runtime (isMoving, odometer, activity), battery and clock. */
    fun create(
        event: RecordEvent,
        location: TrackedLocation?,
        extras: String? = null,
        geofence: GeofenceHit? = null,
        provider: ProviderState? = null,
        reason: String? = null,
    ): Record

    /** `insertLocation`: builds a record from a JS `InsertLocationInput`. */
    fun fromExternal(input: JSONObject): Record
}
