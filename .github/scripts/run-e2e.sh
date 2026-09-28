#!/usr/bin/env bash
# Runs one AVD end-to-end suite (docs/e2e/architecture.md §8-§9) against the emulator that is already running.
#
# .github/workflows/e2e-android.yml calls it from the `script:` of reactivecircus/android-emulator-runner, which runs
# every script line as a separate `sh -c` command; everything that needs shell constructs therefore lives here.
# It also works on a local machine with exactly one emulator attached (or E2E_SERIAL set).
#
# Usage:
#   run-e2e.sh               install E2E_APK and run the suite in E2E_SUITE_DIR (see the environment below)
#   run-e2e.sh --list-long   print the ids of the suite's scenarios that require `long`, one per line (no device
#                            needed); e2e-android-run.yml uses it to skip the emulator when there are none
#
# Environment (inputs). Relative paths are resolved against the current working directory.
#   E2E_SUITE_DIR          required: suite directory (e2e/plugin or examples/field-force/e2e). `npm ci` must already
#                          have run in it and in testing/e2e-kit.
#   E2E_APK                required unless E2E_DRY_RUN=1: debug APK of the app under test. Installed with
#                          `adb install -r -g` before the suite starts, and exported to the suite as an absolute path.
#   E2E_ARTIFACTS_DIR      default <E2E_SUITE_DIR>/e2e-artifacts (the kit's default). Exported as an absolute path. The
#                          kit writes one directory per failed scenario into it; this script writes `_run/` (Outputs).
#   E2E_TEST_NAME_PATTERN  optional: a JavaScript regular expression passed to `node --test-name-pattern`. Test names
#                          are "<scenario id> <title>", e.g. 'P-(L01|H05)\b'.
#   E2E_ONLY_LONG=1        run only the scenarios that require `long` (see --list-long), with E2E_INCLUDE_LONG=1
#                          (replaces E2E_TEST_NAME_PATTERN). Exits 0 without running anything when there are none.
#   E2E_DRY_RUN=1          no device steps: runs `npm run test:e2e` in dry-run mode (checks the pattern handling).
#   E2E_SERIAL             adb serial (default: the only attached device). Also exported as ANDROID_SERIAL.
#   E2E_ADB                adb binary (default $ANDROID_HOME/platform-tools/adb, else `adb` on PATH).
#   E2E_INCLUDE_LONG, E2E_BACKEND_PORT, E2E_BUGREPORT, E2E_APP_ID are passed through to the suite unchanged.
#
# Outputs in $E2E_ARTIFACTS_DIR/_run/:
#   logcat.txt        main, system, crash and events buffers of the whole run (re-attached after every reboot)
#   test-output.txt   the complete `npm run test:e2e` output (TAP)
#   device.txt        API level, fingerprint, ABI, page size, root and Google Play services state of the emulator
#   getprop.txt       `adb shell getprop` at the start
#   crash-buffer.txt  `adb logcat -d -b crash` at the end
#
# Exit status: the exit status of `npm run test:e2e`, or 1 when a setup step fails.
set -euo pipefail

# Diagnostics go to stderr, so `--list-long` prints only ids on stdout.
log() { printf '[run-e2e %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

absolute() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s\n' "$PWD/$1" ;;
  esac
}

mode=run
case "${1:-}" in
  "") ;;
  --list-long) mode=list-long ;;
  *) die "unknown argument: $1 (usage: run-e2e.sh [--list-long])" ;;
esac

[ -n "${E2E_SUITE_DIR:-}" ] || die "E2E_SUITE_DIR is not set"
suite_dir="$(absolute "$E2E_SUITE_DIR")"
[ -f "$suite_dir/package.json" ] || die "no package.json in $suite_dir"

# Ids of the suite's scenarios whose requirements contain `long`, one per line. In dry-run mode the kit prints one
# line per scenario (the TAP reporter prefixes it with "# "):
#   DRY-RUN <id> | <title> | requires: <comma list, or -> | timeout <n>s
list_long_ids() {
  local listing
  listing="$(cd "$suite_dir" && E2E_DRY_RUN=1 npm run --silent test:e2e 2>&1)" ||
    die "the dry-run listing of $E2E_SUITE_DIR failed:"$'\n'"$listing"
  printf '%s\n' "$listing" |
    sed -n -E 's/^[#[:space:]]*DRY-RUN ([PF]-[A-Z]?[0-9]+) \| .* \| requires: (.*) \| timeout [0-9]+s[[:space:]]*$/\1 \2/p' |
    awk '{ id = $1; $1 = ""; n = split($0, parts, /, */); for (i = 1; i <= n; i++) { gsub(/^ +| +$/, "", parts[i]); if (parts[i] == "long") { print id; break } } }'
}

if [ "$mode" = "list-long" ]; then
  list_long_ids
  exit 0
fi

