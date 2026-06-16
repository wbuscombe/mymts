"""Tests for the yt-dlp YouTube-live resolver.

The resolver's `extract_info` is injectable, so every test drives it with a
synthetic yt-dlp dict (or a raised DownloadError) and NEVER touches the
network. Cache TTL is asserted against the manifest's own `expire` epoch.
"""

from __future__ import annotations

import time

import yt_dlp

from mymts_helper.channels import youtube_resolver as yr


def _info(*, is_live=True, manifest_url=None, formats=None, url=None,
          title="T", live_status=None):
    info = {"is_live": is_live, "title": title}
    if live_status is not None:
        info["live_status"] = live_status
    if manifest_url is not None:
        info["manifest_url"] = manifest_url
    if url is not None:
        info["url"] = url
    if formats is not None:
        info["formats"] = formats
    return info


# ── URLCache ───────────────────────────────────────────────────────────────

def test_urlcache_get_set_roundtrip() -> None:
    c = yr.URLCache()
    res = yr.YouTubeResolution(ok=True, hls_url="https://x/m.m3u8")
    c.set("k", res, ttl=100)
    assert c.get("k") is res


def test_urlcache_expires() -> None:
    c = yr.URLCache()
    c.set("k", yr.YouTubeResolution(ok=True), ttl=-1)  # already expired
    assert c.get("k") is None


def test_urlcache_invalidate_and_clear() -> None:
    c = yr.URLCache()
    c.set("a", yr.YouTubeResolution(ok=True), ttl=100)
    c.set("b", yr.YouTubeResolution(ok=True), ttl=100)
    c.invalidate("a")
    assert c.get("a") is None
    assert c.get("b") is not None
    c.clear()
    assert c.get("b") is None


# ── _extract_hls_url precedence ──────────────────────────────────────────────

def test_extract_hls_prefers_manifest_url() -> None:
    info = _info(manifest_url="https://gv/hls_variant/x.m3u8",
                 url="https://gv/other.m3u8")
    assert yr._extract_hls_url(info) == "https://gv/hls_variant/x.m3u8"


def test_extract_hls_falls_back_to_format_url() -> None:
    info = _info(manifest_url=None,
                 formats=[{"protocol": "m3u8_native", "url": "https://gv/f.m3u8"}])
    assert yr._extract_hls_url(info) == "https://gv/f.m3u8"


def test_extract_hls_none_when_no_hls() -> None:
    info = _info(manifest_url=None, formats=[{"protocol": "https", "url": "https://gv/x.mp4"}])
    assert yr._extract_hls_url(info) is None


# ── expiry parsing + TTL derivation ──────────────────────────────────────────

def test_manifest_expiry_from_query() -> None:
    assert yr._manifest_expiry("https://gv/hls/x.m3u8?expire=1781655040&ei=x") == 1781655040


def test_manifest_expiry_from_path() -> None:
    url = "https://gv/api/manifest/hls_variant/expire/1781655040/ei/x"
    assert yr._manifest_expiry(url) == 1781655040


def test_manifest_expiry_absent() -> None:
    assert yr._manifest_expiry("https://gv/x.m3u8") is None


def test_cache_ttl_clamped_to_max_for_distant_expiry() -> None:
    # ~6 h out → clamped to MAX_CACHE_TTL_SECONDS.
    ttl = yr._cache_ttl_for(int(time.time()) + 6 * 3600)
    assert ttl == yr.MAX_CACHE_TTL_SECONDS


def test_cache_ttl_floored_for_imminent_expiry() -> None:
    # Expiry already inside the safety margin → floored to MIN, never negative.
    ttl = yr._cache_ttl_for(int(time.time()) + 60)
    assert ttl == yr.MIN_CACHE_TTL_SECONDS


def test_cache_ttl_fallback_when_no_expiry() -> None:
    assert yr._cache_ttl_for(None) == yr.FALLBACK_CACHE_TTL_SECONDS


# ── resolve() ────────────────────────────────────────────────────────────────

def test_resolve_live_with_manifest() -> None:
    exp = int(time.time()) + 6 * 3600
    url = f"https://gv/api/manifest/hls_variant/expire/{exp}/x.m3u8"
    r = yr.YouTubeResolver(extract_info=lambda u: _info(manifest_url=url))
    out = r.resolve("https://www.youtube.com/@x/live")
    assert out.ok is True
    assert out.hls_url == url
    assert out.is_live is True
    assert out.expires_at is not None


def test_resolve_not_live_is_honest_offline() -> None:
    r = yr.YouTubeResolver(extract_info=lambda u: _info(is_live=False, manifest_url="https://gv/x.m3u8"))
    out = r.resolve("https://www.youtube.com/@x/live")
    assert out.ok is False
    assert out.is_live is False
    assert out.error == "not_live"


def test_resolve_live_but_no_manifest() -> None:
    r = yr.YouTubeResolver(extract_info=lambda u: _info(manifest_url=None, formats=[]))
    out = r.resolve("https://www.youtube.com/@x/live")
    assert out.ok is False
    assert out.error == "no_hls_manifest"


def test_resolve_download_error_offline_marker_is_not_live() -> None:
    def boom(u):
        raise yt_dlp.utils.DownloadError(
            "ERROR: [youtube] x: This live event will begin in 23 hours.")
    out = yr.YouTubeResolver(extract_info=boom).resolve("https://www.youtube.com/@x/live")
    assert out.ok is False
    assert out.error == "not_live"


def test_resolve_download_error_other_is_resolve_error() -> None:
    def boom(u):
        raise yt_dlp.utils.DownloadError("ERROR: [youtube] x: Sign in to confirm you're not a bot")
    out = yr.YouTubeResolver(extract_info=boom).resolve("https://www.youtube.com/@x/live")
    assert out.ok is False
    assert out.error.startswith("resolve_error:")


def test_resolve_unexpected_exception_is_caught() -> None:
    def boom(u):
        raise RuntimeError("kaboom")
    out = yr.YouTubeResolver(extract_info=boom).resolve("https://www.youtube.com/@x/live")
    assert out.ok is False
    assert out.error.startswith("resolve_unexpected:")


def test_resolve_caches_positive_and_serves_from_cache() -> None:
    calls = {"n": 0}
    exp = int(time.time()) + 6 * 3600
    url = f"https://gv/hls_variant/x.m3u8?expire={exp}"

    def extract(u):
        calls["n"] += 1
        return _info(manifest_url=url)

    r = yr.YouTubeResolver(extract_info=extract)
    a = r.resolve("https://www.youtube.com/@x/live")
    b = r.resolve("https://www.youtube.com/@x/live")
    assert a is b               # second call served from cache
    assert calls["n"] == 1      # extractor invoked exactly once


def test_resolve_force_refresh_bypasses_cache() -> None:
    calls = {"n": 0}
    exp = int(time.time()) + 6 * 3600
    url = f"https://gv/hls_variant/x.m3u8?expire={exp}"

    def extract(u):
        calls["n"] += 1
        return _info(manifest_url=url)

    r = yr.YouTubeResolver(extract_info=extract)
    r.resolve("https://www.youtube.com/@x/live")
    r.resolve("https://www.youtube.com/@x/live", force_refresh=True)
    assert calls["n"] == 2


def test_resolve_negative_outcome_not_cached() -> None:
    calls = {"n": 0}

    def extract(u):
        calls["n"] += 1
        return _info(is_live=False)

    r = yr.YouTubeResolver(extract_info=extract)
    r.resolve("https://www.youtube.com/@x/live")
    r.resolve("https://www.youtube.com/@x/live")
    assert calls["n"] == 2      # offline is re-checked, never cached
