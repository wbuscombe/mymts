"""Direct orchestration tests for the markets + sports ticker POLLERS.

Closes review finding API-3: the value-level parsers (`parse_yahoo_chart`,
`parse_scoreboard`, `parse_pga`, …) are tested in `test_ticker_markets.py` /
`test_ticker_sports.py` / `test_ticker_individual.py`, but the *glue* in
`pollers.py` — fetch fan-out, per-symbol / per-league isolation, the
real_as_of stamp, the all-sample degradation, and the keep-prior-snapshot
on a total wipe — had zero direct coverage. This module drives
`MarketsPoller.poll_once` / `SportsPoller.poll_once` end-to-end with the
fetch boundary stubbed, asserting the honest-degradation invariants the
parsers can't (they never see the fan-out).

Injection idiom (mirrors the existing suite): the SSRF-safe `fetcher` takes
an injectable async `resolver`, and `respx` (already a dev dep, used by no
other test yet) intercepts httpx. The two are combined:

  * a public-IP resolver (`8.8.8.8`) lets a fetch through to a respx route,
    so we control the *body shape* (realistic ESPN / Yahoo / CoinGecko JSON,
    NOT mocked-green stubs that would pass even if upstream changed shape —
    the bodies flow through the REAL parsers);
  * a loopback resolver (`127.0.0.1`) makes the fetcher reject the address
    before any socket opens, modelling a total egress block — the same idiom
    `test_ticker_api.py` uses for its blocked-egress path.

No real network is touched.
"""

from __future__ import annotations

import json

import httpx
import respx

from mymts_helper.ticker import markets as markets_mod
from mymts_helper.ticker.pollers import MarketsPoller, SportsPoller

# ---------------------------------------------------------------------------
# resolvers (mirror test_fetcher.py's fake-resolver idiom)
# ---------------------------------------------------------------------------


async def _public_resolver(host: str) -> list[str]:
    """Resolve every host to a public IP so the fetcher proceeds to httpx
    (which respx then intercepts). Nothing leaves the box."""
    return ["8.8.8.8"]


async def _block_all_resolver(host: str) -> list[str]:
    """Resolve every host to loopback so the SSRF guard rejects it before a
    socket opens — models a total egress block (no respx needed)."""
    return ["127.0.0.1"]


# ---------------------------------------------------------------------------
# realistic upstream bodies (mirror the fixture shapes the value-tests use)
# ---------------------------------------------------------------------------

# Yahoo v8 chart response — same nesting the real endpoint returns and the
# real `parse_yahoo_chart` walks (`chart.result[0].meta.{regularMarketPrice,
# chartPreviousClose}`). Shape-faithful: drop a key and the parser drops it.
YAHOO_RE = r"https://query1\.finance\.yahoo\.com/v8/finance/chart/.*"
COINGECKO_RE = r"https://api\.coingecko\.com/api/v3/simple/price.*"
ESPN_RE = r"https://site\.api\.espn\.com/apis/site/v2/sports/.*"


def _yahoo_chart_body(price: float, prev: float | None) -> bytes:
    meta: dict[str, object] = {
        "currency": "USD",
        "symbol": "X",
        "regularMarketPrice": price,
    }
    if prev is not None:
        meta["chartPreviousClose"] = prev
    return json.dumps({"chart": {"result": [{"meta": meta}], "error": None}}).encode()


def _coingecko_body() -> bytes:
    return json.dumps(
        {
            "bitcoin": {"usd": 67210.0, "usd_24h_change": 1.4},
            "ethereum": {"usd": 3452.0, "usd_24h_change": -2.1},
        }
    ).encode()


def _espn_scoreboard_body(events: list[dict]) -> bytes:
    return json.dumps({"events": events}).encode()


def _espn_event(
    away: str,
    away_score: str,
    home: str,
    home_score: str,
    *,
    state: str,
    short: str,
    date_iso: str,
) -> dict:
    """One ESPN-shaped scoreboard event (the 2-competitor team-game shape the
    real `parse_scoreboard` consumes)."""
    return {
        "date": date_iso,
        "competitions": [
            {
                "competitors": [
                    {"homeAway": "home", "score": home_score,
                     "team": {"abbreviation": home}},
                    {"homeAway": "away", "score": away_score,
                     "team": {"abbreviation": away}},
                ]
            }
        ],
        "status": {"type": {"state": state, "shortDetail": short}},
    }


