# @bricks-soft/capacitor-location-tracking

Background location tracking for Capacitor 8 on Android. It records locations with Google Play services (GMS),
Huawei Location Kit (HMS) or the plain Android `LocationManager`, stores them in SQLite and uploads them to your
server. While tracking is on, it also sends **heartbeat** audit records every 3–5 minutes, so your server can tell
whether the user really kept tracking on.

> **Clean-room implementation.** This plugin offers a feature set similar to
> [transistorsoft/capacitor-background-geolocation](https://github.com/transistorsoft/capacitor-background-geolocation),
> with a new, smaller API. It was written from scratch: it contains no code from that project (whose native engine is
> closed-source and commercial) and is **not affiliated with, endorsed by or supported by Transistor Software**.

## Contents

- [Features](#features)
- [Platform status](#platform-status)
- [Installation](#installation)
- [Android setup](#android-setup)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Tracking behavior](#tracking-behavior)
- [Geofencing](#geofencing)
- [Records, uploads and the queue](#records-uploads-and-the-queue)
- [Events reference](#events-reference)
- [Error codes](#error-codes)
- [Web stub](#web-stub)
- [Server side](#server-side)
- [Example app](#example-app)
- [API](#api)

## Features

- **Motion-aware tracking.** The plugin switches between *moving* (continuous, high-accuracy updates with an elastic
  distance filter) and *stationary* (a low-power request that watches for movement) using activity recognition and
  distance. It reports `motionchange` and `activitychange` events.
- **Three location backends.** It supports GMS fused location, HMS fused location and the Android `LocationManager`.
  You choose which SDKs go into the APK at build time (`locationTracking.providers`) and which one to use at runtime
  (`locationProvider: 'auto' | 'gms' | 'hms' | 'android'`).
- **Heartbeat audit.** If no record has been created for `heartbeat.minInterval` seconds (default 180), the plugin
  creates a `heartbeat` record with the last known location. It should exist before `maxInterval` (default 300), and
  it is uploaded immediately. See [docs/heartbeat.md](docs/heartbeat.md).
- **Audit records.** `tracking_start`, `tracking_stop` (with a reason) and `providerchange` records are uploaded the
  same way, so the server can explain gaps.
- **Offline-first HTTP sync.** Every record is stored in SQLite first. Uploads can send one record or a batch per
  request, use JSON templates, merge `params` into the body, and refresh a JWT on `401`. Failed uploads stay queued
  and are retried, and `recorded_at` / `sent_at` expose late delivery. See [docs/wire-format.md](docs/wire-format.md).
- **Geofencing.** Up to 100 circular or polygon geofences, with `ENTER`, `EXIT` and `DWELL` transitions, and a
  geofences-only tracking mode (`startGeofences()`). See [Geofencing](#geofencing).
- **One-off and watched positions.** `getCurrentPosition()` (best of N samples) and `watchPosition()`.
- **Odometer** with an accuracy gate, plus location filters: accuracy threshold, implied-speed filter, optional Kalman
  smoothing and mock-location handling.
- **Foreground service** with a configurable notification and up to three action buttons.
- **Restart after reboot, app update and process death** (`startOnBoot`, `stopOnTerminate: false`).
- **Device helpers.** Permission handling (including the Android 10/11+ background-location flow), battery-optimization
  status and settings, phone-maker power-manager screens, provider state, device info and sensors.
- **Persistent log.** A file log with levels and retention, which you can query, upload or email.

## Platform status

| Platform | Status |
|---|---|
| Android (API 24+, Kotlin) | ✔ Supported |
| iOS | Planned for a later phase. Until then every call rejects with "not implemented on ios". |
| Web | Stub for development only: positions through `navigator.geolocation`, an in-memory config, permissions and device info. Nothing is stored or uploaded, and tracking can't be started. Everything else rejects with `UNIMPLEMENTED`. See [Web stub](#web-stub). |

## Installation

```bash
npm i @bricks-soft/capacitor-location-tracking
npx cap sync android
```

Requirements: Capacitor 8, Android `minSdk` 24, `compileSdk` / `targetSdk` 36 and JDK 21. The plugin is built with
AGP 8.13 and Kotlin 2.2.

## Android setup

### 1. Choose the location SDKs to package

The plugin compiles against both SDKs. The Gradle property `locationTracking.providers` decides which of them are
**packaged** into your APK. Set it in your app's `android/gradle.properties`:

```properties
# gms (default) | hms | gms,hms
locationTracking.providers=gms,hms
```

| Value | Packages | Typical use |
|---|---|---|
| `gms` (default) | `com.google.android.gms:play-services-location` | Google Play builds |
| `hms` | `com.huawei.hms:location` | AppGallery-only builds |
| `gms,hms` | both | One APK for phones with Google services and Huawei phones without them |

You can also pass the property on the command line (`./gradlew assembleRelease -PlocationTracking.providers=hms`),
for example to build separate Play and AppGallery flavors from one project.

The SDK versions default to `play-services-location` 21.3.0 and `com.huawei.hms:location` 6.12.0.300. To use other
versions, set `playServicesLocationVersion` or `hmsLocationVersion` in the `ext` block of your app's
`android/variables.gradle`.

At runtime, the `locationProvider` config option picks the backend:

- `'auto'` (default): GMS if it is packaged and Google Play services are available; otherwise HMS if it is packaged and
  HMS Core is available; otherwise the Android `LocationManager`.
- `'gms'` or `'hms'`: that backend. If it is not packaged or not available on the phone, the plugin logs a warning and
  uses the Android `LocationManager`.
- `'android'`: always the Android `LocationManager`.

The backend is chosen once per process and again whenever `locationProvider` changes (while tracking, the location
request, activity updates and geofences move to the new backend). `getState().backend` and `getDeviceInfo()`
(`gmsAvailable`, `hmsAvailable`, `packagedProviders`) show what was selected.

The `android` backend has two limitations: it has **no activity recognition**, so motion detection relies on distance
only, and **no native geofence dwell**, so the plugin synthesizes `DWELL` itself (see [Geofencing](#geofencing)).

**Release builds (R8 / minify).** Nothing to add: the plugin ships consumer ProGuard/R8 rules that keep its provider
bundles and the GMS/HMS class names it probes by reflection, so a minified release app still detects Google Play
services and HMS. They also include Huawei's recommended keep rules and silence warnings about the SDK that is not
packaged.

### 2. Huawei Maven repository (needed for `hms`, and harmless otherwise)

The HMS SDK is served from Huawei's own Maven repository. Add it to **both** repository blocks of your app's
`android/build.gradle`:

```groovy
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = 'https://developer.huawei.com/repo/' }
    }
    // ...
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = 'https://developer.huawei.com/repo/' }
    }
}
```

### 3. AppGallery Connect (only for HMS)

HMS Location Kit works only for an app that is registered in AppGallery Connect (AGC) with the right signing
certificate:

1. In [AppGallery Connect](https://developer.huawei.com/consumer/en/service/josp/agc/index.html), create a project and
   add an Android app whose package name matches your `applicationId`.
2. Under **Project settings → General information**, add the **SHA-256 certificate fingerprint** of every signing key
   you use (debug and release, and the app-signing key if AppGallery re-signs your app). You can print the fingerprints
   with `./gradlew signingReport` or `keytool -list -v -keystore <keystore>`.
3. Make sure **Location Kit** is enabled under **Project settings → Manage APIs**.
4. Download `agconnect-services.json` and put it in `android/app/`.
5. Apply the AGC Gradle plugin. In `android/build.gradle`, add
   `classpath 'com.huawei.agconnect:agcp:1.9.6.300'` (or the latest version from the Huawei repository) to
   `buildscript.dependencies`. Then, in `android/app/build.gradle`, add `apply plugin: 'com.huawei.agconnect'` after
   `apply plugin: 'com.android.application'`.

The phone needs **HMS Core** (the "HMS Core" / "Huawei Mobile Services" app). If AGC is not set up correctly,
Location Kit calls fail with errors such as `907135xxx` or `6003`. Check the plugin log (`getLog()`) for them.

### 4. Permissions (merged automatically)

The plugin's manifest is merged into your app, so you don't have to declare anything. It declares:

| Permission | Why |
|---|---|
| `INTERNET` | Uploading records, heartbeats and logs. |
| `ACCESS_NETWORK_STATE` | Detecting connectivity: retry when the network returns, `disableAutoSyncOnCellular`, the `connectivitychange` event. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Foreground ("while in use") location. Runtime permission, alias `location`. |
| `ACCESS_BACKGROUND_LOCATION` | "Allow all the time" (Android 10+). Runtime permission, alias `backgroundLocation`. Needed for geofencing and for restarting tracking from the background (boot, app update, process restore). |
| `ACTIVITY_RECOGNITION` | Motion activity (still, walking, in vehicle, ...) on Android 10+. Runtime permission, alias `activityRecognition`. |
| `com.google.android.gms.permission.ACTIVITY_RECOGNITION` (maxSdk 28) | GMS activity recognition on Android 9 and older. |
| `com.huawei.hms.permission.ACTIVITY_RECOGNITION` (maxSdk 28) | HMS activity identification on Android 9 and older. **Not requested at runtime** by `requestPermissions()`: if HMS Core requires it on such a phone, request it yourself, otherwise activity updates fail (logged) and motion detection uses distance only. |
| `FOREGROUND_SERVICE` | Running the tracking foreground service. |
| `FOREGROUND_SERVICE_LOCATION` | The `location` foreground-service type (required on Android 14+). |
| `POST_NOTIFICATIONS` | Showing the foreground-service notification on Android 13+. Runtime permission, alias `notifications`. |
| `RECEIVE_BOOT_COMPLETED` | Resuming tracking after a reboot or an app update (`app.startOnBoot`). |
| `WAKE_LOCK` | Keeping the CPU awake briefly while a heartbeat is created and uploaded. |

These are **intentionally not declared**:

| Permission | Why not |
|---|---|
| `SCHEDULE_EXACT_ALARM` | Denied by default on Android 14+ for new installs, and it needs a policy justification. Apps exempt from battery optimization may use exact alarms without it. |
| `USE_EXACT_ALARM` | Google Play allows it only for alarm-clock and calendar apps. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Google Play restricts the direct "allow this app to ignore battery optimizations?" prompt. The plugin opens the system list instead (`openBatteryOptimizationSettings()`), where the user exempts the app. |

The manifest also adds the tracking service (`foregroundServiceType="location"`, `stopWithTask="false"`), the boot,
alarm, notification-action, geofence and activity receivers, and a `FileProvider` for sharing log files (authority
`${applicationId}.locationtracking.logs`). It also adds `<queries>` entries, so that the phone makers'
power-manager apps and `mailto:` handlers are visible on Android 11+.

If you don't want a permission, remove it in your app's manifest and turn off the matching feature. For example:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
  <!-- no activity recognition: also set activity.disableMotionActivityUpdates = true -->
  <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" tools:node="remove"/>
</manifest>
```

### 5. Runtime permission flow

`requestPermissions()` asks, by default, for all four permissions in this order: `location`, `notifications`,
`activityRecognition`, then `backgroundLocation`. Pass `{ permissions: [...] }` to ask for only some of them. It asks
for one permission at a time, skips those that are already granted or don't exist on the phone's Android version, and
resolves with the resulting `PermissionStatus`. Overlapping calls run one after another.

- **Status values.** A permission that is not granted is `'prompt'` if the plugin never asked for it (in this
  install), `'prompt-with-rationale'` if Android recommends explaining it first, and `'denied'` otherwise (the user
  refused, and Android won't show the dialog again). Coarse-only location counts as `location: 'granted'`.
- **`start()` needs only foreground location.** The foreground service keeps "while in use" access while it runs. Call
  `start()` while your app is visible, because Android 12+ restricts starting foreground services from the
  background.
- **Android 10 (API 29).** The system dialog offers "Allow all the time" directly.
- **Android 11+ (API 30+).** Background location is requested only *after* foreground location has been granted (if
  it isn't, the background step is skipped), and the "request" opens the app's location-permission page in Settings,
  where the user picks **Allow all the time**. The plugin first shows an explanation dialog, which you can reword with
  `backgroundPermissionRationale`; if the user declines it, or no Activity is visible, the step is skipped. Google
  Play requires your own in-app disclosure before this, plus a declaration in the Play Console.
- **Android 12+.** The user may grant only *approximate* location. `getProviderState().accuracy` then reports
  `'approximate'`.
- **Android 13+.** `POST_NOTIFICATIONS` is a runtime permission. If it is denied, tracking still works, but the
  foreground-service notification is hidden.
- **Below Android 10**, there is no separate background permission (it is reported the same as `location`), and
  activity recognition needs no runtime prompt (it is reported as `'granted'`). Notifications below Android 13 are
  also reported as `'granted'`.
- A permission whose Android permission you removed from the app's manifest (`tools:node="remove"`) is not requested.
- Background location is needed for geofencing and for resuming tracking after a reboot, an app update or process
  death. On Android 14+ a resume from the background without it ends with a `tracking_stop` whose reason is
  `service_start_failed`.
- Revoking a location permission in Settings kills the app's process. While tracking is on, the plugin records a
  `providerchange` the next time it runs; if location permission is gone completely, it also stops tracking with a
  `tracking_stop` whose reason is `permission_denied`.

For apps targeting Android 14+, Google Play also asks you to declare the `location` foreground-service type in the
Play Console.

### 6. Battery optimization and phone makers

For reliable heartbeats on idle phones, ask the user to exempt the app from battery optimization, and on some phones
to allow it in the phone maker's power manager. See [docs/heartbeat.md](docs/heartbeat.md#android-reliability) for the
details and the expected intervals.

```ts
const status = await LocationTracking.getBatteryOptimizationStatus();
if (!status.isIgnoringBatteryOptimizations) {
  // Explain why first; the user picks the app and chooses "Don't optimize" / "Unrestricted".
  await LocationTracking.openBatteryOptimizationSettings();
}
const pm = await LocationTracking.getPowerManagerInfo();
if (pm.available) {
  // e.g. Huawei: Battery → App launch → your app → Manage manually (all switches on)
  await LocationTracking.openPowerManagerSettings();
}
```

## Quick start

```ts
import { LocationTracking, onHeartbeat, onLocation } from '@bricks-soft/capacitor-location-tracking';

// 1. Listen first, so no event is missed.
await onLocation((location) => console.log('[location]', location.event, location.coords));
await onHeartbeat(({ location }) => console.log('[heartbeat]', location.recorded_at, location.coords));
await LocationTracking.addListener('providerchange', (state) => console.log('[providerchange]', state));
await LocationTracking.addListener('http', (e) => console.log('[http]', e.status, e.uuids.length));

// 2. Call ready() on every app launch, before any other method.
const state = await LocationTracking.ready({
  config: {
    locationProvider: 'auto',
    geolocation: { desiredAccuracy: 'high', distanceFilter: 10 },
    heartbeat: { enabled: true, minInterval: 180, maxInterval: 300 },
    http: {
      url: 'https://api.example.com/locations',
      headers: { 'X-Api-Key': 'my-key' },
      params: { device_id: 'device-123' },
    },
    app: { stopOnTerminate: false, startOnBoot: true },
    notification: { title: 'Trip recording', text: 'Your location is being shared' },
  },
});

// 3. Ask for foreground permissions, then start while the app is visible.
const permissions = await LocationTracking.requestPermissions({
  permissions: ['location', 'notifications', 'activityRecognition'],
});
if (permissions.location === 'granted' && !state.enabled) {
  await LocationTracking.start();
}

// 4. Later, after explaining why, ask for "Allow all the time".
await LocationTracking.requestPermissions({ permissions: ['backgroundLocation'] });

// Check the heartbeat at any time:
const hb = await LocationTracking.getHeartbeatStatus();
console.log(hb.strategy, hb.nextHeartbeatAt, hb.isIgnoringBatteryOptimizations);
```

**The `NOT_READY` rule.** Until `ready()` has resolved in the current process (once per process, not per WebView),
every method rejects with `NOT_READY`, except `ready`, `getState`, `checkPermissions`, `requestPermissions`,
`getDeviceInfo`, `getSensors`, `log`, `getLog`, `getProviderState` and the `open*Settings` methods
(`openBatteryOptimizationSettings`, `openPowerManagerSettings`, `openLocationSettings`, `openAppSettings`).

**`ready({ reset })`.** With `reset: true` (the default), the config is the defaults plus the config you pass, on every
launch. With `reset: false`, the config you pass is applied only on the very first `ready()` after install, and
afterwards the persisted config wins (change it with `setConfig()`). Note that with `reset: true`, JWT tokens the
plugin refreshed are replaced by the ones in the config you pass (see
[JWT refresh](docs/wire-format.md#jwt-refresh)). If tracking was on but is not running in this process (for example
after the app was force-stopped or its process was killed), `ready()` resumes it and records a `tracking_start` with
reason `restore`.

**`setConfig()`** deep-merges: arrays and map-like objects (`headers`, `params`, `extras`, ...) are replaced as a
whole, and `null` resets a key or a group to its default. Unknown keys are ignored with a warning in the log. Enum
values are case-insensitive, and numeric or boolean strings (`"15"`, `"false"`) are accepted. A wrong type or an
unknown enum value rejects the call with `INVALID_ARGUMENT`, and nothing is applied. Out-of-range values are clamped
(see the [Configuration reference](#configuration-reference)). `reset({ config })` starts from the defaults
instead.

**Event helpers.** Besides `LocationTracking.addListener(name, cb)`, the package exports typed helpers (`onLocation`,
`onMotionChange`, `onActivityChange`, `onProviderChange`, `onHeartbeat`, `onGeofence`, `onGeofencesChange`, `onHttp`,
`onConnectivityChange`, `onPowerSaveChange`, `onEnabledChange`, `onNotificationAction`, `onAuthorization`), a generic
`on(name, cb)` and an `Events` map of event names. Each returns a `PluginListenerHandle`. Call `handle.remove()` or
`LocationTracking.removeAllListeners()` to unsubscribe.

## Configuration reference

All options are optional. `getState().config` returns the full config with every default filled in (including the
JWT tokens). Out-of-range values are clamped to the limits given below, and every clamp is logged as a warning;
numbers that are not finite become the default.

### `config` (top level)

| Option | Type | Default | Description |
|---|---|---|---|
| `locationProvider` | `'auto' \| 'gms' \| 'hms' \| 'android'` | `'auto'` | Location backend (Android only). See [Android setup](#1-choose-the-location-sdks-to-package). |

### `config.geolocation`

| Option | Type | Default | Description |
|---|---|---|---|
| `desiredAccuracy` | `'high' \| 'balanced' \| 'low' \| 'passive'` | `'high'` | Accuracy of the location request while moving. While stationary the plugin uses `'balanced'` (or the configured value if it is lower-power), at most one fix per minute. |
| `distanceFilter` | number (m, ≥ 0) | `10` | Minimum distance between recorded locations while moving. The plugin applies it itself (the OS request has no distance filter). |
| `locationUpdateInterval` | number (ms, ≥ 0) | `1000` | Desired update interval while moving. |
| `fastestLocationUpdateInterval` | number (ms, ≥ 0) | `500` | Fastest accepted update interval. |
| `disableElasticity` | boolean | `false` | Turns off the speed-based scaling of `distanceFilter`. |
| `elasticityMultiplier` | number (≥ 0) | `1` | Elastic filter: `distanceFilter × max(1, round(speed / 5) × elasticityMultiplier)`. |
| `stationaryRadius` | number (m, ≥ 1) | `25` | While stationary, moving further than this (or than the fix accuracy, if larger) switches to moving. While moving, such a displacement counts as evidence of motion for stop detection. |
| `stopTimeout` | number (min, ≥ 0) | `5` | Minutes without evidence of motion (a displacement beyond `stationaryRadius`, or a confident moving activity) before switching to stationary. Values below 1 act as 1. |
| `stopAfterElapsedMinutes` | number (min, ≥ 0) | `0` (off) | Stops tracking automatically this many minutes after it started (`tracking_stop`, reason `stop_after_elapsed`). |
| `stopOnStationary` | boolean | `false` | Stops tracking when stop detection switches the device to stationary (`tracking_stop`, reason `stop_on_stationary`). `changePace({ isMoving: false })` does not stop tracking. |
| `locationTimeout` | number (ms, > 0) | `30000` | Default timeout of `getCurrentPosition()`, and of the first fix after `start()`. |
| `filter` | object | see below | Location filters. |

### `config.geolocation.filter`

| Option | Type | Default | Description |
|---|---|---|---|
| `useKalman` | boolean | `false` | Smooths latitude and longitude with a Kalman filter. |
| `trackingAccuracyThreshold` | number (m, ≥ 0) | `100` | Rejects fixes whose accuracy is worse than this. |
| `maxImpliedSpeed` | number (m/s, ≥ 0) | `80` | Rejects fixes that imply a higher speed than this (GPS jumps). `0` turns the check off. |
| `odometerAccuracyThreshold` | number (m, ≥ 0) | `20` | The odometer ignores fixes whose accuracy is worse than this. |
| `allowIdenticalLocations` | boolean | `false` | Records fixes that are identical to the previous one. |
| `rejectMockLocations` | boolean | `false` | Drops mock fixes. Otherwise they are kept, with `mock: true`. |

### `config.activity`

| Option | Type | Default | Description |
|---|---|---|---|
| `disableMotionActivityUpdates` | boolean | `false` | Doesn't use activity recognition. Motion detection then relies on distance only. |
| `activityRecognitionInterval` | number (ms, ≥ 0) | `10000` | Requested interval of activity updates. |
| `minimumActivityRecognitionConfidence` | number (0–100) | `75` | Minimum confidence for an activity to count (for motion detection and the `activitychange` event). |
| `motionTriggerDelay` | number (ms, ≥ 0) | `0` | How long a moving activity must last before switching to moving. |
| `disableStopDetection` | boolean | `false` | Never switch to stationary automatically; only `changePace()` does. |

### `config.heartbeat`

| Option | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | Creates `heartbeat` records while tracking is on. |
| `minInterval` | number (s, ≥ 60) | `180` | A heartbeat is due when no record has been created for this long. |
| `maxInterval` | number (s, ≥ `minInterval`) | `300` | Upper end of the heartbeat window: the heartbeat should exist by then. A heartbeat Android delivers later is still created, and the lateness is logged. |

### `config.http`

| Option | Type | Default | Description |
|---|---|---|---|
| `url` | string \| null | none | Upload endpoint. Without a URL (or with one that isn't a valid `http(s)` URL) nothing is uploaded, and records stay queued. |
| `method` | `'POST' \| 'PUT' \| 'PATCH'` | `'POST'` | HTTP method. |
| `headers` | object | `{}` | Extra request headers. They can override `Content-Type`; an `Authorization` header turns the JWT handling off. |
| `params` | object | `{}` | Merged into the **root** of every request body. Keys that the body already has are not overwritten. Ignored for a batch with `rootProperty: '.'`. |
| `autoSync` | boolean | `true` | Uploads normal records automatically. Priority records (heartbeat and audit records) are always uploaded immediately, and take queued normal records along. |
| `autoSyncThreshold` | number (≥ 0) | `0` | Uploads normal records when the queue holds at least this many records. `0` uploads every record. |
| `batchSync` | boolean | `false` | Sends several records per request, as an array, oldest first. |
| `maxBatchSize` | number (≥ 1) | `100` | Maximum records per batch request. |
| `disableAutoSyncOnCellular` | boolean | `false` | On cellular, uploads only priority records. `sync()` ignores it. |
| `rootProperty` | string | `'location'` | Key that wraps the record or records. `'.'` (or an empty string) means no wrapping. |
| `locationTemplate` | string \| null | `null` | JSON text with `<%= name %>` placeholders that replaces the default record shape. |
| `geofenceTemplate` | string \| null | `null` | Template for `geofence` records. Falls back to `locationTemplate`. |
| `timeout` | number (ms, > 0) | `60000` | Request timeout (call, read and write). |
| `authorization` | object \| null | `null` | JWT settings, see below. |

The upload rules (priority records, retries, templates, body shapes) are described in
[docs/wire-format.md](docs/wire-format.md).

### `config.http.authorization`

| Option | Type | Default | Description |
|---|---|---|---|
| `strategy` | `'JWT'` | `'JWT'` | Only JWT is supported. |
| `accessToken` | string | none | Sent as `Authorization: Bearer <accessToken>`, unless `headers` already contain `Authorization`. If it is missing and `refreshUrl` is set, the plugin refreshes it before the first upload. |
| `refreshToken` | string | none | Substituted for `{refreshToken}` in `refreshPayload`. |
| `refreshUrl` | string | none | Token-refresh endpoint (POST). |
| `refreshPayload` | object | `{}` | Refresh request body. String values may contain `{refreshToken}`. |
| `refreshHeaders` | object | none | Headers of the refresh request. |
| `refreshPayloadEncoding` | `'json' \| 'form'` | `'json'` | Body encoding of the refresh request. |
| `expires` | number (epoch ms) | `-1` | Access-token expiry. `-1` means unknown. The token is refreshed 60 s before it expires, and after a `401` (at most one refresh per upload). Updated by every refresh. |

### `config.persistence`

| Option | Type | Default | Description |
|---|---|---|---|
| `maxDaysToPersist` | number (≥ 1) | `7` | Queued records older than this many days are deleted, even if they were never uploaded. Applies to every record type, heartbeats and audit records included. |
| `maxRecordsToPersist` | number (≥ -1) | `-1` | Maximum queued records; the oldest are deleted first. `-1` (or `0`) means unlimited. Pruning runs every 50 inserts, so the queue can exceed the limit by up to 49 records. |
| `extras` | object | `{}` | Merged into the `extras` of every record when it is created. Extras passed to a call (for example `getCurrentPosition({ extras })`) win over these. |

### `config.app`

| Option | Type | Default | Description |
|---|---|---|---|
| `stopOnTerminate` | boolean | `true` | Stops tracking when the user swipes the app away (`tracking_stop`, reason `terminate`). With `false`, tracking continues. |
| `startOnBoot` | boolean | `false` | Resumes tracking after a reboot or an app update, if it was on (`tracking_start`, reason `boot` or `package_replaced`). Needs "Allow all the time" location on Android 14+. With `false`, tracking is off after a reboot or an update, without a `tracking_stop` record. |

### `config.notification`

| Option | Type | Default | Description |
|---|---|---|---|
| `title` | string | app label | Notification title. |
| `text` | string | `'Location tracking is active'` | Notification text. |
| `smallIcon` | string | `'drawable/lt_ic_notification'` | `'drawable/name'` or `'mipmap/name'`. Must be a monochrome icon. If it can't be found (or is an adaptive launcher icon), the plugin's icon is used. |
| `largeIcon` | string | none | `'drawable/name'` or `'mipmap/name'`. |
| `color` | string | none | Accent color, `'#RRGGBB'`. |
| `priority` | `'min' \| 'low' \| 'default' \| 'high' \| 'max'` | `'default'` | Mapped to the channel importance. Android fixes a channel's importance when it is created, so use a new `channelId` to change it later. |
| `channelId` | string | `'location_tracking'` | Notification channel id. |
| `channelName` | string | `'Location tracking'` | Channel name shown in the system settings. |
| `actions` | `{ id, label }[]` | none | Up to 3 buttons (extra ones are dropped). A tap emits `notificationaction` with the button's `id`. |

### `config.geofence`

| Option | Type | Default | Description |
|---|---|---|---|
| `initialTriggerEntry` | boolean | `true` | Fires `ENTER` right away for geofences that already contain the device when they are registered (with GMS and HMS also `DWELL` after `loiteringDelay` for dwell geofences). The `android` backend always reports `ENTER` for a circle the device is already inside, so there `false` only applies to polygons. |

### `config.logger`

| Option | Type | Default | Description |
|---|---|---|---|
| `logLevel` | `'off' \| 'error' \| 'warn' \| 'info' \| 'debug' \| 'verbose'` | `'info'` | Minimum level written to the plugin log. |
| `logMaxDays` | number (≥ 1) | `3` | Days of log files to keep. |

### `config.backgroundPermissionRationale`

The dialog shown before the Android 11+ "Allow all the time" settings page.

| Option | Type | Default |
|---|---|---|
| `title` | string | `Allow background location` |
| `message` | string | `To keep tracking your location while the app is closed or not in use, select "Allow all the time" on the next screen.` |
| `positiveAction` | string | `Change to "Allow all the time"` |
| `negativeAction` | string | `Not now` |

## Tracking behavior

**Starting and stopping.**

- `start()` needs foreground location permission (otherwise it rejects with `PERMISSION_DENIED`). It starts the
  foreground service, records a `tracking_start` (reason `start`), emits `enabledchange`, and resolves with the new
  `State`. The first `motionchange` (`is_moving: false`, with a fresh fix or the last known location) follows
  **asynchronously**, within `locationTimeout` + 5 s. If Android refuses to start the foreground service right away,
  `start()` records a `tracking_stop` with reason `permission_denied` and rejects with `PERMISSION_DENIED`; if the
  service fails a moment later, tracking stops with reason `service_start_failed` (the promise has already
  resolved, so listen for `enabledchange`).
- `startGeofences()` works the same way in geofences-only mode (reason `start_geofences`). Calling `start()` while
  `startGeofences()` runs (or the reverse) switches the mode and records another `tracking_start`. Calling the same
  one twice does nothing.
- `stop()` removes the location and activity updates, records a `tracking_stop` (reason `stop`) while the foreground
  service is still running, then stops the service, the heartbeat and the OS geofences, and emits `enabledchange`.
- Tracking also stops by itself: `stopOnStationary`, `stopAfterElapsedMinutes`, `stopOnTerminate` (app swiped away),
  a revoked location permission (`permission_denied`), or Android refusing the foreground service
  (`service_start_failed`). Each case records a `tracking_stop` with that reason and emits `enabledchange`.
- Tracking resumes by itself after the process was killed (reason `restore`), after a reboot (`boot`) and after an
  app update (`package_replaced`, both only with `app.startOnBoot`), and when `ready()` finds it enabled but not
  running. See [docs/heartbeat.md](docs/heartbeat.md#android-reliability) for what Android allows.

**Motion states** (`start()` mode only).

- **Moving:** continuous updates with the configured `desiredAccuracy` and intervals. A `location` record is created
  when a fix is at least the (elastic) `distanceFilter` away from the last recorded one.
- **Stationary:** a low-power request (`'balanced'`, at most one fix per minute). Fixes only feed motion detection and
  polygon geofences; **no `location` records are created and the odometer doesn't grow**, so GPS jitter while parked
  is neither uploaded nor counted. The last known location used by heartbeats is still refreshed from them.
- Stationary → moving: a fix farther than `max(stationaryRadius, fix accuracy)` from where the device stopped, or a
  moving activity (walking, running, on foot, on bicycle, in vehicle) at or above
  `minimumActivityRecognitionConfidence` that lasts for `motionTriggerDelay`.
- Moving → stationary (stop detection, unless `disableStopDetection`): `stopTimeout` minutes (at least 1) after the
  last evidence of motion, even if no more fixes arrive.
- Each transition records a `motionchange` with the new `is_moving`. `changePace({ isMoving })` forces a transition;
  it is ignored while tracking is off or in geofences-only mode.
- Without activity recognition (the `android` backend, `disableMotionActivityUpdates`, or no activity permission),
  motion is detected from distance only.

**Positions.** `getCurrentPosition()` works whether or not tracking is on. It needs location permission
(`PERMISSION_DENIED`) and enabled location services (`LOCATION_DISABLED`). It takes up to `samples` fixes, stops
early on a fix of 10 m or better, and resolves with the most accurate one; at the timeout it resolves with the best
fix so far, or rejects with `TIMEOUT` if there is none. With `maximumAge`, a recent enough known fix is returned
right away. With `persist: true` (default) the fix is stored as a `current_position` record, which is uploaded,
emitted as a `location` event and restarts the heartbeat window. `watchPosition()` reports each fix (or an error,
such as `PERMISSION_DENIED`) to its callback until `clearWatch()`; it doesn't report disabled location services as
an error (fixes start once they are switched on). With `persist: true`, each fix is also stored as a
`watch_position` record (uploaded, and emitted as a `location` event).

## Geofencing

- Up to **100** geofences (`TOO_MANY_GEOFENCES` beyond that). An identifier is 1–100 characters; adding a geofence
  with an existing identifier replaces it. `loiteringDelay` must be ≥ 0.
- **Circle:** `latitude`, `longitude` and a `radius` > 0.
- **Polygon:** `vertices`, at least 3 distinct points with a non-zero area, not crossing the 180° meridian. The plugin
  registers the polygon's smallest enclosing circle, padded by 10 % (at least 50 m), with the OS; `getGeofences()`
  returns it as `latitude` / `longitude` / `radius`. While the device is inside that circle, the plugin requests
  continuous fixes and tests them against the polygon. To avoid flapping at the edge, the state changes only when a
  fix's accuracy circle is entirely on one side of the edge, or when two fixes in a row agree. Leaving the circle
  also means leaving the polygon. Polygon `DWELL` is always computed by the plugin.
- Geofences are stored in the plugin's database, but registered with the OS and reported **only while tracking is
  on** (`start()` or `startGeofences()`); `stop()` unregisters them. GMS and HMS drop all geofences when location
  services are switched off; the plugin registers them again when location comes back or the location permission
  level changes.
- **Backends.** GMS and HMS report `ENTER`, `EXIT` and `DWELL` natively; with `initialTriggerEntry` their initial
  trigger is `ENTER | DWELL`. The `android` backend (proximity alerts) has no dwell: the plugin synthesizes `DWELL`
  when the device stays inside for `loiteringDelay` after `ENTER`. It always reports `ENTER` for a circle the device
  is already inside.
- Every transition creates a `geofence` record (uploaded like a location) and a `geofence` event. `addGeofence(s)`
  and `removeGeofence(s)` emit `geofenceschange`.
- `removeGeofences()` without `identifiers` (or with `identifiers: null`) removes **all** geofences;
  `removeGeofences({ identifiers: [] })` removes **nothing**, so an empty computed list never wipes everything.
  Unknown identifiers are ignored.
- Background location permission ("Allow all the time") is needed for geofences to fire while the app is in the
  background.

## Records, uploads and the queue

- Every record is written to SQLite first and uploaded to `http.url` according to the rules in
  [docs/wire-format.md](docs/wire-format.md#when-uploads-happen). Priority records (`heartbeat`, `tracking_start`,
  `tracking_stop`, `providerchange`) are uploaded right away and take the older queued records along.
- There is **no retry timer**: queued records are retried when a record is inserted (at least every heartbeat while
  tracking), when the network comes back, when tracking starts, and on `sync()`.
- `getLocations({ limit })` returns queued records, oldest first; `getCount()` counts them; `destroyLocations()` and
  `destroyLocation({ uuid })` delete them without uploading.
- `sync()` uploads the whole queue now, ignoring `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`, and
  resolves with the uploaded records. It rejects with `NO_URL` (no valid `http.url`), or with `HTTP_ERROR` /
  `NETWORK_ERROR` at the first failed request (records uploaded before the failure are already deleted).
- `insertLocation({ location })` stores a record of your own (default `event: 'location'`) and uploads it like the
  others. It emits **no** event and does **not** restart the heartbeat window, because it is not evidence that
  tracking runs.
- `persistence.maxDaysToPersist` and `maxRecordsToPersist` prune the queue, including heartbeats and audit records.

## Events reference

Subscribe with `LocationTracking.addListener(name, callback)` or the typed helpers. Events reach JavaScript only
while the app's WebView is alive. Records are persisted and uploaded whether or not anyone is listening.
`connectivitychange` and `powersavechange`, and the `providerchange` events caused by system broadcasts, start once
tracking has been started (or resumed) in the app's process.

| Event | Payload | Emitted when |
|---|---|---|
| `location` | `Location` | A location record was stored (`event` is `location`, `motionchange`, `current_position` or `watch_position`). Not for `insertLocation()` records, nor for audit, heartbeat or geofence records (they have their own events or none). |
| `motionchange` | `{ isMoving, location }` | The motion state switched between moving and stationary (also emitted as a `location` event). |
| `activitychange` | `{ activity, confidence }` | Activity recognition reported a different activity type, with at least `minimumActivityRecognitionConfidence`. The `android` backend has none. |
| `providerchange` | `ProviderState` | Location services, permission level, accuracy or backend changed. Also emitted while tracking is off (then without a record); not for the very first state observed after install. |
| `heartbeat` | `{ location }` | A heartbeat record was created. `location` is the heartbeat record, with the last known coords. |
| `geofence` | `{ identifier, action, location, extras? }` | A geofence was entered, exited or dwelled in (only while tracking is on). |
| `geofenceschange` | `{ on, off }` | The set of geofences changed (`addGeofence(s)`, `removeGeofence(s)`). `on` holds geofences, `off` holds identifiers. |
| `http` | `{ success, status, responseText, uuids }` | An upload request finished. There is one event per HTTP request: a `401` followed by a token refresh and a retry gives two. `status` is `0` for a network error. |
| `connectivitychange` | `{ connected, type }` | The network connection changed. A network blocked for the app by Doze or Data Saver counts as disconnected. |
| `powersavechange` | `{ isPowerSaveMode }` | Battery saver was turned on or off. |
| `enabledchange` | `{ enabled }` | Tracking was started or stopped, including automatic stops (`stopOnStationary`, `stopAfterElapsedMinutes`, `terminate`, `permission_denied`, `service_start_failed`). Not emitted when tracking resumes in a new process. |
| `notificationaction` | `{ id }` | A notification action button was tapped. |
| `authorization` | `{ success, status, error?, response? }` | A JWT refresh was attempted, successfully or not. `response` is the parsed JSON body of the refresh response. |

## Error codes

Every rejected promise has a `code` (see the `ErrorCode` type) and a `message`.

| Code | Meaning |
|---|---|
| `NOT_READY` | The method was called before `ready()` resolved in this process (see the [NOT_READY rule](#quick-start)). |
| `PERMISSION_DENIED` | A required permission is missing, for example `start()` or `getCurrentPosition()` without location permission. Also `start()` / `startGeofences()` when Android refused to start the foreground service. |
| `LOCATION_DISABLED` | Location services are turned off on the device (`getCurrentPosition()`; some HMS geofence errors). |
| `TIMEOUT` | The operation timed out, for example `getCurrentPosition()` got no fix in time. |
| `UNAVAILABLE` | A required system service, backend or feature is not available right now, for example no app can send the log email. |
| `INVALID_ARGUMENT` | Invalid options or config, for example a wrong config type, a geofence without a radius, or a polygon with fewer than 3 vertices. |
| `NOT_FOUND` | Reserved; not currently used. Lookups of unknown items resolve instead (`getGeofence()` with `null`, `destroyLocation()` with `deleted: false`, `clearWatch()` does nothing). |
| `NO_URL` | `sync()` was called without a valid `http.url`. |
| `HTTP_ERROR` | `sync()` got a non-2xx response. |
| `NETWORK_ERROR` | `sync()` failed with a network or I/O error. |
| `TOO_MANY_GEOFENCES` | Adding the geofences would exceed 100 (or the OS geofence limit reported by GMS/HMS). |
| `NO_ACTIVITY` | `emailLog()` was called while no Activity is attached. (The `open*Settings` methods work without one.) |
| `IO_ERROR` | Storage or file error (database, log files). |
| `UNIMPLEMENTED` | Not available on this platform (web stub, iOS). |
| `INTERNAL` | Unexpected internal error. The plugin log has details. |

```ts
try {
  await LocationTracking.start();
} catch (e: any) {
  if (e.code === 'PERMISSION_DENIED') {
    /* ask again or explain */
  }
}
```

## Web stub

The web implementation exists so that an app can run in a browser during development. It keeps no queue, uploads
nothing and cannot track.

- **Works:** `ready`, `setConfig`, `reset` and `getState` (an in-memory config; `getState()` reports `enabled: false`,
  `backend: 'web'` and `lastRecordAt: null`), `getCurrentPosition`, `watchPosition` and `clearWatch` (through
  `navigator.geolocation`), `checkPermissions`, `requestPermissions` and `getDeviceInfo` (`platform: 'web'`, values
  parsed from the user agent, `sdkInt: -1`).
- **`NOT_READY`:** `setConfig`, `reset`, `getCurrentPosition`, `watchPosition` and `clearWatch` reject until
  `ready()` has resolved, like on Android.
- **Positions:** `getCurrentPosition` (unless `persist: false`) and `watchPosition` with `persist: true` also emit
  `location` events, but nothing is stored or uploaded. `WatchPositionOptions.interval` is ignored (the browser
  decides how often fixes arrive). `getCurrentPosition` has its own deadline, because browsers don't count the time
  the permission prompt is open.
- **Permissions:** `location` comes from the Permissions API (`'prompt'` when it can't tell); the other three are
  always `'granted'`. `requestPermissions` asks for a fix so the browser shows its prompt, and gives up after 60 s
  (the status stays `'prompt'`).
- **Everything else** rejects with `UNIMPLEMENTED`.

## Server side

- [docs/wire-format.md](docs/wire-format.md) describes every record variant, batching, templates, upload and retry
  rules, and JWT refresh.
- [docs/heartbeat.md](docs/heartbeat.md) covers heartbeat semantics, what reliability to expect on Android, and how
  to audit tracking gaps on the server.
- [docs/device-test-checklist.md](docs/device-test-checklist.md) is a manual test plan for real GMS, HMS and
  non-GMS phones.

In short, store records idempotently by `uuid` and answer `2xx` quickly (also for records you will never accept, or
they are retried until pruned). While tracking is on, expect some record at least every `maxInterval` seconds (about
every 9 minutes in deep idle when the app is not exempt from battery optimization), and flag longer gaps. A `tracking_stop` with reason `service_start_failed` means Android didn't let tracking resume in
the background; the app has to call `start()` again.

## Example app

[`example/`](example/) is a plain HTML/JS Capacitor app, with a button for every method and a live log of all 13
events:

```bash
npm ci && npm run build            # in the repository root
cd example && npm ci && npm run sync
cd android && ./gradlew assembleDebug -PlocationTracking.providers=gms,hms
```

## API

<docgen-index>

* [`ready(...)`](#ready)
* [`setConfig(...)`](#setconfig)
* [`reset(...)`](#reset)
* [`getState()`](#getstate)
* [`start()`](#start)
* [`startGeofences()`](#startgeofences)
* [`stop()`](#stop)
* [`changePace(...)`](#changepace)
* [`getCurrentPosition(...)`](#getcurrentposition)
* [`watchPosition(...)`](#watchposition)
* [`clearWatch(...)`](#clearwatch)
* [`getOdometer()`](#getodometer)
* [`setOdometer(...)`](#setodometer)
* [`resetOdometer()`](#resetodometer)
* [`getLocations(...)`](#getlocations)
* [`getCount()`](#getcount)
* [`insertLocation(...)`](#insertlocation)
* [`destroyLocations()`](#destroylocations)
* [`destroyLocation(...)`](#destroylocation)
* [`sync()`](#sync)
* [`addGeofence(...)`](#addgeofence)
* [`addGeofences(...)`](#addgeofences)
* [`removeGeofence(...)`](#removegeofence)
* [`removeGeofences(...)`](#removegeofences)
* [`getGeofences()`](#getgeofences)
* [`getGeofence(...)`](#getgeofence)
* [`geofenceExists(...)`](#geofenceexists)
* [`getHeartbeatStatus()`](#getheartbeatstatus)
* [`getProviderState()`](#getproviderstate)
* [`isPowerSaveMode()`](#ispowersavemode)
* [`getBatteryOptimizationStatus()`](#getbatteryoptimizationstatus)
* [`openBatteryOptimizationSettings()`](#openbatteryoptimizationsettings)
* [`getPowerManagerInfo()`](#getpowermanagerinfo)
* [`openPowerManagerSettings()`](#openpowermanagersettings)
* [`openLocationSettings()`](#openlocationsettings)
* [`openAppSettings()`](#openappsettings)
* [`getDeviceInfo()`](#getdeviceinfo)
* [`getSensors()`](#getsensors)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions(...)`](#requestpermissions)
* [`log(...)`](#log)
* [`getLog(...)`](#getlog)
* [`destroyLog()`](#destroylog)
* [`uploadLog(...)`](#uploadlog)
* [`emailLog(...)`](#emaillog)
* [`addListener('location', ...)`](#addlistenerlocation-)
* [`addListener('motionchange', ...)`](#addlistenermotionchange-)
* [`addListener('activitychange', ...)`](#addlisteneractivitychange-)
* [`addListener('providerchange', ...)`](#addlistenerproviderchange-)
* [`addListener('heartbeat', ...)`](#addlistenerheartbeat-)
* [`addListener('geofence', ...)`](#addlistenergeofence-)
* [`addListener('geofenceschange', ...)`](#addlistenergeofenceschange-)
* [`addListener('http', ...)`](#addlistenerhttp-)
* [`addListener('connectivitychange', ...)`](#addlistenerconnectivitychange-)
* [`addListener('powersavechange', ...)`](#addlistenerpowersavechange-)
* [`addListener('enabledchange', ...)`](#addlistenerenabledchange-)
* [`addListener('notificationaction', ...)`](#addlistenernotificationaction-)
* [`addListener('authorization', ...)`](#addlistenerauthorization-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### ready(...)

```typescript
ready(options?: ReadyOptions | undefined) => Promise<State>
```

Loads persisted config and state. Must resolve before most other methods (see NOT_READY rule).

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#readyoptions">ReadyOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### setConfig(...)

```typescript
setConfig(options: { config: Config; }) => Promise<State>
```

deep merge; arrays replaced; null resets key to default

| Param         | Type                                                   |
| ------------- | ------------------------------------------------------ |
| **`options`** | <code>{ config: <a href="#config">Config</a>; }</code> |

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### reset(...)

```typescript
reset(options?: { config?: Config | undefined; } | undefined) => Promise<State>
```

Resets the config to defaults, then applies the given config.

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code>{ config?: <a href="#config">Config</a>; }</code> |

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### getState()

```typescript
getState() => Promise<State>
```

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### start()

```typescript
start() => Promise<State>
```

Starts location tracking (mode 'location').

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### startGeofences()

```typescript
startGeofences() => Promise<State>
```

Starts geofence-only tracking (mode 'geofences').

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### stop()

```typescript
stop() => Promise<State>
```

**Returns:** <code>Promise&lt;<a href="#state">State</a>&gt;</code>

--------------------


### changePace(...)

```typescript
changePace(options: { isMoving: boolean; }) => Promise<void>
```

Forces the motion state to moving or stationary.

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ isMoving: boolean; }</code> |

--------------------


### getCurrentPosition(...)

```typescript
getCurrentPosition(options?: CurrentPositionOptions | undefined) => Promise<Location>
```

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#currentpositionoptions">CurrentPositionOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#location">Location</a>&gt;</code>

--------------------


### watchPosition(...)

```typescript
watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback) => Promise<CallbackID>
```

| Param          | Type                                                                    |
| -------------- | ----------------------------------------------------------------------- |
| **`options`**  | <code><a href="#watchpositionoptions">WatchPositionOptions</a></code>   |
| **`callback`** | <code><a href="#watchpositioncallback">WatchPositionCallback</a></code> |

**Returns:** <code>Promise&lt;string&gt;</code>

--------------------


### clearWatch(...)

```typescript
clearWatch(options: { id: CallbackID; }) => Promise<void>
```

| Param         | Type                         |
| ------------- | ---------------------------- |
| **`options`** | <code>{ id: string; }</code> |

--------------------


### getOdometer()

```typescript
getOdometer() => Promise<{ odometer: number; }>
```

**Returns:** <code>Promise&lt;{ odometer: number; }&gt;</code>

--------------------


### setOdometer(...)

```typescript
setOdometer(options: { odometer: number; }) => Promise<{ odometer: number; }>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ odometer: number; }</code> |

**Returns:** <code>Promise&lt;{ odometer: number; }&gt;</code>

--------------------


### resetOdometer()

```typescript
resetOdometer() => Promise<{ odometer: number; }>
```

**Returns:** <code>Promise&lt;{ odometer: number; }&gt;</code>

--------------------


### getLocations(...)

```typescript
getLocations(options?: { limit?: number | undefined; } | undefined) => Promise<{ locations: Location[]; }>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ limit?: number; }</code> |

**Returns:** <code>Promise&lt;{ locations: Location[]; }&gt;</code>

--------------------


### getCount()

```typescript
getCount() => Promise<{ count: number; }>
```

**Returns:** <code>Promise&lt;{ count: number; }&gt;</code>

--------------------


### insertLocation(...)

```typescript
insertLocation(options: { location: InsertLocationInput; }) => Promise<{ uuid: string; }>
```

| Param         | Type                                                                               |
| ------------- | ---------------------------------------------------------------------------------- |
| **`options`** | <code>{ location: <a href="#insertlocationinput">InsertLocationInput</a>; }</code> |

**Returns:** <code>Promise&lt;{ uuid: string; }&gt;</code>

--------------------


### destroyLocations()

```typescript
destroyLocations() => Promise<{ count: number; }>
```

**Returns:** <code>Promise&lt;{ count: number; }&gt;</code>

--------------------


### destroyLocation(...)

```typescript
destroyLocation(options: { uuid: string; }) => Promise<{ deleted: boolean; }>
```

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ uuid: string; }</code> |

**Returns:** <code>Promise&lt;{ deleted: boolean; }&gt;</code>

--------------------


### sync()

```typescript
sync() => Promise<{ locations: Location[]; }>
```

uploads whole queue now; resolves with uploaded records

**Returns:** <code>Promise&lt;{ locations: Location[]; }&gt;</code>

--------------------


### addGeofence(...)

```typescript
addGeofence(options: { geofence: Geofence; }) => Promise<void>
```

| Param         | Type                                                         |
| ------------- | ------------------------------------------------------------ |
| **`options`** | <code>{ geofence: <a href="#geofence">Geofence</a>; }</code> |

--------------------


### addGeofences(...)

```typescript
addGeofences(options: { geofences: Geofence[]; }) => Promise<void>
```

| Param         | Type                                    |
| ------------- | --------------------------------------- |
| **`options`** | <code>{ geofences: Geofence[]; }</code> |

--------------------


### removeGeofence(...)

```typescript
removeGeofence(options: { identifier: string; }) => Promise<void>
```

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ identifier: string; }</code> |

--------------------


### removeGeofences(...)

```typescript
removeGeofences(options?: { identifiers?: string[] | undefined; } | undefined) => Promise<void>
```

identifiers omitted (or null) = remove all; an empty array removes nothing

| Param         | Type                                     |
| ------------- | ---------------------------------------- |
| **`options`** | <code>{ identifiers?: string[]; }</code> |

--------------------


### getGeofences()

```typescript
getGeofences() => Promise<{ geofences: Geofence[]; }>
```

**Returns:** <code>Promise&lt;{ geofences: Geofence[]; }&gt;</code>

--------------------


### getGeofence(...)

```typescript
getGeofence(options: { identifier: string; }) => Promise<{ geofence: Geofence | null; }>
```

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ identifier: string; }</code> |

**Returns:** <code>Promise&lt;{ geofence: <a href="#geofence">Geofence</a> | null; }&gt;</code>

--------------------


### geofenceExists(...)

```typescript
geofenceExists(options: { identifier: string; }) => Promise<{ exists: boolean; }>
```

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ identifier: string; }</code> |

**Returns:** <code>Promise&lt;{ exists: boolean; }&gt;</code>

--------------------


### getHeartbeatStatus()

```typescript
getHeartbeatStatus() => Promise<HeartbeatStatus>
```

**Returns:** <code>Promise&lt;<a href="#heartbeatstatus">HeartbeatStatus</a>&gt;</code>

--------------------


### getProviderState()

```typescript
getProviderState() => Promise<ProviderState>
```

**Returns:** <code>Promise&lt;<a href="#providerstate">ProviderState</a>&gt;</code>

--------------------


### isPowerSaveMode()

```typescript
isPowerSaveMode() => Promise<{ isPowerSaveMode: boolean; }>
```

**Returns:** <code>Promise&lt;{ isPowerSaveMode: boolean; }&gt;</code>

--------------------


### getBatteryOptimizationStatus()

```typescript
getBatteryOptimizationStatus() => Promise<BatteryOptimizationStatus>
```

**Returns:** <code>Promise&lt;<a href="#batteryoptimizationstatus">BatteryOptimizationStatus</a>&gt;</code>

--------------------


### openBatteryOptimizationSettings()

```typescript
openBatteryOptimizationSettings() => Promise<{ opened: boolean; }>
```

**Returns:** <code>Promise&lt;{ opened: boolean; }&gt;</code>

--------------------


### getPowerManagerInfo()

```typescript
getPowerManagerInfo() => Promise<PowerManagerInfo>
```

**Returns:** <code>Promise&lt;<a href="#powermanagerinfo">PowerManagerInfo</a>&gt;</code>

--------------------


### openPowerManagerSettings()

```typescript
openPowerManagerSettings() => Promise<{ opened: boolean; }>
```

**Returns:** <code>Promise&lt;{ opened: boolean; }&gt;</code>

--------------------


### openLocationSettings()

```typescript
openLocationSettings() => Promise<{ opened: boolean; }>
```

**Returns:** <code>Promise&lt;{ opened: boolean; }&gt;</code>

--------------------


### openAppSettings()

```typescript
openAppSettings() => Promise<{ opened: boolean; }>
```

**Returns:** <code>Promise&lt;{ opened: boolean; }&gt;</code>

--------------------


### getDeviceInfo()

```typescript
getDeviceInfo() => Promise<DeviceInfo>
```

**Returns:** <code>Promise&lt;<a href="#deviceinfo">DeviceInfo</a>&gt;</code>

--------------------


### getSensors()

```typescript
getSensors() => Promise<Sensors>
```

**Returns:** <code>Promise&lt;<a href="#sensors">Sensors</a>&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions(...)

```typescript
requestPermissions(options?: { permissions?: PermissionType[] | undefined; } | undefined) => Promise<PermissionStatus>
```

default all four, order: location, notifications, activityRecognition, backgroundLocation

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ permissions?: PermissionType[]; }</code> |

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### log(...)

```typescript
log(options: { level: Exclude<LogLevel, 'off'>; message: string; }) => Promise<void>
```

| Param         | Type                                                                                          |
| ------------- | --------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ level: 'error' \| 'warn' \| 'info' \| 'debug' \| 'verbose'; message: string; }</code> |

--------------------


### getLog(...)

```typescript
getLog(options?: LogQuery | undefined) => Promise<{ log: string; }>
```

| Param         | Type                                          |
| ------------- | --------------------------------------------- |
| **`options`** | <code><a href="#logquery">LogQuery</a></code> |

**Returns:** <code>Promise&lt;{ log: string; }&gt;</code>

--------------------


### destroyLog()

```typescript
destroyLog() => Promise<void>
```

--------------------


### uploadLog(...)

```typescript
uploadLog(options: { url: string; headers?: Record<string, string>; params?: Record<string, unknown>; }) => Promise<{ success: boolean; status: number; }>
```

| Param         | Type                                                                                                                                                      |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ url: string; headers?: <a href="#record">Record</a>&lt;string, string&gt;; params?: <a href="#record">Record</a>&lt;string, unknown&gt;; }</code> |

**Returns:** <code>Promise&lt;{ success: boolean; status: number; }&gt;</code>

--------------------


### emailLog(...)

```typescript
emailLog(options: { email: string; subject?: string; }) => Promise<void>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ email: string; subject?: string; }</code> |

--------------------


### addListener('location', ...)

```typescript
addListener(eventName: 'location', listenerFunc: (e: Location) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                          |
| ------------------ | ------------------------------------------------------------- |
| **`eventName`**    | <code>'location'</code>                                       |
| **`listenerFunc`** | <code>(e: <a href="#location">Location</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('motionchange', ...)

```typescript
addListener(eventName: 'motionchange', listenerFunc: (e: MotionChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                            |
| ------------------ | ------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'motionchange'</code>                                                     |
| **`listenerFunc`** | <code>(e: <a href="#motionchangeevent">MotionChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('activitychange', ...)

```typescript
addListener(eventName: 'activitychange', listenerFunc: (e: ActivityChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                |
| ------------------ | ----------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'activitychange'</code>                                                       |
| **`listenerFunc`** | <code>(e: <a href="#activitychangeevent">ActivityChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('providerchange', ...)

```typescript
addListener(eventName: 'providerchange', listenerFunc: (e: ProviderState) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                    |
| ------------------ | ----------------------------------------------------------------------- |
| **`eventName`**    | <code>'providerchange'</code>                                           |
| **`listenerFunc`** | <code>(e: <a href="#providerstate">ProviderState</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('heartbeat', ...)

```typescript
addListener(eventName: 'heartbeat', listenerFunc: (e: HeartbeatEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                      |
| ------------------ | ------------------------------------------------------------------------- |
| **`eventName`**    | <code>'heartbeat'</code>                                                  |
| **`listenerFunc`** | <code>(e: <a href="#heartbeatevent">HeartbeatEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('geofence', ...)

```typescript
addListener(eventName: 'geofence', listenerFunc: (e: GeofenceEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                    |
| ------------------ | ----------------------------------------------------------------------- |
| **`eventName`**    | <code>'geofence'</code>                                                 |
| **`listenerFunc`** | <code>(e: <a href="#geofenceevent">GeofenceEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('geofenceschange', ...)

```typescript
addListener(eventName: 'geofenceschange', listenerFunc: (e: GeofencesChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'geofenceschange'</code>                                                        |
| **`listenerFunc`** | <code>(e: <a href="#geofenceschangeevent">GeofencesChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('http', ...)

```typescript
addListener(eventName: 'http', listenerFunc: (e: HttpEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                            |
| ------------------ | --------------------------------------------------------------- |
| **`eventName`**    | <code>'http'</code>                                             |
| **`listenerFunc`** | <code>(e: <a href="#httpevent">HttpEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('connectivitychange', ...)

```typescript
addListener(eventName: 'connectivitychange', listenerFunc: (e: ConnectivityChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'connectivitychange'</code>                                                           |
| **`listenerFunc`** | <code>(e: <a href="#connectivitychangeevent">ConnectivityChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('powersavechange', ...)

```typescript
addListener(eventName: 'powersavechange', listenerFunc: (e: PowerSaveChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'powersavechange'</code>                                                        |
| **`listenerFunc`** | <code>(e: <a href="#powersavechangeevent">PowerSaveChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('enabledchange', ...)

```typescript
addListener(eventName: 'enabledchange', listenerFunc: (e: EnabledChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                              |
| ------------------ | --------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'enabledchange'</code>                                                      |
| **`listenerFunc`** | <code>(e: <a href="#enabledchangeevent">EnabledChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('notificationaction', ...)

```typescript
addListener(eventName: 'notificationaction', listenerFunc: (e: NotificationActionEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'notificationaction'</code>                                                           |
| **`listenerFunc`** | <code>(e: <a href="#notificationactionevent">NotificationActionEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('authorization', ...)

```typescript
addListener(eventName: 'authorization', listenerFunc: (e: AuthorizationEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                              |
| ------------------ | --------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'authorization'</code>                                                      |
| **`listenerFunc`** | <code>(e: <a href="#authorizationevent">AuthorizationEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### State

| Prop               | Type                                                        | Description                   |
| ------------------ | ----------------------------------------------------------- | ----------------------------- |
| **`enabled`**      | <code>boolean</code>                                        |                               |
| **`trackingMode`** | <code><a href="#trackingmode">TrackingMode</a></code>       |                               |
| **`isMoving`**     | <code>boolean</code>                                        |                               |
| **`odometer`**     | <code>number</code>                                         |                               |
| **`backend`**      | <code><a href="#locationbackend">LocationBackend</a></code> |                               |
| **`lastRecordAt`** | <code>string \| null</code>                                 |                               |
| **`config`**       | <code><a href="#config">Config</a></code>                   | fully populated with defaults |


#### Config

| Prop                                | Type                                                                                    | Description  | Default             |
| ----------------------------------- | --------------------------------------------------------------------------------------- | ------------ | ------------------- |
| **`geolocation`**                   | <code><a href="#geolocationconfig">GeolocationConfig</a></code>                         |              |                     |
| **`activity`**                      | <code><a href="#activityconfig">ActivityConfig</a></code>                               |              |                     |
| **`heartbeat`**                     | <code><a href="#heartbeatconfig">HeartbeatConfig</a></code>                             |              |                     |
| **`http`**                          | <code><a href="#httpconfig">HttpConfig</a></code>                                       |              |                     |
| **`persistence`**                   | <code><a href="#persistenceconfig">PersistenceConfig</a></code>                         |              |                     |
| **`app`**                           | <code><a href="#appconfig">AppConfig</a></code>                                         |              |                     |
| **`notification`**                  | <code><a href="#notificationconfig">NotificationConfig</a></code>                       |              |                     |
| **`geofence`**                      | <code><a href="#geofenceconfig">GeofenceConfig</a></code>                               |              |                     |
| **`logger`**                        | <code><a href="#loggerconfig">LoggerConfig</a></code>                                   |              |                     |
| **`backgroundPermissionRationale`** | <code><a href="#backgroundpermissionrationale">BackgroundPermissionRationale</a></code> |              |                     |
| **`locationProvider`**              | <code><a href="#locationprovidersetting">LocationProviderSetting</a></code>             | Android only | <code>'auto'</code> |


#### GeolocationConfig

| Prop                                | Type                                                                  | Description                            | Default             |
| ----------------------------------- | --------------------------------------------------------------------- | -------------------------------------- | ------------------- |
| **`desiredAccuracy`**               | <code><a href="#desiredaccuracy">DesiredAccuracy</a></code>           |                                        | <code>'high'</code> |
| **`distanceFilter`**                | <code>number</code>                                                   | meters                                 | <code>10</code>     |
| **`locationUpdateInterval`**        | <code>number</code>                                                   | ms                                     | <code>1000</code>   |
| **`fastestLocationUpdateInterval`** | <code>number</code>                                                   | ms                                     | <code>500</code>    |
| **`disableElasticity`**             | <code>boolean</code>                                                  |                                        | <code>false</code>  |
| **`elasticityMultiplier`**          | <code>number</code>                                                   |                                        | <code>1</code>      |
| **`stationaryRadius`**              | <code>number</code>                                                   | meters                                 | <code>25</code>     |
| **`stopTimeout`**                   | <code>number</code>                                                   | minutes                                | <code>5</code>      |
| **`stopAfterElapsedMinutes`**       | <code>number</code>                                                   | minutes, 0 = off                       | <code>0</code>      |
| **`stopOnStationary`**              | <code>boolean</code>                                                  |                                        | <code>false</code>  |
| **`locationTimeout`**               | <code>number</code>                                                   | default getCurrentPosition timeout, ms | <code>30000</code>  |
| **`filter`**                        | <code><a href="#locationfilterconfig">LocationFilterConfig</a></code> |                                        |                     |


#### LocationFilterConfig

| Prop                            | Type                 | Description                                                        | Default            |
| ------------------------------- | -------------------- | ------------------------------------------------------------------ | ------------------ |
| **`useKalman`**                 | <code>boolean</code> |                                                                    | <code>false</code> |
| **`trackingAccuracyThreshold`** | <code>number</code>  | Reject fixes with accuracy worse than this (m).                    | <code>100</code>   |
| **`maxImpliedSpeed`**           | <code>number</code>  | Reject fixes implying speed above this (m/s); 0 = off.             | <code>80</code>    |
| **`odometerAccuracyThreshold`** | <code>number</code>  | Odometer ignores fixes worse than this (m).                        | <code>20</code>    |
| **`allowIdenticalLocations`**   | <code>boolean</code> |                                                                    | <code>false</code> |
| **`rejectMockLocations`**       | <code>boolean</code> | Drop mock fixes entirely (otherwise they are kept with mock:true). | <code>false</code> |


#### ActivityConfig

| Prop                                       | Type                 | Description | Default            |
| ------------------------------------------ | -------------------- | ----------- | ------------------ |
| **`disableMotionActivityUpdates`**         | <code>boolean</code> |             | <code>false</code> |
| **`activityRecognitionInterval`**          | <code>number</code>  | ms          | <code>10000</code> |
| **`minimumActivityRecognitionConfidence`** | <code>number</code>  | 0-100       | <code>75</code>    |
| **`motionTriggerDelay`**                   | <code>number</code>  | ms          | <code>0</code>     |
| **`disableStopDetection`**                 | <code>boolean</code> |             | <code>false</code> |


#### HeartbeatConfig

| Prop              | Type                 | Description                | Default           |
| ----------------- | -------------------- | -------------------------- | ----------------- |
| **`enabled`**     | <code>boolean</code> |                            | <code>true</code> |
| **`minInterval`** | <code>number</code>  | seconds, min 60            | <code>180</code>  |
| **`maxInterval`** | <code>number</code>  | seconds, &gt;= minInterval | <code>300</code>  |


#### HttpConfig

| Prop                            | Type                                                                        | Description                                               | Default                 |
| ------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------- | ----------------------- |
| **`url`**                       | <code>string \| null</code>                                                 | No url =&gt; nothing is uploaded (records stay queued).   |                         |
| **`method`**                    | <code><a href="#httpmethod">HttpMethod</a></code>                           |                                                           | <code>'POST'</code>     |
| **`headers`**                   | <code><a href="#record">Record</a>&lt;string, string&gt;</code>             |                                                           | <code>{}</code>         |
| **`params`**                    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code>            | merged into the ROOT of every request body                | <code>{}</code>         |
| **`autoSync`**                  | <code>boolean</code>                                                        |                                                           | <code>true</code>       |
| **`autoSyncThreshold`**         | <code>number</code>                                                         | upload when queue &gt;= threshold; 0 = every record       | <code>0</code>          |
| **`batchSync`**                 | <code>boolean</code>                                                        |                                                           | <code>false</code>      |
| **`maxBatchSize`**              | <code>number</code>                                                         |                                                           | <code>100</code>        |
| **`disableAutoSyncOnCellular`** | <code>boolean</code>                                                        |                                                           | <code>false</code>      |
| **`rootProperty`**              | <code>string</code>                                                         | '.' = no wrapping                                         | <code>'location'</code> |
| **`locationTemplate`**          | <code>string \| null</code>                                                 | JSON text with `&lt;%= name %&gt;` placeholders           |                         |
| **`geofenceTemplate`**          | <code>string \| null</code>                                                 | used for event 'geofence'; falls back to locationTemplate |                         |
| **`timeout`**                   | <code>number</code>                                                         | ms                                                        | <code>60000</code>      |
| **`authorization`**             | <code><a href="#authorizationconfig">AuthorizationConfig</a> \| null</code> |                                                           |                         |


#### AuthorizationConfig

| Prop                         | Type                                                            | Description                                 | Default             |
| ---------------------------- | --------------------------------------------------------------- | ------------------------------------------- | ------------------- |
| **`strategy`**               | <code>'JWT'</code>                                              |                                             | <code>'JWT'</code>  |
| **`accessToken`**            | <code>string</code>                                             |                                             |                     |
| **`refreshToken`**           | <code>string</code>                                             |                                             |                     |
| **`refreshUrl`**             | <code>string</code>                                             |                                             |                     |
| **`refreshPayload`**         | <code><a href="#record">Record</a>&lt;string, string&gt;</code> | String values may contain `{refreshToken}`. | <code>{}</code>     |
| **`refreshHeaders`**         | <code><a href="#record">Record</a>&lt;string, string&gt;</code> |                                             |                     |
| **`refreshPayloadEncoding`** | <code>'json' \| 'form'</code>                                   |                                             | <code>'json'</code> |
| **`expires`**                | <code>number</code>                                             | access-token expiry, epoch ms; -1 = unknown | <code>-1</code>     |


#### PersistenceConfig

| Prop                      | Type                                                             | Description                                           | Default         |
| ------------------------- | ---------------------------------------------------------------- | ----------------------------------------------------- | --------------- |
| **`maxDaysToPersist`**    | <code>number</code>                                              |                                                       | <code>7</code>  |
| **`maxRecordsToPersist`** | <code>number</code>                                              | -1 = unlimited                                        | <code>-1</code> |
| **`extras`**              | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | merged into `extras` of every record at creation time | <code>{}</code> |


#### AppConfig

| Prop                  | Type                 | Default            |
| --------------------- | -------------------- | ------------------ |
| **`stopOnTerminate`** | <code>boolean</code> | <code>true</code>  |
| **`startOnBoot`**     | <code>boolean</code> | <code>false</code> |


#### NotificationConfig

| Prop              | Type                                                                  | Description                                 | Default                                                |
| ----------------- | --------------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------------------ |
| **`title`**       | <code>string</code>                                                   |                                             | <code>app label</code>                                 |
| **`text`**        | <code>string</code>                                                   |                                             | <code>'Location tracking is active'</code>             |
| **`smallIcon`**   | <code>string</code>                                                   | 'drawable/name' \| 'mipmap/name'            | <code>plugin icon 'drawable/lt_ic_notification'</code> |
| **`largeIcon`**   | <code>string</code>                                                   |                                             |                                                        |
| **`color`**       | <code>string</code>                                                   | '#RRGGBB'                                   |                                                        |
| **`priority`**    | <code><a href="#notificationpriority">NotificationPriority</a></code> |                                             | <code>'default'</code>                                 |
| **`channelId`**   | <code>string</code>                                                   |                                             | <code>'location_tracking'</code>                       |
| **`channelName`** | <code>string</code>                                                   |                                             | <code>'Location tracking'</code>                       |
| **`actions`**     | <code>NotificationActionButton[]</code>                               | max 3; tap =&gt; 'notificationaction' event |                                                        |


#### NotificationActionButton

| Prop        | Type                |
| ----------- | ------------------- |
| **`id`**    | <code>string</code> |
| **`label`** | <code>string</code> |


#### GeofenceConfig

| Prop                      | Type                 | Default           |
| ------------------------- | -------------------- | ----------------- |
| **`initialTriggerEntry`** | <code>boolean</code> | <code>true</code> |


#### LoggerConfig

| Prop             | Type                                          | Default             |
| ---------------- | --------------------------------------------- | ------------------- |
| **`logLevel`**   | <code><a href="#loglevel">LogLevel</a></code> | <code>'info'</code> |
| **`logMaxDays`** | <code>number</code>                           | <code>3</code>      |


#### BackgroundPermissionRationale

| Prop                 | Type                | Description                           |
| -------------------- | ------------------- | ------------------------------------- |
| **`title`**          | <code>string</code> | defaults from plugin string resources |
| **`message`**        | <code>string</code> |                                       |
| **`positiveAction`** | <code>string</code> |                                       |
| **`negativeAction`** | <code>string</code> |                                       |


#### ReadyOptions

| Prop         | Type                                      | Description                                                                                                                                          | Default           |
| ------------ | ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| **`config`** | <code><a href="#config">Config</a></code> |                                                                                                                                                      |                   |
| **`reset`**  | <code>boolean</code>                      | true: config = defaults + given config (every launch). false: given config applied only on the very first ready(); afterwards persisted config wins. | <code>true</code> |


#### Location

| Prop                      | Type                                                                                                                                                     | Description                                                                                                                                                                              |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`uuid`**                | <code>string</code>                                                                                                                                      |                                                                                                                                                                                          |
| **`event`**               | <code><a href="#recordevent">RecordEvent</a></code>                                                                                                      |                                                                                                                                                                                          |
| **`timestamp`**           | <code>string \| null</code>                                                                                                                              | fix time (ISO-8601 UTC ms); null if no location ever known                                                                                                                               |
| **`recorded_at`**         | <code>string</code>                                                                                                                                      | record creation time                                                                                                                                                                     |
| **`sent_at`**             | <code>string</code>                                                                                                                                      | only present in HTTP bodies                                                                                                                                                              |
| **`elapsed_realtime_ms`** | <code>number</code>                                                                                                                                      |                                                                                                                                                                                          |
| **`boot_count`**          | <code>number</code>                                                                                                                                      | -1 if unavailable                                                                                                                                                                        |
| **`is_moving`**           | <code>boolean</code>                                                                                                                                     |                                                                                                                                                                                          |
| **`odometer`**            | <code>number</code>                                                                                                                                      |                                                                                                                                                                                          |
| **`mock`**                | <code>boolean</code>                                                                                                                                     |                                                                                                                                                                                          |
| **`coords`**              | <code><a href="#coords">Coords</a> \| null</code>                                                                                                        |                                                                                                                                                                                          |
| **`activity`**            | <code>{ type: <a href="#activitytype">ActivityType</a>; confidence: number; }</code>                                                                     |                                                                                                                                                                                          |
| **`battery`**             | <code>{ level: number; is_charging: boolean; }</code>                                                                                                    | level 0..1, -1 unknown                                                                                                                                                                   |
| **`backend`**             | <code><a href="#locationbackend">LocationBackend</a> \| null</code>                                                                                      |                                                                                                                                                                                          |
| **`extras`**              | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code>                                                                                         |                                                                                                                                                                                          |
| **`geofence`**            | <code>{ identifier: string; action: <a href="#geofenceaction">GeofenceAction</a>; extras?: <a href="#record">Record</a>&lt;string, unknown&gt;; }</code> | event 'geofence' only                                                                                                                                                                    |
| **`provider`**            | <code><a href="#providerstate">ProviderState</a></code>                                                                                                  | event 'providerchange' only                                                                                                                                                              |
| **`reason`**              | <code>string</code>                                                                                                                                      | tracking_start: start\|start_geofences\|boot\|restore\|package_replaced; tracking_stop: stop\|stop_on_stationary\|stop_after_elapsed\|terminate\|permission_denied\|service_start_failed |


#### Coords

| Prop                    | Type                        |
| ----------------------- | --------------------------- |
| **`latitude`**          | <code>number</code>         |
| **`longitude`**         | <code>number</code>         |
| **`accuracy`**          | <code>number</code>         |
| **`altitude`**          | <code>number \| null</code> |
| **`altitude_accuracy`** | <code>number \| null</code> |
| **`speed`**             | <code>number \| null</code> |
| **`speed_accuracy`**    | <code>number \| null</code> |
| **`heading`**           | <code>number \| null</code> |
| **`heading_accuracy`**  | <code>number \| null</code> |


#### ProviderState

| Prop             | Type                                                        |
| ---------------- | ----------------------------------------------------------- |
| **`enabled`**    | <code>boolean</code>                                        |
| **`gps`**        | <code>boolean</code>                                        |
| **`network`**    | <code>boolean</code>                                        |
| **`permission`** | <code>'always' \| 'when_in_use' \| 'denied'</code>          |
| **`accuracy`**   | <code>'precise' \| 'approximate' \| 'none'</code>           |
| **`backend`**    | <code><a href="#locationbackend">LocationBackend</a></code> |


#### CurrentPositionOptions

| Prop                  | Type                                                             | Description | Default                                  |
| --------------------- | ---------------------------------------------------------------- | ----------- | ---------------------------------------- |
| **`samples`**         | <code>number</code>                                              |             | <code>3</code>                           |
| **`timeout`**         | <code>number</code>                                              | ms          | <code>geolocation.locationTimeout</code> |
| **`maximumAge`**      | <code>number</code>                                              | ms          | <code>0</code>                           |
| **`desiredAccuracy`** | <code><a href="#desiredaccuracy">DesiredAccuracy</a></code>      |             | <code>'high'</code>                      |
| **`persist`**         | <code>boolean</code>                                             |             | <code>true</code>                        |
| **`extras`**          | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |             |                                          |


#### WatchPositionOptions

| Prop                  | Type                                                             | Description | Default             |
| --------------------- | ---------------------------------------------------------------- | ----------- | ------------------- |
| **`interval`**        | <code>number</code>                                              | ms          | <code>1000</code>   |
| **`desiredAccuracy`** | <code><a href="#desiredaccuracy">DesiredAccuracy</a></code>      |             | <code>'high'</code> |
| **`persist`**         | <code>boolean</code>                                             |             | <code>false</code>  |
| **`extras`**          | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |             |                     |


#### InsertLocationInput

| Prop            | Type                                                                                                                                                                      |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`coords`**    | <code><a href="#pick">Pick</a>&lt;<a href="#coords">Coords</a>, 'latitude' \| 'longitude'&gt; & <a href="#partial">Partial</a>&lt;<a href="#coords">Coords</a>&gt;</code> |
| **`timestamp`** | <code>string</code>                                                                                                                                                       |
| **`event`**     | <code><a href="#recordevent">RecordEvent</a></code>                                                                                                                       |
| **`is_moving`** | <code>boolean</code>                                                                                                                                                      |
| **`extras`**    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code>                                                                                                          |


#### Geofence

Either (latitude, longitude, radius) = circle, or vertices = polygon. For polygons, getGeofences() also returns
the computed enclosing circle.

| Prop                 | Type                                                             | Description                     | Default            |
| -------------------- | ---------------------------------------------------------------- | ------------------------------- | ------------------ |
| **`identifier`**     | <code>string</code>                                              |                                 |                    |
| **`latitude`**       | <code>number</code>                                              |                                 |                    |
| **`longitude`**      | <code>number</code>                                              |                                 |                    |
| **`radius`**         | <code>number</code>                                              | m                               |                    |
| **`vertices`**       | <code>[number, number][]</code>                                  | [[lat,lng], ...] &gt;= 3 points |                    |
| **`notifyOnEntry`**  | <code>boolean</code>                                             |                                 | <code>true</code>  |
| **`notifyOnExit`**   | <code>boolean</code>                                             |                                 | <code>true</code>  |
| **`notifyOnDwell`**  | <code>boolean</code>                                             |                                 | <code>false</code> |
| **`loiteringDelay`** | <code>number</code>                                              | ms                              | <code>30000</code> |
| **`extras`**         | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |                                 |                    |


#### HeartbeatStatus

| Prop                                 | Type                                                            |
| ------------------------------------ | --------------------------------------------------------------- |
| **`enabled`**                        | <code>boolean</code>                                            |
| **`minInterval`**                    | <code>number</code>                                             |
| **`maxInterval`**                    | <code>number</code>                                             |
| **`lastRecordAt`**                   | <code>string \| null</code>                                     |
| **`lastHeartbeatAt`**                | <code>string \| null</code>                                     |
| **`nextHeartbeatAt`**                | <code>string \| null</code>                                     |
| **`strategy`**                       | <code><a href="#heartbeatstrategy">HeartbeatStrategy</a></code> |
| **`canScheduleExactAlarms`**         | <code>boolean</code>                                            |
| **`isIgnoringBatteryOptimizations`** | <code>boolean</code>                                            |
| **`isDeviceIdleMode`**               | <code>boolean</code>                                            |
| **`isPowerSaveMode`**                | <code>boolean</code>                                            |
| **`pendingHeartbeats`**              | <code>number</code>                                             |


#### BatteryOptimizationStatus

| Prop                                 | Type                 |
| ------------------------------------ | -------------------- |
| **`isIgnoringBatteryOptimizations`** | <code>boolean</code> |
| **`canScheduleExactAlarms`**         | <code>boolean</code> |
| **`isDeviceIdleMode`**               | <code>boolean</code> |


#### PowerManagerInfo

| Prop               | Type                 |
| ------------------ | -------------------- |
| **`manufacturer`** | <code>string</code>  |
| **`available`**    | <code>boolean</code> |


#### DeviceInfo

| Prop                    | Type                                                        |
| ----------------------- | ----------------------------------------------------------- |
| **`platform`**          | <code>'android' \| 'web'</code>                             |
| **`manufacturer`**      | <code>string</code>                                         |
| **`model`**             | <code>string</code>                                         |
| **`brand`**             | <code>string</code>                                         |
| **`osVersion`**         | <code>string</code>                                         |
| **`sdkInt`**            | <code>number</code>                                         |
| **`pluginVersion`**     | <code>string</code>                                         |
| **`gmsAvailable`**      | <code>boolean</code>                                        |
| **`hmsAvailable`**      | <code>boolean</code>                                        |
| **`backend`**           | <code><a href="#locationbackend">LocationBackend</a></code> |
| **`packagedProviders`** | <code>string[]</code>                                       |


#### Sensors

| Prop                    | Type                 |
| ----------------------- | -------------------- |
| **`accelerometer`**     | <code>boolean</code> |
| **`gyroscope`**         | <code>boolean</code> |
| **`magnetometer`**      | <code>boolean</code> |
| **`significantMotion`** | <code>boolean</code> |
| **`stepCounter`**       | <code>boolean</code> |
| **`stepDetector`**      | <code>boolean</code> |
| **`barometer`**         | <code>boolean</code> |


#### PermissionStatus

| Prop                      | Type                                                        |
| ------------------------- | ----------------------------------------------------------- |
| **`location`**            | <code><a href="#permissionstate">PermissionState</a></code> |
| **`backgroundLocation`**  | <code><a href="#permissionstate">PermissionState</a></code> |
| **`activityRecognition`** | <code><a href="#permissionstate">PermissionState</a></code> |
| **`notifications`**       | <code><a href="#permissionstate">PermissionState</a></code> |


#### LogQuery

| Prop        | Type                                          |
| ----------- | --------------------------------------------- |
| **`start`** | <code>number</code>                           |
| **`end`**   | <code>number</code>                           |
| **`level`** | <code><a href="#loglevel">LogLevel</a></code> |
| **`limit`** | <code>number</code>                           |
| **`order`** | <code>'asc' \| 'desc'</code>                  |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### MotionChangeEvent

| Prop           | Type                                          |
| -------------- | --------------------------------------------- |
| **`isMoving`** | <code>boolean</code>                          |
| **`location`** | <code><a href="#location">Location</a></code> |


#### ActivityChangeEvent

| Prop             | Type                                                  |
| ---------------- | ----------------------------------------------------- |
| **`activity`**   | <code><a href="#activitytype">ActivityType</a></code> |
| **`confidence`** | <code>number</code>                                   |


#### HeartbeatEvent

| Prop           | Type                                          |
| -------------- | --------------------------------------------- |
| **`location`** | <code><a href="#location">Location</a></code> |


#### GeofenceEvent

| Prop             | Type                                                             |
| ---------------- | ---------------------------------------------------------------- |
| **`identifier`** | <code>string</code>                                              |
| **`action`**     | <code><a href="#geofenceaction">GeofenceAction</a></code>        |
| **`location`**   | <code><a href="#location">Location</a></code>                    |
| **`extras`**     | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |


#### GeofencesChangeEvent

| Prop      | Type                    |
| --------- | ----------------------- |
| **`on`**  | <code>Geofence[]</code> |
| **`off`** | <code>string[]</code>   |


#### HttpEvent

| Prop               | Type                  |
| ------------------ | --------------------- |
| **`success`**      | <code>boolean</code>  |
| **`status`**       | <code>number</code>   |
| **`responseText`** | <code>string</code>   |
| **`uuids`**        | <code>string[]</code> |


#### ConnectivityChangeEvent

| Prop            | Type                                                          |
| --------------- | ------------------------------------------------------------- |
| **`connected`** | <code>boolean</code>                                          |
| **`type`**      | <code><a href="#connectivitytype">ConnectivityType</a></code> |


#### PowerSaveChangeEvent

| Prop                  | Type                 |
| --------------------- | -------------------- |
| **`isPowerSaveMode`** | <code>boolean</code> |


#### EnabledChangeEvent

| Prop          | Type                 |
| ------------- | -------------------- |
| **`enabled`** | <code>boolean</code> |


#### NotificationActionEvent

| Prop     | Type                |
| -------- | ------------------- |
| **`id`** | <code>string</code> |


#### AuthorizationEvent

| Prop           | Type                                                             |
| -------------- | ---------------------------------------------------------------- |
| **`success`**  | <code>boolean</code>                                             |
| **`status`**   | <code>number</code>                                              |
| **`error`**    | <code>string</code>                                              |
| **`response`** | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |


### Type Aliases


#### TrackingMode

<code>'location' | 'geofences'</code>


#### LocationBackend

<code>'gms' | 'hms' | 'android' | 'web'</code>


#### DesiredAccuracy

<code>'high' | 'balanced' | 'low' | 'passive'</code>


#### HttpMethod

<code>'POST' | 'PUT' | 'PATCH'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### NotificationPriority

<code>'min' | 'low' | 'default' | 'high' | 'max'</code>


#### LogLevel

<code>'off' | 'error' | 'warn' | 'info' | 'debug' | 'verbose'</code>


#### LocationProviderSetting

<code>'auto' | 'gms' | 'hms' | 'android'</code>


#### RecordEvent

<code>'location' | 'motionchange' | 'current_position' | 'watch_position' | 'heartbeat' | 'geofence' | 'tracking_start' | 'tracking_stop' | 'providerchange'</code>


#### ActivityType

<code>'still' | 'on_foot' | 'walking' | 'running' | 'on_bicycle' | 'in_vehicle' | 'unknown'</code>


#### GeofenceAction

<code>'ENTER' | 'EXIT' | 'DWELL'</code>


#### WatchPositionCallback

<code>(location: <a href="#location">Location</a> | null, error?: { code: <a href="#errorcode">ErrorCode</a>; message: string; }): void</code>


#### ErrorCode

Rejection `code` of every failed promise.

<code>'NOT_READY' | 'PERMISSION_DENIED' | 'LOCATION_DISABLED' | 'TIMEOUT' | 'UNAVAILABLE' | 'INVALID_ARGUMENT' | 'NOT_FOUND' | 'NO_URL' | 'HTTP_ERROR' | 'NETWORK_ERROR' | 'TOO_MANY_GEOFENCES' | 'NO_ACTIVITY' | 'IO_ERROR' | 'UNIMPLEMENTED' | 'INTERNAL'</code>


#### CallbackID

<code>string</code>


#### Pick

From T, pick a set of properties whose keys are in the union K

<code>{ [P in K]: T[P]; }</code>


#### Partial

Make all properties in T optional

<code>{ [P in keyof T]?: T[P]; }</code>


#### HeartbeatStrategy

<code>'exact' | 'listener_with_backup' | 'idle_paced' | 'disabled'</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### PermissionType

<code>'location' | 'backgroundLocation' | 'activityRecognition' | 'notifications'</code>


#### Exclude

<a href="#exclude">Exclude</a> from T those types that are assignable to U

<code>T extends U ? never : T</code>


#### ConnectivityType

<code>'wifi' | 'cellular' | 'ethernet' | 'other' | 'none'</code>

</docgen-api>

## License

UNLICENSED: proprietary, © Bricks Soft. See `package.json`.
