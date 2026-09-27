package com.brickssoft.premisemonitor

import android.content.Context
import com.brickssoft.locationtracking.api.NativeCallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native API of PremiseMonitor, used by [PremiseMonitorPlugin] and by the field-force app's debug e2e receiver
 * (`premise.*` commands). Results are the JS shapes (docs/e2e/architecture.md §7): `PremiseStatus` JSON, or the
 * audit entries array. Callbacks run on PremiseMonitor's `PM-native` thread; a failure carries a
 * `com.brickssoft.locationtracking.core.TrackingException` whose `code` is the JS error code (`INVALID_ARGUMENT` for
 * a bad premise or audit URL; the tracking plugin's code when its geofence call fails).
 */
object PremiseMonitorNative {
    /**
     * `startMonitoring({premise, auditUrl?})`; [premise] is `{id, name?, latitude, longitude, radius}` (radius in
     * meters), [auditUrl] an http(s) URL or null (no uploads; entries stay pending).
     *
     * Persists `{premise, auditUrl}`, writes `monitoring_started` and adds the tracking plugin geofence `premise:<id>`
     * (circle, ENTER + EXIT). If the geofence cannot be added, monitoring is rolled back (`monitoring_stopped` with the
     * error in `detail`) and the call fails. Calling it again with the same premise keeps the state (`inside`) and
     * only re-adds the geofence when the tracking plugin no longer has it; a different premise replaces the old one.
     */
    @JvmStatic
    fun start(context: Context, premise: JSONObject, auditUrl: String?, callback: NativeCallback<JSONObject>) =
        PremiseMonitorCore.get(context).start(premise, auditUrl, callback)

    /**
     * `stopMonitoring()`: writes `monitoring_stopped`, stops the service, removes the geofence. The audit URL is kept,
     * so pending entries (including `monitoring_stopped`) are still uploaded, and records are still audited.
     */
    @JvmStatic
    fun stop(context: Context, callback: NativeCallback<JSONObject>) = PremiseMonitorCore.get(context).stop(callback)

    /** `getStatus()`: `{monitoring, premise, inside, serviceRunning, auditUrl, lastEntryAt, pendingUploads}`. */
    @JvmStatic
    fun status(context: Context, callback: NativeCallback<JSONObject>) = PremiseMonitorCore.get(context).status(callback)

    /** `getAuditLog({limit})`: the newest [limit] entries, newest last; [limit] <= 0 = all kept entries. */
    @JvmStatic
    fun auditLog(context: Context, limit: Int, callback: NativeCallback<JSONArray>) =
        PremiseMonitorCore.get(context).auditLog(limit, callback)
}
