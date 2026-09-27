# Round 2 architecture: field-force audit, companion API and AVD end-to-end tests

This document is the contract between the round-2 scaffold and the 15 round-2 work units. The scaffold owns it; units
must not change it. If a contract is insufficient, work around it inside your own files and describe a **contract
change request** in your final report. The round-1 contract ([docs/architecture.md](../architecture.md)) still
applies wherever this document does not override it (global rules §0, threading, logging, `Clock`, no `java.time`,
no GMS/HMS types outside their packages, Robolectric SDK levels, Gradle only through `scripts/gradle-slot.sh`).

**Product goal (owner).** The plugin's main use: a field-force app starts tracking when the app starts and stops it at
02:00 (so an app that is never closed does not hit `ForegroundServiceDidNotStartInTimeException` on a cold start the
next morning). Tracking runs 12+ hours a day and must be as battery efficient as possible without losing the audit
trail. The back office needs the live location (at most 5 minutes old), the tracked route, device details, battery,
whether tracking is online, and the total travel time and distance. A second Capacitor plugin (PremiseMonitor) must
receive **all** audit records and events natively and save them through its own flow; it monitors a circular premise
and has its own foreground location service. Everything is verified by end-to-end tests on an Android emulator (AVD)
in GitHub Actions, plus a runbook for what cannot be automated.

