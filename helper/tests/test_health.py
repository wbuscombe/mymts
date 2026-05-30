"""Tests for /health.

Stage 1 contract:
  - schema_version is 1 (any change requires a major bump + consumer update)
  - ok and ready are true once the app starts
  - phantom reflects PHANTOM_MODE env at app-creation time
  - build_sha and version are surfaced (default to legible dev markers)
  - uptime_seconds is a non-negative float that grows monotonically
"""

from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


@pytest.fixture
def client_normal() -> TestClient:
    cfg = Config(
        phantom_mode=False,
        port=8091,
        log_level="info",
        build_sha="abc1234",
        build_version="1.2.3",
    )
    return TestClient(create_app(cfg))


@pytest.fixture
def client_phantom() -> TestClient:
    cfg = Config(
        phantom_mode=True,
        port=8091,
        log_level="info",
        build_sha="dev",
        build_version="0.0.0-dev",
    )
    return TestClient(create_app(cfg))


def test_health_returns_pinned_schema_version(client_normal: TestClient) -> None:
    r = client_normal.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["schema_version"] == 1, (
        "Bumping schema_version requires a coordinated update in claude-status-bot. "
        "If you intended to change the schema, do it in a dedicated commit and "
        "update the consumer contract test in the same PR."
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


def test_docs_are_disabled() -> None:
    cfg = Config(
        phantom_mode=False, port=8091, log_level="info",
        build_sha="dev", build_version="0.0.0-dev",
    )
    client = TestClient(create_app(cfg))
    # No public HTTP surface for browsing — the contract lives in tests.
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404
