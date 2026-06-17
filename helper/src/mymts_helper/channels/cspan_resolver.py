"""C-SPAN FREE government-stream resolver (Senate floor + Senate committee hearings).

SCOPE — FREE, NO-LOGIN CONTENT ONLY. C-SPAN streams the House/Senate floor and
hearings free, with no login. This resolver powers the **U.S. Senate floor** AND
**U.S. Senate committee hearings**, whose underlying public feeds are the Senate's
own ISVP/Bitmovin streams — verified (recon, 2026-06): NO login, NO Adobe Pass /
TV-Everywhere / entitlement, NO DRM (`EXT-X-KEY`-free, clear `.ts`), and — notably
— NO Akamai token at all. This is DISTINCT from the three curated networks
(C-SPAN / C-SPAN2 / C-SPAN3), which ARE entitlement-gated (TV-provider login) and
are STRICTLY OUT OF SCOPE — never built against here. If resolving a free feed ever
required auth, that would be the gated path → stop + omit (a boundary this resolver
never crosses: it sends no credential and follows no auth handshake).

The only moving part is the per-session filename (`<comm>`+MMDDYY) the senate.gov
schedule feeds publish daily — so this is a filename/session-refresh resolver
(mirroring `youtube_resolver`'s shape), not a token resolver.

TWO MODES, one Akamai master shape:
  • FLOOR (`?comm=stv`): GET the floor-schedule JSON; read `convenedSessionStream`
    → the session's comm (stv) + filename. Its presence is the in-session gate.
  • COMMITTEES (`?schedule=committees`): GET the committee hearings XML feed; each
    `<meeting>` carries a `<video_url>` of the same ISVP shape (comm + filename).
    Many committees may have hearings on a given day (0, 1, or several concurrent),
    so this mode SELECTS the one that is actually live: among today's scheduled
    hearings (most-recently-scheduled first, bounded), it builds each Akamai master
    and returns the first that is a live HLS master. None live → honest-offline.

Master shape (the ISVP player's own build, verbatim from its `streamInfo` table):
  https://www-senate-gov-media-srs.akamaized.net/hls/live/<streamID>/<comm>/<filename>/master.m3u8
The comm→streamID map is the player's baked-in `streamInfo` (stv=floor, plus one
row per committee). The prober's existing master→variant fetch remains the FINAL
live gate: a torn-down session's master 404s (or its variant does) → honest-offline
(no fake-live, no frozen VOD).

Polite client: a TTL cache (re-reads the daily filename, no hammering), a sane UA,
host-allowlisted to senate.gov + the Senate's own Akamai CDN, bounded body/time, and
the committee mode self-bounds how many candidate masters it probes per resolve.
Single LAN deployment.

Blocking note: `resolve()` is synchronous (like `youtube_resolver`); the prober
runs it in an executor.
"""

from __future__ import annotations

import datetime
import json
import logging
import re
import threading
import time
from dataclasses import dataclass
from urllib.parse import parse_qs, urljoin, urlparse
from urllib.request import Request, urlopen

import defusedxml.ElementTree as ET
from defusedxml.common import DefusedXmlException

log = logging.getLogger("mymts_helper.channels.cspan_resolver")

# The free Senate FLOOR schedule API (the same JSON the public floor page polls).
SENATE_FLOOR_SCHEDULE_URL = "https://www.senate.gov/legislative/schedule/floor_schedule.json"
# The free Senate COMMITTEE hearings schedule (first-party XML, the committee analog
# of floor_schedule.json — one feed listing every upcoming hearing's comm+filename).
SENATE_COMMITTEE_SCHEDULE_URL = "https://www.senate.gov/general/committee_schedules/hearings.xml"
# The Akamai HLS host the ISVP player builds its master against.
_AKAMAI_HLS_BASE = "https://www-senate-gov-media-srs.akamaized.net/hls/live"
_MASTER_HOST = "www-senate-gov-media-srs.akamaized.net"
# comm → streamID, from the ISVP player's baked-in `streamInfo` table (gathered at
# build-time from the player the public site serves). `stv` is the FLOOR feed; the
# rest are the standing Senate committees (each row of the same streamInfo table).
# A comm absent here is simply skipped (that hearing honest-offlines) — never faked.
_COMM_STREAM_IDS = {
    "stv": "2096634",       # Senate floor (Senate TV)
    "srs": "2031966",       # Senate Radio/TV fallback
    "approps": "2036802",   # Appropriations
    "armed": "2036800",     # Armed Services
    "aging": "2036801",     # Aging
    "banking": "2036799",   # Banking, Housing, and Urban Affairs
    "budget": "2036798",    # Budget
    "commerce": "2036779",  # Commerce, Science, and Transportation
    "energy": "2036797",    # Energy and Natural Resources
    "epw": "2036783",       # Environment and Public Works
    "ethics": "2036796",    # Ethics
    "finance": "2036795",   # Finance
    "foreign": "2036794",   # Foreign Relations
    "govtaff": "2036792",   # Homeland Security and Governmental Affairs
    "help": "2036793",      # Health, Education, Labor, and Pensions
    "indian": "2036791",    # Indian Affairs
    "intel": "2036790",     # Intelligence (Select)
    "jec": "2036789",       # Joint Economic Committee
    "judiciary": "2036788", # Judiciary
    "rules": "2036787",     # Rules and Administration
    "smbiz": "2036786",     # Small Business and Entrepreneurship
    "vetaff": "2036785",    # Veterans' Affairs
}
# Only fetch from the Senate's own hosts (schedule + the Senate's own Akamai CDN).
OFFICIAL_HOSTS = frozenset({"www.senate.gov", "senate.gov"})
_FETCH_HOSTS = OFFICIAL_HOSTS | {_MASTER_HOST}

