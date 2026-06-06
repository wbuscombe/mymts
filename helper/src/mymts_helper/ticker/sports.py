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
import time
from datetime import datetime

from . import DIR_NONE, TickerEntryDTO

log = logging.getLogger("mymts_helper.ticker.sports")

# Current-games window (ESPN-BottomLine-style "what's on now", 2026-06-06).
# ESPN's scoreboard returns the next scheduled games even when a league is
# off-season — e.g. in June the NFL endpoint returns SEPTEMBER preseason
# fixtures (`state=pre`, dated months out). Those are NOT ticker-worthy:
# a live ticker shows what's current, not a schedule weeks away. So we keep
# a game only if it's:
#   - in-progress  (state == "in")            → always current,
#   - a recent final (state == "post")        → within FINAL_WINDOW back,
#   - scheduled soon (state == "pre")         → within UPCOMING_WINDOW fwd.
# Everything else (future fixtures, yesterday's finals) is dropped; a league
# with zero current games is omitted from the ticker entirely. This is C3
# honesty applied to sports — show what's current, show nothing (not stale
# future fixtures) for a league with nothing on.
FINAL_WINDOW_MS = 12 * 60 * 60 * 1000      # a final from up to ~12h ago is "today"
UPCOMING_WINDOW_MS = 12 * 60 * 60 * 1000   # a game starting within ~12h is "later today"

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


def parse_scoreboard(
    body: bytes,
    *,
    league_label: str,
    now_ms: int | None = None,
) -> list[TickerEntryDTO]:
    """Parse one league's ESPN scoreboard JSON into **current** ticker entries.

    Only ticker-worthy games are returned (see the window constants above):
    in-progress always, recent finals, and games starting soon — far-future
    fixtures and stale results are dropped. A league with no current games
    yields `[]` (the caller omits it). `now_ms` is injectable for tests.

    Each entry: symbol = league label, display per status —
      in-progress → "AWY 4–6 HOM · Bot 9th"
      final       → "AWY 4–6 HOM · Final"
      scheduled   → "AWY @ HOM · 8:20 PM EDT"  (today only)
    Returns [] on any structural problem (never raises). Direction is
    DIR_NONE — sports have no up/down semantics, so the TV draws no arrow.
    """
    now = now_ms if now_ms is not None else int(time.time() * 1000)
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

    for ev in events:
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

        state = _state(ev)
        if not _is_current(state, _event_start_ms(ev), now):
            continue  # drop off-season / far-future / stale games

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

        display = _format_game(state, ev, away_abbr, home_abbr, away.get("score"), home.get("score"))
        out.append(
            TickerEntryDTO(
                symbol=league_label,
                display=display,
                direction=DIR_NONE,
                is_sample=False,
            )
        )
        if len(out) >= MAX_GAMES_PER_LEAGUE:
            break
    return out


def _is_current(state: str, start_ms: int | None, now_ms: int) -> bool:
    """The ticker-worthy test (ESPN-BottomLine-style 'what's on now')."""
    if state == "in":
        return True  # live always shows
    if start_ms is None:
        # No parseable date: only trust it if it's actually in progress
        # (handled above). Without a date we can't prove it's current → drop.
        return False
    if state == "post":
        # A recent final ("today"). Drop yesterday's-and-older results.
        return 0 <= (now_ms - start_ms) <= FINAL_WINDOW_MS
    if state == "pre":
        # Scheduled soon ("later today"). Drop fixtures days/weeks away —
        # this is what suppresses NFL preseason shown months early in June.
        return 0 <= (start_ms - now_ms) <= UPCOMING_WINDOW_MS
    return False


def _format_game(
    state: str, ev: dict, away_abbr: str, home_abbr: str, away_raw: object, home_raw: object
) -> str:
    if state == "pre":
        # Scheduled: no meaningful score yet — show the matchup + start time.
        when = _status_short(ev)
        base = f"{away_abbr} @ {home_abbr}"
        return f"{base} · {when}" if when else base
    # in-progress or final: score line + status detail.
    away_score = _clean_score(away_raw)
    home_score = _clean_score(home_raw)
    status_short = _status_short(ev)
    display = f"{away_abbr} {away_score}–{home_score} {home_abbr}"
    return f"{display} · {status_short}" if status_short else display


def _state(ev: dict) -> str:
    status = ev.get("status")
    if not isinstance(status, dict):
        return ""
    stype = status.get("type")
    if not isinstance(stype, dict):
        return ""
    s = stype.get("state")
    return s if isinstance(s, str) else ""


def _event_start_ms(ev: dict) -> int | None:
    """Parse the ESPN event `date` (ISO-8601, often '...Z' or '+00:00') to
    epoch ms. Returns None on anything unparseable (never raises)."""
    raw = ev.get("date")
    if not isinstance(raw, str) or not raw.strip():
        return None
    s = raw.strip().replace("Z", "+00:00")
    try:
        return int(datetime.fromisoformat(s).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


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
