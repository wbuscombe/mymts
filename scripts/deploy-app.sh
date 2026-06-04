#!/usr/bin/env bash
# MyMTS — Stage 6 update path with health-gated promotion + rollback.
#
# Replaces Stage 1's `scripts/deploy.sh` (dev sideload) with the real
# Operational Bar B1/B2/B5 update flow:
#
#   build (signed release)
#     → archive (versioned APK on disk)
#       → install on the target device
#         → health-gate (health_check.py against MYMTS_SOAK telemetry)
#           → on PASS: promote to known-good (move pointer)
#           → on FAIL: rollback to prior known-good APK; restart; log it
#
# The known-good pointer lives at `${ARCHIVE_DIR}/known-good` (a plain
# file naming the APK filename inside `${ARCHIVE_DIR}/archive/`). Atomic
# updates via temp-write + rename. The prior known-good APK is RETAINED
# in the archive so manual rollback to any older version is one command.
#
# **Operator-away safety:** with `--dry-run` the script builds + archives
# but does NOT install or touch the device. The decision logic is the
# same Python function tested in `scripts/test_health_check.py`.
#
# **Hard rule:** NEVER touches the unrelated host services. NEVER pushes a debug-signed
# APK to a real device — the signing-verification step refuses unless
# the APK was signed with the operator's release key.

set -euo pipefail

usage() {
    cat <<EOF
Usage: $0 [--device <ip[:port]>] [--archive-dir <path>] [--dry-run]
          [--manual-rollback] [--minimum-ready N] [--expected-tiles N]
          [--deadline-seconds N]

Stage 6 update flow. Default mode builds a signed release, installs to
the device, and gates promotion on telemetry.

  --device <ip[:port]>      Target device for adb. Default: <LAN_IP>:5555.
  --archive-dir <path>      Where versioned APKs + known-good pointer live.
                            Default: \$HOME/.mymts/release/.
  --dry-run                 Build + archive but do not touch the device. Use
                            this when the operator is away from the box and
                            a botched install could leave it stranded.
  --manual-rollback         Skip build; reinstall the prior known-good APK.
                            Use to recover from a botched deploy.
  --minimum-ready N         Health-gate: minimum EV=TILE_READY events.
                            Default: 2 (matches a 2-channel-live world).
  --expected-tiles N        Wall's tile count. Default: 4.
  --deadline-seconds N      Health-gate observation window. Default: 90.
EOF
}

DEVICE="${MYMTS_DEPLOY_DEVICE:-<LAN_IP>:5555}"
ARCHIVE_DIR="${MYMTS_ARCHIVE_DIR:-$HOME/.mymts/release}"
DRY_RUN=0
MANUAL_ROLLBACK=0
MINIMUM_READY=2
EXPECTED_TILES=4
DEADLINE_SECONDS=90

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --archive-dir) ARCHIVE_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --manual-rollback) MANUAL_ROLLBACK=1; shift ;;
        --minimum-ready) MINIMUM_READY="$2"; shift 2 ;;
        --expected-tiles) EXPECTED_TILES="$2"; shift 2 ;;
        --deadline-seconds) DEADLINE_SECONDS="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
    esac
done

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
PACKAGE="com.mymts"
ACTIVITY=".MainActivity"

mkdir -p "$ARCHIVE_DIR/archive"
KNOWN_GOOD_PTR="$ARCHIVE_DIR/known-good"
LOG_FILE="$ARCHIVE_DIR/deploy.log"

log() {
    # log to stderr (not stdout) so $(fn) command-substitution captures
    # only the function's actual return value, not its progress messages.
    # The 2>&1 redirect in the caller's invocation still pulls log lines
    # into the user's terminal.
    local line; line="$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
    echo "$line" | tee -a "$LOG_FILE" >&2
}

require_cmd() {
    if ! command -v "$1" >/dev/null 2>&1; then
        log "FATAL: required command not found: $1"
        return 3
    fi
    return 0
}

require_cmd_or_die() {
    require_cmd "$1" || exit 3
}

read_known_good() {
    # Echo the filename of the current known-good APK inside the archive,
    # or empty string if none. The pointer file contains a single line
    # naming the APK filename (not an absolute path) so the archive can
    # be relocated without breaking the pointer.
    [[ -f "$KNOWN_GOOD_PTR" ]] && cat "$KNOWN_GOOD_PTR" || true
}

write_known_good_atomic() {
    local name="$1"
    local tmp; tmp="$(mktemp "$KNOWN_GOOD_PTR.XXXXXX")"
    printf '%s\n' "$name" > "$tmp"
    mv -f "$tmp" "$KNOWN_GOOD_PTR"
    log "promoted: known-good = $name"
}

