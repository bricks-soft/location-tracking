package com.brickssoft.locationtracking.model

import android.os.Build

/**
 * A location fix, independent of the backend that produced it.
 * Optional fields are null when the fix does not carry them.
 *
 * @property time fix time, epoch ms.
 * @property elapsedRealtimeNanos fix time on the elapsed-realtime clock (0 if unknown). Not part of the wire format.
 * @property provider OS provider name (e.g. "gps", "fused"). Not part of the wire format.
 */
data class TrackedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val altitudeAccuracy: Float? = null,
    val speed: Float? = null,
    val speedAccuracy: Float? = null,
    val heading: Float? = null,
    val headingAccuracy: Float? = null,
    val time: Long,
    val elapsedRealtimeNanos: Long = 0,
    val provider: String? = null,
    val isMock: Boolean = false,
) {
    companion object {
        /** Converts an Android [android.location.Location], keeping only the fields it actually has. */
        fun from(l: android.location.Location): TrackedLocation {
            val api26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            return TrackedLocation(
                latitude = l.latitude,
                longitude = l.longitude,
                accuracy = l.accuracy,
                altitude = if (l.hasAltitude()) l.altitude else null,
                altitudeAccuracy = if (api26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
                speed = if (l.hasSpeed()) l.speed else null,
                speedAccuracy = if (api26 && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                heading = if (l.hasBearing()) l.bearing else null,
                headingAccuracy = if (api26 && l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
                time = l.time,
                elapsedRealtimeNanos = l.elapsedRealtimeNanos,
                provider = l.provider,
                isMock = isMock(l),
            )
        }

        @Suppress("DEPRECATION")
        private fun isMock(l: android.location.Location): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else l.isFromMockProvider
    }
}
