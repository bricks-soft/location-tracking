# RFC: iOS implementation

Status: proposal. Design only; no code.

## Goal

Implement the existing plugin API (8.1.0) natively on iOS, so that code written against the README tracks the same
way on iOS as on Android, within the limits listed below. The package has no iOS sources today. This RFC covers
plugin behavior only; what an app or its server does with the records is out of scope.

## Decisions

- **Same public API as Android.** No session API, no listener wrapper. The only new method is
  `requestTemporaryFullAccuracy`. Switching users follows the README logout sequence.
- **iOS 15.0 deployment target** (Capacitor 8's floor). Route tracking uses `CLLocationManager` on every version.
  Geofencing uses `CLMonitor` behind one `if #available(iOS 17, *)` check; below iOS 17, `startGeofences` and the
  geofence methods reject with `UNAVAILABLE`.
- **Stationary profile: continuous.** Low-accuracy updates keep running while stationary; automatic pausing is off.
- **Stop time:** the app passes `stopAfterElapsedMinutes`; the plugin has no civil-time cutoff. A mode switch starts a
  new window, as on Android.
- **Geofence capacity: 20**, the native limit. Extra geofences reject with `TOO_MANY_GEOFENCES`; no rotation.
- **`device.device_id` is `identifierForVendor`.**
- **Core Motion confidence maps low/medium/high to 33/66/100**, the values Transistor reports on iOS. With the
  default `minimumActivityRecognitionConfidence` of 75 only high counts, like Transistor's iOS default of 70; a
  threshold of 66 or lower also accepts medium.

## Approach

**Ownership.** A native core owns the engine, storage, uploader and Core Location objects, independent of the
WebView. `LocationTrackingNative.bootstrap()` creates it; the host calls it from
`application(_:didFinishLaunchingWithOptions:)`, and the plugin's `load()` calls it too (idempotent). State changes run
on one serial queue; location managers live on the main thread.

**Route tracking.** One `CLLocationManager` with `allowsBackgroundLocationUpdates = true` and
`pausesLocationUpdatesAutomatically = false`. While moving it uses the configured `desiredAccuracy` with no OS
distance filter; the engine applies `distanceFilter`, elasticity and the fix filters as on Android. While stationary it
uses `kCLLocationAccuracyHundredMeters`, so fixes stay within the default 100 m `trackingAccuracyThreshold` and
displacement can still detect motion without motion data. Engine and processing classes are ports of the Kotlin
ones.

**Motion.** `CMMotionActivityManager`: automotive → `in_vehicle`, cycling → `on_bicycle`, running, walking,
stationary → `still`, else `unknown`; with several flags, the first in that order wins. Without motion access,
displacement alone drives the state.

**Heartbeat.** A `DispatchSourceTimer` armed from a port of `HeartbeatWindow`, including its 1 s tolerance. It fires
only while the process runs; missed heartbeats are not backfilled. A background-task assertion keeps a heartbeat
upload running for at most `heartbeat.uploadKeepAwake` seconds.

**Termination and restore.**

- `stopOnTerminate: true` (default): on `UIApplication.willTerminateNotification` the core persists off-intent in a
  bounded write and records `tracking_stop` reason `terminate`. Without that notification, process loss counts as an
  OS kill.
- `stopOnTerminate: false`: restore after any process loss.
- While tracking is on, a second `CLLocationManager` runs significant-change monitoring purely as a relaunch trigger.
  Its fixes are not recorded.
- On any launch, the core restores persisted enabled intent and records `tracking_start` reason `restore`. If the
  `stopAfterElapsedMinutes` deadline has passed, it records `tracking_stop` reason `stop_after_elapsed` and starts
  nothing, as Android does. Starting updates in the background needs Always authorization; with When In Use, the
  restore waits until the app is next active.
- The deadline uses `mach_continuous_time` (sleep included) within a boot and the wall-clock start after a reboot,
  like Android.

**Geofences (iOS 17+).** A port of `DefaultGeofenceManager` over one named `CLMonitor`. Definitions are validated and
stored at any time and registered when tracking starts. Circles become circular conditions; a polygon registers its
enclosing circle and is hit-tested against fixes; DWELL is always synthesized, since `CLMonitor` has no dwell. The
monitor is re-created under the same name at every launch. On iOS 18+, a retained `CLServiceSession` with Always
authorization covers events while the app is not in use. With approximate accuracy, stored geofences stay inactive
and a `locationerror` event says why.

**HTTP sync.** A port of `OkHttpSyncer` on an ephemeral `URLSession`: redirects refused (3xx is a failed upload), no
transport retries, `http.timeout` per request. Any 2xx deletes the uploaded records whatever the body says; the `http`
event carries at most 16 KB of the response. Retry, `Retry-After`, stored backoff, token refresh, credential-change
and 413 rules match Android. Bodies come from the host-supplied templates. When tracking is off, queued records upload
at the next launch or in a best-effort `BGAppRefreshTask`.

**Storage.** SQLite in Application Support, excluded from backup, protected until first unlock so background
launches can write while locked. Tokens go in the Keychain.

**Native companion API.** Swift `LocationTrackingNative` and `LocationTrackingListener` mirror the Kotlin API;
listeners added before `bootstrap()` see restored records, before the JS events.

## File map

New Swift target `ios/Sources/LocationTrackingPlugin/`:

| Files | Responsibility |
|---|---|
| `LocationTrackingPlugin.swift` | `CAPBridgedPlugin` method table, argument parsing, JS events, watch callbacks |
| `Core/` | Composition root, serial queue, bootstrap, lifecycle notifications |
| `Engine/` | `TrackingEngine` (start, stop, mode switch, restore, deadline), `MotionStateMachine`, `ElasticDistance` |
| `Location/` | `RouteLocationSource`, `RelaunchMonitor` (significant change), `MotionActivitySource` |
| `Processing/`, `Record/`, `Heartbeat/` | Filters, Kalman, odometer, record factory, `RecordSink` order, heartbeat window and timer |
| `Geofence/` | `GeofenceManager`, `GeofenceMonitor` (`CLMonitor`, service session), `PolygonMath`, `DwellTracker` |
| `Data/`, `Config/`, `Http/` | SQLite stores, config and Keychain secrets, syncer, templates, token refresh, background task |
| `Permission/`, `Position/`, `Device/`, `Logging/`, `Api/` | Prompts and precision, positions and watches, device readings, logger, native API |
| `PrivacyInfo.xcprivacy` | Required-reason APIs (boot time, disk space, user defaults) |

Also: root `Package.swift` and podspec, `package.json` (`files`, `capacitor.ios.src`), `ios/Tests/` (XCTest), an iOS
host in `example/`.

TypeScript: `src/definitions.ts` adds `requestTemporaryFullAccuracy({ purposeKey }): Promise<ProviderState>` and widens
`LocationBackend` (`'corelocation'`), `DeviceInfo.platform` (`'ios'`) and `HeartbeatStrategy`/`HeartbeatMeta.strategy`
(`'timer'`). `src/web.ts` and the Android plugin reject the new method with `UNIMPLEMENTED`.
`testing/e2e-kit/src/types.ts` follows. The README gains an iOS setup section.

Host setup: `UIBackgroundModes` `location` and `fetch`, `BGTaskSchedulerPermittedIdentifiers`, the When In Use,
Always and Motion usage strings, `NSLocationTemporaryUsageDescriptionDictionary` if the precision method is used, and
the `bootstrap()` call.

## Android parity

Everything not listed behaves as on Android.

| Surface | Android | iOS |
|---|---|---|
| `desiredAccuracy` | Provider priorities | `high` Best, `balanced` 100 m, `low` 1 km, `passive` 3 km |
| Update intervals, `locationProvider`, `notification.*`, `backgroundPermissionRationale` | Used | Ignored; `notificationaction` never fires |
| `app.stopOnTerminate` | Task removal | `willTerminate`, best-effort |
| `app.startOnBoot` | Restores, reason `boot` | No-op; a later relaunch restores with reason `restore` |
| Start/stop reasons | `boot`, `reboot`, `package_replaced`, `resume_notification`, `service_start_failed`, `user_stopped` | Not produced |
| Heartbeat strategy | `exact`, `listener_with_backup`, `idle_paced` | `timer` |
| Geofences | 100, all versions | 20, iOS 17+ |
| Records | `boot_count`, Android readings | `boot_count` -1; IDFV omitted while nil, never substituted; model, OS version, RAM, free disk, thermal state |
| `ProviderState` | Per-provider flags | `gps` and `network` mirror the Location Services switch; `backend` `corelocation` |
| Power methods | Battery Saver, optimization, OEM managers | Low Power Mode; nothing to exempt from; `open*Settings` reject `UNIMPLEMENTED`, except `openLocationSettings` (app Settings) |
| `requestPermissions` | Android permissions | When In Use, Always, Motion, notifications; settles when the app is active again even if the Always prompt changed nothing |
| `requestTemporaryFullAccuracy` | `UNIMPLEMENTED` | Prompts with the host's purpose key, resolves with the actual accuracy |

## iOS limitations

- Nothing relaunches an app the user force-quit; tracking resumes when it is opened, if settings and deadline allow.
- `applicationWillTerminate` is not guaranteed, and iOS may also send it when the system ends a running background
  app; with `stopOnTerminate: true` that stops tracking too.
- Significant-change relaunch needs Always authorization and movement of several hundred metres; a killed stationary
  app can stay dark.
- Heartbeats and uploads run only while iOS lets the app run, never before the first unlock after a reboot.
- `identifierForVendor` changes once all of the vendor's apps are removed and one is reinstalled; servers should not
  treat it as permanent.

## Test plan

1. **Unit (XCTest):** the Android unit tests for motion, filters, odometer, heartbeat window (179 s vs 180 s), polygon,
   dwell, upload wait and templates, ported with the same inputs and outputs; confidence boundaries (thresholds 33,
   66, 67); the deadline across mode switch, restore, reboot and clock changes.
2. **Storage and HTTP:** SQLite reopen and interrupted writes; real `URLSession` against the e2e kit's mock server: 2xx
   with a large body, 3xx, 401 refresh, token change mid-upload, 413, `Retry-After`, timeout, stable UUIDs on retry.
3. **Package and bridge:** `npm pack` tarball in a fresh Capacitor 8 app via SPM and CocoaPods; every method, the
   `NOT_READY` allowlist, listeners, watches surviving `stop()`. Existing TS and Android tests still pass.
4. **Runtimes:** route tracking on an iOS 15 or 16 runtime and on the current iOS; geofences `UNAVAILABLE` below 17,
   `CLMonitor` on 17, service session on 18+.
5. **Physical device, Release, no debugger:** locked route moving and stationary, motion denied, approximate accuracy,
   offline replay, force-quit with `stopOnTerminate: true`, battery drain over a full day.
6. **Field-force F-03 equivalent** (`stopOnTerminate: false`, stop a few hours ahead): `SIGKILL` the backgrounded
   process and move; expect `tracking_start` `restore` with the original deadline. Reopen the app; expect the same
   `stopAfterElapsedMinutes`. Reboot, unlock and move; record whether a relaunch restored tracking. After the deadline,
   expect one `tracking_stop` `stop_after_elapsed` and no restart.

Implementation follows in three PRs: package, bridge, storage, HTTP and positions; route engine, motion, heartbeat and
restore; geofences.

## Open

- Cross-platform `device_id` normalization stays open, as on Android.
