"""RSS poller.

A single async task loops over enabled sources, fetches each via the
SSRF-safe fetcher, parses, and stores. Per-source error isolation is
absolute: one source's failure must not affect the others' fetch state
(Op Bar C-series + Trust Bar C2 in the helper's voice).

The poller is intentionally trivial — no parallelism (sequential keeps
the CPU footprint predictable on the NAS; Stage 2 traffic doesn't need
it), no jitter (we're a single-tenant; thundering herds aren't a real
concern), no rate-limiting (per-source intervals + the `If-Modified-Since`
headers we'll add when sources actually rate-limit us are enough).
"""

from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path

from .. import db
from ..fetcher import FetchError, Resolver, default_resolver, fetch
from . import parser as feed_parser
from . import store

log = logging.getLogger("mymts_helper.feeds.poller")


class FeedPoller:
    def __init__(
        self,
        db_path: Path,
        *,
        interval_seconds: int = 300,
        retention_days: int = 14,
        retention_interval_seconds: int = 24 * 3600,
        resolver: Resolver = default_resolver,
    ) -> None:
        self.db_path = db_path
        self.interval = interval_seconds
        self.retention_days = retention_days
        self.retention_interval = retention_interval_seconds
        self.resolver = resolver
        self._task: asyncio.Task | None = None
        self._retention_task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        self.last_poll_at: str | None = None

    async def start(self) -> None:
        if self._task is not None:
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._loop(), name="feed-poller")
        self._retention_task = asyncio.create_task(
            self._retention_loop(), name="feed-retention-sweep"
        )

    async def stop(self) -> None:
        self._stop.set()
        for t in (self._task, self._retention_task):
            if t is not None:
                t.cancel()
                try:
                    await t
                except (asyncio.CancelledError, Exception):
                    pass
        self._task = None
        self._retention_task = None

    async def _loop(self) -> None:
        # Initial poll immediately after boot so /health reflects real state.
        while not self._stop.is_set():
            try:
                await self.poll_once()
            except Exception:  # noqa: BLE001
                log.exception("poll_loop_unexpected")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.interval)
            except TimeoutError:
                pass

    async def _retention_loop(self) -> None:
        while not self._stop.is_set():
            try:
                await self.sweep_once()
            except Exception:  # noqa: BLE001
                log.exception("retention_loop_unexpected")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.retention_interval)
            except TimeoutError:
                pass

    async def poll_once(self) -> None:
        """One sweep across all enabled sources. Per-source errors are caught
        and recorded; the loop never bubbles."""
        loop = asyncio.get_running_loop()
        # Run the read in the executor to keep the loop responsive.
        sources = await loop.run_in_executor(None, self._list_enabled_sources)
        for s in sources:
            await self._poll_one(s)
        self.last_poll_at = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())

    def _list_enabled_sources(self) -> list[store.SourceRow]:
        conn = db.connect(self.db_path)
        try:
            return store.list_sources(conn, enabled_only=True)
        finally:
            conn.close()

    async def _poll_one(self, s: store.SourceRow) -> None:
        try:
            result = await fetch(
                s.url,
                resolver=self.resolver,
                headers={"Accept": "application/rss+xml, application/atom+xml, "
                                   "application/xml, application/json, */*;q=0.5"},
            )
            if result.status_code >= 400:
                await self._record_failure(s.id, f"http_{result.status_code}")
                return
            parsed = feed_parser.parse(result.body, source_url=s.url)
            await self._store(s.id, parsed)
        except FetchError as e:
            await self._record_failure(s.id, f"fetch:{e}")
        except Exception as e:  # noqa: BLE001 — last line of per-source defence
            log.exception("poll_unexpected", extra={"source_id": s.id})
            await self._record_failure(s.id, f"unexpected:{type(e).__name__}")

    async def _store(self, source_id: int, parsed: feed_parser.ParseResult) -> None:
        loop = asyncio.get_running_loop()

        def _do() -> int:
            conn = db.connect(self.db_path)
            try:
                inserted = store.insert_items(conn, source_id, parsed.items)
                store.record_fetch_success(conn, source_id)
                return inserted
            finally:
                conn.close()

        inserted = await loop.run_in_executor(None, _do)
        log.info(
            "poll_ok",
            extra={"source_id": source_id, "items_parsed": len(parsed.items),
                   "inserted": inserted, "bozo": parsed.bozo},
        )

    async def _record_failure(self, source_id: int, reason: str) -> None:
        loop = asyncio.get_running_loop()

        def _do() -> None:
            conn = db.connect(self.db_path)
            try:
                store.record_fetch_failure(conn, source_id, reason)
            finally:
                conn.close()

        await loop.run_in_executor(None, _do)
        log.warning("poll_fail", extra={"source_id": source_id, "reason": reason[:100]})

    async def sweep_once(self) -> int:
        loop = asyncio.get_running_loop()

        def _do() -> int:
            conn = db.connect(self.db_path)
            try:
                return store.retention_sweep(conn, days=self.retention_days)
            finally:
                conn.close()

        deleted = await loop.run_in_executor(None, _do)
        if deleted > 0:
            log.info("retention_sweep", extra={"deleted": deleted})
        return deleted
