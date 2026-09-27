package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.provider.LocationRequestSpec

/**
 * The continuous location request the engine wants in each situation.
 *
 * Requests never ask the backend to filter by distance: the engine applies the (elastic) distance filter itself,
 * and stop detection, polygon geofences and overdue timers need fixes to keep arriving while the device does not
 * move.
 */
internal object LocationRequests {
    /** STATIONARY: low-frequency updates, just enough to notice that the device left the stationary radius. */
    const val STATIONARY_INTERVAL_MS = 60_000L
    const val STATIONARY_FASTEST_INTERVAL_MS = 30_000L

    /**
     * - LOCATION + MOVING, or [needsContinuousLocation] (inside a polygon geofence's enclosing circle):
     *   [moving].
     * - LOCATION + STATIONARY: [stationary].
     * - GEOFENCES otherwise: no request (null).
     */
    fun desired(
        mode: TrackingMode,
        isMoving: Boolean,
        needsContinuousLocation: Boolean,
        config: GeolocationConfig,
    ): LocationRequestSpec? = when {
        mode == TrackingMode.LOCATION && isMoving -> moving(config)
        needsContinuousLocation -> moving(config)
        mode == TrackingMode.LOCATION -> stationary(config)
        else -> null
    }

    /** The configured accuracy and intervals. */
    fun moving(config: GeolocationConfig): LocationRequestSpec = LocationRequestSpec(
        accuracy = config.desiredAccuracy,
        intervalMs = config.locationUpdateInterval.coerceAtLeast(0),
        fastestIntervalMs = config.fastestLocationUpdateInterval.coerceAtLeast(0),
        distanceFilterM = 0f,
    )

    /** BALANCED (or the configured accuracy if it is lower-power), at most one fix per minute. */
    fun stationary(config: GeolocationConfig): LocationRequestSpec {
        val accuracy =
            if (config.desiredAccuracy.ordinal > DesiredAccuracy.BALANCED.ordinal) config.desiredAccuracy else DesiredAccuracy.BALANCED
        val interval = maxOf(STATIONARY_INTERVAL_MS, config.locationUpdateInterval)
        val fastest = minOf(interval, maxOf(STATIONARY_FASTEST_INTERVAL_MS, config.fastestLocationUpdateInterval))
        return LocationRequestSpec(accuracy, interval, fastest, distanceFilterM = 0f)
    }
}
