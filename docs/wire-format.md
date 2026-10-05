# Wire format: what your server receives

This guide is for the developers of the server that receives the uploads of
`@bricks-soft/capacitor-location-tracking`. It covers every record type, the request body shapes, templates, when
uploads happen, retries, response handling and JWT refresh.

- [How records flow](#how-records-flow)
- [The request](#the-request)
- [Record fields](#record-fields)
- [Record variants](#record-variants)
- [Body shapes: single, batch, `rootProperty`, `params`](#body-shapes)
- [Templates](#templates)
- [When uploads happen](#when-uploads-happen), including [live location with `syncInterval`](#live-location-with-syncinterval)
- [Response handling and retries](#response-handling-and-retries)
- [Time fields: `timestamp`, `recorded_at`, `sent_at`](#time-fields)
- [JWT refresh](#jwt-refresh)
- [Server checklist](#server-checklist)

## How records flow

1. The plugin creates a **record**: a location fix, a motion change, a heartbeat, a geofence transition, or an audit
   event. The app can also add records with `insertLocation()`.
2. The record is written to an on-device SQLite queue **first**.
3. An uploader sends queued records to `http.url`, oldest first. A record is deleted from the queue only after the
   server has answered `2xx`.
4. JavaScript listeners get the same record shape, without `sent_at`. Native listeners of the
   [companion API](../README.md#companion-plugins-native-api) get every queued record in this shape too
   (`LocationTrackingListener.onRecord`), when it is queued, not when it is uploaded.

Nothing is uploaded while `http.url` is not set (an invalid `http.url`, one that is not an `http(s)` URL, counts as
not set and is logged as an error). Records then just stay queued until they are pruned.

**Pruning** runs when the queue is first used in a process and then after every 50 inserts. It applies to **every**
record type, heartbeats and audit records included:

- records whose `recorded_at` is more than `persistence.maxDaysToPersist` days old (default 7, minimum 1) are deleted,
  even if they were never uploaded;
- if `persistence.maxRecordsToPersist` is above 0, only the newest that many records (by `recorded_at`) are kept.
  Because pruning runs every 50 inserts, the queue can exceed the limit by up to 49 records in between.

## The request

| Item | Value |
|---|---|
| Method | `http.method`: `POST` (default), `PUT` or `PATCH` |
| URL | `http.url` |
| Timeout | `http.timeout` ms (default 60000; a value of 0 or less means the default). It is the call, read and write timeout. |
| Body | JSON (UTF-8), see [Body shapes](#body-shapes) |

Headers are applied in this order:

1. `Content-Type: application/json; charset=utf-8`;
2. every entry of `http.headers`. A header with the same name (ignoring case) replaces an earlier one, so
   `http.headers` can override `Content-Type`. Invalid header names or values are skipped, with a warning in the log;
3. `Authorization: Bearer <accessToken>`, when `http.authorization` is configured, an access token is available, and
   `http.headers` doesn't already contain an `Authorization` header. An `Authorization` entry in `http.headers` turns
   the JWT handling off completely (no bearer token, no refresh).

Timestamps are ISO-8601 in UTC with milliseconds, for example `2026-09-26T10:15:30.123Z`
(`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).

## Record fields

Every record has all of these keys; values that are unknown are `null`. The exceptions are the optional `extras`,
`provider` (missing only when the plugin could not read the provider state, and on records queued by a plugin version
before 8.0.0), and the event-specific keys `geofence`, `reason` and `heartbeat`, which appear only when they apply. The
examples below show `provider` only on `providerchange`.

| Key | Type | Meaning |
|---|---|---|
| `uuid` | string | Unique record id (UUID v4). **Deduplicate on it**: a record can arrive more than once. |
| `event` | string | Record type: `location`, `motionchange`, `current_position`, `watch_position`, `heartbeat`, `geofence`, `tracking_start`, `tracking_stop` or `providerchange`. |
| `timestamp` | string \| null | Time of the location **fix**. For heartbeat and audit records this is the time of the last known fix, so it can be old. `null` if no location has ever been known. |
| `recorded_at` | string | When the record was **created** on the device. |
| `sent_at` | string | When the request was built. Only in HTTP bodies; every record of one batch has the same value. A retry is a new request with a new `sent_at`. |
| `elapsed_realtime_ms` | number | Milliseconds since the device booted, when the record was created. It is monotonic, so it doesn't change when the user changes the clock. |
| `boot_count` | number | The device's boot counter. It increases with every reboot. `-1` if unavailable. |
| `is_moving` | boolean | Motion state when the record was created. |
| `odometer` | number | Meters travelled since the odometer was last reset. |
| `mock` | boolean | `true` if the fix came from a mock-location app. `false` when there is no fix. |
| `coords` | object \| null | The fix (see below). `null` if no location has ever been known. |
| `activity` | object | `{ "type": "still" \| "on_foot" \| "walking" \| "running" \| "on_bicycle" \| "in_vehicle" \| "unknown", "confidence": 0-100 }`. |
| `battery` | object | `{ "level": 0..1 (or -1 if unknown), "is_charging": boolean }`. |
| `backend` | string \| null | Location backend in use: `gms`, `hms` or `android`. |
| `extras` | object | Optional. `persistence.extras`, merged with the extras passed to the call that created the record (for example `getCurrentPosition({ extras })`); the call's keys win. Absent when both are empty. |
| `geofence` | object | Only for `geofence`: `{ "identifier", "action": "ENTER" \| "EXIT" \| "DWELL", "extras"? }`. |
| `provider` | object | The location provider state when the record was created, read at that moment ([keys](#providerchange)). On `providerchange` it is the new state. A server can keep the newest by `recorded_at`. |
| `reason` | string | Only for `tracking_start` and `tracking_stop` (see the reason tables below). |
| `heartbeat` | object | Only for `heartbeat`, and optional: how heartbeats are scheduled (see [`heartbeat`](#heartbeat)). |

`coords`:

| Key | Type | Meaning |
|---|---|---|
| `latitude`, `longitude` | number | WGS84 degrees. |
| `accuracy` | number | Horizontal accuracy radius in meters. |
| `altitude` | number \| null | Meters above the WGS84 ellipsoid. |
| `altitude_accuracy` | number \| null | Vertical accuracy in meters. |
| `speed` | number \| null | m/s. |
| `speed_accuracy` | number \| null | m/s. |
| `heading` | number \| null | Degrees clockwise from true north (0–360). |
| `heading_accuracy` | number \| null | Degrees. |

## Record variants

The examples below show the **record object only**. How it is wrapped and merged with `params` is explained in
[Body shapes](#body-shapes). Unless noted, the examples come from one phone (boot 42, GMS backend) whose app sets
`persistence.extras` to `{ "driver_id": 7 }`, so every record carries those extras.

### `location`

A fix recorded while tracking. `motionchange`, `current_position` and `watch_position` records have the same shape,
and differ only as described after this example.

```json
{
  "uuid": "0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11",
  "event": "location",
  "timestamp": "2026-09-26T10:15:30.123Z",
  "recorded_at": "2026-09-26T10:15:30.456Z",
  "sent_at": "2026-09-26T10:15:30.912Z",
  "elapsed_realtime_ms": 86400123,
  "boot_count": 42,
  "is_moving": true,
  "odometer": 1532.4,
  "mock": false,
  "coords": {
    "latitude": 24.7136,
    "longitude": 46.6753,
    "accuracy": 5.2,
    "altitude": 612.3,
    "altitude_accuracy": 3.0,
    "speed": 13.4,
    "speed_accuracy": 0.8,
    "heading": 271.5,
    "heading_accuracy": 5.0
  },
  "activity": { "type": "in_vehicle", "confidence": 92 },
  "battery": { "level": 0.81, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 }
}
```

### `motionchange`

The fix at the moment the device switched between moving and stationary. `is_moving` is the **new** state.

```json
{
  "uuid": "7a3f0c55-0b0e-4a4e-8f55-5d8a1a2b9c01",
  "event": "motionchange",
  "timestamp": "2026-09-26T10:21:02.000Z",
  "recorded_at": "2026-09-26T10:21:02.118Z",
  "sent_at": "2026-09-26T10:21:02.540Z",
  "elapsed_realtime_ms": 86731785,
  "boot_count": 42,
  "is_moving": false,
  "odometer": 4210.9,
  "mock": false,
  "coords": {
    "latitude": 24.7302,
    "longitude": 46.6581,
    "accuracy": 8.0,
    "altitude": 609.8,
    "altitude_accuracy": 4.0,
    "speed": 0.0,
    "speed_accuracy": 0.5,
    "heading": null,
    "heading_accuracy": null
  },
  "activity": { "type": "still", "confidence": 100 },
  "battery": { "level": 0.8, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 }
}
```

### `current_position` and `watch_position`

These are fixes requested by the app with `getCurrentPosition()` (persisted by default) or `watchPosition()` with
`persist: true`. They have the same shape as `location`, with `"event": "current_position"` or
`"event": "watch_position"`. Non-persisted positions are never uploaded. The app can request them while tracking is
off, so they can arrive outside a `tracking_start` … `tracking_stop` period.

### Records added with `insertLocation()`

The app can add its own records with `insertLocation()`. They have the default shape, with the `event` the app gave
(`location` if none), its `coords` (`accuracy` 0 if not given), `timestamp` (the insert time if not given) and
`is_moving` (the current state if not given), and the current `odometer`, `activity`, `battery` and `backend`. Their
`extras` are `persistence.extras` merged with the given ones. They are queued and uploaded like any other
record, but they don't count as tracking activity: they don't restart the heartbeat window.

### `heartbeat`

This is an audit record, created while tracking is on when no other record was created for `heartbeat.minInterval`
seconds (default 180). See [heartbeat.md](heartbeat.md).

- `coords` and `timestamp` are the **last known** location. `timestamp` is when that fix was acquired, so it can be
  much older than `recorded_at`. While the phone is stationary, GPS is off and the heartbeat carries the fix where
  the phone stopped (the *anchor*). Fixes that arrive while stationary (from other apps, or from the plugin's
  low-power fallback) do not change it, except when one of them becomes the anchor: the first fix when none was
  known, a current fix that replaces an anchor that was already more than 10 minutes old, or a fix with a better
  accuracy than the anchor. A record with its own fix (a `geofence` record, or a `current_position` /
  `watch_position` record) also sets it; later heartbeats carry that fix (details in
  [heartbeat.md](heartbeat.md#stationary-gps-off-heartbeats-continue)). In the example below, the phone has been
  stationary for 23 minutes.
- `recorded_at` is when the heartbeat was created, and `sent_at` when it was uploaded.
- `is_moving`, `odometer`, `activity` and `battery` are current values.
- `heartbeat` (optional) says how the plugin schedules the **next** heartbeat on this phone, so the server knows which
  gap to expect. Plugin versions before round 2 don't send it, and only `heartbeat` records have it.

  | Key | Type | Meaning |
  |---|---|---|
  | `strategy` | `'exact'` \| `'listener_with_backup'` \| `'idle_paced'` | How the next heartbeat is scheduled (see [heartbeat.md](heartbeat.md#how-it-is-scheduled)). `idle_paced` means the phone is in Doze without the battery exemption, and heartbeats are about 9 minutes apart. Never `disabled`. |
  | `min_interval` | number (s) | `heartbeat.minInterval` when the heartbeat was created. |
  | `max_interval` | number (s) | `heartbeat.maxInterval` when the heartbeat was created. |
  | `next_at` | string \| null | When the next heartbeat will be due if no other record is created (ISO-8601 UTC): `recorded_at + min_interval`, or, for `idle_paced`, the time of the backup alarm (at least 9 minutes after the previous one fired). `null` if unknown. Any other record created before then moves the next heartbeat later. |
  | `battery_exempt` | boolean | The app was exempt from battery optimization when the heartbeat was created. |
  | `device_idle` | boolean | The phone was in deep Doze when the heartbeat was created. |

  How to use it to tell an expected gap from a failure, and to show a device as online:
  [heartbeat.md, Heartbeat metadata](heartbeat.md#heartbeat-metadata). In short: with `battery_exempt: true` the next
  record should arrive within `max_interval`; with `battery_exempt: false` and `device_idle: true`, a gap of about 9
  (up to 11) minutes is normal.

```json
{
  "uuid": "5b1e2c9a-2f0d-4f4b-a6a1-0c3d9e8f7b22",
  "event": "heartbeat",
  "timestamp": "2026-09-26T10:21:02.000Z",
  "recorded_at": "2026-09-26T10:44:05.310Z",
  "sent_at": "2026-09-26T10:44:05.702Z",
  "elapsed_realtime_ms": 88114977,
  "boot_count": 42,
  "is_moving": false,
  "odometer": 4210.9,
  "mock": false,
  "coords": {
    "latitude": 24.7302,
    "longitude": 46.6581,
    "accuracy": 8.0,
    "altitude": 609.8,
    "altitude_accuracy": 4.0,
    "speed": 0.0,
    "speed_accuracy": 0.5,
    "heading": null,
    "heading_accuracy": null
  },
  "activity": { "type": "still", "confidence": 100 },
  "battery": { "level": 0.77, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 },
  "heartbeat": {
    "strategy": "exact",
    "min_interval": 180,
    "max_interval": 300,
    "next_at": "2026-09-26T10:47:05.310Z",
    "battery_exempt": true,
    "device_idle": false
  }
}
```

If no location has ever been known, `timestamp` and `coords` are `null`. This example comes from another phone
(HMS backend), a new install that has not had a fix yet:

```json
{
  "uuid": "c0e5d1f2-8a7b-4c3d-9e2f-1a0b3c4d5e66",
  "event": "heartbeat",
  "timestamp": null,
  "recorded_at": "2026-09-26T08:03:00.050Z",
  "sent_at": "2026-09-26T08:03:00.431Z",
  "elapsed_realtime_ms": 1260050,
  "boot_count": 3,
  "is_moving": false,
  "odometer": 0,
  "mock": false,
  "coords": null,
  "activity": { "type": "unknown", "confidence": 0 },
  "battery": { "level": 0.93, "is_charging": true },
  "backend": "hms"
}
```

### `geofence`

A geofence transition. `coords` is the fix that triggered it, and `geofence.extras` are the extras given when the
geofence was added.

```json
{
  "uuid": "e4c2a7b1-3d5f-4e6a-8b9c-0d1e2f3a4b55",
  "event": "geofence",
  "timestamp": "2026-09-26T17:02:11.500Z",
  "recorded_at": "2026-09-26T17:02:12.004Z",
  "sent_at": "2026-09-26T17:02:12.388Z",
  "elapsed_realtime_ms": 110801671,
  "boot_count": 42,
  "is_moving": true,
  "odometer": 18754.2,
  "mock": false,
  "coords": {
    "latitude": 24.6912,
    "longitude": 46.7004,
    "accuracy": 11.0,
    "altitude": null,
    "altitude_accuracy": null,
    "speed": 1.4,
    "speed_accuracy": null,
    "heading": 88.0,
    "heading_accuracy": null
  },
  "activity": { "type": "walking", "confidence": 83 },
  "battery": { "level": 0.52, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 },
  "geofence": { "identifier": "home", "action": "ENTER", "extras": { "site_id": 12 } }
}
```

### `tracking_start`

An audit record, created when tracking starts or resumes. It carries the last known coords, or `null`: right after
install it often has no coords, even on an emulator with a default location, because the first fix arrives a few
seconds after the start. Calling
`start()` while `startGeofences()` runs (or the reverse) switches the mode and creates another `tracking_start`,
without a `tracking_stop` in between.

```json
{
  "uuid": "a9d8c7b6-5e4f-4a3b-9c2d-1e0f9a8b7c66",
  "event": "tracking_start",
  "timestamp": "2026-09-26T07:55:40.000Z",
  "recorded_at": "2026-09-26T08:00:00.120Z",
  "sent_at": "2026-09-26T08:00:00.498Z",
  "elapsed_realtime_ms": 78269787,
  "boot_count": 42,
  "is_moving": false,
  "odometer": 0,
  "mock": false,
  "coords": {
    "latitude": 24.7136,
    "longitude": 46.6753,
    "accuracy": 15.0,
    "altitude": null,
    "altitude_accuracy": null,
    "speed": null,
    "speed_accuracy": null,
    "heading": null,
    "heading_accuracy": null
  },
  "activity": { "type": "unknown", "confidence": 0 },
  "battery": { "level": 0.93, "is_charging": true },
  "backend": "gms",
  "extras": { "driver_id": 7 },
  "reason": "start"
}
```

| `reason` | Meaning |
|---|---|
| `start` | The app called `start()`. |
| `start_geofences` | The app called `startGeofences()` (geofences-only mode). |
| `boot` | Tracking resumed after the phone rebooted (`app.startOnBoot: true`). Expect a gap before it, covering the time the phone was off. The plugin handles one boot broadcast per boot: it ignores a boot broadcast when the phone's boot counter (`Settings.Global.BOOT_COUNT`) is the same as for the last boot broadcast it handled, or the same as when it last started the tracking service. So a repeated or fake `QUICKBOOT_POWERON` broadcast without a real reboot creates no second `tracking_start`. |
| `restore` | Tracking was still on, but not running in the app's process, and the plugin resumed it: Android restarted the killed service, a heartbeat alarm or an activity update woke the app, or the app called `ready()` after it was reopened (for example after a force-stop). Expect a gap before it. |
| `package_replaced` | Tracking resumed after the app was updated (`app.startOnBoot: true`). |
| `resume_notification` | The user tapped the resume notification (`notification.resume`) after Android had refused to restore tracking from the background; the `tracking_stop` with reason `service_start_failed` comes before it. The session continues with its original `stopAfterElapsedMinutes` deadline. |

When Android doesn't let the plugin resume, the server gets a `tracking_stop` with reason `service_start_failed` or
`permission_denied` instead of the `boot`, `restore` or `package_replaced` start, or right after it (see below).

### `tracking_stop`

An audit record, created when tracking stops. It carries the last known coords, or `null`. After a `tracking_stop`,
no records are expected until the next `tracking_start`.

```json
{
  "uuid": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d77",
  "event": "tracking_stop",
  "timestamp": "2026-09-26T18:30:12.000Z",
  "recorded_at": "2026-09-26T18:31:00.250Z",
  "sent_at": "2026-09-26T18:31:00.611Z",
  "elapsed_realtime_ms": 116129917,
  "boot_count": 42,
  "is_moving": false,
  "odometer": 23120.7,
  "mock": false,
  "coords": {
    "latitude": 24.7136,
    "longitude": 46.6753,
    "accuracy": 6.0,
    "altitude": 611.0,
    "altitude_accuracy": 3.0,
    "speed": 0.0,
    "speed_accuracy": 0.3,
    "heading": null,
    "heading_accuracy": null
  },
  "activity": { "type": "still", "confidence": 97 },
  "battery": { "level": 0.41, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 },
  "reason": "stop"
}
```

| `reason` | Meaning |
|---|---|
| `stop` | The app called `stop()`. |
| `stop_on_stationary` | The device became stationary with `geolocation.stopOnStationary: true`. |
| `stop_after_elapsed` | `geolocation.stopAfterElapsedMinutes` elapsed. If the deadline passed while tracking was not running (the phone was off, or the app was killed or updated), the stop is recorded when the plugin next runs (after the reboot, the update or the app launch), with no `tracking_start` before it, and tracking does not resume. |
| `terminate` | The user swiped the app away with `app.stopOnTerminate: true`. |
| `permission_denied` | Location permission was gone when the plugin tried to resume tracking (the user revoked it). This includes a restart of the killed service by Android itself that fails while location permission is no longer granted (on Android 14+ such a restart fails when the service enters the foreground). |
| `service_start_failed` | Android refused or aborted the tracking foreground service while location permission was granted, in one of these cases: (1) `start()` / `startGeofences()` could not start it (the call then rejects with `PERMISSION_DENIED`); (2) the service failed after tracking had been started; (3) the plugin tried to restart it from the background (after the process was killed, from a heartbeat alarm, after a reboot or an update). On Android 12+, only a battery-optimization-exempt app (the exemption is itself an exemption from background-start restrictions), `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` may start it from the background. On Android 14+ the `location` service type also needs "Allow all the time" location or a visible app; the plugin checks this itself before it asks Android, and records this reason without trying when all of these are true: the service is not in the foreground yet, "Allow all the time" location is not granted, Android rates the app's process below "visible" (a process that runs another foreground service counts as visible), the app's current activity is not started, and no activity of the app was started, stopped or destroyed in the last 15 s. Tracking stays off until the app calls `start()` again. |
| `reboot` | Tracking was on before the phone restarted, and `app.startOnBoot` is `false`, so it is not resumed. Recorded after the reboot (`recorded_at` is after the boot). |
| `package_replaced` | Tracking was on before the app was updated, and `app.startOnBoot` is `false`, so it is not resumed. |

A `tracking_stop` is written before the service stops. If the phone is offline at that moment, the record stays
queued and arrives later, with a late `sent_at`. There is **no** `tracking_stop` when the phone is switched off, the
app is force-stopped, or the process is killed (a reboot or an app update with `app.startOnBoot: false` records one
afterwards, with reason `reboot` or `package_replaced`). Those show up as a gap, which is the correct audit outcome (see
[heartbeat.md](heartbeat.md#server-side-audit)).

### `providerchange`

An audit record, created while tracking is on when the location provider state changes: location services on or off,
GPS or network provider toggled, permission level or accuracy changed, or backend changed. It carries the last known
coords, or `null`.

- The plugin compares the current state with the last one it saved. It checks when the system reports a provider or
  location-mode change (while tracking has run in this process), when the app comes to the foreground, on every
  heartbeat, when tracking starts or resumes, and when the backend changes.
- System broadcasts are debounced by 1 s, so one toggle creates one record, with the final state.
- The very first observation (for example after install) is saved silently, without a record.
- Changes seen while tracking is off are saved without a record. Changes that happened while no check ran are reported
  by the next check.
- When the change is a permission revocation, Android kills the app, so the record is created the next time the
  plugin runs.

```json
{
  "uuid": "d4e5f6a7-b8c9-4dae-8f01-23456789ab88",
  "event": "providerchange",
  "timestamp": "2026-09-26T12:40:03.000Z",
  "recorded_at": "2026-09-26T12:41:15.870Z",
  "sent_at": "2026-09-26T12:41:16.233Z",
  "elapsed_realtime_ms": 95145537,
  "boot_count": 42,
  "is_moving": false,
  "odometer": 9876.5,
  "mock": false,
  "coords": {
    "latitude": 24.7011,
    "longitude": 46.6822,
    "accuracy": 9.0,
    "altitude": null,
    "altitude_accuracy": null,
    "speed": null,
    "speed_accuracy": null,
    "heading": null,
    "heading_accuracy": null
  },
  "activity": { "type": "still", "confidence": 88 },
  "battery": { "level": 0.66, "is_charging": false },
  "backend": "gms",
  "extras": { "driver_id": 7 },
  "provider": {
    "enabled": false,
    "gps": false,
    "network": true,
    "permission": "always",
    "accuracy": "precise",
    "backend": "gms"
  }
}
```

| `provider` key | Values | Meaning |
|---|---|---|
| `enabled` | boolean | Location services are usable (the device-wide switch is on). |
| `gps` | boolean | The GPS provider is enabled. |
| `network` | boolean | The network (Wi-Fi/cell) provider is enabled. |
| `permission` | `always` \| `when_in_use` \| `denied` | App location permission. `when_in_use` means "Allow only while using the app". |
| `accuracy` | `precise` \| `approximate` \| `none` | Location accuracy granted to the app (Android 12+ lets users grant approximate only). |
| `backend` | `gms` \| `hms` \| `android` | Location backend in use. |

## Body shapes

The shape depends on `http.batchSync`, `http.rootProperty` (default `"location"`) and `http.params` (default `{}`).

### Single record (default)

The record goes under `rootProperty`, and every `params` key is merged into the **root**:

```json
{
  "location": { "uuid": "0d6c6a1e-...", "event": "location", "...": "..." },
  "device_id": "abc",
  "tenant": "acme"
}
```

With `http.params = { "device_id": "abc", "tenant": "acme" }`. A custom `rootProperty`, such as `"data"`, just renames
the key: `{ "data": { ... }, "device_id": "abc", "tenant": "acme" }`.

`params` never overwrite what the body already has: a `params` key equal to `rootProperty` (or, with
`rootProperty: "."`, to a record key) is ignored. `params` that are not a JSON object are ignored, with a warning in
the log.

### Batch (`batchSync: true`)

Up to `maxBatchSize` (default 100) records per request, **oldest first**, in an array under `rootProperty`, with
`params` merged into the root:

```json
{
  "location": [
    { "uuid": "0d6c6a1e-...", "event": "location", "recorded_at": "2026-09-26T10:15:30.456Z", "...": "..." },
    { "uuid": "5b1e2c9a-...", "event": "heartbeat", "recorded_at": "2026-09-26T10:18:31.002Z", "...": "..." }
  ],
  "device_id": "abc"
}
```

A batch succeeds or fails as a whole: a `2xx` deletes every record in it, and anything else keeps them all.

### `rootProperty: "."` (no wrapping)

An empty or blank `rootProperty` behaves the same as `"."`.

- **Single record:** the record's fields are merged into the root, together with `params`:
  `{ "uuid": "...", "event": "location", ..., "device_id": "abc" }`. The record's keys win: a `params` key that
  collides with one of them is dropped.
- **Batch:** a bare JSON array, `[ {...}, {...} ]`. **`params` are ignored**, because an array has no root object to
  merge them into. Use headers, or `persistence.extras` (which is copied into every record), for per-device data.
- A template that renders a JSON array is sent bare in the single-record case too, without `params`.

## Templates

If your server expects a different shape, set `http.locationTemplate` (and optionally `http.geofenceTemplate`, used
for `geofence` records; it falls back to `locationTemplate`). A blank template counts as not set. A template is JSON
text with `<%= name %>` placeholders, and its rendered value replaces the default record object. Wrapping in
`rootProperty`, batching and `params` then work exactly as for the default shape.

Substitution rules:

- `<%= name %>` may have whitespace inside the delimiters (`<%=name%>` works too).
- Each value is inserted as a **raw JSON literal**:
  - numbers and booleans are inserted bare (a number that is not finite becomes `null`);
  - `null` is inserted as `null`;
  - strings are JSON-escaped (quotes, backslashes and control characters are safe) but inserted **without quotes**, so
    you write the quotes yourself: `"<%= timestamp %>"`;
  - `extras` is inserted as JSON object text (`{}` when the record has no extras), so write it bare:
    `"extras": <%= extras %>`.
  - `record` is inserted as the whole [default record object](#record-fields), `sent_at` included, so write it bare:
    `"record": <%= record %>`. It carries every field, including the ones without a placeholder of their own
    (`provider.accuracy`, `provider.backend`, `heartbeat`) and any added later, and `coords` and `timestamp` stay
    `null` when the record has no fix.
  - `provider` is inserted as the record's [`provider` object](#providerchange) (`enabled`, `gps`, `network`,
    `permission`, `accuracy`, `backend`), or `null` when the record has none, so write it bare:
    `"provider": <%= provider %>`.
- A `null` value in a placeholder that is wrapped exactly in quotes, such as `"<%= reason %>"`, replaces the quotes
  too, so the result is JSON `null`, not the string `"null"`. This happens with `timestamp`, `backend`, `reason`,
  `geofence.*`, `provider.*` and the coordinates on records where they don't apply. Inside a longer string (for
  example `"<%= uuid %>/<%= reason %>"`) a `null` value becomes the text `null`.
- A template can't render a `null` object, only `null` values. On a record without a fix (a `heartbeat`,
  `tracking_start`, `tracking_stop` or `providerchange` record before the phone's first fix),
  `"coords":{"latitude":<%= latitude %>,...}` renders as `"coords":{"latitude":null,...}`, and `"<%= timestamp %>"`
  as `null`. The server must accept that shape, or the template can nest `<%= record %>`, whose `coords` is `null`.
- An unknown placeholder is replaced by nothing (so `"<%= nope %>"` becomes `""`), and a warning is logged.
- The rendered text must be a valid JSON **object or array** (it is checked strictly). Otherwise the plugin sends the
  **default shape** for that record instead and logs an error. Test your template with `getLog()`.

Available placeholders:

| Placeholder | JSON type | Placeholder | JSON type |
|---|---|---|---|
| `uuid` | string | `activity.type` | string |
| `event` | string | `activity.confidence` | number |
| `timestamp` | string \| null | `battery.level` | number |
| `recorded_at` | string | `battery.is_charging` | boolean |
| `sent_at` | string | `elapsed_realtime_ms` | number |
| `latitude` | number \| null | `boot_count` | number |
| `longitude` | number \| null | `backend` | string \| null |
| `accuracy` | number \| null | `reason` | string \| null |
| `altitude` | number \| null | `geofence.identifier` | string \| null |
| `altitude_accuracy` | number \| null | `geofence.action` | string \| null |
| `speed` | number \| null | `provider.enabled` | boolean \| null |
| `speed_accuracy` | number \| null | `provider.gps` | boolean \| null |
| `heading` | number \| null | `provider.network` | boolean \| null |
| `heading_accuracy` | number \| null | `provider.permission` | string \| null |
| `is_moving` | boolean | `extras` | object (`{}` if none) |
| `odometer` | number | `record` | object (the default record) |
| `mock` | boolean | `provider` | object \| null |

Example:

```ts
http: {
  url: 'https://api.example.com/v2/positions',
  rootProperty: 'position',
  locationTemplate:
    '{"id":"<%= uuid %>","type":"<%= event %>","reason":"<%= reason %>",' +
    '"lat":<%= latitude %>,"lng":<%= longitude %>,"acc":<%= accuracy %>,' +
    '"fix_at":"<%= timestamp %>","created_at":"<%= recorded_at %>","sent_at":"<%= sent_at %>",' +
    '"uptime_ms":<%= elapsed_realtime_ms %>,"boot":<%= boot_count %>,' +
    '"battery":<%= battery.level %>,"meta":<%= extras %>}',
}
```

This renders a heartbeat as:

```json
{
  "position": {
    "id": "5b1e2c9a-2f0d-4f4b-a6a1-0c3d9e8f7b22",
    "type": "heartbeat",
    "reason": null,
    "lat": 24.7302,
    "lng": 46.6581,
    "acc": 8.0,
    "fix_at": "2026-09-26T10:21:02.000Z",
    "created_at": "2026-09-26T10:44:05.310Z",
    "sent_at": "2026-09-26T10:44:05.702Z",
    "uptime_ms": 88114977,
    "boot": 42,
    "battery": 0.77,
    "meta": { "driver_id": 7 }
  }
}
```

**Always include `uuid`, `event`, `recorded_at` and `sent_at` in a template** (and `reason`, `elapsed_realtime_ms` and
`boot_count` if you audit tracking). Without them the server can't deduplicate records, tell heartbeats from
locations, or detect late delivery.

## When uploads happen

Records fall into two groups:

- **Priority records**: `heartbeat`, `tracking_start`, `tracking_stop` and `providerchange`.
- **Normal records**: `location`, `motionchange`, `current_position`, `watch_position` and `geofence`.

An automatic upload pass runs only when `http.url` is set and the phone reports a usable network: one with internet
access that is not a captive portal, and not blocked for the app by Doze or Data Saver. What the pass sends:

1. **If any priority record is queued**, the pass uploads it right away, ignoring `autoSync`, `autoSyncThreshold`
   and batch waiting. It **drains the whole queue in order** (oldest first; in batches if `batchSync` is on), so
   queued normal records go out together with it. The exception: on a cellular connection with
   `disableAutoSyncOnCellular: true`, only the priority records are sent.
2. **Otherwise (only normal records queued)**, the pass drains the whole queue only if `autoSync` is on (default), the
   connection is not cellular with `disableAutoSyncOnCellular: true`, and the queue is **due**:
   - with `http.syncInterval` `0` (default): the queue holds at least `autoSyncThreshold` records (default `0`, which
     uploads every record right away; `N` waits until at least `N` records are queued);
   - with `http.syncInterval` above `0` while tracking is on: the **oldest** queued normal record (the one with the
     smallest `recorded_at`) is at least `syncInterval` seconds old (now − its `recorded_at`; a negative age, after
     the clock was set back, counts as due), or `autoSyncThreshold` is above `0` and the queue holds at least that
     many records. After a failed automatic upload, normal records wait `syncInterval` seconds before the next try.
     While tracking is off, the `syncInterval` `0` rule above applies. See
     [Live location with `syncInterval`](#live-location-with-syncinterval).

   With `autoSync: false`, normal records wait for the next priority record (for example the next heartbeat) or a
   manual `sync()`.
3. **A pass stops at the first failed request**, and the records behind it wait for the next pass. One exception
   keeps the audit trail flowing: when the **server rejects** a request (any non-`2xx` answer, not a network error) in
   a pass that drains the whole queue, the queued priority records behind it are still sent in the same pass. So a
   record your server keeps rejecting cannot hold back heartbeats and audit records. It still holds back the normal
   records queued after it until the server accepts it or it is pruned.
4. Only one upload runs at a time (automatic passes and `sync()` included), so a record is never in two requests at
   once. Triggers that arrive during a pass cause exactly one more pass.

**When a pass runs.** With `http.syncInterval` `0` (the default) there is no retry timer for failed uploads. A pass
is triggered:

- whenever a record is inserted, including every heartbeat (so each heartbeat also retries the queue) and records
  added with `insertLocation()`;
- when the network comes back, including when Doze or Data Saver stops blocking the app. The plugin watches the
  network only after tracking has been started (or resumed) in the app's process;
- when tracking starts (the `tracking_start` record is itself an insert);
- with `http.syncInterval` above `0`, by the `syncInterval` timer, while tracking is on (see below):
  - a normal record is queued but not yet due: a check is scheduled for `oldest.recorded_at + syncInterval`;
  - an automatic upload failed (after its retries, see [Response handling and retries](#response-handling-and-retries)):
    normal records are tried again `syncInterval` seconds after the last try (measured on the elapsed-time clock), by
    the timer, and not on every insert. These still upload at once: the network coming back,
    a queued priority record, and `sync()`;
  - a queued record that is due but could not be tried (offline, or held back on cellular) gets no timer: the network
    coming back triggers it;
- once the process has used an `http.syncInterval` above `0`: also when tracking is switched on or off, and when
  `http.url`, `autoSync`, `autoSyncThreshold`, `syncInterval` or `disableAutoSyncOnCellular` change;
- when the app calls `sync()`, which uploads the whole queue regardless of `autoSync`, `autoSyncThreshold`,
  `syncInterval`, `disableAutoSyncOnCellular` and the reported connectivity.

While tracking is off, nothing creates records by itself, so queued records wait until the app calls `sync()`, a
record is inserted, or tracking starts again. A record inserted while tracking is off follows the `syncInterval` `0`
rule, also when `syncInterval` is above `0`.

The plugin emits one `http` event (`{ success, status, responseText, uuids }`) **per HTTP request**. A `401` that
triggers a token refresh and a retry therefore produces two `http` events, and an upload that is tried again after a
`5xx`, `429`, timeout or network error produces one per try. The token refresh request itself produces
an `authorization` event, not an `http` event.

### Live location with `syncInterval`

`http.syncInterval` (seconds, default `0` = off) limits how old the newest position on the server can be, while
sending one request per interval instead of one per record. It works only with `autoSync: true`. The field-force
example uses `syncInterval: 300`, `batchSync: true`, `maxBatchSize: 100`.

Timeline of a moving phone with `syncInterval: 300`, `batchSync: true`, `heartbeat.minInterval: 180`, and a
`location` record about every 10 s:

| Time | On the phone | Upload |
|---|---|---|
| 10:00:00 | `motionchange` (`is_moving: true`) is queued. It is the oldest queued normal record. | – |
| 10:00:10 … 10:04:50 | 29 `location` records are queued. | – |
| 10:05:00 | The timer fires: the oldest record is 300 s old. | One request with 30 records. `sent_at − recorded_at` is 300 s for the oldest and 10 s for the newest. |
| 10:05:10 | The next `location` is queued; it is now the oldest. | Next upload at 10:10:10. |
| 10:12:00 | The phone has stopped: `motionchange` (`is_moving: false`) is queued. | – |
| 10:15:00 | No record for 180 s: a `heartbeat` is created. It is a priority record. | At once: the heartbeat and every queued record since 10:10:20, in one pass. |

What this means for the server:

- The newest position the server has is at most about `syncInterval` seconds old while the phone moves (plus the
  time of the upload, and later when the phone is offline). While the phone is stationary, heartbeats arrive every
  `minInterval` seconds and carry the last position.
- For normal records, `sent_at − recorded_at` up to `syncInterval` is expected. More than that means late delivery
  (offline, Doze, server errors).
- Audit records (`heartbeat`, `tracking_start`, `tracking_stop`, `providerchange`) are never held back by
  `syncInterval`.
- `autoSyncThreshold` above `0` uploads earlier when the queue reaches that many records.
- "Oldest" is the queued normal record with the smallest `recorded_at`. After the device clock was set back, records
  created before the change look newer than the ones created after it; they wait at most `syncInterval` after the
  first record created after the change (unless they are the only queued normal records: then they are due at once).
- The timer runs in the tracking process. It holds no wake lock and counts only the time the CPU is awake, so in deep
  sleep or Doze it fires late. While the phone moves, every new location record runs the check against the wall
  clock; while it is stationary, each heartbeat is uploaded at once and takes the queue with it. With the heartbeat
  disabled and no new record, queued records wait until the CPU has been awake long enough.
- While tracking is off, normal records are uploaded as with `syncInterval` `0` (there is no timer then). For
  example, a `getCurrentPosition()` record after the 02:00 stop is uploaded at once with the default
  `autoSyncThreshold`.
- After a failed automatic upload, normal records are tried again once per `syncInterval`, by the timer, not on every
  insert. The network coming back, a queued priority record and `sync()` still upload at once. Priority records keep
  the rule without `syncInterval`: a queued heartbeat that failed is tried again on every insert.
- Held records count against `persistence.maxRecordsToPersist` (unlimited by default). A limit smaller than the number
  of records created in `syncInterval` lets pruning delete held records before they are uploaded.

The server's answers are handled the same way with or without `syncInterval` (next section).

## Response handling and retries

| Server response | What the plugin does |
|---|---|
| `2xx` | Deletes the records in the request from the queue. The response body is not interpreted (it only appears in the app's `http` event); a body that can't be read still counts as success. |
| `401` | If JWT authorization is active, refreshes the access token (see [JWT refresh](#jwt-refresh)) and, if that gives a token, retries the request **once**. At most one refresh is attempted per upload: when a refresh was already attempted for this upload (just before this request because the token was missing or about to expire, or in an earlier try), a `401` does not trigger another one. If the retry is answered `5xx` or `429`, or gets no answer, the next row applies; if it fails otherwise, or there is no new token, the records stay queued. |
| `5xx`, `429`, a timeout or a network error | The request is **tried again in the same upload**, up to 3 times, after waiting 2, then 4, then 8 seconds: at most 4 tries and 14 seconds of waiting per upload, plus the time the requests take (each up to `http.timeout`). Each try is a new request with a new `sent_at` and its own `http` event; a `Retry-After` header is not read. No other upload runs meanwhile. A try answered otherwise ends the upload with that answer. If the last try fails too, the next row applies. |
| Any other status (`3xx` after redirects, other `4xx`), or the last failed try of the row above | The records **stay queued**, and their attempt counter and last-attempt time are updated (once per upload, however many requests it made). They are retried at the next trigger (see above; with `syncInterval` above `0`, normal records wait `syncInterval` seconds after the last try). |

There is no maximum number of attempts. A record leaves the queue only after a `2xx`, or when it is pruned
(`maxDaysToPersist`, `maxRecordsToPersist`). Consequences for your server:

- **Return `2xx` for every record you have stored, or have deliberately decided to drop** (for example, invalid data
  you will never accept). Return non-`2xx` only for transient problems. Otherwise the same request comes back on
  every retry, until it is pruned days later, and the normal records queued behind it wait as long.
- **Be idempotent on `uuid`.** If the server stored a record but the response was lost (timeout, dropped connection),
  the device sends the record again.
- **Don't assume arrival order is creation order.** After an outage, records arrive in a burst, oldest first, and a
  priority record can overtake a normal record your server rejected. Order by `recorded_at`, or by `boot_count` then
  `elapsed_realtime_ms`.
- **Answer quickly.** The plugin keeps the CPU awake only while it creates and queues a heartbeat (at most 60 s); the
  upload itself holds no wake lock. On a sleeping phone, Android may suspend the app before a slow answer arrives.

## Time fields

| Field | Clock | Tells you |
|---|---|---|
| `timestamp` | Location provider (fix time) | How fresh the position is. For heartbeats it can be old: the device is stationary, or has no new fix. |
| `recorded_at` | Device wall clock | When the record was created. Use it for gap detection. |
| `sent_at` | Device wall clock | When the request was built. `sent_at - recorded_at` is how long the record waited in the queue. Up to `http.syncInterval` is expected for normal records; more than that (or more than a few seconds for audit records) means late delivery (offline, Doze, server errors). |
| `elapsed_realtime_ms` | Monotonic, since boot | The real time between two records with the same `boot_count`, even if the user changed the wall clock. |
| `boot_count` | Boot counter | A change means the phone rebooted between two records (`elapsed_realtime_ms` restarted from 0). |

Comparing `sent_at` with your server's receive time tells you roughly how far the device's clock is off. A healthy
upload arrives within a few seconds of `sent_at`. See [heartbeat.md](heartbeat.md#server-side-audit) for how to use
these fields in an audit.

## JWT refresh

Configure it in `http.authorization`:

```ts
http: {
  url: 'https://api.example.com/locations',
  authorization: {
    strategy: 'JWT',
    accessToken: 'eyJhbGciOi...',
    refreshToken: 'def50200a1b2...',
    refreshUrl: 'https://api.example.com/oauth/token',
    refreshPayload: { grant_type: 'refresh_token', refresh_token: '{refreshToken}' },
    refreshHeaders: { 'X-Client': 'mobile' },
    refreshPayloadEncoding: 'json', // or 'form'
    expires: 1790000000000,         // access-token expiry, epoch ms; -1 = unknown
  },
}
```

The JWT handling is active when `http.authorization` is set and `http.headers` has no `Authorization` header of its
own. A refresh needs `refreshUrl`; without it the plugin only sends `accessToken` as it is.

**When.** The plugin refreshes the token:

- before a request, if there is no `accessToken`, or if `expires > 0` and the token expires within 60 s (or has
  expired). A refreshed token whose whole lifetime is 60 s or less is not refreshed again before every upload, only
  once it has expired;
- after a `401`, unless a refresh was already attempted for this upload. The request is then retried once with the
  new token.

So at most one refresh is attempted per upload, also when a failed request is tried again. Only one refresh runs at
a time.

**Request.** `POST` to `refreshUrl`. Headers: `Content-Type` (JSON or form, see below), then `refreshHeaders`; no
`Authorization` header is added. The body is `refreshPayload`, with every `{refreshToken}` in its string values
replaced by the current refresh token (an empty string if there is none). Other values are sent as they are; in form
encoding, `null` values are left out.

JSON encoding (`refreshPayloadEncoding: 'json'`, the default):

```http
POST /oauth/token HTTP/1.1
Host: api.example.com
Content-Type: application/json; charset=utf-8
X-Client: mobile

{"grant_type":"refresh_token","refresh_token":"def50200a1b2..."}
```

Form encoding (`refreshPayloadEncoding: 'form'`):

```http
POST /oauth/token HTTP/1.1
Host: api.example.com
Content-Type: application/x-www-form-urlencoded
X-Client: mobile

grant_type=refresh_token&refresh_token=def50200a1b2...
```

**Response.** A `2xx` JSON object. The plugin reads:

| Field (either spelling) | Required | Meaning |
|---|---|---|
| `accessToken` or `access_token` | yes | The new access token. |
| `refreshToken` or `refresh_token` | no | A new refresh token. If present, it replaces the old one. |
| `expires` or `expires_at` | no | Absolute expiry: an epoch time (values below 1e12 are read as **seconds**, larger ones as milliseconds) or an ISO-8601 date string. |
| `expires_in` | no | Relative expiry, in seconds from now (used only if neither `expires` nor `expires_at` is valid). |

If the response has no valid expiry, the new token's `expires` becomes `-1` (unknown): it is then refreshed only
after a `401`.

```json
{ "access_token": "eyJhbGciOi...new", "refresh_token": "def50200c3d4...", "expires_in": 3600 }
```

```json
{ "accessToken": "eyJhbGciOi...new", "expires": 1790003600 }
```

**After a refresh**, the new tokens (and expiry) are saved in the plugin's persisted config, and the pending request
is sent with `Authorization: Bearer <new token>`. The app gets an `authorization` event
(`{ success, status, error?, response? }`, where `response` is the parsed JSON response body). There is an event for
every refresh attempt: on success, and on every failure (invalid `refreshUrl`, network error, non-`2xx` answer, or a
response without an access token). After a failed refresh, the event has `success: false`, and the records stay
queued for a later retry. Token values are never written to the plugin log.

The refreshed tokens survive app restarts **unless the app replaces them**: `ready({ reset: true })` (the default)
rebuilds the config from the defaults plus the config passed to `ready()` on every launch, so the tokens in that
config win over the refreshed ones. Either pass `reset: false`, or keep the latest tokens yourself (from the
`authorization` event's `response`, or from `getState().config.http.authorization`) and pass them to `ready()`.

If your server rejects a refresh token for good, the plugin cannot recover by itself. The app should listen for
`authorization` events with `success: false` and set new tokens with `setConfig({ config: { http: { authorization:
{ ... } } } })`.

## Server checklist

- Accept `POST` (or your configured method) with a JSON body, in single or batch shape, whichever you configured.
- Store records **idempotently by `uuid`**, and answer `2xx` once they are stored.
- Answer `2xx` for records you will never accept, so they aren't retried for days.
- Keep `event`, `reason`, `recorded_at`, `sent_at`, `elapsed_realtime_ms` and `boot_count`. You need them for auditing.
- Treat `heartbeat` coordinates as "last known position", not as a fresh fix: compare `timestamp` with `recorded_at`.
- When you draw the route or add up distance from coordinates, leave out records whose `timestamp` is much older
  than their `recorded_at` (the field-force tests use 30 s). Besides heartbeats and audit records, a `motionchange`
  recorded before the first GPS fix after a start carries the last known position, which can be far away if the phone
  moved while tracking was off. The plugin's `odometer` does not count movement while tracking was off.
- Use the heartbeat's `heartbeat` object to decide which gap to expect next
  ([heartbeat.md](heartbeat.md#heartbeat-metadata)).
- Implement the gap audit described in [heartbeat.md](heartbeat.md#server-side-audit).
