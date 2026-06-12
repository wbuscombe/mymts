#!/usr/bin/env bash
set -euo pipefail

# MyMTS deploy — dev sideload (debug APK). For the production signed-install
# update path with health-gate + rollback, use scripts/deploy-app.sh.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The adb invariant (push + byte-verify + pm install; never streamed install).
source "$SCRIPT_DIR/lib-adb.sh"

DEVICE="${1:?Usage: deploy.sh <device-ip[:port]>}"
[[ "$DEVICE" != *:* ]] && DEVICE="${DEVICE}:5555"

PACKAGE="com.mymts"
ACTIVITY=".MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> assembling debug APK"
./gradlew :app:assembleDebug

echo "==> connecting to $DEVICE"
adb connect "$DEVICE"

echo "==> installing (byte-verified push + pm install, never streamed)"
adb_install_verified "$DEVICE" "$APK_PATH" "$PACKAGE"

echo "==> launching"
adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY"

echo "==> done. Running on $DEVICE."
