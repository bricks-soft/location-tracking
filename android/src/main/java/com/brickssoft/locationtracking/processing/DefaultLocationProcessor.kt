package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.LocationFilterConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.TrackedLocation
import java.util.Locale

/**
 * Location filters and optional Kalman smoothing. Reads `geolocation.filter` live from [configStore] on
 * every fix.
 *
 * Rules, in order (the first match rejects):
 * 1. invalid coordinates (non-finite, out of range, or exactly 0,0 — a known provider glitch);
 * 2. `accuracy > trackingAccuracyThreshold` (a non-finite accuracy is also rejected);
 * 3. a mock fix when `rejectMockLocations`;
 * 4. a stale fix, older than the previous accepted fix;
 * 5. an identical fix (same latitude, longitude and time as the previous accepted fix) unless
 *    `allowIdenticalLocations`;
 * 6. implied speed (haversine distance / time since the previous accepted fix, only when that time is > 0)
 *    above `maxImpliedSpeed` (0 = off).
 *
 * Time between fixes uses the elapsed-realtime clock when both fixes carry it (see [Geo.elapsedMs]), so
 * wall-clock changes do not affect the checks. Accepted fixes are smoothed by [KalmanFilter] when
 * `useKalman` is set. The stale, identical and implied-speed checks compare raw fixes. Thread-safe.
 */
class DefaultLocationProcessor(private val configStore: ConfigStore) : LocationProcessor {
    private val lock = Any()
    private val kalman = KalmanFilter()

    /** The previous accepted fix, before smoothing. */
    private var lastAccepted: TrackedLocation? = null

    override fun process(raw: TrackedLocation, isMoving: Boolean): FilterResult {
        val filter = configStore.config.value.geolocation.filter
        val result = synchronized(lock) {
            val rejection = rejectionReason(raw, filter)
            if (rejection != null) {
                FilterResult.Rejected(rejection)
            } else {
                lastAccepted = raw
                val location = if (filter.useKalman) {
                    kalman.filter(raw)
                } else {
                    // Start fresh if smoothing is turned on again later.
                    kalman.reset()
                    raw
                }
                FilterResult.Accepted(location)
            }
        }
        if (result is FilterResult.Rejected) Logger.d(TAG, "rejected fix: ${result.reason}")
        return result
    }

    override fun reset() = synchronized(lock) {
        lastAccepted = null
        kalman.reset()
    }

    private fun rejectionReason(raw: TrackedLocation, filter: LocationFilterConfig): String? {
        if (!Geo.isValid(raw.latitude, raw.longitude) || (raw.latitude == 0.0 && raw.longitude == 0.0)) {
            return "invalid coordinates (${raw.latitude}, ${raw.longitude})"
        }
        val threshold = filter.trackingAccuracyThreshold
        if (!(raw.accuracy.toDouble() <= threshold)) {
            return "accuracy ${raw.accuracy} m > trackingAccuracyThreshold $threshold m"
        }
        if (raw.isMock && filter.rejectMockLocations) return "mock location"
        val previous = lastAccepted ?: return null
        val dtMs = Geo.elapsedMs(previous, raw)
        if (dtMs < 0) return "stale: ${-dtMs} ms older than the previous fix"
        if (!filter.allowIdenticalLocations &&
            raw.latitude == previous.latitude &&
            raw.longitude == previous.longitude &&
            raw.time == previous.time
        ) {
            return "identical to the previous fix"
        }
        val maxSpeed = filter.maxImpliedSpeed
        if (maxSpeed > 0 && dtMs > 0) {
            val speed = Geo.distance(previous, raw) / (dtMs / 1000.0)
            if (speed > maxSpeed) {
                return "implied speed ${"%.1f".format(Locale.US, speed)} m/s > maxImpliedSpeed $maxSpeed m/s"
            }
        }
        return null
    }

    private companion object {
        const val TAG = "LT.Processor"
    }
}
