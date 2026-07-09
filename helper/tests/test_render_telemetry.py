"""Endpoint tests for /api/render/telemetry — the opt-in render instrumentation sink.

Exercise: an empty sink is honest (latest: null), a POST is stored + handed back on
GET with an age, an oversized body is refused, a non-object is 422, the stored tile
list is bounded — and, critically, the route is on the full LAN app but NEVER on the
Discord public app (the tunnel origin can't reach it).
"""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from mymts_helper.app import create_app, create_public_app
from mymts_helper.config import Config
from mymts_helper.render_telemetry import MAX_TILES, TelemetryStore, get_router, sanitize


async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _router_client() -> TestClient:
    app = FastAPI()
    app.include_router(get_router())
    return TestClient(app)


def test_empty_sink_is_honest() -> None:
    r = _router_client().get("/api/render/telemetry")
    assert r.status_code == 200
    assert r.json() == {"latest": None, "age_s": None}


def test_post_is_stored_and_returned_with_age() -> None:
    c = _router_client()
    snap = {"elapsedS": 12.0, "tiles": [{"index": 0, "presentedFps": 29.5}], "summary": {"minPresentedFps": 29.5}}
    assert c.post("/api/render/telemetry", json=snap).json() == {"ok": True}
    body = c.get("/api/render/telemetry").json()
    assert body["latest"] == snap
    assert isinstance(body["age_s"], (int, float)) and body["age_s"] >= 0


def test_latest_wins() -> None:
    c = _router_client()
    c.post("/api/render/telemetry", json={"elapsedS": 1})
    c.post("/api/render/telemetry", json={"elapsedS": 2})
    assert c.get("/api/render/telemetry").json()["latest"] == {"elapsedS": 2}


def test_non_object_body_is_422() -> None:
    r = _router_client().post("/api/render/telemetry", json=[1, 2, 3])
    assert r.status_code == 422


def test_oversized_body_is_refused() -> None:
    # a body past the cap is rejected (413) before it can balloon memory.
    big = {"blob": "x" * (600 * 1024)}
    r = _router_client().post("/api/render/telemetry", json=big)
    assert r.status_code == 413


def test_sanitize_bounds_the_tile_list() -> None:
    payload = {"tiles": [{"index": i} for i in range(MAX_TILES + 10)]}
    out = sanitize(payload)
    assert len(out["tiles"]) == MAX_TILES
    # a within-bounds payload is returned untouched (same object identity ok)
    small = {"tiles": [{"index": 0}]}
    assert sanitize(small) is small


def test_store_is_per_instance() -> None:
    a, b = TelemetryStore(), TelemetryStore()
    a.put({"x": 1})
    assert a.get()["latest"] == {"x": 1}
    assert b.get()["latest"] is None


# --- posture: on the LAN app, NOT on the Discord public app ---

def _cfg(tmp_path: Path) -> Config:
    return Config(
        phantom_mode=False, port=8091, log_level="warning",
        build_sha="dev", build_version="0.0.0-dev",
        data_dir=str(tmp_path / "data"),
        feed_poll_interval_seconds=3600, channel_probe_interval_seconds=3600,
    )


def test_telemetry_is_on_the_lan_app() -> None:
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        c = TestClient(create_app(_cfg(Path(d)), resolver=_block_all_resolver))
        assert c.get("/api/render/telemetry").status_code == 200


def test_telemetry_is_absent_from_the_discord_public_app(tmp_path: Path) -> None:
    stream = tmp_path / "stream"; stream.mkdir()
    act = tmp_path / "activity"; act.mkdir()
    (act / "index.html").write_text("<!doctype html><title>x</title>")
    app = create_public_app(
        stream_dir=str(stream), activity_dir=str(act),
        discord_client_id="CID", discord_client_secret="SEC", build_sha="dev",
    )
    c = TestClient(app)
    # the public tunnel origin must not see the LAN debug surface: the telemetry
    # handler is simply not registered, so neither verb is accepted (GET falls
    # through to the activity 404; POST has no handler → 405). Either way, never 2xx.
    assert c.get("/api/render/telemetry").status_code == 404
    assert c.post("/api/render/telemetry", json={"x": 1}).status_code in (404, 405)
