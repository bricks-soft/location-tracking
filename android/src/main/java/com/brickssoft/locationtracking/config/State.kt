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
)

/** Snapshot returned to JS as `State` (see `ConfigJson.stateToJson`). */
data class State(val config: Config, val runtime: RuntimeState, val backend: ProviderKind)
