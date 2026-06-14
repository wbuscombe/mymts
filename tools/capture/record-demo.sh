#!/usr/bin/env bash
# tools/capture/record-demo.sh — one-command, bulletproof scrcpy recording of the
# MyMTS wall on the .92 Onn box.
#
# scrcpy (free, open-source, Genymobile) captures the device FRAMEBUFFER over the
# EXISTING adb connection — the same transport the deploy uses. It READS the
# screen only: no deploy, no install, no app change (it pushes an ephemeral
# scrcpy-server to /data/local/tmp that it removes on exit; nothing persists).
#
# The SCRIPT is bulletproof (fail-fast preflight on every precondition with an
# actionable message). The PERFORMANCE is inherently manual: YOU drive the wall
# (the physical remote, or the scrcpy mirror window) while it records. See
# tools/capture/README.md for the recommended shot list.
#
# Conforms to AGENTS.md: hard-targets the configured device serial via `-s`
# (never falls through to the .182/.158 boxes — the wrong-box lesson), does ONE
# foreground adb op at a time, and NEVER runs `adb kill-server` (that would
# disrupt the other Onn boxes) and never backgrounds adb. PIA is never touched.
#
# Re-runnable (idempotent): each run records to a new timestamped file.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"

# The real device serial lives in the gitignored scripts/deploy.local.env (the
# same MYMTS_DEPLOY_DEVICE the deploy uses) — it stays OUT of git. lib-adb.sh
# gives us adb_reconnect() (device-specific; never kill-server).
# shellcheck source=/dev/null
[[ -f "$REPO_ROOT/scripts/deploy.local.env" ]] && source "$REPO_ROOT/scripts/deploy.local.env"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/lib-adb.sh"

# --- defaults (override via the flags below, or the RECORD_* env vars) ---
DEVICE="${MYMTS_DEPLOY_DEVICE:-192.0.2.10:5555}"  # placeholder → the guard refuses it
CODEC="${RECORD_CODEC:-h264}"        # h264 (broad compatibility) | h265 (better quality, smaller)
FORMAT="${RECORD_FORMAT:-mkv}"       # mkv (survives a Ctrl-C stop) | mp4 (universal, but a
                                     #   killed mp4 can corrupt — see README)
BITRATE="${RECORD_BITRATE:-16M}"     # high quality
FPS="${RECORD_FPS:-60}"
MAXSIZE="${RECORD_MAXSIZE:-1920}"    # cap the longest side (0 = native res, larger files)
AUDIO=0                              # default off (the wall is visual; off is simpler/robust)
CONTROL=1                            # 1 = the scrcpy window can drive the box; 0 = pure recorder
WINDOW=1                             # 1 = show the mirror window; 0 = headless record

die() { echo "FATAL: $*" >&2; exit 1; }

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Records the MyMTS wall on the configured device (MYMTS_DEPLOY_DEVICE, the .92
box) to tools/capture/output/. You perform the walkthrough while it records;
stop with Ctrl-C (or by closing the scrcpy window).

Options:
  --device <ip:port>   Target serial (default: MYMTS_DEPLOY_DEVICE from
                       scripts/deploy.local.env). Hard-targeted via scrcpy -s.
  --h265               Use H.265 (better quality / smaller) instead of H.264.
  --mp4                Record .mp4 instead of .mkv (universal, but a Ctrl-C'd
                       mp4 can corrupt; mkv is the robust default).
  --bitrate <rate>     Video bitrate (default: $BITRATE, e.g. 8M / 24M).
  --fps <n>            Max fps (default: $FPS).
  --native             Don't cap resolution (record at native device res).
  --max-size <px>      Cap the longest side (default: $MAXSIZE; 0 = native).
  --audio              Capture device audio too (default: off).
  --no-control         Pure recorder — the scrcpy window can't drive the box
                       (use when driving with the physical remote).
  --no-window          Headless record (no mirror window shown).
  -h, --help           This help.

Env knobs: RECORD_CODEC RECORD_FORMAT RECORD_BITRATE RECORD_FPS RECORD_MAXSIZE
           RECORD_VIDEO_BUFFER (ms, smooths Wi-Fi jitter — see README).
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)   DEVICE="$2"; shift 2 ;;
        --h265)     CODEC="h265"; shift ;;
        --mp4)      FORMAT="mp4"; shift ;;
        --bitrate)  BITRATE="$2"; shift 2 ;;
        --fps)      FPS="$2"; shift 2 ;;
        --native)   MAXSIZE=0; shift ;;
        --max-size) MAXSIZE="$2"; shift 2 ;;
        --audio)    AUDIO=1; shift ;;
        --no-control) CONTROL=0; shift ;;
        --no-window)  WINDOW=0; shift ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
    esac
done

# ── Preflight 1: scrcpy installed ──────────────────────────────────────────────
if ! command -v scrcpy >/dev/null 2>&1; then
    {
        echo "FATAL: scrcpy is not installed (it's free + open-source — Genymobile)."
        case "$(uname -s)" in
            Darwin) echo "  Install: brew install scrcpy" ;;
            Linux)  echo "  Install: sudo apt install scrcpy   (or: sudo snap install scrcpy / your distro's package)" ;;
            *)      echo "  Install: see https://github.com/Genymobile/scrcpy" ;;
        esac
        echo "  Then re-run this script."
    } >&2
    exit 1
fi

# ── Preflight 2: scrcpy version (the high-quality flag set is scrcpy 2.0+) ──────
SCRCPY_VER="$(scrcpy --version 2>&1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 || true)"
SCRCPY_MAJOR="${SCRCPY_VER%%.*}"
if [[ -z "$SCRCPY_MAJOR" ]]; then
    echo "WARN: could not parse scrcpy version ('$SCRCPY_VER'); proceeding with the 2.x flag set." >&2
