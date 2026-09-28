# Changelog

All notable changes to `@bricks-soft/capacitor-location-tracking` are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Round 2: the field-force audit setup, a native API for companion plugins, and end-to-end tests on an Android
emulator. Decisions: [docs/DECISIONS.md](docs/DECISIONS.md#round-2-field-force-audit-companion-api-and-avd-tests).

### Added

- **Companion native API** (Kotlin, package `com.brickssoft.locationtracking.api`): `LocationTrackingListener`
  receives every queued record (`onRecord`, heartbeats and audit records included, also when the plugin's own insert
  failed) and every event (`onEvent`) on one background thread (`LT-native`), in the order they were created, in
  every process, also without a WebView. Listeners are declared in the manifest (meta-data
  `com.brickssoft.locationtracking.LISTENER`, or `….LISTENER.<suffix>` for more than one) and created before the
  process can emit its first record, or added with `LocationTrackingNative.addListener`. `LocationTrackingNative` also
  offers `ready`, `setConfig`, `start`, `startGeofences`, `stop`, `changePace`, `getState`, `getHeartbeatStatus`,
  `sync`, `insertLocation`, `addGeofence`, `removeGeofence` and `getGeofences`, with the JS result shapes and error
  codes, without the `NOT_READY` rule. The consumer R8 rules keep the API and the listeners' constructors. Guide:
  `docs/native-api.md`.
- **`http.syncInterval`** (seconds, default `0` = off): while tracking is on, normal records are uploaded once the
  oldest queued one (the smallest `recorded_at`) is that old, the whole queue at once, so the server's live location
  is at most about `syncInterval` seconds old with one upload per interval. A timer in the tracking process checks
  it; the timer holds no wake lock, so in deep sleep it is late, and the next location record or heartbeat uploads the
  queue. Audit records are still uploaded at once. `autoSyncThreshold` above 0 becomes a size limit. While tracking is
  off, normal records upload as with `syncInterval: 0`. After a failed automatic upload, normal records are retried
  once per `syncInterval`, not on every insert (the network coming back, an audit record and `sync()` still upload at
  once).
- **Heartbeat metadata:** every `heartbeat` record carries an optional `heartbeat` object (`strategy`,
  `min_interval`, `max_interval`, `next_at`, `battery_exempt`, `device_idle`), so a server can tell an expected gap
  (about 9 minutes in Doze without the battery exemption) from a failure. The next window is armed before the record
  is queued, so the object matches `getHeartbeatStatus()`. TypeScript: `Location.heartbeat?: HeartbeatMeta`.
- **Stationary GPS-off mode:** while stationary, the plugin requests only passive fixes and registers one OS geofence
  (the stationary region, radius `max(stationaryRadius, 150 m)`, exit only) around the stop point (the anchor fix).
  It leaves the stationary state on the region's exit, on a fix whose distance from the anchor minus its accuracy is
  more than `stationaryRadius` (with an accuracy no worse than `trackingAccuracyThreshold`), on a confident moving
  activity, or on `changePace(true)`. Heartbeats continue with the anchor fix and its acquisition time. A low-power
  request (at most one fix per 3 minutes, no GPS) replaces the passive one when the region cannot be registered (no
  background location, a backend error, no answer in 10 s), when no current anchor is known, or when the app has 99
  or more geofences.
- **Foreground-service start hardening** against `ForegroundServiceDidNotStartInTimeException` and refused starts:
  the service calls `startForeground` before it loads anything (with the configured notification channel and content
  carried in the start command); stops are sent as commands that arrive after every earlier start, and
  `stopService` is never called while a start is pending; a second `start()` while a start is pending sends nothing;
  start commands left by a dead process restore tracking; on Android 14+ the plugin checks before starting whether
  Android would refuse the `location` service type (no background location and no visible app) and then does not
  try. Tested by P-L01, P-L02, P-L11 and P-L13.
- **`addGeofence` rejects the identifier `__lt_stationary__`** with `INVALID_ARGUMENT`: it is reserved for the
  stationary region.
- **Field-force example** (`examples/field-force/`): auto start on every page load, and again when the app comes back
  to the foreground while tracking is off (document `resume` event, `visibilitychange` as the fallback), so the app
  starts tracking the morning after the 02:00 stop even when it was never closed; stop at 02:00 through
  `stopAfterElapsedMinutes` computed by the app (again right before `start()`); live location with
  `syncInterval: 300`; device details in `http.params`; JWT; GMS only. `window.FF_APP` exposes the startup state
  (`startup`, `status`, `result`, `startupCount`, `lastStartupReason`, `lastResume`, `checkResume()`). Node tests:
  `npm test` in `examples/field-force`.
- **PremiseMonitor fake plugin** (`examples/field-force/plugins/premise-monitor/`): a companion plugin with a
  manifest listener that audits every record and event in its own SQLite log, monitors one circular premise with a
  geofence (`premise:<id>`), runs its own foreground location service while the worker is inside, flags fixes that are
  certainly outside the premise (`presence_violation`), treats `tracking_stop` as "inside unknown" and stops its
  service, and uploads its audit entries (every non-`2xx` answer is retried).
- **End-to-end test kit** (`testing/e2e-kit/`, Node 22, no runtime dependencies): adb and WebView (DevTools) helpers,
  debug commands, a mock back office (`npm run backoffice`) with control endpoints, fixtures, assertions (including
  `travelSummary`), a crash scanner and failure artifacts, and a whole-run back-office log
  (`e2e-artifacts/_run/backoffice.log`).
- **End-to-end suites:** the plugin suite (`e2e/plugin/`, 34 scenarios against `example/`) and the field-force suite
  (`examples/field-force/e2e/`, 12 scenarios). Debug builds of both example apps got test hooks (a command receiver
  that accepts only senders with `android.permission.DUMP`, test-mode files, cleartext HTTP to `10.0.2.2` and
  `localhost`) and share `testing/debug.keystore`. The plugin example's receiver also has `otherAppLocation`, which
  requests GPS the way another app would, because the emulator produces a fix only while some client asks for GPS.
- **CI emulator workflow** (`.github/workflows/e2e-android.yml` with the reusable `e2e-android-run.yml`, script
  `.github/scripts/run-e2e.sh`): the plugin suite on API 34 (subsets on API 29 and 35), P-P08 on an image without
  Google Play services, the field-force suite on API 34, the long scenarios nightly (01:23 UTC) and on manual
  dispatch, artifacts of every job. The build workflow (`.github/workflows/ci.yml`) adds the kit's tests, type checks
  and dry runs of the suites, the field-force Node tests, the PremiseMonitor unit tests, and a 16 KB page-size check
  of the Google Play build (`.github/scripts/check-16kb.py`).
- **Runbook** ([docs/e2e-runbook.md](docs/e2e-runbook.md)) for an AI agent: local AVD setup, running and triaging the
  suites, and the manual procedures M-01 … M-08 (HMS phone, phone makers' task killers, real drive, 12-hour battery
  measurement, real 02:00 stop, Android 14 boot with while-in-use location, 16 KB alignment, real overnight Doze).
- **Documentation:** README sections "Battery", "Live location", "Companion plugins (native API)", "Field-force
  example" and "End-to-end tests"; heartbeat metadata, stationary behavior, native delivery and heartbeat cost in
  `docs/heartbeat.md`; `syncInterval`, the `heartbeat` object and the stop reasons in `docs/wire-format.md`; the
  round-2 contract `docs/e2e/architecture.md`; decisions in `docs/DECISIONS.md`. `docs/device-test-checklist.md` now
  uses the mock back office as its test server.

### Changed

- After a user force stop, a background event that still reaches the app (an activity update or a stationary-region exit already on its way) no longer restarts tracking (Android 11+); tracking resumes when the app is opened (`ready()`).
- **Stationary no longer polls:** before, the stationary state kept a `'balanced'` request with up to one fix per
  minute, and accepted fixes refreshed the heartbeat's location; now GPS and the plugin's own location requests are
  off while stationary (see "Stationary GPS-off mode"), and the heartbeat carries the anchor fix. The exit rule
  `distance − accuracy > stationaryRadius` (with the accuracy threshold) replaces the round-1 rule
  `distance > max(stationaryRadius, accuracy)` for every stationary fix.
- **`tracking_stop` reasons:** when Android refuses the foreground service in `start()` / `startGeofences()`, the
  record now has reason `service_start_failed` (was `permission_denied`); the JS error code stays
  `PERMISSION_DENIED`. When Android's own restart of the killed service fails because location permission was
  revoked, the reason is now `permission_denied` (was `service_start_failed`).
- **Boot broadcasts are gated by the boot count:** a boot broadcast is ignored when `Settings.Global.BOOT_COUNT` is
  the same as for the last handled boot broadcast, or as when the tracking service was last started, so a repeated or
  fake `QUICKBOOT_POWERON` without a real reboot no longer restores tracking a second time (P-L10). Phones without the
  counter keep the old behavior.
- **Heartbeat cost:** the alarms are not set again when a record moves the due time later by less than 30 s (with
  60 records 5 s apart: 20 AlarmManager set calls instead of 120 when not exempt, 10 instead of 60 when exempt); an
  alarm left in place can fire up to 30 s early and then creates no heartbeat. One wake lock on the in-process alarm
  path (was two; still at most 60 s). After the backend answered that it has no last location, it is not asked again
  for 10 minutes. After a refused exact alarm, the fallback stays until the next re-arm.
  `getHeartbeatStatus().nextHeartbeatAt` is the real due time.
- **Native listeners are installed before the plugin's components are published** (`Components.get()`), so no
  record of a process can reach a missing listener; the consumer R8 rules keep `InnerClasses,EnclosingMethod`.

### Known limitations

- A build that packages HMS (`hms` or `gms,hms`) is not 16 KB page-size compatible: `com.huawei.hms:location`
  6.12.0.300 brings `libTransform.so` (`arm64-v8a`) and `libucs-credential.so` (`x86_64`) with 4 KB alignment.
- The 02:00 stop (`stopAfterElapsedMinutes`) happens at the first check after the stop time. While stationary that is
  the first heartbeat: at most `maxInterval` (300 s) late for an exempt app, about 9–11 minutes late in Doze without
  the exemption.
- The PremiseMonitor service can start from the background only while the tracking service is in the foreground or
  right after a geofence transition; a refused start is audited (`service_start_failed`), not a crash.
- The heartbeat's wake lock ends when the heartbeat is queued; the upload itself holds no wake lock.
- Open items and owner questions: [docs/DECISIONS.md, R2.5](docs/DECISIONS.md#r25-open-requests-and-known-limitations).

## [0.1.0] - Unreleased

The first release: Android and TypeScript. iOS is planned for a later phase.

### Added

- `LocationTracking` Capacitor 8 plugin (Android, Kotlin, minSdk 24), a clean-room implementation of a
  background-geolocation feature set with a new, smaller API. It is not affiliated with Transistor Software.
- **Location backends:** GMS fused location, HMS (Huawei Location Kit) fused location, and the Android
  `LocationManager`.
  - Gradle property `locationTracking.providers` (`gms` by default, `hms`, or `gms,hms`) picks which SDKs are packaged.
  - Config `locationProvider: 'auto' | 'gms' | 'hms' | 'android'` picks the backend at runtime.
- **Motion-aware tracking:** moving and stationary states, activity recognition, elastic distance filter, stop
  detection, `changePace()`, `stopOnStationary` and `stopAfterElapsedMinutes`.
- **Location filters:** accuracy threshold, implied-speed filter, optional Kalman smoothing, identical-fix and
  mock-location handling. Odometer with an accuracy gate.
- **Heartbeat audit:** while tracking is on, a `heartbeat` record with the last known location is created when no
  record was created for `heartbeat.minInterval` (180 s by default); it should exist before `maxInterval` (300 s).
  It is uploaded immediately, and retried from the queue if the upload fails. `getHeartbeatStatus()` reports the
  scheduling strategy (`exact`, `listener_with_backup`, `idle_paced` or `disabled`).
- **Audit records:** `tracking_start` (reasons `start`, `start_geofences`, `boot`, `restore`, `package_replaced`),
  `tracking_stop` (reasons `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`,
  `service_start_failed`, `reboot`, `package_replaced`) and `providerchange` (debounced by 1 s; the first observed state is saved silently),
  uploaded as priority records.
- **`tracking_stop` reason `service_start_failed`:** when Android refuses or aborts the foreground service after
  tracking was started, or when the plugin restarts it from the background (process restore, heartbeat alarm, boot,
  app update), tracking ends with this audit record instead of looking enabled while nothing is collected. On
  Android 12+ only battery-exempt apps, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` may start the service from the
  background, and on Android 14+ that also needs background location. The app must call `start()` again. A service
  that Android itself restarted (`START_STICKY`) is not started a second time.
- **`tracking_stop` reasons `reboot` and `package_replaced`:** when tracking was on before a reboot or an app update
  and `app.startOnBoot` is `false`, a `tracking_stop` with that reason is recorded after the restart, so the server
  learns why the heartbeats stopped.
- **SQLite queue with HTTP sync:**
  - single or batch bodies, `rootProperty` (including `"."`), `params` merged into the body root;
  - JSON templates with `<%= name %>` placeholders;
  - `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`;
  - retries with `recorded_at` and `sent_at`;
  - JWT access-token refresh on expiry or `401`;
  - pruning (`maxDaysToPersist`, `maxRecordsToPersist`).
- **Geofencing:** up to 100 circle or polygon geofences, with `ENTER`, `EXIT` and `DWELL`, `initialTriggerEntry`, and a
  geofences-only mode (`startGeofences()`). Geofences are registered with the OS again when location services come
  back on or the location permission level changes (GMS and HMS drop them when location is switched off).
- **Positions:** `getCurrentPosition()` (best of N samples, `maximumAge`, optional persistence) and `watchPosition()`
  / `clearWatch()`.
- **Foreground service:** a configurable notification with up to three action buttons (`notificationaction` event).
  Tracking continues after the app is swiped away (`stopOnTerminate: false`) and resumes after a reboot or an app
  update (`startOnBoot: true`).
- **Permissions:** `checkPermissions()` / `requestPermissions()` for location, background location (with a
  configurable rationale dialog on Android 11+), activity recognition and notifications. `checkPermissions()` can
  report `prompt-with-rationale` right after the app starts.
- **Device helpers:**
  - provider state, power-save mode and battery-optimization status;
  - `openBatteryOptimizationSettings()` (the settings list; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and
    `SCHEDULE_EXACT_ALARM` are intentionally not declared);
  - phone maker power-manager screens, location settings and app settings;
  - device info and sensors.
- **Persistent file log:** levels, retention, query, `uploadLog()` and `emailLog()`.
- **13 events:** `location`, `motionchange`, `activitychange`, `providerchange`, `heartbeat`, `geofence`,
  `geofenceschange`, `http`, `connectivitychange`, `powersavechange`, `enabledchange`, `notificationaction` and
  `authorization`. There are typed helpers (`onHeartbeat()`, and so on).
- **Web stub:** `getCurrentPosition`, `watchPosition` and `clearWatch` via `navigator.geolocation` (also emitted as
  `location` events), in-memory `ready`, `setConfig`, `reset` and `getState`, `checkPermissions`,
  `requestPermissions` and `getDeviceInfo`.
- **Release builds:** the consumer R8/ProGuard rules keep the provider bundles and the GMS/HMS class names the plugin
  probes by reflection, so minified release apps still detect Google Play services and HMS.
- **Example app** (`example/`) with a button for every method, a live event log, and state and heartbeat panels.
- **Documentation:**
  - README with setup, configuration, events, error codes and the generated API reference (`npm run docgen`,
    which uses `tsconfig.docgen.json` so the reference shows the real return types);
  - `docs/wire-format.md` (for server developers);
  - `docs/heartbeat.md` (reliability and server-side audit);
  - `docs/device-test-checklist.md`.

### Known limitations

- iOS is not implemented yet; every call rejects on iOS.
- The `android` backend (`LocationManager`) has no activity recognition (motion is detected from distance only) and
  no native geofence dwell (the plugin synthesizes `DWELL`). It always reports `ENTER` for a circle the device is
  already inside, so `geofence.initialTriggerEntry: false` only applies to polygons there.
- On Android 9 and older, Huawei's `com.huawei.hms.permission.ACTIVITY_RECOGNITION` is declared but not requested at
  runtime. If HMS Core requires it there, activity updates fail (logged) and motion is detected from distance only.
- Without the battery-optimization exemption, Android limits heartbeats to about one every 9 minutes in deep idle,
  and on Android 12+ tracking cannot be restored from the background after the process was killed
  (`service_start_failed`).
- There is no upload retry timer: queued records are retried when a record is inserted, when the network comes back,
  when tracking starts, or on `sync()`.
