"""Sports ticker data — keyless ESPN public scoreboard JSON.

ESPN exposes a public, keyless scoreboard endpoint per league
(`https://site.api.espn.com/apis/site/v2/sports/<sport>/<league>/scoreboard`)
that its own apps consume. It is **undocumented** (could change or
disappear) — but it is public, read-only, requires no auth, and is not
gated content. Honest staleness covers the "it vanished" case: if a
league stops returning events the ticker shows nothing for it rather
than frozen scores. (ToS posture confirmed with the operator: personal
single-box, non-commercial use; far milder than stream extraction.)

Parsing is strict and defensive (A1): every nested field is fetched
with `.get()` and type-checked; a malformed event is skipped, never
trusted. Scores are coerced through `str()` of whatever ESPN sends and
bounded in length — no numeric assumptions that could throw.
"""

from __future__ import annotations

import json
import logging

from . import DIR_NONE, TickerEntryDTO

log = logging.getLogger("mymts_helper.ticker.sports")

# Default curated leagues. The operator's feedback named MLB/NFL/NBA/NHL;
# per-team/league curation UI is a deferred follow-on (BACKLOG).
DEFAULT_LEAGUES: list[tuple[str, str, str]] = [
    # (display label, ESPN sport, ESPN league)
    ("MLB", "baseball", "mlb"),
    ("NFL", "football", "nfl"),
    ("NBA", "basketball", "nba"),
    ("NHL", "hockey", "nhl"),
]

# Cap games per league so one busy night can't flood the marquee.
MAX_GAMES_PER_LEAGUE = 8
MAX_SCORE_LEN = 4


def scoreboard_url(sport: str, league: str) -> str:
    return f"https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard"


def _clean_score(raw: object) -> str:
    s = str(raw).strip() if raw is not None else ""
    if len(s) > MAX_SCORE_LEN:
        s = s[:MAX_SCORE_LEN]
    # Keep only digit-ish content; ESPN scores are integers but defend anyway.
    return s if s and all(ch.isdigit() for ch in s) else "0"


def parse_scoreboard(body: bytes, *, league_label: str) -> list[TickerEntryDTO]:
    """Parse one league's ESPN scoreboard JSON into ticker entries.

    Each entry: symbol = league label, display = "AWY 4–6 HOM · Final".
    Returns [] on any structural problem (never raises). Direction is
    DIR_NONE — sports have no up/down semantics, so the TV draws no arrow.
    """
    out: list[TickerEntryDTO] = []
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return out
    if not isinstance(data, dict):
        return out
    events = data.get("events")
    if not isinstance(events, list):
        return out

    for ev in events[:MAX_GAMES_PER_LEAGUE]:
        if not isinstance(ev, dict):
            continue
        comps = ev.get("competitions")
        if not isinstance(comps, list) or not comps:
            continue
        comp = comps[0]
        if not isinstance(comp, dict):
            continue
        competitors = comp.get("competitors")
        if not isinstance(competitors, list) or len(competitors) != 2:
            continue

        home = away = None
        for c in competitors:
            if not isinstance(c, dict):
                continue
            side = c.get("homeAway")
            if side == "home":
                home = c
            elif side == "away":
                away = c
        if home is None or away is None:
            continue

        away_abbr = _abbr(away)
        home_abbr = _abbr(home)
        if not away_abbr or not home_abbr:
            continue
        away_score = _clean_score(away.get("score"))
        home_score = _clean_score(home.get("score"))

        status_short = _status_short(ev)
        display = f"{away_abbr} {away_score}–{home_score} {home_abbr}"
        if status_short:
            display = f"{display} · {status_short}"

        out.append(
            TickerEntryDTO(
                symbol=league_label,
                display=display,
                direction=DIR_NONE,
                is_sample=False,
            )
        )
    return out


def _abbr(competitor: dict) -> str:
    team = competitor.get("team")
    if not isinstance(team, dict):
        return ""
    abbr = team.get("abbreviation")
    if isinstance(abbr, str) and abbr.strip():
        return abbr.strip()[:5]
    # Fall back to short display name if abbreviation is absent.
    sd = team.get("shortDisplayName")
    return sd.strip()[:5] if isinstance(sd, str) and sd.strip() else ""


def _status_short(ev: dict) -> str:
    status = ev.get("status")
    if not isinstance(status, dict):
        return ""
    stype = status.get("type")
    if not isinstance(stype, dict):
        return ""
    sd = stype.get("shortDetail") or stype.get("description") or ""
    return str(sd).strip()[:24] if sd else ""


# Phantom / no-source sample slate — clearly marked is_sample=True.
SAMPLE_SPORTS: list[TickerEntryDTO] = [
    TickerEntryDTO("MLB", "NYY 4–3 BOS · Final", DIR_NONE, is_sample=True),
    TickerEntryDTO("NBA", "LAL 0–0 BOS · 7:30 ET", DIR_NONE, is_sample=True),
    TickerEntryDTO("NHL", "NYR 2–1 TOR · 2nd", DIR_NONE, is_sample=True),
]


def no_games_entry() -> TickerEntryDTO:
    """A truthful 'nothing on' entry (NOT sample — it's a real state)."""
    return TickerEntryDTO("SPORTS", "no games right now", DIR_NONE, is_sample=False)
