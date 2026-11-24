#!/usr/bin/env bash
# Build, install, and launch the debug app on a connected device.
# Usage: ./run-on-phone.sh [device-serial]
# Defaults to the Pixel 10 Pro; set ANDROID_SERIAL or pass a serial to override.
set -euo pipefail

cd "$(dirname "$0")"

SERIAL="${1:-${ANDROID_SERIAL:-59190DLCH008TS}}"
PACKAGE="com.lockpicking.bench"

if [[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" != "device" ]]; then
  echo "Device $SERIAL not connected. Plug in the phone (USB debugging on) and retry." >&2
  echo "Connected devices:" >&2
  adb devices -l >&2
  exit 1
fi

ANDROID_SERIAL="$SERIAL" ./gradlew installDebug
adb -s "$SERIAL" shell am force-stop "$PACKAGE"
adb -s "$SERIAL" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Launched $PACKAGE on $SERIAL"
