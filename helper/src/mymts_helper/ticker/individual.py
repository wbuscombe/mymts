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


# How many fights a UFC card surfaces (the headline bouts — a full card has
# ~12, mostly prelims). ESPN lists prelims→main, so we read in reverse.
UFC_MAX_FIGHTS = 5


def _fighter(competitor: dict) -> str:
    """Compact fighter label — ESPN `shortName` ('S. Garcia'), else last word
    of `displayName`. Reuses the athlete-short logic."""
    return _athlete_short(competitor)


def parse_ufc(body: bytes, *, now_ms: int | None = None) -> list[TickerEntryDTO]:
    """Parse the ESPN mma/ufc scoreboard into `fight` cards — the current
    event's headline bouts (one card per fight). `[]` if no current event.
    Never raises.
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
    if not _is_current_event(_state(ev), _event_start_ms(ev), now):
        return []
    comps = ev.get("competitions")
    if not isinstance(comps, list) or not comps:
        return []

    out: list[TickerEntryDTO] = []
    for c in reversed(comps):  # main event is typically last → headline first
        if not isinstance(c, dict):
            continue
        cmps = [x for x in (c.get("competitors") or []) if isinstance(x, dict)]
        if len(cmps) != 2:
            continue
        cmps.sort(key=_competitor_order)
        a, b = cmps[0], cmps[1]
        na, nb = _fighter(a), _fighter(b)
        if not na or not nb:
            continue

        fstate = _state(c)
        if fstate == "post":
            winner = a if a.get("winner") is True else (b if b.get("winner") is True else None)
            if winner is not None:
                loser = b if winner is a else a
                title = f"{_fighter(winner)} def. {_fighter(loser)}"
            else:
                title = f"{na} vs {nb}"   # a draw / no-contest — never invent a winner
        else:
            title = f"{na} vs {nb}"

        wclass = _str((c.get("type") or {}).get("text") if isinstance(c.get("type"), dict) else "")
        card = SportCardDTO(
            league="UFC",
            kind="fight",
            title=title[:34],
            state=fstate if fstate in ("pre", "in", "post") else "pre",
            status=_status_short(c),
            lines=[wclass] if wclass else [],
        )
        display = f"{title} · {card.status}".strip(" ·")
        out.append(TickerEntryDTO("UFC", display, DIR_NONE, is_sample=False, card=card))
        if len(out) >= UFC_MAX_FIGHTS:
            break
    return out


# ---- Tennis (match-sets) ----

TENNIS_MAX_MATCHES = 6


def _last_name(competitor: dict) -> str:
    ath = competitor.get("athlete")
    if not isinstance(ath, dict):
        return ""
    disp = ath.get("displayName") or ath.get("shortName")
    if isinstance(disp, str) and disp.strip():
        return disp.strip().split()[-1][:14]
    return ""


def _ls_val(ls: object) -> str | None:
    if not isinstance(ls, dict):
        return None
    v = ls.get("value")
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return str(int(v))
    return None


def _set_scores(a: dict, b: dict) -> str:
    """Build a compact set-score line ('6-1 7-6(3)') from two competitors'
    per-set `linescores`. Bounded; never raises."""
    la = a.get("linescores") if isinstance(a.get("linescores"), list) else []
    lb = b.get("linescores") if isinstance(b.get("linescores"), list) else []
    out: list[str] = []
    for i in range(min(len(la), len(lb))):
        va, vb = _ls_val(la[i]), _ls_val(lb[i])
        if va is None or vb is None:
            continue
        s = f"{va}-{vb}"
        tb = (la[i].get("tiebreak") if isinstance(la[i], dict) else None) or \
             (lb[i].get("tiebreak") if isinstance(lb[i], dict) else None)
        if isinstance(tb, (int, float)) and not isinstance(tb, bool):
            s += f"({int(tb)})"
        out.append(s)
    return " ".join(out)


def parse_tennis(body: bytes, *, now_ms: int | None = None) -> list[TickerEntryDTO]:
    """Parse the ESPN tennis (atp/wta) scoreboard into `match` cards. Matches
    nest under `event.groupings[].competitions[]` (the top-level `competitions`
    is empty — findings/21). Shows in-progress matches first, then recent
    finals, capped. `[]` if nothing current. Never raises.
    """
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return []
    if not isinstance(data, dict):
        return []
    events = data.get("events")
    if not isinstance(events, list):
        return []

    live: list[TickerEntryDTO] = []
    finals: list[TickerEntryDTO] = []
    for ev in events:
        if not isinstance(ev, dict):
            continue
        for g in (ev.get("groupings") or []):
            if not isinstance(g, dict):
                continue
            for c in (g.get("competitions") or []):
                if not isinstance(c, dict):
                    continue
                cmps = [x for x in (c.get("competitors") or []) if isinstance(x, dict)]
                if len(cmps) != 2:
                    continue
                mstate = _state(c)
                if mstate not in ("in", "post"):
                    continue  # skip the rest of the (large) upcoming draw
                a, b = cmps[0], cmps[1]
                na, nb = _last_name(a), _last_name(b)
                if not na or not nb:
                    continue
                if mstate == "post":
                    w = a if a.get("winner") is True else (b if b.get("winner") is True else None)
                    if w is not None:
                        loser = b if w is a else a
                        title = f"{_last_name(w)} d. {_last_name(loser)}"
                        sets = _set_scores(w, loser)
                    else:
                        title, sets = f"{na} vs {nb}", _set_scores(a, b)
                else:
                    title, sets = f"{na} vs {nb}", _set_scores(a, b)
                card = SportCardDTO(
                    league="Tennis", kind="match", title=title[:30],
                    state=mstate, status=_status_short(c), lines=[sets] if sets else [],
                )
                display = f"{title} {sets}".strip()
                entry = TickerEntryDTO("Tennis", display, DIR_NONE, is_sample=False, card=card)
                (live if mstate == "in" else finals).append(entry)
    return (live + finals)[:TENNIS_MAX_MATCHES]


# ---- F1 (race weekend) ----

def _f1_title(name: object) -> str:
    """Compact GP name — keep the location + 'GP' from ESPN's shortName
    ('MSC Cruises Barcelona-Catalunya GP' → 'Barcelona-Catalunya GP')."""
    n = _str(name)
    toks = n.split()
    if "GP" in toks:
        i = toks.index("GP")
        return " ".join(toks[max(0, i - 1):i + 1])[:24]
    return n[:24]


def _f1_driver(competitor: dict) -> str:
    ath = competitor.get("athlete")
    if not isinstance(ath, dict):
        return ""
    disp = ath.get("displayName") or ath.get("shortName")
    if isinstance(disp, str) and disp.strip():
        return disp.strip().split()[-1][:12]
    return ""


def parse_f1(body: bytes, *, now_ms: int | None = None) -> list[TickerEntryDTO]:
    """Parse the ESPN racing/f1 scoreboard into a `race` card. A finished/live
    Race session → the podium (top 3); an upcoming weekend → the race start.
    `[]` if the weekend isn't near. Never raises.
    """
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    try:
        data = json.loads(body)
    except (json.JSONDecodeError, ValueError):
        return []
    if not isinstance(data, dict):
        return []
    events = data.get("events")
    if not isinstance(events, list) or not events or not isinstance(events[0], dict):
        return []
    ev = events[0]
    if not _is_current_event(_state(ev), _event_start_ms(ev), now):
        return []

    title = _f1_title(ev.get("shortName") or ev.get("name"))
    comps = [c for c in (ev.get("competitions") or []) if isinstance(c, dict)]
    race = next(
        (c for c in comps
         if isinstance(c.get("type"), dict) and str(c["type"].get("abbreviation")).lower() == "race"),
        None,
    )

    lines: list[str] = []
    state = _state(ev) if _state(ev) in ("pre", "in", "post") else "pre"
    status = _status_short(ev)
    if race is not None:
        rstate = _state(race)
        rcmps = [x for x in (race.get("competitors") or []) if isinstance(x, dict)]
        if rstate in ("in", "post") and rcmps:
            podium = sorted(rcmps, key=_competitor_order)[:3]
            lines = [f"{i + 1}. {_f1_driver(c)}" for i, c in enumerate(podium) if _f1_driver(c)]
            status = _status_short(race) or "Race"
            state = rstate
        else:
            status = _status_short(race) or status  # upcoming → the race start time
            state = "pre"
    card = SportCardDTO(league="F1", kind="race", title=title, state=state, status=status, lines=lines)
    display = f"{title} · {status}".strip(" ·")
    return [TickerEntryDTO("F1", display, DIR_NONE, is_sample=False, card=card)]


# Individual-sport leagues the helper fetches, paired with their parser.
# (display label, ESPN sport, ESPN league, parser)
INDIVIDUAL_LEAGUES = [
    ("PGA", "golf", "pga", parse_pga),
    ("UFC", "mma", "ufc", parse_ufc),
    ("Tennis", "tennis", "atp", parse_tennis),
    ("F1", "racing", "f1", parse_f1),
]
