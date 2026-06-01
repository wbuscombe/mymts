#!/usr/bin/env bash
set -euo pipefail

# MyMTS fixture validator — solo-test a single HLS URL on the Onn box.
#
# Why this exists: a HEAD-200 from the developer's Mac doesn't prove a stream
# can play from the box's network/CDN path. The first long soak burned 24 h
# on fixtures that HEAD'd fine but never produced a frame on .182. This
# validator launches the soak harness against ONE stream at tiles=1 via an
# ad-hoc-fixture intent extra, sleeps for the requested duration, then
# summarizes the relevant MYMTS_SOAK telemetry so the caller can decide
# pass/fail.
#
# Output is one line of CSV plus, on --verbose, the full event tail.

usage() {
    cat <<EOF
Usage: $0 --device <ip[:port]> --id <id> --label <label> --url <url> [options]

Required:
  --device <ip[:port]>      e.g. <LAN_IP>:5555
  --id <id>                 short slug, used as the tile id
  --label <label>           human-friendly label (shown on screen)
  --url <url>               https://... HLS .m3u8

Options:
  --duration <seconds>      how long to play before sampling (default: 90)
  --verbose                 dump the full MYMTS_SOAK tail before the CSV row
  --leave-running           do not force-stop the app after sampling (use
                            sparingly; the next validator run will stop it)

Exit code is 0 regardless; the caller reads the CSV to judge pass/fail.

CSV columns (one row to stdout, header on stderr):
  id,duration_s,tile_ready_count,decoder,dropped_total,error_count,
  last_frame_age_ms,final_state
EOF
    exit 2
}

DEVICE=""
ID=""
LABEL=""
URL=""
DURATION=90
VERBOSE=0
LEAVE_RUNNING=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --id) ID="$2"; shift 2 ;;
        --label) LABEL="$2"; shift 2 ;;
        --url) URL="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        --verbose) VERBOSE=1; shift ;;
        --leave-running) LEAVE_RUNNING=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

[[ -z "$DEVICE" || -z "$ID" || -z "$LABEL" || -z "$URL" ]] && usage
[[ "$DEVICE" != *:* ]] && DEVICE="${DEVICE}:5555"

PACKAGE="com.mymts"
ACTIVITY=".MainActivity"

>&2 echo "validate: id=$ID label=$LABEL url=${URL:0:60}... device=$DEVICE duration=${DURATION}s"

adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null
adb -s "$DEVICE" shell logcat -c

# Quote arguments for `am start --es`. Android extras don't need shell
# escaping the way the host does, but URLs contain & and other shell-special
# chars; passing them through adb shell requires the URL be on the device
# command-line as one argv element. adb shell glues the args back into a
# command string, so we quote each piece.
adb -s "$DEVICE" shell am start \
    -n "$PACKAGE/$ACTIVITY" \
    --es mode soak \
    --es id "'$ID'" \
    --es label "'$LABEL'" \
    --es url "'$URL'" >/dev/null

sleep "$DURATION"

# Pull the events for this id only.
EVENTS=$(adb -s "$DEVICE" logcat -d -s MYMTS_SOAK 2>/dev/null | grep "id=$ID" || true)

# Stream may produce zero events of any kind (DNS fail, immediate 4xx, etc).
# Every grep that filters EVENTS needs `|| true` so pipefail+set-e doesn't
# bail and lose the row — silent streams ARE the failure mode we want
# recorded, not exited on.
TILE_READY=$(echo "$EVENTS" | grep -c "EV=TILE_READY" || true)
DECODER=$(echo "$EVENTS" | { grep "EV=DECODER" || true; } | head -1 | sed -nE 's/.*decoder=([^|]+).*/\1/p')
[[ -z "$DECODER" ]] && DECODER="-"
ERRORS=$(echo "$EVENTS" | grep -c "EV=ERROR" || true)

# Compare first vs last BEAT: position-advance is the real liveness signal,
# not last_frame_age (which is just "time since last variant switch").
BEATS=$(echo "$EVENTS" | { grep "EV=BEAT" || true; })
FIRST_BEAT=$(echo "$BEATS" | head -1)
LAST_BEAT=$(echo "$BEATS" | tail -1)
LAST_DROPPED=$(echo "$LAST_BEAT" | sed -nE 's/.*dropped=([0-9]+).*/\1/p')
LAST_FRAME_AGE=$(echo "$LAST_BEAT" | sed -nE 's/.*last_frame_age_ms=(-?[0-9]+).*/\1/p')
FINAL_STATE=$(echo "$LAST_BEAT" | sed -nE 's/.*state=([A-Z]+).*/\1/p')
LAST_PLAYING=$(echo "$LAST_BEAT" | sed -nE 's/.*playing=([a-z]+).*/\1/p')
LAST_POS=$(echo "$LAST_BEAT" | sed -nE 's/.*pos_ms=(-?[0-9]+).*/\1/p')
FIRST_POS=$(echo "$FIRST_BEAT" | sed -nE 's/.*pos_ms=(-?[0-9]+).*/\1/p')
[[ -z "$LAST_DROPPED" ]] && LAST_DROPPED=0
[[ -z "$LAST_FRAME_AGE" ]] && LAST_FRAME_AGE=-1
[[ -z "$FINAL_STATE" ]] && FINAL_STATE="UNKNOWN"
[[ -z "$LAST_PLAYING" ]] && LAST_PLAYING="?"
[[ -z "$LAST_POS" ]] && LAST_POS=-1
[[ -z "$FIRST_POS" ]] && FIRST_POS=-1
POS_ADVANCE_MS=$(( LAST_POS - FIRST_POS ))

if [[ "$VERBOSE" == "1" ]]; then
    >&2 echo "---- events tail for $ID ----"
    >&2 echo "$EVENTS" | tail -20
    >&2 echo "----"
fi

# CSV header on stderr (so multiple runs can be appended cleanly into a file).
>&2 echo "id,duration_s,tile_ready,decoder,dropped,errors,last_frame_age_ms,state,playing,pos_advance_ms"
echo "$ID,$DURATION,$TILE_READY,$DECODER,$LAST_DROPPED,$ERRORS,$LAST_FRAME_AGE,$FINAL_STATE,$LAST_PLAYING,$POS_ADVANCE_MS"

if [[ "$LEAVE_RUNNING" != "1" ]]; then
    adb -s "$DEVICE" shell am force-stop "$PACKAGE" >/dev/null
fi
