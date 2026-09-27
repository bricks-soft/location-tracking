// STUB — owned by Unit 13 (PremiseMonitor fake plugin).
package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.api.LocationTrackingListener

/**
 * Declared in this plugin's manifest (`com.brickssoft.locationtracking.LISTENER`), so the tracking plugin creates it in
 * every process before emitting anything. It audits every record and event (`kind` 'record' / 'event' entries) and
 * drives premise enter/exit (docs/e2e/architecture.md §10). Needs a public no-arg constructor.
 */
class PremiseAuditListener : LocationTrackingListener
