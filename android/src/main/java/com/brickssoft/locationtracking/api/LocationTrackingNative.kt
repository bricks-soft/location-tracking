// STUB — owned by Unit 5 (Companion native API).
package com.brickssoft.locationtracking.api

import android.content.Context
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Public native API for companion plugins and native app code (round 2). Each method mirrors the JS method of the
 * same name, takes the same JSON shapes and delivers the JS result shape to a [NativeCallback] on the `LT-native`
 * thread. Native calls are not subject to the JS bridge's NOT_READY rule.
 *
 * SCAFFOLD STUB: [addListener] returns a no-op subscription and every other method fails with UNIMPLEMENTED.
 */
object LocationTrackingNative {
    /**
     * Name (or name prefix, followed by `.`) of the `<meta-data>` that declares a [LocationTrackingListener] class:
     * `<meta-data android:name="com.brickssoft.locationtracking.LISTENER" android:value="com.example.MyListener"/>`.
     */
    const val LISTENER_META_DATA = "com.brickssoft.locationtracking.LISTENER"

    /** Name of the single background thread that runs listeners and callbacks. */
    const val THREAD_NAME = "LT-native"

    /** Subscribes [listener] to everything emitted after this call (see [LocationTrackingListener]). */
    @JvmStatic
    fun addListener(context: Context, listener: LocationTrackingListener): NativeSubscription = NativeSubscription { }

    /** JS `ready({config, reset})`; result: `State` JSON (`ConfigJson.stateToJson`). */
    @JvmStatic
    fun ready(context: Context, config: JSONObject?, reset: Boolean, callback: NativeCallback<JSONObject>) =
        unimplemented("ready", callback)

    /** JS `setConfig({config})`; result: `State` JSON. */
    @JvmStatic
    fun setConfig(context: Context, config: JSONObject, callback: NativeCallback<JSONObject>) =
        unimplemented("setConfig", callback)

    /** JS `start()`; result: `State` JSON. */
    @JvmStatic
    fun start(context: Context, callback: NativeCallback<JSONObject>) = unimplemented("start", callback)

    /** JS `startGeofences()`; result: `State` JSON. */
    @JvmStatic
    fun startGeofences(context: Context, callback: NativeCallback<JSONObject>) =
        unimplemented("startGeofences", callback)

    /** JS `stop()`; result: `State` JSON. */
    @JvmStatic
    fun stop(context: Context, callback: NativeCallback<JSONObject>) = unimplemented("stop", callback)

    /** JS `changePace({isMoving})`. */
    @JvmStatic
    fun changePace(context: Context, isMoving: Boolean, callback: NativeCallback<Unit>) =
        unimplemented("changePace", callback)

    /** JS `getState()`; result: `State` JSON. */
    @JvmStatic
    fun getState(context: Context, callback: NativeCallback<JSONObject>) = unimplemented("getState", callback)

    /** JS `getHeartbeatStatus()`; result: `HeartbeatStatus` JSON. */
    @JvmStatic
    fun getHeartbeatStatus(context: Context, callback: NativeCallback<JSONObject>) =
        unimplemented("getHeartbeatStatus", callback)

    /** JS `sync()`; result: the uploaded records (wire JSON, the JS `locations` array). */
    @JvmStatic
    fun sync(context: Context, callback: NativeCallback<JSONArray>) = unimplemented("sync", callback)

    /** JS `insertLocation({location})` (`InsertLocationInput` JSON); result: the new record's uuid. */
    @JvmStatic
    fun insertLocation(context: Context, location: JSONObject, callback: NativeCallback<String>) =
        unimplemented("insertLocation", callback)

    /** JS `addGeofence({geofence})` (JS `Geofence` JSON, parsed by `GeofenceJson`). */
    @JvmStatic
    fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>) =
        unimplemented("addGeofence", callback)

    /** JS `removeGeofence({identifier})`. */
    @JvmStatic
    fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>) =
        unimplemented("removeGeofence", callback)

    /** JS `getGeofences()`; result: the JS `geofences` array. */
    @JvmStatic
    fun getGeofences(context: Context, callback: NativeCallback<JSONArray>) = unimplemented("getGeofences", callback)

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } }
    }

    private fun <T> unimplemented(method: String, callback: NativeCallback<T>) {
        executor.execute {
            try {
                callback.onResult(
                    Result.failure(
                        TrackingException(ErrorCode.UNIMPLEMENTED, "LocationTrackingNative.$method is not implemented"),
                    ),
                )
            } catch (e: Exception) {
                Logger.e(TAG, "native callback of $method failed", e)
            }
        }
    }

    private const val TAG = "LT.Native"
}
