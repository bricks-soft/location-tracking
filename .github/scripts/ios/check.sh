#!/usr/bin/env bash
# The iOS checks of .github/workflows/ios.yml. ios.yml calls one step per workflow step; on a Mac without Actions,
# `check.sh all` runs the same steps in order (scripts/release.sh does). Results go to $RUNNER_TEMP, or to
# $TMPDIR/location-tracking-ios when it is not set.
#
# Usage: check.sh all | build | xctest | ui-build | ui-permissions | precise-host | ui-checkin | e2e
#                     | package <SPM|CocoaPods> | legacy
#   all     build, xctest, the UI tests, the e2e scenarios on the simulator, then package SPM and CocoaPods
#   legacy  the same steps on an iOS 15 or 16 simulator (ios.yml runs it on macos-15 with Xcode 16)
#
# Environment: IOS_SIMULATOR (set by `build` through $GITHUB_ENV), IOS_DESTINATION (xcodebuild destination of the
# test host build; default any simulator), E2E_TEST_NAME_PATTERN (default: the shared scenarios P-S01 to P-S06).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
export RUNNER_TEMP="${RUNNER_TEMP:-${TMPDIR:-/tmp}/location-tracking-ios}"
mkdir -p "$RUNNER_TEMP"
if [ -z "${GITHUB_ENV:-}" ]; then
  export GITHUB_ENV="$RUNNER_TEMP/github-env"
  touch "$GITHUB_ENV"
fi
here=.github/scripts/ios
project="$RUNNER_TEMP/ios-consumer/ios/App/App.xcodeproj"
signing=(CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-)
serial=(-parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1)

load_env() {
  set -a
  # shellcheck disable=SC1090
  . "$GITHUB_ENV"
  set +a
}

simulator() {
  load_env
  [ -n "${IOS_SIMULATOR:-}" ] || { echo "IOS_SIMULATOR is not set: run the build step first" >&2; exit 1; }
}

backoffice() {
  if ! curl -fs http://127.0.0.1:8787/__health >/dev/null 2>&1; then
    node testing/e2e-kit/src/cli/backoffice.ts --host 127.0.0.1 >"$RUNNER_TEMP/backoffice.log" 2>&1 &
    echo $! >"$RUNNER_TEMP/backoffice.pid"
    for _ in $(seq 1 50); do curl -fs http://127.0.0.1:8787/__health >/dev/null 2>&1 && return 0; sleep 0.2; done
    echo "the mock back office did not start" >&2
    exit 1
  fi
}

stop_backoffice() {
  if [ -f "$RUNNER_TEMP/backoffice.pid" ]; then
    kill "$(cat "$RUNNER_TEMP/backoffice.pid")" 2>/dev/null || true
    rm -f "$RUNNER_TEMP/backoffice.pid"
  fi
}

step_build() {
  npm ci
  (cd testing/e2e-kit && npm ci)
  (cd e2e/plugin && npm ci)
  python3 -m unittest discover -s "$here" -p 'test_*.py' -v
  node "$here/consumer.mjs" SPM
  ruby "$here/test_host.rb"
  ruby "$here/simulator_signing.rb"
  load_env
  xcodebuild build-for-testing -project "$project" -scheme Stage1 -destination "${IOS_DESTINATION:-generic/platform=iOS Simulator}" \
    -derivedDataPath "$RUNNER_TEMP/ios-unit-build" "${signing[@]}"
  if [ -z "${IOS_DESTINATION:-}" ]; then python3 "$here/select_simulator.py"; fi
}

step_xctest() {
  simulator
  backoffice
  if [ -n "${IOS_DESTINATION:-}" ]; then
    xcrun simctl install "$IOS_SIMULATOR" "$RUNNER_TEMP/ios-unit-build/Build/Products/Debug-iphonesimulator/App.app"
    xcrun simctl get_app_container "$IOS_SIMULATOR" com.brickssoft.locationtracking.example app
  fi
  rm -rf "$RUNNER_TEMP/ios-tests.xcresult"
  xcodebuild test-without-building "${serial[@]}" -project "$project" -scheme Stage1 -derivedDataPath "$RUNNER_TEMP/ios-unit-build" \
    -destination "platform=iOS Simulator,id=$IOS_SIMULATOR" -resultBundlePath "$RUNNER_TEMP/ios-tests.xcresult" "${signing[@]}"
}

step_ui_build() {
  simulator
  xcrun simctl boot "$IOS_SIMULATOR" || true
  xcrun simctl bootstatus "$IOS_SIMULATOR" -b
  xcrun simctl location "$IOS_SIMULATOR" set '24.7136,46.6753'
  xcodebuild build-for-testing -project "$project" -scheme Permissions -derivedDataPath "$RUNNER_TEMP/ios-ui-build" \
    -destination "platform=iOS Simulator,id=$IOS_SIMULATOR" "${signing[@]}"
  python3 "$here/ui_prepare.py" "$RUNNER_TEMP/ios-ui-build/Build/Products/Debug-iphonesimulator"
}

