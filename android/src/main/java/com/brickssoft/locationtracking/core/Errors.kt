package com.brickssoft.locationtracking.core

/** Rejection codes; `name` is the JS `code` of a failed promise. */
enum class ErrorCode {
    NOT_READY,
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_ARGUMENT,
    NOT_FOUND,
    NO_URL,
    HTTP_ERROR,
    NETWORK_ERROR,
    TOO_MANY_GEOFENCES,
    NO_ACTIVITY,
    IO_ERROR,
    UNIMPLEMENTED,
    INTERNAL,
}

/** The only exception type that crosses component boundaries; the bridge maps it to `call.reject(message, code.name)`. */
class TrackingException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
