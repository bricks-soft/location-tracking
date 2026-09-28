package com.brickssoft.premisemonitor

import android.util.Log
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Logcat only (this plugin has no log file). Never throws. */
internal object PmLog {
    fun i(tag: String, message: String) = log { Log.i(tag, message) }

    fun w(tag: String, message: String, error: Throwable? = null) = log { Log.w(tag, message, error) }

    fun e(tag: String, message: String, error: Throwable? = null) = log { Log.e(tag, message, error) }

    private inline fun log(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // logcat unavailable (plain JVM test); ignore
        }
    }
}

/** Great-circle distance, with the same mean earth radius as the tracking plugin's geofence code. */
internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Rounds to 0.1 m for the `distance_m` field. */
    fun round(meters: Double): Double = Math.round(meters * 10.0) / 10.0
}

/** The fix of a wire record (`coords` object), or null when the record has no usable coordinates. */
internal data class Fix(val latitude: Double, val longitude: Double, val accuracy: Double) {
    companion object {
        fun of(record: JSONObject): Fix? {
            val coords = record.optJSONObject("coords") ?: return null
            val latitude = coords.optDouble("latitude", Double.NaN)
            val longitude = coords.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            val accuracy = coords.optDouble("accuracy", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
            return Fix(latitude, longitude, accuracy)
        }
    }
}
