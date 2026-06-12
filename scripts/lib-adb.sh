#!/usr/bin/env bash
# Shared adb deploy primitives — the MyMTS adb invariant.
#
# SOURCE this file; do not run it. It implements the deploy invariant codified
# in AGENTS.md ("Deploy — the adb invariant"):
#
#   adb push  ->  verify on-device byte size == local APK (retry on mismatch —
#   handles the flaky .92 partial-push)  ->  pm install -r [flags]  ->  verify
#   lastUpdateTime advanced.
#
# NEVER a streamed `adb install` (the exact failure this guards against). ONE
# foreground adb op at a time. On a wedge, reconnect THIS device only — NEVER
# `adb kill-server` (it would disrupt the other Onn boxes on the same server).
#
# The sourcing script may define log(); if it doesn't, a stderr fallback is used.

if ! declare -F log >/dev/null 2>&1; then
    log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >&2; }
fi

: "${ADB_PUSH_RETRIES:=3}"
: "${ADB_REMOTE_TMP:=/data/local/tmp/mymts-deploy.apk}"

# Local file size — portable across macOS host (stat -f%z) and Linux (stat -c%s).
_adb_local_size() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null; }

# On-device file size via Android toybox stat (-c%s). Strips CR from the shell.
_adb_remote_size() { adb -s "$1" shell stat -c%s "$2" 2>/dev/null | tr -d '\r'; }

# dumpsys lastUpdateTime for a package (a no-op install does NOT advance it).
_adb_last_update_time() {
    adb -s "$1" shell dumpsys package "$2" 2>/dev/null \
        | awk -F= '/lastUpdateTime/ {print $2; exit}' | tr -d '\r '
}

# Device-specific reconnect on a wedge. NEVER kill-server (would disrupt the
# other Onn boxes sharing this adb server) — reconnect only THIS device.
adb_reconnect() {
    local device="$1"
    log "adb: reconnecting $device (device-specific; never kill-server)"
    adb disconnect "$device" >/dev/null 2>&1 || true
    sleep 2
    adb connect "$device" >/dev/null 2>&1 || true
    sleep 2
}

# The invariant.
#   adb_install_verified <device> <local-apk> <package> [extra pm install flags...]
# Returns 0 only on a byte-verified install whose lastUpdateTime advanced.
# Non-zero (and NO install attempted) on a transfer that can't be byte-verified.
adb_install_verified() {
    local device="$1" apk="$2" pkg="$3"; shift 3
    local extra_flags=("$@")

    local want; want="$(_adb_local_size "$apk")"
    if [[ -z "$want" || "$want" -le 0 ]]; then
        log "FATAL: cannot stat local APK size for $apk"; return 20
    fi
    local before; before="$(_adb_last_update_time "$device" "$pkg")"

    # Push with byte-size verify + retry — the .92 partial-push gate.
    local attempt got=""
    for ((attempt = 1; attempt <= ADB_PUSH_RETRIES; attempt++)); do
        log "push ($attempt/$ADB_PUSH_RETRIES): $apk -> $device:$ADB_REMOTE_TMP"
        adb -s "$device" shell rm -f "$ADB_REMOTE_TMP" >/dev/null 2>&1 || true
        if ! adb -s "$device" push "$apk" "$ADB_REMOTE_TMP" >/dev/null 2>&1; then
            log "push failed (transport); reconnecting + retrying"
            adb_reconnect "$device"; got=""; continue
        fi
        got="$(_adb_remote_size "$device" "$ADB_REMOTE_TMP")"
        if [[ "$got" == "$want" ]]; then
            log "push verified: on-device $got bytes == local $want bytes"
            break
        fi
        log "push MISMATCH: on-device '$got' != local '$want' (truncated transfer); retrying"
        got=""; adb_reconnect "$device"
    done
    if [[ "$got" != "$want" ]]; then
        log "FATAL: byte-size never matched after $ADB_PUSH_RETRIES pushes — NOT installing a truncated APK"
        return 21
    fi

    # Install from the verified on-device copy — never a streamed install.
    log "install: pm install -r ${extra_flags[*]:-} <byte-verified apk>"
    if ! adb -s "$device" shell pm install -r ${extra_flags[@]+"${extra_flags[@]}"} "$ADB_REMOTE_TMP"; then
        log "FATAL: pm install failed"; return 22
    fi

    # Verify the install actually took (lastUpdateTime must advance).
    local after; after="$(_adb_last_update_time "$device" "$pkg")"
    if [[ -n "$before" && "$after" == "$before" ]]; then
        log "FATAL: lastUpdateTime did not advance ($before == $after) — install did not take"
        return 23
    fi
    log "install verified: lastUpdateTime $before -> $after"
    adb -s "$device" shell rm -f "$ADB_REMOTE_TMP" >/dev/null 2>&1 || true
    return 0
}
