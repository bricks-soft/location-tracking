package com.brickssoft.locationtracking.provider.gms

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.GeofenceStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GmsErrorsTest {
    @Test
    fun `status codes map to error codes`() {
        val expected = mapOf(
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE to ErrorCode.UNAVAILABLE,
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES to ErrorCode.TOO_MANY_GEOFENCES,
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS to ErrorCode.TOO_MANY_GEOFENCES,
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION to ErrorCode.PERMISSION_DENIED,
            GeofenceStatusCodes.GEOFENCE_REQUEST_TOO_FREQUENT to ErrorCode.UNAVAILABLE,
            CommonStatusCodes.TIMEOUT to ErrorCode.TIMEOUT,
            CommonStatusCodes.DEVELOPER_ERROR to ErrorCode.INTERNAL,
            CommonStatusCodes.INTERNAL_ERROR to ErrorCode.INTERNAL,
            CommonStatusCodes.API_NOT_CONNECTED to ErrorCode.UNAVAILABLE,
            CommonStatusCodes.NETWORK_ERROR to ErrorCode.UNAVAILABLE,
            CommonStatusCodes.RESOLUTION_REQUIRED to ErrorCode.UNAVAILABLE,
        )
        for ((status, code) in expected) {
            val mapped = GmsErrors.map(ApiException(Status(status)), "op")
            assertEquals("status $status", code, mapped.code)
            assertTrue(mapped.message!!, mapped.message!!.startsWith("op failed: "))
        }
    }

    @Test
    fun `message names the status`() {
        val error = ApiException(Status(GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES))

        val mapped = GmsErrors.map(error, "addGeofences")

        assertEquals("addGeofences failed: GEOFENCE_TOO_MANY_GEOFENCES (1001)", mapped.message)
    }

    @Test
    fun `geofence not available explains the likely cause`() {
        val mapped = GmsErrors.map(ApiException(Status(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)), "addGeofences")

        assertEquals(
            "addGeofences failed: GEOFENCE_NOT_AVAILABLE (1000); " +
                "location is off or Google Location Accuracy is disabled",
            mapped.message,
        )
    }

    @Test
    fun `non-api exceptions`() {
        val tracking = TrackingException(ErrorCode.TIMEOUT, "t")
        assertSame(tracking, GmsErrors.map(tracking, "op"))
        assertEquals(ErrorCode.PERMISSION_DENIED, GmsErrors.map(SecurityException("x"), "op").code)
        assertEquals(ErrorCode.INVALID_ARGUMENT, GmsErrors.map(IllegalArgumentException("Invalid radius"), "op").code)
        assertEquals(ErrorCode.UNAVAILABLE, GmsErrors.map(IllegalStateException("not connected"), "op").code)
        assertEquals(ErrorCode.INTERNAL, GmsErrors.map(RuntimeException("x"), "op").code)
    }

    @Test
    fun `mapped exception keeps the cause`() {
        val cause = SecurityException("x")

        assertSame(cause, GmsErrors.map(cause, "op").cause)
    }
}
