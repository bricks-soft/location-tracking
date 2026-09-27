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
  record exists in the `heartbeat.minInterval`–`maxInterval` window (180–300 s by default). It is uploaded
  immediately, and retried from the queue if the upload fails. `getHeartbeatStatus()` reports the scheduling strategy
  (`exact`, `listener_with_backup`, `idle_paced` or `disabled`).
- **Audit records:** `tracking_start` (reasons `start`, `start_geofences`, `boot`, `restore`, `package_replaced`),
  `tracking_stop` (reasons `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`) and
  `providerchange`, uploaded as priority records.
- **SQLite queue with HTTP sync:**
  - single or batch bodies, `rootProperty` (including `"."`), `params` merged into the body root;
  - JSON templates with `<%= name %>` placeholders;
  - `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`;
  - retries with `recorded_at` and `sent_at`;
  - JWT access-token refresh on expiry or `401`;
  - pruning (`maxDaysToPersist`, `maxRecordsToPersist`).
- **Geofencing:** up to 100 circle or polygon geofences, with `ENTER`, `EXIT` and `DWELL`, `initialTriggerEntry`, and a
  geofences-only mode (`startGeofences()`).
- **Positions:** `getCurrentPosition()` (best of N samples, `maximumAge`, optional persistence) and `watchPosition()`
  / `clearWatch()`.
- **Foreground service:** a configurable notification with up to three action buttons (`notificationaction` event).
  Tracking continues after the app is swiped away (`stopOnTerminate: false`) and resumes after a reboot or an app
  update (`startOnBoot: true`).
- **Permissions:** `checkPermissions()` / `requestPermissions()` for location, background location (with a
  configurable rationale dialog on Android 11+), activity recognition and notifications.
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
- **Web stub:** `getCurrentPosition`, `watchPosition` and `clearWatch` via `navigator.geolocation`, and in-memory
  `ready`, `setConfig`, `reset` and `getState`.
- **Example app** (`example/`) with a button for every method, a live event log, and state and heartbeat panels.
- **Documentation:**
  - README with setup, configuration, events, error codes and the generated API reference;
  - `docs/wire-format.md` (for server developers);
  - `docs/heartbeat.md` (reliability and server-side audit);
  - `docs/device-test-checklist.md`.
