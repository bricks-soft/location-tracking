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
    /**
     * STATIONARY with the stationary region registered: PASSIVE updates, delivered at most every 30-60 s. A passive
     * request makes the backend compute nothing; it only forwards fixes that other apps cause.
     */
    const val PASSIVE_INTERVAL_MS = 60_000L
    const val PASSIVE_FASTEST_INTERVAL_MS = 30_000L

    /**
     * STATIONARY without the stationary region (registration failed, or no current anchor is known): one LOW-power
     * fix (Wi-Fi / cell, no GPS) at most every 3 minutes.
     */
    const val FALLBACK_INTERVAL_MS = 180_000L

    /**
     * - LOCATION + MOVING, or [needsContinuousLocation] (inside a polygon geofence's enclosing circle):
     *   [moving].
     * - LOCATION + STATIONARY: the [passive] request when the `passive` argument is true (the OS watches the
     *   stationary region, or the initial fix is still being computed by its own request), otherwise [fallback].
     * - GEOFENCES otherwise: no request (null).
     */
    fun desired(
        mode: TrackingMode,
        isMoving: Boolean,
        needsContinuousLocation: Boolean,
        config: GeolocationConfig,
        passive: Boolean,
    ): LocationRequestSpec? = when {
        mode == TrackingMode.LOCATION && isMoving -> moving(config)
        needsContinuousLocation -> moving(config)
        mode == TrackingMode.LOCATION -> if (passive) passive(config) else fallback(config)
        else -> null
    }

    /** The configured accuracy and intervals. */
    fun moving(config: GeolocationConfig): LocationRequestSpec = LocationRequestSpec(
        accuracy = config.desiredAccuracy,
        intervalMs = config.locationUpdateInterval.coerceAtLeast(0),
        fastestIntervalMs = config.fastestLocationUpdateInterval.coerceAtLeast(0),
        distanceFilterM = 0f,
    )

    /** PASSIVE: interval at least [PASSIVE_INTERVAL_MS], fastest interval at least [PASSIVE_FASTEST_INTERVAL_MS]. */
    fun passive(config: GeolocationConfig): LocationRequestSpec {
        val interval = maxOf(PASSIVE_INTERVAL_MS, config.locationUpdateInterval)
        val fastest = minOf(interval, maxOf(PASSIVE_FASTEST_INTERVAL_MS, config.fastestLocationUpdateInterval))
        return LocationRequestSpec(DesiredAccuracy.PASSIVE, interval, fastest, distanceFilterM = 0f)
    }

    /**
     * LOW (or PASSIVE when that is the configured accuracy), interval and fastest interval at least
     * [FALLBACK_INTERVAL_MS], so at most one fix per 3 minutes.
     */
    fun fallback(config: GeolocationConfig): LocationRequestSpec {
        val accuracy =
            if (config.desiredAccuracy == DesiredAccuracy.PASSIVE) DesiredAccuracy.PASSIVE else DesiredAccuracy.LOW
        val interval = maxOf(FALLBACK_INTERVAL_MS, config.locationUpdateInterval)
        return LocationRequestSpec(accuracy, interval, interval, distanceFilterM = 0f)
    }
}
