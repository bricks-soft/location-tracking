# iOS implementation

Status: approved. The implementation follows in one PR stacked on this one; both merge together.

Implement the existing plugin API (8.1.0) natively on iOS, with Android behavior as the baseline and the limits below.
App and server policy is out of scope.

## Decisions

- **When In Use is recommended**; optional Always enables geofence events and relaunch while the app is not running.
- **Same public API as Android**, plus `requestTemporaryFullAccuracy`. No session API or listener wrapper; use the README logout sequence.
- **iOS 15.0 deployment target** (Capacitor 8's floor). Route tracking uses `CLLocationManager` on every version.
  Geofencing uses `CLMonitor` behind one `if #available(iOS 17, *)` check; below iOS 17, `startGeofences` and the
  geofence methods reject with `UNAVAILABLE`.
- **Stationary profile: continuous.** Low-accuracy updates keep running while stationary; automatic pausing is off.
- **Stop time:** `stopAfterElapsedMinutes`, no civil-time cutoff. A mode switch starts a new window, as on Android.
- **Geofence capacity: 20**, the native limit. Extra geofences reject with `TOO_MANY_GEOFENCES`; no rotation.
- **`device.device_id` is `identifierForVendor`.**
- **Core Motion confidence maps low/medium/high to 33/66/100**. Default threshold 75 accepts only high; ≤66 also accepts medium.

## Approach

**Ownership.** A native core owns tracking, storage and uploads independently of the WebView. The host calls
`LocationTrackingNative.bootstrap()` from `application(_:didFinishLaunchingWithOptions:)`; plugin `load()` also calls
it idempotently. State changes use one serial queue; location managers live on the main thread.

**Route tracking.** One `CLLocationManager` with `allowsBackgroundLocationUpdates = true` and
`pausesLocationUpdatesAutomatically = false`. While moving it uses the configured `desiredAccuracy` with no OS
distance filter; the engine applies `distanceFilter`, elasticity and the fix filters as on Android. While stationary it
uses `kCLLocationAccuracyHundredMeters`, so fixes stay within the default 100 m `trackingAccuracyThreshold` and
displacement still detects motion without motion data. Engine and processing classes port the Kotlin ones.

**Authorization.** `requestPermissions` maps `location` to When In Use, `backgroundLocation` to Always (one prompt per
install) and `activityRecognition` to Motion, in Android's order; `{ permissions: ['location'] }` asks When In Use
only. Under When In Use, updates started in the foreground continue in the background (`location` background mode,
`allowsBackgroundLocationUpdates`) with the blue indicator. Without Always, the app loses geofence events while it is
not running and the significant-change relaunch after a kill or reboot; tracking resumes when the app is opened or from
the check-in notification.
Apps that never ask for Always still ship `NSLocationAlwaysAndWhenInUseUsageDescription`: the plugin references
`requestAlwaysAuthorization`, and App Review requires purpose strings for APIs a library references
([Apple](https://developer.apple.com/documentation/uikit/requesting-access-to-protected-resources)).

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
- With Always, while tracking is on, a second `CLLocationManager` runs significant-change monitoring purely as a
  relaunch trigger. Its fixes are not recorded.
- On any launch, the core restores persisted enabled intent and records `tracking_start` reason `restore`. If the
  `stopAfterElapsedMinutes` deadline has passed, it records `tracking_stop` reason `stop_after_elapsed` and starts
  nothing, as Android does. Starting updates in the background needs Always authorization; with When In Use, the
  restore waits until the app is next active.
- **Check-in notification** (Android's resume notification): opt-in through `notification.resume` `enabled`, `title`
  and `text`, plus iOS-only `delay` (s, default 600; below `heartbeat.maxInterval` + 60 it is raised to that with a
  warning, as Android's `ConfigValidator` clamps). While tracking runs, one local notification
  (`UNTimeIntervalNotificationTrigger`) stays pending `delay` ahead; each fix or record moves it out (at most once a
  minute), warning when records stop, including process loss with `stopOnTerminate: false`. It is cancelled on `stop()`, `terminate` and the session's end, never scheduled past the
  deadline, and needs notification permission; a delivered one is removed when tracking starts. A tap opens the app
  and the launch restore resumes the session, recorded as `restore`: Capacitor's `NotificationRouter` owns the
  notification-center delegate, so detecting the tap would mean taking it over.
- Deadlines use `mach_continuous_time` (sleep included). Restore retains elapsed timing when saved wall/elapsed
  checkpoints match; a reboot or clock discontinuity falls back to the original wall-clock start. A clock change
  while the process is absent cannot be distinguished reliably from reboot.

**Geofences (iOS 17+).** A port of `DefaultGeofenceManager` over one named `CLMonitor`. Definitions are validated and
stored at any time and registered when tracking starts. Circles become circular conditions; a polygon registers its
enclosing circle and is hit-tested against fixes; DWELL is always synthesized, since `CLMonitor` has no dwell. The
monitor is re-created under the same name at every launch.
On iOS 18+, the plugin creates and retains an Always CLServiceSession only after Always permission has already been granted.
It covers events while the app is not in use; When In Use receives events only while it runs. Approximate accuracy
keeps stored geofences inactive and emits `locationerror`.
After relaunch, iOS restores unchanged circles without repeating ENTER when they remain inside, even with `initialTriggerEntry: true`; Android/GMS may repeat the initial ENTER when re-registering.

**HTTP sync.** Ephemeral `URLSession`, redirects refused, `http.timeout` per request. URLSession may retry internally;
the transport adds no retry loop. The syncer retries network/429/5xx failures up to three times per batch, then applies
stored backoff and `Retry-After`; token refresh, credential changes and 413 splitting follow Android. Any 2xx deletes
the uploaded records; `http` carries at most 16 KB of response text. Delivery is at least once: deduplicate by UUID.
Bodies use host templates. With tracking off, uploads run at launch or in best-effort `BGAppRefreshTask`
`com.brickssoft.locationtracking.refresh`.

**Storage.** SQLite in Application Support, excluded from backup, protected until first unlock so background
launches can write while locked. Tokens go in the Keychain. **Native API:** Swift `LocationTrackingNative` and
`LocationTrackingListener` mirror the Kotlin API; listeners added before `bootstrap()` see restored records first.

## Integration

`ios/Sources/LocationTrackingPlugin/` owns the bridge, core, engine, geofences, storage, HTTP and native API.
SPM and CocoaPods package it; `ios/Tests/` and the `example/ios` host cover native and WebView paths.
`PrivacyInfo.xcprivacy` declares UserDefaults access. Disk-space telemetry is omitted; `storagefull` remains, with `diskFreeBytes: null`.

TypeScript: `src/definitions.ts` adds `requestTemporaryFullAccuracy({ purposeKey }): Promise<ProviderState>` and widens
`LocationBackend` (`'corelocation'`), `DeviceInfo.platform` (`'ios'`) and `HeartbeatStrategy`/`HeartbeatMeta.strategy`
(`'timer'`), and adds `notification.resume.delay`. `src/web.ts` and the Android plugin reject the new method with
`UNIMPLEMENTED`. `testing/e2e-kit/src/types.ts` follows. The README gains an iOS setup section.

Host setup: `UIBackgroundModes` `location` and `fetch`, `BGTaskSchedulerPermittedIdentifiers` with
`com.brickssoft.locationtracking.refresh`, the When In Use, Always (see Authorization) and Motion usage strings,
`NSLocationTemporaryUsageDescriptionDictionary` if the precision method is used, and the `bootstrap()` call.

## Android parity

Everything not listed behaves as on Android.

| Surface | Android | iOS |
|---|---|---|
| `desiredAccuracy` | Provider priorities | `high` Best, `balanced` 100 m, `low` 1 km, `passive` 3 km |
| Update intervals, `locationProvider`, `notification.*` except `resume`, `backgroundPermissionRationale` | Used | Ignored; `notificationaction` never fires |
| `notification.resume` | Posted after a refused restore; tap records `resume_notification` | Check-in notification scheduled ahead; tap restores (`restore`) |
| `app.stopOnTerminate` | Task removal | `willTerminate`, best-effort |
| `app.startOnBoot` | Restores, reason `boot` | No-op; a later relaunch restores with reason `restore` |
| Start/stop reasons | `boot`, `reboot`, `package_replaced`, `resume_notification`, `service_start_failed`, `user_stopped` | Not produced |
| Heartbeat strategy | `exact`, `listener_with_backup`, `idle_paced` | `timer` |
| Geofences | 100, all versions | 20, iOS 17+ |
| Records | `boot_count`, Android readings | `boot_count` -1; IDFV omitted while nil, never substituted; model, OS version, RAM, thermal state |
| `ProviderState` | Per-provider flags | `gps` and `network` mirror the Location Services switch; `backend` `corelocation` |
| Power methods | Battery Saver, optimization, OEM managers | Low Power Mode; `openBatteryOptimizationSettings` and `openPowerManagerSettings` resolve `{ opened: false }` |
| `openAppSettings`, `openLocationSettings` | Own screens | Both open the app's Settings page (`openSettingsURLString`), `{ opened: true }` |
| `requestPermissions` | Android permissions | When In Use, Always, Motion, notifications (see Authorization); settles when the app is active again even if the Always prompt changed nothing |
| `requestTemporaryFullAccuracy` | `UNIMPLEMENTED` | Prompts with the host's purpose key, resolves with the actual accuracy |

## iOS limitations

- Nothing relaunches a force-quit app, or any killed app without Always; tracking resumes when it is opened, if
  settings and deadline allow. With Always, the significant-change relaunch needs movement of several hundred metres;
  a killed stationary app can stay dark.
- `applicationWillTerminate` is not guaranteed, and iOS may also send it when the system ends a running background
  app; with `stopOnTerminate: true` that stops tracking too.
- DWELL is synthesized, so it fires only while the process runs, even with Always.
- Fix timestamps are Core Location wall time. Elapsed ordering is inferred at delivery; delayed fixes spanning a
  clock change lack a reliable per-fix monotonic timestamp.
- Heartbeats and uploads run only while iOS lets the app run, never before the first unlock after a reboot.
- `identifierForVendor` changes once all of the vendor's apps are removed and one is reinstalled; servers should not
  treat it as permanent.

## Test plan

1. **XCTest:** Android fixtures for motion, filters, odometer, heartbeat, polygons, dwell and templates; confidence
   thresholds 33/66/67; deadline mode switches, restore, reboot and clock changes; delayed geofence restoration.
2. **Storage/HTTP:** SQLite reopen/interrupted writes; real URLSession against the mock server: 2xx/large body,
   redirects, 401 refresh, credential changes, 413, Retry-After, timeout and stable UUIDs on retry.
3. **Package/bridge:** npm tarball in fresh Capacitor 8 SPM/CocoaPods apps; every method, NOT_READY allowlist,
   listeners and watches surviving stop. Existing TS and Android tests must pass.
4. **Runtimes:** routes on iOS 15/16 and current iOS; geofences unavailable below 17, CLMonitor on 17, session on 18+.
5. **Simulator CI:** moving/stationary routes, background, denied Motion, approximate accuracy, offline replay,
   kill/terminate with both stopOnTerminate values, restart; check-in reschedule/cancel/fire/tap and original deadline.
6. **Real iPhone, Release (BrowserStack):** background GPS route/blue indicator, permission grants/denials,
   approximate accuracy, force-quit and offline replay. Report device, OS, signing and simulated GPS explicitly.
7. **Before release:** Always + stopOnTerminate false, SIGKILL/move/restore/original deadline; reboot/unlock/move;
   check-in delivery after reboot (including power off past fire time) and force-quit; carried-device motion;
   a full day of battery measurement and no check-in while records continue. Simulator/iPad passes do not close these gates.

## Open

- Cross-platform `device_id` normalization stays open, as on Android.