E2E_ARTIFACTS_DIR="$(absolute "${E2E_ARTIFACTS_DIR:-$suite_dir/e2e-artifacts}")"
export E2E_ARTIFACTS_DIR
run_dir="$E2E_ARTIFACTS_DIR/_run"
mkdir -p "$run_dir"

dry_run=0
case "${E2E_DRY_RUN:-}" in 1 | true | yes) dry_run=1 ;; esac

# ---- test name pattern -------------------------------------------------------------------------------------------

pattern="${E2E_TEST_NAME_PATTERN:-}"

if [ "${E2E_ONLY_LONG:-}" = "1" ]; then
  long_ids="$(list_long_ids)"
  if [ -z "$long_ids" ]; then
    log "no scenario in $E2E_SUITE_DIR requires 'long'; nothing to run"
    printf 'no long scenarios in %s\n' "$E2E_SUITE_DIR" >"$run_dir/test-output.txt"
    exit 0
  fi
  pattern="($(printf '%s\n' "$long_ids" | paste -sd '|' -))\\b"
  export E2E_INCLUDE_LONG=1
  log "long scenarios: $(printf '%s\n' "$long_ids" | paste -sd ' ' -)"
fi

if [ -n "$pattern" ]; then
  # `npm run test:e2e -- --test-name-pattern=...` does not work: npm appends the flag after the "*.test.ts" file
  # pattern, where node passes it to the test files as an argument. NODE_OPTIONS reaches the node process of the
  # script (and the per-file test processes). Inside double quotes NODE_OPTIONS treats a backslash as an escape
  # character, so backslashes and double quotes are escaped first.
  escaped="${pattern//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--test-name-pattern=\"$escaped\""
  log "test name pattern: $pattern"
fi

run_suite() {
  local status
  log "npm run test:e2e in $E2E_SUITE_DIR (artifacts: $E2E_ARTIFACTS_DIR)"
  set +e
  (cd "$suite_dir" && npm run test:e2e) 2>&1 | tee "$run_dir/test-output.txt"
  status=${PIPESTATUS[0]}
  set -e
  log "npm run test:e2e exited with $status"
  return "$status"
}

if [ "$dry_run" = "1" ]; then
  export E2E_DRY_RUN=1
  status=0
  run_suite || status=$?
  exit "$status"
fi

# ---- device ------------------------------------------------------------------------------------------------------

[ -n "${E2E_APK:-}" ] || die "E2E_APK is not set"
E2E_APK="$(absolute "$E2E_APK")"
[ -f "$E2E_APK" ] || die "APK not found: $E2E_APK"
export E2E_APK

android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
adb="${E2E_ADB:-}"
if [ -z "$adb" ]; then
  if [ -n "$android_home" ] && [ -x "$android_home/platform-tools/adb" ]; then
    adb="$android_home/platform-tools/adb"
  else
    adb="$(command -v adb)" || die "adb not found (set ANDROID_HOME or E2E_ADB)"
  fi
fi
if [ -n "${E2E_SERIAL:-}" ]; then
  export ANDROID_SERIAL="$E2E_SERIAL"
fi

wait_for_boot() {
  local deadline=$((SECONDS + 300))
  "$adb" wait-for-device
  until [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    [ "$SECONDS" -lt "$deadline" ] || die "the device did not finish booting within 300 s"
    sleep 2
  done
}

log "waiting for the device"
wait_for_boot

# Root (google_apis and default images allow it; the kit's kill -9, clock and reboot scenarios need it).
# `adb root` restarts adbd, so wait until the shell user is root (at most 30 s) and the device is back.
if "$adb" root >/dev/null 2>&1; then
  deadline=$((SECONDS + 30))
  until [ "$("$adb" shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; do
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep 1
    "$adb" wait-for-device >/dev/null 2>&1 || true
  done
fi
wait_for_boot

# Device baseline for every suite:
# - no lock screen, and the current keyguard dismissed, so an activity started by the kit is resumed and visible
#   (the plugin's start() needs a visible app);
# - the screen stays on while the device reports external power (scenarios that run `dumpsys battery unplug` get the
#   normal screen timeout);
# - no package-verifier dialog for `adb install` (the suites reinstall the APK).
"$adb" shell locksettings set-disabled true >/dev/null 2>&1 || true
"$adb" shell input keyevent 82 >/dev/null 2>&1 || true
"$adb" shell svc power stayon true >/dev/null 2>&1 || true
"$adb" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
"$adb" shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
# - a kernel wake lock (root), so the emulator never suspends: on the first API 29 run, deep Doze with the screen off
#   and the battery "unplugged" let the kernel suspend, adbd dropped the connection and every later adb call hung.
#   Doze is a framework state and still works; the kit sets the lock again after each reboot.
"$adb" shell 'echo e2e-kit > /sys/power/wake_lock' >/dev/null 2>&1 || true

# Google Play services restarts its own processes once after boot when a module's configuration changes
# ("ChimeraModuleLdr: Module config changed, forcing restart"). An app that uses one of its content providers is killed
# with it ("depends on provider … in dying proc"): P-H01 and F-01 (API 34) lost the app under test that way. On the
# API 34 image the restart came in every run, 64-132 s after the persistent Play services process started; on the
# API 29 and API 35 images it never came. Wait until the restart was logged (at most 200 s), then until the persistent
# process has kept the same pid for 60 s (at most 8 minutes in all).
# `|| true`: pidof exits 1 while the process is not running (during its restart), which set -e and pipefail would
# turn into the end of this script (API 35 run on 51f7721).
gms_pid() { "$adb" shell pidof com.google.android.gms.persistent 2>/dev/null | tr -d '\r' || true; }
gms_restart_logged() {
  local out
  # Captured first: with pipefail, `adb logcat | grep -q` fails when grep exits before adb (SIGPIPE).
  out="$("$adb" logcat -d -b main -s ChimeraModuleLdr:I 2>/dev/null || true)"
  case "$out" in *"forcing restart"*) return 0 ;; *) return 1 ;; esac
}
if "$adb" shell pm path com.google.android.gms >/dev/null 2>&1; then
  log "waiting for Google Play services to settle"
  settle_start=$SECONDS
  settle_deadline=$((SECONDS + 480))
  restart_seen=no
  stable_since=$SECONDS
  last_pid="$(gms_pid)"
  while [ "$SECONDS" -lt "$settle_deadline" ]; do
    sleep 10
    if [ "$restart_seen" = no ] && gms_restart_logged; then
      restart_seen=yes
      log "Google Play services restarted itself (module config changed)"
    fi
    pid="$(gms_pid)"
    if [ -z "$pid" ] || [ "$pid" != "$last_pid" ]; then
      last_pid="$pid"
      stable_since=$SECONDS
    elif [ $((SECONDS - stable_since)) -ge 60 ] &&
      { [ "$restart_seen" = yes ] || [ $((SECONDS - settle_start)) -ge 200 ]; }; then
      break
    fi
  done
  log "Google Play services pid ${last_pid:-none}, stable for $((SECONDS - stable_since)) s, restart seen: $restart_seen"
