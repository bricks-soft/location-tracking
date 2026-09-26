package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.provider.ActivitySink
import org.json.JSONObject

/** The tracking state machine. All state changes happen on `dispatchers.engine`. */
interface TrackingEngine : ActivitySink {
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
}
