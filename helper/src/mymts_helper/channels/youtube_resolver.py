"""YouTube-live → HLS manifest resolver (yt-dlp, in-process).

The helper was direct-HLS-only: `kind='youtube'` was rejected as a "future
migration". This module is that future — it resolves a YouTube channel's
`/live` URL to the underlying HLS master manifest (googlevideo.com) so the
prober can validate + serve it exactly like a direct-HLS channel.

Design (mirrors the multiview/playlist projects' `stream_resolver.py`, the
house yt-dlp pattern):
  - **Library, not subprocess.** `import yt_dlp`; `YoutubeDL.extract_info(
    url, download=False)`. No shelling out.
  - **No `player_client` pin.** yt-dlp's default client set is what returns
    live HLS formats; forcing `["web"]` yields "No video formats found" for
    live streams. (Learned the hard way during channel validation.)
  - **Honest-offline.** A non-24/7 feed (PBS NewsHour, Court TV, Law&Crime)
    is dark between shows: `/live` 404s, reports "not currently live", or
    resolves to an *upcoming* premiere. We return `ok=False, is_live=False`
    for all of those — the prober records the channel `unavailable` (an
    honest offline), never a dead/fake-live URL, and the next probe cycle
    flips it live the moment it goes live.
  - **Cache + refresh before expiry.** A resolved googlevideo HLS URL carries
    an `expire` epoch (≈6 h out). We cache the resolution with a TTL derived
    from that expiry minus a safety margin (clamped to [MIN, MAX]_TTL), so a
    cached URL is always re-resolved *before* it can expire. The prober is
    the cadence driver (every CHANNEL_PROBE_INTERVAL_SECONDS); the cache just
    spares a redundant yt-dlp call when a probe lands inside the TTL.

EGRESS — residential, never PIA. yt-dlp does its own outbound HTTP (to
youtube.com / googlevideo.com, both public). It runs in the helper container,
whose network is the residential `mymts-net` bridge — so resolution leaves via
the *normal* egress. PIA is never touched here (the helper has no PIA route).
Defense in depth: the URL yt-dlp returns is then re-validated through the
SSRF-safe `fetcher` (https-only, RFC1918/CGNAT rejection, bounded) by the
prober before any channel is marked live — the resolver only *proposes* a URL.

Blocking note: `extract_info` is synchronous and can take seconds. Callers on
the event loop MUST run `resolve()` in an executor (the prober does).
"""

from __future__ import annotations

import logging
import re
import threading
import time
from dataclasses import dataclass
from urllib.parse import parse_qs, urlparse

import yt_dlp

log = logging.getLogger("mymts_helper.channels.youtube_resolver")

# ── Tunable named constants ────────────────────────────────────────────────
# Per-extraction socket timeout handed to yt-dlp (seconds). A stuck upstream
# is bounded by this; the prober adds a belt-and-braces asyncio timeout on top.
DEFAULT_RESOLVE_TIMEOUT = 25
# How long before the manifest's own `expire` we force a re-resolve. The
# resolved URL must never be served past expiry; this margin is the safety
# buffer between "cache expired, re-resolve" and "URL actually dead".
CACHE_SAFETY_MARGIN_SECONDS = 20 * 60     # 20 min
# Floor + ceiling on the derived cache TTL. The floor avoids hammering yt-dlp
# on an oddly-short expiry; the ceiling forces a periodic refresh even when a
# manifest claims a very distant expiry, so a long-lived URL still gets
# re-validated against reality.
MIN_CACHE_TTL_SECONDS = 5 * 60            # 5 min
MAX_CACHE_TTL_SECONDS = 4 * 60 * 60       # 4 h  (< the typical ~6 h expiry)
# TTL used when a resolved manifest carries no parseable `expire` hint.
FALLBACK_CACHE_TTL_SECONDS = 30 * 60      # 30 min

# DownloadError fragments that mean "the channel is simply not live right now"
# (an honest, transient offline) rather than "broken / misconfigured handle".
_OFFLINE_MARKERS = (
    "not currently live",
    "this live event will begin",
    "live event will begin",
    "is offline",
    "premieres in",
    "this video is unavailable",
)


@dataclass(frozen=True)
class YouTubeResolution:
    """Outcome of resolving one YouTube URL.

    `ok` is True only when the channel is live AND an HLS manifest was found.
    `expires_at` is the manifest's own expiry epoch (seconds) when known —
    used both to size the cache TTL and for observability.
    """
    ok: bool
    hls_url: str | None = None
    is_live: bool = False
    title: str | None = None
    expires_at: int | None = None
    error: str | None = None


