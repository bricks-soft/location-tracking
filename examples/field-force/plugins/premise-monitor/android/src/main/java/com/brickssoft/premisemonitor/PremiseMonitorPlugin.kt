// STUB — owned by Unit 13 (PremiseMonitor fake plugin).
package com.brickssoft.premisemonitor

import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * JS `PremiseMonitor` (docs/e2e/architecture.md §10): startMonitoring({premise, auditUrl?}), stopMonitoring(),
 * getStatus(), getAuditLog({limit?}). Thin layer over [PremiseMonitorNative].
 */
@CapacitorPlugin(name = "PremiseMonitor")
class PremiseMonitorPlugin : Plugin() {
    @PluginMethod
    fun startMonitoring(call: PluginCall) = call.unimplemented()

    @PluginMethod
    fun stopMonitoring(call: PluginCall) = call.unimplemented()

    @PluginMethod
    fun getStatus(call: PluginCall) = call.unimplemented()

    @PluginMethod
    fun getAuditLog(call: PluginCall) = call.unimplemented()
}