ui_test() {
  rm -rf "$RUNNER_TEMP/$2.xcresult"
  python3 "$here/ui_run.py" xcodebuild test-without-building "${serial[@]}" -project "$project" -scheme Permissions \
    "-only-testing:PermissionUITests/$1" -derivedDataPath "$RUNNER_TEMP/ios-ui-build" \
    -destination "platform=iOS Simulator,id=$IOS_SIMULATOR" -resultBundlePath "$RUNNER_TEMP/$2.xcresult" "${signing[@]}"
}

step_ui_permissions() {
  simulator
  ui_test PermissionUITests ios-permissions
}

step_precise_host() {
  simulator
  python3 "$here/precise_host.py" "$RUNNER_TEMP/ios-ui-build/Build/Products/Debug-iphonesimulator"
}

step_ui_checkin() {
  simulator
  ui_test CheckInUITests ios-checkin
}

step_e2e() {
  simulator
  E2E_PLATFORM=ios E2E_SIMULATOR="$IOS_SIMULATOR" E2E_SUITE_DIR=e2e/plugin \
    E2E_IOS_APP="$RUNNER_TEMP/ios-ui-build/Build/Products/Debug-iphonesimulator/App.app" \
    E2E_ARTIFACTS_DIR="$RUNNER_TEMP/e2e-artifacts" E2E_BACKEND_PORT=8790 \
    E2E_TEST_NAME_PATTERN="${E2E_TEST_NAME_PATTERN:-P-S0[1-6]\\b}" \
    bash .github/scripts/run-e2e.sh
}

step_package() {
  local manager="$1" app
  [ "$manager" = SPM ] || [ "$manager" = CocoaPods ] || { echo "package needs SPM or CocoaPods" >&2; exit 1; }
  [ -d node_modules ] || npm ci
  (cd testing/e2e-kit && [ -d node_modules ] || npm ci)
  (cd e2e/plugin && [ -d node_modules ] || npm ci)
  load_env
  if [ -z "${IOS_SIMULATOR:-}" ]; then python3 "$here/select_simulator.py"; fi
  simulator
  rm -rf "$RUNNER_TEMP/ios-consumer" "$RUNNER_TEMP/LTExample.xcarchive" "$RUNNER_TEMP/ipa" "$RUNNER_TEMP/adhoc"
  node "$here/consumer.mjs" "$manager"
  local build=(-project App.xcodeproj)
  [ -d "$RUNNER_TEMP/ios-consumer/ios/App/App.xcworkspace" ] && build=(-workspace App.xcworkspace)
  (cd "$RUNNER_TEMP/ios-consumer/ios/App" &&
    xcodebuild "${build[@]}" -scheme App -configuration Release -sdk iphoneos -archivePath "$RUNNER_TEMP/LTExample.xcarchive" archive CODE_SIGNING_ALLOWED=NO)
  mkdir -p "$RUNNER_TEMP/ipa/Payload"
  cp -R "$RUNNER_TEMP/LTExample.xcarchive/Products/Applications/App.app" "$RUNNER_TEMP/ipa/Payload/"
  (cd "$RUNNER_TEMP/ipa" && rm -f "$RUNNER_TEMP/location-tracking-example-unsigned.ipa" && zip -qry "$RUNNER_TEMP/location-tracking-example-unsigned.ipa" Payload)
  python3 "$here/release_adhoc.py"
  ruby "$here/simulator_signing.rb"
  (cd "$RUNNER_TEMP/ios-consumer/ios/App" &&
    xcodebuild "${build[@]}" -scheme App -configuration Debug -sdk iphonesimulator -destination "platform=iOS Simulator,id=$IOS_SIMULATOR" \
      -derivedDataPath "$RUNNER_TEMP/ios-simulator" build "${signing[@]}")
  app="$RUNNER_TEMP/ios-simulator/Build/Products/Debug-iphonesimulator/App.app"
  E2E_PLATFORM=ios E2E_SIMULATOR="$IOS_SIMULATOR" E2E_SUITE_DIR=e2e/plugin E2E_IOS_APP="$app" \
    E2E_ARTIFACTS_DIR="$RUNNER_TEMP/e2e-artifacts-$manager" E2E_BACKEND_PORT=8790 E2E_TEST_NAME_PATTERN='P-S07\b' \
    bash .github/scripts/run-e2e.sh
}

step_legacy() {
  : >"$GITHUB_ENV"
  python3 "$here/legacy_runtime.py"
  load_env
  step_build
  step_xctest
  step_ui_build
  step_ui_permissions
  step_precise_host
  step_ui_checkin
  step_e2e
}

step_all() {
  : >"$GITHUB_ENV"
  step_build
  step_xctest
  step_ui_build
  step_ui_permissions
  step_precise_host
  step_ui_checkin
  step_e2e
  stop_backoffice
  step_package SPM
  step_package CocoaPods
}

trap stop_backoffice EXIT
case "${1:-}" in
  all | build | xctest | ui-build | ui-permissions | precise-host | ui-checkin | e2e | legacy)
    "step_${1//-/_}"
    ;;
  package) step_package "${2:-}" ;;
  *)
    sed -n '2,12p' "$0" >&2
    exit 2
    ;;
esac
