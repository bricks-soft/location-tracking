package com.brickssoft.locationtracking.model

data class LatLng(val latitude: Double, val longitude: Double)

/**
 * A geofence. A circle has [latitude], [longitude] and [radius]. A polygon has [vertices]; its latitude, longitude
 * and radius describe the enclosing circle, which stays 0 until the GeofenceManager computes it.
 *
 * @property loiteringDelay ms.
 * @property extras JSON object text, or null.
 */
data class GeofenceSpec(
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val vertices: List<LatLng>? = null,
    val notifyOnEntry: Boolean = true,
    val notifyOnExit: Boolean = true,
    val notifyOnDwell: Boolean = false,
    val loiteringDelay: Long = 30_000,
    val extras: String? = null,
) {
    val isPolygon get() = vertices != null
}
