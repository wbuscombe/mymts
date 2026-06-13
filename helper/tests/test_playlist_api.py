"""Endpoint tests for /api/playlist.m3u (+ per-profile).

These exercise the live-vs-not-live gating against a real seeded SQLite DB
(the env-invariant: an unavailable channel must NOT appear), the no-proxy
property (the M3U points at the upstream resolved URL), and per-profile
selection. The app is built and channels are marked live BEFORE a
non-context-manager TestClient is created, so the lifespan/prober never
runs and channel state stays exactly as the test set it.
"""

from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from mymts_helper import db
from mymts_helper.app import create_app
from mymts_helper.channels import registry
from mymts_helper.config import Config

URL_BBC = "https://cdn.test/bbc/master.m3u8"
URL_CNN = "https://cdn.test/cnn/master.m3u8"
URL_NASA = "https://cdn.test/nasa/master.m3u8"


async def _block_all_resolver(host: str) -> list[str]:
    # Force the SSRF guard to reject any outbound fetch (defence in depth;
    # the prober never starts here anyway).
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, profiles_file: str | None = None, phantom: bool = False) -> Config:
    return Config(
        phantom_mode=phantom,
        port=8091,
        log_level="warning",
        build_sha="dev",
        build_version="0.0.0-dev",
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        feed_retention_days=14,
        channel_probe_interval_seconds=3600,
        profiles_file=profiles_file,
    )


def _not_helper_hosted(stream_line: str) -> bool:
    # no-proxy: a stream line is the channel's upstream URL, never a
    # helper-relative path or a URL pointing back at the helper itself.
    # (A real upstream URL may legitimately contain '/playlist.m3u8', so a
    # substring check on '/playlist' would be wrong — check the origin.)
    return not stream_line.startswith(("/", "http://testserver", "https://testserver"))


def _db_path(tmp_path: Path) -> Path:
    return Path(tmp_path) / "mymts-helper.db"


def _set_status(tmp_path: Path, slug: str, *, status: str, url: str) -> None:
    conn = db.connect(_db_path(tmp_path))
    try:
        row = next(c for c in registry.list_channels(conn) if c.slug == slug)
        registry.update_status(
            conn,
            channel_id=row.id,
            status=status,
            current_url=url,
            error=None if status == "live" else "probe failed",
            success=status == "live",
        )
    finally:
        conn.close()


