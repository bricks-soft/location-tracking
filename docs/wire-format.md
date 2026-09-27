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
- [When uploads happen](#when-uploads-happen)
- [Response handling and retries](#response-handling-and-retries)
- [Time fields: `timestamp`, `recorded_at`, `sent_at`](#time-fields)
- [JWT refresh](#jwt-refresh)
- [Server checklist](#server-checklist)

## How records flow

1. The plugin creates a **record**: a location fix, a motion change, a heartbeat, a geofence transition, or an audit
   event.
2. The record is written to an on-device SQLite queue **first**.
3. An uploader sends queued records to `http.url`, oldest first. A record is deleted from the queue only after the
   server has answered `2xx`.
4. JavaScript listeners get the same record shape, without `sent_at`.

Nothing is uploaded while `http.url` is not set: records just stay queued, until they are pruned after
`persistence.maxDaysToPersist` days (default 7) or when the queue exceeds `persistence.maxRecordsToPersist`.

## The request

| Item | Value |
|---|---|
| Method | `http.method`: `POST` (default), `PUT` or `PATCH` |
| URL | `http.url` |
| Timeout | `http.timeout` ms (default 60000) |
| Body | JSON, see [Body shapes](#body-shapes) |

Headers are applied in this order:

1. `Content-Type: application/json; charset=utf-8`;
2. every entry of `http.headers`;
3. `Authorization: Bearer <accessToken>`, when `http.authorization` is configured and `http.headers` doesn't already
   contain an `Authorization` header.

Timestamps are ISO-8601 in UTC with milliseconds, for example `2026-09-26T10:15:30.123Z`
(`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).

## Record fields

Every record has all of these keys; values that are unknown are `null`. The only exceptions are the optional `extras`
and the event-specific keys `geofence`, `provider` and `reason`, which appear only when they apply.

| Key | Type | Meaning |
|---|---|---|
| `uuid` | string | Unique record id (UUID v4). **Deduplicate on it**: a record can arrive more than once. |
| `event` | string | Record type: `location`, `motionchange`, `current_position`, `watch_position`, `heartbeat`, `geofence`, `tracking_start`, `tracking_stop` or `providerchange`. |
| `timestamp` | string \| null | Time of the location **fix**. For heartbeat and audit records this is the time of the last known fix, so it can be old. `null` if no location has ever been known. |
| `recorded_at` | string | When the record was **created** on the device. |
| `sent_at` | string | When the request was built. Only in HTTP bodies; every record of one batch has the same value. |
| `elapsed_realtime_ms` | number | Milliseconds since the device booted, when the record was created. It is monotonic, so it doesn't change when the user changes the clock. |
| `boot_count` | number | The device's boot counter. It increases with every reboot. `-1` if unavailable. |
| `is_moving` | boolean | Motion state when the record was created. |
| `odometer` | number | Meters travelled since the odometer was last reset. |
| `mock` | boolean | `true` if the fix came from a mock-location app. `false` when there is no fix. |
| `coords` | object \| null | The fix (see below). `null` if no location has ever been known. |
| `activity` | object | `{ "type": "still" \| "on_foot" \| "walking" \| "running" \| "on_bicycle" \| "in_vehicle" \| "unknown", "confidence": 0-100 }`. |
| `battery` | object | `{ "level": 0..1 (or -1 if unknown), "is_charging": boolean }`. |
| `backend` | string \| null | Location backend in use: `gms`, `hms` or `android`. |
| `extras` | object | Optional. `persistence.extras`, merged with the extras passed to the call that created the record (for example `getCurrentPosition({ extras })`). |
| `geofence` | object | Only for `geofence`: `{ "identifier", "action": "ENTER" \| "EXIT" \| "DWELL", "extras"? }`. |
| `provider` | object | Only for `providerchange`: the new provider state (see below). |
| `reason` | string | Only for `tracking_start` and `tracking_stop` (see the reason tables below). |

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
`"event": "watch_position"`. Non-persisted positions are never uploaded.

### `heartbeat`

This is an audit record, created while tracking is on when no other record was created for `heartbeat.minInterval`
seconds (default 180). See [heartbeat.md](heartbeat.md).

- `coords` and `timestamp` are the **last known** location, so `timestamp` can be much older than `recorded_at`. In
  the example below, the phone has not moved for 23 minutes.
- `recorded_at` is when the heartbeat was created, and `sent_at` when it was uploaded.
- `is_moving`, `odometer`, `activity` and `battery` are current values.

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
  "extras": { "driver_id": 7 }
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

An audit record, created when tracking starts or resumes. It carries the last known coords, or `null`.

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
| `boot` | Tracking resumed after the phone rebooted (`app.startOnBoot: true`). Expect a gap before it, covering the time the phone was off. |
| `restore` | The app's process was restarted while tracking was on, for example after Android or the phone maker's task killer killed it. Expect a gap before it. |
| `package_replaced` | Tracking resumed after the app was updated (`app.startOnBoot: true`). |

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
| `stop_after_elapsed` | `geolocation.stopAfterElapsedMinutes` elapsed. |
| `terminate` | The user swiped the app away with `app.stopOnTerminate: true`. |
| `permission_denied` | Tracking could not continue without location permission, for example the foreground service could not start. |

A `tracking_stop` is written before the service stops. If the phone is offline at that moment, the record stays
queued and arrives later, with a late `sent_at`. There is **no** `tracking_stop` when the phone is switched off, the
app is force-stopped, or the process is killed. Those show up as a gap, which is the correct audit outcome (see
[heartbeat.md](heartbeat.md#server-side-audit)).

### `providerchange`

An audit record, created while tracking is on when the location provider state changes: location services on or off,
GPS or network provider toggled, permission level or accuracy changed, or backend changed. When the change is a
permission revocation, Android kills the app, so the record is created the next time the plugin runs.

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

- **Single record:** the record's fields are merged into the root, together with `params`:
  `{ "uuid": "...", "event": "location", ..., "device_id": "abc" }`. Don't use `params` keys that collide with record
  keys.
- **Batch:** a bare JSON array, `[ {...}, {...} ]`. **`params` are ignored**, because an array has no root object to
  merge them into. Use headers, or `persistence.extras` (which is copied into every record), for per-device data.

## Templates

If your server expects a different shape, set `http.locationTemplate` (and optionally `http.geofenceTemplate`, used
for `geofence` records; it falls back to `locationTemplate`). A template is JSON text with `<%= name %>`
placeholders, and its rendered object replaces the default record object. Wrapping in `rootProperty`, batching and
`params` then work exactly as for the default shape.

Substitution rules:

- `<%= name %>` may have whitespace inside the delimiters (`<%=name%>` works too).
- Each value is inserted as a **raw JSON literal**:
  - numbers and booleans are inserted bare;
  - `null` is inserted as `null`;
  - strings are inserted **without quotes**, so you write the quotes yourself: `"<%= timestamp %>"`;
  - `extras` is inserted as JSON object text, so write it bare: `"extras": <%= extras %>`.
- Inside quotes, a `null` value becomes the string `"null"`. This can happen with `timestamp`, `reason`, `backend`,
  `geofence.*` and `provider.*` on records where they don't apply. Treat `"null"` as absent on the server.
- An unknown placeholder becomes an empty string, and a warning is logged.
- If the rendered text is not valid JSON, the plugin sends the **default shape** instead and logs an error. Test your
  template with `getLog()`.

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
| `is_moving` | boolean | `extras` | object |
| `odometer` | number | | |
| `mock` | boolean | | |

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
    "reason": "null",
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

Rules:

1. **Priority records are uploaded immediately** whenever `http.url` is set. They ignore `autoSync`,
   `autoSyncThreshold`, batch waiting and `disableAutoSyncOnCellular`.
2. When an upload runs, it **drains the whole queue in order** (oldest first; in batches if `batchSync` is on), so
   queued normal records go out together with the priority record. The exception: on a cellular connection with
   `disableAutoSyncOnCellular: true`, only priority records are sent.
3. **Normal records** follow `autoSync` (default `true`) and `autoSyncThreshold` (default `0`, which uploads every
   record right away; `N` waits until at least `N` records are queued). With `autoSync: false`, normal records wait for
   the next priority record or a manual `sync()`.
4. **Queued records are retried**:
   - when the next record is inserted;
   - when connectivity returns;
   - on every heartbeat;
   - when the app calls `sync()`.

The plugin emits one `http` event per request (`{ success, status, responseText, uuids }`) to the app.

## Response handling and retries

| Server response | What the plugin does |
|---|---|
| `2xx` | Deletes the records in the request from the queue. The response body is not interpreted (it only appears in the app's `http` event). |
| `401` | Refreshes the access token (see [JWT refresh](#jwt-refresh)), then retries the request **once**. If that fails too, the records stay queued. |
| Any other status (`3xx` after redirects, `4xx`, `5xx`), a timeout or a network error | The records **stay queued**, and their attempt counter and last-attempt time are updated. They are retried at the next retry trigger (see above). |

There is no maximum number of attempts. A record leaves the queue only after a `2xx`, or when it is pruned
(`maxDaysToPersist`, `maxRecordsToPersist`). Consequences for your server:

- **Return `2xx` for every record you have stored, or have deliberately decided to drop** (for example, invalid data
  you will never accept). Return non-`2xx` only for transient problems. Otherwise the same request comes back on
  every retry, until it is pruned days later.
- **Be idempotent on `uuid`.** If the server stored a record but the response was lost (timeout, dropped connection),
  the device sends the record again.
- **Don't assume arrival order is creation order.** After an outage, records arrive in a burst, oldest first. Order
  by `recorded_at`, or by `boot_count` then `elapsed_realtime_ms`.
- **Answer quickly.** Around a heartbeat upload the plugin keeps the phone awake for at most about a minute, and
  Android may suspend the app soon after that.

## Time fields

| Field | Clock | Tells you |
|---|---|---|
| `timestamp` | Location provider (fix time) | How fresh the position is. For heartbeats it can be old: the device is stationary, or has no new fix. |
| `recorded_at` | Device wall clock | When the record was created. Use it for gap detection. |
| `sent_at` | Device wall clock | When the request was built. `sent_at - recorded_at` is how long the record waited in the queue: a large value means late delivery (offline, Doze, server errors). |
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

**When.** The plugin refreshes the token before a request if `expires > 0` and the token expires within 60 s. It also
refreshes after any `401`. Only one refresh runs at a time; concurrent requests wait for it.

**Request.** `POST` to `refreshUrl`, with `refreshHeaders`. The body is `refreshPayload`, with every `{refreshToken}`
in its string values replaced by the current refresh token.

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
| `expires` or `expires_at` | no | Absolute expiry as an epoch time. Values below 1e12 are read as **seconds**, larger ones as milliseconds. |
| `expires_in` | no | Relative expiry, in seconds from now. |

```json
{ "access_token": "eyJhbGciOi...new", "refresh_token": "def50200c3d4...", "expires_in": 3600 }
```

```json
{ "accessToken": "eyJhbGciOi...new", "expires": 1790003600 }
```

**After a refresh**, the new tokens (and expiry) are saved in the plugin's persisted config, so they survive app
restarts, and the pending request is sent with `Authorization: Bearer <new token>`. The app gets an `authorization`
event (`{ success, status, error?, response? }`). If the refresh fails, the event has `success: false`, and the
records stay queued for a later retry.

If your server rejects a refresh token for good, the plugin cannot recover by itself. The app should listen for
`authorization` events with `success: false` and set new tokens with `setConfig({ config: { http: { authorization:
{ ... } } } })`.

## Server checklist

- Accept `POST` (or your configured method) with a JSON body, in single or batch shape, whichever you configured.
- Store records **idempotently by `uuid`**, and answer `2xx` once they are stored.
- Answer `2xx` for records you will never accept, so they aren't retried for days.
- Keep `event`, `reason`, `recorded_at`, `sent_at`, `elapsed_realtime_ms` and `boot_count`. You need them for auditing.
- Treat `heartbeat` coordinates as "last known position", not as a fresh fix: compare `timestamp` with `recorded_at`.
- Implement the gap audit described in [heartbeat.md](heartbeat.md#server-side-audit).
