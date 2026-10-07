# @bricks-soft/capacitor-location-tracking

Background location tracking for Capacitor 8 on Android. It records locations with Google Play services (GMS),
Huawei Location Kit (HMS) or the plain Android `LocationManager`, stores them in SQLite and uploads them to your
server. While tracking is on, it also sends **heartbeat** audit records every 3–5 minutes, so your server can tell
whether the user really kept tracking on.

> **Clean-room implementation.** This plugin offers a feature set similar to
> [transistorsoft/capacitor-background-geolocation](https://github.com/transistorsoft/capacitor-background-geolocation),
> with a new, smaller API. It was written from scratch: it contains no code from that project (whose native engine is
> closed-source and commercial) and is **not affiliated with, endorsed by or supported by Transistor Software**.
> Moving an app from its v8 release: [docs/migrating-from-transistor-v8.md](docs/migrating-from-transistor-v8.md).

## Contents

- [Features](#features)
- [Platform status](#platform-status)
- [Installation](#installation)
- [Android setup](#android-setup)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Tracking behavior](#tracking-behavior)
- [Battery](#battery)
- [Geofencing](#geofencing)
- [Records, uploads and the queue](#records-uploads-and-the-queue) (including
  [live location with `http.syncInterval`](#live-location-httpsyncinterval))
- [Events reference](#events-reference)
- [Companion plugins (native API)](#companion-plugins-native-api)
- [Error codes](#error-codes)
- [Web stub](#web-stub)
- [Server side](#server-side)
- [Example app](#example-app)
- [Field-force example](#field-force-example)
- [End-to-end tests](#end-to-end-tests)
- [Publishing to npm](#publishing-to-npm)
- [API](#api)

## Features

- **Motion-aware tracking.** The plugin switches between *moving* (continuous, high-accuracy updates with an elastic
  distance filter) and *stationary* (GPS off; a stationary geofence, passive fixes and activity recognition watch for
  movement). It reports `motionchange` and `activitychange` events. See [Battery](#battery).
- **Three location backends.** It supports GMS fused location, HMS fused location and the Android `LocationManager`.
  You choose which SDKs go into the APK at build time (`locationTracking.providers`) and which one to use at runtime
  (`locationProvider: 'auto' | 'gms' | 'hms' | 'android'`).
- **Heartbeat audit.** If no record has been created for `heartbeat.minInterval` seconds (default 180), the plugin
  creates a `heartbeat` record with the last known location. It should exist before `maxInterval` (default 300), and
  it is uploaded immediately. Its `heartbeat` object tells the server when the next one is due. See
  [docs/heartbeat.md](docs/heartbeat.md).
- **Audit records.** `tracking_start`, `tracking_stop` (with a reason) and `providerchange` records are uploaded the
  same way, so the server can explain gaps.
- **Offline-first HTTP sync.** Every record is stored in SQLite first. Uploads can send one record or a batch per
  request, use JSON templates, merge `params` into the body, and refresh a JWT on `401`. Failed uploads stay queued
  and are retried, and `recorded_at` / `sent_at` expose late delivery. `http.syncInterval` batches location uploads
  while keeping the server's live location at most that many seconds old. See [docs/wire-format.md](docs/wire-format.md).
- **Companion native API.** Another plugin in the same app (Kotlin) receives every record and every event natively,
  also when no WebView runs (after a reboot or an alarm), and can call the plugin. See
  [Companion plugins](#companion-plugins-native-api).
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

You can also pass the property on the command line (`./gradlew assembleRelease -PlocationTracking.providers=hms`).
The property applies to the whole Gradle run, so every product flavor of the app gets the same SDKs. To build a Play
APK and an AppGallery APK from product flavors, use `none` and add the SDKs per flavor, as described in
[Separate Play and AppGallery APKs (product flavors)](#separate-play-and-appgallery-apks-product-flavors).

The SDK versions default to `play-services-location` 21.3.0 and `com.huawei.hms:location` 6.16.0.302. To use other
versions, set `playServicesLocationVersion` or `hmsLocationVersion` in the `ext` block of your app's
`android/variables.gradle`. If you package `hms` for Google Play, keep 6.16.0.302: it is Huawei's first release with
16 KB page-size support, and the latest one in Huawei's version history. Earlier versions bring native libraries with
4 KB alignment, which fail Play's 16 KB page-size requirement; so do the undocumented 6.17–6.19 builds in Huawei's
Maven repository.

At runtime, the `locationProvider` config option picks the backend. GMS or HMS counts as *packaged* when its SDK
classes are in the APK **and** the app's merged manifest declares the plugin's two receivers for it
(`provider.gms.GmsActivityReceiver` and `GmsGeofenceReceiver`, or `provider.hms.HmsActivityReceiver` and
`HmsGeofenceReceiver`). Activity and geofence results arrive through those receivers, so a backend without them cannot
work.

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

#### Separate Play and AppGallery APKs (product flavors)

An app that publishes a Google Play APK with only GMS and an AppGallery APK with only HMS adds the SDKs per product
flavor itself, instead of through `locationTracking.providers`. The plugin chooses the backend by checking at runtime
which SDK classes are in the APK and which of its receivers the manifest declares, so it needs no other setting.

1. In `android/gradle.properties`, stop the plugin from packaging any SDK:

   ```properties
   locationTracking.providers=none
   ```

2. In `android/app/build.gradle`, declare the flavors and add one SDK to each:

   ```groovy
   android {
       flavorDimensions = ['store']
       productFlavors {
           gms { dimension 'store' }
           hms { dimension 'store' }
       }
   }

   dependencies {
       gmsImplementation 'com.google.android.gms:play-services-location:21.3.0'
       hmsImplementation 'com.huawei.hms:location:6.16.0.302'
   }
   ```

   The app now sets the SDK versions; `playServicesLocationVersion` and `hmsLocationVersion` no longer apply.

3. Add the Huawei Maven repository ([step 2](#2-huawei-maven-repository-needed-for-hms-and-harmless-otherwise)).
   For AppGallery Connect ([step 3](#3-appgallery-connect-only-for-hms)), put `agconnect-services.json` in
   `android/app/src/hms/` instead of `android/app/`.

4. Remove the other provider's entries from each flavor's manifest. The plugin's manifest declares the receivers and
   the Android 9 activity-recognition permissions of both SDKs, and Android merges it into every flavor. These
   entries do nothing in an APK without that SDK, but they show in the merged manifest and in the store listing's
   permission list. Two flavor manifests remove them.

   This step also decides which provider each APK can use. The plugin never selects a provider whose receivers were
   removed, also not with `locationProvider: 'gms'` or `'hms'` (it falls back to `android` with a warning). So the
   AppGallery APK uses HMS even when another library brings Google's location classes into it (for example another
   location plugin, or the app's own Google Play services availability check) and the phone has Google Play services
   installed. The app does not need to set `locationProvider`.

   `android/app/src/gms/AndroidManifest.xml` (Google Play APK, removes the HMS entries):

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       xmlns:tools="http://schemas.android.com/tools">
     <uses-permission android:name="com.huawei.hms.permission.ACTIVITY_RECOGNITION" tools:node="remove"/>
     <application>
       <receiver android:name="com.brickssoft.locationtracking.provider.hms.HmsActivityReceiver" tools:node="remove"/>
       <receiver android:name="com.brickssoft.locationtracking.provider.hms.HmsGeofenceReceiver" tools:node="remove"/>
     </application>
   </manifest>
   ```

   `android/app/src/hms/AndroidManifest.xml` (AppGallery APK, removes the GMS entries):

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       xmlns:tools="http://schemas.android.com/tools">
     <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" tools:node="remove"/>
     <application>
       <receiver android:name="com.brickssoft.locationtracking.provider.gms.GmsActivityReceiver" tools:node="remove"/>
       <receiver android:name="com.brickssoft.locationtracking.provider.gms.GmsGeofenceReceiver" tools:node="remove"/>
     </application>
   </manifest>
   ```

   Keep everything else from the plugin's manifest in both flavors. The `<queries>` entries such as
   `com.huawei.systemmanager` are phone makers' battery settings apps, not HMS, and the plugin uses them on every
   phone ([step 6](#6-battery-optimization-and-phone-makers)).

5. Build both APKs: `./gradlew assembleGmsRelease assembleHmsRelease` (or `bundleGmsRelease` for Play).

What each APK then contains:

| | Google Play APK (`gms`) | AppGallery APK (`hms`) |
|---|---|---|
| Location SDK and its dependencies | `play-services-location` only | `com.huawei.hms:location` only |
| Manifest entries that the SDK adds itself | Google's only | Huawei's only |
| Plugin receivers and activity-recognition permission | GMS only (after step 4) | HMS only (after step 4) |
| Plugin classes in `provider.gms` and `provider.hms` | both | both |
| `getDeviceInfo().packagedProviders` | `["gms"]` | `["hms"]` |

The plugin classes of both providers stay in both APKs, because the R8 rules keep every provider bundle for the
runtime check. They are in the `com.brickssoft.locationtracking` package and contain no Google or Huawei code.

Other libraries in the app can still bring an SDK into the wrong flavor; for example, Firebase depends on Google
Play services. Check each flavor before publishing:

- `./gradlew :app:dependencies --configuration hmsReleaseRuntimeClasspath` lists the libraries in the AppGallery APK
  (`gmsReleaseRuntimeClasspath` for the Play APK).
- `android/app/build/intermediates/merged_manifests/hmsRelease/` (or Android Studio's **Merged Manifest** tab) shows
  the final manifest.
- On a phone, `getDeviceInfo().packagedProviders` shows which providers the installed APK can use.

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

An app that calls `getCurrentPosition()` (or `watchPosition()`) for users who never configure tracking calls
`ready({ reset: false })` without a config first; that is the intended pattern. It loads the persisted config (the
defaults on the first run) and changes nothing, and calling `ready()` again later in the process is fine. Two side
effects:
- If tracking is enabled but not running in this process, this `ready()` resumes it (reason `restore`), with the
  persisted config.
- It counts as the first `ready()` after install, so a later `ready({ config, reset: false })` keeps the persisted
  config and ignores the one passed. Use `reset: true` or `setConfig()` to apply a config after that.

**`ready({ reset })`.** With `reset: true` (the default), the config is the defaults plus the config you pass, on every
launch. With `reset: false`, the config you pass is applied only on the very first `ready()` after install, and
afterwards the persisted config wins (change it with `setConfig()`). Note that with `reset: true`, JWT tokens the
plugin refreshed are replaced by the ones in the config you pass (see
[JWT refresh](docs/wire-format.md#jwt-refresh)). If tracking was on but is not running in this process (for example
after the app was force-stopped or its process was killed), `ready()` resumes it and records a `tracking_start` with
reason `restore`.

**Force stop.** After the user force-stops the app (Settings → Force stop), Android cancels its alarms and does not
restart its service, so tracking stays off until the app is opened again; `ready()` then resumes it. On Android 11
and newer the plugin also ignores a background event that reaches the stopped app anyway (an activity update or a
stationary-region exit that was already on its way): it does not restart tracking from it, and it releases the leftover
activity, location and geofence registrations so they stop waking the app. The server sees a gap without a
`tracking_stop` record, followed by `tracking_start` with reason `restore` when the app is opened.

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
| `desiredAccuracy` | `'high' \| 'balanced' \| 'low' \| 'passive'` | `'high'` | Accuracy of the location request while moving, and of the first fix after `start()`. While stationary the plugin requests only passive fixes (or, when the stationary geofence cannot be registered, one low-power fix at most every 3 minutes), whatever this value is (see [Battery](#battery)). |
| `distanceFilter` | number (m, ≥ 0) | `10` | Minimum distance between recorded locations while moving. The plugin applies it itself (the OS request has no distance filter). |
| `locationUpdateInterval` | number (ms, ≥ 0) | `1000` | Desired update interval while moving. |
| `fastestLocationUpdateInterval` | number (ms, ≥ 0) | `500` | Fastest accepted update interval. |
| `disableElasticity` | boolean | `false` | Turns off the speed-based scaling of `distanceFilter`. |
| `elasticityMultiplier` | number (≥ 0) | `1` | Elastic filter: `distanceFilter × max(1, round(speed / 5) × elasticityMultiplier)`. |
| `stationaryRadius` | number (m, ≥ 1) | `25` | While stationary: the stationary geofence has a radius of `max(stationaryRadius, 150)` m, and a fix whose distance from the stop point minus its accuracy is more than `stationaryRadius` (with an accuracy no worse than `filter.trackingAccuracyThreshold`) switches to moving. While moving, a displacement beyond it counts as evidence of motion for stop detection. |
| `stopTimeout` | number (min, ≥ 0) | `5` | Minutes without evidence of motion (a displacement beyond `stationaryRadius`, or a confident moving activity) before switching to stationary. Values below 1 act as 1. |
| `stopAfterElapsedMinutes` | number (min, ≥ 0) | `0` (off) | Stops tracking automatically this many minutes after it started (`tracking_stop`, reason `stop_after_elapsed`). While the phone sleeps, the stop can come later: see [Field-force example](#field-force-example). If the deadline passes while the phone is off or the app is not running, the stop is recorded when the plugin next runs, and tracking does not resume. |
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
| `autoSyncThreshold` | number (≥ 0) | `0` | Uploads normal records when the queue holds at least this many records. With `syncInterval` `0`, the value `0` uploads every record. With `syncInterval` above `0`, it is a size limit: the queue is uploaded early when it reaches this many records, and `0` means no size limit. |
| `syncInterval` | number (s, ≥ 0) | `0` | Uploads normal records once the oldest queued one is this old, so the server's live location is at most about this stale (the field-force setup uses `300`). Needs `autoSync: true`. `0` turns it off; priority records are not affected. See [Live location](#live-location-httpsyncinterval). |
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
| `startOnBoot` | boolean | `false` | Resumes tracking after a reboot or an app update, if it was on (`tracking_start`, reason `boot` or `package_replaced`). Needs "Allow all the time" location on Android 14+. With `false`, tracking is off after a reboot or an update, and a `tracking_stop` with reason `reboot` or `package_replaced` is recorded. On Android 14+ with only "while in use" location, Android refuses it (`tracking_stop` reason `service_start_failed`); [`notification.resume`](#confignotificationresume) lets the user resume with a tap. |

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
| `resume` | object | see below | The notification that resumes tracking after Android refused to restore it. |

### `config.notification.resume`

When tracking was on and Android refuses to restore it from the background, the plugin records a `tracking_stop` with
reason `service_start_failed`, and tracking stays off until the app starts it again. This is the normal case after a
reboot, an app update or a killed process on Android 14+ when the app has only "while in use" location (no "Allow all
the time"). With `resume.enabled`, the plugin then posts a notification. Tapping it starts the tracking service
directly: Android lets a location foreground service started from a notification use "while in use" location. The
session resumes with the persisted config and its original `stopAfterElapsedMinutes` deadline, and records a
`tracking_start` with reason `resume_notification`.

- The notification has its own channel (`location_tracking_resume`, default importance), so it shows even when the
  tracking notification's `priority` is `min`. It uses the tracking notification's small icon and color.
- It is not posted when the session's `stopAfterElapsedMinutes` deadline has passed, and it disappears at that
  deadline.
- It is removed when tracking starts by any path, when the app calls `stop()`, and when `resume.enabled` is turned off.
- Without notification permission (`POST_NOTIFICATIONS`, Android 13+) it is not posted; the log says so.

| Option | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Post the resume notification when Android refuses to restore tracking. |
| `title` | string | app label | Notification title. |
| `text` | string | `'Location tracking is paused. Tap to resume.'` | Notification text. The default comes from the string resource `lt_resume_notification_text`, so an app can translate it. |
| `channelName` | string | `'Paused location tracking'` | Channel name shown in the system settings (string resource `lt_resume_channel_name`). |

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
  **asynchronously**, within `locationTimeout` + 5 s.
- If Android refuses to start the foreground service right away, `start()` records a `tracking_stop` with reason
  `service_start_failed` and rejects with `PERMISSION_DENIED`. This happens in two cases:
  - Android 12+ throws for a foreground-service start from the background (the app is not visible and has no
    exemption);
  - on Android 14+, the plugin's own check predicts that Android would refuse the `location` service type, and the
    plugin does not try. The check refuses only when all of these are true: the tracking service is not in the
    foreground yet; "Allow all the time" location is not granted; Android rates the app's process below "visible" (a
    process that runs another foreground service counts as visible); the app's current activity is not started; and
    no activity of the app was started, stopped or destroyed in the last 15 s.

  If the service fails a moment later (after `start()` resolved), tracking stops with reason `service_start_failed`;
  listen for `enabledchange`.
- `startGeofences()` works the same way in geofences-only mode (reason `start_geofences`). Calling `start()` while
  `startGeofences()` runs (or the reverse) switches the mode and records another `tracking_start`. Calling the same
  one twice does nothing.
- `stop()` removes the location and activity updates, records a `tracking_stop` (reason `stop`) while the foreground
  service is still running, then stops the service, the heartbeat and the OS geofences, and emits `enabledchange`.
- Tracking also stops by itself: `stopOnStationary`, `stopAfterElapsedMinutes`, `stopOnTerminate` (app swiped away),
  a revoked location permission (`permission_denied`), or Android refusing the foreground service
  (`service_start_failed`). Each case records a `tracking_stop` with that reason and emits `enabledchange`. When
  Android restarts the killed service by itself and the restart fails, the reason is `permission_denied` if location
  permission is no longer granted, and `service_start_failed` otherwise.
- Tracking resumes by itself after the process was killed (reason `restore`), after a reboot (`boot`) and after an
  app update (`package_replaced`, both only with `app.startOnBoot`), and when `ready()` finds it enabled but not
  running. See [docs/heartbeat.md](docs/heartbeat.md#android-reliability) for what Android allows. When Android
  refuses such a restore, [`notification.resume`](#confignotificationresume) can offer a tap that resumes it
  (`resume_notification`).
- A boot broadcast restores tracking once per boot. The plugin reads the phone's boot counter
  (`Settings.Global.BOOT_COUNT`) and ignores a boot broadcast when the counter is the same as for the last boot
  broadcast it handled, or the same as when it last started the tracking service. So a repeated or fake
  `QUICKBOOT_POWERON` broadcast without a real reboot creates no second `tracking_start`. On a phone that does not
  report the counter, this check is skipped; as before, only the first boot broadcast in a process is handled.

**Motion states** (`start()` mode only).

- **Moving:** continuous updates with the configured `desiredAccuracy` and intervals. A `location` record is created
  when a fix is at least the (elastic) `distanceFilter` away from the last recorded one.
- **Stationary:** GPS off. The plugin removes its location request and asks only for **passive** fixes (fixes that
  other apps requested), and registers one OS geofence, the *stationary region*, around the stop point (the
  *anchor* fix). The foreground service keeps running and heartbeats continue; they carry the anchor fix. Fixes that
  still arrive only feed motion detection and polygon geofences; **no `location` records are created and the
  odometer doesn't grow**, so position jitter while parked is neither uploaded nor counted. Details in
  [Battery](#battery).
- Stationary → moving, whichever comes first: the OS reports that the device left the stationary region; a fix is
  certainly outside (its distance from the anchor minus its accuracy is more than `stationaryRadius`, and its accuracy
  is no worse than `filter.trackingAccuracyThreshold`); a moving activity (walking, running, on foot, on bicycle, in
  vehicle) at or above `minimumActivityRecognitionConfidence` lasts for `motionTriggerDelay`; or
  `changePace({ isMoving: true })`. Then the configured request (GPS for `'high'`) starts again and a `motionchange`
  (`is_moving: true`) is recorded with the fix that showed the movement (the region's exit fix, or the outside fix),
  or with the best known fix when the trigger was an activity or `changePace`.
- Moving → stationary (stop detection, unless `disableStopDetection`): `stopTimeout` minutes (at least 1) after the
  last evidence of motion, even if no more fixes arrive.
- Each transition records a `motionchange` with the new `is_moving`. `changePace({ isMoving })` forces a transition;
  it is ignored while tracking is off or in geofences-only mode.
- Without activity recognition (the `android` backend, `disableMotionActivityUpdates`, or no activity permission),
  motion is detected from the stationary region and from distance only.

**Positions.** `getCurrentPosition()` works whether or not tracking is on. It needs location permission
(`PERMISSION_DENIED`) and enabled location services (`LOCATION_DISABLED`). It takes up to `samples` fixes, stops
early on a fix of 10 m or better, and resolves with the most accurate one; at the timeout it resolves with the best
fix so far, or rejects with `TIMEOUT` if there is none. With `maximumAge`, a recent enough known fix is returned
right away. With `persist: true` (default) the fix is stored as a `current_position` record, which is uploaded,
emitted as a `location` event and restarts the heartbeat window. `watchPosition()` reports each fix (or an error,
such as `PERMISSION_DENIED`) to its callback until `clearWatch()`; it doesn't report disabled location services as
an error (fixes start once they are switched on). With `persist: true`, each fix is also stored as a
`watch_position` record (uploaded, and emitted as a `location` event).

## Battery

Tracking for 12 hours or more a day must cost little battery without losing the audit trail. The plugin follows the
same idea as Transistor Software's
["philosophy of operation"](https://docs.transistorsoft.com/help/philosophy/): **GPS runs only while the device
moves.** Heartbeats are a separate, fixed rule: they are always created and uploaded while tracking is on, and they
never turn GPS on.

**What runs in each state** (`start()` mode):

| State | Runs | Does not run |
|---|---|---|
| Moving | The location request with `desiredAccuracy` (`'high'` = GPS) every `locationUpdateInterval` (1 s by default); activity recognition; uploads; the heartbeat schedule (heartbeats are rarely due, because records keep coming). | – |
| Stationary | The foreground service and its notification; a **passive** location request (it receives only fixes that other apps requested, and costs nothing by itself); the **stationary region**: one OS geofence of `max(stationaryRadius, 150)` m around the anchor fix (the stop point), with an exit trigger only; activity recognition; a heartbeat every `heartbeat.minInterval` seconds. | GPS, and any network-location request of the plugin (except the low-power fallback below). |
| Tracking off | Nothing. | Everything. |

**How the plugin notices that the device moves again** (whichever comes first):

1. the OS reports that the device left the stationary region (typically after 150–250 m). An exit report whose own
   fix is certainly inside the region (distance from the centre plus accuracy ≤ radius) is ignored: it is a late
   report about an earlier region;
2. a fix that arrives while stationary (a passive fix, or a fix of the low-power fallback) is certainly outside: its
   distance from the anchor minus its accuracy is more than `stationaryRadius`, and its accuracy is no worse than
   `filter.trackingAccuracyThreshold` (a coarse fix alone never wakes GPS);
3. activity recognition reports walking, running, on foot, on bicycle or in vehicle, with at least
   `minimumActivityRecognitionConfidence`, for `motionTriggerDelay`;
4. the app calls `changePace({ isMoving: true })`.

Then GPS starts again, and a `motionchange` (`is_moving: true`) is recorded: with the fix of the region's exit report
(case 1, when that fix passes the location filters) or the outside fix (case 2), otherwise with the best known fix.

**The anchor.** The anchor is the fix where the device became stationary: the fix of the `motionchange` with
`is_moving: false` (after `start()`, a fresh fix). While stationary, the anchor changes only in these cases:

- no fix was known when the device became stationary: the first accepted fix becomes the anchor;
- the anchor was more than 10 minutes old when it became the anchor, and a fix arrives that is at most 10 minutes
  old and has an accuracy no worse than `filter.trackingAccuracyThreshold`: that fix replaces the anchor;
- a fix that is not certainly outside has a better (smaller) accuracy than the anchor: that fix replaces the anchor.

The stationary region is registered only around an anchor that was at most 10 minutes old when it became the anchor
(the OS never reports an exit for a device that is already outside a new region). The region is moved only when the
anchor is more than 50 m from the region's centre.

**Low-power fallback.** The plugin uses a low-power request (Wi-Fi and cell towers, no GPS; at most one fix per
3 minutes) instead of the passive one when:

- the region cannot be registered: no "Allow all the time" location, a backend error, or no answer from the OS within
  10 s;
- no anchor is known, or the anchor was more than 10 minutes old when it became the anchor (see above);
- the app has 99 or 100 geofences of its own. GMS accepts at most 100 geofences per app, and the plugin gives the last
  slot to the app's geofences: when a geofence change brings the app to 99 geofences, the plugin removes the
  stationary region (see [Geofencing](#geofencing)).

The plugin logs each case. With `desiredAccuracy: 'passive'` the fallback request stays passive. After a failed
registration the plugin tries again when the device leaves the stationary state, when the location backend changes,
and on every location provider change while stationary (for example after "Allow all the time" is granted); it does
not try again on every fix. A polygon geofence still forces the moving request while the device is inside the
polygon's enclosing circle (see [Geofencing](#geofencing)).

**Heartbeats while stationary.** A heartbeat carries the anchor fix. Its `recorded_at` is the time the heartbeat was
created (now); `timestamp` (`location.timestamp` in JavaScript) is the time the anchor fix was **acquired**, which can
be hours earlier. A passive or low-power fix that arrives while stationary changes what the heartbeat carries only
when it becomes the anchor. A record with its own fix does change it: a transition of one of the app's geofences, or
`getCurrentPosition()` / `watchPosition()` with `persist: true`; later heartbeats carry that record's fix until the
anchor changes again. So the server can show "the device is on (last heartbeat 11:59), last position from 10:26".
Details in [docs/heartbeat.md](docs/heartbeat.md#stationary-gps-off-heartbeats-continue).

**What costs battery, and the keys that change it** (largest cost first):

| Cost | When | Config keys |
|---|---|---|
| GPS | While moving, and for `stopTimeout` minutes after the device stops (stop detection waits that long). | `geolocation.desiredAccuracy` (`'balanced'` uses Wi-Fi and cell towers, no GPS); `locationUpdateInterval` and `fastestLocationUpdateInterval`; `stopTimeout` (5 min by default); `disableStopDetection: true` keeps GPS on all the time: avoid it. |
| Mobile radio | Every upload request wakes the radio for several seconds. | `http.syncInterval` (the field-force setup uses 300 s: one upload about every 5 minutes while moving instead of one per record), `batchSync`, `maxBatchSize`, `autoSyncThreshold`, `disableAutoSyncOnCellular`. Heartbeats and audit records are always uploaded at once. |
| CPU wake-ups | Every heartbeat alarm; every fix while moving. | `heartbeat.minInterval` / `maxInterval` (180/300 s). With the battery-optimization exemption, heartbeats stay exact in Doze (about 20 wake-ups per hour at 180 s); without it, Android spaces them about 9 minutes apart. |
| Polygon geofences | Continuous location while the device is inside a polygon's enclosing circle. | Prefer circles. |
| Activity recognition | While tracking in `start()` mode. It uses the motion sensors and costs little. | `activity.activityRecognitionInterval`, `activity.disableMotionActivityUpdates` (then only the stationary region and passive fixes detect movement). |
| Logging | Every log line is written to a file. | `logger.logLevel` (`'info'` by default; `'debug'` writes much more). |

How to measure the cost on a real phone: procedure M-04 in the
[e2e runbook](docs/e2e-runbook.md#m-04-12-hour-battery-measurement).

## Geofencing

- Up to **100** geofences (`TOO_MANY_GEOFENCES` beyond that). An identifier is 1–100 characters; adding a geofence
  with an existing identifier replaces it. `loiteringDelay` must be ≥ 0. The identifier `__lt_stationary__` is
  reserved for the plugin's stationary region: `addGeofence` rejects it with `INVALID_ARGUMENT`.
- The stationary region (see [Battery](#battery)) does not count against the 100. GMS accepts at most 100 geofences
  per app, stationary region included. While the app has 99 or 100 geofences, the plugin does not register the
  stationary region (stationary tracking then uses the low-power fallback). When an add brings the app to 99
  geofences, the plugin removes a registered region after that add has finished; the removal waits for the tracking
  engine and for the OS. So an `addGeofence` of the 100th geofence right after the 99th, or one `addGeofences` call
  that goes from 98 to 100 geofences, can still fail with `TOO_MANY_GEOFENCES` from the OS while the region is
  registered (an open item in [DECISIONS.md](docs/DECISIONS.md#r25-open-requests-and-known-limitations)).
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
  level changes. If the backend answers that geofencing is not available yet (GMS `GEOFENCE_NOT_AVAILABLE`), the
  plugin tries again after 10, 30, 60, 120 and 300 s.
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
- A request answered `5xx` or `429`, or without an answer (timeout, network error), is **tried again in the same
  upload** after 2, 4 and 8 seconds (at most 4 tries). Other answers are not tried again; a `401` refreshes the JWT
  and retries once ([details](docs/wire-format.md#response-handling-and-retries)).
- With `syncInterval: 0` (the default) there is **no retry timer** for failed uploads: queued records are retried when
  a record is inserted (at least every heartbeat while tracking), when the network comes back, when tracking starts,
  and on `sync()`. With `syncInterval` above 0, a timer uploads normal records that have become due, and retries them
  once per `syncInterval` after a failed upload (see below).
- `getLocations({ limit })` returns queued records, oldest first; `getCount()` counts them; `destroyLocations()` and
  `destroyLocation({ uuid })` delete them without uploading.
- `sync()` uploads the whole queue now, ignoring `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`, and
  resolves with the uploaded records. It rejects with `NO_URL` (no valid `http.url`), or with `HTTP_ERROR` /
  `NETWORK_ERROR` at the first failed upload, after its tries (records uploaded before the failure are already
  deleted).
- `insertLocation({ location })` stores a record of your own (default `event: 'location'`) and uploads it like the
  others. It emits **no** event and does **not** restart the heartbeat window, because it is not evidence that
  tracking runs.
- `persistence.maxDaysToPersist` and `maxRecordsToPersist` prune the queue, including heartbeats and audit records.

### Live location: `http.syncInterval`

A back office that shows where a worker is now needs a recent position, but one upload per location record keeps the
mobile radio busy. `http.syncInterval` sets the maximum age of the newest position on the server:

```ts
http: {
  url: 'https://api.example.com/locations',
  autoSync: true,        // required: syncInterval works only with autoSync
  syncInterval: 300,     // seconds: live location at most about 5 minutes old
  batchSync: true,       // one request carries many records
  maxBatchSize: 100,
}
```

- **Normal records** (`location`, `motionchange`, `current_position`, `watch_position`, `geofence`) wait in the
  queue until the **oldest** of them is `syncInterval` seconds old. "Oldest" is the queued normal record with the
  smallest `recorded_at`; its age is now − `recorded_at`, and a negative age (the wall clock was set back) counts as
  due. Then the whole queue is uploaded, in batches of `maxBatchSize` when `batchSync` is on. While the device moves,
  that is one upload about every `syncInterval` seconds.
- **Audit records** (`heartbeat`, `tracking_start`, `tracking_stop`, `providerchange`) are still uploaded at once, and
  they take the queued normal records with them.
- While stationary, no location records are created; the heartbeat (every `minInterval`) is uploaded at once and
  carries the last position with its acquisition time.
- `autoSyncThreshold` above 0 is a size limit: the queue is uploaded earlier when it reaches that many records.
- The check runs on every insert, when the network comes back, when the uploader starts, and on a timer in the
  tracking process (at `oldest.recorded_at + syncInterval`). `disableAutoSyncOnCellular` still holds normal records
  back on cellular, and `sync()` still uploads everything at once.
- **The timer holds no wake lock.** It counts only the time the CPU is awake, so in deep sleep or Doze it fires late.
  Two other triggers limit the delay: while the device moves, every new location record runs the check against the
  wall clock; while it is stationary, each heartbeat is uploaded at once and takes the queue with it. With the
  heartbeat disabled and no new record, held records wait until the CPU has been awake long enough.
- **While tracking is off**, normal records are uploaded as with `syncInterval: 0` (by `autoSyncThreshold`), because
  there is no timer then. For example, with the default `autoSyncThreshold: 0`, a `getCurrentPosition()` record after
  the 02:00 stop is uploaded at once. When tracking stops, records that were held are handled by this rule at once.
- **After a failed automatic upload** (its last try failed), normal records are retried once per `syncInterval`,
  counted from that last try (measured on the elapsed-time clock), by the timer, not on every insert. These still upload at once: the network coming back, a
  queued audit record, and `sync()`.
- [Native listeners](#companion-plugins-native-api) receive every record when it is queued, not when it is uploaded,
  so `syncInterval` does not delay them.
- `syncInterval: 0` (the default) keeps the behavior without it: normal records follow `autoSync` and
  `autoSyncThreshold`.
- Held records count against `persistence.maxRecordsToPersist` (unlimited by default). A limit smaller than the number
  of records created in `syncInterval` lets pruning delete held records before they are uploaded.

Rules and a timeline example: [docs/wire-format.md](docs/wire-format.md#live-location-with-syncinterval).

## Events reference

Subscribe with `LocationTracking.addListener(name, callback)` or the typed helpers. Events reach JavaScript only
while the app's WebView is alive; [native listeners](#companion-plugins-native-api) receive the same events in every
process, also without a WebView. Records are persisted and uploaded whether or not anyone is listening.
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
| `http` | `{ success, status, responseText, uuids }` | An upload request finished. There is one event per HTTP request: a `401` followed by a token refresh and a retry gives two, and every try of an upload that is tried again gives one. `status` is `0` for a network error. |
| `connectivitychange` | `{ connected, type }` | The network connection changed. A network blocked for the app by Doze or Data Saver counts as disconnected. |
| `powersavechange` | `{ isPowerSaveMode }` | Battery saver was turned on or off. |
| `enabledchange` | `{ enabled }` | Tracking was started or stopped, including automatic stops (`stopOnStationary`, `stopAfterElapsedMinutes`, `terminate`, `permission_denied`, `service_start_failed`, `reboot`, `package_replaced`). Not emitted when tracking resumes in a new process. |
| `notificationaction` | `{ id }` | A notification action button was tapped. |
| `authorization` | `{ success, status, error?, response? }` | A JWT refresh was attempted, successfully or not. `response` is the parsed JSON body of the refresh response. |

## Companion plugins (native API)

Another Capacitor plugin in the same app, or the app's own Android code, can work with this plugin directly in Kotlin,
without JavaScript. This is for a companion that must see **every** record and event, including those created while
no WebView exists: after a reboot, when a heartbeat alarm wakes the app at night, or after Android restarted a killed
process. The example is the fake PremiseMonitor plugin of the [field-force example](#field-force-example), which
audits a premise with its own foreground service.

Package `com.brickssoft.locationtracking.api`:

- `LocationTrackingListener` with two methods, both optional:
  - `onRecord(context, record: JSONObject)`: every record that is queued for upload (all `event` types, including
    `heartbeat`, `tracking_start`, `tracking_stop`, `providerchange`, `geofence` and records from `insertLocation()`),
    in the [wire format](docs/wire-format.md) without `sent_at`. It is called when the record is queued, before any
    upload, so `syncInterval` and network problems do not delay it.
  - `onEvent(context, name: String, payload: JSONObject)`: every event, with the same name and payload as the
    JavaScript `addListener` API.
- `LocationTrackingNative`: `addListener`, and calls that do what the JavaScript methods do (`ready`, `setConfig`,
  `start`, `startGeofences`, `stop`, `changePace`, `getState`, `getHeartbeatStatus`, `sync`, `insertLocation`,
  `addGeofence`, `removeGeofence`, `getGeofences`). Results arrive in a `NativeCallback` as a Kotlin `Result`; a
  failure carries the same error `code` as in JavaScript.

**Register a listener in the manifest** (recommended: it never misses a record). The plugin creates the class once per
process, before it can emit anything, in every process that runs the plugin (app launch, the tracking service, boot,
alarms):

```xml
<application>
  <meta-data android:name="com.brickssoft.locationtracking.LISTENER"
             android:value="com.example.audit.AuditListener"/>
</application>
```

```kotlin
package com.example.audit

import android.content.Context
import com.brickssoft.locationtracking.api.LocationTrackingListener
import org.json.JSONObject

class AuditListener : LocationTrackingListener {        // public no-argument constructor
    override fun onRecord(context: Context, record: JSONObject) {
        // e.g. record.getString("event") == "heartbeat"; hand real work (I/O, uploads) to your own executor
    }

    override fun onEvent(context: Context, name: String, payload: JSONObject) {
        // e.g. name == "providerchange"
    }
}
```

A second listener in the same app needs its own meta-data name with a suffix, for example
`com.brickssoft.locationtracking.LISTENER.analytics`: Android's manifest merger rejects two libraries that declare the
same meta-data name with different values.

**Or subscribe from code.** Such a listener receives only what is emitted after the call:

```kotlin
val subscription = LocationTrackingNative.addListener(context, AuditListener())
LocationTrackingNative.getState(context) { result ->
    result.onSuccess { state -> Log.i("Audit", "tracking enabled: ${state.getBoolean("enabled")}") }
        .onFailure { error -> Log.w("Audit", "getState failed", error) }
}
subscription.remove()                                    // idempotent
```

Listener methods and callbacks run on one background thread named `LT-native`. Listener methods run in the order the
records and events were created (a record's `onRecord` comes before the events that carry it). A callback runs when
its call completes, so callbacks come in completion order, not in call order. Exceptions thrown by a listener or a
callback are caught and logged. Keep the methods short; the listener's constructor must not block. Native calls do not
need the JavaScript `ready()`, and are not subject to the `NOT_READY` rule; call `LocationTrackingNative.ready` first
when you need a config. The consumer R8 rules keep the API and the listeners' constructors in minified builds.

The full guide for companion-plugin authors: [docs/native-api.md](docs/native-api.md).

## Error codes

Every rejected promise has a `code` (see the `ErrorCode` type) and a `message`.

| Code | Meaning |
|---|---|
| `NOT_READY` | The method was called before `ready()` resolved in this process (see the [NOT_READY rule](#quick-start)). |
| `PERMISSION_DENIED` | A required permission is missing, for example `start()` or `getCurrentPosition()` without location permission. Also `start()` / `startGeofences()` when Android refused to start the foreground service. |
| `LOCATION_DISABLED` | Location services are turned off on the device (`getCurrentPosition()`; some HMS geofence errors). |
| `TIMEOUT` | The operation timed out, for example `getCurrentPosition()` got no fix in time. |
| `UNAVAILABLE` | A required system service, backend or feature is not available right now, for example no app can send the log email. |
| `INVALID_ARGUMENT` | Invalid options or config, for example a wrong config type, a geofence without a radius, a polygon with fewer than 3 vertices, or the reserved geofence identifier `__lt_stationary__`. |
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
- [docs/migrating-from-transistor-v8.md](docs/migrating-from-transistor-v8.md) lists what a server built for
  Transistor v8 uploads has to change, together with the app-side migration.
- [docs/heartbeat.md](docs/heartbeat.md) covers heartbeat semantics, what reliability to expect on Android, and how
  to audit tracking gaps on the server.
- [docs/device-test-checklist.md](docs/device-test-checklist.md) is a manual test plan for real GMS, HMS and
  non-GMS phones; [docs/e2e-runbook.md](docs/e2e-runbook.md) covers the emulator tests and the real-phone procedures
  M-01 … M-08.

In short, store records idempotently by `uuid` and answer `2xx` quickly (also for records you will never accept, or
they are retried until pruned). While tracking is on, expect some record at least every `maxInterval` seconds (about
every 9 minutes in deep idle when the app is not exempt from battery optimization), and flag longer gaps. Each
heartbeat's `heartbeat` object (`strategy`, `next_at`, `battery_exempt`, `device_idle`) tells you which gap to expect
next, so a normal 9-minute gap in Doze is not flagged as a failure (see
[docs/heartbeat.md](docs/heartbeat.md#heartbeat-metadata)). A back office can show tracking as **online** while the
latest record is not older than that expected gap. A `tracking_stop` with reason `service_start_failed` means Android
refused the tracking foreground service (a `start()` from the background, or a restart in the background); the app
has to call `start()` again while it is visible.

## Example app

[`example/`](example/) is a plain HTML/JS Capacitor app, with a button for every method and a live log of all 13
events:

```bash
npm ci && npm run build            # in the repository root
cd example && npm ci && npm run sync
cd android && ./gradlew assembleDebug -PlocationTracking.providers=gms,hms
```

Its **debug** build also contains the test hooks of the [end-to-end tests](#end-to-end-tests): a broadcast receiver
that runs plugin calls without the web page, and an "E2E mode" in which the page never changes the plugin's state by
itself. The receiver accepts only senders that hold `android.permission.DUMP` (the adb shell and root hold it; other
apps on the phone do not). Release builds contain neither.

## Field-force example

[`examples/field-force/`](examples/field-force/) is the setup this plugin was built for: a field-force app whose back
office audits a moving worker. It is plain HTML/JS (no bundler), Android only, and packages GMS only (like a Google
Play build).

- **Auto start.** Every time the app's page loads, it calls `ready()` with the preset below and then `start()` if
  tracking is not on yet. It runs the same startup again when the app comes back to the foreground while tracking is
  off, for example the morning after the 02:00 stop when the app was never closed. It detects the foreground with the
  document event `resume` (Capacitor 8 fires it on every activity resume after the first pause) and, as a fallback,
  `visibilitychange` to visible; both lead to one check. The check does nothing while a startup runs, while tracking
  is on, or when the test overrides set `autoStart: false`.
- **Stop at 02:00.** At start, the app computes the minutes until the next 02:00 local time and passes them as
  `geolocation.stopAfterElapsedMinutes`. The plugin then records `tracking_stop` with reason `stop_after_elapsed` at
  about 02:00. This is app code, not a plugin schedule feature: any app can compute its own stop time the same way.
  When tracking is already on at launch, the app keeps the running session's value, because the plugin measures it
  from the session start. When tracking is off, the app computes the minutes again right before `start()` (permission
  dialogs can take minutes) and applies them with `setConfig` if they changed. The daily stop ends each session at
  night, so the next morning starts a fresh session. (The owner's reason: an app that is never closed must not hit a
  `ForegroundServiceDidNotStartInTimeException` on a cold start the next morning.)
- **When the 02:00 stop happens.** The plugin's stop timer counts only the time the CPU is awake. The plugin therefore
  also checks the stop time whenever a fix, an activity update, a stationary-region exit or a heartbeat arrives.
  While the phone moves, fixes arrive every second, so the stop is on time. While it is stationary (GPS off) and
  asleep, the stop happens at the first heartbeat after the stop time: at most `maxInterval` (300 s) late when the app
  is exempt from battery optimization (heartbeats about every 180 s), and about 9–11 minutes late in deep Doze without
  the exemption. The check runs in a separate step right after the heartbeat is queued, and the heartbeat's wake lock
  may already be released then; if the phone falls asleep in that short moment, the stop comes with a later
  heartbeat.
- **Live location at most about 5 minutes old:** `http.syncInterval: 300` with batches.
- **Device details and battery.** `http.params.device` carries the manufacturer, model, brand, OS version, SDK level,
  plugin version, backend and GMS/HMS availability in every request; every record carries `battery`.
- **Online status, route, travel time and distance** come from the records: heartbeats while stationary, `location`
  records while moving, `motionchange` for the moving periods, `odometer` for the distance.
- **Companion plugin.** A fake PremiseMonitor plugin (`examples/field-force/plugins/premise-monitor/`) listens to all
  records and events through the [native API](#companion-plugins-native-api), monitors one circular premise with a
  geofence, runs its own foreground location service while the worker is inside, and uploads its own audit entries.

The preset (production values):

| Group | Values |
|---|---|
| `geolocation` | `desiredAccuracy: 'high'`, `distanceFilter: 20`, `stationaryRadius: 50`, `stopTimeout: 5`, `stopAfterElapsedMinutes`: minutes to the next 02:00, `filter.trackingAccuracyThreshold: 50` |
| `heartbeat` | `enabled: true`, `minInterval: 180`, `maxInterval: 300` |
| `http` | `url: <backend>/locations`, `autoSync: true`, `syncInterval: 300`, `batchSync: true`, `maxBatchSize: 100`, `params: { worker_id, device: {…} }`, JWT `authorization` with `refreshUrl: <backend>/auth/refresh` |
| `app` | `stopOnTerminate: false`, `startOnBoot: true` |
| other | `notification: { title: 'Field Force', text: 'Shift tracking is on' }`, `logger.logLevel: 'debug'`, `locationProvider: 'auto'` |

The startup logic is in `examples/field-force/www/ff-core.js`. The page exposes its progress as `window.FF_APP`:

- `status`: `'running'`, `'done'` or `'failed'` (the latest startup run); `step`: the step that runs now;
- `result`: the state, the computed `stopAfterElapsedMinutes`, the config, the device info and `warnings` about
  ignored test overrides; `error`: `{code, message, step}` of a failed run;
- `startup`: the promise of the latest startup run (page load or resume);
- `startupCount`: the number of startup runs in this page; `lastStartupReason`: `'load'` or `'resume'`;
- `lastResume`: the latest foreground check, `{at, trigger, outcome}`, where `trigger` is `'resume'`,
  `'visibilitychange'` or `'manual'` and `outcome` is `'started'`, `'enabled'`, `'busy'`, `'autostart_off'` or
  `'error'`;
- `checkResume()`: runs the foreground check by hand and resolves with its outcome.

The startup asks for every permission that is not granted and waits until the permission dialog is answered.

Build it (after `npm ci && npm run build` in the repository root), and run the Node tests of its startup logic:

```bash
cd examples/field-force && npm ci && npm test   # Node tests of www/ff-core.js and www/app.js
npm run sync                                    # FF_BACKEND_URL=<url> sets the back office (default http://10.0.2.2:8787)
cd android && ./gradlew assembleDebug
```

## End-to-end tests

The plugin is tested end to end on an Android emulator (AVD), in GitHub Actions and on a developer's machine:

| Part | Where | What |
|---|---|---|
| Plugin suite | [`e2e/plugin/`](e2e/plugin/) | 34 scenarios against the plugin's [example app](#example-app): lifecycle (foreground-service start crashes, kills, reboots, updates), heartbeat and power (stationary GPS off, Doze, clock changes, offline, server errors, `syncInterval`), permissions, providers and geofences. |
| Field-force suite | [`examples/field-force/e2e/`](examples/field-force/e2e/) | 12 scenarios against the [field-force example](#field-force-example): auto start, 02:00 stop, live location, route and odometer, online/offline audit, and PremiseMonitor receiving every record natively (also after a kill and a reboot). |
| Test kit | [`testing/e2e-kit/`](testing/e2e-kit/) | adb, WebView and debug-command helpers, a mock back office, fixtures and assertions, on Node 22 without runtime dependencies. |
| Manual procedures | [docs/e2e-runbook.md](docs/e2e-runbook.md) | M-01 … M-08: HMS on a Huawei phone, phone makers' task killers, a real drive, a 12-hour battery measurement, a real 02:00 stop, a real Android 14 boot, 16 KB alignment, real overnight Doze. |

Run them on a machine with KVM and a booted emulator (details, triage and the manual procedures are in the
[runbook](docs/e2e-runbook.md)):

```bash
cd e2e/plugin && npm ci
export E2E_APK=../../example/android/app/build/outputs/apk/debug/app-debug.apk
npm run test:e2e                                                  # the whole plugin suite
NODE_OPTIONS='--test-name-pattern=^P-L01' npm run test:e2e        # one scenario
npm run dry-run                                                   # list the scenarios, no device
```

(`npm run test:e2e -- --test-name-pattern=...` does not filter: npm puts the option after the file pattern, where
Node ignores it.)

CI runs the emulator suites through `.github/scripts/run-e2e.sh` in `.github/workflows/e2e-android.yml`, only when
started by hand (Actions tab or `gh workflow run`; pushes and pull requests start no run, to save Actions minutes):
the plugin suite on API 34 (with smaller runs on API 29 and 35), P-P08 on an image without Google Play services, the
field-force suite on API 34, and the long scenarios (with the `include-long` input). The build workflow
`.github/workflows/ci.yml`, also started by hand only, type-checks the kit and runs its unit tests,
type-checks and dry-runs both suites, runs the field-force Node tests, and checks that the Google Play build of the
field-force app supports 16 KB memory pages (`.github/scripts/check-16kb.py`).

The field-force suite, together with the field-force app and the kit, is written so that it can move into the Bricks
app as its integration test. The contract behind the tests (scenario ids, debug commands, mock back office) is
[docs/e2e/architecture.md](docs/e2e/architecture.md).

## Publishing to npm

The package is published as
[`@bricks-soft/capacitor-location-tracking`](https://www.npmjs.com/package/@bricks-soft/capacitor-location-tracking),
with public access, by the workflow [`.github/workflows/npm-publish.yml`](.github/workflows/npm-publish.yml). The
setup is the same as in `bricks-soft/cap-downloader`: npm trusted publishing (OpenID Connect, no npm token) and npm
staged publishing.

One-time setup on npmjs.com (by an owner of the `@bricks-soft` npm scope), after the first version exists on npm
(trusted publishers are configured per package):

1. Open the package's **Settings** → **Trusted publishing** → **GitHub Actions**.
2. Enter organization `bricks-soft`, repository `location-tracking`, workflow file `npm-publish.yml`, no environment.
3. Give it the same permission as the `cap-downloader` publisher: stage only (`npm stage publish`).

The first version cannot use the trusted publisher, because the package does not exist yet. Publish it once by hand
from a clean checkout, logged in with `npm login` as a member of the `@bricks-soft` scope:

```bash
npm ci && npm run build && npm test
npm publish               # prepare builds again; publishConfig.access makes it public
```

Each later release:

1. Set the same version in `package.json` (then `npm install --package-lock-only`), `PLUGIN_VERSION` in
   `src/web/device.ts` and `PLUGIN_VERSION` in `android/build.gradle` (`npm test` checks the first two), and give the
   `CHANGELOG.md` section that version and the date.
2. Merge to `master` and create a GitHub release with a tag such as `v8.0.1`. The release starts the workflow (it can
   also be started by hand from the Actions tab). The workflow builds, runs `npm test` and runs `npm stage publish`.
3. In the package's **Staged Packages** tab on npmjs.com, approve the staged version (or run
   `npm stage approve <stage-id>`) with 2FA. Only then can apps install it.

Versions published by the workflow carry an npm provenance statement (the repository is public), which links each
version to the commit and workflow run that built it. The first version, published by hand, has none.

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

| Prop                            | Type                                                                        | Description                                                              | Default                 |
| ------------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ----------------------- |
| **`url`**                       | <code>string \| null</code>                                                 | No url =&gt; nothing is uploaded (records stay queued).                  |                         |
| **`method`**                    | <code><a href="#httpmethod">HttpMethod</a></code>                           |                                                                          | <code>'POST'</code>     |
| **`headers`**                   | <code><a href="#record">Record</a>&lt;string, string&gt;</code>             |                                                                          | <code>{}</code>         |
| **`params`**                    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code>            | merged into the ROOT of every request body                               | <code>{}</code>         |
| **`autoSync`**                  | <code>boolean</code>                                                        |                                                                          | <code>true</code>       |
| **`autoSyncThreshold`**         | <code>number</code>                                                         | upload when queue &gt;= threshold; 0 = every record                      | <code>0</code>          |
| **`syncInterval`**              | <code>number</code>                                                         | s; upload normal records once the oldest queued one is this old; 0 = off | <code>0</code>          |
| **`batchSync`**                 | <code>boolean</code>                                                        |                                                                          | <code>false</code>      |
| **`maxBatchSize`**              | <code>number</code>                                                         |                                                                          | <code>100</code>        |
| **`disableAutoSyncOnCellular`** | <code>boolean</code>                                                        |                                                                          | <code>false</code>      |
| **`rootProperty`**              | <code>string</code>                                                         | '.' = no wrapping                                                        | <code>'location'</code> |
| **`locationTemplate`**          | <code>string \| null</code>                                                 | JSON text with `&lt;%= name %&gt;` placeholders                          |                         |
| **`geofenceTemplate`**          | <code>string \| null</code>                                                 | used for event 'geofence'; falls back to locationTemplate                |                         |
| **`timeout`**                   | <code>number</code>                                                         | ms                                                                       | <code>60000</code>      |
| **`authorization`**             | <code><a href="#authorizationconfig">AuthorizationConfig</a> \| null</code> |                                                                          |                         |


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

| Prop              | Type                                                                          | Description                                                                                             | Default                                                |
| ----------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| **`title`**       | <code>string</code>                                                           |                                                                                                         | <code>app label</code>                                 |
| **`text`**        | <code>string</code>                                                           |                                                                                                         | <code>'Location tracking is active'</code>             |
| **`smallIcon`**   | <code>string</code>                                                           | 'drawable/name' \| 'mipmap/name'                                                                        | <code>plugin icon 'drawable/lt_ic_notification'</code> |
| **`largeIcon`**   | <code>string</code>                                                           |                                                                                                         |                                                        |
| **`color`**       | <code>string</code>                                                           | '#RRGGBB'                                                                                               |                                                        |
| **`priority`**    | <code><a href="#notificationpriority">NotificationPriority</a></code>         |                                                                                                         | <code>'default'</code>                                 |
| **`channelId`**   | <code>string</code>                                                           |                                                                                                         | <code>'location_tracking'</code>                       |
| **`channelName`** | <code>string</code>                                                           |                                                                                                         | <code>'Location tracking'</code>                       |
| **`actions`**     | <code>NotificationActionButton[]</code>                                       | max 3; tap =&gt; 'notificationaction' event                                                             |                                                        |
| **`resume`**      | <code><a href="#resumenotificationconfig">ResumeNotificationConfig</a></code> | notification that resumes tracking when Android refuses to restore it from the background (Android 14+) |                                                        |


#### NotificationActionButton

| Prop        | Type                |
| ----------- | ------------------- |
| **`id`**    | <code>string</code> |
| **`label`** | <code>string</code> |


#### ResumeNotificationConfig

Posted when Android refuses to restore tracking from the background (after a reboot, an app update or a process
restart), typically on Android 14+ without "Allow all the time". A tap resumes the session
(`tracking_start` reason `resume_notification`). Uses the tracking notification's small icon and color.

| Prop              | Type                 | Default                                                    |
| ----------------- | -------------------- | ---------------------------------------------------------- |
| **`enabled`**     | <code>boolean</code> | <code>false</code>                                         |
| **`title`**       | <code>string</code>  | <code>app label</code>                                     |
| **`text`**        | <code>string</code>  | <code>'Location tracking is paused. Tap to resume.'</code> |
| **`channelName`** | <code>string</code>  | <code>'Paused location tracking'</code>                    |


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

| Prop                      | Type                                                                                                                                                     | Description                                                                                                                                                                                                                             |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`uuid`**                | <code>string</code>                                                                                                                                      |                                                                                                                                                                                                                                         |
| **`event`**               | <code><a href="#recordevent">RecordEvent</a></code>                                                                                                      |                                                                                                                                                                                                                                         |
| **`timestamp`**           | <code>string \| null</code>                                                                                                                              | fix time (ISO-8601 UTC ms); null if no location ever known                                                                                                                                                                              |
| **`recorded_at`**         | <code>string</code>                                                                                                                                      | record creation time                                                                                                                                                                                                                    |
| **`sent_at`**             | <code>string</code>                                                                                                                                      | only present in HTTP bodies                                                                                                                                                                                                             |
| **`elapsed_realtime_ms`** | <code>number</code>                                                                                                                                      |                                                                                                                                                                                                                                         |
| **`boot_count`**          | <code>number</code>                                                                                                                                      | -1 if unavailable                                                                                                                                                                                                                       |
| **`is_moving`**           | <code>boolean</code>                                                                                                                                     |                                                                                                                                                                                                                                         |
| **`odometer`**            | <code>number</code>                                                                                                                                      |                                                                                                                                                                                                                                         |
| **`mock`**                | <code>boolean</code>                                                                                                                                     |                                                                                                                                                                                                                                         |
| **`coords`**              | <code><a href="#coords">Coords</a> \| null</code>                                                                                                        |                                                                                                                                                                                                                                         |
| **`activity`**            | <code>{ type: <a href="#activitytype">ActivityType</a>; confidence: number; }</code>                                                                     |                                                                                                                                                                                                                                         |
| **`battery`**             | <code>{ level: number; is_charging: boolean; }</code>                                                                                                    | level 0..1, -1 unknown                                                                                                                                                                                                                  |
| **`backend`**             | <code><a href="#locationbackend">LocationBackend</a> \| null</code>                                                                                      |                                                                                                                                                                                                                                         |
| **`extras`**              | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code>                                                                                         |                                                                                                                                                                                                                                         |
| **`geofence`**            | <code>{ identifier: string; action: <a href="#geofenceaction">GeofenceAction</a>; extras?: <a href="#record">Record</a>&lt;string, unknown&gt;; }</code> | event 'geofence' only                                                                                                                                                                                                                   |
| **`provider`**            | <code><a href="#providerstate">ProviderState</a></code>                                                                                                  | provider state when the record was created; the new state for event 'providerchange'                                                                                                                                                    |
| **`reason`**              | <code>string</code>                                                                                                                                      | tracking_start: start\|start_geofences\|boot\|restore\|package_replaced\|resume_notification; tracking_stop: stop\|stop_on_stationary\|stop_after_elapsed\|terminate\|permission_denied\|service_start_failed\|reboot\|package_replaced |
| **`heartbeat`**           | <code><a href="#heartbeatmeta">HeartbeatMeta</a></code>                                                                                                  | event 'heartbeat' only, optional: how the heartbeat is scheduled                                                                                                                                                                        |


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


#### HeartbeatMeta

Scheduling metadata of a `heartbeat` record (snake_case = wire format).

| Prop                 | Type                                                           | Description                                                            |
| -------------------- | -------------------------------------------------------------- | ---------------------------------------------------------------------- |
| **`strategy`**       | <code>'exact' \| 'listener_with_backup' \| 'idle_paced'</code> |                                                                        |
| **`min_interval`**   | <code>number</code>                                            | heartbeat.minInterval (s) when the heartbeat was created               |
| **`max_interval`**   | <code>number</code>                                            | heartbeat.maxInterval (s) when the heartbeat was created               |
| **`next_at`**        | <code>string \| null</code>                                    | when the next heartbeat is expected (ISO-8601 UTC ms); null if unknown |
| **`battery_exempt`** | <code>boolean</code>                                           | the app is exempt from battery optimization                            |
| **`device_idle`**    | <code>boolean</code>                                           | the device was in deep Doze                                            |


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

<code>{
 [P in K]: T;
 }</code>


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

<code>{
 [P in K]: T[P];
 }</code>


#### Partial

Make all properties in T optional

<code>{
 [P in keyof T]?: T[P];
 }</code>


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

MIT, © 2026 Bricks Soft. See [LICENSE](LICENSE).
