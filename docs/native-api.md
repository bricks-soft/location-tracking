# Native companion API (Android)

This guide is for authors of **companion plugins** and native Android code that must see what the tracking plugin
records, without JavaScript. The example is the PremiseMonitor plugin of the field-force example app
(`examples/field-force/plugins/premise-monitor/`): it saves every audit record and event in its own audit log, and it
starts and stops geofences through the plugin.

The API is in the Kotlin package `com.brickssoft.locationtracking.api`:

| Type | Purpose |
|---|---|
| `LocationTrackingListener` | Receives every queued record (`onRecord`) and every event (`onEvent`). |
| `LocationTrackingNative` | Registers listeners (manifest meta-data name, `addListener`) and calls the plugin methods (`ready`, `start`, `addGeofence`, ...). |
| `NativeSubscription` | Returned by `addListener`; `remove()` ends the subscription. |
| `NativeCallback<T>` | Receives the `Result<T>` of a `LocationTrackingNative` call. |

Contents: [Registering a listener](#registering-a-listener) · [What a listener receives](#what-a-listener-receives) ·
[When a listener receives it](#when-a-listener-receives-it) · [Threads and order](#threads-and-order) ·
[Calling the plugin from Kotlin](#calling-the-plugin-from-kotlin) · [R8 and ProGuard](#r8-and-proguard) ·
[Pitfalls](#pitfalls)

---

## Registering a listener

There are two ways. A companion plugin that must never miss a record uses the manifest.

### In the manifest (recommended)

Declare the listener class in the `<application>` element of your library's (or app's) `AndroidManifest.xml`:

```xml
<application>
  <meta-data
      android:name="com.brickssoft.locationtracking.LISTENER"
      android:value="com.brickssoft.premisemonitor.PremiseAuditListener"/>
</application>
```

```kotlin
package com.brickssoft.premisemonitor

class PremiseAuditListener : LocationTrackingListener {       // public class, public no-arg constructor
    override fun onRecord(context: Context, record: JSONObject) {
        PremiseAuditLog.get(context).append(kind = "record", record = record)
    }

    override fun onEvent(context: Context, name: String, payload: JSONObject) {
        PremiseAuditLog.get(context).append(kind = "event", name = name, payload = payload)
    }
}
```

Rules:
- `android:value` is the fully qualified class name (not `.PremiseAuditListener`, not a resource reference).
- The class must be public, implement `LocationTrackingListener` and have a public constructor without arguments. A
  Kotlin `object` does not work (its constructor is private).
- The plugin creates **one instance per process**. It does this inside `Components.get()`, the first time any part of
  the plugin runs in the process, before any component can emit a record (see
  [When a listener receives it](#when-a-listener-receives-it)).
- A class that cannot be loaded or created (class not found, no public no-arg constructor, the constructor throws, the
  static initializer throws, the class does not implement the interface) is written to the plugin log with its
  exception and skipped. The process does not crash and the other listeners are installed.

**A second listener.** Android merges the `<meta-data>` of all libraries and the app into one map, so a name can appear
only once (the manifest merger rejects two different values for the same name). Give every further listener a name
that starts with `com.brickssoft.locationtracking.LISTENER.` followed by any suffix:

```xml
<meta-data
    android:name="com.brickssoft.locationtracking.LISTENER"
    android:value="com.brickssoft.premisemonitor.PremiseAuditListener"/>
<meta-data
    android:name="com.brickssoft.locationtracking.LISTENER.analytics"
    android:value="com.example.analytics.TrackingAnalyticsListener"/>
```

The listener named exactly `com.brickssoft.locationtracking.LISTENER` is created and called first; the suffixed ones
follow in the alphabetical order of their names. A class that is named twice (under two names) is created once.
A name such as `com.brickssoft.locationtracking.LISTENERS` (no `.` after `LISTENER`) is ignored.

### Programmatically

```kotlin
val subscription = LocationTrackingNative.addListener(context, object : LocationTrackingListener {
    override fun onEvent(context: Context, name: String, payload: JSONObject) {
        if (name == "enabledchange") updateBadge(payload.getBoolean("enabled"))
    }
})
// later
subscription.remove()   // idempotent: a second call does nothing
```

- The listener receives only records and events emitted **after** `addListener` returns.
- `remove()` stops the deliveries that were queued but not yet run; a call that is already running on the `LT-native`
  thread finishes.
- The subscription is process-wide. It does not create the plugin's components and does not start anything.
- It exists only in the process where your code called `addListener`. After a reboot or a process restart, it is gone
  until your code runs again. Use the manifest when the listener must see records of processes that your code does not
  start (boot, heartbeat alarm, `START_STICKY`).

Both methods have empty default implementations, so implement only the one you need.

---

## What a listener receives

### `onRecord(context, record)`

Every record the plugin queues for upload, one call per record:

| `record.event` | Created by |
|---|---|
| `tracking_start` | `start()`, `startGeofences()`, and the restore of a session in a new process (`reason`: `start`, `start_geofences`, `restore`, `boot`, `package_replaced`, `resume_notification`). |
| `tracking_stop` | `stop()` and every automatic stop (`reason`: `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`, `service_start_failed`, `reboot`, `package_replaced`). |
| `motionchange` | A change between moving and stationary, and the first fix after `start()`. |
| `location` | A recorded fix while moving. |
| `heartbeat` | The audit heartbeat: while tracking is on and no other record was created for `heartbeat.minInterval` seconds (default 180). |
| `providerchange` | A change of location services, permission, accuracy or backend while tracking is on. |
| `geofence` | A geofence `ENTER`, `EXIT` or `DWELL` while tracking is on. |
| `current_position`, `watch_position` | `getCurrentPosition()` / `watchPosition()` with `persist: true`. |
| any of the above | `insertLocation()`, from JS or from `LocationTrackingNative.insertLocation` (the inserted record has no JS event). |

- `record` is the wire JSON of the record, the same object the server receives
  ([docs/wire-format.md](wire-format.md#record-fields)) **without `sent_at`** (the record has not been sent yet).
  `recorded_at` is when the record was created; `timestamp` is when its fix was taken.
- A record the plugin creates is delivered also when the plugin failed to store it in its SQLite database (a full
  disk, for example), so your audit log still has it even though the plugin cannot upload it. An `insertLocation()`
  whose insert fails is different: the call is rejected (its caller gets the error and may retry) and the record is
  not delivered.
- Positions requested with `persist: false` are not queued and are not delivered to `onRecord`.

### `onEvent(context, name, payload)`

Every event of the JS `LocationTracking.addListener` API, with the same `name` and the same `payload` as in JavaScript
(see the [events reference in the README](../README.md#events-reference)): `location`, `motionchange`,
`activitychange`, `providerchange`, `heartbeat`, `geofence`, `geofenceschange`, `http`, `connectivitychange`,
`powersavechange`, `enabledchange`, `notificationaction`, `authorization`.

Records and the events that carry them:

| Record | Calls, in this order |
|---|---|
| `motionchange` | `onRecord(motionchange)`, `onEvent("location", <record>)`, `onEvent("motionchange", {isMoving, location: <record>})` |
| `location`, `current_position`, `watch_position` | `onRecord(...)`, `onEvent("location", <record>)` |
| `heartbeat` | `onRecord(heartbeat)`, `onEvent("heartbeat", {location: <record>})` |
| `geofence` | `onRecord(geofence)`, `onEvent("geofence", {identifier, action, location: <record>, extras?})` |
| `tracking_start` / `tracking_stop` | `onRecord(...)`, then `onEvent("enabledchange", {enabled})` when `start()` / `stop()` changed the state (not when a session resumes in a new process) |
| `providerchange` | `onEvent("providerchange", <ProviderState>)` **first**, then `onRecord(providerchange)` (the event carries the provider state, not the record; it is also emitted while tracking is off, then without a record) |
| inserted records | `onRecord(...)` only |

Both methods receive the **application context** and a JSONObject built for this listener alone: a listener that
changes its JSONObject does not change what the next listener receives.

---

## When a listener receives it

Every entry point of the plugin creates the plugin's components with `Components.get(context)`, and that call installs
the manifest listeners before any component can emit a record. So a manifest listener receives the first record of
every process, also when no Activity, no WebView and no JavaScript exist:

| The process runs because of | First records a manifest listener receives |
|---|---|
| The app was launched (the plugin loads in the WebView) | whatever JS starts (`ready()` may restore a session: `tracking_start`, reason `restore`) |
| The device booted (`BOOT_COMPLETED`), `startOnBoot: true` | `tracking_start` (reason `boot`), `motionchange`, then heartbeats |
| The device booted, `startOnBoot: false` | `tracking_stop` (reason `reboot`) |
| The app was updated (`MY_PACKAGE_REPLACED`) | `tracking_start` or `tracking_stop` (reason `package_replaced`) |
| The heartbeat alarm fired after the process was killed | `heartbeat`, then `tracking_start` (reason `restore`), or `tracking_stop` (`permission_denied`, `service_start_failed`) |
| Android restarted the foreground service (`START_STICKY`) | `tracking_start` (reason `restore`), then records as usual |
| A GMS / HMS / Android geofence or activity `PendingIntent` | `geofence` records, `motionchange` |
| Your own code called `LocationTrackingNative` | whatever the call produces |

In the field-force example, PremiseMonitor relies on this: after a `kill -9` or a reboot inside the premise, its
listener receives the heartbeats of the new process before any JavaScript runs (end-to-end scenarios F-09 and F-10).

---

## Threads and order

- All listeners and all `NativeCallback`s run on **one** background thread named `LT-native`
  (`LocationTrackingNative.THREAD_NAME`), one call at a time.
- The plugin emits on its own threads (the engine, receivers, I/O). At the moment of emission it only puts a task on
  the `LT-native` queue; the JSON is built on the `LT-native` thread. A slow listener therefore never delays tracking,
  uploads or the heartbeat.
- The order is global: listener calls run in the order the plugin emitted the records and events, for all listeners
  together. A record's `onRecord` always comes before the events that carry that record (see the table above).
- A `NativeCallback` is queued on the same thread when its call completes. Calls run concurrently on the plugin's
  scope, and a call that waits (for example `start()`) can complete after a later call, so callbacks arrive in
  completion order, not in call order. Chain the next call inside the callback when the order matters.
- For one record or event, the listeners are called in registration order: the manifest listeners (in the order
  described above), then the programmatic listeners in the order of their `addListener` calls.
- Every exception a listener or callback throws (also an `Error`, such as `NoClassDefFoundError`) is caught and written
  to the plugin log with the listener's class name. The other listeners still receive the call, and the listener that
  threw receives the next call as usual.
- The queue has no size limit, so nothing is dropped. A listener that blocks the thread (for example a network call)
  delays every later delivery to every listener and every callback, and the waiting tasks stay in memory. When 1000
  tasks (and every further 1000) are waiting, the plugin writes a warning to its log.

---

## Calling the plugin from Kotlin

`LocationTrackingNative` has one method for each JS method a companion needs. Each takes the same JSON as the JS
method and delivers the JS result shape to a `NativeCallback` on the `LT-native` thread:

| Method | Arguments | Result on success |
|---|---|---|
| `ready(context, config?, reset, cb)` | `config`: a JS `Config` object or null | `State` JSON (as JS `ready()`) |
| `setConfig(context, config, cb)` | a partial `Config` | `State` JSON |
| `start(context, cb)` / `startGeofences(context, cb)` / `stop(context, cb)` | | `State` JSON |
| `changePace(context, isMoving, cb)` | | `Unit` |
| `getState(context, cb)` | | `State` JSON |
| `getHeartbeatStatus(context, cb)` | | `HeartbeatStatus` JSON |
| `sync(context, cb)` | | `JSONArray` of the uploaded records (the JS `locations` array) |
| `insertLocation(context, location, cb)` | an `InsertLocationInput` object (`{coords, timestamp?, event?, is_moving?, extras?}`) | the new record's `uuid` |
| `addGeofence(context, geofence, cb)` | a JS `Geofence` object | `Unit` |
| `removeGeofence(context, identifier, cb)` | | `Unit` |
| `getGeofences(context, cb)` | | `JSONArray` of JS `Geofence` objects |

```kotlin
val premise = JSONObject()
    .put("identifier", "premise:hq")
    .put("latitude", 24.7136)
    .put("longitude", 46.6753)
    .put("radius", 150)
    .put("notifyOnEntry", true)
    .put("notifyOnExit", true)

LocationTrackingNative.addGeofence(context, premise) { result ->
    result.onSuccess { audit("monitoring_started") }
        .onFailure { error ->
            val code = (error as? TrackingException)?.code   // e.g. ErrorCode.TOO_MANY_GEOFENCES
            audit("monitoring_failed", detail = "$code ${error.message}")
        }
}
```

Behavior:
- Each call runs the same code as the JS method (the plugin's bridge handlers, on the plugin's coroutine scope), so
  validation, results and side effects are the same. `insertLocation` also delivers the new record to every
  `onRecord`.
- A failure is `Result.failure(TrackingException)`. `TrackingException.code` is the JS error code
  (`com.brickssoft.locationtracking.core.ErrorCode`: `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `TOO_MANY_GEOFENCES`,
  `NO_URL`, ...; see [Error codes in the README](../README.md#error-codes)). Any other exception is wrapped in a
  `TrackingException` with code `INTERNAL` and the original exception as its `cause`.
- The callback is invoked exactly once, also when the plugin's scope was shut down (then with `INTERNAL`).
- Native calls are **not** subject to the JS `NOT_READY` rule and do **not** mark the plugin as ready for JS: JS must
  still call `ready()` before its other methods. A native caller that needs a configuration (for example in a process
  started by a broadcast) calls `ready()` first. As in JS, `reset = true` replaces the stored configuration with
  `config` applied to the defaults, and `ready(context, null, false, cb)` keeps the stored configuration.
- The JSON arguments are copied when the method is called; you may change or reuse your JSONObject afterwards.
- The first call in a process creates the plugin's components (and so the manifest listeners) on the calling thread.
- `NativeCallback` takes a Kotlin `Result`. On the JVM its method is `onResult(Object)` and receives either the value
  or a `kotlin.Result.Failure`, which is awkward from Java: write companion code in Kotlin.

---

## R8 and ProGuard

The plugin's consumer rules (`android/consumer-rules.pro`) are applied to every app that uses it:

```proguard
-keep class com.brickssoft.locationtracking.api.** { public *; }
-keep class com.brickssoft.locationtracking.core.TrackingException { public *; }
-keep class com.brickssoft.locationtracking.core.ErrorCode { public *; }
-keep class * implements com.brickssoft.locationtracking.api.LocationTrackingListener {
    public <init>();
    public void onRecord(android.content.Context, org.json.JSONObject);
    public void onEvent(android.content.Context, java.lang.String, org.json.JSONObject);
}
```

The second rule keeps the class name and the no-arg constructor of every listener, so a minified release app still
finds the class named in the manifest. You do not need your own keep rule for a listener class.

---

## Pitfalls

- **Keep constructors cheap.** A manifest listener's constructor runs inside `Components.get()`, often on the main
  thread while a receiver or the tracking service starts. Android ends a broadcast receiver that runs too long, and
  crashes the app with `ForegroundServiceDidNotStartInTimeException` when a started foreground service does not call
  `startForeground()` in time; a slow constructor delays both. Do not open databases, read files or start threads in
  the constructor; do it on the first callback or lazily. Do not call `LocationTrackingNative` from the constructor:
  the listeners are not connected yet at that moment, so the records that call produces could be missed.
- **Do I/O on your own executor.** `onRecord` and `onEvent` share one thread with every other listener and every
  callback. Append to memory or a local file quickly, and upload on your own executor (PremiseMonitor queues its
  uploads and retries failed ones after 30 seconds).
- **Do not wait for a `NativeCallback` on the `LT-native` thread.** The callback runs on that same thread, so waiting
  for it inside a listener or another callback blocks forever. Wait on your own thread, or chain the next call inside
  the callback.
- **Do not wait for a `NativeCallback` on the main thread for long.** The call itself runs on the plugin's scope, but
  some calls (`start()`, `ready()` with a restore) take seconds.
- **Expect records from before your JavaScript runs.** In a process started by boot, the heartbeat alarm or
  `START_STICKY`, your listener receives records and events while your plugin's JS side (and any in-memory state it
  sets up) does not exist. Keep the state the listener needs in persistent storage (PremiseMonitor keeps the premise
  and the "inside" flag in SharedPreferences).
- **Starting your own foreground service from a listener** is allowed only while Android allows the app to start
  foreground services from the background (for example while the tracking service is in the foreground, or within the
  time Android grants after a geofence transition). Catch the exception Android throws and record it; never let it
  escape (it is caught and logged by the plugin, but your audit would miss it).
- **Records arrive once per process, not once ever.** A record is delivered when it is created. The plugin does not
  deliver it again after a crash of your listener or a restart of the process; keep your own log if you need replays.
