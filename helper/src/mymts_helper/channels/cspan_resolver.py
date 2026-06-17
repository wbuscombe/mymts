"""C-SPAN FREE government-stream resolver (the U.S. Senate floor).

SCOPE — FREE, NO-LOGIN CONTENT ONLY. C-SPAN streams the House/Senate floor and
hearings free, with no login. This resolver powers the **U.S. Senate floor**,
whose underlying public feed is the Senate's own ISVP/Bitmovin stream — verified
(recon, 2026-06): NO login, NO Adobe Pass / TV-Everywhere / entitlement, NO DRM
(`EXT-X-KEY`-free, clear `.ts`), and — notably — NO Akamai token at all. This is
DISTINCT from the three curated networks (C-SPAN / C-SPAN2 / C-SPAN3), which ARE
entitlement-gated (TV-provider login) and are STRICTLY OUT OF SCOPE — never built
against here. If resolving the free feed ever required auth, that would be the
gated path → stop + omit (a boundary this resolver never crosses: it sends no
credential and follows no auth handshake).

The only moving part is the per-session filename (`stv`+MMDDYY) the senate.gov
floor-schedule JSON publishes daily — so this is a filename/session-refresh
resolver (mirroring `youtube_resolver`'s shape), not a token resolver.

Resolve recipe (mimics the public player — see floor_status.js + isvp/stv.html):
  1. GET the floor-schedule JSON; read `convenedSessionStream` → the session's
     `comm` (stv) + `filename` (stv+MMDDYY). Its presence is the in-session gate.
  2. Map comm → streamID (stv→2096634) and build the Akamai HLS master:
     https://www-senate-gov-media-srs.akamaized.net/hls/live/<id>/<comm>/<filename>/master.m3u8
  3. Return that master. The prober's existing master→variant fetch is the LIVE
     gate: a torn-down / not-yet-live session's master can 200 as a stub, but its
     VARIANT 404s → honest-offline (no fake-live, no frozen VOD).

Polite client: a TTL cache (re-reads the daily filename, no hammering), a sane
UA, host-allowlisted to senate.gov, bounded body/time. Single LAN deployment.

Blocking note: `resolve()` is synchronous (like `youtube_resolver`); the prober
runs it in an executor.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
from dataclasses import dataclass
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

log = logging.getLogger("mymts_helper.channels.cspan_resolver")

# The free Senate floor schedule API (the same JSON the public floor page polls).
SENATE_FLOOR_SCHEDULE_URL = "https://www.senate.gov/legislative/schedule/floor_schedule.json"
# The Akamai HLS host the ISVP player builds its master against.
_AKAMAI_HLS_BASE = "https://www-senate-gov-media-srs.akamaized.net/hls/live"
# comm → streamID, from the ISVP player's baked-in streamInfo table. `stv` is the
# public FLOOR feed (what the live JSON points at); `srs` is kept as a fallback.
_COMM_STREAM_IDS = {"stv": "2096634", "srs": "2031966"}
# Only fetch from the Senate's own host.
OFFICIAL_HOSTS = frozenset({"www.senate.gov", "senate.gov"})

# ── tunable named constants ────────────────────────────────────────────────
DEFAULT_RESOLVE_TIMEOUT = 15
# The filename rotates DAILY and the convened/adjourned status changes during the
# day; re-read on this TTL (well under the prober's ~30-min cycle, so each probe
# sees a fresh session status) — no token to chase.
CACHE_TTL_SECONDS = 15 * 60
_MAX_JSON_BYTES = 4 * 1024 * 1024
_UA = "mymts-helper/0.0 (+cspan-free-floor)"

# A filename we'll trust as a path component: letters then digits (e.g. stv061726).
_SAFE_FILENAME_RE = re.compile(r"^[a-z]{2,5}[0-9]{6,8}$")


@dataclass(frozen=True)
class CSpanResolution:
    """Outcome of resolving the free Senate-floor stream. `ok` is True only when a
    current session master URL was built; `hls_url` is then the Akamai master the
    prober validates (its variant fetch is the final live gate)."""
    ok: bool
    hls_url: str | None = None
    is_live: bool = False
    error: str | None = None


# ── pure decision logic (unit-tested, no I/O) ───────────────────────────────

def is_official_url(url: str) -> bool:
    """True iff `url` is HTTPS and its host is the Senate's own host."""
    try:
        p = urlparse(url)
    except (ValueError, TypeError):
        return False
    return p.scheme == "https" and (p.hostname or "").lower() in OFFICIAL_HOSTS


def is_safe_filename(filename: str) -> bool:
    """True iff `filename` is safe as a single path component (the session code,
    e.g. stv061726) — no slash / traversal / odd characters."""
    return bool(isinstance(filename, str) and _SAFE_FILENAME_RE.match(filename))


