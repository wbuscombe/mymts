"""Tests for the NWS weather-radar widget — regions, cache, proxy endpoint, and
the way radar slots into the channel picker (web-only) + wall-config validation.

The proxy is exercised with an INJECTED fake fetch (no outbound socket); the cache
TTL with an injected clock; the picker/wall integration through the real app with
the SSRF-blocking resolver, so the honest-offline path is covered end to end.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config
from mymts_helper.fetcher import FetchError, FetchResult
from mymts_helper.weather import regions
from mymts_helper.weather.api import get_router as weather_router
from mymts_helper.weather.cache import RadarFrameCache

_GIF = b"GIF89a\x01\x00\x01\x00\x00\xff,\x00;"   # a tiny valid-enough GIF byte string


# ----- regions (the single source of truth) -----

def test_region_keys_map_to_slugs_and_sites():
    slugs = regions.radar_slugs()
    assert "weather-radar-conus" in slugs        # national
    assert "weather-radar-kilx" in slugs         # central Illinois (the local)
    assert all(s.startswith("weather-radar-") for s in slugs)
    assert len(slugs) == len(set(slugs))         # unique


def test_loop_url_built_for_known_regions():
    conus = regions.region_for_key("conus")
    kilx = regions.region_for_key("kilx")
    assert regions.loop_url_for_region(conus) == "https://radar.weather.gov/ridge/standard/CONUS_loop.gif"
    assert regions.loop_url_for_region(kilx) == "https://radar.weather.gov/ridge/standard/KILX_loop.gif"
    assert regions.region_for_key("nope") is None


def test_loop_url_rejects_a_malformed_site():
    bad = regions.RadarRegion(key="x", label="x", site="../evil")
    with pytest.raises(ValueError):
        regions.loop_url_for_region(bad)


def test_channel_entries_shape_for_the_picker():
    entries = {e["slug"]: e for e in regions.channel_entries()}
    kilx = entries["weather-radar-kilx"]
    assert kilx["kind"] == "weather-radar"
    assert kilx["category"] == "Weather Radar"
    assert kilx["current_url"] == "/api/weather/radar/kilx"   # the wall reads this as the img src
    assert kilx["status"] == "live" and kilx["browser_playable"] is True
    assert kilx["region"] == "kilx"


# ----- the TTL + last-good cache -----

def test_cache_fresh_within_ttl_then_expires_but_keeps_last_good():
    clock = {"t": 1000.0}
    cache = RadarFrameCache(ttl_seconds=300, now=lambda: clock["t"])
    cache.put("kilx", _GIF, "image/gif")
    assert cache.get_fresh("kilx").data == _GIF
    clock["t"] += 299
    assert cache.get_fresh("kilx") is not None            # still fresh
    clock["t"] += 2                                        # 301s elapsed > TTL
    assert cache.get_fresh("kilx") is None                # no longer fresh
    assert cache.get_last_good("kilx").data == _GIF       # last-good persists for the fallback


# ----- the proxy endpoint (injected fake fetch — no network) -----

def _bare_app(cache: RadarFrameCache, fetch) -> TestClient:
    app = FastAPI()
    app.include_router(weather_router(cache, fetch=fetch))
    return TestClient(app)


def test_endpoint_fetches_then_serves_from_cache():
    calls: list[str] = []

    async def _fetch(url, **_kw):
        calls.append(url)
        return FetchResult(url=url, status_code=200, content_type="image/gif", body=_GIF)

    client = _bare_app(RadarFrameCache(), _fetch)
    r1 = client.get("/api/weather/radar/conus")
    assert r1.status_code == 200
    assert r1.headers["content-type"].startswith("image/gif")
    assert r1.content == _GIF
    r2 = client.get("/api/weather/radar/conus")
    assert r2.status_code == 200
    assert len(calls) == 1            # second request served from cache — NWS not re-hit


def test_endpoint_unknown_region_404s():
    async def _fetch(url, **_kw):  # never called
        raise AssertionError("should not fetch an unknown region")

    client = _bare_app(RadarFrameCache(), _fetch)
    assert client.get("/api/weather/radar/atlantis").status_code == 404


def test_endpoint_serves_last_good_stale_on_upstream_error():
    state = {"fail": False}

    async def _fetch(url, **_kw):
        if state["fail"]:
            raise FetchError("nws_down")
        return FetchResult(url=url, status_code=200, content_type="image/gif", body=_GIF)

    # ttl < 0 → never "fresh", so the second call refetches (and fails), exercising
    # the last-good fallback rather than a cache hit.
    client = _bare_app(RadarFrameCache(ttl_seconds=-1), _fetch)
    assert client.get("/api/weather/radar/kilx").status_code == 200   # primes last-good
    state["fail"] = True
    r = client.get("/api/weather/radar/kilx")
    assert r.status_code == 200
    assert r.content == _GIF                       # the LAST real frame, not a fake
    assert r.headers.get("x-radar-stale") == "1"   # honestly marked stale


def test_endpoint_503_when_never_loaded_and_upstream_down():
    async def _fetch(url, **_kw):
        raise FetchError("nws_down")

    client = _bare_app(RadarFrameCache(ttl_seconds=-1), _fetch)
    assert client.get("/api/weather/radar/conus").status_code == 503   # honest offline, no fake


def test_endpoint_502_on_non_image_upstream():
    async def _fetch(url, **_kw):
        return FetchResult(
            url=url, status_code=200, content_type="text/html", body=b"<html>404</html>"
        )

    client = _bare_app(RadarFrameCache(ttl_seconds=-1), _fetch)
    assert client.get("/api/weather/radar/conus").status_code == 502


# ----- integration through the real app -----

async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]   # loopback → the SSRF guard rejects → FetchError


def _cfg(tmp_path: Path) -> Config:
    return Config(
        phantom_mode=False, port=8091, log_level="warning",
        build_sha="dev", build_version="0.0.0-dev",
        data_dir=str(tmp_path / "data"),
        feed_poll_interval_seconds=3600, channel_probe_interval_seconds=3600,
    )


def _client(tmp_path: Path) -> TestClient:
    return TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver))


def test_app_mounts_radar_route_with_honest_offline(tmp_path: Path):
    client = _client(tmp_path)
    # blocked resolver → no last-good → honest 503 (never a faked frame)
    assert client.get("/api/weather/radar/conus").status_code == 503
    assert client.get("/api/weather/radar/bogus").status_code == 404


def test_channels_lists_radar_widgets_unconditionally(tmp_path: Path):
    """Unified registry (2026-07): radar is served to EVERY surface — the plain
    /api/channels the native TV picker fetches now lists the radar widgets, closing
    the old web-only split. There is no membership-changing query param: the legacy
    ?widgets=1 an older cached web client might still send is ignored, yielding the
    identical set (no per-surface filtering anywhere)."""
    client = _client(tmp_path)
    base = client.get("/api/channels").json()["channels"]
    radar = [c for c in base if c["slug"].startswith("weather-radar-")]
    assert len(radar) == len(regions.radar_slugs())          # ALL radar slugs, no gate
    assert any(c["slug"] == "weather-radar-kilx" for c in radar)   # the local (central IL)
    assert all(c["kind"] == regions.RADAR_KIND for c in radar)
    assert all(regions.is_widget_kind(c["kind"]) for c in radar)   # widget-kind concept
    assert all(c["category"] == "Weather Radar" for c in radar)
    assert all(c["current_url"].startswith("/api/weather/radar/") for c in radar)
    # The legacy opt-in param is a no-op now — same membership, not an error.
    legacy = client.get("/api/channels?widgets=1").json()["channels"]
    assert {c["slug"] for c in legacy} == {c["slug"] for c in base}


def _cells_with_first(client: TestClient, channel: str) -> list[dict]:
    # Keep the full cells length (validation requires rows*cols entries); only
    # repoint cell 0 at the given source.
    cells = client.get("/api/wall").json()["cells"]
    cells[0] = {**cells[0], "channel": channel}
    return cells


def test_wall_accepts_a_radar_cell(tmp_path: Path):
    client = _client(tmp_path)
    r = client.put("/api/wall", json={"cells": _cells_with_first(client, "weather-radar-kilx")})
    assert r.status_code == 200, r.text
    assert r.json()["cells"][0]["channel"] == "weather-radar-kilx"


def test_wall_rejects_an_unknown_radar_slug(tmp_path: Path):
    client = _client(tmp_path)
    r = client.put("/api/wall", json={"cells": _cells_with_first(client, "weather-radar-atlantis")})
    assert r.status_code == 422
