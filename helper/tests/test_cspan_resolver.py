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


# ── committee-hearings mode (XML schedule + master/variant liveness) ─────────

import datetime  # noqa: E402

COMMITTEE_SRC = "https://www.senate.gov/isvp/?type=live&schedule=committees"
_MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=712800\nmaster/index_1.m3u8\n"
_LIVE_VARIANT = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg0.ts\n"        # no ENDLIST → live
_ENDED_VARIANT = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n"  # ENDLIST → frozen VOD


def test_is_committee_source():
    assert C.is_committee_source(COMMITTEE_SRC) is True
    assert C.is_committee_source(SRC) is False                              # the floor source
    assert C.is_committee_source("https://www.senate.gov/isvp/?comm=stv") is False


def test_is_safe_filename_allows_committee_letter_suffix():
    assert C.is_safe_filename("armedA062316") is True   # mixed-case committee code
    assert C.is_safe_filename("energy061726") is True
    assert C.is_safe_filename("../evil") is False


def test_committee_comm_resolves():
    u = "https://www.senate.gov/isvp/?comm=energy&filename=energy061726"
    assert C.extract_comm_filename(u) == ("energy", "energy061726")
    assert C.build_master_url("energy", "energy061726") == (
        "https://www-senate-gov-media-srs.akamaized.net/hls/live/"
        "2036797/energy/energy061726/master.m3u8"
    )


def test_is_live_media_rejects_endlist():
    assert C.is_live_media(_LIVE_VARIANT) is True
    assert C.is_live_media(_ENDED_VARIANT) is False                        # frozen VOD
    assert C.is_live_media("#EXTM3U\n") is False                           # no segments
    assert C.is_master_manifest(_MASTER) is True
    assert C.pick_first_variant(
        _MASTER, "https://h/a/master.m3u8"
    ) == "https://h/a/master/index_1.m3u8"


def _hearings_xml(meetings):
    """meetings: list of (comm, filename, date_iso, time_iso, committee_name)."""
    rows = "".join(
        f"<meeting><date_iso_8601>{d}</date_iso_8601>"
        f"<time_iso_8601>{t}</time_iso_8601><committee>{name}</committee>"
        f"<video_url>https://www.senate.gov/isvp/?comm={comm}&amp;filename={fn}</video_url>"
        f"</meeting>"
        for comm, fn, d, t, name in meetings
    )
    return f"<css_meetings_scheduled>{rows}</css_meetings_scheduled>"


def test_parse_hearings_xml_keeps_known_drops_unknown():
    xml = _hearings_xml([
        ("energy", "energy061726", "2026-06-17", "09:30:00", "Energy and Natural Resources"),
        ("zzz", "zzz061726", "2026-06-17", "10:00:00", "Bogus"),          # unknown comm → dropped
        ("commerce", "commerce061726", "2026-06-17", "10:00:00", "Commerce"),
    ])
    got = C.parse_hearings_xml(xml)
    comms = [m.comm for m in got]
    assert comms == ["energy", "commerce"]   # zzz dropped (unmapped comm)
    assert got[0].committee == "Energy and Natural Resources"


def test_parse_hearings_xml_defensive_on_garbage():
    assert C.parse_hearings_xml("not xml <<<") == []
    assert C.parse_hearings_xml("") == []


def test_select_committee_candidates_today_desc_capped():
    meetings = [
        C.CommitteeMeeting("energy", "energy061726", "2026-06-17", "09:30:00", "Energy"),
        # tomorrow — must be filtered out by the today-only selection:
        C.CommitteeMeeting("commerce", "commerce061826", "2026-06-18", "10:00:00", "Commerce"),
        C.CommitteeMeeting("aging", "aging061726", "2026-06-17", "15:30:00", "Aging"),
    ]
    got = C.select_committee_candidates(meetings, "2026-06-17", max_n=5)
    assert [m.comm for m in got] == ["aging", "energy"]   # today only, latest-first


def _committee_resolver(xml, state):
    """state: {comm: 'live'|'ended'|'404'} — drives master/variant fetches."""
    import urllib.error

    def fetch(url):
        if url == C.SENATE_COMMITTEE_SCHEDULE_URL:
            return xml
        parts = url.split("/")
        if url.endswith("master.m3u8"):
            comm = parts[-3]
            if state.get(comm, "404") == "404":
                raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
            return _MASTER
        if url.endswith("index_1.m3u8"):
            comm = parts[-4]
            return _ENDED_VARIANT if state.get(comm) == "ended" else _LIVE_VARIANT
        raise OSError(f"unexpected url {url}")

    clock = lambda: datetime.datetime(2026, 6, 17, 12, 0, 0)  # noqa: E731
    return C.CSpanResolver(http_get_text=fetch, clock=clock)


def test_committee_resolve_picks_live_committee():
    xml = _hearings_xml([
        ("energy", "energy061726", "2026-06-17", "09:30:00", "Energy and Natural Resources"),
    ])
    out = _committee_resolver(xml, {"energy": "live"}).resolve(COMMITTEE_SRC)
    assert out.ok is True and out.is_live is True
    assert out.detail == "Energy and Natural Resources"
    assert out.hls_url.endswith("/2036797/energy/energy061726/master.m3u8")


def test_committee_resolve_all_ended_is_honest_offline():
    xml = _hearings_xml([
        ("energy", "energy061726", "2026-06-17", "09:30:00", "Energy"),
        ("aging", "aging061726", "2026-06-17", "15:30:00", "Aging"),
    ])
    out = _committee_resolver(xml, {"energy": "ended", "aging": "ended"}).resolve(COMMITTEE_SRC)
    assert out.ok is False and out.is_live is False
    assert out.error == "no_committee_live"   # NO frozen VOD ever shown live


def test_committee_resolve_skips_frozen_vod_for_live_one():
    # aging (15:30, latest → checked first) is ENDED; energy (09:30) is still LIVE.
    # The resolver must skip the frozen aging VOD and return the genuinely-live energy.
    xml = _hearings_xml([
        ("energy", "energy061726", "2026-06-17", "09:30:00", "Energy"),
        ("aging", "aging061726", "2026-06-17", "15:30:00", "Aging"),
    ])
    out = _committee_resolver(xml, {"energy": "live", "aging": "ended"}).resolve(COMMITTEE_SRC)
    assert out.ok is True and out.detail == "Energy"


def test_committee_resolve_skips_not_started_404():
    # commerce master 404s (not started); energy is live.
    xml = _hearings_xml([
        ("energy", "energy061726", "2026-06-17", "09:30:00", "Energy"),
        ("commerce", "commerce061726", "2026-06-17", "16:00:00", "Commerce"),
    ])
    out = _committee_resolver(xml, {"energy": "live", "commerce": "404"}).resolve(COMMITTEE_SRC)
    assert out.ok is True and out.detail == "Energy"


def test_committee_resolve_no_hearings_today():
    xml = _hearings_xml([
        ("energy", "energy061826", "2026-06-18", "09:30:00", "Energy"),    # tomorrow only
    ])
    out = _committee_resolver(xml, {"energy": "live"}).resolve(COMMITTEE_SRC)
    assert out.ok is False and out.error == "no_committee_live"


def test_committee_resolve_offline_safe_on_schedule_error():
    def boom(url):
        raise OSError("senate.gov unreachable")
    out = C.CSpanResolver(http_get_text=boom).resolve(COMMITTEE_SRC)
    assert out.ok is False
    assert out.error.startswith("resolve_error:")
