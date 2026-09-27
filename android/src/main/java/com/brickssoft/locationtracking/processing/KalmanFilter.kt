package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.model.TrackedLocation
import kotlin.math.max

/**
 * 1-D constant-position Kalman filter applied per axis to latitude and longitude.
 *
 * - Measurement variance is `accuracy²` (m², accuracy floored at [MIN_ACCURACY_M]).
 * - Process noise per step is `q = speed² · dt` (m², dt in seconds). `speed` is the fix's speed floored at
 *   [MIN_SPEED_MPS] (so the estimate never freezes), or [DEFAULT_SPEED_MPS] when the fix has none. For gaps
 *   over 1 s, `q` grows as `(speed · dt)²` because displacement grows linearly with time.
 * - Both axes share one variance because `accuracy` is a horizontal radius. Longitude steps are wrapped, so
 *   tracks across the antimeridian are smoothed correctly.
 * - `dt` uses the elapsed-realtime clock when both fixes carry it (see [Geo.elapsedMs]).
 * - The filter restarts from the measurement when the gap exceeds [resetAfterMs], when time goes
 *   backwards, or after [reset].
 *
 * Only latitude and longitude are replaced; every other field of the fix is returned unchanged.
 * Not thread-safe: [DefaultLocationProcessor] serializes access.
 */
internal class KalmanFilter(
    private val resetAfterMs: Long = DEFAULT_RESET_AFTER_MS,
) {
    /** The last measurement (time reference), or null before the first fix / after [reset]. */
    private var last: TrackedLocation? = null
    private var latitude = 0.0
    private var longitude = 0.0

    /** Estimate variance, m². */
    private var variance = 0.0

    /** Returns [location] with smoothed latitude / longitude. */
    fun filter(location: TrackedLocation): TrackedLocation {
        val accuracy = location.accuracy.toDouble().takeIf { it.isFinite() && it > MIN_ACCURACY_M } ?: MIN_ACCURACY_M
        val measurementVariance = accuracy * accuracy
        val previous = last
        val dtMs = previous?.let { Geo.elapsedMs(it, location) }
        if (dtMs == null || dtMs < 0 || dtMs > resetAfterMs) {
            last = location
            latitude = location.latitude
            longitude = location.longitude
            variance = measurementVariance
            return location
        }
        last = location
        if (dtMs > 0) {
            val reported = location.speed?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            val speed = reported?.let { max(it, MIN_SPEED_MPS) } ?: DEFAULT_SPEED_MPS
            val dt = dtMs / 1000.0
            variance += speed * speed * dt * max(1.0, dt)
        }
        val gain = variance / (variance + measurementVariance)
        latitude += gain * (location.latitude - latitude)
        longitude = Geo.normalizeLongitude(longitude + gain * Geo.wrapLongitudeDelta(location.longitude - longitude))
        variance *= 1 - gain
        return location.copy(latitude = latitude, longitude = longitude)
    }

    /** Forgets all history; the next fix is returned unchanged. */
    fun reset() {
        last = null
        variance = 0.0
    }

    companion object {
        const val DEFAULT_RESET_AFTER_MS = 60_000L
        const val DEFAULT_SPEED_MPS = 3.0
        const val MIN_SPEED_MPS = 1.0
        const val MIN_ACCURACY_M = 1.0
    }
}
