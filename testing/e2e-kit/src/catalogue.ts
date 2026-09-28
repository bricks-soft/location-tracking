// CONTRACT (scaffold, round 2): the scenario catalogue. IDs are stable: suites register exactly these IDs with
// scenario(), and docs/e2e-runbook.md refers to them. Mirror of docs/e2e/architecture.md §9; change both together.

export type CatalogueSuite =
  | 'plugin-lifecycle'
  | 'plugin-heartbeat'
  | 'plugin-permissions'
  | 'field-force'
  | 'manual';

export interface CatalogueEntry {
  id: string;
  suite: CatalogueSuite;
  title: string;
}

function entries(suite: CatalogueSuite, list: ReadonlyArray<readonly [string, string]>): CatalogueEntry[] {
  return list.map(([id, title]) => ({ id, suite, title }));
}

export const CATALOGUE: readonly CatalogueEntry[] = [
  ...entries('plugin-lifecycle', [
    ['P-L01', 'start/stop 50x in a rapid loop: no foreground-service crash, audit pairs complete'],
    ['P-L02', 'start, then force-stop while the service is starting: no crash, clean state on relaunch'],
    ['P-L03', 'kill -9 while tracking: START_STICKY/alarm restore records tracking_start reason restore'],
    ['P-L04', 'force-stop while tracking, relaunch: ready() restores tracking'],
    ['P-L05', 'activity recreation (font scale): tracking keeps running, no duplicate JS events'],
    ['P-L06', 'POST_NOTIFICATIONS denied: service runs, records flow'],
    ['P-L07', 'app update (install -r) with startOnBoot true/false: tracking_start / tracking_stop package_replaced'],
    ['P-L08', 'reboot with startOnBoot true: tracking_start reason boot'],
    ['P-L09', 'reboot with startOnBoot false: tracking_stop reason reboot'],
    ['P-L10', 'fake QUICKBOOT_POWERON broadcast without a real boot is ignored'],
    ['P-L11', 'background start on Android 12+ via the debug receiver: service_start_failed unless temp-allowlisted'],
    ['P-L12', 'heartbeat alarm restores a killed process'],
    ['P-L13', 'busy main thread (4000 ms) around a cold service start: no ForegroundServiceDidNotStartInTimeException'],
  ]),
  ...entries('plugin-heartbeat', [
    ['P-H01', 'stationary heartbeat cadence (60/120 s); recorded_at newer than location.timestamp'],
    ['P-H02', 'stationary: no active non-passive location request from the app (dumpsys location)'],
    ['P-H03', 'movement (geo fix route) turns GPS back on: motionchange isMoving true'],
    ['P-H04', 'deep Doze, not battery-exempt: strategy idle_paced, heartbeats >= 9 min apart'],
    ['P-H05', 'deep Doze, battery-exempt: strategy exact, cadence about minInterval'],
    ['P-H06', 'wall-clock jump and timezone change: cadence unaffected, boot_count/elapsed consistent'],
    ['P-H07', 'airplane mode: records queue, upload after reconnect with original recorded_at and later sent_at'],
    ['P-H08', 'server 500 then 200 is retried; 401 refreshes the JWT via /auth/refresh and retries'],
    ['P-H09', 'syncInterval batches normal records while moving; audit records upload immediately'],
    ['P-H10', 'heartbeat records carry the heartbeat metadata object'],
  ]),
  ...entries('plugin-permissions', [
    ['P-P01', 'revoke fine location (keep coarse): process killed, relaunch, providerchange accuracy approximate'],
    ['P-P02', 'revoke all location: restore records tracking_stop reason permission_denied'],
    ['P-P03', 'revoke background location: providerchange permission when_in_use, background behavior'],
    ['P-P04', 'revoke ACTIVITY_RECOGNITION: tracking continues with distance-based motion'],
    ['P-P05', 'revoke POST_NOTIFICATIONS while tracking: tracking continues'],
    ['P-P06', 'location services off/on: providerchange enabled=false/true, geofences re-registered'],
    ['P-P07', 'locationProvider auto -> android -> gms at runtime: backend changes in records'],
    ['P-P08', 'image without Google Play services: backend android, tracking works'],
    ['P-P09', 'mock locations: mock:true; rejectMockLocations drops them'],
    ['P-P10', 'circular geofence ENTER / EXIT / DWELL via geo fix'],
    ['P-P11', 'geofences re-registered after reboot'],
  ]),
  ...entries('field-force', [
    ['F-01', 'app launch auto-starts tracking: tracking_start + motionchange, device details in params, battery'],
    ['F-02', '02:00 stop: stop time computed from the device clock, tracking_stop reason stop_after_elapsed'],
    ['F-03', '02:00 stop still happens after a process kill (restore) and after a reboot'],
    ['F-04', 'route replay: uploads batched within syncInterval, odometer about route length, travel time'],
    ['F-05', 'stationary: no GPS, heartbeats on cadence with an older location.timestamp'],
    ['F-06', 'online/offline audit on the server: heartbeat cadence, stop records tracking_stop'],
    ['F-07', 'premise ENTER: PremiseMonitor service runs, its audit endpoint receives enter and every later record/event'],
    ['F-08', 'app backgrounded and activity destroyed: PremiseMonitor still receives events natively'],
    ['F-09', 'kill -9 inside the premise: process restored, listener receives events before any JS'],
    ['F-10', 'reboot inside the premise: listener receives tracking_start boot + heartbeats, PremiseMonitor service restored'],
    ['F-11', 'premise EXIT: exit audit, PremiseMonitor service stops'],
    ['F-12', 'presence validation: a fix outside the radius while inside is flagged by PremiseMonitor'],
  ]),
  ...entries('manual', [
    ['M-01', 'HMS on a Huawei phone'],
    ['M-02', 'OEM task killers (Xiaomi/Huawei/Samsung) with and without the power-manager allowlist'],
    ['M-03', 'real drive: server distance vs odometer vs map'],
    ['M-04', '12 h battery measurement with the field-force preset'],
    ['M-05', 'real overnight 02:00 stop'],
    ['M-06', 'Android 14+ real boot with only while-in-use location permission'],
    ['M-07', 'Play build (gms only): 16 KB page-size alignment'],
    ['M-08', 'real overnight Doze heartbeat spacing'],
  ]),
];

const byId = new Map(CATALOGUE.map((entry) => [entry.id, entry]));

/** The catalogue entry of [id], or undefined for an unknown id. */
export function catalogueEntry(id: string): CatalogueEntry | undefined {
  return byId.get(id);
}

/** Entries of one suite, in catalogue order. */
export function catalogueOf(suite: CatalogueSuite): CatalogueEntry[] {
  return CATALOGUE.filter((entry) => entry.suite === suite);
}
