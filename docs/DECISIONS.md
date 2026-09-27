# Decision log

This file lists every decision taken while building this plugin, so they can be reviewed in one place.
Each entry says **what** was decided and **why**. Entries are grouped by who decided:

1. [Decisions made by the product owner](#1-decisions-made-by-the-product-owner-questions-and-answers) during the question rounds before work started.
2. [Decisions made by the coordinator](#2-decisions-made-by-the-coordinator) (the agent that planned and merged the work) when the product owner said "use your best judgment".
3. [Integration decisions](#3-integration-decisions) taken after merging the 18 work units.
4. [Decisions taken inside each work unit](#4-decisions-taken-inside-each-work-unit).
5. [Requests that were not implemented, and known limitations](#5-open-requests-and-known-limitations).

"Record" below means one row in the local SQLite queue, which is also one object in an HTTP upload body
(see [wire-format.md](wire-format.md)). Line references point at the files as merged.

---

## 1. Decisions made by the product owner (questions and answers)

| # | Topic | Decision |
|---|---|---|
| Q1 | When to send a heartbeat | Only when **no record was created** during the window. While locations are being uploaded, no heartbeat is sent. |
| Q2 | Heartbeat upload failure | **Queue and retry later.** The record keeps its original `recorded_at`; `sent_at` is added at upload time so the server can see late delivery. |
| Q3 | Heartbeat endpoint | **Same `http.url` as locations**, same record shape, `event: "heartbeat"`. |
| Q4 | GMS vs HMS selection | **Runtime selection; the app chooses the packaged SDKs** with a Gradle property. |
| Q5 | Platforms | **Android + TypeScript now; iOS in a later batch.** |
| Q6 | API shape | **New, smaller API** (not a drop-in copy of the Transistor API). |
| Q7 | Optional features | Included: geofencing, JWT token refresh, location filter. Excluded: schedule. |
| Q8 | Extra audit records | `tracking_start` / `tracking_stop`, and `providerchange` (GPS on/off, permission change). Not included: a record when the app is swiped away. |
| Q9 | Location capture | Included: `getCurrentPosition` + `watchPosition`, activity recognition, elastic distance filter + `changePace`, auto-stop options. |
| Q10 | HTTP and storage | Included: batch upload controls, custom JSON templates, local database API, `http` event. |
| Q11 | Device state | Included: device status events, battery-optimization and phone-maker power-manager screens, fake-GPS detection, device info / sensors. |
| Q12 | App integration | Included: notification customization, log retrieval API. Excluded: headless mode, debug sounds. |
| Q13 | Geofence extras | Included: polygon geofences, geofences-only mode, geofence records uploaded to the server. Excluded: more than 100 geofences. |
| Q14 | Other | Included: background-permission explanation dialog, clock-change protection, web stub for browser development. Excluded: background-task API. |
| Q15 | Heartbeat timing | **Window of 3 to 5 minutes**: `heartbeat.minInterval = 180 s`, `heartbeat.maxInterval = 300 s`. |
| Q16 | Idle-mode permissions | **Battery-optimization exemption only.** No `SCHEDULE_EXACT_ALARM`, no `USE_EXACT_ALARM`, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| Q17 | Names | npm `@bricks-soft/capacitor-location-tracking`, JS object `LocationTracking`, Android package `com.brickssoft.locationtracking`. |
| Q18 | Android language | Kotlin. |
| Q19 | Delivery | Work units merged **locally into one branch**, then **one large pull request** for review. |

Facts given to the product owner to support Q15/Q16 (from the Android platform source, `AlarmManagerService.java`):
apps the user exempts from battery optimization get `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED` and may set exact alarms
without `SCHEDULE_EXACT_ALARM`; other apps get 7 inexact allow-while-idle alarms per rolling hour while the phone is in
deep idle.

---

## 2. Decisions made by the coordinator

### 2.1 Scope and sources

| Decision | Why |
|---|---|
| Write the native engine from scratch (clean-room). | The Transistor engine (`tslocationmanager` .aar, `TSLocationManager.xcframework`) is closed-source and commercially licensed; only its thin Capacitor bridge is MIT. No Transistor code or binary is used. |
| Do **not** use `transistor-background-fetch`. | Its minimum interval is 15 minutes on Android and iOS decides when it runs; it cannot deliver a 3–5 minute heartbeat. The heartbeat uses its own AlarmManager scheduler instead. |
| Keep iOS out of this pull request; describe the iOS heartbeat approach in [heartbeat.md](heartbeat.md). | Product-owner decision Q5; this container cannot compile Swift. |
| Capacitor 8.5.2, AGP 8.13.0, Kotlin 2.2.20, compileSdk/targetSdk 36, minSdk 24, Java 21, play-services-location 21.3.0, HMS location 6.12.0.300, OkHttp 4.12.0. | Latest versions that match Capacitor 8.5.2's own Android template and that resolved from the reachable Maven repositories. |
| No Room, no WorkManager; SQLite through `SQLiteOpenHelper`; AlarmManager for the heartbeat. | Fewer dependencies and no annotation processing; AlarmManager is the only API that can wake the app every 3–5 minutes (see Q16). |

### 2.2 Architecture

| Decision | Why |
|---|---|
| A scaffold commit first: every contract, model, JSON codec, manifest entry, build file, test fake, and a compiling stub for every class, then 18 units that each replace only their own stubs. | The repository was empty; this let 18 units be built in parallel and merged without conflicts. [architecture.md](architecture.md) is the contract. |
| One service locator (`core/Components.kt`) with fixed constructor signatures; cycles broken with `Lazy<T>`. | Receivers, the service and the plugin can all reach the same component graph from any entry point (including a cold process started by an alarm). |
| GMS and HMS are `compileOnly` in the plugin; the Gradle property `locationTracking.providers` (`gms`, `hms`, `gms,hms`; **default `gms`**) adds them as `implementation`. Backends are loaded by reflection. | One plugin build supports both; an app can ship one APK for both kinds of phone, or two separate APKs. Default `gms` because adding HMS also requires the app to add Huawei's Maven repository. |
| Runtime selection `locationProvider: 'auto'`: GMS if packaged and available, else HMS if packaged and available, else Android `LocationManager`. | A phone that has Google Play services uses them; a Huawei phone without them uses HMS; any other phone still works. |
| The tracking foreground service (type `location`) runs the whole time tracking is enabled, including while stationary. | Keeps the process alive for the in-process heartbeat alarm and gives network access during Doze. |
| Heartbeat scheduling: exact allow-while-idle alarm when `canScheduleExactAlarms()` (true for battery-exempt apps); otherwise an in-process exact alarm plus a backup inexact allow-while-idle alarm, spaced ≥ 9 minutes apart while in deep idle. | Meets 3–5 minutes whenever the OS allows it without extra permissions (Q16), and uses the 7-per-hour idle quota evenly instead of in a burst followed by a ~40-minute gap. |
| Records that are uploaded immediately, ignoring `autoSync`, `autoSyncThreshold` and `disableAutoSyncOnCellular`: `heartbeat`, `tracking_start`, `tracking_stop`, `providerchange`. | These are audit records; their value depends on arriving on time. |
| Default `persistence.maxDaysToPersist` = **7** (Transistor uses 1). | An audit record should survive a few days offline. |
| Defaults `app.stopOnTerminate = true`, `app.startOnBoot = false` (same as Transistor). | Safe defaults; an audit app should set `stopOnTerminate: false` and `startOnBoot: true` (shown in the README quick start). |
| Each record carries `elapsed_realtime_ms` and `boot_count`. | Clock-change protection (Q14): the server can detect a device whose wall clock was changed. |
| All resources carry the `lt_` prefix; a `FileProvider` subclass is used for log e-mails. | Prevents silent overrides by the app's own resources and a manifest-merge conflict with Capacitor's own `FileProvider`. |
| `package.json` is `"private": true`, `"license": "UNLICENSED"`. | Prevents accidental publishing before the owner chooses a license. |

### 2.3 Process

| Decision | Why |
|---|---|
| Units verified without a phone: Robolectric/JVM tests, okhttp `MockWebServer`, and building the example APK for `gms,hms`, `gms` and `hms`. | This environment has no emulator (no KVM) and no device. A manual on-device plan is in [device-test-checklist.md](device-test-checklist.md). |
| At most 3 Gradle builds at once (`scripts/gradle-slot.sh`). | 18 parallel builds on a 4-CPU / 15 GB machine would run out of memory. |
| Base branch for the review: a new branch `main` with one empty commit, merged into the feature branch with `--allow-unrelated-histories`; the pull request goes from `claude/background-geolocation-gms-hms-9ijqdo` into `main`. | The repository had no base branch (the feature branch was the first branch). This shows the whole plugin as one diff without rewriting pushed history. |

---

## 3. Integration decisions

These were taken after all units were merged, to connect behaviors that no single unit owned.

| Decision | Why | Where |
|---|---|---|
| New `TrackingEngine.onServiceStartFailed(error)`. The service calls it when `startForeground` fails after `ServiceController.start()` had already returned true. The engine stops tracking with the `tracking_stop` reason **`service_start_failed`**. | On Android 12+ a foreground service started from the background can be refused after the start call returned (for example Android 14+ with only while-in-use location permission). Without this, tracking looked enabled while nothing was collected, and every heartbeat alarm retried the start. | `engine/TrackingEngine.kt`, `engine/DefaultTrackingEngine.kt`, `service/LocationTrackingService.kt` |
| `restore()` (boot, heartbeat alarm, system restart) now stops with `service_start_failed` when the service is refused, instead of staying enabled and retrying. | Same reason. The server receives an explicit record that explains the end of the audit trail; the app must call `start()` again when it is opened. Battery-exempt apps (Android lists the battery-optimization exemption among its background-start exemptions), boot and app-update restarts are allowed to restart the service from the background, so they are not affected. | `engine/DefaultTrackingEngine.kt` |
| The engine registers all stored geofences again when location services come back on, or when the permission level changes. | GMS and HMS remove every registered geofence when the user switches location off (reported by the GMS and HMS units). | `engine/DefaultTrackingEngine.kt` (`onProviderChange`) |
| `consumer-rules.pro` keeps the names of `com.google.android.gms.location.LocationServices`, `com.google.android.gms.common.GoogleApiAvailability` and `com.huawei.hms.location.LocationServices`. | The provider factory looks for these classes by name. In a minified release app R8 could rename them, and the plugin would silently fall back to the Android backend on every phone. | `android/consumer-rules.pro` |
| `tsconfig.docgen.json` with an explicit ES2017 library; `npm run docgen` uses it. | `@capacitor/docgen` 0.3.1 bundles TypeScript 4.2; without it every return type in the generated API reference showed as `any`, and with the DOM library the plugin's `Location` type was replaced by the browser's. | `tsconfig.docgen.json`, `package.json` |
| The plugin's `load()` gives the current Activity to the permission manager's activity tracker. | Otherwise `checkPermissions()` could not report `prompt-with-rationale` until the next Activity lifecycle event. | `LocationTrackingPlugin.kt` |
| KDoc of `LocationBackend`: `distanceFilterM = 0` means "no distance filter"; `requestUpdates` never throws; `getLastLocation`/`getCurrentLocation` may throw `PERMISSION_DENIED`. | The engine always passes 0 (it applies the elastic filter itself and needs fixes while parked); callers must know which calls can fail. | `provider/Backends.kt` |

---

## 4. Decisions taken inside each work unit

Each unit worker recorded its own choices where the specification left room. Summarised here; the code has the details.

### Unit 1 — TypeScript wrapper and web stub
- The web factory in `plugin.ts` returns one shared instance. Capacitor calls the factory for every call made before the first one finishes; without this, early listeners were lost.
- Web: `setConfig`, `reset`, `getCurrentPosition`, `watchPosition`, `clearWatch` reject with `NOT_READY` before `ready()`, like Android.
- Web: `getCurrentPosition` (unless `persist: false`) and `watchPosition` with `persist: true` also emit `location` events. Nothing is stored or uploaded on web.
- Web: its own overall deadline for `getCurrentPosition` (the browser's timeout does not run while the permission prompt is open); `WatchPositionOptions.interval` is ignored (the browser decides the rate).

### Unit 2 — Android bridge
- Any exception other than `TrackingException` (including `NoClassDefFoundError`) rejects with code `INTERNAL`.
- Permission requests run one at a time (Capacitor matches results to calls in FIFO order). Aliases whose permissions are missing from the app manifest are skipped, because Capacitor would never call back.
- `insertLocation` stores the record and hands it to the uploader, but does **not** go through the record sink: no `location` event, and it does **not** restart the heartbeat window. An inserted location is not evidence that tracking is running.
- `removeGeofences({ identifiers: [] })` removes **nothing**; only a missing list removes all. This protects against a computed empty list removing every geofence. (Transistor removes all for an empty list.)

### Unit 3 — Config and persisted state
- `null` for a key resets it to its default; arrays and free-form maps are replaced as a whole; unknown keys are ignored with a warning; wrong types throw `INVALID_ARGUMENT` and nothing is applied.
- Validator clamps: `minInterval ≥ 60`, `maxInterval ≥ minInterval`, confidence 0–100, `maxBatchSize ≥ 1`, `maxDaysToPersist ≥ 1`, at most 3 notification actions; timeouts ≤ 0 fall back to their defaults.
- One unreadable stored field falls back to its default instead of resetting the whole stored config (protects `http.url` and tokens).
- `ready({reset: true})` re-applies the given config on every launch, so tokens refreshed by the JWT flow are replaced by the ones in the given config.

### Unit 4 — GMS backends
- `initialTriggerEntry: true` sets both `INITIAL_TRIGGER_ENTER` and `INITIAL_TRIGGER_DWELL`, so a dwell geofence the phone is already inside still reports `DWELL`.
- `getLastLocation()` never throws (returns null), so the heartbeat's fallback lookup cannot fail; `requestUpdates` logs failures instead of throwing.

### Unit 5 — HMS backends
- Every HMS class and method used was checked with `javap` against the 6.12.0.300 library.
- HMS status codes are mapped: 10803/10809/10204 → `PERMISSION_DENIED`; 10201/10202 → `TOO_MANY_GEOFENCES`; 10105/10106/10200 → `LOCATION_DISABLED`; others → `UNAVAILABLE`.
- A task that HMS itself cancels is treated as a failure, not as coroutine cancellation.

### Unit 6 — Android backend and provider selection
- HIGH accuracy prefers the `gps` provider, BALANCED/LOW prefer `network`, each falling back to the other and then to `fused` (API 31+). Registrations move when providers are switched on or off.
- Proximity-alert geofences: re-adding an id removes the old alert first; registered ids are saved so `removeAll` works after a restart.
- Any error while probing or creating a GMS/HMS bundle (including `NoClassDefFoundError`) counts as "not available".

### Unit 7 — Tracking engine
- `start()` resolves after `tracking_start`; the first `motionchange` (is_moving false) follows asynchronously, within `locationTimeout`.
- The engine always requests `distanceFilterM = 0` and applies the elastic distance filter itself.
- Stop detection: while MOVING, the device becomes STATIONARY `stopTimeout` after the last sign of motion. `stopTimeout` is at least 1 minute. `stopOnStationary` applies only to this automatic stop, not to `changePace(false)`.
- While STATIONARY, fixes create no records and do not add to the odometer; `runtime.lastLocation` is refreshed at most every 10 s so heartbeats carry a recent position.
- Geofences-only mode has no activity updates, no motion detection and no `motionchange`.
- `ready()` restores tracking (reason `restore`) when it is enabled but not running in this process.

### Unit 8 — Foreground service and notification
- `stop()` sends a STOP command to the service instead of calling `stopService`, so a stop right after a start can never kill the service before `startForeground` (that would crash the app).
- On API 34+, `start()` returns false without trying when no location permission is granted.
- A notification channel's importance cannot change after creation; to apply a new `priority`, change `channelId`.
- Boot handling: enabled and `startOnBoot` → restore; enabled without `startOnBoot` → tracking is marked disabled.

### Unit 9 — Location processing
- Rejection order: invalid coordinates (including exactly 0,0) → accuracy worse than `trackingAccuracyThreshold` → mock (if `rejectMockLocations`) → older than the last accepted fix → identical → implied speed above `maxImpliedSpeed`.
- Kalman smoothing (off by default) changes only latitude/longitude, wraps longitude across ±180°, and resets after a 60-second gap.
- The odometer does not count distance moved while tracking was off.
- A location with accuracy 0 is accepted (Android uses 0 for "unknown").

### Unit 10 — SQLite
- Old and excess records are pruned every 50 inserts and on first use; pruning applies to every record type, including heartbeats. The queue can exceed `maxRecordsToPersist` by up to 49 between prunes.
- A downgrade keeps the data (the Android default would make every open fail).
- A row that cannot be decoded is logged and deleted.

### Unit 11 — HTTP upload and JWT
- One `http` event per HTTP request: a 401 followed by a token refresh and a successful retry produces two events.
- When the server rejects an older normal record, queued audit records (heartbeat, tracking_start/stop, providerchange) are still sent in the same pass, so one record the server keeps rejecting cannot block heartbeats. Normal records still wait behind a rejected normal record until it is pruned.
- Template placeholder wrapped exactly in quotes with a null value becomes JSON `null`; a template must render a JSON object or array, otherwise the default body is used.
- There is no retry timer: queued records are retried on the next insert, when the network comes back, on each heartbeat, on `sync()` and on `start()`.

### Unit 12 — Heartbeat
- `start()` long after the last record does not fire a heartbeat immediately: the window starts at `trackingStartedAt` if the last record is older.
- Before submitting, the scheduler checks again that no record was created meanwhile; the listener alarm and the backup alarm firing in the same window produce one heartbeat.
- After a failed heartbeat attempt, the next attempt waits a full interval (no retry loop).
- A backup alarm fired in a previous boot is ignored after a reboot.

### Unit 13 — Geofences
- Identifier at most 100 characters (the GMS/HMS limit); polygons need at least 3 distinct vertices, a non-zero area, and must not cross the ±180° meridian.
- A polygon is registered with the OS as its minimal enclosing circle, padded by 10% (at least 50 m). Inside that circle the engine requests continuous location, and ENTER/EXIT of the polygon itself are decided by a point-in-polygon test with hysteresis (the accuracy circle fully on one side, or 2 consecutive fixes that agree).
- After a process restart a circle may report ENTER twice; this was chosen over losing a real ENTER.

### Unit 14 — Device monitor
- GPS and permission changes are debounced by 1 s, so one toggle in the phone settings produces one `providerchange` record.
- The first observed provider state is saved silently (no event, no record). A new state is saved only after its record was submitted, so a failed submit is retried.
- "Connected" means the network has the INTERNET capability and is not a captive portal; Doze/Data-Saver blocking counts as disconnected, and unblocking triggers the upload retry.

### Unit 15 — Permissions and settings screens
- Order of requests: location → notifications → activity recognition → background location; background is never requested without foreground location. On Android 11+ the explanation dialog is shown first.
- The battery screen is the settings **list** (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), never the direct request.
- Phone-maker screens are tried in a fixed table per manufacturer; a screen is used only if Android can resolve it and it is exported.

### Unit 16 — getCurrentPosition / watchPosition
- The age of a cached fix is the larger of the wall-clock age and the elapsed-time age, so a clock set backwards cannot make an old fix look new.
- At the timeout, the best fix collected so far is returned; `TIMEOUT` only when there is none.

### Unit 17 — File logger
- One writer, a queue of at most 5,000 entries (oldest dropped with a warning line), one file per UTC day, `logMaxDays` purge.
- `uploadLog` with an invalid URL rejects with `INVALID_ARGUMENT`.

### Unit 18 — Example app and documentation
- The example app has a button for every plugin method, a config form (including `http.url` and heartbeat interval inputs), a live log of all 13 events and a heartbeat status panel that refreshes every 10 s.

---

## 5. Open requests and known limitations

Requests from unit workers that were **not** implemented in this pull request, with the reason:

| Request | Status |
|---|---|
| `ActivityBackend.start()` should report asynchronous failures (GMS/HMS reject after the call returns). | Not done. A rejection is logged; motion detection still works from location distance. Needs a contract change. |
| `LocationBackend.requestUpdates()` should have an error channel. | Not done, same reason; failures are logged. |
| Request Huawei's `com.huawei.hms.permission.ACTIVITY_RECOGNITION` at runtime on Android 9 and below. | Not done. Adding it to the `activityRecognition` alias would make that alias fail on Android 10+ (where the permission is not declared). Documented as a limitation. |
| A shared `FakeDeviceInfoProvider` in `testing/`. | Not moved; it lives in the bridge tests. |

Known limitations (details in [heartbeat.md](heartbeat.md) and the README):

- No test on a real phone was possible here. HMS runtime behavior (HMS Core, AppGallery Connect, signing certificate) is unverified.
- Heartbeats are about 9 minutes apart in deep idle when the app is **not** battery-exempt. No heartbeats at all after a force stop, a "Restricted" battery setting, the phone being off, or a phone-maker task killer: the server sees a gap, which is the correct audit result.
- On Android 12+, when an app that is **not** battery-exempt is killed, Android may refuse to restart its foreground service from the background (the backup heartbeat alarm is inexact and carries no foreground-service exemption). The plugin then records `tracking_stop` with reason `service_start_failed`, and the user must open the app so it can call `start()` again. Battery-exempt apps, restarts after boot and after an app update are allowed to restart the service.
- iOS is not implemented.
