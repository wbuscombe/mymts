"""Tests for /health.

Contract (pinned at `schema_version: 1`):
  - schema_version is 1 (changes require a coordinated update with
    claude-status-bot — see SOURCES OF TRUTH section in the helper
    README). Adding fields is backward-compatible; removing or
    renaming is a bump.
  - top-level fields: ok, ready, phantom, build_sha, version,
    uptime_seconds, schema_version.
  - Stage 2 additions: feeds {sources_count, items_count, stale_sources,
    last_poll_at} and channels {channels_count, live_count,
    unavailable_count, last_probe_at}.
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


async def _block_all_resolver(host: str) -> list[str]:
    """Test resolver that hands back a private IP for every host. Combined
    with the fetcher's SSRF guard, this guarantees no test ever opens a
    real socket — even though the lifespan starts the pollers in
    non-phantom mode."""
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, phantom: bool = False,
         build_sha: str = "abc1234", build_version: str = "1.2.3") -> Config:
    return Config(
        phantom_mode=phantom,
        port=8091,
        log_level="info",
        build_sha=build_sha,
        build_version=build_version,
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        feed_retention_days=14,
        channel_probe_interval_seconds=3600,
    )


@pytest.fixture
def client_normal(tmp_path: Path):
    # `with` triggers FastAPI's lifespan; the explicit blocking resolver
    # guarantees no real network call even though phantom mode is off.
    with TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver)) as c:
        yield c


@pytest.fixture
def client_phantom(tmp_path: Path):
    cfg = _cfg(tmp_path, phantom=True, build_sha="dev", build_version="0.0.0-dev")
    with TestClient(create_app(cfg)) as c:
        yield c


def test_health_returns_pinned_schema_version(client_normal: TestClient) -> None:
    r = client_normal.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["schema_version"] == 1, (
        "Bumping schema_version requires a coordinated update in claude-status-bot. "
        "Adding fields is OK; removing or renaming requires a major bump."
    )


def test_health_reports_ok_and_ready(client_normal: TestClient) -> None:
    body = client_normal.get("/health").json()
    assert body["ok"] is True
    assert body["ready"] is True


def test_health_surfaces_build_identity(client_normal: TestClient) -> None:
    body = client_normal.get("/health").json()
    assert body["build_sha"] == "abc1234"
    assert body["version"] == "1.2.3"


def test_health_dev_defaults_are_legible(client_phantom: TestClient) -> None:
    body = client_phantom.get("/health").json()
    assert body["build_sha"] == "dev"
    assert body["version"] == "0.0.0-dev"


def test_health_reflects_phantom_mode(
    client_normal: TestClient, client_phantom: TestClient
) -> None:
    assert client_normal.get("/health").json()["phantom"] is False
    assert client_phantom.get("/health").json()["phantom"] is True


def test_uptime_is_non_negative_and_monotonic(client_normal: TestClient) -> None:
    first = client_normal.get("/health").json()["uptime_seconds"]
    time.sleep(0.05)
    second = client_normal.get("/health").json()["uptime_seconds"]
    assert first >= 0
    assert second >= first


def test_docs_are_disabled(tmp_path: Path) -> None:
    with TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver)) as client:
        assert client.get("/docs").status_code == 404
        assert client.get("/redoc").status_code == 404
        assert client.get("/openapi.json").status_code == 404


# ---- Stage 2 additions to /health ----


def test_health_includes_feeds_subobject(client_normal: TestClient) -> None:
    feeds = client_normal.get("/health").json()["feeds"]
    assert "sources_count" in feeds
    assert "items_count" in feeds
    assert "stale_sources" in feeds
    assert "last_poll_at" in feeds


def test_health_includes_channels_subobject(client_normal: TestClient) -> None:
    channels = client_normal.get("/health").json()["channels"]
    assert "channels_count" in channels
    assert "live_count" in channels
    assert "unavailable_count" in channels
    assert "last_probe_at" in channels


def test_health_phantom_mode_preloads_channels_live(client_phantom: TestClient) -> None:
    """In phantom mode the lifespan preloads channels to live so the /health
    surface tells the truth: the helper is healthy and "channels are up"."""
    body = client_phantom.get("/health").json()
    assert body["channels"]["channels_count"] >= 2
    # Preload marks every seeded channel as live with current_url set.
    assert body["channels"]["live_count"] == body["channels"]["channels_count"]
    assert body["channels"]["unavailable_count"] == 0
