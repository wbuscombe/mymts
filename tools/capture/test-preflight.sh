#!/usr/bin/env bash
# Tests record-demo.sh's bulletproof preflight guards WITHOUT a real device, by
# stubbing `scrcpy` and `adb` on a temp PATH and forcing the target via --device
# (which always wins over deploy.local.env). Verifies each fail-fast branch fires
# (right exit code + message) and that the happy path reaches the verify-the-
# output reminder. Also lints the script for forbidden adb patterns.
#
# Run: tools/capture/test-preflight.sh   (exit 0 = all guards behave)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/record-demo.sh"
PASS=0 FAIL=0
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"; rm -f "$HERE"/output/mymts-demo-*.mkv 2>/dev/null || true' EXIT

# Build a temp bin dir with fake scrcpy/adb. $2=scrcpy version, $3=adb-devices line.
# The stub BODIES are quoted heredocs (literal — their $vars are the stub's own
# runtime, not ours); the injected version/device line go in via printf %q.
make_stubs() {
    local bin="$1" ver="$2" devline="$3"
    mkdir -p "$bin"
    {
        printf '#!/usr/bin/env bash\n'
        # shellcheck disable=SC2016  # the format string's $1 is the stub's runtime arg, written literally
        printf 'if [[ "$1" == --version ]]; then echo "scrcpy %s"; exit 0; fi\n' "$ver"
        cat <<'STUB'
# happy path: honor --record=<file> by writing a non-trivial file, then exit 0.
for a in "$@"; do case "$a" in --record=*) f="${a#--record=}"; mkdir -p "$(dirname "$f")"; head -c 200000 /dev/zero > "$f";; esac; done
exit 0
STUB
    } > "$bin/scrcpy"
    {
        printf '#!/usr/bin/env bash\n'
        printf 'devline=%q\n' "$devline"
        cat <<'STUB'
if [[ "$1" == devices ]]; then
    echo "List of devices attached"
    [[ -n "$devline" ]] && echo "$devline"
fi
exit 0
STUB
    } > "$bin/adb"
    chmod +x "$bin/scrcpy" "$bin/adb"
}

# run_case <name> <expect_rc> <substr> <PATH> [script args...]
run_case() {
    local name="$1" expect_rc="$2" substr="$3" path="$4"; shift 4
    local out rc
    if out="$(PATH="$path" "$SCRIPT" "$@" 2>&1)"; then rc=0; else rc=$?; fi
    local ok=1
    [[ "$rc" == "$expect_rc" ]] || ok=0
    grep -qF "$substr" <<<"$out" || ok=0
    if (( ok )); then
        echo "  PASS  $name (rc=$rc)"; PASS=$((PASS + 1))
    else
        echo "  FAIL  $name (rc=$rc, expected $expect_rc; needed: $substr)"
        # shellcheck disable=SC2001  # per-line prefix of a multi-line var — sed is clearest
        sed 's/^/        | /' <<<"$out"; FAIL=$((FAIL + 1))
    fi
}

SYS="/usr/bin:/bin"
echo "== record-demo.sh preflight guards =="

# 1. scrcpy missing → install guidance, rc 1.
mkdir -p "$TMP/empty"
run_case "scrcpy-missing → install guidance" 1 "scrcpy is not installed" "$TMP/empty:$SYS"

# 2. scrcpy too old (1.25) → upgrade guidance, rc 1 (version check precedes device check).
make_stubs "$TMP/old" "1.25" "192.168.99.99:5555 device"
run_case "scrcpy-too-old → upgrade guidance" 1 "too old" "$TMP/old:$SYS" --device 192.168.99.99:5555

# 3. placeholder device (192.0.2.*) → wrong-box guard, rc 2.
make_stubs "$TMP/new" "2.4" "192.168.99.99:5555 device"
run_case "placeholder-device → wrong-box guard" 2 "placeholder/unset" "$TMP/new:$SYS" --device 192.0.2.10:5555

# 4. device not connected (adb lists nothing) → reconnect-then-fail guidance, rc 1.
make_stubs "$TMP/nope" "2.4" ""
run_case "device-not-connected → reconnect guidance" 1 "not connected as 'device'" "$TMP/nope:$SYS" --device 192.168.99.99:5555

# 5. happy path → records + reaches the verify reminder, rc 0.
make_stubs "$TMP/ok" "2.4" "192.168.99.99:5555 device"
run_case "happy-path → verify reminder" 0 "VERIFY THE RECORDING" "$TMP/ok:$SYS" --device 192.168.99.99:5555 --no-window

# 6. forbidden-pattern lint. Strip comment lines + single-quoted spans first (the
#    'adb kill-server' warnings live there) so only a REAL invocation fails.
echo "== forbidden-pattern lint (warnings in comments/messages are OK) =="
STRIPPED="$(grep -vE '^[[:space:]]*#' "$SCRIPT" | sed "s/'[^']*'//g; s/\"[^\"]*\"//g")"
if grep -qE 'kill-server' <<<"$STRIPPED"; then echo "  FAIL  real kill-server invocation"; FAIL=$((FAIL + 1)); else echo "  PASS  no kill-server invocation (only 'never do this' warnings)"; PASS=$((PASS + 1)); fi
if grep -qE 'adb[^&]*&[[:space:]]*$' <<<"$STRIPPED"; then echo "  FAIL  backgrounded adb"; FAIL=$((FAIL + 1)); else echo "  PASS  no backgrounded adb"; PASS=$((PASS + 1)); fi
# shellcheck disable=SC2016  # the single-quoted regex is the literal string we grep for
if grep -qE '\-s "\$DEVICE"' "$SCRIPT"; then echo "  PASS  hard-targets the serial (-s \"\$DEVICE\")"; PASS=$((PASS + 1)); else echo "  FAIL  no -s hard-target"; FAIL=$((FAIL + 1)); fi

echo
echo "== $PASS passed, $FAIL failed =="
(( FAIL == 0 ))
