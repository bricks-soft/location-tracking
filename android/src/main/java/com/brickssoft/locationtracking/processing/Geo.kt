package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.model.TrackedLocation
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle geometry on a spherical Earth. */
internal object Geo {
    /** Mean Earth radius (IUGG), meters. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Haversine distance in meters between two points given in degrees. */
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val sinPhi = sin(dPhi / 2)
        val sinLambda = sin(dLambda / 2)
        val h = sinPhi * sinPhi + cos(phi1) * cos(phi2) * sinLambda * sinLambda
        // Clamp: rounding can push h slightly above 1 for antipodal points.
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
    }

    /** Haversine distance in meters between two fixes. */
    fun distance(a: TrackedLocation, b: TrackedLocation): Double =
        haversine(a.latitude, a.longitude, b.latitude, b.longitude)

    /** True if both coordinates are finite and within the valid latitude / longitude ranges. */
    fun isValid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Wraps a longitude difference into [-180, 180), so steps across the antimeridian stay short. */
    fun wrapLongitudeDelta(delta: Double): Double {
        val wrapped = (delta + 180.0) % 360.0
        return (if (wrapped < 0) wrapped + 360.0 else wrapped) - 180.0
    }

    /** Normalizes a longitude into [-180, 180]. */
    fun normalizeLongitude(longitude: Double): Double =
        if (longitude in -180.0..180.0) longitude else wrapLongitudeDelta(longitude)

    /**
     * Milliseconds from [from] to [to]: on the monotonic elapsed-realtime clock when both fixes carry it,
     * otherwise on the wall clock (`time`).
     */
    fun elapsedMs(from: TrackedLocation, to: TrackedLocation): Long =
        if (from.elapsedRealtimeNanos > 0 && to.elapsedRealtimeNanos > 0) {
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000
        } else {
            to.time - from.time
        }
}
