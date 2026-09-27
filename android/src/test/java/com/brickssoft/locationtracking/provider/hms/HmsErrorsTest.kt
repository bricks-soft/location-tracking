package com.brickssoft.locationtracking.provider.hms

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.huawei.hms.location.GeofenceErrorCodes
import com.huawei.hms.support.api.location.common.exception.LocationStatusCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HmsErrorsTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `copied status codes match the HMS SDK`() {
        assertEquals(LocationStatusCode.ARGUMENTS_EMPTY, HmsStatusCodes.ARGUMENTS_EMPTY)
        assertEquals(LocationStatusCode.ARGUMENTS_INVALID, HmsStatusCodes.ARGUMENTS_INVALID)
        assertEquals(
            LocationStatusCode.NETWORK_LOCATION_SERVICES_DISABLED,
            HmsStatusCodes.NETWORK_LOCATION_SERVICES_DISABLED,
        )
        assertEquals(LocationStatusCode.LOCATION_ENABLE_OFF, HmsStatusCodes.LOCATION_ENABLE_OFF)
        assertEquals(LocationStatusCode.GEOFENCE_NOT_AVAILABLE, HmsStatusCodes.GEOFENCE_UNAVAILABLE)
        assertEquals(LocationStatusCode.PARAM_ERROR_EMPTY, HmsStatusCodes.PARAM_ERROR_EMPTY)
        assertEquals(LocationStatusCode.PARAM_ERROR_INVALID, HmsStatusCodes.PARAM_ERROR_INVALID)
        assertEquals(LocationStatusCode.PERMISSION_DENIED, HmsStatusCodes.PERMISSION_DENIED)
        assertEquals(LocationStatusCode.NO_PRECISE_LOCATION_PERMISSION, HmsStatusCodes.NO_PRECISE_LOCATION_PERMISSION)
        assertEquals(LocationStatusCode.GEOFENCE_TOO_MANY_GEOFENCES, HmsStatusCodes.GEOFENCE_NUMBER_OVER_LIMIT)
        assertEquals(LocationStatusCode.GEOFENCE_TOO_MANY_PENDING_INTENTS, HmsStatusCodes.GEOFENCE_PENDINGINTENT_OVER_LIMIT)
        assertEquals(
            LocationStatusCode.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION,
            HmsStatusCodes.GEOFENCE_INSUFFICIENT_PERMISSION,
        )
    }

    @Test
    fun `status codes map to error codes`() {
        val expected = mapOf(
            LocationStatusCode.PERMISSION_DENIED to ErrorCode.PERMISSION_DENIED,
            LocationStatusCode.NO_PRECISE_LOCATION_PERMISSION to ErrorCode.PERMISSION_DENIED,
            GeofenceErrorCodes.GEOFENCE_INSUFFICIENT_PERMISSION to ErrorCode.PERMISSION_DENIED,
            GeofenceErrorCodes.GEOFENCE_NUMBER_OVER_LIMIT to ErrorCode.TOO_MANY_GEOFENCES,
            GeofenceErrorCodes.GEOFENCE_PENDINGINTENT_OVER_LIMIT to ErrorCode.TOO_MANY_GEOFENCES,
            LocationStatusCode.LOCATION_ENABLE_OFF to ErrorCode.LOCATION_DISABLED,
            LocationStatusCode.NETWORK_LOCATION_SERVICES_DISABLED to ErrorCode.LOCATION_DISABLED,
            GeofenceErrorCodes.GEOFENCE_UNAVAILABLE to ErrorCode.LOCATION_DISABLED,
            LocationStatusCode.ARGUMENTS_INVALID to ErrorCode.INVALID_ARGUMENT,
            LocationStatusCode.PARAM_ERROR_INVALID to ErrorCode.INVALID_ARGUMENT,
            GeofenceErrorCodes.GEOFENCE_REQUEST_TOO_OFTEN to ErrorCode.UNAVAILABLE,
            LocationStatusCode.LOCATION_INTERNAL_ERROR to ErrorCode.UNAVAILABLE,
            907135000 to ErrorCode.UNAVAILABLE, // HMS Core: arguments invalid (e.g. missing AGC config)
            907135003 to ErrorCode.UNAVAILABLE, // HMS Core: client API invalid (HMS Core too old)
        )
        for ((status, code) in expected) assertEquals("status $status", code, errorCodeForStatus(status))
    }

    @Test
    fun `throwables map to tracking exceptions`() {
        val api = Hms.apiException(GeofenceErrorCodes.GEOFENCE_NUMBER_OVER_LIMIT)
        val mapped = api.toTrackingException("createGeofenceList")
        assertEquals(ErrorCode.TOO_MANY_GEOFENCES, mapped.code)
        assertSame(api, mapped.cause)
        assertTrue(mapped.message!!.contains("createGeofenceList"))
        assertTrue(mapped.message!!.contains("10201"))

        assertEquals(ErrorCode.PERMISSION_DENIED, SecurityException("x").toTrackingException("op").code)
        assertEquals(ErrorCode.INVALID_ARGUMENT, IllegalArgumentException("x").toTrackingException("op").code)
        assertEquals(ErrorCode.UNAVAILABLE, IllegalStateException("x").toTrackingException("op").code)
        assertEquals(ErrorCode.UNAVAILABLE, NoClassDefFoundError("x").toTrackingException("op").code)
        val original = TrackingException(ErrorCode.TIMEOUT, "t")
        assertSame(original, original.toTrackingException("op"))
    }
}
