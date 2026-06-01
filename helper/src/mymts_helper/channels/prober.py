"""Channel prober.

Periodically GET each enabled channel's `source_url`, validate the response
is a real HLS manifest (`#EXTM3U`), update status + current_url. The TV
asks `/api/channels` and gets either a known-good playable URL or an
honest "unavailable" — never a stale URL labelled live.

Prober uses the SSRF-safe fetcher, so the same A1/A2/A4 guards apply to
the resolution path as to RSS aggregation.
"""

from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path

from .. import db
from ..fetcher import FetchError, Resolver, default_resolver, fetch
from . import registry

log = logging.getLogger("mymts_helper.channels.prober")


def _looks_like_hls_manifest(body: bytes) -> bool:
    # HLS manifests start with #EXTM3U on the first non-BOM line.
    s = body.lstrip(b"\xef\xbb\xbf")  # strip UTF-8 BOM if any
    return s.startswith(b"#EXTM3U")


class ChannelProber:
    def __init__(
        self,
        db_path: Path,
        *,
        interval_seconds: int = 30 * 60,   # 30 min — enough for "is it reachable"
        resolver: Resolver = default_resolver,
    ) -> None:
        self.db_path = db_path
        self.interval = interval_seconds
        self.resolver = resolver
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()
        self.last_probe_at: str | None = None

    async def start(self) -> None:
        if self._task is not None:
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._loop(), name="channel-prober")

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
                await self.probe_once()
            except Exception:  # noqa: BLE001
                log.exception("probe_loop_unexpected")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.interval)
            except TimeoutError:
                pass

    async def probe_once(self) -> None:
        loop = asyncio.get_running_loop()
        chans = await loop.run_in_executor(None, self._list_enabled)
        for c in chans:
            await self._probe_one(c)
        self.last_probe_at = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())

    def _list_enabled(self) -> list[registry.ChannelRow]:
        conn = db.connect(self.db_path)
        try:
            return registry.list_channels(conn, enabled_only=True)
        finally:
            conn.close()

    async def _probe_one(self, c: registry.ChannelRow) -> None:
        try:
            result = await fetch(c.source_url, resolver=self.resolver)
        except FetchError as e:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"fetch:{e}", success=False)
            return
        if result.status_code >= 400:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"http_{result.status_code}", success=False)
            return
        if not _looks_like_hls_manifest(result.body):
            await self._record(c.id, status="unavailable", current_url=None,
                               error="not_hls_manifest", success=False)
            return
        await self._record(c.id, status="live", current_url=c.source_url,
                           error=None, success=True)

    async def _record(
        self, channel_id: int, *, status: str, current_url: str | None,
        error: str | None, success: bool,
    ) -> None:
        loop = asyncio.get_running_loop()

        def _do() -> None:
            conn = db.connect(self.db_path)
            try:
                registry.update_status(
                    conn,
                    channel_id=channel_id,
                    status=status,
                    current_url=current_url,
                    error=error,
                    success=success,
                )
            finally:
                conn.close()

        await loop.run_in_executor(None, _do)