fi

getprop_value() { "$adb" shell getprop "$1" 2>/dev/null | tr -d '\r'; }
{
  echo "serial: $("$adb" get-serialno 2>/dev/null | tr -d '\r')"
  echo "api: $(getprop_value ro.build.version.sdk)"
  echo "release: $(getprop_value ro.build.version.release)"
  echo "fingerprint: $(getprop_value ro.build.fingerprint)"
  echo "abi: $(getprop_value ro.product.cpu.abi)"
  echo "page size: $("$adb" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
  echo "root: $([ "$("$adb" shell id -u 2>/dev/null | tr -d '\r')" = "0" ] && echo yes || echo no)"
  echo "google play services: $("$adb" shell pm path com.google.android.gms >/dev/null 2>&1 && echo yes || echo no)"
} >"$run_dir/device.txt"
"$adb" shell getprop >"$run_dir/getprop.txt" 2>&1 || true
log "device:"
sed 's/^/  /' "$run_dir/device.txt"

# ---- logcat of the whole run -------------------------------------------------------------------------------------
# `adb logcat` ends when the device reboots (reboot scenarios); the loop attaches again. The loop's stdin, stdout
# and stderr are files, so it never holds the emulator-runner's output pipe open.

logcat_stop="$run_dir/.logcat-stop"
rm -f "$logcat_stop"
logcat_loop() {
  set +e # adb logcat exits non-zero on every reboot; the loop must survive it
  while [ ! -e "$logcat_stop" ]; do
    "$adb" wait-for-device
    [ ! -e "$logcat_stop" ] || break
    echo "===== run-e2e: logcat attached at $(date -u +%Y-%m-%dT%H:%M:%SZ) ====="
    "$adb" logcat -v threadtime -b main -b system -b crash -b events
    sleep 2
  done
}
logcat_loop </dev/null >>"$run_dir/logcat.txt" 2>&1 &
logcat_pid=$!

# shellcheck disable=SC2329 # called from finish (EXIT trap)
stop_logcat() {
  [ -n "${logcat_pid:-}" ] || return 0
  touch "$logcat_stop"
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$logcat_pid" 2>/dev/null || break
    pkill -P "$logcat_pid" 2>/dev/null || true
    sleep 1
  done
  kill "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
  logcat_pid=""
}

# shellcheck disable=SC2329 # EXIT trap
finish() {
  "$adb" logcat -d -b crash >"$run_dir/crash-buffer.txt" 2>&1 || true
  stop_logcat
  rm -f "$logcat_stop"
}
trap finish EXIT

# ---- install and run ---------------------------------------------------------------------------------------------

log "installing $E2E_APK"
installed=0
for attempt in 1 2 3; do
  if "$adb" install -r -g "$E2E_APK"; then
    installed=1
    break
  fi
  log "install attempt $attempt failed"
  sleep 10
done
[ "$installed" = "1" ] || die "adb install failed 3 times"

status=0
run_suite || status=$?
exit "$status"
