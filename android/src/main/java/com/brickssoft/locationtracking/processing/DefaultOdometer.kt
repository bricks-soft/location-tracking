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
 * The anchor belongs to one tracking session (`runtime.trackingStartedAt`), so distance travelled while tracking
 * was off is never counted. The first fix of a session in this process is measured from `runtime.lastLocation` if
 * that was recorded in the same session (and is accurate enough and not newer than the fix), otherwise it only
 * becomes the anchor:
 * - after `start()`, `lastLocation` is the session's start position (the initial `motionchange`), which the engine
 *   does not feed to the odometer while stationary, so the leg to the first moving fix is counted in every session;
 * - after a cold process start (e.g. an OEM task killer and `restore`), it is where the previous process left off.
 *
 * [reset] clears the anchor for the rest of the current session. Thread-safe.
 */
class DefaultOdometer(private val configStore: ConfigStore) : Odometer {
    private val lock = Any()

    /** The last fix that passed the accuracy gate. In memory only. */
    private var anchor: TrackedLocation? = null

    /** `trackingStartedAt` of the session [anchor] belongs to; null until this process's first fix or [reset]. */
    private var anchorSession: Long? = null

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
            if (anchorSession != session) {
                // Every new session is seeded, not only the first fix of the process: a later session used to start
                // from a null anchor, which dropped the leg from its start position to its first moving fix.
                anchor = sessionAnchor(runtime, threshold, location)
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
            anchor = null
            // The cleared anchor belongs to the current session: its next fix is not seeded from a lastLocation that
            // may predate the reset.
            anchorSession = configStore.runtime.value.trackingStartedAt
            configStore.updateRuntime { it.copy(odometer = 0.0) }
        }
    }

    private fun passes(location: TrackedLocation, threshold: Double): Boolean =
        location.accuracy.toDouble() <= threshold && Geo.isValid(location.latitude, location.longitude)

    /**
     * The persisted last location, if it was recorded in the current tracking session, is accurate enough and is not
     * newer than [next] (the fix about to be measured from it).
     */
    private fun sessionAnchor(runtime: RuntimeState, threshold: Double, next: TrackedLocation): TrackedLocation? {
        val session = runtime.trackingStartedAt ?: return null
        val last = runtime.lastLocation ?: return null
        if (!runtime.enabled || last.time < session || last.time > next.time || !passes(last, threshold)) return null
        Logger.d(TAG, "odometer starts from the last recorded location of this session")
        return last
    }

    private companion object {
        const val TAG = "LT.Odometer"
    }
}
