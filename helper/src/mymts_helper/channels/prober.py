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

**YouTube channels (`kind='youtube'`):** before the master fetch, the prober
resolves the channel's YouTube `/live` URL to an HLS manifest via the
in-process [YouTubeResolver]. A channel that isn't live right now (a non-24/7
feed between shows) resolves to `ok=False` → recorded as an honest
`unavailable`, never a dead URL. A live channel resolves to a googlevideo HLS
master, which then runs the SAME master/variant fetch + validation as any
direct-HLS channel — so a YouTube channel is marked `live` only when its
resolved manifest is actually reachable through the SSRF-safe fetcher, and
its `current_url` is the resolved manifest the player can load directly.
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
from .cspan_resolver import CSpanResolution, CSpanResolver
from .youtube_resolver import (
    DEFAULT_RESOLVE_TIMEOUT,
    YouTubeResolution,
    YouTubeResolver,
)

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


def classify_browser_playable(*bodies: bytes | None) -> bool:
    """Best-effort: is this HLS chain playable in an HTTPS-served browser?

    The web client is served over HTTPS; a browser refuses to load any
    `http://` sub-resource from an HTTPS page ("mixed content"), which
    silently kills the tile. The native ExoPlayer has no such rule, so
    every resolvable channel plays on the TV — only the HTTPS-clean subset
    plays in the browser.

    The decisive, deterministic signal the helper CAN see server-side is
    the scheme of the chain's sub-resource URLs. We scan the master and the
    followed-variant playlist bodies for the literal `http://` — a variant
    entry, segment URL, `#EXT-X-MAP`/`#EXT-X-KEY` URI, etc. served over
    plaintext http. Note `https://` does NOT contain `http://` (the byte
    after `http` is `s`, not `:`), so this never false-positives on https
    URLs. Relative segment URLs resolve against the HTTPS playlist → https,
    and are correctly NOT flagged.

    Returns False if ANY `http://` is found (conservative: we'd rather
    honestly say "on the TV wall" than promise a tile that won't load),
    else True. CORS is a separate browser blocker the helper cannot
    predict from the body — it's caught by the client's runtime load
    result, the ultimate honesty fallback.

    NOTE: this is a flat substring scan over the WHOLE body, not just
    sub-resource URL lines. An `http://` inside a non-fetched tag attribute
    (e.g. an `#EXT-X-DATERANGE` / SCTE-35 ad-marker URI the player never
    loads) would also trip it and mislabel an otherwise-clean stream
    TV-only. That over-broad bias is intentional and safe: the cost of a
    false "no" is only an honest "on the TV wall" label for a channel that
    might have played; the client's runtime attempt is the real authority
    when the hint is "yes"/unclassified, so nothing is ever shown faked-live.
    """
    for body in bodies:
        if body and b"http://" in body:
            return False
    return True


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
        youtube_resolver: YouTubeResolver | None = None,
        youtube_resolve_timeout_seconds: int = DEFAULT_RESOLVE_TIMEOUT,
        cspan_resolver: CSpanResolver | None = None,
    ) -> None:
        self.db_path = db_path
        self.interval = interval_seconds
        self.resolver = resolver
        # yt-dlp resolver for kind='youtube' channels. Injectable so tests
        # drive it with synthetic output and never touch the network.
        self._youtube = youtube_resolver or YouTubeResolver(
            timeout=youtube_resolve_timeout_seconds
        )
        # Async deadline above yt-dlp's own socket timeout so a wedged
        # extraction can't stall the probe loop. The executor thread still
        # finishes on yt-dlp's socket_timeout; we just stop awaiting it.
        self._youtube_deadline = youtube_resolve_timeout_seconds + 15
        # Resolver for the free C-SPAN/.gov government floor feeds (kind='cspan').
        # Injectable for tests; runs (blocking) in the executor like youtube.
        self._cspan = cspan_resolver or CSpanResolver()
        self._cspan_deadline = DEFAULT_RESOLVE_TIMEOUT + 15
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
        # ---- Resolve (youtube → HLS) ----
        # For a direct-HLS channel the master URL is the source URL itself.
        # For a YouTube channel we first resolve /live → an HLS manifest; an
        # offline (non-24/7) channel resolves to ok=False and is recorded as
        # an honest `unavailable` (never a fake-live URL).
        master_url = c.source_url
        if c.kind == "youtube":
            resolved = await self._resolve_youtube(c.source_url)
            if not resolved.ok or not resolved.hls_url:
                await self._record(c.id, status="unavailable", current_url=None,
                                   error=f"yt:{resolved.error or 'unresolved'}",
                                   success=False)
                return
            master_url = resolved.hls_url
        elif c.kind == "cspan":
            # Free Senate-floor: resolve the current session's HLS master; an
            # offline (not-in-session) feed resolves to ok=False → honest
            # unavailable. The master→variant fetch below is the final live gate.
            resolved = await self._resolve_cspan(c.source_url)
            if not resolved.ok or not resolved.hls_url:
                await self._record(c.id, status="unavailable", current_url=None,
                                   error=f"cspan:{resolved.error or 'unresolved'}",
                                   success=False)
                return
            master_url = resolved.hls_url

        # ---- Master ----
        try:
            master = await fetch(master_url, resolver=self.resolver)
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
            await self._record(c.id, status="live", current_url=master_url,
                               error=None, success=True,
                               browser_playable=classify_browser_playable(master.body))
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
        # `live` matches what the player can actually reach. Classify
        # browser-playability from the scheme of the master + variant chain
        # (a hint for the web picker; the TV plays it regardless).
        await self._record(c.id, status="live", current_url=master_url,
                           error=None, success=True,
                           browser_playable=classify_browser_playable(master.body, variant.body))

    async def _resolve_youtube(self, url: str) -> YouTubeResolution:
        """Resolve a YouTube /live URL to HLS off the event loop, bounded.

        yt-dlp's `extract_info` is blocking, so it runs in the default
        executor; `wait_for` caps how long we await it. A timeout returns an
        honest unresolved result (the channel shows offline this cycle) rather
        than stalling every channel behind it.
        """
        loop = asyncio.get_running_loop()
        try:
            return await asyncio.wait_for(
                loop.run_in_executor(None, self._youtube.resolve, url),
                timeout=self._youtube_deadline,
            )
        except TimeoutError:
            log.warning("youtube_resolve_deadline", extra={"url": url})
            return YouTubeResolution(ok=False, error="resolve_timeout")

    async def _resolve_cspan(self, url: str) -> CSpanResolution:
        """Resolve the free Senate-floor stream off the event loop, bounded.

        The resolver does a blocking HTTP read of the senate.gov floor schedule,
        so it runs in the executor; `wait_for` caps the wait so a slow upstream
        can't stall the probe loop (honest-offline this cycle on a timeout).
        """
        loop = asyncio.get_running_loop()
        try:
            return await asyncio.wait_for(
                loop.run_in_executor(None, self._cspan.resolve, url),
                timeout=self._cspan_deadline,
            )
        except TimeoutError:
            log.warning("cspan_resolve_deadline", extra={"url": url})
            return CSpanResolution(ok=False, error="resolve_timeout")

    async def _record(
        self, channel_id: int, *, status: str, current_url: str | None,
        error: str | None, success: bool, browser_playable: bool | None = None,
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
                    browser_playable=browser_playable,
                )
            finally:
                conn.close()

        await loop.run_in_executor(None, _do)
