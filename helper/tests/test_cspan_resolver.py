"""Tests for the free C-SPAN/.gov Senate-floor resolver — pure logic + the
filename/session resolve path. No live network: the schedule fetch is injected.
"""

from __future__ import annotations

import pytest

from mymts_helper.channels import cspan_resolver as C

# ── pure logic ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "url,ok",
    [
        ("https://www.senate.gov/isvp/?type=live&comm=stv", True),
        ("https://senate.gov/legislative/schedule/floor_schedule.json", True),
        ("http://www.senate.gov/isvp/?comm=stv", False),       # not https
        ("https://www.c-span.org/networks/?channel=c-span-2", False),  # gated network host
        ("https://evil.example/isvp", False),
        ("https://www.senate.gov.evil.example/isvp", False),   # lookalike host
        ("not a url", False),
    ],
)
def test_is_official_url(url, ok):
    assert C.is_official_url(url) is ok


@pytest.mark.parametrize(
    "fn,ok",
    [
        ("stv061726", True), ("srs012025", True), ("stv12345678", True),
        ("stv", False),            # no digits
        ("../evil", False), ("stv/evil", False), ("stv06172x", False),
        ("", False),
    ],
)
def test_is_safe_filename(fn, ok):
    assert C.is_safe_filename(fn) is ok


def test_extract_comm_filename():
    u = "https://www.senate.gov/isvp/stv.html?type=live&comm=stv&filename=stv061726"
    assert C.extract_comm_filename(u) == ("stv", "stv061726")
    # unknown comm -> None
    assert C.extract_comm_filename("https://x/?comm=zzz&filename=stv061726") is None
    # unsafe filename -> None
    assert C.extract_comm_filename("https://x/?comm=stv&filename=../evil") is None
    # missing -> None
    assert C.extract_comm_filename("https://x/?type=live") is None


def test_build_master_url():
    got = C.build_master_url("stv", "stv061726")
    assert got == ("https://www-senate-gov-media-srs.akamaized.net/hls/live/"
                   "2096634/stv/stv061726/master.m3u8")
    assert C.build_master_url("zzz", "stv061726") is None      # unknown comm
    assert C.build_master_url("stv", "../evil") is None         # unsafe filename


def _schedule(filename="stv061726", comm="stv", *, convened=True):
    if not convened:
        return {"floorProceedings": [{"convenedSessionStream": None}]}
    return {"floorProceedings": [{
        "convenedSessionStream":
            f"https://www.senate.gov/isvp/stv.html?type=live&comm={comm}&filename={filename}",
    }]}


def test_parse_schedule():
    assert C.parse_schedule(_schedule()) == ("stv", "stv061726")
    assert C.parse_schedule(_schedule(convened=False)) is None   # not in session
    assert C.parse_schedule({}) is None
    assert C.parse_schedule({"floorProceedings": []}) is None


# ── resolve() (injected schedule fetch) ─────────────────────────────────────

SRC = "https://www.senate.gov/isvp/?type=live&comm=stv"


def test_resolve_in_session_builds_master():
    r = C.CSpanResolver(http_get=lambda url: _schedule("stv061726"))
    out = r.resolve(SRC)
    assert out.ok is True and out.is_live is True
    assert out.hls_url.endswith("/2096634/stv/stv061726/master.m3u8")


def test_resolve_not_in_session_is_honest_offline():
    r = C.CSpanResolver(http_get=lambda url: _schedule(convened=False))
    out = r.resolve(SRC)
    assert out.ok is False and out.is_live is False
    assert out.error == "not_in_session"


def test_resolve_is_offline_safe_on_fetch_error():
    def boom(url):
        raise OSError("senate.gov unreachable")
    out = C.CSpanResolver(http_get=boom).resolve(SRC)
    assert out.ok is False
    assert out.error.startswith("resolve_error:")


def test_resolve_rejects_non_official_source():
    r = C.CSpanResolver(http_get=lambda url: _schedule())
    out = r.resolve("https://evil.example/isvp")
    assert out.ok is False and out.error == "source_not_official"


def test_resolve_caches_and_force_refresh():
    calls = {"n": 0}

    def counting(url):
        calls["n"] += 1
        return _schedule("stv061726")

    r = C.CSpanResolver(http_get=counting)
    a = r.resolve(SRC)
    b = r.resolve(SRC)
    assert a is b and calls["n"] == 1           # second served from cache
    r.resolve(SRC, force_refresh=True)
    assert calls["n"] == 2                       # force_refresh bypasses cache
