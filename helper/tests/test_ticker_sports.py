"""Defensive-parse + current-games-filter tests for the sports ticker.

The filter is the substantive logic (ESPN-BottomLine-style "what's on
now"): in-progress always shown; recent finals + soon-scheduled shown;
far-future fixtures / stale results dropped; an off-season league (only
months-away fixtures, as the NFL endpoint returns in June) yields no
entries. `now_ms` is injected so the window logic is deterministic.
"""

from __future__ import annotations

import json

from mymts_helper.ticker import DIR_NONE
from mymts_helper.ticker import sports

# Fixed reference clock for the tests (arbitrary; all offsets are relative).
NOW_MS = 1_780_000_000_000  # ms
HOUR = 60 * 60 * 1000


def _event(away_abbr, away_score, home_abbr, home_score, *, state="post",
           short="Final", date_ms=NOW_MS):
    """Build an ESPN-shaped event with a state + ISO date at date_ms."""
    from datetime import UTC, datetime
    iso = datetime.fromtimestamp(date_ms / 1000, tz=UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "date": iso,
        "competitions": [
            {
                "competitors": [
                    {"homeAway": "home", "score": home_score, "team": {"abbreviation": home_abbr}},
                    {"homeAway": "away", "score": away_score, "team": {"abbreviation": away_abbr}},
                ]
            }
        ],
        "status": {"type": {"state": state, "shortDetail": short}},
    }


def _body(events) -> bytes:
    return json.dumps({"events": events}).encode("utf-8")


def _parse(events):
    return sports.parse_scoreboard(_body(events), league_label="MLB", now_ms=NOW_MS)


# ---- formatting per status ----

def test_final_today_shows_scoreline() -> None:
    out = _parse([_event("SD", "4", "PHI", "6", state="post", short="Final", date_ms=NOW_MS - 2 * HOUR)])
    assert len(out) == 1
    assert out[0].display == "SD 4–6 PHI · Final"
    assert out[0].symbol == "MLB" and out[0].direction == DIR_NONE and out[0].is_sample is False


def test_in_progress_shows_score_and_period() -> None:
    out = _parse([_event("NYR", "2", "TOR", "1", state="in", short="2nd", date_ms=NOW_MS - HOUR)])
    assert out[0].display == "NYR 2–1 TOR · 2nd"


def test_scheduled_today_shows_matchup_and_time_not_score() -> None:
    out = _parse([_event("LAL", "0", "BOS", "0", state="pre", short="7:30 PM ET", date_ms=NOW_MS + 3 * HOUR)])
    assert out[0].display == "LAL @ BOS · 7:30 PM ET"   # matchup + time, no 0–0 score


# ---- the current-games filter (the substantive fix) ----

def test_far_future_fixtures_are_dropped_offseason_league_omitted() -> None:
    # The NFL-in-June case: all events scheduled months out → none current.
    events = [
        _event("KC", "0", "BAL", "0", state="pre", short="9/9 - 8:20 PM", date_ms=NOW_MS + 90 * 24 * HOUR),
        _event("DAL", "0", "PHI", "0", state="pre", short="9/13 - 1:00 PM", date_ms=NOW_MS + 94 * 24 * HOUR),
    ]
    assert _parse(events) == []   # league omitted entirely

def test_in_progress_always_shown_even_with_odd_date() -> None:
    # Live game shows regardless of date parse.
    out = _parse([_event("A", "1", "B", "2", state="in", short="Bot 9th", date_ms=NOW_MS + 999 * HOUR)])
    assert len(out) == 1

def test_stale_yesterday_final_is_dropped() -> None:
    out = _parse([_event("A", "1", "B", "2", state="post", short="Final", date_ms=NOW_MS - 30 * HOUR)])
    assert out == []   # final >12h ago → not "today" → dropped

