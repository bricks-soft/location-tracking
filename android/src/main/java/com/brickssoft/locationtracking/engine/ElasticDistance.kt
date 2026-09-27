package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.model.TrackedLocation
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elastic distance filter: the faster the device moves, the farther apart recorded locations are.
 *
 * `d = distanceFilter * max(1, round(speed / 5.0) * elasticityMultiplier)`, or plain `distanceFilter` when
 * elasticity is disabled. `round` rounds halves up; a missing, negative or non-finite speed counts as 0.
 */
internal object ElasticDistance {
    /** Speed step (m/s) of the elastic multiplier. */
    private const val SPEED_STEP_MPS = 5.0

    /** The minimum distance (m) between two recorded locations at [speedMps]. */
    fun threshold(
        distanceFilter: Double,
        speedMps: Float?,
        elasticityMultiplier: Double,
        disableElasticity: Boolean,
    ): Double {
        val base = if (distanceFilter.isFinite()) max(0.0, distanceFilter) else 0.0
        if (disableElasticity) return base
        val speed = speedMps?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val multiplier = if (elasticityMultiplier.isFinite()) elasticityMultiplier else 1.0
        val factor = max(1.0, floor(speed / SPEED_STEP_MPS + 0.5) * multiplier)
        return base * factor
    }

    /** [threshold] with the settings of [config], at the speed of [location]. */
    fun threshold(config: GeolocationConfig, location: TrackedLocation): Double =
        threshold(config.distanceFilter, location.speed, config.elasticityMultiplier, config.disableElasticity)

    /** True if [candidate] is far enough from [lastRecorded] (or nothing was recorded yet) to be recorded. */
    fun shouldRecord(lastRecorded: TrackedLocation?, candidate: TrackedLocation, config: GeolocationConfig): Boolean {
        if (lastRecorded == null) return true
        return distanceMeters(lastRecorded, candidate) >= threshold(config, candidate)
    }
}

/** Mean Earth radius (m), IUGG. */
private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle (haversine) distance in meters between two fixes. */
internal fun distanceMeters(a: TrackedLocation, b: TrackedLocation): Double =
    distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

/** Great-circle (haversine) distance in meters. */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lng2 - lng1)
    val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/** Horizontal accuracy (m) of a fix; unknown (non-finite or negative) counts as 0. */
internal val TrackedLocation.accuracyMeters: Double
    get() = accuracy.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
