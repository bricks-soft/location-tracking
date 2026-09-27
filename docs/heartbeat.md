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
  motionchange, geofence, audit, or heartbeat). When no record has been created for `minInterval` seconds, the
  plugin creates a `heartbeat` record. It fires inside the window [`minInterval`, `maxInterval`] after that last
  record. Any record created inside the window restarts it. A moving phone therefore rarely sends heartbeats, and a
  stationary one sends about one every `minInterval`.
- **Last known location.** The heartbeat carries the last known location. It does **not** turn on GPS for a new fix,
  so its battery cost is tiny. `timestamp` is the time of that old fix, and `coords` and `timestamp` are `null` if no
  location has ever been known.
- **Same URL, same shape.** It is POSTed to `http.url` like every other record, with `"event": "heartbeat"` (see
  [wire-format.md](wire-format.md#heartbeat)).
- **Uploaded immediately.** Heartbeats are *priority records*: they are sent right away, ignoring `autoSync`,
  `autoSyncThreshold`, batching and `disableAutoSyncOnCellular`. The upload also drains any older queued records.
- **Queued and retried.** If the upload fails (no network, server error), the heartbeat stays in the SQLite queue. It
  is retried when the next record is inserted, when connectivity returns, on the next heartbeat, or on `sync()`.
  `recorded_at` stays the creation time and `sent_at` is the upload time, so the server can see that a heartbeat was
  created on time but delivered late.
- **Watchdog.** If the tracking service is not running when a heartbeat alarm fires (for example, the process was
  killed), the plugin restores tracking and records `tracking_start` with reason `restore`.
- **JavaScript.** The app gets a `heartbeat` event (`{ location }`) while its WebView is alive.

## How it is scheduled

Every heartbeat is scheduled on the elapsed-time clock (`ELAPSED_REALTIME_WAKEUP`), so changing the wall clock has no
effect. The plugin picks one of four strategies, and `getHeartbeatStatus().strategy` reports which one is in use:

| Strategy | When | How |
|---|---|---|
| `exact` | The app can schedule exact alarms: it is exempt from battery optimization, or the phone runs Android 11 or older | One exact allow-while-idle alarm at the due time (`setExactAndAllowWhileIdle`). |
| `listener_with_backup` | Not exempt (Android 12+), device not in deep idle | An exact in-process alarm (no permission needed, but it dies with the process and deep idle defers it), plus an inexact allow-while-idle **backup** alarm that survives process death. |
| `idle_paced` | Not exempt (Android 12+), device in deep idle | The backup alarm is kept at least 9 minutes after the previous one, because Android allows a non-exempt app only 7 allow-while-idle alarms per rolling hour in deep idle. |
| `disabled` | Heartbeat disabled, or tracking off | Nothing is scheduled. |

The plugin re-evaluates the schedule whenever the phone enters or leaves deep idle. It deliberately does not declare
`SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` (see the README).

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
| Phone awake, or screen off but not yet in deep idle | **3–5 min** (about `minInterval`) | Normal case. |
| Charging | **3–5 min** | Doze does not engage while the phone is plugged in. |
| Deep idle, app **exempt** from battery optimization | **3–5 min** | Strategy `exact`. |
| Deep idle, app **not exempt** | **about 9 min** | Strategy `idle_paced` (Android 12+) or `exact` (Android 11 and older). This is the rate limit for apps that aren't exempt. |
| No network | Created every 3–5 min, **delivered later** | `sent_at` is later than `recorded_at`, and the queue drains when the network returns. |
| Location services turned off | Heartbeats keep coming, with the last known coords | A `providerchange` record (`enabled: false`) explains why the coords are stale. |
| App **force-stopped** (Settings → Force stop) | **None until the app is opened again** | Android cancels the app's alarms, and a stopped app doesn't even receive `BOOT_COMPLETED`. |
| Battery usage set to **"Restricted"** (Android 12+) | **None until the app is opened again** | Background work is blocked. |
| **Phone makers' task killers** (Huawei PowerGenie, Xiaomi, Oppo, Vivo, Samsung) | **None until the app is opened again** (sometimes the process comes back: `tracking_start`, reason `restore`) | Allow the app in the phone maker's power manager, see [below](#phone-makers-power-managers). |
| **Phone off** or battery empty | **None while off** | After boot, tracking resumes (`tracking_start`, reason `boot`) only with `app.startOnBoot: true` and "Allow all the time" location. Otherwise it resumes when the app is opened. |
| **Location permission removed** | **None until the app is opened again** | Android kills the app when a permission is revoked. A `providerchange` record follows when the app runs again. |
| App swiped away with `stopOnTerminate: true` (default) | None, by design | A `tracking_stop` record with reason `terminate` is sent first. |
| App swiped away with `stopOnTerminate: false` | 3–5 min | The foreground service keeps running. On some phones the swipe kills the process anyway, and the heartbeat alarm restores it (`restore`). |

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
| `minInterval`, `maxInterval` | number (s) | The effective window, after clamping. |
| `lastRecordAt` | string \| null | Creation time of the last record of any type: the start of the current window. |
| `lastHeartbeatAt` | string \| null | Creation time of the last heartbeat. |
| `nextHeartbeatAt` | string \| null | When the next heartbeat check is due. `null` when nothing is scheduled. |
| `strategy` | `'exact' \| 'listener_with_backup' \| 'idle_paced' \| 'disabled'` | How the next heartbeat is scheduled (see [above](#how-it-is-scheduled)). |
| `canScheduleExactAlarms` | boolean | Whether Android lets the app set exact alarms (always true on Android 11 and older). |
| `isIgnoringBatteryOptimizations` | boolean | Whether the user exempted the app from battery optimization. |
| `isDeviceIdleMode` | boolean | Whether the phone is in deep idle right now. |
| `isPowerSaveMode` | boolean | Whether Battery Saver is on. |
| `pendingHeartbeats` | number | Heartbeats still waiting in the queue for a successful upload. |

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
- `tracking_stop` (reasons `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`) sets
  it to **OFF**. The reason says who stopped it: your app, the user by swiping the app away (`terminate`), or a
  missing permission.
- Any other record also proves the device was ON at `recorded_at`.

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
| The gap ends with `tracking_start` reason `boot`, and `boot_count` increased | The phone was switched off or rebooted. A `battery.level` near 0 in the last record before the gap points to an empty battery. |
| The gap ends with `tracking_start` reason `restore` | The app's process was killed (by Android or the phone maker's task killer) and came back by itself. |
| The gap ends with `tracking_start` reason `package_replaced` | The app was updated. |
| A `providerchange` at the end of the gap, with `permission: "denied"` or `"when_in_use"` | The user revoked the location permission. |
| No explanation, and records resume only when the user opens the app | Force-stop, battery "Restricted", or a phone maker's task killer. The device was not tracking. |
| `providerchange` with `enabled: false` and no gap | The app was alive (heartbeats kept coming), but location services were off. Treat positions from that period as unknown. |
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
