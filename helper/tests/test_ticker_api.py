"""Contract tests for /api/ticker/{markets,sports} + phantom behavior."""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


async def _block_all_resolver(host: str) -> list[str]:
    # Force the SSRF guard to reject any outbound fetch — the ticker
    # pollers start but every fetch fails, so the endpoint must serve the
    # honest all-sample snapshot rather than 500 or fabricate data.
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, phantom: bool = False) -> Config:
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
        markets_poll_interval_seconds=3600,
        sports_poll_interval_seconds=3600,
    )


@pytest.fixture
def client(tmp_path: Path):
    with TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver)) as c:
        yield c


@pytest.fixture
def phantom_client(tmp_path: Path):
    with TestClient(create_app(_cfg(tmp_path, phantom=True))) as c:
        yield c


def test_markets_envelope(client: TestClient) -> None:
    body = client.get("/api/ticker/markets").json()
    assert body["schema_version"] == 1
    assert body["mode"] == "markets"
    assert isinstance(body["entries"], list) and body["entries"]
    for e in body["entries"]:
        for field in ("symbol", "display", "direction", "is_sample"):
            assert field in e
        assert e["direction"] in ("up", "down", "flat", "none")


def test_markets_under_blocked_egress_is_all_sample_not_500(client: TestClient) -> None:
    # The blocking resolver makes every fetch fail; the endpoint must
    # still 200 with honest sample data (is_sample True), never a 500,
    # never frozen-fake-live values.
    r = client.get("/api/ticker/markets")
    assert r.status_code == 200
    body = r.json()
    assert all(e["is_sample"] for e in body["entries"]), "blocked egress must be all-sample"
    # Sample is honest, NOT stale (we never had real data).
    assert body["stale"] is False
    assert body["as_of"] is None


def test_sports_envelope(client: TestClient) -> None:
    body = client.get("/api/ticker/sports").json()
    assert body["schema_version"] == 1
    assert body["mode"] == "sports"
    assert isinstance(body["entries"], list)


def test_phantom_markets_all_sample(phantom_client: TestClient) -> None:
    body = phantom_client.get("/api/ticker/markets").json()
    assert body["entries"]
    assert all(e["is_sample"] for e in body["entries"])


def test_phantom_sports_serves_sample_slate(phantom_client: TestClient) -> None:
    body = phantom_client.get("/api/ticker/sports").json()
    assert body["entries"]
    assert all(e["is_sample"] for e in body["entries"]), "phantom sports must be all-sample"