# ── tunable named constants ────────────────────────────────────────────────
DEFAULT_RESOLVE_TIMEOUT = 15
# The filename rotates DAILY and the convened/adjourned status changes during the
# day; re-read on this TTL (well under the prober's ~30-min cycle, so each probe
# sees a fresh session status) — no token to chase.
CACHE_TTL_SECONDS = 15 * 60
_MAX_BODY_BYTES = 4 * 1024 * 1024
_UA = "mymts-helper/0.0 (+cspan-free-floor)"
# Committee mode: how many of today's scheduled hearings to probe per resolve, and
# the wall-clock budget for that probing (keeps the committee path well under the
# prober's per-channel deadline even on a heavy hearing day).
_MAX_COMMITTEE_CANDIDATES = 5
_COMMITTEE_PROBE_BUDGET_SECONDS = 18
_COMMITTEE_MASTER_TIMEOUT = 6
# A source_url whose query carries `schedule=committees` selects committee mode.
_COMMITTEE_MARKER = "committees"

# A filename we'll trust as a path component: letters (committee codes are mixed-case,
# e.g. `armedA062316`) then digits — no slash / traversal / odd characters.
_SAFE_FILENAME_RE = re.compile(r"^[A-Za-z]{2,12}[0-9]{6,8}$")


@dataclass(frozen=True)
class CSpanResolution:
    """Outcome of resolving a free Senate stream. `ok` is True only when a current
    session master URL was built; `hls_url` is then the Akamai master the prober
    validates (its variant fetch is the final live gate). `detail` carries the live
    committee name in committee mode (observability; the tile label stays static)."""
    ok: bool
    hls_url: str | None = None
    is_live: bool = False
    error: str | None = None
    detail: str | None = None


# ── pure decision logic (unit-tested, no I/O) ───────────────────────────────

def is_official_url(url: str) -> bool:
    """True iff `url` is HTTPS and its host is the Senate's own host."""
    try:
        p = urlparse(url)
    except (ValueError, TypeError):
        return False
    return p.scheme == "https" and (p.hostname or "").lower() in OFFICIAL_HOSTS


def is_committee_source(source_url: str) -> bool:
    """True iff the seeded source selects committee-hearings mode (`schedule=committees`)."""
    try:
        q = parse_qs(urlparse(source_url).query)
    except (ValueError, TypeError):
        return False
    return (q.get("schedule") or [""])[0].strip().lower() == _COMMITTEE_MARKER


def is_safe_filename(filename: str) -> bool:
    """True iff `filename` is safe as a single path component (the session code,
    e.g. stv061726 or armedA062316) — no slash / traversal / odd characters."""
    return bool(isinstance(filename, str) and _SAFE_FILENAME_RE.match(filename))


