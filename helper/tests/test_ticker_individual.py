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


# ---- UFC fight cards ----

def _fight(state, a, b, *, wclass="Lightweight", winner=None, detail="6/14 - 8 PM"):
    def comp(name, order, win):
        return {"order": order, "winner": win, "athlete": {"shortName": name}}
    return {
        "type": {"text": wclass},
        "status": {"type": {"state": state, "shortDetail": detail}},
        "competitors": [comp(a, 1, winner == a), comp(b, 2, winner == b)],
    }


def _ufc(fights, *, state="pre", date_offset_ms=24 * 60 * 60 * 1000, name="UFC 250"):
    return json.dumps({
        "events": [{
            "name": name,
            "date": _iso(date_offset_ms),
            "status": {"type": {"state": state}},
            "competitions": fights,
        }],
    }).encode()


def test_ufc_builds_fight_cards_main_event_first() -> None:
    # ESPN lists prelims→main; the parser reads in reverse so the headline leads.
    body = _ufc([_fight("pre", "Prelim A", "Prelim B"),
                 _fight("pre", "Topuria", "Gaethje", wclass="Lightweight")])
    entries = individual.parse_ufc(body, now_ms=_NOW_MS)
    assert len(entries) == 2
    main = entries[0].card
    assert main.league == "UFC" and main.kind == "fight"
    assert main.title == "Topuria vs Gaethje"   # order-1 vs order-2
    assert main.lines == ["Lightweight"]
    assert main.state == "pre"


def test_ufc_post_fight_shows_winner() -> None:
    body = _ufc([_fight("post", "Topuria", "Gaethje", winner="Topuria", detail="KO/TKO R2")],
                state="in", date_offset_ms=0)
    card = individual.parse_ufc(body, now_ms=_NOW_MS)[0].card
    assert card.title == "Topuria def. Gaethje"
    assert card.status == "KO/TKO R2"
    assert card.state == "post"


def test_ufc_draw_never_invents_a_winner() -> None:
    body = _ufc([_fight("post", "A", "B", winner=None)], state="in", date_offset_ms=0)
    assert individual.parse_ufc(body, now_ms=_NOW_MS)[0].card.title == "A vs B"


def test_ufc_caps_to_main_card_and_drops_offseason() -> None:
    many = [_fight("pre", f"X{i}", f"Y{i}") for i in range(9)]
    assert len(individual.parse_ufc(_ufc(many), now_ms=_NOW_MS)) == individual.UFC_MAX_FIGHTS
    # far-future event → omitted
    assert individual.parse_ufc(_ufc([_fight("pre", "A", "B")],
                                     date_offset_ms=10 * 24 * 60 * 60 * 1000), now_ms=_NOW_MS) == []


def test_ufc_never_raises_on_junk() -> None:
    assert individual.parse_ufc(b"nope", now_ms=_NOW_MS) == []
    assert individual.parse_ufc(b'{"events":[]}', now_ms=_NOW_MS) == []
