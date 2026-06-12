"""Phantom-mode contract test.

Phantom mode (`PHANTOM_MODE=1`) is the operator's onboarding contract:
the helper boots, serves /api/feed and /api/channels with synthetic
data, and makes **zero outbound HTTP calls**. Run this test (`uv run
pytest -k phantom`) to assert the contract holds — if a future change
ever slips a network call into the phantom path, this test fails.
(A CI gate to run it on every push is planned — see
docs/adversarial-review-2026-06.md, DEPLOY-4.)

The mechanism: phantom mode swaps the fetcher's resolver for one that
raises on every host lookup attempt. Any code path that tries to fetch
something raises `PhantomNetworkBlocked` and surfaces as an error log /
failed test, not a silent live fetch.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config
from mymts_helper.phantom import PhantomNetworkBlocked, phantom_resolver


def _cfg(tmp_path: Path) -> Config:
    return Config(
        phantom_mode=True,
        port=8091,
        log_level="warning",
        build_sha="dev",
        build_version="0.0.0-dev",
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        feed_retention_days=14,
        channel_probe_interval_seconds=3600,
    )


async def test_phantom_resolver_refuses_everything() -> None:
    """First line of the no-network contract: the resolver itself refuses."""
    with pytest.raises(PhantomNetworkBlocked):
        await phantom_resolver("example.test")
    with pytest.raises(PhantomNetworkBlocked):
        await phantom_resolver("127.0.0.1")


def test_phantom_app_boots_and_serves_synthetic_data(tmp_path: Path) -> None:
    """End-to-end: helper boots in phantom mode, both API surfaces respond,
    and the response bodies contain the synthetic fixtures preload wrote."""
    with TestClient(create_app(_cfg(tmp_path))) as client:
        # /health green
        h = client.get("/health").json()
        assert h["ok"] and h["ready"]
        assert h["phantom"] is True

        # /api/feed has the preloaded items
        feed = client.get("/api/feed").json()
        assert feed["schema_version"] == 1
        assert len(feed["items"]) >= 1
        assert any("Phantom" in it["title"] for it in feed["items"])

        # /api/channels has the seeded channels, all live
        ch = client.get("/api/channels").json()
        assert ch["schema_version"] == 1
        assert ch["channels"], "phantom should expose seeded channels"
        for c in ch["channels"]:
            assert c["status"] == "live"


def test_phantom_health_reports_populated_state(tmp_path: Path) -> None:
    """/health in phantom mode must show non-zero counts so a consumer
    (claude-status-bot) sees the synthetic state, not an empty helper."""
    with TestClient(create_app(_cfg(tmp_path))) as client:
        h = client.get("/health").json()
        assert h["feeds"]["sources_count"] >= 1
        assert h["feeds"]["items_count"] >= 1
        assert h["channels"]["channels_count"] >= 2
        assert h["channels"]["live_count"] == h["channels"]["channels_count"]
