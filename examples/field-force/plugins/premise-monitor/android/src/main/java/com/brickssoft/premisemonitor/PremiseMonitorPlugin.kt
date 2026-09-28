package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

/**
 * JS `PremiseMonitor` (docs/e2e/architecture.md §10): startMonitoring({premise, auditUrl?}), stopMonitoring(),
 * getStatus(), getAuditLog({limit?}) → {entries}. Thin layer over [PremiseMonitorNative]. Loading it marks the
 * process as `js: true` for every later audit entry.
 */
@CapacitorPlugin(name = "PremiseMonitor")
class PremiseMonitorPlugin : Plugin() {
    override fun load() {
        ProcessInfo.jsLoaded = true
    }

    @PluginMethod
    fun startMonitoring(call: PluginCall) {
        val premise = call.getObject("premise")
        if (premise == null) {
            call.reject("premise is required", ErrorCode.INVALID_ARGUMENT.name)
            return
        }
        PremiseMonitorNative.start(context, premise, call.getString("auditUrl")) { settle(call, it) }
    }

    @PluginMethod
    fun stopMonitoring(call: PluginCall) {
        PremiseMonitorNative.stop(context) { settle(call, it) }
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        PremiseMonitorNative.status(context) { settle(call, it) }
    }

    @PluginMethod
    fun getAuditLog(call: PluginCall) {
        val limit = call.getInt("limit") ?: 0
        PremiseMonitorNative.auditLog(context, limit) { result ->
            settle(call, result.map { JSONObject().put("entries", it) })
        }
    }

    private fun settle(call: PluginCall, result: Result<JSONObject>) {
        result.fold(
            onSuccess = { call.resolve(JSObject.fromJSONObject(it)) },
            onFailure = { error ->
                val code = (error as? TrackingException)?.code ?: ErrorCode.INTERNAL
                call.reject(error.message ?: code.name, code.name)
            },
        )
    }
}
