package com.brickssoft.locationtracking.config

import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.TrackedLocation

/** JS `TrackingMode`. */
enum class TrackingMode(val wire: String) {
    LOCATION("location"),
    GEOFENCES("geofences"),
    ;

    companion object {
        fun fromWire(value: String?): TrackingMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Persisted runtime state (survives process death).
 *
 * @property lastRecordAt wall time of the last persisted record, epoch ms.
 * @property lastRecordElapsed elapsed-realtime ms of the last persisted record.
 * @property lastRecordBootCount boot count when the last record was persisted.
 * @property providerState last provider state seen, used to detect `providerchange` across process restarts.
 * @property didReady true once `ready()` has ever completed (drives `ReadyOptions.reset=false`).
 * @property trackingStartedElapsed elapsed-realtime ms when the session started (`stopAfterElapsedMinutes`), valid
 *   while the boot count is [trackingStartedBootCount].
 * @property lastStopReason the reason of the last `tracking_stop` record, and [lastStopAt] when tracking stopped
 *   (epoch ms).
 * @property userStopRecordedFor the time of the user stop (Task Manager Stop or force stop) whose `tracking_stop`
 *   record was written, so a later process does not write it again.
 */
data class RuntimeState(
    val enabled: Boolean = false,
    val trackingMode: TrackingMode = TrackingMode.LOCATION,
    val isMoving: Boolean = false,
    val odometer: Double = 0.0,
    val activity: ActivitySample = ActivitySample.UNKNOWN,
    val lastLocation: TrackedLocation? = null,
    val lastRecordAt: Long? = null,
    val lastRecordElapsed: Long? = null,
    val lastRecordBootCount: Int? = null,
    val lastHeartbeatAt: Long? = null,
    val trackingStartedAt: Long? = null,
    val providerState: ProviderState? = null,
    val didReady: Boolean = false,
    val trackingStartedElapsed: Long? = null,
    val trackingStartedBootCount: Int? = null,
    val lastStopReason: String? = null,
    val lastStopAt: Long? = null,
    val userStopRecordedFor: Long? = null,
)

/** Snapshot returned to JS as `State` (see `ConfigJson.stateToJson`). */
data class State(val config: Config, val runtime: RuntimeState, val backend: ProviderKind)
