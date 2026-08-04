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


# --- cache headers on the web surfaces (PR-026) ---
# The mounts previously sent NO Cache-Control at all — only ETag/Last-Modified. With
# no explicit freshness a browser falls back to HEURISTIC caching and, for ES modules
# and stylesheets, reuses the stored copy WITHOUT revalidating: the ETag is never
# consulted and a stale panel persists indefinitely. That is the PR-024 symptom (the
# server demonstrably served four sliders; the operator's browser rendered three).


def _served_client(tmp_path: Path) -> TestClient:
    """A helper with a realistic web tree mounted: /app + /control, HTML, CSS and an
    ES module — the exact asset classes the staleness bug rode in on."""
    web = tmp_path / "web"
    (web / "js").mkdir(parents=True)
    (web / "control").mkdir()
    (web / "index.html").write_text("<!doctype html><title>app</title>", encoding="utf-8")
    (web / "styles.css").write_text(":root{--u:1px}", encoding="utf-8")
    (web / "js" / "wallConfig.mjs").write_text("export const X=1;", encoding="utf-8")
    (web / "control" / "index.html").write_text("<!doctype html><title>control</title>", encoding="utf-8")
    (web / "control" / "control.css").write_text(".slab{}", encoding="utf-8")
    (web / "control" / "control.mjs").write_text("export const Y=1;", encoding="utf-8")
    cfg = _cfg(tmp_path, web_client_dir=str(web))
    return TestClient(create_app(cfg, resolver=_block_all_resolver))


@pytest.mark.parametrize(
    "path",
    [
        "/control/", "/control/control.css", "/control/control.mjs",
        "/app/", "/app/styles.css", "/app/js/wallConfig.mjs",
    ],
)
def test_web_assets_are_never_stored_by_a_browser(tmp_path: Path, path: str) -> None:
    """THE class-killer: every HTML/CSS/JS asset on BOTH surfaces carries no-store, so
    a browser can never hold a panel older than the deployed code."""
    with _served_client(tmp_path) as c:
        r = c.get(path)
        assert r.status_code == 200, path
        assert r.headers.get("cache-control") == "no-store", (
            f"{path} served without no-store — a browser may cache it heuristically"
        )


@pytest.mark.parametrize("path", ["/control/", "/app/js/wallConfig.mjs"])
def test_web_assets_carry_the_build_sha(tmp_path: Path, path: str) -> None:
    """`curl -I` on any asset answers "which build is this?" without view-source."""
    from mymts_helper.app import BUILD_SHA_HEADER

    with _served_client(tmp_path) as c:
        assert c.get(path).headers.get(BUILD_SHA_HEADER) == "dev", path


def test_no_store_is_keyed_on_content_type_not_path(tmp_path: Path) -> None:
    """The stamp keys on content-type, so a NON-asset response served from the same
    mount is untouched — the guard must not leak onto media (the HLS passthrough keeps
    the stream router's own headers) or anything else."""
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<!doctype html>", encoding="utf-8")
    (web / "logo.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 32)
    cfg = _cfg(tmp_path, web_client_dir=str(web))
    with TestClient(create_app(cfg, resolver=_block_all_resolver)) as c:
        assert c.get("/app/").headers.get("cache-control") == "no-store"
        png = c.get("/app/logo.png")
        assert png.status_code == 200
        assert png.headers.get("content-type", "").startswith("image/")
        assert png.headers.get("cache-control") is None, "non-asset content-type stamped"


def test_api_and_health_keep_their_own_cache_semantics(tmp_path: Path) -> None:
    """The wrapper is mounted on the static surfaces only — the JSON API is untouched."""
    with _served_client(tmp_path) as c:
        assert c.get("/health").headers.get("cache-control") is None
        assert c.get("/api/channels").headers.get("cache-control") is None
