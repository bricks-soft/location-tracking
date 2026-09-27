package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.GeofenceTransitionSink
import kotlinx.coroutines.flow.StateFlow

/** Geofence registry, OS registration, polygon hit-testing and dwell synthesis. */
interface GeofenceManager : GeofenceTransitionSink {
    /** More than 100 in total -> TOO_MANY_GEOFENCES; emits GeofencesChange. */
    suspend fun add(geofences: List<GeofenceSpec>)

    suspend fun remove(ids: List<String>)

    suspend fun removeAll()

    suspend fun list(): List<GeofenceSpec>

    suspend fun get(id: String): GeofenceSpec?

    /** Registers the geofences with the OS. */
    suspend fun onTrackingStarted(mode: TrackingMode)

    /** Unregisters the geofences from the OS. */
    suspend fun onTrackingStopped()

    /** Polygon hit-test and synthesized dwell. */
    fun onLocation(location: TrackedLocation)

    /** True while inside any polygon's enclosing circle. */
    val needsContinuousLocation: StateFlow<Boolean>
}
