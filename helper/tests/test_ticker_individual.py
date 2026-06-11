"""Defensive-parse tests for the individual-sports cards (PGA first).

ESPN responses mocked as raw bytes — the parser must be strict (drop
malformed/non-current events, never raise) and produce an honest leaderboard
card (top-N players, score-to-par, status).
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

from mymts_helper.ticker import individual


_NOW_MS = 1_700_000_000_000  # fixed "now" for deterministic windowing


def _iso(offset_ms: int) -> str:
    return datetime.fromtimestamp((_NOW_MS + offset_ms) / 1000, tz=timezone.utc).isoformat()


def _pga(state: str, *, date_offset_ms: int = 0, competitors=None, name="RBC Canadian Open") -> bytes:
    if competitors is None:
        competitors = [
            {"order": 1, "score": "-6", "athlete": {"shortName": "S. Theegala", "displayName": "Sahith Theegala"}},
            {"order": 2, "score": "-5", "athlete": {"shortName": "R. McIlroy"}},
            {"order": 3, "score": "0", "athlete": {"displayName": "Scottie Scheffler"}},
            {"order": 4, "score": "+2", "athlete": {"shortName": "X. Schauffele"}},
        ]
    return json.dumps({
        "events": [{
            "name": name,
            "date": _iso(date_offset_ms),
            "status": {"type": {"state": state, "shortDetail": "Round 2 - In Progress"}},
            "competitions": [{"competitors": competitors}],
        }],
    }).encode()


def test_pga_live_builds_leaderboard_card() -> None:
    entries = individual.parse_pga(_pga("in"), now_ms=_NOW_MS)
    assert len(entries) == 1
    e = entries[0]
    assert e.symbol == "PGA"
    assert e.is_sample is False
    c = e.card
    assert c is not None
    assert (c.league, c.kind, c.state) == ("PGA", "leaderboard", "in")
    assert c.title == "RBC Canadian Open"
    # top-3 by order, compact name + score-to-par; "0" → "E"
    assert c.lines == ["S. Theegala -6", "R. McIlroy -5", "Scheffler E"]
    assert "card" in e.to_json()  # additive on the wire


def test_pga_pre_tournament_lists_players_without_phantom_scores() -> None:
    # Starts in 1 day (within the pre-window) → current, but no scores yet.
    entries = individual.parse_pga(_pga("pre", date_offset_ms=24 * 60 * 60 * 1000), now_ms=_NOW_MS)
    assert len(entries) == 1
    assert entries[0].card.lines == ["S. Theegala", "R. McIlroy", "Scheffler"]


def test_pga_far_future_is_dropped() -> None:
    # A tournament 10 days out is NOT current — omit it (honest, no schedule).
    body = _pga("pre", date_offset_ms=10 * 24 * 60 * 60 * 1000)
    assert individual.parse_pga(body, now_ms=_NOW_MS) == []


def test_pga_long_finished_is_dropped() -> None:
    body = _pga("post", date_offset_ms=-10 * 24 * 60 * 60 * 1000)
    assert individual.parse_pga(body, now_ms=_NOW_MS) == []


def test_pga_orders_by_rank_takes_top_three() -> None:
    # Competitors out of order → parser sorts by `order` and keeps the top 3.
    comp = [
        {"order": 3, "score": "0", "athlete": {"shortName": "C"}},
        {"order": 1, "score": "-9", "athlete": {"shortName": "A"}},
        {"order": 2, "score": "-7", "athlete": {"shortName": "B"}},
        {"order": 4, "score": "+1", "athlete": {"shortName": "D"}},
    ]
    lines = individual.parse_pga(_pga("in", competitors=comp), now_ms=_NOW_MS)[0].card.lines
    assert lines == ["A -9", "B -7", "C E"]


def test_pga_never_raises_on_junk() -> None:
    assert individual.parse_pga(b"not json", now_ms=_NOW_MS) == []
    assert individual.parse_pga(b'{"events": []}', now_ms=_NOW_MS) == []
    assert individual.parse_pga(b'{"events": [{"name": "X", "date": "bad"}]}', now_ms=_NOW_MS) == []
    # event with no competitors → dropped (never a half-built card)
    body = json.dumps({"events": [{"name": "X", "date": _iso(0),
                       "status": {"type": {"state": "in"}}, "competitions": [{"competitors": []}]}]}).encode()
    assert individual.parse_pga(body, now_ms=_NOW_MS) == []
