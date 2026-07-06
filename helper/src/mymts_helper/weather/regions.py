"""NWS weather-radar regions — the selectable radar sources + their NWS sites.

A radar cell is a SELECTABLE per-cell source ("pseudo-channel"): the wall config
stores a channel slug like ``weather-radar-kilx`` exactly as it stores a video
channel's slug, so it rides the existing per-cell picker + partial-merge with no
new config field (the region is encoded in the slug). This module is the SINGLE
source of truth for the region set; it is surfaced to the web picker + wall as
synthetic ``/api/channels`` rows (:func:`channel_entries`) and to the wall-config
validator as the set of legal radar slugs (:func:`radar_slugs`).

The imagery is the free public NWS RIDGE "standard" animated radar loop
(``{SITE}_loop.gif`` — a 10-frame GIF89a NWS assembles server-side, refreshed
~every 5 min). No key, no DRM, no ToS gate. The helper proxies + caches it
(``weather/api.py``) so the browser fetch is same-origin / CORS-clean and NWS is
hit at most once per region per cache TTL.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from ..channels.category import WEATHER_RADAR

# A radar cell's slug is this prefix + the region key (e.g. weather-radar-kilx).
# Keeping radar in the channel-slug namespace lets it reuse the per-cell `channel`
# field unchanged (no new wall-config field; the partial-merge already covers it).
RADAR_SLUG_PREFIX = "weather-radar-"

# The pseudo-channel `kind`, distinct from the video kinds (hls / youtube / cspan)
# so the wall render branches to the animated-image path instead of a <video>.
RADAR_KIND = "weather-radar"

# The set of non-video WIDGET kinds. A widget-kind channel is a FIRST-CLASS registry
# entry every surface LISTS (the unified-registry rule); its `kind` only tells a
# renderer HOW to render it — an animated image / looping frames rather than a
# <video>/ExoPlayer stream — and carries no audio or caption track (so a surface's
# per-tile audio/caption toggles are hidden for it). Radar is the first widget kind;
# a future widget (e.g. a metar/alerts card) joins this set. The web (render.mjs),
# the native app (Channel.isWidget), and the parity guard all key on this concept.
WIDGET_KINDS = frozenset({RADAR_KIND})


def is_widget_kind(kind: object) -> bool:
    """True iff ``kind`` names a non-video WIDGET source (rendered as an animated
    image, listed on every surface, no audio/captions). See :data:`WIDGET_KINDS`."""
    return isinstance(kind, str) and kind in WIDGET_KINDS

# The free public NWS RIDGE "standard" animated loop (a 10-frame GIF89a). Verified
# live: CONUS_loop.gif (national) + {SITE}_loop.gif per WSR-88D site, ~5-min scan.
NWS_LOOP_URL = "https://radar.weather.gov/ridge/standard/{site}_loop.gif"

# Defense-in-depth: a region's NWS site id is interpolated into the upstream URL,
# so it must match this strict shape (the region set is curated below, but the
# URL builder re-checks so a bad edit can never inject into the fetched URL).
_SITE_RE = re.compile(r"^[A-Z0-9-]{3,12}$")


@dataclass(frozen=True)
class RadarRegion:
    key: str      # short url-safe key (the proxy path param + the slug suffix)
    label: str    # human label shown in the picker (under the Weather Radar group)
    site: str     # NWS RIDGE site id (CONUS for national, or a KXXX WSR-88D site)


# Curated radar sources. National + central Illinois (the local KILX) lead, then a
# spread of major-metro WSR-88D sites. Each site's _loop.gif was verified 200 +
# image/gif before listing. (CONUS-LARGE is intentionally omitted — 3400x1600 /
# ~6.5MB is too heavy for a wall tile.)
RADAR_REGIONS: tuple[RadarRegion, ...] = (
    RadarRegion("conus", "US National", "CONUS"),
    RadarRegion("kilx", "Central Illinois (Lincoln)", "KILX"),
    RadarRegion("klot", "Chicago", "KLOT"),
    RadarRegion("kdtx", "Detroit", "KDTX"),
    RadarRegion("kmpx", "Minneapolis-St. Paul", "KMPX"),
    RadarRegion("kokx", "New York City", "KOKX"),
    RadarRegion("kffc", "Atlanta", "KFFC"),
    RadarRegion("kfws", "Dallas-Fort Worth", "KFWS"),
    RadarRegion("kewx", "Austin-San Antonio", "KEWX"),
    RadarRegion("ktlx", "Oklahoma City", "KTLX"),
    RadarRegion("kmux", "San Francisco Bay", "KMUX"),
    RadarRegion("katx", "Seattle", "KATX"),
)

_BY_KEY: dict[str, RadarRegion] = {r.key: r for r in RADAR_REGIONS}


def radar_slug(key: str) -> str:
    """The channel slug for a region key (e.g. 'kilx' -> 'weather-radar-kilx')."""
    return f"{RADAR_SLUG_PREFIX}{key}"


def region_for_key(key: str) -> RadarRegion | None:
    """The region for a proxy path key, or None if unknown (the endpoint 404s)."""
    return _BY_KEY.get(key)


def is_radar_slug(slug: object) -> bool:
    """True iff ``slug`` is one of our radar pseudo-channel slugs."""
    return isinstance(slug, str) and slug.startswith(RADAR_SLUG_PREFIX)


def radar_slugs() -> list[str]:
    """Every legal radar cell slug — added to the wall-config validator's slug set
    so a cell may be assigned a radar source (parity with a real channel slug)."""
    return [radar_slug(r.key) for r in RADAR_REGIONS]


def proxy_path(key: str) -> str:
    """The same-origin helper proxy path the wall loads as the radar image."""
    return f"/api/weather/radar/{key}"


def loop_url_for_region(region: RadarRegion) -> str:
    """The upstream NWS loop-GIF URL for a region. Re-validates the site id (it is
    interpolated into the URL) so a malformed entry can never reach the fetcher."""
    if not _SITE_RE.fullmatch(region.site):
        raise ValueError(f"invalid radar site id: {region.site!r}")
    return NWS_LOOP_URL.format(site=region.site)


def channel_entries() -> list[dict[str, Any]]:
    """Synthetic ``/api/channels`` rows for the radar regions — surfaced to EVERY
    surface (unified registry, 2026-07: no ``?widgets`` gate) so each region appears in
    the per-cell picker under the Weather Radar group on the native TV, the web /app/,
    and /control/ alike, and the wall can look it up by slug exactly like a video
    channel. ``current_url`` is the helper proxy path (the wall reads it as the
    image src — parity with a video channel's HLS url); ``status=live`` +
    ``browser_playable`` make it sort + render as an available source. The actual
    radar freshness is the runtime truth of the <img> load (honest fallback), not
    this static availability flag."""
    return [
        {
            "slug": radar_slug(r.key),
            "label": r.label,
            "kind": RADAR_KIND,
            "category": WEATHER_RADAR,
            "current_url": proxy_path(r.key),
            "status": "live",
            "browser_playable": True,
            "enabled": True,
            "last_check_at": None,
            "last_success_at": None,
            "last_error": None,
            "error_count": 0,
            "region": r.key,
        }
        for r in RADAR_REGIONS
    ]
