package com.brickssoft.locationtracking

import android.Manifest
import com.brickssoft.locationtracking.bridge.CallResponder
import com.brickssoft.locationtracking.bridge.CallWatchTarget
import com.brickssoft.locationtracking.bridge.ComponentServices
import com.brickssoft.locationtracking.bridge.PermissionRequestLauncher
import com.brickssoft.locationtracking.bridge.PluginHandlers
import com.brickssoft.locationtracking.bridge.PluginPermissionHost
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.Subscription
import com.brickssoft.locationtracking.permission.CurrentActivityTracker
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Capacitor bridge for `LocationTracking`. A thin adapter: every method runs the matching [PluginHandlers] function
 * on `Components.scope` and settles the call (TrackingException -> `reject(message, code)`, anything else ->
 * INTERNAL). Tracking events from the EventBus are forwarded to JS listeners.
 */
@CapacitorPlugin(
    name = "LocationTracking",
    permissions = [
        Permission(
            strings = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION],
            alias = "location",
        ),
        Permission(strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION], alias = "backgroundLocation"),
        Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activityRecognition"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications"),
    ],
)
class LocationTrackingPlugin : Plugin() {
    private lateinit var components: Components
    private lateinit var handlers: PluginHandlers

    @Volatile
    private var eventSubscription: Subscription? = null

    /** `onDone` of the in-flight permission request of each call, by callback id. */
    private val pendingPermissionRequests = ConcurrentHashMap<String, () -> Unit>()

    /** Running `requestPermissions` coroutines by callback id, cancelled in [handleOnDestroy]. */
    private val permissionJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var destroyed = false

    override fun load() {
        components = Components.get(context)
        // The Activity is already created when plugins load, so the permission manager's lifecycle tracker would
        // only learn about it at its next lifecycle event; seed it for rationale checks in checkPermissions().
        activity?.let { CurrentActivityTracker.attach(context).remember(it) }
        handlers = PluginHandlers(
            ComponentServices(components),
            activityProvider = { PluginPermissionHost.usableActivity(this) },
        )
        eventSubscription = handlers.forwardEvents { name, payload -> notifyOnBridgeThread(name, payload) }
    }

    override fun handleOnResume() {
        super.handleOnResume()
        if (!::handlers.isInitialized) return
        components.scope.launch { handlers.onResume() }
    }

    /**
     * Stops event forwarding, releases every watch and ends in-flight permission requests: their calls are
     * abandoned (the WebView is going away), but each pending `onDone` still runs so the PermissionManager's request
     * completes; later steps of it complete at once because the host has no usable activity and no launcher.
     */
    override fun handleOnDestroy() {
        destroyed = true
        eventSubscription?.cancel()
        eventSubscription = null
        if (::handlers.isInitialized) handlers.releaseAllWatches()
        for (id in pendingPermissionRequests.keys.toList()) {
            val onDone = pendingPermissionRequests.remove(id) ?: continue
            try {
                onDone()
            } catch (t: Throwable) {
                Logger.w(TAG, "completing permission request $id on destroy failed", t)
            }
        }
        permissionJobs.values.forEach { it.cancel() }
        permissionJobs.clear()
        super.handleOnDestroy()
    }

    // ---- lifecycle and config

    @PluginMethod
    fun ready(call: PluginCall) {
        respond(call) { handlers.ready(it) }
    }

    @PluginMethod
    fun setConfig(call: PluginCall) {
        respond(call) { handlers.setConfig(it) }
    }

    @PluginMethod
    fun reset(call: PluginCall) {
        respond(call) { handlers.reset(it) }
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        respond(call) { handlers.getState() }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        respond(call) { handlers.start() }
    }

    @PluginMethod
    fun startGeofences(call: PluginCall) {
        respond(call) { handlers.startGeofences() }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        respond(call) { handlers.stop() }
    }

    @PluginMethod
    fun changePace(call: PluginCall) {
        respond(call) { handlers.changePace(it) }
    }

