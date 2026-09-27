package com.brickssoft.locationtracking.provider.gms

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.location.GeofenceStatusCodes

/** Maps Google Play services failures to [TrackingException]. */
internal object GmsErrors {
    /**
     * - [TrackingException] is returned unchanged.
     * - [SecurityException] and `GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION` become PERMISSION_DENIED.
     * - `GEOFENCE_TOO_MANY_GEOFENCES` and `GEOFENCE_TOO_MANY_PENDING_INTENTS` become TOO_MANY_GEOFENCES.
     * - `TIMEOUT` becomes TIMEOUT; `DEVELOPER_ERROR` and `INTERNAL_ERROR` become INTERNAL.
     * - Every other [ApiException] (e.g. `GEOFENCE_NOT_AVAILABLE`, `API_NOT_CONNECTED`) and [IllegalStateException]
     *   (GMS "not connected") become UNAVAILABLE.
     * - [IllegalArgumentException] (rejected by a GMS builder) becomes INVALID_ARGUMENT; anything else INTERNAL.
     */
    fun map(error: Throwable, operation: String): TrackingException = when (error) {
        is TrackingException -> error
        is SecurityException -> TrackingException(
            ErrorCode.PERMISSION_DENIED,
            "$operation: location permission denied",
            error,
        )
        is ApiException -> TrackingException(
            codeFor(error.statusCode),
            "$operation failed: ${describe(error.statusCode)}",
            error,
        )
        is IllegalArgumentException -> TrackingException(
            ErrorCode.INVALID_ARGUMENT,
            "$operation: ${error.message ?: "invalid argument"}",
            error,
        )
        is IllegalStateException -> TrackingException(
            ErrorCode.UNAVAILABLE,
            "$operation failed: ${reason(error)}",
            error,
        )
        else -> TrackingException(ErrorCode.INTERNAL, "$operation failed: ${reason(error)}", error)
    }

    /** The [ErrorCode] for a GMS status code (see [map]). */
    fun codeFor(statusCode: Int): ErrorCode = when (statusCode) {
        GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> ErrorCode.PERMISSION_DENIED
        GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES,
        GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS,
        -> ErrorCode.TOO_MANY_GEOFENCES
        CommonStatusCodes.TIMEOUT -> ErrorCode.TIMEOUT
        CommonStatusCodes.DEVELOPER_ERROR, CommonStatusCodes.INTERNAL_ERROR -> ErrorCode.INTERNAL
        else -> ErrorCode.UNAVAILABLE
    }

    /** E.g. `GEOFENCE_TOO_MANY_GEOFENCES (1001)`, with a hint for `GEOFENCE_NOT_AVAILABLE`. */
    fun describe(statusCode: Int): String {
        val name = try {
            GeofenceStatusCodes.getStatusCodeString(statusCode)
        } catch (e: Exception) {
            "status"
        }
        val hint = if (statusCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
            "; location is off or Google Location Accuracy is disabled"
        } else {
            ""
        }
        return "$name ($statusCode)$hint"
    }

    private fun reason(error: Throwable): String = error.message ?: error::class.java.name
}
