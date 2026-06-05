"""Markets + sports ticker pollers.

Each poller loops on a polite interval, fetches its keyless source(s)
through the SSRF-safe `fetcher`, builds a ticker snapshot, and holds the
**latest** snapshot in memory. Ticker data is ephemeral — only the
latest values matter — so there is no DB table, no migration, no
retention sweep, and crucially none of the cross-thread sqlite exposure
the feed path had: the snapshot is a plain list guarded by the asyncio
single-thread event loop and read directly by the (sync) endpoint.

Per-source isolation (C2): markets fetches Stooq and CoinGecko
independently; either failing degrades only its own symbols (they fall
back to honest sample), never the whole snapshot. Sports fetches each
league independently.

Honesty (C3): `real_as_of` records the last time a real value was
fetched. The endpoint marks the envelope `stale` when real data has
aged past a threshold; before any real fetch (and in phantom mode) the
snapshot is all-sample and is NOT called stale — sample is honest, not
stale.
"""

from __future__ import annotations

import asyncio
import logging
import time

from ..fetcher import FetchError, Resolver, default_resolver, fetch
from . import TickerEntryDTO
from . import markets as markets_mod
from . import sports as sports_mod

log = logging.getLogger("mymts_helper.ticker.pollers")


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


class MarketsPoller:
    def __init__(
        self,
        *,
        interval_seconds: int = 120,
        resolver: Resolver = default_resolver,
    ) -> None:
        self.interval = interval_seconds
        self.resolver = resolver
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        # Start all-sample so the endpoint is honest before the first poll.
        self._entries: list[TickerEntryDTO] = markets_mod.all_sample_snapshot()
        self.real_as_of: str | None = None

    @property
    def entries(self) -> list[TickerEntryDTO]:
        return self._entries

    async def start(self) -> None:
        if self._task is not None:
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._loop(), name="markets-poller")

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
        self._task = None

    async def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                await self.poll_once()
            except Exception:  # noqa: BLE001
                log.exception("markets_poll_unexpected")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.interval)
            except TimeoutError:
                pass

    async def poll_once(self) -> None:
        stooq = await self._fetch_stooq()
        coingecko = await self._fetch_coingecko()
        self._entries = markets_mod.build_snapshot(stooq, coingecko)
        if stooq or coingecko:
            self.real_as_of = _now_iso()
        log.info(
            "markets_poll_ok",
            extra={"stooq": len(stooq), "coingecko": len(coingecko)},
        )

    async def _fetch_stooq(self) -> dict[str, tuple[float, str]]:
        symbols = [s for _, s in (
            markets_mod.STOOQ_INDICES + markets_mod.STOOQ_FX + markets_mod.STOOQ_GOLD
        )]
        try:
            r = await fetch(
                markets_mod.stooq_url(symbols),
                resolver=self.resolver,
                headers={"Accept": "text/csv, */*"},
            )
            if r.status_code >= 400:
                log.warning("markets_stooq_http", extra={"status": r.status_code})
                return {}
            return markets_mod.parse_stooq_csv(r.body)
        except FetchError as e:
            log.warning("markets_stooq_fetch_fail", extra={"reason": str(e)[:100]})
            return {}

    async def _fetch_coingecko(self) -> dict[str, tuple[float, str]]:
        try:
            r = await fetch(
                markets_mod.COINGECKO_URL,
                resolver=self.resolver,
                headers={"Accept": "application/json"},
            )
            if r.status_code >= 400:
                log.warning("markets_coingecko_http", extra={"status": r.status_code})
                return {}
            return markets_mod.parse_coingecko(r.body)
        except FetchError as e:
            log.warning("markets_coingecko_fetch_fail", extra={"reason": str(e)[:100]})
            return {}


class SportsPoller:
    def __init__(
        self,
        *,
        interval_seconds: int = 180,
        resolver: Resolver = default_resolver,
        leagues: list[tuple[str, str, str]] | None = None,
    ) -> None:
        self.interval = interval_seconds
        self.resolver = resolver
        self.leagues = leagues if leagues is not None else sports_mod.DEFAULT_LEAGUES
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        self._entries: list[TickerEntryDTO] = [sports_mod.no_games_entry()]
        self.real_as_of: str | None = None

    @property
    def entries(self) -> list[TickerEntryDTO]:
        return self._entries

    def seed_sample_slate(self) -> None:
        """Serve the clearly-marked sample sports slate. Used in phantom
        mode (no live external data), where the slate is honest sample —
        every entry is_sample=True — never claimed as live scores."""
        self._entries = list(sports_mod.SAMPLE_SPORTS)

    async def start(self) -> None:
        if self._task is not None:
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._loop(), name="sports-poller")

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
        self._task = None

    async def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                await self.poll_once()
            except Exception:  # noqa: BLE001
                log.exception("sports_poll_unexpected")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.interval)
            except TimeoutError:
                pass

    async def poll_once(self) -> None:
        collected: list[TickerEntryDTO] = []
        any_ok = False
        for label, sport, league in self.leagues:
            entries = await self._fetch_league(label, sport, league)
            if entries is not None:
                any_ok = True
                collected.extend(entries)
        if any_ok:
            self.real_as_of = _now_iso()
            # A truthful "nothing on" when every league fetched but had no games.
            self._entries = collected if collected else [sports_mod.no_games_entry()]
        # If NO league fetched OK this cycle, keep the prior snapshot
        # (do not wipe to empty) — staleness is surfaced by the endpoint.
        log.info("sports_poll_ok", extra={"games": len(collected), "any_ok": any_ok})

    async def _fetch_league(
        self, label: str, sport: str, league: str
    ) -> list[TickerEntryDTO] | None:
        """Return parsed entries (possibly empty) on success, or None on
        fetch/HTTP failure so the caller can tell 'no games' from 'no fetch'."""
        try:
            r = await fetch(
                sports_mod.scoreboard_url(sport, league),
                resolver=self.resolver,
                headers={"Accept": "application/json"},
            )
            if r.status_code >= 400:
                log.warning("sports_http", extra={"league": league, "status": r.status_code})
                return None
            return sports_mod.parse_scoreboard(r.body, league_label=label)
        except FetchError as e:
            log.warning("sports_fetch_fail", extra={"league": league, "reason": str(e)[:100]})
            return None
