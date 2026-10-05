# Heartbeat: proving that tracking is still on

A tracking app has a blind spot. A phone that lies still on a desk sends no locations, and neither does a phone
whose app has been killed. The server can't tell these two cases apart. The heartbeat closes that gap: while
tracking is on, the plugin makes sure the server hears from the device **every 3–5 minutes**, even when nothing moves.

- [Semantics](#semantics)
- [Stationary: GPS off, heartbeats continue](#stationary-gps-off-heartbeats-continue)
- [Delivery to native listeners](#delivery-to-native-listeners)
- [How it is scheduled](#how-it-is-scheduled)
- [Heartbeat metadata](#heartbeat-metadata)
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
- **Last known location.** The heartbeat carries the plugin's last known location: while moving, the fix of the last
  record, or a newer accepted fix (refreshed at most every 10 s); while stationary, the anchor fix (the stop point,
  see [below](#stationary-gps-off-heartbeats-continue)). Only when the plugin knows no location at all does the
  heartbeat ask the backend for its last known location. After the backend answered that it has none, the plugin
  does not ask again for 10 minutes (a timeout or an error is not remembered; the next heartbeat asks again). The
  heartbeat does **not** turn on GPS for a new fix, so its battery cost is tiny. `recorded_at` is when the heartbeat
  was created; `timestamp` is when that fix was **acquired**, which can be much earlier. `coords` and `timestamp` are
  `null` if no location has ever been known.
- **Checked twice.** Before creating a heartbeat, the plugin checks the location provider state (which may create a
  `providerchange` record), then checks again that no record was created and tracking was not stopped in the
  meantime. Only then does it create the heartbeat, so a heartbeat never duplicates another record.
- **No failure loop.** If creating a heartbeat fails, the next attempt is due a full `minInterval` later.
- **Same URL, same shape.** It is POSTed to `http.url` like every other record, with `"event": "heartbeat"` (see
  [wire-format.md](wire-format.md#heartbeat)).
- **Uploaded immediately.** Heartbeats are *priority records*: they are sent right away, ignoring `autoSync`,
  `autoSyncThreshold`, batching and `disableAutoSyncOnCellular`. The upload also drains any older queued records
  (unless the phone is on cellular with `disableAutoSyncOnCellular`). The plugin holds one partial wake lock per
  alarm: from the alarm until the heartbeat record is queued and its upload has been started (normally well under
  one second). The wake lock times out after 60 s if something hangs. The upload itself holds no wake lock.
- **Always sent.** Heartbeats are a fixed rule of the plugin: while tracking is on they are created, uploaded to
  `http.url` and delivered to native listeners, also while the phone is stationary with GPS off. The battery saving of
  the stationary mode comes from not polling GPS, not from skipping heartbeats.
- **Queued and retried.** If the upload fails (no network, server error), the heartbeat stays in the SQLite queue.
  There is no retry timer: it is retried when the next record is inserted (the next heartbeat at the latest), when
  connectivity returns, when tracking starts, or on `sync()`. `recorded_at` stays the creation time and `sent_at` is
  the upload time, so the server can see that a heartbeat was created on time but delivered late. Heartbeats are
  pruned like every other record (`persistence.maxDaysToPersist`, `maxRecordsToPersist`).
- **Watchdog.** If tracking is on but the foreground service is not running when a heartbeat alarm wakes the app (for
  example, the process was killed), the plugin first creates the heartbeat if it is due, then restores tracking and
  records `tracking_start` with reason `restore`. **On Android 12+ a heartbeat alarm can restart the foreground
  service from the background only for an app that is exempt from battery optimization**: Android lists that exemption
  among the exemptions from its background-start restrictions, while the backup alarm of a non-exempt app is inexact and
  carries no foreground-service allowance (see [below](#how-it-is-scheduled)). On Android 14+ it also needs "Allow all
  the time" location. When
  Android refuses, the plugin records `tracking_stop` with reason `service_start_failed`, and tracking stays off until
  the app calls `start()` again (for example the next time the user opens it).
- **JavaScript.** The app gets a `heartbeat` event (`{ location }`) while its WebView is alive.
- **Native listeners.** Companion plugins get every heartbeat in every process, also without a WebView (see
  [below](#delivery-to-native-listeners)).

## Stationary: GPS off, heartbeats continue

While the device is stationary, the plugin turns GPS off (see the README,
[Battery](../README.md#battery)). The foreground service keeps running, and the heartbeat keeps its normal
schedule. Each heartbeat carries the **anchor** fix: the fix where the device became stationary (the fix of the
`motionchange` with `is_moving: false`):

| Field | Value while stationary |
|---|---|
| `recorded_at` | When the heartbeat was created: now. |
| `timestamp` | When the anchor fix was **acquired** (not when the heartbeat was created). |
| `coords` | The coordinates of the anchor fix. |
| `is_moving` | `false`. |

Fixes that arrive while stationary (passive fixes that other apps caused, or the fixes of the low-power fallback)
are not recorded, and they change the heartbeat's location only when they become the anchor. The anchor changes only
when:

- no fix was known when the device became stationary: the first accepted fix becomes the anchor;
- the anchor was more than 10 minutes old when it became the anchor, and a fix arrives that is at most 10 minutes old
  and has an accuracy no worse than `filter.trackingAccuracyThreshold`: that fix becomes the anchor;
- a fix that is not certainly outside the stationary radius has a better (smaller) accuracy than the anchor: that fix
  becomes the anchor.

Example: a worker parks at 10:21 and stays until 12:00, with `stopTimeout` 5 minutes and heartbeats every
180 s. GPS stays on until the stop is confirmed: at about 10:26 the plugin records the `motionchange`
(`is_moving: false`), with `recorded_at` and `timestamp` about 10:26, and turns GPS off. The heartbeats at 10:29,
10:32, … 11:59 have `recorded_at` 10:29, 10:32, … 11:59, and all of them have `timestamp` 10:26 and the same `coords`,
unless a more accurate fix became the anchor in between; then the later heartbeats carry that fix and its time.

A server shows this as "tracking is on (last heartbeat 11:59), last position from 10:26". Use `recorded_at − timestamp`
as the age of the position. The age does not mean that tracking failed: the heartbeat itself proves the app is alive.

Records that carry their own fix also set the heartbeat's location while stationary: a transition of one of the app's
own geofences, and `getCurrentPosition()` / `watchPosition()` with `persist: true`. Heartbeats after such a record
carry its fix and its time, until the anchor changes again.

## Delivery to native listeners

When a heartbeat is created, the plugin:

1. writes the record to the SQLite queue;
2. hands it to every native listener (`LocationTrackingListener` of the
   [companion API](../README.md#companion-plugins-native-api)): `onRecord(context, record)`, with the record in the
   wire format (without `sent_at`);
3. emits the `heartbeat` event: native listeners get `onEvent(context, "heartbeat", { location })`, and JavaScript gets
   the `heartbeat` event if a WebView is alive;
4. starts the upload to `http.url` at once (see [Semantics](#semantics)). The upload runs on its own; steps 2 and 3 do
   not wait for it.

Listeners declared in the app's manifest are created in **every** process that runs the plugin, before the first
record, so a heartbeat created by an alarm at night, with no WebView and no JavaScript, still reaches them. A companion
plugin (for example the field-force example's PremiseMonitor) can therefore keep its own audit of every heartbeat,
independent of the app's JavaScript and of the network: delivery happens when the record is queued, also when the
phone is offline.

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
heartbeat config or the tracking state changes. Every record moves the due time later, and a moving phone creates a
record every few seconds, so the plugin does not set the alarms again for every record:

- The alarms stay in place when a record moves the due time later by less than 30 s.
- The alarms are set again when the due time moves later by 30 s or more, when a trigger time moves earlier, when the
  strategy changes, and, for `idle_paced`, whenever the backup time moves at all (an early backup alarm would use one
  of the few allow-while-idle alarms Android grants per hour and push the next backup about 9 minutes later).
- An alarm that was left in place can fire up to 30 s before the real due time. The plugin then checks the window,
  finds the heartbeat not due, creates **no** heartbeat, and sets the alarm for the real due time.
- Measured by the unit test `HeartbeatCostTest` with 60 records 5 s apart: 20 AlarmManager set calls instead of 120
  for a non-exempt app (`listener_with_backup`, two alarms per arm), and 10 instead of 60 for an exempt app (`exact`,
  one alarm per arm). The numbers before are from round 1, which set the alarms again for every record.
- The schedule is saved to storage only when the alarms are set, not after every record. A new process computes the
  schedule again from the last record.
- After Android refused an exact alarm, the fallback (`listener_with_backup` or `idle_paced`) stays until the next
  time the alarms are set again; then the plugin asks for the exact alarm again.

When the in-process alarm and the backup alarm both fire for the same window, only one heartbeat is created. The
plugin deliberately does not declare `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` (see the README).

Other costs of one heartbeat:

- **Wake lock.** One partial wake lock per alarm, released as soon as the heartbeat record is queued (at most 60 s).
- **Provider check.** One location-provider check per heartbeat attempt. It is the only check that sees permission
  changes that neither kill the process nor send a broadcast. An alarm that fires before the heartbeat is due skips
  it.
- **Backend last location.** Asked only when the plugin knows no location at all, and not again for 10 minutes after
  the backend answered that it has none.

These facts come from AOSP `AlarmManagerService`:

- Apps the user has exempted from battery optimization get unrestricted allow-while-idle alarms. They may also use
  exact alarms without `SCHEDULE_EXACT_ALARM`.
- Other apps get 7 inexact allow-while-idle alarms per rolling hour in deep idle: on average one every 9 minutes.
- On Android 6–11, every app may set exact alarms, but in deep idle Android still delivers allow-while-idle alarms of
  apps that aren't exempt at most about once every 9 minutes. So on those versions the strategy shows `exact`, but a
  phone that isn't exempt still gets heartbeats about 9 minutes apart in deep idle. Look at
  `isIgnoringBatteryOptimizations`, not only at `strategy`.

## Heartbeat metadata

Every heartbeat record carries a `heartbeat` object that says how the plugin schedules the **next** heartbeat on this
phone. With it, the server can tell an expected gap (for example 9 minutes in Doze without the battery exemption) from
a failure, without any setting in the app.

```json
"heartbeat": { "strategy": "idle_paced", "min_interval": 180, "max_interval": 300,
               "next_at": "2026-09-27T01:14:05.310Z", "battery_exempt": false, "device_idle": true }
```

| Key | Meaning |
|---|---|
| `strategy` | The strategy armed for the next window: `exact`, `listener_with_backup` or `idle_paced` (see [How it is scheduled](#how-it-is-scheduled)). When Android refused the exact alarm, it is the fallback that was armed instead. Never `disabled`: then no heartbeat exists. |
| `min_interval`, `max_interval` | `heartbeat.minInterval` and `maxInterval` (seconds) when this heartbeat was created. |
| `next_at` | When the next heartbeat will be due if no other record is created: `recorded_at + min_interval`, or, for `idle_paced`, the time of the backup alarm (at least 9 minutes after the previous backup alarm fired). `null` if unknown. |
| `battery_exempt` | The app was exempt from battery optimization when the heartbeat was created. |
| `device_idle` | The phone was in deep Doze when the heartbeat was created. |

The object is optional: heartbeats from plugin versions before round 2 don't have it. Only `heartbeat` records have it.
The plugin sets the alarms for the next window before it queues the heartbeat, so the object describes exactly what
AlarmManager holds, and `getHeartbeatStatus()` called at that moment reports the same strategy and time.

**How a server uses it.** Take two consecutive records of one device while tracking is on: `prev` and the next
record `next`. Let `hb` be the `heartbeat` object of the device's latest heartbeat up to `prev` (`prev`'s own object
when `prev` is a heartbeat):

```
grace = 120 s
if hb is missing:
    allowed = maxInterval of the app's config                      # plugin versions before round 2
elif not hb.battery_exempt and (hb.device_idle or next.heartbeat?.device_idle):
    allowed = 660 s                                                # Doze without exemption: about 9 min, up to 11
else:
    allowed = hb.max_interval                                      # awake, charging or exempt
gap      = next.recorded_at - prev.recorded_at
expected = gap <= allowed + grace
open gap = (no next record yet) and now - prev.recorded_at > allowed + grace + delivery allowance
```

- `battery_exempt: true` → the next record should come within `max_interval` (300 s by default), also in Doze.
- `battery_exempt: false` and Doze (`device_idle` true on this heartbeat or on the next one) → about 9 minutes is
  normal. `strategy` is `idle_paced` on Android 12+. On Android 11 and older it shows `exact`, but Android still spaces
  the alarms of a non-exempt app about 9 minutes apart in Doze, so use `battery_exempt` and `device_idle`, not only
  `strategy`.
- `listener_with_backup` → the phone was awake and not exempt; if it enters Doze before the next heartbeat, the next
  one can come about 9 minutes later and will have `device_idle: true`.
- The next heartbeat's `recorded_at` minus this heartbeat's `next_at` shows how much later than planned the next
  heartbeat came (positive = late). Occasional delays of a minute or two are normal; a heartbeat that never comes is
  a gap to explain (table in [Server-side audit](#3-explain-gaps)).

**"Online" in a back office.** Show a device as online while tracking is on (its last `tracking_*` record is a
`tracking_start`) and `now − recorded_at` of its newest record is at most `allowed + grace` from the rule above.
Remember that records can arrive late (`sent_at` later than `recorded_at`), so "offline" is provisional until the
queued records have had time to arrive.

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
| **Phone makers' task killers**, app **not exempt** (Android 12+) | **One more heartbeat, then none until the app calls `start()` again** | The backup alarm can still wake the app and create a heartbeat, but Android 12+ does not let a non-exempt app restart its foreground service from the background (the inexact backup alarm carries no foreground-service allowance). The plugin records `tracking_stop` with reason `service_start_failed`. Allow the app in the phone maker's power manager, see [below](#phone-makers-power-managers). |
| **Phone off** or battery empty | **None while off** | After boot, tracking resumes (`tracking_start`, reason `boot`) only with `app.startOnBoot: true`. On Android 14+ it also needs "Allow all the time" location, otherwise a `tracking_stop` with reason `service_start_failed` follows. With `startOnBoot: false`, tracking is off after the reboot and a `tracking_stop` with reason `reboot` is recorded when the phone has booted. |
| App **updated** | A short gap | With `app.startOnBoot: true`, tracking resumes (`tracking_start`, reason `package_replaced`). With `false`, tracking is off after the update and a `tracking_stop` with reason `package_replaced` is recorded. |
| **Location permission removed** | **None until the app is opened again** | Android kills the app when a permission is revoked. When the plugin runs again, it records a `providerchange`, and if location permission is gone completely, a `tracking_stop` with reason `permission_denied`. The reason is also `permission_denied` when Android restarts the killed service by itself and the restart fails because the permission is gone (on Android 14+ such a restart fails when the service enters the foreground). |
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
| `nextHeartbeatAt` | string \| null | When the next heartbeat is expected: the real due time (not the time of an alarm that was left up to 30 s earlier), or the backup alarm's time while `idle_paced`. It is about now when a heartbeat is overdue, and `null` while `strategy` is `disabled`. After the process was restarted, it is computed from the last record and the strategy the previous process armed (ignored after a reboot, which clears all alarms). |
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

Every heartbeat record already tells the server whether the app is exempt (`heartbeat.battery_exempt`, see
[Heartbeat metadata](#heartbeat-metadata)). To have the flag on every other record too, the app can copy it into
`persistence.extras`. For example, on every launch and resume:

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

- `tracking_start` (reasons `start`, `start_geofences`, `boot`, `restore`, `package_replaced`, `resume_notification`)
  sets the state to **ON**.
  A `tracking_start` while already ON is a mode switch between `start()` and `startGeofences()`.
- `tracking_stop` (reasons `stop`, `stop_on_stationary`, `stop_after_elapsed`, `terminate`, `permission_denied`,
  `service_start_failed`, `reboot`, `package_replaced`) sets it to **OFF**. The reason says who stopped it: your app
  (`stop`, or its configuration: `stop_on_stationary`, `stop_after_elapsed`, and `reboot` / `package_replaced` when
  `app.startOnBoot` is `false`), the user by swiping the app away (`terminate`), a missing permission
  (`permission_denied`), or Android refusing the foreground service (`service_start_failed`). A `reboot` /
  `package_replaced` stop is recorded after the restart, so the gap before it is the time the phone was off or
  updating.
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
- Phones that **aren't exempt** legitimately produce gaps of about 9 minutes in deep idle. Use the
  [heartbeat metadata](#heartbeat-metadata) to allow up to 11 minutes (plus `grace`) exactly when the heartbeat before
  or after the gap shows `battery_exempt: false` and `device_idle: true`. Or require the exemption, or report 5–11
  minute gaps as a separate "idle-paced" category.

### 3. Explain gaps

| What you see | Likely cause |
|---|---|
| A `tracking_stop` before the gap | Not a gap: tracking was stopped. Use `reason`. `terminate` means the user swiped the app away. |
| A `tracking_stop` with reason `service_start_failed` | Android killed the app's process (often a phone maker's task killer), and then did not let the plugin restart its foreground service from the background: typically an app that is not exempt from battery optimization on Android 12+, or on Android 14+ without "Allow all the time". The same reason is recorded when the app called `start()` while it was not visible and Android refused the service. Tracking stays off until the app calls `start()` again, or the user taps the resume notification (`notification.resume`). Treat it as "tracking lost", not as a user decision. |
| The gap ends with `tracking_start` reason `boot`, and `boot_count` increased | The phone was switched off or rebooted. A `battery.level` near 0 in the last record before the gap points to an empty battery. |
| The gap ends with `tracking_start` reason `restore` | The app's process was killed and tracking resumed: by itself (Android restarted the service, or a heartbeat alarm woke the app) or when the user opened the app again (for example after a force-stop). |
| The gap ends with `tracking_start` reason `package_replaced` | The app was updated. |
| `tracking_stop` reason `service_start_failed`, then later `tracking_start` reason `resume_notification` | Android refused to restore tracking in the background (typically Android 14+ without "Allow all the time"), and the user tapped the resume notification. The gap between them is the time tracking was paused. |
| A `providerchange` at the end of the gap, with `permission: "denied"` or `"when_in_use"` | The user revoked the location permission (followed by `tracking_stop` reason `permission_denied` when it is `denied`). |
| No explanation, and records resume only when the user opens the app | Force-stop, battery "Restricted", or a phone maker's task killer. The device was not tracking. |
| `providerchange` with `enabled: false` and no gap | The app was alive (heartbeats kept coming), but location services were off. Treat positions from that period as unknown. |
| No `providerchange` for a change you expected | The plugin records one per settled change (broadcasts are debounced by 1 s), only while tracking is on, and not for the very first state it observes after install. A change the plugin already saw while tracking was off (for example when the app came to the foreground) is not recorded later. |
| Heartbeats with an old `timestamp` | The phone is stationary (GPS is off, see [above](#stationary-gps-off-heartbeats-continue)), or has no new fix (indoors). The app is alive; the position is the last known one. |

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
