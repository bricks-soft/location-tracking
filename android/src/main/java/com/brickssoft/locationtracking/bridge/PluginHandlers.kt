package com.brickssoft.locationtracking.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import com.brickssoft.locationtracking.config.ConfigJson
import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.Subscription
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.BatteryOptimizationStatus
import com.brickssoft.locationtracking.model.BatteryOptimizationStatusJson
import com.brickssoft.locationtracking.model.DeviceInfoJson
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.HeartbeatStatusJson
import com.brickssoft.locationtracking.model.PermissionStatusJson
import com.brickssoft.locationtracking.model.PowerManagerInfoJson
import com.brickssoft.locationtracking.model.ProviderStateJson
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.model.SensorsJson
import com.brickssoft.locationtracking.permission.PermissionHost
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The logic of every `LocationTracking` plugin method, testable without a Capacitor bridge: options come in as the
 * call's JSONObject, results go out as the JS shape (null = `void`), and failures are thrown as
 * [TrackingException] (the plugin maps them to `call.reject(message, code.name)`).
 *
 * Every method except [ALLOWED_BEFORE_READY] throws NOT_READY until [ready] has resolved in this process.
 * Suspend methods run on the caller's coroutine (the plugin uses `Components.scope`); UI work (settings screens,
 * permission requests, the email chooser) is moved to `dispatchers.main`.
 *
 * @param activityProvider the plugin's current activity, if any.
 * @param readyFlag set once `ready()` resolved; process-wide by default.
 */
