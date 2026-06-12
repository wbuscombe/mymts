"""Unit tests for `health_check.decide` — the Stage 6 update gate's
promote-or-rollback decision logic.

Run with:    cd scripts && python -m pytest test_health_check.py -v
or:          python -m pytest scripts/test_health_check.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import pytest

from health_check import Outcome, decide, exit_code_for


def make_logcat(*, ready: int = 0, decoder: int = 0, dead: int = 0) -> str:
    lines = []
    for i in range(ready):
        lines.append(f"06-03 17:00:00.000 1 1 I MYMTS_SOAK: EV=TILE_READY|id=slot-{i}-foo")
    for i in range(decoder):
        lines.append(f"06-03 17:00:00.000 1 1 I MYMTS_SOAK: EV=DECODER|id=slot-{i}-foo")
    for i in range(dead):
        lines.append(f"06-03 17:00:00.000 1 1 W MYMTS_SOAK: EV=DEAD|id=slot-{i}-foo")
    return "\n".join(lines) + "\n"


def test_pass_when_minimum_ready_met_and_no_dead() -> None:
    d = decide(
        make_logcat(ready=4, decoder=4, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.PASS
    assert d.tile_ready == 4
    assert d.dead == 0
    assert d.to_dict()["is_promote"] is True


def test_pass_when_minimum_ready_is_lower_than_expected() -> None:
    # 2-channel helper world: 4 tiles cycle 2 channels; minimum_ready=2
    # is the operator's tolerable bar.
    d = decide(
        make_logcat(ready=2, decoder=2, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.PASS


def test_fail_all_dead_takes_priority_over_anything_else() -> None:
    # All tiles came up briefly then settled DEAD — the wall is not
    # sustaining playback. Promote-this-build would be a regression.
    d = decide(
        make_logcat(ready=4, decoder=4, dead=4),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.FAIL_ALL_DEAD
    assert d.dead == 4
    assert d.to_dict()["is_promote"] is False


def test_fail_decoder_thrash_catches_the_b19b013_regression_shape() -> None:
    # b19b013 regression: decoders init repeatedly (3 strikes × 4 tiles =
    # 12), but NO frames render. Operator's reference failure shape.
    d = decide(
        make_logcat(ready=0, decoder=12, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.FAIL_DECODER_THRASH
    assert d.decoder == 12
    assert d.tile_ready == 0


def test_fail_not_ready_when_only_one_tile_came_up() -> None:
    d = decide(
        make_logcat(ready=1, decoder=2, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.FAIL_NOT_READY
    assert d.tile_ready == 1


def test_fail_when_logcat_is_empty() -> None:
    # No telemetry at all — the app didn't launch, didn't reach the soak
    # log layer, or crashed before emitting anything. NEVER promote.
    d = decide("", minimum_ready=2, expected_tile_count=4)
    assert d.outcome != Outcome.PASS
    assert d.tile_ready == 0
    assert d.decoder == 0


def test_partial_dead_below_threshold_is_not_all_dead() -> None:
    # 1 tile died but 3 are LIVE — that's a bursty-stream blip, not a
    # full-wall failure. With minimum_ready=2, the build is still healthy.
    d = decide(
        make_logcat(ready=3, decoder=4, dead=1),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.PASS


def test_decoder_thrash_only_when_zero_tile_ready() -> None:
    # If even one TILE_READY fires, it's not decoder-thrash; the FAIL
    # falls through to FAIL_NOT_READY if it's below the minimum.
    d = decide(
        make_logcat(ready=1, decoder=12, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == Outcome.FAIL_NOT_READY


def test_dict_serialization_includes_all_fields() -> None:
    d = decide(
        make_logcat(ready=4, decoder=4, dead=0),
        minimum_ready=2,
        expected_tile_count=4,
    )
    out = d.to_dict()
    assert out["outcome"] == "PASS"
    assert out["tile_ready_count"] == 4
    assert out["decoder_count"] == 4
    assert out["dead_count"] == 0
    assert out["is_promote"] is True
    assert "reason" in out


@pytest.mark.parametrize("ready,decoder,dead,expected", [
    (0, 0, 0, Outcome.FAIL_NOT_READY),
    (4, 0, 0, Outcome.PASS),
    (0, 4, 0, Outcome.FAIL_DECODER_THRASH),
    (0, 4, 4, Outcome.FAIL_ALL_DEAD),
    (4, 4, 4, Outcome.FAIL_ALL_DEAD),
    (2, 4, 0, Outcome.PASS),
])
def test_decision_matrix(ready: int, decoder: int, dead: int,
                         expected: Outcome) -> None:
    d = decide(
        make_logcat(ready=ready, decoder=decoder, dead=dead),
        minimum_ready=2,
        expected_tile_count=4,
    )
    assert d.outcome == expected, f"ready={ready} decoder={decoder} dead={dead}: {d.outcome}"


# ---- exit_code_for: the fail-open promote / rollback / unverified policy ----
# 0 = promote, 1 = rollback (confirmed crash), 2 = unverified (fail open).

def test_exit_code_pass_promotes() -> None:
    assert exit_code_for(Outcome.PASS, readable=True) == 0


def test_exit_code_confirmed_crash_rolls_back() -> None:
    # Positive crash evidence over a READABLE transport -> rollback is correct.
    assert exit_code_for(Outcome.FAIL_ALL_DEAD, readable=True) == 1
    assert exit_code_for(Outcome.FAIL_DECODER_THRASH, readable=True) == 1


def test_exit_code_unreadable_transport_never_rolls_back() -> None:
    # The false-fail that bit us: transport unreadable must be INDETERMINATE
    # (fail open, code 2), NEVER a rollback — even if a (truncated/empty) blob
    # would otherwise classify as a crash.
    assert exit_code_for(Outcome.INDETERMINATE, readable=False) == 2
    assert exit_code_for(Outcome.FAIL_ALL_DEAD, readable=False) == 2
    assert exit_code_for(Outcome.PASS, readable=False) == 2


def test_exit_code_not_ready_over_readable_is_unverified_not_rollback() -> None:
    # Too-few-tiles is weak/ambiguous over a flaky transport (a truncated read
    # can fake it) — do NOT destroy a byte-verified install; fail open to 2.
    assert exit_code_for(Outcome.FAIL_NOT_READY, readable=True) == 2
