"""Tests for the helper-computed Discord status state machine.

UNLIKE Mercury (renderer-computed), the Discord checklist is computed by the helper
(it holds DISCORD_* + owns the public origin). The state machine must be honest +
zero-egress-until-configured, and must NEVER claim a live session.
"""

from __future__ import annotations

from mymts_helper.discord import status as ds

# ----- state machine -----

def test_disabled_state_does_no_probe():
    calls: list[str] = []
    out = ds.discord_status(
        output={"enabled": False},
        client_id="cid", client_secret="sec", public_origin="https://w",
        hls_enabled=True, probe=lambda o: calls.append(o) or True,
    )
    assert out["state"] == ds.STATE_DISABLED
    assert calls == []                                  # disabled → zero egress
    assert out["checklist"]["public_origin_reachable"] is None


def test_needs_setup_when_creds_missing_no_probe():
    calls: list[str] = []
    out = ds.discord_status(
        output={"enabled": True},
        client_id=None, client_secret=None, public_origin=None,
        hls_enabled=True, probe=lambda o: calls.append(o) or True,
    )
    assert out["state"] == ds.STATE_NEEDS_SETUP
    assert calls == []                                  # cheap checks fail first → no probe
    assert out["checklist"] == {
        "client_id_present": False, "client_secret_present": False,
        "public_origin_reachable": None, "hls_enabled": True,
    }
    assert "client id" in out["detail"] and "client secret" in out["detail"]


def test_ready_requires_creds_and_reachable_origin():
    out = ds.discord_status(
        output={"enabled": True},
        client_id="cid", client_secret="sec", public_origin="https://wall.example",
        hls_enabled=True, probe=lambda o: True,
    )
    assert out["state"] == ds.STATE_READY
    assert out["checklist"]["public_origin_reachable"] is True
    assert "launch the Activity" in out["detail"]       # honest: ready ≠ live session


def test_ready_but_hls_off_nudges_in_detail():
    out = ds.discord_status(
        output={"enabled": True},
        client_id="cid", client_secret="sec", public_origin="https://wall.example",
        hls_enabled=False, probe=lambda o: True,
    )
    # Per spec, ready = creds + origin reachable (HLS-off doesn't block readiness)…
    assert out["state"] == ds.STATE_READY
    assert out["checklist"]["hls_enabled"] is False
    # …but the detail honestly nudges to enable HLS (the Activity plays that render).
    assert "HLS" in out["detail"]


def test_unreachable_origin_stays_needs_setup():
    out = ds.discord_status(
        output={"enabled": True},
        client_id="cid", client_secret="sec", public_origin="https://wall.example",
        hls_enabled=True, probe=lambda o: False,
    )
    assert out["state"] == ds.STATE_NEEDS_SETUP
    assert out["checklist"]["public_origin_reachable"] is False
    assert "unreachable" in out["detail"]


def test_phantom_skips_probe_and_reports_unknown():
    calls: list[str] = []
    out = ds.discord_status(
        output={"enabled": True},
        client_id="cid", client_secret="sec", public_origin="https://w",
        hls_enabled=True, phantom=True, probe=lambda o: calls.append(o) or True,
    )
    assert calls == []                                  # phantom = strict zero outbound
    assert out["checklist"]["public_origin_reachable"] is None
    assert out["state"] == ds.STATE_NEEDS_SETUP         # never "ready" without a real check


def test_transport_and_guild_pass_through():
    out = ds.discord_status(
        output={"enabled": True, "transport": "activity", "guild_id": "42"},
        client_id="cid", client_secret="sec", public_origin="https://w",
        hls_enabled=True, probe=lambda o: True,
    )
    assert out["transport"] == "activity" and out["guild_id"] == "42"
    # never an always-on "publishing"/"live" state
    assert out["state"] in (ds.STATE_DISABLED, ds.STATE_NEEDS_SETUP, ds.STATE_READY)


# ----- the cached origin probe (cheap, doesn't hammer the tunnel) -----

def test_cached_origin_probe_calls_underlying_once_within_ttl():
    calls: list[str] = []

    def underlying(origin: str) -> bool:
        calls.append(origin)
        return True

    cached = ds.make_cached_origin_probe(underlying, ttl_seconds=999)
    assert cached("https://w") is True
    assert cached("https://w") is True
    assert calls == ["https://w"]                       # second hit served from cache


def test_origin_probe_unparseable_origin_is_unknown():
    # No network: an origin with no scheme/host can't be checked → None (unknown).
    assert ds.httpx_origin_probe("") is None
    assert ds.httpx_origin_probe("not-a-url") is None
