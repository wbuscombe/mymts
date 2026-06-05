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
class TickerEntryDTO:
    """One ticker cell, wire-shaped for the `/api/ticker/*` envelope.

    `symbol` is the short label ("S&P 500", "BTC", "MLB"); `display` is
    the preformatted value the TV draws verbatim; `direction` is one of
    the DIR_* tokens; `is_sample` is the honesty flag.
    """

    symbol: str
    display: str
    direction: str
    is_sample: bool

    def to_json(self) -> dict[str, object]:
        return asdict(self)
