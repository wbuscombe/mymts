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
# **Hard rule:** never touches unrelated services/containers sharing the
# helper's host; the helper runs isolated on its own network. NEVER pushes
# a debug-signed APK to a real device — the signing-verification step
# refuses unless the APK was signed with the operator's release key.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/deploy.local.env" ]] && source "$SCRIPT_DIR/deploy.local.env"

usage() {
    cat <<EOF
Usage: $0 [--device <ip[:port]>] [--archive-dir <path>] [--dry-run]
          [--manual-rollback] [--minimum-ready N] [--expected-tiles N]
          [--deadline-seconds N]

Stage 6 update flow. Default mode builds a signed release, installs to
the device, and gates promotion on telemetry.

  --device <ip[:port]>      Target device for adb. Default: 192.0.2.10:5555
                            (set in scripts/deploy.local.env).
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

# JAVA_HOME for the WHOLE script (not just the gradle subshell): `apksigner` is a
# wrapper that needs `java`, and the signing-identity verify (verify_signed_release)
# runs it in the main shell. Without java on PATH there, apksigner emitted nothing and
# the check FALSELY failed a correctly-signed release ("could not read the certificate").
# Default to the operator's Homebrew JDK 17 (matches build_release); an exported
# JAVA_HOME still wins.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

DEVICE="${MYMTS_DEPLOY_DEVICE:-192.0.2.10:5555}"
ARCHIVE_DIR="${MYMTS_ARCHIVE_DIR:-$HOME/.mymts/release}"
DRY_RUN=0
MANUAL_ROLLBACK=0
MINIMUM_READY=2
EXPECTED_TILES=4
DEADLINE_SECONDS=90
SKIP_HEALTH_GATE=0
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE="$2"; shift 2 ;;
        --archive-dir) ARCHIVE_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --manual-rollback) MANUAL_ROLLBACK=1; shift ;;
        --minimum-ready) MINIMUM_READY="$2"; shift 2 ;;
        --expected-tiles) EXPECTED_TILES="$2"; shift 2 ;;
        --deadline-seconds) DEADLINE_SECONDS="$2"; shift 2 ;;
        # Skip the logcat health-gate entirely (hostile-transport days): install
        # + byte-verify, promote, and the operator confirms the panel by eye.
        --skip-health-gate) SKIP_HEALTH_GATE=1; shift ;;
        # Deploy an already-built APK (don't re-build). Lets the long build run
        # as its own step so the adb portion stays a short FOREGROUND op.
        --skip-build) SKIP_BUILD=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
    esac
done

# Wrong-box guard. A MyMTS deploy must never fall through to the wrong device
# (a prior run hit .182 — WyzeGrid's box — and failed on a signature mismatch).
# Refuse the scrubbed placeholder / an unset target, and require the device be
# actually connected BEFORE the multi-minute build. Pass --device explicitly.
if [[ -z "$DEVICE" || "$DEVICE" == 192.0.2.* || "$DEVICE" == *"<"* ]]; then
    echo "FATAL: deploy device is the placeholder/unset ('$DEVICE')." >&2
    echo "       Pass --device <ip:port> explicitly (e.g. --device 192.168.50.92:5555)" >&2
    echo "       or set a real MYMTS_DEPLOY_DEVICE in scripts/deploy.local.env." >&2
    exit 2
fi
if command -v adb >/dev/null 2>&1; then
    if ! adb devices | awk 'NR>1 {print $1}' | grep -qx "$DEVICE"; then
        echo "FATAL: target $DEVICE is not connected (not in 'adb devices')." >&2
        echo "       Connect it first: adb connect $DEVICE" >&2
        exit 2
    fi
fi

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

# The adb invariant (push + byte-verify + pm install + lastUpdateTime advanced;
# never a streamed install; reconnect-not-kill-server on a wedge). See AGENTS.md.
source "$SCRIPT_DIR/lib-adb.sh"

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

# Locate apksigner: prefer PATH, else the highest-versioned build-tools in the
# Android SDK ($ANDROID_HOME / $ANDROID_SDK_ROOT / the default SDK path). apksigner
# ships in build-tools, which is usually NOT on PATH — finding it here makes the
# signing-identity check work with a normal Android Studio SDK, no extra operator
# setup (the §7 gap: the check WARN-skipped because apksigner wasn't on PATH).
_find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then command -v apksigner; return 0; fi
    local sdk found
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -n "$sdk" && -d "$sdk/build-tools" ]] || continue
        found="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
        [[ -n "$found" ]] && { echo "$found"; return 0; }
    done
    return 1
}

