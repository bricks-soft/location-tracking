// STUB — owned by Unit 13 (PremiseMonitor fake plugin).
package com.brickssoft.premisemonitor

import android.content.Context
import com.brickssoft.locationtracking.api.NativeCallback
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Native API of PremiseMonitor, used by [PremiseMonitorPlugin] and by the field-force app's debug e2e receiver
 * (`premise.*` commands). Results are the JS shapes: `PremiseStatus` JSON, or the audit entries array.
 *
 * SCAFFOLD STUB: every method fails with UNIMPLEMENTED on a background thread.
 */
object PremiseMonitorNative {
    /** `startMonitoring({premise, auditUrl?})`; [premise] is `{id, name?, latitude, longitude, radius}`. */
    @JvmStatic
    fun start(context: Context, premise: JSONObject, auditUrl: String?, callback: NativeCallback<JSONObject>) =
        unimplemented("start", callback)

    /** `stopMonitoring()`. */
    @JvmStatic
    fun stop(context: Context, callback: NativeCallback<JSONObject>) = unimplemented("stop", callback)

    /** `getStatus()`. */
    @JvmStatic
    fun status(context: Context, callback: NativeCallback<JSONObject>) = unimplemented("status", callback)

    /** `getAuditLog({limit})`: newest last; [limit] <= 0 = all kept entries. */
    @JvmStatic
    fun auditLog(context: Context, limit: Int, callback: NativeCallback<JSONArray>) = unimplemented("auditLog", callback)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PM-native").apply { isDaemon = true }
    }

    private fun <T> unimplemented(method: String, callback: NativeCallback<T>) {
        executor.execute {
            callback.onResult(
                Result.failure(TrackingException(ErrorCode.UNIMPLEMENTED, "PremiseMonitorNative.$method is not implemented")),
            )
        }
    }
}