    // ---- positions

    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        respond(call) { handlers.getCurrentPosition(it) }
    }

    /**
     * Kept alive until `clearWatch`: the bridge saves the call and every location/error invokes the JS callback.
     * Kept alive before registering so an early delivery cannot release the call.
     */
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun watchPosition(call: PluginCall) {
        call.setKeepAlive(true)
        try {
            handlers.watchPosition(call.callbackId, optionsOf(call), CallWatchTarget(call) { bridge })
        } catch (t: Throwable) { // also Errors (e.g. NoClassDefFoundError of a missing SDK): never crash the bridge
            call.setKeepAlive(false)
            CallResponder.reject(call, t)
        }
    }

    @PluginMethod
    fun clearWatch(call: PluginCall) {
        respond(call) { handlers.clearWatch(it) }
    }

    // ---- odometer

    @PluginMethod
    fun getOdometer(call: PluginCall) {
        respond(call) { handlers.getOdometer() }
    }

    @PluginMethod
    fun setOdometer(call: PluginCall) {
        respond(call) { handlers.setOdometer(it) }
    }

    @PluginMethod
    fun resetOdometer(call: PluginCall) {
        respond(call) { handlers.resetOdometer() }
    }

    // ---- records

    @PluginMethod
    fun getLocations(call: PluginCall) {
        respond(call) { handlers.getLocations(it) }
    }

    @PluginMethod
    fun getCount(call: PluginCall) {
        respond(call) { handlers.getCount() }
    }

    @PluginMethod
    fun insertLocation(call: PluginCall) {
        respond(call) { handlers.insertLocation(it) }
    }

    @PluginMethod
    fun destroyLocations(call: PluginCall) {
        respond(call) { handlers.destroyLocations() }
    }

    @PluginMethod
    fun destroyLocation(call: PluginCall) {
        respond(call) { handlers.destroyLocation(it) }
    }

    @PluginMethod
    fun sync(call: PluginCall) {
        respond(call) { handlers.sync() }
    }

    // ---- geofences

    @PluginMethod
    fun addGeofence(call: PluginCall) {
        respond(call) { handlers.addGeofence(it) }
    }

    @PluginMethod
    fun addGeofences(call: PluginCall) {
        respond(call) { handlers.addGeofences(it) }
    }

    @PluginMethod
    fun removeGeofence(call: PluginCall) {
        respond(call) { handlers.removeGeofence(it) }
    }

    @PluginMethod
    fun removeGeofences(call: PluginCall) {
        respond(call) { handlers.removeGeofences(it) }
    }

    @PluginMethod
    fun getGeofences(call: PluginCall) {
        respond(call) { handlers.getGeofences() }
    }

    @PluginMethod
    fun getGeofence(call: PluginCall) {
        respond(call) { handlers.getGeofence(it) }
    }

    @PluginMethod
    fun geofenceExists(call: PluginCall) {
        respond(call) { handlers.geofenceExists(it) }
    }

    // ---- heartbeat and device

    @PluginMethod
    fun getHeartbeatStatus(call: PluginCall) {
        respond(call) { handlers.getHeartbeatStatus() }
    }

    @PluginMethod
    fun getProviderState(call: PluginCall) {
        respond(call) { handlers.getProviderState() }
    }

    @PluginMethod
    fun isPowerSaveMode(call: PluginCall) {
        respond(call) { handlers.isPowerSaveMode() }
    }

    @PluginMethod
    fun getBatteryOptimizationStatus(call: PluginCall) {
        respond(call) { handlers.getBatteryOptimizationStatus() }
    }

    @PluginMethod
    fun openBatteryOptimizationSettings(call: PluginCall) {
        respond(call) { handlers.openBatteryOptimizationSettings() }
    }

    @PluginMethod
    fun getPowerManagerInfo(call: PluginCall) {
        respond(call) { handlers.getPowerManagerInfo() }
    }

    @PluginMethod
    fun openPowerManagerSettings(call: PluginCall) {
        respond(call) { handlers.openPowerManagerSettings() }
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        respond(call) { handlers.openLocationSettings() }
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        respond(call) { handlers.openAppSettings() }
    }

    @PluginMethod
    fun getDeviceInfo(call: PluginCall) {
        respond(call) { handlers.getDeviceInfo() }
    }

    @PluginMethod
    fun getSensors(call: PluginCall) {
        respond(call) { handlers.getSensors() }
    }

    // ---- permissions

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        respond(call) { handlers.checkPermissions() }
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        val id = call.callbackId
        val host = PluginPermissionHost(
            this,
            launcher = PermissionRequestLauncher { aliases, onDone -> launchPermissionRequest(call, aliases, onDone) },
        )
        val job = respond(call, CoroutineStart.LAZY) { handlers.requestPermissions(it, host) }
        permissionJobs[id] = job
        job.invokeOnCompletion {
            permissionJobs.remove(id, job)
            pendingPermissionRequests.remove(id)
        }
        job.start()
    }

    /** Main thread (called by the PermissionManager through [PluginPermissionHost]). */
    private fun launchPermissionRequest(call: PluginCall, aliases: List<String>, onDone: () -> Unit) {
        if (destroyed) {
            onDone()
            return
        }
        val id = call.callbackId
        pendingPermissionRequests[id] = onDone
        try {
            requestPermissionForAliases(aliases.toTypedArray(), call, PERMISSION_CALLBACK)
        } catch (e: Exception) {
            pendingPermissionRequests.remove(id)
            throw e
        }
    }

    /** Resolved by name ([PERMISSION_CALLBACK]) through reflection by Capacitor; main thread. */
    @Suppress("unused")
    @PermissionCallback
    private fun onPermissionRequestResult(call: PluginCall?) {
        val id = call?.callbackId ?: return
        val onDone = pendingPermissionRequests.remove(id) ?: return
        onDone()
    }

    // ---- log

    @PluginMethod
    fun log(call: PluginCall) {
        respond(call) { handlers.log(it) }
    }

    @PluginMethod
    fun getLog(call: PluginCall) {
        respond(call) { handlers.getLog(it) }
    }

    @PluginMethod
    fun destroyLog(call: PluginCall) {
        respond(call) { handlers.destroyLog() }
    }

    @PluginMethod
    fun uploadLog(call: PluginCall) {
        respond(call) { handlers.uploadLog(it) }
    }

    @PluginMethod
    fun emailLog(call: PluginCall) {
        respond(call) { handlers.emailLog(it) }
    }

    // ---- plumbing

    /** Runs [block] on `Components.scope` and settles [call] with its result (null = void) or error. */
    private fun respond(
        call: PluginCall,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend (JSONObject) -> JSONObject?,
    ): Job = components.scope.launch(start = start) {
        try {
            CallResponder.resolve(call, block(optionsOf(call)))
        } catch (t: Throwable) { // also Errors (e.g. NoClassDefFoundError of a missing SDK): always settle the call
            // Our own cancellation (plugin destroyed / scope cancelled): nobody is listening any more.
            if (t is CancellationException && !isActive) return@launch
            CallResponder.reject(call, t)
        }
    }

    /**
     * Emits on the bridge's plugin thread, where Capacitor also adds and removes listeners, so the listener list is
     * never read and modified concurrently. The payload is built only if JS listens; events are dropped while no
     * bridge is attached.
     */
    private fun notifyOnBridgeThread(name: String, payload: () -> JSONObject) {
        val b = bridge ?: return
        b.execute {
            try {
                if (hasListeners(name)) notifyListeners(name, JSObject.fromJSONObject(payload()))
            } catch (t: Throwable) {
                Logger.e(TAG, "notifyListeners($name) failed", t)
            }
        }
    }

    private fun optionsOf(call: PluginCall): JSONObject = call.data ?: JSObject()

    private companion object {
        const val TAG = "LT.Plugin"

        /** Name of the `@PermissionCallback` method; Capacitor looks it up by name. */
        const val PERMISSION_CALLBACK = "onPermissionRequestResult"
    }
}