**Owner decisions.**
- 02:00 stop: the app computes the minutes until the next 02:00 into `geolocation.stopAfterElapsedMinutes` at start.
- Stationary: GPS off, the foreground service stays, heartbeats continue with the last known fix (heartbeat
  `recorded_at` = now, `timestamp` = the fix's acquisition time).
- Live location: a new `http.syncInterval` (seconds, 0 = off); the field-force app uses 300.
- Companion API: a public Kotlin API with manifest-declared listener classes (created at process start) plus
  programmatic subscription.
- AVD tests run in GitHub Actions (KVM) and through an AI-agent runbook. The development container has no KVM:
  nothing runs on an emulator there, so every unit verifies with unit tests, type checks, builds and dry runs.

Contents: [1 Units](#1-unit-ownership) · [2 Native companion API](#2-native-companion-api) ·
[3 Stationary GPS-off mode](#3-stationary-gps-off-mode) · [4 http.syncInterval](#4-httpsyncinterval) ·
[5 Heartbeat metadata](#5-heartbeat-metadata) · [6 Debug hooks](#6-debug-hook-contract-both-example-apps) ·
[7 Mock back office and PremiseMonitor wire format](#7-mock-back-office) · [8 e2e-kit API](#8-e2e-kit-typescript-api) ·
[9 Scenario catalogue](#9-scenario-catalogue) · [10 Field-force app and PremiseMonitor](#10-field-force-app-and-premisemonitor) ·
[11 Commands and recipes](#11-commands-and-recipes)

---

## 1. Unit ownership

Units edit only these files (and may add new files inside their own directories). `...` is
`android/src/main/java/com/brickssoft/locationtracking`; tests go to the matching `android/src/test/...` directory.

| # | Unit | Files |
|---|---|---|
| 1 | FGS start hardening | `.../service/**` (+ tests) |
| 2 | Stationary GPS-off mode | `.../engine/**`, `.../geofence/DefaultGeofenceManager.kt` (routing body only, see §3) |
| 3 | Heartbeat cost + metadata | `.../heartbeat/**` |
| 4 | `http.syncInterval` behavior | `.../http/**` |
| 5 | Companion native API | `.../api/**`, `.../record/RecordSink.kt`, `.../bridge/PluginHandlers.kt` (insertLocation hook), `android/consumer-rules.pro`, `docs/native-api.md` |
| 6 | CI emulator workflow + 16 KB check | `.github/workflows/**` |
| 7 | e2e-kit | `testing/e2e-kit/**` |
| 8 | Plugin example test hooks | `example/android/app/src/debug/**`, `example/www/**`, `example/android/app/src/androidTest/**` |
| 9 | Plugin suite A lifecycle | `e2e/plugin/lifecycle.test.ts` |
| 10 | Plugin suite B heartbeat/power | `e2e/plugin/heartbeat.test.ts` |
| 11 | Plugin suite C permissions/providers/geofences | `e2e/plugin/permissions-providers.test.ts` |
| 12 | Field-force app | `examples/field-force/www/**`, `examples/field-force/android/app/src/debug/**` |
| 13 | PremiseMonitor fake plugin | `examples/field-force/plugins/premise-monitor/**` |
| 14 | Field-force suite | `examples/field-force/e2e/**` (except package.json/tsconfig) |
| 15 | Runbook + docs | `docs/e2e-runbook.md`, `README.md`, `docs/heartbeat.md`, `docs/wire-format.md`, `docs/DECISIONS.md`, `CHANGELOG.md` |

**Scaffold-owned (units must not edit):** this document, `.../core/**`, `.../model/**`, `.../config/**`, the contract
interfaces (`engine/TrackingEngine.kt`, `provider/Backends.kt`, `bridge/BridgeServices.kt` and the other round-1
contracts), `src/definitions.ts`, `testing/debug.keystore`, every `package.json`, `tsconfig.json`, `build.gradle`,
`gradle.properties` and `src/main/AndroidManifest.xml`, and the test fakes (`android/src/test/.../testing/**`,
`bridge/TestServices.kt`, `integration/FullStackHarness.kt`).

Clarifications:
- The debug source sets are unit-owned, **including their manifests and resources**:
  `example/android/app/src/debug/AndroidManifest.xml` and `res/` (unit 8),
  `examples/field-force/android/app/src/debug/AndroidManifest.xml` and `res/` (unit 12).
- Unit 13 owns everything under `plugins/premise-monitor/` except `package.json`, `android/build.gradle` and
  `android/src/main/AndroidManifest.xml` (scaffold; ask for changes).
- Unit 14 owns `examples/field-force/e2e/*.test.ts` (the suite's `package.json` and `tsconfig.json` are scaffold).
- Unit 7 adds no runtime dependencies to the kit: Node 22 built-ins only (the global `WebSocket` replaces `ws`).
- Unit 2 may edit `DefaultGeofenceManager.onGeofenceTransitions` (route the stationary id) and `removeAll` (so it no
  longer calls `backend.removeAll()`, which would also drop the stationary region; remove the stored ids instead).
  Nothing else in that file.
- Unit 15 also owns the prose of `docs/wire-format.md`; the scaffold already added the optional `heartbeat` object.

**Merge independence.** Every unit starts from the scaffold commit, which already has every shared type, constructor
parameter, config key, wire field and stub. Units replace only their stubs and their own behavior, so they merge in any
order. Keep `ScaffoldWiringTest` and every existing test green; append constructor parameters only with defaults.

---

## 2. Native companion API

Package `com.brickssoft.locationtracking.api` (public, stable; unit 5 implements the stubs, the signatures are fixed):

```kotlin
/** Receives every queued record and every tracking event, also in processes without a WebView. Methods run on the
 *  plugin's single "LT-native" background thread, in emission order; exceptions are caught and logged. */
interface LocationTrackingListener {
    /** Every record queued for upload (all events incl. tracking_start/stop, providerchange, heartbeat, geofence,
     *  insertLocation): wire JSON of RecordJson.toJson(record) (no sent_at). */
    fun onRecord(context: Context, record: JSONObject) {}
    /** Every JS event with the same name and payload as the JS addListener API (EventJson.name/payload). */
    fun onEvent(context: Context, name: String, payload: JSONObject) {}
}
fun interface NativeSubscription { fun remove() }
fun interface NativeCallback<T> { fun onResult(result: Result<T>) }   // always invoked on the LT-native thread

object LocationTrackingNative {
    const val LISTENER_META_DATA = "com.brickssoft.locationtracking.LISTENER"
    const val THREAD_NAME = "LT-native"
    fun addListener(context: Context, listener: LocationTrackingListener): NativeSubscription
    fun ready(context: Context, config: JSONObject?, reset: Boolean, callback: NativeCallback<JSONObject>) // State JSON
    fun setConfig(context: Context, config: JSONObject, callback: NativeCallback<JSONObject>)              // State JSON
    fun start(context: Context, callback: NativeCallback<JSONObject>)                                      // State JSON
    fun startGeofences(context: Context, callback: NativeCallback<JSONObject>)                             // State JSON
    fun stop(context: Context, callback: NativeCallback<JSONObject>)                                       // State JSON
    fun changePace(context: Context, isMoving: Boolean, callback: NativeCallback<Unit>)
    fun getState(context: Context, callback: NativeCallback<JSONObject>)                                   // State JSON
    fun getHeartbeatStatus(context: Context, callback: NativeCallback<JSONObject>)                         // HeartbeatStatus JSON
    fun sync(context: Context, callback: NativeCallback<JSONArray>)                                        // uploaded records
    fun insertLocation(context: Context, location: JSONObject, callback: NativeCallback<String>)           // uuid
    fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>)                 // JS Geofence (GeofenceJson)
    fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>)
    fun getGeofences(context: Context, callback: NativeCallback<JSONArray>)                                // JS geofences
}
internal object NativeListeners { fun install(context: Context, components: Components) }
```

All methods are `@JvmStatic`. `NativeCallback` takes a Kotlin `Result` (inline class): on the JVM it is
`onResult(Object)` and receives the unboxed value or a `kotlin.Result.Failure`, which is awkward from Java, so companion
code is Kotlin. Both example apps apply `kotlin-android` for their debug hooks.

**Registration.**
- **Manifest listeners.** A `<meta-data>` inside `<application>` whose `android:name` is `LISTENER_META_DATA`, or starts
  with `LISTENER_META_DATA + "."` (e.g. `com.brickssoft.locationtracking.LISTENER.analytics`), and whose
  `android:value` is the fully qualified class name. The class needs a public no-arg constructor. A second listener
  needs a suffixed name: the manifest merger rejects two libraries declaring the same meta-data name with different
  values, and `ApplicationInfo.metaData` is a map. Duplicate class names are instantiated once.
- **Programmatic.** `addListener` receives what is emitted after the call; `NativeSubscription.remove()` is idempotent.

**Lifecycle.**
- `Components.bootstrap()` calls `NativeListeners.install(context, components)` once per `Components` instance (wrapped
  in try/catch). Every entry point runs it (plugin load, the service, all receivers) and it runs before any component
  can emit, so a manifest listener never misses the first record of a process (boot, restore, heartbeat alarm).
- `install` reads the meta-data (`PackageManager.getApplicationInfo(..., GET_META_DATA)`, with
  `ApplicationInfoFlags` on API 33+), instantiates each class by reflection (a failing class is logged with its
  `Throwable` and skipped, never crashes the process), and subscribes once to `components.recordHooks` (records) and
  `components.events` (events). Tests reset Components, so a later `install` replaces the previous subscriptions.
- Listener constructors run inside `Components.get()`: they must be cheap and must not block; do real work on the first
  callback or on your own executor.

**Delivery.**
- Records reach listeners through `core/RecordHooks` (scaffold): `Components.recordHooks` is passed to
  `DefaultRecordSink` (appended constructor parameter `hooks`) and exposed to the bridge as
  `BridgeServices.recordHooks`. Unit 5 adds the two `dispatch` calls: in `DefaultRecordSink.submit` right after the
  insert (also when the insert failed: the companion keeps its own audit), and in `PluginHandlers.insertLocation`
  right after its insert (inserted records bypass the sink).
- `onEvent` mirrors every `TrackingEvent` with `EventJson.name(event)` and `EventJson.payload(event)`.
- Hooks and the EventBus call back synchronously on the emitting thread; `NativeListeners` only enqueues (building the
  JSON on the LT-native thread), so delivery never blocks the engine. One single-thread executor (daemon thread named
  `LT-native`) serves every listener and every `NativeCallback`, so a record's `onRecord` precedes the events that
  carry it (the sink dispatches before it emits) and ordering is global. Listener exceptions are caught and logged per
  call.

**Calls.**
- Each call runs the same component method as the JS bridge (`engine.ready/setConfig/start/...`,
  `heartbeat.status()`, `syncer.sync()`, the geofence manager), launched on `components.scope`, and delivers the JS
  result shape: State via `ConfigJson.stateToJson`, `HeartbeatStatusJson`, `RecordJson.toJsonArray`,
  `GeofenceJson`. `insertLocation` does what `PluginHandlers.insertLocation` does (including the record hook).
- Failures are `Result.failure(TrackingException)`: its `code` is the JS `ErrorCode` (e.g. `PERMISSION_DENIED`,
  `INVALID_ARGUMENT`); other exceptions are wrapped as `INTERNAL`.
- Native calls are **not** subject to the JS bridge's NOT_READY rule and do not set the bridge's ready flag. A native
  caller that needs a config calls `ready` first (the E2E receivers do).
- `consumer-rules.pro` (unit 5) keeps `com.brickssoft.locationtracking.api.**` public members and the no-arg
  constructors of every `LocationTrackingListener` implementation (they are created by reflection).
- `docs/native-api.md` (unit 5) is the user guide for companion-plugin authors (PremiseMonitor is the example).

---

## 3. Stationary GPS-off mode

Owner: unit 2 (engine). Contract pieces already in the scaffold:
- `Constants.STATIONARY_REGION_ID = "__lt_stationary__"`.
- `interface StationaryRegionSink { suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition) }` in
  `provider/Backends.kt` (with `StationaryRegionSink.NONE`).
- `TrackingEngine : ActivitySink, StationaryRegionSink` with a default no-op `onStationaryRegionTransition`; unit 2
  overrides it in `DefaultTrackingEngine`.
- `DefaultGeofenceManager(..., scope, stationarySink: Lazy<StationaryRegionSink> = lazyOf(StationaryRegionSink.NONE))`;
  `Components` passes `lazy { stationarySink }` where `Components.stationarySink` is the engine (the full-stack test
  harness does the same).

Behavior (unit 2):
- **Entering STATIONARY** (stop timeout, `changePace(false)`, restore with `isMoving=false`, the initial fix after
  `start()`): remove the MOVING location request and request **PASSIVE** updates only (no GPS, no network requests of
  our own; fixes other apps cause still arrive). Register one OS geofence with `providers.geofence().add(...)`:
  `OsGeofence(STATIONARY_REGION_ID, anchor, radius = max(stationaryRadius, 150 m), onEntry=false, onExit=true,
  onDwell=false, loiteringDelayMs=0, initialTriggerEntry=false)`. The anchor is the last accepted fix. The foreground
  service keeps running; heartbeats continue with `runtime.lastLocation` (heartbeat `recorded_at` = now,
  `timestamp` = the fix's acquisition time).
- **Leaving STATIONARY** (→ MOVING), whichever comes first:
  - the region's EXIT (`onStationaryRegionTransition`);
  - a passive fix that is certainly outside: `distance(anchor, fix) - fix.accuracy > stationaryRadius` with
    `accuracy <= filter.trackingAccuracyThreshold` (accuracy-aware: a coarse fix never wakes GPS by itself);
  - a confident moving activity lasting `motionTriggerDelay` (unchanged);
  - `changePace(true)`.
  Then remove the region, restore the configured request and record `motionchange` (`is_moving: true`) with the first
  accepted fix, as today.
- **Fallback.** If the region cannot be registered (no background location permission, too many geofences, a backend
  error), log it and use a LOW-power request (at most one fix per 3 minutes) instead of PASSIVE, so movement is still
  detected without GPS.
- `needsContinuousLocation` (inside a polygon's enclosing circle) still forces the MOVING request.
- The region is removed on `stop()` and re-registered after a backend switch and after a provider change that
  re-registers geofences (location switched back on). GMS keeps geofences across a process death but not a reboot;
  restore re-registers (adding the same id is idempotent).
- **Routing** (unit 2, in `DefaultGeofenceManager.onGeofenceTransitions`): transitions whose id is
  `STATIONARY_REGION_ID` go to `stationarySink.value.onStationaryRegionTransition(t)` before any other check; they are
  never stored, recorded, emitted or counted against the 100-geofence limit. `removeAll()` must not remove the region
  (see §1).
- Tests: P-H01, P-H02 (no active non-passive request from the app in `dumpsys location`), P-H03, F-05.

---

## 4. `http.syncInterval`

Config (scaffold): `HttpConfig.syncInterval: Int = 0` (seconds; TS `http.syncInterval?: number`, default 0); the
validator clamps it to ≥ 0; the web stub has the same default and clamp. Behavior: unit 4.

- `syncInterval = 0`: unchanged. Normal records follow `autoSync` and `autoSyncThreshold` (queue ≥
  `max(1, autoSyncThreshold)`).
- `syncInterval > 0` (and `autoSync: true`): normal records are uploaded when the **oldest pending normal record** is at
  least `syncInterval` seconds old (age = now − `recorded_at`; a negative age after a clock change counts as due), or
  earlier when `autoSyncThreshold > 0` and the queue has reached it (a size cap). An upload drains the whole queue,
  in batches of `maxBatchSize` when `batchSync` is on.
- Checked on every insert, on connectivity regained, on syncer start, **and by a timer** in the tracking process: when
  a normal record is queued and not yet due, schedule a check at `oldest.recorded_at + syncInterval` (a coroutine on
  the syncer's scope; cancelled when tracking stops or the queue empties). No timer while tracking is off.
- Priority records (`heartbeat`, `tracking_start`, `tracking_stop`, `providerchange`) are unchanged: uploaded at once,
  taking the whole queue with them. `disableAutoSyncOnCellular` still holds normal records back on cellular.
  `sync()` still uploads everything.
- Worst-case staleness of the server's live location ≈ `syncInterval` (+ Doze deferral of the timer; a heartbeat
  drains the queue anyway). The field-force preset is `syncInterval: 300`, `batchSync: true`, `maxBatchSize: 100`.
- Tests: P-H09, F-04.

---

## 5. Heartbeat metadata

Model (scaffold): `data class HeartbeatMeta(strategy: HeartbeatStrategy, minInterval: Int, maxInterval: Int,
nextAt: Long?, batteryExempt: Boolean, deviceIdle: Boolean)` in `model/DeviceModels.kt`; `Record.heartbeat:
HeartbeatMeta? = null`. `RecordJson` writes it only when non-null and parses it back (an unknown strategy is dropped).
TS: `Location.heartbeat?: HeartbeatMeta`.

Wire (heartbeat records only, optional):

```json
"heartbeat": { "strategy": "exact", "min_interval": 180, "max_interval": 300,
               "next_at": "2026-09-26T10:47:05.310Z", "battery_exempt": true, "device_idle": false }
```

Unit 3 populates it on every heartbeat record (`record.copy(heartbeat = ...)` before `recordSink.submit`):
- `strategy`: the strategy armed for the next window (`exact` | `listener_with_backup` | `idle_paced`; never
  `disabled`, no heartbeat exists then);
- `min_interval` / `max_interval`: the config values at creation;
- `next_at`: when the next heartbeat will be due if no other record is created: `recorded_at + minInterval`, or the
  idle-paced backup time (`max(due, lastBackupFire + 9 min)`) when the strategy is `idle_paced`; null if unknown;
- `battery_exempt`: `device.isIgnoringBatteryOptimizations()`; `device_idle`: `device.isDeviceIdleMode()`.

Unit 3 also reduces the heartbeat's cost (wake-lock time, provider checks, `getLastLocation` calls, re-arming) without
changing the window semantics. Tests: P-H01, P-H04, P-H05, P-H10.

---

## 6. Debug hook contract (both example apps)

Debug build type only (`app/src/debug/**`). Unit 8 implements it in `example/`, unit 12 in `examples/field-force/`.

**Receiver.** Class `<applicationId>.e2e.E2eCommandReceiver` (Kotlin), declared in `app/src/debug/AndroidManifest.xml`
as `android:exported="true"` with an intent filter for action `<applicationId>.E2E`:
- plugin example: `com.brickssoft.locationtracking.example.e2e.E2eCommandReceiver`, action
  `com.brickssoft.locationtracking.example.E2E`;
- field-force: `com.brickssoft.fieldforce.example.e2e.E2eCommandReceiver`, action `com.brickssoft.fieldforce.example.E2E`.

**Request extras.** `id` (request id, `[A-Za-z0-9._-]{1,64}`), `cmd`, and the JSON arguments as `json64` (base64 of
UTF-8 JSON; preferred, avoids shell quoting) or `json` (plain JSON). Missing arguments = `{}`. The kit sends explicit
broadcasts with `--include-stopped-packages`, background by default (`--receiver-foreground` on request).

**Response.** `onReceive` calls `goAsync()`, runs the command through `LocationTrackingNative` (field-force `premise.*`:
`PremiseMonitorNative`), and logs **exactly one** line with `android.util.Log.i("LT-E2E", json)`:
- success: `{"id":"…","cmd":"…","ok":true,"result":<JSON>}`;
- failure: `{"id":"…","cmd":"…","ok":false,"code":"<ErrorCode | BAD_COMMAND | TIMEOUT | INTERNAL>","message":"…"}`;
- if the serialized line would exceed 3000 characters (logcat truncates at about 4 KB), the full response is written to
  `files/e2e/<id>.json` in the app's data dir and the line is `{"id":"…","cmd":"…","ok":true,"resultFile":"files/e2e/<id>.json"}`
  (the kit reads it with `run-as <appId> cat`).
- The receiver waits at most 25 s for the callback (a background broadcast may run 60 s); then it logs `TIMEOUT` and
  finishes. It always calls `PendingResult.finish()`.

| `cmd` | args | `result` |
|---|---|---|
| `ready` | `{config?, reset? = true}` | State |
| `setConfig` | `{config}` | State |
| `start` / `startGeofences` / `stop` | `{}` | State |
| `changePace` | `{isMoving}` | `null` |
| `state` | `{}` | State |
| `heartbeatStatus` | `{}` | HeartbeatStatus |
| `sync` | `{}` | array of uploaded records |
| `insertLocation` | `{location}` (InsertLocationInput) | `{uuid}` |
| `addGeofence` | `{geofence}` (JS Geofence) | `null` |
| `removeGeofence` | `{identifier}` | `null` |
| `getGeofences` | `{}` | array of JS Geofences |
| `blockMainThread` | `{ms, delayMs? = 0}` | `{blockedMs}`: logged first, then a posted main-thread runnable sleeps `ms` after `delayMs` (simulates a busy main thread around a service start) |
| `premise.start` (field-force) | `{premise, auditUrl?}` | PremiseStatus |
| `premise.stop` / `premise.status` (field-force) | `{}` | PremiseStatus |
| `premise.auditLog` (field-force) | `{limit?}` | array of PremiseAuditEntry |

An unknown `cmd` or invalid JSON answers `BAD_COMMAND`. Commands never touch JS.

**Test-mode files (coordinator addendum).** The web pages must know they run under the e2e kit *before* their
first startup code runs; `localStorage` can only be written after a page has loaded, which is too late (the
field-force page auto-starts on its first load, and a running session keeps its stop time). So:
- The kit writes JSON files into the app's internal storage **before it launches the app** (after `pm clear` when it
  clears data): `adb shell run-as <appId> sh -c 'mkdir -p files/e2e && cat > files/e2e/<name>.json'` with the JSON on
  stdin (`Adb.runAsWrite(appId, relativePath, text)` and `Adb.runAsRemove(appId, relativePath)`, unit 7).
- The page reads a file at startup with
  `fetch(Capacitor.convertFileSrc('/data/data/<appId>/files/e2e/<name>.json'))` (Capacitor's local server serves
  `/_capacitor_file_/` paths from the app's storage). Any failure (fetch error, non-2xx status, empty body, invalid
  JSON, a non-object value) means "no file". Release builds never have these files.
- **Plugin example** (unit 8): `files/e2e/example.json` = `{"e2e": true}` turns on **e2e mode**: the page never calls a
  plugin method that changes state on its own (`ready`, `setConfig`, `reset`, `start`, `startGeofences`, `stop`,
  `changePace`, geofence add/remove, `sync`, `destroyLocations`, `destroyLog`); auto-ready is off; it still subscribes
  its event listeners; it shows a visible "E2E mode" banner. `localStorage['lt.e2e'] === '1'` also turns it on (for
  manual use).
- **Field-force** (unit 12): `files/e2e/ff-overrides.json` = `FieldForceOverrides`; it takes precedence over
  `localStorage['ff.e2e.overrides']` (see §10 step 1).
- Kit (unit 7): `AppUnderTest.prepare(...)` accepts `testFiles?: Record<string, unknown>` (file name → JSON) and writes
  them before launching; for the plugin app it writes `example.json` `{"e2e": true}` unless
  `testFiles['example.json'] === null`. `AppUnderTest.writeTestFile(name, value)` / `removeTestFile(name)` change them
  later (the page reads them again on its next load: `WebViewDriver.reload()` or a relaunch).

**Cleartext.** Debug-only `app/src/debug/res/xml/network_security_config.xml`, referenced from the debug manifest's
`<application android:networkSecurityConfig="@xml/network_security_config">`:

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.0.2.2</domain>
    <domain includeSubdomains="false">localhost</domain>
  </domain-config>
</network-security-config>
```

(The main manifests declare no network security config, so the merged debug manifest needs no `tools:replace`.)

**Signing.** `testing/debug.keystore` (PKCS12, alias `androiddebugkey`, store and key password `android`,
`CN=Android Debug,O=Android,C=US`, SHA-256 `AB:9F:2D:ED:BC:52:B8:78:AB:F0:2B:D0:E5:5A:A5:70:83:C5:40:5F:60:0A:D4:21:6D:81:F3:B7:5F:A3:FA:2C`)
is the `debug` signingConfig of both apps, so `adb install -r` of a CI-built APK over a local build (and back) keeps
the app's data, and P-L07 can update in place.

---

## 7. Mock back office

Implemented by unit 7 in `testing/e2e-kit/src/backoffice.ts` (in-process `MockBackOffice`) and
`src/cli/backoffice.ts` (standalone: `npm run backoffice -- --port 8787` in `testing/e2e-kit`). `node:http`, bound to
`0.0.0.0`, default port 8787 (`E2E_BACKEND_PORT`). The emulator reaches it at `http://10.0.2.2:8787`. It logs one line
per request.

| Endpoint | Behavior |
|---|---|
| `POST /locations` | Plugin uploads. Splits single, batch (`{"location":[…]}`), custom `rootProperty` and `rootProperty: "."` bodies (a bare record or array) into records: a body key whose value is a record (has `uuid` and `event`) or an array of records holds them; the other root keys are the `params`. Stores each with `receivedAt`, `requestId`, `index`, `batchSize`, `params`, `authorization`. Answers `200 {"ok":true}`. |
| `POST /premise-audit` | PremiseMonitor uploads `{device_id?, entries: PremiseAuditEntry[]}`; stores each entry with `receivedAt`. Answers `200 {"ok":true,"accepted":n}`. |
| `POST /auth/refresh` | JWT refresh (any body): answers `{"accessToken":"e2e-access-<n>","refreshToken":"e2e-refresh-<n>","expires_in":3600}` (`n` counts refreshes since the last reset). |
| `POST /logs` | `uploadLog()` multipart; stores a size summary. Answers 200. |
| `GET /__records?event=&since=&unique=` | Stored records (JSON array of `StoredRecord`); `event` may be a comma list; `since` = epoch ms. |
| `GET /__premise?since=&kind=&type=` | Stored PremiseMonitor entries. |
| `GET /__requests?path=&since=` | Request log (`RequestLogEntry`). |
| `POST /__faults` | Adds a fault `{path, status? = 500, count? = 1 (-1 = until reset), delayMs?, drop?}` for the next matching requests: answer `status`, or drop the connection, after `delayMs`; only `delayMs` = delay, then answer normally. Faulted requests store nothing. `DELETE /__faults` clears them. |
| `POST /__reset` | Forgets records, premise entries, requests, faults and the refresh counter. |
| `GET /__health` | `{"ok":true,"records":n,"premise":n,"uptimeMs":…}` |

Examples: P-H08 posts `{path:"/locations", status:500, count:1}`, then `{path:"/locations", status:401, count:1}` with
a JWT config and checks the `/auth/refresh` request and the retried `Authorization: Bearer e2e-access-1`. P-H07 uses
airplane mode instead of faults.

**PremiseMonitor audit entry** (`testing/e2e-kit/src/types.ts` `PremiseAuditEntry`; unit 13 produces it, units 7 and
14 consume it):

```json
{ "id": "uuid", "kind": "record" | "event" | "premise", "at": "ISO-8601 UTC ms", "pid": 12345, "js": false,
  "source": "manifest" | "subscription",
  "record": { …wire record… },                          // kind "record"
  "name": "heartbeat", "payload": { … },                // kind "event"
  "type": "monitoring_started" | "monitoring_stopped" | "enter" | "exit" | "presence_violation"
        | "service_started" | "service_stopped" | "service_start_failed",   // kind "premise"
  "premise_id": "hq", "distance_m": 412.5, "location": { …wire record… } | null, "detail": "…" }
```

`pid` tells process restarts apart; `js` is true once a `PremiseMonitorPlugin` instance was loaded in that process
(F-09 checks entries with `js: false` after a kill). `PremiseStatus` =
`{monitoring, premise, inside (null = unknown), serviceRunning, auditUrl, lastEntryAt, pendingUploads}`.

---

## 8. e2e-kit TypeScript API

`testing/e2e-kit` (`@bricks-soft/e2e-kit`, private, ESM, `exports` → `src/index.ts`). Node ≥ 22.18 runs the `.ts`
sources directly (type stripping), so the kit and the suites use **erasable syntax only**: no enums, namespaces,
parameter properties or `import =`; relative imports carry `.ts`; type-only imports use `import type`
(`tsconfig`: `erasableSyntaxOnly`, `verbatimModuleSyntax`, `allowImportingTsExtensions`, `noEmit`, strict,
`module nodenext`). Dev dependencies: `typescript` 5.9.3 and `@types/node` 22.20.4 only; no runtime dependencies
(Node's global `WebSocket`, `fetch`, `node:http`, `node:child_process`). Suites depend on it with `file:`; npm links
it, and Node resolves the link to its real path (outside `node_modules`), which type stripping requires.

Scaffold state: `scenario()`, `readEnv()`, the catalogue and the CLIs' catalogue printer work; every other function
throws `Error('not implemented: …')`. The signatures in `src/*.ts` are the contract; unit 7 implements them. Summary:

| Module | API |
|---|---|
| `env.ts` | `APP_IDS {plugin, fieldForce}`, `EMULATOR_HOST`, `readEnv(): E2eEnv`, `appIdFor(id, env)` |
| `catalogue.ts` | `CATALOGUE`, `catalogueEntry(id)`, `catalogueOf(suite)` (mirror of §9) |
| `adb.ts` | `Adb` (`serial`, `exec`, `shell`, `emu`, `root`, `isRoot`, `getprop`, `apiLevel`, `dumpsys`, `waitForBoot`, `reboot`, `install({replace,grant})`, `uninstall`, `isInstalled`, `clearData`, `startApp`, `forceStop`, `stopApp`, `amKill`, `killHard` (root), `pidof`, `broadcast`, `grant`, `revoke`, `setAppOp`, `forward`, `removeForward`, `runAsCat`, `pull`, `bugreport`, `screenshot`, `setLocationEnabled`, `isLocationEnabled`, `geoFix(lat, lon)`, `playRoute(points, {speedMps, intervalMs})`, `addTestProvider`, `setTestLocation`, `removeTestProvider`, `batteryUnplug`, `batteryReset`, `batterySetLevel`, `setAirplaneMode`, `setWifi`, `setData`, `setAutoTime`, `setTime(ms)` (root), `setTimezone`, `deviceTime`, `setFontScale`, `keyHome`, `screenOff`, `screenOn`); `adb.deviceIdle` (`forceIdle`, `unforce`, `step`, `state`, `whitelistAdd`, `whitelistRemove`, `tempWhitelist`); `adb.logcat` (`clear`, `dump`, `crashBuffer`) |
| `webview.ts` | `WebViewDriver.connect(adb, appId)`, `evaluate<T>(expr)`, `callPlugin<T>(plugin, method, args)` (rejects with `PluginCallError{code}`), `captureEvents(plugin, names)`, `drainEvents(): CapturedEvent[]`, `reload()`, `close()` |
| `commands.ts` | `E2eCommands(adb, appId)`: `send<T>(cmd, args, {timeoutMs, foreground})` (rejects with `E2eCommandError{code}`) and typed helpers for every §6 command (`ready`, `setConfig`, `start`, …, `premiseStart`, `premiseAuditLog`) |
| `backoffice.ts` | `MockBackOffice({port, host, log})`: `start`, `stop`, `running`, `port`, `url(pathFromEmulator)`, `hostUrl(path)`, `records(filter)`, `premiseRecords(filter)`, `requests(filter)`, `waitFor(predicate, {timeoutMs})`, `setFault`, `clearFaults`, `reset`, `logText` |
| `assertions.ts` (exported as `assertions`) | `inOrder(records, matchers)`, `noDuplicates`, `heartbeatCadence(records, {minIntervalS, maxIntervalS, toleranceS, idlePaced})`, `gapsExplained(records, {maxGapS})`, `withinWindow(value, {from, to})`, `approximately`, `haversineMeters`, `routeLengthMeters`, `offsetMeters`, `travelSummary(records)` |
| `crash.ts` | `CrashScanner(adb, appId)`: `mark`, `crashes`, `assertNoCrash`, `assertNoFgsDidNotStartInTime` |
| `artifacts.ts` | `Artifacts({dir, adb, appId, backOffice, bugreport})`: `path`, `writeText`, `writeJson`, `collect(reason)` (logcat, crash buffer, dumpsys activity services/location/deviceidle/alarm/jobscheduler, the plugin's log files via run-as, back office log/records/premise entries, screenshot, optional bugreport) |
| `device.ts` | `detectDevice(adb): DeviceProfile {api, root, gms, emulator, model, abi}` |
| `app.ts` | `PERMISSIONS`, `SERVICES`, `AppUnderTest(adb, appId, env)`: `prepare({reinstall, clearData, permissions, batteryExempt, launch})` (also restores a neutral device: location on, airplane off, Wi-Fi/data on, deviceidle unforce, battery reset, auto time, font scale 1, no test providers), `launch`, `webView`, `pid`, `waitForProcess`, `waitForNoProcess`, `isForegroundServiceRunning(serviceClass)`, `reinstall`, `commands` |
| `fixtures.ts` | `PLACES`, `PREMISES.hq` (24.7136, 46.6753, 150 m), `ROUTES` (`cityLoop3km` 3000 m, `approachHq`, `leaveHq` 1500 m), `TEST_HEARTBEAT` (60/120), `TEST_SYNC_INTERVAL_S` (120), `pluginTestConfig(options)`, `FIELD_FORCE_OVERRIDES_KEY`, `FieldForceOverrides`, `FIELD_FORCE_TEST_OVERRIDES` |
| `util.ts` | `sleep`, `waitUntil(probe, {timeoutMs, intervalMs, message})`, `notImplemented` |
| `scenario.ts` | `scenario(id, title, fn, {timeoutMs, requires: {root?, api?, gms?, long?}, allowCrash?})`, `ScenarioContext`, `registeredScenarios`, `describeRequirements`, `unmetRequirement` |
| `types.ts` | `WireRecord`, `StateJson`, `HeartbeatStatusJson`, `GeofenceJson`, `LatLon`, `Premise`, `PremiseAuditEntry`, `PremiseStatus`, … |

**`scenario(id, title, fn, options)`** registers a `node:test` test named `<id> <title>`:
- `id` must be an automated catalogue id (P-*, F-*); unknown or duplicate ids throw at registration.
- `E2E_DRY_RUN=1`: prints `DRY-RUN <id> | <title> | requires: … | timeout …s`, registers a skipped test, needs no
  device.
- Otherwise: `requires.long` skips unless `E2E_INCLUDE_LONG=1`; the device profile (detected once per process) is
  checked against `root`, `api` (number = minimum, or `{min, max}`) and `gms` (`true` = needs Google Play services,
  `false` = needs an image without them); unmet requirements skip with a reason. Then it resets the shared back office
  (if started), marks the crash scanner, runs `fn(ctx)`, and asserts no crash of the app since the mark unless
  `allowCrash`. On failure it collects artifacts into `<E2E_ARTIFACTS_DIR>/<id>/` and rethrows.
- `ScenarioContext`: `id`, `title`, `env`, `appId`, `adb`, `app`, `commands`, `device`, `crashes`, `artifacts`, `t`
  (node `TestContext`), `signal`, `backOffice()` (shared per test process, started on first use, stopped after the
  file), `testConfig(options)` (`pluginTestConfig` with the back office URL and `persistence.extras.scenario = id`),
  `log(message)`.
- Each scenario sets up its own device state (`ctx.app.prepare(...)`); never rely on a previous scenario.

**Environment.** `E2E_SERIAL` (adb serial), `E2E_APP_ID` (override; default by id prefix: P-* →
`com.brickssoft.locationtracking.example`, F-* → `com.brickssoft.fieldforce.example`), `E2E_APK` (APK for
(re)install scenarios), `E2E_BACKEND_PORT` (8787), `E2E_INCLUDE_LONG`, `E2E_DRY_RUN`, `E2E_ARTIFACTS_DIR`
(`./e2e-artifacts`), `E2E_ADB` (default `$ANDROID_HOME/platform-tools/adb`), `E2E_BUGREPORT`.

**`pluginTestConfig({url, heartbeat?, syncInterval?, startOnBoot?, stopOnTerminate?, locationProvider?, jwt?, patch?})`**
(unit 7): `logger.logLevel 'debug'`; `heartbeat {enabled: true, …TEST_HEARTBEAT}`; `geolocation {desiredAccuracy
'high', distanceFilter 10, stopTimeout 1, stationaryRadius 25}`; `http {url, autoSync: true, syncInterval: 0,
batchSync: false, params: {e2e: true}}`; `app {startOnBoot: true, stopOnTerminate: false}`;
`locationProvider 'auto'`; with `jwt`, `http.authorization {accessToken: 'e2e-initial', refreshToken: 'e2e-refresh',
refreshUrl: <origin>/auth/refresh, refreshPayload: {refresh_token: '{refreshToken}'}}`; `patch` deep-merged last.

---

## 9. Scenario catalogue

Stable ids. Suites register exactly these with `scenario()` (titles may be refined); the runbook refers to them;
`testing/e2e-kit/src/catalogue.ts` mirrors this list. "Needs" is the `requires` the placeholders declare (suites may
tighten it). Default timeout 10 minutes.

**Plugin suite** (against `example/`, files `e2e/plugin/*.test.ts`).

*A. Lifecycle — `lifecycle.test.ts` (unit 9)*

| Id | Scenario | Needs |
|---|---|---|
| P-L01 | `start`/`stop` 50× in a rapid loop (debug commands): no FGS crash, `tracking_start`/`tracking_stop` pairs complete and ordered, no `ForegroundServiceDidNotStartInTimeException` | |
| P-L02 | `start`, then `force-stop` while the service is starting: no crash; relaunch + `ready()` gives a consistent state | |
| P-L03 | root `kill -9` while tracking: START_STICKY or the heartbeat alarm restores it: `tracking_start` reason `restore`, heartbeats resume | root |
| P-L04 | `force-stop` while tracking (alarms cancelled), relaunch: `ready()` restores (`tracking_start: restore`) | |
| P-L05 | activity recreation (font scale change): tracking not stopped, JS listeners get no duplicate events (`WebViewDriver.captureEvents`) | |
| P-L06 | POST_NOTIFICATIONS denied: the service runs (no visible notification), records flow | API 33 |
| P-L07 | app update (`install -r` of `E2E_APK`) with `startOnBoot` true → `tracking_start: package_replaced`; false → `tracking_stop: package_replaced` | |
| P-L08 | reboot with `startOnBoot: true` → `tracking_start: boot`, heartbeats resume | |
| P-L09 | reboot with `startOnBoot: false` → `tracking_stop: reboot` | |
| P-L10 | a fake `QUICKBOOT_POWERON` broadcast without a real boot (boot count unchanged) is ignored: no duplicate `tracking_start` (unit 1 hardening in `BootReceiver`) | |
| P-L11 | Android 12+ background start via the debug receiver (app in the background, not exempt) → `tracking_stop: service_start_failed`; after `deviceidle tempwhitelist` → starts | API 31 |
| P-L12 | the heartbeat PendingIntent alarm restores a killed process (`kill -9`, battery-exempt so the background FGS start is allowed) | root |
| P-L13 | busy main thread: `start` then `blockMainThread {ms: 4000}` around a cold service start → no `ForegroundServiceDidNotStartInTimeException` | |

*B. Heartbeat and power — `heartbeat.test.ts` (unit 10)*

| Id | Scenario | Needs |
|---|---|---|
| P-H01 | stationary heartbeat cadence with the test config (min 60 / max 120 s); `recorded_at` newer than `location.timestamp` | |
| P-H02 | while stationary, `dumpsys location` shows no active non-passive request attributed to the app | |
| P-H03 | movement (geo-fix route) turns GPS back on: `motionchange` with `is_moving: true`, location records follow | |
| P-H04 | deep Doze (`force-idle`), not battery-exempt → `idle_paced`, heartbeats ≥ 9 min apart | long |
| P-H05 | deep Doze, battery-exempt → `exact`, cadence ≈ `minInterval` | |
| P-H06 | wall-clock jump and time-zone change → heartbeat cadence unaffected; `boot_count`/`elapsed_realtime_ms` consistent | root |
| P-H07 | airplane mode → records queue → uploaded after reconnect with their original `recorded_at` and a later `sent_at` | |
| P-H08 | server 500 then 200 → retried; 401 → `/auth/refresh` → retried with the new token | |
| P-H09 | `syncInterval` batching while moving (normal records ≤ syncInterval late, in batches); audit records immediate | |
| P-H10 | heartbeat records carry the `heartbeat` metadata object (§5) consistent with `getHeartbeatStatus()` | |

*C. Permissions, providers and geofences — `permissions-providers.test.ts` (unit 11)*

| Id | Scenario | Needs |
|---|---|---|
| P-P01 | revoke fine location (keep coarse) → Android kills the process → relaunch → `providerchange` with accuracy `approximate` | API 31 |
| P-P02 | revoke all location while tracking → the restore records `tracking_stop: permission_denied` | |
| P-P03 | revoke background location → `providerchange` permission `when_in_use`; background behavior documented | API 29 |
| P-P04 | revoke ACTIVITY_RECOGNITION → tracking continues; motion detected from distance | API 29 |
| P-P05 | revoke POST_NOTIFICATIONS while tracking → tracking continues | API 33 |
| P-P06 | location services off → `providerchange enabled=false`; on → `providerchange` + geofences re-registered (geofence still fires) | |
| P-P07 | `locationProvider` `auto` → `android` → `gms` at runtime → `backend` changes in the records | gms |
| P-P08 | image without Google Play services (`default` target) → backend `android`, tracking works (CI no-GMS job only) | no-gms |
| P-P09 | mock locations (test provider) → `mock: true`; `rejectMockLocations` drops them | |
| P-P10 | circular geofence ENTER / EXIT / DWELL via geo fixes | |
| P-P11 | geofences re-registered after a reboot (a geofence fires after boot) | |

**Field-force suite** (`examples/field-force/e2e/field-force.test.ts`, unit 14; app contract in §10).

| Id | Scenario | Needs |
|---|---|---|
| F-01 | app launch auto-starts tracking: `tracking_start` + `motionchange`; device details in the request `params`; battery in every record | |
| F-02 | 02:00 stop via test override (`stopAt` = now + 2 min) → `tracking_stop: stop_after_elapsed` at that time; minutes come from the device clock (clock set to 01:58 → `stopAfterElapsedMinutes` 2) | root |
| F-03 | the 02:00 stop still happens after a process kill (restore) and after a reboot (measured from the session start) | root |
| F-04 | route replay (`cityLoop3km`): uploads batched within `syncInterval` (test 120 s); odometer ≈ route length ±10%; travel time computable from the records | |
| F-05 | stationary: no GPS; heartbeats every 60–120 s (production 3–5 min) with an older `location.timestamp` | |
| F-06 | online/offline audit on the server: heartbeat cadence while on; `stop` → `tracking_stop`; gaps explained | |
| F-07 | premise ENTER (geo fix inside) → PremiseMonitor service running; `/premise-audit` receives `enter` and every later record and event (heartbeats included) | |
| F-08 | app backgrounded and activity destroyed → PremiseMonitor still receives records and events natively | |
| F-09 | root `kill -9` inside the premise → process restored → the listener receives records/events with `js: false` before any JS runs | root |
| F-10 | reboot inside the premise → the listener receives `tracking_start: boot` and heartbeats; PremiseMonitor service restored | |
| F-11 | premise EXIT → `exit` entry; PremiseMonitor service stops | |
| F-12 | presence validation: a fix outside the radius while "inside" → `presence_violation` entry | |

**Manual (runbook only, `docs/e2e-runbook.md`, unit 15).**

| Id | Scenario |
|---|---|
| M-01 | HMS on a Huawei phone (backend `hms`, activity, geofences) |
| M-02 | OEM task killers (Xiaomi / Huawei / Samsung) with and without the power-manager allowlist |
| M-03 | real drive: server distance vs odometer vs map |
| M-04 | 12 h battery measurement (`batterystats`, % per hour) with the field-force preset |
| M-05 | real overnight 02:00 stop |
| M-06 | Android 14+ real boot with only while-in-use location permission |
| M-07 | Play build (gms only): 16 KB page-size alignment |
| M-08 | real overnight Doze heartbeat spacing |

**Split.** Plugin behavior (lifecycle, heartbeat, permissions, providers, geofences) is tested against the plugin's
example app, so it does not depend on the field-force app. The field-force suite tests the product setup (auto start,
02:00, live location, companion plugin) and can move into the Bricks app later as an integration test, together with
`testing/e2e-kit` (or the kit published to a private registry).

---

## 10. Field-force app and PremiseMonitor

### Field-force app (`examples/field-force/`, unit 12)

`com.brickssoft.fieldforce.example`, "Field Force Example", no bundler: `www/index.html` loads `env.js`,
`vendor/capacitor.js`, `vendor/plugin.js` (global `capacitorLocationTracking`), `vendor/premise-monitor.js` (global
`capacitorPremiseMonitor`) and `app.js`. `npm run copy-vendor` writes `www/env.js` =
`window.FF_ENV = {backendUrl: $FF_BACKEND_URL || 'http://10.0.2.2:8787'}`. Android: gms only
(`locationTracking.providers=gms`), Kotlin applied, shared debug keystore.

**Startup (every page load; this is the auto start):**
1. Read overrides: the test-mode file `files/e2e/ff-overrides.json` (§6), else
   `JSON.parse(localStorage['ff.e2e.overrides'] || '{}')` (`FieldForceOverrides`, fixtures.ts).
2. `state = await LocationTracking.getState()` (allowed before `ready`).
3. `stopAfterElapsedMinutes`: if `state.enabled`, keep `state.config.geolocation.stopAfterElapsedMinutes` (the engine
   measures it from the session start, so recomputing it would move the stop); otherwise
   `minutesUntil(stopAt = overrides.stopAt ?? '02:00')` = `ceil((next local HH:MM − now) / 60000)`, where the next
   occurrence is later today or tomorrow (never 0).
4. `deviceInfo = await LocationTracking.getDeviceInfo()`.
5. `await LocationTracking.ready({config: preset, reset: true})` with the preset below.
6. `await LocationTracking.requestPermissions()` when anything is not granted (a no-op on e2e devices, which grant
   everything with `pm grant`).
7. Unless `overrides.autoStart === false`: `start()` when the state after `ready()` is not enabled.
8. If `overrides.premise`: `PremiseMonitor.startMonitoring({premise, auditUrl: backendUrl + '/premise-audit'})`.
9. `window.FF_APP.startup` is a promise that resolves with `{state, stopAfterElapsedMinutes, config, deviceInfo}` (or
   rejects with the error) so tests can `await` it through `WebViewDriver.evaluate`.

**Preset** (production values; overrides in brackets, `configPatch` deep-merged last):
- `geolocation`: `desiredAccuracy 'high'`, `distanceFilter 20`, `stationaryRadius 50`, `stopTimeout 5`,
  `stopAfterElapsedMinutes` (step 3), `filter.trackingAccuracyThreshold 50`;
- `heartbeat`: `{enabled: true, minInterval: 180, maxInterval: 300}` [`overrides.heartbeat`];
- `http`: `url backendUrl + '/locations'`, `autoSync true`, `syncInterval 300` [`overrides.syncInterval`],
  `batchSync true`, `maxBatchSize 100`, `params {worker_id: 'field-force-example', device: {manufacturer, model,
  brand, osVersion, sdkInt, pluginVersion, backend, gmsAvailable, hmsAvailable}}`, `authorization {strategy 'JWT',
  accessToken 'ff-initial', refreshToken 'ff-refresh', refreshUrl backendUrl + '/auth/refresh', refreshPayload
  {refresh_token: '{refreshToken}'}}`;
- `app`: `{stopOnTerminate: false, startOnBoot: true}`; `notification`: `{title: 'Field Force', text: 'Shift tracking
  is on'}`; `logger.logLevel 'debug'`; `locationProvider 'auto'`.
- `backendUrl` = `overrides.backendUrl ?? FF_ENV.backendUrl`.

The UI shows the state, the stop time, the premise status and the last records; it is not tested beyond `FF_APP`.
The debug receiver (§6) adds the `premise.*` commands via `PremiseMonitorNative`.

### PremiseMonitor (`examples/field-force/plugins/premise-monitor/`, unit 13)

npm package `@bricks-soft/capacitor-premise-monitor` (hand-written `dist/plugin.js`, IIFE global
`capacitorPremiseMonitor`), Gradle project `:bricks-soft-capacitor-premise-monitor` depending on
`:bricks-soft-capacitor-location-tracking`. Classes (package `com.brickssoft.premisemonitor`):
- `PremiseMonitorPlugin` (`@CapacitorPlugin(name = "PremiseMonitor")`): `startMonitoring({premise, auditUrl?})`,
  `stopMonitoring()`, `getStatus()`, `getAuditLog({limit?})` → `{entries}`; thin layer over `PremiseMonitorNative`;
  its `load()` marks the process as `js: true`.
- `PremiseMonitorNative` (object): `start(context, premise, auditUrl, cb)`, `stop(context, cb)`, `status(context, cb)`,
  `auditLog(context, limit, cb)`; results are PremiseStatus JSON / entries array.
- `PremiseAuditListener : LocationTrackingListener`, declared with
  `<meta-data android:name="com.brickssoft.locationtracking.LISTENER" android:value="com.brickssoft.premisemonitor.PremiseAuditListener"/>`.
- `PremiseMonitorService`: foreground service, `foregroundServiceType="location"`, own channel `premise_monitor`.

Behavior:
- `start` persists `{premise, auditUrl}` (SharedPreferences), adds the geofence `premise:<id>` (circle, ENTER + EXIT)
  with `LocationTrackingNative.addGeofence`, and writes `monitoring_started`. `stop` removes the geofence, stops the
  service and writes `monitoring_stopped`.
- Audit: every `onRecord` → a `record` entry; every `onEvent` → an `event` entry (`source: 'manifest'`). Entries are
  kept in a bounded local log (at least the last 1000) and POSTed in order to `auditUrl` as `{device_id, entries}`;
  failed uploads stay pending and are retried with the next entry or after 30 s.
- Enter/exit: a `geofence` record for `premise:<id>` with ENTER → `inside = true`, `enter` entry, start the service
  (`service_started`, or `service_start_failed` with the exception in `detail`); EXIT → `inside = false`, `exit`,
  stop the service (`service_stopped`). `inside` is persisted; in a new process (restore, boot) the listener restarts the
  service if monitoring and inside (F-10).
- Presence validation: while inside, every record with coords whose `distance − accuracy > radius` writes a
  `presence_violation` entry with `distance_m` and the record.
- Background start of the service: it starts from the listener while our tracking service is in the foreground (the
  app's process state is then a foreground-service state, which may start further foreground services) or within the
  geofence-transition allowance; F-07/F-10 verify it on the AVD. A refusal is audited, never a crash.

---

## 11. Commands and recipes

**Unit verification in this container** (no emulator):

```bash
export ANDROID_HOME=/opt/android-sdk
npm ci --prefer-offline --no-audit --no-fund && npm run build && npm test          # plugin TS
npm run docgen                                                                        # only after changing definitions.ts
scripts/gradle-slot.sh -p android testDebugUnitTest                                   # plugin unit tests
(cd example && npm ci && npm run sync && cd android && ../../scripts/gradle-slot.sh assembleDebug -PlocationTracking.providers=gms,hms)
(cd examples/field-force && npm ci && npm run sync && cd android && ../../../scripts/gradle-slot.sh assembleDebug)
(cd examples/field-force/android && ../../../scripts/gradle-slot.sh :bricks-soft-capacitor-premise-monitor:testDebugUnitTest)  # PremiseMonitor Robolectric tests (after the line above)
(cd testing/e2e-kit && npm ci && npm run typecheck && npm test)
(cd e2e/plugin && npm ci && npm run typecheck && npm run dry-run)
(cd examples/field-force/e2e && npm ci && npm run typecheck && npm run dry-run)
```

Build the example apps after `npm run build` at the root (they copy `dist/plugin.js`). The debug APKs are
`example/android/app/build/outputs/apk/debug/app-debug.apk` and
`examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk`.

**Running the suites against a device** (CI or a machine with an AVD):

```bash
emulator -avd e2e-34 -no-snapshot -no-boot-anim &          # google_apis x86_64 image: adb root works
adb wait-for-device && adb root
adb install -r -g example/android/app/build/outputs/apk/debug/app-debug.apk
cd e2e/plugin && E2E_APK=../../example/android/app/build/outputs/apk/debug/app-debug.apk npm run test:e2e
npm run test:e2e -- --test-name-pattern="P-L0[13]"          # a subset by id
E2E_INCLUDE_LONG=1 npm run test:e2e                         # include long scenarios (nightly)
cd examples/field-force/e2e && E2E_APK=../android/app/build/outputs/apk/debug/app-debug.apk npm run test:e2e
```

The suites start the mock back office themselves (host port 8787). For manual work run it standalone:
`cd testing/e2e-kit && npm run backoffice -- --port 8787`, then `curl localhost:8787/__records?event=heartbeat`.

**CI (unit 6, `.github/workflows/**`).** Keep the existing build job. Add emulator jobs on `ubuntu-latest` with KVM
(enable `/dev/kvm` via udev) and `reactivecircus/android-emulator-runner@v2`:
- plugin suite on API 34 `google_apis` x86_64 (root, GMS) — the main job; a smaller run on API 29 and API 35/36;
- P-P08 on a `default` (no-GMS) image with `--test-name-pattern=P-P08`;
- field-force suite on API 34 `google_apis`;
- long scenarios (`E2E_INCLUDE_LONG=1`) on a nightly `schedule` and `workflow_dispatch`;
- upload `e2e-artifacts/**` on failure;
- a 16 KB check: build the field-force release APK (gms only), `zipalign -c -P 16 -v 4`, and check every packaged
  `.so` has ELF `LOAD` alignment ≥ 2^14 (`llvm-readelf -l` from the NDK or an equivalent script).
Dry runs and typechecks of the kit and both suites run in the normal build job.

**Runbook (unit 15, `docs/e2e-runbook.md`).** How an AI agent runs the automated suites on a local AVD (setup, the
commands above, reading artifacts), and step-by-step procedures with pass criteria for M-01…M-08 using the standalone
back office's control endpoints.
