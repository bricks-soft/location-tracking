// Hand-written (no build step). IIFE global `capacitorPremiseMonitor`, like the tracking plugin's
// `capacitorLocationTracking`; needs vendor/capacitor.js (global `capacitorExports`) loaded first.
var capacitorPremiseMonitor = (function (exports, core) {
  'use strict';

  /**
   * JS API (docs/e2e/architecture.md §10):
   * startMonitoring({ premise: {id, name?, latitude, longitude, radius}, auditUrl? }) -> PremiseStatus
   * stopMonitoring() -> PremiseStatus
   * getStatus() -> PremiseStatus
   * getAuditLog({ limit? }) -> { entries: PremiseAuditEntry[] }
   */
  var PremiseMonitor = core.registerPlugin('PremiseMonitor');

  exports.PremiseMonitor = PremiseMonitor;
  return exports;
})({}, capacitorExports);
