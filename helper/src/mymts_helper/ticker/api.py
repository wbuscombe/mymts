"""/api/ticker/{markets,sports} endpoints.

Serve the latest in-memory snapshot held by the pollers. The TV's
`TickerSource` consumes these; it never fetches market/sports data
directly (same boundary as feed/channels — the helper is the only thing
that talks to upstreams).

Envelope (schema_version pinned, additive-only like feed/channels):
  {
    "schema_version": 1,
    "mode": "markets" | "sports",
    "as_of": ISO-8601 | null,    # last real fetch; null before first / phantom
    "stale": bool,               # real data aged past threshold
    "entries": [ {symbol, display, direction, is_sample}, ... ]
  }

`stale` is true only when we HAVE had real data and it has aged past
the threshold — an all-sample snapshot (pre-first-poll / phantom) is
honest sample, not stale.
"""

from __future__ import annotations

import time
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter

from . import TICKER_SCHEMA_VERSION
from .pollers import MarketsPoller, SportsPoller

# Real data older than this is surfaced as stale at the envelope level.
STALE_AFTER_SECONDS = 15 * 60


def _is_stale(real_as_of: str | None) -> bool:
    if real_as_of is None:
        return False  # all-sample: honest sample, not stale
    try:
        ts = datetime.strptime(real_as_of, "%Y-%m-%dT%H:%M:%S.000Z").replace(tzinfo=UTC)
    except (ValueError, TypeError):
        return False
    age = time.time() - ts.timestamp()
    return age > STALE_AFTER_SECONDS


def get_router(markets: MarketsPoller, sports: SportsPoller) -> APIRouter:
    router = APIRouter(prefix="/api/ticker", tags=["ticker"])

    @router.get("/markets")
    def markets_ticker() -> dict[str, Any]:
        return {
            "schema_version": TICKER_SCHEMA_VERSION,
            "mode": "markets",
            "as_of": markets.real_as_of,
            "stale": _is_stale(markets.real_as_of),
            "entries": [e.to_json() for e in markets.entries],
        }

    @router.get("/sports")
    def sports_ticker() -> dict[str, Any]:
        return {
            "schema_version": TICKER_SCHEMA_VERSION,
            "mode": "sports",
            "as_of": sports.real_as_of,
            "stale": _is_stale(sports.real_as_of),
            "entries": [e.to_json() for e in sports.entries],
        }

    return router
