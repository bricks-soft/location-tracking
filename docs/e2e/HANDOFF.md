# Handoff: round 2, from the cloud session to a local session

This file hands pull request [bricks-soft/location-tracking#2](https://github.com/bricks-soft/location-tracking/pull/2)
(branch `claude/background-geolocation-gms-hms-9ijqdo`, draft, base `master`) from a cloud session without an
emulator to a local session that can run Android emulators (AVDs). **Delete this file in the last commit before the
pull request is marked ready for review.**

## 1. Why the handoff

The GitHub Actions emulator runs used up the account's Actions minutes. The owner decided (`docs/DECISIONS.md`
R2.1, R2-Q15):
- both workflows (`.github/workflows/ci.yml` and `.github/workflows/e2e-android.yml`) start only by hand
  (`workflow_dispatch`); a push or a pull request starts no run;
- the emulator runs continue on local AVDs, for a faster loop;
- the running CI runs were cancelled.

Do not start a workflow by hand unless the owner asks for it (a full emulator run uses about 4–5 runner hours).

## 2. Owner's preferences for answers

- Ask clarifying questions before giving answers.
- Use literal, concrete language: no idioms, no jargon, no terms the reader has to interpret; name the specific
  things instead of an abstract phrase.

## 3. Where the work stands

- The code of round 2 is complete. Every decision is in `docs/DECISIONS.md` (round-2 part R2.1–R2.5). The contract is
  `docs/e2e/architecture.md`; the local run instructions are `docs/e2e-runbook.md`.
- Last head on which **every** emulator job passed in CI: `2e03f11`.

| Job (CI names) | Result on `2e03f11` |
|---|---|
| Plugin suite (API 34) | 31 passed, 0 failed, 3 skipped (P-H04 long; P-P08 needs the no-GMS image; P-L13, see below) |
| Plugin subset (API 29) | 11 of 11 passed |
| Plugin subset (API 35) | 13 passed, P-L13 skipped |
| Plugin P-P08 (API 34, no Google Play services) | passed |
| Field-force suite (API 34) | 12 of 12 passed |
| CI workflow (unit tests, type checks, dry runs, APK builds, 16 KB check) | passed |

- Commits after `2e03f11` (each is explained in its commit message and in `docs/DECISIONS.md` R2.3):

| Commit | What | Emulator status |
|---|---|---|
| `68265df` | P-L13 uses the new debug command `startDuringMainThreadBlock`; the plugin logs `foreground service start sent (seq N)` (LT.ServiceController, debug); P-L02 uses that line. | API 35: 14 of 14 passed, P-L13 **passed** (service created about 3.9 s after the start was sent; `startForeground` 5–14 ms after `onCreate`). API 34: not run to the end (cancelled). |
| `44be346` | Plugin: the geofence manager registers every geofence again after 10, 30, 60, 120, 300 s when the backend answered `UNAVAILABLE`. | Not run on an emulator. Unit tests pass. |
| `51f7721` | Kit: on Android 10 and older with Play services, `setLocationEnabled(true)` waits for "GeofencerStateMachine: Network location enabled" and otherwise switches the network provider off and on (at most 3 times); P-P06 accepts up to four `providerchange` records after location is on. | Not run on an emulator. Fixes the API 29 failures of P-P06 and P-P10 on `68265df`. |
| `87f3bf6` | `run-e2e.sh`: `gms_pid` ends in `|| true` (a missing Play services process ended the script under `set -euo pipefail`). | Checked with a fake `adb` only. |
| the commit that adds this file | Both workflows manual only; documents updated. | – |

- Local checks that passed on the last code commit: plugin unit tests 1,492 passed; e2e kit 128 passed; both suites
  type-check and pass their dry runs; the example app's debug APK and its instrumented tests compile.

## 4. What the local session has to do

1. Set up the AVDs and build the APKs: `docs/e2e-runbook.md` §3 (host, SDK packages, KVM check, AVDs, cold boot with
   root, debug APKs, `npm ci` in the kit and the suites). The runbook creates `e2e-34` and `e2e-34-nogms`; also create
   the two images of the API-level subsets:

   ```bash
   sdkmanager "system-images;android-29;google_apis;x86_64" "system-images;android-35;google_apis;x86_64"
   echo no | avdmanager create avd --force -n e2e-29 -k "system-images;android-29;google_apis;x86_64" -d pixel_6
   echo no | avdmanager create avd --force -n e2e-35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
   ```

