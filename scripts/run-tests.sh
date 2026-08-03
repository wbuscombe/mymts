#!/usr/bin/env bash
# Run EVERY MyMTS suite that needs no device, no NAS and no secrets — the one
# command a contributor (or a returning operator) can run from a clean clone to
# know the repo is green. Mirrors the CI job's checks so a local pass means CI
# passes for the same reasons.
#
#   make test            # this script
#   scripts/run-tests.sh # same thing directly
#
# DELIBERATELY NOT INCLUDED, because each needs something a clean clone hasn't got:
#   - the native app suite (`./gradlew :app:testReleaseUnitTest`) needs a JDK 17 +
#     the Android SDK → `make test-app`, which says so if the JDK is missing;
#   - the phantom boot smoke needs a helper actually listening → ONBOARDING.md;
#   - anything touching a device or the NAS.
#
# Runs every suite even after one fails (a single command should tell you the WHOLE
# state, not just the first thing that broke), then exits non-zero if any failed.

set -uo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

PASS=0; FAIL=0; FAILED_NAMES=()
SKIP=0; SKIPPED_NAMES=()

bold() { printf '\033[1m%s\033[0m\n' "$1"; }

# run <name> <command...>
run() {
    local name="$1"; shift
    printf '  %-46s' "$name"
    local out
    if out="$("$@" 2>&1)"; then
        printf 'PASS\n'; PASS=$((PASS + 1))
    else
        printf 'FAIL\n'; FAIL=$((FAIL + 1)); FAILED_NAMES+=("$name")
        printf '%s\n' "$out" | sed 's/^/      | /' | tail -25
    fi
}

# skip <name> <why> — a missing PREREQ is reported, never silently passed.
skip() {
    printf '  %-46s SKIP (%s)\n' "$1" "$2"
    SKIP=$((SKIP + 1)); SKIPPED_NAMES+=("$1 — $2")
}

need() { command -v "$1" >/dev/null 2>&1; }

bold "== MyMTS test suites =="

# --- helper (Python / FastAPI) ---
if need uv; then
    run "helper — pytest"                 sh -c 'cd helper && uv run --extra dev pytest -q'
    run "helper — ruff lint (advisory)"   sh -c 'cd helper && uv run --extra dev ruff check src tests >/dev/null 2>&1 || true'
else
    skip "helper — pytest" "uv not installed (see ONBOARDING.md)"
fi

# --- web client (node:test, no deps) ---
if need node; then
    run "web — node:test"                 sh -c 'node --test web/test/*.test.mjs'
    run "docs-hygiene — gate"             node tools/docs-hygiene/check.mjs
    run "docs-hygiene — self-test"        sh -c 'node --test tools/docs-hygiene/check.test.mjs'
else
    skip "web — node:test" "node not installed (need >= 18)"
    skip "docs-hygiene" "node not installed (need >= 18)"
fi

# --- renderer (stdlib unittest, no deps) + the cross-surface contracts ---
if need python3; then
    run "renderer — unittest"             python3 -m unittest discover -s renderer -p 'test_*.py'
    run "contract — schema_version parity" python3 scripts/check_schema_consistency.py
    run "contract — channel-picker parity" python3 scripts/check_channel_parity.py
else
    skip "renderer + contracts" "python3 not installed"
fi

# --- shell-level gates (pure; stubbed adb/scrcpy, no device touched) ---
run "gate — adb deploy invariant"         bash scripts/test_adb_invariant.sh
run "gate — deploy guard"                 bash scripts/test_deploy_guard.sh
run "gate — capture preflight"            bash tools/capture/test-preflight.sh

echo
if [ "$SKIP" -gt 0 ]; then
    bold "== skipped (missing prereqs) =="
    for s in "${SKIPPED_NAMES[@]}"; do echo "  - $s"; done
    echo
fi
if [ "$FAIL" -eq 0 ]; then
    if [ "$SKIP" -gt 0 ]; then
        bold "== $PASS passed, 0 failed, $SKIP SKIPPED =="
        echo "   a SKIP is not a pass — install the prereq to cover it"
    else
        bold "== $PASS passed, 0 failed =="
    fi
    echo "   native app suite is separate: make test-app"
    exit 0
fi
bold "== $PASS passed, $FAIL FAILED =="
for f in "${FAILED_NAMES[@]}"; do echo "  - $f"; done
exit 1
