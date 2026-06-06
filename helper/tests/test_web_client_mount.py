"""Tests for the optional LAN web-client static mount (config-gated).

The mount is OFF by default (no WEB_CLIENT_DIR) so the running helper is
unchanged; when configured to an existing directory it serves the static
client at /app, same-origin as the API (no CORS). These tests pin both
states + that the API/health routes are unaffected.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, web_client_dir: str | None = None) -> Config:
    return Config(
        phantom_mode=False,
        port=8091,
        log_level="warning",
        build_sha="dev",
        build_version="0.0.0-dev",
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        channel_probe_interval_seconds=3600,
        markets_poll_interval_seconds=3600,
        sports_poll_interval_seconds=3600,
        web_client_dir=web_client_dir,
    )


def test_web_client_not_mounted_by_default(tmp_path: Path) -> None:
    with TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver)) as c:
        # Off by default: /app is not mounted → 404. Helper is unchanged.
        assert c.get("/app/").status_code == 404
        # API + health still work.
        assert c.get("/health").status_code == 200
        assert c.get("/api/feed").status_code == 200


def test_web_client_served_when_configured(tmp_path: Path) -> None:
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<!doctype html><title>MyMTS LAN</title>", encoding="utf-8")
    (web / "styles.css").write_text("body{background:#000}", encoding="utf-8")

    with TestClient(create_app(_cfg(tmp_path, web_client_dir=str(web)), resolver=_block_all_resolver)) as c:
        # index served at /app/ (html=True).
        r = c.get("/app/")
        assert r.status_code == 200
        assert "MyMTS LAN" in r.text
        # a static asset is served too.
        assert c.get("/app/styles.css").status_code == 200
        # the mount does NOT shadow the API or health.
        assert c.get("/health").status_code == 200
        assert c.get("/api/ticker/markets").json()["mode"] == "markets"


def test_missing_web_client_dir_is_soft_noop(tmp_path: Path) -> None:
    # Configured to a non-existent dir → no mount, no crash (logged warning).
    missing = str(tmp_path / "does-not-exist")
    with TestClient(create_app(_cfg(tmp_path, web_client_dir=missing), resolver=_block_all_resolver)) as c:
        assert c.get("/app/").status_code == 404
        assert c.get("/health").status_code == 200
