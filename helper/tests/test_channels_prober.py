"""Tests for the channel prober's master + variant validation."""

from __future__ import annotations

from mymts_helper.channels.prober import (
    _is_media_playlist,
    _looks_like_hls_manifest,
    _pick_variant_url,
)


MASTER_WITH_TWO_VARIANTS = b"""#EXTM3U
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
1080p/index.m3u8
"""

MEDIA_PLAYLIST_BODY = b"""#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:1
#EXTINF:6.0,
seg-001.ts
#EXTINF:6.0,
seg-002.ts
"""

MASTER_WITH_ABSOLUTE_VARIANT = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
https://cdn.test/x/720p/index.m3u8
"""


def test_looks_like_hls_manifest_accepts_extm3u_prefix() -> None:
    assert _looks_like_hls_manifest(b"#EXTM3U\n...")
    # UTF-8 BOM is stripped before the check.
    assert _looks_like_hls_manifest(b"\xef\xbb\xbf#EXTM3U\n...")


def test_looks_like_hls_manifest_rejects_html_or_blank() -> None:
    assert not _looks_like_hls_manifest(b"<html><body>404</body></html>")
    assert not _looks_like_hls_manifest(b"")
    assert not _looks_like_hls_manifest(b"#EXTINF:6.0,\nfoo.ts")  # no #EXTM3U at front


def test_is_media_playlist_recognises_extinf_only() -> None:
    assert _is_media_playlist(MEDIA_PLAYLIST_BODY)


def test_is_media_playlist_refuses_master_even_with_extinf_comment() -> None:
    # A master that happens to mention #EXTINF in a comment should NOT
    # be treated as a media playlist — we only call it a media playlist
    # if there are NO #EXT-X-STREAM-INF entries.
    body = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
720p/index.m3u8
# weird comment containing #EXTINF as text
"""
    assert not _is_media_playlist(body)


def test_pick_variant_url_returns_first_variant_after_stream_inf() -> None:
    url = _pick_variant_url(MASTER_WITH_TWO_VARIANTS,
                            "https://cdn.test/x/master.m3u8")
    assert url == "https://cdn.test/x/720p/index.m3u8"


def test_pick_variant_url_absolutizes_against_master() -> None:
    # Relative path -> joined to master URL.
    master = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
../variants/720p/index.m3u8
"""
    url = _pick_variant_url(master, "https://cdn.test/event/abc/master.m3u8")
    assert url == "https://cdn.test/event/variants/720p/index.m3u8"


def test_pick_variant_url_keeps_absolute_https_url() -> None:
    url = _pick_variant_url(MASTER_WITH_ABSOLUTE_VARIANT,
                            "https://cdn.test/master.m3u8")
    assert url == "https://cdn.test/x/720p/index.m3u8"


def test_pick_variant_url_rejects_absolute_http_variant() -> None:
    # If a master's variant is http (downgrade), we refuse it. The fetcher
    # would reject https-only at the boundary anyway, but the prober's
    # variant picker treats it as "no variant" so the channel gets
    # marked unavailable with a clear "variant_missing" reason rather
    # than a fetcher scheme_rejected error.
    body = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
http://insecure.test/v.m3u8
"""
    assert _pick_variant_url(body, "https://cdn.test/master.m3u8") is None


def test_pick_variant_url_returns_none_when_no_stream_inf() -> None:
    # A media playlist has no #EXT-X-STREAM-INF — pick_variant_url
    # returns None and the caller falls into the media-playlist branch.
    assert _pick_variant_url(MEDIA_PLAYLIST_BODY,
                             "https://cdn.test/index.m3u8") is None


def test_pick_variant_url_skips_intermediate_tag_lines() -> None:
    # Between #EXT-X-STREAM-INF and the URL line some manifests insert
    # other tags (e.g. #EXT-X-I-FRAME-STREAM-INF). The expecting_url
    # flag resets when we see another #-prefixed line, so the URL we
    # pick is the one IMMEDIATELY after a stream-inf — never an audio
    # rendition URL by accident.
    body = b"""#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
#EXT-X-I-FRAME-STREAM-INF:URI="iframe.m3u8"
720p/index.m3u8
"""
    # Because a #-prefixed line interrupts the expectation, the next
    # plain URL is NOT counted as the variant for the previous
    # stream-inf. Conservative: we return None and the channel is
    # marked unavailable rather than picking the wrong rendition.
    assert _pick_variant_url(body, "https://cdn.test/master.m3u8") is None
