# Manual on-device test checklist

Emulators can't show how GMS, HMS, Doze and phone makers' task killers really behave, so this plugin needs manual
tests on real phones. This checklist uses the [example app](../example/) (package
`com.brickssoft.locationtracking.example`), which has a button for every method, a live log of all 13 events, a state
panel, and a heartbeat panel that refreshes every 10 s.

Run it before every release, at least on the three phone types below. Copy the [checklist table](#checklist) into your
test report and fill in the last column.

## What you need

| Item | Notes |
|---|---|
| **GMS phone** | Any phone with Google Play services, ideally Android 14–16. A Pixel or Samsung is a good choice. |
| **Huawei HMS-only phone** | A Huawei phone without Google services (for example P40, Mate 40 or newer) with HMS Core up to date. The app must be registered in AppGallery Connect with your debug key's SHA-256 (see the README). |
| **Phone with neither** | A phone where neither SDK is usable, so that `auto` falls back to the Android `LocationManager`. For example an AOSP/LineageOS phone without Google apps, the Huawei phone with the `gms`-only build, or the GMS phone with the `hms`-only build. |
| Optional: Android 10 and Android 11/12 phones | For the two background-permission flows (dialog versus Settings page). |
| A computer with `adb` | USB debugging enabled on every phone. |
| A test server reachable over **HTTPS** | Android blocks cleartext `http://` by default, and the example app doesn't allow it. See [Test server](#test-server). |

## Build and install

In the repository root, then in `example/`:

```bash
npm ci && npm run build
cd example && npm ci && npm run sync
cd android
./gradlew assembleDebug -PlocationTracking.providers=gms,hms   # the main build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For the provider tests, build and install the single-SDK variants the same way, with
`-PlocationTracking.providers=gms` and `-PlocationTracking.providers=hms`. `getDeviceInfo().packagedProviders` shows
which variant is installed.

## Test server

A minimal Node.js (18+) server with no dependencies. It logs each record with its lateness (`sent_at - recorded_at`)
and the gap since the device's previous record, and it can simulate server failures:

```js
// lt-test-server.mjs — run: node lt-test-server.mjs
import { createServer } from 'node:http';

let failing = false;
const lastRecordedAt = new Map(); // device_id -> ms

createServer((req, res) => {
  if (req.url === '/fail/on' || req.url === '/fail/off') {
    failing = req.url === '/fail/on';
    res.end(`failing=${failing}\n`);
    return;
  }
  let body = '';
  req.on('data', (chunk) => (body += chunk));
  req.on('end', () => {
    let json = null;
    try { json = JSON.parse(body); } catch { /* not JSON */ }
    const payload = json && (json.location ?? json);
    const records = Array.isArray(payload) ? payload : payload ? [payload] : [];
    for (const r of records) {
      const device = json.device_id ?? r.device_id ?? 'unknown';
      const recorded = Date.parse(r.recorded_at);
      const previous = lastRecordedAt.get(device);
      if (!previous || recorded > previous) lastRecordedAt.set(device, recorded);
      const gap = previous ? Math.round((recorded - previous) / 1000) + 's' : '-';
      const late = Math.round((Date.parse(r.sent_at) - recorded) / 1000);
      const where = r.coords ? `${r.coords.latitude.toFixed(5)},${r.coords.longitude.toFixed(5)}` : 'no-coords';
      console.log(
        `${new Date().toISOString()} ${device} ${String(r.event).padEnd(15)} rec=${r.recorded_at} ` +
          `gap=${gap} late=${late}s boot=${r.boot_count} ${r.reason ?? ''} ${where}` +
          (failing ? '  -> 500' : ''),
      );
    }
    res.writeHead(failing ? 500 : 200, { 'Content-Type': 'application/json' });
    res.end(failing ? '{"error":"simulated failure"}' : '{"ok":true}');
  });
}).listen(8080, () => console.log('listening on :8080'));
```

Expose it over HTTPS with a tunnel, for example `cloudflared tunnel --url http://localhost:8080` or `ngrok http 8080`.
In the example app, set **http.url** to `https://<tunnel-host>/locations`. Keep the default `device_id` (it is sent as
`http.params`), or give every phone a readable one. `curl https://<tunnel-host>/fail/on` makes the server answer `500`,
and `/fail/off` turns that off again.

## Useful adb commands

| Command | Purpose |
|---|---|
| `adb shell pidof com.brickssoft.locationtracking.example` | Is the app process alive? Note the PID before and after process tests. |
| `adb shell dumpsys activity services com.brickssoft.locationtracking.example` | Is the foreground service running? |
| `adb shell dumpsys alarm \| grep -A4 com.brickssoft.locationtracking.example` | The scheduled heartbeat alarms. |
| `adb shell dumpsys battery unplug` / `adb shell dumpsys battery reset` | Pretend the phone runs on battery while it is connected by USB (Doze never starts while charging). |
| `adb shell dumpsys deviceidle enable` | Make sure Doze is enabled. |
| `adb shell dumpsys deviceidle force-idle` / `adb shell dumpsys deviceidle unforce` | Enter deep idle now, and leave forced idle again. |
| `adb shell dumpsys deviceidle get deep` | Current deep-idle state (`IDLE` while dozing). |
| `adb shell dumpsys deviceidle whitelist +com.brickssoft.locationtracking.example` (and `-...`) | Grant or remove the battery-optimization exemption without going through the UI. |
| `adb shell am kill com.brickssoft.locationtracking.example` | Asks Android to kill the process. This works only if Android considers the process safe to kill, which a running foreground service usually prevents. |
| `adb shell am force-stop com.brickssoft.locationtracking.example` | The same as Settings → Force stop. |
| `adb reboot` | Reboot. |
| `adb logcat -v threadtime \| grep ' LT'` | Plugin log lines (tags start with `LT`). |

## Standard setup for each test

Unless a test says otherwise:

1. Open the example app, set `http.url`, and keep the defaults: `heartbeat` 180/300 s, `locationProvider: auto`,
   `stopOnTerminate` on, `startOnBoot` off. Press **ready**.
2. Press **requestPermissions (all four)**, and grant location "Allow all the time", notifications and physical
   activity.
3. Press **start**. The tracking notification appears, and the server logs `tracking_start` with reason `start`, then
   a `motionchange`.
4. When a test is finished, press **stop** (the server logs `tracking_stop`, reason `stop`), and reset any adb
   overrides (`deviceidle unforce`, `battery reset`).

## Procedures

### Doze with and without the battery exemption

1. Standard setup, with the phone lying still on a table.
2. Remove the exemption: `adb shell dumpsys deviceidle whitelist -com.brickssoft.locationtracking.example`. The
   heartbeat panel shows `isIgnoringBatteryOptimizations: false`.
3. Turn the screen off. Run `adb shell dumpsys battery unplug`, `adb shell dumpsys deviceidle enable`, and
   `adb shell dumpsys deviceidle force-idle`. Check that `adb shell dumpsys deviceidle get deep` prints `IDLE`.
4. Wait 45 minutes without touching the phone, and watch the server's `gap=` values.
5. Run `adb shell dumpsys deviceidle unforce`.
6. Grant the exemption, either through **openBatteryOptimizationSettings** (All apps → LT Example → Don't optimize),
   or with `adb shell dumpsys deviceidle whitelist +com.brickssoft.locationtracking.example`. Repeat steps 3–5.
7. Run `adb shell dumpsys battery reset`.

Expected: without the exemption, heartbeat gaps of **about 9 minutes** while forced idle (on Android 12+ the strategy
is `idle_paced`). With the exemption, gaps of **180–300 s** (strategy `exact`). Right after unforcing, the heartbeat
panel shows the strategy.

### Process death

1. Standard setup. Press Home, so the app is in the background.
2. Note the PID. Run `adb shell am kill com.brickssoft.locationtracking.example`, then check the PID again.
3. If the process survived (normal while the foreground service runs), note that and go on. For a hard kill of a debug
   build, run `adb shell run-as com.brickssoft.locationtracking.example kill -9 <pid>`.

Expected: if the process survives, nothing changes. After a hard kill, Android restarts the service (`START_STICKY`)
or the heartbeat alarm restores tracking. The server then logs `tracking_start` with reason `restore`, followed by
regular heartbeats. The gap is at most a few minutes beyond `maxInterval`.

### Offline queue and retry

1. Standard setup, phone still. Turn on airplane mode and make sure Wi-Fi is off.
2. Wait 15 minutes. The app's event log keeps showing `heartbeat` events. **getCount** grows, and the heartbeat
   panel's `pendingHeartbeats` is above 0.
3. Turn airplane mode off.

Expected: a `connectivitychange` event (connected), then within seconds the server receives the queued heartbeats.
Their `recorded_at` values are 180–300 s apart during the outage, and their `sent_at` is after the reconnect, so
`late=` is several minutes. **getCount** drops back to 0.

## Checklist

Phones: **GMS** = GMS phone, **HMS** = Huawei HMS-only phone, **None** = phone with neither SDK usable. "Server"
means the test server's log.

| ID | Phone | Scenario | Steps | Expected result | Result |
|---|---|---|---|---|---|
| P1 | GMS | Backend selection, `gms,hms` build, `auto` | ready, getDeviceInfo, getState | `gmsAvailable: true`, `packagedProviders: ["gms","hms"]`, `backend: "gms"`. The server's records have `"backend":"gms"`. | |
| P2 | HMS | Backend selection, `gms,hms` build, `auto` | ready, getDeviceInfo, start | `gmsAvailable: false`, `hmsAvailable: true`, `backend: "hms"`. Locations and `activitychange` events arrive. No `907135xxx` / `6003` errors in getLog. | |
| P3 | None | Backend fallback, `auto` | ready, getDeviceInfo, start, walk 200 m | `backend: "android"`. Locations are recorded. No `activitychange` events (the Android backend has no activity recognition); motion is detected by distance. | |
| P4 | GMS | `hms`-only build | install the `hms` build, ready, getDeviceInfo | `packagedProviders: ["hms"]`. HMS Core is missing, so `backend: "android"`. | |
| P5 | HMS | `gms`-only build | install the `gms` build, ready, getDeviceInfo | `packagedProviders: ["gms"]`, `backend: "android"`. | |
| P6 | GMS | Forced Android backend | set locationProvider `android`, setConfig, getState | `backend: "android"`. Setting `auto` again brings back `gms`. | |
| T1 | all | Start | standard setup | Notification shown. Server: `tracking_start` (reason `start`), then `motionchange` with `is_moving:false`. | |
| T2 | all | Moving | walk or drive for 10 min | `motionchange` with `is_moving:true`, then `location` records about every `distanceFilter` meters. The odometer grows. Few or no heartbeats while moving. | |
| T3 | all | Stationary | stop moving for `stopTimeout` (5 min) or more | `motionchange` with `is_moving:false`. From then on, heartbeats every 180–300 s with the same coords and an old `timestamp`. | |
| T4 | all | Stop | press stop | Server: `tracking_stop` (reason `stop`). The notification disappears, and no more records arrive. The heartbeat strategy is `disabled`. | |
| T5 | GMS | changePace | changePace(moving), then changePace(stationary) | A `motionchange` for each. | |
| H1 | all | Heartbeat baseline | phone still and awake (or charging) for 30 min | Server: a `heartbeat` every 180–300 s, never more than 300 s apart. The heartbeat panel shows strategy `exact` or `listener_with_backup` and a `nextHeartbeatAt` in the future. | |
| H2 | GMS, HMS | Deep idle, **no** exemption | [Doze procedure](#doze-with-and-without-the-battery-exemption), steps 2–5 | Heartbeats about 9 min apart while forced idle. `idle_paced` on Android 12+. | |
| H3 | GMS, HMS | Deep idle, **with** exemption | Doze procedure, step 6 | Heartbeats 180–300 s apart while forced idle. Strategy `exact`. | |
| H4 | GMS | Overnight | exemption on, phone still overnight (unplugged) | No gap above 300 s + 120 s grace. | |
| H5 | GMS | Overnight without exemption | exemption off, phone still overnight | Gaps of about 9 min while dozing, none longer than about 11 min. | |
| H6 | all | Heartbeat without any fix | fresh install, start deep indoors (no fix possible), wait 5 min | A heartbeat with `coords:null` and `timestamp:null` if no location was ever known; otherwise the last known coords. | |
| K1 | all | `am kill` | [process death procedure](#process-death) | The process survives and tracking continues, or after a kill, `tracking_start` (reason `restore`) and heartbeats resume. | |
| K2 | all | Swipe away, `stopOnTerminate: true` | remove the app from Recents | Server: `tracking_stop` (reason `terminate`). The notification disappears, and no heartbeats follow. | |
| K3 | all | Swipe away, `stopOnTerminate: false` | uncheck app.stopOnTerminate, setConfig, start, remove the app from Recents, wait 15 min | The notification stays, and heartbeats continue every 180–300 s. If the phone kills the process anyway, a `tracking_start` (reason `restore`) appears. | |
| K4 | all | Force-stop | `adb shell am force-stop ...` (or Settings → Force stop), wait 15 min, then open the app | Nothing reaches the server while stopped (no `tracking_stop`): a gap, as expected. After opening the app, tracking resumes (`tracking_start`, reason `restore`). | |
| K5 | all | Reboot with `startOnBoot: true` | check app.startOnBoot, setConfig, start, `adb reboot`, unlock the phone | After the first unlock: `tracking_start` (reason `boot`) and a higher `boot_count`. The notification is back, and heartbeats resume. Needs "Allow all the time". | |
| K6 | all | Reboot with `startOnBoot: false` | as in K5, but unchecked | Nothing after the reboot. Opening the app shows `enabled: false`. | |
| K7 | GMS | App update, `startOnBoot: true` | tracking on, then `adb install -r app-debug.apk` | `tracking_start` (reason `package_replaced`). | |
| K8 | all | Battery "Restricted" (Android 12+) | App info → Battery → Restricted, screen off for 30 min | Heartbeats stop (a gap) until the app is opened again. Set it back to Optimized or Unrestricted. | |
| N1 | all | Offline queue | [offline procedure](#offline-queue-and-retry) | Heartbeats created on time and delivered after the reconnect, with `sent_at` later than `recorded_at`. The queue drains to 0. | |
| N2 | GMS | Server errors | `curl .../fail/on` for 10 min, then `/fail/off` | `http` events with `success:false, status:500`, and **getCount** grows. After `/fail/off`, the next heartbeat (or **sync**) uploads everything, oldest first. | |
| N3 | GMS | No URL | clear http.url, setConfig, wait 5 min, then **sync** | Records queue up. `sync` rejects with `NO_URL`. | |
| N4 | GMS | Batch mode | check http.batchSync, setConfig, then airplane mode on/off after 10 min | The server receives JSON arrays under `location`, oldest first. | |
| N5 | GMS | Cellular only | extra config `{"http":{"disableAutoSyncOnCellular":true}}`, Wi-Fi off, mobile data on, walk | Only `heartbeat`, `tracking_*` and `providerchange` records upload on cellular. Locations upload once Wi-Fi is back. | |
| S1 | all | Location services off/on | quick-settings location toggle off, wait 1 min, on | `providerchange` events and records: `enabled:false`, then `enabled:true`. Heartbeats continue meanwhile. | |
| S2 | all | Permission downgrade to "while in use" | Settings → Apps → LT Example → Location → Allow only while using the app, then open the app | The app process was killed (new PID). On the next run, a `providerchange` with `permission:"when_in_use"` is uploaded. | |
| S3 | all | Permission removed | Location → Don't allow, then open the app | A gap while the app is dead. Then a `providerchange` with `permission:"denied"`. `start()` rejects with `PERMISSION_DENIED`. | |
| S4 | Android 12+ | Approximate location | turn off "Use precise location", then open the app | A `providerchange` with `accuracy:"approximate"`. The records' accuracy is coarse. | |
| S5 | Android 11+ | Background permission flow | fresh install; requestPermissions (checked: location, then backgroundLocation) | The rationale dialog, then the Settings page. After choosing "Allow all the time", `backgroundLocation: granted`. | |
| S6 | Android 13+ | Notifications denied | deny notifications, then start | Tracking works (records arrive), but the notification is hidden. | |
| S7 | all | Battery-optimization settings | openBatteryOptimizationSettings | The system list of apps opens (`opened: true`). After exempting, getBatteryOptimizationStatus shows `isIgnoringBatteryOptimizations: true`. | |
| G1 | all | Circle geofence | stand still, **Add circle** (radius 200) | A `geofence` event and record `ENTER` right away (`initialTriggerEntry`), and a `geofenceschange` event. | |
| G2 | all | Exit | walk more than 300 m away | A `geofence` `EXIT` event and record, with the fix's coords. | |
| G3 | all | Polygon | **Add polygon (square)**, walk in and out | `ENTER` / `EXIT` for the square. getGeofences shows the vertices plus the enclosing circle. | |
| G4 | all | Dwell | notifyOnDwell checked, add a circle, stay inside for 1 min | A `DWELL` after `loiteringDelay` (30 s). | |
| G5 | all | Geofences-only mode | **startGeofences** | `tracking_start` (reason `start_geofences`). No continuous `location` records, but geofence transitions and heartbeats keep coming. | |
| O1 | HMS | Huawei App launch | openPowerManagerSettings, set the app to Manage manually (all switches on), then leave the phone overnight | The App launch screen opens (`opened: true`). Overnight, no gaps beyond the H4/H5 expectations. Repeat once with "Manage automatically" to see the difference. | |
| O2 | Xiaomi / Oppo / Vivo / Samsung | Phone maker's power manager | getPowerManagerInfo, openPowerManagerSettings | `available: true` and the phone maker's screen opens, or `available: false` / `opened: false` without a crash. | |
| M1 | all | Server audit | review the server log for H1–K7 | Every gap above 420 s is explained by a `tracking_stop`, a `tracking_start` (`boot` / `restore`), a `providerchange`, or a documented case (force-stop, Restricted). | |
| M2 | all | Event log | exercise every section | All 13 event types appear in the example's event log with timestamps, newest first. | |
| M3 | all | Notification actions | check "notification actions", setConfig, tap **Stop** in the notification | A `notificationaction` event with `id:"stop"`, after which the example app calls `stop()`. | |
| M4 | all | Power saver | toggle Battery Saver | `powersavechange` events. The heartbeat panel shows `isPowerSaveMode`. | |
| M5 | all | Logs | log, getLog, uploadLog (to `https://<tunnel>/logs`), emailLog | Log lines appear in getLog. The upload returns `success:true`. The email chooser opens with the log attached. | |
