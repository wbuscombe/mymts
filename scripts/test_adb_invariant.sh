#!/usr/bin/env bash
# Hermetic regression test for the adb invariant (scripts/lib-adb.sh).
#
# This exercises the GATE LOGIC, not a mocked-green happy path: it stubs `adb`
# with a fake whose on-device byte size and lastUpdateTime are scripted, and
# asserts the invariant REJECTS a truncated transfer (never installs it),
# RECOVERS on retry, ENFORCES that lastUpdateTime advanced, and INSTALLS only a
# byte-verified push. Run: bash scripts/test_adb_invariant.sh  (exit 0 = pass).
#
# Per AGENTS.md / the v5 env-invariant rule: this tests the mismatch->reject
# path, which a real-device test cannot reproduce on demand.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Quiet log + instant sleeps (defined BEFORE sourcing so the lib uses ours).
log() { [[ -n "${VERBOSE:-}" ]] && echo "  [lib] $*" >&2 || true; }
sleep() { :; }

source "$SCRIPT_DIR/lib-adb.sh"

# --- a real local file of a known size, so _adb_local_size reads a real stat ---
TMP_APK="$(mktemp)"; trap 'rm -f "$TMP_APK"' EXIT
head -c 4096 /dev/zero > "$TMP_APK"
WANT="$(_adb_local_size "$TMP_APK")"

# --- scenario state the fake adb reads ---
SCENARIO=""; FAKE_PUSH_COUNT=0; PM_INSTALL_CALLED=0; FAKE_LUT=100; FAKE_INSTALL_TAKES=0

_fake_remote_size() {
    case "$SCENARIO" in
        truncate)        echo $((WANT - 1)) ;;                                  # always short
        retry)           [[ $FAKE_PUSH_COUNT -ge 2 ]] && echo "$WANT" || echo $((WANT - 1)) ;;
        happy|lut_same)  echo "$WANT" ;;
        *)               echo "$WANT" ;;
    esac
}

# Fake adb. Two arg shapes: `-s DEV <sub> ...` and `connect|disconnect DEV`.
adb() {
    local sub scmd
    if [[ "${1:-}" == "-s" ]]; then sub="${3:-}"; scmd="${4:-}"; else sub="${1:-}"; scmd=""; fi
    case "$sub" in
        push) FAKE_PUSH_COUNT=$((FAKE_PUSH_COUNT + 1)); return 0 ;;
        connect|disconnect) return 0 ;;
        shell)
            case "$scmd" in
                rm) return 0 ;;
                stat) _fake_remote_size; return 0 ;;
                dumpsys) echo "    lastUpdateTime=$FAKE_LUT"; return 0 ;;
                pm) PM_INSTALL_CALLED=$((PM_INSTALL_CALLED + 1))
                    [[ "$FAKE_INSTALL_TAKES" == "1" ]] && FAKE_LUT=$((FAKE_LUT + 7))
                    return 0 ;;
                *) return 0 ;;
            esac ;;
        *) return 0 ;;
    esac
}

FAILS=0
check() { # check <name> <expected> <actual>
    if [[ "$2" == "$3" ]]; then echo "  ok: $1"; else echo "  FAIL: $1 (expected '$2', got '$3')"; FAILS=$((FAILS + 1)); fi
}
reset() { SCENARIO="$1"; FAKE_PUSH_COUNT=0; PM_INSTALL_CALLED=0; FAKE_LUT=100; FAKE_INSTALL_TAKES="$2"; }

echo "adb-invariant gate tests (local APK = $WANT bytes):"

# 1. Truncated transfer: every push is short -> REJECT, never install.
reset truncate 1; ADB_PUSH_RETRIES=2
adb_install_verified "dev:5555" "$TMP_APK" "com.mymts"; rc=$?
check "truncation returns 21 (byte-verify abort)" 21 "$rc"
check "truncation NEVER calls pm install" 0 "$PM_INSTALL_CALLED"

# 2. Happy path: byte-verified push + lastUpdateTime advances -> install, rc 0.
reset happy 1; ADB_PUSH_RETRIES=3
adb_install_verified "dev:5555" "$TMP_APK" "com.mymts"; rc=$?
check "happy path returns 0" 0 "$rc"
check "happy path installs once" 1 "$PM_INSTALL_CALLED"

# 3. lastUpdateTime did NOT advance (no-op install) -> rc 23.
reset lut_same 0; ADB_PUSH_RETRIES=3
adb_install_verified "dev:5555" "$TMP_APK" "com.mymts"; rc=$?
check "stale-install (lastUpdateTime unchanged) returns 23" 23 "$rc"

# 4. Retry recovery: first push short, second verified -> install, rc 0.
reset retry 1; ADB_PUSH_RETRIES=3
adb_install_verified "dev:5555" "$TMP_APK" "com.mymts"; rc=$?
check "retry recovers and returns 0" 0 "$rc"
check "retry installs exactly once (after verify)" 1 "$PM_INSTALL_CALLED"
[[ $FAKE_PUSH_COUNT -ge 2 ]] && echo "  ok: retry re-pushed ($FAKE_PUSH_COUNT attempts)" || { echo "  FAIL: retry did not re-push"; FAILS=$((FAILS + 1)); }

echo
if [[ $FAILS -eq 0 ]]; then echo "PASS — adb invariant gate enforced"; exit 0
else echo "FAIL — $FAILS assertion(s) failed"; exit 1; fi
