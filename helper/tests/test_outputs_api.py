"""Tests for /api/outputs — the unified Outputs panel's status + controls.

status merges the wall config's CONFIGURED outputs with the renderer's live status
file (path-safe read); controls edit the config through the validated merge path.
"""

from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, stream_dir: str | None = None) -> Config:
    return Config(
        phantom_mode=False, port=8091, log_level="warning",
        build_sha="dev", build_version="0.0.0-dev",
        data_dir=str(tmp_path / "data"),
        feed_poll_interval_seconds=3600, channel_probe_interval_seconds=3600,
        stream_dir=stream_dir,
    )


def _client(tmp_path: Path, *, stream_dir: str | None = None) -> TestClient:
    app = create_app(_cfg(tmp_path, stream_dir=stream_dir), resolver=_block_all_resolver)
    return TestClient(app)


# ----- status (no renderer report → config-derived) -----

def test_status_without_a_report_is_config_derived(tmp_path: Path):
    body = _client(tmp_path).get("/api/outputs/status").json()
    assert body["reporting"] is False
    assert body["render"]["resolution"] == "1080p"          # derived from default hls
    hls = body["outputs"]["hls"]
    assert hls["enabled"] is True and hls["state"] == "stopped"   # configured-on but no live report
    assert hls["playlist_path"] == "/api/stream/playlist.m3u8"
    mercury = body["outputs"]["mercury"]
    assert mercury["state"] == "disabled"                   # default disabled
    assert mercury["channel_guid"] == "" and "checklist" in mercury


def test_status_derives_render_from_max_enabled(tmp_path: Path):
    client = _client(tmp_path)
    # enable mercury at 720p, set hls to 1440p → render derives to 1440p
    client.put("/api/wall", json={"outputs": {
        "hls": {"resolution": "1440p"}, "mercury": {"enabled": True, "resolution": "720p"},
    }})
    assert client.get("/api/outputs/status").json()["render"]["resolution"] == "1440p"


# ----- status (renderer report present → runtime overlaid) -----

def test_status_overlays_the_renderer_report(tmp_path: Path):
    stream = tmp_path / "stream"
    stream.mkdir()
    (stream / "outputs-status.json").write_text(json.dumps({
        "render": {"resolution": "1080p", "width": 1920, "height": 1080, "fps": 30},
        "outputs": {
            "hls": {"state": "running", "playlist_path": "/api/stream/playlist.m3u8"},
            "mercury": {
                "state": "needs_setup",
                "checklist": {"key_present": False, "channel_set": True, "tailnet_reachable": None},
                "detail": "Waiting on setup: missing LiveKit key",
            },
        },
    }))
    body = _client(tmp_path, stream_dir=str(stream)).get("/api/outputs/status").json()
    assert body["reporting"] is True
    assert body["outputs"]["hls"]["state"] == "running"     # from the live report
    m = body["outputs"]["mercury"]
    assert m["state"] == "needs_setup"
    assert m["checklist"]["key_present"] is False
    assert "LiveKit key" in m["detail"]


def test_status_path_safety_rejects_symlink(tmp_path: Path):
    stream = tmp_path / "stream"
    stream.mkdir()
    secret = tmp_path / "secret.json"
    secret.write_text(json.dumps({"outputs": {"hls": {"state": "running"}}}))
    (stream / "outputs-status.json").symlink_to(secret)
    # a symlinked status file is refused → falls back to "not reporting"
    body = _client(tmp_path, stream_dir=str(stream)).get("/api/outputs/status").json()
    assert body["reporting"] is False


def test_status_tolerates_corrupt_report(tmp_path: Path):
    stream = tmp_path / "stream"
    stream.mkdir()
    (stream / "outputs-status.json").write_text("{ not json ]")
    body = _client(tmp_path, stream_dir=str(stream)).get("/api/outputs/status").json()
    assert body["reporting"] is False
    assert body["outputs"]["hls"]["state"] == "stopped"     # degrades, never crashes


# ----- controls (edit config through the validated merge path) -----

def test_control_start_stop_flip_enabled(tmp_path: Path):
    client = _client(tmp_path)
    assert client.post("/api/outputs/hls/stop").json()["outputs"]["hls"]["enabled"] is False
    assert client.get("/api/outputs/status").json()["outputs"]["hls"]["enabled"] is False
    assert client.post("/api/outputs/hls/start").json()["outputs"]["hls"]["enabled"] is True


def test_control_restart_bumps_monotonic_epoch(tmp_path: Path):
    client = _client(tmp_path)
    assert client.post("/api/outputs/hls/restart").json()["outputs"]["hls"]["restart_epoch"] == 1
    assert client.post("/api/outputs/hls/restart").json()["outputs"]["hls"]["restart_epoch"] == 2


def test_control_only_touches_the_named_output(tmp_path: Path):
    client = _client(tmp_path)
    client.put("/api/wall", json={"outputs": {"mercury": {"channel_guid": "g-1"}}})
    client.post("/api/outputs/hls/restart")
    # the mercury non-secret field survives an hls control (no clobber)
    assert client.get("/api/outputs/status").json()["outputs"]["mercury"]["channel_guid"] == "g-1"


def test_control_rejects_unknown_output_and_action(tmp_path: Path):
    client = _client(tmp_path)
    assert client.post("/api/outputs/bogus/start").status_code == 404
    assert client.post("/api/outputs/hls/bogus").status_code == 404


def test_mercury_prefill_persists_and_stays_disabled(tmp_path: Path):
    # Will can pre-fill the Mercury non-secret fields; it stays disabled (no connect).
    client = _client(tmp_path)
    client.put("/api/wall", json={"outputs": {"mercury": {
        "channel_guid": "abc-123", "display_name": "MyMTS News Wall", "resolution": "720p",
    }}})
    m = client.get("/api/outputs/status").json()["outputs"]["mercury"]
    assert m["channel_guid"] == "abc-123" and m["state"] == "disabled"
