#!/usr/bin/env bash
set -euo pipefail

# MyMTS deploy — Stage 1 dev sideload only. NOT the production update path.
# Stage 6 will replace this with the signed-install update story that
# satisfies Op Bar B1/B2 (never bricks, always a way back).

DEVICE="${1:?Usage: deploy.sh <device-ip[:port]>}"
[[ "$DEVICE" != *:* ]] && DEVICE="${DEVICE}:5555"

PACKAGE="com.mymts"
ACTIVITY=".MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> assembling debug APK"
./gradlew :app:assembleDebug

echo "==> connecting to $DEVICE"
adb connect "$DEVICE"

echo "==> installing"
adb -s "$DEVICE" install -r "$APK_PATH"

echo "==> launching"
adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY"

echo "==> done. Running on $DEVICE."
