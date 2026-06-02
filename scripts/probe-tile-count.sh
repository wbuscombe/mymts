#!/usr/bin/env bash
set -euo pipefail

# MyMTS tile-count probe (Stage 2 Part C).
#
# For a given N: install fresh APK on the device, launch the soak harness
# with N tiles drawn (cycling) from the helper's status=live channel set,
# wait DURATION seconds, then pull MYMTS_SOAK telemetry + dumpsys meminfo
# and produce a one-line per-tile summary plus an overall PASS/FAIL on
# state-machine health.
#
# This is the apparatus for the "escalating probe" — call it for N=1..6
# and bracket the ceiling.
#
# Standing rule: NEVER touches the unrelated host container.

usage() {
    cat <<EOF
Usage: $0 --device <ip[:port]> --tiles <N> --duration <seconds> [options]

Required:
  --device <ip[:port]>      Onn box address (e.g. <LAN_IP>:5555)
  --tiles <N>               number of tiles to probe (1..16)
  --duration <seconds>      how long to leave the soak running

Options:
  --helper <url>            helper base URL (default: http://<LAN_IP>:8091)
  --run-id <id>             output dir name (default: auto-timestamped)
  --no-install              skip APK install (assume already on device)

Exit status:
  Always 0 — the caller reads the JSON summary to decide.
EOF
    exit 2
}

DEVICE=""
TILES=""
DURATION=""
HELPER="http://<LAN_IP>:8091"
RUN_ID=""
INSTALL=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --tiles) TILES="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        --helper) HELPER="$2"; shift 2 ;;
        --run-id) RUN_ID="$2"; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

[[ -z "$DEVICE" || -z "$TILES" || -z "$DURATION" ]] && usage
[[ "$DEVICE" != *:* ]] && DEVICE="${DEVICE}:5555"
if [[ -z "$RUN_ID" ]]; then
    RUN_ID="probe-$(date +%Y%m%d-%H%M%S)-${TILES}t"
fi

cd "$(dirname "$0")/.."
RUNDIR="docs/findings/runs/$RUN_ID"
mkdir -p "$RUNDIR"

PACKAGE="com.mymts"
ACTIVITY=".MainActivity"

>&2 echo "==> probe: tiles=$TILES duration=${DURATION}s device=$DEVICE helper=$HELPER"

>&2 echo "==> fetching helper-resolved live channels"
LIVE_URLS=()
LIVE_SLUGS=()
LIVE_LABELS=()
while IFS=$'\t' read -r slug label url; do
    LIVE_SLUGS+=("$slug")
    LIVE_LABELS+=("$label")
    LIVE_URLS+=("$url")
