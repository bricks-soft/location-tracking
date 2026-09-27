# Changelog

All notable changes to `@bricks-soft/capacitor-location-tracking` are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

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
  `service_start_failed`) and `providerchange` (debounced by 1 s; the first observed state is saved silently),
  uploaded as priority records.
- **`tracking_stop` reason `service_start_failed`:** when Android refuses or aborts the foreground service after
  tracking was started, or when the plugin restarts it from the background (process restore, heartbeat alarm, boot,
  app update), tracking ends with this audit record instead of looking enabled while nothing is collected. On
  Android 12+ only exact alarms (battery-exempt apps), `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` may start the
  service from the background, and on Android 14+ that also needs background location. The app must call `start()`
  again.
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
