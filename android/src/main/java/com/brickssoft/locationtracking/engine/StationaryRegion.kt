package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.OsGeofence
import kotlin.math.max

/**
 * The engine's own OS geofence while STATIONARY (architecture round 2, §3).
 *
 * While the device is STATIONARY the engine stops its own location requests (GPS off) and registers one circular
 * geofence around the stationary anchor with the OS geofencing service of the selected backend. The OS reports the
 * EXIT of that circle to `DefaultGeofenceManager`, which forwards it to the engine (`onStationaryRegionTransition`);
 * the engine then switches to MOVING. The region is never stored, recorded or emitted as a user geofence.
 */
internal object StationaryRegion {
    /**
     * Smallest radius of the region, in meters. OS geofencing (GMS, HMS, Android proximity alerts) works with
     * Wi-Fi and cell locations whose error is often 50-150 m, so a smaller circle would report EXIT without movement.
     */
    const val MIN_RADIUS_M = 150.0

    /** Region radius in meters: `max(stationaryRadius, MIN_RADIUS_M)`; a non-finite radius counts as 0. */
    fun radius(stationaryRadius: Double): Float {
        val configured = stationaryRadius.takeIf { it.isFinite() } ?: 0.0
        return max(configured, MIN_RADIUS_M).toFloat()
    }

    /** The region around [anchor]: EXIT only, no initial trigger, id [Constants.STATIONARY_REGION_ID]. */
    fun around(anchor: TrackedLocation, stationaryRadius: Double): OsGeofence = OsGeofence(
        id = Constants.STATIONARY_REGION_ID,
        latitude = anchor.latitude,
        longitude = anchor.longitude,
        radius = radius(stationaryRadius),
        onEntry = false,
        onExit = true,
        onDwell = false,
        loiteringDelayMs = 0,
        initialTriggerEntry = false,
    )
}
