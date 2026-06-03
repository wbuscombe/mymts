"""Channel prober.

Periodically GET each enabled channel's `source_url`, validate the response
is a real HLS manifest (`#EXTM3U`), update status + current_url. The TV
asks `/api/channels` and gets either a known-good playable URL or an
honest "unavailable" — never a stale URL labelled live.

**Deepened validation (channel-resolution investigation, 2026-06-03):**
the prober now follows the master manifest one level — it picks a variant
playlist URL from the master, fetches it through the same SSRF-safe
[fetcher], and only marks the channel `live` if that variant also
responds with a real HLS body. This closes the master-OK / variant-FAIL
gap (NASA TV pattern) where the helper's old "live" definition was
satisfied by a master manifest the ExoPlayer-side then couldn't follow.

The variant fetch reuses [fetch] verbatim — same A1/A2/A4 guards apply
(https-only, DNS-pinned IP, RFC1918/loopback/CGNAT/ULA rejection on
each hop, bounded body, bounded time, redirects re-validated). No new
egress surface; this only deepens what "reachable" means.

A master that's already a **media playlist itself** (no `#EXT-X-STREAM-INF`,
contains `#EXTINF`) is its own variant — no second fetch is needed. The
prober marks it live based on the master body alone, which is the
correct behaviour for single-rendition streams.
"""

from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path
from urllib.parse import urljoin

from .. import db
from ..fetcher import FetchError, Resolver, default_resolver, fetch
from . import registry

log = logging.getLogger("mymts_helper.channels.prober")


def _looks_like_hls_manifest(body: bytes) -> bool:
    # HLS manifests start with #EXTM3U on the first non-BOM line.
    s = body.lstrip(b"\xef\xbb\xbf")  # strip UTF-8 BOM if any
    return s.startswith(b"#EXTM3U")


def _is_media_playlist(body: bytes) -> bool:
    """True iff the body is a *media* playlist (segments), not a master.

    Distinguishing rule: a media playlist contains `#EXTINF` lines
    (segment duration markers); a master playlist contains
    `#EXT-X-STREAM-INF` (variant entries). Some master manifests
    accidentally include `#EXTINF` in comments — we only treat a body
    as a media playlist if it has `#EXTINF` AND does NOT have
    `#EXT-X-STREAM-INF`. This is conservative: master-with-no-variants
    falls through to the "no variant URL" branch, which logs unavailable.
    """
    return b"#EXTINF" in body and b"#EXT-X-STREAM-INF" not in body


def _pick_variant_url(body: bytes, master_url: str) -> str | None:
    """Pick one variant playlist URL from a master manifest.

    Returns an **absolute https** URL, resolved against [master_url].
    Returns `None` if no variant entry is found (caller decides whether
    that's an error or a media-playlist passthrough).

    Strategy: walk the body line-by-line. When we see `#EXT-X-STREAM-INF`,
    the **next non-blank, non-comment** line is the variant's URL. Pick
    the first such pair we find — bandwidth-aware selection is overkill
    for "is it fetchable" probing.
    """
    text = body.decode("utf-8", errors="ignore")
    lines = text.splitlines()
    expecting_url = False
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if expecting_url and not line.startswith("#"):
            # The variant URL — absolutize against the master URL.
            absolute = urljoin(master_url, line)
            return absolute if absolute.startswith("https://") else None
        if line.startswith("#EXT-X-STREAM-INF"):
            expecting_url = True
        # `#EXT-X-MEDIA` and other tag lines reset the "expecting URL"
        # state — only the line *immediately* after #EXT-X-STREAM-INF is
        # the variant URL.
        elif line.startswith("#"):
            expecting_url = False
    return None


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
        # ---- Master ----
        try:
            master = await fetch(c.source_url, resolver=self.resolver)
        except FetchError as e:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"fetch:{e}", success=False)
            return
        if master.status_code >= 400:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"http_{master.status_code}", success=False)
            return
        if not _looks_like_hls_manifest(master.body):
            await self._record(c.id, status="unavailable", current_url=None,
                               error="not_hls_manifest", success=False)
            return

        # ---- Variant ----
        # A master that's already a media playlist (single rendition,
        # contains #EXTINF, no #EXT-X-STREAM-INF) is its own variant.
        if _is_media_playlist(master.body):
            await self._record(c.id, status="live", current_url=c.source_url,
                               error=None, success=True)
            return

        variant_url = _pick_variant_url(master.body, master.url)
        if variant_url is None:
            # Master fetched, but no variant entry found. That's an
            # unusual manifest — refuse to claim live until we can
            # verify the chain.
            await self._record(c.id, status="unavailable", current_url=None,
                               error="variant_missing_in_master", success=False)
            return

        try:
            variant = await fetch(variant_url, resolver=self.resolver)
        except FetchError as e:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"variant_fetch:{e}", success=False)
            return
        if variant.status_code >= 400:
            await self._record(c.id, status="unavailable", current_url=None,
                               error=f"variant_http_{variant.status_code}",
                               success=False)
            return
        if not _looks_like_hls_manifest(variant.body):
            await self._record(c.id, status="unavailable", current_url=None,
                               error="variant_not_hls_manifest", success=False)
            return

        # Master + at least one variant both fetchable. Now the helper's
        # `live` matches what the player can actually reach.
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
