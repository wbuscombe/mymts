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
    # top-10 by order (all 4 here), position + compact name + score; "0" → "E"
    assert c.lines == ["1. S. Theegala -6", "2. R. McIlroy -5", "3. Scheffler E", "4. X. Schauffele +2"]
    assert "card" in e.to_json()  # additive on the wire


def test_pga_pre_tournament_lists_players_without_phantom_scores() -> None:
    # Starts in 1 day (within the pre-window) → current, but no scores yet.
    entries = individual.parse_pga(_pga("pre", date_offset_ms=24 * 60 * 60 * 1000), now_ms=_NOW_MS)
    assert len(entries) == 1
    assert entries[0].card.lines == ["1. S. Theegala", "2. R. McIlroy", "3. Scheffler", "4. X. Schauffele"]


def test_pga_far_future_is_dropped() -> None:
    # A tournament 10 days out is NOT current — omit it (honest, no schedule).
    body = _pga("pre", date_offset_ms=10 * 24 * 60 * 60 * 1000)
    assert individual.parse_pga(body, now_ms=_NOW_MS) == []


def test_pga_long_finished_is_dropped() -> None:
    body = _pga("post", date_offset_ms=-10 * 24 * 60 * 60 * 1000)
    assert individual.parse_pga(body, now_ms=_NOW_MS) == []


def test_pga_orders_by_rank_with_position_and_caps_at_top_10() -> None:
    # Out of order → sorted by `order`, numbered, capped at PGA_TOP_N (10).
    comp = [
        {"order": 3, "score": "0", "athlete": {"shortName": "C"}},
        {"order": 1, "score": "-9", "athlete": {"shortName": "A"}},
        {"order": 2, "score": "-7", "athlete": {"shortName": "B"}},
        {"order": 4, "score": "+1", "athlete": {"shortName": "D"}},
    ]
    lines = individual.parse_pga(_pga("in", competitors=comp), now_ms=_NOW_MS)[0].card.lines
    assert lines == ["1. A -9", "2. B -7", "3. C E", "4. D +1"]
    # 12 players → capped to the top 10
    many = [{"order": i, "score": f"-{i}", "athlete": {"shortName": f"P{i}"}} for i in range(1, 13)]
    capped = individual.parse_pga(_pga("in", competitors=many), now_ms=_NOW_MS)[0].card.lines
    assert len(capped) == individual.PGA_TOP_N == 10


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


# ---- Tennis match cards ----

def _ls(*vals):
    out = []
    for v in vals:
        if isinstance(v, tuple):
            out.append({"value": float(v[0]), "tiebreak": v[1]})
        else:
            out.append({"value": float(v)})
    return out


def _match(state, a, b, *, winner=None, a_sets=(), b_sets=(), detail="Final"):
    def comp(name, sets, win):
        return {"winner": win, "athlete": {"displayName": name}, "linescores": _ls(*sets)}
    return {"status": {"type": {"state": state, "shortDetail": detail}},
            "competitors": [comp(a, a_sets, winner == a), comp(b, b_sets, winner == b)]}


def _tennis(matches):
    return json.dumps({"events": [{"name": "Boss Open", "groupings": [{"competitions": matches}]}]}).encode()


def test_tennis_final_shows_winner_and_sets() -> None:
    body = _tennis([_match("post", "Marc Huesler", "Nikoloz Basilashvili",
                           winner="Marc Huesler", a_sets=(6, 7), b_sets=(1, 5))])
    c = individual.parse_tennis(body, now_ms=_NOW_MS)[0].card
    assert c.league == "Tennis" and c.kind == "match"
    assert c.title == "Huesler d. Basilashvili"
    assert c.lines == ["6-1 7-5"]
    assert c.state == "post"


def test_tennis_tiebreak_renders_in_set_score() -> None:
    body = _tennis([_match("post", "A B", "C D", winner="A B", a_sets=(7, 6), b_sets=((6, 7), 4))])
    # set1 7-6(7) (loser's tiebreak), set2 6-4
    assert individual.parse_tennis(body, now_ms=_NOW_MS)[0].card.lines == ["7-6(7) 6-4"]


def test_tennis_skips_upcoming_and_caps() -> None:
    pre = [_match("pre", f"A{i} x", f"B{i} y") for i in range(3)]
    assert individual.parse_tennis(_tennis(pre), now_ms=_NOW_MS) == []  # draw upcoming = skipped
    many = [_match("post", f"A{i} x", f"B{i} y", winner=f"A{i} x", a_sets=(6,), b_sets=(4,)) for i in range(9)]
    assert len(individual.parse_tennis(_tennis(many), now_ms=_NOW_MS)) == individual.TENNIS_MAX_MATCHES


def test_tennis_live_first_then_finals() -> None:
    body = _tennis([
        _match("post", "Final Winner", "Final Loser", winner="Final Winner", a_sets=(6,), b_sets=(2,)),
        _match("in", "Carlos Alcaraz", "Jannik Sinner", a_sets=(3,), b_sets=(2,), detail="Set 1"),
    ])
    titles = [e.card.title for e in individual.parse_tennis(body, now_ms=_NOW_MS)]
    assert titles[0] == "Alcaraz vs Sinner"  # in-progress leads


def test_tennis_never_raises_on_junk() -> None:
    assert individual.parse_tennis(b"x", now_ms=_NOW_MS) == []
    assert individual.parse_tennis(b'{"events":[{"groupings":[]}]}', now_ms=_NOW_MS) == []


# ---- F1 race cards ----

def _f1(sessions, *, state="pre", date_offset_ms=24 * 60 * 60 * 1000, short="MSC Cruises Barcelona-Catalunya GP"):
    return json.dumps({"events": [{
        "name": short, "shortName": short, "date": _iso(date_offset_ms),
        "status": {"type": {"state": state}}, "competitions": sessions,
    }]}).encode()


def _session(abbr, state, *, drivers=(), detail=""):
    comps = [{"order": i + 1, "athlete": {"displayName": f"X {d}"}} for i, d in enumerate(drivers)]
    return {"type": {"abbreviation": abbr}, "status": {"type": {"state": state, "shortDetail": detail}},
            "competitors": comps}


def test_f1_upcoming_shows_race_start() -> None:
    body = _f1([_session("FP1", "pre", detail="6/12 - 7:30 AM"),
                _session("Race", "pre", detail="6/14 - 9:00 AM EDT")])
    c = individual.parse_f1(body, now_ms=_NOW_MS)[0].card
    assert c.league == "F1" and c.kind == "race"
    assert c.title == "Barcelona-Catalunya GP"   # location + GP from the long name
    assert c.status == "6/14 - 9:00 AM EDT"
    assert c.state == "pre" and c.lines == []


def test_f1_finished_race_shows_podium() -> None:
    body = _f1([_session("Race", "post", drivers=["Verstappen", "Norris", "Leclerc", "Russell"], detail="Final")],
               state="in", date_offset_ms=0)
    c = individual.parse_f1(body, now_ms=_NOW_MS)[0].card
    assert c.lines == ["1. Verstappen", "2. Norris", "3. Leclerc"]
    assert c.state == "post"


def test_f1_far_future_is_dropped() -> None:
    body = _f1([_session("Race", "pre", detail="x")], date_offset_ms=10 * 24 * 60 * 60 * 1000)
    assert individual.parse_f1(body, now_ms=_NOW_MS) == []


def test_f1_never_raises_on_junk() -> None:
    assert individual.parse_f1(b"x", now_ms=_NOW_MS) == []
    assert individual.parse_f1(b'{"events":[]}', now_ms=_NOW_MS) == []
