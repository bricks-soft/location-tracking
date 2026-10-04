# Migrating from Transistor `capacitor-background-geolocation` v8

This guide moves an Android app from `@transistorsoft/capacitor-background-geolocation` **v8** (flat config) to
`@bricks-soft/capacitor-location-tracking`, and lists what the server that receives the uploads has to change.

This plugin is not a drop-in copy of the Transistor API ([DECISIONS.md](DECISIONS.md#1-decisions-made-by-the-product-owner-questions-and-answers),
Q6): the config is grouped, methods take one options object, and the upload body has more fields and record types.
The default upload body keeps the v8 layout (`{ "location": { "coords": …, "activity": …, … } }` with `params` at the
root), so most server changes are small. The ones you can't skip are the new record types, `null` instead of `-1`,
and the `event` key (see [Update the server endpoint](#3-update-the-server-endpoint)).

- [Before you start](#before-you-start)
- [1. Replace the packages](#1-replace-the-packages)
- [2. Update the app code](#2-update-the-app-code)
  - [Calling style](#calling-style)
  - [Config keys](#config-keys)
  - [Methods](#methods)
  - [Events](#events)
  - [Errors](#errors)
  - [Behavior that changes](#behavior-that-changes)
  - [First launch after the update](#first-launch-after-the-update)
- [3. Update the server endpoint](#3-update-the-server-endpoint)
  - [Rollout: old and new app versions at the same time](#rollout-old-and-new-app-versions-at-the-same-time)
  - [What stays the same](#what-stays-the-same)
  - [What changes in each record](#what-changes-in-each-record)
  - [New record types](#new-record-types)
  - [Responses and retries](#responses-and-retries)
  - [JWT refresh](#jwt-refresh)
  - [Custom templates](#custom-templates)
  - [Server checklist](#server-checklist)
- [4. Verify](#4-verify)

## Before you start

- **Android only.** iOS is not implemented yet: every call rejects with "not implemented on ios". Don't migrate an
  app that ships on iOS until it is.
- **Capacitor 8**, Android `minSdk` 24, `compileSdk` / `targetSdk` 36, JDK 21. Transistor v8 also ran on Capacitor
  7; upgrade Capacitor first if you are still on 7.
- **No license key.** Polygon geofences and HMS (Huawei) phones need no add-on.
- **Nothing is carried over** from Transistor: records it had not uploaded yet, geofences, the odometer, the persisted
  config and state, and the log. See [First launch after the update](#first-launch-after-the-update).

## 1. Replace the packages

Remove Transistor and its background-fetch dependency, then add this plugin:

```bash
npm uninstall @transistorsoft/capacitor-background-geolocation @transistorsoft/capacitor-background-fetch
npm i @bricks-soft/capacitor-location-tracking
npx cap sync android
```

Undo Transistor's Android setup:

`android/build.gradle`: remove the two `maven` entries.

```diff
 allprojects {
     repositories {
         google()
         mavenCentral()
-        maven { url("${project(':transistorsoft-capacitor-background-geolocation').projectDir}/libs") }
-        maven { url("${project(':transistorsoft-capacitor-background-fetch').projectDir}/libs") }
     }
 }
```

`android/app/build.gradle`: remove the `app.gradle` extension.

```diff
 apply plugin: 'com.android.application'
-Project background_geolocation = project(':transistorsoft-capacitor-background-geolocation')
-apply from: "${background_geolocation.projectDir}/app.gradle"
```

`android/app/src/main/AndroidManifest.xml`: remove the license keys.

```diff
-<meta-data android:name="com.transistorsoft.locationmanager.license" android:value="…" />
-<meta-data android:name="com.transistorsoft.locationmanager.polygon.license" android:value="…" />
```

Also undo whatever the [background-fetch Android setup](https://github.com/transistorsoft/capacitor-background-fetch/blob/master/help/INSTALL-ANDROID.md)
added. `playServicesLocationVersion` and `hmsLocationVersion` in `android/variables.gradle` can stay: this plugin reads
the same names. If you package `hms`, remove `hmsLocationVersion` or set it to `6.20.0.300` or newer: most older
versions bring native libraries that fail Google Play's 16 KB page-size requirement (README, "Android setup").

Then follow [Android setup](../README.md#android-setup) in the README: choose the packaged location SDKs
(`locationTracking.providers`, default `gms`), add Huawei's Maven repository if you package `hms`, and review the
merged permissions.

## 2. Update the app code

### Calling style

| v8 | Now |
|---|---|
| `import BackgroundGeolocation from '@transistorsoft/capacitor-background-geolocation'` | `import { LocationTracking, onLocation, … } from '@bricks-soft/capacitor-location-tracking'` |
| Positional arguments: `addGeofence(geofence)`, `changePace(true)` | One options object: `addGeofence({ geofence })`, `changePace({ isMoving: true })` |
| Success / failure callbacks or promises | Promises only |
| Results as bare values: `getCount()` → `12` | Results wrapped in an object: `getCount()` → `{ count: 12 }` |
| Constants: `BackgroundGeolocation.DESIRED_ACCURACY_HIGH`, `LOG_LEVEL_DEBUG` | Strings: `'high'`, `'debug'` |
| Flat config: `ready({ distanceFilter, url, … })` | Grouped config: `ready({ config: { geolocation: { distanceFilter }, http: { url } } })` |

**Regroup every key.** `ready()` ignores unknown keys (with a warning in the log), so a flat v8 config passed as it is
leaves you with the defaults and no `http.url`. A number where a string is expected (a v8 constant) rejects the whole
call with `INVALID_ARGUMENT`.

Before:

```ts
import BackgroundGeolocation from '@transistorsoft/capacitor-background-geolocation';

BackgroundGeolocation.onLocation((location) => console.log(location));
BackgroundGeolocation.onHeartbeat(() => BackgroundGeolocation.getCurrentPosition({ samples: 1, persist: true }));

const state = await BackgroundGeolocation.ready({
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 20,
  stopTimeout: 5,
  heartbeatInterval: 60,
  stopOnTerminate: false,
  startOnBoot: true,
  url: 'https://api.example.com/locations',
  batchSync: true,
  maxBatchSize: -1,
  params: { device_id: 'abc' },
  extras: { driver_id: 7 },
  authorization: {
    strategy: 'JWT',
    accessToken,
    refreshToken,
    refreshUrl: 'https://api.example.com/oauth/token',
    refreshPayload: { grant_type: 'refresh_token', refresh_token: '{refreshToken}' },
    expires: expiresAtSeconds,
  },
  logLevel: BackgroundGeolocation.LOG_LEVEL_DEBUG,
  notification: { title: 'Trip', text: 'Tracking', priority: BackgroundGeolocation.NOTIFICATION_PRIORITY_LOW },
});
if (!state.enabled) await BackgroundGeolocation.start(); // v8 asks for location permission itself
```

After:

```ts
import { LocationTracking, onLocation } from '@bricks-soft/capacitor-location-tracking';

await onLocation((location) => console.log(location));
// No onHeartbeat handler: the plugin creates and uploads heartbeat records itself.

const state = await LocationTracking.ready({
  config: {
    geolocation: { desiredAccuracy: 'high', distanceFilter: 20, stopTimeout: 5 },
    heartbeat: { minInterval: 180, maxInterval: 300 }, // the defaults
    app: { stopOnTerminate: false, startOnBoot: true },
    http: {
      url: 'https://api.example.com/v2/locations', // a new path, see "Rollout" below
      batchSync: true,
      maxBatchSize: 100,
      params: { device_id: 'abc' },
      authorization: {
        strategy: 'JWT',
        accessToken,
        refreshToken,
        refreshUrl: 'https://api.example.com/oauth/token',
        refreshPayload: { grant_type: 'refresh_token', refresh_token: '{refreshToken}' },
        refreshPayloadEncoding: 'form', // v8 sent a form body
        expires: expiresAtSeconds * 1000, // epoch milliseconds
      },
    },
    persistence: { extras: { driver_id: 7 } },
    logger: { logLevel: 'debug' },
    notification: { title: 'Trip', text: 'Tracking', priority: 'low' },
  },
});

// This plugin never asks for permissions by itself.
const permissions = await LocationTracking.requestPermissions({
  permissions: ['location', 'notifications', 'activityRecognition'],
});
if (permissions.location === 'granted' && !state.enabled) await LocationTracking.start();
// Later, after your own explanation: "Allow all the time" (needed for geofences and background restarts).
await LocationTracking.requestPermissions({ permissions: ['backgroundLocation'] });
```

### Config keys

Every v8 key, where it goes, and what changes. Units are the same unless the row says otherwise. Defaults of this
plugin: [Configuration reference](../README.md#configuration-reference).

| v8 key | Now | Notes |
|---|---|---|
| `desiredAccuracy` | `geolocation.desiredAccuracy` | `DESIRED_ACCURACY_NAVIGATION` / `HIGH` → `'high'`, `MEDIUM` → `'balanced'`, `LOW` / `VERY_LOW` → `'low'`. `'passive'` also exists. |
| `distanceFilter` | `geolocation.distanceFilter` | In v8, `locationUpdateInterval` applied only with `distanceFilter: 0`. Here both always apply. |
| `disableElasticity`, `elasticityMultiplier` | `geolocation.*` | |
| `stationaryRadius` | `geolocation.stationaryRadius` | The stationary geofence has a radius of at least 150 m ([Battery](../README.md#battery)). |
| `stopTimeout` | `geolocation.stopTimeout` | Minutes. |
| `stopAfterElapsedMinutes` | `geolocation.stopAfterElapsedMinutes` | `0` = off. |
| `stopOnStationary` | `geolocation.stopOnStationary` | |
| `locationTimeout` | `geolocation.locationTimeout` | **Seconds → milliseconds** (v8 default 60 s, now 30000 ms). |
| `locationUpdateInterval`, `fastestLocationUpdateInterval` | `geolocation.*` | Milliseconds. |
| `allowIdenticalLocations` | `geolocation.filter.allowIdenticalLocations` | |
| `speedJumpFilter` | `geolocation.filter.maxImpliedSpeed` | m/s. Default 80 (v8: 300). |
| `desiredOdometerAccuracy` | `geolocation.filter.odometerAccuracyThreshold` | Meters. Default 20 (v8: 100). |
| – | `geolocation.filter.trackingAccuracyThreshold` | **New: fixes worse than 100 m are dropped.** Raise it if you use `'low'` accuracy. |
| `isMoving` | – | Call `changePace({ isMoving: true })` after `start()`. |
| `geofenceInitialTriggerEntry` | `geofence.initialTriggerEntry` | |
| `disableStopDetection`, `disableMotionActivityUpdates` | `activity.*` | |
| `motionTriggerDelay` | `activity.motionTriggerDelay` | Milliseconds. |
| `activityRecognitionInterval`, `minimumActivityRecognitionConfidence` | `activity.*` | Ignored by v8; used here (10000 ms, 75 %). |
| `url`, `headers`, `params`, `autoSync`, `autoSyncThreshold`, `batchSync`, `disableAutoSyncOnCellular` | `http.*` | |
| `method` | `http.method` | `POST`, `PUT` or `PATCH`. `OPTIONS` is not accepted. |
| `maxBatchSize` | `http.maxBatchSize` | Default 100 (v8: `-1`, no limit). The minimum is 1: **`-1` is clamped to 1**, one record per request. |
| `httpRootProperty` | `http.rootProperty` | |
| `httpTimeout` | `http.timeout` | Milliseconds. |
| `locationTemplate`, `geofenceTemplate` | `http.*` | See [Custom templates](#custom-templates). |
| `authorization` | `http.authorization` | See [JWT refresh](#jwt-refresh). `expires` is epoch **milliseconds**. |
| `locationsOrderDirection` | – | Always oldest first. |
| – | `http.syncInterval` | New: live location at most this many seconds old, one upload per interval ([details](../README.md#live-location-httpsyncinterval)). |
| `extras` | `persistence.extras` | |
| `maxDaysToPersist` | `persistence.maxDaysToPersist` | Default 7 (v8: 1). |
| `maxRecordsToPersist` | `persistence.maxRecordsToPersist` | |
| `persistMode` | – | Every record is stored and uploaded. |
| `disableProviderChangeRecord` | – | `providerchange` records are always created while tracking. |
| `stopOnTerminate`, `startOnBoot` | `app.*` | Same defaults. |
| `heartbeatInterval` | `heartbeat.minInterval`, `heartbeat.maxInterval` | Different meaning: see [Heartbeat](#heartbeat). |
| `notification.title`, `text`, `color`, `smallIcon`, `largeIcon`, `channelId`, `channelName` | `notification.*` | |
| `notificationTitle`, `notificationText`, `notificationColor`, `notificationSmallIcon`, `notificationLargeIcon`, `notificationChannelName` | `notification.title`, `text`, `color`, `smallIcon`, `largeIcon`, `channelName` | |
| `notification.priority`, `notificationPriority` | `notification.priority` | `NOTIFICATION_PRIORITY_MIN` / `LOW` / `DEFAULT` / `HIGH` / `MAX` → `'min'` / `'low'` / `'default'` / `'high'` / `'max'`. |
| `notification.actions` | `notification.actions` | `[{ id, label }]`, at most 3, instead of the button ids of a custom layout. |
| `notification.layout`, `strings`, `sticky` | – | No custom layouts. |
| `backgroundPermissionRationale` | `backgroundPermissionRationale` | No `{applicationName}` or `{backgroundPermissionOptionLabel}` tags: write the text out. |
| `logLevel` | `logger.logLevel` | `LOG_LEVEL_OFF` / `ERROR` / `WARNING` / `INFO` / `DEBUG` / `VERBOSE` → `'off'` / `'error'` / `'warn'` / `'info'` / `'debug'` / `'verbose'`. Default `'info'` (v8: off). |
| `logMaxDays` | `logger.logMaxDays` | |
| `reset` | `ready({ config, reset })` | An option of `ready()`, not a config key. Default `true` in both. |
| – | `locationProvider` | New: `'auto'` (default), `'gms'`, `'hms'` or `'android'`. |

**No equivalent** (remove them): `debug` (no sounds), `schedule`, `scheduleUseAlarmManager`, `enableHeadless`,
`forceReloadOnLocationChange` and the other `forceReloadOn*` keys, `foregroundService` (always on),
`geofenceProximityRadius`, `geofenceModeHighAccuracy`, `deferTime`, `triggerActivities` (every moving activity
triggers), `enableTimestampMeta`, `locationAuthorizationRequest`, `transistorAuthorizationToken`, and the iOS-only
keys (`preventSuspend`, `pausesLocationUpdatesAutomatically`, `useSignificantChangesOnly`, `activityType`,
`stopDetectionDelay`, `showsBackgroundLocationIndicator`, `locationAuthorizationAlert`,
`disableLocationAuthorizationAlert`).

### Methods

| v8 | Now |
|---|---|
| `ready(config)`, `configure(config)` | `ready({ config, reset? })` |
| `setConfig(config)` | `setConfig({ config })` |
| `reset(config?)` | `reset({ config? })` |
| `start()`, `stop()`, `startGeofences()` | Same. `start()` rejects with `PERMISSION_DENIED` without location permission instead of asking for it. |
| `getState()` | Same name. Result: `{ enabled, trackingMode: 'location' \| 'geofences', isMoving, odometer, backend, lastRecordAt, config }`. v8's numeric `trackingMode` (1 / 0), `schedulerEnabled`, `didLaunchInBackground` and `didDeviceReboot` are gone, and the config is under `config`. |
| `changePace(isMoving)` | `changePace({ isMoving })` |
| `getCurrentPosition(options)` | `getCurrentPosition(options)`. `timeout` is in **milliseconds** (v8: seconds). `desiredAccuracy` is `'high' \| 'balanced' \| 'low' \| 'passive'` (v8: meters); sampling stops early at a fix of 10 m or better. |
| `watchPosition(success, failure, options)` | `const id = await watchPosition(options, (location, error) => …)`. No `timeout` option. |
| `stopWatchPosition()` | `clearWatch({ id })` |
| `getLocations()` | `getLocations({ limit? })` → `{ locations }` |
| `getCount()` | `getCount()` → `{ count }` |
| `destroyLocations()` | `destroyLocations()` → `{ count }` |
| `destroyLocation(uuid)` | `destroyLocation({ uuid })` → `{ deleted }` |
| `insertLocation(location)` | `insertLocation({ location: { coords, timestamp?, event?, is_moving?, extras? } })` → `{ uuid }`. It emits no event. |
| `sync()` | `sync()` → `{ locations }` |
| `getOdometer()` | `getOdometer()` → `{ odometer }` |
| `setOdometer(value)` | `setOdometer({ odometer })` → `{ odometer }` (v8 resolved a location) |
| `resetOdometer()` | `resetOdometer()` → `{ odometer }` |
| `addGeofence(geofence)` | `addGeofence({ geofence })` |
| `addGeofences(geofences)` | `addGeofences({ geofences })` |
| `removeGeofence(identifier)` | `removeGeofence({ identifier })` |
| `removeGeofences()` | `removeGeofences()` removes all. `removeGeofences({ identifiers })` removes some; an empty array removes **nothing**. |
| `getGeofences()` | `getGeofences()` → `{ geofences }` |
| `getGeofence(identifier)` | `getGeofence({ identifier })` → `{ geofence }`, `null` when unknown (v8 rejected) |
| `geofenceExists(identifier)` | `geofenceExists({ identifier })` → `{ exists }` |
| `getProviderState()` | Same name, new shape: see [Events](#events), `providerchange`. |
| `requestPermission()` | `requestPermissions({ permissions? })` → `{ location, backgroundLocation, activityRecognition, notifications }`, each `'granted' \| 'denied' \| 'prompt' \| 'prompt-with-rationale'`. Also `checkPermissions()`. |
| `isPowerSaveMode()` | `isPowerSaveMode()` → `{ isPowerSaveMode }` |
| `getDeviceInfo()`, `getSensors()` | Same names, different fields ([API](../README.md#api)). |
| `deviceSettings.isIgnoringBatteryOptimizations()` | `getBatteryOptimizationStatus()` → `{ isIgnoringBatteryOptimizations, … }` |
| `deviceSettings.showIgnoreBatteryOptimizations()` + `show()` | `openBatteryOptimizationSettings()` |
| `deviceSettings.showPowerManager()` + `show()` | `getPowerManagerInfo()` (is there a screen?) + `openPowerManagerSettings()` |
| `setLogLevel(level)` | `setConfig({ config: { logger: { logLevel } } })` |
| `logger.error()` / `warn()` / `info()` / `notice()` / `debug()` | `log({ level: 'error' \| 'warn' \| 'info' \| 'debug', message })` (`notice` → `'info'`) |
| `getLog()`, `logger.getLog(query)` | `getLog({ start?, end?, level?, limit?, order?: 'asc' \| 'desc' })` → `{ log }` |
| `emailLog(email)`, `logger.emailLog(email)` | `emailLog({ email, subject? })` |
| `logger.uploadLog(url)` | `uploadLog({ url, headers?, params? })` → `{ success, status }` |
| `destroyLog()`, `logger.destroyLog()` | `destroyLog()` |
| `removeListener(event, fn)`, `un(event, fn)` | `handle.remove()` on the handle the subscription resolved with |
| `removeListeners()`, `removeAllListeners()` | `removeAllListeners()` |
| – | New: `getHeartbeatStatus()`, `openLocationSettings()`, `openAppSettings()` |

**No equivalent:** `startSchedule()` / `stopSchedule()`, `startBackgroundTask()` / `stopBackgroundTask()` /
`finish()`, `registerHeadlessTask()`, `playSound()`, `requestTemporaryFullAccuracy()` (iOS), `transistorTrackerParams()`,
`findOrCreateTransistorAuthorizationToken()`, `destroyTransistorAuthorizationToken()`.

### Events

Subscriptions return a **promise** of a handle (v8 returned a `Subscription` synchronously): `const handle = await
onLocation(cb); … handle.remove();`. Register them before `ready()`, as with v8.

| v8 | Now | Payload change |
|---|---|---|
| `onLocation(cb, failure)` | `onLocation(cb)` | The record shape of [What changes in each record](#what-changes-in-each-record). No failure callback. Also fires for `current_position` and `watch_position` records. |
| `onMotionChange` | `onMotionChange` | None: `{ isMoving, location }`. |
| `onActivityChange` | `onActivityChange` | None: `{ activity, confidence }`. |
| `onProviderChange` | `onProviderChange` | `{ enabled, gps, network, permission: 'always' \| 'when_in_use' \| 'denied', accuracy: 'precise' \| 'approximate' \| 'none', backend }` instead of `{ enabled, gps, network, status, accuracyAuthorization }`. |
| `onHeartbeat` | `onHeartbeat` | `{ location }` as before, but it is now a stored and uploaded record: see [Heartbeat](#heartbeat). |
| `onGeofence` | `onGeofence` | `{ identifier, action, location, extras? }`. No `timestamp`: use `location.timestamp`. |
| `onGeofencesChange` | `onGeofencesChange` | None: `{ on, off }`. |
| `onHttp` | `onHttp` | Adds `uuids`. One event per request, retries included. |
| `onConnectivityChange` | `onConnectivityChange` | Adds `type` (`'wifi'`, `'cellular'`, …). |
| `onPowerSaveChange(enabled)` | `onPowerSaveChange` | `{ isPowerSaveMode }` instead of a boolean. |
| `onEnabledChange(enabled)` | `onEnabledChange` | `{ enabled }` instead of a boolean. |
| `onNotificationAction(buttonId)` | `onNotificationAction` | `{ id }` instead of a string. |
| `onAuthorization` | `onAuthorization` | None: `{ success, status, error?, response? }`. |
| `onSchedule`, `boot`, `terminate` | – | A reboot or a swipe-away shows up as a `tracking_start` (reason `boot`) or `tracking_stop` (reason `terminate`) record. |

### Errors

v8 rejected with a string, or with a number for position calls (`0` unknown, `1` permission denied, `2` network,
`408` timeout, `499` cancelled). Every rejection is now an object with a `code` and a `message`
([Error codes](../README.md#error-codes)):

```ts
try {
  await LocationTracking.getCurrentPosition({ timeout: 30000 });
} catch (e: any) {
  // v8 1 → 'PERMISSION_DENIED', 408 → 'TIMEOUT'; location services off → 'LOCATION_DISABLED'
  if (e.code === 'TIMEOUT') { /* … */ }
}
```

New: most methods reject with `NOT_READY` until `ready()` has resolved in the process
([the NOT_READY rule](../README.md#quick-start)).

### Behavior that changes

#### Permissions

v8's `start()` asked for location permission itself (`locationAuthorizationRequest`). This plugin never shows a
permission dialog on its own. Call `requestPermissions()` before `start()`, then ask for `backgroundLocation` ("Allow
all the time") separately. That permission is needed for geofences and for restarting tracking from the background.
Call `start()` while the app is visible; Android 12+ refuses to start the service from the background.
Details: [Runtime permission flow](../README.md#5-runtime-permission-flow).

#### Heartbeat

In v8, `heartbeatInterval` only fired the `heartbeat` event while the device was stationary. Nothing was stored or
uploaded unless your handler did it, typically with `getCurrentPosition({ persist: true })`.

Here the plugin creates a `heartbeat` **record** itself whenever tracking is on and no other record was created for
`heartbeat.minInterval` seconds (default 180), and uploads it at once. It carries the last known position, and GPS
stays off. **Remove `getCurrentPosition()` from your heartbeat handler.** It would switch GPS on every few minutes, and
each `current_position` record it creates restarts the heartbeat window. To turn heartbeats off, set
`heartbeat.enabled: false`. More: [heartbeat.md](heartbeat.md).

#### Uploads

- **Audit records upload at once.** `heartbeat`, `tracking_start`, `tracking_stop` and `providerchange` ignore
  `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`, and take the queued records with them.
- **`motionchange` no longer forces an upload.** v8 ignored `autoSyncThreshold` for a motion change. Here it is a
  normal record and waits like a `location`; the next heartbeat (at most 3–5 minutes later on a stationary device)
  uploads it.
- **Retries within an upload.** A `5xx`, `429`, timeout or network error is tried again after 2, 4 and 8 seconds.
- Rules: [wire-format.md, When uploads happen](wire-format.md#when-uploads-happen).

#### Schedule

There is no `schedule`. For a daily stop, compute the minutes to the stop time and set
`geolocation.stopAfterElapsedMinutes` before `start()`, as the [field-force example](../README.md#field-force-example)
does for 02:00. Start tracking from your app when it opens.

#### Headless code

With `enableHeadless`, v8 ran your Java code when no WebView existed. Here, a Kotlin `LocationTrackingListener`
declared in the manifest receives every record and every event in every process, also after a reboot or an alarm.
See [native-api.md](native-api.md).

#### Geofences

- At most **100** geofences (`TOO_MANY_GEOFENCES` beyond that). v8's `geofenceProximityRadius` trick for thousands of
  geofences has no equivalent.
- Polygons (`vertices`) need no add-on.
- As in v8, geofences are monitored only while tracking is on (`start()` or `startGeofences()`).
- `removeGeofences({ identifiers: [] })` removes nothing.

### First launch after the update

The update removes Transistor's service and receivers, and this plugin has never run, so:

- **Tracking is off until your app calls `start()`**, even with `startOnBoot: true`: resuming after an app update
  (`package_replaced`) only applies when this plugin was tracking before the update. If the user was tracking, start
  again on the first launch (the field-force example starts tracking on every launch).
- Records that Transistor had not uploaded yet are lost. The server sees a gap between the last v8 record and the new
  `tracking_start` record.
- Add your geofences again (`addGeofences()`), and call `setOdometer()` if you need the odometer to continue.

## 3. Update the server endpoint

Reference for everything below: [wire-format.md](wire-format.md).

### Rollout: old and new app versions at the same time

Phones update over days or weeks, so the endpoint receives v8 bodies and new bodies side by side. The simplest way to
keep them apart is to give the new app version a new URL (for example `/v2/locations`) and leave the v8 handler as
it is. If the URL can't change, tell the bodies apart per record: only this plugin sends `recorded_at`.

### What stays the same

- `POST` (or your `http.method`) with a JSON body and `Content-Type: application/json`.
- The body layout: the record under `rootProperty` (default `"location"`), an array of records with `batchSync`, and
  `params` merged into the root. `rootProperty: "."` puts the record at the root.
- `headers`, and `Authorization: Bearer <accessToken>` with `http.authorization`.
- These record keys, with the same meaning: `uuid`, `timestamp` (fix time, ISO-8601 UTC), `is_moving`, `odometer`
  (meters), `coords.latitude`, `longitude`, `accuracy`, `speed`, `heading`, `altitude`, `altitude_accuracy`,
  `speed_accuracy`, `heading_accuracy`, `activity.type` and `confidence`, `battery.level` (0–1) and `is_charging`,
  `extras`, and `geofence.identifier`, `action` and `extras` on geofence records.
- A `2xx` answer deletes the records from the device queue; any other answer keeps them for a later retry.

### What changes in each record

| Key | v8 | Now | What to do |
|---|---|---|---|
| `event` | Missing on ordinary fixes; `motionchange`, `geofence`, `heartbeat` or `providerchange` otherwise | **Always present**: `location`, `motionchange`, `current_position`, `watch_position`, `heartbeat`, `geofence`, `tracking_start`, `tracking_stop` or `providerchange` | Replace "no `event` means a fix" with checks on the value. Store unknown values and answer `2xx`. |
| Unknown `speed`, `heading`, `speed_accuracy`, `heading_accuracy`, `altitude_accuracy` | `-1` | `null` (`altitude` too) | Make the columns nullable; drop `-1` checks. |
| `coords`, `timestamp` | Always a fix | `null` on heartbeat and audit records when the phone has never had a fix (a fresh install) | Accept `null`. |
| `age` | Milliseconds since the fix | Removed | Use `recorded_at − timestamp`. |
| `coords.ellipsoidal_altitude`, `coords.floor` | Present | Removed | On Android, `altitude` is already above the WGS84 ellipsoid. |
| `timestampMeta` | With `enableTimestampMeta` | Removed | Use `elapsed_realtime_ms` and `boot_count`. |
| `mock` | Optional | Always present | – |
| `battery.level` | 0–1 | 0–1, or `-1` when unknown | Treat `-1` as unknown. |
| `provider` (on `providerchange`) | `{ enabled, status, network, gps, accuracyAuthorization }` | `{ enabled, gps, network, permission, accuracy, backend }`, where `permission` is `always` / `when_in_use` / `denied` and `accuracy` is `precise` / `approximate` / `none` | Map the new keys. |
| New keys on every record | – | `recorded_at` (created on the phone), `sent_at` (request built; only in HTTP bodies), `elapsed_realtime_ms`, `boot_count`, `backend` (`gms` / `hms` / `android`) | Store them: they are what an audit needs ([Time fields](wire-format.md#time-fields)). |
| `reason` | – | On `tracking_start` and `tracking_stop` | See the reason tables in [wire-format.md](wire-format.md#tracking_start). |
| `heartbeat` | – | Optional scheduling metadata on `heartbeat` records | Tells you the next expected gap ([heartbeat.md](heartbeat.md#heartbeat-metadata)). |

### New record types

- **`heartbeat`**: every `heartbeat.minInterval` seconds (default 180) while tracking is on and nothing else was
  recorded, so up to about 20 requests per hour from a parked device. Its coords are the **last known** position, and its
  `timestamp` is when that fix was taken, which can be hours before `recorded_at`. Don't treat it as a new position:
  leave records whose `timestamp` is much older than `recorded_at` out of routes and distance. Use heartbeats to show
  a device as online and to flag gaps ([heartbeat.md, Server-side audit](heartbeat.md#server-side-audit)).
- **`tracking_start` / `tracking_stop`**: tracking switched on or off, with a `reason` (`start`, `boot`, `restore`,
  `stop`, `terminate`, `stop_after_elapsed`, `service_start_failed`, …). They carry the last known coords.
- **`current_position` / `watch_position`**: fixes from `getCurrentPosition()` and `watchPosition({ persist: true })`.
  In v8 these arrived as ordinary fixes. They can arrive while tracking is off.
- **`providerchange`**: v8 sent these too (unless `disableProviderChangeRecord`), with the old `provider` shape above.

### Responses and retries

- **The response body is ignored.** v8's commands in the response (`{ "background_geolocation": [["stop"], …] }`)
  are not supported. Drive those actions from the app.
- **Answer `2xx` for every record you stored, and for every record you will never accept.** Any other answer keeps the
  record queued for up to 7 days (v8: 1 day) and holds back the normal records queued after it.
- **Be idempotent on `uuid`.** A `5xx`, `429`, timeout or network error is tried again within the same upload, up to
  4 requests in about 14 seconds, each with a new `sent_at`. A record whose answer was lost comes again later.
- **Batches hold at most `maxBatchSize` records (default 100)**, always oldest first. After an outage, records up to
  7 days old can arrive, and an audit record can overtake a normal record that you rejected: order by `recorded_at`.
- **Answer quickly.** The upload holds no wake lock.

Details: [Response handling and retries](wire-format.md#response-handling-and-retries).

### JWT refresh

The upload side is unchanged: `Authorization: Bearer <accessToken>`, and a `401` triggers a refresh and one retry.
The refresh request and the parsing of its response differ:

| | v8 | Now | To keep the refresh endpoint unchanged |
|---|---|---|---|
| Request body | Form-encoded `refreshPayload` | JSON by default | Set `refreshPayloadEncoding: 'form'`. |
| Request headers | `Authorization: Bearer {accessToken}` unless `refreshHeaders` was set | Only your `refreshHeaders`. `{accessToken}` is not replaced. | The endpoint must not require the old access token; authenticate the refresh with the refresh token. |
| Response | Any JSON; the access token, refresh token and expiry were searched for at any depth | Top-level keys only: `accessToken` or `access_token` (required), `refreshToken` or `refresh_token`, `expires` or `expires_at` (epoch seconds, epoch milliseconds or ISO-8601), `expires_in` (seconds) | Return these keys at the top level. A nested `{ "token": { "access_token": … } }` fails. |
| `authorization.expires` in the config | Seconds | Epoch milliseconds, `-1` = unknown | If you passed epoch seconds, multiply by 1000. |

With `ready({ reset: true })` (the default), the tokens in the config you pass replace the refreshed ones on every
launch. Pass the latest tokens yourself, or use `reset: false` ([JWT refresh](wire-format.md#jwt-refresh)).

### Custom templates

If your v8 app set `locationTemplate` or `geofenceTemplate`:

- The `<%= tag %>` syntax and the quoting rule are the same, and every v8 tag exists except `timestampMeta`. Remove
  it: an unknown tag renders as nothing, which usually breaks the JSON, and the plugin then sends the default shape.
- **`extras` are no longer merged into the rendered object.** Add `"extras": <%= extras %>`; the server then finds
  them under `extras` instead of at the top level of the record. Per-device constants can go into `http.params`
  (root of the request) instead.
- The template is applied to **every** record type, heartbeats and audit records included. `<%= event %>` renders
  `location` for ordinary fixes.
- Unknown numbers render as `null` (v8: `-1`). A tag written exactly as `"<%= timestamp %>"` renders JSON `null` when
  the value is `null`.
- Add `uuid`, `event`, `recorded_at` and `sent_at`, and `reason`, `elapsed_realtime_ms` and `boot_count` if you audit
  tracking. Every tag: [Templates](wire-format.md#templates).
- To keep your v8 body shape and still send every new field, nest the whole default record:
  `"raw_event": <%= record %>`.

### Server checklist

- [ ] Route the new app version to its own handler (new URL, or detect `recorded_at`).
- [ ] Read `event` on every record; store unknown values and answer `2xx`.
- [ ] Accept `null` for unknown coordinates, and for `coords` and `timestamp`.
- [ ] Replace `age` with `recorded_at − timestamp`; map the new `provider` shape.
- [ ] Keep heartbeats and audit records out of routes and distance, and use them for online status and gap audits.
- [ ] Store `recorded_at`, `sent_at`, `elapsed_realtime_ms`, `boot_count`, `backend` and `reason`.
- [ ] Deduplicate on `uuid`, answer `2xx` fast, and never answer non-`2xx` for a record you won't accept later.
- [ ] Stop relying on commands in the response body.
- [ ] Refresh endpoint: form or JSON body, no old access token required, token fields at the top level.
- [ ] Expect up to about 20 heartbeat requests per hour per tracking device while it is parked.

## 4. Verify

1. Point the app at a staging endpoint, call `ready()` and `start()`, and watch `onHttp` (status and `uuids`) and
   `getLog()`. Unknown config keys and template errors are logged as warnings and errors.
2. Leave the phone still for 5 minutes: a `heartbeat` record should arrive, carrying the position where it stopped.
3. Swipe the app away, reboot, and toggle location services: check the `tracking_stop`, `tracking_start` and
   `providerchange` records against [wire-format.md](wire-format.md#record-variants).
4. On real phones, run the parts of [device-test-checklist.md](device-test-checklist.md) your app uses.
