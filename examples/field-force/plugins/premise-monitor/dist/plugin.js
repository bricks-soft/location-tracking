// Hand-written (no build step). IIFE global `capacitorPremiseMonitor`, like the tracking plugin's
// `capacitorLocationTracking`; needs vendor/capacitor.js (global `capacitorExports`) loaded first.
var capacitorPremiseMonitor = (function (exports, core) {
  'use strict';

  /**
   * JS API (docs/e2e/architecture.md §7 and §10; types in dist/esm/index.d.ts). Android only: on the web every method
   * rejects with UNIMPLEMENTED.
   *
   * startMonitoring({ premise: {id, name?, latitude, longitude, radius}, auditUrl? }) -> PremiseStatus
   *   Adds the tracking plugin geofence `premise:<id>` (ENTER + EXIT) and uploads the audit log to auditUrl.
   *   Calling it again with the same premise (a page reload) keeps the state.
   * stopMonitoring() -> PremiseStatus
   * getStatus() -> PremiseStatus
   *   {monitoring, premise, inside (null = unknown), serviceRunning, auditUrl, lastEntryAt, pendingUploads}
   * getAuditLog({ limit? }) -> { entries: PremiseAuditEntry[] }   (newest last; no limit = all kept entries)
   *
   * Errors: `code` INVALID_ARGUMENT (bad premise or auditUrl), or the tracking plugin's code when the geofence
   * cannot be added (for example TOO_MANY_GEOFENCES).
   */
  var PremiseMonitor = core.registerPlugin('PremiseMonitor');

  exports.PremiseMonitor = PremiseMonitor;
  return exports;
})({}, capacitorExports);
