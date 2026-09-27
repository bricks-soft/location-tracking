// PLACEHOLDER — owned by Unit 12 (Field-force app). Contract: docs/e2e/architecture.md §10.
// Globals: window.FF_ENV (env.js), capacitorLocationTracking.LocationTracking, capacitorPremiseMonitor.PremiseMonitor.
(function () {
  'use strict';
  var status = document.getElementById('status');
  var env = window.FF_ENV || {};
  var hasTracking = !!(window.capacitorLocationTracking && window.capacitorLocationTracking.LocationTracking);
  var hasPremise = !!(window.capacitorPremiseMonitor && window.capacitorPremiseMonitor.PremiseMonitor);
  status.textContent =
    'Placeholder. backendUrl=' + env.backendUrl + ', LocationTracking=' + hasTracking + ', PremiseMonitor=' + hasPremise;
})();