def extract_comm_filename(stream_url: str) -> tuple[str, str] | None:
    """From an ISVP URL (…/isvp/?comm=energy&filename=energy061726) return
    (comm, filename), or None if missing / unknown comm / unsafe filename."""
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
    """From the FLOOR schedule JSON, return (comm, filename) for the CURRENT
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


@dataclass(frozen=True)
class CommitteeMeeting:
    """One scheduled committee hearing parsed from the hearings XML feed."""
    comm: str
    filename: str
    date_iso: str   # YYYY-MM-DD (ET)
    time_iso: str   # HH:MM:SS (ET)
    committee: str  # human name, e.g. "Energy and Natural Resources"


def parse_hearings_xml(xml_text: str) -> list[CommitteeMeeting]:
    """Parse the committee hearings XML into the meetings we can build a master for.

    Only meetings with a `video_url` whose comm is a KNOWN committee streamID and
    whose filename is safe survive (others are silently dropped — never faked).
    Pure / defensive: any parse failure yields an empty list.
    """
    out: list[CommitteeMeeting] = []
    try:
        root = ET.fromstring(xml_text)
    except (ET.ParseError, DefusedXmlException):
        return out
    for m in root.findall("meeting"):
        video_url = (m.findtext("video_url") or "").strip()
        if not video_url:
            continue
        got = extract_comm_filename(video_url)
        if got is None:
            continue
        comm, filename = got
        out.append(CommitteeMeeting(
            comm=comm,
            filename=filename,
            date_iso=(m.findtext("date_iso_8601") or "").strip(),
            time_iso=(m.findtext("time_iso_8601") or "").strip(),
            committee=(m.findtext("committee") or "").strip(),
        ))
    return out


def select_committee_candidates(
    meetings: list[CommitteeMeeting], today_iso: str, *, max_n: int = _MAX_COMMITTEE_CANDIDATES,
) -> list[CommitteeMeeting]:
    """Today's hearings, most-recently-scheduled first, capped — the set whose
    Akamai masters we probe for a live one. A not-yet-started or finished hearing's
    master simply 404s, so probing this bounded set and taking the first live master
    is an honest 'is any Senate committee live right now' without faking."""
    today = [m for m in meetings if m.date_iso == today_iso]
    today.sort(key=lambda m: m.time_iso, reverse=True)
    return today[:max_n]


def build_master_url(comm: str, filename: str) -> str | None:
    """Build the Akamai HLS master from (comm, filename), the player's own way.
    Returns None for an unknown comm or an unsafe filename (defensive)."""
    stream_id = _COMM_STREAM_IDS.get(comm)
    if stream_id is None or not is_safe_filename(filename):
        return None
    return f"{_AKAMAI_HLS_BASE}/{stream_id}/{comm}/{filename}/master.m3u8"


def is_master_manifest(body: str) -> bool:
    """True iff `body` is an HLS MASTER manifest (an `#EXTM3U` listing variant
    streams). The committee master persists with a 200 even AFTER a hearing ends
    (it then points at a frozen VOD variant) — so this is only the first gate; the
    variant's `#EXT-X-ENDLIST` (see `is_live_media`) is what proves it's still live."""
    s = body.lstrip("\ufeff").lstrip()
    return s.startswith("#EXTM3U") and "#EXT-X-STREAM-INF" in s


def pick_first_variant(master_body: str, master_url: str) -> str | None:
    """First variant playlist URL from a master, absolutized against `master_url`
    (the ISVP variants are same-host relative paths, e.g. `master/index_1.m3u8`)."""
    expecting = False
    for raw in master_body.splitlines():
        line = raw.strip()
        if not line:
            continue
        if expecting and not line.startswith("#"):
            return urljoin(master_url, line)
        if line.startswith("#EXT-X-STREAM-INF"):
            expecting = True
        elif line.startswith("#"):
            expecting = False
    return None


def is_live_media(body: str) -> bool:
    """True iff `body` is a LIVE media playlist: it has segments (`#EXTINF`) and is
    NOT closed with `#EXT-X-ENDLIST`. An ENDED committee hearing's variant carries
    `#EXT-X-ENDLIST` — a frozen VOD — which this REJECTS so the wall never shows a
    finished hearing as live (the 'no frozen VOD' rule). This is the real liveness
    signal the bare master can't give: every committee master 200s all day."""
    s = body.lstrip("\ufeff")
    return "#EXTINF" in s and "#EXT-X-ENDLIST" not in s


def _default_clock() -> datetime.datetime:
    """Current time in US Eastern (the Senate schedule's timezone), DST-correct."""
    try:
        from zoneinfo import ZoneInfo
        return datetime.datetime.now(ZoneInfo("America/New_York"))
    except Exception:  # noqa: BLE001 — no tzdata: approximate ET (only nudges the
        # midnight date boundary, which has no real hearings)
        return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=-4)))


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
    return json.loads(_http_get_text(url, timeout))


def _http_get_text(url: str, timeout: int) -> str:
    """GET an OFFICIAL https URL (senate.gov or the Senate's own Akamai CDN) as text.
    Raises on a non-allowlisted host (the resolver never fetches anywhere else)."""
    host = (urlparse(url).hostname or "").lower()
    if host not in _FETCH_HOSTS:
        raise ValueError(f"refusing non-official url: {host!r}")
    req = Request(url, headers={"User-Agent": _UA})  # noqa: S310 — host-allowlisted https above
    with urlopen(req, timeout=timeout) as r:  # noqa: S310 — host-allowlisted https above
        return r.read(_MAX_BODY_BYTES + 1).decode("utf-8", errors="replace")


