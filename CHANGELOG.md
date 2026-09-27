# Changelog

All notable changes to `@bricks-soft/capacitor-location-tracking` are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

Round 2: the field-force audit setup, a native API for companion plugins, and end-to-end tests on an Android
emulator. Decisions: [docs/DECISIONS.md](docs/DECISIONS.md#round-2-field-force-audit-companion-api-and-avd-tests).

### Added

- **Companion native API** (Kotlin, package `com.brickssoft.locationtracking.api`): `LocationTrackingListener`
  receives every queued record (`onRecord`, heartbeats and audit records included) and every event (`onEvent`) on one
  background thread (`LT-native`), in every process, also without a WebView. Listeners are declared in the manifest
  (meta-data `com.brickssoft.locationtracking.LISTENER`, or `….LISTENER.<suffix>` for more than one) or added with
  `LocationTrackingNative.addListener`. `LocationTrackingNative` also offers `ready`, `setConfig`, `start`,
  `startGeofences`, `stop`, `changePace`, `getState`, `getHeartbeatStatus`, `sync`, `insertLocation`, `addGeofence`,
  `removeGeofence` and `getGeofences`. Guide: `docs/native-api.md`.
- **`http.syncInterval`** (seconds, default `0` = off): normal records are uploaded once the oldest queued one is
  that old, so the server's live location is at most about `syncInterval` seconds old with one upload per interval.
  Audit records are still uploaded at once. `autoSyncThreshold` above 0 becomes a size limit.
- **Heartbeat metadata:** every `heartbeat` record carries an optional `heartbeat` object (`strategy`,
  `min_interval`, `max_interval`, `next_at`, `battery_exempt`, `device_idle`), so a server can tell an expected gap
  (about 9 minutes in Doze without the battery exemption) from a failure. TypeScript: `Location.heartbeat?:
  HeartbeatMeta`.
- **Stationary GPS-off mode:** while stationary, the plugin requests only passive fixes and registers one OS geofence
  (the stationary region, radius `max(stationaryRadius, 150 m)`) to notice movement; heartbeats continue with the
  last fix and its acquisition time. If the region cannot be registered, a low-power request (at most one fix per
  3 minutes, no GPS) is used instead.
- **Foreground-service start hardening** against `ForegroundServiceDidNotStartInTimeException` and refused
  background starts (tested by P-L01, P-L02, P-L11 and P-L13).
- **Field-force example** (`examples/field-force/`): auto start on every app launch, stop at 02:00 through
  `stopAfterElapsedMinutes` computed by the app, live location with `syncInterval: 300`, device details in
  `http.params`, JWT, GMS only.
- **PremiseMonitor fake plugin** (`examples/field-force/plugins/premise-monitor/`): a companion plugin with a
  manifest listener that audits every record and event, monitors one circular premise with a geofence, runs its own
  foreground location service while the worker is inside, flags fixes outside the premise, and uploads its audit
  entries.
- **End-to-end test kit** (`testing/e2e-kit/`, Node 22, no runtime dependencies): adb and WebView helpers, debug
  commands, a mock back office (`npm run backoffice`), fixtures, assertions and failure artifacts.
- **End-to-end suites:** the plugin suite (`e2e/plugin/`, 34 scenarios against `example/`) and the field-force suite
  (`examples/field-force/e2e/`, 12 scenarios). Debug builds of both example apps got test hooks (a command receiver,
  test-mode files, cleartext HTTP to the emulator host) and share `testing/debug.keystore`.
- **CI emulator workflow** (`.github/workflows/e2e-android.yml`, script `.github/scripts/run-e2e.sh`): the plugin
  suite on API 34 (smaller runs on API 29 and 35), P-P08 on an image without Google Play services, the field-force
  suite, nightly long scenarios, artifacts of every job; and a 16 KB page-size check of the Google Play build
  (`.github/scripts/check-16kb.py`).
- **Runbook** ([docs/e2e-runbook.md](docs/e2e-runbook.md)) for an AI agent: local AVD setup, running and triaging the
  suites, and the manual procedures M-01 … M-08 (HMS phone, phone makers' task killers, real drive, 12-hour battery
  measurement, real 02:00 stop, Android 14 boot with while-in-use location, 16 KB alignment, real overnight Doze).
- **Documentation:** README sections "Battery", "Live location", "Companion plugins (native API)", "Field-force
  example" and "End-to-end tests"; heartbeat metadata, stationary behavior and native delivery in
  `docs/heartbeat.md`; `syncInterval` and the `heartbeat` object in `docs/wire-format.md`.

### Changed

- **Stationary no longer polls:** before, the stationary state kept a `'balanced'` request with up to one fix per
  minute; now GPS and the plugin's own location requests are off while stationary (see "Stationary GPS-off mode").
- **Boot broadcasts are gated by the boot count:** a boot broadcast without a real reboot (for example a repeated or
  fake `QUICKBOOT_POWERON`) no longer restores tracking a second time (P-L10).

<!-- verify after merge: the FGS start hardening (unit 1) and the boot-count gate — describe the merged behavior in one sentence each -->

### Known limitations

- A build that packages HMS (`hms` or `gms,hms`) is not 16 KB page-size compatible: `com.huawei.hms:location`
  6.12.0.300 brings `libTransform.so` (`arm64-v8a`) and `libucs-credential.so` (`x86_64`) with 4 KB alignment.
- The 02:00 stop (`stopAfterElapsedMinutes`) happens at the first wake-up after the stop time: within about
  `maxInterval` for an exempt app, about 9–10 minutes in Doze without the exemption.
- The PremiseMonitor service can start from the background only while the tracking service is in the foreground or
  right after a geofence transition; a refused start is audited (`service_start_failed`), not a crash.

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
