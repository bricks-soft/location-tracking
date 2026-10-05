package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.provider.ActivitySink
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.provider.StationaryRegionSink
import org.json.JSONObject

/** The tracking state machine. All state changes happen on `dispatchers.engine`. */
interface TrackingEngine : ActivitySink, StationaryRegionSink {
    suspend fun ready(config: JSONObject?, reset: Boolean): State

    suspend fun setConfig(config: JSONObject): State

    suspend fun reset(config: JSONObject?): State

    /** @throws com.brickssoft.locationtracking.core.TrackingException PERMISSION_DENIED if no foreground location. */
    suspend fun start(): State

    suspend fun startGeofences(): State

    suspend fun stop(): State

    suspend fun changePace(isMoving: Boolean)

    fun state(): State

    /** "restore" | "boot" | "package_replaced": cold process while enabled. */
    suspend fun restore(reason: String)

    /** Task removed. */
    suspend fun onTerminate()

    /**
     * The foreground service could not enter the foreground after `ServiceController.start()` had returned true,
     * for example a start from the background on Android 12+ without an exemption, or on Android 14+ with only
     * while-in-use location permission. While tracking is enabled this stops it with the `tracking_stop` reason
     * `service_start_failed`, so the server sees why the audit trail ends.
     */
    suspend fun onServiceStartFailed(error: String) = Unit

    /**
     * Tracking was enabled in a process that ended with a reboot or an app update, and `app.startOnBoot` is false, so
     * it is not resumed. Records `tracking_stop` with [reason] (`reboot` | `package_replaced`), so the server learns why
     * the heartbeats stopped, and clears `enabled`.
     */
    suspend fun endWithoutRestore(reason: String) = Unit

    /**
     * The resume notification was tapped and the service is in the foreground: resumes the session that Android refused
     * to restore (`tracking_start` reason `resume_notification`), or stops the service if there is nothing to resume.
     */
    suspend fun resumeFromNotification() = Unit

    /**
     * A transition of the engine's stationary region (`Constants.STATIONARY_REGION_ID`), routed by the
     * GeofenceManager. Round 2, unit 2 implements it (EXIT = the device left the stationary anchor). Default: ignored.
     */
    override suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition) = Unit
}
