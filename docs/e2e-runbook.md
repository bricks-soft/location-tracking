# End-to-end test runbook

This runbook is written for an **AI agent** that runs the end-to-end tests of
`@bricks-soft/capacitor-location-tracking` step by step. A person can follow it too.

It covers:

- the automated suites on an Android emulator (AVD): the plugin suite (`e2e/plugin`, against the plugin's example app
  `example/`) and the field-force suite (`examples/field-force/e2e`, against the field-force example app);
- the manual procedures **M-01 … M-08**, which need a real phone, a real drive, a real night or a release build.

The contract behind the tests (scenario ids, debug commands, mock back office, kit API) is
[docs/e2e/architecture.md](e2e/architecture.md). The older manual release checklist for real phones is
[docs/device-test-checklist.md](device-test-checklist.md); this runbook does not replace it.

## Contents

1. [How to read this runbook](#1-how-to-read-this-runbook)
2. [What is tested where](#2-what-is-tested-where)
3. [Prerequisites and local AVD setup](#3-prerequisites-and-local-avd-setup)
4. [The mock back office](#4-the-mock-back-office)
5. [Driving the apps by hand](#5-driving-the-apps-by-hand)
6. [Running the automated suites](#6-running-the-automated-suites)
7. [Triage of a failed scenario](#7-triage-of-a-failed-scenario)
8. [Manual procedures M-01 … M-08](#8-manual-procedures-m-01--m-08)
9. [Report template for a full run](#9-report-template-for-a-full-run)

---

## 1. How to read this runbook

Every step starts with one of three markers:

| Marker | Who does it | How |
|---|---|---|
| **[auto]** | The agent | A command the agent runs on the host computer (`adb`, `npm`, `curl`, `node`). |
| **[agent]** | The agent | An action on the phone or emulator that the agent performs itself through `adb`: open a screen, tap, type, read the screen (see [5.5](#55-ui-actions-the-agent-performs-itself)). |
| **[person]** | A person, asked by the agent | A physical action, or a UI action the agent cannot do: carry or drive the phone, unplug the USB cable, unlock a phone with a PIN the agent does not know, register an app in AppGallery Connect. The agent writes the exact instruction, waits for the person to confirm, and writes down the time the person reports. |

Rules for the agent:

- Run the steps in order. Do not skip a step because it "probably" works.
- Write down every time as ISO-8601 UTC **and** as the phone's local time. Records use UTC; the 02:00 stop uses local
  time.
- Keep every output file. Put the files of one run in one directory outside the repository, for example
  `~/lt-runs/2026-09-27-m05/`.
- When a step fails, stop the procedure, save the evidence ([7](#7-triage-of-a-failed-scenario)), and report it. Do not
  retry more than the rerun rule in [7.3](#73-rerun-rule-and-what-to-report) allows.

Words used in this runbook:

| Word | Meaning |
|---|---|
| AVD | Android Virtual Device: an emulator configuration created with `avdmanager`. |
| KVM | The Linux kernel's hardware virtualization. The x86_64 emulator needs it; without it the emulator is 10 to 50 times slower and the tests are not valid. |
| `google_apis` image | An emulator system image with Google Play services (GMS) that allows `adb root`. The suites need root for some scenarios. |
| `default` image | An emulator system image without Google Play services. Used for P-P08. |
| Doze, deep idle | Android's battery saving state when the screen is off, the phone is unplugged and does not move. Alarms and network access are limited. |
| FGS | Foreground service: a service with a visible notification. The plugin's tracking service is one. |
| Back office | The server that receives the uploads. In the tests this is the **mock back office** (section [4](#4-the-mock-back-office)). |
| Record | One object the plugin uploads, for example a `location` or a `heartbeat`. Fields: [wire-format.md](wire-format.md). |
| Scenario id | A stable test id: `P-L01` … `P-P11` (plugin suite), `F-01` … `F-12` (field-force suite), `M-01` … `M-08` (manual). |
| Battery exemption | The app is excluded from battery optimization ("Unrestricted" battery use). See [heartbeat.md](heartbeat.md#battery-optimization-exemption). |

---

## 2. What is tested where

The plugin's behavior (lifecycle, heartbeat, permissions, providers, geofences) is tested against the plugin's own
example app, so it does not depend on the field-force app. The field-force suite tests the product setup of the
Bricks app (auto start, 02:00 stop, live location, the companion PremiseMonitor plugin). The field-force app, its
suite and `testing/e2e-kit` can move into the Bricks app later as its integration test.

| Edge case | Automated on the AVD | Manual on a real phone |
|---|---|---|
| `ForegroundServiceDidNotStartInTimeException` and other foreground-service start crashes | P-L01, P-L02, P-L13 | M-05 (the morning start after the 02:00 stop) |
| Process killed (low memory, phone maker's task killer) | P-L03, P-L12, F-09 | M-02 |
| Force stop from Settings | P-L04 | – |
| Activity recreated (configuration change) | P-L05 | – |
| Notifications denied | P-L06, P-P05 | – |
| App update | P-L07 | – |
| Phone restarted | P-L08, P-L09, P-P11, F-03, F-10 | M-06 |
| Fake boot broadcast | P-L10 | – |
| Android 12+ refuses a background service start | P-L11 | M-06 |
| Stationary: GPS off, heartbeats continue | P-H01, P-H02, F-05 | M-04 |
| Movement detected again after a stop | P-H03, F-04 | M-01, M-03 |
| Deep sleep (Doze) | P-H04, P-H05 | M-08 |
| Clock and time-zone changes | P-H06, F-02 | – |
| No network | P-H07, F-06 | – |
| Server errors, JWT refresh | P-H08 | – |
| Live location (`http.syncInterval`) | P-H09, F-04 | M-03 |
| Heartbeat metadata | P-H10 | M-08 |
| Permission changes (fine, all, background, activity, notifications) | P-P01 … P-P05 | M-06 |
| Location services off and on | P-P06 | – |
| Location provider change (GMS, Android, HMS) | P-P07, P-P08 | M-01 |
| Mock locations | P-P09 | – |
| Geofences | P-P10, P-P11, F-07, F-11, F-12 | M-01 |
| Companion plugin receives every record and event natively | F-07, F-08, F-09, F-10 | M-02 |
| 02:00 stop | F-02, F-03 | M-05 |
| Travel distance and time | F-04 | M-03 |
| Battery use | – | M-04 |
| 16 KB page size (Google Play) | CI 16 KB check | M-07 |

The scenario list with titles is printed by `npm run catalogue` in `testing/e2e-kit`.

---

## 3. Prerequisites and local AVD setup

### 3.1 Host

| Item | Requirement |
|---|---|
| Operating system | Linux x86_64 with KVM (this runbook's commands). On an Apple Silicon Mac, use the `arm64-v8a` system images instead of `x86_64`; the other commands are the same. |
| CPU and memory | 4 cores and 8 GB RAM at least; the emulator uses 4 GB. |
| Disk | 20 GB free (SDK, two system images, Gradle caches). |
| Node.js | 22.18 or newer (`node --version`). The kit runs its TypeScript sources directly. |
| JDK | 21 (`java -version`). |
| Android SDK | Command-line tools installed; `ANDROID_HOME` set. |
| Tools | `curl`, `unzip`, `python3` or `objdump` (M-07 only). |

Set the environment once per shell:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"        # or wherever the SDK is
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

> The Claude development container that built this plugin has **no KVM**. Nothing in this section works there. In that
> container, the suites can only be type-checked and dry-run (see [6.7](#67-dry-run-without-a-device)); the real runs
> happen in GitHub Actions or on a machine with KVM.

### 3.2 Android SDK packages

**[auto]**

```bash
yes | sdkmanager --licenses > /dev/null
sdkmanager "platform-tools" "emulator" "platforms;android-36" "build-tools;35.0.0" \
  "system-images;android-34;google_apis;x86_64" \
  "system-images;android-34;default;x86_64"
```

- `system-images;android-34;google_apis;x86_64`: the main image (Google Play services, `adb root` works).
- `system-images;android-34;default;x86_64`: the image without Google Play services (P-P08).
- `build-tools;35.0.0`: its `zipalign` has the `-P 16` option that M-07 needs.
- Optional, for the smaller runs CI does on other API levels: `system-images;android-29;google_apis;x86_64` and
  `system-images;android-35;google_apis;x86_64`.

### 3.3 KVM check

**[auto]**

```bash
ls -l /dev/kvm                                 # must exist, e.g. "crw-rw---- 1 root kvm ..."
grep -cE 'vmx|svm' /proc/cpuinfo               # must print a number > 0
emulator -accel-check                          # must print "... KVM (version N) is installed and usable."
```

If `/dev/kvm` exists but the user may not open it, add the user to the `kvm` group and log in again:
`sudo usermod -aG kvm "$USER"`. If `/dev/kvm` does not exist (a container or a VM without nested virtualization),
stop: this machine cannot run the suites. Report "no KVM" and use the CI emulator jobs instead.

### 3.4 Create the AVDs

**[auto]**

```bash
echo no | avdmanager create avd --force -n e2e-34 \
  -k "system-images;android-34;google_apis;x86_64" -d pixel_6
echo no | avdmanager create avd --force -n e2e-34-nogms \
  -k "system-images;android-34;default;x86_64" -d pixel_6
avdmanager list avd | grep -E 'Name: e2e-34'   # both names must be listed
```

`echo no` answers "Do you wish to create a custom hardware profile?".

### 3.5 Start an AVD (cold boot) and get root

**[auto]**

```bash
mkdir -p ~/lt-runs/current
emulator -avd e2e-34 -no-snapshot -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect \
  -memory 4096 -cores 4 > ~/lt-runs/emulator-e2e-34.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb root
adb wait-for-device
adb shell whoami                               # must print "root"
adb shell getprop ro.build.version.sdk         # must print 34
```

- `-no-snapshot` makes every start a **cold boot** and saves no snapshot on exit. A snapshot would keep app state,
  alarms and Doze state from an earlier run.
- Add `-wipe-data` to also erase all apps and settings (use it when an earlier run left the device in an unknown
  state, and before a rerun of a failed scenario).
- `-no-window` runs without a window. The agent sees the screen with `adb exec-out screencap -p > screen.png`.
- Leave out `-no-window` when a person wants to watch.
- A second emulator at the same time needs another port: `emulator -avd e2e-34-nogms -port 5556 ...`. Then select a
  device with `adb -s emulator-5556 ...` and `E2E_SERIAL=emulator-5556` for the suites.
- Stop the emulator with `adb emu kill` (or `adb -s <serial> emu kill`).

Optional, for steadier timing: turn animations off.

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

### 3.6 Build the two debug APKs

Build the plugin first; both apps copy its `dist/plugin.js`. Run from the repository root.

**[auto]**

```bash
npm ci && npm run build

(cd example && npm ci && npm run sync && cd android && ./gradlew assembleDebug -PlocationTracking.providers=gms,hms)
ls -l example/android/app/build/outputs/apk/debug/app-debug.apk

(cd examples/field-force && npm ci && npm run sync && cd android && ./gradlew assembleDebug)
ls -l examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk
```

- The plugin example packages GMS and HMS (`gms,hms`). The field-force app packages GMS only (its
  `gradle.properties` sets `locationTracking.providers=gms`, like a Google Play build).
- Both debug builds are signed with `testing/debug.keystore`, so an APK built in CI can be installed over a local one
  (and back) with `adb install -r` without losing the app's data.
- The field-force app reaches the back office at `http://10.0.2.2:8787` (the host, seen from the emulator). To build it
  for another address, set `FF_BACKEND_URL` before `npm run sync`, for example
  `FF_BACKEND_URL=https://abc.trycloudflare.com npm run sync`.
- In the Claude development container, use `../../scripts/gradle-slot.sh` (from `example/android`) or
  `../../../scripts/gradle-slot.sh` (from `examples/field-force/android`) instead of `./gradlew`.

Install both on the running AVD. `-g` grants every runtime permission; each scenario sets its own permissions again.

**[auto]**

```bash
adb install -r -g example/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r -g examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep -E 'com.brickssoft.(locationtracking|fieldforce).example'   # 2 lines
```

### 3.7 Install the kit and the suites

**[auto]**

```bash
(cd testing/e2e-kit && npm ci && npm run typecheck)
(cd e2e/plugin && npm ci && npm run typecheck)
(cd examples/field-force/e2e && npm ci && npm run typecheck)
```

---

## 4. The mock back office

The mock back office is a small HTTP server in `testing/e2e-kit` (Node built-ins only). It stores what the apps
upload, in memory, and has control endpoints for the tests. The automated suites start their own copy on port 8787.
The manual procedures use a standalone copy.

### 4.1 Start it standalone

**[auto]**

```bash
cd testing/e2e-kit
npm run backoffice -- --port 8787 | tee ~/lt-runs/backoffice.log
```

- It listens on `0.0.0.0:8787` and prints one line per request. `--host` changes the address.
- From the emulator it is `http://10.0.2.2:8787`. From the host it is `http://localhost:8787`.
- **Stop it (Ctrl+C) before running the automated suites.** They start their own back office on the same port and
  fail with `EADDRINUSE` if the port is taken (or set `E2E_BACKEND_PORT` to another port).
- Everything is kept in memory. Stopping the process loses all records. See [4.4](#44-saving-records-during-long-runs).

### 4.2 Control endpoints

| Endpoint | What it does | Example |
|---|---|---|
| `GET /__health` | `{"ok":true,"records":n,"premise":n,"uptimeMs":…}` | `curl -s localhost:8787/__health` |
| `GET /__records` | Stored plugin records (JSON array of `{receivedAt, requestId, path, index, batchSize, record, params, authorization}`), in arrival order. Filters: `event` (a comma list), `since` (epoch ms of arrival), `uuid` (every receipt of one record), `unique=1` (only the first receipt of each uuid; `true` and `yes` also work, any other value is off). | `curl -s 'localhost:8787/__records?event=heartbeat,tracking_start,tracking_stop&unique=1'` |
| `GET /__premise` | Stored PremiseMonitor audit entries (JSON array of `{receivedAt, requestId, deviceId, entry}`; `deviceId` only when the upload had a `device_id`). Filters: `since`, `kind` (`record`, `event`, `premise`), `type` (for `premise` entries: `enter`, `exit`, `presence_violation`, `service_started`, …), `event` (the record's `event` of a `record` entry, or the event name of an `event` entry). | `curl -s 'localhost:8787/__premise?kind=premise&type=enter'` |
| `GET /__requests` | The request log (method, path, status, headers, body). Filters: `path`, `since`. | `curl -s 'localhost:8787/__requests?path=/locations'` |
| `POST /__faults` | Makes the next matching requests fail: `{path, status? = 500, count? = 1 (-1 = until reset or cleared), delayMs?, drop?}`. A fault with only `delayMs` delays and then answers normally. Faulted requests store nothing. | `curl -s -X POST localhost:8787/__faults -H 'content-type: application/json' -d '{"path":"/locations","status":500,"count":3}'` |
| `DELETE /__faults` | Clears all faults. | `curl -s -X DELETE localhost:8787/__faults` |
| `POST /__reset` | Forgets records, premise entries, requests, faults and the token counter. | `curl -s -X POST localhost:8787/__reset` |

Faults never apply to the control endpoints (`/__*`).

The upload endpoints the apps use: `POST /locations` (plugin records; `PUT`, `PATCH` and sub-paths such as
`/locations/x` are stored the same way), `POST /premise-audit` (PremiseMonitor), `POST /auth/refresh` (JWT refresh:
answers `accessToken` `e2e-access-<n>`), `POST /logs` (`uploadLog()`).

### 4.3 Reaching it from a real phone

The debug builds allow plain `http://` only to `10.0.2.2` and `localhost`. A real phone has two ways to reach the
back office on the host:

| Way | When | Setup | URL for the app |
|---|---|---|---|
| USB reverse port | Short checks while the phone stays connected by USB | **[auto]** `adb reverse tcp:8787 tcp:8787` (lost on reboot and when the cable is unplugged) | `http://localhost:8787` |
| HTTPS tunnel | All M procedures: the phone is unplugged, moves, sleeps or reboots | **[auto]** `cloudflared tunnel --url http://localhost:8787` (prints `https://<words>.trycloudflare.com`) or `ngrok http 8787` | `https://<tunnel host>` |

For the tunnel: the host computer must stay on, online and awake for the whole procedure. On Linux run
`systemd-inhibit --what=sleep:idle sleep 86400 &`; on macOS run `caffeinate -dimsu &`.

**[auto]** Check the tunnel from the host: `curl -s https://<tunnel host>/__health` must print `{"ok":true,...}`.

The mock back office has no authentication. Anyone who knows the tunnel URL can read every stored record
(`/__records`, including each upload's `Authorization` header) and can reset the back office or add faults. Use
test phones, test accounts and test tokens only, keep the tunnel URL out of reports that leave the team, and stop the
tunnel when the procedure ends.

### 4.4 Saving records during long runs

**[auto]** For every procedure longer than one hour, save a copy of the records every 30 minutes, so a crash of the
back office or the host loses at most 30 minutes:

```bash
mkdir -p ~/lt-runs/current
while true; do
  curl -s localhost:8787/__records > ~/lt-runs/current/records-$(date -u +%Y%m%dT%H%M%SZ).json
  curl -s localhost:8787/__premise > ~/lt-runs/current/premise-$(date -u +%Y%m%dT%H%M%SZ).json
  sleep 1800
done &
```

If the back office was restarted, the phone uploads the queued records again only for records that were not yet
accepted. Records accepted before the restart exist only in the saved files. **[auto]** Merge the last snapshot from
before the restart with the records received after it, then evaluate the merged file (`lt-report.mjs` below removes
duplicate uuids):

```bash
curl -s localhost:8787/__records > ~/lt-runs/current/records-after-restart.json
node -e 'const fs = require("fs");
  const rows = process.argv.slice(1).flatMap((f) => JSON.parse(fs.readFileSync(f, "utf8")));
  fs.writeFileSync("merged.json", JSON.stringify(rows)); console.log(rows.length + " rows")' \
  ~/lt-runs/current/records-<last snapshot before the restart>.json ~/lt-runs/current/records-after-restart.json
```

The command writes `merged.json` into the current directory.

### 4.5 Helper: `lt-report.mjs`

The manual procedures evaluate the records with this script. **[auto]** Save it as `~/lt-runs/lt-report.mjs`:

```js
// lt-report.mjs: summary of the records the mock back office received.
// Usage: node lt-report.mjs <records.json | http://127.0.0.1:8787/__records> [--tz Area/City] [--from ISO] [--to ISO]
//                           [--max-interval 300] [--grace 120] [--quiet]
import { readFileSync } from 'node:fs';

const [source, ...args] = process.argv.slice(2);
const opt = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const tz = opt('--tz', Intl.DateTimeFormat().resolvedOptions().timeZone);
const from = Date.parse(opt('--from', '1970-01-01T00:00:00Z'));
const to = Date.parse(opt('--to', '2999-01-01T00:00:00Z'));
const maxIntervalDefault = Number(opt('--max-interval', '300'));
const grace = Number(opt('--grace', '120'));
const text = /^https?:/.test(source) ? await (await fetch(source)).text() : readFileSync(source, 'utf8');

const seen = new Set();
const records = JSON.parse(text)
  .map((stored) => stored.record ?? stored) // /__records rows are {receivedAt, ..., record}
  .filter((r) => !seen.has(r.uuid) && seen.add(r.uuid))
  .filter((r) => Date.parse(r.recorded_at) >= from && Date.parse(r.recorded_at) <= to)
  .sort((a, b) => Date.parse(a.recorded_at) - Date.parse(b.recorded_at));

const t = (iso) => Date.parse(iso);
const local = (ms) => new Date(ms).toLocaleString('sv-SE', { timeZone: tz });
const sec = (ms) => Math.round(ms / 1000);
const pct = (values, p) => {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
};
const haversine = (a, b) => {
  const rad = Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * rad;
  const dLon = (b.longitude - a.longitude) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.latitude * rad) * Math.cos(b.latitude * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371008.8 * Math.asin(Math.sqrt(h));
};

// 1. Timeline, gaps and their explanation (docs/heartbeat.md, "Heartbeat metadata").
const gaps = [];
const flagged = [];
let previous = null;
let meta = null; // `heartbeat` object of the latest heartbeat so far
for (const r of records) {
  const gapS = previous && previous.event !== 'tracking_stop' ? sec(t(r.recorded_at) - t(previous.recorded_at)) : null;
  if (gapS !== null) {
    gaps.push(gapS);
    const idle = meta && !meta.battery_exempt && (meta.device_idle || r.heartbeat?.device_idle);
    const allowedS = (idle ? 660 : (meta?.max_interval ?? maxIntervalDefault)) + grace;
    if (gapS > allowedS) flagged.push({ at: local(t(previous.recorded_at)), gapS, allowedS, after: previous.event, before: r.event });
  }
  if (!args.includes('--quiet')) {
    const extra = r.reason ?? (r.heartbeat ? `${r.heartbeat.strategy}${r.heartbeat.device_idle ? ' idle' : ''}` : '');
    const lateS = r.sent_at ? sec(t(r.sent_at) - t(r.recorded_at)) : '';
    const fixAgeS = r.timestamp ? sec(t(r.recorded_at) - t(r.timestamp)) : '';
    console.log([local(t(r.recorded_at)), r.event.padEnd(14), String(extra).padEnd(22), `gap=${gapS ?? '-'}`,
      `late=${lateS}`, `fixAge=${fixAgeS}`, `moving=${r.is_moving}`, `odo=${Math.round(r.odometer)}`,
      `bat=${r.battery?.level}`].join('  '));
  }
  if (r.heartbeat) meta = r.heartbeat;
  previous = r;
}

// 2. Distance and moving time.
const track = records.filter((r) => r.coords && ['location', 'motionchange'].includes(r.event));
let polylineM = 0;
for (let i = 1; i < track.length; i++) polylineM += haversine(track[i - 1].coords, track[i].coords);
const odometers = records.map((r) => r.odometer).filter((v) => typeof v === 'number');
let movingMs = 0;
let movingSince = null;
let lastMovingFix = null;
for (const r of records) {
  if (r.event === 'motionchange' && r.is_moving) movingSince = lastMovingFix = t(r.recorded_at);
  else if (r.event === 'location' && movingSince !== null) lastMovingFix = t(r.recorded_at);
  else if ((r.event === 'motionchange' || r.event === 'tracking_stop') && movingSince !== null) {
    movingMs += lastMovingFix - movingSince;
    movingSince = lastMovingFix = null;
  }
}
if (movingSince !== null) movingMs += lastMovingFix - movingSince;

// 3. Upload lateness of normal records and accuracy of heartbeat.next_at.
const normal = ['location', 'motionchange', 'current_position', 'watch_position', 'geofence'];
const lateNormal = records.filter((r) => normal.includes(r.event) && r.sent_at).map((r) => sec(t(r.sent_at) - t(r.recorded_at)));
const heartbeats = records.filter((r) => r.event === 'heartbeat');
const nextAtErrors = [];
for (let i = 1; i < heartbeats.length; i++) {
  const nextAt = heartbeats[i - 1].heartbeat?.next_at;
  if (nextAt) nextAtErrors.push(sec(t(heartbeats[i].recorded_at) - t(nextAt)));
}
const counts = {};
for (const r of records) counts[r.event] = (counts[r.event] ?? 0) + 1;

console.log(JSON.stringify({
  timeZone: tz,
  records: records.length,
  first: records.length ? local(t(records[0].recorded_at)) : null,
  last: records.length ? local(t(records.at(-1).recorded_at)) : null,
  counts,
  gapS: { median: pct(gaps, 50), p95: pct(gaps, 95), max: pct(gaps, 100) },
  unexplainedGaps: flagged,
  heartbeatNextAtErrorS: { median: pct(nextAtErrors, 50), p95: pct(nextAtErrors, 95), max: pct(nextAtErrors, 100) },
  normalRecordLateS: { median: pct(lateNormal, 50), p95: pct(lateNormal, 95), max: pct(lateNormal, 100) },
  polylineM: Math.round(polylineM),
  odometerDeltaM: odometers.length ? Math.round(Math.max(...odometers) - odometers[0]) : null,
  movingMinutes: Math.round(movingMs / 60000),
}, null, 2));
```

What it prints:

- One line per record (unless `--quiet`): local time, `event`, the `reason` or the heartbeat strategy, `gap` (seconds
  since the previous record), `late` (`sent_at − recorded_at`, seconds), `fixAge` (`recorded_at − timestamp`: how old
  the position is), `is_moving`, odometer, battery level.
- A JSON summary:
  - `gapS`: median, 95th percentile and maximum gap between consecutive records while tracking is on;
  - `unexplainedGaps`: gaps longer than the rule in [heartbeat.md](heartbeat.md#heartbeat-metadata) allows
    (`max_interval + grace`, or 11 minutes + grace when the phone was in Doze without the battery exemption);
  - `heartbeatNextAtErrorS`: how much later than the previous heartbeat's `next_at` each heartbeat arrived;
  - `normalRecordLateS`: upload delay of normal records (`syncInterval` shows here);
  - `polylineM`: the length of the line through the `location` and `motionchange` records;
  - `odometerDeltaM`: the plugin odometer at the end minus at the start (one tracking session);
  - `movingMinutes`: the sum of the moving periods (from each `motionchange` with `is_moving: true` to the last
    `location` record before the next stop).

Example: `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --tz Asia/Riyadh --quiet`.

---

## 5. Driving the apps by hand

| App | Package (application id) | Launcher activity | Debug receiver action |
|---|---|---|---|
| Plugin example | `com.brickssoft.locationtracking.example` | `.MainActivity` | `com.brickssoft.locationtracking.example.E2E` |
| Field-force example | `com.brickssoft.fieldforce.example` | `.MainActivity` | `com.brickssoft.fieldforce.example.E2E` |

### 5.1 Launch, background, stop

| Action | Command |
|---|---|
| Launch (cold or warm) | `adb shell am start -W -n com.brickssoft.fieldforce.example/.MainActivity` |
| Send to background | `adb shell input keyevent KEYCODE_HOME` |
| Is the process alive? | `adb shell pidof com.brickssoft.fieldforce.example` (no output = no process) |
| Is the tracking service running? | `adb shell dumpsys activity services com.brickssoft.fieldforce.example \| grep -E 'LocationTrackingService\|PremiseMonitorService'` |
| Force stop (like Settings → Force stop) | `adb shell am force-stop com.brickssoft.fieldforce.example` |
| Kill like a task killer (emulator, root) | `adb shell kill -9 $(adb shell pidof com.brickssoft.fieldforce.example)` |
| Kill like a task killer (real phone, debug build) | `adb shell run-as com.brickssoft.fieldforce.example kill -9 <pid>` |
| Clear data (also revokes permissions) | `adb shell pm clear com.brickssoft.fieldforce.example` |
| Plugin log lines | `adb logcat -d -v threadtime \| grep -E ' LT[. ]'` |
| Plugin log files (UTC day files) | `adb shell run-as com.brickssoft.fieldforce.example ls files/location-tracking-logs` then `adb shell run-as com.brickssoft.fieldforce.example cat files/location-tracking-logs/lt-2026-09-27.log` |
| Crashes of the app | `adb logcat -d -b crash \| grep -A20 'Process: com.brickssoft.fieldforce.example'` |

The same commands work for the plugin example with its package name.

### 5.2 Debug commands

Both **debug** builds have a broadcast receiver that runs plugin calls through the native API, without the web page
(contract §6). The agent uses it in the manual procedures. Save this helper as `~/lt-runs/e2e-cmd.sh` and load it with
`source ~/lt-runs/e2e-cmd.sh`:

```bash
# e2e_cmd <package> <request id> <command> [JSON arguments]
# Prints the answer: {"id":…,"cmd":…,"ok":true,"result":…} or {"ok":false,"code":…,"message":…}.
e2e_cmd() {
  local app="$1" id="$2" cmd="$3" json="${4:-}"
  [ -z "$json" ] && json='{}'
  adb shell am broadcast -a "$app.E2E" -n "$app/.e2e.E2eCommandReceiver" --include-stopped-packages \
    --es id "$id" --es cmd "$cmd" --es json64 "$(printf '%s' "$json" | base64 | tr -d '\n')" > /dev/null
  local i line
  for i in $(seq 1 30); do
    line=$(adb logcat -d -s LT-E2E:I | grep -F "\"id\":\"$id\"" | tail -n 1 | sed 's/^[^{]*//')
    if [ -n "$line" ]; then
      case "$line" in
        *'"resultFile":'*) adb shell run-as "$app" cat "files/e2e/$id.json" ;;
        *) printf '%s\n' "$line" ;;
      esac
      return 0
    fi
    sleep 1
  done
  echo "no LT-E2E answer for $id after 30 s" >&2
  return 1
}
```

- Use a new request id for every call (`[A-Za-z0-9._-]`, at most 64 characters), for example `m05-state-1`,
  `m05-state-2`. The plugin example refuses the id `example`, and the field-force app refuses the ids `ff-overrides`
  and `example`, with `BAD_COMMAND`: their result file would replace a test-mode file ([5.3](#53-test-mode-files)).
- Commands: `ready {config?, reset?}`, `setConfig {config}`, `start`, `startGeofences`, `stop`,
  `changePace {isMoving}`, `state`, `heartbeatStatus`, `sync`, `insertLocation {location}`,
  `addGeofence {geofence}`, `removeGeofence {identifier}`, `getGeofences`, `blockMainThread {ms, delayMs?}`; in the
  plugin example also `otherAppLocation {enabled, intervalMs?}` (see below); in the field-force app also
  `premise.start {premise, auditUrl?}`, `premise.stop`, `premise.status`, `premise.auditLog {limit?}`. A missing or wrongly typed argument, an unknown command or invalid JSON answers
  `BAD_COMMAND`; a plugin error answers with the plugin's error code (for example `PERMISSION_DENIED`).
- The receiver answers within 25 s, or with `"code":"TIMEOUT"`. (This helper sends background broadcasts. With
  `--receiver-foreground`, the plugin example answers `TIMEOUT` after 8 s; the field-force app ends the broadcast
  after 8 s and still answers within 25 s.)
- Both receivers accept only senders that hold `android.permission.DUMP`. `adb shell` and root hold it; other apps on
  the phone do not, so they cannot stop tracking, insert locations or change the upload URL.
- A result longer than 3000 bytes goes to `files/e2e/<id>.json` (the full response object); the helper prints that
  file.
- `otherAppLocation {enabled, intervalMs = 1000}` (plugin example only) requests GPS updates from the app's own process
  through the platform `LocationManager`, the way another app would, or stops them; it answers
  `{enabled, intervalMs, fixes}`. It exists for the emulator, which produces a fix only while some client asks the GPS
  provider: P-H03 and P-P04 turn it on during the drive so the plugin's passive request and Google Play services'
  geofencing get fixes while the plugin's GPS is off. **On a real phone it is not needed** (network location and
  other apps produce fixes), and a procedure that checks "no GPS request of the app" must keep it off, because
  `dumpsys location` attributes the request to the app.
- `start` from a background app is refused on Android 12+ (that is P-L11). **Keep the app's screen open** when sending
  `start` or `startGeofences`.

Examples:

```bash
e2e_cmd com.brickssoft.fieldforce.example m05-state-1 state
e2e_cmd com.brickssoft.fieldforce.example m05-hb-1 heartbeatStatus
e2e_cmd com.brickssoft.locationtracking.example m01-pace-1 changePace '{"isMoving":true}'
```

### 5.3 Test-mode files

The web pages read JSON files from the app's storage at startup (debug builds only, contract §6). Write them **after**
`pm clear` and **before** launching the app.

| App | File | Effect |
|---|---|---|
| Plugin example | `files/e2e/example.json` = `{"e2e": true}` | E2E mode: the page never calls `ready`, `start`, `stop` or any other state-changing method by itself, and shows an "E2E mode" banner. Use it when the agent drives the plugin with debug commands. |
| Field-force | `files/e2e/ff-overrides.json` = `FieldForceOverrides` | Changes the production preset: `stopAt` (`'HH:MM'`, default `'02:00'`), `heartbeat {minInterval, maxInterval}`, `syncInterval`, `backendUrl`, `premise`, `autoStart` (default true), `configPatch` (deep-merged into the plugin config last). Keys that are not given keep the production values. |

**[auto]** Write, read back and remove a file:

```bash
printf '%s' '{"backendUrl":"https://abc.trycloudflare.com"}' | \
  adb shell "run-as com.brickssoft.fieldforce.example sh -c 'mkdir -p files/e2e && cat > files/e2e/ff-overrides.json'"
adb shell run-as com.brickssoft.fieldforce.example cat files/e2e/ff-overrides.json
adb shell run-as com.brickssoft.fieldforce.example rm files/e2e/ff-overrides.json
```

The field-force page keeps a running session's stop time: when tracking is already on at launch (or at a page
reload), it does not compute `stopAfterElapsedMinutes` again. To test a new `stopAt`, write `ff-overrides.json` before
the first launch after `pm clear`, or send `stop` first and then reload or relaunch the app.

The field-force page also runs its startup again when the app comes back to the foreground while tracking is off (the
document event `resume`, or `visibilitychange` to visible). It then reads the overrides file again and computes the
minutes to the next stop time again. It does nothing when the overrides say `autoStart: false`. So after a `stop`
debug command, bringing the app to the front starts tracking again.

The field-force page asks for every permission that is not granted, and its startup waits until the permission
dialog is answered. Grant every permission a procedure needs with `pm grant` before the launch; when a procedure
leaves a permission out on purpose (M-06), the agent answers the dialog ([5.5](#55-ui-actions-the-agent-performs-itself)).

### 5.4 Reading the field-force page's startup result

The field-force page exposes its startup in `window.FF_APP`:

- `status` (`'running'`, `'done'` or `'failed'`), `step` (the startup step it is on), `result`
  (`{state, stopAfterElapsedMinutes, config, deviceInfo, warnings, …}`; `warnings` explains overrides that were
  ignored), `error` (`{code, message, step}`), and `startup` (the promise of the latest startup run). These describe
  the latest run.
- `startupCount` (startup runs in this page), `lastStartupReason` (`'load'` or `'resume'`), `lastResume` (the latest
  foreground check: `{at, trigger, outcome}`, `outcome` one of `started`, `enabled`, `busy`, `autostart_off`,
  `error`), and `checkResume()` (runs the foreground check by hand).

**[auto]** Save this helper as `~/lt-runs/ff-app.mjs` and run `node ~/lt-runs/ff-app.mjs <repository root>` while the
app runs (it uses the kit's WebView driver, so `testing/e2e-kit` must be installed):

```js
// ff-app.mjs: prints the field-force page's startup state (window.FF_APP) through the app's WebView.
// Usage: node ff-app.mjs <repository root>     (Node >= 22.18, the app is running, E2E_SERIAL for several devices)
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const kit = await import(pathToFileURL(resolve(process.argv[2] ?? '.', 'testing/e2e-kit/src/index.ts')).href);
const env = kit.readEnv();
const adb = new kit.Adb({ serial: env.serial, adbPath: env.adbPath });
const page = await kit.WebViewDriver.connect(adb, kit.APP_IDS.fieldForce);
try {
  const expression =
    '({ status: FF_APP.status, step: FF_APP.step, result: FF_APP.result, error: FF_APP.error, ' +
    'startupCount: FF_APP.startupCount, lastStartupReason: FF_APP.lastStartupReason, lastResume: FF_APP.lastResume })';
  console.log(JSON.stringify(await page.evaluate(expression), null, 2));
} finally {
  await page.close();
}
```

`status: 'done'` with `result.state.enabled: true` means the auto start worked. A `status` that stays `'running'`
usually means a permission dialog is open (take a screenshot).

### 5.5 UI actions the agent performs itself

| Goal | Command |
|---|---|
| See the screen | `adb exec-out screencap -p > ~/lt-runs/current/screen-$(date -u +%H%M%S).png`, then read the image. |
| Find a button's position | `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` and read the `bounds` of the node with the wanted `text`. |
| Tap, type, keys | `adb shell input tap <x> <y>`, `adb shell input text 'abc'`, `adb shell input keyevent KEYCODE_BACK` |
| Wake and unlock a phone without a PIN | `adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard` |
| Screen off | `adb shell input keyevent KEYCODE_SLEEP` |
| Open the app's settings page | `adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.brickssoft.fieldforce.example` |
| Open the battery-optimization list | `adb shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| Battery exemption on / off | `adb shell dumpsys deviceidle whitelist +com.brickssoft.fieldforce.example` / `... whitelist -com.brickssoft.fieldforce.example` |
| Grant / revoke a permission | `adb shell pm grant com.brickssoft.fieldforce.example android.permission.ACCESS_BACKGROUND_LOCATION` / `pm revoke ...` |
| Show granted location permissions | `adb shell dumpsys package com.brickssoft.fieldforce.example \| grep -E 'ACCESS_(FINE\|COARSE\|BACKGROUND)_LOCATION'` |

Phone makers' power-manager screens differ per model and are not reachable with one intent. The agent may try them with
taps (using the menu paths in [heartbeat.md](heartbeat.md#phone-makers-power-managers)); when a screen does not match,
it asks a person.

---

## 6. Running the automated suites

Before a run: an AVD is booted and rooted ([3.5](#35-start-an-avd-cold-boot-and-get-root)), both APKs are installed
([3.6](#36-build-the-two-debug-apks)), the kit and suites are installed ([3.7](#37-install-the-kit-and-the-suites)),
and **no standalone back office runs on port 8787**. The suites start their own back office and write every test
file they need. Each scenario prepares its own device state; the order does not matter.

Scenarios run one at a time (`--test-concurrency=1`): there is one device and one back office port. Never run the
two suites at the same time against one emulator.

### 6.1 Full runs

**[auto]** The plugin suite (34 scenarios; estimated 2 to 3 hours on a KVM host):

```bash
cd e2e/plugin
E2E_APK=../../example/android/app/build/outputs/apk/debug/app-debug.apk \
  npm run test:e2e 2>&1 | tee ~/lt-runs/current/plugin-suite.log
```

**[auto]** The field-force suite (12 scenarios; estimated 70 to 80 minutes):

```bash
cd examples/field-force/e2e
E2E_APK=../android/app/build/outputs/apk/debug/app-debug.apk \
  npm run test:e2e 2>&1 | tee ~/lt-runs/current/field-force-suite.log
```

Both durations are estimates from the scenario timeouts and the units' own estimates. No full run of either suite had
finished when this runbook was written (the first CI emulator run covered the API 35 subset only). The CI jobs allow 180 minutes (plugin suite) and 150 minutes (field-force
suite). Write the real durations into the report ([9](#9-report-template-for-a-full-run)).

`E2E_APK` is the APK that the update scenarios (P-L07) install again with `adb install -r`.

### 6.2 A subset by id

Test names are `<id> <title>`, so a regular expression on the id selects scenarios. Pass the pattern through
`NODE_OPTIONS`:

```bash
cd e2e/plugin
export E2E_APK=../../example/android/app/build/outputs/apk/debug/app-debug.apk
NODE_OPTIONS='--test-name-pattern=^P-L0[13]' npm run test:e2e                       # P-L01 and P-L03
NODE_OPTIONS='--test-name-pattern=^P-H05' npm run test:e2e                          # one scenario
node --test --test-concurrency=1 --test-name-pattern='^P-H05 ' '*.test.ts'          # the same, without npm
node --test --test-concurrency=1 heartbeat.test.ts                                   # one file
```

Do **not** use `npm run test:e2e -- --test-name-pattern=...`: npm appends the option after the file pattern
`"*.test.ts"`, and Node 22 then ignores it and runs every scenario (checked with Node 22.22).
When `node --test` is called directly, the option must come **before** the file pattern.

### 6.3 Long scenarios

Scenarios marked `long` (P-H04: about one hour of deep Doze) are skipped unless `E2E_INCLUDE_LONG=1`:

```bash
cd e2e/plugin
E2E_INCLUDE_LONG=1 E2E_APK=../../example/android/app/build/outputs/apk/debug/app-debug.apk npm run test:e2e
E2E_INCLUDE_LONG=1 NODE_OPTIONS='--test-name-pattern=^P-H04' npm run test:e2e
```

CI runs them only on a manual dispatch with `include-long` (see [6.8](#68-the-ci-emulator-jobs)).

### 6.4 The same run as CI: `.github/scripts/run-e2e.sh`

CI runs the suites through one script. The agent can run it locally too, from the repository root, against a booted
AVD. It waits for the boot to finish, runs `adb root`, sets a device baseline (keyguard disabled,
`svc power stayon true`, package verifier off), installs `E2E_APK` with `adb install -r -g`, records logcat for the
whole run into `$E2E_ARTIFACTS_DIR/_run/`, and then runs the suite. Relative paths in `E2E_SUITE_DIR`, `E2E_APK` and
`E2E_ARTIFACTS_DIR` are resolved against the current directory; `E2E_ARTIFACTS_DIR` defaults to
`<E2E_SUITE_DIR>/e2e-artifacts`. `npm ci` must already have run in `testing/e2e-kit` and in the suite directory.

```bash
E2E_SUITE_DIR=e2e/plugin E2E_APK=example/android/app/build/outputs/apk/debug/app-debug.apk \
  .github/scripts/run-e2e.sh                                                   # the whole plugin suite
E2E_TEST_NAME_PATTERN='P-L0[13]' E2E_SUITE_DIR=e2e/plugin \
  E2E_APK=example/android/app/build/outputs/apk/debug/app-debug.apk .github/scripts/run-e2e.sh   # a subset
E2E_ONLY_LONG=1 E2E_SUITE_DIR=e2e/plugin \
  E2E_APK=example/android/app/build/outputs/apk/debug/app-debug.apk .github/scripts/run-e2e.sh   # only long scenarios
E2E_SUITE_DIR=e2e/plugin .github/scripts/run-e2e.sh --list-long                # list the long scenarios
E2E_DRY_RUN=1 E2E_SUITE_DIR=examples/field-force/e2e .github/scripts/run-e2e.sh  # dry run, no device
```

- `E2E_TEST_NAME_PATTERN` is a JavaScript regular expression; the script passes it to Node through `NODE_OPTIONS`.
- `E2E_ONLY_LONG=1` runs only the scenarios whose requirements contain `long` (found from the dry-run listing) with
  `E2E_INCLUDE_LONG=1`, and replaces `E2E_TEST_NAME_PATTERN`. With no such scenario it exits 0 without a device step.
- `--list-long` prints those scenario ids, one per line, and needs no device.
- `E2E_DRY_RUN=1` skips every device step.
- The exit status is the suite's exit status, or 1 when a setup step failed.

The `node --test` commands in [6.1](#61-full-runs)–[6.3](#63-long-scenarios) do not set the device baseline; use the
script when a scenario fails only locally.

**Several AVDs at once.** Jobs on different AVDs can run at the same time. Each job needs its own emulator console
port, adb serial, back-office port and artifacts directory; nothing else in the kit is shared. An AVD cannot run twice at
once, so two jobs on the same image need two AVDs (for example a copy of `e2e-34` named `e2e-34b`, created as in
[3.4](#34-create-the-avds)). Stop only your own emulator (`adb -s <serial> emu kill`), never all of them.

```bash
emulator -avd e2e-34b -port 5556 -no-snapshot -wipe-data -no-boot-anim -no-audio -no-window \
  -gpu swiftshader_indirect -memory 4096 -cores 4 > ~/lt-runs/emulator-e2e-34b.log 2>&1 &
adb -s emulator-5556 wait-for-device
until [ "$(adb -s emulator-5556 shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
E2E_SERIAL=emulator-5556 E2E_BACKEND_PORT=8788 E2E_ARTIFACTS_DIR=~/lt-runs/field-force-api34 \
  E2E_SUITE_DIR=examples/field-force/e2e \
  E2E_APK=examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk .github/scripts/run-e2e.sh
```

The round-2 follow-up ran the whole matrix in three lanes on a 12-core host with 62 GB of memory (4 cores and 4 GB
per emulator, boots 60 s apart): API 34 plugin suite (ports 5554/8787); field-force suite on `e2e-34b`, then P-P08
on `e2e-34-nogms` (5556/8788); API 29 subset, then API 35 subset (5558/8789). Results and times:
[DECISIONS.md](DECISIONS.md), section R2F.

### 6.5 The image without Google Play services (P-P08)

P-P08 needs the `default` image; on a `google_apis` image it is skipped ("needs an image without Google Play
services"), and P-P07 is skipped on the `default` image.

**[auto]**

```bash
adb emu kill
emulator -avd e2e-34-nogms -no-snapshot -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect \
  -memory 4096 -cores 4 > ~/lt-runs/emulator-e2e-34-nogms.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb root && adb wait-for-device
adb install -r -g example/android/app/build/outputs/apk/debug/app-debug.apk
cd e2e/plugin && NODE_OPTIONS='--test-name-pattern=^P-P08' npm run test:e2e
```

### 6.6 Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `E2E_SERIAL` | the only device | adb serial, for example `emulator-5556`. |
| `E2E_APP_ID` | by id: `P-*` plugin example, `F-*` field-force | Overrides the app under test. |
| `E2E_APK` | none | APK for the (re)install scenarios. |
| `E2E_BACKEND_PORT` | `8787` | Port of the suites' back office on the host. |
| `E2E_INCLUDE_LONG` | off | `1` also runs `long` scenarios. |
| `E2E_DRY_RUN` | off | `1` lists the scenarios and runs nothing (no device needed). |
| `E2E_ARTIFACTS_DIR` | `./e2e-artifacts` (in the suite directory) | Where failure artifacts go. |
| `E2E_ADB` | `$ANDROID_HOME/platform-tools/adb` | The adb binary. |
| `E2E_BUGREPORT` | off | `1` also saves a full bugreport on failure (slow: about 1 minute and 20 MB or more). |

### 6.7 Dry run without a device

```bash
(cd e2e/plugin && npm run dry-run)                  # prints "DRY-RUN <id> | <title> | requires: … | timeout …s"
(cd examples/field-force/e2e && npm run dry-run)
(cd testing/e2e-kit && npm run catalogue)           # the whole catalogue, including M-01 … M-08
```

A dry run checks that every scenario id exists in the catalogue and prints each scenario's requirements. It needs no
emulator.

### 6.8 The CI emulator jobs

GitHub Actions runs the suites in `.github/workflows/e2e-android.yml` (workflow name "E2E (Android emulator)"). It
runs only when started by hand (manual dispatch): pushes, pull requests and a schedule start no run, to save Actions
minutes (a full run uses about 4–5 runner hours). The job "Build debug APKs" builds both debug APKs once (artifact `e2e-apks`); every
emulator job downloads them and calls the reusable workflow `.github/workflows/e2e-android-run.yml`, which calls
`.github/scripts/run-e2e.sh`. Every emulator run cold-boots the AVD (`-no-snapshot`).

| Job name | Label (artifact `e2e-artifacts-<label>`) | Image | What | Time limit |
|---|---|---|---|---|
| Plugin suite (API 34) | `plugin-api34` | API 34 `google_apis` x86_64 | Every plugin scenario that is not `long`. | 180 min |
| Plugin subset (API 29) | `plugin-api29` | API 29 `google_apis` | `P-(L01\|L03\|L08\|L12\|H01\|H03\|H05\|P03\|P04\|P06\|P10)\b` | 120 min |
| Plugin subset (API 35) | `plugin-api35` | API 35 `google_apis` | `P-(L01\|L03\|L06\|L08\|L11\|L12\|L13\|H01\|H03\|H05\|P01\|P05\|P06\|P10)\b` | 120 min |
| Plugin P-P08 (API 34, no Google Play services) | `plugin-api34-no-gms` | API 34 `default` | P-P08 only. | 45 min |
| Field-force suite (API 34) | `field-force-api34` | API 34 `google_apis` | Every field-force scenario that is not `long`. | 150 min |
| Long scenarios (long-plugin-api34), Long scenarios (long-field-force-api34) | `long-plugin-api34`, `long-field-force-api34` | API 34 `google_apis` | Only the `long` scenarios of each suite; on manual dispatch with `include-long`. A suite without `long` scenarios boots no emulator. | 300 min |

The artifacts of every emulator job are uploaded as `e2e-artifacts-<label>`, also when the job passed.

The manual dispatch has two inputs: `test-name-pattern` (a regular expression for the "Plugin suite (API 34)" and
"Field-force suite (API 34)" jobs; empty = the whole suite; the subset and P-P08 jobs keep their own patterns) and
`include-long` (also run the long jobs).

The build workflow `.github/workflows/ci.yml` (job `build`) also runs only when started by hand: TypeScript build and
tests, the kit's type check and unit tests, type checks and dry runs of both suites, the Android unit tests, both
example apps' debug builds, the field-force Node tests (`npm test`), the PremiseMonitor unit tests, the field-force
release build and the 16 KB check ([M-07](#m-07-play-build-gms-only-16-kb-page-size-alignment)). It uploads both debug
APKs as `debug-apks`.

**[auto]** Start a run by hand and fetch its artifacts with the GitHub CLI:

```bash
gh workflow run e2e-android.yml --ref <branch> -f test-name-pattern='P-L0[13]' -f include-long=false
gh run list --workflow e2e-android.yml --limit 3          # the run id is in the list
gh run watch <run id>
gh run download <run id> --dir ~/lt-runs/ci-<run id>      # every e2e-artifacts-<label>
```

A CI failure is triaged the same way as a local one ([7](#7-triage-of-a-failed-scenario)); the whole-run logcat is in
the `_run/` directory of the job's artifact.

### 6.9 Reading the output and the artifacts

Without a terminal (for example through `tee`), Node prints the TAP format:

- `ok 12 - P-L12 heartbeat alarm restores a killed process`: passed.
- `ok 3 - P-L03 … # SKIP needs adb root (…)`: skipped; the text after `# SKIP` is the reason.
- `not ok 5 - P-L05 …`, followed by an indented block with `error:` and `stack:`: failed.
- `# [+42.1s] …`: a diagnostic line the scenario wrote, with the time since the scenario started.
- `# artifacts: <dir>`: where the failure artifacts are.
- At the end: `# tests`, `# pass`, `# fail`, `# skipped`.

Add `--test-reporter=spec` to the `node --test` command for a human-readable format.

On failure, the kit saves these files in `e2e-artifacts/<id>/` (in the suite directory):

| File | Content |
|---|---|
| `reason.txt` | The time and the reason the artifacts were collected (the failure). |
| `crash.txt` | The logcat crash buffer. |
| `logcat.txt` | The last 20,000 lines of the main, system, crash and events buffers (the whole run is in `_run/logcat.txt` when the run used `.github/scripts/run-e2e.sh`). |
| `records.json` | What the back office received in this scenario. |
| `premise.json` | PremiseMonitor entries (field-force). |
| `backoffice.log` | One line per request to the back office. |
| `plugin-logs/` | The plugin's own log files, copied with `run-as`. They survive process restarts. |
| `dumpsys-activity-services.txt` | Running services (is `LocationTrackingService` in the foreground?). |
| `dumpsys-location.txt` | Location requests per app (GPS or passive?). |
| `dumpsys-deviceidle.txt` | Doze state and the battery-exemption list. |
| `dumpsys-alarm.txt` | The app's pending alarms (heartbeat alarms). |
| `dumpsys-jobscheduler.txt` | Jobs. |
| `screenshot.png` | The screen at the time of the failure. |
| `bugreport.zip` | Only with `E2E_BUGREPORT=1`. |

`e2e-artifacts/_run/backoffice.log` holds every back-office request of the whole run, each line prefixed with the
scenario id (the kit writes it once a scenario has started the suite's back office). With
`.github/scripts/run-e2e.sh`, `$E2E_ARTIFACTS_DIR/_run/` also holds `logcat.txt` (the whole run: the main, system,
crash and events buffers), `test-output.txt`, `device.txt`, `getprop.txt` and `crash-buffer.txt`.

---

## 7. Triage of a failed scenario

### 7.1 Order of files to open

1. **The test output.** Read the `error:` message of the `not ok` block, then the `# [+…s]` lines before it. They say
   which step failed and what was expected.
2. **`crash.txt`.** Search for the app's package. Any of these is an **app failure**: `FATAL EXCEPTION`,
   `ForegroundServiceDidNotStartInTimeException`, `ForegroundServiceStartNotAllowedException` (as a crash, not a
   handled log line), `ANR in com.brickssoft`.
3. **`records.json`.** Compare what arrived with what the scenario expected: missing `tracking_start` /
   `tracking_stop`, a wrong `reason`, wrong order, duplicates, a `heartbeat` without its `heartbeat` object.
4. **`logcat.txt`.** Search `LT-E2E` (debug command answers), ` LT.` (plugin lines), and `ActivityManager` lines with
   the package (`Start proc`, `Killing`, `Process … has died`, `Background start not allowed`).
5. **`plugin-logs/`.** The plugin's own log. Logcat is a ring buffer and can lose old lines; these files do not.
6. **`dumpsys-*.txt`.** Was the service in the foreground? Were heartbeat alarms scheduled? Did the app hold a GPS
   request while stationary? Was the phone in Doze?
7. **`screenshot.png`.** A system dialog on screen ("App isn't responding", a permission dialog) explains many UI
   waits.

### 7.2 App failure or environment problem?

| Sign | Verdict | What to do |
|---|---|---|
| A crash of the app package; a record with wrong content, order or reason; a debug command answering `ok:false` with an unexpected plugin error code; a command `TIMEOUT` while the emulator answers other adb commands quickly | **App failure** | Rerun once ([7.3](#73-rerun-rule-and-what-to-report)); report with the artifacts. |
| `error: device offline`, `error: no devices/emulators found`, `device 'emulator-5554' not found`, `error: closed`, `cannot connect to daemon` | Environment (adb) | `adb kill-server && adb start-server`; if the device does not return, restart the AVD with `-wipe-data`; rerun. If it starts right after a large `adb logcat` dump (the adb messages show `connection terminated: write failed`), report it as a kit problem: the first CI run lost the device this way before the kit filtered its crash scans. |
| The boot wait times out; `sys.boot_completed` never becomes `1`; the emulator process is gone (`pgrep -f qemu-system` prints nothing) | Environment (emulator) | Read `~/lt-runs/emulator-*.log`; cold-boot the AVD again; rerun. |
| Many scenarios time out in one run; `emulator -accel-check` fails; `adb shell uptime` shows a load average above 8 | Environment (slow host, no KVM) | The run is not valid. Fix KVM or use a larger machine; rerun everything. |
| `EADDRINUSE` for port 8787 | Environment | Stop the standalone back office, or set `E2E_BACKEND_PORT`. |
| Nothing reaches the back office; `http` events with `status: 0`; `curl -s localhost:8787/__health` fails on the host | Environment (network) | The host firewall must allow incoming TCP 8787 from the emulator. Check `http.url` in `state` (it must start with `http://10.0.2.2:`). |
| A skip: "needs adb root", "needs Google Play services", "needs an image without Google Play services", "needs API >= …" | Not a failure | The image does not fit that scenario. Use the right image ([6.5](#65-the-image-without-google-play-services-p-p08)). |
| No `LT-E2E` line at all for a debug command | Check the install first | `adb shell dumpsys package com.brickssoft.fieldforce.example \| grep -c E2eCommandReceiver` must be at least 1. 0 means a release build or a wrong APK is installed (environment). |

### 7.3 Rerun rule and what to report

1. Cold-boot the AVD with `-wipe-data`, install both APKs again, and run **only the failed scenario** once
   ([6.2](#62-a-subset-by-id)).
2. It fails again with the same assertion: **app failure**. It passes: **flaky**. A scenario that is flaky in 3 runs
   within 7 days counts as an app failure.
3. Report, per failed scenario: the id, the assertion message, the first abnormal line in `crash.txt` or `logcat.txt`
   (quoted), the artifacts directory of both runs, and the verdict. Use the table in
   [9](#9-report-template-for-a-full-run).

---

## 8. Manual procedures M-01 … M-08

### 8.0 Common setup for a real phone

Use this setup when a procedure says "common setup".

1. **[person]** Turn on USB debugging on the phone (Settings → About phone → tap "Build number" 7 times; then
   Settings → Developer options → USB debugging) and connect it by USB. Accept the "Allow USB debugging?" dialog.
2. **[auto]** `adb devices` lists the phone as `device`. With an emulator also running, use `adb -s <serial>` and
   `E2E_SERIAL`.
3. **[auto]** Record the phone: `adb shell getprop ro.product.manufacturer`, `ro.product.model`,
   `ro.build.version.release`, `ro.build.version.sdk`, `ro.build.fingerprint`, and
   `adb shell dumpsys battery | grep -E 'level|status|temperature'`.
4. **[auto]** Start the standalone back office ([4.1](#41-start-it-standalone)), a tunnel
   ([4.3](#43-reaching-it-from-a-real-phone)) and the record saver ([4.4](#44-saving-records-during-long-runs)).
   Write the tunnel URL into the run notes.
5. **[auto]** Install the debug APK the procedure names with `adb install -r -g <apk>`.
6. **[auto]** Keep automatic time on: `adb shell settings get global auto_time` must print `1`. The phone's time zone
   is the worker's time zone (`adb shell getprop persist.sys.timezone`); write it down, `lt-report.mjs --tz` needs it.

`pm grant` prints an error for a permission that the phone's Android version does not have (`ACTIVITY_RECOGNITION`
before Android 10, `POST_NOTIFICATIONS` before Android 13). That error is expected; the other grants still apply.

For the field-force app, the tunnel URL goes into `files/e2e/ff-overrides.json` as `backendUrl`
([5.3](#53-test-mode-files)). All other preset values stay the production values (heartbeat 180/300 s,
`syncInterval` 300 s, `stopTimeout` 5 min, `distanceFilter` 20 m, stop at 02:00).

---

### M-01 HMS on a Huawei phone

**Goal.** The plugin works on a Huawei phone without Google Play services, with the HMS backend: backend selection,
location records, activity recognition, stop and start detection, geofences and heartbeats.

**Device and setup.**

- A Huawei phone without Google Play services (for example P40, Mate 40 or newer; EMUI 12+ or HarmonyOS 2–4, which run
  Android apps), HMS Core updated from AppGallery. Not HarmonyOS NEXT (it does not run APKs).
- The plugin example app (`gms,hms` debug build), registered in AppGallery Connect.
- Outdoors, a place where the person can walk 400 m in a straight line and back.

**Steps.**

1. **[person]** In [AppGallery Connect](https://developer.huawei.com/consumer/en/service/josp/agc/index.html), add an
   Android app with package `com.brickssoft.locationtracking.example`, add the SHA-256 fingerprint of
   `testing/debug.keystore`
   (`AB:9F:2D:ED:BC:52:B8:78:AB:F0:2B:D0:E5:5A:A5:70:83:C5:40:5F:60:0A:D4:21:6D:81:F3:B7:5F:A3:FA:2C`), enable
   Location Kit, and give the agent the file `agconnect-services.json`.
2. **[auto]** Copy `agconnect-services.json` to `example/android/app/`, apply the AppGallery Connect Gradle plugin
   exactly as in the README ("Android setup", step 3), and build:
   `cd example/android && ./gradlew assembleDebug -PlocationTracking.providers=gms,hms`. These are local changes
   only: **do not commit them**. After the procedure, remove them with
   `git checkout -- example/android && rm example/android/app/agconnect-services.json`.
3. **[auto]** Common setup ([8.0](#80-common-setup-for-a-real-phone)) with this APK. Then check that the phone has no
   Google Play services: `adb shell pm list packages com.google.android.gms` prints nothing.
4. **[auto]** Prepare the app:

   ```bash
   APP=com.brickssoft.locationtracking.example
   adb shell pm clear $APP
   for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do
     adb shell pm grant $APP android.permission.$p
   done
   adb shell dumpsys deviceidle whitelist +$APP
   printf '%s' '{"e2e":true}' | adb shell "run-as $APP sh -c 'mkdir -p files/e2e && cat > files/e2e/example.json'"
   adb shell am start -W -n $APP/.MainActivity
   ```

   The screen shows the "E2E mode" banner.
5. **[auto]** Configure and check the backend (replace `<tunnel>`):

   ```bash
   source ~/lt-runs/e2e-cmd.sh
   e2e_cmd $APP m01-ready ready '{"reset":true,"config":{"locationProvider":"auto","logger":{"logLevel":"debug"},
     "heartbeat":{"enabled":true,"minInterval":60,"maxInterval":120},
     "geolocation":{"desiredAccuracy":"high","distanceFilter":10,"stopTimeout":1,"stationaryRadius":25},
     "http":{"url":"https://<tunnel>/locations","autoSync":true,"params":{"procedure":"M-01"}},
     "app":{"startOnBoot":true,"stopOnTerminate":false},"persistence":{"extras":{"scenario":"M-01"}}}}'
   e2e_cmd $APP m01-state-1 state
   ```

   Expected: `"ok":true` and `"backend":"hms"` in the `state` result.
6. **[auto]** With the app on screen: `e2e_cmd $APP m01-start start`. Wait 60 s.
7. **[auto]** `curl -s 'localhost:8787/__records?event=tracking_start,motionchange'`: one `tracking_start` (reason
   `start`) and one `motionchange` with `is_moving: false`. Write down its `coords.latitude` and `coords.longitude`.
8. **[auto]** Add a geofence of 100 m around that point (replace `<lat>` and `<lon>`):

   ```bash
   e2e_cmd $APP m01-geo addGeofence '{"geofence":{"identifier":"m01-start","latitude":<lat>,"longitude":<lon>,
     "radius":100,"notifyOnEntry":true,"notifyOnExit":true,"notifyOnDwell":true,"loiteringDelay":60000}}'
   ```

9. **[auto]** Wait 2 minutes. `curl -s 'localhost:8787/__records?event=geofence'` shows `ENTER` for `m01-start`
   (the phone is already inside, `initialTriggerEntry` is on by default).
10. **[person]** Put the phone in a pocket (screen off). Stand still for 3 minutes, then walk 400 m in a straight line
    at a normal pace, stand still for 3 minutes, walk back to the start, and stand still for 3 minutes. Report the
    local time of each start and stop of walking.
11. **[auto]** Save the records and evaluate:
    `curl -s localhost:8787/__records > ~/lt-runs/current/m01-records.json` and
    `node ~/lt-runs/lt-report.mjs ~/lt-runs/current/m01-records.json --max-interval 120`.
12. **[auto]** Search the plugin log for HMS errors:
    `adb shell "run-as $APP sh -c 'cat files/location-tracking-logs/*.log'" | grep -E '907135|6003| ERROR '`. (The
    whole device command is in double quotes, so the app's own shell expands `*.log`.) Also save the complete log:
    `adb shell "run-as $APP sh -c 'cat files/location-tracking-logs/*.log'" > ~/lt-runs/current/m01-plugin.log`; it
    must not be empty.
13. **[auto]** `e2e_cmd $APP m01-stop stop`. Remove the geofence: `e2e_cmd $APP m01-rm removeGeofence '{"identifier":"m01-start"}'`.

**Expected results.**

| Check | Expected |
|---|---|
| `backend` of every record | `hms` (100 %) |
| First `motionchange` with `is_moving: true` | At most 3 minutes after the person started walking. |
| `location` records during each 400 m walk | At least 20 (with `distanceFilter` 10 m, about 40 are possible). |
| `activity.type` of the `location` records while walking | `walking` or `on_foot` in at least 50 % of them. |
| `motionchange` with `is_moving: false` | One per stop, 1 to 3 minutes after the stop (`stopTimeout` 1 min). |
| Geofence `m01-start` | `ENTER` at step 9, `EXIT` during the walk out, `ENTER` during the walk back. |
| Heartbeats while standing still | At most 180 s apart (`maxInterval` 120 s + 60 s). Their `timestamp` is older than their `recorded_at`. |
| Plugin log | No line with `907135` or `6003`. |

**Pass / fail.** Pass when every row of the table holds. Fail otherwise; an HMS error code in the log usually means
the AppGallery Connect setup (step 1–2) is wrong, so check it and repeat once before reporting a plugin failure.

**Result template.**

| Field | Value |
|---|---|
| Date, agent | |
| Phone (manufacturer, model, Android/EMUI version, HMS Core version) | |
| APK (commit, providers) | |
| `state.backend` | |
| Records with `backend: hms` / all records | |
| Minutes from walking start to `motionchange` true (walk 1 / walk 2) | |
| `location` records per walk (walk 1 / walk 2) | |
| Share of `walking`/`on_foot` activity while walking | |
| Minutes from stop to `motionchange` false (stop 1 / 2 / 3) | |
| Geofence transitions received (in order) | |
| Maximum heartbeat gap while still (s) | |
| HMS errors in the log (count, first line) | |
| Verdict (PASS / FAIL), notes | |

---

### M-02 Phone makers' task killers

**Goal.** Record what Xiaomi, Huawei and Samsung power managers do to a tracking app, with and without the
allowlist, and check that the audit trail explains every gap: the server sees either continuous heartbeats, a gap that
ends with `tracking_start` (reason `restore`), or a `tracking_stop` that says why tracking ended. The plugin must never
crash.

**Device and setup.**

- Three phones, Android 12 or newer: one Xiaomi / Redmi / POCO (MIUI or HyperOS), one Huawei or Honor (EMUI or
  MagicOS), one Samsung (One UI). The Huawei phone may lack Google Play services; the field-force app then uses the
  `android` backend, which does not change the task killer's behavior.
- The field-force debug APK, the production preset, a tunnel.
- Two runs per phone, each 3 hours: **Run A** without any allowlist (factory settings), **Run B** with the battery
  exemption and the phone maker's allowlist.

**Steps (per phone, per run).**

1. **[auto]** Common setup ([8.0](#80-common-setup-for-a-real-phone)) with
   `examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk`; local time zone.
2. **[auto]** Clear the app and write the overrides:

   ```bash
   APP=com.brickssoft.fieldforce.example
   adb shell pm clear $APP
   for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do
     adb shell pm grant $APP android.permission.$p
   done
   printf '%s' '{"backendUrl":"https://<tunnel>"}' | \
     adb shell "run-as $APP sh -c 'mkdir -p files/e2e && cat > files/e2e/ff-overrides.json'"
   ```

3. Allowlist:
   - Run A: **[auto]** `adb shell dumpsys deviceidle whitelist -$APP`. Leave the phone maker's settings at their
     defaults.
   - Run B: **[auto]** `adb shell dumpsys deviceidle whitelist +$APP`. **[agent]** or **[person]** set the phone
     maker's setting from the table in [heartbeat.md](heartbeat.md#phone-makers-power-managers) (Huawei: App launch →
     Manage manually with all switches on; Xiaomi: Autostart on and Battery saver "No restrictions"; Samsung: "Never
     sleeping apps"). Take a screenshot of the final setting.
4. **[agent]** Launch the app: `adb shell am start -W -n $APP/.MainActivity`. Within 60 s the back office has a
   `tracking_start` (reason `start`).
5. **[person]** Open Recents and swipe the app away. Unplug the USB cable. Put the phone on a table, screen off. Do not
   touch it for 3 hours. Report the local time of the swipe.
6. **[person]** After 3 hours, connect the USB cable again. Do not open the app yet.
7. **[auto]** Save and evaluate the records of the run:
   `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --from <swipe time, ISO UTC> --max-interval 300`.
   Save the output. Save `adb logcat -d -b crash > m02-crash.txt` and
   `adb shell dumpsys activity services $APP > m02-services.txt`.
8. **[agent]** Now open the app. Wait 60 s. Record whether a new `tracking_start` arrives, and its reason.
9. **[auto]** Stop: `e2e_cmd $APP m02-stop stop` (with the app on screen). `curl -s -X POST localhost:8787/__reset`
   before the next run.

**Expected results.**

| Check | Expected |
|---|---|
| App crashes (`m02-crash.txt`) | None for `com.brickssoft.fieldforce.example`. |
| Gaps (from `lt-report.mjs`) | Every entry of `unexplainedGaps` is followed by a `tracking_start` with reason `restore`, or preceded by a `tracking_stop` (any reason, for example `service_start_failed`), or ends only when the app was opened at step 8 (a documented "none until the app is opened again" case in [heartbeat.md](heartbeat.md#android-reliability)). |
| Run B | Maximum gap ≤ 420 s (300 s `maxInterval` + 120 s) for the whole 3 hours. |
| Run A | Informational: record the first time a gap longer than 420 s started and how it ended. |

**Pass / fail.**

- **Plugin PASS** when there is no crash and every gap is explained as in the table, in both runs.
- **Run B target**: maximum gap ≤ 420 s. When a phone misses it although the allowlist is set, that is a phone
  maker's limitation, not a plugin failure. Report it with the evidence (the gap, the screenshot of the setting, the
  phone's build fingerprint) so the Bricks app can add instructions for that model.
- **Plugin FAIL** when the app crashed, or tracking looked enabled (`state.enabled: true`, notification shown) while
  no record arrived for more than 15 minutes and no `tracking_stop` explains it.

**Result template (one row per phone and run).**

| Phone (model, OS version) | Run | Allowlist settings | Swipe time (local) | Max gap (s) | Unexplained gaps (count) | First gap > 420 s: start, length, how it ended | `tracking_stop` reasons seen | Resumed at step 8 (reason) | Crashes | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| | A | none | | | | | | | | |
| | B | | | | | | | | | |

---

### M-03 Real drive: server distance, odometer and map

**Goal.** On a real drive, the plugin's odometer, the line through the uploaded records, and the travel time match
reality closely enough to compute a worker's travel distance and time. Stops are detected, departures are detected
without GPS running while parked, and the live location is at most about 5 minutes old.

**Device and setup.**

- A phone with Google Play services, Android 12 or newer, mobile data on, in a car mount. It may be charging.
- The field-force debug APK, production preset (`distanceFilter` 20 m, `stopTimeout` 5 min, `syncInterval` 300 s,
  battery exemption on), a tunnel.
- A planned route of 10 to 20 km with at least 3 stops of 6 minutes or more (engine running or off). Traffic-light
  stops shorter than 5 minutes are fine; they must not end the moving state.

**Steps.**

1. **[auto]** Common setup and app preparation as in M-02 steps 1–2, then
   `adb shell dumpsys deviceidle whitelist +com.brickssoft.fieldforce.example`.
2. **[agent]** Launch the app at the start point. Check the `tracking_start` in the back office.
3. **[person]** Before driving, write down: the car's trip odometer reading (0.1 km resolution), the local time, and
   the planned route's distance from the map app. Then drive the route. At every stop, write down the arrival and
   departure times. At the end, write down the trip odometer reading and the time, and keep the phone still for
   6 minutes.
4. **[person]** Give the agent the notes, and the map app's record of the drive if one exists (for example Google Maps
   Timeline distance).
5. **[auto]** Evaluate the drive window:
   `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --from <start ISO UTC> --to <end ISO UTC> --tz <zone>`.
   Save the output.
6. **[auto]** For each stop, compute the distance from the `motionchange` record with `is_moving: false` to the next
   `motionchange` with `is_moving: true` (departure detection distance), with the `haversine` function of
   `lt-report.mjs`.
7. **[auto]** `curl -s 'localhost:8787/__requests?path=/locations' > m03-requests.json`: the times of the upload
   requests while driving. One due upload drains the whole queue in batches of 100 records, so it can be several
   requests a few seconds apart. Group requests that are less than 10 s apart into one **upload burst**, and
   compute the time between the starts of consecutive bursts.

**Expected results and pass criteria.**

| Check | Pass | Why this limit |
|---|---|---|
| Plugin odometer (`odometerDeltaM`) vs car trip odometer | Difference ≤ 5 % of the trip odometer | Car odometers are typically within about 2–4 % of the true distance; GPS distance over 10–20 km is typically within 1–3 %. 5 % allows both. |
| Line through the records (`polylineM`) vs plugin odometer | Between 90 % and 102 % of the odometer | The line only connects recorded points (every 20 m or more, farther at speed), so it cuts corners and is slightly shorter. More than 2 % longer would mean recorded GPS jumps. |
| Stops of 6 minutes or more | Each has exactly one `motionchange` `is_moving: false`, 5 to 7 minutes after the arrival time | `stopTimeout` is 5 minutes. |
| Stops shorter than 5 minutes | No `motionchange` `is_moving: false` | Stop detection must not end a moving period at traffic lights. |
| Departure detection distance (step 6) | ≤ 500 m for every departure | While parked, GPS is off; the departure is detected by the stationary geofence (radius at least 150 m), a passive fix, or activity recognition. |
| Moving time (`movingMinutes`) vs the person's driving time | Difference ≤ 5 minutes or ≤ 10 %, whichever is larger | Each departure may be detected up to about a minute late. |
| Live location: `normalRecordLateS.p95` while driving | ≤ 330 s | `syncInterval` is 300 s; 30 s for the upload itself. |
| Time between upload bursts while driving (step 7) | Between about 300 and 330 s | Same reason. Heartbeats do not happen while records are created. |

**Pass / fail.** Pass when every row passes. When the odometer differs by 5 to 10 %, report "investigate" with the
record timeline around the largest distance jump; above 10 % is a fail.

**Result template.**

| Field | Value |
|---|---|
| Date, driver, agent | |
| Phone (model, Android version), backend | |
| Planned route distance (map app), km | |
| Car trip odometer, km | |
| Plugin odometer delta, km, and % difference | |
| Line through records, km, and % of odometer | |
| Stops ≥ 6 min: arrival → `motionchange` false (min), per stop | |
| Short stops with a false stop detection (count) | |
| Departure detection distance per stop (m) | |
| Person's driving time vs `movingMinutes` | |
| `normalRecordLateS` median / p95 / max | |
| Time between upload bursts while driving: median / max (s) | |
| Verdict, notes | |

---

### M-04 12-hour battery measurement

**Goal.** Measure how much battery the field-force setup costs over a 12-hour shift, and check that GPS is really off
while the phone is stationary.

**Device and setup.**

- One phone with Google Play services, Android 13 or newer, battery health at least 80 % (Settings → Battery →
  Battery health, where the phone shows it). The same phone for all three runs.
- The field-force debug APK with the production preset and the battery exemption on (the recommended production
  setup; it is the worst case for battery, because heartbeats stay every 3 minutes in Doze).
- Wi-Fi off, mobile data on, the same place for every run, a SIM with signal.
- Three runs of 12 hours, one per day:
  - **Run 0 (baseline):** tracking off, phone still.
  - **Run S (stationary):** tracking on, phone still.
  - **Run W (workday):** tracking on, the phone is carried: 2 to 3 hours of driving or walking in total, the rest
    stationary; the person uses the screen for about 1 hour in total.

**Steps (per run).**

1. **[person]** Charge the phone to 100 % and keep it connected by USB.
2. **[auto]** Common setup; clear the app; grant all permissions (as in M-02 step 2); battery exemption on
   (`dumpsys deviceidle whitelist +com.brickssoft.fieldforce.example`). For **Run 0**, write
   `{"backendUrl":"https://<tunnel>","autoStart":false}` as the overrides; for Runs S and W,
   `{"backendUrl":"https://<tunnel>"}`.
3. **[auto]** Find the app's user id: `adb shell pm list packages -U com.brickssoft.fieldforce.example` prints
   `uid:10234` (for example). The batterystats name is `u0a` + (uid − 10000): here `u0a234`.
4. **[agent]** Launch the app. For Runs S and W, check `tracking_start` in the back office.
5. **[auto]** Reset the statistics and record the start:
   `adb shell dumpsys batterystats --reset`, `adb shell dumpsys battery | grep level`, `date -u`.
6. **[person]** Unplug the USB cable right away. Run 0 and S: leave the phone on the table, screen off, for 12 hours.
   Run W: carry the phone through the workday; report the start and end times of each drive or walk.
7. **[person]** After 12 hours, connect the USB cable.
8. **[auto]** Right away:

   ```bash
   adb shell dumpsys battery | grep level
   adb shell dumpsys batterystats --charged com.brickssoft.fieldforce.example > m04-<run>-app.txt
   adb shell dumpsys batterystats --charged > m04-<run>-all.txt
   adb shell dumpsys location > m04-<run>-location.txt
   adb bugreport m04-<run>-bugreport.zip        # optional: for Battery Historian
   ```

9. **[auto]** From the files, read:
   - the battery level at the start and at the end;
   - the battery capacity (`Capacity:` in the "Estimated power use (mAh)" section of `m04-<run>-all.txt`);
   - the app's estimated use: the number after `UID u0a234:` in that section (mAh);
   - the app's GPS time: the `Sensor GPS:` line in the app's section of `m04-<run>-app.txt`, and the app's `gps`
     entries under "Historical Aggregate Location Provider Data" in `m04-<run>-location.txt` (use the larger);
   - the app's wakeup alarms (`Wakeup alarm` lines) and wake lock time (`Wake lock` lines);
   - `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --quiet` for Runs S and W.

**Calculations.**

- Drop per hour = (start level − end level) / 12 h, in percentage points per hour.
- Tracking cost while stationary = drop per hour (Run S) − drop per hour (Run 0).
- App share in Run W = app's estimated mAh / battery capacity × 100 %.

**Pass criteria and why.**

| Check | Pass |
|---|---|
| GPS time of the app in Run S | ≤ 2 minutes in 12 hours (the first fix after `start()` and nothing else). |
| Tracking cost while stationary (Run S − Run 0) | ≤ 0.5 percentage points per hour (≤ 6 points in 12 hours). |
| App share in Run W | ≤ 10 % of the battery capacity. |
| Heartbeats in Run S | Present all night, maximum gap ≤ 420 s (the exemption is on). |

Why these limits: a typical phone loses 0.5–1 % per hour in standby, and about 10 % per hour with the screen on. With
these limits, a 12-hour shift with 3 hours of driving and 1 hour of screen use costs at most about 10 percentage points
more than the same day without tracking, so a phone that starts the shift at 100 % still has about 60 % left without
charging (12 × 1 % standby + 10 % screen + at most 10 % tracking + margin). While stationary, the only costs are the
heartbeat wake-ups (about 20 per hour with 180 s heartbeats) and their uploads; 0.5 points per hour is about 20–25 mA
on a 4,500 mAh battery, which leaves room for the mobile radio's wake-up after each upload. The GPS limit checks the
owner's decision directly: GPS off while stationary.

**Result template.**

| Field | Run 0 | Run S | Run W |
|---|---|---|---|
| Date, start–end (local) | | | |
| Phone, Android version, capacity (mAh), battery health | | | |
| Level start → end (%) | | | |
| Drop per hour (points) | | | |
| App estimated use (mAh) and share of capacity | | | |
| App GPS time | | | |
| App wakeup alarms (count), wake lock time | | | |
| Heartbeats (count), max gap (s) | – | | |
| Hours moving (Run W) | – | – | |
| Tracking cost while stationary (S − 0), points per hour | – | | – |
| Verdict | | | |

---

### M-05 Real overnight 02:00 stop

**Goal.** On a real phone that stays on all night, the field-force app's tracking stops by itself at about 02:00
local time (`tracking_stop` with reason `stop_after_elapsed`), and the next morning the app starts tracking again
without any crash (no `ForegroundServiceDidNotStartInTimeException`).

**Device and setup.**

- A phone with Google Play services, Android 12 or newer, in the worker's time zone, automatic time on.
- The field-force debug APK, production preset (stop at `02:00`), a tunnel.
- **Night A:** battery exemption on (recommended production setup). **Night B (optional):** exemption off.

**Steps.**

1. **[auto]** Common setup; clear the app; grant all permissions; write `{"backendUrl":"https://<tunnel>"}` as the
   overrides (as in M-02 step 2). Night A: `dumpsys deviceidle whitelist +com.brickssoft.fieldforce.example`; Night
   B: `whitelist -…`.
2. **[agent]** Between 20:00 and 23:30 local time, launch the app. Write down the launch time.
3. **[auto]** Check the stop time the app computed:

   ```bash
   source ~/lt-runs/e2e-cmd.sh
   e2e_cmd com.brickssoft.fieldforce.example m05-state-1 state
   ```

   `result.config.geolocation.stopAfterElapsedMinutes` must equal the minutes from the launch to the next 02:00,
   rounded up (for a launch at 21:37:20: 02:00 − 21:37:20 = 4 h 22 min 40 s → 263). The back office has a
   `tracking_start` with reason `start`.
4. **[person]** Unplug the phone (battery at least 60 %), leave it still with the screen off until 07:00.
5. **[person]** After 07:00, connect the USB cable. Do not open the app yet, and do not unlock the phone: when the
   app is in front, unlocking brings it to the foreground, and the page starts tracking again
   ([5.3](#53-test-mode-files)).
6. **[auto]** Evaluate:
   `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --tz <zone>` and save the output. Save
   `adb logcat -d -b crash > m05-crash.txt` and
   `adb shell dumpsys activity services com.brickssoft.fieldforce.example > m05-services.txt`.
7. **[auto]** `e2e_cmd com.brickssoft.fieldforce.example m05-state-2 state`: `enabled` must be `false`.
8. **Morning start, case 1 (the app was never closed).** **[agent]** Unlock the phone first
   (`adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard`; with a PIN, **[person]** unlocks it):
   behind the lock screen the activity does not resume, and the page does not run its startup. Then bring the app to
   the front with `adb shell am start -W -n com.brickssoft.fieldforce.example/.MainActivity`. Wait 60 s. Check for a new
   `tracking_start`, and run `node ~/lt-runs/ff-app.mjs <repository root>`
   ([5.4](#54-reading-the-field-force-pages-startup-result)): write down `lastStartupReason` and `lastResume`. The page
   runs its startup again when it comes to the foreground with tracking off: `lastStartupReason` is `'resume'` when the
   activity survived the night, and `'load'` when Android had recreated it.
9. **Morning start, case 2 (cold start).** **[auto]** Stop tracking first, so this case starts from tracking off
   again: `e2e_cmd com.brickssoft.fieldforce.example m05-stop stop`. Then
   `adb shell am force-stop com.brickssoft.fieldforce.example`, and **[agent]** launch the app. Wait 60 s.
10. **[auto]** `e2e_cmd com.brickssoft.fieldforce.example m05-state-3 state` and
    `adb logcat -d -b crash > m05-crash-morning.txt`.

**Expected results.**

| Check | Expected |
|---|---|
| `stopAfterElapsedMinutes` at step 3 | Minutes to the next 02:00, rounded up. |
| `tracking_stop` during the night | Exactly one, reason `stop_after_elapsed`, `recorded_at` between 02:00:00 and **02:06:00** local (Night A) or **02:12:00** (Night B). (Step 9 adds a `tracking_stop` with reason `stop` in the morning.) |
| Records after the stop | None until the morning start. |
| State at step 7 | `enabled: false`; `m05-services.txt` has no running `LocationTrackingService`. |
| Crash buffer (both files) | No crash of `com.brickssoft.fieldforce.example`; no `ForegroundServiceDidNotStartInTimeException`. |
| Case 1 (app never closed) | A `tracking_start` with reason `start` within 60 s, a new `stopAfterElapsedMinutes` that points to the next 02:00, and `FF_APP.lastStartupReason` `'resume'` with `startupCount` 2 or more (or `lastStartupReason` `'load'` when Android had recreated the activity). `lastResume.outcome` is usually `'busy'`: the document `resume` event and `visibilitychange` both fire, the first starts the run, and the second reports `'busy'`. |
| Case 2 (cold start) | A `tracking_start` with reason `start` within 60 s, and a new `stopAfterElapsedMinutes` that points to the next 02:00. |

Why 02:06 and 02:12: the plugin's stop timer counts only the time the CPU is awake, so on a sleeping phone it fires
late. The plugin therefore also checks the stop time whenever a fix, an activity update, a stationary-region exit or
a heartbeat arrives (`DefaultTrackingEngine` runs its due timers on each of them). While the phone lies still, GPS is
off and the heartbeat is the check that comes: the stop happens at the first heartbeat after 02:00. With the
exemption, heartbeats come about every 180 s and at most `maxInterval` (300 s) apart, so the stop comes by about
02:05; the limit adds one minute. Without the exemption, in deep Doze, heartbeats are about 9–11 minutes apart, so the
stop comes by about 02:11; the limit adds one minute. The check runs in a separate step right after the heartbeat is
queued, and the heartbeat's wake lock may already be released then. A stop that comes exactly one heartbeat interval
later than expected points to the phone falling asleep in that moment; report it with the plugin log
(`files/location-tracking-logs`) around the stop.

**Pass / fail.** Pass when every row holds. A `tracking_stop` later than the limit, or none at all, is a fail: the
stop time was not checked while the phone slept.

**Result template.**

| Field | Night A | Night B |
|---|---|---|
| Date, phone, Android version, time zone | | |
| Battery exemption | on | off |
| Launch time (local) and computed `stopAfterElapsedMinutes` | | |
| Expected minutes (by hand) | | |
| `tracking_stop` reason and `recorded_at` (local) | | |
| Minutes after 02:00 | | |
| Records after the stop (count) | | |
| Case 1: `tracking_start` reason, `lastStartupReason`, `lastResume.outcome` | | |
| Case 2: `tracking_start` reason, new `stopAfterElapsedMinutes` | | |
| Crashes | | |
| Verdict | | |

---

### M-06 Android 14+ real boot with only while-in-use location

**Goal.** After a real reboot of an Android 14+ phone where the app has only "Allow only while using the app"
location, Android does not let the plugin restart its location foreground service. The plugin must record this as
`tracking_stop` with reason `service_start_failed`, must not crash, and must start normally the next time the app is
opened. With "Allow all the time", the same reboot resumes tracking.

**Device and setup.**

- A phone with Android 14 or newer (API 34+), Google Play services, no screen lock PIN (or a person to unlock it).
- The field-force debug APK (`app.startOnBoot: true` in its preset), a tunnel (`adb reverse` does not survive a reboot).

**Steps.**

1. **[auto]** Common setup; clear the app; write `{"backendUrl":"https://<tunnel>"}` as the overrides.
2. **[auto]** Grant only while-in-use location:

   ```bash
   APP=com.brickssoft.fieldforce.example
   for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do
     adb shell pm grant $APP android.permission.$p
   done
   adb shell pm revoke $APP android.permission.ACCESS_BACKGROUND_LOCATION
   adb shell dumpsys package $APP | grep ACCESS_BACKGROUND_LOCATION     # must show granted=false
   adb shell dumpsys deviceidle whitelist +$APP
   ```

3. **[agent]** Launch the app. Its startup asks for the missing background location: the plugin's dialog "Allow
   background location" appears. Tap **Not now** (find the button with `uiautomator dump`, see
   [5.5](#55-ui-actions-the-agent-performs-itself)); do not choose "Allow all the time". The startup then continues.
   Within 60 s the back office has `tracking_start` (reason `start`). Wait 5 minutes (at least one heartbeat).
4. **[auto]** Reboot and wait: `adb reboot`, `adb wait-for-device`, then wait for `sys.boot_completed` = `1` as in
   [3.5](#35-start-an-avd-cold-boot-and-get-root). Write down the time.
5. **[agent]** Unlock the phone (`adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard`); with a
   PIN, **[person]** unlocks it. Android sends `BOOT_COMPLETED` to apps only after the first unlock. Write down the
   time.
6. **[auto]** Wait 5 minutes, then:
   `curl -s 'localhost:8787/__records?event=tracking_start,tracking_stop,heartbeat' > m06-run1.json`,
   `adb logcat -d -b crash > m06-crash-1.txt`, `adb shell dumpsys activity services $APP > m06-services-1.txt`.
7. **[agent]** Open the app and answer the background-location dialog with **Not now** again. Within 60 s:
   `tracking_start` with reason `start`.
8. **Control run.** **[auto]** `adb shell pm grant $APP android.permission.ACCESS_BACKGROUND_LOCATION`, then repeat
   steps 4–6 (files `m06-run2.json`, `m06-crash-2.txt`, `m06-services-2.txt`), and wait 10 minutes instead of 5.

**Expected results.**

| Check | Run 1 (while in use only) | Control run (all the time) |
|---|---|---|
| Records after the unlock | `tracking_stop` with reason `service_start_failed` within 2 minutes of the unlock, optionally after a `tracking_start` with reason `boot` | `tracking_start` with reason `boot` within 2 minutes of the unlock |
| Heartbeats after that | None | About every 180 s, maximum gap ≤ 420 s |
| `boot_count` | Higher than before the reboot | Higher than before the reboot |
| Service | Not running | `LocationTrackingService` running in the foreground |
| Crash buffer | No crash of the app | No crash of the app |
| Opening the app (step 7) | `tracking_start` with reason `start` | – |

In Run 1 the plugin's own Android 14 check normally refuses the start before it asks Android (no background location,
no visible activity after a boot), so the `tracking_stop` usually comes without a `tracking_start` before it. When the
check lets the start through and Android then refuses it, the `tracking_start` with reason `boot` comes first.

**Pass / fail.** Pass when both columns hold. A crash, a missing `tracking_stop` in Run 1 (tracking looks enabled but
nothing arrives), or heartbeats in Run 1 after the stop, is a fail.

**Result template.**

| Field | Run 1 | Control run |
|---|---|---|
| Date, phone, Android version | | |
| Background location granted | no | yes |
| Reboot time, unlock time (local) | | |
| Records after the unlock (event, reason, `recorded_at`) | | |
| Seconds from unlock to the first record | | |
| Heartbeats after the unlock (count, max gap) | | |
| Service running after boot | | |
| Crashes | | |
| Step 7 result | | – |
| Verdict | | |

---

### M-07 Play build (GMS only): 16 KB page-size alignment

**Goal.** The field-force release build, as it would go to Google Play (GMS only), supports devices with a 16 KB memory
page size: Google Play requires this for apps that target Android 15 or newer. Uncompressed native libraries in the APK
must start at 16 KB boundaries, and every native library must have ELF `LOAD` segments aligned to at least 16 KB.

**Device and setup.** No phone. A host with the Android SDK (`build-tools;35.0.0` or newer), `python3` and `unzip`.

**Steps.**

1. **[auto]** Build the plugin and the release APK:

   ```bash
   npm ci && npm run build
   (cd examples/field-force && npm ci && npm run sync && cd android && ./gradlew assembleRelease)
   APK=examples/field-force/android/app/build/outputs/apk/release/app-release-unsigned.apk
   ls -l "$APK"
   ```

2. **[auto]** Run the same check as CI. It runs `zipalign -c -P 16 -v 4` and reads the ELF `PT_LOAD` alignment of
   every native library; 64-bit ABIs (`arm64-v8a`, `x86_64`) must be aligned to 16 KB or more:

   ```bash
   python3 .github/scripts/check-16kb.py --label "field-force release (gms)" "$APK"; echo "exit=$?"
   ```

   Usage: `check-16kb.py [--report-only] [--label NAME] [--zipalign PATH] APK [APK ...]`. The script finds
   `zipalign` in the newest `$ANDROID_HOME/build-tools/<version>/` with version 35 or newer, unless `--zipalign`
   names it. It prints one text table per APK: every `lib/<abi>/*.so` with its smallest `PT_LOAD` alignment and, for
   an uncompressed library, whether it starts on a 16 KB boundary. 32-bit libraries are listed as "32-bit, not
   required". Exit status: 0 = every APK passed, 1 = an APK failed a check, 2 = an APK could not be read or zipalign
   was not found. With `--report-only` it exits 0 (2 only after a usage error).

   Expected for the GMS-only field-force APK: 0 native libraries, and `exit=0`.
3. **[auto]** Cross-check by hand:

   ```bash
   "$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 "$APK" | tail -n 3; echo "exit=${PIPESTATUS[0]}"
   unzip -v "$APK" | grep '\.so$' || echo "no native libraries"
   ```

   Expected: the last zipalign line is `Verification succesful` (zipalign's own spelling) and `exit=0`. Every `.so`
   line, if there is one, shows `Stored` (not `Defl:N`).
4. **[auto]** Optional, the bundle Google Play receives: `./gradlew bundleRelease` (in
   `examples/field-force/android`). `check-16kb.py` reads APKs only; check
   `app/build/outputs/bundle/release/app-release.aab` with this script instead. Save it as `~/lt-runs/elf-align.py`
   and run `python3 ~/lt-runs/elf-align.py <aab>`:

   ```python
   # Prints the PT_LOAD alignment of every .so in an APK/AAB/AAR; exit code 1 if a 64-bit one is below 16 KB.
   # 16 KB pages exist only on 64-bit devices, so 32-bit libraries (armeabi-v7a, x86) are listed but not counted.
   import struct, sys, zipfile
   bad = 0
   with zipfile.ZipFile(sys.argv[1]) as z:
       for name in (n for n in z.namelist() if n.endswith('.so')):
           data = z.read(name)
           is64 = data[4] == 2
           phoff = struct.unpack_from('<Q' if is64 else '<I', data, 0x20 if is64 else 0x1C)[0]
           phentsize, phnum = struct.unpack_from('<HH', data, 0x36 if is64 else 0x2A)
           aligns = []
           for i in range(phnum):
               off = phoff + i * phentsize
               if struct.unpack_from('<I', data, off)[0] == 1:  # PT_LOAD
                   aligns.append(struct.unpack_from('<Q' if is64 else '<I', data, off + (0x30 if is64 else 0x1C))[0])
           ok = all(a >= 0x4000 for a in aligns)
           bad += is64 and not ok
           print(('OK  ' if ok else 'FAIL' if is64 else '32-bit, not checked'), name, [hex(a) for a in aligns])
   print('64-bit native libraries below 16 KB alignment:', bad)
   sys.exit(1 if bad else 0)
   ```

   The same check by hand, per extracted 64-bit library: `llvm-readelf -l -W lib.so | grep LOAD` (last column
   `0x4000` or larger) or `objdump -p lib.so | grep LOAD` (`align 2**14` or larger).
5. **[auto]** Informational, not part of the pass criteria: build the plugin example with GMS and HMS
   (`cd example/android && ./gradlew assembleRelease -PlocationTracking.providers=gms,hms`) and run
   `python3 .github/scripts/check-16kb.py --report-only example/android/app/build/outputs/apk/release/app-release-unsigned.apk`.
   Record which libraries fail. Expected: none. With `com.huawei.hms:location` 6.20.0.300 the APK has no native
   libraries. Known in round 2, with 6.12.0.300: `lib/arm64-v8a/libTransform.so` (from
   `com.huawei.hms.LocationLiteSdk:core` 2.12.0.300) and `lib/x86_64/libucs-credential.so` (from
   `com.huawei.hms:ucs-credential-developers` 1.0.4.312) had `p_align` 4096. See
   [DECISIONS.md](DECISIONS.md#bricksrep-adoption).
6. **[auto]** Optional runtime check: install `system-images;android-35;google_apis_ps16k;x86_64` if
   `sdkmanager --list` offers it, create an AVD from it, check `adb shell getconf PAGE_SIZE` prints `16384`, install
   the field-force debug APK, launch it, and confirm tracking starts (`tracking_start` in the back office) without a
   crash.

**Expected results and pass / fail.**

| Check | Pass |
|---|---|
| `check-16kb.py` on the field-force release APK (step 2) | Exit code 0 |
| `zipalign -c -P 16 -v 4` (step 3) | `Verification succesful`, exit code 0 |
| Native libraries in the APK (step 3) | All `Stored`. An APK without native libraries passes this row (write "0 libraries"). |
| AAB (if step 4 was done) | The script prints no `FAIL` and exits with 0 |

**Result template.**

| Field | Value |
|---|---|
| Date, commit | |
| AGP version, build-tools version | |
| APK path and size | |
| `check-16kb.py` exit code and summary | |
| zipalign result (last line, exit code) | |
| Native libraries (count; list with alignment) | |
| AAB check (optional) | |
| HMS build (informational): failing libraries | |
| 16 KB emulator run (optional) | |
| Verdict | |

---

### M-08 Real overnight Doze heartbeat spacing

**Goal.** On a real phone in real overnight Doze, heartbeats keep the documented spacing: about `minInterval`
(180 s) with the battery exemption, and about 9 minutes without it. Each heartbeat's `heartbeat` object predicts the
next one (`next_at`), so a server can tell an expected 9-minute gap from a failure.

**Device and setup.**

- A phone with Google Play services, Android 12 or newer (on Android 11 and older the strategy is always `exact`).
- The field-force debug APK, production preset (heartbeat 180/300 s), a tunnel.
- **Night A:** battery exemption off. **Night B:** exemption on. Two nights, or two identical phones in the same
  night.

**Steps.**

1. **[auto]** Common setup; clear the app; grant all permissions; write
   `{"backendUrl":"https://<tunnel>","stopAt":"07:00"}` as the overrides, so tracking runs the whole night instead
   of stopping at 02:00. Night A: `adb shell dumpsys deviceidle whitelist -com.brickssoft.fieldforce.example`;
   Night B: `whitelist +…`.
2. **[agent]** Between 20:00 and 23:00 local time, launch the app. Check `tracking_start` in the back office. Write
   down the launch time.
3. **[person]** Unplug the phone (battery at least 60 %). Put it on a table, screen off. Do not touch it until 07:00.
   (While a phone charges, it never enters Doze.)
4. **[person]** After 07:00, connect the USB cable.
5. **[auto]** Evaluate the window from launch + 60 minutes (Doze starts some time after the screen goes off) until
   06:55 local time:
   `node ~/lt-runs/lt-report.mjs http://127.0.0.1:8787/__records --from <launch + 60 min, ISO UTC> --to <06:55 local, as ISO UTC> --tz <zone> > m08-<night>.txt`.
6. **[auto]** From the heartbeat lines, count the heartbeats with `idle` (the phone was in deep Doze) and their
   strategies.

**Expected results and pass criteria.**

| Check | Night A (not exempt) | Night B (exempt) |
|---|---|---|
| Heartbeats marked `idle` | At least one hour of them (the phone entered deep Doze) | At least one hour of them |
| Strategy of heartbeats marked `idle` | `idle_paced` | `exact` |
| `battery_exempt` in the `heartbeat` object | `false` | `true` |
| Gaps between consecutive records during Doze | 95 % ≤ 660 s (11 min), maximum ≤ 780 s (13 min) | Maximum ≤ 420 s (300 + 120 s) |
| Heartbeats per hour during Doze | ≤ 8 | About 20 (one per 180 s) |
| `unexplainedGaps` | Empty | Empty |
| `heartbeatNextAtErrorS.p95` | ≤ 180 s | ≤ 120 s |

The 660 s and 780 s limits are the same rule the server uses ([heartbeat.md](heartbeat.md#heartbeat-metadata)):
Android allows a non-exempt app 7 allow-while-idle alarms per hour in deep Doze (one per 9 minutes on average), plus up
to 2 minutes of delivery delay. "≤ 8 per hour" allows one extra heartbeat from the in-process alarm when Android opens
a Doze maintenance window.

**Pass / fail.** Pass when both nights meet their column. If the phone never entered deep Doze (no `idle`
heartbeats), the night is not valid; repeat it (make sure the phone is unplugged and lies still).

**Result template.**

| Field | Night A | Night B |
|---|---|---|
| Date, phone, Android version | | |
| Battery exemption | off | on |
| Evaluated window (local) | | |
| Heartbeats (all / `idle`) | | |
| Strategies seen while `idle` | | |
| Gap median / p95 / max during Doze (s) | | |
| Shortest gap between two `idle_paced` heartbeats (s) | | – |
| Heartbeats per hour during Doze | | |
| `unexplainedGaps` (count) | | |
| `heartbeatNextAtErrorS` median / p95 / max | | |
| Verdict | | |

---

## 9. Report template for a full run

The agent fills in this report at the end of a run and attaches the per-procedure tables from section 8.

```markdown
# E2E run report: <date>

- Agent / person: <name>
- Commit: <git rev-parse HEAD>, branch: <branch>
- Host: <OS, CPU, RAM>, KVM: <yes/no>, Node: <node --version>, emulator: <emulator -version | head -n 1>
- Images: <e.g. android-34 google_apis x86_64, android-34 default x86_64>
- APKs: plugin example <path, providers>, field-force <path>

## Automated suites

| Suite | Image | Passed | Failed | Skipped | Duration | Log file |
|---|---|---|---|---|---|---|
| Plugin (e2e/plugin) | android-34 google_apis | | | | | |
| Plugin, long (E2E_INCLUDE_LONG=1) | android-34 google_apis | | | | | |
| Plugin, P-P08 | android-34 default | | | | | |
| Field-force (examples/field-force/e2e) | android-34 google_apis | | | | | |

### Failures

| Id | Assertion message | First abnormal log line | Artifacts (run 1 / rerun) | Verdict (app failure / flaky / environment) |
|---|---|---|---|---|

### Skips that were not expected

| Id | Skip reason | Why unexpected |
|---|---|---|

## Manual procedures

| Id | Date | Device | Verdict | Key numbers | Notes |
|---|---|---|---|---|---|
| M-01 | | | | backend, activity share, geofence transitions | |
| M-02 | | | | max gap per phone and run | |
| M-03 | | | | odometer vs trip meter %, polyline %, late p95 | |
| M-04 | | | | points/h S − 0, app share in W, GPS time | |
| M-05 | | | | tracking_stop time, crashes | |
| M-06 | | | | records after unlock, crashes | |
| M-07 | | | | zipalign result, failing libraries | |
| M-08 | | | | gap p95/max per night | |

## Open issues found in this run

| # | Summary | Evidence | Suggested owner |
|---|---|---|---|
```
