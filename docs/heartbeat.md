# Heartbeat: proving that tracking is still on

A tracking app has a blind spot. A phone that lies still on a desk sends no locations, and neither does a phone
whose app has been killed. The server can't tell these two cases apart. The heartbeat closes that gap: while
tracking is on, the plugin makes sure the server hears from the device **every 3–5 minutes**, even when nothing moves.

- [Semantics](#semantics)
- [How it is scheduled](#how-it-is-scheduled)
- [Android reliability](#android-reliability)
- [Battery-optimization exemption](#battery-optimization-exemption)
- [Phone makers' power managers](#phone-makers-power-managers)
- [`getHeartbeatStatus()`](#getheartbeatstatus)
- [Server-side audit](#server-side-audit)
- [iOS outlook](#ios-outlook)

## Semantics

```ts
heartbeat: {
  enabled: true,     // default
  minInterval: 180,  // seconds, minimum 60 (default 180)
  maxInterval: 300,  // seconds, >= minInterval (default 300)
}
```

- **Only while tracking is on.** Heartbeats run after `start()` or `startGeofences()`, until `stop()` (or an automatic
  stop). They are not created while tracking is off.
- **Only when nothing else was recorded.** The window starts at the **last record of any type** (location,
  motionchange, geofence, audit, or heartbeat; records added with `insertLocation()` don't count, because they don't
  prove that tracking runs). A heartbeat is **due `minInterval` seconds after that record** and should exist before
  `maxInterval`; any record created in between restarts the window. A moving phone therefore rarely sends
  heartbeats, and a stationary one sends one about every `minInterval`. When Android delays the alarm beyond
  `maxInterval` (see [Android reliability](#android-reliability)), the heartbeat is still created, late, and the
  plugin log notes by how much.
- **Where the window starts.**
  - Within one boot, the window is measured on the elapsed-time clock from the last record, so changing the wall clock
    has no effect. After a reboot, it is derived from the last record's wall-clock time.
  - There is **no heartbeat right at `start()`**: a last record from an earlier tracking session doesn't make the new
    window overdue. The window starts at the session start (in practice at its `tracking_start` record).
  - Before any record exists (a new install), the window starts when the plugin first schedules it.
- **Last known location.** The heartbeat carries the last known location: the last recorded fix, which is kept up to
  date from the low-power fixes while the phone is stationary, or else the backend's last known location. It does
  **not** turn on GPS for a new fix, so its battery cost is tiny. `timestamp` is the time of that fix, and `coords`
  and `timestamp` are `null` if no location has ever been known.
- **Checked twice.** Before creating a heartbeat, the plugin checks the location provider state (which may create a
  `providerchange` record), then checks again that no record was created and tracking was not stopped in the
  meantime. Only then does it create the heartbeat, so a heartbeat never duplicates another record.
- **No failure loop.** If creating a heartbeat fails, the next attempt is due a full `minInterval` later.
- **Same URL, same shape.** It is POSTed to `http.url` like every other record, with `"event": "heartbeat"` (see
  [wire-format.md](wire-format.md#heartbeat)).
- **Uploaded immediately.** Heartbeats are *priority records*: they are sent right away, ignoring `autoSync`,
  `autoSyncThreshold`, batching and `disableAutoSyncOnCellular`. The upload also drains any older queued records
  (unless the phone is on cellular with `disableAutoSyncOnCellular`). The plugin holds a partial wake lock for up to
  60 s while it handles the alarm and starts the upload.
- **Queued and retried.** If the upload fails (no network, server error), the heartbeat stays in the SQLite queue.
  There is no retry timer: it is retried when the next record is inserted (the next heartbeat at the latest), when
  connectivity returns, when tracking starts, or on `sync()`. `recorded_at` stays the creation time and `sent_at` is
  the upload time, so the server can see that a heartbeat was created on time but delivered late. Heartbeats are
  pruned like every other record (`persistence.maxDaysToPersist`, `maxRecordsToPersist`).
- **Watchdog.** If tracking is on but the foreground service is not running when a heartbeat alarm wakes the app (for
  example, the process was killed), the plugin first creates the heartbeat if it is due, then restores tracking and
  records `tracking_start` with reason `restore`. **On Android 12+ a heartbeat alarm may restart the foreground
  service from the background only if it is an exact alarm**, which means only for an app that is exempt from battery
  optimization (see [below](#how-it-is-scheduled)); on Android 14+ it also needs "Allow all the time" location. When
  Android refuses, the plugin records `tracking_stop` with reason `service_start_failed`, and tracking stays off until
  the app calls `start()` again (for example the next time the user opens it).
- **JavaScript.** The app gets a `heartbeat` event (`{ location }`) while its WebView is alive.

## How it is scheduled

Every heartbeat is scheduled on the elapsed-time clock (`ELAPSED_REALTIME_WAKEUP`), so changing the wall clock has no
effect. The plugin picks one of four strategies, and `getHeartbeatStatus().strategy` reports which one is in use:

| Strategy | When | How |
|---|---|---|
| `exact` | The app can schedule exact alarms (`canScheduleExactAlarms()`): it is exempt from battery optimization, or the phone runs Android 11 or older | One exact allow-while-idle alarm at the due time (`setExactAndAllowWhileIdle`). If Android refuses it anyway, the plugin falls back to `listener_with_backup`. |
| `listener_with_backup` | Not exempt (Android 12+), device not in deep idle | An exact in-process alarm at the due time (no permission needed, but it dies with the process and deep idle defers it), plus an inexact allow-while-idle **backup** alarm, also at the due time, that survives process death. |
| `idle_paced` | Not exempt (Android 12+), device in deep idle | The backup alarm is set at least 9 minutes after the previous backup alarm fired, because Android allows a non-exempt app only 7 allow-while-idle alarms per rolling hour in deep idle. The time of the last backup alarm is saved, so a restarted process keeps the pacing (a reboot resets it). |
| `disabled` | Heartbeat disabled, or tracking off | Nothing is scheduled; pending alarms are cancelled. |

The plugin re-evaluates the schedule after every record, whenever the phone enters or leaves deep idle, and when the
heartbeat config or the tracking state changes. It only calls AlarmManager when the schedule actually changes (for a
non-exempt app, two calls per record). When the in-process alarm and the backup alarm both fire for the same window,
only one heartbeat is created. The plugin deliberately does not declare `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`
(see the README).

These facts come from AOSP `AlarmManagerService`:

- Apps the user has exempted from battery optimization get unrestricted allow-while-idle alarms. They may also use
  exact alarms without `SCHEDULE_EXACT_ALARM`.
- Other apps get 7 inexact allow-while-idle alarms per rolling hour in deep idle: on average one every 9 minutes.
- On Android 6–11, every app may set exact alarms, but in deep idle Android still delivers allow-while-idle alarms of
  apps that aren't exempt at most about once every 9 minutes. So on those versions the strategy shows `exact`, but a
  phone that isn't exempt still gets heartbeats about 9 minutes apart in deep idle. Look at
  `isIgnoringBatteryOptimizations`, not only at `strategy`.

## Android reliability

"Deep idle" (Doze) starts when the screen has been off for a while, the phone is **unplugged** and **not moving**,
for example overnight on a table.

| Situation | Heartbeat spacing | Notes |
|---|---|---|
| Phone awake, or screen off but not yet in deep idle | **About `minInterval`** (3 min by default) | Normal case. |
| Charging | **About `minInterval`** | Doze does not engage while the phone is plugged in. |
| Deep idle, app **exempt** from battery optimization | **About `minInterval`** | Strategy `exact`. |
| Deep idle, app **not exempt** | **About 9 min** | Strategy `idle_paced` (Android 12+) or `exact` (Android 11 and older). This is the rate limit for apps that aren't exempt. |
| No network | Created on time, **delivered later** | `sent_at` is later than `recorded_at`, and the queue drains when the network returns. |
| Location services turned off | Heartbeats keep coming, with the last known coords | A `providerchange` record (`enabled: false`) explains why the coords are stale. GMS and HMS drop all geofences when location is switched off; the plugin registers them again when it comes back. |
| App **force-stopped** (Settings → Force stop) | **None until the app is opened again** | Android cancels the app's alarms, and a stopped app doesn't even receive `BOOT_COMPLETED`. When the app is opened and calls `ready()`, tracking resumes (`tracking_start`, reason `restore`). |
| Battery usage set to **"Restricted"** (Android 12+) | **None until the app is opened again** | Background work is blocked. |
| **Phone makers' task killers** (Huawei PowerGenie, Xiaomi, Oppo, Vivo, Samsung) kill the process, app **exempt** | A gap, then heartbeats again | If the killer leaves the app's alarms alone, the next heartbeat alarm creates a heartbeat and restores tracking (`tracking_start`, reason `restore`). Some killers also block alarms: then nothing until the app is opened again. |
| **Phone makers' task killers**, app **not exempt** (Android 12+) | **One more heartbeat, then none until the app calls `start()` again** | The backup alarm can still wake the app and create a heartbeat, but Android doesn't let an inexact alarm restart the foreground service. The plugin records `tracking_stop` with reason `service_start_failed`. Allow the app in the phone maker's power manager, see [below](#phone-makers-power-managers). |
| **Phone off** or battery empty | **None while off** | After boot, tracking resumes (`tracking_start`, reason `boot`) only with `app.startOnBoot: true`. On Android 14+ it also needs "Allow all the time" location, otherwise a `tracking_stop` with reason `service_start_failed` follows. With `startOnBoot: false`, tracking is off after the reboot (no `tracking_stop` is recorded). |
| App **updated** | A short gap | With `app.startOnBoot: true`, tracking resumes (`tracking_start`, reason `package_replaced`). With `false`, tracking is off after the update, without a `tracking_stop`. |
| **Location permission removed** | **None until the app is opened again** | Android kills the app when a permission is revoked. When the plugin runs again, it records a `providerchange`, and if location permission is gone completely, a `tracking_stop` with reason `permission_denied`. |
| App swiped away with `stopOnTerminate: true` (default) | None, by design | A `tracking_stop` record with reason `terminate` is sent first. |
| App swiped away with `stopOnTerminate: false` | About `minInterval` | The foreground service keeps running. On some phones the swipe kills the process anyway; then the cases above apply (`restore` when exempt, `service_start_failed` when not exempt on Android 12+). |

In the "none until the app is opened again" cases, **the server sees a gap. That is the correct audit outcome**: the
device really wasn't tracking, and no Android API lets an app override the user's or phone maker's decision.

## Battery-optimization exemption

The exemption is the single most effective setting for timely heartbeats. The plugin doesn't use the direct
"Let app always run in background?" prompt, because `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted by Google
Play policy. Instead, it opens the system's list of apps (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), and the
user changes the setting there:

```ts
const status = await LocationTracking.getBatteryOptimizationStatus();
if (!status.isIgnoringBatteryOptimizations) {
  // 1. Explain in your own UI: "To report every few minutes while your phone is idle, allow <App> to run
  //    without battery optimization. On the next screen choose 'All apps', find <App>, select 'Don't optimize'."
  // 2. Then open the settings list:
  await LocationTracking.openBatteryOptimizationSettings();
}
// Check again when the app comes back to the foreground (for example on the 'resume' event).
```

On Android 12+, **App info → Battery → Unrestricted** is the same setting. **Restricted** is the opposite: it blocks
background work completely (see the table above).

## Phone makers' power managers

Several phone makers add their own task killers on top of Android. They can stop the app, alarms included, even when
it is exempt from battery optimization. `getPowerManagerInfo()` reports whether the plugin knows a power-manager
screen for this phone, and `openPowerManagerSettings()` opens it. Both are best effort: the screens move between OS
versions, and `openPowerManagerSettings()` resolves `{ opened: false }` if no known screen exists.

```ts
const pm = await LocationTracking.getPowerManagerInfo(); // { manufacturer: 'HUAWEI', available: true }
if (pm.available) {
  // Explain which switches to change first (see the list below), then:
  await LocationTracking.openPowerManagerSettings();
}
```

What to tell users (menu names vary by model and OS version):

| Phone maker | Setting |
|---|---|
| **Huawei / Honor** (EMUI, MagicOS, PowerGenie) | Settings → Battery → **App launch** → the app → turn off "Manage automatically" (**Manage manually**), and keep **Auto-launch**, **Secondary launch** and **Run in background** on. |
| **Xiaomi / Redmi / POCO** (MIUI, HyperOS) | Security app → **Autostart** on. App info → Battery saver → **No restrictions**. |
| **Oppo / Realme / OnePlus** (ColorOS, OxygenOS) | App info → Battery → **Allow background activity** (and **Allow auto launch**), or turn off "Optimize battery use". |
| **Vivo / iQOO** (Funtouch, OriginOS) | i Manager → App manager → **Autostart** on. Battery → **Background power consumption management** → allow. |
| **Samsung** (One UI) | Settings → Battery → Background usage limits → add the app to **Never sleeping apps**, and make sure it isn't in "Sleeping" or "Deep sleeping" apps. |

[dontkillmyapp.com](https://dontkillmyapp.com) keeps current, model-specific instructions.

## `getHeartbeatStatus()`

```ts
const hb = await LocationTracking.getHeartbeatStatus();
```

| Field | Type | Meaning |
|---|---|---|
| `enabled` | boolean | `heartbeat.enabled` from the config. |
| `minInterval`, `maxInterval` | number (s) | The effective window, after clamping (`minInterval` at least 60, `maxInterval` at least `minInterval`). |
| `lastRecordAt` | string \| null | Creation time of the last record of any type (except `insertLocation()` records): the start of the current window. |
| `lastHeartbeatAt` | string \| null | Creation time of the last heartbeat. |
| `nextHeartbeatAt` | string \| null | When the next heartbeat is expected: the due time, or the backup alarm's time while `idle_paced`. It is about now when a heartbeat is overdue, and `null` while `strategy` is `disabled`. After the process was restarted, it shows what the previous process scheduled (ignored after a reboot, which clears all alarms). |
| `strategy` | `'exact' \| 'listener_with_backup' \| 'idle_paced' \| 'disabled'` | How the next heartbeat is scheduled (see [above](#how-it-is-scheduled)). |
| `canScheduleExactAlarms` | boolean | Whether Android lets the app set exact alarms (always true on Android 11 and older; on Android 12+ true when the app is exempt from battery optimization). |
| `isIgnoringBatteryOptimizations` | boolean | Whether the user exempted the app from battery optimization. |
| `isDeviceIdleMode` | boolean | Whether the phone is in deep idle right now. |
| `isPowerSaveMode` | boolean | Whether Battery Saver is on. |
| `pendingHeartbeats` | number | Heartbeat records still waiting in the queue for a successful upload. |

Strategies for the app UI:

- `exact`, together with `isIgnoringBatteryOptimizations: true`, is the best case. Nothing to do.
- `listener_with_backup`: fine while the phone is awake. Suggest the battery exemption to get on-time heartbeats
  overnight.
- `idle_paced`: the phone is in deep idle and the app isn't exempt, so heartbeats are about 9 minutes apart. Ask for
  the exemption if your audit needs 5-minute resolution.
- `disabled`: tracking is off, or heartbeats are disabled.
- A growing `pendingHeartbeats` means uploads fail: check the network, `http.url` and the `http` events.

To let the server know which devices are exempt, the app can copy the flag into every record. For example, on every
launch and resume:

```ts
const { isIgnoringBatteryOptimizations } = await LocationTracking.getHeartbeatStatus();
await LocationTracking.setConfig({
  config: { persistence: { extras: { battery_exempt: isIgnoringBatteryOptimizations } } },
});
```

## Server-side audit

The heartbeat lets the server decide, for any period, whether the device was verifiably tracking.

### 1. Track the tracking state per device

Replay each device's records ordered by `recorded_at`. When a device's clock may be wrong, order by `boot_count`,
then `elapsed_realtime_ms`.

- `tracking_start` (reasons `start`, `start_geofences`, `boot`, `restore`, `package_replaced`) sets the state to **ON**.
  A `tracking_start` while already ON is a mode switch between `start()` and `startGeofences()`.
- `tracking_stop` (reasons `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`,
  `service_start_failed`) sets it to **OFF**. The reason says who stopped it: your app (`stop`, or its configuration:
  `stop_on_stationary`, `stop_after_elapsed`), the user by swiping the app away (`terminate`), a missing permission
  (`permission_denied`), or Android refusing the foreground service (`service_start_failed`).
- `heartbeat`, `location`, `motionchange`, `geofence` and `providerchange` records also prove that the device was ON
  at `recorded_at`. Positions requested by the app (`current_position`, `watch_position`) and records added with
  `insertLocation()` can also exist while tracking is OFF, so don't count them as proof.

### 2. Flag gaps

While the state is ON, consecutive records should be at most `maxInterval` apart. Flag a gap when:

```
gap = next.recorded_at - prev.recorded_at      // both records while ON
gap > maxInterval + grace                      // e.g. 300 s + 120 s
```

and flag an **open gap** (the device is silent right now) when `now - last.recorded_at > maxInterval + grace +
delivery allowance`.

Choosing `grace`:

- **60–120 s** covers alarm batching and processing time on phones that are awake or exempt.
- Phones that **aren't exempt** legitimately produce gaps of about 9 minutes in deep idle. Either require the
  exemption, raise the threshold for those devices to about 10–11 minutes (you can tell them apart with a flag in
  `extras`, see [above](#getheartbeatstatus)), or report 5–11 minute gaps as a separate "idle-paced" category.

### 3. Explain gaps

| What you see | Likely cause |
|---|---|
| A `tracking_stop` before the gap | Not a gap: tracking was stopped. Use `reason`. `terminate` means the user swiped the app away. |
| A `tracking_stop` with reason `service_start_failed` | Android killed the app's process (often a phone maker's task killer), and then did not let the plugin restart its foreground service from the background: typically an app that is not exempt from battery optimization on Android 12+, or on Android 14+ without "Allow all the time". Tracking stays off until the app calls `start()` again. Treat it as "tracking lost", not as a user decision. |
| The gap ends with `tracking_start` reason `boot`, and `boot_count` increased | The phone was switched off or rebooted. A `battery.level` near 0 in the last record before the gap points to an empty battery. |
| The gap ends with `tracking_start` reason `restore` | The app's process was killed and tracking resumed: by itself (Android restarted the service, or a heartbeat alarm woke the app) or when the user opened the app again (for example after a force-stop). |
| The gap ends with `tracking_start` reason `package_replaced` | The app was updated. |
| A `providerchange` at the end of the gap, with `permission: "denied"` or `"when_in_use"` | The user revoked the location permission (followed by `tracking_stop` reason `permission_denied` when it is `denied`). |
| No explanation, and records resume only when the user opens the app | Force-stop, battery "Restricted", a reboot or an update with `startOnBoot: false`, or a phone maker's task killer. The device was not tracking. |
| `providerchange` with `enabled: false` and no gap | The app was alive (heartbeats kept coming), but location services were off. Treat positions from that period as unknown. |
| No `providerchange` for a change you expected | The plugin records one per settled change (broadcasts are debounced by 1 s), only while tracking is on, and not for the very first state it observes after install. A change the plugin already saw while tracking was off (for example when the app came to the foreground) is not recorded later. |
| Heartbeats with an old `timestamp` | The phone is stationary, or has no new fix (indoors). The app is alive; the position is the last known one. |

### 4. Detect device clock changes

`recorded_at` and `sent_at` come from the device's wall clock, which the user can change.

- For two records with the **same `boot_count`**, `elapsed_realtime_ms(next) - elapsed_realtime_ms(prev)` is the real
  time between them. If it differs from `recorded_at(next) - recorded_at(prev)` by more than a few seconds, the wall
  clock was changed in between (by the user or a network time correction). Use the elapsed difference for gap
  detection.
- When `boot_count` changes, the phone rebooted, and the elapsed clock restarted from zero. Fall back to
  `recorded_at`, and expect a `tracking_start` with reason `boot` (or a gap until the app was opened).
- `boot_count` is `-1` on phones that don't expose it. Then only a decreasing `elapsed_realtime_ms` reveals a reboot.

### 5. Handle late delivery

- **Evaluate gaps on `recorded_at`, not on arrival time.** A heartbeat created on time but uploaded 40 minutes later,
  after an outage, still proves the device was tracking. `sent_at - recorded_at` shows how late it was.
- Treat open-gap alerts as **provisional**. Queued records can still arrive and close the gap, oldest first, as soon
  as the device is online again. Close an audit period only after a delivery allowance (for example a few hours, or
  the next day).
- `server_received_at - sent_at` should be a few seconds. A large, stable offset means the device's clock is off; you
  can use it to correct that device's timestamps.
- Deduplicate on `uuid`: records can be delivered twice when a response was lost.

## iOS outlook

iOS support is planned for the next phase. It will behave differently:

- **Heartbeats every 3–5 minutes need continuous background location.** iOS has no timer that wakes a suspended app.
  So the plugin will keep background location updates running while tracking is on, with low accuracy while the
  device is stationary to save battery, and will emit heartbeats from those wake-ups.
- **Nothing is sent after the user force-quits the app** (swipes it away in the app switcher): no heartbeats and no
  timer wake-ups. The server sees a gap, which is again the correct audit outcome.
- **Once the app has been terminated, iOS can relaunch it only on significant movement** (about 500 m, through the
  significant-change location service), **never on a timer**. A stationary phone stays silent until it moves or the
  user opens the app.
