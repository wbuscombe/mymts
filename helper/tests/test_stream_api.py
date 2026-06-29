"""Endpoint tests for /api/stream — the renderer's HLS serve route.

Exercise: a present playlist/segment serve with the right HLS media types, an
absent playlist is an honest 503 (renderer booting), a traversal/garbage
segment name is 404 (never escapes the stream dir), and the route is absent
when STREAM_DIR is unset (opt-in). Seeded DB + TestClient, same harness as the
other API tests.
"""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from mymts_helper.app import create_app, create_stream_app
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
    return TestClient(create_app(_cfg(tmp_path, stream_dir=stream_dir), resolver=_block_all_resolver))


def test_no_route_when_stream_dir_unset(tmp_path: Path):
    # Opt-in: with STREAM_DIR unset the helper adds no /api/stream surface.
    r = _client(tmp_path).get("/api/stream/playlist.m3u8")
    assert r.status_code == 404


def test_playlist_503_when_renderer_not_ready(tmp_path: Path):
    sd = tmp_path / "stream"
    sd.mkdir()
    r = _client(tmp_path, stream_dir=str(sd)).get("/api/stream/playlist.m3u8")
    assert r.status_code == 503  # honest "not ready", not a 404


def test_serves_playlist_and_segment_with_hls_media_types(tmp_path: Path):
    sd = tmp_path / "stream"
    sd.mkdir()
    (sd / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:4.0,\nseg_00001.ts\n")
    (sd / "seg_00001.ts").write_bytes(b"\x47" + b"\x00" * 187)  # a TS-ish blob
    client = _client(tmp_path, stream_dir=str(sd))

    pl = client.get("/api/stream/playlist.m3u8")
    assert pl.status_code == 200
    assert pl.headers["content-type"].startswith("application/vnd.apple.mpegurl")
    assert pl.headers.get("cache-control") == "no-store"
    assert pl.text.startswith("#EXTM3U")

    seg = client.get("/api/stream/seg_00001.ts")
    assert seg.status_code == 200
    assert seg.headers["content-type"].startswith("video/mp2t")


def test_unknown_segment_is_404(tmp_path: Path):
    sd = tmp_path / "stream"
    sd.mkdir()
    (sd / "playlist.m3u8").write_text("#EXTM3U\n")
    r = _client(tmp_path, stream_dir=str(sd)).get("/api/stream/seg_99999.ts")
    assert r.status_code == 404  # well-formed name but no such file


def test_garbage_segment_name_is_404(tmp_path: Path):
    sd = tmp_path / "stream"
    sd.mkdir()
    (sd / "secret.txt").write_text("nope")
    client = _client(tmp_path, stream_dir=str(sd))
    # a non-seg name (and any traversal-ish single segment) must not match the
    # strict seg_<n>.ts pattern → 404, never serving an arbitrary file.
    for bad in ["secret.txt", "..%2Fsecret.txt", "playlist.m3u8.ts", "seg_.ts", "seg_1.tsx"]:
        assert client.get(f"/api/stream/{bad}").status_code == 404


def test_segment_name_regex_rejects_trailing_newline():
    # The renderer name is matched with fullmatch (not .match, whose `$` would
    # also accept `seg_1.ts\n`) — close the anchor-bypass at the regex level.
    from mymts_helper.stream import api as stream_api
    assert stream_api._SEGMENT_RE.fullmatch("seg_00001.ts")
    assert stream_api._SEGMENT_RE.fullmatch("seg_00001.ts\n") is None
    assert stream_api._SEGMENT_RE.fullmatch("seg_1.ts/../x") is None


def test_segment_symlink_escape_is_refused(tmp_path: Path):
    # The shared volume is renderer-WRITABLE; a compromised renderer could plant
    # a symlink whose NAME passes the filter but whose target resolves, in the
    # HELPER's namespace, to the TLS key / DB. The route must refuse it — the
    # isolation guarantee the feature advertises.
    sd = tmp_path / "stream"
    sd.mkdir()
    (sd / "playlist.m3u8").write_text("#EXTM3U\n")
    secret = tmp_path / "helper.key"          # stands in for /etc/ssl/mymts/helper.key
    secret.write_text("TOP-SECRET-PRIVATE-KEY")
    (sd / "seg_00001.ts").symlink_to(secret)  # name passes seg_<n>.ts
    r = _client(tmp_path, stream_dir=str(sd)).get("/api/stream/seg_00001.ts")
    assert r.status_code == 404
    assert "TOP-SECRET-PRIVATE-KEY" not in r.text   # the target was NEVER served


def test_playlist_symlink_escape_is_refused(tmp_path: Path):
    sd = tmp_path / "stream"
    sd.mkdir()
    secret = tmp_path / "helper.key"
    secret.write_text("TOP-SECRET-PRIVATE-KEY")
    (sd / "playlist.m3u8").symlink_to(secret)
    r = _client(tmp_path, stream_dir=str(sd)).get("/api/stream/playlist.m3u8")
    assert r.status_code == 503                      # treated as not-ready
    assert "TOP-SECRET-PRIVATE-KEY" not in r.text     # never serves the target


def test_head_on_stream_returns_200_not_405(tmp_path: Path):
    # Strict players (tvOS) may HEAD-probe before fetching; GET+HEAD so the probe
    # gets 200 + the right content-type (headers only), never a 405 that stalls it.
    sd = tmp_path / "stream"
    sd.mkdir()
    (sd / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:4.0,\nseg_00001.ts\n")
    (sd / "seg_00001.ts").write_bytes(b"\x47" + b"\x00" * 187)
    client = _client(tmp_path, stream_dir=str(sd))
    h = client.head("/api/stream/playlist.m3u8")
    assert h.status_code == 200
    assert h.headers["content-type"].startswith("application/vnd.apple.mpegurl")
    assert h.content == b""   # HEAD → headers only, no body
    hs = client.head("/api/stream/seg_00001.ts")
    assert hs.status_code == 200
    assert hs.headers["content-type"].startswith("video/mp2t")


# ---- the stream-only HTTP app (for the plain-HTTP LAN listener) ----

def _stream_client(tmp_path: Path) -> tuple[TestClient, Path]:
    sd = tmp_path / "stream"
    sd.mkdir()
    return TestClient(create_stream_app(str(sd))), sd


def test_stream_app_serves_only_the_stream(tmp_path: Path):
    # The plain-HTTP listener's app must expose ONLY /api/stream (+ /health) —
    # NOT the API / channels / control / app (those stay HTTPS-only).
    client, sd = _stream_client(tmp_path)
    (sd / "playlist.m3u8").write_text("#EXTM3U\n")
    assert client.get("/api/stream/playlist.m3u8").status_code == 200
    assert client.get("/health").json()["stream"] is True
    # the rest of the surface is absent on this minimal app
    assert client.get("/api/channels").status_code == 404
    assert client.get("/api/wall").status_code == 404
    assert client.get("/api/feed").status_code == 404


def test_stream_app_reuses_the_symlink_guard(tmp_path: Path):
    # The SAME hardened serving — a planted symlink is refused on the HTTP app too,
    # so the isolation guarantee survives the new (plain-HTTP) serving path.
    client, sd = _stream_client(tmp_path)
    (sd / "playlist.m3u8").write_text("#EXTM3U\n")
    secret = tmp_path / "helper.key"
    secret.write_text("TOP-SECRET-PRIVATE-KEY")
    (sd / "seg_00001.ts").symlink_to(secret)
    r = client.get("/api/stream/seg_00001.ts")
    assert r.status_code == 404
    assert "TOP-SECRET-PRIVATE-KEY" not in r.text


def test_stream_app_head_and_media_types(tmp_path: Path):
    client, sd = _stream_client(tmp_path)
    (sd / "playlist.m3u8").write_text("#EXTM3U\n")
    (sd / "seg_00001.ts").write_bytes(b"\x47" + b"\x00" * 187)
    assert client.head("/api/stream/playlist.m3u8").status_code == 200
    assert client.get("/api/stream/seg_00001.ts").headers["content-type"].startswith("video/mp2t")


def test_config_reads_stream_http_port(monkeypatch):
    from mymts_helper.config import Config
    monkeypatch.setenv("STREAM_HTTP_PORT", "8082")
    assert Config.from_env().stream_http_port == 8082
    monkeypatch.delenv("STREAM_HTTP_PORT", raising=False)
    assert Config.from_env().stream_http_port is None


def test_stream_serves_cors_header_for_browser_consumers(tmp_path: Path):
    # The HLS is public video; a browser HLS consumer (the Mercury publisher's
    # livekit-client, served from a different local origin) fetches it cross-origin,
    # which CORS otherwise blocks. The playlist + segments carry Access-Control-Allow-Origin: *.
    stream = tmp_path / "stream"
    stream.mkdir()
    (stream / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:4.0,\nseg_00001.ts\n")
    (stream / "seg_00001.ts").write_bytes(b"\x47" + b"\x00" * 187)
    c = _client(tmp_path, stream_dir=str(stream))
    assert c.get("/api/stream/playlist.m3u8").headers.get("access-control-allow-origin") == "*"
    assert c.get("/api/stream/seg_00001.ts").headers.get("access-control-allow-origin") == "*"