def test_scheduled_far_out_dropped_but_scheduled_today_kept() -> None:
    soon = _event("A", "0", "B", "0", state="pre", short="8 PM", date_ms=NOW_MS + 4 * HOUR)
    later = _event("C", "0", "D", "0", state="pre", short="next week", date_ms=NOW_MS + 8 * 24 * HOUR)
    out = sports.parse_scoreboard(_body([soon, later]), league_label="MLB", now_ms=NOW_MS)
    assert len(out) == 1 and out[0].display.startswith("A @ B")

def test_mixed_league_keeps_only_current() -> None:
    events = [
        _event("L1", "3", "L2", "1", state="in", short="Top 5th", date_ms=NOW_MS),          # live → keep
        _event("F1", "5", "F2", "4", state="post", short="Final", date_ms=NOW_MS - 3 * HOUR),  # today final → keep
        _event("S1", "0", "S2", "0", state="pre", short="9/9", date_ms=NOW_MS + 90 * 24 * HOUR),  # months out → drop
        _event("Y1", "2", "Y2", "2", state="post", short="Final", date_ms=NOW_MS - 40 * HOUR),    # stale → drop
    ]
    out = sports.parse_scoreboard(_body(events), league_label="MLB", now_ms=NOW_MS)
    assert [e.display.split(" ")[0] for e in out] == ["L1", "F1"]


def test_caps_games_per_league() -> None:
    events = [_event(f"A{i}", str(i), f"B{i}", str(i + 1), state="in", short="1st", date_ms=NOW_MS) for i in range(20)]
    assert len(_parse(events)) == sports.MAX_GAMES_PER_LEAGUE


# ---- defensive parsing (never raises) ----

def test_skips_malformed_events_without_raising() -> None:
    events = [
        {"competitions": []},
        {"competitions": [{"competitors": []}]},
        {"not": "an event"},
        _event("NYR", "2", "TOR", "1", state="in", short="2nd", date_ms=NOW_MS),
    ]
    out = _parse(events)
    assert len(out) == 1 and out[0].display == "NYR 2–1 TOR · 2nd"


def test_non_numeric_score_coerced_safely() -> None:
    ev = _event("AAA", "not-a-number", "BBB", "3", state="post", short="Final", date_ms=NOW_MS)
    out = _parse([ev])
    assert len(out) == 1 and out[0].display.startswith("AAA 0")


def test_never_raises_on_junk_body() -> None:
    assert sports.parse_scoreboard(b"not json", league_label="MLB", now_ms=NOW_MS) == []
    assert sports.parse_scoreboard(b'{"events": "not a list"}', league_label="MLB", now_ms=NOW_MS) == []
    assert sports.parse_scoreboard(b"[]", league_label="MLB", now_ms=NOW_MS) == []


def test_event_with_no_date_and_not_live_is_dropped() -> None:
    ev = _event("A", "1", "B", "2", state="post", short="Final")
    del ev["date"]
    assert sports.parse_scoreboard(_body([ev]), league_label="MLB", now_ms=NOW_MS) == []


def test_falls_back_to_short_name_when_no_abbreviation() -> None:
    ev = {
        "date": _event("x", "0", "y", "0", state="in", date_ms=NOW_MS)["date"],
        "competitions": [{"competitors": [
            {"homeAway": "home", "score": "1", "team": {"shortDisplayName": "Kings"}},
            {"homeAway": "away", "score": "2", "team": {"shortDisplayName": "Ducks"}},
        ]}],
        "status": {"type": {"state": "in", "shortDetail": "Final"}},
    }
    out = sports.parse_scoreboard(_body([ev]), league_label="NHL", now_ms=NOW_MS)
    assert len(out) == 1 and "Ducks" in out[0].display and "Kings" in out[0].display


def test_sample_slate_is_all_sample() -> None:
    assert sports.SAMPLE_SPORTS and all(e.is_sample for e in sports.SAMPLE_SPORTS)


def test_no_games_entry_is_truthful_not_sample() -> None:
    e = sports.no_games_entry()
    assert e.is_sample is False and "no games" in e.display.lower()
