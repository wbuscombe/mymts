#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/deploy.local.env" ]] && source "$SCRIPT_DIR/deploy.local.env"

# MyMTS soak harness — host-side runner.
#
# Pushes a debug APK to the Onn 4K, starts the in-app soak mode, then
# collects two streams of telemetry to CSV:
#
#   1. Periodic `dumpsys meminfo` samples (the slow-leak detector).
#   2. The in-app MYMTS_SOAK logcat events (per-tile mounts, decoder
#      inits, dropped frames, errors, heartbeats).
#
# Output goes to docs/findings/runs/<run-id>/ as:
#
#   meta.json           run inputs + device facts
#   meminfo.csv         dumpsys samples
#   events.log          raw MYMTS_SOAK logcat (one line per event)
#
# After the run completes (Ctrl+C or duration expiry), the operator runs
#   scripts/parse-soak-log.py docs/findings/runs/<run-id>/
# to produce a summary the finding doc can quote.
#
# Hard rule: this script never touches unrelated services/containers
# sharing the helper's host; the helper runs isolated on its own network.
# It only talks to the Onn box over adb.

usage() {
    cat <<EOF
Usage: $0 --device <ip[:port]> --tiles <N> [options]

Required:
  --device <ip[:port]>      e.g. 192.0.2.10 or 192.0.2.10:5555
                            (default set in scripts/deploy.local.env)
  --tiles <N>               number of simultaneous tiles (1-16)

Options:
  --pool <live|stable>      fixture pool (default: live)
  --resolution <hint>       resolution hint string (default: auto)
  --duration <seconds>      run for N seconds then exit (default: 0 = run until Ctrl+C)
  --meminfo-interval <s>    seconds between meminfo samples (default: 60)
  --apk <path>              APK to install (default: app/build/outputs/apk/debug/app-debug.apk)
  --run-id <id>             results dir name (default: auto-timestamped)
  --no-install              skip APK install; just start + collect

Notes:
  * For the gate-clearing soak, run for at least 4 hours (--duration 14400)
    and pick the LIVE pool, since slow leaks only manifest under sustained
    real-stream load.
  * For method validation, --duration 600 with --pool stable is enough.
EOF
    exit 2
}

DEVICE="${MYMTS_DEPLOY_DEVICE:-}"
TILES=""
POOL="live"
RESOLUTION="auto"
DURATION=0
MEMINFO_INTERVAL=60
APK="app/build/outputs/apk/debug/app-debug.apk"
RUN_ID=""
INSTALL=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --tiles) TILES="$2"; shift 2 ;;
        --pool) POOL="$2"; shift 2 ;;
        --resolution) RESOLUTION="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        --meminfo-interval) MEMINFO_INTERVAL="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --run-id) RUN_ID="$2"; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

[[ -z "$DEVICE" ]] && { echo "missing --device" >&2; usage; }
[[ -z "$TILES" ]] && { echo "missing --tiles" >&2; usage; }
[[ "$DEVICE" != *:* ]] && DEVICE="${DEVICE}:5555"

if [[ -z "$RUN_ID" ]]; then
    RUN_ID="$(date +%Y%m%d-%H%M%S)-${TILES}t-${POOL}"
fi

RUNDIR="docs/findings/runs/$RUN_ID"
mkdir -p "$RUNDIR"

PACKAGE="com.mymts"
ACTIVITY=".MainActivity"

echo "==> connecting to $DEVICE"
adb connect "$DEVICE" >/dev/null

if [[ "$INSTALL" == "1" ]]; then
    [[ -f "$APK" ]] || { echo "APK not found: $APK (build it first or pass --apk)" >&2; exit 1; }
    echo "==> installing $APK"
    adb -s "$DEVICE" install -r "$APK"
fi

echo "==> capturing device facts"
adb -s "$DEVICE" shell "
    getprop ro.product.model
    getprop ro.product.device
    getprop ro.product.cpu.abi
    getprop ro.build.version.release
    getprop ro.build.version.sdk
    getprop ro.hardware
    getprop ro.soc.model
    getprop ro.soc.manufacturer
    cat /proc/meminfo | head -3
" > "$RUNDIR/device.txt"