def extract_comm_filename(stream_url: str) -> tuple[str, str] | None:
    """From a `convenedSessionStream` ISVP URL (…/isvp/stv.html?type=live&comm=stv
    &filename=stv061726) return (comm, filename), or None if missing/unsafe."""
    try:
        q = parse_qs(urlparse(stream_url).query)
    except (ValueError, TypeError):
        return None
    comm = (q.get("comm") or [""])[0].strip().lower()
    filename = (q.get("filename") or [""])[0].strip()
    if comm in _COMM_STREAM_IDS and is_safe_filename(filename):
        return (comm, filename)
    return None


def parse_schedule(schedule: dict) -> tuple[str, str] | None:
    """From the floor-schedule JSON, return (comm, filename) for the CURRENT
    convened session, or None when not in session (no `convenedSessionStream`)."""
    try:
        procs = schedule.get("floorProceedings") or []
        for p in procs:
            stream = (p or {}).get("convenedSessionStream")
            if stream:
                got = extract_comm_filename(str(stream))
                if got is not None:
                    return got
    except (AttributeError, TypeError):
        return None
    return None


def build_master_url(comm: str, filename: str) -> str | None:
    """Build the Akamai HLS master from (comm, filename), the player's own way.
    Returns None for an unknown comm or an unsafe filename (defensive)."""
    stream_id = _COMM_STREAM_IDS.get(comm)
    if stream_id is None or not is_safe_filename(filename):
        return None
    return f"{_AKAMAI_HLS_BASE}/{stream_id}/{comm}/{filename}/master.m3u8"


# ── cache + I/O ─────────────────────────────────────────────────────────────

class _Cache:
    """Thread-safe TTL cache of resolutions (same shape as youtube_resolver's)."""

    def __init__(self) -> None:
        self._d: dict[str, dict] = {}
        self._lock = threading.Lock()

    def get(self, key: str) -> CSpanResolution | None:
        with self._lock:
            e = self._d.get(key)
            if e is None:
                return None
            if time.time() < e["expires_at"]:
                return e["data"]
            del self._d[key]
            return None

    def set(self, key: str, data: CSpanResolution, ttl: float) -> None:
        with self._lock:
            self._d[key] = {"data": data, "expires_at": time.time() + ttl}

    def clear(self) -> None:
        with self._lock:
            self._d.clear()


def _http_get_json(url: str, timeout: int) -> dict:
    """GET an OFFICIAL senate.gov https URL and parse JSON. Raises on a non-official
    host (the resolver never fetches anywhere else) or a parse failure."""
    if not is_official_url(url):
        raise ValueError(f"refusing non-official url: {urlparse(url).hostname!r}")
    req = Request(url, headers={"User-Agent": _UA, "Accept": "application/json"})  # noqa: S310 — host-allowlisted https above
    with urlopen(req, timeout=timeout) as r:  # noqa: S310 — host-allowlisted https above
        return json.loads(r.read(_MAX_JSON_BYTES + 1).decode("utf-8"))


class CSpanResolver:
    """Resolves the free Senate-floor stream to its current HLS master, TTL-cached.
    `http_get` (returns the parsed schedule JSON for a URL) is injectable so tests
    drive it with synthetic output and never touch the network."""

    def __init__(self, *, timeout: int = DEFAULT_RESOLVE_TIMEOUT, http_get=None,
                 cache: _Cache | None = None) -> None:
        self._timeout = timeout
        self._cache = cache or _Cache()
        self._http_get = http_get or (lambda url: _http_get_json(url, self._timeout))

    def resolve(self, source_url: str, *, force_refresh: bool = False) -> CSpanResolution:
        """Resolve to the current Senate-floor HLS master. BLOCKING — run in an
        executor. Honest-offline (ok=False) when not in session; offline-safe
        (never raises) — any failure falls back to an honest unavailable."""
        if not force_refresh:
            cached = self._cache.get(source_url)
            if cached is not None:
                return cached
        try:
            # Defense: the seeded source must be the Senate's own host.
            if not is_official_url(source_url):
                return CSpanResolution(ok=False, error="source_not_official")
            schedule = self._http_get(SENATE_FLOOR_SCHEDULE_URL)
            got = parse_schedule(schedule)
            if got is None:
                # No convened session → honest-offline (Senate not in session).
                return CSpanResolution(ok=False, is_live=False, error="not_in_session")
            comm, filename = got
            master = build_master_url(comm, filename)
            if master is None:
                return CSpanResolution(ok=False, error="build_failed")
            result = CSpanResolution(ok=True, hls_url=master, is_live=True)
            self._cache.set(source_url, result, CACHE_TTL_SECONDS)
            return result
        except Exception as e:  # noqa: BLE001 — offline-safe: never crash the probe
            log.info("cspan_resolve_error", extra={"reason": str(e)[:140]})
            return CSpanResolution(ok=False, error=f"resolve_error:{str(e)[:100]}")
