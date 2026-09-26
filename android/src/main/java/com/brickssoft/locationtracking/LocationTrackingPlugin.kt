// STUB — owned by Unit 2 (Android bridge). Replace this implementation.
// The @CapacitorPlugin annotation (name and permission aliases) is fixed by the scaffold; keep it as is.
package com.brickssoft.locationtracking

import android.Manifest
import com.brickssoft.locationtracking.core.Components
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission

/** Capacitor bridge for `LocationTracking`. Stub: every method rejects with "Not implemented yet". */
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
    override fun load() {
        Components.get(context)
    }

    @PluginMethod
    fun ready(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun setConfig(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun reset(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun start(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun startGeofences(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun changePace(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun watchPosition(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun clearWatch(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getOdometer(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun setOdometer(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun resetOdometer(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getLocations(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getCount(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun insertLocation(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun destroyLocations(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun destroyLocation(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun sync(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun addGeofence(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun addGeofences(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun removeGeofence(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun removeGeofences(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getGeofences(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getGeofence(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun geofenceExists(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getHeartbeatStatus(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getProviderState(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun isPowerSaveMode(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getBatteryOptimizationStatus(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun openBatteryOptimizationSettings(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getPowerManagerInfo(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun openPowerManagerSettings(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getDeviceInfo(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getSensors(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun log(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun getLog(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun destroyLog(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun uploadLog(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    @PluginMethod
    fun emailLog(call: PluginCall) {
        call.unimplemented(NOT_IMPLEMENTED)
    }

    private companion object {
        const val NOT_IMPLEMENTED = "Not implemented yet"
    }
}
