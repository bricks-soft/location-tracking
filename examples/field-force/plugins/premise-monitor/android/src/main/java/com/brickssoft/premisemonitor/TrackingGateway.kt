package com.brickssoft.premisemonitor

import android.content.Context
import com.brickssoft.locationtracking.api.LocationTrackingNative
import com.brickssoft.locationtracking.api.NativeCallback
import org.json.JSONArray
import org.json.JSONObject

/** The calls PremiseMonitor makes into the tracking plugin; an interface so tests can replace the plugin. */
internal interface TrackingGateway {
    fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>)

    fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>)

    fun getGeofences(context: Context, callback: NativeCallback<JSONArray>)
}

/** The real gateway: the tracking plugin's public native API (callbacks arrive on its `LT-native` thread). */
internal object LocationTrackingGateway : TrackingGateway {
    override fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>) =
        LocationTrackingNative.addGeofence(context, geofence, callback)

    override fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>) =
        LocationTrackingNative.removeGeofence(context, identifier, callback)

    override fun getGeofences(context: Context, callback: NativeCallback<JSONArray>) =
        LocationTrackingNative.getGeofences(context, callback)
}