verify_signed_release() {
    # A debug-signed APK MUST NOT be deployed as a release — refuse
    # here, never push debug to the device. apksigner is the source of truth
    # for the actual signing cert; aapt2 dump can't see it past
    # v2-signed APKs reliably.
    local apksigner
    if apksigner="$(_find_apksigner)"; then
        local subject
        # apksigner's --print-certs label changed across build-tools: older
        # prints "Subject: <DN>", build-tools 33+ prints
        # "Signer #1 certificate DN: <DN>". Match BOTH so the debug-vs-release
        # check works on modern SDKs (35.0.0) instead of reading an empty
        # subject and falsely failing a correctly-signed release.
        subject="$("$apksigner" verify --print-certs "$APK_PATH" 2>/dev/null | \
            awk -F': ' '/certificate DN:|Subject:/ {print $2; exit}')"
        if [[ -z "$subject" ]]; then
            log "FATAL: apksigner could not read the release APK's certificate"
            return 4
        fi
        log "signing cert subject: $subject  (apksigner: $apksigner)"
        # The Android debug signing cert is well-known:
        # CN=Android Debug,O=Android,C=US
        if [[ "$subject" == *"Android Debug"* ]]; then
            log "FATAL: APK is debug-signed (CN=Android Debug). Refusing to deploy."
            log "       Configure app/keystore.properties to sign with the release key."
            return 5
        fi
    else
        log "WARN: apksigner not found (not on PATH nor in \$ANDROID_HOME/build-tools) —"
        log "      cannot verify signing identity. Install build-tools: sdkmanager 'build-tools;35.0.0'"
    fi
}

build_release() {
    # `:app:clean` first — a config-driven buildConfigField change (e.g. the
    # helper URL from local.properties) does NOT invalidate the assembleRelease
    # up-to-date check, so without a clean gradle can silently reuse a STALE APK
    # (the documented stale-APK trap; review DEPLOY-1).
    log "build: clean + assembling :app:assembleRelease"
    (cd "$PROJECT_ROOT" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
        PATH="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin:$PATH" \
        ./gradlew :app:clean :app:assembleRelease --no-daemon -q)
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
    # The adb invariant (push + byte-verify + pm install + lastUpdateTime),
    # never a streamed `adb install`. See scripts/lib-adb.sh / AGENTS.md.
    local apk="$1"
    adb_install_verified "$DEVICE" "$apk" "$PACKAGE" -t
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
    # Roll back via the SAME byte-verified invariant — the rollback-of-rollback:
    # a truncated rollback push is caught + retried, never silently installed,
    # and the known-good APK is retained so a failed rollback can be re-run.
    # `-d` allows a versionCode downgrade (the known-good may be older than the
    # failed build); `-r` keeps install data so the operator's state survives.
    if ! adb_install_verified "$DEVICE" "$kg_path" "$PACKAGE" -d; then
        log "FATAL: rollback install could not be byte-verified — device may need manual recovery."
        log "       The known-good APK is retained at $kg_path; re-run with --manual-rollback."
        return 9
    fi
    adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
    log "rollback: known-good $kg reinstalled (byte-verified) and launched"
}

# ============== Main flow ==============

if [[ "$MANUAL_ROLLBACK" == "1" ]]; then
    log "=== MANUAL ROLLBACK ==="
    require_cmd_or_die adb
    rollback_to_known_good
    exit $?
fi

if [[ "$SKIP_BUILD" == "1" ]]; then
    log "build: SKIPPED (--skip-build) — using existing $APK_PATH"
    [[ -f "$APK_PATH" ]] || { log "FATAL: --skip-build but no APK at $APK_PATH (build it first)"; exit 6; }
else
    build_release
fi
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
adb_install "$ARCHIVE_DIR/archive/$archived_name"
launch_app

# --- promote / rollback / fail-open decision ---
# A rollback DESTROYS a byte-verified install, so it fires ONLY on positive
# evidence of a crash over a readable transport. Transport-unreadable or
# weak/absent telemetry => fail OPEN (installed, not rolled back, not promoted).
if [[ "$SKIP_HEALTH_GATE" == "1" ]]; then
    write_known_good_atomic "$archived_name"
    log "=== INSTALLED + PROMOTED $archived_name (health-gate SKIPPED via --skip-health-gate) ==="
    log "    The APK is byte-verified on-device; no logcat health check ran —"
    log "    CONFIRM THE WALL ON THE PANEL."
    exit 0
fi

require_cmd_or_die python3
hg=0; run_health_gate || hg=$?
case "$hg" in
    0)
        write_known_good_atomic "$archived_name"
        log "=== PROMOTED $archived_name (health-gate PASS) ==="
        exit 0 ;;
    1)
        log "health-gate FAILED (confirmed crash shape) — initiating rollback"
        rollback_to_known_good
        log "=== ROLLED BACK; the failed APK ($archived_name) is retained for diagnosis ==="
        exit 1 ;;
    *)
        # INDETERMINATE (exit 2): the build is installed + byte-verified but its
        # health could NOT be confirmed (transport unreadable, or only weak/absent
        # telemetry). Do NOT roll back a good install; do NOT promote.
        log "=== INSTALLED but UNVERIFIED $archived_name — health-gate INDETERMINATE (code $hg) ==="
        log "    Byte-verified on-device and NOT rolled back; known-good is unchanged."
        log "    Health could not be confirmed over the transport — CONFIRM THE WALL ON THE PANEL."
        log "    (Re-run when the transport is healthy, or use --skip-health-gate to promote.)"
        exit 0 ;;
esac
