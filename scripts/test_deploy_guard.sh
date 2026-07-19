#!/usr/bin/env bash
# Regression test for deploy-app.sh's wrong-box guard (the wrong-box incident).
#
# Asserts the script REFUSES the scrubbed placeholder / unset device BEFORE any
# build or device op — so a MyMTS deploy can never fall through to the wrong box.
# Exercises the actual guard logic (not a mock). Run: bash scripts/test_deploy_guard.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$SCRIPT_DIR/deploy-app.sh"
fails=0

# 1. The placeholder device is rejected with exit 2.
out="$(bash "$DEPLOY" --device 192.0.2.10:5555 2>&1)"; rc=$?
if [[ $rc -eq 2 && "$out" == *"placeholder/unset"* ]]; then
    echo "  ok: placeholder device rejected (exit 2)"
else
    echo "  FAIL: placeholder not rejected (rc=$rc): $out"; fails=$((fails + 1))
fi

# 2. The reject happens BEFORE the multi-minute build (no gradle work).
if [[ "$out" != *"assembling"* && "$out" != *"build:"* ]]; then
    echo "  ok: rejected before any build ran"
else
    echo "  FAIL: build started before the guard fired"; fails=$((fails + 1))
fi

echo
if [[ $fails -eq 0 ]]; then echo "PASS — deploy wrong-box guard enforced"; exit 0
else echo "FAIL — $fails assertion(s)"; exit 1; fi
