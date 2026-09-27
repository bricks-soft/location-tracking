package com.brickssoft.locationtracking.model

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class TrackedLocationTest {
    @Test
    fun `copies every field the fix has`() {
        val l = Location("fused").apply {
            latitude = 24.7136
            longitude = 46.6753
            accuracy = 5.2f
            altitude = 612.3
            verticalAccuracyMeters = 3f
            speed = 13.4f
            speedAccuracyMetersPerSecond = 0.8f
            bearing = 271.5f
            bearingAccuracyDegrees = 5f
            time = 1_790_417_730_123L
            elapsedRealtimeNanos = 42_000_000L
        }

        val t = TrackedLocation.from(l)

        assertEquals(
            TrackedLocation(
                latitude = 24.7136,
                longitude = 46.6753,
                accuracy = 5.2f,
                altitude = 612.3,
                altitudeAccuracy = 3f,
                speed = 13.4f,
                speedAccuracy = 0.8f,
                heading = 271.5f,
                headingAccuracy = 5f,
                time = 1_790_417_730_123L,
                elapsedRealtimeNanos = 42_000_000L,
                provider = "fused",
                isMock = false,
            ),
            t,
        )
    }

    @Test
    fun `missing optional fields are null`() {
        val l = Location("gps").apply {
            latitude = 1.0
            longitude = 2.0
            accuracy = 10f
            time = 1_000L
        }

        val t = TrackedLocation.from(l)

        assertNull(t.altitude)
        assertNull(t.altitudeAccuracy)
        assertNull(t.speed)
        assertNull(t.speedAccuracy)
        assertNull(t.heading)
        assertNull(t.headingAccuracy)
        assertFalse(t.isMock)
    }
}