2. Run the same matrix as CI, one AVD at a time (cold boot with `-no-snapshot`, as in runbook §3.5), from the
   repository root with `.github/scripts/run-e2e.sh` (runbook §6.4). The script waits for Play services to settle,
   sets the device baseline, installs the APK and records the whole-run logcat in `<E2E_ARTIFACTS_DIR>/_run/`.

   ```bash
   EX=example/android/app/build/outputs/apk/debug/app-debug.apk
   FF=examples/field-force/android/app/build/outputs/apk/debug/app-debug.apk

   # AVD e2e-34 (google_apis): the whole plugin suite (CI: about 80-85 minutes)
   E2E_SUITE_DIR=e2e/plugin E2E_APK=$EX E2E_ARTIFACTS_DIR=$HOME/lt-runs/plugin-api34 .github/scripts/run-e2e.sh
   # AVD e2e-34 (google_apis), after a new cold boot: the field-force suite (CI: about 45-50 minutes)
   E2E_SUITE_DIR=examples/field-force/e2e E2E_APK=$FF E2E_ARTIFACTS_DIR=$HOME/lt-runs/field-force-api34 .github/scripts/run-e2e.sh
   # AVD e2e-29: the API 29 subset (CI: about 30 minutes)
   E2E_TEST_NAME_PATTERN='P-(L01|L03|L08|L12|H01|H03|H05|P03|P04|P06|P10)\b' \
     E2E_SUITE_DIR=e2e/plugin E2E_APK=$EX E2E_ARTIFACTS_DIR=$HOME/lt-runs/plugin-api29 .github/scripts/run-e2e.sh
   # AVD e2e-35: the API 35 subset (CI: about 35 minutes)
   E2E_TEST_NAME_PATTERN='P-(L01|L03|L06|L08|L11|L12|L13|H01|H03|H05|P01|P05|P06|P10)\b' \
     E2E_SUITE_DIR=e2e/plugin E2E_APK=$EX E2E_ARTIFACTS_DIR=$HOME/lt-runs/plugin-api35 .github/scripts/run-e2e.sh
   # AVD e2e-34-nogms (default image): P-P08 (CI: about 4 minutes)
   E2E_TEST_NAME_PATTERN='P-P08\b' \
     E2E_SUITE_DIR=e2e/plugin E2E_APK=$EX E2E_ARTIFACTS_DIR=$HOME/lt-runs/plugin-api34-nogms .github/scripts/run-e2e.sh
   ```

   The example APK must be built with both providers, as in CI: `-PlocationTracking.providers=gms,hms` (runbook §3.6).
   For a fast check of one change, run only the affected ids first with `E2E_TEST_NAME_PATTERN` (runbook §6.2).

