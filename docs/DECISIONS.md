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
| `package.json` is `"private": true`, `"license": "UNLICENSED"`. | Prevents accidental publishing before the owner chooses a license. Replaced in 8.0.0, see [npm publishing](#npm-publishing). |

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
| R2-Q15 | CI minutes | After the CI runs used the account's Actions minutes: "the CI runs consumed all the minutes, I want to switch to local test runs. I want to hand off this session to another local session. That can run AVD for a faster iteration loop." Chosen options: both workflows (`e2e-android.yml` and `ci.yml`) start only by hand (`workflow_dispatch`; no pull request, push or schedule trigger); the running CI runs were cancelled. |
| R2-Q14 | Delivery | Pull request #1 was merged by the owner before round 2 started. The base branch was renamed from `main` to `master` at the owner's request. Round 2 is one new pull request into `master`. |
| R2-Q16 | Quiet period after the 02:00 stop | Asked whether the field-force app should skip the auto start for some hours after the 02:00 stop (R2.5): "Keep today's behavior". The app starts tracking whenever it is opened or brought to the front while tracking is off, also shortly after 02:00. |
| R2-Q17 | `syncInterval` while tracking is off | Unit 4's first extension is confirmed: while tracking is off, normal records upload at once, as with `syncInterval = 0`. |
| R2-Q18 | `syncInterval` after a failed upload | Unit 4's second extension is confirmed, with an addition: "Confirm. But the uploader itself should do three exponential backing retires before giving up as failed. Next interval starts after the failure or success". Details the owner chose afterwards: one upload makes up to 4 tries, the first request and 3 retries after 2, 4 and 8 s; only a request without an answer (connection error or timeout), HTTP 5xx or HTTP 429 is retried (a 401 keeps its token refresh and one retry; other 4xx answers are not retried); after the last failed try, normal records wait `syncInterval` as today; after a success the rule stays as it is (the next upload when the oldest waiting normal record is `syncInterval` old). The retries go into a follow-up pull request. |
| R2-Q19 | Wake lock for the heartbeat upload and the 02:00 check | "Keep as is": the heartbeat's wake lock still ends when the heartbeat record is queued, so the upload and the 02:00 stop check can wait until the phone next wakes up (R2.5). |

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
| Test intervals: heartbeat 60/120 s and `syncInterval` 120 s (production 180/300 s and 300 s; `syncInterval` 60 s since R2F). Long scenarios (P-H04, about one hour of Doze) run only nightly. | Each scenario fits the 10-minute default timeout; the pull-request jobs stay short. |
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

Taken by the coordinator while the 15 units ran and while they were merged into
`claude/background-geolocation-gms-hms-9ijqdo`.

| Decision | Why | Where |
|---|---|---|
| Every worker reset its own branch to the round-2 contract commit `070524e` before it started. | The tool created all worktrees at the old empty base commit `abca418` (the first commit of the repository), which has none of the scaffold. | each unit's branch |
| When all 13 running workers stopped at the same time on the account's API spend limit (HTTP 429), they were resumed after the limit reset, each with its conversation and its uncommitted work. | Resuming kept the work done so far; restarting would have repeated it. | – |
| The field-force app got an `npm test` script (`node --test "test/*.test.js"`) and a CI step that runs it (unit 12 request). | Its Node tests of the startup logic ran only by hand. | `examples/field-force/package.json`, `.github/workflows/ci.yml` |
| Contract: unit 6 also owns `.github/scripts/**`; subset runs are written `NODE_OPTIONS='--test-name-pattern=…' npm run test:e2e`. | Unit 6 needed committed scripts for the emulator steps. `npm run test:e2e -- --test-name-pattern=…` does not filter on Node 22: npm puts the option after the file pattern, where Node ignores it (found by unit 6). | `docs/e2e/architecture.md` §1, §11 |
| A follow-up for unit 12: the field-force page runs its startup again when the app comes back to the foreground while tracking is off; its debug receiver requires `android.permission.DUMP` from the sender, like unit 8's. | Unit 15 found that the app would not restart tracking the next morning when it was never closed (the startup ran only on a page load). The sender guard keeps other apps from stopping tracking or redirecting uploads through a debug build. | `examples/field-force/www/**`, `examples/field-force/android/app/src/debug/AndroidManifest.xml` |
| Engine stop reasons (implemented by unit 2 at the coordinator's request): `onServiceStartFailed` records `permission_denied` when foreground location is no longer granted, else `service_start_failed`; a service refused in `start()` records `service_start_failed` (the JS error code stays `PERMISSION_DENIED`). | Unit 11 found that on Android 14 the system's restart after a revoked permission fails in `startForeground` and was recorded as `service_start_failed`, and that a refused background `start()` was recorded as `permission_denied` although the permission was granted. The server needs the real cause. | `engine/DefaultTrackingEngine.kt` |
| `Components` passes the shared `Clock` to `DefaultServiceController` (unit 1 request). | The Android 14 pre-check and the boot-count gate must use the same clock as the rest of the plugin (and the test clock in tests). | `core/Components.kt` |
| `Components.get()` publishes the instance only after `bootstrap()` has installed the manifest listeners; a nested call on the constructing thread gets the instance under construction (unit 5 request). | Otherwise another thread could get the instance and emit a record before the listeners were installed, and no companion listener would receive it. | `core/Components.kt` |
| `consumer-rules.pro` keeps `InnerClasses,EnclosingMethod` (was `InnerClasses`). | R8 fails in an app that does not use AGP's default rules once a kept class has inner classes and `EnclosingMethod` is missing (found by unit 5). | `android/consumer-rules.pro` |
| `addGeofence` rejects the identifier `__lt_stationary__` with `INVALID_ARGUMENT` (unit 2 request). | A user geofence with that id would have been routed to the engine as the stationary region. | `geofence/DefaultGeofenceManager.kt` |
| The kit's fake adb test helper locks its state file. | The kit runs the broadcast and the logcat poll at the same time; the unlocked read-modify-write lost the request id on the slower CI runner (one CI failure of the test `typed helpers send the documented args`). | `testing/e2e-kit/test/helpers/fake-adb.ts` |
| The pull request was opened as a draft before the integration finished. | `e2e-android.yml` runs only on pull requests and pushes to `master`, so the emulator jobs could run only once a pull request existed. | – |
| The 02:00 stop may come up to one heartbeat interval late while the phone is stationary. Kept as it is. | The stop only has to happen once per night. An exact stop alarm would be a separate feature for the owner to request. | `engine/DefaultTrackingEngine.kt` (see R2.5) |
| P-L11 accepts either `permission_denied` or `service_start_failed` when `start()` from the background is refused. | Round 1 recorded `permission_denied` there; the merged engine records `service_start_failed`. | `e2e/plugin/lifecycle.test.ts` |
| The `gms,hms` example APK file is smaller than the `hms` one only because of compression. Checked: uncompressed it is larger (22.1 MB against 21.2 MB) and contains the location classes of both SDKs. | The smaller file looked like a missing SDK. | – |
| New debug command in the plugin example's receiver (not in the field-force app): `otherAppLocation {enabled, intervalMs = 1000 (0–60000)}` → `{enabled, intervalMs, fixes}`. It requests GPS updates through the platform `LocationManager` from the app's own process, the way another app on a phone would, or stops them. Kit helper `E2eCommands.otherAppLocation(enabled, intervalMs?)`. P-H03 and P-P04 turn it on during the drive; P-H02 and F-05 keep it off. | First CI emulator run (API 35): the emulator has no network location and no activity recognition, and it produces a fix only while some client asks the GPS provider. With the plugin's GPS off while stationary, no fix reached the plugin's passive request or Google Play services' geofencer, and P-H03 got no `motionchange` in 10 minutes. `dumpsys location` attributes the request to the app, which is why the "no GPS request" scenarios keep it off. A real phone does not need it. | `example/android/app/src/debug/**`, `testing/e2e-kit/src/commands.ts`, `e2e/plugin/*.test.ts` |
| The kit's `CrashScanner` reads the crash, main and system buffers with the device-side tag filters `AndroidRuntime:E ActivityManager:W libc:F DEBUG:F *:S` (`CRASH_LOGCAT_FILTERS`). | First CI emulator run: an unfiltered dump of several MB on a freshly booted API 35 emulator made adbd drop the connection ("host-19: connection terminated: write failed"), and the next six lifecycle scenarios failed with "device offline". | `testing/e2e-kit/src/crash.ts` |
| The per-scenario artifact `logcat.txt` holds only the last 20,000 lines (`LogcatDumpOptions.tailLines`, `logcat -t`); the whole-run logcat stays in `_run/`. | The same adbd problem: a full dump per failed scenario is several MB. | `testing/e2e-kit/src/adb.ts`, `testing/e2e-kit/src/artifacts.ts` |
| `parseServiceRecords` reads the Android 15 header `ServiceRecord{… pkg/cls c:<package>}`. | Android 15 appends ` c:<calling package>` inside the braces; the suffix made every "foreground service running" check false on API 35 (P-P05 failed although the service ran with `isForeground=true`). | `testing/e2e-kit/src/app.ts` |
| First emulator results recorded (API 35 subset, head `21501fc`): passed P-H01, P-H05, P-P01, P-P06, P-P10; failed P-H03 (fixed by `otherAppLocation`), P-L01, P-L03, P-L06, P-L08, P-L11, P-L12, P-L13 ("device offline", fixed by the crash-scan filters) and P-P05 (fixed by the service-record parser). | The analysis of that run found the causes in the emulator and in the kit, not in the plugin; the fixes above address them. | – |
| After the merge, the documents were brought in line with the merged code: the 18 notes that unit 15 left for a check after the merge were checked against the code and resolved, and the round-2 contract got "As merged" paragraphs in §3, §4, §6, §7, §8, §10 and §11. The original contract text stays. | The code is the source of truth. Keeping the original text shows what the units were asked to build, and the added paragraphs show what they built. | `README.md`, `docs/**`, `CHANGELOG.md` |
| A background trigger (activity update, stationary region EXIT) does not restore tracking in a process that started after a user force stop (`ForceStopProbe`: the newest `ApplicationExitInfo` of the app's main process has reason `REASON_USER_REQUESTED`, Android 11+). It releases the leftover OS registrations and keeps `enabled`, so `ready()` restores tracking when the app is opened. No `tracking_stop` is recorded. | First full CI run (API 34, P-L04): a Play services activity update arrived 0.56 s after `am force-stop`, started the process and restarted tracking. A force stop means the user asked Android to stop the app; the plugin should not start again from an event that was already on its way. | `core/ForceStopProbe.kt`, `engine/DefaultTrackingEngine.kt` |
| The kit reboots with `svc power reboot` (ShutdownThread) and falls back to `adb reboot` only when `svc` is missing. | F-03: runtime permissions granted about 6 s before a plain `adb reboot` were missing after the reboot (init stops system_server without the framework shutdown), so the boot restore recorded `permission_denied`. The power menu uses the framework path, so the test now matches a real reboot. | `testing/e2e-kit/src/adb.ts` |
| The kit runs the test-provider commands as the shell user when adbd is root (`su shell cmd location providers …`). | P-P09: as uid 0 the command is attributed to the package `android`, which lacks MOCK_LOCATION ("android from uid 0 not allowed to perform MOCK_LOCATION"). | `testing/e2e-kit/src/adb.ts` |
| `run-e2e.sh` holds a kernel wake lock (`/sys/power/wake_lock`); the kit sets it again after each reboot. | First API 29 run: in deep Doze with the screen off and the battery "unplugged" the emulator's kernel suspended, adbd dropped the connection, and every later adb call timed out until the job hit its 2-hour limit. Doze is a framework state and still works with the lock. | `.github/scripts/run-e2e.sh`, `testing/e2e-kit/src/adb.ts` |
| `run-e2e.sh` waits, before the first scenario, until Play services has logged its post-boot restart ("ChimeraModuleLdr: Module config changed, forcing restart", at most 200 s) and then until `com.google.android.gms.persistent` keeps the same pid for 60 s (at most 8 minutes in all). | P-H01 (API 34): Play services restarted its processes a few minutes after boot and Android killed the example app with it ("depends on provider com.google.android.gms/.fonts.provider.FontsProvider in dying proc"). The plugin restored correctly (`tracking_start: restore`), but the scenario measures an undisturbed session. The first version (pid unchanged for 90 s) was not enough: on `4392778` the wait ended 25 s before the restart and F-01 lost the field-force app 4 s after its launch. In the logcat of every earlier run the restart came once on the API 34 image, 64–132 s after the persistent process started, and never on the API 29 and API 35 images. `gms_pid` ends in `|| true`: `pidof` exits 1 while the process is not running, and with `set -euo pipefail` that ended the script (API 35 job on `51f7721`, 10 s into the wait). | `.github/scripts/run-e2e.sh` |
| Field-force debug command `finishActivities` (a debug-only content provider remembers the activities from process start); F-08 uses HOME + `finishActivities`. | `settings put global always_finish_activities 1` from the shell does not reach the running activity manager (the developer option calls `ActivityManager.setAlwaysFinish`); the activity stayed alive after HOME. | `examples/field-force/android/app/src/debug/**`, `testing/e2e-kit/src/commands.ts`, `examples/field-force/e2e/field-force.test.ts` |
| Field-force `enterPremise`: GPS on at the start point for 15 s, then `premise.stop` + `premise.start`, then the drive. | F-11, F-12: the page registered the premise geofence while Play services still held the previous scenario's position inside the premise, so the geofence started "inside" and no ENTER came in 3 minutes. The plugin's own geofence scenario P-P10 already used this order and passed. | `examples/field-force/e2e/field-force.test.ts` |
| First full emulator results recorded (head `21501fc`): plugin API 34 passed 26 of 34 (3 skipped by design: P-H04 long, P-L13 timing, P-P08 no-GMS image); failed P-H01, P-H03, P-L04, P-P04, P-P09. Field-force API 34 passed 8 of 12; failed F-03, F-08, F-11, F-12. API 29 stopped after 3 scenarios (kernel suspend). The no-GMS job (P-P08) passed. | Every failure had a cause in the emulator setup or the kit, except P-L04 (the force-stop row above). | – |
| Suite C's `startTracking` first requests GPS through `otherAppLocation` for 20 s at the new emulated position (`settleFusedPosition`). | First API 29 run: Play services' fused provider smoothed the test's jumps of several kilometres, so the first fixes were 0.6–1 km away and moving towards the new position; P-P04, P-P06 and P-P10 failed. A real phone does not jump. | `e2e/plugin/permissions-providers.test.ts` |
| P-P03 still asserts the refusal of a geofence without background location, but only logs it when Play services accepts one. | Play services refused the geofence with only while-in-use permission on API 34 and accepted it on API 29. The plugin passes the request on; the decision is Play services'. | `e2e/plugin/permissions-providers.test.ts` |
| The kit's `prepare()` waits (root) until the device's persisted runtime-permission file lists the granted permissions (since R2F: the next `Adb.reboot()` waits instead) (`Adb.waitForPersistedPermissions`, `abx2xml` for the binary XML of API 31+). | P-L08, P-P11: a reboot about 15–20 s after `pm grant` came back without the grants, also with `svc power reboot`, so the boot restore recorded `permission_denied`. Android writes the file with a delay; a real user does not reboot seconds after granting. The first version looked for the wrong tag names, never matched, and waited the full 90 s in every scenario (run on `acd65c7`: the API 35 job took 54 minutes instead of 31); it now reads both formats (Android 10: `<pkg>` with `<item>`; Android 11+: `<package>` with `<permission>`) and prints a warning on a timeout. That worked on API 29 and API 34 (the API 29 job took 28 minutes instead of 44, field-force 41 instead of 65). On the API 35 image the permission module's file had no section for the app at all (948 characters read), so every scenario still waited 90 s; the kit now also reads the Android 10 location and gives up after 3 reads in which no file has a section for the app. P-L08 passed on API 35 without this wait in earlier runs. | `testing/e2e-kit/src/adb.ts`, `testing/e2e-kit/src/app.ts` |
| P-P04 drives the route back and forth until the `motionchange` and three `location` records arrive (at most 10 minutes) and logs the detection time. | In the background, with activity recognition denied and GPS off while stationary, the emulator noticed the drive only after about 4.5 minutes (through the stationary region), at the end of the single pass, so no `location` records followed. The scenario checks that tracking recovers, not how fast (R2.5). | `e2e/plugin/permissions-providers.test.ts` |
| The kit's `setLocationEnabled` tries `cmd location set-location-enabled`, then `settings put secure location_mode`, then `location_providers_allowed`, until `isLocationEnabled` reports the wanted state; otherwise it throws. | API 29 has no `cmd location` ("Can't find service: location"); that answer passed as success, location stayed on, and P-P06 waited in vain for `providerchange` with `enabled: false`. | `testing/e2e-kit/src/adb.ts` |
| F-04 leaves out of the drawn route the fixes that are more than 30 s older than their record (their event and time stay). | The field-force app was started and `changePace(true)` called 0.4 s later, before the first GPS fix, so the `motionchange` (is_moving true) carried the last known position: the previous scenario's, 10 minutes old and 2.7 km away. The plugin's odometer did not count that jump (0 m at the first GPS fix); the test's route check did. The fix's `timestamp` shows its age, so a back office can do the same (see `docs/wire-format.md`). | `examples/field-force/e2e/field-force.test.ts` |
| `ForceStopProbe` reads the newest exit record whose process name is the app's main process (up to 16 records), not the newest record of the package, and logs the reason it found. P-L04 accepts that the process starts again after `am force-stop` for a broadcast that was already on its way, and checks instead that the tracking service does not run and nothing is recorded until the app is opened. | API 34 run on `acd65c7`: a Play services activity update started the process 0.33 s after the force stop, and the probe missed the force stop, so the plugin tried to restore, Android refused the background service start, and `tracking_stop: service_start_failed` was recorded. The WebView's isolated renderer runs under the app (`u0a192i20`) and the force stop killed it 9 ms after the main process ("isolated not needed"), so its exit record is most likely the newest one; the new unit test `ForceStopProbeTest` reproduces this order. | `core/ForceStopProbe.kt`, `e2e/plugin/lifecycle.test.ts` |
| P-H09 accepts the initial `motionchange` in the same upload pass as a priority record (requests at most 2 s apart), not only in the same request. | API 34 run on `acd65c7`: the `tracking_start` request was built before the initial fix and needed 8 s to reach the mock back office (the app opened its socket at once; the emulator network delayed the connection). The pass then continued with the `motionchange`, 0.5 s later, as the §4 contract says ("taking the whole queue with them"). | `e2e/plugin/heartbeat.test.ts` |
| P-L13 now uses a new debug command of the plugin example, `startDuringMainThreadBlock {ms, startAfterMs}`: it blocks the main thread for 4 s and calls the plugin's `start()` from a background thread 100 ms into the block, so the service creation waits for the blocked main thread while Android's startForeground deadline runs, on every attempt (3 attempts). The plugin logs `foreground service start sent (seq N)` (LT.ServiceController, debug) right after `startForegroundService`; P-L13 checks that the service's `created` line came at least half the remaining block later, then that no `ForegroundServiceDidNotStartInTimeException` occurred and no `service_start_failed` was recorded. P-L02 uses the same line. | P-L13 was skipped in every CI run (API 34 and 35): it read the service creation from the `am_create_service` event, which was not in the logcat of any CI image (API 29, 34, 35), so no attempt could be placed; and in the logged attempts only about 2 ms passed between the start being sent and the service's `created` line, a window a block sent from the shell cannot hit. The scenario covers the owner's first requirement (no `ForegroundServiceDidNotStartInTimeException`), so it must not be skipped. | `example/android/app/src/debug/**`, `service/DefaultServiceController.kt`, `e2e/plugin/lifecycle.test.ts`, `testing/e2e-kit/src/commands.ts` |
| The geofence manager registers every geofence again after 10, 30, 60, 120 and 300 s when the backend answered `UNAVAILABLE` during a registration (tracking start, location back on, backend change), and stops at the first registration without `UNAVAILABLE`, when tracking stops or starts again, or after the last try. | API 29 run on `68265df` (P-P06): location was switched back on, the plugin re-registered its geofence 1 s later, and Google Play services answered `GEOFENCE_NOT_AVAILABLE` (1000) because its geofencer still considered its network location off ("Ignoring addGeofence because network location is disabled"). The single re-registration was lost, so no ENTER came; on a phone the same happens when Play services is slower than the plugin. | `geofence/DefaultGeofenceManager.kt` |
| On Android 10 and older with Google Play services, the kit's `setLocationEnabled(true)` waits (when location was off) for Play services' log line "GeofencerStateMachine: Network location enabled"; if it does not come within 10 s, it switches the network provider off and on (`location_providers_allowed -network`, `+network`) so Play services asks again, at most 3 times. P-P06 accepts up to four `providerchange` records after location is switched on. | Same run: in the passing API 29 runs Play services logged "sendQueryLocationOptIn" and then "Network location enabled." after location came back on; in the failing run the answer never came, and Play services refused every geofence for more than 4 minutes, which also failed P-P10. The shell switches location through the settings on Android 10 (no `cmd location`), and the system could not deliver the provider-change broadcasts to Play services' declared receivers ("Background execution not allowed"). | `testing/e2e-kit/src/adb.ts`, `e2e/plugin/permissions-providers.test.ts` |
| Final emulator results recorded (head `ff79f21`; the commit after it changes only documents and a comment in `ci.yml`). Where: local AVDs on Ubuntu 24.04.5 (Linux 7.0.0-31, x86_64, KVM), Android emulator 37.1.11, `x86_64` system images android-29 `google_apis` r13, android-34 `google_apis` r14, android-34 `default` r2, android-35 `google_apis` r9; one AVD at a time, each job on a cold boot with wiped data, through `.github/scripts/run-e2e.sh`. Plugin suite (API 34): 32 passed, 0 failed, 2 skipped (P-H04: long scenario, runs only with `E2E_INCLUDE_LONG=1`; P-P08: needs the image without Google Play services). Plugin subset (API 29): 11 of 11 passed. Plugin subset (API 35): 14 of 14 passed. Plugin P-P08 (API 34, no Google Play services): passed. Field-force suite (API 34): 12 of 12 passed. | P-L13 passed on API 34 and API 35 instead of being skipped: in all 6 attempts the service was created 3,898–3,900 ms after the start was sent, and `startForeground` came 3–12 ms after `onCreate`. On API 29, P-P06 and P-P10 passed and the kit did not print its "Network location enabled" warning. Play services restarted itself after boot on API 34 (both boots), not on API 29 or API 35, as in CI. No run logged `geofence backend unavailable`, so no geofence retry was needed. Job times (setup included): API 29 26 min, API 35 30 min, API 34 plugin 72 min, field-force 45 min, P-P08 2 min. | – |

### R2.4 Per-unit decisions (round 2)

Each unit worker recorded its own choices where the contract left room. One line per decision: the decision, then the
reason. Contract change requests and findings are listed with the unit that made them; the open ones are also in
[R2.5](#r25-open-requests-and-known-limitations).

#### Unit 1 — Foreground-service start hardening
- Three new classes: `ServiceCommands` (a per-process start/stop state machine with sequence numbers and a process token), `WhileInUseStartCheck` and `BootCountStore` — each rule is small and unit-tested on its own.
- The start command carries the notification's channel id and name, priority, title, text, small icon and color from the in-memory config (a deviation from "constants and resources only") — Android fixes a channel's importance when the channel is first created, so the first creation must use the configured priority, and a custom channel id must not also create the default channel.
- A restart with a null intent (`START_STICKY`) uses the default channel and creates it if it is missing — there is no command to read the configured channel from.
- Stops are sent with `startService`, not `startForegroundService`; a stop that races a start in the same process only calls `stopSelf(startId)`, never `stopService` — `stopService` before `startForeground` makes Android crash the app with `ForegroundServiceDidNotStartInTimeException`.
- `start()` while a start is pending and no stop followed it returns true and sends no second command — the pending start brings the service into the foreground.
- Start commands from an earlier process are recognized by a process token (pid plus a random number), and only those restore tracking — sequence numbers of another process mean nothing in this one.
- Android 14+ pre-check before `startForegroundService`: it refuses only when the API level is ≥ 34, the service is not in the foreground, `ACCESS_BACKGROUND_LOCATION` is not granted, the process importance is worse than `VISIBLE` (`IMPORTANCE_FOREGROUND_SERVICE` counts as possibly visible), the remembered activity is not `STARTED`, and no activity was started, stopped or destroyed in the last 15 s — Android would throw `SecurityException` from `startForeground`; 15 s covers Android's 10 s grace with a margin, and every uncertain case lets the start proceed.
- The pre-check does not model Android's other exemptions (a notification tap, a `PendingIntent` from a visible app) — a start in such a state without a visible activity is refused by the check, and the engine records `service_start_failed`.
- Boot gate: a boot broadcast is ignored when `BOOT_COUNT` equals the boot count of the last handled boot broadcast, or the boot count during which a service start was last accepted — the second condition lets P-L10 pass on a device that was not rebooted since the install; a "count of records" signal was rejected because it would block real boot restores.
- The handled boot count is written with `commit()` after handling — it must be on disk if the process dies right after.
- An unreadable boot count (-1) keeps the round-1 behavior (every boot broadcast is handled) — tracking must still resume after a real reboot on phones that do not report the counter.
- An HTC-style quickboot without a `BOOT_COUNT` change is ignored — alarms survive such a boot, so the heartbeat alarm restores tracking (reason `restore`).
- `isRunning` is true only after `startForeground` succeeded, and is cleared in `onDestroy` and on any `startForeground` failure — the engine uses it to decide whether a restore must start the service.
- The service loads its dependencies on a serial IO coroutine after `startForeground`; `onCreate` only logs — nothing may delay `startForeground`, also with a busy main thread.
- `ServiceDeps` gained a `clock` parameter; contract change request: `Components` passes the shared `Clock` to `DefaultServiceController` (applied, R2.3) — the pre-check's grace window and the boot counts need the same clock as the rest of the plugin.

#### Unit 2 — Stationary GPS-off mode
- `runtime.lastLocation` follows the anchor: it changes when the first fix sets the anchor, when a current fix replaces an anchor that was not current, or when a strictly more accurate fix replaces it; other fixes never change it — heartbeats show the stop point with its acquisition time (owner decision R2-Q10), and no distance is added while stationary.
- The exit rule is taken literally: `distance − fix.accuracy > stationaryRadius` (the anchor's accuracy is not subtracted, as in round 1), with the accuracy-threshold check kept in the state machine (a threshold ≤ 0 disables it); it replaces the old `max(radius, accuracy)` rule for every STATIONARY fix — contract §3.
- The region is registered only around an anchor that was at most 10 minutes old when it became the anchor — the region has no initial trigger, so the OS never reports EXIT for a device that is already outside; a region around an old fix could keep GPS off after the device moved away.
- The region is moved only when the anchor is more than 50 m from its centre — small anchor improvements cause no OS call.
- Before the initial fix is recorded, the request is PASSIVE — the initial fix has its own request, so there is one registration per start.
- A failed registration is not retried on every fix; it is retried on leaving STATIONARY, on a backend switch and on any provider change while STATIONARY — a retry per fix would call Play services every few seconds.
- OS add and remove calls time out after 10 s — the engine's lock is held during the call.
- LOW fallback (interval and fastest interval 180 s) when the registration fails or gets no answer in 10 s, when the anchor is not current, or when the user has 99 or more geofences; the region is removed when a geofence change brings them to 99 — GMS allows 100 geofences per app, and the user's 100th `add` should not fail (a remaining race is in R2.5).
- An EXIT whose fix is certainly inside the registered region (`distance + accuracy ≤ radius`) is ignored — it is a late EXIT of an earlier region.
- The geofence manager handles the user's transitions of a batch first and forwards the stationary transition afterwards, outside its lock — the engine must never run while the geofence manager's lock is held.
- Order: `motionchange(false)` is recorded before the region is registered; GPS goes on before the region is removed; `tracking_stop` is recorded before the region is removed — a slow Play services call must not delay a record.
- With `stopOnStationary`, no region is registered before the stop — it would be removed at once.
- On a region EXIT, the EXIT's own fix (if the processor accepts it) is the `motionchange` location and goes to the odometer — it is the first fix that shows the movement.
- An EXIT that wakes a cold process restores tracking first, then switches to MOVING; an EXIT while tracking is off removes the region; `stop()` removes a region that a dead process left — GMS keeps geofences across a process death.
- Coordinator request applied (R2.3): `onServiceStartFailed` records `permission_denied` when foreground location is no longer granted, else `service_start_failed`; a service refused in `start()` records `service_start_failed`, the JS code stays `PERMISSION_DENIED`, and the message says that Android refused a start from the background — unit 11's finding.
- Contract change requests: (1) `addGeofence` rejects `__lt_stationary__` (applied, R2.3); (2) the GMS/HMS geofence receivers only log `GEOFENCE_NOT_AVAILABLE` (1000) instead of telling the engine (open; mitigation: the region is registered again on every provider change); (3) count the stationary region in the geofence limit or reserve a slot (open).
- Test notes: P-H02 and F-05 need background location granted, otherwise the LOW fallback appears by design; P-H03 needs a route longer than 150 m — the region's radius is at least 150 m.

#### Unit 3 — Heartbeat cost and metadata
- The metadata is built by arming the next window before the record is submitted — the strategy then shows a refused exact alarm's fallback, and the metadata equals `status()` at that moment.
- Re-arm threshold: a due time that moves later by less than 30 s keeps the alarms; any earlier move re-arms; any move of an `idle_paced` backup re-arms — an early backup alarm would use one of the few allow-while-idle alarms and push the next heartbeat about 9 minutes later.
- An early fire of the backup alarm while `listener_with_backup` is accepted (at most once per stop, during the stop timeout) — outside deep idle the backup is not rate-limited, so one extra wake-up per stop costs little.
- After Android refused an exact alarm, the fallback stays until the next re-arm — before, every record retried the exact alarm (3 set calls per record).
- `status().nextHeartbeatAt` is the real due time, not the alarm time — an alarm left in place can be up to 30 s early.
- The schedule is saved only when the alarms are armed, not on every record; a new process computes it again from the last record — no SharedPreferences write per record.
- The provider-state check stays once per heartbeat attempt (an alarm that finds the heartbeat due; an early alarm skips it) — it is the only check that sees permission changes that neither kill the process nor send a broadcast.
- The backend's last location is not requested again for 10 minutes after a real "no location" answer (a timeout or an error is not remembered) — a new request would almost always answer the same, while a timeout may be transient.
- The wake-lock cap stays 60 s, and the listener path takes one wake lock instead of two — the lock is released as soon as the heartbeat is submitted; a shorter cap could let the CPU sleep before a slow submit completes.
- Measured by `HeartbeatCostTest`, 60 records 5 s apart: `listener_with_backup` 120 → 20 AlarmManager set calls, `exact` 60 → 10.
- `FullStackIntegrationTest` case d was updated: two records 1 s apart leave the alarms at the first record's due time — that is the threshold's intended behavior.

#### Unit 4 — `http.syncInterval`
- Extension (owner to confirm): while tracking is off, normal records follow the `syncInterval = 0` rule, and when tracking stops, held records follow it at once — there is no timer while tracking is off, so a held record (for example `getCurrentPosition()` after the 02:00 stop) would wait without limit.
- Extension (owner to confirm): after a failed automatic upload, normal records are retried once per `syncInterval` through the timer (the wait is measured on elapsed realtime), not on every insert; connectivity regained, a queued priority record and `sync()` still upload at once; priority records keep the round-1 rule (a failed heartbeat is retried on every insert) — while moving, records arrive every few seconds, and each insert would otherwise try the failing server again.
- Records that are due but could not be tried (offline, held back on cellular) get no timer — connectivity regained triggers them; a timer would loop.
- Once `syncInterval > 0` was seen in a process, one settings watcher requests a pass when tracking is switched on or off, or when `url`, `autoSync`, `autoSyncThreshold`, `syncInterval` or `disableAutoSyncOnCellular` change; with 0 there is no watcher — a changed rule applies at once, and apps that do not use `syncInterval` pay nothing.
- "Oldest" is the pending normal record with the smallest `recorded_at` (the store's order); after the clock is set back, earlier records wait at most `syncInterval` after the first record created after the change — the store already orders by `recorded_at`.
- The timer is a coroutine `delay` without a wake lock — the syncer has no `Context`; the timer is late in deep sleep, and the delay is limited by new records while moving and by heartbeats while stationary (no limit with the heartbeat disabled and no records).
- Pruning of held records by `maxRecordsToPersist` is documented, with no guard — the default is unlimited.
- An extra `LIMIT 1` query per insert (to find the oldest normal record) was kept, not optimized away — the query uses an index.
- Verification: 144 http tests, 30 of them new for the interval rule, mutation-checked 9 ways.
- Contract change request: §4 wording for the two extensions (applied in the contract, "As merged").

#### Unit 5 — Companion native API
- The facade reuses `PluginHandlers` with an always-true ready flag — the same validation and result codes as JS; `NOT_READY` does not apply to native calls.
- Callbacks arrive in completion order, not in call order — calls run concurrently on the plugin's scope.
- JSON arguments are copied at call time; JSON that cannot be serialized answers `INVALID_ARGUMENT` — the caller may reuse its object.
- JSON is built once per record or event; the first listener gets that object, the others get parsed copies — a listener that changes its object cannot change what the next listener receives.
- Every exception from a listener or a callback is caught, including `Error` — an exception that escaped on `LT-native` would kill the tracking service.
- Programmatic listeners are process-wide and survive a `Components` reset; manifest listeners are created again by every install — tests reset `Components`, and a companion's subscription must not disappear with it.
- `addListener` does not create `Components` — subscribing must not start the plugin.
- The listener named exactly `LISTENER` is called first, then the suffixed names in alphabetical order; a class named twice is created once — a fixed, documented order.
- The sink dispatches a record also when its insert was cancelled or failed; an `insertLocation` whose insert failed is not dispatched — the companion keeps its own audit, and the `insertLocation` caller gets the error and can retry.
- The delivery queue has no size limit and drops nothing; a warning is logged every 1000 waiting tasks — an audit must not lose records.
- The facade's first call takes the `Components` lock once — it waits for a bootstrap that runs on another thread.
- A Kotlin `object` cannot be a manifest listener — the plugin needs a public no-argument constructor.
- The `providerchange` event comes before its record — the event carries only the provider state.
- Contract change requests: (1) `Components.get` publishes the instance only after bootstrap (applied, R2.3); (2) one single dispatch point for record hooks in `LocationStore.insert` (optional, not applied; open).
- Finding: R8 fails without `-keepattributes EnclosingMethod` in an app that does not use AGP's default rules (applied by the coordinator, R2.3). The keep rules were checked with R8 from build-tools 36.

#### Unit 6 — CI emulator workflow and 16 KB check
- The scripts are in `.github/scripts/` (`run-e2e.sh`, `check-16kb.py`), outside `.github/workflows/**` — contract change request (applied: unit 6 owns `.github/scripts/**`).
- `e2e-android.yml` builds the two debug APKs once in the job `build-apks`; the emulator jobs download them; `ci.yml` also uploads `debug-apks` — artifacts do not cross workflows, so both workflows build the APKs on every pull request (about 10 extra runner minutes).
- The emulator jobs do not run the root `npm ci && npm run build` — they install prebuilt APKs, and the kit does not need the plugin build.
- One reusable workflow, `e2e-android-run.yml`, holds the emulator steps; callers pass `timeout-minutes` — the steps exist once.
- No AVD snapshot cache; every run cold-boots with `-no-snapshot` — a snapshot is 2–4 GB per AVD and would fill the 10 GB Actions cache, and a cold boot gives the same device state every run.
- Emulator options: `-no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -camera-back none -camera-front none`, RAM 3072 MB, boot timeout 900 s — a headless runner without a GPU or cameras.
- API 29 subset `P-(L01|L03|L08|L12|H01|H03|H05|P03|P04|P06|P10)`; API 35 subset `P-(L01|L03|L06|L08|L11|L12|L13|H01|H03|H05|P01|P05|P06|P10)` — the scenarios whose behavior depends on the API level, kept short.
- The test-name pattern is passed through `NODE_OPTIONS='--test-name-pattern=…'` — `npm run test:e2e -- --test-name-pattern` does not filter on Node 22.22.
- The long scenarios are found from the dry-run listing ("requires … long"), nothing is hard-coded — a new long scenario is picked up without a workflow change.
- Device baseline: `adb root`; `locksettings set-disabled true` and key event 82; `svc power stayon true`; package verifier off for adb installs — an activity started by the kit must be visible, and a verifier dialog would block reinstalls.
- The 16 KB check enforces only 64-bit ABIs (`arm64-v8a`, `x86_64`) — Google Play's rule covers 64-bit devices.
- The release check uses the unsigned `assembleRelease` APK — it has the same zip layout and native libraries as a signed one.
- The `gms,hms` report uses the example's debug APK — it has the same native libraries as a release build.
- The ELF check is a committed Python reader, not `llvm-readelf` — no NDK dependency.
- `e2e-android.yml` ignores changes to `**/*.md` and `docs/**` — documentation-only pull requests start no emulator jobs.
- `setup-node` uses `check-latest: true` — type stripping needs Node ≥ 22.18, and the runner's cached Node 22 may be older.
- `ci.yml`: a concurrency group (a new push cancels the older run of the same pull request), `permissions: contents: read`, timeout 60 → 75 minutes — the build job now does more.
- Artifacts are uploaded always, also on success — a passing run can still be inspected.
- Results: the field-force release APK (GMS) has 0 native libraries and passes; the example `gms,hms` APK has two 64-bit HMS libraries with `p_align` 4096: `lib/arm64-v8a/libTransform.so` and `lib/x86_64/libucs-credential.so` (R2.5).
- Schedule: nightly at 01:23 UTC. Job time limits: plugin API 34 180 minutes; API levels 120; no-GMS P-P08 45; field-force 150; long 300.

#### Unit 7 — e2e-kit
- 117 kit tests with a fake adb and a fake DevTools server — no emulator in the development container.
- "Crash since the mark" is a baseline of the log buffers taken at `mark()`, not a `-T` time filter — scenarios move the wall clock.
- Kit error codes `NO_RESPONSE` and `BAD_RESULT_FILE`, separate from the receiver's `TIMEOUT` and `INTERNAL` — a failure report says which side failed.
- `killHard` without root uses `run-as <app> kill -9` and is judged by which pids remain — `kill` exits 1 when one of several pids is already gone.
- `prepare()`: force-stop first; with root, set the device clock to the host clock if they are more than 30 s apart; reset app ops; install a missing app from `E2E_APK`; with `clearData: false` and a permission list, revoke the other entries; grant every runtime permission from `dumpsys package` by default — each scenario starts from a known state.
- The `pluginTestConfig` patch replaces free-form maps (`http.params`, `http.headers`, `persistence.extras`, refresh payload and headers) instead of merging them — the same rule as the plugin's `setConfig`.
- Faults never apply to the `/__*` control endpoints — a test must always be able to read and reset the back office.
- `interpolateRoute` moves a last remainder shorter than 5 % of a step onto the route's end — no tiny last step.
- `heartbeatCadence` idle-paced bounds are 540 s − tolerance … max(540, `maxInterval`) + tolerance; `gapsExplained` accepts idle pacing up to 540 s + `maxGapS` — Android's 9-minute allow-while-idle spacing.
- The kit enforces each scenario's timeout itself (`node:test` gets 3 minutes more, 20 with `E2E_BUGREPORT`) — cleanup and artifact collection still run after a timeout.
- The "deep idle enabled by the kit" marker is the device property `debug.e2ekit.deep_idle_enabled` — it survives the kit's process.
- Request ids start with `e2e-` — they never equal the reserved ids `example` and `ff-overrides`.
- Not added: a `dumpsys location` GPS parser and `defaultNetworkState` — the output format varies by API level; the suites keep local copies.
- The whole run's back-office log is `e2e-artifacts/_run/backoffice.log` — one file shows every request of a run.
- Suggested contract §8 wording (applied, "As merged"): `travelSummary` `pathM` (moving fixes) plus `allFixesPathM`, `travelS`, `fixes`, `segments`; `killHard` returns the killed pids; `ScenarioContext.onTeardown`.

#### Unit 8 — Plugin example test hooks
- The receiver is split into `E2eCommandReceiver` (`goAsync`, one `LT-E2E` line, always `finish`), `E2eCommandRunner` (14 commands: 13 through `LocationTrackingNative`, plus `blockMainThread`, which blocks the main thread itself; `otherAppLocation` was added later, R2.3), `E2eProtocol` (pure functions) and `E2eExchange` (the first outcome wins; writes on its own `LT-E2E` daemon thread; result files through a temporary file and a rename) — the protocol can be tested without Android, and a result file is never read half-written.
- `--receiver-foreground` broadcasts time out after 8 s instead of 25 s — Android reports a broadcast ANR after 10 s for a foreground broadcast.
- The receiver requires `android.permission.DUMP` from the sender — adb shell and root have it; other apps cannot stop tracking, inject locations or redirect uploads.
- The request id `example` is refused — its result file would overwrite `files/e2e/example.json`.
- Missing or wrongly typed arguments answer `BAD_COMMAND` (not `INVALID_ARGUMENT`); plugin errors keep their codes; only JSON `true`/`false` are booleans; blank `json64`/`json` is `{}`; text after the JSON is `BAD_COMMAND` — a malformed request is the test's fault, not the plugin's.
- `blockMainThread` accepts `ms` and `delayMs` from 0 to 60000; `blockedMs` is the requested `ms`; the line is logged before the block — the answer must not wait for the block.
- The line limit counts UTF-8 bytes; failure messages are shortened to fit (never splitting a surrogate pair), so failures never need a result file; an invalid id or unknown command is echoed, cut to 64 characters — logcat truncates by bytes, and non-ASCII titles exist.
- The result file holds the full response `{id, cmd, ok, result}`; old result files are never deleted (`pm clear` empties the folder) — the kit can check the id in the file.
- E2E mode needs exactly `{"e2e": true}` in the file, or `'1'` in `localStorage`; the file read has no timeout — a timeout could switch to normal mode, and auto-ready would then reset the config in the middle of a test.
- Buttons and listeners do not wait for the file read; only auto-ready and the notification's "stop" action wait — the page stays usable.
- In e2e mode the page refreshes `getState`/`getHeartbeatStatus` read-only and hides `NOT_READY`; the notification's "stop" action does not call `stop()` — the test alone changes the plugin's state.
- The network security config is exactly the contract's; side effect: debug builds ignore `usesCleartextTraffic` (a `cap run -l` over a LAN address needs that host added) — documented in the debug manifest.
- The template's `androidTest` was replaced by `E2eProtocolTest` (28 tests) and `E2eExchangeTest` (7), run with `:app:connectedDebugAndroidTest` — the root-level `assembleDebugAndroidTest` fails in `:capacitor-cordova-android-plugins` (existing before this unit).
- Optional request (not applied): `testImplementation org.json:json` in the example app, so these tests can run on the JVM.
- Manual use: `localStorage.setItem('lt.e2e','1'); location.reload();` and `adb shell am broadcast … --es id r1 --es cmd state`, answer with `adb logcat -s LT-E2E` — documented in the runbook.

#### Unit 9 — Plugin suite A (lifecycle)
- P-L03 and P-L12 run battery-exempt — Android 12+ refuses a background restart otherwise (`service_start_failed`), and the catalogue expects a restore.
- P-L12: `am stopservice` (root) and then `kill -9` in the same shell — Android does not restart a service that is not started, so the heartbeat alarm is the only restore path; the test asserts the order alarm heartbeat → `tracking_start` (reason `restore`), 59–180 s after the device's last record.
- P-L12 and P-L07 turn activity recognition off — an activity update could restore tracking first. P-L07 uses a heartbeat of 600/900 s — no alarm may restore before `MY_PACKAGE_REPLACED`.
- P-L11 accepts `permission_denied` or `service_start_failed` when `start()` is rejected, and only `service_start_failed` when `start()` succeeded; it never launches the app — both engine versions are valid (R2.3).
- P-L05 uses `stopOnTerminate: true` — an activity recreation must not count as a termination; it finds the new page by a marker and moves moving → stationary within the 1-minute stop timeout.
- P-L02 and P-L04 call `ready()` through the WebView (the JS API), as the app would; P-L02 calls `sync` after `ready()`.
- P-L13 reads the service creation time from the events buffer (`am_create_service`) and the `LT.Service` log; it bisects over up to 16 attempts and reports "skipped" if the block never lands around the start — the timing on an emulator varies. (Replaced after the CI runs, see R2.3: the event was not in the logcat of any CI image, and the scenario was skipped in every run.)
- P-L06 fails its precondition only if notifications show as granted.
- Timeouts: P-L03, P-L06, P-L07 and P-L12 12–15 minutes; P-L08 20 minutes — reboots and alarms take minutes.
- P-L10 fails until unit 1's boot-count gate is merged — it asserts the fixed behavior on purpose.
- Kit requests: `broadcastLine` (to combine a command and `force-stop` in one `adb shell`), device timestamps of `LT-E2E` lines, a logcat line parser (the suite had local copies `e2eBroadcastLine`, `parseLogcat`, `rawResponseNow`, `waitForRawResponse`) — added to the kit by unit 7 (`broadcastLine`, `waitForResponse`, `sendDetailed`).
- CI notes: P-L07 needs `E2E_APK`; after the first real runs, check that P-L02 hit "force-stop while `start()` ran" and that P-L13 was not skipped.

#### Unit 10 — Plugin suite B (heartbeat and power)
- P-H03 requires GMS and waits up to 10 minutes for `motionchange(true)` — the emulator has no activity recognition, AOSP images have no network location, so leaving STATIONARY with GPS off depends on GMS geofence evaluation; Google documents up to 6 minutes of geofence latency for a stationary device.
- P-H04 requires API ≥ 31 — `idle_paced` exists only when `canScheduleExactAlarms()` is false. A gap counts as paced only when the previous `next_at` was about 9 minutes ahead (the first backup alarm fires at the plain due time); the upper bound is the announced time × 1.75 + 60 s (AOSP `maxTriggerTime`: an inexact alarm may be up to 75 % late); the kit's `idlePaced` option was not used — too tight.
- P-H01 checks strict anchor-timestamp equality only when `dumpsys location` shows no newer fix — passive listening lets other apps' fixes arrive; three windows.
- P-H02 checks the app's own registrations in every sample; "GPS on the app's behalf" counts only another registrant that names the app with an interval under 5 minutes, and fails only on 2 consecutive samples — the geofence's duty-cycled request may turn GPS on briefly; the GMS phase logs and checks only the app's own registrations if the MOVING control never shows GMS naming the app.
- P-H07: records between "airplane on" and "confirmed offline" count as neither online nor offline — the moment of the switch is not exact.
- P-H09 accepts the initial `motionchange` going out with `tracking_start` or with the first heartbeat.
- P-H09 sets `batchSync` and `maxBatchSize` directly — the kit's patch merge rules were not clear to the unit (`ctx.testConfig` spreads the patch shallowly).
- P-H10 expects `exact` after `whitelistAdd` on every API level.
- A geo fix is sent every 2 s until the initial `motionchange`, then stops — the emulator reports a fix only while GPS is requested.
- `enterDeepIdle` unplugs before turning the screen off — the CI baseline keeps the screen on while powered. P-H06 falls back to the time zone GMT when `persist.sys.timezone` is empty.
- Kit requests: `waitForRecords` with a timeline, a `dumpsys location` registration parser and newest-fix reader, the app's uid, `bodyRecords(body)`, upload grouping, `defaultNetworkState`, `startRouteLoop` — the suite keeps local versions.
- Kit assumptions: `StoredRecord.requestId` groups one request; `records()` without `unique` returns every receipt; `forceIdle` enables deep idle on emulators; `setTimezone('GMT')` is accepted.
- Expected run time of the suite: 45–60 minutes.

#### Unit 11 — Plugin suite C (permissions, providers, geofences)
- P-P09 requires API 31 (test-provider commands) and uses the GMS backend — the mock flag must survive the fused provider. Timeouts: 12 minutes for P-P03, P-P04, P-P06 and P-P10; 15 for P-P11.
- The revoke scenarios mark the app battery-exempt, revoke with the app in the background, and send `ready()` after the relaunch — the e2e-mode page never calls it.
- P-P02 revokes fine and coarse location in one shell call joined with `&&` — Android restarts the sticky service about 1 s after the first revoke kills the process; a restore that still saw one of the two permissions would resume tracking, and `&&` stops at a failed revoke instead of leaving a partial one.
- P-P03 accepts two documented outcomes: the restore succeeds (a `providerchange` `when_in_use`), or Android 14+ refuses the background restart (`service_start_failed`).
- P-P05 does not require the process to be killed — AOSP kills the app for every revoked runtime permission, but if a release does not, tracking must simply go on in the same process.
- Geofence, backend and mock scenarios turn stop detection off and call `changePace(true)` — the emulator reports a geo fix only while GPS is requested. Routes are replayed continuously at 10–12 m/s — the plugin rejects speeds above 80 m/s.
- Finding 1: on API 34 the `START_STICKY` restart after a revoke throws in `startForeground` and was recorded as `service_start_failed`; it should be `permission_denied` — sent to unit 2 (applied, R2.3).
- Finding 2 (P-P03 gap): on API 34+ a refused background restart means the `when_in_use` change reaches the server only as `tracking_stop: service_start_failed` (R2.5).
- Finding 3: GMS geofencing on the emulator may need "Google Location Accuracy" turned on (`addGeofence` answers `UNAVAILABLE`, GMS status 1000) — affects P-P06, P-P10, P-P11 and F-07 (R2.5).
- Finding 4: leaving STATIONARY on the emulator relies on the GMS geofence exit or on passive fixes — affects P-P04, P-H03 and F-04 (R2.5).

#### Unit 12 — Field-force app
- The startup logic is in `www/ff-core.js` (global `FFCore`, also CommonJS) — Node tests it with fake plugins.
- The stop minutes are computed again right before `start()` and applied with `setConfig` only if they changed — the engine counts from `start()`, and permission dialogs could move the stop past 02:00.
- A running session keeps its minutes — contract §10 step 3.
- A wrong-typed override rejects the startup with `INVALID_OVERRIDES`, step `overrides`, before any plugin call; unknown keys are ignored — a test bug must be visible, not silently replaced by production values.
- An unreadable overrides file counts as no file; a 5 s timeout, a fetch error or a non-object value adds a warning; a 404 adds nothing — a test can see why its overrides were not applied.
- Invalid JSON in `localStorage` rejects the startup.
- The file is read only when `Capacitor.isNativePlatform()`, with `cache: 'no-store'` — a browser has no app storage, and a cached file could be stale.
- A `null` override means "not set"; `configPatch` deep-merges plain objects, arrays and `null` replace; a `configPatch` `stopAfterElapsedMinutes` wins over the computed one.
- A failed `requestPermissions` is a warning; `start()` then reports `PERMISSION_DENIED` itself.
- The premise step runs also when `autoStart` is false, and its failure rejects the startup — `autoStart` controls only the tracking `start()` (contract §10 steps 7 and 8).
- The stop clock time is shown from `localStorage['ff.session']` — `State` has no session start time.
- The status is refreshed every 30 s only while the page is visible and not during a startup; refresh requests queue, they are not merged.
- Debug receiver: `BAD_COMMAND` for missing or wrong arguments and for `blockMainThread` `ms`/`delayMs` outside 0–60000; the ids `ff-overrides` and `example` are refused; a foreground broadcast is finished after 8 s (ANR limit 10 s) but the answer still waits up to 25 s; answers are written on the daemon thread `FF-E2E`; diagnostics use the tag `FF.E2eHook` (never parsed by the kit).
- ES2017 syntax only (no optional chaining, no `??`, no object spread), like `example/www/app.js` — the page also runs on old Android System WebView versions.
- Contract change request: an `npm test` script in `examples/field-force/package.json` and a CI step (applied, R2.3).
- Follow-up (coordinator request, R2.3): the startup runs again on resume when tracking is off. Capacitor 8 fires the document event `resume` (`MockCordovaWebViewImpl.handleResume` → `Capacitor.triggerEvent('resume', 'document')`) after the activity was paused at least once, without `@capacitor/app`; `visibilitychange` is the fallback; one guard makes a double trigger harmless.
- Follow-up: the resume check (`FFCore.createAutoStarter`) does nothing while a run is in progress (`busy`), while tracking is on, when `autoStart` is false (also after a failed run) or when `getState()` fails; with tracking off it starts one full startup run (reason `resume`: the overrides are read again and the 02:00 minutes are computed again).
- Follow-up: `FF_APP.startup` is the latest run's promise; new fields `startupCount`, `lastStartupReason` (`'load'` | `'resume'`), `lastResume` (`{at, trigger, outcome}`) and `checkResume()` — a test can see and trigger the check.
- Follow-up: the field-force receiver requires `android.permission.DUMP` from the sender, like the example's.
- Owner question from the follow-up: unlocking the phone with the app in front shortly after 02:00 (for example at 02:30) restarts tracking until the next 02:00. A quiet period (no auto start between 02:00 and, for example, 05:00) needs an owner decision (R2.5).
- Verification: 47 Node tests; the debug and release APKs checked with `aapt2` (21 of 21 checks).

#### Unit 13 — PremiseMonitor fake plugin
- Two executors: `PM-native` (state and SQLite) and `PM-upload` (HTTP) — a slow audit endpoint must not delay `premise.status` beyond the receiver's 25 s.
- Every non-`2xx` answer is retried, also `4xx`; the order never changes (a drain always starts with the oldest pending entry) — an entry is marked uploaded only after a `2xx`, so no audit entry is dropped.
- `getAuditLog()` without a limit returns every kept entry (at most 10,000); the log keeps the newest 1000 entries, deletes older uploaded ones, and has a hard cap of 10,000 — the log cannot grow without limit while no audit URL is set.
- Every entry has `source: 'manifest'`; records and events are audited also when no premise is monitored; `stopMonitoring` keeps the `auditUrl` — pending entries are still uploaded.
- A `tracking_stop` while monitoring makes `inside` unknown and stops the service (detail `tracking_stop: <reason>`) — after the 02:00 stop no EXIT arrives.
- A fix with a `timestamp` older than the ENTER is not presence-checked (a stationary heartbeat carries a fix from before the ENTER), and PremiseMonitor's own ENTER/EXIT records are not presence-checked.
- `enter` and `exit` entries carry `distance_m` when the record has coordinates.
- `startMonitoring` with the same area keeps `inside` and adds the geofence again only if `getGeofences` lacks it — the page calls it on every load.
- The service is `START_NOT_STICKY` — the listener's first record in a new process is the only restart path (detail `restore`); a refused start is retried at most once per 60 s (detail `retry`); a repeated ENTER while the service runs does not restart it.
- On Android 14+ without location permission the service start is refused before `startForegroundService` — Android would throw.
- Entry order: ENTER: record (`geofence`) → `enter` → event (`geofence`) → `service_started` (enter); EXIT: `exit` → `service_stopped` (exit); restore: first record → `service_started` (restore) with `js: false`; `stopMonitoring`: `monitoring_stopped` → `service_stopped` (`stop_monitoring`).
- Verification: 61 Robolectric tests.

#### Unit 14 — Field-force suite
- `gms: true` on every scenario — the field-force app packages GMS only; F-02, F-03 and F-09 need root.
- Every scenario checks the overrides file (`run-as cat`) and `FF_APP.startup.overridesSource === 'file'` — a test must not run with the production preset by mistake.
- Movement starts with `changePace(true)` in F-04, F-05 and F-07 … F-12 — independent of emulator motion detection (P-H03 tests detection).
- `stopTimeout` is patched to 1 minute in every scenario (production 5) — shorter scenarios.
- F-02 part 1 uses the real clock with `stopAt` and waits for the stop (at most 3 s early, 60 s late); part 2 (root) sets the clock to 01:58 with the default 02:00 and checks only the computed minutes, and accepts 1 minute after a cold start longer than 60 s.
- The expected minutes are the range the device clock allows across the launch window — the launch takes seconds.
- F-03 plans the stop 10 minutes ahead, fails early if fewer than 5 minutes remain before the reboot, and opens the app again after the reboot (since R2F: 5 minutes ahead, and 2.5 minutes before the reboot).
- F-03, F-09 and F-10 run battery-exempt — a background restart on Android 12+ needs the exemption; production field-force installs would be exempt.
- F-05 checks only the `gps` section of `dumpsys location`, attributed by package or by WorkSource uid, and needs at least one stationary heartbeat with a fix at least 50 s old — fixes from other apps may refresh the last location.
- F-06 restarts through a page reload; "online" means the newest record is at most 150 s old and is not a `tracking_stop`.
- F-08 uses "Don't keep activities" (`always_finish_activities=1`) and HOME, and restores the setting afterwards; it starts outside the premise.
- F-12 inserts the outside fix with `insertLocation` — a real fix would also trigger EXIT; a control fix 200 m away with 100 m accuracy must not be flagged.
- F-10 tells the new process apart by `boot_count`, not by pid.
- Reads filter by host time after `prepare()`; a timed-out scenario's cleanup skips app commands and only restores device settings.
- Kit requests: `assertions.onlineStatus`, a shared `dumpsys location` GPS parser (with P-H02), `adb.batterySetStatus`, `AppUnderTest.hasActivity`, and a per-scenario teardown hook that completes before the next scenario (added as `ScenarioContext.onTeardown`).
- Expected CI run time 70–80 minutes. Likely first failures: the `dumpsys location` layout (F-05), the "Hist #n" pattern (F-08), the GNSS position across a reboot (F-10).

#### Unit 15 — Runbook and documentation
- The runbook covers the emulator suites and M-01 … M-08; the round-1 device checklist stays and is linked.
- Real phones reach the back office through an HTTPS tunnel (cloudflared or ngrok) — debug builds allow cleartext only to `10.0.2.2` and `localhost`, and `adb reverse` does not survive unplugging or a reboot.
- Field-force overrides on real phones: only `backendUrl` (the production preset stays); M-08 also `stopAt` 07:00; M-04 Run 0 `autoStart: false`.
- M-01 drives the plugin example with debug commands and e2e mode (`example.json`); the AppGallery Connect setup is a local change that is never committed.
- Pass criteria: M-01 at least 20 location records per 400 m walk and at least 50 % `walking`/`on_foot`; M-02 no crash and every gap explained, maximum gap ≤ 420 s with the allowlist (a miss is a phone maker's limitation); M-03 odometer within 5 % of the trip meter, route line 90–102 % of the odometer, departure detected within 500 m, moving time within 5 minutes or 10 %, normal-record lateness p95 ≤ 330 s, upload bursts about 300–330 s apart; M-04 three 12-hour runs (baseline, stationary, workday): app GPS time ≤ 2 minutes while stationary, stationary cost ≤ 0.5 points per hour, app share ≤ 10 % of the capacity on the workday; M-05 stop by 02:06 (exempt) or 02:12 (not exempt); M-06 no crash, `service_start_failed`, and a control run with "Allow all the time"; M-08 not exempt: 95 % of gaps ≤ 660 s, maximum ≤ 780 s, at most 8 heartbeats per hour; exempt: maximum ≤ 420 s.
- The server gap rule (heartbeat.md) is `max_interval` + 120 s, or 660 s + 120 s in Doze without the exemption; the same rule is in `lt-report.mjs` and M-08.
- The subset command is documented in the `NODE_OPTIONS` form and in the direct `node --test` form.
- DECISIONS got R2.1 owner, R2.2 coordinator, R2.3 integration, R2.4 per unit and R2.5 open items, plus the contract design table; the CHANGELOG got a separate `[Unreleased]` section above `[0.1.0]`.
- Findings: (1) the 02:00 stop can be late — the `stopAfterElapsed` timer does not advance while the CPU sleeps, and it is checked on every fix and heartbeat (`fireDueTimers`), so the stop happens at the first heartbeat after 02:00 (R2.3, R2.5); (2) the field-force app did not restart tracking the next morning when it was never closed (sent to unit 12 as a follow-up); (3) the HMS 16 KB libraries confirmed: `libTransform.so` (`arm64-v8a`, `com.huawei.hms.LocationLiteSdk:core` 2.12.0.300) and `libucs-credential.so` (`x86_64`, `ucs-credential-developers` 1.0.4.312), `p_align` 0x1000; (4) the device checklist's test-server section and its stationary lines were outdated (fixed in the integration).
- `npm run docgen`: the generated README section differed from the scaffold's in 3 whitespace hunks (the `Record`, `Pick` and `Partial` mapped types); the docgen output was committed.
- 18 notes for a check after the merge (HTML comments) were left in the README (5), the runbook (8), heartbeat.md (3), wire-format.md (1), DECISIONS (1) and the CHANGELOG (1) for the integration (resolved, R2.3).

### R2.5 Open requests and known limitations

| Item | Status |
|---|---|
| **HMS and the 16 KB page size.** `com.huawei.hms:location` 6.12.0.300 brings two native libraries with 4 KB ELF alignment: `libTransform.so` for `arm64-v8a` (from `com.huawei.hms.LocationLiteSdk:core` 2.12.0.300) and `libucs-credential.so` for `x86_64` (from `com.huawei.hms:ucs-credential-developers` 1.0.4.312). A build that packages HMS (`hms` or `gms,hms`) therefore does not support 16 KB page-size devices, which Google Play requires for apps targeting Android 15 or newer. | Open, depends on Huawei. The Play build is GMS only: the GMS-only field-force release APK has no native libraries and passes the CI 16 KB check (`.github/scripts/check-16kb.py`). On the `gms,hms` example APK the same check reports exactly these two libraries (`p_align` 4096). Runbook procedure M-07 repeats both checks. |
| **No emulator in the development container.** The container has no KVM, so nothing in round 2 ran on an emulator before the merge. The units verified with unit tests, type checks, builds and dry runs. | The real runs are the CI emulator jobs and the runbook ([docs/e2e-runbook.md](e2e-runbook.md)). The first CI run (API 35 subset) is recorded in R2.3; the other jobs have not completed a run yet. |
| **Background start of the PremiseMonitor service.** PremiseMonitor starts its foreground service from the native listener. Android 12+ allows that only while the app's process is in a foreground-service state (the tracking service runs in the foreground) or inside the short allowance after a geofence transition; Android 14+ also needs location permission for a `location` foreground service. In other cases (for example a restore of a non-exempt app whose tracking service was refused), Android refuses the start. | By design: a refusal is written as a `service_start_failed` audit entry, never a crash. F-07 and F-10 check the allowed cases on the emulator. |
| **The 02:00 stop runs on the next wake-up.** `stopAfterElapsedMinutes` is a timer (a coroutine `delay`) that does not advance while the CPU sleeps. `DefaultTrackingEngine` therefore also runs its due timers (`fireDueTimers`) after every batch of fixes, every activity update, every stationary-region exit and every heartbeat (checked after unit 2's changes). While stationary (GPS off), the stop happens at the first heartbeat after the stop time: at most `maxInterval` (5 minutes) late for an exempt app, about 9–11 minutes late in deep Doze without the exemption. The check runs in a coroutine that the heartbeat event starts; the heartbeat's wake lock is released when the heartbeat has been submitted, so it may not cover the check. If the phone falls asleep in that moment, the stop moves to a later heartbeat (found while checking the documents; not seen in a test). | Accepted (R2.3: the stop only has to happen once per night). F-02/F-03 and runbook procedure M-05 measure it. An exact stop alarm, or holding the wake lock until the engine has run its timers, would be a code change; the owner kept it as it is (R2-Q19). |
| **Subset runs of the suites.** `npm run test:e2e -- --test-name-pattern=...` runs every scenario: npm appends the option after the file pattern `"*.test.ts"`, and Node 22 then ignores it (checked with Node 22.22). | Worked around, found by units 6 and 15. Contract §11 now shows `NODE_OPTIONS='--test-name-pattern=…' npm run test:e2e` (R2.3). CI passes the pattern as `E2E_TEST_NAME_PATTERN` to `.github/scripts/run-e2e.sh`; the README and the runbook use the `NODE_OPTIONS` form, or `node --test` with the option before the file pattern. |
| **Owner question: a quiet period after the 02:00 stop.** The field-force page runs its startup again whenever the app comes to the foreground while tracking is off. Unlocking the phone with the app in front at, for example, 02:30 therefore starts tracking again until the next 02:00. | Decided: no quiet period, today's behavior stays (R2-Q16). |
| **Owner question: the two `syncInterval` extensions of unit 4.** (1) While tracking is off, normal records upload as with `syncInterval = 0`. (2) After a failed automatic upload, normal records are retried once per `syncInterval`, not on every insert (connectivity regained, a queued priority record and `sync()` still upload at once). | Both confirmed (R2-Q17, R2-Q18). R2-Q18 adds up to 3 retries per upload (after 2, 4 and 8 s): done in the round-2 follow-up (R2F). |
| **Geofence not available is only logged** (unit 2 contract change request 2). The GMS and HMS geofence receivers log `GEOFENCE_NOT_AVAILABLE` (GMS status 1000) but do not tell the engine, so the engine does not learn that the OS dropped the stationary region. | Open. Mitigation: the engine registers the region again on every location provider change while stationary. |
| **The stationary region and the 100-geofence limit** (unit 2 contract change request 3). The plugin's limit of 100 counts only the app's geofences. The engine gives the stationary region's OS slot up when the app has 99 or more geofences. It removes the region after the add that reached 99 has finished (the `GeofencesChange` event starts a coroutine that waits for the engine lock and the OS call), so an `addGeofence` of the 100th geofence right after the 99th, or one `addGeofences` call from 98 to 100, can still get `TOO_MANY_GEOFENCES` from GMS while the region is registered. | Open: count the region in the limit, or reserve a slot. |
| **One dispatch point for record hooks** (unit 5 contract change request 2). `RecordHooks.dispatch` is called in two places (`DefaultRecordSink.submit` and `PluginHandlers.insertLocation`); a single call in `LocationStore.insert` would cover every future insert path. | Open, optional. |
| **A refused background restart hides a permission change** (unit 11, P-P03). On Android 14+, when the user changes location to "while in use", the restart in the background is refused, so the `when_in_use` change reaches the server only as `tracking_stop` with reason `service_start_failed` (no `providerchange` record). | Open. The next provider check runs while tracking is off (for example when the app is opened) and saves the new state without a record, so no `providerchange` record for this change follows. |
| **GMS geofencing on the emulator.** `addGeofence` may answer `UNAVAILABLE` (GMS status 1000) until "Google Location Accuracy" is turned on in the emulator's settings (P-P06, P-P10, P-P11, F-07). | Checked on the CI runs: the images have it on. On API 29, after location was switched off and on through the settings, Play services once kept its network location off; the kit now waits for it and asks again, and the plugin retries a registration that met `UNAVAILABLE` (R2.3). |
| **Leaving the stationary state on the emulator.** The emulator has no activity recognition and no network location, and it reports a geo fix only while GPS is requested. While stationary (GPS off), movement is detected only by the GMS geofence exit or by fixes other apps request (P-P04, P-H03, F-04). | Confirmed by the first CI run (P-H03 got no `motionchange`). P-H03 and P-P04 now turn on the debug command `otherAppLocation` (R2.3), which plays another app that requests GPS. F-04 starts moving with `changePace(true)`. |
| **Instrumented tests of the example's debug hooks** (`E2eProtocolTest`, `E2eExchangeTest`, unit 8) are not run by CI. They run with `:app:connectedDebugAndroidTest` in `example/android` on a device or emulator. | Open. |
| **The heartbeat upload holds no wake lock.** The heartbeat's wake lock ends when the heartbeat record is queued and its upload has been started; the upload runs without a wake lock (the uploader has no `Context`). On a sleeping phone Android may suspend the app before the upload finishes; the heartbeat then stays queued and is retried at the next trigger. The round-1 text of wire-format.md ("around a heartbeat upload the plugin keeps the phone awake for at most about a minute") promised more than the code did in round 1 and does now. | Kept as it is (owner decision R2-Q19). The documents say what the code does. |
| **Suite durations.** The runbook's durations (plugin suite 2–3 hours, field-force suite 70–80 minutes) are estimates; no full run of either suite had finished when the documents were written (the first CI run covered the API 35 subset only). | Open: write the durations of the first complete CI runs into the runbook. |

## Round 2 follow-up (R2F): upload retries, faster emulator runs, `uuid`

Delivered as one pull request into `master` after pull request #2 was merged. It carries out the upload retries of
R2-Q18, the emulator-run speed-ups the owner chose on 2026-09-28 ("Follow-up PR"; options 5, 6 and 9 of the speed
analysis), and the fix for Dependabot alerts #1 and #2 (one advisory, one alert per example lock file), which the owner asked to
add to the same pull request.

| Decision | Why | Where |
|---|---|---|
| One upload makes up to 4 tries: after a request answered `5xx` or `429`, or without an answer (timeout, connection error), the same records are sent again after 2, 4 and 8 s. Any other answer ends the upload; a `401` keeps its token refresh and one retry. Every try is a new request (new `sent_at`) with its own `http` event; the attempt counter counts the upload once. At most one token refresh per upload, also across the tries (`AuthorizationManager.tokenForRequest(mayRefresh = false)` after a refresh). A `Retry-After` header is not read. The tries hold the upload lock, so no other upload (and no `sync()`) runs in between; the waits are coroutine `delay`s without a wake lock. After the last failed try, `syncInterval` counts from that try. | R2-Q18. One lock keeps the rule "a record is never in two requests at once". | `android/…/http/OkHttpSyncer.kt`, `AuthorizationManager.kt` |
| P-H08 answers the first upload 500 three times and checks the four tries: 2, 4 and 8 s apart (−0.5 s / +3 s), the same token, a later `sent_at` each time, the fourth one stored. | The retries on a real device, with the timing. | `e2e/plugin/heartbeat.test.ts` |
| The kit waits for the persisted runtime permissions only before a reboot: `prepare()` hands the granted permissions to `Adb.rememberGrants()`, and `Adb.reboot()` waits for them first (`revoke()` drops a revoked permission from the list). | Only P-L08, P-L09, P-P11 and F-03 reboot; every other scenario paid for the wait in `prepare()` (speed analysis: about 7 minutes per full matrix). | `testing/e2e-kit/src/adb.ts`, `app.ts` |
| F-03 plans the stop 5 minutes ahead (was 10) and reboots only while at least 2.5 minutes remain (was 5). | Local API 34 run on `ff79f21`: the restore after `kill -9` came 2 s after the start, and the boot restore 35 s after it. | `examples/field-force/e2e/field-force.test.ts` |
| `TEST_SYNC_INTERVAL_S` 60 s (was 120 s). P-H09 uses 90 s. | Shorter waits in F-01, F-04 and P-H09. P-H09 checks that the initial `motionchange` goes out with the first heartbeat, which comes `TEST_HEARTBEAT.minInterval` (60 s) after it; a `syncInterval` of the same length would upload it on its own at the same moment. | `testing/e2e-kit/src/fixtures.ts`, `e2e/plugin/heartbeat.test.ts` |
| npm `overrides` `"uuid": "^11.1.1"` in `example/` and `examples/field-force/`; both lock files regenerated. | Dependabot alerts #1 (`example/package-lock.json`) and #2 (`examples/field-force/package-lock.json`), GHSA-w5hq-g745-h8pq, CVE-2026-41907, medium: `uuid` before 11.1.1 does not check buffer bounds in `v3`/`v5`/`v6` when a `buf` is passed. `uuid` 7.0.3 came from `@capacitor/cli` 8.5.2 → `xcode` 3.0.1, a development dependency of the example apps only; both were the newest releases, so no upstream update fixed it. `xcode` calls only `uuid.v4()`, and only for iOS projects, which the examples do not have; `uuid` 11 still loads with `require()` and has `v4()`. | `example/package.json`, `examples/field-force/package.json`, lock files |
| Emulator results recorded (head `8dc7677`; the commit after it changes only documents). Where: the host and system images of the round-2 run (R2.3), now three AVDs at the same time, each on a cold boot with wiped data, 4 cores and 4 GB, boots 60 s apart: lane 1 `e2e-34` (console port 5554, back office 8787); lane 2 `e2e-34b` (a second AVD on the API 34 `google_apis` image), then `e2e-34-nogms` (5556, 8788); lane 3 `e2e-29`, then `e2e-35` (5558, 8789). Plugin suite (API 34): 32 passed, 0 failed, 2 skipped (P-H04 is `long`; P-P08 needs the image without Google Play services). Plugin subset (API 29): 11 of 11 passed. Plugin subset (API 35): 14 of 14 passed. P-P08 (API 34 without Google Play services): passed. Field-force suite (API 34): 12 of 12 passed. | P-H08 on API 34: the `tracking_start`'s four tries reached the back office 2.5, 4.0 and 8.5 s apart (500, 500, 500, 200). F-03 took 5.1 minutes (10.3 on `ff79f21`). P-L13 on API 34 and API 35: the service was created 3,897–3,899 ms after the start. The reboot wait: on API 35 the permission file had no section for the app, so the wait gave up after 3 reads (as in R2.3), and P-L08 passed. Job times (on `ff79f21` in brackets): API 34 plugin 65 min (72), field-force 36 min (45), API 29 26 min (26), API 35 29 min (30), P-P08 1 min (2). The whole matrix took 66 minutes from the first boot to the last result; the same jobs one after the other took about 187 minutes on `ff79f21`. | – |

## npm publishing

The owner asked to publish the plugin to npm the same way as `bricks-soft/cap-downloader`, and answered four
questions on 2026-10-04.

| Decision | Why | Where |
|---|---|---|
| This repository (`bricks-soft/location-tracking`) publishes the package. The public repository `bricks-soft/capacitor-location-tracking`, an older codebase with the same package name and its own `npm-publish.yml`, is not used for publishing. | Owner's choice. An npm trusted publisher names one repository and one workflow file. The owner made this repository public on 2026-10-04, so versions published by the workflow carry an npm provenance statement. | `.github/workflows/npm-publish.yml` |
| License MIT (was `UNLICENSED`). | Owner's choice; same as `cap-downloader`. | `LICENSE`, `package.json`, README "License" |
| Public access (`publishConfig.access` `public`). | Owner's choice; same as `cap-downloader`. A scoped package is restricted by default. | `package.json` |
| First npm version 8.0.0 (was 0.1.0, never published). `PLUGIN_VERSION` in `src/web/device.ts` and `android/build.gradle` follows it. | Owner's choice: the major version follows Capacitor's, as `cap-downloader` (8.0.2) does. | `package.json`, `src/web/device.ts`, `android/build.gradle` |
| The workflow is `cap-downloader`'s (`release: created` and `workflow_dispatch`, Node 24, npm latest, `npm ci`, `npm stage publish`) plus `npm run build` and `npm test` before the publish step. | Same trusted-publishing and staged-publishing setup; the tests stop a broken version before it is staged. It starts on a release, unlike `ci.yml` (manual only, R2.1), because releases are rare. | `.github/workflows/npm-publish.yml` |