internal class PluginHandlers(
    private val services: BridgeServices,
    private val activityProvider: () -> Activity? = { null },
    private val readyFlag: AtomicBoolean = processReady,
    val watches: WatchRegistry = WatchRegistry(),
) {
    constructor(components: Components) : this(ComponentServices(components))

    /** Serializes permission requests (see [requestPermissions]). */
    private val permissionRequests = Mutex()

    /** True once `ready()` has resolved (in this process, unless a custom flag was injected). */
    val isReady: Boolean get() = readyFlag.get()

    // ---- lifecycle and config

    /** `ready({config?, reset = true})` -> State. */
    suspend fun ready(options: JSONObject): JSONObject {
        gate("ready")
        val config = OptionParsers.optObject(options, "config")
        val reset = OptionParsers.optBoolean(options, "reset") ?: true
        val state = services.engine.ready(config, reset)
        readyFlag.set(true)
        return stateJson(state)
    }

    /** `setConfig({config})` -> State. */
    suspend fun setConfig(options: JSONObject): JSONObject {
        gate("setConfig")
        return stateJson(services.engine.setConfig(OptionParsers.requireObject(options, "config")))
    }

    /** `reset({config?})` -> State. */
    suspend fun reset(options: JSONObject): JSONObject {
        gate("reset")
        return stateJson(services.engine.reset(OptionParsers.optObject(options, "config")))
    }

    fun getState(): JSONObject {
        gate("getState")
        return stateJson(services.engine.state())
    }

    suspend fun start(): JSONObject {
        gate("start")
        return stateJson(services.engine.start())
    }

    suspend fun startGeofences(): JSONObject {
        gate("startGeofences")
        return stateJson(services.engine.startGeofences())
    }

    suspend fun stop(): JSONObject {
        gate("stop")
        return stateJson(services.engine.stop())
    }

    /** `changePace({isMoving})` -> void. */
    suspend fun changePace(options: JSONObject): JSONObject? {
        gate("changePace")
        services.engine.changePace(OptionParsers.requireBoolean(options, "isMoving"))
        return null
    }

    // ---- positions

    /** `getCurrentPosition(options?)` -> Location. */
    suspend fun getCurrentPosition(options: JSONObject): JSONObject {
        gate("getCurrentPosition")
        val record = services.positions.getCurrentPosition(OptionParsers.currentPositionOptions(options))
        return RecordJson.toJson(record)
    }

    /**
     * `watchPosition(options, callback)`: registers [target] under [id] (the call's callback id) and starts the
     * watch. Locations and errors go to [target] until [clearWatch] or [releaseAllWatches].
     */
    fun watchPosition(id: String, options: JSONObject, target: WatchTarget) {
        gate("watchPosition")
        if (id.isBlank()) OptionParsers.invalid("watchPosition needs a callback id")
        val watchOptions = OptionParsers.watchPositionOptions(options)
        watches.add(id, target)
        try {
            services.positions.watchPosition(id, watchOptions) { record, error -> deliverWatch(id, record, error) }
        } catch (e: Exception) {
            watches.release(id)
            throw e
        }
    }

    /**
     * `clearWatch({id})` -> void; unknown ids are ignored. The position watch is stopped first; the JS callback is
     * released even if that fails.
     */
    fun clearWatch(options: JSONObject): JSONObject? {
        gate("clearWatch")
        val id = OptionParsers.requireString(options, "id")
        try {
            services.positions.clearWatch(id)
        } finally {
            watches.release(id)
        }
        return null
    }

    /** Releases every watch of this plugin instance (handleOnDestroy). */
    fun releaseAllWatches() {
        val ids = watches.releaseAll()
        if (ids.isEmpty()) return
        val positions = services.positions
        for (id in ids) {
            try {
                positions.clearWatch(id)
            } catch (e: Exception) {
                Logger.w(TAG, "clearWatch($id) failed", e)
            }
        }
    }

    private fun deliverWatch(id: String, record: Record?, error: TrackingException?) {
        val target = watches[id] ?: return
        try {
            when {
                record != null -> target.deliver(RecordJson.toJson(record))
                error != null -> target.fail(error.code, error.message ?: error.code.name)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "watch $id delivery failed", e)
        }
    }

    // ---- odometer

    fun getOdometer(): JSONObject {
        gate("getOdometer")
        return odometerJson()
    }

    /** `setOdometer({odometer})`: meters, >= 0. */
    fun setOdometer(options: JSONObject): JSONObject {
        gate("setOdometer")
        val value = OptionParsers.requireNumber(options, "odometer")
        if (value < 0) OptionParsers.invalid("'odometer' must be >= 0")
        services.odometer.set(value)
        return odometerJson()
    }

    fun resetOdometer(): JSONObject {
        gate("resetOdometer")
        services.odometer.reset()
        return odometerJson()
    }

    private fun odometerJson(): JSONObject = JSONObject().put("odometer", services.odometer.value)

    // ---- records

    /** `getLocations({limit?})` -> `{locations}`, oldest first; no limit = all. */
    suspend fun getLocations(options: JSONObject): JSONObject {
        gate("getLocations")
        val limit = OptionParsers.optInt(options, "limit", min = 0) ?: -1
        return JSONObject().put("locations", RecordJson.toJsonArray(services.locationStore.list(limit)))
    }

    suspend fun getCount(): JSONObject {
        gate("getCount")
        return JSONObject().put("count", services.locationStore.count())
    }

    /**
     * `insertLocation({location})` -> `{uuid}`. The record is queued (and handed to the upload policy) like any
     * other record, but it does not count as tracking activity: no event, no runtime or heartbeat update.
     * Round 2: after a successful insert the record goes to `recordHooks` (native companion listeners receive it
     * in `onRecord`), because inserted records do not pass through the record sink. A failed insert rejects the
     * call and is not dispatched: the caller receives the error and may retry.
     */
    suspend fun insertLocation(options: JSONObject): JSONObject {
        gate("insertLocation")
        val input = OptionParsers.insertLocationInput(options)
        val record = try {
            services.recordFactory.fromExternal(input)
        } catch (e: JSONException) {
            throw TrackingException(ErrorCode.INVALID_ARGUMENT, e.message ?: "invalid location", e)
        } catch (e: IllegalArgumentException) {
            throw TrackingException(ErrorCode.INVALID_ARGUMENT, e.message ?: "invalid location", e)
        }
        services.locationStore.insert(record)
        services.recordHooks.dispatch(record)
        services.syncer.onRecordInserted(record)
        return JSONObject().put("uuid", record.uuid)
    }

    suspend fun destroyLocations(): JSONObject {
        gate("destroyLocations")
        return JSONObject().put("count", services.locationStore.deleteAll())
    }

    /** `destroyLocation({uuid})` -> `{deleted}`. */
    suspend fun destroyLocation(options: JSONObject): JSONObject {
        gate("destroyLocation")
        val uuid = OptionParsers.requireString(options, "uuid")
        return JSONObject().put("deleted", services.locationStore.delete(listOf(uuid)) > 0)
    }

    /** `sync()` -> `{locations}`: the uploaded records. */
    suspend fun sync(): JSONObject {
        gate("sync")
        return JSONObject().put("locations", RecordJson.toJsonArray(services.syncer.sync()))
    }

    // ---- geofences

    suspend fun addGeofence(options: JSONObject): JSONObject? {
        gate("addGeofence")
        val spec = OptionParsers.geofence(OptionParsers.requireObject(options, "geofence"))
        services.geofences.add(listOf(spec))
        return null
    }

    /** `addGeofences({geofences})`; an empty array is a no-op. */
    suspend fun addGeofences(options: JSONObject): JSONObject? {
        gate("addGeofences")
        val specs = OptionParsers.geofences(options)
        if (specs.isNotEmpty()) services.geofences.add(specs)
        return null
    }

    suspend fun removeGeofence(options: JSONObject): JSONObject? {
        gate("removeGeofence")
        services.geofences.remove(listOf(OptionParsers.requireString(options, "identifier")))
        return null
    }

    /**
     * `removeGeofences({identifiers?})`: no `identifiers` (missing or null) removes all; an empty array removes
     * nothing, so a computed list that happens to be empty never wipes every geofence.
     */
    suspend fun removeGeofences(options: JSONObject): JSONObject? {
        gate("removeGeofences")
        val array = OptionParsers.optArray(options, "identifiers")
        when {
            array == null -> services.geofences.removeAll()
            array.length() > 0 -> services.geofences.remove(identifiers(array))
        }
        return null
    }

    suspend fun getGeofences(): JSONObject {
        gate("getGeofences")
        return JSONObject().put("geofences", GeofenceJson.toJsonArray(services.geofences.list()))
    }

    /** `getGeofence({identifier})` -> `{geofence}` (null if unknown). */
    suspend fun getGeofence(options: JSONObject): JSONObject {
        gate("getGeofence")
        val spec = services.geofences.get(OptionParsers.requireString(options, "identifier"))
        return JSONObject().put("geofence", spec?.let { GeofenceJson.toJson(it) } ?: JSONObject.NULL)
    }

    suspend fun geofenceExists(options: JSONObject): JSONObject {
        gate("geofenceExists")
        val spec = services.geofences.get(OptionParsers.requireString(options, "identifier"))
        return JSONObject().put("exists", spec != null)
    }

    private fun identifiers(array: JSONArray): List<String> = (0 until array.length()).map { i ->
        (array.opt(i) as? String)?.takeIf { it.isNotBlank() }
            ?: OptionParsers.invalid("identifiers[$i] must be a non-empty string")
    }

    // ---- heartbeat and device

    suspend fun getHeartbeatStatus(): JSONObject {
        gate("getHeartbeatStatus")
        return HeartbeatStatusJson.toJson(services.heartbeat.status())
    }

    fun getProviderState(): JSONObject {
        gate("getProviderState")
        return ProviderStateJson.toJson(services.device.providerState())
    }

    fun isPowerSaveMode(): JSONObject {
        gate("isPowerSaveMode")
        return JSONObject().put("isPowerSaveMode", services.device.isPowerSaveMode())
    }

    fun getBatteryOptimizationStatus(): JSONObject {
        gate("getBatteryOptimizationStatus")
        val device = services.device
        return BatteryOptimizationStatusJson.toJson(
            BatteryOptimizationStatus(
                isIgnoringBatteryOptimizations = device.isIgnoringBatteryOptimizations(),
                canScheduleExactAlarms = device.canScheduleExactAlarms(),
                isDeviceIdleMode = device.isDeviceIdleMode(),
            ),
        )
    }

    fun getPowerManagerInfo(): JSONObject {
        gate("getPowerManagerInfo")
        return PowerManagerInfoJson.toJson(services.deviceSettings.powerManagerInfo())
    }

    fun getDeviceInfo(): JSONObject {
        gate("getDeviceInfo")
        return DeviceInfoJson.toJson(services.deviceInfo.deviceInfo())
    }

    fun getSensors(): JSONObject {
        gate("getSensors")
        return SensorsJson.toJson(services.deviceInfo.sensors())
    }

    // ---- settings screens (main thread) -> {opened}

    suspend fun openBatteryOptimizationSettings(): JSONObject =
        openSettings("openBatteryOptimizationSettings") { services.deviceSettings.openBatteryOptimizationSettings(it) }

    suspend fun openPowerManagerSettings(): JSONObject =
        openSettings("openPowerManagerSettings") { services.deviceSettings.openPowerManagerSettings(it) }

    suspend fun openLocationSettings(): JSONObject =
        openSettings("openLocationSettings") { services.deviceSettings.openLocationSettings(it) }

    suspend fun openAppSettings(): JSONObject =
        openSettings("openAppSettings") { services.deviceSettings.openAppSettings(it) }

    private suspend fun openSettings(method: String, open: (Activity?) -> Boolean): JSONObject {
        gate(method)
        val opened = withContext(services.dispatchers.main) { open(activity()) }
        return JSONObject().put("opened", opened)
    }

    // ---- permissions -> PermissionStatus

    fun checkPermissions(): JSONObject {
        gate("checkPermissions")
        return permissionStatusJson(emptyMap())
    }

    /**
     * `requestPermissions({permissions?})`: runs `PermissionManager.request` on the main thread through [host] and
     * resolves with the resulting status (entries reported by the request win over `permissions.status()`).
     * Requests are serialized: Android answers an overlapping request at once with an empty result, and Capacitor
     * matches results to calls in FIFO order, so concurrent requests would receive each other's results.
     */
    suspend fun requestPermissions(options: JSONObject, host: PermissionHost): JSONObject {
        gate("requestPermissions")
        val types = OptionParsers.permissionTypes(options)
        if (types.isEmpty()) return permissionStatusJson(emptyMap())
        val result = permissionRequests.withLock {
            val rationale = services.configStore.config.value.backgroundPermissionRationale
            withContext(services.dispatchers.main) {
                suspendCancellableCoroutine { continuation ->
                    val settled = AtomicBoolean(false)
                    services.permissions.request(host, types, rationale) { status ->
                        if (settled.compareAndSet(false, true)) continuation.resume(status)
                    }
                }
            }
        }
        return permissionStatusJson(result)
    }

    private fun permissionStatusJson(overrides: Map<PermissionType, PermissionState>): JSONObject =
        PermissionStatusJson.toJson(services.permissions.status() + overrides)

    // ---- log

    /** `log({level, message})` -> void; written through [Logger] (logcat and the log file). */
    fun log(options: JSONObject): JSONObject? {
        gate("log")
        val level = OptionParsers.logLevel(options)
        val message = OptionParsers.optString(options, "message") ?: OptionParsers.invalid("'message' is required")
        Logger.log(level, JS_TAG, message)
        return null
    }

    suspend fun getLog(options: JSONObject): JSONObject {
        gate("getLog")
        return JSONObject().put("log", services.logStore.read(OptionParsers.logQuery(options)))
    }

    suspend fun destroyLog(): JSONObject? {
        gate("destroyLog")
        services.logStore.destroy()
        return null
    }

    /** `uploadLog({url, headers?, params?})` -> `{success, status}`. */
    suspend fun uploadLog(options: JSONObject): JSONObject {
        gate("uploadLog")
        val o = OptionParsers.uploadLogOptions(options)
        val result = services.logStore.upload(o.url, o.headers, o.paramsJson)
        return JSONObject().put("success", result.success).put("status", result.status)
    }

    /** `emailLog({email, subject?})` -> void: opens a chooser with the log attached; NO_ACTIVITY without UI. */
    suspend fun emailLog(options: JSONObject): JSONObject? {
        gate("emailLog")
        val email = OptionParsers.requireString(options, "email")
        val subject = OptionParsers.optString(options, "subject")
        requireActivity()
        val intent = services.logStore.prepareEmail(email, subject)
        withContext(services.dispatchers.main) {
            val chooser = Intent.createChooser(intent, subject?.takeIf { it.isNotBlank() } ?: EMAIL_CHOOSER_TITLE)
            try {
                requireActivity().startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                throw TrackingException(ErrorCode.UNAVAILABLE, "No app can send the log", e)
            }
        }
        return null
    }

    // ---- events and lifecycle

    /**
     * Forwards every bus event to [notify] as (JS event name, payload builder); cancel the result to stop. The
     * payload is built only when [notify] calls the builder, so an event without JS listeners costs nothing and the
     * emitting (engine) thread never serializes records.
     */
    fun forwardEvents(notify: (name: String, payload: () -> JSONObject) -> Unit): Subscription =
        services.events.subscribe { event ->
            try {
                notify(EventJson.name(event)) { EventJson.payload(event) }
            } catch (e: Exception) {
                Logger.e(TAG, "failed to forward ${event::class.simpleName}", e)
            }
        }

    /** handleOnResume: re-checks the provider state (catches changes made while the app was in the background). */
    suspend fun onResume() {
        try {
            services.device.checkProviderState(RESUME_REASON)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "provider state check on resume failed", e)
        }
    }

    // ---- helpers

    private fun gate(method: String) {
        if (method !in ALLOWED_BEFORE_READY && !readyFlag.get()) {
            throw TrackingException(ErrorCode.NOT_READY, "$method() requires ready() to have resolved first")
        }
    }

    private fun stateJson(state: State): JSONObject = ConfigJson.stateToJson(state)

    private fun activity(): Activity? = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun requireActivity(): Activity =
        activity() ?: throw TrackingException(ErrorCode.NO_ACTIVITY, "No activity is attached to the plugin")

    companion object {
        private const val TAG = "LT.Bridge"

        /** Log tag of messages written by JS `log()`. */
        const val JS_TAG = "LT.JS"

        /** The `checkProviderState` reason used on handleOnResume. */
        const val RESUME_REASON = "resume"

        const val EMAIL_CHOOSER_TITLE = "Send location tracking log"

        /** Methods that work before `ready()` (architecture §1, NOT_READY rule). */
        val ALLOWED_BEFORE_READY: Set<String> = setOf(
            "ready",
            "getState",
            "checkPermissions",
            "requestPermissions",
            "getDeviceInfo",
            "getSensors",
            "log",
            "getLog",
            "getProviderState",
            "openBatteryOptimizationSettings",
            "openPowerManagerSettings",
            "openLocationSettings",
            "openAppSettings",
        )

        /** Process-wide "ready() resolved" flag. */
        internal val processReady = AtomicBoolean(false)
    }
}
