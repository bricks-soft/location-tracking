package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.TrackedLocation

/**
 * Distance accumulator persisted in `runtime.odometer` via [ConfigStore.updateRuntime].
 *
 * Adds the haversine distance between consecutive fixes whose accuracy is at most
 * `geolocation.filter.odometerAccuracyThreshold`; a less accurate fix is skipped and the previous anchor is
 * kept. [reset] also clears the anchor; [set] keeps it.
 *
 * The anchor belongs to one tracking session (`runtime.trackingStartedAt`): a new session starts from its
 * own first fix, so distance travelled while tracking was off is never counted. After a cold process start
 * (e.g. an OEM task killer and `restore`), the first anchor is `runtime.lastLocation` if it was recorded in
 * the current session, so the distance across the restart is kept. Thread-safe.
 */
class DefaultOdometer(private val configStore: ConfigStore) : Odometer {
    private val lock = Any()

    /** The last fix that passed the accuracy gate. In memory only. */
    private var anchor: TrackedLocation? = null

    /** `trackingStartedAt` of the session [anchor] belongs to. */
    private var anchorSession: Long? = null

    /** False until the first fix or [reset] of this process; after that no restore seeding happens. */
    private var started = false

    override val value: Double get() = configStore.runtime.value.odometer

    override fun onLocation(location: TrackedLocation) {
        val threshold = configStore.config.value.geolocation.filter.odometerAccuracyThreshold
        if (!passes(location, threshold)) {
            Logger.v(TAG, "odometer skips fix with accuracy ${location.accuracy} m (threshold $threshold m)")
            return
        }
        synchronized(lock) {
            val runtime = configStore.runtime.value
            val session = runtime.trackingStartedAt
            if (!started) {
                started = true
                anchor = restoredAnchor(runtime, threshold)
                anchorSession = session
            }
            if (anchorSession != session) {
                anchor = null
                anchorSession = session
            }
            val previous = anchor
            anchor = location
            if (previous == null) return
            val distance = Geo.distance(previous, location)
            if (distance > 0) configStore.updateRuntime { it.copy(odometer = it.odometer + distance) }
        }
    }

    /** @throws TrackingException INVALID_ARGUMENT if [value] is negative or not finite. */
    override fun set(value: Double) {
        if (!value.isFinite() || value < 0) {
            throw TrackingException(ErrorCode.INVALID_ARGUMENT, "odometer must be a finite number >= 0, got $value")
        }
        synchronized(lock) {
            configStore.updateRuntime { it.copy(odometer = value) }
        }
    }

    override fun reset() {
        synchronized(lock) {
            started = true
            anchor = null
            configStore.updateRuntime { it.copy(odometer = 0.0) }
        }
    }

    private fun passes(location: TrackedLocation, threshold: Double): Boolean =
        location.accuracy.toDouble() <= threshold && Geo.isValid(location.latitude, location.longitude)

    /** The persisted last location, if it was recorded in the current tracking session and is accurate enough. */
    private fun restoredAnchor(runtime: RuntimeState, threshold: Double): TrackedLocation? {
        val session = runtime.trackingStartedAt ?: return null
        val last = runtime.lastLocation ?: return null
        if (!runtime.enabled || last.time < session || !passes(last, threshold)) return null
        Logger.d(TAG, "odometer resumes from the last recorded location of this session")
        return last
    }

    private companion object {
        const val TAG = "LT.Odometer"
    }
}
