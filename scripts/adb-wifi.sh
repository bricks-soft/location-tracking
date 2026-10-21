#!/usr/bin/env bash
# Connects an Android 11+ phone to adb over Wi-Fi with a QR code, and keeps its screen on during a manual procedure
# (docs/e2e-runbook.md 8.0).
#
# Usage:
#   adb-wifi.sh pair [qr.png]           show a pairing QR code in the terminal (and write it as a PNG when a path is
#                                       given), wait up to 5 minutes for the phone to scan it, pair, and connect
#   adb-wifi.sh screen-timeout [min]    save the phone's screen timeout on the phone, then set it to <min> minutes
#                                       (default 30); a second call keeps the first saved value
#   adb-wifi.sh screen-timeout-restore  set the saved screen timeout again and delete the saved value
#
# On the phone: Developer options -> Wireless debugging -> on -> "Pair device with QR code". The phone and this
# computer must be on the same network. The QR code comes from the npm package qrcode (npx downloads it once).
# With more than one device attached, set ANDROID_SERIAL for the screen-timeout commands.
set -euo pipefail

QRCODE=qrcode@1.5.4
SAVED=/data/local/tmp/lt-screen-off-timeout

pair() {
  local name pass qr png="${1:-}" end=$((SECONDS + 300)) svc addr out guid="" serial=""
  name="adb-lt-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
  pass="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
  qr="WIFI:T:ADB;S:$name;P:$pass;;"
  npx --yes "$QRCODE" --small "$qr"
  [ -z "$png" ] || npx --yes "$QRCODE" -w 480 -o "$png" "$qr"
  echo "Scan this code on the phone: Wireless debugging -> Pair device with QR code. Waiting up to 5 minutes."
  while [ $SECONDS -lt $end ] && [ -z "$serial" ]; do
    svc=$(adb mdns services 2>/dev/null || true)
    if [ -z "$guid" ]; then
      addr=$(awk -v n="$name" '$1 == n && $2 ~ /_adb-tls-pairing/ {print $3; exit}' <<<"$svc")
      if [ -n "$addr" ]; then
        out=$(adb pair "$addr" "$pass" 2>&1 || true); echo "$out"
        guid=$(sed -n 's/.*\[guid=\(.*\)\].*/\1/p' <<<"$out")
      fi
    else
      # adb connects a paired phone on its own when it sees the phone's _adb-tls-connect service; else connect here.
      serial=$(adb devices | awk -v g="$guid" 'index($1, g) == 1 && $2 == "device" {print $1; exit}')
      addr=$(awk -v n="$guid" '$1 == n && $2 ~ /_adb-tls-connect/ {print $3; exit}' <<<"$svc")
      if [ -z "$serial" ] && [ -n "$addr" ]; then
        adb connect "$addr" | grep -q '^\(already \)\?connected to' && serial=$addr
      fi
    fi
    [ -n "$serial" ] || sleep 2
  done
  [ -n "$serial" ] || { echo "no phone paired and connected within 5 minutes" >&2; exit 1; }
  adb devices -l
  echo "Connected: ANDROID_SERIAL=$serial"
}

screen_timeout() {
  local min="${1:-30}"
  adb shell "[ -f $SAVED ] || settings get system screen_off_timeout > $SAVED"
  adb shell settings put system screen_off_timeout $((min * 60000))
  echo "Screen timeout: $min min. Saved value: $(adb shell cat $SAVED | tr -d '\r') ms."
}

screen_timeout_restore() {
  local v
  v=$(adb shell "cat $SAVED 2>/dev/null" | tr -d '\r')
  [ -n "$v" ] || { echo "no saved screen timeout on the phone" >&2; exit 1; }
  if [ "$v" = null ]; then adb shell settings delete system screen_off_timeout; else adb shell settings put system screen_off_timeout "$v"; fi
  adb shell rm $SAVED
  echo "Screen timeout restored: $v ms."
}

case "${1:-}" in
  pair) shift; pair "$@" ;;
  screen-timeout) shift; screen_timeout "$@" ;;
  screen-timeout-restore) screen_timeout_restore ;;
  *) sed -n '2,14p' "$0"; exit 2 ;;
esac