class CSpanResolver:
    """Resolves a free Senate stream (floor or committee hearings) to its current
    HLS master, TTL-cached. `http_get` (floor schedule JSON) and `http_get_text`
    (committee XML + candidate masters) are injectable so tests drive the resolver
    with synthetic output and never touch the network; `clock` is injectable so the
    committee 'today' filter is deterministic in tests."""

    def __init__(self, *, timeout: int = DEFAULT_RESOLVE_TIMEOUT, http_get=None,
                 http_get_text=None, cache: _Cache | None = None, clock=None) -> None:
        self._timeout = timeout
        self._cache = cache or _Cache()
        self._http_get = http_get or (lambda url: _http_get_json(url, self._timeout))
        self._http_get_text = http_get_text or (lambda url: _http_get_text(url, self._timeout))
        self._clock = clock or _default_clock

    def resolve(self, source_url: str, *, force_refresh: bool = False) -> CSpanResolution:
        """Resolve to the current Senate HLS master (floor or committee, by source).
        BLOCKING — run in an executor. Honest-offline (ok=False) when nothing is live;
        offline-safe (never raises) — any failure falls back to an honest unavailable."""
        if not force_refresh:
            cached = self._cache.get(source_url)
            if cached is not None:
                return cached
        try:
            # Defense: the seeded source must be the Senate's own host.
            if not is_official_url(source_url):
                return CSpanResolution(ok=False, error="source_not_official")
            if is_committee_source(source_url):
                result = self._resolve_committees()
            else:
                result = self._resolve_floor()
            if result.ok:
                self._cache.set(source_url, result, CACHE_TTL_SECONDS)
            return result
        except Exception as e:  # noqa: BLE001 — offline-safe: never crash the probe
            log.info("cspan_resolve_error", extra={"reason": str(e)[:140]})
            return CSpanResolution(ok=False, error=f"resolve_error:{str(e)[:100]}")

    def _resolve_floor(self) -> CSpanResolution:
        """Floor mode: convened-session filename → Akamai master (honest-offline when
        the chamber is not in session)."""
        schedule = self._http_get(SENATE_FLOOR_SCHEDULE_URL)
        got = parse_schedule(schedule)
        if got is None:
            return CSpanResolution(ok=False, is_live=False, error="not_in_session")
        comm, filename = got
        master = build_master_url(comm, filename)
        if master is None:
            return CSpanResolution(ok=False, error="build_failed")
        return CSpanResolution(ok=True, hls_url=master, is_live=True)

    def _resolve_committees(self) -> CSpanResolution:
        """Committee mode: among today's scheduled hearings (most-recent first,
        bounded), return the first whose Akamai master is live; else honest-offline.
        Self-bounded in wall-clock so the heavy-day path stays under the probe deadline."""
        xml_text = self._http_get_text(SENATE_COMMITTEE_SCHEDULE_URL)
        meetings = parse_hearings_xml(xml_text)
        today_iso = self._clock().date().isoformat()
        candidates = select_committee_candidates(meetings, today_iso)
        deadline = time.monotonic() + _COMMITTEE_PROBE_BUDGET_SECONDS
        for m in candidates:
            if time.monotonic() >= deadline:
                break
            master = build_master_url(m.comm, m.filename)
            if master is None:
                continue
            if self._master_is_live(master):
                return CSpanResolution(ok=True, hls_url=master, is_live=True, detail=m.committee)
        return CSpanResolution(ok=False, is_live=False, error="no_committee_live")

    def _master_is_live(self, master_url: str) -> bool:
        """True iff `master_url` is a GENUINELY-live ISVP stream — its master is a
        real manifest AND its variant is a live media playlist (no `#EXT-X-ENDLIST`).
        The variant check is decisive: a finished hearing's master still 200s but its
        variant is a frozen VOD, which is rejected here. Two bounded fetches; any
        failure (404 / unreachable / not-yet-live) is a quiet False (try the next)."""
        try:
            master_body = self._http_get_text(master_url)
        except Exception:  # noqa: BLE001 — 404 is the EXPECTED not-live signal
            return False
        if not is_master_manifest(master_body):
            return False
        variant_url = pick_first_variant(master_body, master_url)
        if variant_url is None:
            return False
        try:
            variant_body = self._http_get_text(variant_url)
        except Exception:  # noqa: BLE001 — variant gone = not live
            return False
        return is_live_media(variant_body)