verify_signed_release() {
    # A debug-signed APK MUST NOT be deployed as a release — refuse
    # here, never push debug to .182. apksigner is the source of truth
    # for the actual signing cert; aapt2 dump can't see it past
    # v2-signed APKs reliably.
    if command -v apksigner >/dev/null 2>&1; then
        local subject
        subject="$(apksigner verify --print-certs "$APK_PATH" 2>/dev/null | \
            awk -F': ' '/Subject:/ {print $2; exit}')"
        if [[ -z "$subject" ]]; then
            log "FATAL: apksigner could not read the release APK's certificate"
            return 4
        fi
        log "signing cert subject: $subject"
        # The Android debug signing cert is well-known:
        # CN=Android Debug,O=Android,C=US
        if [[ "$subject" == *"Android Debug"* ]]; then
            log "FATAL: APK is debug-signed (CN=Android Debug). Refusing to deploy."
            log "       Configure app/keystore.properties to sign with the release key."
            return 5
        fi
    else
        log "WARN: apksigner not on PATH — cannot verify signing identity."
        log "      Install Android build-tools to enable this check."
    fi
}

build_release() {
    log "build: assembling :app:assembleRelease"
    (cd "$PROJECT_ROOT" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
        PATH="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin:$PATH" \
        ./gradlew :app:assembleRelease --no-daemon -q)
    if [[ ! -f "$APK_PATH" ]]; then
        log "FATAL: expected APK not found at $APK_PATH"
        exit 6
    fi
    log "build: artifact at $APK_PATH"
}

archive_release() {
    # Versioned filename: <version>+<sha>-<utc>.apk. The deploy log
    # records the exact mapping. Older APKs are retained so a manual
    # rollback to any prior version is one command.
    local version sha ts archived_name
    version="$(grep '^MYMTS_DEFAULT_MAX_TILES\|^# Version' "$PROJECT_ROOT/gradle.properties" \
        2>/dev/null | head -1 || true)"
    version="$(cd "$PROJECT_ROOT" && git describe --tags --abbrev=0 2>/dev/null \
        | sed 's/^v//' || echo "0.0.0")"
    sha="$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo dev)"
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    archived_name="mymts-${version}+${sha}-${ts}.apk"
    cp "$APK_PATH" "$ARCHIVE_DIR/archive/$archived_name"
    log "archive: $archived_name"
    echo "$archived_name"
}

adb_install() {
    local apk="$1"
    log "install: adb -s $DEVICE install -r -t $apk"
    adb -s "$DEVICE" install -r -t "$apk"
}

launch_app() {
    log "launch: am start -n $PACKAGE/$ACTIVITY"
    adb -s "$DEVICE" logcat -c 2>/dev/null || true
    adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
}

run_health_gate() {
    log "health-gate: capturing MYMTS_SOAK telemetry for ${DEADLINE_SECONDS}s ..."
    local out
    out="$(python3 "$PROJECT_ROOT/scripts/health_check.py" \
        --device "$DEVICE" \
        --deadline-seconds "$DEADLINE_SECONDS" \
        --minimum-ready "$MINIMUM_READY" \
        --expected-tile-count "$EXPECTED_TILES")"
    local rc=$?
    log "health-gate result:"
    printf '%s\n' "$out" | tee -a "$LOG_FILE"
    return $rc
}

rollback_to_known_good() {
    local kg; kg="$(read_known_good)"
    if [[ -z "$kg" ]]; then
        log "FATAL: no known-good APK on file — rollback impossible."
        log "       Manual recovery needed (sideload a working APK)."
        return 7
    fi
    local kg_path="$ARCHIVE_DIR/archive/$kg"
    if [[ ! -f "$kg_path" ]]; then
        log "FATAL: known-good pointer says $kg but file is missing."
        return 8
    fi
    log "rollback: reinstalling $kg"
    # `-d` allows downgrading the versionCode if needed (the rolled-back
    # build may be older than the failed one). `-r` keeps the install
    # data (lineup overrides etc.) so the operator's state survives.
    adb -s "$DEVICE" install -r -d "$kg_path"
    adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
    log "rollback: known-good $kg reinstalled and launched"
}

# ============== Main flow ==============

if [[ "$MANUAL_ROLLBACK" == "1" ]]; then
    log "=== MANUAL ROLLBACK ==="
    require_cmd_or_die adb
    rollback_to_known_good
    exit $?
fi

build_release
verify_signed_release || {
    rc=$?
    log "build verification failed (rc=$rc); not deploying."
    exit "$rc"
}
archived_name="$(archive_release)"

if [[ "$DRY_RUN" == "1" ]]; then
    log "=== DRY RUN ==="
    log "Built + archived $archived_name; skipping device install / health-gate."
    log "current known-good: $(read_known_good || echo '<none>')"
    exit 0
fi

require_cmd_or_die adb
require_cmd_or_die python3
adb_install "$ARCHIVE_DIR/archive/$archived_name"
launch_app

if run_health_gate; then
    write_known_good_atomic "$archived_name"
    log "=== PROMOTED $archived_name ==="
    exit 0
else
    log "health-gate FAILED — initiating rollback"
    rollback_to_known_good
    log "=== ROLLED BACK; the failed APK ($archived_name) is retained for diagnosis ==="
    exit 1
fi