3. What to look at first:
   - API 29: P-P06 and P-P10 must pass. The job output must not print the kit's warning `setLocationEnabled(true):
     Google Play services did not log "Network location enabled"`; if it does, read the whole-run logcat for
     `GeofencerStateMachine` lines (the analysis of the failure is in `docs/DECISIONS.md` R2.3).
   - API 34 and API 35: P-L13 must **pass**, not skip. Its three `attempt N:` log lines show how long the service
     creation waited (about 3.9 s) and when `startForeground` came.
   - Every job: the start-up line `Google Play services pid …, stable for … s, restart seen: …` (API 34 logged a
     restart in every CI run; API 29 and 35 never did).
   - The geofence scenarios (P-P06, P-P10, P-P11, F-07, F-10, F-11, F-12): the plugin log may now contain
     `geofence backend unavailable; registering the geofences again in … s (retry N)`; it must end with
     `geofences registered again (retry N)` when a retry was needed.
   - Triage of a failure: runbook §6.9 (output and artifacts) and §7 (order of files, app failure or environment
     problem, rerun rule). "Flaky" is not a cause: find the cause in the logcat and `dumpsys` files, as every entry of
     R2.3 does.

4. Before every push, run the checks of `ci.yml` locally (the workflow no longer runs on push):

   ```bash
   npm ci && npm run build && npm test                                          # repository root
   (cd testing/e2e-kit && npm ci && npm run typecheck && npm test)
   (cd e2e/plugin && npm ci && npm run typecheck && npm run dry-run)
   (cd examples/field-force/e2e && npm ci && npm run typecheck && npm run dry-run)
   (cd android && ./gradlew testDebugUnitTest)
   (cd examples/field-force && npm ci && npm test && npm run sync)
   (cd examples/field-force/android && ./gradlew :bricks-soft-capacitor-premise-monitor:testDebugUnitTest assembleDebug assembleRelease)
   (cd example && npm ci && npm run sync && cd android && ./gradlew assembleDebug -PlocationTracking.providers=gms,hms)
   python3 .github/scripts/check-16kb.py --report-only --label "example debug (gms,hms)" \
     example/android/app/build/outputs/apk/debug/app-debug.apk                  # reports only
   python3 .github/scripts/check-16kb.py --label "field-force release (gms)" \
     examples/field-force/android/app/build/outputs/apk/release/app-release-unsigned.apk   # must pass
   ```

   When `.github/scripts/*.sh` or a workflow changes, also run `shellcheck` and `actionlint`.

## 5. Finishing the pull request

When every job of step 4.2 passes on the same head:
1. Add a row to `docs/DECISIONS.md` R2.3 with the final emulator results, in the form of the rows "First full
   emulator results recorded (head …)": the head, where it ran (local AVDs, host OS, emulator version, image
   versions), and per job the passed, failed and skipped counts with the reason of each skip.
2. Replace the pull request description with the draft in §7 of this file: put the results of step 1 where it says
   `EMULATOR_RESULTS`, and recompute "What is in the diff" with `git diff --shortstat origin/master...HEAD` and
   `git diff --numstat origin/master...HEAD`.
3. Delete this file (`docs/e2e/HANDOFF.md`) and the two references to it (`.github/workflows/ci.yml` header comment
   and `docs/DECISIONS.md` R2-Q15 keep only the decision), commit, push.
4. Mark the pull request ready for review (`gh pr ready 2`, or the "Ready for review" button).

## 6. Open questions for the owner (unchanged, `docs/DECISIONS.md` R2.5)

1. Should the field-force app skip auto-start for some hours after the 02:00 stop? Today, unlocking the phone with the
   app in front at 02:30 restarts tracking until the next 02:00.
2. Confirm unit 4's two `syncInterval` extensions: normal records upload at once while tracking is off; after a failed
   upload they are retried once per `syncInterval`.
3. Should the plugin hold a wake lock during the heartbeat upload and the 02:00 check? Today a sleeping phone can delay
   them until the next wake-up.

## 7. Pull request description draft

Everything between the two lines below is the draft description (Markdown), to be pasted as the pull request body.

---

## Summary

Round 2 of `@bricks-soft/capacitor-location-tracking`. It builds the Bricks field-force use case on top of the plugin merged in #1, and adds end-to-end tests that run the real apps on Android emulators in GitHub Actions.

What the owner asked for, and where it is:

| Owner's requirement | What this PR does | Where |
|---|---|---|
| Start tracking when the app starts, stop at 02:00, without `ForegroundServiceDidNotStartInTimeException` | The field-force example app starts tracking on every page load and when it comes back to the front with tracking off. It passes the minutes until the next 02:00 as `geolocation.stopAfterElapsedMinutes`. The plugin's foreground service now enters the foreground before it reads any settings, a stop can no longer race a start, and a start that Android 14+ would refuse is not attempted. | `examples/field-force/www/`, `android/…/service/` |
| 12+ hours a day, battery efficient, without losing the audit trail | GPS is off while stationary: passive updates, a 150 m stationary geofence and activity recognition detect movement. The foreground service and the heartbeats keep running; a heartbeat keeps the last fix with the time it was acquired. The heartbeat makes 6 times fewer alarm calls while moving. | `android/…/engine/`, `android/…/heartbeat/` |
| Back office: live location, route, device details, battery, online status, travel time and distance | New `http.syncInterval` (the field-force app uses 300 s): normal location records upload at most 5 minutes late, audit records (heartbeat, `tracking_start`, `tracking_stop`, `providerchange`) at once. Heartbeat records carry a `heartbeat` object that tells the server whether a gap was expected. Device details go in `http.params.device`; battery is in every record. | `android/…/http/`, `docs/wire-format.md`, `docs/heartbeat.md` |
| A second plugin (PremiseMonitor) receives every audit event natively and monitors a circular premise | Public Kotlin API for companion plugins: manifest-declared listener classes (created at process start, so no event is missed after a reboot or an alarm) and programmatic subscriptions, plus the plugin's methods as Kotlin calls. A fake PremiseMonitor plugin with its own foreground location service uses it. | `android/…/api/`, `docs/native-api.md`, `examples/field-force/plugins/premise-monitor/` |
| Real tests on an emulator for the hard edge cases, a runbook for what cannot be automated | 34 plugin scenarios and 12 field-force scenarios run on Android emulators (in GitHub Actions until the Actions minutes ran out, then on local emulators; both workflows now start only by hand). `docs/e2e-runbook.md` is a step-by-step runbook for an AI agent, with 8 manual scenarios (Huawei phone, phone-maker task killers, real drive, 12-hour battery, real 02:00 stop, Android 14 boot, 16 KB check, overnight Doze). | `testing/e2e-kit/`, `e2e/plugin/`, `examples/field-force/e2e/`, `.github/workflows/e2e-android*.yml` |

**Every decision is logged in [`docs/DECISIONS.md`](docs/DECISIONS.md), round-2 part**: your decisions (quoted), the coordinator's decisions, the integration decisions (including what each emulator run found), every unit's decisions with reasons, and the open items.

## Your answers used in this round
- 02:00 stop: example-app config (`stopAfterElapsedMinutes` computed at start), no plugin schedule feature.
- Stationary: GPS off, service and heartbeats keep running, the heartbeat carries the last fix with its acquisition time.
- Live location at most 5 minutes old: `http.syncInterval`.
- Companion API: manifest listener + programmatic subscription.
- Emulator tests in GitHub Actions + an AI-agent runbook.
- PremiseMonitor has its own foreground location service.

## What is in the diff (249 files, about 38,000 added lines)

| Area | Lines added | Where |
|---|---|---|
| Plugin (Kotlin) | ~2,770 | `android/src/main/**` |
| Plugin unit tests | ~5,280 | `android/src/test/**` |
| e2e kit (TypeScript: adb, WebView driver, mock back office, assertions, crash scanner) | ~7,280 | `testing/e2e-kit/` |
| Plugin emulator suites (34 scenarios) | ~5,150 | `e2e/plugin/` |
| Field-force app + PremiseMonitor + its suite (12 scenarios), without the npm lock file | ~9,580 | `examples/field-force/` |
| Plugin example test hooks | ~1,310 | `example/` |
| Docs (runbook, contract, decisions, README, heartbeat, wire format, native API) | ~4,140 | `docs/`, `README.md` |
| CI (emulator workflow, 16 KB check, scripts) | ~1,130 | `.github/` |

## Suggested review order
1. `docs/e2e/architecture.md` §2–§5 (the contracts for the plugin changes), then `docs/DECISIONS.md` round-2 part.
2. Plugin: `service/` (start hardening), `engine/StationaryRegion.kt` + `engine/DefaultTrackingEngine.kt` (GPS off while stationary, force-stop rule), `heartbeat/`, `http/OkHttpSyncer.kt` (`syncInterval`), `api/` + `docs/native-api.md`.
3. Field-force app: `examples/field-force/www/ff-core.js`, then PremiseMonitor `examples/field-force/plugins/premise-monitor/android/`.
4. Tests: `e2e/plugin/*.test.ts`, `examples/field-force/e2e/field-force.test.ts`, then the kit.
5. `docs/e2e-runbook.md`, `.github/workflows/e2e-android.yml`, `.github/scripts/`.

## How it was built
1. A scaffold commit fixed every contract (types, constructor parameters, config keys, wire fields, debug-command protocol, kit API, scenario catalogue) and compiling stubs.
2. 15 units were implemented in parallel, each code-reviewed and tested.
3. The units were merged; the coordinator applied the units' change requests and a documentation pass matched every document to the code.
4. The emulator runs in CI found problems that no unit test could; they were root-caused from the uploaded logcat and `dumpsys` artifacts and fixed (see below and `docs/DECISIONS.md` R2.3). When the Actions minutes ran out, both workflows were set to start only by hand and the runs moved to local emulators.

## Verification
- Plugin unit tests (`testDebugUnitTest`): **1,492 tests, 0 failures**.
- e2e kit tests: **128 passed**. Field-force startup logic: **47 passed**. PremiseMonitor: **61 passed**. Root TypeScript: **46 passed**.
- Example app APK builds with `gms,hms`, `gms` and `hms`; field-force debug and release APKs build; the release APK contains no debug hooks.
- 16 KB page-size check: the field-force Play build (GMS only) passes; the `gms,hms` build has 2 unaligned HMS native libraries (reported, as expected).
- Emulator runs (final head):

EMULATOR_RESULTS

## What the emulator runs found
Two plugin problems:
- After a user force stop, a Google Play services activity update that was already on its way restarted the process and tracking. Now a background trigger (activity update, stationary region EXIT) does not restore tracking in a process that started after a user force stop (Android 11+); tracking resumes when the app is opened. The check reads the exit record of the app's main process: the first version read the newest record of the package, which can belong to the WebView's renderer process (it runs under the app and the force stop kills it a few milliseconds later), and missed the force stop on the emulator.
- When location services came back on, the plugin re-registered its geofences once, about 1 s later. If Google Play services answered `GEOFENCE_NOT_AVAILABLE` at that moment (its network location not yet on again), the geofences stayed unregistered until tracking started again. Now a registration that meets `UNAVAILABLE` is repeated after 10, 30, 60, 120 and 300 s.

One scenario did not test what it claimed: P-L13 (busy main thread around a cold service start, the `ForegroundServiceDidNotStartInTimeException` case) was skipped in every CI run, because its timing signal (`am_create_service`) is not logged on any CI image and its timing window was about 2 ms. A new debug command blocks the main thread and calls `start()` from a background thread during the block, so every attempt delays the service creation by about 3.9 s; the plugin enters the foreground 5–14 ms after `onCreate`.

Everything else was in the emulator setup or the test kit (details and evidence in `docs/DECISIONS.md` R2.3): a crash-log read so large it dropped the adb connection; an Android 15 `dumpsys` format change; no fixes on the emulator while GPS is off (a debug-only "other app requests GPS" command); a kernel suspend in deep Doze; Play services restarting itself after boot and taking the app under test with it; permission grants lost by an early reboot; mock locations under root; "Don't keep activities" not settable from the shell; a stale Play services position before a premise geofence; the fused provider smoothing the tests' position jumps; `cmd location` missing on API 29; a route check that counted a last known position from the previous scenario; an upload check stricter than the `syncInterval` contract.

One guidance line was added for back offices (`docs/wire-format.md`): when drawing the route or adding up distance, leave out records whose fix `timestamp` is much older than their `recorded_at`. A `motionchange` recorded before the first GPS fix carries the last known position.

## Known limitations and open questions
Recorded in `docs/DECISIONS.md` R2.5. For you to decide:
1. Should the field-force app skip auto-start for some hours after the 02:00 stop? Today, unlocking the phone with the app in front at 02:30 restarts tracking until the next 02:00.
2. Confirm unit 4's two `syncInterval` extensions: normal records upload at once while tracking is off; after a failed upload they are retried once per `syncInterval`.
3. Should the plugin hold a wake lock during the heartbeat upload and the 02:00 check? Today a sleeping phone can delay them until the next wake-up.

Other limitations:
- The 02:00 stop happens at the first heartbeat after 02:00 while stationary (up to 5 minutes late when battery-exempt, about 9–11 minutes when not).
- With activity recognition denied, in the background and stationary, the emulator noticed a drive only after 4.5 to 5 minutes (P-P04).
- HMS: the `gms,hms` build carries 2 native libraries that are not 16 KB aligned; Play builds must use `gms` only. HMS on a real Huawei phone is runbook scenario M-01.
- The instrumented tests of the example's debug receiver are not run by CI yet.
- Both CI workflows (`ci.yml`, `e2e-android.yml`) start only by hand; pushes and pull requests start no run.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_017AZrP773cNgQLT4AVPqyG7

---
