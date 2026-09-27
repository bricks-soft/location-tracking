package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import org.json.JSONObject

/** A promise rejection: `call.reject(message, code.name)`. */
internal data class Rejection(val message: String, val code: ErrorCode) {
    companion object {
        /** [TrackingException] keeps its code; anything else is [ErrorCode.INTERNAL]. */
        fun of(error: Throwable): Rejection {
            val message = error.message?.takeIf { it.isNotBlank() }
            return when (error) {
                is TrackingException -> Rejection(message ?: error.code.name, error.code)
                else -> Rejection(message ?: error.javaClass.simpleName, ErrorCode.INTERNAL)
            }
        }
    }
}

/** Settles a [PluginCall] with a handler result (null = `void`) or error. */
internal object CallResponder {
    private const val TAG = "LT.Bridge"

    fun resolve(call: PluginCall, result: JSONObject?) {
        if (result == null) call.resolve() else call.resolve(JSObject.fromJSONObject(result))
    }

    fun reject(call: PluginCall, error: Throwable) {
        val rejection = Rejection.of(error)
        if (rejection.code == ErrorCode.INTERNAL) {
            Logger.e(TAG, "${call.methodName} failed", error)
        } else {
            Logger.d(TAG, "${call.methodName} rejected: ${rejection.code} ${rejection.message}")
        }
        call.reject(rejection.message, rejection.code.name)
    }
}