def _live_now_iso() -> str:
    """An ISO date 'now-ish' so a state=in/post game passes the current-games
    window in `parse_scoreboard` (live always shows, but a date is required
    for the entry to even reach scoring on non-live states)."""
    import time
    from datetime import UTC, datetime

    return datetime.fromtimestamp(time.time(), tz=UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


# Per-symbol Yahoo router: a side_effect that reads the requested symbol from
# the URL and serves a body — or a 404 / garbage for the symbols we want to
# fail, so we exercise the partial-degradation branch with real routing.
def _yahoo_side_effect(
    *, bad_symbols: set[str] | None = None, garbage_symbols: set[str] | None = None
):
    bad = bad_symbols or set()
    garbage = garbage_symbols or set()

    def _cb(request: httpx.Request) -> httpx.Response:
        # The path is the URL-quoted Yahoo symbol, e.g. /chart/%5EGSPC.
        from urllib.parse import unquote

        sym = unquote(request.url.path.rsplit("/", 1)[-1])
        if sym in bad:
            return httpx.Response(404)
        if sym in garbage:
            return httpx.Response(200, content=b"<html>bot wall, not json</html>")
        # Plausible per-symbol value; the real parser turns it into an entry.
        return httpx.Response(200, content=_yahoo_chart_body(100.0, 90.0))

    return _cb


# ===========================================================================
# MARKETS poll_once
# ===========================================================================


@respx.mock
async def test_markets_good_data_yields_real_entries_and_stamps_as_of() -> None:
    """(a) Every source answers → entries are real (is_sample False),
    real_as_of is stamped, and the snapshot is NOT all-sample."""
    respx.get(url__regex=YAHOO_RE).mock(side_effect=_yahoo_side_effect())
    respx.get(url__regex=COINGECKO_RE).mock(
        return_value=httpx.Response(200, content=_coingecko_body())
    )

    p = MarketsPoller(resolver=_public_resolver)
    assert p.real_as_of is None  # pristine before first poll
    await p.poll_once()

    by_symbol = {e.symbol: e for e in p.entries}
    # Full canonical list is always present (stable ticker shape).
    assert len(p.entries) == len(markets_mod.YAHOO_QUOTES) + len(markets_mod.COINGECKO_CRYPTO)
    # A real fetch happened this cycle → real_as_of stamped, not all-sample.
    assert p.real_as_of is not None
    assert not all(e.is_sample for e in p.entries), "good data must not be all-sample"
    # Specific real entries (drawn through the genuine parser + formatter).
    assert by_symbol["S&P 500"].is_sample is False
    assert by_symbol["BTC"].is_sample is False
    assert by_symbol["BTC"].display == "$67,210"
    assert by_symbol["ETH"].is_sample is False


@respx.mock
async def test_markets_partial_failure_degrades_per_entry_to_sample() -> None:
    """(b) One source blocked / garbage while another answers → ONLY the
    failed symbols fall back to honest sample; no crash, the rest stay real.
    This is the per-symbol isolation contract (C2)."""
    # ^DJI 404s; ^IXIC returns a bot-wall HTML page (garbage, parser → None);
    # everything else (incl. CoinGecko) answers cleanly.
    respx.get(url__regex=YAHOO_RE).mock(
        side_effect=_yahoo_side_effect(
            bad_symbols={"^DJI"}, garbage_symbols={"^IXIC"}
        )
    )
    respx.get(url__regex=COINGECKO_RE).mock(
        return_value=httpx.Response(200, content=_coingecko_body())
    )

    p = MarketsPoller(resolver=_public_resolver)
    await p.poll_once()  # must not raise

    by_symbol = {e.symbol: e for e in p.entries}
    # The two poisoned symbols degraded to honest sample…
    assert by_symbol["DOW"].is_sample is True  # ^DJI 404 → sample
    assert by_symbol["NASDAQ"].is_sample is True  # ^IXIC garbage → sample
    # …while a healthy sibling and the unrelated source stayed real.
    assert by_symbol["S&P 500"].is_sample is False
    assert by_symbol["BTC"].is_sample is False
    # A partial success still counts as real data this cycle.
    assert p.real_as_of is not None


@respx.mock
async def test_markets_coingecko_404_degrades_only_crypto() -> None:
    """(b') A clean HTTP >=400 from one whole source is an honest miss, not a
    parse-crash: CoinGecko 404 → BTC/ETH sample, Yahoo symbols still real."""
    respx.get(url__regex=YAHOO_RE).mock(side_effect=_yahoo_side_effect())
    respx.get(url__regex=COINGECKO_RE).mock(return_value=httpx.Response(503))

    p = MarketsPoller(resolver=_public_resolver)
    await p.poll_once()

    by_symbol = {e.symbol: e for e in p.entries}
    assert by_symbol["BTC"].is_sample is True
    assert by_symbol["ETH"].is_sample is True
    assert by_symbol["S&P 500"].is_sample is False
    assert p.real_as_of is not None  # Yahoo still gave real data


async def test_markets_total_egress_block_is_all_sample_not_crash() -> None:
    """(c) Total egress blocked (every fetch rejected by the SSRF guard) →
    full all-sample snapshot, NO crash / 500 / frozen-fake-live, and
    real_as_of stays None (sample is honest, not stale)."""
    p = MarketsPoller(resolver=_block_all_resolver)
    await p.poll_once()  # every fetch raises FetchError internally → swallowed

    assert len(p.entries) == len(markets_mod.YAHOO_QUOTES) + len(markets_mod.COINGECKO_CRYPTO)
    assert all(e.is_sample for e in p.entries), "blocked egress must be all-sample"
    assert p.real_as_of is None, "no real fetch → as_of stays None (honest, not stale)"


# ===========================================================================
# SPORTS poll_once
# ===========================================================================

# Two team leagues for a focused, deterministic test (avoids fetching the full
# eight-league + four-individual slate). The individual-league list is left at
# its default; we stub ESPN for ALL of them so nothing escapes.
_TWO_LEAGUES = [
    ("NBA", "basketball", "nba"),
    ("NHL", "hockey", "nhl"),
]


def _espn_router(*, bodies_by_league: dict[str, bytes], fail_leagues: set[str]):
    """A single side_effect routing every ESPN scoreboard request by the
    `<sport>/<league>` path segment, so per-league isolation is exercised with
    real routing — a 404 for one league, real JSON for another."""

    def _cb(request: httpx.Request) -> httpx.Response:
        # .../sports/<sport>/<league>/scoreboard
        parts = request.url.path.rstrip("/").split("/")
        league = parts[-2] if parts[-1] == "scoreboard" else parts[-1]
        if league in fail_leagues:
            return httpx.Response(404)
        body = bodies_by_league.get(league)
        if body is None:
            # Any league we didn't explicitly fixture (e.g. the individual
            # leagues PGA/UFC/…) returns an empty-but-valid scoreboard so the
            # fetch SUCCEEDS (any_ok) but contributes no games.
            return httpx.Response(200, content=_espn_scoreboard_body([]))
        return httpx.Response(200, content=body)

    return _cb


@respx.mock
async def test_sports_normal_scoreboard_surfaces_games() -> None:
    """(a) A league returns a normal live scoreboard → its games surface as
    real entries and real_as_of is stamped."""
    iso = _live_now_iso()
    nba_body = _espn_scoreboard_body(
        [_espn_event("LAL", "88", "BOS", "90", state="in", short="3rd 4:21", date_iso=iso)]
    )
    nhl_body = _espn_scoreboard_body(
        [_espn_event("NYR", "2", "TOR", "1", state="in", short="2nd", date_iso=iso)]
    )
    respx.get(url__regex=ESPN_RE).mock(
        side_effect=_espn_router(
            bodies_by_league={"nba": nba_body, "nhl": nhl_body}, fail_leagues=set()
        )
    )

    p = SportsPoller(resolver=_public_resolver, leagues=_TWO_LEAGUES)
    await p.poll_once()

    displays = [e.display for e in p.entries]
    assert any("LAL" in d and "BOS" in d for d in displays)
    assert any("NYR" in d and "TOR" in d for d in displays)
    assert p.real_as_of is not None
    # Every surfaced game entry is real (a live score, not a sample).
    game_entries = [e for e in p.entries if e.game is not None]
    assert game_entries and all(e.is_sample is False for e in game_entries)


@respx.mock
async def test_sports_one_league_404_others_unaffected() -> None:
    """(b) One league 404s while another succeeds → the failing league is
    simply omitted (no entries, no crash); the healthy league is unaffected.
    Per-league isolation."""
    iso = _live_now_iso()
    nhl_body = _espn_scoreboard_body(
        [_espn_event("NYR", "2", "TOR", "1", state="in", short="2nd", date_iso=iso)]
    )
    respx.get(url__regex=ESPN_RE).mock(
        side_effect=_espn_router(
            bodies_by_league={"nhl": nhl_body}, fail_leagues={"nba"}
        )
    )

    p = SportsPoller(resolver=_public_resolver, leagues=_TWO_LEAGUES)
    await p.poll_once()

    displays = [e.display for e in p.entries]
    # NHL surfaced…
    assert any("NYR" in d and "TOR" in d for d in displays)
    # …NBA (the 404'd league) contributed nothing — no LAL/BOS anywhere.
    assert not any("LAL" in d for d in displays)
    # A partial success still stamps real_as_of (NHL fetched OK).
    assert p.real_as_of is not None


@respx.mock
async def test_sports_all_leagues_fail_keeps_prior_snapshot() -> None:
    """(c) When EVERY league fails on a cycle, the prior snapshot is KEPT, not
    wiped to []. We first seed a good snapshot, then run a cycle where every
    fetch 404s, and assert the games from cycle 1 survive."""
    iso = _live_now_iso()
    nba_body = _espn_scoreboard_body(
        [_espn_event("LAL", "88", "BOS", "90", state="in", short="3rd 4:21", date_iso=iso)]
    )

    p = SportsPoller(resolver=_public_resolver, leagues=_TWO_LEAGUES)

    # Cycle 1: a real game lands.
    with respx.mock:
        respx.get(url__regex=ESPN_RE).mock(
            side_effect=_espn_router(
                bodies_by_league={"nba": nba_body}, fail_leagues={"nhl"}
            )
        )
        await p.poll_once()
    seeded = list(p.entries)
    as_of_1 = p.real_as_of
    assert any("LAL" in e.display for e in seeded)
    assert as_of_1 is not None

    # Cycle 2: EVERYTHING 404s (team leagues AND the default individual ones).
    with respx.mock:
        respx.get(url__regex=ESPN_RE).mock(return_value=httpx.Response(404))
        await p.poll_once()

    # Prior snapshot preserved verbatim — not wiped to [] or to "no games".
    assert p.entries == seeded, "total failure must keep the prior snapshot"
    assert any("LAL" in e.display for e in p.entries)
    # real_as_of was NOT advanced (no league fetched OK this cycle).
    assert p.real_as_of == as_of_1


@respx.mock
async def test_sports_all_fetched_but_no_current_games_is_truthful_no_games() -> None:
    """When every league fetches OK but none has a current game, the snapshot
    is the truthful 'no games right now' entry (NOT sample, NOT a stale
    leftover) — the any_ok-but-empty branch."""
    respx.get(url__regex=ESPN_RE).mock(
        side_effect=_espn_router(bodies_by_league={}, fail_leagues=set())
    )

    p = SportsPoller(resolver=_public_resolver, leagues=_TWO_LEAGUES)
    await p.poll_once()

    assert len(p.entries) == 1
    only = p.entries[0]
    assert only.is_sample is False
    assert "no games" in only.display.lower()
    assert p.real_as_of is not None  # leagues fetched OK, just had nothing on
