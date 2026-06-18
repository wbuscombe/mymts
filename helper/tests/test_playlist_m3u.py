"""Render-side tests for the M3U playlist (pure function, no I/O)."""

from __future__ import annotations

from mymts_helper.channels.registry import ChannelRow
from mymts_helper.playlist.m3u import M3U_MEDIA_TYPE, render_m3u


def _ch(
    slug: str,
    label: str,
    *,
    status: str = "live",
    current_url: str | None = None,
    browser_playable: bool | None = True,
) -> ChannelRow:
    if current_url is None:
        current_url = f"https://cdn.test/{slug}/master.m3u8"
    return ChannelRow(
        id=1,
        slug=slug,
        label=label,
        kind="hls",
        source_url=f"https://x.test/{slug}.m3u8",
        enabled=True,
        category=None,
        current_url=current_url,
        status=status,
        last_check_at=None,
        last_success_at=None,
        last_error=None,
        error_count=0,
        browser_playable=browser_playable,
    )


def test_empty_list_renders_valid_empty_playlist() -> None:
    # Honest degradation: a down lineup is an empty playlist, not a fake one.
    assert render_m3u([]) == "#EXTM3U\n"


def test_single_live_channel_shape() -> None:
    out = render_m3u([_ch("cnn", "CNN", current_url="https://cdn.test/cnn/master.m3u8")])
    assert out.startswith("#EXTM3U\n")
    assert out.endswith("\n")
    assert '#EXTINF:-1 tvg-id="cnn" tvg-name="CNN",CNN' in out
    assert "https://cdn.test/cnn/master.m3u8" in out
    # exactly one entry → one EXTINF + the url line
    assert out.count("#EXTINF") == 1


def test_order_is_preserved() -> None:
    out = render_m3u([_ch("a", "A"), _ch("b", "B"), _ch("c", "C")])
    assert out.index("tvg-id=\"a\"") < out.index("tvg-id=\"b\"") < out.index("tvg-id=\"c\"")


def test_non_live_channel_is_skipped() -> None:
    # status != live must never appear, even if a current_url lingers.
    out = render_m3u(
        [_ch("cnn", "CNN", status="unavailable", current_url="https://cdn.test/cnn/x.m3u8")]
    )
    assert out == "#EXTM3U\n"
    assert "cnn" not in out


def test_channel_without_url_is_skipped() -> None:
    # A live channel with no resolved URL yet must not be listed.
    row = _ch("nourl", "No URL")
    live_no_url = ChannelRow(**{**row.__dict__, "current_url": None})
    assert render_m3u([live_no_url]) == "#EXTM3U\n"


def test_label_newline_cannot_inject_a_playlist_line() -> None:
    evil = 'Evil\n#EXTINF:-1,injected\nhttps://evil.test/x.m3u8'
    out = render_m3u([_ch("evil", evil, current_url="https://cdn.test/evil/m.m3u8")])
    # the injected newlines are collapsed → only ONE line is a real directive
    extinf_lines = [ln for ln in out.splitlines() if ln.startswith("#EXTINF")]
    assert len(extinf_lines) == 1
    # the upstream url is the only stream line; the injected one is neutralized
    assert "https://evil.test/x.m3u8" not in out.splitlines()
    assert "https://cdn.test/evil/m.m3u8" in out.splitlines()


def test_label_unicode_line_separators_cannot_inject() -> None:
    # VT, NEL, and U+2028 are line boundaries to str.splitlines() — the guard
    # must collapse them too, not just ASCII CR/LF.
    for sep in ("\x0b", "\x85", "\u2028"):
        evil = f"Evil{sep}#EXTINF:-1,injected{sep}https://evil.test/x.m3u8"
        out = render_m3u([_ch("e", evil, current_url="https://cdn.test/e/m.m3u8")])
        extinf_lines = [ln for ln in out.splitlines() if ln.startswith("#EXTINF")]
        assert len(extinf_lines) == 1, sep
        assert "https://evil.test/x.m3u8" not in out.splitlines()


def test_label_quote_cannot_break_the_attribute() -> None:
    out = render_m3u([_ch("q", 'Quote"Name', current_url="https://cdn.test/q/m.m3u8")])
    assert 'tvg-name="QuoteName"' in out


def test_url_with_control_char_is_skipped() -> None:
    out = render_m3u([_ch("bad", "Bad", current_url="https://cdn.test/bad\n/evil.m3u8")])
    assert out == "#EXTM3U\n"


def test_media_type_is_an_m3u_type() -> None:
    assert M3U_MEDIA_TYPE == "audio/x-mpegurl"
