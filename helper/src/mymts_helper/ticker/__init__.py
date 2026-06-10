"""Ticker data — real markets + sports, served to the TV's scrolling strip.

The TV's ticker was built (Stage 3) to consume any source behind a
`TickerSource` interface, with every entry carrying an `is_sample`
honesty flag. This package is the helper-side half of making that real:

  - `markets` — keyless quotes (Stooq CSV for indices/FX/gold,
    CoinGecko for BTC/ETH). Symbols with no free keyless source
    (Brent, WTI, 10Y UST) stay honest **sample** rather than faked.
  - `sports` — keyless ESPN public scoreboard JSON for MLB/NFL/NBA/NHL.
  - `api`     — `/api/ticker/markets` and `/api/ticker/sports`.

Boundary discipline (Trust Bar A1): every external response is treated
as hostile — fetched through the SSRF-safe `fetcher`, parsed strictly
and defensively (never `eval`/exec, bounded, fail-closed), and reduced
to inert primitives before it reaches the envelope. No API key is
required for any source, so the helper holds **no new secret**.

Honesty (Trust Bar C3): each entry carries `is_sample`. Real values
drop the flag; sample/unsupported values keep it; the TV decorates
sample entries with a visible SAMPLE pill. Stale real data is surfaced
at the envelope level (`stale`) rather than shown frozen as current.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

# Envelope schema version for /api/ticker/*. Bump only on a
# non-additive change (the TV pins this, same contract as feed/channels).
TICKER_SCHEMA_VERSION = 1

# Direction tokens the TV maps to its arrow glyphs. "none" renders no
# glyph — used for sports, which have no up/down semantics.
DIR_UP = "up"
DIR_DOWN = "down"
DIR_FLAT = "flat"
DIR_NONE = "none"


@dataclass(frozen=True)
class GameDTO:
    """Structured sports game — the BottomLine card payload (2026-06-10).

    Lets the TV draw a real game CARD (team abbrs + scores + a weighted
    status block) instead of parsing a flat `display` string. `state` is the
    ESPN lifecycle token (`pre` | `in` | `post`); `status` is ESPN's
    `shortDetail` ("Final", "5:42 - 1st", "9/9 - 8:20 PM EDT"). Scores are the
    already-cleaned digit strings (empty for a `pre` matchup). Additive: only
    sports entries carry a `game`; markets/news leave it `None`.
    """

    league: str
    away: str
    away_score: str
    home: str
    home_score: str
    state: str
    status: str

    def to_json(self) -> dict[str, object]:
        return asdict(self)


@dataclass(frozen=True)
class TickerEntryDTO:
    """One ticker cell, wire-shaped for the `/api/ticker/*` envelope.

    `symbol` is the short label ("S&P 500", "BTC", "MLB"); `display` is
    the preformatted value the TV draws verbatim; `direction` is one of
    the DIR_* tokens; `is_sample` is the honesty flag. `game` carries the
    structured card payload for sports (None for markets/news).
    """

    symbol: str
    display: str
    direction: str
    is_sample: bool
    game: GameDTO | None = None

    def to_json(self) -> dict[str, object]:
        d = asdict(self)
        # Additive on the wire: omit `game` entirely for markets/news so their
        # entries stay byte-identical to schema v1 (the TV pins the contract).
        if d.get("game") is None:
            d.pop("game", None)
        return d
