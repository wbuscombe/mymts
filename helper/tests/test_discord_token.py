"""Tests for the Discord Activity's server-side surface:
  - the token-exchange router (`/api/discord/token` + `/api/discord/config`), and
  - the dedicated minimal PUBLIC app (`create_public_app`) it is served on.

Hard rules under test: the client SECRET never appears in a response; only the
``access_token`` is returned; bad/missing inputs and unconfigured creds FAIL CLOSED;
the public surface exposes ONLY the Activity + /api/discord/* + the /api/stream
passthrough (never the LAN API / /control/); the `/.proxy/` prefix is tolerated.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from mymts_helper.app import create_public_app
from mymts_helper.discord import token_api

# ----- the pure form builder (no network, no real secret) -----

def test_build_token_form_is_the_exact_field_set():
    form = token_api.build_token_form("CID", "SECRET", "thecode")
    assert form == {
        "client_id": "CID",
        "client_secret": "SECRET",
        "grant_type": "authorization_code",
        "code": "thecode",
    }
    # Embedded-App flow: NO redirect_uri.
    assert "redirect_uri" not in form


# ----- the router (injected exchanger → no network) -----

def _router_client(*, client_id, client_secret, exchanger=None) -> TestClient:
    app = FastAPI()
    app.include_router(
        token_api.get_router(
            client_id=client_id, client_secret=client_secret, token_exchanger=exchanger
        )
    )
    return TestClient(app)


def test_config_returns_public_client_id():
    c = _router_client(client_id="PUBLIC_ID", client_secret="s")
    assert c.get("/api/discord/config").json() == {"client_id": "PUBLIC_ID"}


def test_config_is_empty_string_when_unset():
    c = _router_client(client_id=None, client_secret=None)
    assert c.get("/api/discord/config").json() == {"client_id": ""}


def test_token_returns_only_access_token_never_the_secret():
    seen: dict = {}

    async def fake_exchange(code: str) -> str:
        seen["code"] = code
        return "ACCESS_TOKEN_123"

    c = _router_client(client_id="cid", client_secret="SHHH", exchanger=fake_exchange)
    r = c.post("/api/discord/token", json={"code": "abc"})
    assert r.status_code == 200
    assert r.json() == {"access_token": "ACCESS_TOKEN_123"}     # ONLY the access token
    body_text = r.text
    assert "SHHH" not in body_text and "client_secret" not in body_text
    assert "refresh_token" not in body_text
    assert seen["code"] == "abc"                               # the code reached the exchanger


def test_token_fails_closed_when_unconfigured():
    # No exchanger and no creds → the surface is up but exchange is "not configured".
    c = _router_client(client_id=None, client_secret=None)
    assert c.post("/api/discord/token", json={"code": "abc"}).status_code == 503


def test_token_rejects_blank_and_missing_code():
    async def fake_exchange(code: str) -> str:
        return "tok"

    c = _router_client(client_id="cid", client_secret="s", exchanger=fake_exchange)
    assert c.post("/api/discord/token", json={"code": "   "}).status_code == 400
    assert c.post("/api/discord/token", json={}).status_code == 422       # missing field
    assert c.post("/api/discord/token", json={"code": 123}).status_code == 422  # wrong type


def test_token_upstream_failure_is_generic_502_no_leak():
    async def boom(code: str) -> str:
        raise RuntimeError("discord said: client_secret SHHH is wrong")

    c = _router_client(client_id="cid", client_secret="SHHH", exchanger=boom)
    r = c.post("/api/discord/token", json={"code": "abc"})
    assert r.status_code == 502
    assert "SHHH" not in r.text and "discord said" not in r.text          # no upstream leak


def test_token_empty_access_token_is_502():
    async def empty(code: str):
        return None

    c = _router_client(client_id="cid", client_secret="s", exchanger=empty)
    assert c.post("/api/discord/token", json={"code": "abc"}).status_code == 502


def test_token_oversized_code_rejected():
    async def fake_exchange(code: str) -> str:
        return "tok"

    c = _router_client(client_id="cid", client_secret="s", exchanger=fake_exchange)
    big = "x" * (token_api.MAX_CODE_LEN + 1)
    assert c.post("/api/discord/token", json={"code": big}).status_code == 422


# ----- the public app (the only public surface; minimal by construction) -----

def _public_client(tmp_path, *, client_id=None, client_secret=None):
    stream = tmp_path / "stream"
    stream.mkdir()
    (stream / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:2.0,\nseg_0.ts\n")
    (stream / "seg_0.ts").write_bytes(b"\x47" + b"\x00" * 187)
    act = tmp_path / "activity"
    act.mkdir()
    (act / "index.html").write_text("<!doctype html><title>MyMTS</title><video id=wall></video>")
    (act / "activity.mjs").write_text("// entry")
    app = create_public_app(
        stream_dir=str(stream), activity_dir=str(act),
        discord_client_id=client_id, discord_client_secret=client_secret,
    )
    return TestClient(app), stream


def test_public_app_serves_the_activity_and_config(tmp_path):
    c, _ = _public_client(tmp_path, client_id="CID")
    r = c.get("/")
    assert r.status_code == 200 and "<video" in r.text
    assert c.get("/api/discord/config").json() == {"client_id": "CID"}


def test_public_app_relays_hls_with_content_types(tmp_path):
    c, _ = _public_client(tmp_path)
    pl = c.get("/api/stream/playlist.m3u8")
    assert pl.status_code == 200 and "mpegurl" in pl.headers["content-type"]
    seg = c.get("/api/stream/seg_0.ts")
    assert seg.status_code == 200 and seg.headers["content-type"] == "video/mp2t"


def test_public_app_tolerates_proxy_prefix(tmp_path):
    c, _ = _public_client(tmp_path, client_id="CID")
    # Discord normally strips /.proxy/, but we tolerate it being forwarded.
    assert c.get("/.proxy/api/stream/playlist.m3u8").status_code == 200
    assert c.get("/.proxy/api/discord/config").json() == {"client_id": "CID"}


def test_public_app_does_not_expose_the_lan_api(tmp_path):
    c, _ = _public_client(tmp_path)
    # The full API + /control/ + /app/ are NOT on the public surface.
    assert c.get("/api/wall").status_code == 404
    assert c.get("/api/feed").status_code == 404
    assert c.get("/api/channels").status_code == 404
    assert c.get("/control/").status_code == 404


def test_public_app_stream_passthrough_is_path_safe(tmp_path):
    c, stream = _public_client(tmp_path)
    # A traversal-style segment name is rejected by the hardened stream router regex.
    assert c.get("/api/stream/..%2f..%2fsecret.ts").status_code in (400, 404)
    assert c.get("/api/stream/not_a_segment.txt").status_code in (400, 404)
