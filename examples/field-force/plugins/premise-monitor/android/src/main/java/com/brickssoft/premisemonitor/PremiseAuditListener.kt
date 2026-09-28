package com.brickssoft.premisemonitor

import android.content.Context
import com.brickssoft.locationtracking.api.LocationTrackingListener
import org.json.JSONObject

/**
 * Declared in this plugin's manifest (`com.brickssoft.locationtracking.LISTENER`), so the tracking plugin creates it in
 * every process before emitting anything: also after a reboot, a heartbeat alarm or a `START_STICKY` restart, when no
 * WebView exists. It audits every record and event (`kind` 'record' / 'event' entries, `source` 'manifest') and drives
 * premise enter/exit, presence validation and the service restart in a new process (docs/e2e/architecture.md §10).
 *
 * The constructor does nothing (it runs inside the tracking plugin's `Components.get()`). Each callback copies its
 * JSON, captures `at`/`pid`/`js` and hands the work to PremiseMonitor's own `PM-native` thread, so the tracking
 * plugin's `LT-native` thread is never held up by SQLite or HTTP.
 */
class PremiseAuditListener : LocationTrackingListener {
    override fun onRecord(context: Context, record: JSONObject) {
        PremiseMonitorCore.get(context).onRecord(record, PremiseMonitorCore.SOURCE_MANIFEST)
    }

    override fun onEvent(context: Context, name: String, payload: JSONObject) {
        PremiseMonitorCore.get(context).onEvent(name, payload, PremiseMonitorCore.SOURCE_MANIFEST)
    }
}
