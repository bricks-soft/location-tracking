# Decision log

This file lists every decision taken while building this plugin, so they can be reviewed in one place.
Each entry says **what** was decided and **why**. Entries are grouped by who decided:

1. [Decisions made by the product owner](#1-decisions-made-by-the-product-owner-questions-and-answers) during the question rounds before work started.
2. [Decisions made by the coordinator](#2-decisions-made-by-the-coordinator) (the agent that planned and merged the work) when the product owner said "use your best judgment".
3. [Integration decisions](#3-integration-decisions) taken after merging the 18 work units.
4. [Decisions taken inside each work unit](#4-decisions-taken-inside-each-work-unit).
5. [Requests that were not implemented, and known limitations](#5-open-requests-and-known-limitations).

Parts 1–5 are round 1 (the first pull request, #1). [Round 2](#round-2-field-force-audit-companion-api-and-avd-tests)
(field-force audit, companion API and emulator tests) follows the same structure in R2.1–R2.5.

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
| Base branch for the review: a new branch with one empty commit, merged into the feature branch with `--allow-unrelated-histories`; the pull request goes from `claude/background-geolocation-gms-hms-9ijqdo` into it. It was first named `main`; at the owner's request it is now `master` (same commit, `abca418`), and the pull request targets `master`. | The repository had no base branch (the feature branch was the first branch). This shows the whole plugin as one diff without rewriting pushed history. |

---

## 3. Integration decisions

These were taken after all units were merged, to connect behaviors that no single unit owned.

| Decision | Why | Where |
|---|---|---|
| New `TrackingEngine.onServiceStartFailed(error)`. The service calls it when `startForeground` fails after `ServiceController.start()` had already returned true. The engine stops tracking with the `tracking_stop` reason **`service_start_failed`**. | On Android 12+ a foreground service started from the background can be refused after the start call returned (for example Android 14+ with only while-in-use location permission). Without this, tracking looked enabled while nothing was collected, and every heartbeat alarm retried the start. | `engine/TrackingEngine.kt`, `engine/DefaultTrackingEngine.kt`, `service/LocationTrackingService.kt` |
| `restore()` skips the service start when the service is already running (Android restarted it itself with `START_STICKY`). | Found by the integration tests: the redundant start from the background could be refused on Android 12+ and would then end tracking needlessly. | `engine/DefaultTrackingEngine.kt` |
| A reboot or app update with `app.startOnBoot: false` records `tracking_stop` with reason `reboot` or `package_replaced` (new `TrackingEngine.endWithoutRestore`). Before, `BootReceiver` only cleared the enabled flag. | Found by the integration tests: without a record the server could not tell why the heartbeats stopped. The record is written after the restart, so its `recorded_at` is after the boot; the gap before it is the time the phone was off or updating. | `engine/DefaultTrackingEngine.kt`, `service/BootReceiver.kt` |
| The odometer seeds every new tracking session (not only the first session of a process) from the start position. | Found by the integration tests: a second session in the same process lost the leg from the start position to the first moving fix (40 m counted instead of 140 m). | `processing/DefaultOdometer.kt` |
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
| Odometer edge cases: a start fix stamped before `start()`, `start()` then `resetOdometer()`, and a restore after a reset (the reset time is not persisted). | Not changed; each can lose or add one leg of distance. |
| A batch that mixes audit and normal records and gets a 5xx is followed by a second request with the audit records alone in the same pass. | Accepted: it is how audit records get past a rejected normal record. |
| Config and runtime state are written with `SharedPreferences.apply()` (asynchronous). | Accepted: Android flushes pending writes at Activity/Service lifecycle points; a process killed within milliseconds of a write could lose it. Not testable in Robolectric. |

Known limitations (details in [heartbeat.md](heartbeat.md) and the README):

- No test on a real phone was possible here. HMS runtime behavior (HMS Core, AppGallery Connect, signing certificate) is unverified.
- Heartbeats are about 9 minutes apart in deep idle when the app is **not** battery-exempt. No heartbeats at all after a force stop, a "Restricted" battery setting, the phone being off, or a phone-maker task killer: the server sees a gap, which is the correct audit result.
- On Android 12+, when an app that is **not** battery-exempt is killed, Android may refuse to restart its foreground service from the background (the backup heartbeat alarm is inexact and carries no foreground-service exemption). The plugin then records `tracking_stop` with reason `service_start_failed`, and the user must open the app so it can call `start()` again. Battery-exempt apps, restarts after boot and after an app update are allowed to restart the service.
- iOS is not implemented.

---

## Round 2: field-force audit, companion API and AVD tests

Round 2 started after the owner merged pull request #1 into `master`. It is delivered as one new pull request into
`master`. Its contract is [docs/e2e/architecture.md](e2e/architecture.md); where it differs from
[architecture.md](architecture.md), the round-2 contract wins. The owner's words are quoted exactly, including
typing errors.

### R2.1 Decisions made by the product owner

| # | Topic | Decision |
|---|---|---|
| R2-Q1 | Main use | "auto start location tracking when the app starts and auto stop at 2AM (so we need to prevent possible crashes of ForegroundServiceDidNotStartInTime - see example of the issue in https://github.com/transistorsoft/capacitor-background-geolocation/blob/master/CHANGELOG.md)" |
| R2-Q2 | Battery and audit | "As the tracking is expected to run +12 hours every day, we want to be battery efficient as possible without loosing the audit trial." |
| R2-Q3 | What the back office needs | "the tracking is an audit for a moving field force worker. The back-office need to know the user live location and see his tracked route. They need to know the user device details, battery status, if tracking is online or not. Also, the tracked route will be used to calculate the total travel time and distance." |
| R2-Q4 | Companion plugin | "(derived by another Capactitor plugin, but location tracking features should come from here) In some optional cases, the user gets into a specific premise where we want to validate he stays inside this premise and audit his existence there - the audit are the same audit events we use but saved thru the other plugin flow - so the other plugin needs to listen to all audit events. This other plugin also monitor to enter and exist from the premise. (we assumed a circual geofence circle is enough here)." |
| R2-Q5 | Example app and tests | "I want to have another example app configured for this setup. Use a fake PremiseMonitor plugin in this app to simulate the other plugin. Then write tests that will run on AVD to do e2e testing for those flows." Edge cases to cover: "changing permissions, changing provider, phone going to deep sleep, phone restarted, ...etc". "If something can't be automated and needs manual steps, please write a testing runbook that will be executed by an AI agent, manaul steps are manually exeucted by it and auomated steps are triggered by it." |
| R2-Q6 | Split of the tests | "Also use best jedgement to split the AVD testing scenarios, some belongs to the plugin itself not the example app. The example app just makes sure that the main usecase inside bricks is verified and checked - if we ever open source this plugin, this example app and its tests will go to bricks app itself as an integration test." |
| R2-Q7 | 02:00 stop | "the 2AM auto-stop is an example config in the main example app. transistor plugin supports that AFAIK (stopAfterMs - calculated when the plugin start method is called." → The field-force app computes the minutes until the next 02:00 into `geolocation.stopAfterElapsedMinutes` when it starts tracking. The plugin gets no schedule feature. |
| R2-Q8 | PremiseMonitor service | "PremiseMonitor plugin should have its own foreground location service". |
| R2-Q9 | Battery philosophy | "use similiar philosophy as https://docs.transistorsoft.com/help/philosophy/ which should reduce the battery usage - however we have a hard rule to send the hearbeat events (both to other native listners and to the http url), AFAIK this don't affect how much we poll the location fixes." |
| R2-Q10 | Stationary | "GPS off, keep service, keep hearbeat with latest known location - hearbeat event itself will have a new timestamp, but the location record should be showing its acquired timestamp". |
| R2-Q11 | Live location | "At most 5 min old" → new config key `http.syncInterval` (seconds); the field-force app uses `300`. |
| R2-Q12 | Native API shape | Manifest-declared listener classes plus programmatic subscription (the recommended option was chosen). |
| R2-Q13 | Where the AVD tests run | A GitHub Actions emulator job plus a runbook that an AI agent executes (the recommended option was chosen). |
| R2-Q14 | Delivery | Pull request #1 was merged by the owner before round 2 started. The base branch was renamed from `main` to `master` at the owner's request. Round 2 is one new pull request into `master`. |

### R2.2 Decisions made by the coordinator

#### Design decisions in the round-2 contract

| Decision | Why |
|---|---|
| Same process as round 1: a scaffold commit with every shared type, config key, wire field, stub and contract, then 15 work units that each replace only their own stubs. | The 15 units run in parallel and merge in any order without conflicts. |
| Stationary mode: remove the moving location request, request PASSIVE fixes only, and register one OS geofence (the *stationary region*, id `__lt_stationary__`) of `max(stationaryRadius, 150 m)` around the last accepted fix, exit only. Leave the stationary state on the region's exit, on a passive fix that is certainly outside (`distance − accuracy > stationaryRadius`, accuracy ≤ `trackingAccuracyThreshold`), on a confident moving activity, or on `changePace(true)`. | Owner decisions R2-Q9 and R2-Q10: no GPS while stationary. 150 m is the default stationary geofence radius of the Transistor philosophy the owner referred to. The accuracy rule means a coarse fix alone never turns GPS on. |
| If the region cannot be registered: a low-power request (at most one fix per 3 minutes) instead of PASSIVE. | Movement must still be detected without "Allow all the time" or when the geofence limit is reached. |
| The stationary region is never stored, recorded, emitted or counted against the 100-geofence limit, and `removeGeofences()` does not remove it. | It is an internal mechanism, not an app geofence. |
| `http.syncInterval`: normal records are uploaded when the oldest queued one is `syncInterval` seconds old (a timer checks while tracking); `autoSyncThreshold > 0` is a size limit; priority records are unchanged. | Owner decision R2-Q11 ("at most 5 min old") with one upload per interval instead of one per record; the heartbeat rule (R2-Q9) keeps audit records immediate. |
| Every heartbeat record carries an optional `heartbeat` object (`strategy`, `min_interval`, `max_interval`, `next_at`, `battery_exempt`, `device_idle`). | The server can tell an expected gap (about 9 minutes in Doze without the exemption) from a failure, and show "online", without any app setting (R2-Q3). |
| Companion API: listeners declared in the manifest are created in `Components.bootstrap()` in every process; `addListener` for code; one `LT-native` thread delivers every record (when it is queued, also when the local insert failed) and every event, in order. | Owner decision R2-Q12. A companion must not miss the first record of a process started by a boot or an alarm (R2-Q4), must see records in order, and keeps its own audit even when the plugin's database fails. |
| Debug-only test hooks: an exported broadcast receiver in each example app runs plugin calls through the native API and answers with exactly one `LT-E2E` logcat line (large results in a file). | The tests drive the plugin without the WebView and from the background, like real background triggers; release builds have no hooks. |
| A mock back office in the kit (`node:http`, port 8787, reached from the emulator at `10.0.2.2`), with control endpoints for records, faults and resets; cleartext HTTP only in debug builds and only to `10.0.2.2` and `localhost`. | Tests and the runbook need a server whose received records they can query and whose failures they can control. |
| The kit and the suites use Node 22's built-in TypeScript type stripping and `node:test`, with no runtime dependencies. | The kit and the field-force suite can move into the Bricks app unchanged (R2-Q6). |
| Both example apps' debug builds are signed with the shared `testing/debug.keystore`. | An APK built in CI can be installed over a local one (and back) with `adb install -r` and keeps the app's data; P-L07 updates in place. |
| Stable scenario ids (`P-L*`, `P-H*`, `P-P*`, `F-*`, `M-*`) in the contract, mirrored in `testing/e2e-kit/src/catalogue.ts`. | The suites, CI and the runbook refer to the same ids. |
| Two suites: the plugin suite against the plugin's example app, and the field-force suite against the field-force app. | Owner request R2-Q6: plugin behavior is tested without the field-force app; the field-force suite checks the Bricks use case and can move into the Bricks app. |
| Test intervals: heartbeat 60/120 s and `syncInterval` 120 s (production 180/300 s and 300 s). Long scenarios (P-H04, about one hour of Doze) run only nightly. | Each scenario fits the 10-minute default timeout; the pull-request jobs stay short. |
| The field-force app packages GMS only. | It stands for the Google Play build, which M-07 checks for 16 KB alignment. |

#### Deviations of the scaffold from the plan

| Decision | Why |
|---|---|
| A second manifest listener needs a meta-data name with a suffix (`com.brickssoft.locationtracking.LISTENER.<x>`). | Android's manifest merger rejects two libraries that declare the same meta-data name with different values, and `ApplicationInfo.metaData` is a map. |
| `core/RecordHooks` was added (a fan-out of every queued record to in-process observers). | Unit 5 could add the two `dispatch` calls without editing scaffold files. |
| `TrackingEngine` extends `StationaryRegionSink` with a default no-op, and `DefaultGeofenceManager`'s new `stationarySink` parameter defaults to `StationaryRegionSink.NONE`. | Existing tests compile unchanged. |
| Kotlin is applied in both example apps. | The debug receivers are Kotlin; `NativeCallback`'s Kotlin `Result` is awkward to use from Java. |
| The debug command protocol got: `json64` (base64 arguments, no shell quoting), `resultFile` for answers over 3000 characters, a 25-second timeout, `blockMainThread {delayMs}`, and fixed result shapes. | Reliable commands through `adb shell am broadcast` and logcat (logcat truncates long lines at about 4 KB). |
| `PremiseMonitorNative` was defined in the scaffold, with the `PremiseAuditEntry` wire format (`pid`, `js`, `source`). | Units 12, 13 and 14 share it; `pid` and `js` let F-09 prove that entries were created before any JavaScript ran. |
| Extra kit modules: `AppUnderTest`, fixtures, device detection, the catalogue, and two command-line tools (`npm run backoffice`, `npm run catalogue`). | Shared by all suites and by the runbook. |
| The field-force app keeps a running session's `stopAfterElapsedMinutes` at startup. | The engine measures it from the session start, so recomputing it at a relaunch would move the stop time. |
| `syncInterval` applies only with `autoSync` on, and `autoSyncThreshold > 0` acts as a size limit. | Keeps `autoSync: false` meaning "never upload normal records automatically". |
| `HeartbeatMeta.strategy` is written as a literal union in TypeScript. | Clean `npm run docgen` output. |

#### Additions by the coordinator after the scaffold

| Decision | Why |
|---|---|
| Test-mode files: the kit writes JSON files into the app's storage with `run-as` before it launches the app (`files/e2e/example.json`, `files/e2e/ff-overrides.json`); the pages read them through `Capacitor.convertFileSrc`. | `localStorage` can be written only after the first page load, and the field-force page starts tracking on that first load. |
| Robolectric test dependencies were added to the PremiseMonitor module. | Unit 13 can unit-test the fake plugin without an emulator. |
| The field-force template's `ExampleInstrumentedTest` was removed. | It asserts the package `com.getcapacitor.app` and would fail. |
| The base branch was renamed to `master`. | The owner's request (R2-Q14). |

### R2.3 Integration decisions (round 2)

<!-- coordinator fills -->

### R2.4 Per-unit decisions (round 2)

<!-- coordinator fills -->

### R2.5 Open requests and known limitations

| Item | Status |
|---|---|
| **HMS and the 16 KB page size.** `com.huawei.hms:location` 6.12.0.300 brings two native libraries with 4 KB ELF alignment: `libTransform.so` for `arm64-v8a` (from `com.huawei.hms.LocationLiteSdk:core` 2.12.0.300) and `libucs-credential.so` for `x86_64` (from `com.huawei.hms:ucs-credential-developers` 1.0.4.312). A build that packages HMS (`hms` or `gms,hms`) therefore does not support 16 KB page-size devices, which Google Play requires for apps targeting Android 15 or newer. | Open, depends on Huawei. The Play build is GMS only: the GMS-only field-force release APK has no native libraries and passes the CI 16 KB check (`.github/scripts/check-16kb.py`). On the `gms,hms` example APK the same check reports exactly these two libraries (`p_align` 4096). Runbook procedure M-07 repeats both checks. |
| **No emulator in the development container.** The container has no KVM, so nothing in round 2 ran on an emulator before the merge. The units verified with unit tests, type checks, builds and dry runs. | The first real runs are the CI emulator jobs and the runbook ([docs/e2e-runbook.md](e2e-runbook.md)). |
| **Background start of the PremiseMonitor service.** PremiseMonitor starts its foreground service from the native listener. Android 12+ allows that only while the app's process is in a foreground-service state (the tracking service runs in the foreground) or inside the short allowance after a geofence transition; Android 14+ also needs location permission for a `location` foreground service. In other cases (for example a restore of a non-exempt app whose tracking service was refused), Android refuses the start. | By design: a refusal is written as a `service_start_failed` audit entry, never a crash. F-07 and F-10 check the allowed cases on the emulator. |
| **The 02:00 stop runs on the next wake-up.** `stopAfterElapsedMinutes` is a timer that does not advance while the CPU sleeps; the engine checks it on every fix and every heartbeat. While stationary (GPS off), the stop therefore happens at the first heartbeat after the stop time: within about `maxInterval` (5 minutes) for an exempt app, about 9–10 minutes in Doze without the exemption. | Accepted (a few minutes late is fine for a nightly stop). F-02/F-03 and runbook procedure M-05 measure it. <!-- verify after merge: still true after unit 2's engine changes (DefaultTrackingEngine runs fireDueTimers on heartbeat events) --> |
| **Subset runs of the suites.** `npm run test:e2e -- --test-name-pattern=...` (the form in contract §11) runs every scenario: npm appends the option after the file pattern `"*.test.ts"`, and Node 22 then ignores it (checked with Node 22.22). | Worked around, found by units 6 and 15. The contract text is unchanged. CI passes the pattern as `E2E_TEST_NAME_PATTERN` to `.github/scripts/run-e2e.sh`; the README and the runbook use `NODE_OPTIONS='--test-name-pattern=…' npm run test:e2e`, or `node --test` with the option before the file pattern. |
