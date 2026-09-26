#!/usr/bin/env bash
# Runs system gradle while holding one of 3 machine-wide slots, so many worktrees can build concurrently.
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
while :; do
  for i in 1 2 3; do
    exec {fd}>"/tmp/lt-gradle-slot-$i.lock"
    if flock -n "$fd"; then gradle "$@"; rc=$?; exec {fd}>&-; exit $rc; fi
    exec {fd}>&-
  done
  sleep 5
done