def test_default_playlist_lists_only_live_channels(tmp_path: Path) -> None:
    app = create_app(_cfg(tmp_path), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    _set_status(tmp_path, "cnn", status="unavailable", url=URL_CNN)
    client = TestClient(app)

    r = client.get("/api/playlist.m3u")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/x-mpegurl")
    body = r.text
    assert body.startswith("#EXTM3U")
    # the live channel's UPSTREAM url is present verbatim (no-proxy)...
    assert URL_BBC in body.splitlines()
    assert 'tvg-id="bbc-news"' in body
    # ...and the unavailable channel is absent (honest degradation / env gate)
    assert URL_CNN not in body
    assert 'tvg-id="cnn"' not in body


def test_playlist_does_not_proxy_through_the_helper(tmp_path: Path) -> None:
    app = create_app(_cfg(tmp_path), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    client = TestClient(app)

    body = client.get("/api/playlist.m3u").text
    stream_lines = [ln for ln in body.splitlines() if ln and not ln.startswith("#")]
    assert stream_lines == [URL_BBC]
    # the helper is the resolver/shield, never the byte path
    for ln in stream_lines:
        assert _not_helper_hosted(ln)


def test_named_profile_subsets_and_orders(tmp_path: Path) -> None:
    pf = tmp_path / "profiles.json"
    pf.write_text(json.dumps({"profiles": [{"name": "office", "slugs": ["cnn", "bbc-news"]}]}))
    app = create_app(_cfg(tmp_path, profiles_file=str(pf)), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    _set_status(tmp_path, "cnn", status="live", url=URL_CNN)
    _set_status(tmp_path, "nasa-tv", status="live", url=URL_NASA)
    client = TestClient(app)

    body = client.get("/api/playlist/office.m3u").text
    assert body.index(URL_CNN) < body.index(URL_BBC)  # profile order: cnn, then bbc
    # a live channel NOT in the profile is excluded
    assert URL_NASA not in body


def test_named_profile_drops_offline_slug(tmp_path: Path) -> None:
    pf = tmp_path / "profiles.json"
    pf.write_text(json.dumps({"profiles": [{"name": "office", "slugs": ["cnn", "bbc-news"]}]}))
    app = create_app(_cfg(tmp_path, profiles_file=str(pf)), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    _set_status(tmp_path, "cnn", status="unavailable", url=URL_CNN)
    client = TestClient(app)

    body = client.get("/api/playlist/office.m3u").text
    assert URL_BBC in body
    assert URL_CNN not in body


def test_unknown_profile_is_404(tmp_path: Path) -> None:
    app = create_app(_cfg(tmp_path), resolver=_block_all_resolver)
    client = TestClient(app)
    assert client.get("/api/playlist/does-not-exist.m3u").status_code == 404


def test_default_playlist_is_honest_empty_when_nothing_live(tmp_path: Path) -> None:
    app = create_app(_cfg(tmp_path), resolver=_block_all_resolver)
    client = TestClient(app)
    # nothing marked live → a valid, empty playlist (never a faked lineup)
    assert client.get("/api/playlist.m3u").text == "#EXTM3U\n"


def test_default_playlist_orders_by_slug(tmp_path: Path) -> None:
    app = create_app(_cfg(tmp_path), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    _set_status(tmp_path, "cnn", status="live", url=URL_CNN)
    _set_status(tmp_path, "nasa-tv", status="live", url=URL_NASA)
    client = TestClient(app)

    body = client.get("/api/playlist.m3u").text
    # registry.list_channels is ORDER BY slug: bbc-news < cnn < nasa-tv
    assert body.index('tvg-id="bbc-news"') < body.index('tvg-id="cnn"')
    assert body.index('tvg-id="cnn"') < body.index('tvg-id="nasa-tv"')
    assert len([ln for ln in body.splitlines() if ln.startswith("#EXTINF")]) == 3


def test_phantom_default_playlist_lists_the_seeded_lineup(tmp_path: Path) -> None:
    # Phantom mode marks the whole seeded lineup live (the env-shape the wall
    # actually demos with) — the default playlist must render it all, upstream.
    app = create_app(_cfg(tmp_path, phantom=True))
    with TestClient(app) as client:
        channels = client.get("/api/channels").json()["channels"]
        live = [c for c in channels if c["status"] == "live"]
        assert live  # phantom preloads seeded channels as live
        body = client.get("/api/playlist.m3u").text
        assert body.startswith("#EXTM3U")
        extinf = [ln for ln in body.splitlines() if ln.startswith("#EXTINF")]
        stream_lines = [ln for ln in body.splitlines() if ln and not ln.startswith("#")]
        assert len(extinf) == len(live)
        assert len(stream_lines) == len(live)
        for ln in stream_lines:
            assert _not_helper_hosted(ln)  # no-proxy holds on real seeded URLs


def test_malformed_profiles_file_still_serves_default(tmp_path: Path) -> None:
    # Bad config degrades to default-only end-to-end — the wall still boots
    # and serves the lineup (AGENTS negative/error path, through the wiring).
    pf = tmp_path / "profiles.json"
    pf.write_text("{ not json ]")
    app = create_app(_cfg(tmp_path, profiles_file=str(pf)), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    client = TestClient(app)

    assert URL_BBC in client.get("/api/playlist.m3u").text
    # the would-be profile never loaded → 404, not a 500
    assert client.get("/api/playlist/office.m3u").status_code == 404


def test_empty_named_profile_is_honest_empty(tmp_path: Path) -> None:
    # slugs=[] => nothing (the opposite of default's slugs=None => everything);
    # the wiring must keep them distinct even when channels are live.
    pf = tmp_path / "profiles.json"
    pf.write_text(json.dumps({"profiles": [{"name": "void", "slugs": []}]}))
    app = create_app(_cfg(tmp_path, profiles_file=str(pf)), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    client = TestClient(app)

    assert client.get("/api/playlist/void.m3u").text == "#EXTM3U\n"


def test_profile_naming_unknown_slug_drops_it(tmp_path: Path) -> None:
    # A profile slug that was never a channel is dropped honestly (200, not 500).
    pf = tmp_path / "profiles.json"
    pf.write_text(
        json.dumps({"profiles": [{"name": "office", "slugs": ["bbc-news", "nonexistent-channel"]}]})
    )
    app = create_app(_cfg(tmp_path, profiles_file=str(pf)), resolver=_block_all_resolver)
    _set_status(tmp_path, "bbc-news", status="live", url=URL_BBC)
    client = TestClient(app)

    body = client.get("/api/playlist/office.m3u").text
    assert body.startswith("#EXTM3U")
    assert URL_BBC in body
    assert len([ln for ln in body.splitlines() if ln.startswith("#EXTINF")]) == 1