done < <(curl -fsS "$HELPER/api/channels" | python3 -c "
import json, sys
for c in json.load(sys.stdin)['channels']:
    if c['status'] == 'live':
        print(f\"{c['slug']}\t{c['label']}\t{c['current_url']}\")
")
N_LIVE=${#LIVE_URLS[@]}
if [[ $N_LIVE -eq 0 ]]; then
    >&2 echo "ERROR: helper has zero live channels — cannot probe"
    exit 1
fi
>&2 echo "    helper has $N_LIVE live channel(s); cycling to fill $TILES tile(s)"

# Build comma-separated lists, cycling the live pool to fill N tiles.
URLS_CSV=""
LABELS_CSV=""
IDS_CSV=""
for (( i=0; i<TILES; i++ )); do
    j=$(( i % N_LIVE ))
    sep=""
    [[ $i -gt 0 ]] && sep=","
    URLS_CSV="${URLS_CSV}${sep}${LIVE_URLS[$j]}"
    LABELS_CSV="${LABELS_CSV}${sep}${LIVE_LABELS[$j]}"
    IDS_CSV="${IDS_CSV}${sep}${LIVE_SLUGS[$j]}-${i}"
done

if [[ "$INSTALL" == "1" ]]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$APK_PATH" ]] || { >&2 echo "ERROR: APK not found at $APK_PATH"; exit 1; }
    >&2 echo "==> installing $APK_PATH"
    adb -s "$DEVICE" install -r "$APK_PATH" >/dev/null
fi

>&2 echo "==> capturing device facts"
adb -s "$DEVICE" shell "
    getprop ro.product.model
    getprop ro.product.cpu.abi
    getprop ro.build.version.release
    cat /proc/meminfo | head -3
" > "$RUNDIR/device.txt"

cat > "$RUNDIR/meta.json" <<EOF
{
  "run_id": "$RUN_ID",
  "device": "$DEVICE",
  "tiles": $TILES,
  "duration_seconds": $DURATION,
  "helper": "$HELPER",
  "live_channels_in_pool": $N_LIVE,
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

>&2 echo "==> stopping prior instance + clearing logcat"
adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null
adb -s "$DEVICE" shell logcat -c

>&2 echo "==> launching probe: $TILES tile(s)"
adb -s "$DEVICE" shell am start \
    -n "$PACKAGE/$ACTIVITY" \
    --es mode soak \
    --es urls "'$URLS_CSV'" \
    --es labels "'$LABELS_CSV'" \
    --es ids "'$IDS_CSV'" >/dev/null

# Background memory sampler — every 60s.
(
    echo "timestamp,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb" > "$RUNDIR/meminfo.csv"
    while sleep 60; do
        TS="$(date '+%Y-%m-%d %H:%M:%S')"
        MEM="$(adb -s "$DEVICE" shell dumpsys -t 30 meminfo "$PACKAGE" 2>/dev/null || true)"
        if echo "$MEM" | grep -q "TOTAL PSS"; then
            PSS=$(echo "$MEM" | grep "TOTAL PSS:" | awk '{print $3}')
            JAVA=$(echo "$MEM" | grep "Java Heap:" | awk '{print $3}' | head -1)
            NATIVE=$(echo "$MEM" | grep "Native Heap:" | awk '{print $3}' | head -1)
            GRAPHICS=$(echo "$MEM" | grep "Graphics:" | awk '{print $2}' | head -1)
            echo "$TS,${PSS:-0},${JAVA:-0},${NATIVE:-0},${GRAPHICS:-0}" >> "$RUNDIR/meminfo.csv"
        fi
    done
) &
MEM_PID=$!

sleep "$DURATION"

kill "$MEM_PID" 2>/dev/null || true

>&2 echo "==> pulling MYMTS_SOAK events"
adb -s "$DEVICE" logcat -d -s MYMTS_SOAK > "$RUNDIR/events.log" 2>/dev/null

>&2 echo "==> stopping app"
adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null

# ---- Per-tile state-machine summary -------------------------------------
python3 - "$RUNDIR" "$TILES" <<'PY' > "$RUNDIR/summary.json"
import json, re, sys
from collections import defaultdict, Counter
from pathlib import Path

rundir = Path(sys.argv[1])
tiles_requested = int(sys.argv[2])

events_path = rundir / "events.log"
text = events_path.read_text() if events_path.exists() else ""

EVENT_RE = re.compile(r"EV=(?P<name>\w+)\|(?P<rest>.*)$")
KV_RE = re.compile(r"(\w+)=([^|]+)")

per_tile = defaultdict(lambda: {
    "tile_ready": 0, "dropped_total": 0, "errors": Counter(),
    "state_transitions": Counter(), "recovery_strikes": Counter(),
    "dead": False, "dead_attempts": 0,
    "decoder": None,
    "first_ready_ts_ms": None, "last_ready_ts_ms": None,
    "last_beat": None,
})
tile_mounted = []

for line in text.splitlines():
    m = EVENT_RE.search(line)
    if not m: continue
    name = m.group("name")
    fields = dict(KV_RE.findall(m.group("rest")))
    tid = fields.get("id")
    if not tid: continue
    if name == "TILE_MOUNT":
        tile_mounted.append(tid)
    elif name == "TILE_READY":
        per_tile[tid]["tile_ready"] += 1
        ts = int(fields.get("ts_ms", "0"))
        if per_tile[tid]["first_ready_ts_ms"] is None:
            per_tile[tid]["first_ready_ts_ms"] = ts
        per_tile[tid]["last_ready_ts_ms"] = ts
    elif name == "DROPPED":
        per_tile[tid]["dropped_total"] += int(fields.get("dropped", "0"))
    elif name == "ERROR":
        per_tile[tid]["errors"][fields.get("code", "?")] += 1
    elif name == "DECODER":
        per_tile[tid]["decoder"] = fields.get("decoder")
    elif name == "STATE":
        per_tile[tid]["state_transitions"][fields.get("to", "?")] += 1
    elif name == "RECOVERY":
        per_tile[tid]["recovery_strikes"][fields.get("kind", "?")] += 1
    elif name == "DEAD":
        per_tile[tid]["dead"] = True
        per_tile[tid]["dead_attempts"] = int(fields.get("attempts", "0"))
    elif name == "BEAT":
        per_tile[tid]["last_beat"] = {
            "state": fields.get("state"),
            "playing": fields.get("playing"),
            "dropped": int(fields.get("dropped", "0")),
            "last_frame_age_ms": int(fields.get("last_frame_age_ms", "-1")),
        }

# Tiles that mounted but never produced a beat → still report.
tiles_observed = set(per_tile.keys()) | set(tile_mounted)
tiles_with_first_ready = sum(1 for v in per_tile.values() if v["tile_ready"] > 0)
tiles_dead = sum(1 for v in per_tile.values() if v["dead"])
tiles_live_at_end = sum(
    1 for v in per_tile.values()
    if v["last_beat"] and v["last_beat"]["state"] == "LIVE"
)

# Meminfo summary
import csv, statistics
mem_path = rundir / "meminfo.csv"
mem = {"samples": 0}
if mem_path.exists():
    pss = []
    with mem_path.open() as f:
        for row in csv.DictReader(f):
            try: pss.append(int(row["total_pss_kb"]))
            except (KeyError, ValueError): pass
    if pss:
        mem = {
            "samples": len(pss),
            "pss_first_mb": round(pss[0]/1024, 1),
            "pss_last_mb": round(pss[-1]/1024, 1),
            "pss_min_mb": round(min(pss)/1024, 1),
            "pss_max_mb": round(max(pss)/1024, 1),
            "pss_median_mb": round(statistics.median(pss)/1024, 1),
        }

# Verdict shape (the operator decides on PASS/FAIL after reading):
verdict = {
    "tiles_requested": tiles_requested,
    "tiles_mounted": len(tile_mounted),
    "tiles_with_first_ready": tiles_with_first_ready,
    "tiles_live_at_end": tiles_live_at_end,
    "tiles_dead_at_end": tiles_dead,
    "all_reached_live": tiles_with_first_ready == tiles_requested,
    "all_live_at_end": tiles_live_at_end == tiles_requested,
}

print(json.dumps({
    "verdict": verdict,
    "memory": mem,
    "per_tile": {tid: {
        "decoder": v["decoder"],
        "tile_ready_count": v["tile_ready"],
        "dropped_total": v["dropped_total"],
        "errors": dict(v["errors"]),
        "state_transitions": dict(v["state_transitions"]),
        "recovery_strikes": dict(v["recovery_strikes"]),
        "dead": v["dead"],
        "dead_attempts": v["dead_attempts"],
        "final_state": (v["last_beat"] or {}).get("state"),
        "final_frame_age_ms": (v["last_beat"] or {}).get("last_frame_age_ms"),
    } for tid, v in sorted(per_tile.items())},
}, indent=2, default=str))
PY

>&2 echo "==> summary -> $RUNDIR/summary.json"
cat "$RUNDIR/summary.json"