cat > "$RUNDIR/meta.json" <<EOF
{
  "run_id": "$RUN_ID",
  "device": "$DEVICE",
  "tiles": $TILES,
  "pool": "$POOL",
  "resolution_hint": "$RESOLUTION",
  "duration_seconds": $DURATION,
  "meminfo_interval_seconds": $MEMINFO_INTERVAL,
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo "==> stopping prior instance + clearing logcat"
adb -s "$DEVICE" shell am force-stop "$PACKAGE" || true
adb -s "$DEVICE" shell logcat -c

echo "==> launching soak: tiles=$TILES pool=$POOL resolution=$RESOLUTION"
adb -s "$DEVICE" shell am start \
    -n "$PACKAGE/$ACTIVITY" \
    --es mode soak \
    --ei tiles "$TILES" \
    --es pool "$POOL" \
    --es resolution "$RESOLUTION" >/dev/null

echo "==> streaming events to $RUNDIR/events.log"
adb -s "$DEVICE" logcat -s MYMTS_SOAK >> "$RUNDIR/events.log" &
LOG_PID=$!

cleanup() {
    echo "==> cleanup"
    kill "$LOG_PID" 2>/dev/null || true
    kill "$MEM_PID" 2>/dev/null || true
    jobs -p | xargs -I{} kill {} 2>/dev/null || true
    {
        echo "{"
        echo "  \"ended_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\""
        echo "}"
    } > "$RUNDIR/end.json"
    echo "==> results in $RUNDIR"
}
trap cleanup EXIT INT TERM

(
    echo "timestamp,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,code_kb,stack_kb,system_kb" > "$RUNDIR/meminfo.csv"
    while true; do
        TS="$(date '+%Y-%m-%d %H:%M:%S')"
        # Retry dumpsys up to 3 times. Under heavy decoder load (6 active
        # ExoPlayers + Surface composition on a 2GB Amlogic box), dumpsys's
        # default 10s timeout can be exceeded — that doesn't mean the app
        # died, it just means binder/system_server is contended. We use
        # `dumpsys -t 30` to extend the per-call timeout and retry on a
        # short backoff before deciding we have nothing.
        MEM=""
        for attempt in 1 2 3; do
            MEM="$(adb -s "$DEVICE" shell dumpsys -t 30 meminfo "$PACKAGE" 2>/dev/null || true)"
            if echo "$MEM" | grep -q "TOTAL PSS"; then break; fi
            sleep 2
        done
        # Liveness fallback: even if dumpsys consistently times out, check
        # whether the process is genuinely gone before logging "app died".
        if ! echo "$MEM" | grep -q "TOTAL PSS"; then
            ALIVE="$(adb -s "$DEVICE" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
            if [[ -n "$ALIVE" ]]; then
                echo "[$TS] meminfo timed out 3× (pid $ALIVE still alive); sample skipped"
                sleep "$INTERVAL"
                continue
            fi
        fi
        if echo "$MEM" | grep -q "TOTAL PSS"; then
            PSS=$(echo "$MEM" | grep "TOTAL PSS:" | awk '{print $3}')
            JAVA=$(echo "$MEM" | grep "Java Heap:" | awk '{print $3}' | head -1)
            NATIVE=$(echo "$MEM" | grep "Native Heap:" | awk '{print $3}' | head -1)
            GRAPHICS=$(echo "$MEM" | grep "Graphics:" | awk '{print $2}' | head -1)
            CODE=$(echo "$MEM" | grep "Code:" | awk '{print $2}' | head -1)
            STACK=$(echo "$MEM" | grep "Stack:" | awk '{print $2}' | head -1)
            SYSTEM=$(echo "$MEM" | grep "System:" | awk '{print $2}' | head -1)
            echo "$TS,${PSS:-0},${JAVA:-0},${NATIVE:-0},${GRAPHICS:-0},${CODE:-0},${STACK:-0},${SYSTEM:-0}" >> "$RUNDIR/meminfo.csv"
            MB=$(awk "BEGIN { printf \"%.1f\", ${PSS:-0} / 1024 }")
            echo "[$TS] pss=${MB}MB java=${JAVA:-0}KB native=${NATIVE:-0}KB graphics=${GRAPHICS:-0}KB"
        else
            echo "[$TS] (could not read meminfo; app died?)"
        fi
        sleep "$MEMINFO_INTERVAL"
    done
) &
MEM_PID=$!

if [[ "$DURATION" -gt 0 ]]; then
    echo "==> running for $DURATION seconds (Ctrl+C aborts early)"
    sleep "$DURATION"
else
    echo "==> running until Ctrl+C"
    wait "$MEM_PID"
fi