class URLCache:
    """Thread-safe cache of resolutions, keyed by source URL, with per-entry TTL.

    Matches the multiview `URLCache` shape (get/set/invalidate/clear) so the
    pattern reads the same across the two projects.
    """

    def __init__(self) -> None:
        self._cache: dict[str, dict] = {}
        self._lock = threading.Lock()

    def get(self, url: str) -> YouTubeResolution | None:
        with self._lock:
            entry = self._cache.get(url)
            if entry is None:
                return None
            if time.time() < entry["expires_at"]:
                return entry["data"]
            del self._cache[url]
            return None

    def set(self, url: str, data: YouTubeResolution, ttl: float) -> None:
        with self._lock:
            self._cache[url] = {"data": data, "expires_at": time.time() + ttl}

    def invalidate(self, url: str) -> None:
        with self._lock:
            self._cache.pop(url, None)

    def clear(self) -> None:
        with self._lock:
            self._cache.clear()


def _extract_hls_url(info: dict) -> str | None:
    """Find an HLS manifest URL in a yt-dlp extraction dict.

    Same precedence as the multiview resolver: the live `manifest_url` master
    first, then a format URL that is itself m3u8, then any m3u8 format.
    """
    manifest = info.get("manifest_url")
    if manifest and ".m3u8" in manifest:
        return manifest
    url = info.get("url")
    if url and (".m3u8" in url or info.get("protocol") in ("m3u8", "m3u8_native")):
        return url
    formats = info.get("formats") or []
    hls = [
        f for f in formats
        if f.get("protocol") in ("m3u8", "m3u8_native")
        or (f.get("url") and ".m3u8" in f["url"])
    ]
    if hls:
        return hls[-1].get("url")
    return None


def _manifest_expiry(url: str) -> int | None:
    """Parse a googlevideo manifest's expiry epoch from `expire=`/`/expire/<n>`."""
    try:
        q = parse_qs(urlparse(url).query)
        for key in ("expire", "expires"):
            if key in q and q[key]:
                return int(q[key][0])
        m = re.search(r"/expire/(\d+)", url)
        if m:
            return int(m.group(1))
    except (ValueError, TypeError):
        return None
    return None


def _cache_ttl_for(expires_at: int | None) -> float:
    """Derive a cache TTL that re-resolves comfortably before expiry."""
    if not expires_at:
        return FALLBACK_CACHE_TTL_SECONDS
    remaining = expires_at - time.time() - CACHE_SAFETY_MARGIN_SECONDS
    return max(MIN_CACHE_TTL_SECONDS, min(MAX_CACHE_TTL_SECONDS, remaining))


class YouTubeResolver:
    """Resolves YouTube `/live` URLs to HLS, with a TTL cache.

    One instance is held by the prober. `extract_info` is injectable so tests
    drive the resolver with synthetic yt-dlp output and never touch the network.
    """

    def __init__(
        self,
        *,
        timeout: int = DEFAULT_RESOLVE_TIMEOUT,
        extract_info=None,
        cache: URLCache | None = None,
    ) -> None:
        self._timeout = timeout
        self._cache = cache or URLCache()
        self._extract = extract_info or self._default_extract

    def _default_extract(self, url: str) -> dict:
        # NB: no `player_client` pin — see module docstring. quiet/no_warnings
        # keep yt-dlp from writing to stderr; socket_timeout bounds a hang.
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            "socket_timeout": self._timeout,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            return ydl.extract_info(url, download=False)

    def resolve(self, url: str, *, force_refresh: bool = False) -> YouTubeResolution:
        """Resolve `url` to an HLS manifest. BLOCKING — run in an executor.

        Positive resolutions are cached with an expiry-derived TTL. Offline /
        error outcomes are returned uncached so the next probe re-checks and
        flips the channel live the instant it goes live.
        """
        if not force_refresh:
            cached = self._cache.get(url)
            if cached is not None:
                return cached

        try:
            info = self._extract(url)
        except yt_dlp.utils.DownloadError as e:
            msg = str(e)
            low = msg.lower()
            if any(marker in low for marker in _OFFLINE_MARKERS):
                # Honest offline — the channel is dark right now, not broken.
                return YouTubeResolution(ok=False, is_live=False, error="not_live")
            log.info("youtube_resolve_error", extra={"url": url, "detail": msg[:160]})
            return YouTubeResolution(ok=False, error=f"resolve_error:{msg[:120]}")
        except Exception as e:  # noqa: BLE001 — yt-dlp can raise a grab-bag; never crash the probe
            log.warning("youtube_resolve_unexpected", extra={"url": url, "err": str(e)[:160]})
            return YouTubeResolution(ok=False, error=f"resolve_unexpected:{str(e)[:120]}")

        is_live = bool(info.get("is_live")) or info.get("live_status") == "is_live"
        title = info.get("title")
        if not is_live:
            return YouTubeResolution(ok=False, is_live=False, title=title, error="not_live")

        hls = _extract_hls_url(info)
        if not hls:
            return YouTubeResolution(ok=False, is_live=True, title=title, error="no_hls_manifest")

        expires_at = _manifest_expiry(hls)
        result = YouTubeResolution(
            ok=True, hls_url=hls, is_live=True, title=title, expires_at=expires_at,
        )
        self._cache.set(url, result, _cache_ttl_for(expires_at))
        return result
