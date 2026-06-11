"""Individual-sports ticker data — the structurally-different leagues that
don't fit the team-vs-team `GameDTO` (2026-06-11).

ESPN's keyless scoreboard exposes each as its own shape (see
`docs/findings/21`):
  - **PGA** (`golf/pga`)   — a tournament = one leaderboard (~150 players,
    score-to-par + rank) → a `leaderboard` card.
  - **UFC** (`mma/ufc`)    — an event = several fights (2 athletes each) →
    one `fight` card per bout. *(staged — added next)*
  - **Tennis** (`tennis/atp|wta`) — matches nest under
    `event.groupings[].competitions[].linescores` → a `match` card. *(staged)*
  - **F1** (`racing/f1`)   — a race weekend = sessions; results populate once
    a session runs → a `race` card. *(staged)*

Same boundary discipline as `sports.py` (A1): every nested field is fetched
with `.get()` + type-checked, bounded, and reduced to inert primitives;
nothing raises on malformed input. Honest (C3): an event that isn't current
(off-season / far-future / long-finished) yields `[]` — the league is omitted
rather than shown stale. Keyless — no new secret.
"""

from __future__ import annotations

import json
import time

from . import DIR_NONE, SportCardDTO, TickerEntryDTO
from .sports import _event_start_ms, _state, _status_short, scoreboard_url

__all__ = ["scoreboard_url", "parse_pga", "INDIVIDUAL_LEAGUES"]

# How many leaderboard rows a PGA card shows (the leader + a couple chasers —
# a ticker can't show 150). Kept small for 10-ft legibility.
PGA_TOP_N = 3

# "Current event" windows for individual sports — wider than the team-game
# windows because a tournament/weekend spans multiple days.
_PRE_WINDOW_MS = 4 * 24 * 60 * 60 * 1000     # an event starting within ~4 days
_POST_WINDOW_MS = 2 * 24 * 60 * 60 * 1000    # a result from within ~2 days


def _is_current_event(state: str, start_ms: int | None, now_ms: int) -> bool:
    """Show a live event always; an upcoming/finished one only if it's near
    today (so a tournament from weeks ago / months out is dropped)."""
    if state == "in":
        return True
    if start_ms is None:
        return False
    if state == "pre":
        return 0 <= (start_ms - now_ms) <= _PRE_WINDOW_MS
    if state == "post":
        return 0 <= (now_ms - start_ms) <= (_POST_WINDOW_MS + _PRE_WINDOW_MS)
    return False


def _str(v: object) -> str:
    return v.strip() if isinstance(v, str) and v.strip() else ""


def _athlete_short(competitor: dict) -> str:
    """A compact athlete label for a ticker row — prefer ESPN's `shortName`
    ('S. Theegala'), else the last word of `displayName`, bounded."""
    ath = competitor.get("athlete")
    if not isinstance(ath, dict):
        return ""
    short = ath.get("shortName")
    if isinstance(short, str) and short.strip():
        return short.strip()[:18]
    disp = ath.get("displayName")
    if isinstance(disp, str) and disp.strip():
        return disp.strip().split()[-1][:18]
    return ""


def _to_par(raw: object) -> str:
    """Normalise a golf score-to-par to a compact token: 0 → 'E', else keep
    ESPN's signed string ('-6', '+2'). Bounded; never raises."""
    s = str(raw).strip() if raw is not None else ""
    if s in ("", "0", "E", "e"):
        return "E"
    return s[:4]


def _competitor_order(c: dict) -> int:
    o = c.get("order")
    if isinstance(o, (int, float)) and not isinstance(o, bool):
        return int(o)
    return 9999  # unranked sinks to the bottom


def parse_pga(body: bytes, *, now_ms: int | None = None) -> list[TickerEntryDTO]:
    """Parse the ESPN golf/pga scoreboard into a single `leaderboard` card
    (the current tournament's top players), or `[]` if nothing is current.
    Never raises on malformed input.
    """
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return []
    if not isinstance(data, dict):
        return []
    events = data.get("events")
    if not isinstance(events, list) or not events:
        return []
    ev = events[0]
    if not isinstance(ev, dict):
        return []

    state = _state(ev)
    if not _is_current_event(state, _event_start_ms(ev), now):
        return []

    title = _str(ev.get("name")) or _str(ev.get("shortName"))
    if not title:
        return []

    comps = ev.get("competitions")
    comp = comps[0] if isinstance(comps, list) and comps else None
    competitors = comp.get("competitors") if isinstance(comp, dict) else None
    if not isinstance(competitors, list) or not competitors:
        return []

    ranked = sorted(
        (c for c in competitors if isinstance(c, dict)),
        key=_competitor_order,
    )
    lines: list[str] = []
    for c in ranked[:PGA_TOP_N]:
        name = _athlete_short(c)
        if not name:
            continue
        # Pre-tournament has no score yet — list the player without a phantom par.
        score = "" if state == "pre" else _to_par(c.get("score"))
        lines.append(f"{name} {score}".strip())
    if not lines:
        return []

    status = _status_short(ev)
    card = SportCardDTO(
        league="PGA",
        kind="leaderboard",
        title=title[:40],
        state=state if state in ("pre", "in", "post") else "pre",
        status=status,
        lines=lines,
    )
    display = f"{title} · " + " · ".join(lines)
    return [TickerEntryDTO("PGA", display, DIR_NONE, is_sample=False, card=card)]


# Individual-sport leagues the helper fetches, paired with their parser. Each
# ships one at a time (PGA first); the rest are added as their cards land.
# (display label, ESPN sport, ESPN league, parser)
INDIVIDUAL_LEAGUES = [
    ("PGA", "golf", "pga", parse_pga),
]