elif (( SCRCPY_MAJOR < 2 )); then
    die "scrcpy $SCRCPY_VER is too old — this script uses the 2.0+ flags (--video-codec / --video-bit-rate / --no-audio).
       Upgrade: brew upgrade scrcpy   (macOS)  |  your distro's package (Linux). scrcpy 2.0+ is recommended."
fi

# ── Preflight 3: the device serial is REAL, not the scrubbed placeholder ────────
# (the wrong-box guard — a MyMTS adb op must never fall through to .182/.158.)
if [[ -z "$DEVICE" || "$DEVICE" == 192.0.2.* || "$DEVICE" == *"<"* ]]; then
    {
        echo "FATAL: target device is the placeholder/unset ('$DEVICE')."
        echo "  Set MYMTS_DEPLOY_DEVICE in scripts/deploy.local.env (the .92 box),"
        echo "  or pass --device <ip:port> explicitly."
    } >&2
    exit 2
fi

# ── Preflight 4: adb present + the device connected (ONE foreground reconnect; ──
#    NEVER kill-server — that disrupts the other Onn boxes .182/.158) ───────────
command -v adb >/dev/null 2>&1 \
    || die "adb is not on PATH. Install the Android platform-tools (e.g. brew install android-platform-tools)."

device_connected() { adb devices | awk 'NR>1 {print $1, $2}' | grep -qx "$DEVICE device"; }

if ! device_connected; then
    echo "==> $DEVICE not connected; attempting a device-specific reconnect (never kill-server)…" >&2
    adb connect "$DEVICE" >/dev/null 2>&1 || true   # one foreground op
    sleep 2
    device_connected || adb_reconnect "$DEVICE"      # lib-adb: disconnect+connect THIS device only
fi

if ! device_connected; then
    die "$DEVICE is not connected as 'device' in 'adb devices'.
       The .92 Wi-Fi adb transport may need a device-specific reconnect or a box power-cycle.
       Try:  adb connect $DEVICE     (NEVER 'adb kill-server' — it disrupts the other Onn boxes).
       Or use a USB cable to the box for a rock-solid connection (see tools/capture/README.md)."
fi

# ── Build the recording invocation (hard-targeted via -s "$DEVICE") ────────────
mkdir -p "$OUTPUT_DIR"
OUTPUT="$OUTPUT_DIR/mymts-demo-$(date -u +%Y%m%dT%H%M%SZ).$FORMAT"

SCRCPY_FLAGS=(
    -s "$DEVICE"
    --video-codec="$CODEC"
    --video-bit-rate="$BITRATE"
    --max-fps="$FPS"
    --record="$OUTPUT"
)
if (( MAXSIZE > 0 )); then SCRCPY_FLAGS+=( --max-size="$MAXSIZE" ); fi
if (( AUDIO == 0 ));  then SCRCPY_FLAGS+=( --no-audio ); fi
if (( CONTROL == 0 )); then SCRCPY_FLAGS+=( --no-control ); fi
if (( WINDOW == 0 ));  then SCRCPY_FLAGS+=( --no-playback ); fi
if [[ -n "${RECORD_VIDEO_BUFFER:-}" ]]; then SCRCPY_FLAGS+=( --video-buffer="$RECORD_VIDEO_BUFFER" ); fi

echo "==> Recording the MyMTS wall  (scrcpy ${SCRCPY_VER:-?}, device $DEVICE)"
echo "    codec=$CODEC  bitrate=$BITRATE  fps=$FPS  max-size=$MAXSIZE (0=native)  audio=$( ((AUDIO)) && echo on || echo off )"
echo "    output: $OUTPUT"
echo
echo "    >>> PERFORM THE WALKTHROUGH NOW — see tools/capture/README.md for the shot list."
echo "    >>> Drive it with the physical remote, or click the scrcpy mirror window."
echo "    >>> STOP: press Ctrl-C here, or close the scrcpy window. (Recording finalizes on stop.)"
echo

# Absorb Ctrl-C in THIS script so scrcpy (the child) finalizes the file cleanly
# and we still reach the verify-the-output reminder. bash defers the trap until
# the foreground child returns, so scrcpy gets its own SIGINT and stops first.
trap ':' INT
set +e
scrcpy "${SCRCPY_FLAGS[@]}"
SCRCPY_RC=$?
set -e
trap - INT

echo
if [[ ! -f "$OUTPUT" ]]; then
    die "no output file was produced ($OUTPUT); scrcpy exit code: $SCRCPY_RC.
       If scrcpy printed a device/transport error above, re-check the connection (reconnect guidance above)."
fi
SIZE_BYTES="$(stat -f%z "$OUTPUT" 2>/dev/null || stat -c%s "$OUTPUT" 2>/dev/null || echo 0)"
SIZE_HUMAN="$(du -h "$OUTPUT" 2>/dev/null | cut -f1)"
echo "==> Recording saved: $OUTPUT  (${SIZE_HUMAN:-?}, scrcpy exit $SCRCPY_RC)"
if (( SIZE_BYTES < 102400 )); then
    echo "    ⚠️  WARNING: the file is suspiciously small (<100KB) — the recording may not have captured." >&2
fi

cat <<'NOTE'

⚠️  VERIFY THE RECORDING before relying on it.
    scrcpy captures the device FRAMEBUFFER, so a DRM / hardware-protected video
    tile can record as BLACK. MyMTS plays free news streams (likely fine), but
    DO NOT assume — open the file and confirm the video tiles show a picture:
        macOS:  open <file>        any:  vlc <file>
    A black tile means that channel is protected; the rest of the demo is fine.
NOTE
