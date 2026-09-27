package com.brickssoft.locationtracking.api

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.bridge.BridgeServices
import com.brickssoft.locationtracking.bridge.ComponentServices
import com.brickssoft.locationtracking.bridge.PluginHandlers
import com.brickssoft.locationtracking.bridge.Rejection
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Public native API for companion plugins and native app code (round 2; guide: `docs/native-api.md`). Each method
 * mirrors the JS method of the same name: it takes the same JSON shapes, runs the same code as the JS bridge
 * (`PluginHandlers`, launched on `Components.scope`) and delivers the JS result shape to a [NativeCallback].
 *
 * - Every callback is invoked exactly once, on the `LT-native` thread ([THREAD_NAME]), when its call completes. Calls
 *   run concurrently on the plugin's scope: a call that suspends (for example `start()`) can complete after a later
 *   call, so callbacks arrive in completion order, not in call order.
 * - A failure is a `Result.failure` holding a [TrackingException] whose `code` is the JS `ErrorCode` (for example
 *   `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `NO_URL`); any other exception is wrapped as `INTERNAL` with the original
 *   exception as its cause.
 * - Native calls are not subject to the JS bridge's NOT_READY rule and do not set the bridge's ready flag: JS must
 *   still call `ready()` itself. A native caller that needs a configuration calls [ready] first.
 * - JSON arguments are copied when the method is called, so the caller may change or reuse its JSONObject afterwards.
 */
object LocationTrackingNative {
    /**
     * Name (or name prefix, followed by `.`) of the `<meta-data>` that declares a [LocationTrackingListener] class:
     * `<meta-data android:name="com.brickssoft.locationtracking.LISTENER" android:value="com.example.MyListener"/>`.
     */
    const val LISTENER_META_DATA = "com.brickssoft.locationtracking.LISTENER"

    /** Name of the single background thread that runs listeners and callbacks. */
    const val THREAD_NAME = "LT-native"

    private const val TAG = "LT.Native"

    /**
     * Subscribes [listener] to every record and event emitted after this call (see [LocationTrackingListener]). The
     * listener's methods receive the application context of [context]. The subscription is process-wide and does not
     * create the plugin's components (nothing is emitted before they exist); [NativeSubscription.remove] ends it.
     */
    @JvmStatic
    fun addListener(context: Context, listener: LocationTrackingListener): NativeSubscription =
        NativeListeners.add(context, listener)

    /** JS `ready({config, reset})`; result: `State` JSON (`ConfigJson.stateToJson`). */
    @JvmStatic
    fun ready(context: Context, config: JSONObject?, reset: Boolean, callback: NativeCallback<JSONObject>) {
        val copy = if (config == null) null else copyOf(config, "ready", callback) ?: return
        call(context, "ready", callback) {
            val options = JSONObject().put("reset", reset)
            if (copy != null) options.put("config", copy)
            ready(options)
        }
    }

    /** JS `setConfig({config})`; result: `State` JSON. */
    @JvmStatic
    fun setConfig(context: Context, config: JSONObject, callback: NativeCallback<JSONObject>) {
        val copy = copyOf(config, "setConfig", callback) ?: return
        call(context, "setConfig", callback) { setConfig(JSONObject().put("config", copy)) }
    }

    /** JS `start()`; result: `State` JSON. */
    @JvmStatic
    fun start(context: Context, callback: NativeCallback<JSONObject>) {
        call(context, "start", callback) { start() }
    }

    /** JS `startGeofences()`; result: `State` JSON. */
    @JvmStatic
    fun startGeofences(context: Context, callback: NativeCallback<JSONObject>) {
        call(context, "startGeofences", callback) { startGeofences() }
    }

    /** JS `stop()`; result: `State` JSON. */
    @JvmStatic
    fun stop(context: Context, callback: NativeCallback<JSONObject>) {
        call(context, "stop", callback) { stop() }
    }

    /** JS `changePace({isMoving})`. */
    @JvmStatic
    fun changePace(context: Context, isMoving: Boolean, callback: NativeCallback<Unit>) {
        call(context, "changePace", callback) {
            changePace(JSONObject().put("isMoving", isMoving))
            Unit
        }
    }

    /** JS `getState()`; result: `State` JSON. */
    @JvmStatic
    fun getState(context: Context, callback: NativeCallback<JSONObject>) {
        call(context, "getState", callback) { getState() }
    }

    /** JS `getHeartbeatStatus()`; result: `HeartbeatStatus` JSON. */
    @JvmStatic
    fun getHeartbeatStatus(context: Context, callback: NativeCallback<JSONObject>) {
        call(context, "getHeartbeatStatus", callback) { getHeartbeatStatus() }
    }

    /** JS `sync()`; result: the uploaded records (wire JSON, the JS `locations` array). */
    @JvmStatic
    fun sync(context: Context, callback: NativeCallback<JSONArray>) {
        call(context, "sync", callback) { sync().getJSONArray("locations") }
    }

    /**
     * JS `insertLocation({location})` (`InsertLocationInput` JSON); result: the new record's uuid. The record is
     * queued for upload and delivered to every [LocationTrackingListener.onRecord], like the JS method.
     */
    @JvmStatic
    fun insertLocation(context: Context, location: JSONObject, callback: NativeCallback<String>) {
        val copy = copyOf(location, "insertLocation", callback) ?: return
        call(context, "insertLocation", callback) {
            insertLocation(JSONObject().put("location", copy)).getString("uuid")
        }
    }

    /** JS `addGeofence({geofence})` (JS `Geofence` JSON, parsed by `GeofenceJson`). */
    @JvmStatic
    fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>) {
        val copy = copyOf(geofence, "addGeofence", callback) ?: return
        call(context, "addGeofence", callback) {
            addGeofence(JSONObject().put("geofence", copy))
            Unit
        }
    }

    /** JS `removeGeofence({identifier})`. */
    @JvmStatic
    fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>) {
        call(context, "removeGeofence", callback) {
            removeGeofence(JSONObject().put("identifier", identifier))
            Unit
        }
    }

    /** JS `getGeofences()`; result: the JS `geofences` array. */
    @JvmStatic
    fun getGeofences(context: Context, callback: NativeCallback<JSONArray>) {
        call(context, "getGeofences", callback) { getGeofences().getJSONArray("geofences") }
    }

    // ---- plumbing

    /** Test seam: replaces the components the calls run on. */
    @VisibleForTesting
    @Volatile
    internal var backendOverride: ((Context) -> NativeBackend)? = null

    @Volatile
    private var cached: NativeBackend? = null

    private fun backend(context: Context): NativeBackend {
        backendOverride?.let { return it(context) }
        val components = Components.get(context)
        // Components.get() publishes a new instance before its bootstrap() has run NativeListeners.install, and runs
        // bootstrap() while it holds the lock of the Components companion object. If another thread is still inside
        // that bootstrap, taking the same lock once waits until it has finished, so a native call can never emit a
        // record before the manifest listeners are connected. (Uncontended, the lock costs nothing measurable.)
        synchronized(Components) { }
        cached?.takeIf { it.owner === components }?.let { return it }
        return NativeBackend(ComponentServices(components), components.scope, owner = components).also { cached = it }
    }

    /**
     * Runs [block] on the backend's scope and delivers its result, or its failure mapped by [failureOf], to
     * [callback] on the `LT-native` thread. The callback is invoked exactly once: also when the scope is already
     * cancelled or is cancelled while the call runs (then the failure is INTERNAL).
     */
    private fun <T> call(
        context: Context,
        method: String,
        callback: NativeCallback<T>,
        block: suspend PluginHandlers.() -> T,
    ) {
        val settled = AtomicBoolean(false)
        val settle = { result: Result<T> -> if (settled.compareAndSet(false, true)) deliver(method, callback, result) }
        try {
            val backend = backend(context)
            backend.scope.launch {
                val result = try {
                    Result.success(backend.handlers.block())
                } catch (t: Throwable) { // also Errors and our own cancellation: the callback must always run
                    Result.failure(failureOf(method, t, shutDown = t is CancellationException && !isActive))
                }
                settle(result)
            }.invokeOnCompletion { cause ->
                // Cancelled before the block ran (the scope was already cancelled): nothing settled the call yet.
                if (cause != null && !settled.get()) settle(Result.failure(failureOf(method, cause, shutDown = true)))
            }
        } catch (t: Throwable) {
            settle(Result.failure(failureOf(method, t, shutDown = false)))
        }
    }

    /**
     * Copies [json] now, so a caller that changes it afterwards does not affect the call. A JSONObject that cannot be
     * serialized (for example one that contains itself) fails the call with INVALID_ARGUMENT.
     */
    private fun <T> copyOf(json: JSONObject, method: String, callback: NativeCallback<T>): JSONObject? = try {
        JSONObject(json.toString())
    } catch (t: Throwable) { // also StackOverflowError: never throw to the caller, always invoke the callback
        Logger.d(TAG, "LocationTrackingNative.$method: the JSON argument cannot be serialized ($t)")
        deliver(method, callback, Result.failure(TrackingException(ErrorCode.INVALID_ARGUMENT, "invalid JSON argument: $t", t)))
        null
    }

    /**
     * The failure delivered for [t], with the JS bridge's mapping ([Rejection.of]): a [TrackingException] keeps its
     * code, anything else is INTERNAL. [shutDown] is true when the plugin's own scope was cancelled (the components
     * were shut down), which gets its own message. Logged like the bridge logs a rejection.
     */
    private fun failureOf(method: String, t: Throwable, shutDown: Boolean): TrackingException {
        val failure = when {
            t is TrackingException -> t
            shutDown -> TrackingException(
                ErrorCode.INTERNAL,
                "LocationTrackingNative.$method did not complete: the plugin's components were shut down",
                t,
            )
            else -> Rejection.of(t).let { TrackingException(it.code, it.message, t) }
        }
        if (failure.code == ErrorCode.INTERNAL) {
            Logger.e(TAG, "LocationTrackingNative.$method failed", t)
        } else {
            Logger.d(TAG, "LocationTrackingNative.$method failed: ${failure.code} ${failure.message}")
        }
        return failure
    }

    private fun <T> deliver(method: String, callback: NativeCallback<T>, result: Result<T>) {
        NativeThread.post {
            try {
                callback.onResult(result)
            } catch (t: Throwable) {
                Logger.e(TAG, "the callback of LocationTrackingNative.$method threw", t)
            }
        }
    }
}

/**
 * What [LocationTrackingNative] calls run on: the bridge's handlers over [services] with their own ready flag (always
 * true, so the NOT_READY rule does not apply and the JS bridge's flag is never set), launched on [scope]. [owner] is
 * the [Components] instance the backend was built from (the facade builds a new backend after a reset).
 */
internal class NativeBackend(
    services: BridgeServices,
    val scope: CoroutineScope,
    val owner: Any? = null,
) {
    val handlers = PluginHandlers(services, readyFlag = AtomicBoolean(true))
}
