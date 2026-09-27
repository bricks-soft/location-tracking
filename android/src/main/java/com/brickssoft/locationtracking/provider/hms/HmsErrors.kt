package com.brickssoft.locationtracking.provider.hms

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.huawei.hms.common.ApiException
import com.huawei.hms.location.GeofenceErrorCodes

/**
 * HMS Location Kit status codes used for error mapping. The values mirror
 * `com.huawei.hms.support.api.location.common.exception.LocationStatusCode` (shipped in a transitive artifact, so
 * they are copied here) and [GeofenceErrorCodes].
 */
internal object HmsStatusCodes {
    const val ARGUMENTS_EMPTY = 10100
    const val ARGUMENTS_INVALID = 10101
    const val NETWORK_LOCATION_SERVICES_DISABLED = 10105
    const val LOCATION_ENABLE_OFF = 10106
    const val PARAM_ERROR_EMPTY = 10801
    const val PARAM_ERROR_INVALID = 10802
    const val PERMISSION_DENIED = 10803
    const val NO_PRECISE_LOCATION_PERMISSION = 10809

    /** Like GMS `GEOFENCE_NOT_AVAILABLE`: typically location was switched off. */
    const val GEOFENCE_UNAVAILABLE = GeofenceErrorCodes.GEOFENCE_UNAVAILABLE
    const val GEOFENCE_NUMBER_OVER_LIMIT = GeofenceErrorCodes.GEOFENCE_NUMBER_OVER_LIMIT
    const val GEOFENCE_PENDINGINTENT_OVER_LIMIT = GeofenceErrorCodes.GEOFENCE_PENDINGINTENT_OVER_LIMIT
    const val GEOFENCE_INSUFFICIENT_PERMISSION = GeofenceErrorCodes.GEOFENCE_INSUFFICIENT_PERMISSION
}

/**
 * Maps an HMS status code to the plugin's [ErrorCode]:
 * permission errors → [ErrorCode.PERMISSION_DENIED], geofence limits → [ErrorCode.TOO_MANY_GEOFENCES],
 * location (or network location, which geofencing needs) switched off → [ErrorCode.LOCATION_DISABLED],
 * argument errors → [ErrorCode.INVALID_ARGUMENT],
 * everything else (HMS Core missing or outdated, AppGallery Connect errors, …) → [ErrorCode.UNAVAILABLE].
 */
internal fun errorCodeForStatus(statusCode: Int): ErrorCode = when (statusCode) {
    HmsStatusCodes.PERMISSION_DENIED,
    HmsStatusCodes.NO_PRECISE_LOCATION_PERMISSION,
    HmsStatusCodes.GEOFENCE_INSUFFICIENT_PERMISSION,
    -> ErrorCode.PERMISSION_DENIED

    HmsStatusCodes.GEOFENCE_NUMBER_OVER_LIMIT,
    HmsStatusCodes.GEOFENCE_PENDINGINTENT_OVER_LIMIT,
    -> ErrorCode.TOO_MANY_GEOFENCES

    HmsStatusCodes.LOCATION_ENABLE_OFF,
    HmsStatusCodes.NETWORK_LOCATION_SERVICES_DISABLED,
    HmsStatusCodes.GEOFENCE_UNAVAILABLE,
    -> ErrorCode.LOCATION_DISABLED

    HmsStatusCodes.ARGUMENTS_EMPTY,
    HmsStatusCodes.ARGUMENTS_INVALID,
    HmsStatusCodes.PARAM_ERROR_EMPTY,
    HmsStatusCodes.PARAM_ERROR_INVALID,
    -> ErrorCode.INVALID_ARGUMENT

    else -> ErrorCode.UNAVAILABLE
}

/** Converts a failure of an HMS call into the only exception type that crosses component boundaries. */
internal fun Throwable.toTrackingException(operation: String): TrackingException = when (this) {
    is TrackingException -> this
    is SecurityException -> TrackingException(ErrorCode.PERMISSION_DENIED, "$operation: location permission denied", this)
    is ApiException -> TrackingException(errorCodeForStatus(statusCode), "$operation failed: ${describeHmsError(this)}", this)
    is IllegalArgumentException -> TrackingException(ErrorCode.INVALID_ARGUMENT, "$operation: ${message ?: "invalid argument"}", this)
    else -> TrackingException(ErrorCode.UNAVAILABLE, "$operation failed: ${describeHmsError(this)}", this)
}

/** Short description for logs and messages, including the HMS status code when there is one. */
internal fun describeHmsError(t: Throwable): String =
    if (t is ApiException) {
        "HMS status ${t.message ?: t.statusCode}" // ApiException's message is "<code>:<status message>"
    } else {
        "${t.javaClass.simpleName}: ${t.message}"
    }
