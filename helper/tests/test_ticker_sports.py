"""Defensive-parse tests for the sports ticker (ESPN scoreboard JSON)."""

from __future__ import annotations

import json

from mymts_helper.ticker import DIR_NONE
from mymts_helper.ticker import sports


def _event(away_abbr, away_score, home_abbr, home_score, status="Final"):
    return {
        "competitions": [
            {
                "competitors": [
                    {"homeAway": "home", "score": home_score,
                     "team": {"abbreviation": home_abbr}},
                    {"homeAway": "away", "score": away_score,
                     "team": {"abbreviation": away_abbr}},
                ]
            }
        ],
        "status": {"type": {"shortDetail": status}},
    }


def _body(events) -> bytes:
    return json.dumps({"events": events}).encode("utf-8")


def test_parses_event_into_scoreline() -> None:
    body = _body([_event("SD", "4", "PHI", "6", status="Final")])
    out = sports.parse_scoreboard(body, league_label="MLB")
    assert len(out) == 1
    e = out[0]
    assert e.symbol == "MLB"
    assert e.display == "SD 4–6 PHI · Final"
    assert e.direction == DIR_NONE
    assert e.is_sample is False


def test_caps_games_per_league() -> None:
    events = [_event("A", str(i), "B", str(i + 1)) for i in range(20)]
    out = sports.parse_scoreboard(_body(events), league_label="NBA")
    assert len(out) == sports.MAX_GAMES_PER_LEAGUE


def test_skips_malformed_events_without_raising() -> None:
    events = [
        {"competitions": []},                       # no competition
        {"competitions": [{"competitors": []}]},    # no competitors
        {"not": "an event"},
        _event("NYR", "2", "TOR", "1", status="2nd"),  # one good
    ]
    out = sports.parse_scoreboard(_body(events), league_label="NHL")
    assert len(out) == 1
    assert out[0].display == "NYR 2–1 TOR · 2nd"


def test_non_numeric_score_coerced_safely() -> None:
    # A hostile/garbage score must not throw and must not be trusted.
    ev = _event("AAA", "not-a-number", "BBB", "3")
    out = sports.parse_scoreboard(_body([ev]), league_label="MLB")
    assert len(out) == 1
    assert "BBB" in out[0].display
    # away score sanitized to "0" (non-digit dropped)
    assert out[0].display.startswith("AAA 0")


def test_never_raises_on_junk_body() -> None:
    assert sports.parse_scoreboard(b"not json", league_label="MLB") == []
    assert sports.parse_scoreboard(b'{"events": "not a list"}', league_label="MLB") == []
    assert sports.parse_scoreboard(b"[]", league_label="MLB") == []  # not a dict


def test_falls_back_to_short_name_when_no_abbreviation() -> None:
    ev = {
        "competitions": [{"competitors": [
            {"homeAway": "home", "score": "1", "team": {"shortDisplayName": "Kings"}},
            {"homeAway": "away", "score": "2", "team": {"shortDisplayName": "Ducks"}},
        ]}],
        "status": {"type": {"shortDetail": "Final"}},
    }
    out = sports.parse_scoreboard(_body([ev]), league_label="NHL")
    assert len(out) == 1
    assert "Ducks" in out[0].display and "Kings" in out[0].display


def test_sample_slate_is_all_sample() -> None:
    assert sports.SAMPLE_SPORTS
    assert all(e.is_sample for e in sports.SAMPLE_SPORTS)


def test_no_games_entry_is_truthful_not_sample() -> None:
    e = sports.no_games_entry()
    assert e.is_sample is False  # "no games" is a real state, not sample data
    assert "no games" in e.display.lower()
