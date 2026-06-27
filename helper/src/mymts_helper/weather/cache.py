"""Thread-safe TTL cache for proxied NWS radar loop GIFs.

Mirrors the resolver caches' shape (``channels/youtube_resolver.py``): a small
lock + per-key entry. Two reads: :meth:`get_fresh` (within the TTL — serve without
touching NWS) and :meth:`get_last_good` (any age — the honest fallback when an
upstream fetch fails, so a blip shows the LAST real frame we got, never a blank or
a faked one). Keyed by the region key only, so a browser cache-buster (``?t=...``)
still hits the warm cache and NWS is fetched at most once per region per TTL.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

# Match the NWS standard-loop refresh cadence (~5 min). The origin's own
# Cache-Control max-age is short (~80s) but the underlying volume scan only
# advances every ~5 min, so a 5-min TTL is fresh AND rate-respectful (<= 1 NWS
# fetch per region per 5 min regardless of how many tiles / refreshes request it).
RADAR_CACHE_TTL_SECONDS = 300.0


@dataclass(frozen=True)
class RadarFrame:
    data: bytes
    content_type: str
    fetched_at: float


class RadarFrameCache:
    """Per-region cache of the latest fetched radar loop GIF. ``now`` is injectable
    so the TTL is deterministically testable (defaults to a monotonic clock)."""

    def __init__(
        self,
        ttl_seconds: float = RADAR_CACHE_TTL_SECONDS,
        *,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        self._ttl = ttl_seconds
        self._now = now
        self._frames: dict[str, RadarFrame] = {}
        self._lock = threading.Lock()

    def get_fresh(self, key: str) -> RadarFrame | None:
        """The cached frame if it is within the TTL, else None (caller refetches)."""
        with self._lock:
            frame = self._frames.get(key)
            if frame is None:
                return None
            if self._now() - frame.fetched_at <= self._ttl:
                return frame
            return None

    def get_last_good(self, key: str) -> RadarFrame | None:
        """The last successfully-fetched frame for the key REGARDLESS of age — the
        honest fallback served (marked stale) when a refresh fails."""
        with self._lock:
            return self._frames.get(key)

    def put(self, key: str, data: bytes, content_type: str) -> None:
        with self._lock:
            self._frames[key] = RadarFrame(
                data=data, content_type=content_type, fetched_at=self._now()
            )
