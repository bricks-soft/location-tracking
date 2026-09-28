package com.brickssoft.locationtracking.example.e2e

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import org.json.JSONObject

/**
 * Debug command `otherAppLocation {enabled, intervalMs = 1000}`: requests (or stops requesting) GPS updates through the
 * platform `LocationManager`, the way another app on the phone would.
 *
 * Why: while stationary the plugin turns GPS off and only listens passively. A real phone still produces fixes (network
 * location, other apps, Google Play services' geofencing), but the emulator produces a fix only while some client asks
 * the GPS provider for one. With this request on, the emulator's `geo fix` positions reach the plugin's passive request
 * and Google Play services' geofencer, so the GPS-off exit paths can be tested (P-H03, P-P04). The request is made from
 * the app's own process: `dumpsys location` attributes it to the app, so scenarios that check "no GPS request" (P-H02,
 * F-05) must not turn it on.
 *
 * Main thread only. The listener lives as long as the process; a killed process drops it.
 */
internal object OtherAppLocation {
    private var listener: LocationListener? = null
    private var fixes = 0

    /** Applies the request and returns `{enabled, intervalMs, fixes}` (`fixes` = fixes received since enabled). */
    @SuppressLint("MissingPermission") // the e2e kit grants location permissions before it sends this command
    fun set(context: Context, enabled: Boolean, intervalMs: Long): JSONObject {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listener?.let { manager.removeUpdates(it) }
        listener = null
        if (enabled) {
            fixes = 0
            val l = LocationListener { _: Location -> fixes++ }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, l, Looper.getMainLooper())
            listener = l
        }
        return JSONObject().put("enabled", listener != null).put("intervalMs", intervalMs).put("fixes", fixes)
    }
}
